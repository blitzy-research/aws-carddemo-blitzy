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
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

import com.carddemo.domain.enums.DateFormat;
import com.carddemo.domain.enums.KeyAction;
import com.carddemo.domain.enums.ReportPeriod;
import com.carddemo.exception.JobSubmissionException;
import com.carddemo.exception.ValidationException;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * Unit test for {@link ReportRequestService}, the translation of {@code app/cbl/CORPT00C.cbl} —
 * legacy CICS transaction {@code CR00}, 649 source lines and 10 procedure-division paragraphs, the
 * online half of the estate's single online-to-batch bridge.
 *
 * <h2>Harness</h2>
 * A surefire unit test: no Spring context, no container, no database, no queue client, no port and no
 * network. All four collaborators — the date-validation subprogram, the queue bridge, the
 * common-message catalogue and the navigation rules — are Mockito mocks, so every decision this
 * service makes is observed at its own boundary rather than through another class's behaviour. The
 * card builder is used through the service as a static contract and is never asked to supply an
 * expected value.
 *
 * <p>The clock is injected and fixed. The legacy derives both the month-to-date and the year-to-date
 * window from {@code FUNCTION CURRENT-DATE} at source lines 215 and 241 and stamps the screen header
 * from it at line 611, so an ambient time source would make three of this suite's assertions
 * unrepeatable. Nothing here reads the host clock.
 *
 * <h2>Every expected value is authored in this file</h2>
 * No expectation is produced by the class under test or by any production formatter, codec, template
 * or builder. The seventeen eighty-column card images are written out below as explicit literals with
 * their padding stated as an explicit repeat count, so each frame is countable by eye; the period
 * dates are declared rather than recomputed with the same arithmetic the service uses; and every
 * message literal and every padded catalogue value is declared as a test constant. Asking the card
 * builder for the expected cards would make the strongest assertion in this suite tautological.
 *
 * <h2>What the suite pins</h2>
 * <ul>
 *   <li><strong>The date-acceptance test is two-field, not boolean.</strong> The caller's overlay of
 *       the subprogram result carries a severity and a message number, and the source accepts a
 *       non-zero severity when — and only when — the message number is the tolerated one. Both halves
 *       are asserted independently, and the two non-zero outcomes are compared against each other,
 *       because a translation that collapsed them into one boolean would still pass a suite that
 *       asserted only one of them.</li>
 *   <li><strong>The submission is seventeen cards, sentinel included.</strong> The emitting loop
 *       raises its end-of-stream flag before writing the card that raised it, so the sentinel is
 *       transmitted. A translation that held it back as a loop terminator would publish sixteen.</li>
 *   <li><strong>A publish failure is not fatal.</strong> The queue is defined errors-ignored, so the
 *       failure is logged, the remaining cards are abandoned, the operator sees the queue-write text
 *       and the caller receives a value rather than an exception. No retry, back-off or delay
 *       exists to assert, and the absence is asserted as an invocation count.</li>
 *   <li><strong>The confirmation gate has three arms and they are not symmetrical.</strong> A blank
 *       confirmation publishes nothing at all, a declined one raises the error flag with no message
 *       text whatsoever, and only an affirmative one reaches the queue.</li>
 *   <li><strong>Message text is an external contract.</strong> Every literal is asserted byte for
 *       byte and untrimmed, including the one that capitalises {@code NOT} against surrounding mixed
 *       case and the one that carries a space before its three trailing dots.</li>
 * </ul>
 *
 * <p>Provenance: legacy checkout SHA {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream
 * release stamp {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. No legacy source line is
 * transcribed here; the card images, the widths, the offsets and the message texts are the external
 * contract this suite exists to pin, and they are declared as test constants.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ReportRequestService - the CR00 report-request turn")
class ReportRequestServiceTest {

    // ==============================================================================================
    // Pinned time. The module's local pinned instant, so a derived window is repeatable.
    // ==============================================================================================

    /** The module's pinned instant, which lands inside a thirty-day month. */
    private static final Instant PINNED_INSTANT = Instant.parse("2022-06-10T19:27:53Z");

    private static final Clock PINNED_CLOCK = Clock.fixed(PINNED_INSTANT, ZoneOffset.UTC);

    // ==============================================================================================
    // Contract widths and counts. Legacy contract values, never tuning parameters.
    // ==============================================================================================

    private static final int CARD_IMAGE_WIDTH = 80;

    private static final int SUBMISSION_CARD_COUNT = 17;

    /** The concatenated image is the seventeen frames end to end with nothing between them. */
    private static final int CONCATENATED_IMAGE_WIDTH = SUBMISSION_CARD_COUNT * CARD_IMAGE_WIDTH;

    private static final int DATE_SLOT_WIDTH = 10;

    private static final int COMMON_MESSAGE_WIDTH = 50;

    private static final int SCREEN_TITLE_WIDTH = 40;

    /** The outbound message field is narrower than the eighty-character work field behind it. */
    private static final int OUTBOUND_MESSAGE_WIDTH = 78;

    private static final int START_SORT_SYMBOL_SLOT_OFFSET = 18;

    private static final int END_SORT_SYMBOL_SLOT_OFFSET = 16;

    private static final int PARAMETER_START_SLOT_OFFSET = 0;

    private static final int PARAMETER_SEPARATOR_OFFSET = 10;

    private static final int PARAMETER_END_SLOT_OFFSET = 11;

    private static final int START_SORT_SYMBOL_CARD_INDEX = 10;

    private static final int END_SORT_SYMBOL_CARD_INDEX = 11;

    private static final int PARAMETER_CARD_INDEX = 14;

    // ==============================================================================================
    // The seventeen card images, authored here from the legacy card literals.
    //
    // Each frame is a literal plus an explicit repeat count, so the eighty columns are countable
    // without running anything. Nothing below is obtained from the production card builder.
    // ==============================================================================================

    private static final String CARD_01_JOB =
            "//TRNRPT00 JOB 'TRAN REPORT',CLASS=A,MSGCLASS=0," + " ".repeat(32);

    private static final String CARD_02_NOTIFY = "// NOTIFY=&SYSUID" + " ".repeat(63);

    private static final String CARD_COMMENT = "//*" + " ".repeat(77);

    private static final String CARD_04_JOBLIB =
            "//JOBLIB JCLLIB ORDER=('AWS.M2.CARDDEMO.PROC')" + " ".repeat(34);

    private static final String CARD_06_EXEC_PROC = "//STEP10 EXEC PROC=TRANREPT" + " ".repeat(53);

    private static final String CARD_08_SYMNAMES = "//STEP05R.SYMNAMES DD *" + " ".repeat(57);

    private static final String CARD_09_CARD_NUM_SYMBOL = "TRAN-CARD-NUM,263,16,ZD" + " ".repeat(57);

    private static final String CARD_10_PROC_DT_SYMBOL = "TRAN-PROC-DT,305,10,CH" + " ".repeat(58);

    private static final String START_SORT_SYMBOL_LEAD = "PARM-START-DATE,C'";

    private static final String END_SORT_SYMBOL_LEAD = "PARM-END-DATE,C'";

    private static final String CARD_IN_STREAM_TERMINATOR = "/*" + " ".repeat(78);

    private static final String CARD_14_DATEPARM = "//STEP10R.DATEPARM DD *" + " ".repeat(57);

    private static final String CARD_17_SENTINEL = "/*EOF" + " ".repeat(75);

    // ==============================================================================================
    // Message literals. Byte for byte, never trimmed, never respaced.
    // ==============================================================================================

    /**
     * The start-month emptiness text. <strong>{@code NOT} is capitalised</strong> where the
     * surrounding words are mixed case, and the three trailing dots carry no space before them. Both
     * are contractual.
     */
    private static final String MSG_START_MONTH_EMPTY = "Start Date - Month can NOT be empty...";

    private static final String MSG_START_DAY_EMPTY = "Start Date - Day can NOT be empty...";

    private static final String MSG_START_YEAR_EMPTY = "Start Date - Year can NOT be empty...";

    private static final String MSG_END_MONTH_EMPTY = "End Date - Month can NOT be empty...";

    private static final String MSG_END_DAY_EMPTY = "End Date - Day can NOT be empty...";

    private static final String MSG_END_YEAR_EMPTY = "End Date - Year can NOT be empty...";

    private static final String MSG_START_MONTH_INVALID = "Start Date - Not a valid Month...";

    private static final String MSG_START_DAY_INVALID = "Start Date - Not a valid Day...";

    private static final String MSG_START_YEAR_INVALID = "Start Date - Not a valid Year...";

    private static final String MSG_END_MONTH_INVALID = "End Date - Not a valid Month...";

    private static final String MSG_END_DAY_INVALID = "End Date - Not a valid Day...";

    private static final String MSG_END_YEAR_INVALID = "End Date - Not a valid Year...";

    /** The text a start date the subprogram refuses produces; note the lower-case final word. */
    private static final String MSG_START_DATE_INVALID = "Start Date - Not a valid date...";

    private static final String MSG_END_DATE_INVALID = "End Date - Not a valid date...";

    private static final String MSG_SELECT_REPORT_TYPE = "Select a report type to print report...";

    /**
     * The queue-write failure text, with <strong>exactly three</strong> trailing dots. Never two,
     * never four, and never the single ellipsis character.
     */
    private static final String MSG_UNABLE_TO_WRITE_TDQ = "Unable to Write TDQ (JOBS)...";

    /**
     * The acknowledgement suffix, which <strong>carries a space before its three dots</strong> where
     * every other text in this contract does not. The artefact is reproduced, not normalised.
     */
    private static final String FRAGMENT_SUBMITTED_SUFFIX = " report submitted for printing ...";

    private static final String FRAGMENT_CONFIRM_PREFIX = "Please confirm to print the ";

    private static final String FRAGMENT_CONFIRM_SUFFIX = " report...";

    private static final String FRAGMENT_INVALID_CONFIRM_SUFFIX =
            "\" is not a valid value to confirm...";

    /**
     * The common invalid-key message at its declared width: forty visible characters and ten trailing
     * spaces. The single space after the first full stop is part of the text.
     */
    private static final String PADDED_INVALID_KEY_MESSAGE =
            "Invalid key pressed. Please see below..." + " ".repeat(10);

    /**
     * The common thank-you message at its declared width: forty-three visible characters and seven
     * trailing spaces. This service never emits it, so it is asserted as a catalogue contract rather
     * than as a turn outcome.
     */
    private static final String PADDED_THANK_YOU_MESSAGE =
            "Thank you for using CardDemo application..." + " ".repeat(7);

    private static final String PADDED_TITLE_01 =
            " ".repeat(6) + "AWS Mainframe Modernization" + " ".repeat(7);

    private static final String PADDED_TITLE_02 = " ".repeat(14) + "CardDemo" + " ".repeat(18);

    // ==============================================================================================
    // Screen values, report names and the periods this clock derives.
    // ==============================================================================================

    private static final String SELECTED = "Y";

    private static final String CONFIRM_YES = "Y";

    /** The affirmative confirmation entry in lower case, which the source admits as well. */
    private static final String CONFIRM_YES_LOWER = "y";

    private static final String CONFIRM_NO = "N";

    private static final String CONFIRM_NO_LOWER = "n";

    private static final String CONFIRM_INADMISSIBLE = "Q";

    /** The month-to-date report name, as the result reports it delimited at its first space. */
    private static final String REPORT_NAME_MONTHLY = "Monthly";

    private static final String REPORT_NAME_YEARLY = "Yearly";

    private static final String REPORT_NAME_CUSTOM = "Custom";

    private static final String PINNED_MONTHLY_START = "2022-06-01";

    private static final String PINNED_MONTHLY_END = "2022-06-30";

    private static final String PINNED_YEARLY_START = "2022-01-01";

    private static final String PINNED_YEARLY_END = "2022-12-31";

    private static final String PINNED_HEADER_DATE = "06/10/22";

    private static final String PINNED_HEADER_TIME = "19:27:53";

    private static final String RE_ARMED_TRANSACTION_ID = "CR00";

    private static final String HEADER_PROGRAM_NAME = "CORPT00C";

    // ==============================================================================================
    // The subprogram result block, at the component widths its record declares.
    // ==============================================================================================

    private static final String SEVERITY_ACCEPTED = "0000";

    private static final String SEVERITY_ERROR = "0003";

    /** The one message number a non-zero severity is nevertheless accepted under. */
    private static final String MESSAGE_NUMBER_TOLERATED = "2513";

    private static final String MESSAGE_NUMBER_REFUSED = "2508";

    private static final String MESSAGE_NUMBER_NONE = "0000";

    /** The fifteen-character result text of an accepted date. */
    private static final String RESULT_TEXT_VALID = "Date is valid" + " ".repeat(2);

    /** The fifteen-character result text of a refused date. */
    private static final String RESULT_TEXT_ERROR = "Datevalue error";

    private static final String MASK_HYPHENATED = "YYYY-MM-DD";

    // ==============================================================================================
    // Field identities the two-state field contract reports.
    // ==============================================================================================

    /** The screen field the cursor falls back to, and the property the catch-all arm faults. */
    private static final String FIELD_MONTHLY = "MONTHLY";

    private static final String FIELD_CONFIRM = "CONFIRM";

    private static final String FIELD_START_MONTH = "SDTMM";

    private static final String FIELD_START_DAY = "SDTDD";

    private static final String FIELD_START_YEAR = "SDTYYYY";

    private static final String FIELD_END_MONTH = "EDTMM";

    private static final String FIELD_END_DAY = "EDTDD";

    private static final String FIELD_END_YEAR = "EDTYYYY";

    private static final String PROPERTY_REPORT_TYPE = "reportType";

    private static final String PROPERTY_START_MONTH = "startMonth";

    private static final String PROPERTY_START_DAY = "startDay";

    private static final String PROPERTY_START_YEAR = "startYear";

    private static final String PROPERTY_END_MONTH = "endMonth";

    private static final String PROPERTY_END_DAY = "endDay";

    private static final String PROPERTY_END_YEAR = "endYear";

    private static final String PROPERTY_START_DATE = "startDate";

    private static final String PROPERTY_END_DATE = "endDate";

    /** The program name of the dangling CICS program definition that has no source member. */
    private static final String DANGLING_PROGRAM_DEFINITION = "COCRDSEC";

    /** A submission identity the stubbed bridge echoes back; whitespace-free, as the bridge demands. */
    private static final String STUBBED_SUBMISSION_ID = "stubbed-submission-identity";

    private static final String RETRY_TOKEN = "report-request-retry-001";

    private static final String OTHER_TOKEN = "report-request-new-002";

    /**
     * One of the two operators the scoping assertions use, at the eight-character width of a user-record
     * key. It is an identifier and carries no credential of any kind.
     */
    private static final String FIRST_OPERATOR = "USER0001";

    /** The other operator, so that one token in two hands can be shown to be two submissions. */
    private static final String SECOND_OPERATOR = "ADMIN001";

    /**
     * The longest submission identity the bridge accepts once a card ordinal is appended to it.
     *
     * <p>The bridge bounds a deduplication identifier at 128 characters and composes it from the identity
     * plus a separator plus a one- or two-digit ordinal, so an identity comfortably inside this figure
     * cannot compose an over-long identifier. It is a protocol bound of the queue service, not a capacity
     * or latency figure.
     */
    private static final int MAX_SUBMISSION_IDENTITY_LENGTH = 120;

    /** The character a 3270 field the terminal did not transmit arrives as. */
    private static final char LOW_VALUE = '\0';

    // ==============================================================================================
    // Collaborators. All four are mocks; the clock is fixed.
    // ==============================================================================================

    @Mock
    private DateValidationService dateValidationService;

    /** The queue bridge. Mocked because publishing is a boundary, never a decision. */
    @Mock
    private JobSubmissionService jobSubmissionService;

    /** The common-message catalogue, whose padded values must pass through untrimmed. */
    @Mock
    private MessageCatalogService messageCatalogService;

    /** The navigation rules, which the service must consult rather than hardcoding a destination. */
    @Mock
    private NavigationService navigationService;

    @Captor
    private ArgumentCaptor<List<String>> publishedCardsCaptor;

    @Captor
    private ArgumentCaptor<String> submissionIdentityCaptor;

    @Captor
    private ArgumentCaptor<DateFormat> dateFormatCaptor;

    /** Captures the date the service offers the subprogram, so call order can be asserted. */
    @Captor
    private ArgumentCaptor<String> validatedDateCaptor;

    private ReportRequestService subject;

    /** The service's own logger, so a non-fatal failure can be proven to have been recorded. */
    private Logger serviceLogger;

    private Level previousLogLevel;

    private ListAppender<ILoggingEvent> logCapture;

    /**
     * Builds the service over the pinned clock and attaches the log capture.
     *
     * <p>The capture is attached here and detached in the paired teardown so that a test asserting on
     * a recorded diagnostic never observes another test's events and the logger is left as it was
     * found.
     */
    @BeforeEach
    void setUp() {
        subject = new ReportRequestService(dateValidationService, jobSubmissionService,
                messageCatalogService, navigationService, PINNED_CLOCK);

        serviceLogger = (Logger) LoggerFactory.getLogger(ReportRequestService.class);
        previousLogLevel = serviceLogger.getLevel();
        serviceLogger.setLevel(Level.TRACE);
        logCapture = new ListAppender<>();
        logCapture.start();
        serviceLogger.addAppender(logCapture);
    }

    @AfterEach
    void tearDown() {
        serviceLogger.detachAppender(logCapture);
        serviceLogger.setLevel(previousLogLevel);
        logCapture.stop();
    }

    @Test
    @DisplayName("the constructor refuses an absent collaborator and an absent clock rather than "
            + "standing up a service that would fail later in the turn")
    void theConstructorRefusesAnAbsentCollaborator() {
        assertAll(
                () -> assertThatExceptionOfType(NullPointerException.class)
                        .isThrownBy(() -> new ReportRequestService(null, jobSubmissionService,
                                messageCatalogService, navigationService, PINNED_CLOCK)),
                () -> assertThatExceptionOfType(NullPointerException.class)
                        .isThrownBy(() -> new ReportRequestService(dateValidationService, null,
                                messageCatalogService, navigationService, PINNED_CLOCK)),
                () -> assertThatExceptionOfType(NullPointerException.class)
                        .isThrownBy(() -> new ReportRequestService(dateValidationService,
                                jobSubmissionService, null, navigationService, PINNED_CLOCK)),
                () -> assertThatExceptionOfType(NullPointerException.class)
                        .isThrownBy(() -> new ReportRequestService(dateValidationService,
                                jobSubmissionService, messageCatalogService, null, PINNED_CLOCK)),
                () -> assertThatExceptionOfType(NullPointerException.class)
                        .as("an absent clock would make the derived windows read the host clock")
                        .isThrownBy(() -> new ReportRequestService(dateValidationService,
                                jobSubmissionService, messageCatalogService, navigationService,
                                null)));
        verifyNoInteractions(dateValidationService, jobSubmissionService, messageCatalogService,
                navigationService);
    }

    // ==============================================================================================
    // Test-side helpers. Every one of them is an oracle authored here; none delegates to production.
    // ==============================================================================================

    /**
     * Builds a service over a clock other than the pinned one, for the month-length matrix.
     *
     * @param  clock the clock the derived windows are to be read from
     * @return a service identical to the one under test except for its clock
     */
    private ReportRequestService serviceAt(final Clock clock) {
        return new ReportRequestService(dateValidationService, jobSubmissionService,
                messageCatalogService, navigationService, clock);
    }

    /**
     * A fixed clock at the given instant, expressed as a date and a time-of-day.
     *
     * @param  isoInstant the instant in ISO form
     * @return a fixed clock, never an ambient one
     */
    private static Clock clockAt(final String isoInstant) {
        return Clock.fixed(Instant.parse(isoInstant), ZoneOffset.UTC);
    }

    private static ConversationState reEntry() {
        return ConversationState.empty().withReEntry();
    }

    /**
     * Assembles one submitted turn.
     *
     * @param  monthly    the month-to-date marker
     * @param  yearly     the year-to-date marker
     * @param  custom     the operator-range marker
     * @param  startMonth the start month as transmitted
     * @param  startDay   the start day as transmitted
     * @param  startYear  the start year as transmitted
     * @param  endMonth   the end month as transmitted
     * @param  endDay     the end day as transmitted
     * @param  endYear    the end year as transmitted
     * @param  confirm    the confirmation field as transmitted
     * @return the input record
     */
    private static ReportRequestService.ReportScreenInput turn(final String monthly,
            final String yearly, final String custom, final String startMonth, final String startDay,
            final String startYear, final String endMonth, final String endDay, final String endYear,
            final String confirm) {
        return new ReportRequestService.ReportScreenInput(monthly, yearly, custom, startMonth,
                startDay, startYear, endMonth, endDay, endYear, confirm, KeyAction.ENTER, reEntry());
    }

    /**
     * A submitted turn marking the month-to-date period.
     *
     * @param  confirm the confirmation field as transmitted
     * @return the input record
     */
    private static ReportRequestService.ReportScreenInput monthlyTurn(final String confirm) {
        return turn(SELECTED, null, null, null, null, null, null, null, null, confirm);
    }

    /**
     * A submitted turn marking the year-to-date period.
     *
     * @param  confirm the confirmation field as transmitted
     * @return the input record
     */
    private static ReportRequestService.ReportScreenInput yearlyTurn(final String confirm) {
        return turn(null, SELECTED, null, null, null, null, null, null, null, confirm);
    }

    /**
     * A submitted turn marking the operator-supplied range.
     *
     * @param  startMonth the start month as transmitted
     * @param  startDay   the start day as transmitted
     * @param  startYear  the start year as transmitted
     * @param  endMonth   the end month as transmitted
     * @param  endDay     the end day as transmitted
     * @param  endYear    the end year as transmitted
     * @param  confirm    the confirmation field as transmitted
     * @return the input record
     */
    private static ReportRequestService.ReportScreenInput customTurn(final String startMonth,
            final String startDay, final String startYear, final String endMonth,
            final String endDay, final String endYear, final String confirm) {
        return turn(null, null, SELECTED, startMonth, startDay, startYear, endMonth, endDay, endYear,
                confirm);
    }

    private static ReportRequestService.ReportScreenInput confirmedCustomTurn() {
        return customTurn("01", "01", "2022", "12", "31", "2022", CONFIRM_YES);
    }

    private static final String CUSTOM_START = "2022-01-01";

    private static final String CUSTOM_END = "2022-12-31";

    /**
     * Card 11, the start-date sort symbol: an eighteen-byte literal, the ten-byte slot, and a
     * fifty-two-byte trailer opening with the closing apostrophe. {@code 18 + 10 + 52 = 80}.
     *
     * @param  startDate the ten-character start date placed into the slot
     * @return the card image
     */
    private static String startSortSymbolCard(final String startDate) {
        return START_SORT_SYMBOL_LEAD + startDate + "'" + " ".repeat(51);
    }

    /**
     * Card 12, the end-date sort symbol: a sixteen-byte literal, the ten-byte slot, and a
     * fifty-four-byte trailer opening with the closing apostrophe. {@code 16 + 10 + 54 = 80}.
     *
     * @param  endDate the ten-character end date placed into the slot
     * @return the card image
     */
    private static String endSortSymbolCard(final String endDate) {
        return END_SORT_SYMBOL_LEAD + endDate + "'" + " ".repeat(53);
    }

    /**
     * Card 15, the report date-parameter card: the ten-byte start slot, a one-byte separator, the
     * ten-byte end slot, and fifty-nine trailing spaces. {@code 10 + 1 + 10 + 59 = 80}.
     *
     * @param  startDate the ten-character start date
     * @param  endDate   the ten-character end date
     * @return the card image
     */
    private static String dateParameterCard(final String startDate, final String endDate) {
        return startDate + " " + endDate + " ".repeat(59);
    }

    /**
     * The complete seventeen-card stream one submission carries, in the order the source declares it.
     *
     * <p>Written out card by card rather than generated, because the order is as contractual as the
     * content: an internal reader that received these frames in another order would not recognise the
     * job at all.
     *
     * @param  startDate the ten-character start date the four slots carry
     * @param  endDate   the ten-character end date
     * @return the expected stream
     */
    private static List<String> expectedCards(final String startDate, final String endDate) {
        return List.of(CARD_01_JOB,
                CARD_02_NOTIFY,
                CARD_COMMENT,
                CARD_04_JOBLIB,
                CARD_COMMENT,
                CARD_06_EXEC_PROC,
                CARD_COMMENT,
                CARD_08_SYMNAMES,
                CARD_09_CARD_NUM_SYMBOL,
                CARD_10_PROC_DT_SYMBOL,
                startSortSymbolCard(startDate),
                endSortSymbolCard(endDate),
                CARD_IN_STREAM_TERMINATOR,
                CARD_14_DATEPARM,
                dateParameterCard(startDate, endDate),
                CARD_IN_STREAM_TERMINATOR,
                CARD_17_SENTINEL);
    }

    /**
     * The encoded byte width of a value, which is the only width a fixed-column frame is measured in.
     *
     * @param  value the value to measure
     * @return the number of single-byte characters it encodes to
     */
    private static int encodedWidth(final String value) {
        return value.getBytes(StandardCharsets.US_ASCII).length;
    }

    /**
     * Reads a slot out of a card by byte offset, so a position assertion is a byte assertion.
     *
     * @param  cardImage the card to read
     * @param  offset    the zero-based byte offset of the slot
     * @param  width     the slot width in bytes
     * @return the slot's content
     */
    private static String slotAt(final String cardImage, final int offset, final int width) {
        final byte[] image = cardImage.getBytes(StandardCharsets.US_ASCII);
        return new String(image, offset, width, StandardCharsets.US_ASCII);
    }

    /**
     * Pads a value into a fixed-width field the way a left-justified move fills one.
     *
     * <p>The shortfall is computed on the encoded width, so the padding is stated in the same unit the
     * field reserves rather than in characters.
     *
     * @param  value the significant content
     * @param  width the declared field width in bytes
     * @return the value at exactly {@code width} bytes
     */
    private static String padded(final String value, final int width) {
        return value + " ".repeat(width - encodedWidth(value));
    }

    /**
     * Counts one byte value across a whole value, so a punctuation count is a byte count.
     *
     * @param  value    the value to scan
     * @param  wanted   the byte to count
     * @return how many times {@code wanted} occurs
     */
    private static int countOf(final String value, final char wanted) {
        final byte[] image = value.getBytes(StandardCharsets.US_ASCII);
        int occurrences = 0;
        for (final byte candidate : image) {
            if (candidate == (byte) wanted) {
                occurrences++;
            }
        }
        return occurrences;
    }

    /**
     * A subprogram result block at the component widths its record declares.
     *
     * @param  feedback      the outcome the block reports
     * @param  severityCode  the four-character severity
     * @param  messageNumber the four-character message number
     * @param  resultText    the fifteen-character outcome text
     * @return the result block
     */
    private static DateValidationService.SubprogramResult resultBlock(
            final DateValidationService.DateFeedback feedback, final String severityCode,
            final String messageNumber, final String resultText) {
        return new DateValidationService.SubprogramResult(feedback, severityCode, messageNumber,
                resultText, CUSTOM_START, MASK_HYPHENATED);
    }

    private static DateValidationService.SubprogramResult acceptedBlock() {
        return resultBlock(DateValidationService.DateFeedback.DATE_IS_VALID, SEVERITY_ACCEPTED,
                MESSAGE_NUMBER_NONE, RESULT_TEXT_VALID);
    }

    private static DateValidationService.SubprogramResult toleratedBlock() {
        return resultBlock(DateValidationService.DateFeedback.UNSUPPORTED_RANGE, SEVERITY_ERROR,
                MESSAGE_NUMBER_TOLERATED, RESULT_TEXT_ERROR);
    }

    private static DateValidationService.SubprogramResult refusedBlock() {
        return resultBlock(DateValidationService.DateFeedback.BAD_DATE_VALUE, SEVERITY_ERROR,
                MESSAGE_NUMBER_REFUSED, RESULT_TEXT_ERROR);
    }

    /**
     * An outcome reporting the whole stream accepted.
     *
     * @param  cardCount how many cards the caller supplied
     * @return the outcome
     */
    private static JobSubmissionService.SubmissionResult accepted(final int cardCount) {
        return new JobSubmissionService.SubmissionResult(STUBBED_SUBMISSION_ID, cardCount, cardCount,
                false, "");
    }

    /**
     * An outcome reporting the queue refusing at a given card, which is the ignore-on-error path.
     *
     * @param  cardCount      how many cards the caller supplied
     * @param  cardsPublished how many the queue accepted before refusing
     * @return the outcome
     */
    private static JobSubmissionService.SubmissionResult refusedAfter(final int cardCount,
            final int cardsPublished) {
        return new JobSubmissionService.SubmissionResult(STUBBED_SUBMISSION_ID, cardCount,
                cardsPublished, true, JobSubmissionException.DEFAULT_MESSAGE);
    }

    private void bridgeAcceptsEveryCard() {
        when(jobSubmissionService.submitCanonicalJobImage(any(), any()))
                .thenReturn(accepted(SUBMISSION_CARD_COUNT));
    }

    private void validatorAcceptsEveryDate() {
        when(dateValidationService.validateDate(any(), any(DateFormat.class)))
                .thenReturn(acceptedBlock());
    }

    private void validatorToleratesEveryDate() {
        when(dateValidationService.validateDate(any(), any(DateFormat.class)))
                .thenReturn(toleratedBlock());
    }

    private void validatorRefusesEveryDate() {
        when(dateValidationService.validateDate(any(), any(DateFormat.class)))
                .thenReturn(refusedBlock());
    }

    private void catalogueSuppliesTitles() {
        when(messageCatalogService.screenTitle01()).thenReturn(PADDED_TITLE_01);
        when(messageCatalogService.screenTitle02()).thenReturn(PADDED_TITLE_02);
    }

    private void navigationResolves(final NavigationService.Route destination) {
        when(navigationService.resolveNominatedDestination(any(),
                eq(NavigationService.Route.SIGN_ON))).thenReturn(destination);
    }

    /**
     * The card stream the bridge actually received, captured rather than reconstructed.
     *
     * @return the published stream
     */
    private List<String> capturedPublishedCards() {
        verify(jobSubmissionService).submitCanonicalJobImage(submissionIdentityCaptor.capture(),
                publishedCardsCaptor.capture());
        return publishedCardsCaptor.getValue();
    }

    /**
     * The submission identity the bridge actually received, captured rather than recomputed.
     *
     * @return the identity handed to the bridge
     */
    private String capturedSubmissionIdentity() {
        verify(jobSubmissionService).submitCanonicalJobImage(submissionIdentityCaptor.capture(),
                publishedCardsCaptor.capture());
        return submissionIdentityCaptor.getValue();
    }

    /**
     * The six emptiness cases of the operator-supplied cascade, in the order the source tests them.
     *
     * <p>An omitted column is a field the terminal did not transmit, which the source's blankness test
     * treats exactly as it treats spaces. Declared here rather than inside the nested class so the
     * arguments can name this file's own constants instead of restating each text.
     *
     * @return one case per emptiness test
     */
    static List<Arguments> emptyDatePartCases() {
        return List.of(
                Arguments.of(null, "15", "2022", "12", "31", "2022", MSG_START_MONTH_EMPTY,
                        PROPERTY_START_MONTH, FIELD_START_MONTH),
                Arguments.of("01", null, "2022", "12", "31", "2022", MSG_START_DAY_EMPTY,
                        PROPERTY_START_DAY, FIELD_START_DAY),
                Arguments.of("01", "15", null, "12", "31", "2022", MSG_START_YEAR_EMPTY,
                        PROPERTY_START_YEAR, FIELD_START_YEAR),
                Arguments.of("01", "15", "2022", null, "31", "2022", MSG_END_MONTH_EMPTY,
                        PROPERTY_END_MONTH, FIELD_END_MONTH),
                Arguments.of("01", "15", "2022", "12", null, "2022", MSG_END_DAY_EMPTY,
                        PROPERTY_END_DAY, FIELD_END_DAY),
                Arguments.of("01", "15", "2022", "12", "31", null, MSG_END_YEAR_EMPTY,
                        PROPERTY_END_YEAR, FIELD_END_YEAR));
    }

    /**
     * The six range cases, in the order the source tests them. A month above the twelfth, a day above
     * the thirty-first, and a year that is not digits at all, on each side of the range.
     *
     * @return one case per range test
     */
    static List<Arguments> outOfRangeDatePartCases() {
        return List.of(
                Arguments.of("13", "15", "2022", "12", "31", "2022", MSG_START_MONTH_INVALID,
                        PROPERTY_START_MONTH, FIELD_START_MONTH),
                Arguments.of("01", "32", "2022", "12", "31", "2022", MSG_START_DAY_INVALID,
                        PROPERTY_START_DAY, FIELD_START_DAY),
                Arguments.of("01", "15", "20X2", "12", "31", "2022", MSG_START_YEAR_INVALID,
                        PROPERTY_START_YEAR, FIELD_START_YEAR),
                Arguments.of("01", "15", "2022", "13", "31", "2022", MSG_END_MONTH_INVALID,
                        PROPERTY_END_MONTH, FIELD_END_MONTH),
                Arguments.of("01", "15", "2022", "12", "32", "2022", MSG_END_DAY_INVALID,
                        PROPERTY_END_DAY, FIELD_END_DAY),
                Arguments.of("01", "15", "2022", "12", "31", "20X2", MSG_END_YEAR_INVALID,
                        PROPERTY_END_YEAR, FIELD_END_YEAR));
    }

    /**
     * The diagnostics this turn recorded at error level.
     *
     * @return the captured error events, in the order they were recorded
     */
    private List<ILoggingEvent> capturedErrorEvents() {
        final List<ILoggingEvent> errors = new ArrayList<>();
        for (final ILoggingEvent event : logCapture.list) {
            if (event.getLevel() == Level.ERROR) {
                errors.add(event);
            }
        }
        return errors;
    }

    // ==============================================================================================
    // The main paragraph and the attention-key dispatch
    // ==============================================================================================

    @Nested
    @DisplayName("Turn entry and the attention-key dispatch")
    class TurnEntry {

        @Test
        @DisplayName("mainPara: a turn whose navigation state the rules call absent transfers to the "
                + "resolved destination, re-arms nothing and publishes nothing")
        void aTurnCarryingNoNavigationStateTransfersToTheResolvedDestination() {
            when(navigationService.isConversationStateAbsent(any())).thenReturn(true);
            when(navigationService.resolveAbsentContextRoute())
                    .thenReturn(NavigationService.Route.SIGN_ON);
            navigationResolves(NavigationService.Route.SIGN_ON);

            final ReportRequestService.ReportRequestResult result =
                    subject.processReportRequest(new ReportRequestService.ReportScreenInput(SELECTED,
                            null, null, null, null, null, null, null, null, CONFIRM_YES,
                            KeyAction.ENTER, ConversationState.empty()));

            assertAll(() -> assertThat(result.route()).isEqualTo(NavigationService.Route.SIGN_ON),
                    () -> assertThat(result.reArmedTransactionId()).isEmpty(),
                    () -> assertThat(result.cardsPublished()).isZero(),
                    () -> assertThat(result.reportPeriod()).isNull());
            verifyNoInteractions(jobSubmissionService);
            verify(navigationService).resolveAbsentContextRoute();
        }

        @Test
        @DisplayName("mainPara: a turn whose navigation state is absent altogether behaves the same "
                + "way and raises no runtime failure of its own")
        void aTurnWithAnAbsentNavigationStateRaisesNoRuntimeFailure() {
            when(navigationService.isConversationStateAbsent(null)).thenReturn(true);
            when(navigationService.resolveAbsentContextRoute())
                    .thenReturn(NavigationService.Route.SIGN_ON);
            navigationResolves(NavigationService.Route.SIGN_ON);

            final ReportRequestService.ReportRequestResult result =
                    assertDoesNotThrow(() -> subject.processReportRequest(
                            new ReportRequestService.ReportScreenInput(SELECTED, null, null, null,
                                    null, null, null, null, null, CONFIRM_YES, KeyAction.ENTER,
                                    null)));

            assertAll(() -> assertThat(result.route()).isEqualTo(NavigationService.Route.SIGN_ON),
                    () -> assertThat(result.navigationContext()).isNotNull(),
                    () -> assertThat(result.cardsPublished()).isZero());
            verifyNoInteractions(jobSubmissionService);
        }

        @Test
        @DisplayName("mainPara: a first entry sends the screen, sets the re-entry gate, parks the "
                + "cursor on the first report-type field and publishes nothing")
        void aFirstEntrySendsTheScreenAndPublishesNothing() {
            final ReportRequestService.ReportRequestResult result =
                    subject.processReportRequest(new ReportRequestService.ReportScreenInput(null,
                            null, null, null, null, null, null, null, null, null, KeyAction.ENTER,
                            ConversationState.empty()));

            assertAll(() -> assertThat(result.route())
                            .isEqualTo(NavigationService.Route.REPORT_REQUEST),
                    () -> assertThat(result.reArmedTransactionId())
                            .isEqualTo(RE_ARMED_TRANSACTION_ID),
                    () -> assertThat(result.focusField()).isEqualTo(FIELD_MONTHLY),
                    () -> assertThat(result.navigationContext().reEntry()).isTrue(),
                    () -> assertThat(result.errorFlag()).isFalse(),
                    () -> assertThat(result.message()).isEmpty(),
                    () -> assertThat(result.fieldErrors()).isEmpty(),
                    () -> assertThat(result.cardsPublished()).isZero());
            verifyNoInteractions(jobSubmissionService);
            verifyNoInteractions(dateValidationService);
        }

        @Test
        @DisplayName("populateHeaderInfo: the header carries both padded titles, the transaction and "
                + "program names, and the clock-derived date and time")
        void theHeaderCarriesTheCatalogueTitlesAndTheClockDerivedStamp() {
            catalogueSuppliesTitles();

            final ReportRequestService.ReportRequestResult result =
                    subject.processReportRequest(new ReportRequestService.ReportScreenInput(null,
                            null, null, null, null, null, null, null, null, null, KeyAction.ENTER,
                            ConversationState.empty()));

            final ReportRequestService.ScreenHeader header = result.header();
            assertAll(() -> assertThat(header.title01()).isEqualTo(PADDED_TITLE_01),
                    () -> assertThat(encodedWidth(header.title01())).isEqualTo(SCREEN_TITLE_WIDTH),
                    () -> assertThat(header.title02()).isEqualTo(PADDED_TITLE_02),
                    () -> assertThat(encodedWidth(header.title02())).isEqualTo(SCREEN_TITLE_WIDTH),
                    () -> assertThat(header.transactionName()).isEqualTo(RE_ARMED_TRANSACTION_ID),
                    () -> assertThat(header.programName()).isEqualTo(HEADER_PROGRAM_NAME),
                    () -> assertThat(header.currentDate()).isEqualTo(PINNED_HEADER_DATE),
                    () -> assertThat(header.currentTime()).isEqualTo(PINNED_HEADER_TIME),
                    () -> assertThat(encodedWidth(header.errorMessage()))
                            .isEqualTo(OUTBOUND_MESSAGE_WIDTH),
                    () -> assertThat(header.errorMessage())
                            .isEqualTo(" ".repeat(OUTBOUND_MESSAGE_WIDTH)));
        }

        @Test
        @DisplayName("returnToPrevScreen: the exit key transfers to the destination the navigation "
                + "rules resolve, so no destination is hardcoded and nothing is published")
        void theExitKeyTransfersToTheResolvedDestination() {
            navigationResolves(NavigationService.Route.USER_MENU);

            final ReportRequestService.ReportRequestResult result =
                    subject.processReportRequest(new ReportRequestService.ReportScreenInput(SELECTED,
                            null, null, null, null, null, null, null, null, CONFIRM_YES,
                            KeyAction.PFK03, reEntry()));

            assertAll(() -> assertThat(result.route()).isEqualTo(NavigationService.Route.USER_MENU),
                    () -> assertThat(result.reArmedTransactionId()).isEmpty(),
                    () -> assertThat(result.cardsPublished()).isZero());
            verify(navigationService).resolveNominatedDestination(any(),
                    eq(NavigationService.Route.SIGN_ON));
            verifyNoInteractions(jobSubmissionService);
        }

        @ParameterizedTest
        @EnumSource(value = KeyAction.class, names = {"CLEAR", "PA1", "PA2", "PFK01", "PFK12"})
        @DisplayName("mainPara: a key this screen does not map reports the padded invalid-key message "
                + "untrimmed and publishes nothing")
        void anUnmappedKeyReportsTheInvalidKeyMessageUntrimmed(final KeyAction unmappedKey) {
            when(messageCatalogService.invalidKeyMessage()).thenReturn(PADDED_INVALID_KEY_MESSAGE);

            final ReportRequestService.ReportRequestResult result =
                    subject.processReportRequest(new ReportRequestService.ReportScreenInput(SELECTED,
                            null, null, null, null, null, null, null, null, CONFIRM_YES, unmappedKey,
                            reEntry()));

            assertAll(() -> assertThat(result.message()).isEqualTo(PADDED_INVALID_KEY_MESSAGE),
                    () -> assertThat(encodedWidth(result.message()))
                            .isEqualTo(COMMON_MESSAGE_WIDTH),
                    () -> assertThat(result.errorFlag()).isTrue(),
                    () -> assertThat(result.focusField()).isEqualTo(FIELD_MONTHLY),
                    () -> assertThat(result.reportPeriod()).isNull(),
                    () -> assertThat(result.cardsPublished()).isZero());
            verifyNoInteractions(jobSubmissionService);
        }

        @Test
        @DisplayName("mainPara: a key that was never decoded reaches the same catch-all arm, because "
                + "this screen declares no clear-key arm of its own")
        void anUndecodedKeyReachesTheCatchAllArm() {
            when(messageCatalogService.invalidKeyMessage()).thenReturn(PADDED_INVALID_KEY_MESSAGE);

            final ReportRequestService.ReportRequestResult result =
                    subject.processReportRequest(new ReportRequestService.ReportScreenInput(SELECTED,
                            null, null, null, null, null, null, null, null, CONFIRM_YES, null,
                            reEntry()));

            assertAll(() -> assertThat(result.message()).isEqualTo(PADDED_INVALID_KEY_MESSAGE),
                    () -> assertThat(result.errorFlag()).isTrue(),
                    () -> assertThat(result.cardsPublished()).isZero());
            verifyNoInteractions(jobSubmissionService);
        }

        @Test
        @DisplayName("receiveTrnrptScreen: every transmitted field is bounded to its declared screen "
                + "width, so a longer value loses its excess and a shorter one is space filled")
        void everyTransmittedFieldIsBoundedToItsDeclaredWidth() {
            validatorAcceptsEveryDate();
            bridgeAcceptsEveryCard();

            final ReportRequestService.ReportRequestResult result = subject.processReportRequest(
                    customTurn("011", "011", "20222", "122", "311", "20222", "YY"));

            final ReportRequestService.ScreenFields screen = result.screen();
            assertAll(() -> assertThat(encodedWidth(screen.startMonth())).isEqualTo(2),
                    () -> assertThat(encodedWidth(screen.startDay())).isEqualTo(2),
                    () -> assertThat(encodedWidth(screen.startYear())).isEqualTo(4),
                    () -> assertThat(encodedWidth(screen.endMonth())).isEqualTo(2),
                    () -> assertThat(encodedWidth(screen.endDay())).isEqualTo(2),
                    () -> assertThat(encodedWidth(screen.endYear())).isEqualTo(4),
                    () -> assertThat(encodedWidth(screen.confirm())).isEqualTo(1),
                    () -> assertThat(result.startDate()).isEqualTo(CUSTOM_START),
                    () -> assertThat(result.endDate()).isEqualTo(CUSTOM_END));
        }

        @Test
        @DisplayName("returnToCics: a turn that re-presents the screen re-arms its own transaction, "
                + "which is what makes the next turn a re-entry")
        void aTurnThatRePresentsTheScreenReArmsItsOwnTransaction() {
            final ReportRequestService.ReportRequestResult result =
                    subject.processReportRequest(monthlyTurn(null));

            assertThat(result.reArmedTransactionId()).isEqualTo(RE_ARMED_TRANSACTION_ID);
        }

        @Test
        @DisplayName("both entry points refuse an absent input rather than defaulting it, and touch "
                + "no collaborator while doing so")
        void anAbsentInputIsRefused() {
            assertAll(() -> assertThatExceptionOfType(NullPointerException.class)
                            .isThrownBy(() -> subject.processReportRequest(null)),
                    () -> assertThatExceptionOfType(NullPointerException.class)
                            .isThrownBy(() -> subject.processReportRequest(null, RETRY_TOKEN)));
            verifyNoInteractions(jobSubmissionService, dateValidationService, messageCatalogService,
                    navigationService);
        }
    }

    // ==============================================================================================
    // Period derivation: the three arms of the enter-key paragraph
    // ==============================================================================================

    /**
     * Covers the enter-key paragraph's ordered evaluation and the two derived windows.
     *
     * <p>Both derived windows are read from the injected clock, and every expected bound below is a
     * declared literal rather than a value recomputed with the same arithmetic the service applies.
     */
    @Nested
    @DisplayName("processEnterKey - deriving the reporting period")
    class PeriodDerivation {

        @ParameterizedTest(name = "{3}: {0} derives {1} to {2}")
        @CsvSource({
            "2023-02-15T08:00:00Z, 2023-02-01, 2023-02-28, a twenty-eight day February",
            "2024-02-10T08:00:00Z, 2024-02-01, 2024-02-29, a twenty-nine day leap February",
            "2022-04-05T08:00:00Z, 2022-04-01, 2022-04-30, a thirty day month",
            "2022-07-19T23:12:33Z, 2022-07-01, 2022-07-31, a thirty-one day month",
            "2022-12-31T23:59:59Z, 2022-12-01, 2022-12-31, a December start that rolls the year",
        })
        @DisplayName("monthToDatePeriod: the window opens on the first of the month and closes on its "
                + "last day, for every month length and across the year boundary")
        void theMonthToDateWindowClosesOnTheLastDayOfTheMonth(final String isoInstant,
                final String expectedStart, final String expectedEnd, final String description) {
            bridgeAcceptsEveryCard();

            final ReportRequestService.ReportRequestResult result =
                    serviceAt(clockAt(isoInstant)).processReportRequest(monthlyTurn(CONFIRM_YES));

            final List<String> cards = capturedPublishedCards();
            assertAll(() -> assertThat(result.startDate()).as(description).isEqualTo(expectedStart),
                    () -> assertThat(result.endDate()).as(description).isEqualTo(expectedEnd),
                    () -> assertThat(result.reportPeriod()).isEqualTo(ReportPeriod.MONTHLY),
                    () -> assertThat(result.reportName()).isEqualTo(REPORT_NAME_MONTHLY),
                    () -> assertThat(cards)
                            .containsExactlyElementsOf(expectedCards(expectedStart, expectedEnd)));
        }

        @ParameterizedTest(name = "{0} derives {1} to {2}")
        @CsvSource({
            "2023-02-15T08:00:00Z, 2023-01-01, 2023-12-31",
            "2024-02-10T08:00:00Z, 2024-01-01, 2024-12-31",
            "2022-12-31T23:59:59Z, 2022-01-01, 2022-12-31",
        })
        @DisplayName("yearToDatePeriod: the window is the whole of the clock's own year, so it is "
                + "derived from the injected clock and never from an ambient source")
        void theYearToDateWindowIsTheClocksOwnYear(final String isoInstant,
                final String expectedStart, final String expectedEnd) {
            bridgeAcceptsEveryCard();

            final ReportRequestService.ReportRequestResult result =
                    serviceAt(clockAt(isoInstant)).processReportRequest(yearlyTurn(CONFIRM_YES));

            final List<String> cards = capturedPublishedCards();
            assertAll(() -> assertThat(result.startDate()).isEqualTo(expectedStart),
                    () -> assertThat(result.endDate()).isEqualTo(expectedEnd),
                    () -> assertThat(result.reportPeriod()).isEqualTo(ReportPeriod.YEARLY),
                    () -> assertThat(result.reportName()).isEqualTo(REPORT_NAME_YEARLY),
                    () -> assertThat(cards)
                            .containsExactlyElementsOf(expectedCards(expectedStart, expectedEnd)));
        }

        @Test
        @DisplayName("monthToDatePeriod: the pinned clock derives its own thirty-day window and asks "
                + "the date-validation subprogram nothing, because a derived window is not validated")
        void theDerivedMonthlyWindowIsNotValidated() {
            bridgeAcceptsEveryCard();

            final ReportRequestService.ReportRequestResult result =
                    subject.processReportRequest(monthlyTurn(CONFIRM_YES));

            assertAll(() -> assertThat(result.startDate()).isEqualTo(PINNED_MONTHLY_START),
                    () -> assertThat(result.endDate()).isEqualTo(PINNED_MONTHLY_END),
                    () -> assertThat(result.submissionAccepted()).isTrue());
            verifyNoInteractions(dateValidationService);
        }

        @Test
        @DisplayName("yearToDatePeriod: the pinned clock derives its own year and likewise validates "
                + "nothing")
        void theDerivedYearlyWindowIsNotValidated() {
            bridgeAcceptsEveryCard();

            final ReportRequestService.ReportRequestResult result =
                    subject.processReportRequest(yearlyTurn(CONFIRM_YES));

            assertAll(() -> assertThat(result.startDate()).isEqualTo(PINNED_YEARLY_START),
                    () -> assertThat(result.endDate()).isEqualTo(PINNED_YEARLY_END),
                    () -> assertThat(result.submissionAccepted()).isTrue());
            verifyNoInteractions(dateValidationService);
        }

        @Test
        @DisplayName("processEnterKey: the ordered evaluation stops at the first marked period, so a "
                + "turn marking both month-to-date and year-to-date resolves to month-to-date")
        void theOrderedEvaluationStopsAtTheFirstMarkedPeriod() {
            bridgeAcceptsEveryCard();

            final ReportRequestService.ReportRequestResult result = subject.processReportRequest(
                    turn(SELECTED, SELECTED, SELECTED, null, null, null, null, null, null,
                            CONFIRM_YES));

            assertAll(() -> assertThat(result.reportPeriod()).isEqualTo(ReportPeriod.MONTHLY),
                    () -> assertThat(result.startDate()).isEqualTo(PINNED_MONTHLY_START),
                    () -> assertThat(result.endDate()).isEqualTo(PINNED_MONTHLY_END));
            verifyNoInteractions(dateValidationService);
        }

        @Test
        @DisplayName("operatorSuppliedPeriod: an operator-supplied range reaches the queue with its "
                + "own window in all four slots")
        void anOperatorSuppliedRangeCarriesItsOwnWindow() {
            validatorAcceptsEveryDate();
            bridgeAcceptsEveryCard();

            final ReportRequestService.ReportRequestResult result =
                    subject.processReportRequest(confirmedCustomTurn());

            final List<String> cards = capturedPublishedCards();
            assertAll(() -> assertThat(result.reportPeriod()).isEqualTo(ReportPeriod.CUSTOM),
                    () -> assertThat(result.reportName()).isEqualTo(REPORT_NAME_CUSTOM),
                    () -> assertThat(result.startDate()).isEqualTo(CUSTOM_START),
                    () -> assertThat(result.endDate()).isEqualTo(CUSTOM_END),
                    () -> assertThat(cards)
                            .containsExactlyElementsOf(expectedCards(CUSTOM_START, CUSTOM_END)));
        }

        @Test
        @DisplayName("processEnterKey: marking no period at all reports the selection message "
                + "verbatim, faults the report-type field and publishes nothing")
        void markingNoPeriodReportsTheSelectionMessage() {
            final ReportRequestService.ReportRequestResult result = subject.processReportRequest(
                    turn(null, null, null, null, null, null, null, null, null, CONFIRM_YES));

            assertAll(() -> assertThat(result.message()).isEqualTo(MSG_SELECT_REPORT_TYPE),
                    () -> assertThat(result.errorFlag()).isTrue(),
                    () -> assertThat(result.reportPeriod()).isNull(),
                    () -> assertThat(result.focusField()).isEqualTo(FIELD_MONTHLY),
                    () -> assertThat(result.fieldErrors()).hasSize(1),
                    () -> assertThat(result.fieldErrors().get(0).field())
                            .isEqualTo(PROPERTY_REPORT_TYPE),
                    () -> assertThat(result.fieldErrors().get(0).state())
                            .isEqualTo(ValidationException.FieldState.MISSING),
                    () -> assertThat(result.cardsPublished()).isZero());
            verifyNoInteractions(jobSubmissionService);
        }

        @Test
        @DisplayName("processEnterKey: a successful submission composes the acknowledgement from the "
                + "space-delimited report name and recolours the message field")
        void aSuccessfulSubmissionComposesTheAcknowledgement() {
            bridgeAcceptsEveryCard();

            final ReportRequestService.ReportRequestResult result =
                    subject.processReportRequest(monthlyTurn(CONFIRM_YES));

            assertAll(() -> assertThat(result.message())
                            .isEqualTo(REPORT_NAME_MONTHLY + FRAGMENT_SUBMITTED_SUFFIX),
                    () -> assertThat(result.messageHighlightedGreen()).isTrue(),
                    () -> assertThat(result.errorFlag()).isFalse(),
                    () -> assertThat(result.confirmationBlocked()).isFalse(),
                    () -> assertThat(result.focusField()).isEqualTo(FIELD_MONTHLY));
        }

        @Test
        @DisplayName("initializeAllFields: a successful submission returns a cleared screen, because "
                + "the reset paragraph blanks all ten input fields")
        void aSuccessfulSubmissionReturnsAClearedScreen() {
            bridgeAcceptsEveryCard();

            final ReportRequestService.ReportRequestResult result =
                    subject.processReportRequest(monthlyTurn(CONFIRM_YES));

            final ReportRequestService.ScreenFields screen = result.screen();
            assertAll(() -> assertThat(screen.monthlySelection()).isEqualTo(" "),
                    () -> assertThat(screen.yearlySelection()).isEqualTo(" "),
                    () -> assertThat(screen.customSelection()).isEqualTo(" "),
                    () -> assertThat(screen.startMonth()).isEqualTo(" ".repeat(2)),
                    () -> assertThat(screen.startDay()).isEqualTo(" ".repeat(2)),
                    () -> assertThat(screen.startYear()).isEqualTo(" ".repeat(4)),
                    () -> assertThat(screen.endMonth()).isEqualTo(" ".repeat(2)),
                    () -> assertThat(screen.endDay()).isEqualTo(" ".repeat(2)),
                    () -> assertThat(screen.endYear()).isEqualTo(" ".repeat(4)),
                    () -> assertThat(screen.confirm()).isEqualTo(" "));
        }
    }

    // ==============================================================================================
    // The two-field date-acceptance test - the decisive assertions of this suite
    // ==============================================================================================

    /**
     * Covers the acceptance test the source applies to the subprogram's result block.
     *
     * <p>The block carries a four-character severity and a four-character message number. The source
     * accepts the zero severity outright and otherwise accepts anyway when the message number is the
     * one tolerated value, refusing only when it is something else. Both halves are asserted
     * independently, and the two non-zero outcomes are compared with each other: a translation that
     * reduced the pair to one boolean would satisfy either half alone but not that comparison.
     */
    @Nested
    @DisplayName("editSuppliedDates - the severity and message-number pair")
    class DateAcceptance {

        @Test
        @DisplayName("a non-zero severity carrying the tolerated message number is accepted, so the "
                + "turn proceeds all the way to the queue")
        void aNonZeroSeverityCarryingTheToleratedMessageNumberIsAccepted() {
            validatorToleratesEveryDate();
            bridgeAcceptsEveryCard();

            final ReportRequestService.ReportRequestResult result =
                    subject.processReportRequest(confirmedCustomTurn());

            assertAll(() -> assertThat(result.errorFlag()).isFalse(),
                    () -> assertThat(result.message())
                            .isEqualTo(REPORT_NAME_CUSTOM + FRAGMENT_SUBMITTED_SUFFIX),
                    () -> assertThat(result.cardsPublished()).isEqualTo(SUBMISSION_CARD_COUNT),
                    () -> assertThat(result.submissionAccepted()).isTrue(),
                    () -> assertThat(result.fieldErrors()).isEmpty());
            verify(dateValidationService, times(2)).validateDate(any(), any(DateFormat.class));
        }

        @Test
        @DisplayName("the same non-zero severity carrying any other message number is refused, and "
                + "the refusal text is reported byte for byte")
        void theSameNonZeroSeverityCarryingAnotherMessageNumberIsRefused() {
            validatorRefusesEveryDate();

            final ReportRequestService.ReportRequestResult result =
                    subject.processReportRequest(confirmedCustomTurn());

            assertAll(() -> assertThat(result.message()).isEqualTo(MSG_START_DATE_INVALID),
                    () -> assertThat(result.errorFlag()).isTrue(),
                    () -> assertThat(result.focusField()).isEqualTo(FIELD_START_MONTH),
                    () -> assertThat(result.cardsPublished()).isZero(),
                    () -> assertThat(result.fieldErrors()).hasSize(1),
                    () -> assertThat(result.fieldErrors().get(0).field())
                            .isEqualTo(PROPERTY_START_DATE),
                    () -> assertThat(result.fieldErrors().get(0).state())
                            .isEqualTo(ValidationException.FieldState.INVALID));
            verify(dateValidationService, times(1)).validateDate(any(), any(DateFormat.class));
            verifyNoInteractions(jobSubmissionService);
        }

        @Test
        @DisplayName("the accepted severity passes outright, whatever message number accompanies it")
        void theAcceptedSeverityPassesOutright() {
            validatorAcceptsEveryDate();
            bridgeAcceptsEveryCard();

            final ReportRequestService.ReportRequestResult result =
                    subject.processReportRequest(confirmedCustomTurn());

            assertAll(() -> assertThat(result.errorFlag()).isFalse(),
                    () -> assertThat(result.cardsPublished()).isEqualTo(SUBMISSION_CARD_COUNT),
                    () -> assertThat(result.submissionAccepted()).isTrue());
        }

        @Test
        @DisplayName("the two non-zero outcomes differ although their severities are identical, which "
                + "is the comparison a collapsed single-field test cannot survive")
        void theTwoNonZeroOutcomesDifferAlthoughTheirSeveritiesAreIdentical() {
            when(dateValidationService.validateDate(any(), any(DateFormat.class)))
                    .thenReturn(toleratedBlock(), toleratedBlock(), refusedBlock());
            bridgeAcceptsEveryCard();

            final ReportRequestService.ReportRequestResult tolerated =
                    subject.processReportRequest(confirmedCustomTurn());
            final ReportRequestService.ReportRequestResult refused =
                    subject.processReportRequest(confirmedCustomTurn());

            assertAll(() -> assertThat(toleratedBlock().severityCode())
                            .as("both outcomes carry the same non-zero severity")
                            .isEqualTo(refusedBlock().severityCode())
                            .isNotEqualTo(SEVERITY_ACCEPTED),
                    () -> assertThat(toleratedBlock().messageNumber())
                            .as("they differ only in the message number")
                            .isEqualTo(MESSAGE_NUMBER_TOLERATED)
                            .isNotEqualTo(refusedBlock().messageNumber()),
                    () -> assertThat(tolerated.errorFlag()).isFalse(),
                    () -> assertThat(refused.errorFlag()).isTrue(),
                    () -> assertThat(refused.errorFlag()).isNotEqualTo(tolerated.errorFlag()),
                    () -> assertThat(refused.message()).isNotEqualTo(tolerated.message()),
                    () -> assertThat(refused.cardsPublished())
                            .isNotEqualTo(tolerated.cardsPublished()),
                    () -> assertThat(tolerated.cardsPublished()).isEqualTo(SUBMISSION_CARD_COUNT),
                    () -> assertThat(refused.cardsPublished()).isZero());
            verify(jobSubmissionService, times(1)).submitCanonicalJobImage(any(), any());
        }

        @Test
        @DisplayName("editSuppliedDates: the format selector transmitted to the subprogram is the "
                + "hyphenated ten-character mask, on both invocations")
        void theTransmittedFormatSelectorIsTheHyphenatedMask() {
            validatorAcceptsEveryDate();
            bridgeAcceptsEveryCard();

            subject.processReportRequest(confirmedCustomTurn());

            verify(dateValidationService, times(2)).validateDate(validatedDateCaptor.capture(),
                    dateFormatCaptor.capture());
            assertAll(() -> assertThat(dateFormatCaptor.getAllValues())
                            .containsExactly(DateFormat.YYYY_MM_DD, DateFormat.YYYY_MM_DD),
                    () -> assertThat(DateFormat.YYYY_MM_DD.getValue())
                            .isEqualTo(MASK_HYPHENATED),
                    () -> assertThat(encodedWidth(DateFormat.YYYY_MM_DD.getValue()))
                            .isEqualTo(DATE_SLOT_WIDTH),
                    () -> assertThat(validatedDateCaptor.getAllValues())
                            .containsExactly(CUSTOM_START, CUSTOM_END));
        }

        @Test
        @DisplayName("editSuppliedDates: the end date is offered to the subprogram only after the "
                + "start date has been accepted, in that order")
        void theEndDateIsOfferedOnlyAfterTheStartDateIsAccepted() {
            validatorAcceptsEveryDate();
            bridgeAcceptsEveryCard();

            subject.processReportRequest(confirmedCustomTurn());

            final InOrder order = inOrder(dateValidationService);
            order.verify(dateValidationService).validateDate(CUSTOM_START, DateFormat.YYYY_MM_DD);
            order.verify(dateValidationService).validateDate(CUSTOM_END, DateFormat.YYYY_MM_DD);
            order.verifyNoMoreInteractions();
        }

        @Test
        @DisplayName("editSuppliedDates: a refused end date reports the end-date text once the start "
                + "date has been accepted, and publishes nothing")
        void aRefusedEndDateReportsTheEndDateText() {
            when(dateValidationService.validateDate(any(), any(DateFormat.class)))
                    .thenReturn(acceptedBlock(), refusedBlock());

            final ReportRequestService.ReportRequestResult result =
                    subject.processReportRequest(confirmedCustomTurn());

            assertAll(() -> assertThat(result.message()).isEqualTo(MSG_END_DATE_INVALID),
                    () -> assertThat(result.errorFlag()).isTrue(),
                    () -> assertThat(result.focusField()).isEqualTo(FIELD_END_MONTH),
                    () -> assertThat(result.fieldErrors()).hasSize(1),
                    () -> assertThat(result.fieldErrors().get(0).field())
                            .isEqualTo(PROPERTY_END_DATE),
                    () -> assertThat(result.fieldErrors().get(0).state())
                            .isEqualTo(ValidationException.FieldState.INVALID),
                    () -> assertThat(result.cardsPublished()).isZero());
            verify(dateValidationService, times(2)).validateDate(any(), any(DateFormat.class));
            verifyNoInteractions(jobSubmissionService);
        }

        @Test
        @DisplayName("editSuppliedDates: a subprogram that breaks its own non-null contract fails the "
                + "turn at that boundary and publishes nothing")
        void aSubprogramReturningNothingPublishesNothing() {
            when(dateValidationService.validateDate(any(), any(DateFormat.class))).thenReturn(null);

            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> subject.processReportRequest(confirmedCustomTurn()));

            verifyNoInteractions(jobSubmissionService);
        }
    }

    // ==============================================================================================
    // The confirmation gate: three arms, in the source's own clause order
    // ==============================================================================================

    /**
     * Covers the job-submission paragraph's confirmation gate.
     *
     * <p>The three arms are asserted in the order the source declares them, and they are deliberately
     * not symmetrical: a blank confirmation publishes nothing at all, a declined one raises the error
     * flag with no message text whatsoever, and an unrecognised one quotes the entry back.
     */
    @Nested
    @DisplayName("submitJobToIntrdr - the confirmation gate")
    class ConfirmationGate {

        @ParameterizedTest(name = "a confirmation field holding [{0}] publishes nothing")
        @ValueSource(strings = {"", " "})
        @DisplayName("a blank confirmation publishes nothing at all and prompts for confirmation "
                + "verbatim")
        void aBlankConfirmationPublishesNothingAtAll(final String blankConfirmation) {
            final ReportRequestService.ReportRequestResult result =
                    subject.processReportRequest(monthlyTurn(blankConfirmation));

            assertAll(() -> assertThat(result.message()).isEqualTo(FRAGMENT_CONFIRM_PREFIX
                            + REPORT_NAME_MONTHLY + FRAGMENT_CONFIRM_SUFFIX),
                    () -> assertThat(result.errorFlag()).isTrue(),
                    () -> assertThat(result.confirmationBlocked()).isTrue(),
                    () -> assertThat(result.focusField()).isEqualTo(FIELD_CONFIRM),
                    () -> assertThat(result.cardsPublished()).isZero(),
                    () -> assertThat(result.submissionAccepted()).isFalse());
            verify(jobSubmissionService, never()).submitCanonicalJobImage(any(), any());
            verifyNoInteractions(jobSubmissionService);
        }

        @Test
        @DisplayName("a confirmation field the client omitted altogether is blank in the same sense "
                + "and publishes nothing")
        void anOmittedConfirmationFieldIsBlankInTheSameSense() {
            final ReportRequestService.ReportRequestResult result =
                    subject.processReportRequest(monthlyTurn(null));

            assertAll(() -> assertThat(result.confirmationBlocked()).isTrue(),
                    () -> assertThat(result.cardsPublished()).isZero());
            verifyNoInteractions(jobSubmissionService);
        }

        @Test
        @DisplayName("a confirmation field the terminal did not transmit arrives as a low value and "
                + "counts as blank exactly as spaces do")
        void aLowValueConfirmationFieldCountsAsBlank() {
            final ReportRequestService.ReportRequestResult result =
                    subject.processReportRequest(monthlyTurn(String.valueOf(LOW_VALUE)));

            assertAll(() -> assertThat(result.confirmationBlocked()).isTrue(),
                    () -> assertThat(result.message()).isEqualTo(FRAGMENT_CONFIRM_PREFIX
                            + REPORT_NAME_MONTHLY + FRAGMENT_CONFIRM_SUFFIX),
                    () -> assertThat(result.cardsPublished()).isZero());
            verifyNoInteractions(jobSubmissionService);
        }

        @ParameterizedTest(name = "a confirmation field holding [{0}] declines silently")
        @ValueSource(strings = {CONFIRM_NO, CONFIRM_NO_LOWER})
        @DisplayName("a declined confirmation raises the error flag with an empty message text, and "
                + "publishes nothing")
        void aDeclinedConfirmationRaisesTheErrorFlagWithAnEmptyMessage(final String declined) {
            final ReportRequestService.ReportRequestResult result =
                    subject.processReportRequest(monthlyTurn(declined));

            assertAll(() -> assertThat(result.errorFlag())
                            .as("the flag is raised even though nothing is said").isTrue(),
                    () -> assertThat(result.message())
                            .as("the source sets no text at all on this arm").isNotNull().isEmpty(),
                    () -> assertThat(result.messageHighlightedGreen()).isFalse(),
                    () -> assertThat(result.confirmationBlocked()).isTrue(),
                    () -> assertThat(result.cardsPublished()).isZero(),
                    () -> assertThat(result.focusField()).isEqualTo(FIELD_MONTHLY),
                    () -> assertThat(result.screen().confirm()).isEqualTo(" "));
            verifyNoInteractions(jobSubmissionService);
        }

        @Test
        @DisplayName("an unrecognised confirmation entry is quoted back inside the unrecognised-value "
                + "text and publishes nothing")
        void anUnrecognisedConfirmationEntryIsQuotedBack() {
            final ReportRequestService.ReportRequestResult result =
                    subject.processReportRequest(monthlyTurn(CONFIRM_INADMISSIBLE));

            assertAll(() -> assertThat(result.message()).isEqualTo("\"" + CONFIRM_INADMISSIBLE
                            + FRAGMENT_INVALID_CONFIRM_SUFFIX),
                    () -> assertThat(result.errorFlag()).isTrue(),
                    () -> assertThat(result.confirmationBlocked()).isTrue(),
                    () -> assertThat(result.focusField()).isEqualTo(FIELD_CONFIRM),
                    () -> assertThat(result.cardsPublished()).isZero());
            verifyNoInteractions(jobSubmissionService);
        }

        @ParameterizedTest(name = "a confirmation field holding [{0}] proceeds to the queue")
        @ValueSource(strings = {CONFIRM_YES, CONFIRM_YES_LOWER})
        @DisplayName("only an affirmative confirmation reaches the queue, in either letter case")
        void onlyAnAffirmativeConfirmationReachesTheQueue(final String affirmative) {
            bridgeAcceptsEveryCard();

            final ReportRequestService.ReportRequestResult result =
                    subject.processReportRequest(monthlyTurn(affirmative));

            assertAll(() -> assertThat(result.confirmationBlocked()).isFalse(),
                    () -> assertThat(result.errorFlag()).isFalse(),
                    () -> assertThat(result.cardsPublished()).isEqualTo(SUBMISSION_CARD_COUNT),
                    () -> assertThat(result.submissionAccepted()).isTrue());
            verify(jobSubmissionService, times(1)).submitCanonicalJobImage(any(), any());
        }
    }

    // ==============================================================================================
    // The job image: seventeen eighty-column cards, four slots, one transmitted sentinel
    // ==============================================================================================

    /**
     * Covers the card stream a complete submission hands the queue bridge.
     *
     * <p>Every expectation here is a literal declared at the top of this file. The card builder is
     * never asked what it would produce, because comparing a producer with itself proves nothing.
     */
    @Nested
    @DisplayName("submitJobToIntrdr - the eighty-column job image")
    class JobImage {

        @Test
        @DisplayName("a confirmed submission hands the bridge exactly seventeen cards in one call, in "
                + "the order the source declares them")
        void aConfirmedSubmissionHandsTheBridgeSeventeenOrderedCards() {
            bridgeAcceptsEveryCard();

            final ReportRequestService.ReportRequestResult result =
                    subject.processReportRequest(monthlyTurn(CONFIRM_YES));

            final List<String> cards = capturedPublishedCards();
            assertAll(() -> assertThat(cards).hasSize(SUBMISSION_CARD_COUNT),
                    () -> assertThat(cards).containsExactlyElementsOf(
                            expectedCards(PINNED_MONTHLY_START, PINNED_MONTHLY_END)),
                    () -> assertThat(result.cardsPublished()).isEqualTo(SUBMISSION_CARD_COUNT));
            verify(jobSubmissionService, times(1)).submitCanonicalJobImage(any(), any());
        }

        @Test
        @DisplayName("every published card occupies its full eighty-column frame, measured on encoded "
                + "bytes and never trimmed")
        void everyPublishedCardOccupiesItsFullEightyColumnFrame() {
            bridgeAcceptsEveryCard();

            subject.processReportRequest(monthlyTurn(CONFIRM_YES));

            final List<String> cards = capturedPublishedCards();
            assertThat(cards).allSatisfy(cardImage ->
                    assertThat(encodedWidth(cardImage)).isEqualTo(CARD_IMAGE_WIDTH));
        }

        @Test
        @DisplayName("the four substitution slots carry the window at their declared byte offsets, and "
                + "each carrying card still measures eighty bytes after substitution")
        void theFourSubstitutionSlotsCarryTheWindowAtTheirDeclaredOffsets() {
            bridgeAcceptsEveryCard();

            subject.processReportRequest(monthlyTurn(CONFIRM_YES));

            final List<String> cards = capturedPublishedCards();
            final String startSortSymbol = cards.get(START_SORT_SYMBOL_CARD_INDEX);
            final String endSortSymbol = cards.get(END_SORT_SYMBOL_CARD_INDEX);
            final String parameterCard = cards.get(PARAMETER_CARD_INDEX);
            assertAll(
                    () -> assertThat(slotAt(startSortSymbol, START_SORT_SYMBOL_SLOT_OFFSET,
                            DATE_SLOT_WIDTH)).isEqualTo(PINNED_MONTHLY_START),
                    () -> assertThat(slotAt(endSortSymbol, END_SORT_SYMBOL_SLOT_OFFSET,
                            DATE_SLOT_WIDTH)).isEqualTo(PINNED_MONTHLY_END),
                    () -> assertThat(slotAt(parameterCard, PARAMETER_START_SLOT_OFFSET,
                            DATE_SLOT_WIDTH)).isEqualTo(PINNED_MONTHLY_START),
                    () -> assertThat(slotAt(parameterCard, PARAMETER_SEPARATOR_OFFSET, 1))
                            .isEqualTo(" "),
                    () -> assertThat(slotAt(parameterCard, PARAMETER_END_SLOT_OFFSET,
                            DATE_SLOT_WIDTH)).isEqualTo(PINNED_MONTHLY_END),
                    () -> assertThat(encodedWidth(startSortSymbol)).isEqualTo(CARD_IMAGE_WIDTH),
                    () -> assertThat(encodedWidth(endSortSymbol)).isEqualTo(CARD_IMAGE_WIDTH),
                    () -> assertThat(encodedWidth(parameterCard)).isEqualTo(CARD_IMAGE_WIDTH));
        }

        @Test
        @DisplayName("the terminal sentinel is transmitted rather than held back, which is why a "
                + "complete submission is seventeen cards and not sixteen")
        void theTerminalSentinelIsTransmitted() {
            bridgeAcceptsEveryCard();

            subject.processReportRequest(monthlyTurn(CONFIRM_YES));

            final List<String> cards = capturedPublishedCards();
            assertAll(() -> assertThat(cards).contains(CARD_17_SENTINEL),
                    () -> assertThat(cards.get(cards.size() - 1)).isEqualTo(CARD_17_SENTINEL),
                    () -> assertThat(cards).hasSize(SUBMISSION_CARD_COUNT),
                    () -> assertThat(encodedWidth(cards.get(cards.size() - 1)))
                            .isEqualTo(CARD_IMAGE_WIDTH));
        }

        @Test
        @DisplayName("the published stream concatenates to exactly one thousand three hundred and "
                + "sixty encoded bytes, with nothing inserted between the frames")
        void thePublishedStreamConcatenatesToTheDeclaredImageWidth() {
            bridgeAcceptsEveryCard();

            subject.processReportRequest(monthlyTurn(CONFIRM_YES));

            final StringBuilder image = new StringBuilder(CONCATENATED_IMAGE_WIDTH);
            for (final String cardImage : capturedPublishedCards()) {
                image.append(cardImage);
            }
            assertThat(encodedWidth(image.toString())).isEqualTo(CONCATENATED_IMAGE_WIDTH);
        }

        @Test
        @DisplayName("the two fixed cards that repeat are byte-identical wherever they appear, so the "
                + "comment card and the in-stream terminator are not respaced")
        void theRepeatedFixedCardsAreByteIdentical() {
            bridgeAcceptsEveryCard();

            subject.processReportRequest(monthlyTurn(CONFIRM_YES));

            final List<String> cards = capturedPublishedCards();
            assertAll(() -> assertThat(cards.get(2)).isEqualTo(CARD_COMMENT),
                    () -> assertThat(cards.get(4)).isEqualTo(CARD_COMMENT),
                    () -> assertThat(cards.get(6)).isEqualTo(CARD_COMMENT),
                    () -> assertThat(cards.get(12)).isEqualTo(CARD_IN_STREAM_TERMINATOR),
                    () -> assertThat(cards.get(15)).isEqualTo(CARD_IN_STREAM_TERMINATOR));
        }

        @Test
        @DisplayName("the submission identity the bridge receives is whitespace-free and names both "
                + "bounds of the window, because a deduplication identifier may hold no whitespace")
        void theSubmissionIdentityIsWhitespaceFreeAndNamesBothBounds() {
            bridgeAcceptsEveryCard();

            subject.processReportRequest(monthlyTurn(CONFIRM_YES));

            capturedPublishedCards();
            final String identity = submissionIdentityCaptor.getValue();
            assertAll(() -> assertThat(identity).isNotBlank().doesNotContainAnyWhitespaces(),
                    () -> assertThat(identity).contains(PINNED_MONTHLY_START),
                    () -> assertThat(identity).contains(PINNED_MONTHLY_END));
        }
    }

    // ==============================================================================================
    // The publish boundary: ignore-on-error, one submission, no retry
    // ==============================================================================================

    /**
     * Covers the queue-write paragraph, whose legacy name is misspelled in the source and spelled
     * correctly in the translation.
     *
     * <p><strong>On the attempt count.</strong> The legacy wrote one queue record per card and the
     * translation hands the bridge the whole stream in one call, because a per-card call from a
     * singleton service lets two concurrent turns interleave their sends into one unparseable job
     * stream. The per-card attempt count is therefore reported by the bridge's outcome rather than
     * counted at this boundary: a submission that stopped at the fifth card is one call reporting four
     * cards published, and that is the count asserted below. The number of calls is asserted too,
     * because a translation that re-called the bridge would be retrying, and there is no retry.
     */
    @Nested
    @DisplayName("writeJobSubmissionTdq - a failed publish is not fatal")
    class PublishFailure {

        @ParameterizedTest(name = "a queue refusing at card {0} reports {0} cards published")
        @ValueSource(ints = {0, 1, 8, 16})
        @DisplayName("a queue that refuses part way through reports exactly the cards it accepted, "
                + "abandons the rest and returns normally")
        void aQueueThatRefusesPartWayThroughReportsExactlyTheCardsItAccepted(
                final int cardsAccepted) {
            when(jobSubmissionService.submitCanonicalJobImage(any(), any()))
                    .thenReturn(refusedAfter(SUBMISSION_CARD_COUNT, cardsAccepted));

            final ReportRequestService.ReportRequestResult result = assertDoesNotThrow(
                    () -> subject.processReportRequest(monthlyTurn(CONFIRM_YES)));

            assertAll(() -> assertThat(result.cardsPublished()).isEqualTo(cardsAccepted),
                    () -> assertThat(result.errorFlag()).isTrue(),
                    () -> assertThat(result.submissionAccepted()).isFalse(),
                    () -> assertThat(result.message()).isEqualTo(MSG_UNABLE_TO_WRITE_TDQ),
                    () -> assertThat(result.focusField()).isEqualTo(FIELD_MONTHLY),
                    () -> assertThat(result.reArmedTransactionId())
                            .isEqualTo(RE_ARMED_TRANSACTION_ID));
            verify(jobSubmissionService, times(1)).submitCanonicalJobImage(any(), any());
            verifyNoMoreInteractions(jobSubmissionService);
        }

        @Test
        @DisplayName("a refused publish is recorded as a diagnostic naming both the accepted count and "
                + "the requested count")
        void aRefusedPublishIsRecordedAsADiagnostic() {
            final int cardsAccepted = 5;
            when(jobSubmissionService.submitCanonicalJobImage(any(), any()))
                    .thenReturn(refusedAfter(SUBMISSION_CARD_COUNT, cardsAccepted));

            subject.processReportRequest(monthlyTurn(CONFIRM_YES));

            final List<ILoggingEvent> errors = capturedErrorEvents();
            assertAll(() -> assertThat(errors).isNotEmpty(),
                    () -> assertThat(errors.get(0).getFormattedMessage())
                            .contains(String.valueOf(cardsAccepted))
                            .contains(String.valueOf(SUBMISSION_CARD_COUNT)));
        }

        @Test
        @DisplayName("a bridge that raises instead of reporting is caught, logged and treated as a "
                + "refusal, so no exception escapes to the caller")
        void aBridgeThatRaisesIsCaughtAndTreatedAsARefusal() {
            final JobSubmissionException publishFailure = new JobSubmissionException(
                    JobSubmissionException.DEFAULT_QUEUE_NAME, "ServiceUnavailable", "",
                    SUBMISSION_CARD_COUNT, null);
            when(jobSubmissionService.submitCanonicalJobImage(any(), any()))
                    .thenThrow(publishFailure);

            final ReportRequestService.ReportRequestResult result = assertDoesNotThrow(
                    () -> subject.processReportRequest(monthlyTurn(CONFIRM_YES)));

            final List<ILoggingEvent> errors = capturedErrorEvents();
            assertAll(() -> assertThat(result.cardsPublished()).isZero(),
                    () -> assertThat(result.errorFlag()).isTrue(),
                    () -> assertThat(result.submissionAccepted()).isFalse(),
                    () -> assertThat(result.message()).isEqualTo(MSG_UNABLE_TO_WRITE_TDQ),
                    () -> assertThat(errors).isNotEmpty(),
                    () -> assertThat(errors.get(0).getFormattedMessage())
                            .contains(JobSubmissionException.DEFAULT_QUEUE_NAME)
                            .contains(String.valueOf(SUBMISSION_CARD_COUNT)));
            verify(jobSubmissionService, times(1)).submitCanonicalJobImage(any(), any());
            verifyNoMoreInteractions(jobSubmissionService);
        }

        @Test
        @DisplayName("the queue-write failure text carries exactly three trailing dots, and neither "
                + "two, nor four, nor a single ellipsis character")
        void theQueueWriteFailureTextCarriesExactlyThreeTrailingDots() {
            when(jobSubmissionService.submitCanonicalJobImage(any(), any()))
                    .thenReturn(refusedAfter(SUBMISSION_CARD_COUNT, 0));

            final ReportRequestService.ReportRequestResult result =
                    subject.processReportRequest(monthlyTurn(CONFIRM_YES));

            assertAll(() -> assertThat(result.message()).isEqualTo(MSG_UNABLE_TO_WRITE_TDQ),
                    () -> assertThat(result.message()).endsWith("..."),
                    () -> assertThat(result.message())
                            .as("three dots, so it cannot also end in four").doesNotEndWith("...."),
                    () -> assertThat(countOf(result.message(), '.'))
                            .as("exactly three full stops in the whole text, so a two-dot or"
                                    + " four-dot spelling fails here")
                            .isEqualTo(3),
                    () -> assertThat(result.message()).doesNotContain("\u2026"),
                    () -> assertThat(result.message())
                            .isEqualTo(JobSubmissionException.DEFAULT_MESSAGE));
        }

        @Test
        @DisplayName("a successful publish records no error diagnostic at all, which is what makes the "
                + "failure diagnostic meaningful")
        void aSuccessfulPublishRecordsNoErrorDiagnostic() {
            bridgeAcceptsEveryCard();

            subject.processReportRequest(monthlyTurn(CONFIRM_YES));

            assertThat(capturedErrorEvents()).isEmpty();
        }
    }

    // ==============================================================================================
    // The message contract: byte for byte, untrimmed, unrespaced
    // ==============================================================================================

    /**
     * Covers every text this screen emits.
     *
     * <p>Two spacing artefacts are load bearing and both are reproduced rather than tidied: the six
     * emptiness texts capitalise {@code NOT} against surrounding mixed case, and the acknowledgement
     * suffix carries a space before its three trailing dots. Nothing is trimmed anywhere below.
     */
    @Nested
    @DisplayName("The message contract")
    class MessageContract {

        @ParameterizedTest(name = "{6}")
        @MethodSource("com.carddemo.service.ReportRequestServiceTest#emptyDatePartCases")
        @DisplayName("each emptiness text is reported byte for byte, capital NOT included, and reports "
                + "its own field as not supplied")
        void eachEmptinessTextIsReportedByteForByte(final String startMonth, final String startDay,
                final String startYear, final String endMonth, final String endDay,
                final String endYear, final String expectedMessage, final String expectedProperty,
                final String expectedFieldId) {
            final ReportRequestService.ReportRequestResult result = subject.processReportRequest(
                    customTurn(startMonth, startDay, startYear, endMonth, endDay, endYear,
                            CONFIRM_YES));

            assertAll(() -> assertThat(result.message()).isEqualTo(expectedMessage),
                    () -> assertThat(result.message()).contains("NOT"),
                    () -> assertThat(result.message()).doesNotContain(" not be empty"),
                    () -> assertThat(result.message()).endsWith("..."),
                    () -> assertThat(result.errorFlag()).isTrue(),
                    () -> assertThat(result.focusField()).isEqualTo(expectedFieldId),
                    () -> assertThat(result.fieldErrors()).hasSize(1),
                    () -> assertThat(result.fieldErrors().get(0).field()).isEqualTo(expectedProperty),
                    () -> assertThat(result.fieldErrors().get(0).bmsFieldId())
                            .isEqualTo(expectedFieldId),
                    () -> assertThat(result.fieldErrors().get(0).state())
                            .isEqualTo(ValidationException.FieldState.MISSING),
                    () -> assertThat(result.fieldErrors().get(0).message())
                            .isEqualTo(expectedMessage),
                    () -> assertThat(result.cardsPublished()).isZero());
            verifyNoInteractions(jobSubmissionService, dateValidationService);
        }

        @ParameterizedTest(name = "{6}")
        @MethodSource("com.carddemo.service.ReportRequestServiceTest#outOfRangeDatePartCases")
        @DisplayName("each range text is reported byte for byte and reports its own field as supplied "
                + "wrongly rather than as not supplied")
        void eachRangeTextIsReportedByteForByte(final String startMonth, final String startDay,
                final String startYear, final String endMonth, final String endDay,
                final String endYear, final String expectedMessage, final String expectedProperty,
                final String expectedFieldId) {
            final ReportRequestService.ReportRequestResult result = subject.processReportRequest(
                    customTurn(startMonth, startDay, startYear, endMonth, endDay, endYear,
                            CONFIRM_YES));

            assertAll(() -> assertThat(result.message()).isEqualTo(expectedMessage),
                    () -> assertThat(result.message()).contains("Not a valid"),
                    () -> assertThat(result.message()).endsWith("..."),
                    () -> assertThat(result.errorFlag()).isTrue(),
                    () -> assertThat(result.focusField()).isEqualTo(expectedFieldId),
                    () -> assertThat(result.fieldErrors()).hasSize(1),
                    () -> assertThat(result.fieldErrors().get(0).field()).isEqualTo(expectedProperty),
                    () -> assertThat(result.fieldErrors().get(0).state())
                            .isEqualTo(ValidationException.FieldState.INVALID),
                    () -> assertThat(result.cardsPublished()).isZero());
            verifyNoInteractions(jobSubmissionService, dateValidationService);
        }

        @Test
        @DisplayName("the acknowledgement carries a space before its three trailing dots, which every "
                + "other text in this contract does not")
        void theAcknowledgementCarriesASpaceBeforeItsThreeTrailingDots() {
            bridgeAcceptsEveryCard();

            final ReportRequestService.ReportRequestResult result =
                    subject.processReportRequest(monthlyTurn(CONFIRM_YES));

            assertAll(() -> assertThat(result.message())
                            .isEqualTo(REPORT_NAME_MONTHLY + FRAGMENT_SUBMITTED_SUFFIX),
                    () -> assertThat(result.message()).endsWith(" ..."),
                    () -> assertThat(result.message()).doesNotContain("\u2026"),
                    () -> assertThat(result.message())
                            .isEqualTo("Monthly report submitted for printing ..."));
        }

        @Test
        @DisplayName("the confirmation prompt is composed from the space-delimited report name, so the "
                + "ten-character field's padding never reaches the operator")
        void theConfirmationPromptUsesTheSpaceDelimitedReportName() {
            final ReportRequestService.ReportRequestResult result =
                    subject.processReportRequest(yearlyTurn(null));

            assertAll(() -> assertThat(result.message()).isEqualTo(FRAGMENT_CONFIRM_PREFIX
                            + REPORT_NAME_YEARLY + FRAGMENT_CONFIRM_SUFFIX),
                    () -> assertThat(result.message())
                            .isEqualTo("Please confirm to print the Yearly report..."),
                    () -> assertThat(result.reportName()).isEqualTo(REPORT_NAME_YEARLY));
        }

        @Test
        @DisplayName("the outbound message field carries the summary text at its own narrower width, "
                + "space filled and never trimmed")
        void theOutboundMessageFieldCarriesTheSummaryAtItsOwnWidth() {
            catalogueSuppliesTitles();

            final ReportRequestService.ReportRequestResult result =
                    subject.processReportRequest(turn(null, null, null, null, null, null, null, null,
                            null, CONFIRM_YES));

            assertAll(() -> assertThat(result.header().errorMessage())
                            .isEqualTo(padded(MSG_SELECT_REPORT_TYPE, OUTBOUND_MESSAGE_WIDTH)),
                    () -> assertThat(encodedWidth(result.header().errorMessage()))
                            .isEqualTo(OUTBOUND_MESSAGE_WIDTH),
                    () -> assertThat(result.header().errorMessage())
                            .startsWith(MSG_SELECT_REPORT_TYPE));
        }

        @Test
        @DisplayName("the two padded common messages are fifty encoded bytes each, trailing spaces "
                + "intact, and neither is trimmed on its way through this screen")
        void theTwoPaddedCommonMessagesAreFiftyEncodedBytesEach() {
            when(messageCatalogService.invalidKeyMessage()).thenReturn(PADDED_INVALID_KEY_MESSAGE);

            final ReportRequestService.ReportRequestResult result =
                    subject.processReportRequest(new ReportRequestService.ReportScreenInput(SELECTED,
                            null, null, null, null, null, null, null, null, CONFIRM_YES,
                            KeyAction.CLEAR, reEntry()));

            assertAll(
                    () -> assertThat(encodedWidth(PADDED_INVALID_KEY_MESSAGE))
                            .isEqualTo(COMMON_MESSAGE_WIDTH),
                    () -> assertThat(encodedWidth(PADDED_THANK_YOU_MESSAGE))
                            .isEqualTo(COMMON_MESSAGE_WIDTH),
                    () -> assertThat(MessageCatalogService.CCDA_MSG_INVALID_KEY)
                            .isEqualTo(PADDED_INVALID_KEY_MESSAGE),
                    () -> assertThat(MessageCatalogService.CCDA_MSG_THANK_YOU)
                            .isEqualTo(PADDED_THANK_YOU_MESSAGE),
                    () -> assertThat(result.message()).isEqualTo(PADDED_INVALID_KEY_MESSAGE),
                    () -> assertThat(encodedWidth(result.message()))
                            .isEqualTo(COMMON_MESSAGE_WIDTH),
                    () -> assertThat(result.message()).endsWith(" ".repeat(10)));
        }

        @Test
        @DisplayName("the two padded screen titles are forty encoded bytes each, so their centring "
                + "spaces survive the header assembly")
        void theTwoPaddedScreenTitlesAreFortyEncodedBytesEach() {
            assertAll(() -> assertThat(encodedWidth(PADDED_TITLE_01)).isEqualTo(SCREEN_TITLE_WIDTH),
                    () -> assertThat(encodedWidth(PADDED_TITLE_02)).isEqualTo(SCREEN_TITLE_WIDTH),
                    () -> assertThat(MessageCatalogService.CCDA_TITLE01).isEqualTo(PADDED_TITLE_01),
                    () -> assertThat(MessageCatalogService.CCDA_TITLE02).isEqualTo(PADDED_TITLE_02));
        }
    }

    // ==============================================================================================
    // The two-state field contract and the route contract
    // ==============================================================================================

    @Nested
    @DisplayName("The field-error and route contracts")
    class FieldAndRouteContracts {

        @Test
        @DisplayName("the field state has exactly two constants, so a field is either not supplied or "
                + "supplied wrongly and there is no third state to report")
        void theFieldStateHasExactlyTwoConstants() {
            assertAll(() -> assertThat(EnumSet.allOf(ValidationException.FieldState.class))
                            .containsExactly(ValidationException.FieldState.MISSING,
                                    ValidationException.FieldState.INVALID),
                    () -> assertThat(ValidationException.FieldState.values()).hasSize(2));
        }

        @Test
        @DisplayName("a blank required part is reported as not supplied while a badly formed one is "
                + "reported as supplied wrongly, which is the whole of the two-state distinction")
        void aBlankPartIsMissingWhileABadlyFormedOneIsInvalid() {
            final ReportRequestService.ReportRequestResult missing = subject.processReportRequest(
                    customTurn(null, "15", "2022", "12", "31", "2022", CONFIRM_YES));
            final ReportRequestService.ReportRequestResult invalid = subject.processReportRequest(
                    customTurn("13", "15", "2022", "12", "31", "2022", CONFIRM_YES));

            assertAll(() -> assertThat(missing.fieldErrors().get(0).state())
                            .isEqualTo(ValidationException.FieldState.MISSING),
                    () -> assertThat(invalid.fieldErrors().get(0).state())
                            .isEqualTo(ValidationException.FieldState.INVALID),
                    () -> assertThat(missing.fieldErrors().get(0).state())
                            .isNotEqualTo(invalid.fieldErrors().get(0).state()),
                    () -> assertThat(missing.message()).isNotEqualTo(invalid.message()));
        }

        @Test
        @DisplayName("the per-field detail is never absent and never modifiable, so a consumer cannot "
                + "alter what the turn reported")
        void thePerFieldDetailIsNeitherAbsentNorModifiable() {
            final ReportRequestService.ReportRequestResult faulted = subject.processReportRequest(
                    customTurn(null, "15", "2022", "12", "31", "2022", CONFIRM_YES));
            final List<ValidationException.FieldError> reported = faulted.fieldErrors();

            assertAll(() -> assertThat(reported).isNotNull().hasSize(1),
                    () -> assertThatExceptionOfType(UnsupportedOperationException.class)
                            .isThrownBy(() -> reported.add(new ValidationException.FieldError(
                                    PROPERTY_START_MONTH, FIELD_START_MONTH,
                                    ValidationException.FieldState.INVALID, MSG_START_MONTH_EMPTY))),
                    () -> assertThatExceptionOfType(UnsupportedOperationException.class)
                            .isThrownBy(reported::clear));
        }

        @Test
        @DisplayName("a turn that re-presents its own screen reports the screen's own route, and a "
                + "first entry carries no per-field detail at all")
        void aTurnThatRePresentsItsOwnScreenReportsItsOwnRoute() {
            final ReportRequestService.ReportRequestResult result =
                    subject.processReportRequest(monthlyTurn(null));

            assertAll(() -> assertThat(result.route())
                            .isEqualTo(NavigationService.Route.REPORT_REQUEST),
                    () -> assertThat(result.route().getRouteValue()).isEqualTo("report-request"),
                    () -> assertThat(result.route().getLegacyTransactionId())
                            .isEqualTo(RE_ARMED_TRANSACTION_ID),
                    () -> assertThat(result.route().getLegacyProgramName())
                            .isEqualTo(HEADER_PROGRAM_NAME));
        }

        @Test
        @DisplayName("no route names the dangling CICS program definition that has no source member, "
                + "so nothing in this screen's dispatch can reach it")
        void noRouteNamesTheDanglingProgramDefinition() {
            final List<String> programNames = new ArrayList<>();
            for (final NavigationService.Route route : NavigationService.Route.values()) {
                programNames.add(route.getLegacyProgramName());
            }

            assertAll(() -> assertThat(programNames).doesNotContain(DANGLING_PROGRAM_DEFINITION),
                    () -> assertThat(programNames).contains(HEADER_PROGRAM_NAME));
        }

        @Test
        @DisplayName("the destination on a transfer comes from the navigation rules rather than from a "
                + "literal, so the rules are consulted on every transfer")
        void theTransferDestinationComesFromTheNavigationRules() {
            navigationResolves(NavigationService.Route.ADMIN_MENU);

            final ReportRequestService.ReportRequestResult result =
                    subject.processReportRequest(new ReportRequestService.ReportScreenInput(SELECTED,
                            null, null, null, null, null, null, null, null, CONFIRM_YES,
                            KeyAction.PFK03, reEntry()));

            assertAll(() -> assertThat(result.route())
                            .as("the route is whatever the rules resolved, not a hardcoded screen")
                            .isEqualTo(NavigationService.Route.ADMIN_MENU),
                    () -> assertThat(result.reArmedTransactionId())
                            .as("a transfer re-arms nothing").isEmpty());
            verify(navigationService).resolveNominatedDestination(any(),
                    eq(NavigationService.Route.SIGN_ON));
        }
    }

    // ==============================================================================================
    // The turn's outcome, the logical-request token, and the terminal send
    // ==============================================================================================

    @Nested
    @DisplayName("The turn outcome and the logical-request token")
    class TurnOutcome {

        @Test
        @DisplayName("sendTrnrptScreen: the first failure ends the turn, so no later part is "
                + "normalised, no later bound is compared and no submission is attempted")
        void theFirstFailureEndsTheTurn() {
            final ReportRequestService.ReportRequestResult result = subject.processReportRequest(
                    customTurn(null, "9", "20X2", null, null, null, CONFIRM_YES));

            assertAll(() -> assertThat(result.message()).isEqualTo(MSG_START_MONTH_EMPTY),
                    () -> assertThat(result.message()).isNotEqualTo(MSG_START_YEAR_INVALID),
                    () -> assertThat(result.fieldErrors())
                            .as("the legacy screen can carry one failure per turn").hasSize(1),
                    () -> assertThat(result.screen().startDay())
                            .as("nothing after the first failure is normalised").isEqualTo("9 "),
                    () -> assertThat(result.screen().startYear()).isEqualTo("20X2"),
                    () -> assertThat(result.startDate())
                            .as("neither date is assembled at all").isEmpty(),
                    () -> assertThat(result.endDate()).isEmpty(),
                    () -> assertThat(result.reportPeriod())
                            .as("the report name and period are set after the cascade").isNull(),
                    () -> assertThat(result.cardsPublished()).isZero());
            verifyNoInteractions(dateValidationService, jobSubmissionService);
        }

        @Test
        @DisplayName("a supplied logical-request token is carried through unchanged, so a caller can "
                + "repeat it to retry the same submission")
        void aSuppliedTokenIsCarriedThroughUnchanged() {
            bridgeAcceptsEveryCard();

            final ReportRequestService.ReportRequestResult result =
                    subject.processReportRequest(monthlyTurn(CONFIRM_YES), RETRY_TOKEN);

            assertThat(result.submissionToken()).isEqualTo(RETRY_TOKEN);
        }

        @Test
        @DisplayName("an absent token is minted, and two deliberate requests receive two different "
                + "tokens")
        void anAbsentTokenIsMintedAndDistinctPerRequest() {
            bridgeAcceptsEveryCard();

            final ReportRequestService.ReportRequestResult first =
                    subject.processReportRequest(monthlyTurn(CONFIRM_YES));
            final ReportRequestService.ReportRequestResult second =
                    subject.processReportRequest(monthlyTurn(CONFIRM_YES));

            assertAll(() -> assertThat(first.submissionToken()).isNotBlank(),
                    () -> assertThat(second.submissionToken()).isNotBlank(),
                    () -> assertThat(second.submissionToken())
                            .isNotEqualTo(first.submissionToken()));
        }

        @Test
        @DisplayName("a blank token is treated as absent and minted, rather than published as a blank "
                + "identity the queue would refuse")
        void aBlankTokenIsTreatedAsAbsent() {
            bridgeAcceptsEveryCard();

            final ReportRequestService.ReportRequestResult result =
                    subject.processReportRequest(monthlyTurn(CONFIRM_YES), "   ");

            assertAll(() -> assertThat(result.submissionToken()).isNotBlank(),
                    () -> assertThat(result.submissionToken()).isNotEqualTo("   "),
                    () -> assertThat(capturedSubmissionIdentity()).doesNotContainAnyWhitespaces());
        }

        @Test
        @DisplayName("the same token and the same window reproduce the same submission identity, while "
                + "a different token makes a distinct submission of that same window")
        void theSameTokenAndWindowReproduceTheSameIdentity() {
            bridgeAcceptsEveryCard();

            subject.processReportRequest(monthlyTurn(CONFIRM_YES), RETRY_TOKEN);
            subject.processReportRequest(monthlyTurn(CONFIRM_YES), RETRY_TOKEN);
            subject.processReportRequest(monthlyTurn(CONFIRM_YES), OTHER_TOKEN);

            verify(jobSubmissionService, times(3)).submitCanonicalJobImage(
                    submissionIdentityCaptor.capture(), publishedCardsCaptor.capture());
            final List<String> identities = submissionIdentityCaptor.getAllValues();
            assertAll(() -> assertThat(identities.get(1))
                            .as("a retry of one logical request repeats its identity")
                            .isEqualTo(identities.get(0)),
                    () -> assertThat(identities.get(2))
                            .as("a deliberate new request of the same window is distinct")
                            .isNotEqualTo(identities.get(0)),
                    () -> assertThat(publishedCardsCaptor.getAllValues())
                            .allSatisfy(cards -> assertThat(cards)
                                    .hasSize(SUBMISSION_CARD_COUNT)));
        }

        @Test
        @DisplayName("the SAME token presented by two DIFFERENT operators makes two distinct submissions, "
                + "so one operator's key cannot silently suppress another's cards")
        void theSameTokenFromTwoOperatorsMakesTwoSubmissions() {
            bridgeAcceptsEveryCard();

            // A token is a value the caller chooses, so two operators naturally arrive at the same one - a
            // period name, a run label. Before the identity named the operator, the queue collapsed the
            // second operator's cards as a duplicate of the first's and answered the second operator as
            // though its request had been submitted.
            subject.processReportRequest(monthlyTurn(CONFIRM_YES), RETRY_TOKEN, FIRST_OPERATOR);
            subject.processReportRequest(monthlyTurn(CONFIRM_YES), RETRY_TOKEN, SECOND_OPERATOR);

            verify(jobSubmissionService, times(2)).submitCanonicalJobImage(
                    submissionIdentityCaptor.capture(), publishedCardsCaptor.capture());
            final List<String> identities = submissionIdentityCaptor.getAllValues();
            assertAll(() -> assertThat(identities.get(1))
                            .as("the same token in two operators' hands is two submissions")
                            .isNotEqualTo(identities.get(0)),
                    () -> assertThat(identities)
                            .as("and neither identity leaks the operator it names, which travels only "
                                    + "through the digest")
                            .allSatisfy(identity -> assertThat(identity)
                                    .doesNotContain(FIRST_OPERATOR)
                                    .doesNotContain(SECOND_OPERATOR)),
                    () -> assertThat(publishedCardsCaptor.getAllValues())
                            .as("both submissions publish their whole stream")
                            .allSatisfy(cards -> assertThat(cards).hasSize(SUBMISSION_CARD_COUNT)));
        }

        @Test
        @DisplayName("the same token presented twice by the SAME operator reproduces one identity, so a "
                + "retry still completes a half-published stream rather than doubling it")
        void theSameTokenFromOneOperatorReproducesOneIdentity() {
            bridgeAcceptsEveryCard();

            subject.processReportRequest(monthlyTurn(CONFIRM_YES), RETRY_TOKEN, FIRST_OPERATOR);
            subject.processReportRequest(monthlyTurn(CONFIRM_YES), RETRY_TOKEN, FIRST_OPERATOR);
            subject.processReportRequest(monthlyTurn(CONFIRM_YES), OTHER_TOKEN, FIRST_OPERATOR);

            verify(jobSubmissionService, times(3)).submitCanonicalJobImage(
                    submissionIdentityCaptor.capture(), publishedCardsCaptor.capture());
            final List<String> identities = submissionIdentityCaptor.getAllValues();
            assertAll(() -> assertThat(identities.get(1))
                            .as("one operator retrying its own request repeats its own identifiers")
                            .isEqualTo(identities.get(0)),
                    () -> assertThat(identities.get(2))
                            .as("and a deliberate new request of the same window is still distinct")
                            .isNotEqualTo(identities.get(0)));
        }

        @Test
        @DisplayName("a turn that names no operator is its own namespace rather than sharing one with a "
                + "named operator, and stays whitespace-free and inside the bridge's bound")
        void aTurnThatNamesNoOperatorIsItsOwnNamespace() {
            bridgeAcceptsEveryCard();

            subject.processReportRequest(monthlyTurn(CONFIRM_YES), RETRY_TOKEN, null);
            subject.processReportRequest(monthlyTurn(CONFIRM_YES), RETRY_TOKEN, FIRST_OPERATOR);

            verify(jobSubmissionService, times(2)).submitCanonicalJobImage(
                    submissionIdentityCaptor.capture(), publishedCardsCaptor.capture());
            final List<String> identities = submissionIdentityCaptor.getAllValues();
            assertAll(() -> assertThat(identities.get(0)).isNotEqualTo(identities.get(1)),
                    () -> assertThat(identities).allSatisfy(identity -> assertThat(identity)
                            .doesNotContainAnyWhitespaces()
                            .hasSizeLessThanOrEqualTo(MAX_SUBMISSION_IDENTITY_LENGTH)),
                    () -> assertThat(identities.get(0))
                            .as("the two date slots stay readable in the identity; only the operator and "
                                    + "the token are digested")
                            .contains(PINNED_MONTHLY_START)
                            .contains(PINNED_MONTHLY_END));
        }

        @Test
        @DisplayName("a window whose end precedes its start is submitted as entered, because the legacy "
                + "compares the two bounds nowhere at all")
        void aWindowWhoseEndPrecedesItsStartIsSubmittedAsEntered() {
            validatorAcceptsEveryDate();
            bridgeAcceptsEveryCard();

            final ReportRequestService.ReportRequestResult result = subject.processReportRequest(
                    customTurn("12", "31", "2022", "01", "01", "2022", CONFIRM_YES));

            final List<String> cards = capturedPublishedCards();
            assertAll(() -> assertThat(result.startDate()).isEqualTo("2022-12-31"),
                    () -> assertThat(result.endDate()).isEqualTo("2022-01-01"),
                    () -> assertThat(result.errorFlag()).isFalse(),
                    () -> assertThat(result.cardsPublished()).isEqualTo(SUBMISSION_CARD_COUNT),
                    () -> assertThat(cards).containsExactlyElementsOf(
                            expectedCards("2022-12-31", "2022-01-01")));
        }

        @Test
        @DisplayName("the outcome reports a submission accepted only when no error was raised and every "
                + "card of the canonical image reached the queue")
        void theOutcomeReportsAcceptanceOnlyOnACompleteUnfaultedSubmission() {
            final ReportRequestService.ScreenHeader header = new ReportRequestService.ScreenHeader(
                    PADDED_TITLE_01, PADDED_TITLE_02, RE_ARMED_TRANSACTION_ID, HEADER_PROGRAM_NAME,
                    PINNED_HEADER_DATE, PINNED_HEADER_TIME, " ".repeat(OUTBOUND_MESSAGE_WIDTH));
            final ReportRequestService.ScreenFields screen = new ReportRequestService.ScreenFields(
                    " ", " ", " ", " ".repeat(2), " ".repeat(2), " ".repeat(4), " ".repeat(2),
                    " ".repeat(2), " ".repeat(4), " ");

            final ReportRequestService.ReportRequestResult complete =
                    new ReportRequestService.ReportRequestResult(
                            NavigationService.Route.REPORT_REQUEST, ConversationState.empty(),
                            RE_ARMED_TRANSACTION_ID, ReportPeriod.MONTHLY, REPORT_NAME_MONTHLY,
                            PINNED_MONTHLY_START, PINNED_MONTHLY_END, SUBMISSION_CARD_COUNT, false,
                            "", false, FIELD_MONTHLY, false, List.of(), header, screen);
            final ReportRequestService.ReportRequestResult faulted =
                    new ReportRequestService.ReportRequestResult(
                            NavigationService.Route.REPORT_REQUEST, ConversationState.empty(),
                            RE_ARMED_TRANSACTION_ID, ReportPeriod.MONTHLY, REPORT_NAME_MONTHLY,
                            PINNED_MONTHLY_START, PINNED_MONTHLY_END, SUBMISSION_CARD_COUNT, false,
                            MSG_UNABLE_TO_WRITE_TDQ, false, FIELD_MONTHLY, true, List.of(), header,
                            screen);
            final ReportRequestService.ReportRequestResult partial =
                    new ReportRequestService.ReportRequestResult(
                            NavigationService.Route.REPORT_REQUEST, ConversationState.empty(),
                            RE_ARMED_TRANSACTION_ID, ReportPeriod.MONTHLY, REPORT_NAME_MONTHLY,
                            PINNED_MONTHLY_START, PINNED_MONTHLY_END, SUBMISSION_CARD_COUNT - 1,
                            false, MSG_UNABLE_TO_WRITE_TDQ, false, FIELD_MONTHLY, true, List.of(),
                            header, screen);

            assertAll(() -> assertThat(complete.submissionAccepted()).isTrue(),
                    () -> assertThat(faulted.submissionAccepted()).isFalse(),
                    () -> assertThat(partial.submissionAccepted()).isFalse(),
                    () -> assertThat(complete.submissionToken())
                            .as("the compatibility form carries no token, and says so rather than"
                                    + " manufacturing one")
                            .isNull(),
                    () -> assertThat(complete.header()).isEqualTo(header),
                    () -> assertThat(complete.screen()).isEqualTo(screen));
        }

        @Test
        @DisplayName("the outcome of a real successful turn carries the same acceptance verdict as the "
                + "manually assembled one, so the two forms agree")
        void theOutcomeOfARealSuccessfulTurnAgreesWithTheAssembledForm() {
            bridgeAcceptsEveryCard();

            final ReportRequestService.ReportRequestResult result =
                    subject.processReportRequest(monthlyTurn(CONFIRM_YES), RETRY_TOKEN);

            assertAll(() -> assertThat(result.submissionAccepted()).isTrue(),
                    () -> assertThat(result.cardsPublished()).isEqualTo(SUBMISSION_CARD_COUNT),
                    () -> assertThat(result.errorFlag()).isFalse(),
                    () -> assertThat(result.submissionToken()).isEqualTo(RETRY_TOKEN),
                    () -> assertThat(result.navigationContext()).isNotNull(),
                    () -> assertThat(result.fieldErrors()).isEmpty(),
                    () -> assertThat(result.reArmedTransactionId())
                            .isEqualTo(RE_ARMED_TRANSACTION_ID));
        }
    }
}
