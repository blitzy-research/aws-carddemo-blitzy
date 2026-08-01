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

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Stream;

import com.carddemo.domain.enums.DateFormat;
import com.carddemo.service.DateValidationService.DateEditFlag;
import com.carddemo.service.DateValidationService.DateEditResult;
import com.carddemo.service.DateValidationService.DateFeedback;
import com.carddemo.service.DateValidationService.SubprogramResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.params.provider.Arguments.arguments;

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
 *
 * <p><strong>Every width asserted below is a byte width.</strong> The eight-character cascade input
 * field, the two ten-character linkage parameters and the eighty-character result area are all
 * {@code PIC X(n)} declarations, and a {@code PIC X(n)} field reserves <em>n bytes</em>. Width
 * assertions therefore measure {@code getBytes(StandardCharsets.US_ASCII).length} rather than a
 * {@code String} character count, and no fixed-width comparison anywhere below is trimmed: a trailing
 * space that the layout reserves is part of the value, so trimming it would assert a contract the
 * legacy does not have.
 *
 * <p><strong>Deliberately not covered here.</strong> The five-paragraph range
 * {@code 1260-EDIT-US-PHONE-NUM THRU 1260-EDIT-US-PHONE-NUM-EXIT}, invoked from two sites in
 * {@code app/cbl/COACTUPC.cbl}, is <em>not</em> a member of either range this service translates and
 * the service exposes no phone-number entry point. It belongs to the account-update translation and is
 * covered there; duplicating it here would assert a member this class does not own.
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

    // Inputs and oracles used by the dispatch, exemption and byte-width proofs below. Each is written
    // out as a literal, because an oracle computed by the code under test asserts only that the code
    // agrees with itself.

    /** Ten spaces: the whole hyphenated slot supplied blank, so the picture gets no digits at all. */
    private static final String HYPHENATED_ALL_SPACES = "          ";

    /** A non-digit in a month position the hyphenated picture requires to be numeric. */
    private static final String HYPHENATED_NON_NUMERIC_MONTH = "2022-1X-01";

    /** Year zero: accepted by proleptic parsing, refused by the date service, so tested before parsing. */
    private static final String HYPHENATED_YEAR_ZERO = "0000-01-01";

    /** Month thirteen in the hyphenated mask. */
    private static final String HYPHENATED_MONTH_ABOVE_RANGE = "2022-13-01";

    /** A 30th of February: well formed, not a calendar date, so strict resolution must refuse it. */
    private static final String HYPHENATED_THIRTIETH_OF_FEBRUARY = "2022-02-30";

    /** A 31st of April: well formed, not a calendar date. */
    private static final String HYPHENATED_THIRTY_FIRST_OF_SHORT_MONTH = "2022-04-31";

    /** A 29th of February in a common year: well formed, not a calendar date. */
    private static final String HYPHENATED_LEAP_DAY_COMMON_YEAR = "2023-02-29";

    /** A 29th of February in a leap year: a real calendar date that must be accepted. */
    private static final String HYPHENATED_LEAP_DAY = "2024-02-29";

    /** The first day the Lilian day count covers, rendered in the hyphenated mask. */
    private static final String HYPHENATED_LILIAN_FIRST_DAY = "1582-10-15";

    /** The day before the Lilian count begins: resolvable, but outside the supported range. */
    private static final String HYPHENATED_BEFORE_LILIAN = "1582-10-14";

    /** Year zero <em>and</em> month thirteen: two detectable conditions in one value. */
    private static final String HYPHENATED_YEAR_ZERO_AND_BAD_MONTH = "0000-13-01";

    /** A non-digit <em>and</em> month thirteen: two detectable conditions in one value. */
    private static final String HYPHENATED_NON_NUMERIC_AND_BAD_MONTH = "2X22-13-01";

    /** The compact mask value the copybook places in the format field, {@code [app/cpy/CSUTLDPY.cpy:L291]}. */
    private static final String COMPACT_MASK_VALUE = "YYYYMMDD  ";

    /** The hyphenated mask value both callers hold in their format work field. */
    private static final String HYPHENATED_MASK_VALUE = "YYYY-MM-DD";

    /** A mask the estate never transmits, so the picture string cannot be used. */
    private static final String UNSUPPORTED_MASK_VALUE = "DD/MM/YYYY";

    /** A month bad in the month stage and a day bad in the day stage, in one image. */
    private static final String BAD_MONTH_AND_BAD_DAY_DATE = "20221332";

    /** A century bad in the year stage and a month bad in the month stage, in one image. */
    private static final String BAD_CENTURY_AND_BAD_MONTH_DATE = "18221301";

    /** All three field slices bad at once: bad century, month thirteen, day thirty-two. */
    private static final String BAD_YEAR_MONTH_AND_DAY_DATE = "18221332";

    /** A ten-character image whose rightmost two characters the eight-character move must discard. */
    private static final String OVERLONG_DATE = "2022010199";

    /** Four characters only: the year slice fills, the month and day slices arrive blank. */
    private static final String SHORT_DATE = "2022";

    /** The empty sender, which a fixed-width move turns into an all-spaces field. */
    private static final String EMPTY_DATE = "";

    /**
     * The three-character image the head paragraph alone leaves behind,
     * {@code [app/cpy/CSUTLDPY.cpy:L19]} writing the group value at {@code [app/cpy/CSUTLDWY.cpy:L45]}.
     * It is also the value the Language-Environment guard tests against and never matches.
     */
    private static final String ORACLE_HEAD_PARAGRAPH_FLAG_GROUP = "000";

    /**
     * The three-character image of an all-valid group: the {@code LOW-VALUES} the group-level condition
     * name at {@code [app/cpy/CSUTLDWY.cpy:L44]} compares against.
     */
    private static final String ORACLE_ALL_VALID_FLAG_GROUP = "\u0000\u0000\u0000";

    /** Offset of the severity code in the eighty-byte block: it is the leading field. */
    private static final int ORACLE_SEVERITY_OFFSET = 0;

    /** Offset of the message number: four bytes of severity plus the eleven-byte label. */
    private static final int ORACLE_MESSAGE_NUMBER_OFFSET = 15;

    /** The eleven-byte label between severity and message number, {@code [app/cbl/CSUTLDTC.cbl:L45]}. */
    private static final String ORACLE_MESSAGE_CODE_LABEL = "Mesg Code: ";

    /** The nine-byte label preceding the tested date, {@code [app/cbl/CSUTLDTC.cbl:L51]}. */
    private static final String ORACLE_TESTED_DATE_LABEL = "TstDate: ";

    /** The ten-byte label preceding the mask, {@code [app/cbl/CSUTLDTC.cbl:L54]}, which carries no padding. */
    private static final String ORACLE_MASK_USED_LABEL = "Mask used:";

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

    /**
     * Measures a value the way a {@code PIC X(n)} field measures it: in bytes.
     *
     * <p>Declared here rather than reached for through the production class, so that a width assertion
     * cannot silently inherit whatever the code under test happens to believe a width is. Nothing is
     * trimmed and no platform default charset is consulted.
     *
     * @param value the value to measure
     * @return the number of bytes the value occupies in the single-byte character set of the legacy
     *         fields
     */
    private static int encodedBytes(final String value) {
        return value.getBytes(StandardCharsets.US_ASCII).length;
    }

    /**
     * Cuts a byte range out of a rendered fixed-width block, the way a caller's group overlay cuts it.
     *
     * <p>The overlay both callers declare is a byte overlay onto an eighty-byte area, so the slice is
     * taken from the encoded image at a byte offset rather than from the character sequence at a
     * character index.
     *
     * @param block  the rendered block
     * @param offset the byte offset the overlay begins at
     * @param width  the byte width the overlay declares
     * @return the slice, with every reserved space retained
     */
    private static String byteSlice(final String block, final int offset, final int width) {
        return new String(block.getBytes(StandardCharsets.US_ASCII), offset, width,
                StandardCharsets.US_ASCII);
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
            assertThat(result.severityCode()).isEqualTo(ORACLE_FAILURE_SEVERITY);
            assertThat(result.messageNumber()).isEqualTo(ORACLE_TOLERATED_MESSAGE_NUMBER);
            assertThat(result.numericSeverity())
                    .as("the numeric view of the severity, stated as a literal rather than read back "
                            + "off the outcome the service chose")
                    .isEqualTo(3);
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
        @DisplayName("the convenience overload starts from the blank state, and both overloads are "
                + "pinned to the same explicit literal so a shared defect cannot hide behind their "
                + "agreement")
        void theConvenienceOverloadStartsBlank() {
            final DateEditResult implicitBlank = service.validateCcyymmddDate(ALL_SPACES_DATE);
            final DateEditResult explicitBlank =
                    service.validateCcyymmddDate(ALL_SPACES_DATE, ORACLE_NO_MESSAGE);

            assertAll("each side judged against the literal, then against the other",
                    () -> assertThat(implicitBlank.returnMessage())
                            .isEqualTo(ORACLE_YEAR_NOT_SUPPLIED),
                    () -> assertThat(explicitBlank.returnMessage())
                            .isEqualTo(ORACLE_YEAR_NOT_SUPPLIED),
                    () -> assertThat(implicitBlank.returnMessage())
                            .isEqualTo(explicitBlank.returnMessage()),
                    () -> assertThat(implicitBlank).isEqualTo(explicitBlank));
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

    // THE DECISIVE PROOF: the eleven-paragraph THRU range really is an ordered cascade.

    /**
     * Proves that the behaviour of the range lives in the paragraphs the {@code THRU} falls through and
     * not in its head.
     *
     * <p>The head paragraph {@code EDIT-DATE-CCYYMMDD} at {@code [app/cpy/CSUTLDPY.cpy:L18]} has a body
     * of exactly one statement, at {@code [app/cpy/CSUTLDPY.cpy:L19]}: it writes the all-invalid value
     * into the three-byte flag group and returns. It compares nothing, so a translation that mapped the
     * head paragraph alone would produce, for <em>every</em> input in the table below, an outcome with no
     * input error, the flag group {@code 000} and an empty message.
     *
     * <p>Each row therefore pins three independent things at once - the input-error contribution, the
     * exact three-flag triple, and the exact message suffix - so a head-only translation fails on all
     * three counts rather than being caught by luck. Every value in the table is well formed as far as
     * the head paragraph is concerned and is refused only by an inner stage.
     */
    @Nested
    @DisplayName("the THRU range is a genuine ordered cascade: if any of these inner-stage values were "
            + "accepted, the cascade was not translated and the head paragraph alone was")
    final class CascadeIsGenuinelyACascade {

        /**
         * The inner-stage rejections, one row per stage that can refuse a head-clean value.
         *
         * <p>Every expected value is a literal read from {@code app/cpy/CSUTLDPY.cpy}, never a value
         * obtained by asking the service what it thinks.
         *
         * @return rows of candidate image, the stage that refuses it, and the expected year, month and
         *         day flags followed by the expected message suffix
         */
        static Stream<Arguments> innerStageRejections() {
            return Stream.of(
                    arguments(INVALID_CENTURY_DATE, "year stage, century neither 19 nor 20",
                            DateEditFlag.NOT_OK, DateEditFlag.VALID, DateEditFlag.VALID,
                            ORACLE_CENTURY_NOT_VALID),
                    arguments(MONTH_BELOW_RANGE_DATE, "month stage, month 00 below the declared range",
                            DateEditFlag.VALID, DateEditFlag.NOT_OK, DateEditFlag.VALID,
                            ORACLE_MONTH_OUT_OF_RANGE),
                    arguments(MONTH_ABOVE_RANGE_DATE, "month stage, month 13 above the declared range",
                            DateEditFlag.VALID, DateEditFlag.NOT_OK, DateEditFlag.VALID,
                            ORACLE_MONTH_OUT_OF_RANGE),
                    arguments(DAY_BELOW_RANGE_DATE, "day stage, day 00 below the declared range",
                            DateEditFlag.VALID, DateEditFlag.VALID, DateEditFlag.NOT_OK,
                            ORACLE_DAY_OUT_OF_RANGE),
                    arguments(DAY_ABOVE_RANGE_DATE, "day stage, day 32 above the declared range",
                            DateEditFlag.VALID, DateEditFlag.VALID, DateEditFlag.NOT_OK,
                            ORACLE_DAY_OUT_OF_RANGE),
                    arguments(THIRTY_FIRST_OF_SHORT_MONTH_DATE,
                            "combination stage, a 31st in a 30-day month",
                            DateEditFlag.VALID, DateEditFlag.NOT_OK, DateEditFlag.NOT_OK,
                            ORACLE_CANNOT_HAVE_31_DAYS),
                    arguments(THIRTIETH_OF_FEBRUARY_DATE, "combination stage, a 30th of February",
                            DateEditFlag.VALID, DateEditFlag.NOT_OK, DateEditFlag.NOT_OK,
                            ORACLE_CANNOT_HAVE_30_DAYS),
                    arguments(LEAP_DAY_COMMON_YEAR,
                            "combination stage, a 29th of February in a common year",
                            DateEditFlag.NOT_OK, DateEditFlag.NOT_OK, DateEditFlag.NOT_OK,
                            ORACLE_NOT_A_LEAP_YEAR),
                    arguments(LEAP_DAY_CENTURY_REJECTED,
                            "combination stage, the four-hundred divisor witness",
                            DateEditFlag.NOT_OK, DateEditFlag.NOT_OK, DateEditFlag.NOT_OK,
                            ORACLE_NOT_A_LEAP_YEAR));
        }

        @ParameterizedTest(name = "[{0}] is refused by the {1}")
        @MethodSource("innerStageRejections")
        @DisplayName("each value below clears the head paragraph and is refused only by an inner stage, "
                + "so acceptance here would prove the eleven fall-through paragraphs were dropped")
        void anInnerStageValueIsRefusedByTheStageThatOwnsIt(final String candidate,
                                                            final String refusingStage,
                                                            final DateEditFlag expectedYearFlag,
                                                            final DateEditFlag expectedMonthFlag,
                                                            final DateEditFlag expectedDayFlag,
                                                            final String expectedMessage) {
            final DateEditResult result = service.validateCcyymmddDate(candidate);

            assertAll("[" + candidate + "] must be refused by the " + refusingStage,
                    () -> assertThat(result.inputError())
                            .as("the input-error contribution a head-only translation never makes")
                            .isTrue(),
                    () -> assertThat(result.yearFlag()).as("year flag").isEqualTo(expectedYearFlag),
                    () -> assertThat(result.monthFlag()).as("month flag").isEqualTo(expectedMonthFlag),
                    () -> assertThat(result.dayFlag()).as("day flag").isEqualTo(expectedDayFlag),
                    () -> assertThat(result.returnMessage()).as("message suffix, byte for byte")
                            .isEqualTo(expectedMessage),
                    () -> assertThat(result.returnMessage())
                            .as("a claimed message is the witness a head-only translation never leaves, "
                                    + "because the head paragraph writes the pessimistic flag group and "
                                    + "nothing else")
                            .isNotEqualTo(ORACLE_NO_MESSAGE),
                    () -> assertThat(encodedBytes(result.flagsImage()))
                            .as("the group keeps its declared width whatever verdict it carries")
                            .isEqualTo(ORACLE_FLAG_GROUP_WIDTH));
        }

        @Test
        @DisplayName("the flag group alone is NOT a valid cascade witness: the leap-year branch lands on "
                + "exactly the pessimistic group the head paragraph writes, so only the input-error flag "
                + "and the message distinguish a real refusal from an untranslated cascade")
        void theFlagGroupAloneIsNotAValidCascadeWitness() {
            final DateEditResult leapYearRefusal = service.validateCcyymmddDate(LEAP_DAY_COMMON_YEAR);

            assertAll("the group coincides, so the other two witnesses carry the whole proof",
                    () -> assertThat(leapYearRefusal.flagsImage())
                            .as("all three flags unfavourable is byte-identical to the head constant")
                            .isEqualTo(ORACLE_HEAD_PARAGRAPH_FLAG_GROUP),
                    () -> assertThat(leapYearRefusal.inputError())
                            .as("the first witness the head paragraph never provides")
                            .isTrue(),
                    () -> assertThat(leapYearRefusal.returnMessage())
                            .as("the second witness the head paragraph never provides")
                            .isEqualTo(ORACLE_NOT_A_LEAP_YEAR),
                    () -> assertThat(leapYearRefusal.returnMessage()).isNotEqualTo(ORACLE_NO_MESSAGE));
        }

        /**
         * The control group: values that clear every stage and therefore reach the end of the range.
         *
         * @return candidate images that the whole cascade accepts
         */
        static Stream<Arguments> valuesEveryStageAccepts() {
            return Stream.of(arguments(VALID_DATE, "an unremarkable first of January"),
                    arguments(VALID_LAST_DAY_OF_YEAR, "the 31st of a 31-day month"),
                    arguments(LEAP_DAY_ORDINARY, "a leap day under the divisor of four"),
                    arguments(LEAP_DAY_CENTURY_ACCEPTED, "a leap day under the divisor of four hundred"));
        }

        @ParameterizedTest(name = "[{0}] is accepted: {1}")
        @MethodSource("valuesEveryStageAccepts")
        @DisplayName("a value that clears all five stages carries no input error, an all-valid group and "
                + "no message, which is what makes the rejections above meaningful rather than blanket")
        void aValueThatClearsEveryStageIsAccepted(final String candidate, final String description) {
            final DateEditResult result = service.validateCcyymmddDate(candidate);

            assertAll("[" + candidate + "] is " + description,
                    () -> assertThat(result.inputError()).isFalse(),
                    () -> assertThat(result.yearFlag()).isEqualTo(DateEditFlag.VALID),
                    () -> assertThat(result.monthFlag()).isEqualTo(DateEditFlag.VALID),
                    () -> assertThat(result.dayFlag()).isEqualTo(DateEditFlag.VALID),
                    () -> assertThat(result.returnMessage()).isEqualTo(ORACLE_NO_MESSAGE),
                    () -> assertThat(result.flagsImage()).isEqualTo(ORACLE_ALL_VALID_FLAG_GROUP));
        }

        @Test
        @DisplayName("the combination stage is the only stage that judges the combination, so a 31st and "
                + "a 29th of February pass the day stage on their own before it refuses them")
        void theCombinationStageIsWhereTheCombinationIsJudged() {
            final DateEditResult thirtyFirstInLongMonth =
                    service.validateCcyymmddDate(VALID_LAST_DAY_OF_YEAR);
            final DateEditResult twentyNinthInLeapYear = service.validateCcyymmddDate(LEAP_DAY_ORDINARY);

            assertAll("a 31 and a 29 are in range for the day stage and only the combination refuses them",
                    () -> assertThat(thirtyFirstInLongMonth.dayFlag()).isEqualTo(DateEditFlag.VALID),
                    () -> assertThat(thirtyFirstInLongMonth.inputError()).isFalse(),
                    () -> assertThat(twentyNinthInLeapYear.dayFlag()).isEqualTo(DateEditFlag.VALID),
                    () -> assertThat(twentyNinthInLeapYear.inputError()).isFalse(),
                    () -> assertThat(service.validateCcyymmddDate(THIRTY_FIRST_OF_SHORT_MONTH_DATE)
                            .returnMessage()).isEqualTo(ORACLE_CANNOT_HAVE_31_DAYS),
                    () -> assertThat(service.validateCcyymmddDate(LEAP_DAY_COMMON_YEAR).returnMessage())
                            .isEqualTo(ORACLE_NOT_A_LEAP_YEAR));
        }
    }

    /**
     * Proves that each stage keeps its own early exit, in the source's order.
     *
     * <p>Two properties are separable and both are asserted. First, a stage that fails does <em>not</em>
     * abandon the range: its jump lands on its own {@code -EXIT} paragraph, which is only the exit of
     * that stage, so the following stages still run and still set their flags. Second, the accumulated
     * message is first-wins, guarded by the message-off condition at {@code [app/cbl/COACTUPC.cbl:L480]},
     * so the <em>earliest</em> failing stage owns the text while later stages contribute flags silently.
     *
     * <p>Together these give the observable proof that an early failure stops the later stages from
     * reporting: the service holds no collaborator to verify against, so the reported message is the
     * evidence, and it is the earlier stage's in every doubly-bad case below.
     */
    @Nested
    @DisplayName("stage ordering and early exit: the earliest failing stage owns the message while the "
            + "later stages still set their own flags")
    final class CascadeStageOrderingAndEarlyExit {

        @Test
        @DisplayName("a bad month and a bad day together report the month stage, because the month stage "
                + "runs first and claims the message")
        void aBadMonthAndABadDayReportTheMonthStage() {
            final DateEditResult result = service.validateCcyymmddDate(BAD_MONTH_AND_BAD_DAY_DATE);

            assertAll("month 13 with day 32: the month stage precedes the day stage",
                    () -> assertThat(result.returnMessage())
                            .as("the earlier stage's suffix, not the day stage's")
                            .isEqualTo(ORACLE_MONTH_OUT_OF_RANGE),
                    () -> assertThat(result.returnMessage())
                            .as("the later stage never overwrites the claimed message")
                            .isNotEqualTo(ORACLE_DAY_OUT_OF_RANGE),
                    () -> assertThat(result.monthFlag()).isEqualTo(DateEditFlag.NOT_OK),
                    () -> assertThat(result.dayFlag())
                            .as("the day stage still ran and still set its own flag")
                            .isEqualTo(DateEditFlag.NOT_OK),
                    () -> assertThat(result.inputError()).isTrue());
        }

        @Test
        @DisplayName("a bad century and a bad month together report the year stage, because the year "
                + "stage is the first of the five")
        void aBadCenturyAndABadMonthReportTheYearStage() {
            final DateEditResult result = service.validateCcyymmddDate(BAD_CENTURY_AND_BAD_MONTH_DATE);

            assertAll("century 18 with month 13: the year stage precedes the month stage",
                    () -> assertThat(result.returnMessage()).isEqualTo(ORACLE_CENTURY_NOT_VALID),
                    () -> assertThat(result.returnMessage()).isNotEqualTo(ORACLE_MONTH_OUT_OF_RANGE),
                    () -> assertThat(result.yearFlag()).isEqualTo(DateEditFlag.NOT_OK),
                    () -> assertThat(result.monthFlag())
                            .as("the month stage still ran despite the year stage having failed")
                            .isEqualTo(DateEditFlag.NOT_OK),
                    () -> assertThat(result.inputError()).isTrue());
        }

        @Test
        @DisplayName("all three field stages failing still reports the year stage, and all three flags "
                + "carry their own failure")
        void allThreeFieldStagesFailingReportsTheFirstOfThem() {
            final DateEditResult result = service.validateCcyymmddDate(BAD_YEAR_MONTH_AND_DAY_DATE);

            assertAll("the first stage to fail owns the message; every stage owns its flag",
                    () -> assertThat(result.returnMessage()).isEqualTo(ORACLE_CENTURY_NOT_VALID),
                    () -> assertThat(result.yearFlag()).isEqualTo(DateEditFlag.NOT_OK),
                    () -> assertThat(result.monthFlag()).isEqualTo(DateEditFlag.NOT_OK),
                    () -> assertThat(result.dayFlag()).isEqualTo(DateEditFlag.NOT_OK),
                    () -> assertThat(result.flagsImage())
                            .as("three failures spell the same image the head paragraph writes, which is "
                                    + "why the message and the input-error flag carry the proof here")
                            .isEqualTo(ORACLE_HEAD_PARAGRAPH_FLAG_GROUP));
        }

        @Test
        @DisplayName("a blank year and a bad month are both reported on one pass, which is only possible "
                + "because the year stage's exit falls through to the month stage")
        void aBlankYearAndABadMonthAreBothReportedOnOnePass() {
            final DateEditResult result = service.validateCcyymmddDate("    1301");

            assertAll("a blank year does not end the range",
                    () -> assertThat(result.yearFlag()).isEqualTo(DateEditFlag.BLANK),
                    () -> assertThat(result.monthFlag()).isEqualTo(DateEditFlag.NOT_OK),
                    () -> assertThat(result.returnMessage()).isEqualTo(ORACLE_YEAR_NOT_SUPPLIED));
        }

        @Test
        @DisplayName("a combination failure leaves the range early, so the stage that follows it never "
                + "re-marks the flag group as valid")
        void aCombinationFailureLeavesTheRangeEarly() {
            final DateEditResult result = service.validateCcyymmddDate(LEAP_DAY_COMMON_YEAR);

            assertAll("the combination stage jumps to the exit of the whole range",
                    () -> assertThat(result.flagsImage()).isNotEqualTo(ORACLE_ALL_VALID_FLAG_GROUP),
                    () -> assertThat(result.returnMessage()).isEqualTo(ORACLE_NOT_A_LEAP_YEAR),
                    () -> assertThat(result.inputError()).isTrue());
        }
    }

    // The one construct that could not be carried across: the Language Environment date service becomes
    // strict java.time resolution. Asserted by outcome, never by inspecting a formatter.

    /**
     * Proves that resolution refuses to normalise.
     *
     * <p>The legacy converts a date through the Lilian day services, which reject a value that is not a
     * real calendar date. The Java substitution is only behaviour preserving under
     * {@code ResolverStyle.STRICT}: the default resolution would roll a 31st in a 30-day month back to
     * the 30th and would pull a 29 February in a common year back to the 28th, and both of those are
     * acceptances the legacy never makes.
     *
     * <p>Every expectation below is the <em>outcome</em>, stated as a literal. None of them is obtained
     * by parsing the same value with the same library the way the service does, because that would
     * assert only that two identical parses agree.
     */
    @Nested
    @DisplayName("strict resolution: a value that is not a real calendar date is refused, never rolled "
            + "forward and never adjusted backwards")
    final class StrictCalendarResolution {

        /**
         * Values that are well formed digit by digit and are still not calendar dates.
         *
         * @return the value, and the acceptance a normalising resolver would wrongly have produced
         */
        static Stream<Arguments> valuesNoNormalisingResolverMayAccept() {
            return Stream.of(
                    arguments(HYPHENATED_THIRTY_FIRST_OF_SHORT_MONTH,
                            "a normalising resolver would roll this back to the 30th"),
                    arguments(HYPHENATED_THIRTIETH_OF_FEBRUARY,
                            "a normalising resolver would roll this forward into March"),
                    arguments(HYPHENATED_LEAP_DAY_COMMON_YEAR,
                            "a normalising resolver would adjust this to the 28th"));
        }

        @ParameterizedTest(name = "[{0}] is refused: {1}")
        @MethodSource("valuesNoNormalisingResolverMayAccept")
        @DisplayName("the subprogram reports a bad date value rather than normalising, and the tested "
                + "date comes back with its own characters untouched")
        void anImpossibleCalendarDateIsRefusedRatherThanNormalised(final String candidate,
                                                                   final String whatNormalisingWouldDo) {
            final SubprogramResult result = service.validateDate(candidate, DateFormat.YYYY_MM_DD);

            assertAll("[" + candidate + "]: " + whatNormalisingWouldDo,
                    () -> assertThat(result.feedback()).isEqualTo(DateFeedback.BAD_DATE_VALUE),
                    () -> assertThat(result.severityCode()).isEqualTo(ORACLE_FAILURE_SEVERITY),
                    () -> assertThat(result.messageNumber()).isEqualTo("2508"),
                    () -> assertThat(result.resultText()).isEqualTo(ORACLE_TEXT_DATEVALUE_ERROR),
                    () -> assertThat(service.isDateAcceptable(result))
                            .as("a bad date value carries neither the accepted severity nor 2513")
                            .isFalse(),
                    () -> assertThat(result.testedDate())
                            .as("the date is reported back as sent, not as a resolver would rewrite it")
                            .isEqualTo(candidate));
        }

        @ParameterizedTest(name = "the cascade also refuses [{0}]")
        @ValueSource(strings = {"20220431", "20220230", "20230229", "19000229"})
        @DisplayName("the cascade reaches the same conclusion through its own combination stage, so the "
                + "two entry points do not disagree about which dates exist")
        void theCascadeRefusesTheSameImpossibleDates(final String candidate) {
            final DateEditResult result = service.validateCcyymmddDate(candidate);

            assertAll("[" + candidate + "] is not a calendar date",
                    () -> assertThat(result.inputError()).isTrue(),
                    () -> assertThat(result.returnMessage()).isNotEqualTo(ORACLE_NO_MESSAGE),
                    () -> assertThat(result.flagsImage()).isNotEqualTo(ORACLE_ALL_VALID_FLAG_GROUP));
        }

        @Test
        @DisplayName("a 29th of February in a leap year is a real date and is accepted through both "
                + "entry points")
        void aLeapDayInALeapYearIsAccepted() {
            final SubprogramResult subprogram = service.validateDate(HYPHENATED_LEAP_DAY,
                    DateFormat.YYYY_MM_DD);
            final DateEditResult cascade = service.validateCcyymmddDate(LEAP_DAY_ORDINARY);

            assertAll("29 February 2024 exists",
                    () -> assertThat(subprogram.feedback()).isEqualTo(DateFeedback.DATE_IS_VALID),
                    () -> assertThat(subprogram.severityCode()).isEqualTo(ORACLE_ACCEPTED_SEVERITY),
                    () -> assertThat(service.isDateAcceptable(subprogram)).isTrue(),
                    () -> assertThat(cascade.inputError()).isFalse(),
                    () -> assertThat(cascade.flagsImage()).isEqualTo(ORACLE_ALL_VALID_FLAG_GROUP));
        }

        @Test
        @DisplayName("a well-formed date round-trips with its input characters unchanged: no reformatting, "
                + "no zero-stripping and no separator substitution")
        void aWellFormedDateRoundTripsUnchanged() {
            final SubprogramResult hyphenated = service.validateDate(HYPHENATED_DATE,
                    DateFormat.YYYY_MM_DD);
            final SubprogramResult compact = service.validateDate(VALID_DATE, DateFormat.YYYYMMDD);

            assertAll("the tested date is echoed, not rendered",
                    () -> assertThat(hyphenated.testedDate()).isEqualTo(HYPHENATED_DATE),
                    () -> assertThat(hyphenated.maskUsed()).isEqualTo(HYPHENATED_MASK_VALUE),
                    () -> assertThat(compact.testedDate())
                            .as("padded into the ten-byte slot, with the eight sent characters intact")
                            .isEqualTo("20220101  "),
                    () -> assertThat(compact.maskUsed()).isEqualTo(COMPACT_MASK_VALUE),
                    () -> assertThat(encodedBytes(hyphenated.testedDate()))
                            .isEqualTo(ORACLE_LINKAGE_TEXT_WIDTH),
                    () -> assertThat(encodedBytes(compact.testedDate()))
                            .isEqualTo(ORACLE_LINKAGE_TEXT_WIDTH));
        }

        @Test
        @DisplayName("the leading zeros of a single-digit month and day survive, because the picture "
                + "describes fixed digit positions rather than a numeric value")
        void leadingZerosSurvive() {
            final SubprogramResult result = service.validateDate("2022-01-02", DateFormat.YYYY_MM_DD);

            assertAll("nothing is parsed back out and re-rendered",
                    () -> assertThat(result.feedback()).isEqualTo(DateFeedback.DATE_IS_VALID),
                    () -> assertThat(result.testedDate()).isEqualTo("2022-01-02"),
                    () -> assertThat(result.testedDate()).doesNotContain("2022-1-2"));
        }

        @Test
        @DisplayName("the Lilian boundary is inclusive on its first day and exclusive the day before, "
                + "which is the condition the tolerated message number reports")
        void theLilianBoundaryIsExact() {
            final SubprogramResult firstSupportedDay = service.validateDate(HYPHENATED_LILIAN_FIRST_DAY,
                    DateFormat.YYYY_MM_DD);
            final SubprogramResult dayBefore = service.validateDate(HYPHENATED_BEFORE_LILIAN,
                    DateFormat.YYYY_MM_DD);

            assertAll("the day count begins on 15 October 1582",
                    () -> assertThat(ORACLE_LILIAN_RANGE_START).isEqualTo(LocalDate.of(1582, 10, 15)),
                    () -> assertThat(firstSupportedDay.feedback()).isEqualTo(DateFeedback.DATE_IS_VALID),
                    () -> assertThat(dayBefore.feedback()).isEqualTo(DateFeedback.UNSUPPORTED_RANGE),
                    () -> assertThat(dayBefore.messageNumber())
                            .isEqualTo(ORACLE_TOLERATED_MESSAGE_NUMBER));
        }
    }

    // The two-level acceptance test, driven from a table so that a collapsed boolean cannot survive.

    /**
     * Proves the escape hatch that every one of the four genuine call sites applies.
     *
     * <p>All four sites - {@code [app/cbl/CORPT00C.cbl:L392]}, {@code [app/cbl/CORPT00C.cbl:L412]},
     * {@code [app/cbl/COTRN02C.cbl:L393]} and {@code [app/cbl/COTRN02C.cbl:L413]} - test the severity
     * code first and, only when it is not the accepted value, test the message number against
     * {@code 2513}. A non-zero severity carrying that message number is therefore accepted <em>silently</em>.
     *
     * <p>This is the single most likely place for a downstream service to collapse the two-field result
     * into one boolean and start rejecting input the legacy accepts, so the table below drives the
     * decision from the two code fields alone. The rows pair codes that no single classifier outcome
     * would produce together on purpose: that is what proves the decision reads the two fields
     * independently rather than deriving one from the other or from the outcome constant.
     */
    @Nested
    @DisplayName("the tolerated message number is an escape hatch, not decoration: severity and message "
            + "number are two separate levels and neither collapses into the other")
    final class ToleratedMessageNumberExemption {

        /**
         * The acceptance table, expressed purely in the two four-character code fields.
         *
         * @return the severity code, the message number, whether the callers would proceed, and why
         */
        static Stream<Arguments> acceptanceDecisions() {
            return Stream.of(
                    arguments("0000", "0000", true, "the accepted severity, decided at the first level"),
                    arguments("0000", "2513", true, "a zero severity is accepted whatever the number"),
                    arguments("0000", "2508", true, "a zero severity is accepted whatever the number"),
                    arguments("0000", "9999", true, "a zero severity is accepted whatever the number"),
                    arguments("0003", "2513", true, "the exemption itself: non-zero severity, tolerated "
                            + "number, accepted silently"),
                    arguments("0001", "2513", true, "any non-zero severity is exempted by the number, "
                            + "because the first level tests only against the accepted value"),
                    arguments("0003", "2507", false, "the same severity with any other number is refused"),
                    arguments("0003", "2508", false, "the same severity with any other number is refused"),
                    arguments("0003", "2517", false, "the same severity with any other number is refused"),
                    arguments("0003", "2512", false, "one away from the tolerated number is still refused"),
                    arguments("0003", "0000", false, "the unrecognised tail: non-zero severity with a "
                            + "zero message number is refused"));
        }

        @ParameterizedTest(name = "severity [{0}] with message number [{1}] is accepted={2}")
        @MethodSource("acceptanceDecisions")
        @DisplayName("acceptance is decided from the severity code and the message number as two "
                + "independent character comparisons, exactly as all four call sites decide it")
        void acceptanceIsDecidedFromTwoIndependentFields(final String severityCode,
                                                         final String messageNumber,
                                                         final boolean expectedAcceptance,
                                                         final String reason) {
            final SubprogramResult result = new SubprogramResult(DateFeedback.UNSUPPORTED_RANGE,
                    severityCode, messageNumber, ORACLE_TEXT_UNSUPPORTED_RANGE, HYPHENATED_DATE,
                    HYPHENATED_MASK_VALUE);

            assertThat(service.isDateAcceptable(result)).as(reason).isEqualTo(expectedAcceptance);
        }

        @Test
        @DisplayName("the exemption is exercised end to end: a date before the Lilian range carries a "
                + "non-zero severity and is still accepted")
        void theExemptionIsReachedByARealInput() {
            final SubprogramResult beforeLilian = service.validateDate(HYPHENATED_BEFORE_LILIAN,
                    DateFormat.YYYY_MM_DD);

            assertAll("the unsupported-range condition is the one both callers tolerate",
                    () -> assertThat(beforeLilian.severityCode())
                            .as("the severity is genuinely not the accepted value")
                            .isEqualTo(ORACLE_FAILURE_SEVERITY),
                    () -> assertThat(beforeLilian.numericSeverity()).isEqualTo(3),
                    () -> assertThat(beforeLilian.messageNumber())
                            .isEqualTo(ORACLE_TOLERATED_MESSAGE_NUMBER),
                    () -> assertThat(service.isDateAcceptable(beforeLilian))
                            .as("collapsing the two levels into one would reject a date the legacy takes")
                            .isTrue());
        }

        @Test
        @DisplayName("a failure that is not the tolerated condition is rejected end to end, so the "
                + "exemption does not leak into every non-zero severity")
        void anUntoleratedFailureIsStillRejected() {
            final SubprogramResult badValue = service.validateDate(HYPHENATED_THIRTIETH_OF_FEBRUARY,
                    DateFormat.YYYY_MM_DD);
            final SubprogramResult badMonth = service.validateDate(HYPHENATED_MONTH_ABOVE_RANGE,
                    DateFormat.YYYY_MM_DD);

            assertAll("the same severity, different message numbers, opposite decisions",
                    () -> assertThat(badValue.severityCode()).isEqualTo(ORACLE_FAILURE_SEVERITY),
                    () -> assertThat(badMonth.severityCode()).isEqualTo(ORACLE_FAILURE_SEVERITY),
                    () -> assertThat(service.isDateAcceptable(badValue)).isFalse(),
                    () -> assertThat(service.isDateAcceptable(badMonth)).isFalse());
        }

        @Test
        @DisplayName("exactly one of the ten outcomes carries the tolerated message number, so the "
                + "exemption is as narrow as the source makes it")
        void exactlyOneOutcomeCarriesTheToleratedNumber() {
            final Set<DateFeedback> tolerated = EnumSet.noneOf(DateFeedback.class);
            for (final DateFeedback feedback : DateFeedback.values()) {
                if (feedback.getMessageNumber() == 2513) {
                    tolerated.add(feedback);
                }
            }

            assertThat(tolerated).containsExactly(DateFeedback.UNSUPPORTED_RANGE);
        }
    }

    // The ten-clause outcome selection: clause order is the contract.

    /**
     * Proves the ten-clause selection at {@code [app/cbl/CSUTLDTC.cbl:L128]} through
     * {@code [app/cbl/CSUTLDTC.cbl:L149]} in both of its aspects.
     *
     * <p><strong>Order.</strong> The source evaluates the clauses top down and stops at the first match,
     * so a value that satisfies two conditions must report the earlier one. The rows below that pair two
     * conditions in one value are the ones that pin this; reordering the classification would flip them.
     *
     * <p><strong>Coverage.</strong> Eight of the ten clauses are reachable by supplying an input. The
     * remaining two are reachable in the legacy only through a feedback token the substituted parser
     * cannot produce, and inventing an input for them would be inventing behaviour:
     * <ul>
     *   <li>the era clause needs an era field, and neither of the two masks this estate transmits carries
     *       one - a fact asserted below rather than asserted about;</li>
     *   <li>the {@code WHEN OTHER} clause fires precisely when none of the nine declared tokens matched,
     *       and the substituted parser classifies every failure it can detect into one of the nine, so
     *       the clause is the defensive tail of the chain.</li>
     * </ul>
     * Both are therefore covered through their outcome contract - the decoded severity and message
     * number that make them behave correctly if they ever were selected - with the reason they are
     * unreachable stated rather than papered over.
     */
    @Nested
    @DisplayName("the ten-clause outcome selection: first match wins and every clause reachable by input "
            + "is reached")
    final class TenClauseDispatchOrdering {

        /**
         * One row per clause that an input can select, with the severity, message number and outcome text
         * the source declares for it.
         *
         * @return the candidate, the mask, and the four expected outcome components
         */
        static Stream<Arguments> clausesReachableByInput() {
            return Stream.of(
                    arguments(HYPHENATED_DATE, DateFormat.YYYY_MM_DD, DateFeedback.DATE_IS_VALID,
                            ORACLE_ACCEPTED_SEVERITY, ORACLE_ZERO_MESSAGE_NUMBER,
                            ORACLE_TEXT_DATE_IS_VALID),
                    arguments(HYPHENATED_ALL_SPACES, DateFormat.YYYY_MM_DD,
                            DateFeedback.INSUFFICIENT_DATA, ORACLE_FAILURE_SEVERITY, "2507",
                            ORACLE_TEXT_INSUFFICIENT),
                    arguments(HYPHENATED_THIRTIETH_OF_FEBRUARY, DateFormat.YYYY_MM_DD,
                            DateFeedback.BAD_DATE_VALUE, ORACLE_FAILURE_SEVERITY, "2508",
                            ORACLE_TEXT_DATEVALUE_ERROR),
                    arguments(HYPHENATED_BEFORE_LILIAN, DateFormat.YYYY_MM_DD,
                            DateFeedback.UNSUPPORTED_RANGE, ORACLE_FAILURE_SEVERITY,
                            ORACLE_TOLERATED_MESSAGE_NUMBER, ORACLE_TEXT_UNSUPPORTED_RANGE),
                    arguments(HYPHENATED_MONTH_ABOVE_RANGE, DateFormat.YYYY_MM_DD,
                            DateFeedback.INVALID_MONTH, ORACLE_FAILURE_SEVERITY, "2517",
                            ORACLE_TEXT_INVALID_MONTH),
                    arguments(HYPHENATED_NON_NUMERIC_MONTH, DateFormat.YYYY_MM_DD,
                            DateFeedback.NON_NUMERIC_DATA, ORACLE_FAILURE_SEVERITY, "2520",
                            ORACLE_TEXT_NON_NUMERIC_DATA),
                    arguments(HYPHENATED_YEAR_ZERO, DateFormat.YYYY_MM_DD,
                            DateFeedback.YEAR_IN_ERA_ZERO, ORACLE_FAILURE_SEVERITY, "2521",
                            ORACLE_TEXT_YEAR_IN_ERA_ZERO),
                    arguments(VALID_DATE, DateFormat.YYYYMMDD, DateFeedback.DATE_IS_VALID,
                            ORACLE_ACCEPTED_SEVERITY, ORACLE_ZERO_MESSAGE_NUMBER,
                            ORACLE_TEXT_DATE_IS_VALID),
                    arguments(HYPHENATED_LILIAN_FIRST_DAY, DateFormat.YYYY_MM_DD,
                            DateFeedback.DATE_IS_VALID, ORACLE_ACCEPTED_SEVERITY,
                            ORACLE_ZERO_MESSAGE_NUMBER, ORACLE_TEXT_DATE_IS_VALID));
        }

        @ParameterizedTest(name = "[{0}] under {1} selects {2} with severity {3} and number {4}")
        @MethodSource("clausesReachableByInput")
        @DisplayName("each clause an input can select carries its own decoded severity, its own message "
                + "number and its own outcome text padded to the receiving field's width")
        void everyClauseReachableByInputIsReached(final String candidate,
                                                 final DateFormat mask,
                                                 final DateFeedback expectedFeedback,
                                                 final String expectedSeverity,
                                                 final String expectedMessageNumber,
                                                 final String expectedText) {
            final SubprogramResult result = service.validateDate(candidate, mask);

            assertAll("[" + candidate + "] must select " + expectedFeedback,
                    () -> assertThat(result.feedback()).isEqualTo(expectedFeedback),
                    () -> assertThat(result.severityCode()).isEqualTo(expectedSeverity),
                    () -> assertThat(result.messageNumber()).isEqualTo(expectedMessageNumber),
                    () -> assertThat(result.resultText())
                            .as("the outcome text, with the padding the receiving field's width implies")
                            .isEqualTo(expectedText),
                    () -> assertThat(encodedBytes(result.resultText()))
                            .isEqualTo(ORACLE_RESULT_TEXT_WIDTH));
        }

        @Test
        @DisplayName("the bad-picture-string clause is selected by a mask the estate never transmits, "
                + "which is the ninth of the ten reachable outcomes")
        void theBadPictureStringClauseIsSelectedByAnUnknownMask() {
            final SubprogramResult result = service.validateDate(HYPHENATED_DATE, UNSUPPORTED_MASK_VALUE);

            assertAll("an unresolvable mask is reported, never guessed at",
                    () -> assertThat(result.feedback()).isEqualTo(DateFeedback.BAD_PICTURE_STRING),
                    () -> assertThat(result.severityCode()).isEqualTo(ORACLE_FAILURE_SEVERITY),
                    () -> assertThat(result.messageNumber()).isEqualTo("2518"),
                    () -> assertThat(result.resultText()).isEqualTo(ORACLE_TEXT_BAD_PICTURE_STRING),
                    () -> assertThat(result.maskUsed())
                            .as("the mask is reported back as sent, so a reader can see what was refused")
                            .isEqualTo(UNSUPPORTED_MASK_VALUE),
                    () -> assertThat(service.isDateAcceptable(result))
                            .as("a guessed mask would have returned a confidently wrong verdict")
                            .isFalse());
        }

        /**
         * Values that satisfy two detectable conditions at once.
         *
         * <p>The winning message number is carried as an explicit literal rather than read back off the
         * expected outcome constant, so the row states the whole answer and does not borrow any part of
         * it from the code under test.
         *
         * @return the candidate, the outcome the earlier test must win with, its message number, the
         *         outcome a reordered classification would have produced instead, and why
         */
        static Stream<Arguments> valuesSatisfyingTwoConditions() {
            return Stream.of(
                    arguments(HYPHENATED_YEAR_ZERO_AND_BAD_MONTH, DateFeedback.YEAR_IN_ERA_ZERO, "2521",
                            DateFeedback.INVALID_MONTH,
                            "year zero is tested before the month range"),
                    arguments(HYPHENATED_NON_NUMERIC_AND_BAD_MONTH, DateFeedback.NON_NUMERIC_DATA, "2520",
                            DateFeedback.INVALID_MONTH,
                            "the digit-position test precedes the month range"),
                    arguments(HYPHENATED_ALL_SPACES, DateFeedback.INSUFFICIENT_DATA, "2507",
                            DateFeedback.NON_NUMERIC_DATA,
                            "a blank slot supplies no digits at all, and insufficiency is tested first"));
        }

        @ParameterizedTest(name = "[{0}] selects {1} and not {3}")
        @MethodSource("valuesSatisfyingTwoConditions")
        @DisplayName("first match wins: a value satisfying two conditions reports the earlier one, so the "
                + "clause order may not be rearranged")
        void firstMatchWins(final String candidate,
                            final DateFeedback expectedEarlier,
                            final String expectedMessageNumber,
                            final DateFeedback rejectedLater,
                            final String why) {
            final SubprogramResult result = service.validateDate(candidate, DateFormat.YYYY_MM_DD);

            assertAll(why,
                    () -> assertThat(result.feedback()).isEqualTo(expectedEarlier),
                    () -> assertThat(result.feedback()).isNotEqualTo(rejectedLater),
                    () -> assertThat(result.messageNumber()).isEqualTo(expectedMessageNumber));
        }

        @Test
        @DisplayName("declaration order is the evaluation order, and the catch-all is last of the ten")
        void declarationOrderIsEvaluationOrder() {
            final DateFeedback[] declared = DateFeedback.values();

            assertAll("the nine declared tokens in source order, then the WHEN OTHER tail",
                    () -> assertThat(declared).hasSize(10),
                    () -> assertThat(declared).containsExactly(DateFeedback.DATE_IS_VALID,
                            DateFeedback.INSUFFICIENT_DATA,
                            DateFeedback.BAD_DATE_VALUE,
                            DateFeedback.INVALID_ERA,
                            DateFeedback.UNSUPPORTED_RANGE,
                            DateFeedback.INVALID_MONTH,
                            DateFeedback.BAD_PICTURE_STRING,
                            DateFeedback.NON_NUMERIC_DATA,
                            DateFeedback.YEAR_IN_ERA_ZERO,
                            DateFeedback.UNRECOGNISED_FEEDBACK),
                    () -> assertThat(declared[declared.length - 1])
                            .as("the catch-all must be evaluated last or it would shadow the nine")
                            .isEqualTo(DateFeedback.UNRECOGNISED_FEEDBACK));
        }

        @Test
        @DisplayName("the era clause cannot be selected by any input, because neither mask the estate "
                + "transmits carries an era field, and it still honours its own outcome contract")
        void theEraClauseIsUnreachableByInputAndStillContractual() {
            for (final DateFormat mask : DateFormat.values()) {
                assertThat(mask.getValue().chars().allMatch(character -> character == 'Y'
                                || character == 'M' || character == 'D' || character == '-'
                                || character == ' '))
                        .as("mask [%s] describes only year, month and day positions", mask.getValue())
                        .isTrue();
            }

            assertAll("the constant exists because the clause exists",
                    () -> assertThat(DateFormat.values()).hasSize(2),
                    () -> assertThat(DateFeedback.INVALID_ERA.getSeverity()).isEqualTo(3),
                    () -> assertThat(DateFeedback.INVALID_ERA.getMessageNumber()).isEqualTo(2509));
        }

        @Test
        @DisplayName("the catch-all clause is unreachable by input because every detectable failure is "
                + "classified onto one of the nine, and it is rejected if it ever were selected")
        void theCatchAllClauseIsTheDefensiveTail() {
            final SubprogramResult unrecognised = new SubprogramResult(
                    DateFeedback.UNRECOGNISED_FEEDBACK, ORACLE_FAILURE_SEVERITY,
                    ORACLE_ZERO_MESSAGE_NUMBER, ORACLE_TEXT_DATE_IS_INVALID, HYPHENATED_DATE,
                    HYPHENATED_MASK_VALUE);

            assertAll("neither the accepted severity nor the tolerated message number",
                    () -> assertThat(DateFeedback.UNRECOGNISED_FEEDBACK.getSeverity()).isEqualTo(3),
                    () -> assertThat(DateFeedback.UNRECOGNISED_FEEDBACK.getMessageNumber()).isZero(),
                    () -> assertThat(service.isDateAcceptable(unrecognised)).isFalse(),
                    () -> assertThat(unrecognised.resultText()).isEqualTo(ORACLE_TEXT_DATE_IS_INVALID));
        }

        @Test
        @DisplayName("the ten outcome texts are distinct, so no clause can be mistaken for another")
        void theTenOutcomeTextsAreDistinct() {
            assertThat(Set.of(ORACLE_TEXT_DATE_IS_VALID, ORACLE_TEXT_INSUFFICIENT,
                    ORACLE_TEXT_DATEVALUE_ERROR, ORACLE_TEXT_INVALID_ERA, ORACLE_TEXT_UNSUPPORTED_RANGE,
                    ORACLE_TEXT_INVALID_MONTH, ORACLE_TEXT_BAD_PICTURE_STRING,
                    ORACLE_TEXT_NON_NUMERIC_DATA, ORACLE_TEXT_YEAR_IN_ERA_ZERO,
                    ORACLE_TEXT_DATE_IS_INVALID)).hasSize(10);
        }
    }

    @Nested
    @DisplayName("the result block measured in ENCODED BYTES, which is the only width a fixed-width "
            + "linkage area understands")
    final class EncodedByteWidthContract {

        @Test
        @DisplayName("the rendered block is exactly eighty encoded bytes for every one of the ten "
                + "outcomes, not merely eighty characters")
        void theRenderedBlockIsExactlyEightyEncodedBytes() {
            for (final DateFeedback feedback : DateFeedback.values()) {
                final SubprogramResult result = new SubprogramResult(feedback,
                        ORACLE_FAILURE_SEVERITY,
                        ORACLE_TOLERATED_MESSAGE_NUMBER,
                        ORACLE_TEXT_DATE_IS_INVALID,
                        HYPHENATED_DATE,
                        HYPHENATED_MASK_VALUE);

                assertThat(encodedBytes(result.render()))
                        .as("encoded block width for %s", feedback)
                        .isEqualTo(ORACLE_RESULT_BLOCK_WIDTH);
            }
        }

        @Test
        @DisplayName("every field sits at the byte offset its declaration implies, and the offsets sum "
                + "to eighty with no gap and no overlap")
        void everyFieldSitsAtItsDeclaredByteOffset() {
            final String block =
                    service.validateDate(HYPHENATED_DATE, DateFormat.YYYY_MM_DD).render();

            assertAll("the thirteen declared items, read back by byte offset",
                    () -> assertThat(byteSlice(block, ORACLE_SEVERITY_OFFSET, ORACLE_CODE_WIDTH))
                            .isEqualTo(ORACLE_ACCEPTED_SEVERITY),
                    () -> assertThat(byteSlice(block, 4, 11)).isEqualTo(ORACLE_MESSAGE_CODE_LABEL),
                    () -> assertThat(byteSlice(block, ORACLE_MESSAGE_NUMBER_OFFSET, ORACLE_CODE_WIDTH))
                            .isEqualTo(ORACLE_ZERO_MESSAGE_NUMBER),
                    () -> assertThat(byteSlice(block, 19, 1)).isEqualTo(" "),
                    () -> assertThat(byteSlice(block, 20, ORACLE_RESULT_TEXT_WIDTH))
                            .isEqualTo(ORACLE_TEXT_DATE_IS_VALID),
                    () -> assertThat(byteSlice(block, 35, 1)).isEqualTo(" "),
                    () -> assertThat(byteSlice(block, 36, 9)).isEqualTo(ORACLE_TESTED_DATE_LABEL),
                    () -> assertThat(byteSlice(block, 45, ORACLE_LINKAGE_TEXT_WIDTH))
                            .isEqualTo(HYPHENATED_DATE),
                    () -> assertThat(byteSlice(block, 55, 1)).isEqualTo(" "),
                    () -> assertThat(byteSlice(block, 56, 10)).isEqualTo(ORACLE_MASK_USED_LABEL),
                    () -> assertThat(byteSlice(block, 66, ORACLE_LINKAGE_TEXT_WIDTH))
                            .isEqualTo(HYPHENATED_MASK_VALUE),
                    () -> assertThat(byteSlice(block, 76, 1)).isEqualTo(" "),
                    () -> assertThat(byteSlice(block, 77, 3)).isEqualTo("   "),
                    () -> assertThat(4 + 11 + 4 + 1 + 15 + 1 + 9 + 10 + 1 + 10 + 10 + 1 + 3)
                            .as("the thirteen widths sum to the declared block")
                            .isEqualTo(ORACLE_RESULT_BLOCK_WIDTH));
        }

        @Test
        @DisplayName("the coarser caller-side overlay of four, eleven, four and sixty-one bytes reads "
                + "the very same eighty bytes, which is the documented layout mismatch")
        void theCallerSideOverlayReadsTheSameEightyBytes() {
            final SubprogramResult result =
                    service.validateDate(HYPHENATED_BEFORE_LILIAN, DateFormat.YYYY_MM_DD);
            final String block = result.render();

            assertAll("the callers slice four items where the callee declares thirteen",
                    () -> assertThat(4 + 11 + 4 + ORACLE_MESSAGE_SEGMENT_WIDTH)
                            .isEqualTo(ORACLE_RESULT_BLOCK_WIDTH),
                    () -> assertThat(byteSlice(block, ORACLE_SEVERITY_OFFSET, ORACLE_CODE_WIDTH))
                            .as("the severity the callers test first")
                            .isEqualTo(ORACLE_FAILURE_SEVERITY),
                    () -> assertThat(byteSlice(block, 4, 11)).isEqualTo(ORACLE_MESSAGE_CODE_LABEL),
                    () -> assertThat(byteSlice(block, ORACLE_MESSAGE_NUMBER_OFFSET, ORACLE_CODE_WIDTH))
                            .as("the message number the callers test second")
                            .isEqualTo(ORACLE_TOLERATED_MESSAGE_NUMBER),
                    () -> assertThat(byteSlice(block, 19, ORACLE_MESSAGE_SEGMENT_WIDTH))
                            .as("the caller's tail, judged against the literal it must contain rather "
                                    + "than against whatever the service chose to publish")
                            .startsWith(" " + ORACLE_TEXT_UNSUPPORTED_RANGE),
                    () -> assertThat(encodedBytes(byteSlice(block, 19, ORACLE_MESSAGE_SEGMENT_WIDTH)))
                            .isEqualTo(ORACLE_MESSAGE_SEGMENT_WIDTH),
                    () -> assertThat(byteSlice(block, 19, ORACLE_MESSAGE_SEGMENT_WIDTH))
                            .as("and only then cross-checked against the published accessor, so the two "
                                    + "views of the same bytes are proven to agree")
                            .isEqualTo(result.messageSegment()));
        }

        @Test
        @DisplayName("the message segment is sixty-one encoded bytes taken from byte nineteen, and it "
                + "opens with the filler space that follows the message number")
        void theMessageSegmentIsSixtyOneEncodedBytesFromByteNineteen() {
            final SubprogramResult result =
                    service.validateDate(HYPHENATED_DATE, DateFormat.YYYY_MM_DD);

            assertAll("the tail the callers overlay",
                    () -> assertThat(encodedBytes(result.messageSegment()))
                            .isEqualTo(ORACLE_MESSAGE_SEGMENT_WIDTH),
                    () -> assertThat(result.messageSegment())
                            .isEqualTo(byteSlice(result.render(), 19, ORACLE_MESSAGE_SEGMENT_WIDTH)),
                    () -> assertThat(result.messageSegment())
                            .startsWith(" " + ORACLE_TEXT_DATE_IS_VALID));
        }

        @Test
        @DisplayName("a short value is padded on the right to its declared byte width, never truncated, "
                + "so its own bytes survive intact")
        void aShortValueIsPaddedToItsDeclaredByteWidth() {
            final SubprogramResult result = service.validateDate(SHORT_DATE, DateFormat.YYYY_MM_DD);

            assertAll("pad, do not truncate",
                    () -> assertThat(encodedBytes(result.testedDate()))
                            .isEqualTo(ORACLE_LINKAGE_TEXT_WIDTH),
                    () -> assertThat(result.testedDate()).isEqualTo("2022      "),
                    () -> assertThat(result.testedDate()).startsWith(SHORT_DATE),
                    () -> assertThat(byteSlice(result.render(), 45, ORACLE_LINKAGE_TEXT_WIDTH))
                            .as("the padded image is what reaches the block")
                            .isEqualTo("2022      "),
                    () -> assertThat(encodedBytes(result.render()))
                            .isEqualTo(ORACLE_RESULT_BLOCK_WIDTH));
        }

        @Test
        @DisplayName("an over-long value loses its rightmost excess so the declared byte width is never "
                + "exceeded, which is what keeps the block renderable")
        void anOverLongValueLosesItsRightmostExcess() {
            final SubprogramResult result = service.validateDate(OVERLONG_DATE, DateFormat.YYYYMMDD);

            assertAll("truncation is on the right, and only past the declared width",
                    () -> assertThat(encodedBytes(result.testedDate()))
                            .isEqualTo(ORACLE_LINKAGE_TEXT_WIDTH),
                    () -> assertThat(result.testedDate()).isEqualTo(OVERLONG_DATE),
                    () -> assertThat(encodedBytes(result.render()))
                            .isEqualTo(ORACLE_RESULT_BLOCK_WIDTH));
        }

        @Test
        @DisplayName("nothing in the block may be trimmed: the trailing filler is contract, and trimming "
                + "would shorten the block below its declared width")
        void nothingInTheBlockMayBeTrimmed() {
            final String block = service.validateDate(
                    " ".repeat(ORACLE_LINKAGE_TEXT_WIDTH), DateFormat.YYYY_MM_DD).render();

            assertAll("the padding carries meaning",
                    () -> assertThat(encodedBytes(block)).isEqualTo(ORACLE_RESULT_BLOCK_WIDTH),
                    () -> assertThat(byteSlice(block, 45, ORACLE_LINKAGE_TEXT_WIDTH))
                            .as("a blank date stays blank across all ten of its bytes")
                            .isEqualTo(" ".repeat(ORACLE_LINKAGE_TEXT_WIDTH)),
                    () -> assertThat(byteSlice(block, 77, 3)).isEqualTo("   "),
                    () -> assertThat(encodedBytes(block.trim()))
                            .as("trimming would destroy the fixed-width contract, so it is never done")
                            .isLessThan(ORACLE_RESULT_BLOCK_WIDTH));
        }

        @Test
        @DisplayName("severity and message number are two separate four-byte fields and are never "
                + "collapsed into one verdict")
        void severityAndMessageNumberAreNeverCollapsed() {
            final SubprogramResult tolerated =
                    service.validateDate(HYPHENATED_BEFORE_LILIAN, DateFormat.YYYY_MM_DD);
            final SubprogramResult untolerated =
                    service.validateDate(HYPHENATED_MONTH_ABOVE_RANGE, DateFormat.YYYY_MM_DD);

            assertAll("one severity, two message numbers, two different verdicts",
                    () -> assertThat(encodedBytes(tolerated.severityCode()))
                            .isEqualTo(ORACLE_CODE_WIDTH),
                    () -> assertThat(encodedBytes(tolerated.messageNumber()))
                            .isEqualTo(ORACLE_CODE_WIDTH),
                    () -> assertThat(tolerated.severityCode()).isEqualTo(ORACLE_FAILURE_SEVERITY),
                    () -> assertThat(untolerated.severityCode()).isEqualTo(ORACLE_FAILURE_SEVERITY),
                    () -> assertThat(tolerated.messageNumber())
                            .isEqualTo(ORACLE_TOLERATED_MESSAGE_NUMBER),
                    () -> assertThat(untolerated.messageNumber()).isEqualTo("2517"),
                    () -> assertThat(service.isDateAcceptable(tolerated))
                            .as("same severity, tolerated number, accepted")
                            .isTrue(),
                    () -> assertThat(service.isDateAcceptable(untolerated))
                            .as("same severity, other number, rejected")
                            .isFalse());
        }

        @Test
        @DisplayName("the two outcomes carrying an all-zero message number are separated by their "
                + "severity and their result text, never by the message number alone")
        void theAllZeroMessageNumberOutcomesAreSeparatedBySeverityAndText() {
            final SubprogramResult valid =
                    service.validateDate(HYPHENATED_DATE, DateFormat.YYYY_MM_DD);
            final SubprogramResult unrecognised = new SubprogramResult(
                    DateFeedback.UNRECOGNISED_FEEDBACK, ORACLE_FAILURE_SEVERITY,
                    ORACLE_ZERO_MESSAGE_NUMBER, ORACLE_TEXT_DATE_IS_INVALID, HYPHENATED_DATE,
                    HYPHENATED_MASK_VALUE);

            assertAll("the all-zero token is ambiguous on its number and unambiguous on the pair",
                    () -> assertThat(valid.messageNumber()).isEqualTo(ORACLE_ZERO_MESSAGE_NUMBER),
                    () -> assertThat(unrecognised.messageNumber())
                            .as("the number alone cannot tell them apart")
                            .isEqualTo(ORACLE_ZERO_MESSAGE_NUMBER),
                    () -> assertThat(valid.severityCode()).isEqualTo(ORACLE_ACCEPTED_SEVERITY),
                    () -> assertThat(unrecognised.severityCode()).isEqualTo(ORACLE_FAILURE_SEVERITY),
                    () -> assertThat(valid.resultText()).isEqualTo(ORACLE_TEXT_DATE_IS_VALID),
                    () -> assertThat(unrecognised.resultText()).isEqualTo(ORACLE_TEXT_DATE_IS_INVALID),
                    () -> assertThat(service.isDateAcceptable(valid)).isTrue(),
                    () -> assertThat(service.isDateAcceptable(unrecognised)).isFalse());
        }

        @Test
        @DisplayName("a block whose components no longer fill eighty bytes is refused rather than "
                + "silently shipped short")
        void anUnderfilledBlockIsRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new SubprogramResult(DateFeedback.DATE_IS_VALID,
                            ORACLE_ACCEPTED_SEVERITY, ORACLE_ZERO_MESSAGE_NUMBER,
                            ORACLE_TEXT_DATE_IS_VALID, "short", HYPHENATED_MASK_VALUE))
                    .withMessageContaining("testedDate");
        }
    }

    @Nested
    @DisplayName("the format-mask parameter: two declared masks, an exact ten-byte match, and no "
            + "silent fallback")
    final class DateFormatParameterContract {

        @Test
        @DisplayName("exactly two masks are declared, which is the whole vocabulary the subprogram accepts")
        void exactlyTwoMasksAreDeclared() {
            assertThat(DateFormat.values()).hasSize(2);
            assertThat(EnumSet.allOf(DateFormat.class))
                    .containsExactly(DateFormat.YYYY_MM_DD, DateFormat.YYYYMMDD);
        }

        @Test
        @DisplayName("each declared mask is exactly ten encoded bytes, and the compact one carries two "
                + "contractual trailing spaces that pad it to the linkage width")
        void eachDeclaredMaskIsTenEncodedBytes() {
            assertAll("the mask field is ten bytes wide in the linkage area",
                    () -> assertThat(DateFormat.YYYY_MM_DD.getValue())
                            .isEqualTo(HYPHENATED_MASK_VALUE),
                    () -> assertThat(encodedBytes(DateFormat.YYYY_MM_DD.getValue()))
                            .isEqualTo(ORACLE_LINKAGE_TEXT_WIDTH),
                    () -> assertThat(DateFormat.YYYYMMDD.getValue()).isEqualTo(COMPACT_MASK_VALUE),
                    () -> assertThat(encodedBytes(DateFormat.YYYYMMDD.getValue()))
                            .isEqualTo(ORACLE_LINKAGE_TEXT_WIDTH),
                    () -> assertThat(DateFormat.YYYYMMDD.getValue())
                            .as("the two trailing spaces are part of the declared value")
                            .endsWith("  "));
        }

        @Test
        @DisplayName("both declared masks are honoured, each against the date shape it describes")
        void bothDeclaredMasksAreHonoured() {
            final SubprogramResult hyphenated =
                    service.validateDate(HYPHENATED_DATE, DateFormat.YYYY_MM_DD);
            final SubprogramResult compact = service.validateDate(VALID_DATE, DateFormat.YYYYMMDD);

            assertAll("each mask parses its own shape",
                    () -> assertThat(hyphenated.feedback()).isEqualTo(DateFeedback.DATE_IS_VALID),
                    () -> assertThat(hyphenated.maskUsed()).isEqualTo(HYPHENATED_MASK_VALUE),
                    () -> assertThat(compact.feedback()).isEqualTo(DateFeedback.DATE_IS_VALID),
                    () -> assertThat(compact.maskUsed()).isEqualTo(COMPACT_MASK_VALUE));
        }

        @Test
        @DisplayName("a date presented under the wrong declared mask is never a bad mask, because the "
                + "mask itself resolved: the shape mismatch surfaces at the digit-position test, which "
                + "the source evaluates before the date-value test")
        void aDateUnderTheWrongDeclaredMaskFailsTheDigitPositionTest() {
            final SubprogramResult compactUnderHyphenated =
                    service.validateDate(VALID_DATE, DateFormat.YYYY_MM_DD);
            final SubprogramResult hyphenatedUnderCompact =
                    service.validateDate(HYPHENATED_DATE, DateFormat.YYYYMMDD);

            assertAll("both directions of the mismatch land on the earlier clause",
                    () -> assertThat(compactUnderHyphenated.feedback())
                            .as("the separators the resolved mask demands are digits instead")
                            .isEqualTo(DateFeedback.NON_NUMERIC_DATA),
                    () -> assertThat(compactUnderHyphenated.messageNumber()).isEqualTo("2520"),
                    () -> assertThat(compactUnderHyphenated.resultText())
                            .isEqualTo(ORACLE_TEXT_NON_NUMERIC_DATA),
                    () -> assertThat(compactUnderHyphenated.maskUsed())
                            .as("the mask resolved, so it is echoed back rather than faulted")
                            .isEqualTo(HYPHENATED_MASK_VALUE),
                    () -> assertThat(hyphenatedUnderCompact.feedback())
                            .as("the digits the compact mask demands are separators instead")
                            .isEqualTo(DateFeedback.NON_NUMERIC_DATA),
                    () -> assertThat(hyphenatedUnderCompact.messageNumber()).isEqualTo("2520"),
                    () -> assertThat(hyphenatedUnderCompact.maskUsed()).isEqualTo(COMPACT_MASK_VALUE),
                    () -> assertThat(service.isDateAcceptable(compactUnderHyphenated)).isFalse(),
                    () -> assertThat(service.isDateAcceptable(hyphenatedUnderCompact)).isFalse());
        }

        @Test
        @DisplayName("a correctly shaped but impossible date under a resolved mask is the bad-value "
                + "outcome, which is the clause that follows the digit-position test")
        void aCorrectlyShapedImpossibleDateIsTheBadValueOutcome() {
            final SubprogramResult result =
                    service.validateDate(HYPHENATED_THIRTY_FIRST_OF_SHORT_MONTH, DateFormat.YYYY_MM_DD);

            assertAll("every digit is in place, so the value itself is what fails",
                    () -> assertThat(result.feedback()).isEqualTo(DateFeedback.BAD_DATE_VALUE),
                    () -> assertThat(result.messageNumber()).isEqualTo("2508"),
                    () -> assertThat(result.resultText()).isEqualTo(ORACLE_TEXT_DATEVALUE_ERROR),
                    () -> assertThat(result.maskUsed()).isEqualTo(HYPHENATED_MASK_VALUE));
        }

        /**
         * Masks that no declared value can match once the ten-byte linkage move has been applied.
         *
         * @return unrecognised mask images
         */
        static Stream<Arguments> unrecognisedMasks() {
            return Stream.of(
                    arguments(UNSUPPORTED_MASK_VALUE),
                    arguments("MM-DD-YYYY"),
                    arguments("YYYY/MM/DD"),
                    arguments("yyyy-mm-dd"),
                    arguments("CCYYMMDD  "),
                    arguments("          "),
                    arguments(""));
        }

        @ParameterizedTest(name = "mask [{0}] is a bad picture string")
        @MethodSource("unrecognisedMasks")
        @DisplayName("an unrecognised mask is reported as a bad picture string rather than silently "
                + "falling back to a supported one")
        void anUnrecognisedMaskIsABadPictureString(final String mask) {
            final SubprogramResult result = service.validateDate(HYPHENATED_DATE, mask);

            assertAll("no fallback, and the offending mask is echoed back",
                    () -> assertThat(result.feedback()).isEqualTo(DateFeedback.BAD_PICTURE_STRING),
                    () -> assertThat(result.severityCode()).isEqualTo(ORACLE_FAILURE_SEVERITY),
                    () -> assertThat(result.messageNumber()).isEqualTo("2518"),
                    () -> assertThat(result.resultText()).isEqualTo(ORACLE_TEXT_BAD_PICTURE_STRING),
                    () -> assertThat(encodedBytes(result.maskUsed()))
                            .isEqualTo(ORACLE_LINKAGE_TEXT_WIDTH),
                    () -> assertThat(service.isDateAcceptable(result))
                            .as("2518 is not the tolerated number")
                            .isFalse());
        }

        @Test
        @DisplayName("the mask is resolved after the ten-byte linkage move, so an eight-character "
                + "compact mask pads into the declared value and is accepted")
        void theMaskIsResolvedAfterTheLinkageMove() {
            final SubprogramResult result = service.validateDate(VALID_DATE, "YYYYMMDD");

            assertAll("padding happens first, resolution second",
                    () -> assertThat(result.maskUsed()).isEqualTo(COMPACT_MASK_VALUE),
                    () -> assertThat(result.feedback()).isEqualTo(DateFeedback.DATE_IS_VALID),
                    () -> assertThat(DateFormat.fromValue("YYYYMMDD"))
                            .as("resolution on its own is exact and does not pad")
                            .isEmpty());
        }

        @Test
        @DisplayName("mask resolution is an exact ten-byte match with no fallback and no case folding")
        void maskResolutionIsExactWithNoFallback() {
            assertAll("the lookup is literal",
                    () -> assertThat(DateFormat.fromValue(HYPHENATED_MASK_VALUE))
                            .contains(DateFormat.YYYY_MM_DD),
                    () -> assertThat(DateFormat.fromValue(COMPACT_MASK_VALUE))
                            .contains(DateFormat.YYYYMMDD),
                    () -> assertThat(DateFormat.fromValue(UNSUPPORTED_MASK_VALUE)).isEmpty(),
                    () -> assertThat(DateFormat.fromValue("yyyy-mm-dd"))
                            .as("no case folding")
                            .isEmpty(),
                    () -> assertThat(DateFormat.fromValue("YYYY-MM-DD "))
                            .as("eleven bytes is not the declared value")
                            .isEmpty(),
                    () -> assertThat(DateFormat.fromValue(null))
                            .as("null is tolerated and resolves to nothing")
                            .isEmpty());
        }

        @Test
        @DisplayName("a null mask is refused on both overloads, and the cast that picks the overload is "
                + "part of the calling contract")
        void aNullMaskIsRefusedOnBothOverloads() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> service.validateDate(HYPHENATED_DATE, (DateFormat) null))
                    .withMessageContaining("dateFormat");
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> service.validateDate(HYPHENATED_DATE, (String) null))
                    .withMessageContaining("formatMask");
        }

        @Test
        @DisplayName("a null date is refused on both overloads, because an absent field is not a blank one")
        void aNullDateIsRefusedOnBothOverloads() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> service.validateDate(null, DateFormat.YYYY_MM_DD))
                    .withMessageContaining("candidateDate");
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> service.validateDate(null, HYPHENATED_MASK_VALUE))
                    .withMessageContaining("candidateDate");
        }
    }

    @Nested
    @DisplayName("the flag-group guard that gates the fifth stage: it reproduces the pessimistic constant "
            + "the head paragraph writes, so the stage opens only when all three field stages have cleared")
    final class LanguageEnvironmentStageGuard {

        @Test
        @DisplayName("the head paragraph writes the pessimistic three-character constant, so nothing is "
                + "presumed valid before a stage has said so")
        void theHeadParagraphWritesThePessimisticConstant() {
            final DateEditResult headOnly = service.validateCcyymmddDate(ALL_SPACES_DATE);

            assertAll("the group starts wholly unfavourable and the guard tests for its opposite",
                    () -> assertThat(encodedBytes(ORACLE_HEAD_PARAGRAPH_FLAG_GROUP))
                            .isEqualTo(ORACLE_FLAG_GROUP_WIDTH),
                    () -> assertThat(encodedBytes(ORACLE_ALL_VALID_FLAG_GROUP))
                            .as("the favourable state is the same width, written as low values")
                            .isEqualTo(ORACLE_FLAG_GROUP_WIDTH),
                    () -> assertThat(ORACLE_HEAD_PARAGRAPH_FLAG_GROUP)
                            .isNotEqualTo(ORACLE_ALL_VALID_FLAG_GROUP),
                    () -> assertThat(headOnly.flagsImage())
                            .as("a wholly blank date never clears a single stage")
                            .isNotEqualTo(ORACLE_ALL_VALID_FLAG_GROUP),
                    () -> assertThat(headOnly.inputError()).isTrue());
        }

        @Test
        @DisplayName("the guard opens only on the wholly favourable group, which is the one state the "
                + "head paragraph never writes")
        void theGuardOpensOnlyOnTheWhollyFavourableGroup() {
            assertAll("open on all-clear, closed on anything else",
                    () -> assertThat(service.validateCcyymmddDate(VALID_DATE).flagsImage())
                            .as("every stage cleared, so the guard opened and the stage exit re-marked "
                                    + "the group")
                            .isEqualTo(ORACLE_ALL_VALID_FLAG_GROUP),
                    () -> assertThat(service.validateCcyymmddDate(MONTH_ABOVE_RANGE_DATE).flagsImage())
                            .isNotEqualTo(ORACLE_ALL_VALID_FLAG_GROUP),
                    () -> assertThat(service.validateCcyymmddDate(DAY_ABOVE_RANGE_DATE).flagsImage())
                            .isNotEqualTo(ORACLE_ALL_VALID_FLAG_GROUP),
                    () -> assertThat(service.validateCcyymmddDate(INVALID_CENTURY_DATE).flagsImage())
                            .isNotEqualTo(ORACLE_ALL_VALID_FLAG_GROUP),
                    () -> assertThat(
                            service.validateCcyymmddDate(THIRTY_FIRST_OF_SHORT_MONTH_DATE).flagsImage())
                            .as("a combination failure closes the guard just as a field failure does")
                            .isNotEqualTo(ORACLE_ALL_VALID_FLAG_GROUP));
        }

        /**
         * An independent day-count oracle written straight from the copybook's own rules, so that the
         * sweep below never asks the code under test what the answer is.
         *
         * <p>The sweep is restricted to two years whose century is inside the accepted pair and whose
         * leap status follows the plain four-year rule, which keeps the century-boundary subtlety out of
         * this oracle; the two century witnesses are asserted separately.
         *
         * @param year  the four-digit year
         * @param month the month slot, which may be outside one to twelve
         * @param day   the day slot, which may be outside one to thirty-one
         * @return whether the copybook's rules admit the combination
         */
        private static boolean admittedByIndependentOracle(final int year, final int month,
                                                           final int day) {
            final int century = year / 100;
            if (century != ORACLE_THIS_CENTURY && century != ORACLE_LAST_CENTURY) {
                return false;
            }
            if (month < 1 || month > 12 || day < 1 || day > 31) {
                return false;
            }
            if (ORACLE_THIRTY_ONE_DAY_MONTHS.contains(month)) {
                return true;
            }
            if (month == ORACLE_FEBRUARY) {
                return day <= (year % 4 == 0 ? 29 : 28);
            }
            return day <= 30;
        }

        @Test
        @DisplayName("across every month and day slot the cascade admits exactly what the copybook's own "
                + "rules admit, which is what makes the fifth stage redundant rather than load-bearing")
        void theCascadeAdmitsExactlyWhatTheCopybookRulesAdmit() {
            final StringBuilder disagreements = new StringBuilder();

            for (final int year : new int[] {2022, 2024}) {
                for (int month = 0; month <= 13; month++) {
                    for (int day = 0; day <= 32; day++) {
                        final String candidate =
                                String.format("%04d%02d%02d", year, month, day);
                        if (encodedBytes(candidate) != ORACLE_CCYYMMDD_WIDTH) {
                            continue;
                        }
                        final boolean expected = admittedByIndependentOracle(year, month, day);
                        final boolean actual = !service.validateCcyymmddDate(candidate).inputError();
                        if (expected != actual) {
                            disagreements.append('[').append(candidate)
                                    .append("] expected admitted=").append(expected)
                                    .append(" but was ").append(actual).append(System.lineSeparator());
                        }
                    }
                }
            }

            assertThat(disagreements.toString())
                    .as("a head-paragraph-only translation would admit every one of these")
                    .isEmpty();
        }

        @Test
        @DisplayName("the redundant fifth stage cannot change the verdict, because the two century leap "
                + "witnesses are already settled by the combination stage")
        void theRedundantFifthStageCannotChangeTheVerdict() {
            assertAll("the stage runs on the cleared path and finds nothing left to reject",
                    () -> assertThat(service.validateCcyymmddDate(LEAP_DAY_CENTURY_ACCEPTED).inputError())
                            .as("a four-hundred-year leap day is admitted before the stage is entered")
                            .isFalse(),
                    () -> assertThat(service.validateCcyymmddDate(LEAP_DAY_CENTURY_ACCEPTED).flagsImage())
                            .isEqualTo(ORACLE_ALL_VALID_FLAG_GROUP),
                    () -> assertThat(service.validateCcyymmddDate(LEAP_DAY_CENTURY_REJECTED).inputError())
                            .as("a hundred-year non-leap day is rejected before the stage is reached")
                            .isTrue(),
                    () -> assertThat(
                            service.validateCcyymmddDate(LEAP_DAY_CENTURY_REJECTED).returnMessage())
                            .isEqualTo(ORACLE_NOT_A_LEAP_YEAR),
                    () -> assertThat(service.validateCcyymmddDate(LEAP_DAY_CENTURY_REJECTED).flagsImage())
                            .as("the guard is therefore closed for it")
                            .isNotEqualTo(ORACLE_ALL_VALID_FLAG_GROUP));
        }

        @Test
        @DisplayName("the stage's own verification agrees with the cascade for every date that reaches "
                + "it, which is exactly why its rejection branch is unreachable from outside")
        void theStageAgreesWithTheCascadeForEveryDateThatReachesIt() {
            final String[] datesThatClearEveryStage = {VALID_DATE, VALID_LAST_DAY_OF_YEAR,
                LEAP_DAY_ORDINARY, LEAP_DAY_CENTURY_ACCEPTED};

            for (final String candidate : datesThatClearEveryStage) {
                final DateEditResult cascade = service.validateCcyymmddDate(candidate);
                final SubprogramResult stage = service.validateDate(candidate, DateFormat.YYYYMMDD);

                assertAll("agreement for [" + candidate + "]",
                        () -> assertThat(cascade.inputError()).isFalse(),
                        () -> assertThat(cascade.flagsImage()).isEqualTo(ORACLE_ALL_VALID_FLAG_GROUP),
                        () -> assertThat(stage.feedback()).isEqualTo(DateFeedback.DATE_IS_VALID),
                        () -> assertThat(stage.severityCode()).isEqualTo(ORACLE_ACCEPTED_SEVERITY),
                        () -> assertThat(service.isDateAcceptable(stage)).isTrue());
            }
        }
    }

    @Nested
    @DisplayName("the birth-date range sits outside the main cascade, so it is a supplement to the "
            + "cascade and never a substitute for it")
    final class DateOfBirthEntryPointIsSeparate {

        /**
         * Values the cascade rejects at an inner stage and which are not resolvable calendar dates.
         *
         * @return candidate images
         */
        static Stream<Arguments> innerStageFailuresThatAreNotCalendarDates() {
            return Stream.of(
                    arguments(MONTH_BELOW_RANGE_DATE),
                    arguments(MONTH_ABOVE_RANGE_DATE),
                    arguments(DAY_BELOW_RANGE_DATE),
                    arguments(DAY_ABOVE_RANGE_DATE),
                    arguments(THIRTY_FIRST_OF_SHORT_MONTH_DATE),
                    arguments(THIRTIETH_OF_FEBRUARY_DATE),
                    arguments(LEAP_DAY_COMMON_YEAR),
                    arguments(BAD_MONTH_AND_BAD_DAY_DATE));
        }

        @ParameterizedTest(name = "[{0}] is stopped by the cascade before the birth check is reached")
        @MethodSource("innerStageFailuresThatAreNotCalendarDates")
        @DisplayName("the composition the callers use stops an inner-stage failure at the cascade, so the "
                + "birth check is never asked to judge a value the cascade has already refused")
        void theCompositionStopsAnInnerStageFailureAtTheCascade(final String candidate) {
            final DateEditResult cascade = service.validateCcyymmddDate(candidate);

            assertAll("the caller's guard is the cascade's own verdict",
                    () -> assertThat(cascade.inputError()).isTrue(),
                    () -> assertThat(cascade.flagsImage()).isNotEqualTo(ORACLE_ALL_VALID_FLAG_GROUP),
                    () -> assertThat(cascade.returnMessage()).isNotEqualTo(ORACLE_NO_MESSAGE));
        }

        @ParameterizedTest(name = "[{0}] is refused loudly if the birth check is entered out of order")
        @MethodSource("innerStageFailuresThatAreNotCalendarDates")
        @DisplayName("entered out of order on an unresolvable image the birth check refuses loudly rather "
                + "than accepting silently, so no caller can use it to bypass the cascade")
        void theBirthCheckRefusesAnUnresolvableImageLoudly(final String candidate) {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> service.validateDateOfBirth(candidate, CURRENT_DATE))
                    .withMessageContaining(candidate);
        }

        @Test
        @DisplayName("the birth check runs none of the field stages: a century the cascade refuses is "
                + "still an ordinary past date to the birth check alone")
        void theBirthCheckRunsNoneOfTheFieldStages() {
            final DateEditResult cascade = service.validateCcyymmddDate(INVALID_CENTURY_DATE);
            final DateEditResult birthOnly =
                    service.validateDateOfBirth(INVALID_CENTURY_DATE, CURRENT_DATE);

            assertAll("two different questions about the same image",
                    () -> assertThat(cascade.inputError())
                            .as("the cascade owns the century rule")
                            .isTrue(),
                    () -> assertThat(cascade.returnMessage()).isEqualTo(ORACLE_CENTURY_NOT_VALID),
                    () -> assertThat(birthOnly.inputError())
                            .as("the birth check asks only whether the date is in the past")
                            .isFalse(),
                    () -> assertThat(birthOnly.returnMessage()).isEqualTo(ORACLE_NO_MESSAGE),
                    () -> assertThat(birthOnly.flagsImage()).isEqualTo(ORACLE_ALL_VALID_FLAG_GROUP));
        }

        @Test
        @DisplayName("the two verdicts compose the way the caller composes them: a well-formed future "
                + "date clears the cascade and is then refused by the birth check")
        void theTwoVerdictsComposeTheWayTheCallerComposesThem() {
            final String futureDate = "20991231";
            final DateEditResult cascade = service.validateCcyymmddDate(futureDate);
            final DateEditResult birthCheck = service.validateDateOfBirth(futureDate, CURRENT_DATE);

            assertAll("cascade first, birth check second",
                    () -> assertThat(cascade.inputError())
                            .as("well formed, so the cascade passes it through")
                            .isFalse(),
                    () -> assertThat(cascade.flagsImage()).isEqualTo(ORACLE_ALL_VALID_FLAG_GROUP),
                    () -> assertThat(birthCheck.inputError())
                            .as("only the birth check knows it is unreasonable")
                            .isTrue(),
                    () -> assertThat(birthCheck.returnMessage()).isEqualTo(ORACLE_DATE_IN_FUTURE),
                    () -> assertThat(birthCheck.flagsImage())
                            .isEqualTo(ORACLE_HEAD_PARAGRAPH_FLAG_GROUP));
        }

        @Test
        @DisplayName("a value that clears both is accepted by both, so the supplement adds a rule and "
                + "removes none")
        void aValueThatClearsBothIsAcceptedByBoth() {
            final DateEditResult cascade = service.validateCcyymmddDate(VALID_DATE);
            final DateEditResult birthCheck = service.validateDateOfBirth(VALID_DATE, CURRENT_DATE);

            assertAll("both verdicts favourable",
                    () -> assertThat(cascade.inputError()).isFalse(),
                    () -> assertThat(birthCheck.inputError()).isFalse(),
                    () -> assertThat(cascade.returnMessage()).isEqualTo(ORACLE_NO_MESSAGE),
                    () -> assertThat(birthCheck.returnMessage()).isEqualTo(ORACLE_NO_MESSAGE));
        }
    }

    @Nested
    @DisplayName("every message is contract data reproduced byte for byte, with no trimming and no "
            + "tidying of the source's own punctuation")
    final class MessageTextIsContractData {

        @Test
        @DisplayName("the leap-year message runs two sentences together with NO space after its first "
                + "period, and that is asserted on the byte that follows the period")
        void theLeapYearMessageHasNoSpaceAfterItsFirstPeriod() {
            final int firstPeriod = ORACLE_NOT_A_LEAP_YEAR.indexOf('.');
            final DateEditResult produced = service.validateCcyymmddDate(LEAP_DAY_COMMON_YEAR);

            assertAll("the run-together punctuation is the contract",
                    () -> assertThat(firstPeriod).isPositive(),
                    () -> assertThat(ORACLE_NOT_A_LEAP_YEAR.charAt(firstPeriod + 1))
                            .as("the character after the first period is a capital, not a space")
                            .isEqualTo('C'),
                    () -> assertThat(ORACLE_NOT_A_LEAP_YEAR).doesNotContain(". "),
                    () -> assertThat(produced.returnMessage())
                            .as("and the service reproduces it exactly")
                            .isEqualTo(ORACLE_NOT_A_LEAP_YEAR),
                    () -> assertThat(encodedBytes(produced.returnMessage()))
                            .isEqualTo(encodedBytes(ORACLE_NOT_A_LEAP_YEAR)));
        }

        /**
         * Each candidate paired with the exact message the copybook writes for it.
         *
         * @return the candidate and its verbatim message
         */
        static Stream<Arguments> messagesTheCascadeCanWrite() {
            return Stream.of(
                    arguments(ALL_SPACES_DATE, ORACLE_YEAR_NOT_SUPPLIED),
                    arguments(NON_NUMERIC_YEAR_DATE, ORACLE_YEAR_NOT_FOUR_DIGITS),
                    arguments(INVALID_CENTURY_DATE, ORACLE_CENTURY_NOT_VALID),
                    arguments(BLANK_MONTH_DATE, ORACLE_MONTH_NOT_SUPPLIED),
                    arguments(MONTH_ABOVE_RANGE_DATE, ORACLE_MONTH_OUT_OF_RANGE),
                    arguments(MONTH_BELOW_RANGE_DATE, ORACLE_MONTH_OUT_OF_RANGE),
                    arguments(BLANK_DAY_DATE, ORACLE_DAY_NOT_SUPPLIED),
                    arguments(DAY_ABOVE_RANGE_DATE, ORACLE_DAY_OUT_OF_RANGE),
                    arguments(DAY_BELOW_RANGE_DATE, ORACLE_DAY_OUT_OF_RANGE),
                    arguments(THIRTY_FIRST_OF_SHORT_MONTH_DATE, ORACLE_CANNOT_HAVE_31_DAYS),
                    arguments(THIRTIETH_OF_FEBRUARY_DATE, ORACLE_CANNOT_HAVE_30_DAYS),
                    arguments(LEAP_DAY_COMMON_YEAR, ORACLE_NOT_A_LEAP_YEAR),
                    arguments(VALID_DATE, ORACLE_NO_MESSAGE));
        }

        @ParameterizedTest(name = "[{0}] writes [{1}]")
        @MethodSource("messagesTheCascadeCanWrite")
        @DisplayName("every message the cascade can write matches its literal byte for byte, and its "
                + "encoded byte count matches too, so nothing has been trimmed on either side")
        void everyMessageMatchesItsLiteralByteForByte(final String candidate, final String expected) {
            final String actual = service.validateCcyymmddDate(candidate).returnMessage();

            assertAll("verbatim, and the same number of bytes",
                    () -> assertThat(actual).isEqualTo(expected),
                    () -> assertThat(encodedBytes(actual)).isEqualTo(encodedBytes(expected)));
        }

        @Test
        @DisplayName("the four supply messages open with a spaced colon, and the year-width message is "
                + "the single exception that carries no colon at all")
        void theSupplyMessagesOpenWithASpacedColonExceptOne() {
            assertAll("the source's own inconsistency, preserved",
                    () -> assertThat(ORACLE_YEAR_NOT_SUPPLIED).startsWith(" : "),
                    () -> assertThat(ORACLE_CENTURY_NOT_VALID).startsWith(" : "),
                    () -> assertThat(ORACLE_MONTH_NOT_SUPPLIED).startsWith(" : "),
                    () -> assertThat(ORACLE_DAY_NOT_SUPPLIED).startsWith(" : "),
                    () -> assertThat(ORACLE_YEAR_NOT_FOUR_DIGITS)
                            .as("no colon anywhere in the one exception")
                            .doesNotContain(":")
                            .startsWith(" must"));
        }

        @Test
        @DisplayName("the range and combination messages open with a bare colon, and only the month one "
                + "puts a space after it")
        void theRangeMessagesOpenWithABareColon() {
            assertAll("bare colon, and one space that only the month message has",
                    () -> assertThat(ORACLE_MONTH_OUT_OF_RANGE).startsWith(": Month"),
                    () -> assertThat(ORACLE_DAY_OUT_OF_RANGE)
                            .as("lower-case day, and no space after the colon")
                            .startsWith(":day"),
                    () -> assertThat(ORACLE_CANNOT_HAVE_31_DAYS).startsWith(":Cannot"),
                    () -> assertThat(ORACLE_CANNOT_HAVE_30_DAYS).startsWith(":Cannot"),
                    () -> assertThat(ORACLE_NOT_A_LEAP_YEAR).startsWith(":Not"),
                    () -> assertThat(ORACLE_DATE_IN_FUTURE).startsWith(":cannot"));
        }

        @Test
        @DisplayName("the future message keeps the trailing space the source literal carries, which a "
                + "trimming comparison would silently lose")
        void theFutureMessageKeepsItsTrailingSpace() {
            final DateEditResult refused = service.validateDateOfBirth("20991231", CURRENT_DATE);

            assertAll("the trailing space is contract",
                    () -> assertThat(ORACLE_DATE_IN_FUTURE).endsWith(" "),
                    () -> assertThat(refused.returnMessage()).isEqualTo(ORACLE_DATE_IN_FUTURE),
                    () -> assertThat(refused.returnMessage()).endsWith(" "),
                    () -> assertThat(encodedBytes(refused.returnMessage()))
                            .as("a trimmed comparison would have lost a byte")
                            .isGreaterThan(encodedBytes(ORACLE_DATE_IN_FUTURE.trim())));
        }

        @Test
        @DisplayName("all twelve messages are distinct, so no failure can be reported as another")
        void allTwelveMessagesAreDistinct() {
            assertThat(Set.of(ORACLE_YEAR_NOT_SUPPLIED, ORACLE_YEAR_NOT_FOUR_DIGITS,
                    ORACLE_CENTURY_NOT_VALID, ORACLE_MONTH_NOT_SUPPLIED, ORACLE_MONTH_OUT_OF_RANGE,
                    ORACLE_DAY_NOT_SUPPLIED, ORACLE_DAY_OUT_OF_RANGE, ORACLE_CANNOT_HAVE_31_DAYS,
                    ORACLE_CANNOT_HAVE_30_DAYS, ORACLE_NOT_A_LEAP_YEAR, ORACLE_DATE_IN_FUTURE,
                    ORACLE_NO_MESSAGE)).hasSize(12);
        }
    }

    @Nested
    @DisplayName("absent, blank and malformed input: every one produces the documented verdict and none "
            + "escapes as an unchecked runtime failure")
    final class MalformedAndAbsentInput {

        @ParameterizedTest(name = "[{0}] is handled rather than thrown")
        @ValueSource(strings = {"", " ", "  ", "        ", "2022", "202201", "2022010199", "2X220101",
            "202201XX", "20221332", "ABCDEFGH", "-1234567", "20 20101", "0000-01-01", "        99"})
        @DisplayName("no malformed image escapes as a null-pointer or number-format failure: each is "
                + "classified and returned")
        void noMalformedImageEscapesAsARuntimeFailure(final String candidate) {
            assertThatNoException()
                    .isThrownBy(() -> service.validateCcyymmddDate(candidate));
            assertThatNoException()
                    .isThrownBy(() -> service.validateCcyymmddDate(candidate, CARRIED_IN_MESSAGE));
            assertThatNoException()
                    .isThrownBy(() -> service.validateDate(candidate, DateFormat.YYYYMMDD));
            assertThatNoException()
                    .isThrownBy(() -> service.validateDate(candidate, DateFormat.YYYY_MM_DD));
        }

        @ParameterizedTest(name = "[{0}] is rejected with a message")
        @ValueSource(strings = {"", " ", "  ", "        ", "2022", "202201", "2X220101", "202201XX",
            "20221332", "ABCDEFGH", "-1234567", "20 20101"})
        @DisplayName("every malformed image is rejected and claims a message, so nothing malformed is "
                + "waved through in silence")
        void everyMalformedImageIsRejectedWithAMessage(final String candidate) {
            final DateEditResult result = service.validateCcyymmddDate(candidate);

            assertAll("rejected, flagged and explained",
                    () -> assertThat(result.inputError()).isTrue(),
                    () -> assertThat(result.returnMessage()).isNotEqualTo(ORACLE_NO_MESSAGE),
                    () -> assertThat(result.flagsImage()).isNotEqualTo(ORACLE_ALL_VALID_FLAG_GROUP),
                    () -> assertThat(encodedBytes(result.flagsImage()))
                            .isEqualTo(ORACLE_FLAG_GROUP_WIDTH));
        }

        @Test
        @DisplayName("the empty string is an all-blank field rather than an absent one, and is reported "
                + "as a missing year")
        void theEmptyStringIsAnAllBlankFieldRatherThanAbsent() {
            final DateEditResult result = service.validateCcyymmddDate(EMPTY_DATE);

            assertAll("blank, not absent",
                    () -> assertThat(result.inputError()).isTrue(),
                    () -> assertThat(result.yearFlag()).isEqualTo(DateEditFlag.BLANK),
                    () -> assertThat(result.monthFlag()).isEqualTo(DateEditFlag.BLANK),
                    () -> assertThat(result.dayFlag()).isEqualTo(DateEditFlag.BLANK),
                    () -> assertThat(result.returnMessage()).isEqualTo(ORACLE_YEAR_NOT_SUPPLIED));
        }

        @Test
        @DisplayName("an over-long image is judged on its leading eight bytes only, so truncation is "
                + "visible in the verdict rather than hidden")
        void anOverLongImageIsJudgedOnItsLeadingEightBytes() {
            final DateEditResult truncated = service.validateCcyymmddDate(OVERLONG_DATE);

            assertAll("only the declared width is examined",
                    () -> assertThat(OVERLONG_DATE).startsWith(VALID_DATE),
                    () -> assertThat(truncated.inputError()).isFalse(),
                    () -> assertThat(truncated.flagsImage()).isEqualTo(ORACLE_ALL_VALID_FLAG_GROUP));
        }

        @Test
        @DisplayName("a null date is refused loudly at every entry point, because an absent field is not "
                + "a blank field")
        void aNullDateIsRefusedLoudlyAtEveryEntryPoint() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> service.validateCcyymmddDate(null))
                    .withMessageContaining("candidateDate");
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> service.validateCcyymmddDate(null, CARRIED_IN_MESSAGE))
                    .withMessageContaining("candidateDate");
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> service.validateDateOfBirth(null, CURRENT_DATE))
                    .withMessageContaining("candidateDate");
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> service.validateDate(null, DateFormat.YYYYMMDD))
                    .withMessageContaining("candidateDate");
        }

        @Test
        @DisplayName("a null carried-in message is refused, because the blank state is an empty string "
                + "and not an absent reference")
        void aNullCarriedInMessageIsRefused() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> service.validateCcyymmddDate(VALID_DATE, null))
                    .withMessageContaining("currentReturnMessage");
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> service.validateDateOfBirth(VALID_DATE, CURRENT_DATE, null))
                    .withMessageContaining("currentReturnMessage");
        }

        @Test
        @DisplayName("a null current date is refused on the birth check, since the comparison has no "
                + "meaning without it")
        void aNullCurrentDateIsRefusedOnTheBirthCheck() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> service.validateDateOfBirth(VALID_DATE, null))
                    .withMessageContaining("currentDate");
        }

        @ParameterizedTest(name = "[{0}] reaches the subprogram as a classified outcome")
        @ValueSource(strings = {"", "  ", "ABCDEFGHIJ", "----------", "2022-1X-01", "9999-99-99"})
        @DisplayName("the subprogram entry point classifies malformed input into one of its ten outcomes "
                + "instead of failing, and never reports it acceptable")
        void theSubprogramClassifiesMalformedInputInsteadOfFailing(final String candidate) {
            final SubprogramResult result = service.validateDate(candidate, DateFormat.YYYY_MM_DD);

            assertAll("classified, rendered and refused",
                    () -> assertThat(result.feedback()).isNotNull(),
                    () -> assertThat(encodedBytes(result.render()))
                            .isEqualTo(ORACLE_RESULT_BLOCK_WIDTH),
                    () -> assertThat(result.severityCode()).isEqualTo(ORACLE_FAILURE_SEVERITY),
                    () -> assertThat(service.isDateAcceptable(result)).isFalse());
        }
    }
}
