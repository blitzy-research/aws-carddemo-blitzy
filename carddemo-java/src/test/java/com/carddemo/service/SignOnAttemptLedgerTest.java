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

import com.carddemo.service.SignOnAttemptLedger.Entry;
import com.carddemo.service.SignOnAttemptLedger.FailureOutcome;
import com.carddemo.service.SignOnAttemptLedger.Policy;
import com.carddemo.service.SignOnAttemptLedger.Transition;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Specifies the shared state machine every sign-on attempt store applies, and the two bounds it declares.
 *
 * <h2>Why the transition is specified here rather than once per store</h2>
 *
 * <p>{@link SignOnAttemptLedger#nextAfterFailure(Entry, Instant, Policy)} is a pure function and it is the
 * only place the transition is written down. Two stores that each implemented it would be two answers to one
 * question, and the interesting cases - a refusal already in force, a window that has lapsed, the failure
 * that exhausts an allowance - are exactly the cases in which two implementations drift. Specifying it here
 * once is what makes {@code InMemorySignOnAttemptLedgerTest} and {@code PostgresSignOnAttemptLedgerIT} tests
 * of <em>storage</em> rather than two copies of this file.
 *
 * <p>The behaviour under specification is recorded in {@code docs/decision-log.md} entries DL-268 for the
 * allowance and DL-343 for the store's scope.
 *
 * @since 1.0.0
 */
@DisplayName("the sign-on attempt state machine, shared by every store")
class SignOnAttemptLedgerTest {

    /** A small allowance, so the exhausting failure is reachable in three lines rather than ten. */
    private static final int ALLOWANCE = 3;

    /** The window failures accumulate over. */
    private static final Duration WINDOW = Duration.ofMinutes(5);

    /** How long an exhausted subject is refused. */
    private static final Duration REFUSAL = Duration.ofMinutes(2);

    /** Room enough that the ceiling never enters these specifications. */
    private static final int TRACKED_SUBJECTS = 64;

    /** The thresholds every case below applies. */
    private static final Policy POLICY = new Policy(ALLOWANCE, WINDOW, REFUSAL, TRACKED_SUBJECTS);

    /** A fixed instant, so every window and deadline below is arithmetic rather than timing. */
    private static final Instant NOW = Instant.parse("2026-03-01T12:00:00Z");

    /** Creates the specification. */
    SignOnAttemptLedgerTest() {
        // Intentionally empty: every case builds its own state.
    }

    @Nested
    @DisplayName("the first failure a subject has")
    class TheFirstFailure {

        @Test
        @DisplayName("opens a window at the instant of the failure and counts one")
        void opensAWindowAndCountsOne() {
            final Transition transition = SignOnAttemptLedger.nextAfterFailure(null, NOW, POLICY);

            assertThat(transition.outcome())
                    .as("one failure against an allowance of %d is counted, not refused", ALLOWANCE)
                    .isEqualTo(FailureOutcome.COUNTED);
            assertThat(transition.entry().failures()).isEqualTo(1);
            assertThat(transition.entry().windowStartedAt())
                    .as("the window opens at the failure rather than at some earlier moment, so an "
                            + "operator who mistypes once is measured from then")
                    .isEqualTo(NOW);
            assertThat(transition.entry().refusedUntil())
                    .as("and nothing is refused yet")
                    .isNull();
        }

        @Test
        @DisplayName("engages the refusal immediately when the allowance is one, because one failure then "
                + "exhausts it")
        void engagesImmediatelyWhenTheAllowanceIsOne() {
            final Policy strict = new Policy(1, WINDOW, REFUSAL, TRACKED_SUBJECTS);

            final Transition transition = SignOnAttemptLedger.nextAfterFailure(null, NOW, strict);

            assertThat(transition.outcome())
                    .as("an allowance of one means the first failure spends it. The delivered default is "
                            + "ten, so this is a configuration a deployment could choose rather than one "
                            + "it has - and the arithmetic must still hold at the boundary")
                    .isEqualTo(FailureOutcome.REFUSAL_ENGAGED);
            assertThat(transition.entry().refusedUntil()).isEqualTo(NOW.plus(REFUSAL));
        }
    }

    @Nested
    @DisplayName("a failure inside the window")
    class AFailureInsideTheWindow {

        @Test
        @DisplayName("advances the count and keeps the window's original start, so the window is not "
                + "extended by being used")
        void advancesTheCountAndKeepsTheWindowStart() {
            final Entry existing = new Entry(1, NOW, null);
            final Instant later = NOW.plus(Duration.ofMinutes(1));

            final Transition transition = SignOnAttemptLedger.nextAfterFailure(existing, later, POLICY);

            assertThat(transition.outcome()).isEqualTo(FailureOutcome.COUNTED);
            assertThat(transition.entry().failures()).isEqualTo(2);
            assertThat(transition.entry().windowStartedAt())
                    .as("a sliding window would never lapse for a caller attempting steadily, which "
                            + "would turn a rate limit into a lifetime limit")
                    .isEqualTo(NOW);
        }

        @Test
        @DisplayName("engages the refusal on the failure that reaches the allowance, and restarts the "
                + "count so the period is measured once")
        void engagesOnTheFailureThatReachesTheAllowance() {
            final Entry existing = new Entry(ALLOWANCE - 1, NOW, null);
            final Instant later = NOW.plus(Duration.ofMinutes(1));

            final Transition transition = SignOnAttemptLedger.nextAfterFailure(existing, later, POLICY);

            assertThat(transition.outcome()).isEqualTo(FailureOutcome.REFUSAL_ENGAGED);
            assertThat(transition.entry().refusedUntil())
                    .as("the deadline is one refusal period after the failure that engaged it")
                    .isEqualTo(later.plus(REFUSAL));
            assertThat(transition.entry().failures())
                    .as("and the count restarts, so the period that follows is measured once rather than "
                            + "re-measured on every further attempt")
                    .isZero();
            assertThat(transition.entry().isRefusing(later)).isTrue();
        }

        @Test
        @DisplayName("counts the last failure before the allowance as a count and not an engagement, so "
                + "the boundary is the allowance itself")
        void theBoundaryIsTheAllowanceItself() {
            final Entry existing = new Entry(ALLOWANCE - 2, NOW, null);

            final Transition transition = SignOnAttemptLedger.nextAfterFailure(existing, NOW, POLICY);

            assertThat(transition.outcome()).isEqualTo(FailureOutcome.COUNTED);
            assertThat(transition.entry().failures()).isEqualTo(ALLOWANCE - 1);
        }
    }

    @Nested
    @DisplayName("a failure after the window has lapsed")
    class AFailureAfterTheWindowHasLapsed {

        @Test
        @DisplayName("restarts the count at one and the window now, so an operator who mistypes once a "
                + "month is never refused")
        void restartsTheCountAndTheWindow() {
            final Entry existing = new Entry(ALLOWANCE - 1, NOW, null);
            final Instant muchLater = NOW.plus(WINDOW).plus(Duration.ofSeconds(1));

            final Transition transition =
                    SignOnAttemptLedger.nextAfterFailure(existing, muchLater, POLICY);

            assertThat(transition.outcome())
                    .as("accumulating across a lapsed window would refuse a legitimate operator on their "
                            + "%dth mistype however far apart the mistypes were", ALLOWANCE)
                    .isEqualTo(FailureOutcome.COUNTED);
            assertThat(transition.entry().failures()).isEqualTo(1);
            assertThat(transition.entry().windowStartedAt()).isEqualTo(muchLater);
        }

        @Test
        @DisplayName("treats the instant the window ends as outside it, so the window is half-open")
        void theWindowIsHalfOpen() {
            final Entry existing = new Entry(1, NOW, null);

            final Transition atTheBoundary =
                    SignOnAttemptLedger.nextAfterFailure(existing, NOW.plus(WINDOW), POLICY);

            assertThat(atTheBoundary.entry().failures())
                    .as("the window covers [start, start + window). A closed upper bound would make the "
                            + "window one instant longer than configured, which is immaterial in itself "
                            + "and is worth pinning so the two stores cannot disagree about it")
                    .isEqualTo(1);
            assertThat(atTheBoundary.entry().windowStartedAt()).isEqualTo(NOW.plus(WINDOW));
        }
    }

    @Nested
    @DisplayName("a failure while a refusal is in force")
    class AFailureWhileRefusing {

        @Test
        @DisplayName("leaves the entry exactly as it was, so the period cannot be extended by attempting "
                + "during it")
        void leavesTheEntryUntouched() {
            final Entry refusing = new Entry(0, NOW, NOW.plus(REFUSAL));
            final Instant during = NOW.plus(Duration.ofSeconds(30));

            final Transition transition = SignOnAttemptLedger.nextAfterFailure(refusing, during, POLICY);

            assertThat(transition.outcome())
                    .as("the failure IS recorded as seen - it happened - but it is not a fresh "
                            + "engagement, because the refusal it belongs to was engaged once already")
                    .isEqualTo(FailureOutcome.ALREADY_REFUSING);
            assertThat(transition.entry())
                    .as("extending the deadline on every further attempt would make the period "
                            + "unbounded, which is a caller-driven denial of service against whoever "
                            + "legitimately owns the subject")
                    .isEqualTo(refusing);
        }

        @Test
        @DisplayName("counts again once the refusal has lifted, and does so against the window that was "
                + "still open rather than a fresh one")
        void countsAgainOnceTheRefusalHasLifted() {
            final Entry refusing = new Entry(0, NOW, NOW.plus(REFUSAL));
            final Instant after = NOW.plus(REFUSAL).plus(Duration.ofSeconds(1));

            final Transition transition = SignOnAttemptLedger.nextAfterFailure(refusing, after, POLICY);

            assertThat(transition.outcome())
                    .as("the release is by time alone - nothing had to clear the refusal for counting to "
                            + "resume")
                    .isEqualTo(FailureOutcome.COUNTED);
            assertThat(transition.entry().refusedUntil()).isNull();
            assertThat(transition.entry().failures()).isEqualTo(1);
            assertThat(transition.entry().windowStartedAt())
                    .as("the refusal period here is shorter than the window, so lifting it lands back "
                            + "inside the window the count restarted with when the refusal engaged. That "
                            + "window is kept, which is the same rule as every other in-window failure")
                    .isEqualTo(NOW);
        }

        @Test
        @DisplayName("treats the deadline instant itself as no longer refusing, so the period is "
                + "half-open like the window")
        void theRefusalIsHalfOpen() {
            final Entry refusing = new Entry(0, NOW, NOW.plus(REFUSAL));

            assertThat(refusing.isRefusing(NOW.plus(REFUSAL).minusNanos(1))).isTrue();
            assertThat(refusing.isRefusing(NOW.plus(REFUSAL)))
                    .as("a refusal lasts [engaged, engaged + period)")
                    .isFalse();
        }
    }

    @Nested
    @DisplayName("what may be swept to make room, and what may not")
    class WhatMayBeSwept {

        @Test
        @DisplayName("a refusing entry is never sweepable, whatever the pressure on the store")
        void aRefusingEntryIsNeverSweepable() {
            final Entry refusing = new Entry(0, NOW, NOW.plus(REFUSAL));

            assertThat(refusing.isSweepable(NOW.plus(Duration.ofSeconds(1)), POLICY))
                    .as("dropping a refusing entry would let a caller clear the record of its own abuse "
                            + "by generating subjects until the ceiling evicted it")
                    .isFalse();
        }

        @Test
        @DisplayName("an entry inside its window is not sweepable either, because its allowance is still "
                + "being spent")
        void anEntryInsideItsWindowIsNotSweepable() {
            final Entry counting = new Entry(1, NOW, null);

            assertThat(counting.isSweepable(NOW.plus(Duration.ofMinutes(1)), POLICY)).isFalse();
        }

        @Test
        @DisplayName("an entry that is neither refusing nor inside its window is sweepable, which is "
                + "exactly the state whose next failure restarts the count anyway")
        void aSpentEntryIsSweepable() {
            final Entry spent = new Entry(ALLOWANCE - 1, NOW, null);

            assertThat(spent.isSweepable(NOW.plus(WINDOW), POLICY))
                    .as("removing it loses nothing: the next failure for that subject would have "
                            + "restarted the count at one in any case")
                    .isTrue();
        }

        @Test
        @DisplayName("an entry whose refusal has lapsed and whose window has passed is sweepable, so a "
                + "refusal does not pin an entry for ever")
        void aLapsedRefusalBecomesSweepable() {
            final Entry lapsed = new Entry(0, NOW, NOW.plus(REFUSAL));

            assertThat(lapsed.isSweepable(NOW.plus(WINDOW).plus(REFUSAL), POLICY)).isTrue();
        }
    }

    @Nested
    @DisplayName("the values the contract declares")
    class TheDeclaredValues {

        @Test
        @DisplayName("a subject is bounded at 64 characters, which is far above the eight the delivered "
                + "sign-on contract permits")
        void aSubjectIsBoundedAtSixtyFour() {
            assertThat(SignOnAttemptLedger.MAX_SUBJECT_LENGTH)
                    .as("the bound exists for a caller reaching the service below the delivered "
                            + "contract: without it a caller chooses the size of a stored key, which in a "
                            + "durable ledger is a storage lever and an index-size one besides. It is "
                            + "also the width of the column V5 declares, and the two must agree")
                    .isEqualTo(64);
        }

        @Test
        @DisplayName("every outcome a store can report is named, so a switch over them can be exhaustive")
        void everyOutcomeIsNamed() {
            assertThat(FailureOutcome.values())
                    .as("the governor switches over these without a default arm, so adding one is a "
                            + "compile error rather than a silently unhandled case")
                    .containsExactly(FailureOutcome.COUNTED, FailureOutcome.REFUSAL_ENGAGED,
                            FailureOutcome.ALREADY_REFUSING, FailureOutcome.DECLINED_AT_CEILING,
                            FailureOutcome.DECLINED_BY_RACE, FailureOutcome.STORE_UNAVAILABLE);
        }

        @Test
        @DisplayName("a policy refuses every non-positive figure and every non-positive duration")
        void aPolicyRefusesNonPositiveFigures() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new Policy(0, WINDOW, REFUSAL, TRACKED_SUBJECTS));
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new Policy(ALLOWANCE, WINDOW, REFUSAL, 0));
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new Policy(ALLOWANCE, Duration.ZERO, REFUSAL, TRACKED_SUBJECTS));
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new Policy(ALLOWANCE, Duration.ofSeconds(-1), REFUSAL,
                            TRACKED_SUBJECTS));
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new Policy(ALLOWANCE, WINDOW, Duration.ZERO, TRACKED_SUBJECTS));
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new Policy(ALLOWANCE, WINDOW, Duration.ofSeconds(-1),
                            TRACKED_SUBJECTS));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new Policy(ALLOWANCE, null, REFUSAL, TRACKED_SUBJECTS));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new Policy(ALLOWANCE, WINDOW, null, TRACKED_SUBJECTS));
        }

        @Test
        @DisplayName("an entry refuses a negative count and an absent window start")
        void anEntryRefusesInvalidState() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("a negative count would make the allowance arithmetic run backwards, and the "
                            + "column V5 declares refuses it too")
                    .isThrownBy(() -> new Entry(-1, NOW, null));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new Entry(0, null, null));
        }

        @Test
        @DisplayName("a transition refuses an absent entry and an absent outcome")
        void aTransitionRefusesAbsentComponents() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new Transition(null, FailureOutcome.COUNTED));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new Transition(new Entry(0, NOW, null), null));
        }

        @Test
        @DisplayName("the transition refuses an absent instant and an absent policy rather than assuming "
                + "either")
        void theTransitionRefusesAbsentArguments() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> SignOnAttemptLedger.nextAfterFailure(null, null, POLICY));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> SignOnAttemptLedger.nextAfterFailure(null, NOW, null));
        }

        @Test
        @DisplayName("an entry refuses an absent instant on both of its predicates")
        void anEntryRefusesAnAbsentInstant() {
            final Entry entry = new Entry(1, NOW, null);

            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> entry.isRefusing(null));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> entry.isSweepable(null, POLICY));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> entry.isSweepable(NOW, null));
        }
    }
}
