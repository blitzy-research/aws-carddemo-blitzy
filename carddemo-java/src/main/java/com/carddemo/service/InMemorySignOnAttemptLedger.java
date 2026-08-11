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

import java.time.Instant;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One process's own view of the sign-on attempt state, held in memory.
 *
 * <h2>What this is for, and what it is not for</h2>
 *
 * <p>It is the store for a deployment that runs <strong>one</strong> instance: a developer machine, the
 * local Compose stack, a test. For those it is exactly right - no schema, no round trip, no clean-up job -
 * and it is the default so that a fresh clone runs with the protection on and needs nothing provisioned.
 *
 * <p>It is <strong>not</strong> correct for a deployment that runs more than one instance, and
 * {@link #isDeploymentWide()} answers {@code false} so that nothing has to infer it. Two replicas holding
 * separate maps give a caller two allowances; ten give ten; and a restart returns every allowance to full,
 * so a caller that can provoke a deployment - or simply wait for one - resets the bound without
 * authenticating. A production start-up refuses this store for that reason; see
 * {@code config/SignOnThrottleConfig} and {@code config/ProductionConfigurationValidator}.
 *
 * <h2>The bound on the map, and why a counter sits beside it</h2>
 *
 * <p>A subject is partly caller-supplied, so the map is capped. {@link Map#size()} answers a question about
 * the past: reading it, deciding there is room and then inserting is three steps, and the cap is only
 * respected if nothing inserts in between - which is exactly what a burst of first attempts for distinct
 * subjects does. A slot is therefore <em>reserved</em> against an {@link AtomicInteger} before the
 * per-key computation runs, and released if the computation turns out not to need it. The counter is
 * maintained to equal the map's size exactly: every insertion consumes a reservation and every removal -
 * the expiry sweep and a release alike - returns one.
 *
 * <p>The sweep runs <em>outside</em> every per-key computation, because a mapping function is forbidden
 * from modifying the map it is computing in and doing so risks not terminating rather than merely being
 * untidy.
 *
 * <p>Recorded in {@code docs/decision-log.md} entries DL-308 for the reservation and DL-343 for the scope.
 *
 * <p>Safe for concurrent use: the map is concurrent and every state change to one subject is applied inside
 * that map's own per-key atomic computation.
 *
 * @since 1.0.0
 */
public final class InMemorySignOnAttemptLedger implements SignOnAttemptLedger {

    /** Per-subject state, keyed by prefixed subject. */
    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    /** Slots taken in {@link #entries}, held separately so the ceiling can be enforced atomically. */
    private final AtomicInteger reservedSlots = new AtomicInteger();

    /** Creates an empty ledger. */
    public InMemorySignOnAttemptLedger() {
        // Intentionally empty: the state is the two fields above and both are final.
    }

    @Override
    public boolean isRefusing(final String subject, final Instant now) {
        Objects.requireNonNull(subject, "subject must not be null");
        Objects.requireNonNull(now, "now must not be null");
        final Entry existing = this.entries.get(subject);
        return existing != null && existing.isRefusing(now);
    }

    @Override
    public FailureOutcome recordFailure(final String subject, final Instant now, final Policy policy) {
        Objects.requireNonNull(subject, "subject must not be null");
        Objects.requireNonNull(now, "now must not be null");
        Objects.requireNonNull(policy, "policy must not be null");
        // Decided BEFORE the computation. A mapping function can see one key; the ceiling is a property of
        // the map. Deciding admission inside the computation meant two first attempts for different
        // subjects each observed room and each took it, and it also meant the sweep that frees room ran
        // while a bin was locked. See docs/decision-log.md entry DL-308.
        final Reservation reservation = reserveIfNeeded(subject, now, policy);
        final FailureOutcome[] outcome = {FailureOutcome.DECLINED_BY_RACE};
        final boolean[] consumed = {false};
        this.entries.compute(subject, (key, current) -> {
            if (current == null && reservation != Reservation.HELD) {
                // No entry and no slot to make one with: at the ceiling this is the saturated case, and
                // otherwise it is the immaterial race in which an entry was swept between the probe and
                // this computation.
                outcome[0] = reservation == Reservation.REFUSED
                        ? FailureOutcome.DECLINED_AT_CEILING
                        : FailureOutcome.DECLINED_BY_RACE;
                return null;
            }
            if (current == null) {
                consumed[0] = true;
            }
            final Transition transition = SignOnAttemptLedger.nextAfterFailure(current, now, policy);
            outcome[0] = transition.outcome();
            return transition.entry();
        });
        if (reservation == Reservation.HELD && !consumed[0]) {
            // The subject already existed, so the slot was not needed after all. Returning it is what
            // keeps the counter equal to the map's size; keeping it would leak the ceiling downwards until
            // no new subject could ever be tracked.
            this.reservedSlots.decrementAndGet();
        }
        // The computed entry itself is not read back: the outcome already says what happened to it, and the
        // map holds whatever the transition produced.
        return outcome[0];
    }

    @Override
    public void release(final String subject) {
        Objects.requireNonNull(subject, "subject must not be null");
        // Conditional on the removal actually happening, because two callers can ask to release the same
        // subject and only one of them frees a slot. Decrementing unconditionally would drift the counter
        // below the map's size and hand out slots that do not exist.
        if (this.entries.remove(subject) != null) {
            this.reservedSlots.decrementAndGet();
        }
    }

    @Override
    public int trackedSubjectCount() {
        return this.entries.size();
    }

    @Override
    public boolean canBeginTracking(final String identitySubject, final String sourceSubject,
            final Instant now, final Policy policy) {
        Objects.requireNonNull(identitySubject, "identitySubject must not be null");
        Objects.requireNonNull(sourceSubject, "sourceSubject must not be null");
        Objects.requireNonNull(now, "now must not be null");
        Objects.requireNonNull(policy, "policy must not be null");
        if (this.entries.containsKey(identitySubject) || this.entries.containsKey(sourceSubject)) {
            return true;
        }
        if (this.reservedSlots.get() < policy.trackedSubjects()) {
            return true;
        }
        sweepExpired(now, policy);
        return this.reservedSlots.get() < policy.trackedSubjects();
    }

    @Override
    public boolean isDeploymentWide() {
        return false;
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
     * <p>The probe for an existing subject is a fast path and not a correctness guarantee: an entry can be
     * swept between the probe and the computation. When that happens the computation finds no mapping while
     * holding no reservation and declines, which the outcome reports as the immaterial race it is.
     *
     * @param  subject the prefixed subject about to be recorded
     * @param  now     the instant of the failure, for the sweep
     * @param  policy  the thresholds whose ceiling is being enforced
     * @return what the caller holds
     */
    private Reservation reserveIfNeeded(final String subject, final Instant now, final Policy policy) {
        if (this.entries.containsKey(subject)) {
            return Reservation.NOT_NEEDED;
        }
        if (tryReserveSlot(policy)) {
            return Reservation.HELD;
        }
        // Only here, outside every computation, is it safe to remove other mappings.
        sweepExpired(now, policy);
        return tryReserveSlot(policy) ? Reservation.HELD : Reservation.REFUSED;
    }

    /**
     * Takes one slot if the ceiling leaves room, atomically.
     *
     * @param  policy the thresholds whose ceiling is being enforced
     * @return {@code true} when a slot was taken and is now owed back or consumed
     */
    private boolean tryReserveSlot(final Policy policy) {
        int taken = this.reservedSlots.get();
        while (taken < policy.trackedSubjects()) {
            if (this.reservedSlots.compareAndSet(taken, taken + 1)) {
                return true;
            }
            taken = this.reservedSlots.get();
        }
        return false;
    }

    /**
     * Removes every entry that is neither refusing nor inside its window.
     *
     * @param now    the instant to judge expiry against
     * @param policy the thresholds whose window decides
     */
    private void sweepExpired(final Instant now, final Policy policy) {
        final Iterator<Map.Entry<String, Entry>> iterator = this.entries.entrySet().iterator();
        while (iterator.hasNext()) {
            final Map.Entry<String, Entry> mapping = iterator.next();
            final Entry entry = mapping.getValue();
            if (!entry.isSweepable(now, policy)) {
                continue;
            }
            // Removed by key AND value rather than through the iterator, for two reasons. It reports
            // whether it actually removed anything, which is what makes the slot release exact when two
            // threads sweep at once; and it declines to remove an entry another thread has replaced since
            // this iteration observed it - which could otherwise discard a refusal that had just engaged,
            // letting a caller clear the record of its own abuse.
            if (this.entries.remove(mapping.getKey(), entry)) {
                this.reservedSlots.decrementAndGet();
            }
        }
    }
}
