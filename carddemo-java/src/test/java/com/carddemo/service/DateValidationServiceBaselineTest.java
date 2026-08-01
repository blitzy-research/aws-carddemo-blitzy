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
import java.util.stream.Collectors;

import com.carddemo.domain.enums.DateFormat;
import com.carddemo.service.DateValidationService.DateEditFlag;
import com.carddemo.service.DateValidationService.DateEditResult;
import com.carddemo.service.DateValidationService.DateFeedback;
import com.carddemo.service.DateValidationService.SubprogramResult;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Unit tests for {@link DateValidationService}, the Java translation of the
 * CardDemo date-validation estate.
 *
 * <h2>What is under test</h2>
 *
 * <p>This one service absorbs two legacy artifacts that the migration treats as
 * a single unit because they are two halves of the same contract:</p>
 *
 * <ul>
 *   <li>the standalone date-validation subprogram {@code app/cbl/CSUTLDTC.cbl},
 *       a 157-line member invoked from four call sites and registered to CICS in
 *       its own right, whose linkage area carries a candidate date, a format
 *       mask, and a result block; and</li>
 *   <li>the procedural copybook {@code app/cpy/CSUTLDPY.cpy}, whose
 *       {@code EDIT-DATE-CCYYMMDD} range spans <em>eleven</em> paragraphs and is
 *       performed as a {@code THRU} range from the account-update program.</li>
 * </ul>
 *
 * <p>The eleven-paragraph span is the single most dangerous construct in the
 * estate: a reader who assumes the conventional paired
 * {@code NNNN-NAME}/{@code NNNN-NAME-EXIT} idiom would translate only the head
 * paragraph and silently skip every year, month, day, combination, and
 * Language-Environment stage. Date validation would then appear to work and
 * would validate nothing. These tests therefore assert the <em>cascade</em>
 * itself — that each stage runs, in source order, and that the specific field
 * flag and the specific message each stage produces are the ones the legacy
 * paragraphs produce.</p>
 *
 * <h2>Behaviours pinned here</h2>
 *
 * <ul>
 *   <li><strong>Stage ordering.</strong> Year, then month, then day, then the
 *       day/month/year combination, then the Lilian range edit — each stage
 *       setting only its own flag.</li>
 *   <li><strong>First-wins message accumulation.</strong> The legacy code moves
 *       a message into the shared return field only when that field is still
 *       blank, so the <em>earliest</em> failure owns the text even when later
 *       stages also fail. A caller-supplied message survives untouched.</li>
 *   <li><strong>Three-state field flags.</strong> A field is valid (low-values),
 *       not-OK, or blank. Not-OK and blank are <em>not</em> interchangeable:
 *       the screen-decoration macro writes an additional marker only for the
 *       blank state, which is why the REST error contract exposes MISSING and
 *       INVALID separately.</li>
 *   <li><strong>Fixed result-block widths.</strong> The subprogram's result
 *       block is exactly 80 characters assembled from 4/4/15/10/10-character
 *       fields, and its message segment is the trailing 61. These are
 *       contractual widths, so the record refuses a wrongly-sized component
 *       rather than silently padding it.</li>
 *   <li><strong>Gregorian leap rule with the century divisor.</strong> A year
 *       ending in {@code 00} is tested against 400 and every other year against
 *       4, so 2000 accepts 29 February and 1900 refuses it.</li>
 *   <li><strong>The tolerated message number.</strong> A severity of 0003 is
 *       normally a rejection, but the callers deliberately accept message
 *       number 2513 anyway. That tolerance is behaviour, not an oversight.</li>
 * </ul>
 *
 * <p>Provenance: legacy sources read at commit
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. No legacy source text
 * is reproduced here; only field widths, message texts that form the external
 * contract, and paragraph names are cited.</p>
 */
@DisplayName("DateValidationService - the CSUTLDTC subprogram and the eleven-paragraph CCYYMMDD cascade")
class DateValidationServiceBaselineTest {

    /** The result-block width the subprogram's linkage area fixes. */
    private static final int RESULT_BLOCK_WIDTH = 80;

    /** The trailing message-segment width the callers slice out of the block. */
    private static final int MESSAGE_SEGMENT_WIDTH = 61;

    /** Severity that means "accepted" in the subprogram's result block. */
    private static final String SEVERITY_ACCEPTED = "0000";

    /** Severity that every failure feedback reports. */
    private static final String SEVERITY_FAILED = "0003";

    /** The one non-zero message number the callers deliberately tolerate. */
    private static final String TOLERATED_MESSAGE_NUMBER = "2513";

    // -- Message texts that form the external screen contract. ------------------------------------
    private static final String MESSAGE_YEAR_NOT_SUPPLIED = " : Year must be supplied.";
    private static final String MESSAGE_YEAR_NOT_FOUR_DIGITS = " must be 4 digit number.";
    private static final String MESSAGE_CENTURY_NOT_VALID = " : Century is not valid.";
    private static final String MESSAGE_MONTH_NOT_SUPPLIED = " : Month must be supplied.";
    private static final String MESSAGE_MONTH_OUT_OF_RANGE =
            ": Month must be a number between 1 and 12.";
    private static final String MESSAGE_DAY_NOT_SUPPLIED = " : Day must be supplied.";
    private static final String MESSAGE_DAY_OUT_OF_RANGE =
            ":day must be a number between 1 and 31.";
    private static final String MESSAGE_CANNOT_HAVE_31_DAYS =
            ":Cannot have 31 days in this month.";
    private static final String MESSAGE_CANNOT_HAVE_30_DAYS =
            ":Cannot have 30 days in this month.";
    private static final String MESSAGE_NOT_A_LEAP_YEAR =
            ":Not a leap year.Cannot have 29 days in this month.";
    private static final String MESSAGE_DATE_IN_FUTURE = ":cannot be in the future ";

    /** A blank CCYYMMDD field: eight spaces, the legacy "nothing typed" state. */
    private static final String BLANK_CCYYMMDD = "        ";

    private final DateValidationService service = new DateValidationService();

    /**
     * Renders a three-flag image readably. The valid state is a low-value
     * character, which is invisible in assertion output, so it is shown as a dot
     * exactly the way the migration notes depict it.
     */
    private static String readable(final String flagsImage) {
        return flagsImage.replace(DateEditFlag.VALID.getImage(), '.');
    }

    /** Asserts the year, month, and day flags of a cascade result in one place. */
    private static void assertFlags(final DateEditResult result,
                                    final DateEditFlag expectedYear,
                                    final DateEditFlag expectedMonth,
                                    final DateEditFlag expectedDay) {
        assertThat(result.yearFlag()).as("year flag").isEqualTo(expectedYear);
        assertThat(result.monthFlag()).as("month flag").isEqualTo(expectedMonth);
        assertThat(result.dayFlag()).as("day flag").isEqualTo(expectedDay);
        assertThat(readable(result.flagsImage()))
                .as("flag image is the year, month, and day flags in that order")
                .isEqualTo(readable(new String(new char[] {
                        expectedYear.getImage(), expectedMonth.getImage(), expectedDay.getImage(),
                })));
    }

    /** Builds a well-formed result block for record-level tests. */
    private static SubprogramResult wellFormedResult(final DateFeedback feedback,
                                                     final String severityCode,
                                                     final String messageNumber) {
        return new SubprogramResult(feedback, severityCode, messageNumber,
                "Date is valid  ", "20240115  ", "YYYYMMDD  ");
    }

    @Nested
    @DisplayName("DateEditFlag - the three-state field flag vocabulary")
    class DateEditFlagVocabulary {

        @Test
        @DisplayName("declares exactly the three states the legacy flag group can hold")
        void declaresExactlyThreeStates() {
            assertThat(DateEditFlag.values())
                    .containsExactly(DateEditFlag.VALID, DateEditFlag.NOT_OK, DateEditFlag.BLANK);
        }

        @Test
        @DisplayName("the valid state is a low-value character, not a printable one")
        void validStateIsLowValues() {
            assertThat(DateEditFlag.VALID.getImage()).isEqualTo('\u0000');
            assertThat((int) DateEditFlag.VALID.getImage()).isZero();
        }

        @Test
        @DisplayName("not-OK and blank carry distinct printable images so the two failures stay apart")
        void notOkAndBlankAreDistinct() {
            assertThat(DateEditFlag.NOT_OK.getImage()).isEqualTo('0');
            assertThat(DateEditFlag.BLANK.getImage()).isEqualTo('B');
            assertThat(DateEditFlag.NOT_OK.getImage()).isNotEqualTo(DateEditFlag.BLANK.getImage());
        }

        @Test
        @DisplayName("every state carries a distinct image")
        void everyStateCarriesADistinctImage() {
            assertThat(DateEditFlag.values())
                    .extracting(DateEditFlag::getImage)
                    .doesNotHaveDuplicates();
        }
    }

    @Nested
    @DisplayName("DateEditResult - the cascade's frozen outcome")
    class DateEditResultRecord {

        @Test
        @DisplayName("exposes the three flags as a three-character image in year, month, day order")
        void exposesFlagsImageInFieldOrder() {
            final DateEditResult result = new DateEditResult(true, DateEditFlag.VALID,
                    DateEditFlag.NOT_OK, DateEditFlag.BLANK, "a message");

            assertThat(result.flagsImage()).hasSize(3);
            assertThat(result.flagsImage().charAt(0)).isEqualTo(DateEditFlag.VALID.getImage());
            assertThat(result.flagsImage().charAt(1)).isEqualTo(DateEditFlag.NOT_OK.getImage());
            assertThat(result.flagsImage().charAt(2)).isEqualTo(DateEditFlag.BLANK.getImage());
        }

        @Test
        @DisplayName("keeps the input-error indicator and the return message it was built with")
        void keepsComponents() {
            final DateEditResult result = new DateEditResult(false, DateEditFlag.VALID,
                    DateEditFlag.VALID, DateEditFlag.VALID, "");

            assertThat(result.inputError()).isFalse();
            assertThat(result.returnMessage()).isEmpty();
        }

        @Test
        @DisplayName("refuses a null year flag")
        void refusesNullYearFlag() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new DateEditResult(true, null,
                            DateEditFlag.VALID, DateEditFlag.VALID, ""))
                    .withMessageContaining("yearFlag");
        }

        @Test
        @DisplayName("refuses a null month flag")
        void refusesNullMonthFlag() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new DateEditResult(true, DateEditFlag.VALID,
                            null, DateEditFlag.VALID, ""))
                    .withMessageContaining("monthFlag");
        }

        @Test
        @DisplayName("refuses a null day flag")
        void refusesNullDayFlag() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new DateEditResult(true, DateEditFlag.VALID,
                            DateEditFlag.VALID, null, ""))
                    .withMessageContaining("dayFlag");
        }

        @Test
        @DisplayName("refuses a null return message, because the blank state is an empty string")
        void refusesNullReturnMessage() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new DateEditResult(true, DateEditFlag.VALID,
                            DateEditFlag.VALID, DateEditFlag.VALID, null))
                    .withMessageContaining("returnMessage");
        }
    }

    @Nested
    @DisplayName("DateFeedback - the subprogram's feedback-code table")
    class DateFeedbackVocabulary {

        @Test
        @DisplayName("declares the ten feedback outcomes in evaluation order")
        void declaresTenOutcomesInEvaluationOrder() {
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

        @ParameterizedTest(name = "{0} reports severity {1} and message number {2}")
        @CsvSource({
            "DATE_IS_VALID,         0, 0",
            "INSUFFICIENT_DATA,     3, 2507",
            "BAD_DATE_VALUE,        3, 2508",
            "INVALID_ERA,           3, 2509",
            "UNSUPPORTED_RANGE,     3, 2513",
            "INVALID_MONTH,         3, 2517",
            "BAD_PICTURE_STRING,    3, 2518",
            "NON_NUMERIC_DATA,      3, 2520",
            "YEAR_IN_ERA_ZERO,      3, 2521",
            "UNRECOGNISED_FEEDBACK, 3, 0",
        })
        @DisplayName("maps each feedback to its legacy severity and message number")
        void mapsFeedbackToSeverityAndMessageNumber(final DateFeedback feedback,
                                                     final int expectedSeverity,
                                                     final int expectedMessageNumber) {
            assertThat(feedback.getSeverity()).isEqualTo(expectedSeverity);
            assertThat(feedback.getMessageNumber()).isEqualTo(expectedMessageNumber);
        }

        @Test
        @DisplayName("only the valid outcome carries a zero severity")
        void onlyValidOutcomeIsZeroSeverity() {
            assertThat(DateFeedback.values())
                    .filteredOn(feedback -> feedback.getSeverity() == 0)
                    .containsExactly(DateFeedback.DATE_IS_VALID);
        }
    }

    @Nested
    @DisplayName("SubprogramResult - the fixed 80-character result block")
    class SubprogramResultRecord {

        @Test
        @DisplayName("renders exactly 80 characters in the legacy field order")
        void rendersExactlyEightyCharacters() {
            final SubprogramResult result =
                    wellFormedResult(DateFeedback.DATE_IS_VALID, SEVERITY_ACCEPTED, "0000");

            assertThat(result.render())
                    .hasSize(RESULT_BLOCK_WIDTH)
                    .isEqualTo("0000Mesg Code: 0000 Date is valid   "
                            + "TstDate: 20240115   Mask used:YYYYMMDD      ");
        }

        @Test
        @DisplayName("the message segment is the trailing 61 characters of the block")
        void messageSegmentIsTrailingSixtyOne() {
            final SubprogramResult result =
                    wellFormedResult(DateFeedback.DATE_IS_VALID, SEVERITY_ACCEPTED, "0000");

            assertThat(result.messageSegment())
                    .hasSize(MESSAGE_SEGMENT_WIDTH)
                    .isEqualTo(result.render()
                            .substring(RESULT_BLOCK_WIDTH - MESSAGE_SEGMENT_WIDTH));
        }

        @Test
        @DisplayName("reads the severity code numerically when it is all digits")
        void readsNumericSeverity() {
            assertThat(wellFormedResult(DateFeedback.DATE_IS_VALID, SEVERITY_ACCEPTED, "0000")
                    .numericSeverity()).isZero();
            assertThat(wellFormedResult(DateFeedback.BAD_DATE_VALUE, SEVERITY_FAILED, "2508")
                    .numericSeverity()).isEqualTo(3);
        }

        @Test
        @DisplayName("falls back to a failing severity when the code is not numeric")
        void fallsBackWhenSeverityIsNotNumeric() {
            assertThat(wellFormedResult(DateFeedback.BAD_DATE_VALUE, "00X0", "2508")
                    .numericSeverity())
                    .as("a corrupt severity code must never read as accepted")
                    .isEqualTo(3);
        }

        @ParameterizedTest(name = "a {1}-character {0} is refused")
        @CsvSource({
            "severityCode,  3",
            "messageNumber, 5",
            "resultText,    14",
            "testedDate,    9",
            "maskUsed,      11",
        })
        @DisplayName("refuses any component that would not fill its result-block positions")
        void refusesWronglySizedComponents(final String component, final int actualWidth) {
            final String severity = "severityCode".equals(component) ? "000" : "0000";
            final String messageNumber = "messageNumber".equals(component) ? "00000" : "0000";
            final String resultText =
                    "resultText".equals(component) ? "Date is valid " : "Date is valid  ";
            final String testedDate =
                    "testedDate".equals(component) ? "20240115 " : "20240115  ";
            final String maskUsed =
                    "maskUsed".equals(component) ? "YYYYMMDD   " : "YYYYMMDD  ";

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new SubprogramResult(DateFeedback.DATE_IS_VALID,
                            severity, messageNumber, resultText, testedDate, maskUsed))
                    .withMessageContaining(component)
                    .withMessageContaining(String.valueOf(actualWidth));
        }

        @Test
        @DisplayName("refuses a null feedback")
        void refusesNullFeedback() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new SubprogramResult(null, SEVERITY_ACCEPTED, "0000",
                            "Date is valid  ", "20240115  ", "YYYYMMDD  "))
                    .withMessageContaining("feedback");
        }
    }

    @Nested
    @DisplayName("validateDate - the CSUTLDTC subprogram driven by a typed format")
    class SubprogramDrivenByTypedFormat {

        @ParameterizedTest(name = "[{0}] yields {1} with severity {2} and message number {3}")
        @CsvSource({
            "20240115, DATE_IS_VALID,     0000, 0000, Date is valid",
            "19991231, DATE_IS_VALID,     0000, 0000, Date is valid",
            "20240229, DATE_IS_VALID,     0000, 0000, Date is valid",
            "20230229, BAD_DATE_VALUE,    0003, 2508, Datevalue error",
            "20240231, BAD_DATE_VALUE,    0003, 2508, Datevalue error",
            "20241131, BAD_DATE_VALUE,    0003, 2508, Datevalue error",
            "19000229, BAD_DATE_VALUE,    0003, 2508, Datevalue error",
            "20241315, INVALID_MONTH,     0003, 2517, Invalid month",
            "00000101, YEAR_IN_ERA_ZERO,  0003, 2521, YearInEra is 0",
            "2024011X, NON_NUMERIC_DATA,  0003, 2520, Nonnumeric data",
        })
        @DisplayName("classifies each candidate exactly as the subprogram's feedback table does")
        void classifiesCandidates(final String candidate,
                                   final DateFeedback expectedFeedback,
                                   final String expectedSeverity,
                                   final String expectedMessageNumber,
                                   final String expectedTextPrefix) {
            final SubprogramResult result = service.validateDate(candidate, DateFormat.YYYYMMDD);

            assertThat(result.feedback()).isEqualTo(expectedFeedback);
            assertThat(result.severityCode()).isEqualTo(expectedSeverity);
            assertThat(result.messageNumber()).isEqualTo(expectedMessageNumber);
            assertThat(result.resultText()).startsWith(expectedTextPrefix);
            assertThat(result.render()).hasSize(RESULT_BLOCK_WIDTH);
        }

        @Test
        @DisplayName("pads the tested date and the mask into their ten-character linkage fields")
        void padsLinkageTextFields() {
            final SubprogramResult result = service.validateDate("20240115", DateFormat.YYYYMMDD);

            assertThat(result.testedDate()).isEqualTo("20240115  ").hasSize(10);
            assertThat(result.maskUsed()).isEqualTo("YYYYMMDD  ").hasSize(10);
        }

        @Test
        @DisplayName("reports insufficient data only when the whole field is blank")
        void reportsInsufficientDataForABlankField() {
            final SubprogramResult result = service.validateDate("          ", DateFormat.YYYYMMDD);

            assertThat(result.feedback()).isEqualTo(DateFeedback.INSUFFICIENT_DATA);
            assertThat(result.messageNumber()).isEqualTo("2507");
        }

        @Test
        @DisplayName("a short candidate is padded and so reports non-numeric data, not insufficient data")
        void aShortCandidateIsPaddedAndReadsAsNonNumeric() {
            final SubprogramResult result = service.validateDate("2024", DateFormat.YYYYMMDD);

            assertThat(result.feedback())
                    .as("padding to the linkage width leaves digits followed by spaces, "
                            + "which fails the digit test rather than the blank test")
                    .isEqualTo(DateFeedback.NON_NUMERIC_DATA);
            assertThat(result.messageNumber()).isEqualTo("2520");
        }

        @Test
        @DisplayName("refuses a date before the Lilian range starts and tolerates it at the boundary")
        void honoursTheLilianRangeBoundary() {
            final SubprogramResult before = service.validateDate("15821014", DateFormat.YYYYMMDD);
            final SubprogramResult onBoundary =
                    service.validateDate("15821015", DateFormat.YYYYMMDD);

            assertThat(before.feedback()).isEqualTo(DateFeedback.UNSUPPORTED_RANGE);
            assertThat(before.messageNumber()).isEqualTo(TOLERATED_MESSAGE_NUMBER);
            assertThat(onBoundary.feedback()).isEqualTo(DateFeedback.DATE_IS_VALID);
        }

        @Test
        @DisplayName("accepts the hyphenated format against its own mask")
        void acceptsTheHyphenatedFormat() {
            final SubprogramResult result =
                    service.validateDate("2024-01-15", DateFormat.YYYY_MM_DD);

            assertThat(result.feedback()).isEqualTo(DateFeedback.DATE_IS_VALID);
            assertThat(result.maskUsed()).isEqualTo("YYYY-MM-DD");
        }

        @Test
        @DisplayName("applies the strict resolver, so an impossible day in the hyphenated format fails")
        void appliesTheStrictResolverToTheHyphenatedFormat() {
            final SubprogramResult result =
                    service.validateDate("2024-02-31", DateFormat.YYYY_MM_DD);

            assertThat(result.feedback()).isEqualTo(DateFeedback.BAD_DATE_VALUE);
            assertThat(result.messageNumber()).isEqualTo("2508");
        }
    }

    @Nested
    @DisplayName("validateDate - the subprogram driven by a raw mask string")
    class SubprogramDrivenByRawMask {

        @Test
        @DisplayName("resolves a recognised mask and validates against it")
        void resolvesARecognisedMask() {
            final SubprogramResult result = service.validateDate("20240115", "YYYYMMDD");

            assertThat(result.feedback()).isEqualTo(DateFeedback.DATE_IS_VALID);
            assertThat(result.maskUsed()).isEqualTo("YYYYMMDD  ");
        }

        @Test
        @DisplayName("reports a bad picture string for an unrecognised mask rather than guessing")
        void reportsBadPictureStringForAnUnrecognisedMask() {
            final SubprogramResult result = service.validateDate("20240115", "DD/MM/YYYY");

            assertThat(result.feedback()).isEqualTo(DateFeedback.BAD_PICTURE_STRING);
            assertThat(result.severityCode()).isEqualTo(SEVERITY_FAILED);
            assertThat(result.messageNumber()).isEqualTo("2518");
            assertThat(result.maskUsed()).isEqualTo("DD/MM/YYYY");
        }

        @Test
        @DisplayName("applies the resolved mask literally, so compact digits fail the hyphenated mask")
        void appliesTheResolvedMaskLiterally() {
            final SubprogramResult result = service.validateDate("20240115", "YYYY-MM-DD");

            assertThat(result.feedback()).isEqualTo(DateFeedback.NON_NUMERIC_DATA);
        }
    }

    @Nested
    @DisplayName("isDateAcceptable - the callers' acceptance rule")
    class AcceptanceRule {

        @Test
        @DisplayName("accepts a zero severity")
        void acceptsAZeroSeverity() {
            assertThat(service.isDateAcceptable(
                    wellFormedResult(DateFeedback.DATE_IS_VALID, SEVERITY_ACCEPTED, "0000")))
                    .isTrue();
        }

        @Test
        @DisplayName("accepts a failing severity when the message number is the tolerated one")
        void toleratesTheOneKnownMessageNumber() {
            assertThat(service.isDateAcceptable(wellFormedResult(
                    DateFeedback.UNSUPPORTED_RANGE, SEVERITY_FAILED, TOLERATED_MESSAGE_NUMBER)))
                    .as("the callers deliberately continue past this condition")
                    .isTrue();
        }

        @ParameterizedTest(name = "rejects a failing severity carrying message number {0}")
        @ValueSource(strings = {"2507", "2508", "2509", "2517", "2518", "2520", "2521"})
        @DisplayName("rejects every other failing severity")
        void rejectsEveryOtherFailure(final String messageNumber) {
            assertThat(service.isDateAcceptable(
                    wellFormedResult(DateFeedback.BAD_DATE_VALUE, SEVERITY_FAILED, messageNumber)))
                    .isFalse();
        }

        @Test
        @DisplayName("agrees with the subprogram on a real unsupported-range outcome")
        void agreesWithTheSubprogramOnARealOutcome() {
            final SubprogramResult result = service.validateDate("15821014", DateFormat.YYYYMMDD);

            assertThat(result.numericSeverity()).isEqualTo(3);
            assertThat(service.isDateAcceptable(result)).isTrue();
        }
    }

    @Nested
    @DisplayName("EDIT-YEAR-CCYY - the cascade's first stage")
    class YearStage {

        @Test
        @DisplayName("flags a blank year as blank, not merely not-OK, and claims the message")
        void flagsABlankYearAsBlank() {
            final DateEditResult result = service.validateCcyymmddDate(BLANK_CCYYMMDD);

            assertThat(result.inputError()).isTrue();
            assertFlags(result, DateEditFlag.BLANK, DateEditFlag.BLANK, DateEditFlag.BLANK);
            assertThat(result.returnMessage()).isEqualTo(MESSAGE_YEAR_NOT_SUPPLIED);
        }

        @Test
        @DisplayName("flags a non-numeric year as not-OK with the four-digit message")
        void flagsANonNumericYear() {
            final DateEditResult result = service.validateCcyymmddDate("2");

            assertThat(result.inputError()).isTrue();
            assertFlags(result, DateEditFlag.NOT_OK, DateEditFlag.BLANK, DateEditFlag.BLANK);
            assertThat(result.returnMessage()).isEqualTo(MESSAGE_YEAR_NOT_FOUR_DIGITS);
        }

        @ParameterizedTest(name = "[{0}] has an unsupported century")
        @ValueSource(strings = {"18990115", "00000115", "21240115"})
        @DisplayName("accepts only the twentieth and twenty-first centuries")
        void acceptsOnlyTwoCenturies(final String candidate) {
            final DateEditResult result = service.validateCcyymmddDate(candidate);

            assertThat(result.inputError()).isTrue();
            assertFlags(result, DateEditFlag.NOT_OK, DateEditFlag.VALID, DateEditFlag.VALID);
            assertThat(result.returnMessage()).isEqualTo(MESSAGE_CENTURY_NOT_VALID);
        }

        @ParameterizedTest(name = "[{0}] carries an accepted century")
        @ValueSource(strings = {"19991231", "20240115"})
        @DisplayName("accepts a year in either supported century")
        void acceptsASupportedCentury(final String candidate) {
            final DateEditResult result = service.validateCcyymmddDate(candidate);

            assertThat(result.yearFlag()).isEqualTo(DateEditFlag.VALID);
            assertThat(result.inputError()).isFalse();
        }
    }

    @Nested
    @DisplayName("EDIT-MONTH - the cascade's second stage")
    class MonthStage {

        @Test
        @DisplayName("flags a blank month as blank while leaving a good year valid")
        void flagsABlankMonthAsBlank() {
            final DateEditResult result = service.validateCcyymmddDate("2024  15");

            assertThat(result.inputError()).isTrue();
            assertFlags(result, DateEditFlag.VALID, DateEditFlag.BLANK, DateEditFlag.VALID);
            assertThat(result.returnMessage()).isEqualTo(MESSAGE_MONTH_NOT_SUPPLIED);
        }

        @ParameterizedTest(name = "[{0}] falls outside the one-to-twelve range")
        @ValueSource(strings = {"20241315", "20240 15"})
        @DisplayName("refuses a month outside one to twelve")
        void refusesAnOutOfRangeMonth(final String candidate) {
            final DateEditResult result = service.validateCcyymmddDate(candidate);

            assertThat(result.inputError()).isTrue();
            assertFlags(result, DateEditFlag.VALID, DateEditFlag.NOT_OK, DateEditFlag.VALID);
            assertThat(result.returnMessage()).isEqualTo(MESSAGE_MONTH_OUT_OF_RANGE);
        }

        @Test
        @DisplayName("treats a zero month as out of range")
        void treatsAZeroMonthAsOutOfRange() {
            final DateEditResult result = service.validateCcyymmddDate("202400 5");

            assertThat(result.inputError()).isTrue();
            assertThat(result.monthFlag()).isEqualTo(DateEditFlag.NOT_OK);
            assertThat(result.returnMessage()).isEqualTo(MESSAGE_MONTH_OUT_OF_RANGE);
        }
    }

    @Nested
    @DisplayName("EDIT-DAY - the cascade's third stage")
    class DayStage {

        @Test
        @DisplayName("flags a blank day as blank while leaving the year and month valid")
        void flagsABlankDayAsBlank() {
            final DateEditResult result = service.validateCcyymmddDate("202401  ");

            assertThat(result.inputError()).isTrue();
            assertFlags(result, DateEditFlag.VALID, DateEditFlag.VALID, DateEditFlag.BLANK);
            assertThat(result.returnMessage()).isEqualTo(MESSAGE_DAY_NOT_SUPPLIED);
        }

        @ParameterizedTest(name = "[{0}] is refused by the day stage")
        @ValueSource(strings = {"20240100", "20240132", "2024011X"})
        @DisplayName("refuses a day outside one to thirty-one or containing a non-digit")
        void refusesAnUnusableDay(final String candidate) {
            final DateEditResult result = service.validateCcyymmddDate(candidate);

            assertThat(result.inputError()).isTrue();
            assertFlags(result, DateEditFlag.VALID, DateEditFlag.VALID, DateEditFlag.NOT_OK);
            assertThat(result.returnMessage()).isEqualTo(MESSAGE_DAY_OUT_OF_RANGE);
        }
    }

    @Nested
    @DisplayName("EDIT-DAY-MONTH-YEAR - the combination stage and its leap-year rule")
    class DayMonthYearCombinationStage {

        @ParameterizedTest(name = "[{0}] cannot have a thirty-first day")
        @ValueSource(strings = {"20240231", "20240431", "20240631", "20240931", "20241131"})
        @DisplayName("refuses a thirty-first day in a month that has none, flagging month and day")
        void refusesAThirtyFirstDayInAShortMonth(final String candidate) {
            final DateEditResult result = service.validateCcyymmddDate(candidate);

            assertThat(result.inputError()).isTrue();
            assertFlags(result, DateEditFlag.VALID, DateEditFlag.NOT_OK, DateEditFlag.NOT_OK);
            assertThat(result.returnMessage()).isEqualTo(MESSAGE_CANNOT_HAVE_31_DAYS);
        }

        @Test
        @DisplayName("refuses a thirtieth day in February")
        void refusesAThirtiethDayInFebruary() {
            final DateEditResult result = service.validateCcyymmddDate("20240230");

            assertThat(result.inputError()).isTrue();
            assertFlags(result, DateEditFlag.VALID, DateEditFlag.NOT_OK, DateEditFlag.NOT_OK);
            assertThat(result.returnMessage()).isEqualTo(MESSAGE_CANNOT_HAVE_30_DAYS);
        }

        @ParameterizedTest(name = "[{0}] is not a leap year, so it has no twenty-ninth of February")
        @ValueSource(strings = {"20230229", "20250229", "19000229"})
        @DisplayName("refuses the twenty-ninth of February outside a leap year, flagging all three fields")
        void refusesTheTwentyNinthOutsideALeapYear(final String candidate) {
            final DateEditResult result = service.validateCcyymmddDate(candidate);

            assertThat(result.inputError()).isTrue();
            assertFlags(result, DateEditFlag.NOT_OK, DateEditFlag.NOT_OK, DateEditFlag.NOT_OK);
            assertThat(result.returnMessage()).isEqualTo(MESSAGE_NOT_A_LEAP_YEAR);
        }

        @ParameterizedTest(name = "[{0}] is a leap year, so the twenty-ninth stands")
        @ValueSource(strings = {"20240229", "20000229", "19960229"})
        @DisplayName("accepts the twenty-ninth of February in a leap year, using 400 for a century year")
        void acceptsTheTwentyNinthInALeapYear(final String candidate) {
            final DateEditResult result = service.validateCcyymmddDate(candidate);

            assertThat(result.inputError())
                    .as("a century year is tested against 400 and every other year against 4")
                    .isFalse();
            assertFlags(result, DateEditFlag.VALID, DateEditFlag.VALID, DateEditFlag.VALID);
            assertThat(result.returnMessage()).isEmpty();
        }
    }

    @Nested
    @DisplayName("EDIT-DATE-LE - the range's final stage, reached only when all three flags are valid")
    class LilianEditStage {

        @ParameterizedTest(name = "[{0}] survives the whole cascade")
        @ValueSource(strings = {"20240115", "19991231", "20240229", "20000229", "19820101"})
        @DisplayName("leaves every field flag valid and the message blank for a wholly good date")
        void leavesAGoodDateEntirelyValid(final String candidate) {
            final DateEditResult result = service.validateCcyymmddDate(candidate);

            assertThat(result.inputError()).isFalse();
            assertFlags(result, DateEditFlag.VALID, DateEditFlag.VALID, DateEditFlag.VALID);
            assertThat(result.returnMessage()).isEmpty();
        }

        @Test
        @DisplayName("is skipped when an earlier stage already failed, so that failure keeps its flags")
        void isSkippedWhenAnEarlierStageFailed() {
            final DateEditResult result = service.validateCcyymmddDate("18990115");

            assertThat(result.inputError()).isTrue();
            assertThat(result.yearFlag())
                    .as("the final stage would have forced every flag valid had it run")
                    .isEqualTo(DateEditFlag.NOT_OK);
            assertThat(result.returnMessage()).isEqualTo(MESSAGE_CENTURY_NOT_VALID);
        }
    }

    @Nested
    @DisplayName("Return-message accumulation - the earliest failure owns the text")
    class FirstWinsMessageAccumulation {

        @Test
        @DisplayName("keeps a caller-supplied message instead of overwriting it with its own")
        void keepsACallerSuppliedMessage() {
            final DateEditResult result =
                    service.validateCcyymmddDate(BLANK_CCYYMMDD, "PRIOR MESSAGE");

            assertThat(result.inputError()).isTrue();
            assertFlags(result, DateEditFlag.BLANK, DateEditFlag.BLANK, DateEditFlag.BLANK);
            assertThat(result.returnMessage())
                    .as("the shared message field is written only while it is still blank")
                    .isEqualTo("PRIOR MESSAGE");
        }

        @Test
        @DisplayName("supplies its own message when the caller passes the blank state")
        void suppliesItsOwnMessageWhenTheFieldIsBlank() {
            final DateEditResult result = service.validateCcyymmddDate(BLANK_CCYYMMDD, "");

            assertThat(result.returnMessage()).isEqualTo(MESSAGE_YEAR_NOT_SUPPLIED);
        }

        @Test
        @DisplayName("leaves a caller-supplied message untouched even when nothing fails")
        void leavesACallerMessageUntouchedOnSuccess() {
            final DateEditResult result =
                    service.validateCcyymmddDate("20240115", "PRIOR MESSAGE");

            assertThat(result.inputError()).isFalse();
            assertThat(result.returnMessage()).isEqualTo("PRIOR MESSAGE");
        }

        @Test
        @DisplayName("reports the month failure, not the later day failure, when both fail")
        void reportsTheEarliestOfTwoFailures() {
            final DateEditResult result = service.validateCcyymmddDate("202400 5");

            assertFlags(result, DateEditFlag.VALID, DateEditFlag.NOT_OK, DateEditFlag.NOT_OK);
            assertThat(result.returnMessage())
                    .as("the month stage runs before the day stage and so claims the field")
                    .isEqualTo(MESSAGE_MONTH_OUT_OF_RANGE);
        }
    }

    @Nested
    @DisplayName("Field normalisation - the candidate is moved into a fixed eight-character field")
    class FieldNormalisation {

        @Test
        @DisplayName("pads a short candidate with spaces, which the day stage then refuses")
        void padsAShortCandidate() {
            final DateEditResult result = service.validateCcyymmddDate("2024011");

            assertThat(result.inputError()).isTrue();
            assertFlags(result, DateEditFlag.VALID, DateEditFlag.VALID, DateEditFlag.NOT_OK);
            assertThat(result.returnMessage()).isEqualTo(MESSAGE_DAY_OUT_OF_RANGE);
        }

        @Test
        @DisplayName("truncates an over-long candidate to the field width and validates the prefix")
        void truncatesAnOverLongCandidate() {
            final DateEditResult result = service.validateCcyymmddDate("202401156789");

            assertThat(result.inputError())
                    .as("a fixed-width move discards the overflow rather than failing")
                    .isFalse();
            assertFlags(result, DateEditFlag.VALID, DateEditFlag.VALID, DateEditFlag.VALID);
        }

        @Test
        @DisplayName("treats an empty candidate exactly as a blank field")
        void treatsAnEmptyCandidateAsBlank() {
            final DateEditResult result = service.validateCcyymmddDate("");

            assertFlags(result, DateEditFlag.BLANK, DateEditFlag.BLANK, DateEditFlag.BLANK);
            assertThat(result.returnMessage()).isEqualTo(MESSAGE_YEAR_NOT_SUPPLIED);
        }
    }

    @Nested
    @DisplayName("EDIT-DATE-OF-BIRTH - the range that refuses a future date")
    class DateOfBirthRange {

        private static final LocalDate TODAY = LocalDate.of(2024, 6, 15);

        @ParameterizedTest(name = "[{0}] is in the past and is accepted")
        @ValueSource(strings = {"19800101", "20240614", "19991231"})
        @DisplayName("accepts a date of birth strictly before the current date")
        void acceptsAPastDate(final String candidate) {
            final DateEditResult result = service.validateDateOfBirth(candidate, TODAY);

            assertThat(result.inputError()).isFalse();
            assertFlags(result, DateEditFlag.VALID, DateEditFlag.VALID, DateEditFlag.VALID);
            assertThat(result.returnMessage()).isEmpty();
        }

        @Test
        @DisplayName("refuses the current date itself, because the comparison is strict")
        void refusesTheCurrentDateItself() {
            final DateEditResult result = service.validateDateOfBirth("20240615", TODAY);

            assertThat(result.inputError())
                    .as("the legacy test admits only a date strictly earlier than today")
                    .isTrue();
            assertFlags(result, DateEditFlag.NOT_OK, DateEditFlag.NOT_OK, DateEditFlag.NOT_OK);
            assertThat(result.returnMessage()).isEqualTo(MESSAGE_DATE_IN_FUTURE);
        }

        @ParameterizedTest(name = "[{0}] is in the future and is refused")
        @ValueSource(strings = {"20240616", "20991231"})
        @DisplayName("refuses a future date of birth and flags all three fields")
        void refusesAFutureDate(final String candidate) {
            final DateEditResult result = service.validateDateOfBirth(candidate, TODAY);

            assertThat(result.inputError()).isTrue();
            assertFlags(result, DateEditFlag.NOT_OK, DateEditFlag.NOT_OK, DateEditFlag.NOT_OK);
            assertThat(result.returnMessage()).isEqualTo(MESSAGE_DATE_IN_FUTURE);
        }

        @Test
        @DisplayName("keeps a caller-supplied message rather than replacing it")
        void keepsACallerSuppliedMessage() {
            final DateEditResult result =
                    service.validateDateOfBirth("20991231", TODAY, "PRIOR MESSAGE");

            assertThat(result.inputError()).isTrue();
            assertThat(result.returnMessage()).isEqualTo("PRIOR MESSAGE");
        }

        @Test
        @DisplayName("refuses a candidate that is not a resolvable calendar date, stating the precondition")
        void refusesAnUnresolvableCandidate() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> service.validateDateOfBirth(BLANK_CCYYMMDD, TODAY))
                    .withMessageContaining("not a resolvable CCYYMMDD calendar date");
        }

        @Test
        @DisplayName("accepts a leap day of birth")
        void acceptsALeapDayOfBirth() {
            final DateEditResult result = service.validateDateOfBirth("20240229", TODAY);

            assertThat(result.inputError()).isFalse();
        }
    }

    @Nested
    @DisplayName("Precondition guards - an absent field is not a blank field")
    class PreconditionGuards {

        @Test
        @DisplayName("validateCcyymmddDate refuses a null candidate")
        void ccyymmddRefusesANullCandidate() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> service.validateCcyymmddDate(null))
                    .withMessageContaining("candidateDate");
        }

        @Test
        @DisplayName("validateCcyymmddDate refuses a null current message")
        void ccyymmddRefusesANullCurrentMessage() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> service.validateCcyymmddDate("20240115", null))
                    .withMessageContaining("currentReturnMessage");
        }

        @Test
        @DisplayName("validateDate refuses a null candidate")
        void validateDateRefusesANullCandidate() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> service.validateDate(null, DateFormat.YYYYMMDD))
                    .withMessageContaining("candidateDate");
        }

        @Test
        @DisplayName("validateDate refuses a null typed format")
        void validateDateRefusesANullTypedFormat() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> service.validateDate("20240115", (DateFormat) null))
                    .withMessageContaining("dateFormat");
        }

        @Test
        @DisplayName("validateDate refuses a null mask string")
        void validateDateRefusesANullMask() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> service.validateDate("20240115", (String) null))
                    .withMessageContaining("formatMask");
        }

        @Test
        @DisplayName("validateDateOfBirth refuses a null candidate")
        void dateOfBirthRefusesANullCandidate() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> service.validateDateOfBirth(null, LocalDate.of(2024, 6, 15)))
                    .withMessageContaining("candidateDate");
        }

        @Test
        @DisplayName("validateDateOfBirth refuses a null current date")
        void dateOfBirthRefusesANullCurrentDate() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> service.validateDateOfBirth("19800101", null))
                    .withMessageContaining("currentDate");
        }

        @Test
        @DisplayName("isDateAcceptable refuses a null result")
        void acceptanceRefusesANullResult() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> service.isDateAcceptable(null))
                    .withMessageContaining("result");
        }
    }

    /**
     * Asserts that nothing a caller submitted reaches a log record.
     *
     * <p>A review finding recorded that three statements here wrote the candidate date, and one of them
     * additionally wrote the parser's own failure message - which quotes the offending text back verbatim.
     * Two consequences follow, and the second is the serious one. The candidate is external, fixed-width
     * text that the surrounding fields admit any single-byte character into, so a submission carrying a
     * line separator would append a line of the caller's choosing to the log and have it read as a record
     * this service emitted. The local profile raises this package to its most detailed level, so the
     * statements were reachable in the profile a developer actually runs.</p>
     *
     * <p>These assertions read the recorded events rather than the source, so they hold against any later
     * edit that reintroduces a value by a different route - including the parser message, which reads as a
     * library detail rather than as external input and is the easiest of the three to put back by
     * accident.</p>
     */
    @Nested
    @DisplayName("Log records: nothing a caller submitted is ever written")
    class LogRecordsWithholdSubmittedText {

        /** A candidate carrying separators, which a naive record would let inject whole lines. */
        private static final String FORGING_CANDIDATE = "2024\n\rX1";

        /** A candidate that is well formed but names a day that does not exist. */
        private static final String NON_EXISTENT_DAY = "20240230";

        /** A format mask that resolves to none this module names. */
        private static final String UNRESOLVABLE_MASK = "ZZ\nINJECT";

        /**
         * An accumulated message a caller might arrive with, carrying a separator.
         *
         * <p>Non-blank, so the first-wins field is already claimed and no edit of the cascade will
         * replace it: whatever this holds is what the call returns, and therefore what a record would
         * carry were the text written rather than described.
         */
        private static final String FORGED_CALLER_MESSAGE = "prior edit failed\nINJECTED RECORD";

        private Logger logger;
        private ListAppender<ILoggingEvent> recorder;
        private Level originalLevel;

        @BeforeEach
        void attachRecorderAtTheMostDetailedLevel() {
            logger = (Logger) LoggerFactory.getLogger(DateValidationService.class);
            originalLevel = logger.getLevel();
            recorder = new ListAppender<>();
            recorder.setContext(logger.getLoggerContext());
            recorder.start();
            logger.addAppender(recorder);
            // The local profile raises this package to its most detailed level, so the statements under
            // test are recorded there. Asserting at a quieter level would pass while proving nothing.
            logger.setLevel(Level.TRACE);
        }

        @AfterEach
        void detachRecorderAndRestoreLevel() {
            logger.detachAppender(recorder);
            recorder.stop();
            logger.setLevel(originalLevel);
        }

        /**
         * Renders every recorded event as the text a log file would carry.
         *
         * @return the formatted messages, arguments substituted
         */
        private String recordedText() {
            return recorder.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .collect(Collectors.joining("\u0001"));
        }

        @Test
        @DisplayName("withhold a candidate carrying line separators, so a submission cannot append a "
                + "record of its own")
        void withholdACandidateCarryingSeparators() {
            service.validateDate(FORGING_CANDIDATE, DateFormat.YYYYMMDD);

            assertThat(recorder.list)
                    .as("the statement under test must actually have fired, or this proves nothing")
                    .isNotEmpty();
            assertThat(recordedText())
                    .doesNotContain("2024\n")
                    .doesNotContain("\r")
                    .doesNotContain("X1");
        }

        @ParameterizedTest(name = "candidate {0}")
        @ValueSource(strings = {"20240230", "19000101", "ABCDEFGH", "        ", "00000000"})
        @DisplayName("withhold the candidate whatever it is and whichever branch reports it")
        void withholdTheCandidateOnEveryBranch(final String candidate) {
            service.validateDate(candidate, DateFormat.YYYYMMDD);

            assertThat(recordedText())
                    .as("no recorded text may reproduce what was submitted")
                    .doesNotContain(candidate.strip().isEmpty() ? "\u0000" : candidate.strip());
        }

        @Test
        @DisplayName("withhold the parser's own message, which would otherwise quote the candidate back "
                + "by a route that reads as a library detail")
        void withholdTheParserMessage() {
            service.validateDate(NON_EXISTENT_DAY, DateFormat.YYYYMMDD);

            assertThat(recordedText())
                    .doesNotContain(NON_EXISTENT_DAY)
                    .doesNotContainIgnoringCase("could not be parsed")
                    .doesNotContainIgnoringCase("Text '")
                    .doesNotContainIgnoringCase("Invalid date");
        }

        @Test
        @DisplayName("withhold an unresolvable format mask, reporting its width instead, because on that "
                + "branch the mask is arbitrary caller text rather than one this module names")
        void withholdAnUnresolvableMask() {
            service.validateDate("20240101", UNRESOLVABLE_MASK);

            assertThat(recordedText())
                    .doesNotContain("INJECT")
                    .doesNotContain("\n");
            assertThat(recorder.list)
                    .anySatisfy(event -> assertThat(event.getLevel()).isEqualTo(Level.WARN));
        }

        @Test
        @DisplayName("report a resolved format mask by value, because a resolved mask is this module's "
                + "own literal and is what makes the record useful")
        void reportAResolvedMaskByValue() {
            service.validateDate("20240101", DateFormat.YYYYMMDD);

            assertThat(recordedText())
                    .as("the diagnostic must still say which format was applied")
                    .contains(DateFormat.YYYYMMDD.getValue().strip());
        }

        @Test
        @DisplayName("carry no control byte at all, so no recorded line can be split or overwritten")
        void carryNoControlByte() {
            service.validateDate(FORGING_CANDIDATE, DateFormat.YYYYMMDD);
            service.validateDate(NON_EXISTENT_DAY, DateFormat.YYYY_MM_DD);
            service.validateDate("20240101", UNRESOLVABLE_MASK);

            for (final ILoggingEvent event : recorder.list) {
                assertThat(event.getFormattedMessage().chars()
                        .filter(character -> character < 0x20 || character == 0x7F)
                        .count())
                        .as("record [%s] carries a control byte", event.getFormattedMessage())
                        .isZero();
            }
        }

        @Test
        @DisplayName("withhold the tested date on the tolerated-message branch, which is the record most "
                + "worth reading and therefore the one most worth controlling")
        void withholdTheTestedDateOnTheToleratedBranch() {
            final SubprogramResult result =
                    service.validateDate(FORGING_CANDIDATE, DateFormat.YYYYMMDD);
            recorder.list.clear();

            service.isDateAcceptable(result);

            assertThat(recordedText())
                    .doesNotContain("X1")
                    .doesNotContain("\n");
        }

        @Test
        @DisplayName("withhold an accumulated message the caller brought in, describing it instead, "
                + "because the module never authored that text and cannot vouch for its bytes")
        void withholdAnAccumulatedMessageTheCallerBroughtIn() {
            service.validateCcyymmddDate(NON_EXISTENT_DAY, FORGED_CALLER_MESSAGE);

            assertThat(recorder.list)
                    .as("the cascade's closing statement must have fired, or this proves nothing")
                    .isNotEmpty();
            assertThat(recordedText())
                    .as("the field is first-wins, so a non-blank message returns unchanged and would "
                            + "reach the record verbatim if it were written")
                    .doesNotContain("INJECTED")
                    .doesNotContain("\n")
                    .contains("unchanged caller message of");
        }

        @Test
        @DisplayName("withhold a caller's accumulated message on the date-of-birth entry point too, "
                + "which takes the same field and reports it the same way")
        void withholdACallersMessageOnTheDateOfBirthEntryPoint() {
            service.validateDateOfBirth("19800101", LocalDate.of(2024, 1, 1), FORGED_CALLER_MESSAGE);

            assertThat(recordedText())
                    .doesNotContain("INJECTED")
                    .doesNotContain("\n")
                    .contains("unchanged caller message of");
        }

        @Test
        @DisplayName("record in full the message the module itself authored, because suppressing that "
                + "would remove the one diagnostic these statements exist for")
        void recordInFullTheMessageTheModuleAuthored() {
            service.validateCcyymmddDate(NON_EXISTENT_DAY);

            assertThat(recordedText())
                    .as("a blank field on entry means any text on return is this module's own literal, "
                            + "and that literal is the diagnostic these statements exist to carry")
                    .doesNotContain("unchanged caller message of")
                    .contains(MESSAGE_CANNOT_HAVE_30_DAYS);
        }
    }
}
