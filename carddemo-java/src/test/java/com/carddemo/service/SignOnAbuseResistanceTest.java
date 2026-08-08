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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.carddemo.domain.UserSecurity;
import com.carddemo.domain.enums.KeyAction;
import com.carddemo.repository.UserSecurityRepository;
import com.carddemo.support.TestDataFactory;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Abuse resistance on the module's one anonymous surface: the attempt allowance, and the work the
 * not-found path now does.
 *
 * <h2>What this file is about</h2>
 *
 * <p>The sign-on transaction reproduces two properties of the legacy screen that are harmless on a
 * terminal and are not harmless on an open network. It reports an unknown identifier and a wrong secret
 * with <em>different</em> messages, so a caller can tell one from the other; and it verifies with a
 * deliberately expensive digest, so every attempt costs processor time. Neither may be withdrawn - both
 * message texts are frozen external contract and the cost factor is the point of the digest - so the
 * exposure is closed by bounding how many attempts a caller gets and by making the two outcomes cost the
 * same.
 *
 * <p>Three exposures follow from an unbounded surface and each has its own specification below: an
 * enumeration oracle, which is <em>explicit</em> in the messages and was additionally <em>implicit</em>
 * in the timing; an unbounded credential-stuffing channel; and a way to spend the server's processor
 * budget by presenting secrets that are certain to fail.
 *
 * <h2>Two subjects, because the two abuses have different shapes</h2>
 *
 * <p>A stuffing run drives many secrets at one identifier, so the identity subject catches it. A sweep
 * drives one secret at many identifiers and never repeats an identity, so only the source subject can
 * catch it. Both shapes are driven below, and the sweep specification is the one that would pass with an
 * identity-only counter and must not.
 *
 * <h2>Independent expectations</h2>
 *
 * <p>Every figure is written out here rather than read from the class under test, and time is advanced
 * by moving a test clock rather than by sleeping, so the window and the release are exact.
 *
 * <p>Provenance: the sign-on transaction of {@code app/cbl/COSGN00C.cbl} at checkout SHA
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, read as read-only reference. The parity exception
 * this file pins is recorded in {@code docs/decision-log.md}.
 */
@DisplayName("Sign-on abuse resistance :: the attempt allowance and the equalised not-found path")
class SignOnAbuseResistanceTest {

    /** Instant the test clock starts at. */
    private static final Instant START = Instant.parse("2026-01-01T12:00:00Z");

    /** Failures inside the window that exhaust an allowance in these specifications. */
    private static final int ALLOWANCE = 4;

    /** The window failures accumulate over. */
    private static final Duration WINDOW = Duration.ofMinutes(5);

    /** How long an exhausted subject is refused. */
    private static final Duration REFUSAL = Duration.ofMinutes(2);

    /** Ceiling on tracked subjects, small enough that the bound can be driven. */
    private static final int TRACKED_SUBJECTS = 4;

    /** One caller address, which is a fixture and identifies nothing. */
    private static final String SOURCE = "203.0.113.7";

    /** A second caller address, so the source subject can be shown to be per-source. */
    private static final String OTHER_SOURCE = "198.51.100.9";

    /** An identifier the credential master holds, at the record's fixed width. */
    private static final String KNOWN_ID = "TESTUSR1";

    /** An identifier the credential master does not hold. */
    private static final String UNKNOWN_ID = "NOSUCH01";

    /** The secret presented in these specifications. Not a credential of anything. */
    private static final String PRESENTED = "PASSWORD";

    /** A digest stand-in the doubled verifier answers with. Not a digest and not a credential. */
    private static final String STORED_DIGEST_STANDIN = "a-double-answers-with-this-not-a-digest";

    /** Creates the specification. */
    SignOnAbuseResistanceTest() {
        // Intentionally empty: each slice builds what it needs.
    }

    /** A clock the specifications move, so a window and a release are exact rather than timed. */
    private static final class MovableClock extends Clock {

        /** The instant this clock currently reports. */
        private Instant now;

        /**
         * @param start the instant to begin at
         */
        MovableClock(final Instant start) {
            this.now = start;
        }

        /**
         * Advances the reported instant.
         *
         * @param amount how far to move forward
         */
        void advance(final Duration amount) {
            this.now = this.now.plus(amount);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(final ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return this.now;
        }
    }

    /**
     * Reads one counter's total for one namespace.
     *
     * @param  meters    the registry the governor counted into
     * @param  name      the metric name
     * @param  namespace the namespace tag value
     * @return the count, or zero when the counter was never registered
     */
    private static double counted(final SimpleMeterRegistry meters, final String name,
            final String namespace) {
        final Counter counter = meters.find(name)
                .tag(SignOnAttemptGovernor.NAMESPACE_TAG, namespace)
                .counter();
        return counter == null ? 0.0d : counter.count();
    }

    @Nested
    @DisplayName("the allowance itself")
    class TheAllowance {

        /** The clock these specifications move. */
        private MovableClock clock;

        /** Where the governor's counters land. */
        private SimpleMeterRegistry meters;

        /** Subject under test. */
        private SignOnAttemptGovernor governor;

        /** Creates the slice. */
        TheAllowance() {
            // Intentionally empty.
        }

        /** Builds a fresh governor per specification, so none inherits another's count. */
        @BeforeEach
        void buildGovernor() {
            clock = new MovableClock(START);
            meters = new SimpleMeterRegistry();
            governor = new SignOnAttemptGovernor(true, ALLOWANCE, WINDOW, REFUSAL,
                    TRACKED_SUBJECTS, clock, meters);
        }

        @Test
        @DisplayName("nothing is refused before the allowance is spent, and the attempt that spends it "
                + "engages the refusal")
        void theAllowanceIsSpentBeforeAnythingIsRefused() {
            for (int attempt = 1; attempt < ALLOWANCE; attempt++) {
                governor.recordFailure(KNOWN_ID, SOURCE);
                assertThat(governor.isRefusing(KNOWN_ID, SOURCE))
                        .as("attempt %d of an allowance of %d must still be served: an operator who "
                                + "mistypes a secret is not an attacker", attempt, ALLOWANCE)
                        .isFalse();
            }

            governor.recordFailure(KNOWN_ID, SOURCE);

            assertThat(governor.isRefusing(KNOWN_ID, SOURCE))
                    .as("the attempt that spends the allowance engages the refusal")
                    .isTrue();
            assertThat(counted(meters, SignOnAttemptGovernor.ENGAGED_METRIC,
                    SignOnAttemptGovernor.IDENTITY_NAMESPACE))
                    .as("and the transition is counted, which is how an operator learns of it, since "
                            + "the caller is deliberately told nothing that distinguishes it")
                    .isEqualTo(1.0d);
        }

        @Test
        @DisplayName("A SWEEP IS CAUGHT: every failure names a different identifier, so only the source "
                + "subject accumulates - an identity-only counter would never refuse this")
        void anEnumerationSweepIsCaughtByTheSourceSubject() {
            for (int attempt = 0; attempt < ALLOWANCE; attempt++) {
                governor.recordFailure("SWEEP" + String.format(Locale.ROOT, "%03d", attempt), SOURCE);
            }

            assertThat(governor.isRefusing("NEVERSEEN", SOURCE))
                    .as("an identifier this governor has never counted a failure for is refused, "
                            + "because the SOURCE has spent its allowance; this is the assertion that "
                            + "fails if the source namespace is ever removed")
                    .isTrue();
            assertThat(governor.isRefusing("NEVERSEEN", OTHER_SOURCE))
                    .as("and the refusal is per source, so an unrelated caller is unaffected")
                    .isFalse();
        }

        @Test
        @DisplayName("A STUFFING RUN IS CAUGHT: one identifier from many addresses exhausts the identity "
                + "subject, so moving address does not buy a fresh allowance")
        void aCredentialStuffingRunIsCaughtByTheIdentitySubject() {
            for (int attempt = 0; attempt < ALLOWANCE; attempt++) {
                governor.recordFailure(KNOWN_ID, "192.0.2." + attempt);
            }

            assertThat(governor.isRefusing(KNOWN_ID, "192.0.2.250"))
                    .as("the identity has spent its allowance, so an address the governor has never "
                            + "seen is still refused for that identity")
                    .isTrue();
        }

        @Test
        @DisplayName("case is not a fresh allowance: two spellings of one identifier are one subject, "
                + "because the transaction folds the identifier before it looks anything up")
        void identityFoldingMakesTwoSpellingsOneSubject() {
            for (int attempt = 0; attempt < ALLOWANCE; attempt++) {
                governor.recordFailure(KNOWN_ID.toLowerCase(Locale.ROOT), SOURCE);
            }

            assertThat(governor.isRefusing(KNOWN_ID, SOURCE))
                    .as("the upper-case spelling is the same subject as the lower-case one")
                    .isTrue();
        }

        @Test
        @DisplayName("the refusal lifts by itself once the period passes, so this is a delay rather than "
                + "a lockout an operator has to be released from")
        void theRefusalLiftsWhenThePeriodPasses() {
            for (int attempt = 0; attempt < ALLOWANCE; attempt++) {
                governor.recordFailure(KNOWN_ID, SOURCE);
            }
            assertThat(governor.isRefusing(KNOWN_ID, SOURCE)).isTrue();

            clock.advance(REFUSAL.plusSeconds(1));

            assertThat(governor.isRefusing(KNOWN_ID, SOURCE))
                    .as("a legitimate operator caught behind someone else's abuse of their identifier "
                            + "is delayed and not locked out, which is why the period is finite")
                    .isFalse();
        }

        @Test
        @DisplayName("a refusal already in force is not extended by further attempts, so a caller cannot "
                + "hold an identifier refused indefinitely by continuing to hammer it")
        void aRefusalInForceIsNotExtended() {
            for (int attempt = 0; attempt < ALLOWANCE; attempt++) {
                governor.recordFailure(KNOWN_ID, SOURCE);
            }
            clock.advance(REFUSAL.minusSeconds(1));
            governor.recordFailure(KNOWN_ID, SOURCE);

            clock.advance(Duration.ofSeconds(2));

            assertThat(governor.isRefusing(KNOWN_ID, SOURCE))
                    .as("the deadline set when the allowance was spent is the deadline: extending it on "
                            + "every further attempt would be a caller-driven denial of service against "
                            + "whoever legitimately owns the identifier")
                    .isFalse();
        }

        @Test
        @DisplayName("failures outside the window do not accumulate, so an operator who mistypes once in "
                + "a while is never refused")
        void failuresOutsideTheWindowDoNotAccumulate() {
            for (int attempt = 0; attempt < ALLOWANCE - 1; attempt++) {
                governor.recordFailure(KNOWN_ID, SOURCE);
                clock.advance(WINDOW.plusSeconds(1));
            }
            governor.recordFailure(KNOWN_ID, SOURCE);

            assertThat(governor.isRefusing(KNOWN_ID, SOURCE))
                    .as("each failure fell outside the previous window, so the count restarted each "
                            + "time and the allowance was never spent")
                    .isFalse();
        }

        @Test
        @DisplayName("an admitted sign-on clears both subjects, so earlier mistypes are not carried "
                + "towards a later refusal")
        void anAdmittedSignOnClearsBothSubjects() {
            for (int attempt = 0; attempt < ALLOWANCE - 1; attempt++) {
                governor.recordFailure(KNOWN_ID, SOURCE);
            }

            governor.recordSuccess(KNOWN_ID, SOURCE);
            governor.recordFailure(KNOWN_ID, SOURCE);

            assertThat(governor.isRefusing(KNOWN_ID, SOURCE))
                    .as("the success reset the count, so the single failure after it is the first of a "
                            + "new window rather than the last of the old one")
                    .isFalse();
            assertThat(governor.trackedSubjectCount())
                    .as("and the cleared subjects were released rather than left behind")
                    .isEqualTo(2);
        }

        @Test
        @DisplayName("the tracked-subject count is bounded, because the subject of a failure is partly "
                + "caller-supplied and an unbounded map is a second denial-of-service channel")
        void theTrackedSubjectCountIsBounded() {
            for (int subject = 0; subject < TRACKED_SUBJECTS * 8; subject++) {
                governor.recordFailure("FLOOD" + String.format(Locale.ROOT, "%03d", subject),
                        "192.0.2." + subject);
            }

            assertThat(governor.trackedSubjectCount())
                    .as("the map never exceeds its configured ceiling however many subjects a caller "
                            + "invents")
                    .isLessThanOrEqualTo(TRACKED_SUBJECTS);
            assertThat(counted(meters, SignOnAttemptGovernor.UNTRACKED_METRIC,
                    SignOnAttemptGovernor.IDENTITY_NAMESPACE)
                    + counted(meters, SignOnAttemptGovernor.UNTRACKED_METRIC,
                            SignOnAttemptGovernor.SOURCE_NAMESPACE))
                    .as("and reaching the ceiling is counted rather than silent, so the bound is "
                            + "observable to an operator")
                    .isGreaterThan(0.0d);
        }

        @Test
        @DisplayName("a refusal already in force survives the pressure that a flood of invented subjects "
                + "puts on the map, so a caller cannot evict the record of its own abuse")
        void aRefusalSurvivesSubjectPressure() {
            for (int attempt = 0; attempt < ALLOWANCE; attempt++) {
                governor.recordFailure(KNOWN_ID, SOURCE);
            }
            assertThat(governor.isRefusing(KNOWN_ID, SOURCE)).isTrue();

            for (int subject = 0; subject < TRACKED_SUBJECTS * 8; subject++) {
                governor.recordFailure("FLOOD" + String.format(Locale.ROOT, "%03d", subject),
                        "192.0.2." + subject);
            }

            assertThat(governor.isRefusing(KNOWN_ID, SOURCE))
                    .as("the refusing entry is never swept to make room, whatever the pressure")
                    .isTrue();
        }

        @Test
        @DisplayName("refused attempts are counted per namespace, so the two abuse shapes are "
                + "distinguishable in the metrics an operator reads")
        void refusedAttemptsAreCountedPerNamespace() {
            for (int attempt = 0; attempt < ALLOWANCE; attempt++) {
                governor.recordFailure(KNOWN_ID, SOURCE);
            }

            governor.isRefusing(KNOWN_ID, SOURCE);

            assertThat(counted(meters, SignOnAttemptGovernor.REFUSED_METRIC,
                    SignOnAttemptGovernor.IDENTITY_NAMESPACE))
                    .as("the identity namespace refused this attempt")
                    .isEqualTo(1.0d);
            assertThat(counted(meters, SignOnAttemptGovernor.RECORDED_METRIC,
                    SignOnAttemptGovernor.SOURCE_NAMESPACE))
                    .as("and every failure was recorded in both namespaces")
                    .isEqualTo(ALLOWANCE);
        }

        @Test
        @DisplayName("an unattributed caller address is counted under an explicit stand-in rather than "
                + "skipped, so a flood from a caller with no attributed address is still bounded")
        void anUnattributedSourceIsStillBounded() {
            for (int attempt = 0; attempt < ALLOWANCE; attempt++) {
                governor.recordFailure("NOSRC" + String.format(Locale.ROOT, "%03d", attempt), null);
            }

            assertThat(governor.isRefusing("NEVERSEEN", null))
                    .as("the unattributed subject accumulated exactly as an attributed one would")
                    .isTrue();
        }

        @Test
        @DisplayName("switched off, the governor refuses nothing at all, so a deployment that must not "
                + "carry the protection carries none of it rather than a weakened form")
        void switchedOffTheGovernorRefusesNothing() {
            final SignOnAttemptGovernor disabled = new SignOnAttemptGovernor(false, 1, WINDOW,
                    REFUSAL, TRACKED_SUBJECTS, clock, meters);

            for (int attempt = 0; attempt < ALLOWANCE * 4; attempt++) {
                disabled.recordFailure(KNOWN_ID, SOURCE);
            }

            assertThat(disabled.isRefusing(KNOWN_ID, SOURCE))
                    .as("nothing is refused")
                    .isFalse();
            assertThat(disabled.trackedSubjectCount())
                    .as("and nothing is retained either, so the switch costs no memory")
                    .isZero();
            assertThat(disabled.isEnabled()).isFalse();
        }

        @Test
        @DisplayName("every configured figure is validated at construction, so a deployment cannot "
                + "silently start with an allowance or a period that means nothing")
        void everyConfiguredFigureIsValidated() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("an allowance of nothing")
                    .isThrownBy(() -> new SignOnAttemptGovernor(true, 0, WINDOW, REFUSAL,
                            TRACKED_SUBJECTS, clock, meters));
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("a window of no duration")
                    .isThrownBy(() -> new SignOnAttemptGovernor(true, ALLOWANCE, Duration.ZERO,
                            REFUSAL, TRACKED_SUBJECTS, clock, meters));
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("a refusal that lifts immediately")
                    .isThrownBy(() -> new SignOnAttemptGovernor(true, ALLOWANCE, WINDOW,
                            Duration.ofSeconds(-1), TRACKED_SUBJECTS, clock, meters));
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("a ceiling that tracks nothing")
                    .isThrownBy(() -> new SignOnAttemptGovernor(true, ALLOWANCE, WINDOW, REFUSAL,
                            0, clock, meters));
            assertThatExceptionOfType(NullPointerException.class)
                    .as("no clock, without which no window could be measured")
                    .isThrownBy(() -> new SignOnAttemptGovernor(true, ALLOWANCE, WINDOW, REFUSAL,
                            TRACKED_SUBJECTS, null, meters));
            assertThatExceptionOfType(NullPointerException.class)
                    .as("nowhere to count, without which the protection would be invisible")
                    .isThrownBy(() -> new SignOnAttemptGovernor(true, ALLOWANCE, WINDOW, REFUSAL,
                            TRACKED_SUBJECTS, clock, null));
        }
    }

    @Nested
    @DisplayName("the transaction, with the allowance and the equalised path in place")
    class TheTransaction {

        /** The credential master, doubled so a not-found outcome is exact. */
        private UserSecurityRepository repository;

        /** The digest verifier, doubled so what it is asked to do is observable. */
        private CredentialDigestService digests;

        /** The clock this slice moves. */
        private MovableClock clock;

        /** Where the governor's counters land. */
        private SimpleMeterRegistry meters;

        /** The governor the service consults. */
        private SignOnAttemptGovernor governor;

        /** Subject under test. */
        private AuthenticationService subject;

        /** Creates the slice. */
        TheTransaction() {
            // Intentionally empty.
        }

        /** Wires the transaction over its doubles and a real governor. */
        @BeforeEach
        void wireTheTransaction() {
            repository = mock(UserSecurityRepository.class);
            digests = mock(CredentialDigestService.class);
            clock = new MovableClock(START);
            meters = new SimpleMeterRegistry();
            governor = new SignOnAttemptGovernor(true, ALLOWANCE, WINDOW, REFUSAL,
                    TRACKED_SUBJECTS, clock, meters);
            subject = new AuthenticationService(repository, digests, new NavigationService(),
                    new MessageCatalogService(), clock, governor);
            when(digests.encode(any(CharSequence.class))).thenReturn(STORED_DIGEST_STANDIN);
        }

        @Test
        @DisplayName("THE TIMING ORACLE IS CLOSED: the not-found path performs a digest verification "
                + "too, so it is never measurably cheaper than the wrong-secret path")
        void theNotFoundPathPerformsAVerification() {
            when(repository.findById(UNKNOWN_ID)).thenReturn(Optional.empty());

            final AuthenticationService.SignOnScreen screen =
                    subject.handle(KeyAction.ENTER, UNKNOWN_ID, PRESENTED, SOURCE);

            assertThat(screen.decision())
                    .as("the outcome and therefore the message text is unchanged, because both sign-on "
                            + "literals are frozen external contract")
                    .isEqualTo(AuthenticationService.Decision.USER_NOT_FOUND);
            verify(digests, atLeastOnce()).matches(any(CharSequence.class), anyString());
        }

        @Test
        @DisplayName("and the two outcomes still carry different decisions, so the explicit distinction "
                + "the contract requires is bounded rather than removed")
        void theTwoOutcomesStillCarryDifferentDecisions() {
            when(repository.findById(UNKNOWN_ID)).thenReturn(Optional.empty());
            when(repository.findById(KNOWN_ID)).thenReturn(Optional.of(storedRecord()));
            when(digests.matches(any(CharSequence.class), anyString())).thenReturn(false);

            final AuthenticationService.SignOnScreen absent =
                    subject.handle(KeyAction.ENTER, UNKNOWN_ID, PRESENTED, SOURCE);
            final AuthenticationService.SignOnScreen wrong =
                    subject.handle(KeyAction.ENTER, KNOWN_ID, PRESENTED, SOURCE);

            assertThat(absent.decision())
                    .isEqualTo(AuthenticationService.Decision.USER_NOT_FOUND);
            assertThat(wrong.decision())
                    .as("the wrong-secret literal is a different frozen literal, and the parity "
                            + "exception is recorded in the decision log rather than resolved by "
                            + "flattening the two")
                    .isEqualTo(AuthenticationService.Decision.WRONG_PASSWORD);
        }

        @Test
        @DisplayName("once the allowance is spent the turn reads NOTHING and verifies NOTHING, which is "
                + "what makes the refusal worth having: it is the work that was being spent")
        void anExhaustedAllowanceDoesNoWorkAtAll() {
            when(repository.findById(anyString())).thenReturn(Optional.empty());
            for (int attempt = 0; attempt < ALLOWANCE; attempt++) {
                subject.handle(KeyAction.ENTER, "SWEEP" + String.format(Locale.ROOT, "%03d", attempt),
                        PRESENTED, SOURCE);
            }
            reset(repository, digests);
            when(digests.encode(any(CharSequence.class))).thenReturn(STORED_DIGEST_STANDIN);

            final AuthenticationService.SignOnScreen refused =
                    subject.handle(KeyAction.ENTER, KNOWN_ID, PRESENTED, SOURCE);

            assertThat(refused.decision())
                    .as("the caller is told the screen's existing catch-all rather than a new text: no "
                            + "literal may be minted, and a distinct answer would itself be an oracle "
                            + "telling a sweeping caller when to pause and where to resume")
                    .isEqualTo(AuthenticationService.Decision.UNABLE_TO_VERIFY);
            verify(repository, never()).findById(anyString());
            verify(digests, never()).matches(any(CharSequence.class), anyString());
        }

        @Test
        @DisplayName("a store failure is not counted as a failed attempt, so an outage cannot lock every "
                + "operator out for the outage plus the refusal period")
        void aStoreFailureIsNotCountedAgainstTheCaller() {
            when(repository.findById(anyString()))
                    .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("down"));

            for (int attempt = 0; attempt < ALLOWANCE * 2; attempt++) {
                assertThat(subject.handle(KeyAction.ENTER, KNOWN_ID, PRESENTED, SOURCE).decision())
                        .isEqualTo(AuthenticationService.Decision.UNABLE_TO_VERIFY);
            }

            assertThat(governor.isRefusing(KNOWN_ID, SOURCE))
                    .as("nothing about the caller was learned from the store being unavailable")
                    .isFalse();
        }

        @Test
        @DisplayName("a turn that supplied no identifier or no secret is not counted, because it "
                + "presented no credential to be wrong about")
        void anIncompleteSubmissionIsNotCounted() {
            for (int attempt = 0; attempt < ALLOWANCE * 2; attempt++) {
                subject.handle(KeyAction.ENTER, "", "", SOURCE);
                subject.handle(KeyAction.ENTER, KNOWN_ID, "   ", SOURCE);
            }

            assertThat(governor.isRefusing(KNOWN_ID, SOURCE))
                    .as("an operator submitting an empty form must not exhaust their own allowance")
                    .isFalse();
            verify(repository, never()).findById(anyString());
        }

        @Test
        @DisplayName("an admitted turn clears the count, so a few mistypes before a correct secret cost "
                + "the operator nothing later")
        void anAdmittedTurnClearsTheCount() {
            when(repository.findById(KNOWN_ID)).thenReturn(Optional.of(storedRecord()));
            when(digests.matches(any(CharSequence.class), anyString())).thenReturn(false);
            for (int attempt = 0; attempt < ALLOWANCE - 1; attempt++) {
                subject.handle(KeyAction.ENTER, KNOWN_ID, PRESENTED, SOURCE);
            }

            when(digests.matches(any(CharSequence.class), anyString())).thenReturn(true);
            assertThat(subject.handle(KeyAction.ENTER, KNOWN_ID, PRESENTED, SOURCE).decision())
                    .isEqualTo(AuthenticationService.Decision.ADMITTED);

            when(digests.matches(any(CharSequence.class), anyString())).thenReturn(false);
            assertThat(subject.handle(KeyAction.ENTER, KNOWN_ID, PRESENTED, SOURCE).decision())
                    .as("the failure after the admission is the first of a new window")
                    .isEqualTo(AuthenticationService.Decision.WRONG_PASSWORD);
            assertThat(governor.isRefusing(KNOWN_ID, SOURCE)).isFalse();
        }

        @Test
        @DisplayName("the refusal lifts by itself, so the transaction serves the identifier again once "
                + "the period has passed")
        void theTransactionServesTheIdentifierAgainAfterTheRefusalLifts() {
            when(repository.findById(KNOWN_ID)).thenReturn(Optional.of(storedRecord()));
            when(digests.matches(any(CharSequence.class), anyString())).thenReturn(false);
            for (int attempt = 0; attempt < ALLOWANCE; attempt++) {
                subject.handle(KeyAction.ENTER, KNOWN_ID, PRESENTED, SOURCE);
            }
            assertThat(subject.handle(KeyAction.ENTER, KNOWN_ID, PRESENTED, SOURCE).decision())
                    .isEqualTo(AuthenticationService.Decision.UNABLE_TO_VERIFY);

            clock.advance(REFUSAL.plusSeconds(1));

            assertThat(subject.handle(KeyAction.ENTER, KNOWN_ID, PRESENTED, SOURCE).decision())
                    .as("the identifier is served again, reaching the store and the verifier")
                    .isEqualTo(AuthenticationService.Decision.WRONG_PASSWORD);
        }

        @Test
        @DisplayName("no diagnostic of the refusal path names the identifier, the address or the "
                + "presented secret")
        void theRefusalNamesNothingSensitive() {
            when(repository.findById(anyString())).thenReturn(Optional.empty());
            for (int attempt = 0; attempt < ALLOWANCE + 1; attempt++) {
                subject.handle(KeyAction.ENTER, KNOWN_ID, PRESENTED, SOURCE);
            }

            assertThat(counted(meters, SignOnAttemptGovernor.ENGAGED_METRIC,
                    SignOnAttemptGovernor.IDENTITY_NAMESPACE))
                    .as("the transition is reported through a counter tagged by namespace only; the "
                            + "subject is never a tag value, because an enumerated identifier as a "
                            + "metric label is both a disclosure and unbounded cardinality")
                    .isEqualTo(1.0d);
            assertThat(meters.find(SignOnAttemptGovernor.ENGAGED_METRIC).counter().getId().getTags())
                    .as("exactly one tag, and it names a namespace rather than a subject")
                    .hasSize(1)
                    .allSatisfy(tag -> assertThat(tag.getValue())
                            .isIn(SignOnAttemptGovernor.IDENTITY_NAMESPACE,
                                    SignOnAttemptGovernor.SOURCE_NAMESPACE));
        }

        /**
         * A stored credential record for the identifier this slice uses.
         *
         * @return the record
         */
        private UserSecurity storedRecord() {
            return TestDataFactory.userSecurity()
                    .userId(KNOWN_ID)
                    .firstName("Test")
                    .lastName("Operator")
                    .userTypeCode("U")
                    .storedDigest(TestDataFactory.SYNTHETIC_BCRYPT_DIGEST)
                    .build();
        }
    }
}
