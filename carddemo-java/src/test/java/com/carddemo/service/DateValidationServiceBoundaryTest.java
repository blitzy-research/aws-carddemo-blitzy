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
import java.util.Arrays;

import com.carddemo.domain.enums.DateFormat;
import com.carddemo.service.DateValidationService.DateEditFlag;
import com.carddemo.service.DateValidationService.DateEditResult;
import com.carddemo.service.DateValidationService.DateFeedback;
import com.carddemo.service.DateValidationService.SubprogramResult;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Unit tests for {@link DateValidationService}, which carries both legacy date-validation
 * entry points of the estate.
 *
 * <h2>What is under test</h2>
 *
 * <p>Two entirely separate legacy constructs meet in this one class. The first is the
 * procedural copybook range {@code EDIT-DATE-CCYYMMDD THRU EDIT-DATE-CCYYMMDD-EXIT}
 * declared across {@code app/cpy/CSUTLDPY.cpy} lines 18 to 329, performed four times from
 * the account-maintenance transaction. The second is the callable subprogram
 * {@code app/cbl/CSUTLDTC.cbl}, invoked from four sites in the report-request and
 * transaction-add transactions through an eighty-byte result block.</p>
 *
 * <h2>Why the cascade is tested stage by stage rather than as a single verdict</h2>
 *
 * <p>The performed range spans eleven intermediate paragraphs, and its head paragraph
 * validates nothing at all: the behaviour lives entirely in the paragraphs it falls through
 * to. Translating only the head — the obvious reading for anyone who has not measured the
 * span — would make date validation appear to work while validating nothing. The
 * assertions below therefore exercise the year stage, the month stage, the day stage, the
 * combined day-month-year stage and the language-environment stage individually, and prove
 * that a failing stage does not abandon the range: a blank year and a bad month are both
 * reported on the same pass.</p>
 *
 * <h2>Why the accumulated message follows a first-wins rule</h2>
 *
 * <p>Each stage writes its message suffix only while the caller's message field is still
 * blank, because in the account-maintenance transaction that field is shared by every field
 * on the screen. The earliest failure therefore keeps the message even though later stages
 * continue to set their own flags, and a message already claimed by an earlier screen field
 * survives untouched. Both properties are asserted.</p>
 *
 * <h2>Why width is asserted in encoded bytes and unmappable values are refused</h2>
 *
 * <p>The subprogram's linkage areas are byte reservations: two ten-byte text parameters and
 * an eighty-byte result block. Every width check and every truncation therefore happens on
 * the encoded image rather than on the character sequence, and a value the single-byte
 * charset cannot carry is refused outright rather than transcoded to a substitute byte that
 * would still measure the right width. Control bytes are deliberately still accepted,
 * because the cascade's own blank test at {@code app/cpy/CSUTLDPY.cpy} line 30 treats a
 * null-filled field as unsupplied and must keep seeing it.</p>
 *
 * <p>Translated from the CardDemo COBOL estate at checkout commit
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19.</p>
 */
@DisplayName("DateValidationService: the copybook cascade and the callable date subprogram")
class DateValidationServiceBoundaryTest {

    /** Byte width of the subprogram's result block, {@code LS-RESULT PIC X(80)}. */
    private static final int RESULT_BLOCK_WIDTH = 80;

    /** Byte width of the message tail the callers overlay onto the block. */
    private static final int MESSAGE_SEGMENT_WIDTH = 61;

    /** Byte width of each of the two text linkage parameters. */
    private static final int LINKAGE_TEXT_WIDTH = 10;

    /** Byte width of the outcome-text field inside the result block. */
    private static final int RESULT_TEXT_WIDTH = 15;

    /** Byte width of the severity and message-number views inside the result block. */
    private static final int CODE_WIDTH = 4;

    /** The severity code that admits a date outright. */
    private static final String ACCEPTED_SEVERITY_CODE = "0000";

    /** The message number a non-zero severity is nevertheless forgiven for carrying. */
    private static final String TOLERATED_MESSAGE_NUMBER = "2513";

    /** Blank accumulated message, standing for the all-spaces state of the caller's field. */
    private static final String NO_MESSAGE = "";

    /** Year-stage message when the field was not supplied. */
    private static final String YEAR_NOT_SUPPLIED = " : Year must be supplied.";

    /** Year-stage message when the field was supplied but is not four digits. */
    private static final String YEAR_NOT_FOUR_DIGITS = " must be 4 digit number.";

    /** Year-stage message when the century is outside the two admitted values. */
    private static final String CENTURY_NOT_VALID = " : Century is not valid.";

    /** Month-stage message when the field was not supplied. */
    private static final String MONTH_NOT_SUPPLIED = " : Month must be supplied.";

    /** Month-stage message when the field is outside one to twelve. */
    private static final String MONTH_OUT_OF_RANGE = ": Month must be a number between 1 and 12.";

    /** Day-stage message when the field was not supplied. */
    private static final String DAY_NOT_SUPPLIED = " : Day must be supplied.";

    /** Day-stage message when the field is non-numeric or outside one to thirty-one. */
    private static final String DAY_OUT_OF_RANGE = ":day must be a number between 1 and 31.";

    /** Combined-stage message when a thirty-one is offered to a shorter month. */
    private static final String CANNOT_HAVE_31_DAYS = ":Cannot have 31 days in this month.";

    /** Combined-stage message when a thirty is offered to February. */
    private static final String CANNOT_HAVE_30_DAYS = ":Cannot have 30 days in this month.";

    /** Combined-stage message when a twenty-nine is offered to a non-leap February. */
    private static final String NOT_A_LEAP_YEAR =
            ":Not a leap year.Cannot have 29 days in this month.";

    /** Date-of-birth message when the candidate is not strictly in the past. */
    private static final String DATE_IN_FUTURE = ":cannot be in the future ";

    /** A value carrying a character the single-byte charset cannot represent. */
    private static final String UNMAPPABLE = "2024\u00e929";

    /** The service under test; it holds no state, so one instance serves every assertion. */
    private final DateValidationService service = new DateValidationService();

    /**
     * Measures a value in legacy single-byte characters.
     *
     * @param value the value to measure
     * @return the encoded byte count
     */
    private static int encodedWidth(String value) {
        return value.getBytes(StandardCharsets.US_ASCII).length;
    }

    /**
     * Builds a result block whose components are all at their declared widths.
     *
     * @param feedback the outcome the block reports
     * @return a valid result block
     */
    private static SubprogramResult blockFor(DateFeedback feedback) {
        return new SubprogramResult(feedback, "0003", "2508", "Datevalue error",
                "2023-02-29", "YYYY-MM-DD");
    }

    @Nested
    @DisplayName("the three-state per-field flag")
    class FlagStates {

        @Test
        @DisplayName("exactly three states exist: valid, supplied-but-wrong and not supplied")
        void exactlyThreeStatesExist() {
            assertThat(DateEditFlag.values())
                    .containsExactly(DateEditFlag.VALID, DateEditFlag.NOT_OK, DateEditFlag.BLANK);
        }

        @Test
        @DisplayName("the valid state occupies the null character, which is the low-values image")
        void theValidStateOccupiesTheNullCharacter() {
            assertThat(DateEditFlag.VALID.getImage()).isEqualTo('\u0000');
        }

        @Test
        @DisplayName("the supplied-but-wrong state occupies the digit zero")
        void theSuppliedButWrongStateOccupiesTheDigitZero() {
            assertThat(DateEditFlag.NOT_OK.getImage()).isEqualTo('0');
        }

        @Test
        @DisplayName("the not-supplied state occupies the letter B")
        void theNotSuppliedStateOccupiesTheLetterB() {
            assertThat(DateEditFlag.BLANK.getImage()).isEqualTo('B');
        }

        @Test
        @DisplayName("the two failure states are distinct, so missing and invalid stay separable")
        void theTwoFailureStatesAreDistinct() {
            assertThat(DateEditFlag.NOT_OK.getImage()).isNotEqualTo(DateEditFlag.BLANK.getImage());
        }

        @Test
        @DisplayName("every image is a single character, because the flag group is three bytes wide")
        void everyImageIsASingleCharacter() {
            assertThat(Arrays.stream(DateEditFlag.values())
                    .map(flag -> String.valueOf(flag.getImage()))
                    .allMatch(image -> image.length() == 1)).isTrue();
        }
    }

    @Nested
    @DisplayName("the cascade outcome record")
    class CascadeOutcomeRecord {

        @Test
        @DisplayName("the flag group renders as three characters in year, month, day order")
        void theFlagGroupRendersAsThreeCharacters() {
            DateEditResult result = new DateEditResult(true, DateEditFlag.BLANK,
                    DateEditFlag.NOT_OK, DateEditFlag.VALID, NO_MESSAGE);

            assertThat(result.flagsImage()).hasSize(3).isEqualTo("B0\u0000");
        }

        @Test
        @DisplayName("an all-valid group is three null characters, the low-values value the group tests")
        void anAllValidGroupIsThreeNullCharacters() {
            DateEditResult result = new DateEditResult(false, DateEditFlag.VALID,
                    DateEditFlag.VALID, DateEditFlag.VALID, NO_MESSAGE);

            assertThat(result.flagsImage()).isEqualTo("\u0000\u0000\u0000");
        }

        @Test
        @DisplayName("a group written by the head paragraph alone is the three characters 000")
        void aGroupWrittenByTheHeadParagraphAloneIsThreeZeros() {
            DateEditResult result = new DateEditResult(false, DateEditFlag.NOT_OK,
                    DateEditFlag.NOT_OK, DateEditFlag.NOT_OK, NO_MESSAGE);

            assertThat(result.flagsImage()).isEqualTo("000");
        }

        @Test
        @DisplayName("a null year flag is refused")
        void aNullYearFlagIsRefused() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new DateEditResult(false, null, DateEditFlag.VALID,
                            DateEditFlag.VALID, NO_MESSAGE))
                    .withMessageContaining("yearFlag");
        }

        @Test
        @DisplayName("a null month flag is refused")
        void aNullMonthFlagIsRefused() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new DateEditResult(false, DateEditFlag.VALID, null,
                            DateEditFlag.VALID, NO_MESSAGE))
                    .withMessageContaining("monthFlag");
        }

        @Test
        @DisplayName("a null day flag is refused")
        void aNullDayFlagIsRefused() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new DateEditResult(false, DateEditFlag.VALID,
                            DateEditFlag.VALID, null, NO_MESSAGE))
                    .withMessageContaining("dayFlag");
        }

        @Test
        @DisplayName("a null message is refused, because the blank state is the empty string")
        void aNullMessageIsRefused() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new DateEditResult(false, DateEditFlag.VALID,
                            DateEditFlag.VALID, DateEditFlag.VALID, null))
                    .withMessageContaining("returnMessage");
        }
    }

    @Nested
    @DisplayName("the ten feedback outcomes of the subprogram")
    class FeedbackOutcomes {

        @Test
        @DisplayName("ten outcomes are declared, nine conditions plus the unrecognised tail")
        void tenOutcomesAreDeclared() {
            assertThat(DateFeedback.values()).hasSize(10);
        }

        @Test
        @DisplayName("declaration order is the legacy evaluation order")
        void declarationOrderIsTheEvaluationOrder() {
            assertThat(DateFeedback.values()).containsExactly(
                    DateFeedback.DATE_IS_VALID,
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
        @DisplayName("only the success outcome carries severity zero")
        void onlyTheSuccessOutcomeCarriesSeverityZero() {
            assertThat(DateFeedback.DATE_IS_VALID.getSeverity()).isZero();
            assertThat(Arrays.stream(DateFeedback.values())
                    .filter(feedback -> feedback.getSeverity() == 0))
                    .containsExactly(DateFeedback.DATE_IS_VALID);
        }

        @Test
        @DisplayName("every failure outcome carries severity three")
        void everyFailureOutcomeCarriesSeverityThree() {
            assertThat(Arrays.stream(DateFeedback.values())
                    .filter(feedback -> feedback != DateFeedback.DATE_IS_VALID)
                    .allMatch(feedback -> feedback.getSeverity() == 3)).isTrue();
        }

        @Test
        @DisplayName("each message number is the value decoded from its feedback token")
        void eachMessageNumberIsTheDecodedValue() {
            assertThat(DateFeedback.DATE_IS_VALID.getMessageNumber()).isZero();
            assertThat(DateFeedback.INSUFFICIENT_DATA.getMessageNumber()).isEqualTo(2507);
            assertThat(DateFeedback.BAD_DATE_VALUE.getMessageNumber()).isEqualTo(2508);
            assertThat(DateFeedback.INVALID_ERA.getMessageNumber()).isEqualTo(2509);
            assertThat(DateFeedback.UNSUPPORTED_RANGE.getMessageNumber()).isEqualTo(2513);
            assertThat(DateFeedback.INVALID_MONTH.getMessageNumber()).isEqualTo(2517);
            assertThat(DateFeedback.BAD_PICTURE_STRING.getMessageNumber()).isEqualTo(2518);
            assertThat(DateFeedback.NON_NUMERIC_DATA.getMessageNumber()).isEqualTo(2520);
            assertThat(DateFeedback.YEAR_IN_ERA_ZERO.getMessageNumber()).isEqualTo(2521);
            assertThat(DateFeedback.UNRECOGNISED_FEEDBACK.getMessageNumber()).isZero();
        }

        @Test
        @DisplayName("the unsupported-range outcome is the one carrying the tolerated message number")
        void theUnsupportedRangeOutcomeCarriesTheToleratedMessageNumber() {
            assertThat(Arrays.stream(DateFeedback.values())
                    .filter(feedback -> feedback.getMessageNumber() == 2513))
                    .containsExactly(DateFeedback.UNSUPPORTED_RANGE);
        }

        @Test
        @DisplayName("the unrecognised tail is neither accepted severity nor tolerated message number")
        void theUnrecognisedTailIsRejectedByBothLevels() {
            assertThat(DateFeedback.UNRECOGNISED_FEEDBACK.getSeverity()).isNotZero();
            assertThat(DateFeedback.UNRECOGNISED_FEEDBACK.getMessageNumber()).isNotEqualTo(2513);
        }
    }

    @Nested
    @DisplayName("the eighty-byte result block")
    class ResultBlock {

        @Test
        @DisplayName("the rendered block is exactly eighty encoded bytes")
        void theRenderedBlockIsExactlyEightyBytes() {
            assertThat(encodedWidth(blockFor(DateFeedback.BAD_DATE_VALUE).render()))
                    .isEqualTo(RESULT_BLOCK_WIDTH);
        }

        @Test
        @DisplayName("the block opens with the severity view and then the message-code label")
        void theBlockOpensWithTheSeverityViewAndLabel() {
            assertThat(blockFor(DateFeedback.BAD_DATE_VALUE).render())
                    .startsWith("0003Mesg Code: 2508 ");
        }

        @Test
        @DisplayName("the block carries the tested date after its own label")
        void theBlockCarriesTheTestedDateAfterItsLabel() {
            assertThat(blockFor(DateFeedback.BAD_DATE_VALUE).render())
                    .contains("TstDate: 2023-02-29");
        }

        @Test
        @DisplayName("the block carries the mask after its own label")
        void theBlockCarriesTheMaskAfterItsLabel() {
            assertThat(blockFor(DateFeedback.BAD_DATE_VALUE).render())
                    .contains("Mask used:YYYY-MM-DD");
        }

        @Test
        @DisplayName("the block closes with the declared trailing filler")
        void theBlockClosesWithTheDeclaredTrailingFiller() {
            assertThat(blockFor(DateFeedback.BAD_DATE_VALUE).render()).endsWith("    ");
        }

        @Test
        @DisplayName("the message tail is exactly sixty-one bytes and opens with the filler space")
        void theMessageTailIsExactlySixtyOneBytes() {
            String segment = blockFor(DateFeedback.BAD_DATE_VALUE).messageSegment();

            assertThat(encodedWidth(segment)).isEqualTo(MESSAGE_SEGMENT_WIDTH);
            assertThat(segment).startsWith(" Datevalue error");
        }

        @Test
        @DisplayName("the message tail is the tail of the block at the declared byte offset")
        void theMessageTailIsTheTailOfTheBlock() {
            SubprogramResult block = blockFor(DateFeedback.BAD_DATE_VALUE);

            assertThat(block.render()).endsWith(block.messageSegment());
        }

        @Test
        @DisplayName("the numeric severity comes from the outcome, never parsed back out of the text")
        void theNumericSeverityComesFromTheOutcome() {
            assertThat(blockFor(DateFeedback.BAD_DATE_VALUE).numericSeverity()).isEqualTo(3);
            assertThat(new SubprogramResult(DateFeedback.DATE_IS_VALID, "0000", "0000",
                    "Date is valid  ", "2024-02-29", "YYYY-MM-DD").numericSeverity()).isZero();
        }

        @Test
        @DisplayName("a null outcome is refused")
        void aNullOutcomeIsRefused() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new SubprogramResult(null, "0000", "0000",
                            "Date is valid  ", "2024-02-29", "YYYY-MM-DD"))
                    .withMessageContaining("feedback");
        }

        @Test
        @DisplayName("a severity view of the wrong width is refused with its measured byte width")
        void aSeverityViewOfTheWrongWidthIsRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new SubprogramResult(DateFeedback.DATE_IS_VALID, "000",
                            "0000", "Date is valid  ", "2024-02-29", "YYYY-MM-DD"))
                    .withMessageContaining("severityCode")
                    .withMessageContaining(String.valueOf(CODE_WIDTH));
        }

        @Test
        @DisplayName("a message-number view of the wrong width is refused")
        void aMessageNumberViewOfTheWrongWidthIsRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new SubprogramResult(DateFeedback.DATE_IS_VALID, "0000",
                            "00000", "Date is valid  ", "2024-02-29", "YYYY-MM-DD"))
                    .withMessageContaining("messageNumber");
        }

        @Test
        @DisplayName("an outcome text of the wrong width is refused")
        void anOutcomeTextOfTheWrongWidthIsRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new SubprogramResult(DateFeedback.DATE_IS_VALID, "0000",
                            "0000", "Date is valid", "2024-02-29", "YYYY-MM-DD"))
                    .withMessageContaining("resultText")
                    .withMessageContaining(String.valueOf(RESULT_TEXT_WIDTH));
        }

        @Test
        @DisplayName("a tested date of the wrong width is refused")
        void aTestedDateOfTheWrongWidthIsRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new SubprogramResult(DateFeedback.DATE_IS_VALID, "0000",
                            "0000", "Date is valid  ", "2024-02-2", "YYYY-MM-DD"))
                    .withMessageContaining("testedDate")
                    .withMessageContaining(String.valueOf(LINKAGE_TEXT_WIDTH));
        }

        @Test
        @DisplayName("a mask of the wrong width is refused")
        void aMaskOfTheWrongWidthIsRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new SubprogramResult(DateFeedback.DATE_IS_VALID, "0000",
                            "0000", "Date is valid  ", "2024-02-29", "YYYYMMDD"))
                    .withMessageContaining("maskUsed");
        }

        @Test
        @DisplayName("a null component is refused before any width is measured")
        void aNullComponentIsRefused() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new SubprogramResult(DateFeedback.DATE_IS_VALID, null,
                            "0000", "Date is valid  ", "2024-02-29", "YYYY-MM-DD"))
                    .withMessageContaining("severityCode");
        }

        @Test
        @DisplayName("a component the single-byte charset cannot carry is refused, not transcoded")
        void anUnmappableComponentIsRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new SubprogramResult(DateFeedback.DATE_IS_VALID, "0000",
                            "0000", "Date is valid  ", "2024-02-2\u00e9", "YYYY-MM-DD"))
                    .withMessageContaining("testedDate");
        }
    }

    @Nested
    @DisplayName("the subprogram entry point, typed mask")
    class SubprogramWithTypedMask {

        @Test
        @DisplayName("a well-formed hyphenated date validates")
        void aWellFormedHyphenatedDateValidates() {
            SubprogramResult result = service.validateDate("2024-02-29", DateFormat.YYYY_MM_DD);

            assertThat(result.feedback()).isSameAs(DateFeedback.DATE_IS_VALID);
            assertThat(result.severityCode()).isEqualTo("0000");
            assertThat(result.messageNumber()).isEqualTo("0000");
            assertThat(result.resultText()).isEqualTo("Date is valid  ");
            assertThat(result.testedDate()).isEqualTo("2024-02-29");
            assertThat(result.maskUsed()).isEqualTo("YYYY-MM-DD");
        }

        @Test
        @DisplayName("a well-formed compact date validates and its mask keeps its declared padding")
        void aWellFormedCompactDateValidates() {
            SubprogramResult result = service.validateDate("20240229", DateFormat.YYYYMMDD);

            assertThat(result.feedback()).isSameAs(DateFeedback.DATE_IS_VALID);
            assertThat(result.maskUsed()).isEqualTo("YYYYMMDD  ");
            assertThat(encodedWidth(result.maskUsed())).isEqualTo(LINKAGE_TEXT_WIDTH);
        }

        @Test
        @DisplayName("an all-blank slot reports insufficient data")
        void anAllBlankSlotReportsInsufficientData() {
            SubprogramResult result = service.validateDate("", DateFormat.YYYY_MM_DD);

            assertThat(result.feedback()).isSameAs(DateFeedback.INSUFFICIENT_DATA);
            assertThat(result.messageNumber()).isEqualTo("2507");
            assertThat(result.resultText()).isEqualTo("Insufficient   ");
        }

        @Test
        @DisplayName("a null-filled slot also reports insufficient data")
        void aNullFilledSlotAlsoReportsInsufficientData() {
            SubprogramResult result =
                    service.validateDate("\u0000".repeat(10), DateFormat.YYYY_MM_DD);

            assertThat(result.feedback()).isSameAs(DateFeedback.INSUFFICIENT_DATA);
        }

        @Test
        @DisplayName("a non-digit where the picture requires a digit reports non-numeric data")
        void aNonDigitWherePictureRequiresADigitReportsNonNumericData() {
            SubprogramResult result = service.validateDate("20X4-01-01", DateFormat.YYYY_MM_DD);

            assertThat(result.feedback()).isSameAs(DateFeedback.NON_NUMERIC_DATA);
            assertThat(result.messageNumber()).isEqualTo("2520");
            assertThat(result.resultText()).isEqualTo("Nonnumeric data");
        }

        @Test
        @DisplayName("a hyphenated value offered to the compact mask reports non-numeric data")
        void aHyphenatedValueOfferedToTheCompactMaskReportsNonNumericData() {
            assertThat(service.validateDate("2024-02-2", DateFormat.YYYYMMDD).feedback())
                    .isSameAs(DateFeedback.NON_NUMERIC_DATA);
        }

        @Test
        @DisplayName("a short value padded into the slot reports non-numeric data at the pad bytes")
        void aShortValuePaddedIntoTheSlotReportsNonNumericData() {
            assertThat(service.validateDate("2024", DateFormat.YYYYMMDD).feedback())
                    .isSameAs(DateFeedback.NON_NUMERIC_DATA);
        }

        @Test
        @DisplayName("a zero year reports year-in-era-zero, tested before the parser is reached")
        void aZeroYearReportsYearInEraZero() {
            SubprogramResult result = service.validateDate("0000-01-01", DateFormat.YYYY_MM_DD);

            assertThat(result.feedback()).isSameAs(DateFeedback.YEAR_IN_ERA_ZERO);
            assertThat(result.messageNumber()).isEqualTo("2521");
            assertThat(result.resultText()).isEqualTo("YearInEra is 0 ");
        }

        @ParameterizedTest(name = "the month in [{0}] is outside one to twelve")
        @ValueSource(strings = {"2024-00-01", "2024-13-01", "2024-99-01"})
        @DisplayName("a month outside one to twelve reports its own condition, not a bad date value")
        void aMonthOutsideOneToTwelveReportsInvalidMonth(String candidate) {
            SubprogramResult result = service.validateDate(candidate, DateFormat.YYYY_MM_DD);

            assertThat(result.feedback()).isSameAs(DateFeedback.INVALID_MONTH);
            assertThat(result.messageNumber()).isEqualTo("2517");
            assertThat(result.resultText()).isEqualTo("Invalid month  ");
        }

        @ParameterizedTest(name = "the calendar date [{0}] does not exist")
        @ValueSource(strings = {"2023-02-29", "2024-02-30", "2024-04-31", "2024-01-32"})
        @DisplayName("a well-formed but non-existent calendar date reports a bad date value")
        void aNonExistentCalendarDateReportsABadDateValue(String candidate) {
            SubprogramResult result = service.validateDate(candidate, DateFormat.YYYY_MM_DD);

            assertThat(result.feedback()).isSameAs(DateFeedback.BAD_DATE_VALUE);
            assertThat(result.messageNumber()).isEqualTo("2508");
            assertThat(result.resultText()).isEqualTo("Datevalue error");
        }

        @Test
        @DisplayName("strict resolution refuses to normalise, so a thirtieth of February is rejected")
        void strictResolutionRefusesToNormalise() {
            assertThat(service.validateDate("2024-02-30", DateFormat.YYYY_MM_DD).feedback())
                    .isNotSameAs(DateFeedback.DATE_IS_VALID);
        }

        @Test
        @DisplayName("the day before the first Lilian day reports an unsupported range")
        void theDayBeforeTheFirstLilianDayReportsAnUnsupportedRange() {
            SubprogramResult result = service.validateDate("1582-10-14", DateFormat.YYYY_MM_DD);

            assertThat(result.feedback()).isSameAs(DateFeedback.UNSUPPORTED_RANGE);
            assertThat(result.messageNumber()).isEqualTo(TOLERATED_MESSAGE_NUMBER);
            assertThat(result.resultText()).isEqualTo("Unsupp. Range  ");
        }

        @Test
        @DisplayName("the first Lilian day itself validates")
        void theFirstLilianDayItselfValidates() {
            assertThat(service.validateDate("1582-10-15", DateFormat.YYYY_MM_DD).feedback())
                    .isSameAs(DateFeedback.DATE_IS_VALID);
        }

        @Test
        @DisplayName("a longer candidate is truncated on the right into the ten-byte parameter")
        void aLongerCandidateIsTruncatedIntoTheParameter() {
            SubprogramResult result =
                    service.validateDate("2024-02-29XYZ", DateFormat.YYYY_MM_DD);

            assertThat(result.testedDate()).isEqualTo("2024-02-29");
            assertThat(result.feedback()).isSameAs(DateFeedback.DATE_IS_VALID);
        }

        @Test
        @DisplayName("a shorter candidate is space padded on the right into the ten-byte parameter")
        void aShorterCandidateIsSpacePaddedIntoTheParameter() {
            SubprogramResult result = service.validateDate("20240229", DateFormat.YYYYMMDD);

            assertThat(result.testedDate()).isEqualTo("20240229  ");
            assertThat(encodedWidth(result.testedDate())).isEqualTo(LINKAGE_TEXT_WIDTH);
        }

        @Test
        @DisplayName("a null candidate is refused, because an absent field is not a blank field")
        void aNullCandidateIsRefused() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> service.validateDate(null, DateFormat.YYYY_MM_DD))
                    .withMessageContaining("candidateDate");
        }

        @Test
        @DisplayName("a null mask is refused")
        void aNullMaskIsRefused() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> service.validateDate("2024-02-29", (DateFormat) null))
                    .withMessageContaining("dateFormat");
        }

        @Test
        @DisplayName("a candidate the single-byte charset cannot carry is refused, not transcoded")
        void anUnmappableCandidateIsRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> service.validateDate(UNMAPPABLE, DateFormat.YYYYMMDD));
        }
    }

    @Nested
    @DisplayName("the subprogram entry point, raw mask")
    class SubprogramWithRawMask {

        @Test
        @DisplayName("the hyphenated mask resolves and the date validates")
        void theHyphenatedMaskResolves() {
            SubprogramResult result = service.validateDate("2024-02-29", "YYYY-MM-DD");

            assertThat(result.feedback()).isSameAs(DateFeedback.DATE_IS_VALID);
            assertThat(result.maskUsed()).isEqualTo("YYYY-MM-DD");
        }

        @Test
        @DisplayName("the compact mask resolves once padded to its declared ten bytes")
        void theCompactMaskResolvesOncePadded() {
            SubprogramResult result = service.validateDate("20240229", "YYYYMMDD");

            assertThat(result.feedback()).isSameAs(DateFeedback.DATE_IS_VALID);
            assertThat(result.maskUsed()).isEqualTo("YYYYMMDD  ");
        }

        @Test
        @DisplayName("the compact mask already padded to ten bytes also resolves")
        void theCompactMaskAlreadyPaddedAlsoResolves() {
            assertThat(service.validateDate("20240229", "YYYYMMDD  ").feedback())
                    .isSameAs(DateFeedback.DATE_IS_VALID);
        }

        @ParameterizedTest(name = "the mask [{0}] is not one the estate transmits")
        @ValueSource(strings = {"DD/MM/YYYY", "MM-DD-YYYY", "yyyy-MM-dd", "", "GARBAGE   "})
        @DisplayName("a mask the estate never transmits reports a bad picture string, never a guess")
        void anUnknownMaskReportsABadPictureString(String mask) {
            SubprogramResult result = service.validateDate("2024-02-29", mask);

            assertThat(result.feedback()).isSameAs(DateFeedback.BAD_PICTURE_STRING);
            assertThat(result.messageNumber()).isEqualTo("2518");
            assertThat(result.resultText()).isEqualTo("Bad Pic String ");
        }

        @Test
        @DisplayName("the unresolved mask is still reported back at its declared ten bytes")
        void theUnresolvedMaskIsReportedBackAtItsDeclaredWidth() {
            SubprogramResult result = service.validateDate("2024-02-29", "DD/MM/YY");

            assertThat(result.maskUsed()).isEqualTo("DD/MM/YY  ");
            assertThat(encodedWidth(result.maskUsed())).isEqualTo(LINKAGE_TEXT_WIDTH);
        }

        @Test
        @DisplayName("an unresolved mask is rejected by the two-level acceptance test")
        void anUnresolvedMaskIsRejectedByTheAcceptanceTest() {
            assertThat(service.isDateAcceptable(service.validateDate("2024-02-29", "DD/MM/YYYY")))
                    .isFalse();
        }

        @Test
        @DisplayName("a null candidate is refused")
        void aNullCandidateIsRefused() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> service.validateDate(null, "YYYY-MM-DD"))
                    .withMessageContaining("candidateDate");
        }

        @Test
        @DisplayName("a null mask is refused")
        void aNullMaskIsRefused() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> service.validateDate("2024-02-29", (String) null))
                    .withMessageContaining("formatMask");
        }

        @Test
        @DisplayName("a mask the single-byte charset cannot carry is refused, not transcoded")
        void anUnmappableMaskIsRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> service.validateDate("2024-02-29", "YYYY\u00e9MM-DD"));
        }
    }

    @Nested
    @DisplayName("the two-level acceptance test the four call sites apply")
    class AcceptanceTest {

        @Test
        @DisplayName("a zero severity code is accepted outright")
        void aZeroSeverityCodeIsAcceptedOutright() {
            SubprogramResult accepted = service.validateDate("2024-02-29", DateFormat.YYYY_MM_DD);

            assertThat(accepted.severityCode()).isEqualTo(ACCEPTED_SEVERITY_CODE);
            assertThat(service.isDateAcceptable(accepted)).isTrue();
        }

        @Test
        @DisplayName("a non-zero severity carrying the tolerated message number is still accepted")
        void aNonZeroSeverityCarryingTheToleratedNumberIsAccepted() {
            SubprogramResult tolerated = service.validateDate("1582-10-14", DateFormat.YYYY_MM_DD);

            assertThat(tolerated.severityCode()).isNotEqualTo(ACCEPTED_SEVERITY_CODE);
            assertThat(tolerated.messageNumber()).isEqualTo(TOLERATED_MESSAGE_NUMBER);
            assertThat(service.isDateAcceptable(tolerated)).isTrue();
        }

        @ParameterizedTest(name = "the candidate [{0}] is rejected")
        @ValueSource(strings = {"2023-02-29", "2024-13-01", "0000-01-01", "20X4-01-01", ""})
        @DisplayName("any other non-zero severity is rejected")
        void anyOtherNonZeroSeverityIsRejected(String candidate) {
            SubprogramResult rejected = service.validateDate(candidate, DateFormat.YYYY_MM_DD);

            assertThat(rejected.severityCode()).isNotEqualTo(ACCEPTED_SEVERITY_CODE);
            assertThat(rejected.messageNumber()).isNotEqualTo(TOLERATED_MESSAGE_NUMBER);
            assertThat(service.isDateAcceptable(rejected)).isFalse();
        }

        @Test
        @DisplayName("both comparisons are against four-character views, so neither field is parsed")
        void bothComparisonsAreAgainstFourCharacterViews() {
            SubprogramResult crafted = new SubprogramResult(DateFeedback.UNSUPPORTED_RANGE,
                    "0003", "2513", "Unsupp. Range  ", "1582-10-14", "YYYY-MM-DD");

            assertThat(crafted.severityCode()).hasSize(CODE_WIDTH);
            assertThat(crafted.messageNumber()).hasSize(CODE_WIDTH);
            assertThat(service.isDateAcceptable(crafted)).isTrue();
        }

        @Test
        @DisplayName("a null result is refused")
        void aNullResultIsRefused() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> service.isDateAcceptable(null))
                    .withMessageContaining("result");
        }
    }

    @Nested
    @DisplayName("the copybook cascade, year stage")
    class CascadeYearStage {

        @Test
        @DisplayName("an all-blank field reports the year as not supplied and claims the message")
        void anAllBlankFieldReportsTheYearAsNotSupplied() {
            DateEditResult result = service.validateCcyymmddDate("        ");

            assertThat(result.yearFlag()).isSameAs(DateEditFlag.BLANK);
            assertThat(result.inputError()).isTrue();
            assertThat(result.returnMessage()).isEqualTo(YEAR_NOT_SUPPLIED);
        }

        @Test
        @DisplayName("a null-filled field also reports the year as not supplied")
        void aNullFilledFieldAlsoReportsTheYearAsNotSupplied() {
            DateEditResult result = service.validateCcyymmddDate("\u0000".repeat(8));

            assertThat(result.yearFlag()).isSameAs(DateEditFlag.BLANK);
            assertThat(result.returnMessage()).isEqualTo(YEAR_NOT_SUPPLIED);
        }

        @Test
        @DisplayName("an empty candidate is padded into the field and reads as not supplied")
        void anEmptyCandidateIsPaddedAndReadsAsNotSupplied() {
            assertThat(service.validateCcyymmddDate("").yearFlag()).isSameAs(DateEditFlag.BLANK);
        }

        @Test
        @DisplayName("a non-numeric year is supplied-but-wrong, distinct from not supplied")
        void aNonNumericYearIsSuppliedButWrong() {
            DateEditResult result = service.validateCcyymmddDate("ABCD0101");

            assertThat(result.yearFlag()).isSameAs(DateEditFlag.NOT_OK);
            assertThat(result.inputError()).isTrue();
            assertThat(result.returnMessage()).isEqualTo(YEAR_NOT_FOUR_DIGITS);
        }

        @ParameterizedTest(name = "the century in [{0}] is outside the two admitted values")
        @ValueSource(strings = {"18000101", "21000101", "00010101", "99990101"})
        @DisplayName("a century other than nineteen or twenty is refused")
        void aCenturyOtherThanNineteenOrTwentyIsRefused(String candidate) {
            DateEditResult result = service.validateCcyymmddDate(candidate);

            assertThat(result.yearFlag()).isSameAs(DateEditFlag.NOT_OK);
            assertThat(result.returnMessage()).isEqualTo(CENTURY_NOT_VALID);
        }

        @Test
        @DisplayName("the nineteenth century is admitted")
        void theNineteenthCenturyIsAdmitted() {
            assertThat(service.validateCcyymmddDate("19990101").yearFlag())
                    .isSameAs(DateEditFlag.VALID);
        }

        @Test
        @DisplayName("the twentieth century is admitted")
        void theTwentiethCenturyIsAdmitted() {
            assertThat(service.validateCcyymmddDate("20240101").yearFlag())
                    .isSameAs(DateEditFlag.VALID);
        }
    }

    @Nested
    @DisplayName("the copybook cascade, month and day stages")
    class CascadeMonthAndDayStages {

        @Test
        @DisplayName("a blank month reports the month as not supplied")
        void aBlankMonthReportsTheMonthAsNotSupplied() {
            DateEditResult result = service.validateCcyymmddDate("2024  01");

            assertThat(result.monthFlag()).isSameAs(DateEditFlag.BLANK);
            assertThat(result.inputError()).isTrue();
            assertThat(result.returnMessage()).isEqualTo(MONTH_NOT_SUPPLIED);
        }

        @ParameterizedTest(name = "the month in [{0}] is outside one to twelve")
        @ValueSource(strings = {"20240001", "20241301", "2024XX01"})
        @DisplayName("a month outside one to twelve is supplied-but-wrong")
        void aMonthOutsideOneToTwelveIsSuppliedButWrong(String candidate) {
            DateEditResult result = service.validateCcyymmddDate(candidate);

            assertThat(result.monthFlag()).isSameAs(DateEditFlag.NOT_OK);
            assertThat(result.returnMessage()).isEqualTo(MONTH_OUT_OF_RANGE);
        }

        @Test
        @DisplayName("a blank day reports the day as not supplied")
        void aBlankDayReportsTheDayAsNotSupplied() {
            DateEditResult result = service.validateCcyymmddDate("202401  ");

            assertThat(result.dayFlag()).isSameAs(DateEditFlag.BLANK);
            assertThat(result.inputError()).isTrue();
            assertThat(result.returnMessage()).isEqualTo(DAY_NOT_SUPPLIED);
        }

        @Test
        @DisplayName("a non-numeric day is supplied-but-wrong")
        void aNonNumericDayIsSuppliedButWrong() {
            DateEditResult result = service.validateCcyymmddDate("202401XX");

            assertThat(result.dayFlag()).isSameAs(DateEditFlag.NOT_OK);
            assertThat(result.returnMessage()).isEqualTo(DAY_OUT_OF_RANGE);
        }

        @ParameterizedTest(name = "the day in [{0}] is outside one to thirty-one")
        @ValueSource(strings = {"20240100", "20240199"})
        @DisplayName("a day outside one to thirty-one is supplied-but-wrong")
        void aDayOutsideOneToThirtyOneIsSuppliedButWrong(String candidate) {
            DateEditResult result = service.validateCcyymmddDate(candidate);

            assertThat(result.dayFlag()).isSameAs(DateEditFlag.NOT_OK);
            assertThat(result.returnMessage()).isEqualTo(DAY_OUT_OF_RANGE);
        }

        @Test
        @DisplayName("a failing year stage does not abandon the range, so the month is still reported")
        void aFailingYearStageDoesNotAbandonTheRange() {
            DateEditResult result = service.validateCcyymmddDate("ABCD1301");

            assertThat(result.yearFlag()).isSameAs(DateEditFlag.NOT_OK);
            assertThat(result.monthFlag()).isSameAs(DateEditFlag.NOT_OK);
            assertThat(result.returnMessage()).isEqualTo(YEAR_NOT_FOUR_DIGITS);
        }

        @Test
        @DisplayName("all three stages report on the same pass when all three fields are blank")
        void allThreeStagesReportOnTheSamePass() {
            DateEditResult result = service.validateCcyymmddDate("        ");

            assertThat(result.flagsImage()).isEqualTo("BBB");
        }
    }

    @Nested
    @DisplayName("the copybook cascade, combined day-month-year stage")
    class CascadeCombinedStage {

        @ParameterizedTest(name = "[{0}] offers a thirty-first to a shorter month")
        @ValueSource(strings = {"20240431", "20240631", "20240931", "20241131", "20240231"})
        @DisplayName("a thirty-first offered to a shorter month fails both the day and the month")
        void aThirtyFirstOfferedToAShorterMonthFailsBoth(String candidate) {
            DateEditResult result = service.validateCcyymmddDate(candidate);

            assertThat(result.dayFlag()).isSameAs(DateEditFlag.NOT_OK);
            assertThat(result.monthFlag()).isSameAs(DateEditFlag.NOT_OK);
            assertThat(result.inputError()).isTrue();
            assertThat(result.returnMessage()).isEqualTo(CANNOT_HAVE_31_DAYS);
        }

        @ParameterizedTest(name = "[{0}] offers a thirty-first to a month that has one")
        @ValueSource(strings = {"20240131", "20240331", "20240531", "20240731", "20240831",
            "20241031", "20241231"})
        @DisplayName("a thirty-first offered to a month that has one is accepted")
        void aThirtyFirstOfferedToAMonthThatHasOneIsAccepted(String candidate) {
            DateEditResult result = service.validateCcyymmddDate(candidate);

            assertThat(result.inputError()).isFalse();
            assertThat(result.flagsImage()).isEqualTo("\u0000\u0000\u0000");
        }

        @Test
        @DisplayName("a thirtieth offered to February fails both the day and the month")
        void aThirtiethOfferedToFebruaryFailsBoth() {
            DateEditResult result = service.validateCcyymmddDate("20240230");

            assertThat(result.dayFlag()).isSameAs(DateEditFlag.NOT_OK);
            assertThat(result.monthFlag()).isSameAs(DateEditFlag.NOT_OK);
            assertThat(result.returnMessage()).isEqualTo(CANNOT_HAVE_30_DAYS);
        }

        @Test
        @DisplayName("a twenty-ninth of February in an ordinary leap year is accepted")
        void aTwentyNinthOfFebruaryInAnOrdinaryLeapYearIsAccepted() {
            DateEditResult result = service.validateCcyymmddDate("20240229");

            assertThat(result.inputError()).isFalse();
            assertThat(result.flagsImage()).isEqualTo("\u0000\u0000\u0000");
            assertThat(result.returnMessage()).isEmpty();
        }

        @Test
        @DisplayName("a twenty-ninth of February in a common year fails all three fields")
        void aTwentyNinthOfFebruaryInACommonYearFailsAllThree() {
            DateEditResult result = service.validateCcyymmddDate("20230229");

            assertThat(result.flagsImage()).isEqualTo("000");
            assertThat(result.inputError()).isTrue();
            assertThat(result.returnMessage()).isEqualTo(NOT_A_LEAP_YEAR);
        }

        @Test
        @DisplayName("a century year divisible by four hundred is a leap year")
        void aCenturyYearDivisibleByFourHundredIsALeapYear() {
            DateEditResult result = service.validateCcyymmddDate("20000229");

            assertThat(result.inputError()).isFalse();
            assertThat(result.flagsImage()).isEqualTo("\u0000\u0000\u0000");
        }

        @Test
        @DisplayName("a century year not divisible by four hundred is not a leap year")
        void aCenturyYearNotDivisibleByFourHundredIsNotALeapYear() {
            DateEditResult result = service.validateCcyymmddDate("19000229");

            assertThat(result.flagsImage()).isEqualTo("000");
            assertThat(result.returnMessage()).isEqualTo(NOT_A_LEAP_YEAR);
        }

        @Test
        @DisplayName("a failed earlier flag stops the range before the exit paragraph clears them")
        void aFailedEarlierFlagStopsTheRangeBeforeTheExitClearsThem() {
            DateEditResult result = service.validateCcyymmddDate("ABCD0115");

            assertThat(result.yearFlag()).isSameAs(DateEditFlag.NOT_OK);
            assertThat(result.monthFlag()).isSameAs(DateEditFlag.VALID);
            assertThat(result.dayFlag()).isSameAs(DateEditFlag.VALID);
            assertThat(result.returnMessage()).isEqualTo(YEAR_NOT_FOUR_DIGITS);
        }

        @Test
        @DisplayName("a non-numeric year still reaches the leap check, which then fails all three")
        void aNonNumericYearStillReachesTheLeapCheck() {
            DateEditResult result = service.validateCcyymmddDate("ABCD0229");

            assertThat(result.flagsImage()).isEqualTo("000");
            assertThat(result.inputError()).isTrue();
            assertThat(result.returnMessage()).isEqualTo(YEAR_NOT_FOUR_DIGITS);
        }
    }

    @Nested
    @DisplayName("the copybook cascade, message accumulation and truncation")
    class CascadeMessageAndWidth {

        @Test
        @DisplayName("a message already claimed by an earlier screen field survives untouched")
        void aMessageAlreadyClaimedSurvivesUntouched() {
            DateEditResult result = service.validateCcyymmddDate("        ", "earlier field failed");

            assertThat(result.returnMessage()).isEqualTo("earlier field failed");
            assertThat(result.yearFlag()).isSameAs(DateEditFlag.BLANK);
            assertThat(result.inputError()).isTrue();
        }

        @Test
        @DisplayName("an all-spaces incoming message still counts as blank and can be claimed")
        void anAllSpacesIncomingMessageCountsAsBlank() {
            DateEditResult result = service.validateCcyymmddDate("        ", "     ");

            assertThat(result.returnMessage()).isEqualTo(YEAR_NOT_SUPPLIED);
        }

        @Test
        @DisplayName("the earliest failing stage keeps the message even though later stages run")
        void theEarliestFailingStageKeepsTheMessage() {
            DateEditResult result = service.validateCcyymmddDate("    1301");

            assertThat(result.returnMessage()).isEqualTo(YEAR_NOT_SUPPLIED);
            assertThat(result.monthFlag()).isSameAs(DateEditFlag.NOT_OK);
        }

        @Test
        @DisplayName("a valid date leaves the accumulated message blank")
        void aValidDateLeavesTheMessageBlank() {
            assertThat(service.validateCcyymmddDate("20240229").returnMessage()).isEmpty();
        }

        @Test
        @DisplayName("a longer candidate is truncated on the right into the eight-byte field")
        void aLongerCandidateIsTruncatedIntoTheField() {
            DateEditResult result = service.validateCcyymmddDate("20240229EXTRA");

            assertThat(result.inputError()).isFalse();
            assertThat(result.flagsImage()).isEqualTo("\u0000\u0000\u0000");
        }

        @Test
        @DisplayName("a shorter candidate is space padded on the right, so the day reads as blank")
        void aShorterCandidateIsSpacePaddedOnTheRight() {
            DateEditResult result = service.validateCcyymmddDate("202401");

            assertThat(result.dayFlag()).isSameAs(DateEditFlag.BLANK);
            assertThat(result.returnMessage()).isEqualTo(DAY_NOT_SUPPLIED);
        }

        @Test
        @DisplayName("a null candidate is refused, because an absent field is not a blank field")
        void aNullCandidateIsRefused() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> service.validateCcyymmddDate(null))
                    .withMessageContaining("candidateDate");
        }

        @Test
        @DisplayName("a null incoming message is refused, because the blank state is an empty string")
        void aNullIncomingMessageIsRefused() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> service.validateCcyymmddDate("20240229", null))
                    .withMessageContaining("currentReturnMessage");
        }

        @Test
        @DisplayName("a candidate the single-byte charset cannot carry is refused, not transcoded")
        void anUnmappableCandidateIsRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> service.validateCcyymmddDate(UNMAPPABLE));
        }
    }

    @Nested
    @DisplayName("the date-of-birth reasonableness range")
    class DateOfBirthRange {

        /** A fixed current date, so the range is deterministic rather than wall-clock dependent. */
        private static final LocalDate TODAY = LocalDate.of(2024, 1, 1);

        @Test
        @DisplayName("a date strictly in the past leaves every flag valid and the message blank")
        void aDateStrictlyInThePastPasses() {
            DateEditResult result = service.validateDateOfBirth("19800415", TODAY);

            assertThat(result.inputError()).isFalse();
            assertThat(result.flagsImage()).isEqualTo("\u0000\u0000\u0000");
            assertThat(result.returnMessage()).isEmpty();
        }

        @Test
        @DisplayName("a date in the future fails all three flags and claims the message")
        void aDateInTheFutureFailsAllThreeFlags() {
            DateEditResult result = service.validateDateOfBirth("20300101", TODAY);

            assertThat(result.inputError()).isTrue();
            assertThat(result.flagsImage()).isEqualTo("000");
            assertThat(result.returnMessage()).isEqualTo(DATE_IN_FUTURE);
        }

        @Test
        @DisplayName("the current date itself is not strictly in the past, so it fails")
        void theCurrentDateItselfFails() {
            DateEditResult result = service.validateDateOfBirth("20240101", TODAY);

            assertThat(result.inputError()).isTrue();
            assertThat(result.returnMessage()).isEqualTo(DATE_IN_FUTURE);
        }

        @Test
        @DisplayName("the day before the current date passes")
        void theDayBeforeTheCurrentDatePasses() {
            assertThat(service.validateDateOfBirth("20231231", TODAY).inputError()).isFalse();
        }

        @Test
        @DisplayName("a message already claimed by an earlier screen field survives untouched")
        void aMessageAlreadyClaimedSurvivesUntouched() {
            DateEditResult result =
                    service.validateDateOfBirth("20300101", TODAY, "earlier field failed");

            assertThat(result.returnMessage()).isEqualTo("earlier field failed");
            assertThat(result.inputError()).isTrue();
        }

        @Test
        @DisplayName("an unresolvable candidate is refused, because the range's precondition failed")
        void anUnresolvableCandidateIsRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> service.validateDateOfBirth("2024ABCD", TODAY))
                    .withMessageContaining("not a resolvable CCYYMMDD");
        }

        @Test
        @DisplayName("a non-existent calendar date is refused rather than normalised")
        void aNonExistentCalendarDateIsRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> service.validateDateOfBirth("20230229", TODAY));
        }

        @Test
        @DisplayName("a null candidate is refused")
        void aNullCandidateIsRefused() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> service.validateDateOfBirth(null, TODAY))
                    .withMessageContaining("candidateDate");
        }

        @Test
        @DisplayName("a null current date is refused")
        void aNullCurrentDateIsRefused() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> service.validateDateOfBirth("19800415", null))
                    .withMessageContaining("currentDate");
        }

        @Test
        @DisplayName("a null incoming message is refused")
        void aNullIncomingMessageIsRefused() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> service.validateDateOfBirth("19800415", TODAY, null))
                    .withMessageContaining("currentReturnMessage");
        }
    }
}
