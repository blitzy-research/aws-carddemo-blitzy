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

import java.time.LocalDate;
import java.util.EnumSet;
import java.util.Set;

import com.carddemo.domain.enums.DateFormat;
import com.carddemo.service.DateValidationService.DateEditFlag;
import com.carddemo.service.DateValidationService.DateEditResult;
import com.carddemo.service.DateValidationService.DateFeedback;
import com.carddemo.service.DateValidationService.SubprogramResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNoException;

/**
 * Verifies {@code DateValidationService} against the two legacy artefacts it translates.
 *
 * <p>The service carries <strong>two entirely separate entry points</strong> and the tests are
 * organised to keep them separate, because conflating them is the easiest way to assert something the
 * legacy never did:
 *
 * <ul>
 *   <li>the <em>copybook cascade</em>, {@code PERFORM EDIT-DATE-CCYYMMDD THRU
 *       EDIT-DATE-CCYYMMDD-EXIT} spanning {@code [app/cpy/CSUTLDPY.cpy:L18]} to
 *       {@code [app/cpy/CSUTLDPY.cpy:L329]}, an <strong>eleven-paragraph fall-through range</strong>
 *       whose head paragraph validates nothing at all, so the behaviour lives entirely in the five
 *       stages it falls through; and</li>
 *   <li>the <em>callable subprogram</em>, {@code CALL 'CSUTLDTC'}, whose procedure division runs from
 *       {@code [app/cbl/CSUTLDTC.cbl:L88]} to {@code [app/cbl/CSUTLDTC.cbl:L102]} and whose ten-clause
 *       feedback evaluation runs from {@code [app/cbl/CSUTLDTC.cbl:L128]} to
 *       {@code [app/cbl/CSUTLDTC.cbl:L149]}.</li>
 * </ul>
 *
 * <p><strong>Every oracle below is read from the legacy source, never from the Java.</strong> The
 * message literals carry the source's own irregular spacing - three suffixes open with
 * {@code " : "}, two with {@code ": "}, four with {@code ":"} alone, one with no colon at all, and
 * one closes with a trailing space - and that irregularity is asserted rather than tidied, because it
 * is displayed on the account-update screen today.
 *
 * <p>Three properties of the cascade are the ones a careless translation loses, so each has its own
 * nested class:
 *
 * <ol>
 *   <li><strong>A stage failing does not abandon the range.</strong> The year stage's early exit lands
 *       on the month stage, which is why a blank year and a bad month are both reported on one pass.
 *       Tests that assert on more than one flag at a time exist precisely to pin this.</li>
 *   <li><strong>The accumulated message is first-wins.</strong> Each stage writes its suffix only
 *       while the message is still blank, so later stages set flags silently.</li>
 *   <li><strong>The leap-year test selects a divisor before dividing.</strong> Four hundred when the
 *       year within the century is zero, four otherwise,
 *       {@code [app/cpy/CSUTLDPY.cpy:L245]}-{@code [app/cpy/CSUTLDPY.cpy:L249]}. The year 1900 is the
 *       witness that this is not decorative: {@code 1900 % 4 == 0}, so a single-divisor translation
 *       would accept a 29 February 1900 that the legacy rejects.</li>
 * </ol>
 */
@DisplayName("DateValidationService: the copybook cascade and the callable subprogram")
final class DateValidationServiceTest {

    // The thirteen cascade message suffixes, reproduced from app/cpy/CSUTLDPY.cpy byte for byte.
    // These are duplicated here deliberately: a test that imported the production constants would
    // assert only that the code equals itself.

    /** {@code [app/cpy/CSUTLDPY.cpy:L37]}. Opens with a spaced colon. */
    private static final String ORACLE_YEAR_NOT_SUPPLIED = " : Year must be supplied.";

    /** {@code [app/cpy/CSUTLDPY.cpy:L54]}. The one suffix of the thirteen that carries no colon. */
    private static final String ORACLE_YEAR_NOT_FOUR_DIGITS = " must be 4 digit number.";

    /** {@code [app/cpy/CSUTLDPY.cpy:L79]}. */
    private static final String ORACLE_CENTURY_NOT_VALID = " : Century is not valid.";

    /** {@code [app/cpy/CSUTLDPY.cpy:L101]}. */
    private static final String ORACLE_MONTH_NOT_SUPPLIED = " : Month must be supplied.";

    /** {@code [app/cpy/CSUTLDPY.cpy:L119]} and, identically, {@code [app/cpy/CSUTLDPY.cpy:L136]}. */
    private static final String ORACLE_MONTH_OUT_OF_RANGE =
            ": Month must be a number between 1 and 12.";

    /** {@code [app/cpy/CSUTLDPY.cpy:L161]}. */
    private static final String ORACLE_DAY_NOT_SUPPLIED = " : Day must be supplied.";

    /**
     * {@code [app/cpy/CSUTLDPY.cpy:L180]} and {@code [app/cpy/CSUTLDPY.cpy:L195]}. Lower-case "day"
     * where the month peer capitalises, and no space after the colon; both are the source's.
     */
    private static final String ORACLE_DAY_OUT_OF_RANGE = ":day must be a number between 1 and 31.";

    /** {@code [app/cpy/CSUTLDPY.cpy:L221]}. */
    private static final String ORACLE_CANNOT_HAVE_31_DAYS = ":Cannot have 31 days in this month.";

    /** {@code [app/cpy/CSUTLDPY.cpy:L236]}. */
    private static final String ORACLE_CANNOT_HAVE_30_DAYS = ":Cannot have 30 days in this month.";

    /**
     * {@code [app/cpy/CSUTLDPY.cpy:L266]}. <strong>Two sentences run together with no space after the
     * first period</strong>, exactly as the source literal has them.
     */
    private static final String ORACLE_NOT_A_LEAP_YEAR =
            ":Not a leap year.Cannot have 29 days in this month.";

    /** {@code [app/cpy/CSUTLDPY.cpy:L363]}. The trailing space is in the source literal. */
    private static final String ORACLE_DATE_IN_FUTURE = ":cannot be in the future ";

    /** The all-spaces state of {@code WS-RETURN-MSG}, {@code [app/cbl/COACTUPC.cbl:L479]}. */
    private static final String ORACLE_NO_MESSAGE = "";

    // The ten outcome texts of the evaluation at [app/cbl/CSUTLDTC.cbl:L128] to [L149], each padded to
    // the fifteen-character receiving field WS-RESULT at [app/cbl/CSUTLDTC.cbl:L49].

    /** {@code [app/cbl/CSUTLDTC.cbl:L130]}, padded from thirteen characters to fifteen. */
    private static final String ORACLE_TEXT_DATE_IS_VALID = "Date is valid  ";

    /** {@code [app/cbl/CSUTLDTC.cbl:L132]}, padded from twelve characters to fifteen. */
    private static final String ORACLE_TEXT_INSUFFICIENT = "Insufficient   ";

    /** {@code [app/cbl/CSUTLDTC.cbl:L134]}, already fifteen characters. */
    private static final String ORACLE_TEXT_DATEVALUE_ERROR = "Datevalue error";

    /** {@code [app/cbl/CSUTLDTC.cbl:L136]}, written out to the full width in the source. */
    private static final String ORACLE_TEXT_INVALID_ERA = "Invalid Era    ";

    /** {@code [app/cbl/CSUTLDTC.cbl:L138]}. */
    private static final String ORACLE_TEXT_UNSUPPORTED_RANGE = "Unsupp. Range  ";

    /** {@code [app/cbl/CSUTLDTC.cbl:L140]}. */
    private static final String ORACLE_TEXT_INVALID_MONTH = "Invalid month  ";

    /** {@code [app/cbl/CSUTLDTC.cbl:L142]}. */
    private static final String ORACLE_TEXT_BAD_PICTURE_STRING = "Bad Pic String ";

    /** {@code [app/cbl/CSUTLDTC.cbl:L144]}, already fifteen characters. */
    private static final String ORACLE_TEXT_NON_NUMERIC_DATA = "Nonnumeric data";

    /** {@code [app/cbl/CSUTLDTC.cbl:L146]}. */
    private static final String ORACLE_TEXT_YEAR_IN_ERA_ZERO = "YearInEra is 0 ";

    /** The {@code WHEN OTHER} text at {@code [app/cbl/CSUTLDTC.cbl:L148]}, already fifteen. */
    private static final String ORACLE_TEXT_DATE_IS_INVALID = "Date is invalid";

    // Widths and codes, every one read from a record layout rather than assumed.

    /** {@code LS-RESULT PIC X(80)}, {@code [app/cbl/CSUTLDTC.cbl:L86]}. */
    private static final int ORACLE_RESULT_BLOCK_WIDTH = 80;

    /** {@code CSUTLDTC-RESULT-MSG PIC X(61)}, {@code [app/cbl/CORPT00C.cbl:L136]}. */
    private static final int ORACLE_MESSAGE_SEGMENT_WIDTH = 61;

    /** {@code WS-SEVERITY PIC X(04)} and {@code WS-MSG-NO}, {@code [app/cbl/CSUTLDTC.cbl:L43]}. */
    private static final int ORACLE_CODE_WIDTH = 4;

    /** {@code WS-RESULT PIC X(15)}, {@code [app/cbl/CSUTLDTC.cbl:L49]}. */
    private static final int ORACLE_RESULT_TEXT_WIDTH = 15;

    /** {@code LS-DATE} and {@code LS-DATE-FORMAT PIC X(10)}, {@code [app/cbl/CSUTLDTC.cbl:L84]}. */
    private static final int ORACLE_LINKAGE_TEXT_WIDTH = 10;

    /** {@code WS-EDIT-DATE-CCYYMMDD PIC X(08)}, {@code [app/cpy/CSUTLDWY.cpy:L4]}. */
    private static final int ORACLE_CCYYMMDD_WIDTH = 8;

    /** The three-character group {@code WS-EDIT-DATE-FLGS}, {@code [app/cpy/CSUTLDWY.cpy:L43]}. */
    private static final int ORACLE_FLAG_GROUP_WIDTH = 3;

    /** The severity the callers accept outright, {@code [app/cbl/CORPT00C.cbl:L396]}. */
    private static final String ORACLE_ACCEPTED_SEVERITY = "0000";

    /** The four-character severity every failure token carries: decimal three, zero filled. */
    private static final String ORACLE_FAILURE_SEVERITY = "0003";

    /**
     * The message number both callers tolerate despite a non-zero severity,
     * {@code [app/cbl/CORPT00C.cbl:L406]}. Decoded from {@code 0x09D1} in the feedback token of
     * {@code 88 FC-UNSUPP-RANGE} at {@code [app/cbl/CSUTLDTC.cbl:L66]}.
     */
    private static final String ORACLE_TOLERATED_MESSAGE_NUMBER = "2513";

    /** The message number of the success token: zero, zero filled to four characters. */
    private static final String ORACLE_ZERO_MESSAGE_NUMBER = "0000";

    // Calendar constants, each a condition-name value from app/cpy/CSUTLDWY.cpy.

    /** {@code 88 THIS-CENTURY VALUE 20}, {@code [app/cpy/CSUTLDWY.cpy:L9]}. */
    private static final int ORACLE_THIS_CENTURY = 20;

    /** {@code 88 LAST-CENTURY VALUE 19}, {@code [app/cpy/CSUTLDWY.cpy:L10]}. */
    private static final int ORACLE_LAST_CENTURY = 19;

    /**
     * The seven months enumerated by {@code 88 WS-31-DAY-MONTH},
     * {@code [app/cpy/CSUTLDWY.cpy:L21]}-{@code [app/cpy/CSUTLDWY.cpy:L23]}. Written out as the
     * condition name writes them rather than derived from a calendar library.
     */
    private static final Set<Integer> ORACLE_THIRTY_ONE_DAY_MONTHS = Set.of(1, 3, 5, 7, 8, 10, 12);

    /** {@code 88 WS-FEBRUARY VALUE 2}, {@code [app/cpy/CSUTLDWY.cpy:L24]}. */
    private static final int ORACLE_FEBRUARY = 2;

    /**
     * The first day the Lilian date services {@code CEEDAYS} covers. The Lilian day count begins on
     * 15 October 1582, so this is the boundary the unsupported-range condition reports.
     */
    private static final LocalDate ORACLE_LILIAN_RANGE_START = LocalDate.of(1582, 10, 15);

    // Representative inputs. Each is named for the branch it selects, so a failure names the branch.

    /** A wholly unremarkable date: every stage passes and no message is claimed. */
    private static final String VALID_DATE = "20220101";

    /** 31 December: the upper bound of a 31-day month. */
    private static final String VALID_LAST_DAY_OF_YEAR = "20221231";

    /** A leap day in a year divisible by four but not by one hundred: the ordinary-divisor branch. */
    private static final String LEAP_DAY_ORDINARY = "20240229";

    /** A leap day in a common year: the ordinary-divisor branch, rejecting. */
    private static final String LEAP_DAY_COMMON_YEAR = "20230229";

    /**
     * A leap day in a century year that <em>is</em> a leap year: year within century zero selects the
     * four-hundred divisor and {@code 2000 % 400 == 0} accepts.
     */
    private static final String LEAP_DAY_CENTURY_ACCEPTED = "20000229";

    /**
     * A leap day in a century year that is <strong>not</strong> a leap year. This is the witness for
     * the divisor selection: {@code 1900 % 4 == 0} would accept it, {@code 1900 % 400 == 300} rejects
     * it, and the legacy rejects it.
     */
    private static final String LEAP_DAY_CENTURY_REJECTED = "19000229";

    /** Eight spaces: the {@code SPACES} half of the blankness test. */
    private static final String ALL_SPACES_DATE = "        ";

    /** Eight null characters: the {@code LOW-VALUES} half of the blankness test. */
    private static final String ALL_LOW_VALUES_DATE = "\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000";

    /** Month thirteen: outside {@code 88 WS-VALID-MONTH VALUES 1 THROUGH 12}. */
    private static final String MONTH_ABOVE_RANGE_DATE = "20221301";

    /** Month zero: below the same range. */
    private static final String MONTH_BELOW_RANGE_DATE = "20220001";

    /** A 31st in April, a 30-day month. */
    private static final String THIRTY_FIRST_OF_SHORT_MONTH_DATE = "20220431";

    /** A 30th of February. */
    private static final String THIRTIETH_OF_FEBRUARY_DATE = "20220230";

    /** Century eighteen: neither of the two the source accepts. */
    private static final String INVALID_CENTURY_DATE = "18220101";

    /** A non-digit inside the year slice. */
    private static final String NON_NUMERIC_YEAR_DATE = "2X220101";

    /** A blank month slice inside an otherwise populated image. */
    private static final String BLANK_MONTH_DATE = "2022  01";

    /** A blank day slice inside an otherwise populated image. */
    private static final String BLANK_DAY_DATE = "202201  ";

    /** Day zero: below {@code 88 WS-VALID-DAY VALUES 1 THROUGH 31}. */
    private static final String DAY_BELOW_RANGE_DATE = "20220100";

    /** Day thirty-two: above the same range. */
    private static final String DAY_ABOVE_RANGE_DATE = "20220132";

    /** A non-digit inside the day slice. */
    private static final String NON_NUMERIC_DAY_DATE = "202201XX";

    /** The hyphenated mask, {@code WS-DATE-FORMAT VALUE 'YYYY-MM-DD'}, {@code [app/cbl/CORPT00C.cbl:L72]}. */
    private static final String HYPHENATED_DATE = "2022-01-01";

    /** A message an earlier field on the same screen has already claimed. */
    private static final String CARRIED_IN_MESSAGE = "Account Filter Number must be a non zero";

    /** A current date used for the date-of-birth comparison. */
    private static final LocalDate CURRENT_DATE = LocalDate.of(2022, 7, 6);

    /** The service under test. Stateless, so a fresh instance per test costs nothing. */
    private DateValidationService service;

    @BeforeEach
    void createService() {
        service = new DateValidationService();
    }

    /**
     * Renders a flag-group image with its null characters made visible, for failure messages only.
     *
     * @param image the three-character group image
     * @return the same image with each null character shown as a period
     */
    private static String readableFlags(final String image) {
        return image.replace('\u0000', '.');
    }

    // ENTRY POINT TWO: the callable subprogram, CALL 'CSUTLDTC'.

    @Nested
    @DisplayName("the callable subprogram: ten feedback outcomes selected in source order")
    final class SubprogramFeedbackSelection {

        @Test
        @DisplayName("a good date selects the success token, whose severity and message number are zero")
        void aGoodDateSelectsTheSuccessToken() {
            final SubprogramResult result = service.validateDate(HYPHENATED_DATE, DateFormat.YYYY_MM_DD);

            assertThat(result.feedback()).isEqualTo(DateFeedback.DATE_IS_VALID);
            assertThat(result.severityCode()).isEqualTo(ORACLE_ACCEPTED_SEVERITY);
            assertThat(result.messageNumber()).isEqualTo(ORACLE_ZERO_MESSAGE_NUMBER);
            assertThat(result.resultText()).isEqualTo(ORACLE_TEXT_DATE_IS_VALID);
            assertThat(result.numericSeverity()).isZero();
        }

        @Test
        @DisplayName("an all-blank slot is insufficient data rather than a bad value")
        void anAllBlankSlotIsInsufficientData() {
            final SubprogramResult spaces =
                    service.validateDate(ALL_SPACES_DATE, DateFormat.YYYYMMDD);
            final SubprogramResult lowValues =
                    service.validateDate(ALL_LOW_VALUES_DATE, DateFormat.YYYYMMDD);

            assertThat(spaces.feedback()).isEqualTo(DateFeedback.INSUFFICIENT_DATA);
            assertThat(spaces.resultText()).isEqualTo(ORACLE_TEXT_INSUFFICIENT);
            assertThat(lowValues.feedback()).as("LOW-VALUES is the other half of the blankness test")
                    .isEqualTo(DateFeedback.INSUFFICIENT_DATA);
        }

        @Test
        @DisplayName("a non-digit where the picture wants a digit is non-numeric data")
        void aNonDigitWhereThePictureWantsADigitIsNonNumericData() {
            final SubprogramResult result = service.validateDate("2022-1X-01", DateFormat.YYYY_MM_DD);

            assertThat(result.feedback()).isEqualTo(DateFeedback.NON_NUMERIC_DATA);
            assertThat(result.resultText()).isEqualTo(ORACLE_TEXT_NON_NUMERIC_DATA);
            assertThat(result.messageNumber()).isEqualTo("2520");
        }

        @Test
        @DisplayName("a zero year is caught before parsing, because ISO parsing would accept year zero")
        void aZeroYearIsCaughtBeforeParsing() {
            final SubprogramResult result = service.validateDate("0000-01-01", DateFormat.YYYY_MM_DD);

            assertThat(result.feedback()).isEqualTo(DateFeedback.YEAR_IN_ERA_ZERO);
            assertThat(result.resultText()).isEqualTo(ORACLE_TEXT_YEAR_IN_ERA_ZERO);
            assertThat(result.messageNumber()).isEqualTo("2521");
        }

        @Test
        @DisplayName("a month outside one to twelve gets its own condition, not the general bad value")
        void aMonthOutsideTheRangeGetsItsOwnCondition() {
            final SubprogramResult above = service.validateDate("2022-13-01", DateFormat.YYYY_MM_DD);
            final SubprogramResult below = service.validateDate("2022-00-01", DateFormat.YYYY_MM_DD);

            assertThat(above.feedback()).isEqualTo(DateFeedback.INVALID_MONTH);
            assertThat(above.resultText()).isEqualTo(ORACLE_TEXT_INVALID_MONTH);
            assertThat(above.messageNumber()).isEqualTo("2517");
            assertThat(below.feedback()).isEqualTo(DateFeedback.INVALID_MONTH);
        }

        @Test
        @DisplayName("strict resolution refuses to normalise: a 30th of February is a bad date value")
        void strictResolutionRefusesToNormalise() {
            final SubprogramResult thirtiethOfFebruary =
                    service.validateDate("2022-02-30", DateFormat.YYYY_MM_DD);
            final SubprogramResult twentyNinthOfCommonYear =
                    service.validateDate("2023-02-29", DateFormat.YYYY_MM_DD);
            final SubprogramResult thirtyFirstOfApril =
                    service.validateDate("2022-04-31", DateFormat.YYYY_MM_DD);

            assertThat(thirtiethOfFebruary.feedback()).isEqualTo(DateFeedback.BAD_DATE_VALUE);
            assertThat(thirtiethOfFebruary.resultText()).isEqualTo(ORACLE_TEXT_DATEVALUE_ERROR);
            assertThat(thirtiethOfFebruary.messageNumber()).isEqualTo("2508");
            assertThat(twentyNinthOfCommonYear.feedback())
                    .as("a leap day in a common year must not roll into March")
                    .isEqualTo(DateFeedback.BAD_DATE_VALUE);
            assertThat(thirtyFirstOfApril.feedback()).isEqualTo(DateFeedback.BAD_DATE_VALUE);
        }

        @Test
        @DisplayName("a wrong separator is a bad date value, not non-numeric data")
        void aWrongSeparatorIsABadDateValue() {
            final SubprogramResult result = service.validateDate("2022/01/01", DateFormat.YYYY_MM_DD);

            assertThat(result.feedback()).isEqualTo(DateFeedback.BAD_DATE_VALUE);
        }

        @Test
        @DisplayName("the Lilian boundary is exact: 15 October 1582 is supported, the day before is not")
        void theLilianBoundaryIsExact() {
            final SubprogramResult firstSupported = service.validateDate("15821015", DateFormat.YYYYMMDD);
            final SubprogramResult dayBefore = service.validateDate("15821014", DateFormat.YYYYMMDD);

            assertThat(ORACLE_LILIAN_RANGE_START).isEqualTo(LocalDate.of(1582, 10, 15));
            assertThat(firstSupported.feedback()).isEqualTo(DateFeedback.DATE_IS_VALID);
            assertThat(dayBefore.feedback()).isEqualTo(DateFeedback.UNSUPPORTED_RANGE);
            assertThat(dayBefore.resultText()).isEqualTo(ORACLE_TEXT_UNSUPPORTED_RANGE);
            assertThat(dayBefore.messageNumber()).isEqualTo(ORACLE_TOLERATED_MESSAGE_NUMBER);
        }

        @Test
        @DisplayName("the compact mask inspects only the eight positions it describes")
        void theCompactMaskInspectsOnlyItsOwnPositions() {
            final SubprogramResult result = service.validateDate("2022010199", DateFormat.YYYYMMDD);

            assertThat(result.feedback()).as("the surplus two positions are not part of the picture")
                    .isEqualTo(DateFeedback.DATE_IS_VALID);
            assertThat(result.testedDate()).as("but the tested-date field still carries all ten")
                    .isEqualTo("2022010199");
        }

        @Test
        @DisplayName("both masks the estate transmits are accepted and each selects its own picture")
        void bothMasksAreAccepted() {
            assertThat(service.validateDate(HYPHENATED_DATE, DateFormat.YYYY_MM_DD).feedback())
                    .isEqualTo(DateFeedback.DATE_IS_VALID);
            assertThat(service.validateDate(VALID_DATE, DateFormat.YYYYMMDD).feedback())
                    .isEqualTo(DateFeedback.DATE_IS_VALID);
            assertThat(service.validateDate(VALID_DATE, DateFormat.YYYY_MM_DD).feedback())
                    .as("the compact image cannot satisfy the hyphenated picture")
                    .isNotEqualTo(DateFeedback.DATE_IS_VALID);
        }

        @Test
        @DisplayName("neither argument may be null")
        void neitherArgumentMayBeNull() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> service.validateDate(null, DateFormat.YYYY_MM_DD))
                    .withMessageContaining("candidateDate");
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> service.validateDate(HYPHENATED_DATE, (DateFormat) null))
                    .withMessageContaining("dateFormat");
        }
    }

    @Nested
    @DisplayName("the callable subprogram: a raw mask is resolved, never guessed at")
    final class SubprogramMaskResolution {

        @Test
        @DisplayName("the two masks the estate holds in work fields resolve to their pictures")
        void theTwoRealMasksResolve() {
            assertThat(service.validateDate(HYPHENATED_DATE, "YYYY-MM-DD").feedback())
                    .isEqualTo(DateFeedback.DATE_IS_VALID);
            assertThat(service.validateDate(VALID_DATE, "YYYYMMDD  ").feedback())
                    .isEqualTo(DateFeedback.DATE_IS_VALID);
        }

        @Test
        @DisplayName("an unpadded mask still resolves, because the move pads it to the linkage width")
        void anUnpaddedMaskStillResolves() {
            assertThat(service.validateDate(VALID_DATE, "YYYYMMDD").feedback())
                    .as("moved into LS-DATE-FORMAT PIC X(10), so it becomes the padded form")
                    .isEqualTo(DateFeedback.DATE_IS_VALID);
        }

        @Test
        @DisplayName("a mask the estate never transmits is a bad picture string, not a substituted guess")
        void anUnknownMaskIsABadPictureString() {
            final SubprogramResult reversed = service.validateDate(HYPHENATED_DATE, "DD-MM-YYYY");
            final SubprogramResult blank = service.validateDate(HYPHENATED_DATE, "");
            final SubprogramResult nonsense = service.validateDate(HYPHENATED_DATE, "x");

            assertThat(reversed.feedback()).isEqualTo(DateFeedback.BAD_PICTURE_STRING);
            assertThat(reversed.resultText()).isEqualTo(ORACLE_TEXT_BAD_PICTURE_STRING);
            assertThat(reversed.messageNumber()).isEqualTo("2518");
            assertThat(blank.feedback()).isEqualTo(DateFeedback.BAD_PICTURE_STRING);
            assertThat(nonsense.feedback()).isEqualTo(DateFeedback.BAD_PICTURE_STRING);
        }

        @Test
        @DisplayName("a bad picture string is rejected by the acceptance test, so no wrong verdict escapes")
        void aBadPictureStringIsRejected() {
            final SubprogramResult result = service.validateDate(HYPHENATED_DATE, "DD-MM-YYYY");

            assertThat(service.isDateAcceptable(result)).isFalse();
        }

        @Test
        @DisplayName("the mask is reported back at the linkage width, padded or truncated as moved")
        void theMaskIsReportedBackAtTheLinkageWidth() {
            assertThat(service.validateDate(HYPHENATED_DATE, "").maskUsed())
                    .isEqualTo(" ".repeat(ORACLE_LINKAGE_TEXT_WIDTH));
            assertThat(service.validateDate(HYPHENATED_DATE, "x").maskUsed())
                    .isEqualTo("x" + " ".repeat(ORACLE_LINKAGE_TEXT_WIDTH - 1));
            assertThat(service.validateDate(HYPHENATED_DATE, "YYYYMMDD").maskUsed())
                    .isEqualTo("YYYYMMDD  ");
        }

        @Test
        @DisplayName("neither argument may be null")
        void neitherArgumentMayBeNull() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> service.validateDate(null, "YYYY-MM-DD"))
                    .withMessageContaining("candidateDate");
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> service.validateDate(HYPHENATED_DATE, (String) null))
                    .withMessageContaining("formatMask");
        }
    }

    @Nested
    @DisplayName("the two-level acceptance test all four call sites apply")
    final class TwoLevelAcceptanceTest {

        @Test
        @DisplayName("level one: the severity code 0000 is accepted outright")
        void severityZeroIsAcceptedOutright() {
            final SubprogramResult result = service.validateDate(HYPHENATED_DATE, DateFormat.YYYY_MM_DD);

            assertThat(result.severityCode()).isEqualTo(ORACLE_ACCEPTED_SEVERITY);
            assertThat(service.isDateAcceptable(result)).isTrue();
        }

        @Test
        @DisplayName("level two: a non-zero severity carrying message number 2513 is still accepted")
        void theToleratedMessageNumberIsAcceptedDespiteANonZeroSeverity() {
            final SubprogramResult result = service.validateDate("15821014", DateFormat.YYYYMMDD);

            assertThat(result.severityCode()).as("the severity is not zero")
                    .isEqualTo(ORACLE_FAILURE_SEVERITY);
            assertThat(result.messageNumber()).isEqualTo(ORACLE_TOLERATED_MESSAGE_NUMBER);
            assertThat(service.isDateAcceptable(result))
                    .as("dropping this exemption would reject dates the legacy accepts")
                    .isTrue();
        }

        @Test
        @DisplayName("a non-zero severity with any other message number is rejected")
        void anyOtherFailureIsRejected() {
            for (final DateFeedback feedback : DateFeedback.values()) {
                if (feedback == DateFeedback.DATE_IS_VALID
                        || feedback == DateFeedback.UNSUPPORTED_RANGE) {
                    continue;
                }
                final SubprogramResult synthesised = new SubprogramResult(feedback,
                        ORACLE_FAILURE_SEVERITY,
                        String.format("%04d", feedback.getMessageNumber()),
                        ORACLE_TEXT_DATE_IS_INVALID,
                        " ".repeat(ORACLE_LINKAGE_TEXT_WIDTH),
                        " ".repeat(ORACLE_LINKAGE_TEXT_WIDTH));

                assertThat(service.isDateAcceptable(synthesised))
                        .as("outcome %s must be rejected", feedback)
                        .isFalse();
            }
        }

        @Test
        @DisplayName("both comparisons are on four-character strings, so neither field is parsed")
        void bothComparisonsAreOnCharacters() {
            final SubprogramResult numericallyZeroButNotTheLiteral = new SubprogramResult(
                    DateFeedback.INSUFFICIENT_DATA,
                    "   0",
                    "2513",
                    ORACLE_TEXT_INSUFFICIENT,
                    " ".repeat(ORACLE_LINKAGE_TEXT_WIDTH),
                    " ".repeat(ORACLE_LINKAGE_TEXT_WIDTH));

            assertThat(numericallyZeroButNotTheLiteral.severityCode())
                    .as("a space-padded zero is numerically zero but is not the literal 0000")
                    .isNotEqualTo(ORACLE_ACCEPTED_SEVERITY);
            assertThat(service.isDateAcceptable(numericallyZeroButNotTheLiteral))
                    .as("it is accepted only by the second level, on the tolerated message number")
                    .isTrue();
        }

        @Test
        @DisplayName("the result may not be null")
        void theResultMayNotBeNull() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> service.isDateAcceptable(null))
                    .withMessageContaining("result");
        }
    }

    @Nested
    @DisplayName("the 80-character result block: thirteen items whose widths sum to eighty")
    final class ResultBlockLayout {

        @Test
        @DisplayName("the rendered block is exactly eighty characters for every outcome")
        void theRenderedBlockIsAlwaysEighty() {
            for (final DateFeedback feedback : DateFeedback.values()) {
                final SubprogramResult result = new SubprogramResult(feedback,
                        ORACLE_FAILURE_SEVERITY,
                        String.format("%04d", feedback.getMessageNumber()),
                        ORACLE_TEXT_DATE_IS_INVALID,
                        " ".repeat(ORACLE_LINKAGE_TEXT_WIDTH),
                        " ".repeat(ORACLE_LINKAGE_TEXT_WIDTH));

                assertThat(result.render()).as("block width for %s", feedback)
                        .hasSize(ORACLE_RESULT_BLOCK_WIDTH);
            }
        }

        @Test
        @DisplayName("the block carries its literal label fillers in declaration order")
        void theBlockCarriesItsLabelsInOrder() {
            final String block =
                    service.validateDate(HYPHENATED_DATE, DateFormat.YYYY_MM_DD).render();

            assertThat(block).startsWith(ORACLE_ACCEPTED_SEVERITY + "Mesg Code: ");
            assertThat(block).contains("TstDate: " + HYPHENATED_DATE);
            assertThat(block).contains("Mask used:" + DateFormat.YYYY_MM_DD.getValue());
            assertThat(block).endsWith("    ");
            assertThat(block).hasSize(ORACLE_RESULT_BLOCK_WIDTH);
        }

        @Test
        @DisplayName("the message segment is the sixty-one character tail the callers overlay")
        void theMessageSegmentIsTheSixtyOneCharacterTail() {
            final SubprogramResult result =
                    service.validateDate(HYPHENATED_DATE, DateFormat.YYYY_MM_DD);

            assertThat(result.messageSegment()).hasSize(ORACLE_MESSAGE_SEGMENT_WIDTH);
            assertThat(result.messageSegment())
                    .as("it opens with the single-space filler that follows the message number")
                    .startsWith(" " + ORACLE_TEXT_DATE_IS_VALID);
            assertThat(result.render()).endsWith(result.messageSegment());
        }

        @Test
        @DisplayName("severity and message number are individually addressable, as the callers read them")
        void severityAndMessageNumberAreIndividuallyAddressable() {
            final SubprogramResult result = service.validateDate("15821014", DateFormat.YYYYMMDD);

            assertThat(result.severityCode()).hasSize(ORACLE_CODE_WIDTH);
            assertThat(result.messageNumber()).hasSize(ORACLE_CODE_WIDTH);
            assertThat(result.numericSeverity()).as("taken from the outcome, never parsed back")
                    .isEqualTo(result.feedback().getSeverity());
        }

        @Test
        @DisplayName("every component is checked against the width its PIC declares")
        void everyComponentIsWidthChecked() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new SubprogramResult(DateFeedback.DATE_IS_VALID, "000",
                            ORACLE_ZERO_MESSAGE_NUMBER, ORACLE_TEXT_DATE_IS_VALID,
                            " ".repeat(ORACLE_LINKAGE_TEXT_WIDTH),
                            " ".repeat(ORACLE_LINKAGE_TEXT_WIDTH)))
                    .withMessageContaining("severityCode")
                    .withMessageContaining(String.valueOf(ORACLE_CODE_WIDTH));
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new SubprogramResult(DateFeedback.DATE_IS_VALID,
                            ORACLE_ACCEPTED_SEVERITY, "25130", ORACLE_TEXT_DATE_IS_VALID,
                            " ".repeat(ORACLE_LINKAGE_TEXT_WIDTH),
                            " ".repeat(ORACLE_LINKAGE_TEXT_WIDTH)))
                    .withMessageContaining("messageNumber");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new SubprogramResult(DateFeedback.DATE_IS_VALID,
                            ORACLE_ACCEPTED_SEVERITY, ORACLE_ZERO_MESSAGE_NUMBER, "too short",
                            " ".repeat(ORACLE_LINKAGE_TEXT_WIDTH),
                            " ".repeat(ORACLE_LINKAGE_TEXT_WIDTH)))
                    .withMessageContaining("resultText")
                    .withMessageContaining(String.valueOf(ORACLE_RESULT_TEXT_WIDTH));
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new SubprogramResult(DateFeedback.DATE_IS_VALID,
                            ORACLE_ACCEPTED_SEVERITY, ORACLE_ZERO_MESSAGE_NUMBER,
                            ORACLE_TEXT_DATE_IS_VALID, "short",
                            " ".repeat(ORACLE_LINKAGE_TEXT_WIDTH)))
                    .withMessageContaining("testedDate");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new SubprogramResult(DateFeedback.DATE_IS_VALID,
                            ORACLE_ACCEPTED_SEVERITY, ORACLE_ZERO_MESSAGE_NUMBER,
                            ORACLE_TEXT_DATE_IS_VALID,
                            " ".repeat(ORACLE_LINKAGE_TEXT_WIDTH), "short"))
                    .withMessageContaining("maskUsed");
        }

        @Test
        @DisplayName("no component may be null")
        void noComponentMayBeNull() {
            final String ten = " ".repeat(ORACLE_LINKAGE_TEXT_WIDTH);

            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new SubprogramResult(null, ORACLE_ACCEPTED_SEVERITY,
                            ORACLE_ZERO_MESSAGE_NUMBER, ORACLE_TEXT_DATE_IS_VALID, ten, ten))
                    .withMessageContaining("feedback");
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new SubprogramResult(DateFeedback.DATE_IS_VALID, null,
                            ORACLE_ZERO_MESSAGE_NUMBER, ORACLE_TEXT_DATE_IS_VALID, ten, ten))
                    .withMessageContaining("severityCode");
        }

        @Test
        @DisplayName("the record is a value type: equal components mean equal results")
        void theRecordIsAValueType() {
            final SubprogramResult first =
                    service.validateDate(HYPHENATED_DATE, DateFormat.YYYY_MM_DD);
            final SubprogramResult second =
                    service.validateDate(HYPHENATED_DATE, DateFormat.YYYY_MM_DD);

            assertThat(first).isEqualTo(second).hasSameHashCodeAs(second);
            assertThat(first.toString()).contains(ORACLE_ACCEPTED_SEVERITY);
        }
    }

    @Nested
    @DisplayName("the feedback enumeration: ten outcomes with decoded severity and message numbers")
    final class FeedbackEnumerationContract {

        @Test
        @DisplayName("exactly ten outcomes exist: nine declared tokens plus the WHEN OTHER tail")
        void exactlyTenOutcomesExist() {
            assertThat(DateFeedback.values()).hasSize(10);
            assertThat(EnumSet.allOf(DateFeedback.class))
                    .containsExactly(DateFeedback.DATE_IS_VALID,
                            DateFeedback.INSUFFICIENT_DATA,
                            DateFeedback.BAD_DATE_VALUE,
                            DateFeedback.INVALID_ERA,
                            DateFeedback.UNSUPPORTED_RANGE,
                            DateFeedback.INVALID_MONTH,
                            DateFeedback.BAD_PICTURE_STRING,
                            DateFeedback.NON_NUMERIC_DATA,
                            DateFeedback.YEAR_IN_ERA_ZERO,
                            DateFeedback.UNRECOGNISED_FEEDBACK);
        }

        @Test
        @DisplayName("the success token carries severity zero; every failure token carries severity three")
        void severitiesAreDecodedFromTheTokens() {
            assertThat(DateFeedback.DATE_IS_VALID.getSeverity()).isZero();
            for (final DateFeedback feedback : DateFeedback.values()) {
                if (feedback == DateFeedback.DATE_IS_VALID) {
                    continue;
                }
                assertThat(feedback.getSeverity()).as("severity of %s", feedback).isEqualTo(3);
            }
        }

        @Test
        @DisplayName("each message number is the decoded halfword of its own feedback token")
        void messageNumbersAreDecodedHalfwords() {
            assertThat(DateFeedback.DATE_IS_VALID.getMessageNumber()).isZero();
            assertThat(DateFeedback.INSUFFICIENT_DATA.getMessageNumber()).isEqualTo(2507);
            assertThat(DateFeedback.BAD_DATE_VALUE.getMessageNumber()).isEqualTo(2508);
            assertThat(DateFeedback.INVALID_ERA.getMessageNumber()).isEqualTo(2509);
            assertThat(DateFeedback.UNSUPPORTED_RANGE.getMessageNumber())
                    .as("0x09D1 is decimal 2513, the number both callers tolerate")
                    .isEqualTo(2513);
            assertThat(DateFeedback.INVALID_MONTH.getMessageNumber()).isEqualTo(2517);
            assertThat(DateFeedback.BAD_PICTURE_STRING.getMessageNumber()).isEqualTo(2518);
            assertThat(DateFeedback.NON_NUMERIC_DATA.getMessageNumber()).isEqualTo(2520);
            assertThat(DateFeedback.YEAR_IN_ERA_ZERO.getMessageNumber()).isEqualTo(2521);
            assertThat(DateFeedback.UNRECOGNISED_FEEDBACK.getMessageNumber())
                    .as("the WHEN OTHER tail has no token, so its number is zero")
                    .isZero();
        }

        @Test
        @DisplayName("only the unsupported-range outcome carries the tolerated number")
        void onlyUnsupportedRangeCarriesTheToleratedNumber() {
            final int tolerated = Integer.parseInt(ORACLE_TOLERATED_MESSAGE_NUMBER);

            assertThat(EnumSet.allOf(DateFeedback.class).stream()
                    .filter(feedback -> feedback.getMessageNumber() == tolerated)
                    .toList())
                    .containsExactly(DateFeedback.UNSUPPORTED_RANGE);
        }

        @Test
        @DisplayName("the unrecognised tail is neither accepted severity nor tolerated number")
        void theUnrecognisedTailIsRejectedByBothLevels() {
            final SubprogramResult tail = new SubprogramResult(DateFeedback.UNRECOGNISED_FEEDBACK,
                    ORACLE_FAILURE_SEVERITY,
                    ORACLE_ZERO_MESSAGE_NUMBER,
                    ORACLE_TEXT_DATE_IS_INVALID,
                    " ".repeat(ORACLE_LINKAGE_TEXT_WIDTH),
                    " ".repeat(ORACLE_LINKAGE_TEXT_WIDTH));

            assertThat(service.isDateAcceptable(tail)).isFalse();
        }
    }

    // ENTRY POINT ONE: the eleven-paragraph copybook cascade, stage by stage in source order.

    @Nested
    @DisplayName("cascade stage one, EDIT-YEAR-CCYY: supplied, then four digits, then a known century")
    final class CascadeYearStage {

        @Test
        @DisplayName("a blank year is reported as blank, not as invalid: the two states are distinct")
        void aBlankYearIsReportedAsBlank() {
            final DateEditResult spaces = service.validateCcyymmddDate(ALL_SPACES_DATE);
            final DateEditResult lowValues = service.validateCcyymmddDate(ALL_LOW_VALUES_DATE);

            assertThat(spaces.inputError()).isTrue();
            assertThat(spaces.yearFlag()).isEqualTo(DateEditFlag.BLANK);
            assertThat(spaces.returnMessage()).isEqualTo(ORACLE_YEAR_NOT_SUPPLIED);
            assertThat(lowValues.yearFlag())
                    .as("LOW-VALUES is the other half of the paired blankness test")
                    .isEqualTo(DateEditFlag.BLANK);
            assertThat(lowValues.returnMessage()).isEqualTo(ORACLE_YEAR_NOT_SUPPLIED);
        }

        @Test
        @DisplayName("a slice mixing null characters and spaces is not blank, because each half tests the whole field")
        void aMixedSliceIsNotBlank() {
            final DateEditResult result = service.validateCcyymmddDate("\u0000\u0000  0101");

            assertThat(result.yearFlag())
                    .as("neither the LOW-VALUES half nor the SPACES half matches the whole slice")
                    .isEqualTo(DateEditFlag.NOT_OK);
            assertThat(result.returnMessage()).isEqualTo(ORACLE_YEAR_NOT_FOUR_DIGITS);
        }

        @Test
        @DisplayName("a non-numeric year takes the four-digit message, the only suffix with no colon")
        void aNonNumericYearTakesTheFourDigitMessage() {
            final DateEditResult result = service.validateCcyymmddDate(NON_NUMERIC_YEAR_DATE);

            assertThat(result.inputError()).isTrue();
            assertThat(result.yearFlag()).isEqualTo(DateEditFlag.NOT_OK);
            assertThat(result.returnMessage()).isEqualTo(ORACLE_YEAR_NOT_FOUR_DIGITS);
            assertThat(result.returnMessage()).doesNotContain(":");
        }

        @Test
        @DisplayName("only the two centuries the source names are accepted")
        void onlyTheTwoNamedCenturiesAreAccepted() {
            assertThat(service.validateCcyymmddDate(ORACLE_THIS_CENTURY + "220101").yearFlag())
                    .isEqualTo(DateEditFlag.VALID);
            assertThat(service.validateCcyymmddDate(ORACLE_LAST_CENTURY + "220101").yearFlag())
                    .isEqualTo(DateEditFlag.VALID);

            final DateEditResult rejected = service.validateCcyymmddDate(INVALID_CENTURY_DATE);
            assertThat(rejected.inputError()).isTrue();
            assertThat(rejected.yearFlag()).isEqualTo(DateEditFlag.NOT_OK);
            assertThat(rejected.returnMessage()).isEqualTo(ORACLE_CENTURY_NOT_VALID);
        }

        @Test
        @DisplayName("every century outside nineteen and twenty is refused")
        void everyOtherCenturyIsRefused() {
            for (int century = 0; century <= 99; century++) {
                if (century == ORACLE_THIS_CENTURY || century == ORACLE_LAST_CENTURY) {
                    continue;
                }
                final String candidate = String.format("%02d220101", century);

                assertThat(service.validateCcyymmddDate(candidate).yearFlag())
                        .as("century %02d", century)
                        .isEqualTo(DateEditFlag.NOT_OK);
            }
        }

        @Test
        @DisplayName("a year failure does not abandon the range: the month and day stages still run")
        void aYearFailureDoesNotAbandonTheRange() {
            final DateEditResult result = service.validateCcyymmddDate(INVALID_CENTURY_DATE);

            assertThat(result.yearFlag()).isEqualTo(DateEditFlag.NOT_OK);
            assertThat(result.monthFlag())
                    .as("the year stage's exit lands on the month stage, which passed")
                    .isEqualTo(DateEditFlag.VALID);
            assertThat(result.dayFlag()).isEqualTo(DateEditFlag.VALID);
        }

        @Test
        @DisplayName("a blank year and a bad month are both reported on one pass")
        void aBlankYearAndABadMonthAreBothReported() {
            final DateEditResult result = service.validateCcyymmddDate("    1301");

            assertThat(result.yearFlag()).isEqualTo(DateEditFlag.BLANK);
            assertThat(result.monthFlag()).isEqualTo(DateEditFlag.NOT_OK);
            assertThat(result.returnMessage())
                    .as("the year claimed the message first, so the month's text is suppressed")
                    .isEqualTo(ORACLE_YEAR_NOT_SUPPLIED);
        }
    }

    @Nested
    @DisplayName("cascade stage two, EDIT-MONTH: supplied, then in range, then numeric")
    final class CascadeMonthStage {

        @Test
        @DisplayName("a blank month is reported as blank with its own message")
        void aBlankMonthIsReportedAsBlank() {
            final DateEditResult result = service.validateCcyymmddDate(BLANK_MONTH_DATE);

            assertThat(result.inputError()).isTrue();
            assertThat(result.monthFlag()).isEqualTo(DateEditFlag.BLANK);
            assertThat(result.returnMessage()).isEqualTo(ORACLE_MONTH_NOT_SUPPLIED);
            assertThat(result.yearFlag()).as("the year stage passed").isEqualTo(DateEditFlag.VALID);
        }

        @Test
        @DisplayName("a month outside one to twelve is refused at both ends")
        void aMonthOutsideTheRangeIsRefused() {
            final DateEditResult above = service.validateCcyymmddDate(MONTH_ABOVE_RANGE_DATE);
            final DateEditResult below = service.validateCcyymmddDate(MONTH_BELOW_RANGE_DATE);

            assertThat(above.monthFlag()).isEqualTo(DateEditFlag.NOT_OK);
            assertThat(above.returnMessage()).isEqualTo(ORACLE_MONTH_OUT_OF_RANGE);
            assertThat(below.monthFlag()).isEqualTo(DateEditFlag.NOT_OK);
            assertThat(below.returnMessage()).isEqualTo(ORACLE_MONTH_OUT_OF_RANGE);
        }

        @Test
        @DisplayName("all twelve months in range are accepted")
        void allTwelveMonthsAreAccepted() {
            for (int month = 1; month <= 12; month++) {
                final String candidate = String.format("2022%02d01", month);

                assertThat(service.validateCcyymmddDate(candidate).monthFlag())
                        .as("month %02d", month)
                        .isEqualTo(DateEditFlag.VALID);
            }
        }

        @Test
        @DisplayName("the range test precedes the numeric test, and both branches emit the same text")
        void bothMonthFailureBranchesEmitTheSameText() {
            final DateEditResult nonNumeric = service.validateCcyymmddDate("2022XX01");
            final DateEditResult outOfRange = service.validateCcyymmddDate(MONTH_ABOVE_RANGE_DATE);

            assertThat(nonNumeric.returnMessage())
                    .as("a non-numeric slice cannot hold any in-range value, so it fails the range test first")
                    .isEqualTo(ORACLE_MONTH_OUT_OF_RANGE);
            assertThat(nonNumeric.returnMessage()).isEqualTo(outOfRange.returnMessage());
            assertThat(nonNumeric.monthFlag()).isEqualTo(outOfRange.monthFlag());
        }

        @Test
        @DisplayName("the month message opens with an unspaced colon, unlike the blank-month message")
        void theTwoMonthMessagesDifferInSpacing() {
            assertThat(ORACLE_MONTH_OUT_OF_RANGE).startsWith(":");
            assertThat(ORACLE_MONTH_NOT_SUPPLIED).startsWith(" : ");
        }

        @Test
        @DisplayName("a month failure leaves the day flag untouched, because the day stage opens valid")
        void aMonthFailureLeavesTheDayFlagValid() {
            final DateEditResult result = service.validateCcyymmddDate(MONTH_ABOVE_RANGE_DATE);

            assertThat(result.monthFlag()).isEqualTo(DateEditFlag.NOT_OK);
            assertThat(result.dayFlag())
                    .as("the day slice 01 is supplied, numeric and in range")
                    .isEqualTo(DateEditFlag.VALID);
        }
    }

    @Nested
    @DisplayName("cascade stage three, EDIT-DAY: supplied, then numeric, then in range")
    final class CascadeDayStage {

        @Test
        @DisplayName("a blank day is reported as blank with its own message")
        void aBlankDayIsReportedAsBlank() {
            final DateEditResult result = service.validateCcyymmddDate(BLANK_DAY_DATE);

            assertThat(result.inputError()).isTrue();
            assertThat(result.dayFlag()).isEqualTo(DateEditFlag.BLANK);
            assertThat(result.returnMessage()).isEqualTo(ORACLE_DAY_NOT_SUPPLIED);
        }

        @Test
        @DisplayName("a non-numeric day takes the range message, lower-cased and unspaced as the source has it")
        void aNonNumericDayTakesTheRangeMessage() {
            final DateEditResult result = service.validateCcyymmddDate(NON_NUMERIC_DAY_DATE);

            assertThat(result.dayFlag()).isEqualTo(DateEditFlag.NOT_OK);
            assertThat(result.returnMessage()).isEqualTo(ORACLE_DAY_OUT_OF_RANGE);
            assertThat(result.returnMessage()).as("lower-case day where the month peer capitalises")
                    .startsWith(":day");
        }

        @Test
        @DisplayName("a day outside one to thirty-one is refused at both ends")
        void aDayOutsideTheRangeIsRefused() {
            final DateEditResult below = service.validateCcyymmddDate(DAY_BELOW_RANGE_DATE);
            final DateEditResult above = service.validateCcyymmddDate(DAY_ABOVE_RANGE_DATE);

            assertThat(below.dayFlag()).isEqualTo(DateEditFlag.NOT_OK);
            assertThat(below.returnMessage()).isEqualTo(ORACLE_DAY_OUT_OF_RANGE);
            assertThat(above.dayFlag()).isEqualTo(DateEditFlag.NOT_OK);
            assertThat(above.returnMessage()).isEqualTo(ORACLE_DAY_OUT_OF_RANGE);
        }

        @Test
        @DisplayName("every day from one to thirty-one is accepted by this stage in a 31-day month")
        void everyDayInRangeIsAcceptedByThisStage() {
            for (int day = 1; day <= 31; day++) {
                final String candidate = String.format("202201%02d", day);

                assertThat(service.validateCcyymmddDate(candidate).dayFlag())
                        .as("day %02d of January", day)
                        .isEqualTo(DateEditFlag.VALID);
            }
        }

        @Test
        @DisplayName("the stage opens by setting the day flag valid, not invalid")
        void theStageOpensValid() {
            final DateEditResult yearFailure = service.validateCcyymmddDate(INVALID_CENTURY_DATE);

            assertThat(yearFailure.dayFlag())
                    .as("the head paragraph wrote NOT_OK, so a valid day flag proves the stage re-set it")
                    .isEqualTo(DateEditFlag.VALID);
        }
    }

    @Nested
    @DisplayName("cascade stage four, EDIT-DAY-MONTH-YEAR: the only stage that judges the combination")
    final class CascadeCombinationStage {

        @Test
        @DisplayName("a 31st in a short month clears both the day and the month flag")
        void aThirtyFirstInAShortMonthClearsBothFlags() {
            final DateEditResult result =
                    service.validateCcyymmddDate(THIRTY_FIRST_OF_SHORT_MONTH_DATE);

            assertThat(result.inputError()).isTrue();
            assertThat(result.dayFlag()).isEqualTo(DateEditFlag.NOT_OK);
            assertThat(result.monthFlag())
                    .as("the month is individually valid, yet the combination clears it too")
                    .isEqualTo(DateEditFlag.NOT_OK);
            assertThat(result.yearFlag()).as("the year is left alone on this branch")
                    .isEqualTo(DateEditFlag.VALID);
            assertThat(result.returnMessage()).isEqualTo(ORACLE_CANNOT_HAVE_31_DAYS);
        }

        @Test
        @DisplayName("the seven 31-day months are exactly those the condition name enumerates")
        void theSevenThirtyOneDayMonthsAreTheEnumeratedOnes() {
            for (int month = 1; month <= 12; month++) {
                final String candidate = String.format("2022%02d31", month);
                final DateEditResult result = service.validateCcyymmddDate(candidate);

                if (ORACLE_THIRTY_ONE_DAY_MONTHS.contains(month)) {
                    assertThat(result.inputError()).as("month %02d has a 31st", month).isFalse();
                    assertThat(result.dayFlag()).isEqualTo(DateEditFlag.VALID);
                } else {
                    assertThat(result.inputError()).as("month %02d has no 31st", month).isTrue();
                    assertThat(result.returnMessage()).isEqualTo(ORACLE_CANNOT_HAVE_31_DAYS);
                }
            }
        }

        @Test
        @DisplayName("a 30th of February has its own message, distinct from the 31-day one")
        void aThirtiethOfFebruaryHasItsOwnMessage() {
            final DateEditResult result = service.validateCcyymmddDate(THIRTIETH_OF_FEBRUARY_DATE);

            assertThat(result.inputError()).isTrue();
            assertThat(result.dayFlag()).isEqualTo(DateEditFlag.NOT_OK);
            assertThat(result.monthFlag()).isEqualTo(DateEditFlag.NOT_OK);
            assertThat(result.returnMessage()).isEqualTo(ORACLE_CANNOT_HAVE_30_DAYS);
            assertThat(ORACLE_CANNOT_HAVE_30_DAYS).isNotEqualTo(ORACLE_CANNOT_HAVE_31_DAYS);
        }

        @Test
        @DisplayName("only February rejects a 30th; the other short months reject only the 31st")
        void onlyFebruaryRejectsAThirtieth() {
            for (int month = 1; month <= 12; month++) {
                final String candidate = String.format("2022%02d30", month);
                final DateEditResult result = service.validateCcyymmddDate(candidate);

                if (month == ORACLE_FEBRUARY) {
                    assertThat(result.returnMessage()).isEqualTo(ORACLE_CANNOT_HAVE_30_DAYS);
                } else {
                    assertThat(result.inputError()).as("month %02d has a 30th", month).isFalse();
                }
            }
        }

        @Test
        @DisplayName("a leap day in a year divisible by four is accepted through the ordinary divisor")
        void aLeapDayInAnOrdinaryLeapYearIsAccepted() {
            final DateEditResult result = service.validateCcyymmddDate(LEAP_DAY_ORDINARY);

            assertThat(result.inputError()).isFalse();
            assertThat(result.returnMessage()).isEqualTo(ORACLE_NO_MESSAGE);
            assertThat(result.dayFlag()).isEqualTo(DateEditFlag.VALID);
        }

        @Test
        @DisplayName("a leap day in a common year clears all three flags and runs two sentences together")
        void aLeapDayInACommonYearClearsAllThreeFlags() {
            final DateEditResult result = service.validateCcyymmddDate(LEAP_DAY_COMMON_YEAR);

            assertThat(result.inputError()).isTrue();
            assertThat(result.yearFlag()).as("this is the one branch that also clears the year")
                    .isEqualTo(DateEditFlag.NOT_OK);
            assertThat(result.monthFlag()).isEqualTo(DateEditFlag.NOT_OK);
            assertThat(result.dayFlag()).isEqualTo(DateEditFlag.NOT_OK);
            assertThat(result.returnMessage()).isEqualTo(ORACLE_NOT_A_LEAP_YEAR);
            assertThat(result.returnMessage())
                    .as("no space after the first period; the source runs two sentences together")
                    .contains("leap year.Cannot");
        }

        @Test
        @DisplayName("the divisor selection is load bearing: 1900 is refused although 1900 % 4 is zero")
        void theDivisorSelectionIsLoadBearing() {
            assertThat(1900 % 4).as("a single-divisor translation would accept 29 February 1900").isZero();
            assertThat(1900 % 400).isNotZero();

            final DateEditResult refused = service.validateCcyymmddDate(LEAP_DAY_CENTURY_REJECTED);
            assertThat(refused.inputError()).isTrue();
            assertThat(refused.returnMessage()).isEqualTo(ORACLE_NOT_A_LEAP_YEAR);
        }

        @Test
        @DisplayName("a century year divisible by four hundred is accepted through the century divisor")
        void aCenturyYearDivisibleByFourHundredIsAccepted() {
            assertThat(2000 % 400).isZero();

            final DateEditResult accepted = service.validateCcyymmddDate(LEAP_DAY_CENTURY_ACCEPTED);
            assertThat(accepted.inputError()).isFalse();
            assertThat(accepted.returnMessage()).isEqualTo(ORACLE_NO_MESSAGE);
        }

        @Test
        @DisplayName("the divisor is chosen by the year within the century being zero, not by the whole year")
        void theDivisorIsChosenByTheYearWithinTheCentury() {
            assertThat(service.validateCcyymmddDate("19960229").inputError())
                    .as("1996: year within century 96, ordinary divisor, 1996 %% 4 == 0")
                    .isFalse();
            assertThat(service.validateCcyymmddDate("19970229").inputError())
                    .as("1997: ordinary divisor, remainder 1")
                    .isTrue();
            assertThat(service.validateCcyymmddDate("20000229").inputError())
                    .as("2000: year within century 00, century divisor, 2000 %% 400 == 0")
                    .isFalse();
            assertThat(service.validateCcyymmddDate("19000229").inputError())
                    .as("1900: year within century 00, century divisor, remainder 300")
                    .isTrue();
        }

        @Test
        @DisplayName("a 28th of February is accepted in every year, leap or common")
        void aTwentyEighthOfFebruaryIsAlwaysAccepted() {
            assertThat(service.validateCcyymmddDate("20220228").inputError()).isFalse();
            assertThat(service.validateCcyymmddDate("20240228").inputError()).isFalse();
            assertThat(service.validateCcyymmddDate("19000228").inputError()).isFalse();
            assertThat(service.validateCcyymmddDate("20000228").inputError()).isFalse();
        }

        @Test
        @DisplayName("a combination failure skips the Language-Environment stage, leaving flags cleared")
        void aCombinationFailureSkipsTheLanguageEnvironmentStage() {
            final DateEditResult result = service.validateCcyymmddDate(LEAP_DAY_COMMON_YEAR);

            assertThat(result.flagsImage())
                    .as("had the range continued, the exit paragraph would have overwritten all three as valid")
                    .isEqualTo("000");
            assertThat(readableFlags(result.flagsImage())).isEqualTo("000");
        }
    }

    @Nested
    @DisplayName("cascade stage five and its exit: reached only while the flag group reads low values")
    final class CascadeLanguageEnvironmentStage {

        @Test
        @DisplayName("a date that clears all three field stages comes back wholly valid")
        void aDateThatClearsEveryStageComesBackValid() {
            final DateEditResult result = service.validateCcyymmddDate(VALID_DATE);

            assertThat(result.inputError()).isFalse();
            assertThat(result.yearFlag()).isEqualTo(DateEditFlag.VALID);
            assertThat(result.monthFlag()).isEqualTo(DateEditFlag.VALID);
            assertThat(result.dayFlag()).isEqualTo(DateEditFlag.VALID);
            assertThat(result.returnMessage()).isEqualTo(ORACLE_NO_MESSAGE);
        }

        @Test
        @DisplayName("the all-valid group image is three null characters, the LOW-VALUES the guard tests")
        void theAllValidGroupImageIsLowValues() {
            final DateEditResult result = service.validateCcyymmddDate(VALID_LAST_DAY_OF_YEAR);

            assertThat(result.flagsImage()).isEqualTo("\u0000\u0000\u0000");
            assertThat(readableFlags(result.flagsImage())).isEqualTo("...");
        }

        @Test
        @DisplayName("any field-stage failure closes the guard, so the group is never all low values")
        void anyFieldStageFailureClosesTheGuard() {
            final String[] failures = {ALL_SPACES_DATE, INVALID_CENTURY_DATE, NON_NUMERIC_YEAR_DATE,
                BLANK_MONTH_DATE, MONTH_ABOVE_RANGE_DATE, BLANK_DAY_DATE, DAY_ABOVE_RANGE_DATE,
                NON_NUMERIC_DAY_DATE};

            for (final String candidate : failures) {
                final DateEditResult result = service.validateCcyymmddDate(candidate);

                assertThat(result.flagsImage()).as("group image for [%s]", candidate)
                        .isNotEqualTo("\u0000\u0000\u0000");
                assertThat(result.inputError()).isTrue();
            }
        }

        @Test
        @DisplayName("the guard is evaluated, not hard-wired: reachability follows the flag group")
        void theGuardIsEvaluatedRatherThanHardWired() {
            assertThat(service.validateCcyymmddDate(VALID_DATE).flagsImage())
                    .as("all three stages cleared, so the guard opened and the exit re-marked the group")
                    .isEqualTo("\u0000\u0000\u0000");
            assertThat(service.validateCcyymmddDate(MONTH_ABOVE_RANGE_DATE).flagsImage())
                    .as("one stage failed, so the guard closed and the group kept its failure")
                    .isNotEqualTo("\u0000\u0000\u0000");
        }
    }

    @Nested
    @DisplayName("the accumulated message is first-wins across the whole range")
    final class MessageAccumulation {

        @Test
        @DisplayName("a message already claimed by an earlier field is never overwritten")
        void anAlreadyClaimedMessageIsNeverOverwritten() {
            final DateEditResult result =
                    service.validateCcyymmddDate(ALL_SPACES_DATE, CARRIED_IN_MESSAGE);

            assertThat(result.returnMessage()).isEqualTo(CARRIED_IN_MESSAGE);
            assertThat(result.inputError()).as("the flags are still set even though the text is not")
                    .isTrue();
            assertThat(result.yearFlag()).isEqualTo(DateEditFlag.BLANK);
        }

        @Test
        @DisplayName("an all-spaces carried-in message still counts as blank and may be claimed")
        void anAllSpacesCarriedInMessageMayBeClaimed() {
            final DateEditResult result = service.validateCcyymmddDate(ALL_SPACES_DATE, "   ");

            assertThat(result.returnMessage()).isEqualTo(ORACLE_YEAR_NOT_SUPPLIED);
        }

        @Test
        @DisplayName("the earliest failing stage keeps the message when several stages fail")
        void theEarliestFailingStageKeepsTheMessage() {
            assertThat(service.validateCcyymmddDate("    13XX").returnMessage())
                    .as("year, month and day all fail; the year stage runs first")
                    .isEqualTo(ORACLE_YEAR_NOT_SUPPLIED);
            assertThat(service.validateCcyymmddDate("20221399").returnMessage())
                    .as("month and day both fail; the month stage runs first")
                    .isEqualTo(ORACLE_MONTH_OUT_OF_RANGE);
        }

        @Test
        @DisplayName("the convenience overload starts from the blank state")
        void theConvenienceOverloadStartsBlank() {
            assertThat(service.validateCcyymmddDate(ALL_SPACES_DATE).returnMessage())
                    .isEqualTo(service.validateCcyymmddDate(ALL_SPACES_DATE, ORACLE_NO_MESSAGE)
                            .returnMessage());
        }

        @Test
        @DisplayName("a passing date claims no message at all")
        void aPassingDateClaimsNoMessage() {
            assertThat(service.validateCcyymmddDate(VALID_DATE).returnMessage())
                    .isEqualTo(ORACLE_NO_MESSAGE);
            assertThat(service.validateCcyymmddDate(VALID_DATE, CARRIED_IN_MESSAGE).returnMessage())
                    .as("and it leaves an earlier claim untouched")
                    .isEqualTo(CARRIED_IN_MESSAGE);
        }

        @Test
        @DisplayName("the thirteen suffixes carry the source's irregular spacing")
        void theSuffixesCarryTheSourcesIrregularSpacing() {
            assertThat(ORACLE_YEAR_NOT_SUPPLIED).startsWith(" : ");
            assertThat(ORACLE_MONTH_NOT_SUPPLIED).startsWith(" : ");
            assertThat(ORACLE_DAY_NOT_SUPPLIED).startsWith(" : ");
            assertThat(ORACLE_CENTURY_NOT_VALID).startsWith(" : ");
            assertThat(ORACLE_MONTH_OUT_OF_RANGE).startsWith(": ");
            assertThat(ORACLE_DAY_OUT_OF_RANGE).startsWith(":d");
            assertThat(ORACLE_CANNOT_HAVE_31_DAYS).startsWith(":C");
            assertThat(ORACLE_CANNOT_HAVE_30_DAYS).startsWith(":C");
            assertThat(ORACLE_NOT_A_LEAP_YEAR).startsWith(":N");
            assertThat(ORACLE_YEAR_NOT_FOUR_DIGITS).doesNotContain(":");
            assertThat(ORACLE_DATE_IN_FUTURE).endsWith(" ");
        }
    }

    @Nested
    @DisplayName("the fixed-width move into WS-EDIT-DATE-CCYYMMDD pads right and truncates right")
    final class FixedWidthMoveSemantics {

        @Test
        @DisplayName("a short value is space padded on the right, so its tail slices read as blank")
        void aShortValueIsSpacePaddedOnTheRight() {
            final DateEditResult result = service.validateCcyymmddDate("2022");

            assertThat(result.yearFlag()).as("the four supplied characters land in the year slice")
                    .isEqualTo(DateEditFlag.VALID);
            assertThat(result.monthFlag()).isEqualTo(DateEditFlag.BLANK);
            assertThat(result.dayFlag()).isEqualTo(DateEditFlag.BLANK);
            assertThat(result.returnMessage()).isEqualTo(ORACLE_MONTH_NOT_SUPPLIED);
        }

        @Test
        @DisplayName("a long value loses its rightmost excess, which is the opposite of a JUST RIGHT field")
        void aLongValueLosesItsRightmostExcess() {
            final DateEditResult result = service.validateCcyymmddDate(VALID_DATE + "1234");

            assertThat(result.inputError()).as("only the leading eight characters are examined")
                    .isFalse();
            assertThat(result.flagsImage()).isEqualTo("\u0000\u0000\u0000");
        }

        @Test
        @DisplayName("an empty value becomes eight spaces and is reported as a blank year")
        void anEmptyValueBecomesEightSpaces() {
            final DateEditResult result = service.validateCcyymmddDate("");

            assertThat(result.yearFlag()).isEqualTo(DateEditFlag.BLANK);
            assertThat(result.returnMessage()).isEqualTo(ORACLE_YEAR_NOT_SUPPLIED);
        }

        @Test
        @DisplayName("a value of exactly the declared width passes through unchanged")
        void anExactWidthValuePassesThroughUnchanged() {
            assertThat(VALID_DATE).hasSize(ORACLE_CCYYMMDD_WIDTH);
            assertThat(service.validateCcyymmddDate(VALID_DATE).inputError()).isFalse();
        }

        @Test
        @DisplayName("the linkage move into LS-DATE uses the ten-character width")
        void theLinkageMoveUsesTheTenCharacterWidth() {
            assertThat(service.validateDate("2022", DateFormat.YYYY_MM_DD).testedDate())
                    .isEqualTo("2022      ");
            assertThat(service.validateDate("2022-01-01-EXTRA", DateFormat.YYYY_MM_DD).testedDate())
                    .isEqualTo(HYPHENATED_DATE);
        }
    }

    @Nested
    @DisplayName("EDIT-DATE-OF-BIRTH: a reasonableness check that sits outside the main cascade")
    final class DateOfBirthRange {

        @Test
        @DisplayName("a date strictly in the past is accepted and claims no message")
        void aDateStrictlyInThePastIsAccepted() {
            final DateEditResult result = service.validateDateOfBirth("19800101", CURRENT_DATE);

            assertThat(result.inputError()).isFalse();
            assertThat(result.returnMessage()).isEqualTo(ORACLE_NO_MESSAGE);
            assertThat(result.flagsImage()).as("the range is entered with all three flags valid")
                    .isEqualTo("\u0000\u0000\u0000");
        }

        @Test
        @DisplayName("the comparison is strict, so today itself is refused")
        void todayItselfIsRefused() {
            final DateEditResult result = service.validateDateOfBirth("20220706", CURRENT_DATE);

            assertThat(result.inputError()).isTrue();
            assertThat(result.returnMessage()).isEqualTo(ORACLE_DATE_IN_FUTURE);
        }

        @Test
        @DisplayName("a future date is refused")
        void aFutureDateIsRefused() {
            final DateEditResult result = service.validateDateOfBirth("20301231", CURRENT_DATE);

            assertThat(result.inputError()).isTrue();
            assertThat(result.returnMessage()).isEqualTo(ORACLE_DATE_IN_FUTURE);
        }

        @Test
        @DisplayName("a failure clears all three flags, although the date is well formed")
        void aFailureClearsAllThreeFlags() {
            final DateEditResult result = service.validateDateOfBirth("20301231", CURRENT_DATE);

            assertThat(result.yearFlag()).isEqualTo(DateEditFlag.NOT_OK);
            assertThat(result.monthFlag()).isEqualTo(DateEditFlag.NOT_OK);
            assertThat(result.dayFlag()).isEqualTo(DateEditFlag.NOT_OK);
            assertThat(result.flagsImage()).isEqualTo("000");
        }

        @Test
        @DisplayName("the day before today passes, which pins the boundary exactly")
        void theDayBeforeTodayPasses() {
            assertThat(service.validateDateOfBirth("20220705", CURRENT_DATE).inputError()).isFalse();
            assertThat(service.validateDateOfBirth("20220706", CURRENT_DATE).inputError()).isTrue();
            assertThat(service.validateDateOfBirth("20220707", CURRENT_DATE).inputError()).isTrue();
        }

        @Test
        @DisplayName("an unresolvable image breaks the documented entry precondition and is refused loudly")
        void anUnresolvableImageIsRefusedLoudly() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> service.validateDateOfBirth("20220230", CURRENT_DATE))
                    .withMessageContaining("not a resolvable CCYYMMDD");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> service.validateDateOfBirth(ALL_SPACES_DATE, CURRENT_DATE))
                    .withMessageContaining("main cascade");
        }

        @Test
        @DisplayName("a carried-in message is preserved, as everywhere else in the copybook")
        void aCarriedInMessageIsPreserved() {
            final DateEditResult result =
                    service.validateDateOfBirth("20301231", CURRENT_DATE, CARRIED_IN_MESSAGE);

            assertThat(result.returnMessage()).isEqualTo(CARRIED_IN_MESSAGE);
            assertThat(result.inputError()).isTrue();
        }

        @Test
        @DisplayName("no argument may be null")
        void noArgumentMayBeNull() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> service.validateDateOfBirth(null, CURRENT_DATE))
                    .withMessageContaining("candidateDate");
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> service.validateDateOfBirth("19800101", null))
                    .withMessageContaining("currentDate");
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> service.validateDateOfBirth("19800101", CURRENT_DATE, null))
                    .withMessageContaining("currentReturnMessage");
        }

        @Test
        @DisplayName("a leap day is resolvable and therefore admissible as a date of birth")
        void aLeapDayIsAdmissible() {
            assertThatNoException()
                    .isThrownBy(() -> service.validateDateOfBirth("20000229", CURRENT_DATE));
            assertThat(service.validateDateOfBirth("20000229", CURRENT_DATE).inputError()).isFalse();
        }
    }

    @Nested
    @DisplayName("the three-character flag group WS-EDIT-DATE-FLGS and its condition-name images")
    final class FlagGroupContract {

        @Test
        @DisplayName("the three states carry the images their condition names declare")
        void theThreeStatesCarryTheirDeclaredImages() {
            assertThat(DateEditFlag.VALID.getImage()).as("VALUE LOW-VALUES").isEqualTo('\u0000');
            assertThat(DateEditFlag.NOT_OK.getImage()).as("VALUE '0'").isEqualTo('0');
            assertThat(DateEditFlag.BLANK.getImage()).as("VALUE 'B'").isEqualTo('B');
        }

        @Test
        @DisplayName("exactly three states exist, and a blank field is not the same as an invalid one")
        void exactlyThreeStatesExist() {
            assertThat(DateEditFlag.values()).hasSize(3);
            assertThat(EnumSet.allOf(DateEditFlag.class)).containsExactly(DateEditFlag.VALID,
                    DateEditFlag.NOT_OK, DateEditFlag.BLANK);
            assertThat(DateEditFlag.BLANK).isNotEqualTo(DateEditFlag.NOT_OK);
        }

        @Test
        @DisplayName("the group image is three characters in year, month, day order")
        void theGroupImageIsThreeCharactersInOrder() {
            final DateEditResult result = new DateEditResult(true, DateEditFlag.BLANK,
                    DateEditFlag.NOT_OK, DateEditFlag.VALID, ORACLE_NO_MESSAGE);

            assertThat(result.flagsImage()).hasSize(ORACLE_FLAG_GROUP_WIDTH);
            assertThat(result.flagsImage()).isEqualTo("B0\u0000");
        }

        @Test
        @DisplayName("a group written by the head paragraph alone is the three characters 000")
        void theHeadParagraphGroupIsThreeZeroes() {
            final DateEditResult result = new DateEditResult(true, DateEditFlag.NOT_OK,
                    DateEditFlag.NOT_OK, DateEditFlag.NOT_OK, ORACLE_NO_MESSAGE);

            assertThat(result.flagsImage()).isEqualTo("000");
        }

        @Test
        @DisplayName("the result carries no synthesised verdict, only the flags and the message")
        void theResultCarriesNoSynthesisedVerdict() {
            final DateEditResult result = service.validateCcyymmddDate(VALID_DATE);

            assertThat(result.inputError()).isFalse();
            assertThat(result.yearFlag()).isNotNull();
            assertThat(result.monthFlag()).isNotNull();
            assertThat(result.dayFlag()).isNotNull();
            assertThat(result.returnMessage()).isNotNull();
        }

        @Test
        @DisplayName("no component of the result may be null")
        void noComponentOfTheResultMayBeNull() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new DateEditResult(false, null, DateEditFlag.VALID,
                            DateEditFlag.VALID, ORACLE_NO_MESSAGE))
                    .withMessageContaining("yearFlag");
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new DateEditResult(false, DateEditFlag.VALID, null,
                            DateEditFlag.VALID, ORACLE_NO_MESSAGE))
                    .withMessageContaining("monthFlag");
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new DateEditResult(false, DateEditFlag.VALID,
                            DateEditFlag.VALID, null, ORACLE_NO_MESSAGE))
                    .withMessageContaining("dayFlag");
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new DateEditResult(false, DateEditFlag.VALID,
                            DateEditFlag.VALID, DateEditFlag.VALID, null))
                    .withMessageContaining("returnMessage");
        }

        @Test
        @DisplayName("the result is a value type")
        void theResultIsAValueType() {
            final DateEditResult first = service.validateCcyymmddDate(MONTH_ABOVE_RANGE_DATE);
            final DateEditResult second = service.validateCcyymmddDate(MONTH_ABOVE_RANGE_DATE);

            assertThat(first).isEqualTo(second).hasSameHashCodeAs(second);
            assertThat(first.toString()).contains("NOT_OK");
        }

        @Test
        @DisplayName("the cascade rejects null arguments")
        void theCascadeRejectsNullArguments() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> service.validateCcyymmddDate(null))
                    .withMessageContaining("candidateDate");
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> service.validateCcyymmddDate(VALID_DATE, null))
                    .withMessageContaining("currentReturnMessage");
        }
    }
}
