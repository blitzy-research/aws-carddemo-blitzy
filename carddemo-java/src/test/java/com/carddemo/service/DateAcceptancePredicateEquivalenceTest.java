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
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import com.carddemo.domain.CardCrossReference;
import com.carddemo.domain.Transaction;
import com.carddemo.domain.enums.DateFormat;
import com.carddemo.domain.enums.KeyAction;
import com.carddemo.repository.CardCrossReferenceRepository;
import com.carddemo.repository.TransactionRepository;

/**
 * Equivalence specification for the two-level date-acceptance test, which the migration delivers three
 * times over.
 *
 * <h2>Why this file exists</h2>
 * The legacy applies one acceptance test to the date subprogram's result block, at
 * {@code [app/cbl/CORPT00C.cbl:L396-L406, L416-L426]} and again at
 * {@code [app/cbl/COTRN02C.cbl:L397, L417]}: the zero severity is accepted outright, and otherwise the
 * result is accepted anyway when — and only when — the message number is the one tolerated value. The
 * migration carries that rule in three separate places:
 * <ul>
 *   <li>{@link DateValidationService#isDateAcceptable} — public, and the one a caller can reach;</li>
 *   <li>a private static copy inside {@link ReportRequestService}, reached only through a report
 *       turn;</li>
 *   <li>a private static copy inside {@link TransactionAddService}, reached only through an add
 *       turn.</li>
 * </ul>
 *
 * <p>Each copy had its own tests, and each of those tests proved its own copy correct. None of them
 * compared the three with one another, so the three could drift apart one at a time and every suite
 * would stay green: a copy that lost its second level would still pass a test that only fed it the
 * zero severity, and a copy that inverted its tolerated-number comparison would still pass a test that
 * only fed it a refused block. This file is the comparison those suites were missing. It feeds one
 * table through all three and requires a single verdict.
 *
 * <h2>Why the duplication is not simply removed</h2>
 * Centralising the two private copies on the public predicate is the other way to close the gap, and it
 * is deliberately not taken. The add service's own suite pins that it does <em>not</em> delegate —
 * {@code TransactionAddServiceTest} verifies {@code isDateAcceptable} is never called — because the
 * legacy applies the test inline in each program rather than calling a shared subroutine, and the
 * invocation counts offered to the subprogram are themselves part of what that suite pins. Delegation
 * would change an asserted interaction contract in order to remove a duplication that a test can hold
 * together instead. So the duplication stays and this specification is what keeps it honest.
 *
 * <h2>The table</h2>
 * A full cross product of seven four-character severity codes and eight four-character message
 * numbers, run twice over two different feedback constants — fifty-six combinations, one hundred and
 * twelve rows. The severities include the accepted zero, five non-zero values and an all-blank field;
 * the message numbers include the tolerated value, the near-miss transposition {@code 2153}, four other
 * real subprogram numbers, an all-blank field and a value no subprogram emits. Every combination is
 * legal input: both fields are four bytes wide, which is what the result block declares, so no row is
 * rejected by the record before a predicate sees it.
 *
 * <p>The feedback constant is the third dimension and it is there to prove a negative. The result block
 * also carries an enum whose {@code numericSeverity()} is zero for a valid date and three otherwise,
 * and a copy that read <em>that</em> instead of the four-character severity view would pass a suite
 * whose fixtures always kept the two consistent. Here they are deliberately inconsistent: the same
 * fifty-six combinations run once under a feedback of severity zero and once under a feedback of
 * severity three, and the verdict must not move.
 *
 * <h2>The oracle</h2>
 * The expected verdict is not taken from any of the three copies. It is restated in this file from the
 * legacy rule, over test-local literals, as
 * {@code "0000".equals(severity) || "2513".equals(messageNumber)}. Three copies agreeing with one
 * another proves only that they are the same; agreeing with an independently authored oracle is what
 * proves they are right. Both are asserted, because they fail differently: a shared drift breaks the
 * oracle comparison in all three rows at once, and a single-copy drift breaks a pairwise comparison and
 * names which copy moved.
 *
 * <h2>Harness</h2>
 * A surefire unit test: no Spring context, no container, no database, no queue and no port. The public
 * predicate is exercised on a real {@link DateValidationService}, since it needs no collaborator. Each
 * private copy is exercised through its service's public entry point over Mockito doubles, because that
 * is the only way to reach a private static method without reflection, and this module's reflection
 * budget is zero. Both turns are driven on a fixed clock.
 *
 * <p>Each turn is also observed through more than one signal, so the verdict cannot be read off a
 * single field that happened to move: an accepted report turn publishes its full card stream and
 * carries no field error, an accepted add turn yields a record and carries no field error, and the
 * refusals are the negation of each. The number of dates offered to the subprogram is counted too,
 * because acceptance is what lets a turn go on to its second date: both services must offer two dates
 * when the first is accepted and stop at one when it is not, and that count agreeing across the two
 * services is a property no single-service suite could state.
 *
 * <p>Provenance: legacy checkout SHA {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release
 * stamp {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. No legacy source line is transcribed
 * here; the two codes and the rule that reads them are the contract this file pins, and they are
 * declared as test constants.
 */
@DisplayName("Date acceptance - the three copies of the severity and message-number test agree")
final class DateAcceptancePredicateEquivalenceTest {

    // ==================================================================================================
    // The oracle, authored here. The production copies hold these two values privately, three times
    // over; restating them once in the test is what makes the comparison independent of all three.
    // ==================================================================================================

    /** The severity that is accepted outright, whatever message number accompanies it. */
    private static final String ORACLE_ACCEPTED_SEVERITY = "0000";

    /** The one message number that is accepted despite a non-zero severity. */
    private static final String ORACLE_TOLERATED_MESSAGE_NUMBER = "2513";

    /** Declared byte width of each of the two code fields in the result block. */
    private static final int CODE_WIDTH = 4;

    /**
     * The severity column: the accepted value, five non-zero values and a field the terminal left blank.
     *
     * <p>{@code 0003} is the value the subprogram actually returns for every non-valid outcome. The other
     * four non-zero values are there so that no copy can be passing by treating one specific non-zero
     * code as the only failure, and the blank field is there because an unset four-byte code is not the
     * accepted value either.
     */
    private static final List<String> SEVERITY_CODES =
            List.of("0000", "0001", "0003", "0004", "0008", "0012", "    ");

    /**
     * The message-number column: the tolerated value, its digit transposition, four other numbers the
     * subprogram emits, a number none emits, and a field the terminal left blank.
     *
     * <p>The transposition is the row that matters most. A comparison written against a prefix, a
     * substring or a numeric parse rather than the whole four-character value could accept {@code 2153}
     * or refuse {@code 2513}, and only a near miss in the table can tell those apart.
     */
    private static final List<String> MESSAGE_NUMBERS =
            List.of("0000", "2153", "2507", "2508", "2513", "2517", "9999", "    ");

    /**
     * The two feedback constants the table runs under: one whose numeric severity is zero and one whose
     * numeric severity is not. Holding the four-character codes fixed while this moves is what proves no
     * copy is reading the enum instead of the code.
     */
    private static final List<DateValidationService.DateFeedback> FEEDBACK_CONSTANTS =
            List.of(DateValidationService.DateFeedback.DATE_IS_VALID,
                    DateValidationService.DateFeedback.BAD_DATE_VALUE);

    // ==================================================================================================
    // Result-block components that are not read by any of the three copies, at their declared widths.
    // ==================================================================================================

    /** Fifteen-character outcome text. Present because the block declares it; never read. */
    private static final String BLOCK_RESULT_TEXT = "Tabled outcome" + " ";

    /** Ten-character tested date. Present because the block declares it; never read. */
    private static final String BLOCK_TESTED_DATE = "2022-01-01";

    // ==================================================================================================
    // The two turns. Every value is a fixture: none is an expected value, because the only expectation
    // this file makes is the verdict, and that comes from the oracle above.
    // ==================================================================================================

    /** The instant both turns are stamped with, so neither reads the host clock. */
    private static final Clock PINNED_CLOCK =
            Clock.fixed(Instant.parse("2022-06-10T19:27:53Z"), ZoneOffset.UTC);

    /** How many cards a complete submission publishes, which is what an accepted report turn reaches. */
    private static final int SUBMISSION_CARD_COUNT = 17;

    /** A whitespace-free US-ASCII submission identity, which is all the bridge's outcome requires. */
    private static final String ORACLE_SUBMISSION_ID = "date-acceptance-equivalence-oracle";

    /** The marker a selected 3270 radio-style field arrives as. */
    private static final String SELECTED = "Y";

    /** The affirmative confirmation, so both turns are submitted rather than merely displayed. */
    private static final String CONFIRMED = "Y";

    private static final String ACCOUNT_ID = "00000000011";

    private static final String CARD_NUMBER = "4111111111111111";

    private static final String CUSTOMER_ID = "000000011";

    private static final String TRANSACTION_AMOUNT = "-00000100.00";

    private static final String ORIGINATION_DATE = "2022-07-19";

    private static final String PROCESSING_DATE = "2022-07-20";

    /**
     * How many dates a turn offers the subprogram when the first one is accepted. Both services offer an
     * origination-style date and then a processing-style one, and both stop at the first refusal.
     */
    private static final int DATES_OFFERED_WHEN_ACCEPTED = 2;

    /** How many dates a turn offers when the first is refused: the refusal ends the validation task. */
    private static final int DATES_OFFERED_WHEN_REFUSED = 1;

    // ==================================================================================================
    // The specification
    // ==================================================================================================

    /**
     * Every legal severity and message-number pairing, under both feedback constants.
     *
     * @return one argument set per combination, in a stable order
     */
    static List<Arguments> everySeverityAndMessageNumberPairing() {
        final List<Arguments> rows = new ArrayList<>();
        for (final DateValidationService.DateFeedback feedback : FEEDBACK_CONSTANTS) {
            for (final String severityCode : SEVERITY_CODES) {
                for (final String messageNumber : MESSAGE_NUMBERS) {
                    rows.add(Arguments.of(feedback, severityCode, messageNumber));
                }
            }
        }
        return rows;
    }

    @ParameterizedTest(name = "[{index}] feedback {0}, severity [{1}], message number [{2}]")
    @MethodSource("everySeverityAndMessageNumberPairing")
    @DisplayName("the public predicate, the report turn and the add turn reach the same verdict as the "
            + "oracle, and offer the same number of dates, for every combination")
    void theThreeCopiesAgreeWithOneAnotherAndWithTheOracle(
            final DateValidationService.DateFeedback feedback, final String severityCode,
            final String messageNumber) {
        final DateValidationService.SubprogramResult block =
                resultBlock(feedback, severityCode, messageNumber);
        final boolean oracleAccepts = ORACLE_ACCEPTED_SEVERITY.equals(severityCode)
                || ORACLE_TOLERATED_MESSAGE_NUMBER.equals(messageNumber);
        final int expectedDatesOffered =
                oracleAccepts ? DATES_OFFERED_WHEN_ACCEPTED : DATES_OFFERED_WHEN_REFUSED;

        final boolean publicVerdict = new DateValidationService().isDateAcceptable(block);
        final ObservedTurn report = observeReportTurn(block);
        final ObservedTurn add = observeAddTurn(block);

        assertAll(
                () -> assertThat(publicVerdict)
                        .as("the public predicate must reach the verdict the legacy rule reaches for "
                                + "severity [%s] and message number [%s]", severityCode, messageNumber)
                        .isEqualTo(oracleAccepts),
                () -> assertThat(report.accepted())
                        .as("the report service's own copy of the rule must reach the same verdict")
                        .isEqualTo(oracleAccepts),
                () -> assertThat(add.accepted())
                        .as("the add service's own copy of the rule must reach the same verdict")
                        .isEqualTo(oracleAccepts),
                () -> assertThat(report.accepted())
                        .as("the report service's copy has drifted from the public predicate, which is "
                                + "the divergence three separately tested copies of one rule invite")
                        .isEqualTo(publicVerdict),
                () -> assertThat(add.accepted())
                        .as("the add service's copy has drifted from the report service's, so two "
                                + "call sites of one legacy rule now decide differently")
                        .isEqualTo(report.accepted()),
                () -> assertThat(report.noFieldError())
                        .as("an accepted date leaves the report turn with no field error, and a "
                                + "refused one raises exactly the date's own")
                        .isEqualTo(oracleAccepts),
                () -> assertThat(report.workProduced())
                        .as("an accepted date lets the report turn publish its whole card stream")
                        .isEqualTo(oracleAccepts),
                () -> assertThat(add.noFieldError())
                        .as("an accepted date leaves the add turn with no field error")
                        .isEqualTo(oracleAccepts),
                () -> assertThat(add.workProduced())
                        .as("an accepted date lets the add turn write its record")
                        .isEqualTo(oracleAccepts),
                () -> assertThat(report.datesOffered())
                        .as("acceptance is what lets a turn go on to its second date, so the report "
                                + "turn must offer %d", expectedDatesOffered)
                        .isEqualTo(expectedDatesOffered),
                () -> assertThat(add.datesOffered())
                        .as("the add turn must offer the same number of dates as the report turn for "
                                + "the same verdict, which is the count the legacy makes")
                        .isEqualTo(expectedDatesOffered));
    }

    @Test
    @DisplayName("the same four-character codes decide the same way under both feedback constants, so "
            + "no copy is reading the enum's numeric severity instead of the code")
    void noCopyReadsTheEnumDerivedSeverityInsteadOfTheCode() {
        final DateValidationService.SubprogramResult zeroCodeWithFailingFeedback = resultBlock(
                DateValidationService.DateFeedback.BAD_DATE_VALUE, ORACLE_ACCEPTED_SEVERITY, "2508");
        final DateValidationService.SubprogramResult nonZeroCodeWithValidFeedback = resultBlock(
                DateValidationService.DateFeedback.DATE_IS_VALID, "0003", "2508");

        final boolean publicOnZeroCode =
                new DateValidationService().isDateAcceptable(zeroCodeWithFailingFeedback);
        final boolean publicOnNonZeroCode =
                new DateValidationService().isDateAcceptable(nonZeroCodeWithValidFeedback);

        assertAll(
                () -> assertThat(zeroCodeWithFailingFeedback.numericSeverity())
                        .as("the block's enum reports a non-zero severity while its code field reports "
                                + "the accepted one, which is the disagreement this test needs")
                        .isNotZero(),
                () -> assertThat(nonZeroCodeWithValidFeedback.numericSeverity())
                        .as("and here the enum reports zero while the code field does not")
                        .isZero(),
                () -> assertThat(publicOnZeroCode)
                        .as("the code field decides, so the accepted code is accepted however the "
                                + "enum reads")
                        .isTrue(),
                () -> assertThat(observeReportTurn(zeroCodeWithFailingFeedback).accepted()).isTrue(),
                () -> assertThat(observeAddTurn(zeroCodeWithFailingFeedback).accepted()).isTrue(),
                () -> assertThat(publicOnNonZeroCode)
                        .as("and a non-zero code carrying an untolerated number is refused however "
                                + "the enum reads")
                        .isFalse(),
                () -> assertThat(observeReportTurn(nonZeroCodeWithValidFeedback).accepted()).isFalse(),
                () -> assertThat(observeAddTurn(nonZeroCodeWithValidFeedback).accepted()).isFalse());
    }

    @Test
    @DisplayName("the table is a full cross product at the declared code width and covers all four "
            + "quadrants of the two-level rule")
    void theTableIsAFullCrossProductCoveringAllFourQuadrants() {
        final List<Arguments> rows = everySeverityAndMessageNumberPairing();
        final int expectedRows =
                FEEDBACK_CONSTANTS.size() * SEVERITY_CODES.size() * MESSAGE_NUMBERS.size();

        int acceptedBySeverity = 0;
        int acceptedByMessageNumber = 0;
        int acceptedByBoth = 0;
        int refused = 0;
        for (final Arguments row : rows) {
            final String severityCode = (String) row.get()[1];
            final String messageNumber = (String) row.get()[2];
            final boolean severityAccepts = ORACLE_ACCEPTED_SEVERITY.equals(severityCode);
            final boolean messageNumberAccepts = ORACLE_TOLERATED_MESSAGE_NUMBER.equals(messageNumber);
            if (severityAccepts && messageNumberAccepts) {
                acceptedByBoth++;
            } else if (severityAccepts) {
                acceptedBySeverity++;
            } else if (messageNumberAccepts) {
                acceptedByMessageNumber++;
            } else {
                refused++;
            }
        }

        final int quadrantAcceptedBySeverity = acceptedBySeverity;
        final int quadrantAcceptedByMessageNumber = acceptedByMessageNumber;
        final int quadrantAcceptedByBoth = acceptedByBoth;
        final int quadrantRefused = refused;
        assertAll(
                () -> assertThat(rows)
                        .as("the table must stay a full cross product; a row quietly dropped is a "
                                + "combination quietly no longer compared across the three copies")
                        .hasSize(expectedRows),
                () -> assertThat(SEVERITY_CODES).doesNotHaveDuplicates()
                        .allSatisfy(code -> assertThat(code).hasSize(CODE_WIDTH)),
                () -> assertThat(MESSAGE_NUMBERS).doesNotHaveDuplicates()
                        .allSatisfy(number -> assertThat(number).hasSize(CODE_WIDTH)),
                () -> assertThat(SEVERITY_CODES)
                        .as("the accepted severity must be in the table, or the first level of the "
                                + "rule is never exercised")
                        .contains(ORACLE_ACCEPTED_SEVERITY),
                () -> assertThat(MESSAGE_NUMBERS)
                        .as("the tolerated message number must be in the table, or the second level "
                                + "of the rule is never exercised")
                        .contains(ORACLE_TOLERATED_MESSAGE_NUMBER),
                () -> assertThat(quadrantAcceptedByBoth)
                        .as("accepted on both levels at once")
                        .isPositive(),
                () -> assertThat(quadrantAcceptedBySeverity)
                        .as("accepted on the severity alone, which is the level a copy that lost its "
                                + "second test would still pass")
                        .isPositive(),
                () -> assertThat(quadrantAcceptedByMessageNumber)
                        .as("accepted on the tolerated number alone despite a non-zero severity, "
                                + "which is the level a collapsed single-field copy would fail")
                        .isPositive(),
                () -> assertThat(quadrantRefused)
                        .as("refused on both levels")
                        .isPositive());
    }

    // ==================================================================================================
    // Observation helpers. Each drives one service's public entry point, which is the only way to reach
    // a private static predicate without reflection.
    // ==================================================================================================

    /**
     * What one turn reveals about the verdict its private copy of the rule reached.
     *
     * @param accepted     whether the turn treated the date as acceptable
     * @param noFieldError whether the turn ended with no field error at all
     * @param workProduced whether the turn went on to produce its output
     * @param datesOffered how many dates the turn offered the subprogram
     */
    private record ObservedTurn(boolean accepted, boolean noFieldError, boolean workProduced,
            int datesOffered) {
    }

    /**
     * Drives one confirmed operator-range report turn whose every date validation answers the given
     * block, and reports what the turn reveals about the verdict.
     *
     * @param  block the result block the subprogram double returns for every date
     * @return the observation
     */
    private static ObservedTurn observeReportTurn(final DateValidationService.SubprogramResult block) {
        final DateValidationService dateValidation = mock(DateValidationService.class);
        final JobSubmissionService jobSubmission = mock(JobSubmissionService.class);
        final AtomicInteger datesOffered = new AtomicInteger();

        when(dateValidation.validateDate(anyString(), any(DateFormat.class))).thenAnswer(invocation -> {
            datesOffered.incrementAndGet();
            return block;
        });
        when(jobSubmission.submitCanonicalJobImage(any(), any()))
                .thenReturn(new JobSubmissionService.SubmissionResult(ORACLE_SUBMISSION_ID,
                        SUBMISSION_CARD_COUNT, SUBMISSION_CARD_COUNT, false, ""));

        final ReportRequestService service = new ReportRequestService(dateValidation, jobSubmission,
                mock(MessageCatalogService.class), mock(NavigationService.class), PINNED_CLOCK,
                new ReportRetryTokenService(null, PINNED_CLOCK, new SimpleMeterRegistry()));
        final ReportRequestService.ReportRequestResult result =
                service.processReportRequest(confirmedCustomRangeTurn());

        return new ObservedTurn(!result.errorFlag(), result.fieldErrors().isEmpty(),
                result.cardsPublished() == SUBMISSION_CARD_COUNT, datesOffered.get());
    }

    /**
     * Drives one confirmed transaction-add turn whose every date validation answers the given block, and
     * reports what the turn reveals about the verdict.
     *
     * <p>The lock-read-insert unit is run inline so that an accepted turn actually reaches its insert;
     * everything downstream of the date test is stubbed to succeed, so the only thing that can refuse
     * the turn is the acceptance rule under specification.
     *
     * @param  block the result block the subprogram double returns for every date
     * @return the observation
     */
    private static ObservedTurn observeAddTurn(final DateValidationService.SubprogramResult block) {
        final DateValidationService dateValidation = mock(DateValidationService.class);
        final TransactionRepository transactionRepository = mock(TransactionRepository.class);
        final CardCrossReferenceRepository crossReferenceRepository =
                mock(CardCrossReferenceRepository.class);
        final OnlineTransactionBoundary transactionBoundary = mock(OnlineTransactionBoundary.class);
        final AtomicInteger datesOffered = new AtomicInteger();

        when(dateValidation.validateDate(anyString(), any(DateFormat.class))).thenAnswer(invocation -> {
            datesOffered.incrementAndGet();
            return block;
        });
        when(crossReferenceRepository.findFirstByXrefAcctIdOrderByXrefCardNumAsc(ACCOUNT_ID))
                .thenReturn(Optional.of(new CardCrossReference(CARD_NUMBER, CUSTOMER_ID, ACCOUNT_ID)));
        when(transactionBoundary.<Transaction>execute(any())).thenAnswer(invocation -> {
            final Supplier<Transaction> unit = invocation.getArgument(0);
            return unit.get();
        });
        when(transactionRepository.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 1), 0));
        when(transactionRepository.insertAndFlush(any(Transaction.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        final TransactionAddService service = new TransactionAddService(dateValidation,
                mock(MessageCatalogService.class), mock(NavigationService.class), transactionRepository,
                crossReferenceRepository, transactionBoundary, PINNED_CLOCK);
        final TransactionAddService.TransactionAddResult result =
                service.processTransactionAdd(confirmedAddTurn());

        return new ObservedTurn(result.transactionAdded(), result.fieldErrors().isEmpty(),
                result.transaction() != null, datesOffered.get());
    }

    // ==================================================================================================
    // Fixtures
    // ==================================================================================================

    /**
     * Assembles a result block at the component widths the record declares, from the two codes the rule
     * reads and a feedback constant the rule must ignore.
     *
     * <p>The feedback is deliberately allowed to disagree with the two codes, which is the whole point of
     * the third table dimension. The remaining components are fixtures at their declared widths.
     *
     * @param  feedback      the outcome constant, whose numeric severity no copy of the rule may read
     * @param  severityCode  the four-character severity view
     * @param  messageNumber the four-character message-number view
     * @return the result block
     */
    private static DateValidationService.SubprogramResult resultBlock(
            final DateValidationService.DateFeedback feedback, final String severityCode,
            final String messageNumber) {
        return new DateValidationService.SubprogramResult(feedback, severityCode, messageNumber,
                BLOCK_RESULT_TEXT, BLOCK_TESTED_DATE, DateFormat.YYYY_MM_DD.getValue());
    }

    /**
     * A confirmed operator-supplied range turn, which is the report path that validates two dates.
     *
     * @return the report screen input
     */
    private static ReportRequestService.ReportScreenInput confirmedCustomRangeTurn() {
        return new ReportRequestService.ReportScreenInput(null, null, SELECTED, "01", "01", "2022",
                "12", "31", "2022", CONFIRMED, KeyAction.ENTER,
                ConversationState.empty().withReEntry());
    }

    /**
     * A confirmed, fully populated add turn keyed on the account identifier, which is the add path that
     * validates two dates.
     *
     * @return the add screen input
     */
    private static TransactionAddService.TransactionAddScreenInput confirmedAddTurn() {
        return new TransactionAddService.TransactionAddScreenInput(ACCOUNT_ID, null, "01", "0001",
                "POS TERM", "PURCHASE AT MERCHANT", TRANSACTION_AMOUNT, ORIGINATION_DATE,
                PROCESSING_DATE, "000000001", "MERCHANT NAME", "MERCHANT CITY", "10001", CONFIRMED,
                null, KeyAction.ENTER, addScreenState());
    }

    /**
     * The re-submission navigation state the add turn arrives with.
     *
     * @return the navigation state
     */
    private static ScreenNavigationState addScreenState() {
        return new ScreenNavigationState("CT02", "COTRN02C", "CT02", "COTRN02C", "USER0001", "U",
                ScreenNavigationState.ProgramContext.REENTER, null, null, null, null, null, null,
                null, "COTRN2A", "COTRN02");
    }
}
