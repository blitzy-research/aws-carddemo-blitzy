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
import java.time.Instant;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
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
 * <h2>Bounded memory, stated as a property rather than assumed</h2>
 *
 * <p>The subject of a failure is partly caller-supplied - an identifier a caller invented is still a
 * subject - so an unbounded map is a second denial-of-service channel wearing the first one's clothes. The
 * number of tracked subjects is capped. When the cap is reached, expired entries are swept first; if the
 * map is still full, a <em>new</em> subject is not admitted and the omission is counted, so the ceiling is
 * observable rather than silent. An already-tracked refusal is never dropped to make room, because that
 * would let a caller evict the record of its own abuse.
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
 * <p>Safe for concurrent use: the map is concurrent and every state change to one subject is applied inside
 * that map's own per-key atomic computation.
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

    /** Tag naming which of the two namespaces a counted event belongs to. */
    public static final String NAMESPACE_TAG = "namespace";

    /** Namespace of a subject that is one sign-on identifier. */
    public static final String IDENTITY_NAMESPACE = "identity";

    /** Namespace of a subject that is one caller address. */
    public static final String SOURCE_NAMESPACE = "source";

    /** Subject prefix keeping the two namespaces apart inside one map. */
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

    /** Where the four counters are registered. */
    private final MeterRegistry meterRegistry;

    /** Per-subject state, keyed by prefixed subject. */
    private final Map<String, AttemptRecord> records = new ConcurrentHashMap<>();

    /**
     * Slots taken in {@link #records}, held separately so the ceiling can be enforced atomically.
     *
     * <h2>Why a counter beside the map rather than the map's own size</h2>
     *
     * <p>{@code size()} answers a question about the past. Reading it, deciding there is room, and then
     * inserting is three steps, and the ceiling is only respected if nothing else inserts in between -
     * which is exactly what a burst of first-attempts from distinct subjects does. Each thread saw room
     * and each thread took it, so the hard bound this governor exists to provide could be exceeded by as
     * many subjects as there were threads. The bound matters because it is the only thing standing between
     * an enumeration sweep across generated identifiers and unbounded growth of this map.
     *
     * <p>A slot is therefore <em>reserved</em> before the per-key computation runs and released if the
     * computation turns out not to need it. Reservation is a compare-and-set against this counter, so two
     * threads cannot both take the last slot however they interleave. The counter is maintained to equal
     * the map's size exactly: every insertion consumes a reservation and every removal - the expiry sweep
     * and a successful sign-on alike - returns one.
     */
    private final AtomicInteger reservedSlots = new AtomicInteger();

    /**
     * @param enabled         whether the governor refuses anything, default {@code true}
     * @param maxFailures     failures inside the window that exhaust an allowance, default ten
     * @param failureWindow   span over which failures accumulate, default five minutes
     * @param refusalPeriod   how long an exhausted subject is refused, default one minute
     * @param trackedSubjects ceiling on tracked subjects, default ten thousand
     * @param clock           the clock windows are measured against; must not be {@code null}
     * @param meterRegistry   where the counters are registered; must not be {@code null}
     * @throws IllegalArgumentException if a figure is not positive, or a duration is zero or negative
     */
    public SignOnAttemptGovernor(
            @Value("${" + ENABLED_PROPERTY + ":true}") final boolean enabled,
            @Value("${" + MAX_FAILURES_PROPERTY + ":10}") final int maxFailures,
            @Value("${" + FAILURE_WINDOW_PROPERTY + ":PT5M}") final Duration failureWindow,
            @Value("${" + REFUSAL_PERIOD_PROPERTY + ":PT1M}") final Duration refusalPeriod,
            @Value("${" + TRACKED_SUBJECTS_PROPERTY + ":10000}") final int trackedSubjects,
            final Clock clock,
            final MeterRegistry meterRegistry) {
        this.enabled = enabled;
        this.maxFailures = requirePositive(maxFailures, MAX_FAILURES_PROPERTY);
        this.failureWindow = requirePositive(failureWindow, FAILURE_WINDOW_PROPERTY);
        this.refusalPeriod = requirePositive(refusalPeriod, REFUSAL_PERIOD_PROPERTY);
        this.trackedSubjects = requirePositive(trackedSubjects, TRACKED_SUBJECTS_PROPERTY);
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry must not be null");
        LOG.info("Sign-on attempt governor configured: enabled={} maxFailures={} failureWindow={}"
                        + " refusalPeriod={} trackedSubjects={}",
                this.enabled, this.maxFailures, this.failureWindow, this.refusalPeriod,
                this.trackedSubjects);
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
     * Clears both subjects of an attempt that authenticated.
     *
     * <p>An operator who mistypes a secret several times and then gets it right must not carry the
     * earlier failures towards a later refusal, so success releases the identity. It releases the source
     * too, because a source that produced a genuine sign-on is by that evidence not mid-sweep.
     *
     * @param userId    the identifier that authenticated
     * @param sourceKey the caller address the boundary attributed, possibly {@code null}
     */
    public void recordSuccess(final String userId, final String sourceKey) {
        if (!this.enabled) {
            return;
        }
        releaseIfPresent(identitySubject(userId));
        releaseIfPresent(sourceSubject(sourceKey));
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
     * <p>Read from the reservation counter rather than from the table's {@code size()}, for the same
     * reason admission is: the counter is the authority on occupancy and {@code size()} is an estimate.
     *
     * @param  identitySubject the prefixed identity subject
     * @param  sourceSubject   the prefixed source subject
     * @return {@code true} when the attempt must be refused because it could not be counted
     */
    private boolean saturatedFor(final String identitySubject, final String sourceSubject) {
        if (this.records.containsKey(identitySubject) || this.records.containsKey(sourceSubject)) {
            return false;
        }
        if (this.reservedSlots.get() < this.trackedSubjects) {
            return false;
        }
        sweepExpired();
        if (this.reservedSlots.get() < this.trackedSubjects) {
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
     * <p>Published so that the bounded-memory property can be asserted rather than assumed.
     *
     * @return the number of tracked subjects
     */
    public int trackedSubjectCount() {
        return this.records.size();
    }

    /**
     * Decides one namespace's answer for one subject, releasing an expired refusal on the way.
     *
     * @param  subject   the prefixed subject
     * @param  namespace the namespace, for the counter tag
     * @return {@code true} when this subject is currently refused
     */
    private boolean refusing(final String subject, final String namespace) {
        final AttemptRecord existing = this.records.get(subject);
        if (existing == null) {
            return false;
        }
        final Instant now = this.clock.instant();
        if (existing.refusedUntil() == null || !now.isBefore(existing.refusedUntil())) {
            return false;
        }
        count(REFUSED_METRIC, namespace,
                "attempts the governor refused before reading any credential");
        return true;
    }

    /**
     * Applies one failure to one subject atomically, engaging the refusal when the allowance is spent.
     *
     * @param subject   the prefixed subject
     * @param namespace the namespace, for the counter tags and the log record
     */
    private void record(final String subject, final String namespace) {
        final Instant now = this.clock.instant();
        final boolean[] engaged = {false};
        final boolean[] consumed = {false};
        // Decided BEFORE the computation, and this is the whole of the fix. A mapping function can see
        // one key; the ceiling is a property of the map. Deciding admission inside the computation meant
        // two first-attempts for different subjects each observed room and each took it, and it also meant
        // the sweep that frees room ran while a bin was locked - a mapping function is forbidden from
        // modifying the map it is computing in, and doing so risks not terminating rather than merely
        // being untidy. See docs/decision-log.md entry DL-308.
        final Reservation reservation = reserveIfNeeded(subject);
        final AttemptRecord updated = this.records.compute(subject, (key, current) -> {
            if (current == null) {
                if (reservation != Reservation.HELD) {
                    return null;
                }
                consumed[0] = true;
                return new AttemptRecord(1, now, null);
            }
            // A refusal already in force is left exactly as it is: extending it on every further
            // attempt would make the period unbounded, which is a caller-driven denial of service
            // against whoever legitimately owns the subject.
            if (current.refusedUntil() != null && now.isBefore(current.refusedUntil())) {
                return current;
            }
            // Outside the window the count restarts rather than accumulating for ever, so an operator
            // who mistypes once a month is never refused.
            final int failures = now.isBefore(current.windowStartedAt().plus(this.failureWindow))
                    ? current.failures() + 1
                    : 1;
            final Instant windowStart = failures == 1 ? now : current.windowStartedAt();
            if (failures >= this.maxFailures) {
                engaged[0] = true;
                return new AttemptRecord(0, now, now.plus(this.refusalPeriod));
            }
            return new AttemptRecord(failures, windowStart, null);
        });
        if (reservation == Reservation.HELD && !consumed[0]) {
            // The subject already existed, so the slot was not needed after all. Returning it is what
            // keeps the counter equal to the map's size; keeping it would leak the ceiling downwards until
            // no new subject could ever be tracked.
            this.reservedSlots.decrementAndGet();
        }
        if (updated == null) {
            count(UNTRACKED_METRIC, namespace,
                    "subjects the tracking ceiling declined to begin counting");
            if (reservation == Reservation.REFUSED) {
                LOG.warn("Sign-on governor is tracking its configured maximum of {} subjects, so one"
                                + " further subject is not being counted; raise {} if this recurs"
                                + " outside an attack",
                        this.trackedSubjects, TRACKED_SUBJECTS_PROPERTY);
            }
            return;
        }
        count(RECORDED_METRIC, namespace, "sign-on attempts recorded as failures by the governor");
        if (engaged[0]) {
            count(ENGAGED_METRIC, namespace, "transitions into a temporarily refusing state");
            // No identifier and no address: an operator needs to know that the protection engaged and
            // in which namespace, and a log record naming the subject would put an enumerated
            // identifier or a caller address into the log for the caller's benefit rather than ours.
            LOG.warn("Sign-on attempts temporarily refused: namespace={} allowance={} window={}"
                            + " refusalPeriod={}",
                    namespace, this.maxFailures, this.failureWindow, this.refusalPeriod);
        }
    }

    /** What the caller of {@link #reserveIfNeeded} holds when the per-key computation runs. */
    private enum Reservation {

        /** A slot was taken and must be consumed by an insertion or returned. */
        HELD,

        /** The subject was already tracked, so no slot was taken and none is owed. */
        NOT_NEEDED,

        /** The ceiling was reached even after a sweep, so no new subject may be inserted. */
        REFUSED
    }

    /**
     * Takes a tracking slot for a subject that does not yet have one.
     *
     * <p>The probe for an existing subject is a fast path and not a correctness guarantee: an entry can
     * be swept between the probe and the computation. When that happens the computation finds no mapping
     * while holding no reservation and declines, so this attempt goes uncounted for that subject. That is
     * a deliberate and immaterial loss - an entry is only ever swept once it is neither refusing nor
     * inside its window, which is exactly the state in which the next failure restarts the count at one
     * anyway - and it is the price of never inserting without a reservation, which is what keeps the
     * bound absolute.
     *
     * @param  subject the prefixed subject about to be recorded
     * @return what the caller holds
     */
    private Reservation reserveIfNeeded(final String subject) {
        if (this.records.containsKey(subject)) {
            return Reservation.NOT_NEEDED;
        }
        if (tryReserveSlot()) {
            return Reservation.HELD;
        }
        // Only here, outside every computation, is it safe to remove other mappings.
        sweepExpired();
        return tryReserveSlot() ? Reservation.HELD : Reservation.REFUSED;
    }

    /**
     * Takes one slot if the ceiling leaves room, atomically.
     *
     * @return {@code true} when a slot was taken and is now owed back or consumed
     */
    private boolean tryReserveSlot() {
        int taken = this.reservedSlots.get();
        while (taken < this.trackedSubjects) {
            if (this.reservedSlots.compareAndSet(taken, taken + 1)) {
                return true;
            }
            taken = this.reservedSlots.get();
        }
        return false;
    }

    /**
     * Removes one subject and returns its slot, if it was there to remove.
     *
     * <p>Conditional on the removal actually happening, because two callers can ask to release the same
     * subject and only one of them frees a slot. Decrementing unconditionally would drift the counter
     * below the map's size and hand out slots that do not exist.
     *
     * @param subject the prefixed subject to release
     */
    private void releaseIfPresent(final String subject) {
        if (this.records.remove(subject) != null) {
            this.reservedSlots.decrementAndGet();
        }
    }

    /**
     * Removes every entry that is neither refusing nor inside its window.
     *
     * <p>A refusing entry is never swept, whatever the pressure on the map: dropping one would let a
     * caller clear the record of its own abuse by generating subjects.
     */
    private void sweepExpired() {
        final Instant now = this.clock.instant();
        final Iterator<Map.Entry<String, AttemptRecord>> entries = this.records.entrySet().iterator();
        while (entries.hasNext()) {
            final Map.Entry<String, AttemptRecord> entry = entries.next();
            final AttemptRecord record = entry.getValue();
            final boolean stillRefusing =
                    record.refusedUntil() != null && now.isBefore(record.refusedUntil());
            final boolean insideWindow =
                    now.isBefore(record.windowStartedAt().plus(this.failureWindow));
            if (stillRefusing || insideWindow) {
                continue;
            }
            // Removed by key AND value rather than through the iterator, for two reasons. It reports
            // whether it actually removed anything, which is what makes the slot release exact when two
            // threads sweep at once; and it declines to remove an entry another thread has replaced since
            // this iteration observed it - which could otherwise discard a refusal that had just engaged,
            // letting a caller clear the record of its own abuse.
            if (this.records.remove(entry.getKey(), record)) {
                this.reservedSlots.decrementAndGet();
            }
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
        return IDENTITY_PREFIX + folded;
    }

    /**
     * @param  sourceKey the caller address the boundary attributed, possibly {@code null}
     * @return the source subject, with an explicit stand-in when no address was attributed
     */
    private static String sourceSubject(final String sourceKey) {
        final String resolved = sourceKey == null || sourceKey.isBlank()
                ? UNATTRIBUTED_SOURCE
                : sourceKey.strip();
        return SOURCE_PREFIX + resolved;
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
     * One subject's accumulated state.
     *
     * @param failures      failures inside the current window
     * @param windowStartedAt when the current window began
     * @param refusedUntil  when the refusal lifts, or {@code null} when the subject is not refused
     */
    private record AttemptRecord(int failures, Instant windowStartedAt, Instant refusedUntil) {
    }
}
