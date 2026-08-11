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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.carddemo.service.SignOnAttemptLedger.FailureOutcome;
import com.carddemo.service.SignOnAttemptLedger.Policy;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Specifies how the per-process store keeps state: its ceiling, its slot accounting, and its scope.
 *
 * <h2>What is under specification here, and what is not</h2>
 *
 * <p>The transition itself - what a failure does to a count, when a refusal engages, when a window lapses -
 * belongs to {@link SignOnAttemptLedgerTest} and is deliberately not restated here. What this file
 * specifies is everything the <em>storage</em> is responsible for: that the tracked-subject ceiling is
 * exact, that a slot is never leaked or double-freed, that a refusing entry is never dropped to make room,
 * that one subject's concurrent failures all count, and that the state is confined to one process.
 *
 * <p>That last property is the reason this class exists rather than being the only store. It is a correct
 * store for a single-instance deployment and an incorrect one for every other, because an allowance held
 * in one process's memory is multiplied by the number of processes and reset by any restart. The
 * specification below asserts the confinement rather than describing it, so the reason production refuses
 * this store is a demonstrated fact. See {@code docs/decision-log.md} entries DL-308 for the ceiling's
 * atomicity and DL-343 for the scope.
 *
 * @since 1.0.0
 */
@DisplayName("the per-process sign-on attempt store")
class InMemorySignOnAttemptLedgerTest {

    /** A fixed instant, so every window and deadline below is arithmetic rather than timing. */
    private static final Instant NOW = Instant.parse("2026-03-01T12:00:00Z");

    /** The window failures accumulate over. */
    private static final Duration WINDOW = Duration.ofMinutes(5);

    /** How long an exhausted subject is refused. */
    private static final Duration REFUSAL = Duration.ofMinutes(2);

    /** Longest a concurrency case waits before it is treated as hung. */
    private static final int WAIT_SECONDS = 20;

    /** Creates the specification. */
    InMemorySignOnAttemptLedgerTest() {
        // Intentionally empty: each case builds the ledger and policy it needs.
    }

    /**
     * Builds a policy whose only interesting figure is the ceiling.
     *
     * @param  trackedSubjects the ceiling to apply
     * @return a policy with an allowance far above any case's failure count
     */
    private static Policy roomyAllowanceWithCeiling(final int trackedSubjects) {
        return new Policy(1_000, WINDOW, REFUSAL, trackedSubjects);
    }

    /**
     * Builds a policy on which a single failure engages a refusal.
     *
     * @param  trackedSubjects the ceiling to apply
     * @return a policy whose allowance is one
     */
    private static Policy immediateRefusalWithCeiling(final int trackedSubjects) {
        return new Policy(1, WINDOW, REFUSAL, trackedSubjects);
    }

    @Nested
    @DisplayName("the tracked-subject ceiling")
    class TheCeiling {

        @Test
        @DisplayName("admits exactly as many distinct subjects as it names, and reports the next one as "
                + "saturated rather than silently dropping it")
        void admitsExactlyTheCeiling() {
            final InMemorySignOnAttemptLedger ledger = new InMemorySignOnAttemptLedger();
            final Policy policy = roomyAllowanceWithCeiling(3);

            for (int subject = 0; subject < 3; subject++) {
                assertThat(ledger.recordFailure("i:subject-" + subject, NOW, policy))
                        .isEqualTo(FailureOutcome.COUNTED);
            }

            assertThat(ledger.recordFailure("i:one-too-many", NOW, policy))
                    .as("the saturated case is reported, because it is the state in which the protection "
                            + "is at its limit and an operator needs to know the limit was reached")
                    .isEqualTo(FailureOutcome.DECLINED_AT_CEILING);
            assertThat(ledger.trackedSubjectCount()).isEqualTo(3);
        }

        @Test
        @DisplayName("does not spend a slot on a subject it already tracks, so repeated failures for one "
                + "subject cannot squeeze the ceiling downwards")
        void doesNotSpendASlotOnAnExistingSubject() {
            final InMemorySignOnAttemptLedger ledger = new InMemorySignOnAttemptLedger();
            final Policy policy = roomyAllowanceWithCeiling(2);

            ledger.recordFailure("i:persistent", NOW, policy);
            for (int repeat = 0; repeat < 25; repeat++) {
                assertThat(ledger.recordFailure("i:persistent", NOW, policy))
                        .isEqualTo(FailureOutcome.COUNTED);
            }

            assertThat(ledger.recordFailure("i:newcomer", NOW, policy))
                    .as("a slot taken speculatively for an already-tracked subject must be returned. "
                            + "Keeping it would leak the ceiling downwards on every repeat until no new "
                            + "subject could be tracked at all - which is a throttle that stops "
                            + "throttling")
                    .isEqualTo(FailureOutcome.COUNTED);
            assertThat(ledger.trackedSubjectCount()).isEqualTo(2);
        }

        @Test
        @DisplayName("never drops a refusing entry to make room, so a caller cannot clear the record of "
                + "its own abuse by generating subjects")
        void neverDropsARefusingEntry() {
            final InMemorySignOnAttemptLedger ledger = new InMemorySignOnAttemptLedger();
            final Policy policy = immediateRefusalWithCeiling(2);

            assertThat(ledger.recordFailure("i:abuser-one", NOW, policy))
                    .isEqualTo(FailureOutcome.REFUSAL_ENGAGED);
            assertThat(ledger.recordFailure("i:abuser-two", NOW, policy))
                    .isEqualTo(FailureOutcome.REFUSAL_ENGAGED);

            final Instant during = NOW.plus(Duration.ofSeconds(1));
            assertThat(ledger.recordFailure("i:pressure", during, policy))
                    .as("the ceiling holds rather than evicting a refusal")
                    .isEqualTo(FailureOutcome.DECLINED_AT_CEILING);
            assertThat(ledger.isRefusing("i:abuser-one", during))
                    .as("both refusals survive the pressure. If they did not, generating subjects would "
                            + "be a way to release one's own refusal, which would make the ceiling a "
                            + "bypass for the throttle instead of a bound on it")
                    .isTrue();
            assertThat(ledger.isRefusing("i:abuser-two", during)).isTrue();
        }

        @Test
        @DisplayName("frees room by sweeping entries whose windows have lapsed, so the ceiling is a "
                + "concurrent bound and not a lifetime one")
        void sweepsLapsedEntriesToFreeRoom() {
            final InMemorySignOnAttemptLedger ledger = new InMemorySignOnAttemptLedger();
            final Policy policy = roomyAllowanceWithCeiling(2);

            ledger.recordFailure("i:long-gone-one", NOW, policy);
            ledger.recordFailure("i:long-gone-two", NOW, policy);

            final Instant afterTheWindow = NOW.plus(WINDOW);
            assertThat(ledger.recordFailure("i:newcomer", afterTheWindow, policy))
                    .as("a spent entry may go: the next failure for that subject would have restarted "
                            + "its count at one in any case, so nothing is lost by removing it")
                    .isEqualTo(FailureOutcome.COUNTED);
            assertThat(ledger.trackedSubjectCount())
                    .as("and the two lapsed entries went, rather than the store growing past its bound")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("reports room for an attempt whose subject it already tracks even when it is full, "
                + "so a saturated store still throttles the callers it knows")
        void reportsRoomForAnAlreadyTrackedSubject() {
            final InMemorySignOnAttemptLedger ledger = new InMemorySignOnAttemptLedger();
            final Policy policy = roomyAllowanceWithCeiling(1);
            ledger.recordFailure("i:known", NOW, policy);

            assertThat(ledger.canBeginTracking("i:known", "s:unknown", NOW, policy))
                    .as("the identity is tracked, so a failure for this attempt can still be counted "
                            + "and the attempt need not be refused")
                    .isTrue();
            assertThat(ledger.canBeginTracking("i:unknown", "s:unknown", NOW, policy))
                    .as("neither subject is tracked and there is no room, so a failure could NOT be "
                            + "counted - which is what the governor turns into a refusal rather than "
                            + "admitting an unlimited number of such attempts")
                    .isFalse();
        }

        @Test
        @DisplayName("reports room again once a sweep can free it, so saturation is transient")
        void reportsRoomAgainAfterASweep() {
            final InMemorySignOnAttemptLedger ledger = new InMemorySignOnAttemptLedger();
            final Policy policy = roomyAllowanceWithCeiling(1);
            ledger.recordFailure("i:spent", NOW, policy);

            assertThat(ledger.canBeginTracking("i:unknown", "s:unknown", NOW, policy)).isFalse();
            assertThat(ledger.canBeginTracking("i:unknown", "s:unknown", NOW.plus(WINDOW), policy))
                    .as("the probe sweeps before it answers, so a store full of spent entries recovers "
                            + "without waiting for a failure to arrive")
                    .isTrue();
        }
    }

    @Nested
    @DisplayName("releasing a subject")
    class ReleasingASubject {

        @Test
        @DisplayName("frees exactly one slot however many times it is asked, so the accounting cannot "
                + "drift below what is stored")
        void freesExactlyOneSlotHoweverManyTimesItIsAsked() {
            final InMemorySignOnAttemptLedger ledger = new InMemorySignOnAttemptLedger();
            final Policy policy = roomyAllowanceWithCeiling(2);
            ledger.recordFailure("i:first", NOW, policy);
            ledger.recordFailure("i:second", NOW, policy);

            ledger.release("i:first");
            ledger.release("i:first");
            ledger.release("i:never-tracked");

            assertThat(ledger.recordFailure("i:replacement", NOW, policy))
                    .as("one slot was freed, so one subject fits")
                    .isEqualTo(FailureOutcome.COUNTED);
            assertThat(ledger.recordFailure("i:one-too-many", NOW, policy))
                    .as("and only one. Decrementing per request rather than per removal would hand out "
                            + "slots that do not exist, letting the store grow past the bound that makes "
                            + "it safe to key on caller-influenced text")
                    .isEqualTo(FailureOutcome.DECLINED_AT_CEILING);
            assertThat(ledger.trackedSubjectCount()).isEqualTo(2);
        }

        @Test
        @DisplayName("discards the released subject's accumulated failures, so an admitted operator "
                + "starts clean")
        void discardsTheReleasedSubjectsFailures() {
            final InMemorySignOnAttemptLedger ledger = new InMemorySignOnAttemptLedger();
            final Policy policy = new Policy(2, WINDOW, REFUSAL, 8);

            assertThat(ledger.recordFailure("i:operator", NOW, policy))
                    .isEqualTo(FailureOutcome.COUNTED);
            ledger.release("i:operator");

            assertThat(ledger.recordFailure("i:operator", NOW, policy))
                    .as("had the earlier failure survived the release, this second one would have "
                            + "exhausted an allowance of two and engaged a refusal against an operator "
                            + "who has just signed on successfully")
                    .isEqualTo(FailureOutcome.COUNTED);
            assertThat(ledger.isRefusing("i:operator", NOW)).isFalse();
        }
    }

    @Nested
    @DisplayName("reading a subject's state")
    class ReadingState {

        @Test
        @DisplayName("reports an unknown subject as not refused without beginning to track it, because "
                + "the probe runs on every attempt including the ones that are admitted")
        void doesNotTrackASubjectItIsMerelyAskedAbout() {
            final InMemorySignOnAttemptLedger ledger = new InMemorySignOnAttemptLedger();

            assertThat(ledger.isRefusing("i:stranger", NOW)).isFalse();
            assertThat(ledger.trackedSubjectCount())
                    .as("a read that inserted would let every admitted sign-on consume a slot, so the "
                            + "store would saturate on ordinary traffic and the ceiling's fail-closed "
                            + "refusal would fire against legitimate operators")
                    .isZero();
        }

        @Test
        @DisplayName("starts empty and counts one entry per distinct subject")
        void countsOneEntryPerDistinctSubject() {
            final InMemorySignOnAttemptLedger ledger = new InMemorySignOnAttemptLedger();
            final Policy policy = roomyAllowanceWithCeiling(8);

            assertThat(ledger.trackedSubjectCount()).isZero();
            ledger.recordFailure("i:alpha", NOW, policy);
            ledger.recordFailure("s:alpha", NOW, policy);
            ledger.recordFailure("i:alpha", NOW, policy);

            assertThat(ledger.trackedSubjectCount())
                    .as("the two namespaces are distinct subjects even when the text after the prefix "
                            + "coincides, and a repeat is not a second subject")
                    .isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("under concurrency")
    class UnderConcurrency {

        @Test
        @DisplayName("counts every concurrent failure for one subject, so an attacker gains nothing by "
                + "attempting in parallel")
        void countsEveryConcurrentFailureForOneSubject() throws Exception {
            final int threads = 8;
            final int perThread = 25;
            final int total = threads * perThread;
            final InMemorySignOnAttemptLedger ledger = new InMemorySignOnAttemptLedger();
            // The allowance is exactly the number of failures about to be applied, so the final failure
            // is the one that engages the refusal. Any lost failure leaves the subject unrefused, which
            // is precisely the flaw a non-atomic read-modify-write would produce.
            final Policy policy = new Policy(total, WINDOW, REFUSAL, 16);

            final List<FailureOutcome> outcomes = applyConcurrently(ledger, policy, threads,
                    thread -> "i:contended", perThread);

            assertThat(ledger.isRefusing("i:contended", NOW))
                    .as("all %d failures counted, so the last one exhausted the allowance", total)
                    .isTrue();
            assertThat(outcomes.stream().filter(FailureOutcome.REFUSAL_ENGAGED::equals).count())
                    .as("and the refusal engaged exactly once rather than repeatedly")
                    .isEqualTo(1L);
            assertThat(outcomes).hasSize(total)
                    .doesNotContain(FailureOutcome.ALREADY_REFUSING,
                            FailureOutcome.DECLINED_AT_CEILING, FailureOutcome.DECLINED_BY_RACE,
                            FailureOutcome.STORE_UNAVAILABLE);
        }

        @Test
        @DisplayName("holds the ceiling exactly when many threads race to insert distinct subjects, so "
                + "the bound is atomic rather than approximate")
        void holdsTheCeilingExactlyUnderContention() throws Exception {
            final int threads = 8;
            final int perThread = 12;
            final int ceiling = 5;
            final InMemorySignOnAttemptLedger ledger = new InMemorySignOnAttemptLedger();
            final Policy policy = roomyAllowanceWithCeiling(ceiling);

            final List<FailureOutcome> outcomes = applyConcurrently(ledger, policy, threads,
                    thread -> "i:racer-" + thread, perThread);

            assertThat(ledger.trackedSubjectCount())
                    .as("deciding admission inside a per-key computation let two first attempts for "
                            + "different subjects each observe room and each take it, so the store grew "
                            + "past its bound. The reservation is taken before the computation for "
                            + "exactly this reason - see decision-log entry DL-308")
                    .isEqualTo(ceiling);
            assertThat(outcomes.stream().filter(FailureOutcome.DECLINED_AT_CEILING::equals).count())
                    .as("the threads that found no room were told so")
                    .isPositive();
        }

        /**
         * Applies failures from several threads at once and collects every outcome.
         *
         * @param  ledger    the store under specification
         * @param  policy    the thresholds to apply
         * @param  threads   how many threads apply failures
         * @param  subjectOf names the subject a given thread uses
         * @param  perThread how many failures each thread applies
         * @return every outcome reported, in no particular order
         * @throws Exception if a worker fails or the pool does not drain in time
         */
        private List<FailureOutcome> applyConcurrently(final InMemorySignOnAttemptLedger ledger,
                final Policy policy, final int threads,
                final IntFunction<String> subjectOf, final int perThread)
                throws Exception {
            final ExecutorService workers = Executors.newFixedThreadPool(threads);
            final List<FailureOutcome> collected = new ArrayList<>();
            try {
                final List<Future<List<FailureOutcome>>> runs = new ArrayList<>();
                for (int thread = 0; thread < threads; thread++) {
                    final String subject = subjectOf.apply(thread);
                    runs.add(workers.submit(() -> {
                        final List<FailureOutcome> mine = new ArrayList<>();
                        for (int attempt = 0; attempt < perThread; attempt++) {
                            mine.add(ledger.recordFailure(subject, NOW, policy));
                        }
                        return mine;
                    }));
                }
                for (final Future<List<FailureOutcome>> run : runs) {
                    collected.addAll(run.get(WAIT_SECONDS, TimeUnit.SECONDS));
                }
            } finally {
                workers.shutdown();
                assertThat(workers.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
            }
            return collected;
        }
    }

    @Nested
    @DisplayName("the scope of the state, which is the reason a second store exists")
    class TheScopeOfTheState {

        @Test
        @DisplayName("declares itself not deployment-wide, which is what a production start-up refuses")
        void declaresItselfNotDeploymentWide() {
            assertThat(new InMemorySignOnAttemptLedger().isDeploymentWide())
                    .as("the declaration is the hook production validation reads. A store that "
                            + "misreported this would pass validation and still multiply the allowance "
                            + "by the replica count")
                    .isFalse();
        }

        @Test
        @DisplayName("confines a refusal to the instance that engaged it, so two replicas would give a "
                + "caller two allowances")
        void confinesARefusalToOneInstance() {
            final Policy policy = immediateRefusalWithCeiling(8);
            final InMemorySignOnAttemptLedger replicaOne = new InMemorySignOnAttemptLedger();
            final InMemorySignOnAttemptLedger replicaTwo = new InMemorySignOnAttemptLedger();

            assertThat(replicaOne.recordFailure("i:attacker", NOW, policy))
                    .isEqualTo(FailureOutcome.REFUSAL_ENGAGED);

            assertThat(replicaOne.isRefusing("i:attacker", NOW)).isTrue();
            assertThat(replicaTwo.isRefusing("i:attacker", NOW))
                    .as("this is the defect F-AUTH-2 named, asserted rather than described: behind one "
                            + "address, a caller refused by the instance it happened to reach is "
                            + "admitted by the next. Two instances give two allowances, ten give ten, "
                            + "and a restart returns every allowance to full. The behaviour is correct "
                            + "for this store and it is why production requires the shared one")
                    .isFalse();
            assertThat(replicaTwo.trackedSubjectCount()).isZero();
        }
    }

    @Nested
    @DisplayName("the arguments it refuses")
    class TheArgumentsItRefuses {

        @Test
        @DisplayName("refuses an absent subject, instant or policy on every operation rather than "
                + "treating one as a wildcard")
        void refusesAbsentArguments() {
            final InMemorySignOnAttemptLedger ledger = new InMemorySignOnAttemptLedger();
            final Policy policy = roomyAllowanceWithCeiling(4);

            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> ledger.isRefusing(null, NOW));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> ledger.isRefusing("i:a", null));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> ledger.recordFailure(null, NOW, policy));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> ledger.recordFailure("i:a", null, policy));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> ledger.recordFailure("i:a", NOW, null));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> ledger.release(null));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> ledger.canBeginTracking(null, "s:a", NOW, policy));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> ledger.canBeginTracking("i:a", null, NOW, policy));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> ledger.canBeginTracking("i:a", "s:a", null, policy));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> ledger.canBeginTracking("i:a", "s:a", NOW, null));
        }
    }
}
