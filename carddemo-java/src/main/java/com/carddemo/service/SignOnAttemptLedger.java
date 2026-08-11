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

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Where one sign-on subject's accumulated failures live, and the state machine that advances them.
 *
 * <h2>Why the store is an abstraction at all</h2>
 *
 * <p>{@link SignOnAttemptGovernor} decides <em>policy</em> - how many failures exhaust an allowance, over
 * what window, for how long a refusal holds, how many subjects may be tracked - and it counts and logs
 * what happened. This interface is the <em>state</em> that policy is applied to, and the two were one
 * class until the state's scope became a security question.
 *
 * <p>The scope is the question. An allowance held in one process's memory is an allowance <em>per
 * process</em>: two replicas behind one address give a caller twice the attempts, ten give ten times, and
 * a restart - a deployment, a crash, an autoscaler removing an instance - returns every allowance to
 * full. None of that is visible from inside a replica, and all of it multiplies exactly the budget the
 * governor exists to bound. So the store is named, and a deployment chooses one whose scope matches its
 * shape: {@link InMemorySignOnAttemptLedger} for a single instance, {@link PostgresSignOnAttemptLedger}
 * for every deployment that runs more than one.
 *
 * <h2>The state machine lives here, not in either implementation</h2>
 *
 * <p>{@link #nextAfterFailure(Entry, Instant, Policy)} is a pure function, and it is the only place the
 * transition is written down. Two stores that each implemented it would be two answers to one question,
 * and the interesting cases - a refusal already in force, a window that has lapsed, the failure that
 * exhausts an allowance - are exactly the cases in which two implementations drift. Each store is
 * therefore responsible for reading an entry, applying this function, and writing the result back
 * atomically; it is responsible for nothing about what the result should be.
 *
 * <h2>What every implementation must guarantee</h2>
 *
 * <ul>
 *   <li><strong>A refusal in force is not extended.</strong> Further failures against a refused subject
 *       leave the deadline where it is, or a caller could hold someone else's identifier refused
 *       indefinitely.</li>
 *   <li><strong>A window that has lapsed restarts the count.</strong> An operator who mistypes once a
 *       month never accumulates towards a refusal.</li>
 *   <li><strong>The tracked-subject count is bounded.</strong> A subject is partly caller-supplied, so an
 *       unbounded store is a second denial-of-service channel. Reaching the bound is reported rather than
 *       silent, and a <em>refusing</em> entry is never removed to make room - that would let a caller
 *       clear the record of its own abuse by generating subjects.</li>
 *   <li><strong>One subject's transition is atomic.</strong> Two concurrent failures for one subject must
 *       count as two, and neither may overwrite the other's write.</li>
 *   <li><strong>Nothing is logged from the store about a subject.</strong> A subject is an operator
 *       identifier or a caller address; the governor's own diagnostics deliberately name neither, and a
 *       store that named one would reintroduce the disclosure underneath it.</li>
 * </ul>
 *
 * <p>See {@code docs/decision-log.md} entries DL-268 for the allowance itself, DL-342 for the
 * identity-only release, and DL-343 for the scope of this store.
 *
 * <p>Provenance: legacy estate checkout SHA {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream
 * release stamp {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. Nothing here has a legacy
 * antecedent: the estate's sign-on transaction compared a credential and counted nothing.
 *
 * @since 1.0.0
 */
public interface SignOnAttemptLedger {

    /**
     * Greatest number of characters a subject may occupy in a ledger.
     *
     * <p>The governor derives a subject partly from caller-supplied text - an identifier a caller invented
     * is still a subject - and the delivered sign-on contract bounds an identifier at eight characters, so
     * an ordinary subject is far shorter than this. The bound exists for a caller that reaches the service
     * below that contract: without it a caller chooses the size of a stored key, which is a storage lever
     * in a durable ledger and an index-size one besides. Subjects longer than this are cut to it, and two
     * that then coincide share one allowance - which can only happen between identifiers no delivered
     * identity could hold, and which makes the throttle stricter rather than weaker.
     */
    int MAX_SUBJECT_LENGTH = 64;

    /**
     * Reports whether a subject is currently inside a refusal period.
     *
     * <p>Read-only, and it must not advance any state: it is called before a credential is read, on every
     * attempt, including attempts that will be admitted.
     *
     * @param  subject the prefixed subject; must not be {@code null}
     * @param  now     the instant to judge the deadline against; must not be {@code null}
     * @return {@code true} when a refusal is recorded for this subject and has not yet expired
     */
    boolean isRefusing(String subject, Instant now);

    /**
     * Applies one failure to one subject, atomically.
     *
     * @param  subject the prefixed subject; must not be {@code null}
     * @param  now     the instant the failure occurred; must not be {@code null}
     * @param  policy  the thresholds to apply; must not be {@code null}
     * @return what the store did, which the governor turns into counters and log records
     */
    FailureOutcome recordFailure(String subject, Instant now, Policy policy);

    /**
     * Removes one subject's accumulated state, if it has any.
     *
     * <p>Called for the authenticated identity of an admitted sign-on and for nothing else - see
     * {@link SignOnAttemptGovernor#recordSuccess(String)} for why a source is never released this way.
     *
     * @param subject the prefixed subject; must not be {@code null}
     */
    void release(String subject);

    /**
     * Reports how many subjects the store currently holds.
     *
     * <p>Published so that the bounded-store property can be asserted rather than assumed.
     *
     * @return the number of tracked subjects, which is never above the configured ceiling
     */
    int trackedSubjectCount();

    /**
     * Reports whether an attempt whose two subjects are both untracked could be counted right now.
     *
     * <p>The question exists because of what a {@code false} answer means: with the store at its ceiling
     * and neither of an attempt's subjects in it, a failure <em>cannot</em> be counted against that
     * attempt, so admitting it would admit an unlimited number of them. The governor's answer to
     * {@code false} is therefore to refuse, which is the fail-closed direction. An attempt whose identity
     * or source is already tracked is unaffected however full the store is, which is what keeps the
     * refusal proportional.
     *
     * @param  identitySubject the prefixed identity subject; must not be {@code null}
     * @param  sourceSubject   the prefixed source subject; must not be {@code null}
     * @param  now             the instant to judge expiry against; must not be {@code null}
     * @param  policy          the thresholds, whose ceiling is the bound being tested; must not be
     *                         {@code null}
     * @return {@code true} when the attempt can be counted, either because a subject is already tracked or
     *         because the store has room for a new one
     */
    boolean canBeginTracking(String identitySubject, String sourceSubject, Instant now, Policy policy);

    /**
     * Reports whether this store's state is shared by every instance of the deployment.
     *
     * <p>Published because it is the difference the security posture turns on, and because a production
     * start-up refuses a store that answers {@code false}: an allowance held per process is multiplied by
     * the number of processes and reset by any restart. It is not a description of a technology - a store
     * is deployment-wide because its state outlives one process and is seen by every other, not because
     * of what it is built on.
     *
     * @return {@code true} when the state is shared and durable across instances and restarts
     */
    boolean isDeploymentWide();

    /**
     * Advances one subject's state by one failure.
     *
     * <p>The whole transition, in the order the conditions must be tested:
     *
     * <ol>
     *   <li><strong>No entry yet.</strong> The failure is the first of a new window.</li>
     *   <li><strong>A refusal is in force.</strong> The entry is returned unchanged. Extending it on every
     *       further attempt would make the period unbounded, which is a caller-driven denial of service
     *       against whoever legitimately owns the subject.</li>
     *   <li><strong>Inside the window.</strong> The count advances. Reaching the allowance engages the
     *       refusal and resets the count, so the period that follows is measured once rather than
     *       re-measured per attempt.</li>
     *   <li><strong>Outside the window.</strong> The count restarts at one and the window restarts now.
     *       A single attempt cannot engage a refusal unless the allowance is one.</li>
     * </ol>
     *
     * @param  current the subject's present state, or {@code null} when it has none
     * @param  now     the instant of this failure; must not be {@code null}
     * @param  policy  the thresholds to apply; must not be {@code null}
     * @return the state to store and what happened, never {@code null}
     * @throws NullPointerException if {@code now} or {@code policy} is {@code null}
     */
    static Transition nextAfterFailure(final Entry current, final Instant now, final Policy policy) {
        Objects.requireNonNull(now, "now must not be null");
        Objects.requireNonNull(policy, "policy must not be null");
        if (current == null) {
            return engagedOrCounted(1, now, now, policy);
        }
        if (current.isRefusing(now)) {
            return new Transition(current, FailureOutcome.ALREADY_REFUSING);
        }
        if (now.isBefore(current.windowStartedAt().plus(policy.failureWindow()))) {
            return engagedOrCounted(current.failures() + 1, current.windowStartedAt(), now, policy);
        }
        return engagedOrCounted(1, now, now, policy);
    }

    /**
     * Decides between a counted failure and the failure that engages the refusal.
     *
     * @param  failures    how many failures the window now holds
     * @param  windowStart when the window the count belongs to began
     * @param  now         the instant of this failure
     * @param  policy      the thresholds to apply
     * @return the state to store and what happened
     */
    private static Transition engagedOrCounted(final int failures, final Instant windowStart,
            final Instant now, final Policy policy) {
        if (failures >= policy.maxFailures()) {
            return new Transition(new Entry(0, now, now.plus(policy.refusalPeriod())),
                    FailureOutcome.REFUSAL_ENGAGED);
        }
        return new Transition(new Entry(failures, windowStart, null), FailureOutcome.COUNTED);
    }

    /** What a store did with one failure. */
    enum FailureOutcome {

        /** The failure was counted and the subject is not refused. */
        COUNTED,

        /** The failure exhausted the allowance, so a refusal period now runs. */
        REFUSAL_ENGAGED,

        /**
         * The subject was already inside a refusal period, so the failure changed nothing.
         *
         * <p>Counted as recorded, because it <em>was</em> a failure the store saw, and not counted as a
         * fresh engagement, because the refusal it belongs to was engaged once already.
         */
        ALREADY_REFUSING,

        /**
         * The ceiling declined to begin tracking this subject, so the failure could not be counted.
         *
         * <p>Reported rather than silent: it is the state in which the protection is at its limit, and an
         * operator needs to know the limit was reached.
         */
        DECLINED_AT_CEILING,

        /**
         * The subject's entry vanished between this store's probe and its write, so this one failure went
         * uncounted for it.
         *
         * <p>A deliberate and immaterial loss rather than a defect: an entry is only ever removed once it
         * is neither refusing nor inside its window, which is exactly the state in which the next failure
         * restarts the count at one anyway. Distinguished from {@link #DECLINED_AT_CEILING} because that
         * one means the protection is saturated and this one means nothing at all.
         */
        DECLINED_BY_RACE,

        /**
         * The store could not be reached, so this failure was neither counted nor refused.
         *
         * <p>Its own outcome rather than one of the two declines above, because it means something neither
         * of them means: the protection is not working. It is reported as an operational alert and it
         * never refuses the attempt.
         *
         * <p>Not refusing is the right direction here, and not merely the convenient one. The only store
         * that can be unreachable is the shared one, and it is the same database the credential store
         * lives in - so while it is unreachable no credential can be verified either, and every attempt
         * already ends in the legacy inability-to-verify reply without a credential being read. Counting
         * nothing during that interval therefore withholds no protection that was available: there is no
         * admission to be had. Refusing instead would convert one database fault into a deployment-wide
         * sign-on outage that outlasts the fault by the whole refusal period, and would let anyone able to
         * disturb the database lock every operator out.
         */
        STORE_UNAVAILABLE
    }

    /**
     * One subject's accumulated state.
     *
     * @param failures        failures inside the current window
     * @param windowStartedAt when the current window began; never {@code null}
     * @param refusedUntil    when a refusal lifts, or {@code null} when the subject is not refused
     */
    record Entry(int failures, Instant windowStartedAt, Instant refusedUntil) {

        /**
         * @param failures        failures inside the current window; must not be negative
         * @param windowStartedAt when the current window began; must not be {@code null}
         * @param refusedUntil    when a refusal lifts, or {@code null}
         * @throws IllegalArgumentException if {@code failures} is negative
         * @throws NullPointerException     if {@code windowStartedAt} is {@code null}
         */
        public Entry {
            Objects.requireNonNull(windowStartedAt, "windowStartedAt must not be null");
            if (failures < 0) {
                throw new IllegalArgumentException("failures must not be negative");
            }
        }

        /**
         * @param  now the instant to judge the deadline against; must not be {@code null}
         * @return {@code true} when a refusal is recorded and has not yet expired
         */
        public boolean isRefusing(final Instant now) {
            Objects.requireNonNull(now, "now must not be null");
            return this.refusedUntil != null && now.isBefore(this.refusedUntil);
        }

        /**
         * Reports whether this entry may be removed to make room for another subject.
         *
         * <p>Only an entry that is neither refusing nor inside its window may go. A refusing entry is
         * never removed, whatever the pressure on the store: dropping one would let a caller clear the
         * record of its own abuse by generating subjects.
         *
         * @param  now    the instant to judge against; must not be {@code null}
         * @param  policy the thresholds whose window decides; must not be {@code null}
         * @return {@code true} when the entry is spent and may be swept
         */
        public boolean isSweepable(final Instant now, final Policy policy) {
            Objects.requireNonNull(policy, "policy must not be null");
            return !isRefusing(now)
                    && !now.isBefore(this.windowStartedAt.plus(policy.failureWindow()));
        }
    }

    /**
     * The thresholds a store applies, supplied by the governor on every call.
     *
     * <p>Passed rather than injected, and that is deliberate: the four figures are read from configuration
     * in exactly one place - the governor's constructor - so a store cannot hold a stale or a second
     * opinion of what the allowance is.
     *
     * @param maxFailures     failures inside the window that exhaust an allowance
     * @param failureWindow   span over which failures accumulate before the count restarts
     * @param refusalPeriod   how long an exhausted subject is refused
     * @param trackedSubjects ceiling on how many subjects are tracked at once
     */
    record Policy(int maxFailures, Duration failureWindow, Duration refusalPeriod,
            int trackedSubjects) {

        /**
         * @param maxFailures     failures that exhaust an allowance; must be positive
         * @param failureWindow   span over which failures accumulate; must be positive
         * @param refusalPeriod   how long a refusal holds; must be positive
         * @param trackedSubjects ceiling on tracked subjects; must be positive
         * @throws IllegalArgumentException if a figure is not positive or a duration is zero or negative
         * @throws NullPointerException     if a duration is {@code null}
         */
        public Policy {
            Objects.requireNonNull(failureWindow, "failureWindow must not be null");
            Objects.requireNonNull(refusalPeriod, "refusalPeriod must not be null");
            if (maxFailures <= 0) {
                throw new IllegalArgumentException("maxFailures must be positive");
            }
            if (trackedSubjects <= 0) {
                throw new IllegalArgumentException("trackedSubjects must be positive");
            }
            if (failureWindow.isZero() || failureWindow.isNegative()) {
                throw new IllegalArgumentException("failureWindow must be a positive duration");
            }
            if (refusalPeriod.isZero() || refusalPeriod.isNegative()) {
                throw new IllegalArgumentException("refusalPeriod must be a positive duration");
            }
        }
    }

    /**
     * The result of one transition: the state to store, and what happened.
     *
     * @param entry   the state after the failure; never {@code null}
     * @param outcome what the transition did; never {@code null}
     */
    record Transition(Entry entry, FailureOutcome outcome) {

        /**
         * @param entry   the state after the failure; must not be {@code null}
         * @param outcome what the transition did; must not be {@code null}
         * @throws NullPointerException if either argument is {@code null}
         */
        public Transition {
            Objects.requireNonNull(entry, "entry must not be null");
            Objects.requireNonNull(outcome, "outcome must not be null");
        }
    }
}
