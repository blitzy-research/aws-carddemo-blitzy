/*
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License
 */
package com.carddemo.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Decides whether one more sign-on attempt is served or temporarily refused, per identity and per source.
 *
 * <h2>What this exists to stop</h2>
 *
 * <p>The sign-on transaction is the module's one anonymous surface, and reproducing the legacy screen
 * faithfully means reproducing two of its properties that are harmless on a 3270 terminal and are not
 * harmless on an open network. It reports an unknown identifier and a wrong secret with <em>different</em>
 * messages, so a caller can tell one from the other; and it verifies a presented secret with a deliberately
 * expensive digest, so each attempt costs real processor time. Neither property may be withdrawn - the
 * message texts are part of the external interface contract and the cost factor is the whole point of the
 * digest - so what has to change is how many attempts a caller gets. Without a limit the surface hands an
 * unauthenticated caller three things at once: an oracle that enumerates which identifiers exist, an
 * unbounded credential-stuffing channel against any identifier it has found, and a way to spend the
 * server's processor budget by presenting secrets that will fail.
 *
 * <h2>Two namespaces, because the two abuses look different</h2>
 *
 * <p>Failures are counted twice, under two independent subjects, because a single counter would miss one of
 * the two shapes. A stuffing run drives many secrets at <em>one</em> identifier and is caught by the
 * identity subject. An enumeration sweep drives one secret at <em>many</em> identifiers and never repeats an
 * identity, so the identity subject never accumulates - it is caught by the source subject. Either subject
 * reaching its limit refuses the attempt; both are released by the same passage of time.
 *
 * <h2>What a refusal is, and what it deliberately is not</h2>
 *
 * <p>A refused attempt performs no credential read and no digest verification, which is what makes the
 * refusal worth having: it is the work, not the answer, that the abuse was spending. What the <em>caller</em>
 * is told is decided by {@link AuthenticationService}, not here, and it is deliberately one of the screen's
 * existing outcomes rather than a new one. No new message text is minted, no new status code is introduced
 * and no header announces the limit, for two reasons that agree: the seven sign-on message literals are a
 * frozen external contract, and a distinct "you are being throttled" answer is itself an oracle - it tells
 * a sweeping caller exactly when to pause and from where to resume. Operators learn about it from the
 * counters and the log records below, which is where that information belongs.
 *
 * <h2>Bounded storage, stated as a property rather than assumed</h2>
 *
 * <p>The subject of a failure is partly caller-supplied - an identifier a caller invented is still a
 * subject - so an unbounded store is a second denial-of-service channel wearing the first one's clothes.
 * Two bounds answer that. The number of tracked subjects is capped: when the cap is reached, spent entries
 * are swept first, and if the store is still full a <em>new</em> subject is not admitted and the omission
 * is counted, so the ceiling is observable rather than silent. An already-tracked refusal is never dropped
 * to make room, because that would let a caller evict the record of its own abuse. The length of one
 * subject is capped too, at {@link SignOnAttemptLedger#MAX_SUBJECT_LENGTH}, so that a caller reaching this
 * service below the delivered eight-character contract cannot choose the size of a stored key.
 *
 * <h2>Where the count lives is a security question, so the store is named</h2>
 *
 * <p>This class holds no attempt state of its own. It decides policy - the allowance, the window, the
 * refusal period, the ceiling - and it counts and logs what happened; the state that policy is applied to
 * belongs to an injected {@link SignOnAttemptLedger}. The separation exists because an allowance is only an
 * allowance if it is counted once, and a count held in one process's memory is counted once <em>per
 * process</em>: two replicas behind one address grant a caller twice the attempts, ten grant ten times, and
 * every restart returns every allowance to full without anyone authenticating. None of that is visible from
 * inside a replica and all of it multiplies exactly the budget this class exists to bound.
 *
 * <p>So a deployment chooses a store whose scope matches its shape - {@link InMemorySignOnAttemptLedger}
 * for a single instance, {@link PostgresSignOnAttemptLedger} for every deployment that runs more than one -
 * and a production start-up refuses a store that is not deployment-wide. See
 * {@code config/SignOnThrottleConfig} for the selection and {@code docs/decision-log.md} entry DL-343.
 *
 * <h2>Every figure is configuration, and none is a service level</h2>
 *
 * <p>The four settings below are abuse-resistance thresholds, not latency, throughput or capacity targets:
 * the estate documents no service level and none is invented here. Each carries a default so that a fresh
 * clone runs with the protection on, and each is overridable per deployment because what counts as abusive
 * depends on how a deployment is reached.
 *
 * <p>Time is read from the injected {@link Clock} rather than from the platform, so a test can drive the
 * window and the release deterministically instead of sleeping.
 *
 * <p>The decision, including the parity exception that keeps the two refusal messages distinct rather
 * than flattening them, is recorded in {@code docs/decision-log.md} entry DL-268.
 *
 * <p>Safe for concurrent use: this class holds only immutable configuration, and every state change is
 * applied atomically by the injected ledger.
 *
 * @since 1.0.0
 */
@Service
public class SignOnAttemptGovernor {

    /** Property naming whether the governor refuses anything at all. */
    public static final String ENABLED_PROPERTY = "carddemo.security.sign-on.throttle-enabled";

    /** Property naming how many failures inside the window exhaust a subject's allowance. */
    public static final String MAX_FAILURES_PROPERTY = "carddemo.security.sign-on.max-failures";

    /** Property naming the span over which failures accumulate. */
    public static final String FAILURE_WINDOW_PROPERTY = "carddemo.security.sign-on.failure-window";

    /** Property naming how long a subject is refused once its allowance is exhausted. */
    public static final String REFUSAL_PERIOD_PROPERTY = "carddemo.security.sign-on.refusal-period";

    /** Property naming the ceiling on how many subjects are tracked at once. */
    public static final String TRACKED_SUBJECTS_PROPERTY = "carddemo.security.sign-on.tracked-subjects";

    /** Metric counting attempts the governor refused, tagged by which namespace refused them. */
    public static final String REFUSED_METRIC = "carddemo.signon.attempts.refused";

    /** Metric counting failures the governor recorded, tagged by namespace. */
    public static final String RECORDED_METRIC = "carddemo.signon.failures.recorded";

    /** Metric counting the transitions into a refusing state, tagged by namespace. */
    public static final String ENGAGED_METRIC = "carddemo.signon.throttle.engaged";

    /** Metric counting subjects the ceiling declined to begin tracking. */
    public static final String UNTRACKED_METRIC = "carddemo.signon.subjects.untracked";

    /**
     * Metric counting attempts refused because the tracking table was full and neither of the attempt's
     * subjects already had a record, so no failure could have been counted against them.
     */
    public static final String SATURATED_METRIC = "carddemo.signon.attempts.refused.saturated";

    /**
     * Counter of failures the ledger could not record because it could not be reached.
     *
     * <p>Its own counter rather than a share of {@link #UNTRACKED_METRIC}, because the two mean opposite
     * things about the protection: that one means the ceiling is doing its job, and this one means the
     * protection is not working at all. An operator seeing this rise is looking at a database fault, and
     * folding it into the ceiling counter would present that fault as a capacity decision.
     */
    public static final String UNAVAILABLE_METRIC = "carddemo.signon.ledger.unavailable";

    /** Tag naming which of the two namespaces a counted event belongs to. */
    public static final String NAMESPACE_TAG = "namespace";

    /** Namespace of a subject that is one sign-on identifier. */
    public static final String IDENTITY_NAMESPACE = "identity";

    /** Namespace of a subject that is one caller address. */
    public static final String SOURCE_NAMESPACE = "source";

    /** Subject prefix keeping the two namespaces apart inside one ledger. */
    private static final String IDENTITY_PREFIX = "i:";

    /** Subject prefix for the source namespace. */
    private static final String SOURCE_PREFIX = "s:";

    /** Subject recorded when a caller address was not attributed by the boundary. */
    private static final String UNATTRIBUTED_SOURCE = "unattributed";

    /** Diagnostic channel. Never carries an identifier, an address or a credential. */
    private static final Logger LOG = LoggerFactory.getLogger(SignOnAttemptGovernor.class);

    /** Whether the governor refuses anything. */
    private final boolean enabled;

    /** Failures inside the window that exhaust a subject's allowance. */
    private final int maxFailures;

    /** Span over which failures accumulate before the count restarts. */
    private final Duration failureWindow;

    /** How long an exhausted subject is refused. */
    private final Duration refusalPeriod;

    /** Ceiling on tracked subjects. */
    private final int trackedSubjects;

    /** The clock every window and deadline is measured against. */
    private final Clock clock;

    /** Where the counters are registered. */
    private final MeterRegistry meterRegistry;

    /** Where the attempt state lives, and the only thing that mutates it. */
    private final SignOnAttemptLedger ledger;

    /**
     * The four thresholds as one value, handed to the ledger on every call.
     *
     * <p>Passed rather than injected into the store, so the figures are read from configuration in exactly
     * one place - this class's constructor - and a store cannot hold a stale or a second opinion of what
     * the allowance is.
     */
    private final SignOnAttemptLedger.Policy policy;

    /**
     * @param enabled         whether the governor refuses anything, default {@code true}
     * @param maxFailures     failures inside the window that exhaust an allowance, default ten
     * @param failureWindow   span over which failures accumulate, default five minutes
     * @param refusalPeriod   how long an exhausted subject is refused, default one minute
     * @param trackedSubjects ceiling on tracked subjects, default ten thousand
     * @param clock           the clock windows are measured against; must not be {@code null}
     * @param meterRegistry   where the counters are registered; must not be {@code null}
     * @param ledger          where the attempt state lives; must not be {@code null}
     * @throws IllegalArgumentException if a figure is not positive, or a duration is zero or negative
     */
    public SignOnAttemptGovernor(
            @Value("${" + ENABLED_PROPERTY + ":true}") final boolean enabled,
            @Value("${" + MAX_FAILURES_PROPERTY + ":10}") final int maxFailures,
            @Value("${" + FAILURE_WINDOW_PROPERTY + ":PT5M}") final Duration failureWindow,
            @Value("${" + REFUSAL_PERIOD_PROPERTY + ":PT1M}") final Duration refusalPeriod,
            @Value("${" + TRACKED_SUBJECTS_PROPERTY + ":10000}") final int trackedSubjects,
            final Clock clock,
            final MeterRegistry meterRegistry,
            final SignOnAttemptLedger ledger) {
        this.enabled = enabled;
        this.maxFailures = requirePositive(maxFailures, MAX_FAILURES_PROPERTY);
        this.failureWindow = requirePositive(failureWindow, FAILURE_WINDOW_PROPERTY);
        this.refusalPeriod = requirePositive(refusalPeriod, REFUSAL_PERIOD_PROPERTY);
        this.trackedSubjects = requirePositive(trackedSubjects, TRACKED_SUBJECTS_PROPERTY);
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry must not be null");
        this.ledger = Objects.requireNonNull(ledger, "ledger must not be null");
        this.policy = new SignOnAttemptLedger.Policy(this.maxFailures, this.failureWindow,
                this.refusalPeriod, this.trackedSubjects);
        LOG.info("Sign-on attempt governor configured: enabled={} maxFailures={} failureWindow={}"
                        + " refusalPeriod={} trackedSubjects={} stateScope={}",
                this.enabled, this.maxFailures, this.failureWindow, this.refusalPeriod,
                this.trackedSubjects,
                this.ledger.isDeploymentWide() ? "deployment-wide" : "this instance only");
    }

    /**
     * Reports whether this attempt is refused before any credential work is done.
     *
     * <p>Both namespaces are consulted and either one refuses. The identity is folded to upper case for
     * the same reason the transaction folds it: two spellings of one identifier are one subject, and a
     * caller alternating the case of a name must not get two allowances.
     *
     * @param  userId    the submitted identifier, possibly {@code null} or blank
     * @param  sourceKey the caller address the boundary attributed, possibly {@code null}
     * @return {@code true} when the attempt must be refused without verifying anything
     */
    public boolean isRefusing(final String userId, final String sourceKey) {
        if (!this.enabled) {
            return false;
        }
        // Fail closed on saturation, before either namespace is consulted. A subject with no record
        // cannot have a failure counted against it while the table is full, so admitting the attempt
        // would be admitting an unlimited number of them - which is exactly the state the allowance
        // exists to prevent, reached by generating enough distinct subjects to fill the table. Before
        // this check the governor turned itself off at capacity: the attempt was neither counted nor
        // refused, and guessing continued at full rate against the credential store and the hasher.
        //
        // The state is not sticky. It is re-derived on every call from the table's own occupancy, after
        // a sweep, so it lifts of its own accord as windows expire and refusals end.
        if (saturatedFor(identitySubject(userId), sourceSubject(sourceKey))) {
            return true;
        }
        if (refusing(identitySubject(userId), IDENTITY_NAMESPACE)) {
            return true;
        }
        return refusing(sourceSubject(sourceKey), SOURCE_NAMESPACE);
    }

    /**
     * Records one attempt that did not authenticate, in both namespaces.
     *
     * <p>Called for every non-admitting outcome of a verification, including the not-found outcome:
     * counting only the wrong-secret outcome would leave the enumeration sweep - which never produces a
     * wrong-secret outcome at all, only not-found ones - entirely uncounted.
     *
     * @param userId    the submitted identifier, possibly {@code null} or blank
     * @param sourceKey the caller address the boundary attributed, possibly {@code null}
     */
    public void recordFailure(final String userId, final String sourceKey) {
        if (!this.enabled) {
            return;
        }
        record(identitySubject(userId), IDENTITY_NAMESPACE);
        record(sourceSubject(sourceKey), SOURCE_NAMESPACE);
    }

    /**
     * Clears the <em>authenticated identity</em> of an attempt that authenticated, and nothing else.
     *
     * <p>An operator who mistypes a secret several times and then gets it right must not carry the
     * earlier failures towards a later refusal, so success releases that identity. That is the whole of
     * what a successful credential establishes, and it is therefore the whole of what is released.
     *
     * <h2>Why the source subject is deliberately NOT released</h2>
     *
     * <p>This method used to release the source as well, on the reasoning that a source which produced a
     * genuine sign-on is by that evidence not mid-sweep. The reasoning does not hold, and the gap it left
     * is the one the source namespace exists to close. An enumeration sweep is driven from <em>somewhere</em>,
     * and the caller driving it needs only one credential it is entitled to - its own, a colleague's, a
     * shared low-privilege operator account, an identity it created through the very surface it is
     * enumerating - to clear the source-wide failure record on demand. The sweep then resumed at full
     * rate: exhaust the source allowance, sign in once legitimately, resume. The source counter existed,
     * counted correctly, and could be reset at will by the party it was counting.
     *
     * <p>So a success now says only "this identity's own history is spent". The source's history is
     * released by <em>time</em> and by nothing else: an entry outside its failure window restarts its
     * count on the next failure, an engaged refusal lifts when its period expires, and the capacity sweep
     * removes an entry that is neither refusing nor inside its window. None of those is reachable by
     * presenting a credential, which is what makes the source namespace a bound on the caller rather than
     * a formality.
     *
     * <p>The cost of this is bounded and is stated rather than hidden. An operator behind an address that
     * has genuinely exhausted its allowance - a shared office egress address, several operators mistyping
     * inside one window - waits out the refusal period even after a correct credential. The allowance and
     * the period are both configuration for that reason, and the alternative is a bypass any caller can
     * take.
     *
     * <p>Recorded in {@code docs/decision-log.md} entry DL-342.
     *
     * @param userId the identifier that authenticated
     */
    public void recordSuccess(final String userId) {
        if (!this.enabled) {
            return;
        }
        this.ledger.release(identitySubject(userId));
    }

    /**
     * Reports whether the tracking table is full and neither of an attempt's two subjects already has a
     * record, which is the state in which counting a failure is impossible.
     *
     * <p>An attempt whose identity or source is already tracked is unaffected however full the table is:
     * its record exists, its failures are still counted and its allowance still applies. Only an attempt
     * that would need a new slot, at a moment when no slot can be produced, is refused. That is what
     * keeps the fail-closed answer proportional - a subject-flooding attack locks out the operators who
     * had not attempted a sign-on before it began, rather than every operator.
     *
     * <p>The question is asked of the ledger rather than answered here, because occupancy is a property of
     * the stored state and only the store can decide it without racing its own writers.
     *
     * @param  identitySubject the prefixed identity subject
     * @param  sourceSubject   the prefixed source subject
     * @return {@code true} when the attempt must be refused because it could not be counted
     */
    private boolean saturatedFor(final String identitySubject, final String sourceSubject) {
        if (this.ledger.canBeginTracking(identitySubject, sourceSubject, this.clock.instant(),
                this.policy)) {
            return false;
        }
        count(SATURATED_METRIC, SOURCE_NAMESPACE,
                "attempts refused because the tracking table could not begin counting the subject");
        // No identifier and no address, for the same reason the refusal record carries neither.
        LOG.warn("Sign-on refused because the governor's tracking table is at its configured maximum"
                        + " of {} subjects and this attempt's subjects are not among them; raise {} if"
                        + " this recurs outside an attack",
                this.trackedSubjects, TRACKED_SUBJECTS_PROPERTY);
        return true;
    }

    /**
     * Reports whether the governor is configured to refuse anything.
     *
     * @return {@code true} when enabled
     */
    public boolean isEnabled() {
        return this.enabled;
    }

    /**
     * Reports how many failures inside the window exhaust one subject's allowance.
     *
     * @return the configured allowance
     */
    public int getMaxFailures() {
        return this.maxFailures;
    }

    /**
     * Reports how many subjects are currently tracked.
     *
     * <p>Published so that the bounded-storage property can be asserted rather than assumed.
     *
     * @return the number of tracked subjects
     */
    public int trackedSubjectCount() {
        return this.ledger.trackedSubjectCount();
    }

    /**
     * Reports whether the attempt state this governor applies is shared by every instance.
     *
     * <p>Published because it is the difference the security posture turns on, and because it is what a
     * production start-up refuses when it is {@code false}: an allowance held per process is multiplied by
     * the number of processes and reset by every restart.
     *
     * @return {@code true} when the injected ledger's state is shared and durable across instances
     */
    public boolean isDeploymentWideState() {
        return this.ledger.isDeploymentWide();
    }

    /**
     * Decides one namespace's answer for one subject, releasing an expired refusal on the way.
     *
     * @param  subject   the prefixed subject
     * @param  namespace the namespace, for the counter tag
     * @return {@code true} when this subject is currently refused
     */
    private boolean refusing(final String subject, final String namespace) {
        if (!this.ledger.isRefusing(subject, this.clock.instant())) {
            return false;
        }
        count(REFUSED_METRIC, namespace,
                "attempts the governor refused before reading any credential");
        return true;
    }

    /**
     * Applies one failure to one subject, and turns what the ledger did into counters and log records.
     *
     * <p>The transition itself belongs to {@link SignOnAttemptLedger}, which owns both the state and the
     * pure function that advances it. What is left here is the observability: which counter to increment,
     * and whether an operator needs to be told something. Five outcomes, and each says something different
     * to an operator:
     *
     * <ul>
     *   <li>a counted failure and a failure that engaged a refusal are both recorded, and only the second
     *       is announced;</li>
     *   <li>a failure against a subject already refused is recorded and not announced, because the
     *       refusal it belongs to was announced when it engaged;</li>
     *   <li>the ceiling declining a new subject is announced, because it means the protection is at its
     *       limit, while the immaterial race that looks like it is not;</li>
     *   <li>an unreachable ledger gets its own counter, because it means the protection is not working -
     *       the ledger has already logged that fault, so nothing is logged twice here.</li>
     * </ul>
     *
     * @param subject   the prefixed subject
     * @param namespace the namespace, for the counter tags and the log record
     */
    private void record(final String subject, final String namespace) {
        final SignOnAttemptLedger.FailureOutcome outcome =
                this.ledger.recordFailure(subject, this.clock.instant(), this.policy);
        switch (outcome) {
            case COUNTED, ALREADY_REFUSING -> count(RECORDED_METRIC, namespace,
                    "sign-on attempts recorded as failures by the governor");
            case REFUSAL_ENGAGED -> {
                count(RECORDED_METRIC, namespace,
                        "sign-on attempts recorded as failures by the governor");
                count(ENGAGED_METRIC, namespace, "transitions into a temporarily refusing state");
                // No identifier and no address: an operator needs to know that the protection engaged and
                // in which namespace, and a log record naming the subject would put an enumerated
                // identifier or a caller address into the log for the caller's benefit rather than ours.
                LOG.warn("Sign-on attempts temporarily refused: namespace={} allowance={} window={}"
                                + " refusalPeriod={}",
                        namespace, this.maxFailures, this.failureWindow, this.refusalPeriod);
            }
            case DECLINED_AT_CEILING -> {
                count(UNTRACKED_METRIC, namespace,
                        "subjects the tracking ceiling declined to begin counting");
                LOG.warn("Sign-on governor is tracking its configured maximum of {} subjects, so one"
                                + " further subject is not being counted; raise {} if this recurs"
                                + " outside an attack",
                        this.trackedSubjects, TRACKED_SUBJECTS_PROPERTY);
            }
            case DECLINED_BY_RACE -> count(UNTRACKED_METRIC, namespace,
                    "subjects the tracking ceiling declined to begin counting");
            case STORE_UNAVAILABLE -> count(UNAVAILABLE_METRIC, namespace,
                    "failures the attempt ledger could not record because it could not be reached");
        }
    }

    /**
     * Increments one counter, registering it on first use.
     *
     * @param name        metric name
     * @param namespace   namespace tag value
     * @param description what the counter counts
     */
    private void count(final String name, final String namespace, final String description) {
        Counter.builder(name)
                .description(description)
                .tag(NAMESPACE_TAG, namespace)
                .register(this.meterRegistry)
                .increment();
    }

    /**
     * @param  userId the submitted identifier, possibly {@code null} or blank
     * @return the identity subject, folded so two spellings are one subject
     */
    private static String identitySubject(final String userId) {
        final String folded = userId == null ? "" : userId.strip().toUpperCase(Locale.ROOT);
        return bounded(IDENTITY_PREFIX + folded);
    }

    /**
     * @param  sourceKey the caller address the boundary attributed, possibly {@code null}
     * @return the source subject, with an explicit stand-in when no address was attributed
     */
    private static String sourceSubject(final String sourceKey) {
        final String resolved = sourceKey == null || sourceKey.isBlank()
                ? UNATTRIBUTED_SOURCE
                : sourceKey.strip();
        return bounded(SOURCE_PREFIX + resolved);
    }

    /**
     * @param  value    the configured figure
     * @param  property the property that named it, for the refusal text
     * @return the figure
     * @throws IllegalArgumentException when it is not positive
     */
    private static int requirePositive(final int value, final String property) {
        if (value <= 0) {
            throw new IllegalArgumentException(property + " must be a positive number of attempts");
        }
        return value;
    }

    /**
     * @param  value    the configured duration
     * @param  property the property that named it, for the refusal text
     * @return the duration
     * @throws IllegalArgumentException when it is {@code null}, zero or negative
     */
    private static Duration requirePositive(final Duration value, final String property) {
        Objects.requireNonNull(value, property + " must name a duration");
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(property + " must be a positive duration");
        }
        return value;
    }

    /**
     * Cuts a subject to the greatest length a ledger will store.
     *
     * <p>An ordinary subject is nowhere near the bound - the delivered sign-on contract stops an identifier
     * at eight characters, so an ordinary identity subject is ten - and the bound exists for a caller that
     * reaches this service below that contract. Without it a caller chooses the size of a stored key, which
     * in a durable ledger is a storage lever and an index-size one besides.
     *
     * <p>Two over-long subjects that coincide once cut share one allowance. That can only happen between
     * values no delivered identity could hold, and it makes the throttle stricter rather than weaker, so it
     * is accepted rather than worked around.
     *
     * <p>The cut steps back off a trailing high surrogate. Splitting a surrogate pair leaves a lone
     * surrogate, which is not a character any encoding can represent - a durable store would reject the
     * write outright, and a rejected write is an uncounted failure.
     *
     * @param  subject the prefixed subject
     * @return the subject, cut to {@link SignOnAttemptLedger#MAX_SUBJECT_LENGTH} if it was longer
     */
    private static String bounded(final String subject) {
        if (subject.length() <= SignOnAttemptLedger.MAX_SUBJECT_LENGTH) {
            return subject;
        }
        final int limit = SignOnAttemptLedger.MAX_SUBJECT_LENGTH;
        final int end = Character.isHighSurrogate(subject.charAt(limit - 1)) ? limit - 1 : limit;
        return subject.substring(0, end);
    }
}
