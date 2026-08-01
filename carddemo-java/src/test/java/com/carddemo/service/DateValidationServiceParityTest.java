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
import java.time.format.DateTimeParseException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.carddemo.domain.enums.DateFormat;
import com.carddemo.service.DateValidationService.DateEditFlag;
import com.carddemo.service.DateValidationService.DateEditResult;
import com.carddemo.service.DateValidationService.DateFeedback;
import com.carddemo.service.DateValidationService.SubprogramResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNoException;

/**
 * Verifies {@link DateValidationService} against the legacy date-validation contract it replaces.
 *
 * <p><strong>What the legacy authority is.</strong> Two source artefacts define the behaviour, and
 * every assertion in this class is anchored to one of them rather than to the Java implementation:
 *
 * <ul>
 *   <li>{@code app/cpy/CSUTLDPY.cpy} — the procedural copybook holding the eleven-paragraph
 *       {@code EDIT-DATE-CCYYMMDD THRU EDIT-DATE-CCYYMMDD-EXIT} cascade plus the separate
 *       {@code EDIT-DATE-OF-BIRTH} range. The paragraph labels sit at lines 18, 25, 88, 91, 145,
 *       150, 205, 209, 280, 284, 323, 329, 341 and 370, which fixes the stage ordering that this
 *       class asserts. The flag vocabulary comes from {@code app/cpy/CSUTLDWY.cpy} lines 43-57,
 *       where {@code WS-EDIT-DATE-FLGS} is a three-byte group whose level-88 names are
 *       {@code WS-EDIT-DATE-IS-VALID VALUE LOW-VALUES} and
 *       {@code WS-EDIT-DATE-IS-INVALID VALUE '000'} over three {@code PIC X(01)} subordinates.</li>
 *   <li>{@code app/cbl/CSUTLDTC.cbl} — the callable subprogram whose {@code LINKAGE SECTION}
 *       declares {@code LS-DATE PIC X(10)}, {@code LS-DATE-FORMAT PIC X(10)} and
 *       {@code LS-RESULT PIC X(80)}, and whose {@code WS-MESSAGE} group at lines 42-57 fixes the
 *       eighty-character result block this class reproduces position by position.</li>
 * </ul>
 *
 * <p><strong>Why the cascade ordering can be observed at all.</strong> The copybook writes its
 * diagnostic through {@code IF WS-RETURN-MSG-OFF ... STRING ... INTO WS-RETURN-MSG}, so the first
 * stage to fail is the only stage whose wording survives. That first-failure-wins rule turns the
 * returned message into a witness for which stage ran first, which is what lets this class prove
 * stage ordering from the outside without reaching into private methods.
 *
 * <p><strong>Deliberately unasserted, because unreachable through the published surface.</strong>
 * Three code paths are legacy safety nets that no input can reach, and this class documents rather
 * than fabricates them. The {@code EDIT-DATE-LE} failure branch is unreachable because the
 * copybook's own leap divisor — 400 when the year-of-century digits are {@code 00}, otherwise 4 —
 * is exactly correct for the only two centuries the year stage admits, so every calendar-impossible
 * date is already rejected before the Language Environment stage is entered; the copybook says as
 * much in its own comment, "In case some one managed to enter a bad date that passed all the edits
 * above". The {@code INVALID_ERA} and default arms of the result-text mapping are unreachable
 * because strict {@code java.time} resolution never produces those Language Environment feedback
 * codes. The insufficient-data-by-length arm is unreachable because the linkage field is always
 * padded to its full ten characters before the length is compared. None of the three is chased with
 * reflection: the technical specification sets the reflection budget for this module at zero, and a
 * reflective test would assert the shape of a private method rather than the behaviour of a
 * contract.
 */
@DisplayName("DateValidationService — the eleven-stage CCYYMMDD cascade and the callable subprogram")
class DateValidationServiceParityTest {

    // =================================================================================================
    // LEGACY LITERALS
    //
    // Every string below is the exact diagnostic wording carried by app/cpy/CSUTLDPY.cpy, reproduced
    // here as an expectation rather than imported from the production class. Importing the production
    // constant would make the assertion agree with itself; typing the legacy wording out means a
    // reworded diagnostic fails this suite, which is the point.
    // =================================================================================================

    /** From the {@code EDIT-YEAR-CCYY} blank branch. */
    private static final String YEAR_NOT_SUPPLIED = " : Year must be supplied.";

    /** From the {@code EDIT-YEAR-CCYY} non-numeric branch. */
    private static final String YEAR_NOT_FOUR_DIGITS = " must be 4 digit number.";

    /** From the {@code EDIT-YEAR-CCYY} century branch. */
    private static final String CENTURY_NOT_VALID = " : Century is not valid.";

    /** From the {@code EDIT-MONTH} blank branch. */
    private static final String MONTH_NOT_SUPPLIED = " : Month must be supplied.";

    /** From both out-of-range branches of {@code EDIT-MONTH}. */
    private static final String MONTH_OUT_OF_RANGE = ": Month must be a number between 1 and 12.";

    /** From the {@code EDIT-DAY} blank branch. */
    private static final String DAY_NOT_SUPPLIED = " : Day must be supplied.";

    /** From both out-of-range branches of {@code EDIT-DAY}. */
    private static final String DAY_OUT_OF_RANGE = ":day must be a number between 1 and 31.";

    /** From the first branch of {@code EDIT-DAY-MONTH-YEAR}. */
    private static final String CANNOT_HAVE_31_DAYS = ":Cannot have 31 days in this month.";

    /** From the second branch of {@code EDIT-DAY-MONTH-YEAR}. */
    private static final String CANNOT_HAVE_30_DAYS = ":Cannot have 30 days in this month.";

    /** From the leap-year branch of {@code EDIT-DAY-MONTH-YEAR}. */
    private static final String NOT_A_LEAP_YEAR =
            ":Not a leap year.Cannot have 29 days in this month.";

    /** From {@code EDIT-DATE-OF-BIRTH}. */
    private static final String CANNOT_BE_IN_THE_FUTURE = ":cannot be in the future ";

    /** The blank state of {@code WS-RETURN-MSG}, which the service models as an empty string. */
    private static final String NO_MESSAGE = "";

    // =================================================================================================
    // LEGACY WIDTHS AND IMAGES
    // =================================================================================================

    /** {@code WS-EDIT-DATE-CCYYMMDD PIC X(08)}. */
    private static final int CCYYMMDD_WIDTH = 8;

    /** {@code LS-DATE} and {@code LS-DATE-FORMAT}, both {@code PIC X(10)}. */
    private static final int LINKAGE_TEXT_WIDTH = 10;

    /** {@code LS-RESULT PIC X(80)}. */
    private static final int RESULT_BLOCK_WIDTH = 80;

    /** The tail of {@code LS-RESULT} that {@code COACTUPC} copies onto the screen. */
    private static final int MESSAGE_SEGMENT_WIDTH = 61;

    /** {@code WS-SEVERITY PIC X(04)} and {@code WS-MSG-NO PIC X(04)}. */
    private static final int CODE_WIDTH = 4;

    /** {@code WS-RESULT PIC X(15)}. */
    private static final int RESULT_TEXT_WIDTH = 15;

    /** The three-byte image of a fully valid {@code WS-EDIT-DATE-FLGS}: {@code LOW-VALUES}. */
    private static final String ALL_FLAGS_VALID = "\u0000\u0000\u0000";

    /** The three-byte image the head paragraph arms: level-88 {@code VALUE '000'}. */
    private static final String ALL_FLAGS_NOT_OK = "000";

    /** A four-character run of {@code LOW-VALUE}, which the copybook tests for alongside spaces. */
    private static final String FOUR_LOW_VALUES = "\u0000\u0000\u0000\u0000";

    /** The service under test. It injects no collaborator and holds no state. */
    private final DateValidationService service = new DateValidationService();

    // =================================================================================================
    // HELPERS
    // =================================================================================================

    /**
     * Renders a {@link DateEditFlag} triple as the three-byte {@code WS-EDIT-DATE-FLGS} image.
     *
     * @param result the cascade outcome
     * @return the three-character group image in year, month, day order
     */
    private static String flags(final DateEditResult result) {
        return result.flagsImage();
    }

    /**
     * Runs the CCYYMMDD cascade with a blank starting return message.
     *
     * @param candidate the candidate date, moved to the eight-character field by the service
     * @return the cascade outcome
     */
    private DateEditResult cascade(final String candidate) {
        return service.validateCcyymmddDate(candidate);
    }

    /**
     * Runs the callable subprogram against the compact mask.
     *
     * @param candidate the candidate date
     * @return the subprogram outcome
     */
    private SubprogramResult compact(final String candidate) {
        return service.validateDate(candidate, DateFormat.YYYYMMDD);
    }

    // =================================================================================================
    // STAGE ORDERING
    // =================================================================================================

    /**
     * Proves the cascade visits its stages in copybook order.
     *
     * <p>The technique is the first-failure-wins rule: when several stages would each fail, only the
     * earliest one's wording reaches {@code WS-RETURN-MSG}. Reading the surviving message therefore
     * names the stage that ran first. This is the assertion that would catch the single most damaging
     * mistranslation available here — performing only the head paragraph of
     * {@code PERFORM EDIT-DATE-CCYYMMDD THRU EDIT-DATE-CCYYMMDD-EXIT} and silently skipping the ten
     * paragraphs the range falls through.
     */
    @Nested
    @DisplayName("Stage ordering across the eleven-paragraph range")
    class StageOrdering {

        @Test
        @DisplayName("with year, month and day all blank the year stage wins the message")
        void theYearStageRunsFirst() {
            final DateEditResult result = cascade("        ");

            assertThat(result.returnMessage()).isEqualTo(YEAR_NOT_SUPPLIED);
            assertThat(result.inputError()).isTrue();
            assertThat(flags(result)).isEqualTo("BBB");
        }

        @Test
        @DisplayName("with a good year and a blank month and day the month stage wins the message")
        void theMonthStageRunsSecond() {
            final DateEditResult result = cascade("2024    ");

            assertThat(result.returnMessage()).isEqualTo(MONTH_NOT_SUPPLIED);
            assertThat(result.yearFlag()).isEqualTo(DateEditFlag.VALID);
            assertThat(result.monthFlag()).isEqualTo(DateEditFlag.BLANK);
            assertThat(result.dayFlag()).isEqualTo(DateEditFlag.BLANK);
        }

        @Test
        @DisplayName("with a good year and month and a blank day the day stage wins the message")
        void theDayStageRunsThird() {
            final DateEditResult result = cascade("202402  ");

            assertThat(result.returnMessage()).isEqualTo(DAY_NOT_SUPPLIED);
            assertThat(flags(result)).isEqualTo("\u0000\u0000B");
        }

        @Test
        @DisplayName("the combined stage runs after the three field stages have each passed")
        void theCombinedStageRunsFourth() {
            final DateEditResult result = cascade("20240431");

            assertThat(result.returnMessage()).isEqualTo(CANNOT_HAVE_31_DAYS);
            assertThat(result.yearFlag()).isEqualTo(DateEditFlag.VALID);
            assertThat(result.monthFlag()).isEqualTo(DateEditFlag.NOT_OK);
            assertThat(result.dayFlag()).isEqualTo(DateEditFlag.NOT_OK);
        }

        @Test
        @DisplayName("a field-stage failure suppresses the combined stage's own wording")
        void anEarlierStageOutranksTheCombinedStage() {
            // April has 30 days and the day field says 31, so the combined stage would object; but the
            // century is 21, so the year stage objected first and its wording is the one that survives.
            final DateEditResult result = cascade("21240431");

            assertThat(result.returnMessage()).isEqualTo(CENTURY_NOT_VALID);
            assertThat(result.inputError()).isTrue();
        }

        @Test
        @DisplayName("a caller-supplied message suppresses every stage's wording")
        void aPriorMessageIsNeverOverwritten() {
            final DateEditResult result = service.validateCcyymmddDate("        ", "PRIOR FAILURE");

            assertThat(result.returnMessage()).isEqualTo("PRIOR FAILURE");
            assertThat(result.inputError()).isTrue();
            assertThat(flags(result)).isEqualTo("BBB");
        }

        @Test
        @DisplayName("an all-space caller message still counts as the blank state")
        void anAllSpaceMessageIsTreatedAsBlank() {
            final DateEditResult result = service.validateCcyymmddDate("        ", "   ");

            assertThat(result.returnMessage()).isEqualTo(YEAR_NOT_SUPPLIED);
        }

        @Test
        @DisplayName("the one-argument entry point starts from the blank message state")
        void theSingleArgumentFormStartsBlank() {
            assertThat(cascade("20240229").returnMessage()).isEqualTo(NO_MESSAGE);
            assertThat(cascade("        ").returnMessage()).isEqualTo(YEAR_NOT_SUPPLIED);
        }
    }

    // =================================================================================================
    // THE YEAR STAGE
    // =================================================================================================

    /**
     * Verifies {@code EDIT-YEAR-CCYY} at lines 25-88 of the copybook.
     *
     * <p>The stage arms {@code FLG-YEAR-NOT-OK} before testing anything, so a stage that returns
     * early through an unrecognised path still reports not-OK rather than valid. Its three tests run
     * in the order blank, all-digits, century — and the century vocabulary is deliberately only 19
     * and 20, a restriction the copybook explains in its own comment about not having learnt from
     * Y2K.
     */
    @Nested
    @DisplayName("EDIT-YEAR-CCYY")
    class YearStage {

        @Test
        @DisplayName("a space-filled year is blank rather than merely invalid")
        void spacesAreBlank() {
            final DateEditResult result = cascade("    0101");

            assertThat(result.yearFlag()).isEqualTo(DateEditFlag.BLANK);
            assertThat(result.returnMessage()).isEqualTo(YEAR_NOT_SUPPLIED);
            assertThat(result.inputError()).isTrue();
        }

        @Test
        @DisplayName("a low-values year is blank too, matching the copybook's twin test")
        void lowValuesAreBlank() {
            final DateEditResult result = cascade(FOUR_LOW_VALUES + "0101");

            assertThat(result.yearFlag()).isEqualTo(DateEditFlag.BLANK);
            assertThat(result.returnMessage()).isEqualTo(YEAR_NOT_SUPPLIED);
        }

        @Test
        @DisplayName("a non-numeric year is reported as not four digits")
        void nonNumericIsNotFourDigits() {
            final DateEditResult result = cascade("20X40101");

            assertThat(result.yearFlag()).isEqualTo(DateEditFlag.NOT_OK);
            assertThat(result.returnMessage()).isEqualTo(YEAR_NOT_FOUR_DIGITS);
        }

        @Test
        @DisplayName("the digit test precedes the century test")
        void theDigitTestOutranksTheCenturyTest() {
            // The century slice "1X" is neither 19 nor 20, so both tests would object; the digit test
            // runs first, so its wording is the one that survives.
            final DateEditResult result = cascade("1X990101");

            assertThat(result.returnMessage()).isEqualTo(YEAR_NOT_FOUR_DIGITS);
        }

        @Test
        @DisplayName("century 19 is accepted")
        void nineteenIsAccepted() {
            assertThat(cascade("19991231").yearFlag()).isEqualTo(DateEditFlag.VALID);
        }

        @Test
        @DisplayName("century 20 is accepted")
        void twentyIsAccepted() {
            assertThat(cascade("20240101").yearFlag()).isEqualTo(DateEditFlag.VALID);
        }

        @Test
        @DisplayName("century 18 is rejected — the vocabulary is only 19 and 20")
        void eighteenIsRejected() {
            final DateEditResult result = cascade("18991231");

            assertThat(result.yearFlag()).isEqualTo(DateEditFlag.NOT_OK);
            assertThat(result.returnMessage()).isEqualTo(CENTURY_NOT_VALID);
        }

        @Test
        @DisplayName("century 21 is rejected even though it is a perfectly real century")
        void twentyOneIsRejected() {
            final DateEditResult result = cascade("21000101");

            assertThat(result.yearFlag()).isEqualTo(DateEditFlag.NOT_OK);
            assertThat(result.returnMessage()).isEqualTo(CENTURY_NOT_VALID);
        }

        @Test
        @DisplayName("century 00 is rejected")
        void centuryZeroIsRejected() {
            assertThat(cascade("00010101").returnMessage()).isEqualTo(CENTURY_NOT_VALID);
        }
    }

    // =================================================================================================
    // THE MONTH STAGE
    // =================================================================================================

    /**
     * Verifies {@code EDIT-MONTH} at lines 91-145 of the copybook.
     *
     * <p>The copybook tests blank, then the {@code WS-VALID-MONTH} range, then
     * {@code FUNCTION TEST-NUMVAL}. The range test is evaluated on a field that is not yet known to
     * be numeric, so a non-numeric month fails the range test and never reaches the numeric test —
     * in the copybook because the level-88 range check on a {@code PIC X(2)} field cannot match, and
     * in the translation because the numeric view of a non-digit slice is negative. Both languages
     * therefore emit the range wording for a non-numeric month, and this suite asserts that rather
     * than the tidier behaviour a rewrite would produce.
     */
    @Nested
    @DisplayName("EDIT-MONTH")
    class MonthStage {

        @Test
        @DisplayName("a space-filled month is blank")
        void spacesAreBlank() {
            final DateEditResult result = cascade("2024  01");

            assertThat(result.monthFlag()).isEqualTo(DateEditFlag.BLANK);
            assertThat(result.returnMessage()).isEqualTo(MONTH_NOT_SUPPLIED);
        }

        @Test
        @DisplayName("a low-values month is blank")
        void lowValuesAreBlank() {
            final DateEditResult result = cascade("2024\u0000\u000001");

            assertThat(result.monthFlag()).isEqualTo(DateEditFlag.BLANK);
            assertThat(result.returnMessage()).isEqualTo(MONTH_NOT_SUPPLIED);
        }

        @Test
        @DisplayName("month 00 is out of range")
        void zeroIsOutOfRange() {
            final DateEditResult result = cascade("20240001");

            assertThat(result.monthFlag()).isEqualTo(DateEditFlag.NOT_OK);
            assertThat(result.returnMessage()).isEqualTo(MONTH_OUT_OF_RANGE);
        }

        @Test
        @DisplayName("month 13 is out of range")
        void thirteenIsOutOfRange() {
            assertThat(cascade("20241301").returnMessage()).isEqualTo(MONTH_OUT_OF_RANGE);
        }

        @Test
        @DisplayName("month 01 and month 12 are both in range")
        void theRangeBoundsAreInclusive() {
            assertThat(cascade("20240101").monthFlag()).isEqualTo(DateEditFlag.VALID);
            assertThat(cascade("20241201").monthFlag()).isEqualTo(DateEditFlag.VALID);
        }

        @Test
        @DisplayName("a non-numeric month reports the range wording, not a numeric complaint")
        void aNonNumericMonthUsesTheRangeWording() {
            final DateEditResult result = cascade("2024X101");

            assertThat(result.monthFlag()).isEqualTo(DateEditFlag.NOT_OK);
            assertThat(result.returnMessage()).isEqualTo(MONTH_OUT_OF_RANGE);
        }
    }

    // =================================================================================================
    // THE DAY STAGE
    // =================================================================================================

    /**
     * Verifies {@code EDIT-DAY} at lines 150-205 of the copybook.
     *
     * <p>This stage is asymmetric with the two before it: it arms {@code FLG-DAY-ISVALID} rather
     * than the not-OK flag before testing anything. The asymmetry is preserved rather than
     * regularised. Its tests also run in a different order from the month stage — blank, then
     * {@code FUNCTION TEST-NUMVAL}, then the {@code WS-VALID-DAY} range — which is why a non-numeric
     * day and an out-of-range day happen to share one wording here for a different reason than in
     * the month stage.
     */
    @Nested
    @DisplayName("EDIT-DAY")
    class DayStage {

        @Test
        @DisplayName("a space-filled day is blank")
        void spacesAreBlank() {
            final DateEditResult result = cascade("202402  ");

            assertThat(result.dayFlag()).isEqualTo(DateEditFlag.BLANK);
            assertThat(result.returnMessage()).isEqualTo(DAY_NOT_SUPPLIED);
        }

        @Test
        @DisplayName("a low-values day is blank")
        void lowValuesAreBlank() {
            final DateEditResult result = cascade("202402\u0000\u0000");

            assertThat(result.dayFlag()).isEqualTo(DateEditFlag.BLANK);
            assertThat(result.returnMessage()).isEqualTo(DAY_NOT_SUPPLIED);
        }

        @Test
        @DisplayName("a non-numeric day is out of range")
        void nonNumericIsOutOfRange() {
            final DateEditResult result = cascade("202402X1");

            assertThat(result.dayFlag()).isEqualTo(DateEditFlag.NOT_OK);
            assertThat(result.returnMessage()).isEqualTo(DAY_OUT_OF_RANGE);
        }

        @Test
        @DisplayName("day 00 is out of range")
        void zeroIsOutOfRange() {
            assertThat(cascade("20240200").returnMessage()).isEqualTo(DAY_OUT_OF_RANGE);
        }

        @Test
        @DisplayName("day 32 is out of range")
        void thirtyTwoIsOutOfRange() {
            assertThat(cascade("20240132").returnMessage()).isEqualTo(DAY_OUT_OF_RANGE);
        }

        @Test
        @DisplayName("day 01 and day 31 are both in range at this stage")
        void theRangeBoundsAreInclusive() {
            assertThat(cascade("20240101").dayFlag()).isEqualTo(DateEditFlag.VALID);
            assertThat(cascade("20240131").dayFlag()).isEqualTo(DateEditFlag.VALID);
        }
    }

    // =================================================================================================
    // THE COMBINED STAGE
    // =================================================================================================

    /**
     * Verifies {@code EDIT-DAY-MONTH-YEAR} at lines 209-280 of the copybook.
     *
     * <p>Three checks fire here in order — a thirty-first day in a month that has none, a thirtieth
     * of February, and a twenty-ninth of February in a year the copybook's own divisor rule calls
     * ordinary. The divisor rule is the interesting one: 400 when the year-of-century digits are
     * {@code 00}, otherwise 4. That rule is not the full Gregorian rule, but it is exactly
     * equivalent to it across the only two centuries the year stage admits, because the sole
     * multiples of four that are not leap years are century years and those are precisely the years
     * whose year-of-century digits are {@code 00}.
     *
     * <p>This stage also runs unconditionally, after the three field stages, whatever they concluded
     * — which is what makes the non-numeric-year path through the leap-year arithmetic reachable.
     */
    @Nested
    @DisplayName("EDIT-DAY-MONTH-YEAR")
    class CombinedStage {

        @Test
        @DisplayName("a thirty-first day in a thirty-day month fails and blames day and month")
        void thirtyFirstOfAThirtyDayMonth() {
            final DateEditResult result = cascade("20240631");

            assertThat(result.returnMessage()).isEqualTo(CANNOT_HAVE_31_DAYS);
            assertThat(result.inputError()).isTrue();
            assertThat(result.yearFlag()).isEqualTo(DateEditFlag.VALID);
            assertThat(result.monthFlag()).isEqualTo(DateEditFlag.NOT_OK);
            assertThat(result.dayFlag()).isEqualTo(DateEditFlag.NOT_OK);
        }

        @Test
        @DisplayName("every thirty-one-day month accepts a thirty-first day")
        void theSevenLongMonthsAcceptTheThirtyFirst() {
            for (final String month : new String[] {"01", "03", "05", "07", "08", "10", "12"}) {
                final DateEditResult result = cascade("2024" + month + "31");

                assertThat(result.inputError())
                        .as("month %s has 31 days", month)
                        .isFalse();
                assertThat(flags(result)).isEqualTo(ALL_FLAGS_VALID);
            }
        }

        @Test
        @DisplayName("every thirty-day month rejects a thirty-first day")
        void theFourShortMonthsRejectTheThirtyFirst() {
            for (final String month : new String[] {"04", "06", "09", "11"}) {
                final DateEditResult result = cascade("2024" + month + "31");

                assertThat(result.returnMessage())
                        .as("month %s has only 30 days", month)
                        .isEqualTo(CANNOT_HAVE_31_DAYS);
            }
        }

        @Test
        @DisplayName("February rejects a thirty-first day through the first check, not the second")
        void februaryThirtyFirstUsesTheThirtyOneDayWording() {
            assertThat(cascade("20240231").returnMessage()).isEqualTo(CANNOT_HAVE_31_DAYS);
        }

        @Test
        @DisplayName("a thirtieth of February fails with its own wording")
        void februaryThirtieth() {
            final DateEditResult result = cascade("20240230");

            assertThat(result.returnMessage()).isEqualTo(CANNOT_HAVE_30_DAYS);
            assertThat(result.yearFlag()).isEqualTo(DateEditFlag.VALID);
            assertThat(result.monthFlag()).isEqualTo(DateEditFlag.NOT_OK);
            assertThat(result.dayFlag()).isEqualTo(DateEditFlag.NOT_OK);
        }

        @Test
        @DisplayName("a twenty-ninth of February passes in a year divisible by four")
        void februaryTwentyNinthInAnOrdinaryLeapYear() {
            final DateEditResult result = cascade("20240229");

            assertThat(result.inputError()).isFalse();
            assertThat(result.returnMessage()).isEqualTo(NO_MESSAGE);
            assertThat(flags(result)).isEqualTo(ALL_FLAGS_VALID);
        }

        @Test
        @DisplayName("a twenty-ninth of February fails in a year not divisible by four")
        void februaryTwentyNinthInACommonYear() {
            final DateEditResult result = cascade("20230229");

            assertThat(result.returnMessage()).isEqualTo(NOT_A_LEAP_YEAR);
            assertThat(result.inputError()).isTrue();
            assertThat(flags(result)).isEqualTo(ALL_FLAGS_NOT_OK);
        }

        @Test
        @DisplayName("the year-of-century 00 switches the divisor to 400, so 2000 is a leap year")
        void theCenturyYearTwoThousandIsLeap() {
            final DateEditResult result = cascade("20000229");

            assertThat(result.inputError()).isFalse();
            assertThat(flags(result)).isEqualTo(ALL_FLAGS_VALID);
        }

        @Test
        @DisplayName("the year-of-century 00 switches the divisor to 400, so 1900 is not")
        void theCenturyYearNineteenHundredIsNotLeap() {
            final DateEditResult result = cascade("19000229");

            assertThat(result.returnMessage()).isEqualTo(NOT_A_LEAP_YEAR);
            assertThat(flags(result)).isEqualTo(ALL_FLAGS_NOT_OK);
        }

        @Test
        @DisplayName("1996 and 2096 are leap years under the divisor-of-four rule")
        void otherOrdinaryLeapYears() {
            assertThat(cascade("19960229").inputError()).isFalse();
            assertThat(cascade("20960229").inputError()).isFalse();
        }

        @Test
        @DisplayName("the combined stage still runs when the year stage already failed")
        void theStageRunsRegardlessOfEarlierFlags() {
            // The year is non-numeric, so the year stage flagged it and claimed the message. The
            // combined stage nonetheless evaluates the leap-year arithmetic on a year whose numeric
            // view is negative, finds a non-zero remainder, and sets its own flags — but the earlier
            // wording survives.
            final DateEditResult result = cascade("XXXX0229");

            assertThat(result.returnMessage()).isEqualTo(YEAR_NOT_FOUR_DIGITS);
            assertThat(result.inputError()).isTrue();
            assertThat(flags(result)).isEqualTo(ALL_FLAGS_NOT_OK);
        }

        @Test
        @DisplayName("a blank month and a twenty-ninth day do not enter the leap-year arithmetic")
        void aBlankMonthSkipsTheFebruaryChecks() {
            final DateEditResult result = cascade("2024  29");

            assertThat(result.returnMessage()).isEqualTo(MONTH_NOT_SUPPLIED);
            assertThat(result.monthFlag()).isEqualTo(DateEditFlag.BLANK);
            assertThat(result.yearFlag()).isEqualTo(DateEditFlag.VALID);
        }
    }

    // =================================================================================================
    // THE LANGUAGE ENVIRONMENT STAGE
    // =================================================================================================

    /**
     * Verifies {@code EDIT-DATE-LE} and {@code EDIT-DATE-LE-EXIT} at lines 284-328 of the copybook.
     *
     * <p>The stage is guarded: the copybook ends {@code EDIT-DAY-MONTH-YEAR} with
     * {@code IF WS-EDIT-DATE-IS-VALID CONTINUE ELSE GO TO EDIT-DATE-CCYYMMDD-EXIT}, so the Language
     * Environment call is reached only when all three field flags are already low-values. That guard
     * is the behaviour asserted here, and it is also the reason the stage's own failure branch is
     * unreachable — see this class's type comment.
     *
     * <p>{@code EDIT-DATE-LE-EXIT} carries a genuine source oddity worth stating plainly: the
     * statement {@code SET WS-EDIT-DATE-IS-VALID TO TRUE} sits after that paragraph's {@code EXIT}
     * but before the {@code EDIT-DATE-CCYYMMDD-EXIT} label, and {@code EXIT} in COBOL is a no-op
     * rather than a return, so the statement executes on every path through the paragraph. The
     * translation reproduces the unconditional rewrite instead of guarding it.
     *
     * <p>A guarded variant of that rewrite is indistinguishable from the unconditional one through
     * the published surface, precisely because the only path on which the guard would bite is the
     * unreachable failure branch above it. That was confirmed by mutation: guarding the rewrite
     * leaves every assertion in this class passing. The unconditional form is kept for source
     * fidelity rather than because a test can tell the difference, and this paragraph exists so a
     * later reader does not mistake the absent guard for an oversight.
     */
    @Nested
    @DisplayName("EDIT-DATE-LE and its unconditional exit rewrite")
    class LanguageEnvironmentStage {

        @Test
        @DisplayName("a date that clears every field stage clears the Language Environment stage too")
        void aFullyValidDateSurvivesTheSafetyNet() {
            final DateEditResult result = cascade("20240229");

            assertThat(result.inputError()).isFalse();
            assertThat(result.yearFlag()).isEqualTo(DateEditFlag.VALID);
            assertThat(result.monthFlag()).isEqualTo(DateEditFlag.VALID);
            assertThat(result.dayFlag()).isEqualTo(DateEditFlag.VALID);
            assertThat(result.returnMessage()).isEqualTo(NO_MESSAGE);
        }

        @Test
        @DisplayName("the stage is skipped when an earlier stage left a flag set")
        void anEarlierFailureSkipsTheStageAndLeavesTheFlagsAsSet() {
            // If the exit rewrite were reachable from here it would blank these flags, so observing
            // them still set is the proof that the guard held and the stage was never entered.
            final DateEditResult result = cascade("20230229");

            assertThat(flags(result)).isEqualTo(ALL_FLAGS_NOT_OK);
            assertThat(result.inputError()).isTrue();
        }

        @Test
        @DisplayName("a boundary date at the start of the supported range is accepted")
        void theEarliestSupportedDateIsAccepted() {
            // 1582-10-15 is the first day of the Gregorian calendar and the first day the subprogram
            // accepts. Century 15 is outside the year stage's vocabulary, so this is asserted through
            // the subprogram rather than the cascade.
            assertThat(compact("15821015").feedback()).isEqualTo(DateFeedback.DATE_IS_VALID);
            assertThat(compact("15821014").feedback()).isEqualTo(DateFeedback.UNSUPPORTED_RANGE);
        }
    }

    // =================================================================================================
    // FIELD-WIDTH SEMANTICS
    // =================================================================================================

    /**
     * Verifies the {@code MOVE}-to-{@code PIC X(n)} semantics the entry points apply to their
     * arguments.
     *
     * <p>A COBOL {@code MOVE} into a fixed-width alphanumeric field pads on the right with spaces
     * and truncates on the right, and never fails. Reproducing that is what lets the cascade slice
     * the eight-character image by offset without a length check.
     */
    @Nested
    @DisplayName("Fixed-width field semantics")
    class FieldWidthSemantics {

        @Test
        @DisplayName("a candidate longer than eight characters is truncated, not rejected")
        void anOverlongCandidateIsTruncated() {
            final DateEditResult result = cascade("20240229IGNORED");

            assertThat(result.inputError()).isFalse();
            assertThat(flags(result)).isEqualTo(ALL_FLAGS_VALID);
        }

        @Test
        @DisplayName("a candidate shorter than eight characters is space padded")
        void aShortCandidateIsPadded() {
            // "202402" pads to "202402  ", so the day slice is two spaces and the day stage reports
            // the blank state rather than a length error.
            final DateEditResult result = cascade("202402");

            assertThat(result.dayFlag()).isEqualTo(DateEditFlag.BLANK);
            assertThat(result.returnMessage()).isEqualTo(DAY_NOT_SUPPLIED);
        }

        @Test
        @DisplayName("an empty candidate becomes eight spaces")
        void anEmptyCandidateBecomesAllSpaces() {
            assertThat(cascade("").returnMessage()).isEqualTo(YEAR_NOT_SUPPLIED);
            assertThat(flags(cascade(""))).isEqualTo("BBB");
        }

        @Test
        @DisplayName("a candidate of exactly eight characters is passed through untouched")
        void anExactWidthCandidateIsUnchanged() {
            assertThat(compact("20240229").testedDate())
                    .isEqualTo("20240229  ")
                    .hasSize(LINKAGE_TEXT_WIDTH);
        }

        @Test
        @DisplayName("the subprogram pads its date and mask to the ten-character linkage width")
        void theLinkageFieldsAreTenCharacters() {
            final SubprogramResult result = service.validateDate("2024", "YYYYMMDD  ");

            assertThat(result.testedDate()).isEqualTo("2024      ").hasSize(LINKAGE_TEXT_WIDTH);
            assertThat(result.maskUsed()).isEqualTo("YYYYMMDD  ").hasSize(LINKAGE_TEXT_WIDTH);
        }

        @Test
        @DisplayName("an overlong mask is truncated to ten characters before it is resolved")
        void anOverlongMaskIsTruncated() {
            // "YYYY-MM-DDXX" truncates to "YYYY-MM-DD", which is a recognised selector, so the date
            // is validated rather than reported as a bad picture string.
            final SubprogramResult result = service.validateDate("2024-02-29", "YYYY-MM-DDXX");

            assertThat(result.maskUsed()).isEqualTo("YYYY-MM-DD");
            assertThat(result.feedback()).isEqualTo(DateFeedback.DATE_IS_VALID);
        }

        @Test
        @DisplayName("the eight-character field width is what the cascade slices by")
        void theCascadeWidthIsEight() {
            // Eight characters of digits are consumed; a ninth is not read at all, which is only
            // observable because the ninth here would otherwise change the verdict.
            assertThat(CCYYMMDD_WIDTH).isEqualTo(8);
            assertThat(cascade("202402299").inputError()).isFalse();
        }
    }

    // =================================================================================================
    // THE DATE-OF-BIRTH RANGE
    // =================================================================================================

    /**
     * Verifies {@code EDIT-DATE-OF-BIRTH} at lines 341-370 of the copybook.
     *
     * <p>This is a separate {@code PERFORM} range, not a stage of the main cascade, and it differs
     * from the main cascade in two ways that matter. It arms the flag group to low-values rather
     * than to {@code '000'}, because it is only ever entered once the main cascade has already left
     * every flag valid. And its comparison is strict — the copybook writes
     * {@code IF WS-CURRENT-DATE-BINARY > WS-EDIT-DATE-BINARY}, so a birth date equal to today is
     * rejected along with genuinely future dates.
     */
    @Nested
    @DisplayName("EDIT-DATE-OF-BIRTH")
    class DateOfBirthRange {

        private static final LocalDate TODAY = LocalDate.of(2024, 6, 15);

        @Test
        @DisplayName("a past birth date passes and leaves every flag valid")
        void aPastDateIsAccepted() {
            final DateEditResult result = service.validateDateOfBirth("19800101", TODAY);

            assertThat(result.inputError()).isFalse();
            assertThat(flags(result)).isEqualTo(ALL_FLAGS_VALID);
            assertThat(result.returnMessage()).isEqualTo(NO_MESSAGE);
        }

        @Test
        @DisplayName("yesterday is accepted")
        void theDayBeforeIsAccepted() {
            assertThat(service.validateDateOfBirth("20240614", TODAY).inputError()).isFalse();
        }

        @Test
        @DisplayName("today is rejected, because the comparison is strictly greater than")
        void todayIsRejected() {
            final DateEditResult result = service.validateDateOfBirth("20240615", TODAY);

            assertThat(result.inputError()).isTrue();
            assertThat(result.returnMessage()).isEqualTo(CANNOT_BE_IN_THE_FUTURE);
            assertThat(flags(result)).isEqualTo(ALL_FLAGS_NOT_OK);
        }

        @Test
        @DisplayName("a future birth date is rejected and blames all three fields")
        void aFutureDateIsRejected() {
            final DateEditResult result = service.validateDateOfBirth("20301231", TODAY);

            assertThat(result.inputError()).isTrue();
            assertThat(result.yearFlag()).isEqualTo(DateEditFlag.NOT_OK);
            assertThat(result.monthFlag()).isEqualTo(DateEditFlag.NOT_OK);
            assertThat(result.dayFlag()).isEqualTo(DateEditFlag.NOT_OK);
            assertThat(result.returnMessage()).isEqualTo(CANNOT_BE_IN_THE_FUTURE);
        }

        @Test
        @DisplayName("a caller-supplied message survives a future birth date")
        void aPriorMessageIsPreserved() {
            final DateEditResult result =
                    service.validateDateOfBirth("20301231", TODAY, "EARLIER COMPLAINT");

            assertThat(result.returnMessage()).isEqualTo("EARLIER COMPLAINT");
            assertThat(result.inputError()).isTrue();
        }

        @Test
        @DisplayName("the three-argument form defaults its message to the blank state")
        void theTwoArgumentFormStartsBlank() {
            assertThat(service.validateDateOfBirth("19800101", TODAY).returnMessage())
                    .isEqualTo(NO_MESSAGE);
        }

        @Test
        @DisplayName("an unresolvable image is refused rather than silently normalised")
        void anUnresolvableImageIsRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> service.validateDateOfBirth("20240230", TODAY))
                    .withMessageContaining("[20240230]")
                    .withMessageContaining("is not a resolvable CCYYMMDD")
                    .withCauseInstanceOf(DateTimeParseException.class);
        }

        @Test
        @DisplayName("a blank image is refused for the same reason")
        void aBlankImageIsRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> service.validateDateOfBirth("        ", TODAY))
                    .withMessageContaining("is not a resolvable CCYYMMDD");
        }

        @Test
        @DisplayName("a leap day is resolvable and a false leap day is not")
        void leapDayHandling() {
            assertThatNoException()
                    .isThrownBy(() -> service.validateDateOfBirth("20000229", TODAY));
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> service.validateDateOfBirth("19000229", TODAY));
        }
    }

    // =================================================================================================
    // THE CALLABLE SUBPROGRAM
    // =================================================================================================

    /**
     * Verifies the {@code CSUTLDTC} substitution: {@code validateDate} and the feedback vocabulary
     * it publishes.
     *
     * <p>The legacy subprogram wraps {@code CALL "CEEDAYS"} and translates the Language Environment
     * feedback token into a severity, a message number and a fifteen-character result text through
     * an {@code EVALUATE} whose clause order is reproduced here. Each message number is the decimal
     * value of the corresponding token: the insufficient-data token
     * {@code X'000309CB59C3C5C5'} yields 2507, and the unsupported-range token
     * {@code X'000309D159C3C5C5'} yields 2513.
     */
    @Nested
    @DisplayName("The CSUTLDTC substitution")
    class CallableSubprogram {

        @Test
        @DisplayName("a valid compact date reports zero severity and the valid text")
        void aValidCompactDate() {
            final SubprogramResult result = compact("20240229");

            assertThat(result.feedback()).isEqualTo(DateFeedback.DATE_IS_VALID);
            assertThat(result.severityCode()).isEqualTo("0000");
            assertThat(result.messageNumber()).isEqualTo("0000");
            assertThat(result.resultText()).isEqualTo("Date is valid  ");
            assertThat(result.numericSeverity()).isZero();
        }

        @Test
        @DisplayName("a valid hyphenated date reports the same outcome under the other mask")
        void aValidHyphenatedDate() {
            final SubprogramResult result = service.validateDate("2024-02-29", DateFormat.YYYY_MM_DD);

            assertThat(result.feedback()).isEqualTo(DateFeedback.DATE_IS_VALID);
            assertThat(result.maskUsed()).isEqualTo("YYYY-MM-DD");
            assertThat(result.severityCode()).isEqualTo("0000");
        }

        @Test
        @DisplayName("an all-space date is insufficient data, message 2507")
        void insufficientData() {
            final SubprogramResult result = compact("");

            assertThat(result.feedback()).isEqualTo(DateFeedback.INSUFFICIENT_DATA);
            assertThat(result.severityCode()).isEqualTo("0003");
            assertThat(result.messageNumber()).isEqualTo("2507");
            assertThat(result.resultText()).isEqualTo("Insufficient   ");
        }

        @Test
        @DisplayName("a low-values date is insufficient data too")
        void lowValuesAreInsufficientData() {
            assertThat(compact(FOUR_LOW_VALUES + FOUR_LOW_VALUES).feedback())
                    .isEqualTo(DateFeedback.INSUFFICIENT_DATA);
        }

        @Test
        @DisplayName("a calendar-impossible date is a bad date value, message 2508")
        void badDateValue() {
            final SubprogramResult result = compact("20240230");

            assertThat(result.feedback()).isEqualTo(DateFeedback.BAD_DATE_VALUE);
            assertThat(result.messageNumber()).isEqualTo("2508");
            assertThat(result.resultText()).isEqualTo("Datevalue error");
        }

        @Test
        @DisplayName("a false leap day is a bad date value, proving strict resolution")
        void strictResolutionRefusesToNormalise() {
            // Lenient resolution would roll 2023-02-29 forward to 2023-03-01 and report success.
            assertThat(compact("20230229").feedback()).isEqualTo(DateFeedback.BAD_DATE_VALUE);
        }

        @Test
        @DisplayName("a date before the Gregorian epoch is an unsupported range, message 2513")
        void unsupportedRange() {
            final SubprogramResult result = compact("15000101");

            assertThat(result.feedback()).isEqualTo(DateFeedback.UNSUPPORTED_RANGE);
            assertThat(result.messageNumber()).isEqualTo("2513");
            assertThat(result.resultText()).isEqualTo("Unsupp. Range  ");
        }

        @Test
        @DisplayName("a month outside one to twelve is an invalid month, message 2517")
        void invalidMonth() {
            final SubprogramResult high = compact("20241301");
            final SubprogramResult low = compact("20240001");

            assertThat(high.feedback()).isEqualTo(DateFeedback.INVALID_MONTH);
            assertThat(high.messageNumber()).isEqualTo("2517");
            assertThat(high.resultText()).isEqualTo("Invalid month  ");
            assertThat(low.feedback()).isEqualTo(DateFeedback.INVALID_MONTH);
        }

        @Test
        @DisplayName("an unrecognised mask is a bad picture string, message 2518")
        void badPictureString() {
            final SubprogramResult result = service.validateDate("20240229", "DDMMYYYY  ");

            assertThat(result.feedback()).isEqualTo(DateFeedback.BAD_PICTURE_STRING);
            assertThat(result.messageNumber()).isEqualTo("2518");
            assertThat(result.resultText()).isEqualTo("Bad Pic String ");
            assertThat(result.maskUsed()).isEqualTo("DDMMYYYY  ");
            assertThat(result.testedDate()).isEqualTo("20240229  ");
        }

        @Test
        @DisplayName("a blank mask is a bad picture string as well")
        void aBlankMaskIsABadPictureString() {
            assertThat(service.validateDate("20240229", "").feedback())
                    .isEqualTo(DateFeedback.BAD_PICTURE_STRING);
        }

        @Test
        @DisplayName("non-digits where the mask wants digits is non-numeric data, message 2520")
        void nonNumericData() {
            final SubprogramResult result = compact("2024XX29");

            assertThat(result.feedback()).isEqualTo(DateFeedback.NON_NUMERIC_DATA);
            assertThat(result.messageNumber()).isEqualTo("2520");
            assertThat(result.resultText()).isEqualTo("Nonnumeric data");
        }

        @Test
        @DisplayName("the hyphenated mask tolerates its own separators but not stray characters")
        void theSeparatorPositionsAreNotDigitPositions() {
            assertThat(service.validateDate("2024-02-29", DateFormat.YYYY_MM_DD).feedback())
                    .isEqualTo(DateFeedback.DATE_IS_VALID);
            assertThat(service.validateDate("2024/02/29", DateFormat.YYYY_MM_DD).feedback())
                    .isEqualTo(DateFeedback.BAD_DATE_VALUE);
            assertThat(service.validateDate("2024-X2-29", DateFormat.YYYY_MM_DD).feedback())
                    .isEqualTo(DateFeedback.NON_NUMERIC_DATA);
        }

        @Test
        @DisplayName("a zero year is year-in-era zero, message 2521")
        void yearInEraZero() {
            final SubprogramResult result = compact("00000101");

            assertThat(result.feedback()).isEqualTo(DateFeedback.YEAR_IN_ERA_ZERO);
            assertThat(result.messageNumber()).isEqualTo("2521");
            assertThat(result.resultText()).isEqualTo("YearInEra is 0 ");
        }

        @Test
        @DisplayName("the checks fire in the copybook's order")
        void theSubprogramChecksAreOrdered() {
            // A zero year with a thirteenth month would fail both the year check and the month check;
            // the year check runs first. A non-numeric month with a zero year fails the digit check
            // before either, because the digit check precedes both.
            assertThat(compact("00001301").feedback()).isEqualTo(DateFeedback.YEAR_IN_ERA_ZERO);
            assertThat(compact("0000XX01").feedback()).isEqualTo(DateFeedback.NON_NUMERIC_DATA);
            assertThat(compact("20241332").feedback()).isEqualTo(DateFeedback.INVALID_MONTH);
        }

        @Test
        @DisplayName("every failing outcome carries severity three and the valid outcome zero")
        void theSeverityVocabularyIsBinary() {
            assertThat(compact("20240229").numericSeverity()).isZero();
            assertThat(compact("").numericSeverity()).isEqualTo(3);
            assertThat(compact("20240230").numericSeverity()).isEqualTo(3);
            assertThat(compact("20241301").numericSeverity()).isEqualTo(3);
            assertThat(compact("2024XX29").numericSeverity()).isEqualTo(3);
            assertThat(compact("00000101").numericSeverity()).isEqualTo(3);
            assertThat(compact("15000101").numericSeverity()).isEqualTo(3);
        }

        @Test
        @DisplayName("the string-mask form agrees with the enum form for a recognised selector")
        void theTwoOverloadsAgree() {
            final SubprogramResult viaEnum = service.validateDate("20240230", DateFormat.YYYYMMDD);
            final SubprogramResult viaString = service.validateDate("20240230", "YYYYMMDD  ");

            assertThat(viaString).isEqualTo(viaEnum);
        }
    }

    // =================================================================================================
    // THE FEEDBACK VOCABULARY
    // =================================================================================================

    /**
     * Verifies {@link DateFeedback} against the {@code FEEDBACK-CODE} condition names at lines 60-70
     * of {@code app/cbl/CSUTLDTC.cbl}.
     *
     * <p>Each severity and message number is asserted as a literal pair rather than read back from
     * the enum, because the numbers are an external contract: the reporting transaction at
     * {@code app/cbl/CORPT00C.cbl} lines 399 and 419 branches on the character string
     * {@code '2513'}, so a changed number would silently change which dates that screen accepts.
     */
    @Nested
    @DisplayName("The Language Environment feedback vocabulary")
    class FeedbackVocabulary {

        @Test
        @DisplayName("the valid outcome alone carries severity zero")
        void onlyTheValidOutcomeIsSeverityZero() {
            assertThat(DateFeedback.DATE_IS_VALID.getSeverity()).isZero();
            assertThat(DateFeedback.DATE_IS_VALID.getMessageNumber()).isZero();

            for (final DateFeedback feedback : DateFeedback.values()) {
                if (feedback != DateFeedback.DATE_IS_VALID) {
                    assertThat(feedback.getSeverity())
                            .as("%s must report the failing severity", feedback)
                            .isEqualTo(3);
                }
            }
        }

        @Test
        @DisplayName("each message number is the decimal of its feedback token")
        void theMessageNumbersMatchTheTokens() {
            assertThat(DateFeedback.INSUFFICIENT_DATA.getMessageNumber()).isEqualTo(2507);
            assertThat(DateFeedback.BAD_DATE_VALUE.getMessageNumber()).isEqualTo(2508);
            assertThat(DateFeedback.INVALID_ERA.getMessageNumber()).isEqualTo(2509);
            assertThat(DateFeedback.UNSUPPORTED_RANGE.getMessageNumber()).isEqualTo(2513);
            assertThat(DateFeedback.INVALID_MONTH.getMessageNumber()).isEqualTo(2517);
            assertThat(DateFeedback.BAD_PICTURE_STRING.getMessageNumber()).isEqualTo(2518);
            assertThat(DateFeedback.NON_NUMERIC_DATA.getMessageNumber()).isEqualTo(2520);
            assertThat(DateFeedback.YEAR_IN_ERA_ZERO.getMessageNumber()).isEqualTo(2521);
        }

        @Test
        @DisplayName("the catch-all outcome carries no message number of its own")
        void theCatchAllHasNoNumber() {
            assertThat(DateFeedback.UNRECOGNISED_FEEDBACK.getSeverity()).isEqualTo(3);
            assertThat(DateFeedback.UNRECOGNISED_FEEDBACK.getMessageNumber()).isZero();
        }

        @Test
        @DisplayName("the declared order is the EVALUATE clause order the subprogram uses")
        void theDeclarationOrderMatchesTheEvaluate() {
            assertThat(DateFeedback.values())
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
    }

    // =================================================================================================
    // THE EIGHTY-CHARACTER RESULT BLOCK
    // =================================================================================================

    /**
     * Verifies {@link SubprogramResult} against the {@code WS-MESSAGE} group at lines 42-57 of
     * {@code app/cbl/CSUTLDTC.cbl}, which the subprogram moves wholesale into
     * {@code LS-RESULT PIC X(80)}.
     *
     * <p>The group is, position by position: {@code WS-SEVERITY PIC X(04)}, a {@code PIC X(11)}
     * filler valued {@code 'Mesg Code:'}, {@code WS-MSG-NO PIC X(04)}, a one-character space,
     * {@code WS-RESULT PIC X(15)}, a space, a {@code PIC X(09)} filler valued {@code 'TstDate:'},
     * {@code WS-DATE PIC X(10)}, a space, a {@code PIC X(10)} filler valued {@code 'Mask used:'},
     * {@code WS-DATE-FMT PIC X(10)}, a space and a three-character filler. Those widths sum to
     * exactly eighty, and because the two label fillers are wider than their literals they carry
     * trailing spaces that a hand-written concatenation would be liable to drop.
     */
    @Nested
    @DisplayName("The eighty-character LS-RESULT block")
    class ResultBlock {

        @Test
        @DisplayName("the rendered block is exactly eighty characters")
        void theBlockIsEightyCharacters() {
            assertThat(compact("20240229").render()).hasSize(RESULT_BLOCK_WIDTH);
            assertThat(compact("").render()).hasSize(RESULT_BLOCK_WIDTH);
            assertThat(service.validateDate("2024-02-29", DateFormat.YYYY_MM_DD).render())
                    .hasSize(RESULT_BLOCK_WIDTH);
        }

        @Test
        @DisplayName("the block reproduces every field and filler position for a valid date")
        void theValidBlockIsReproducedInFull() {
            assertThat(compact("20240229").render())
                    .isEqualTo("0000Mesg Code: 0000 Date is valid   TstDate: 20240229   "
                            + "Mask used:YYYYMMDD      ");
        }

        @Test
        @DisplayName("the block reproduces every position for a failing date too")
        void theFailingBlockIsReproducedInFull() {
            assertThat(compact("20240230").render())
                    .isEqualTo("0003Mesg Code: 2508 Datevalue error TstDate: 20240230   "
                            + "Mask used:YYYYMMDD      ");
        }

        @Test
        @DisplayName("the label fillers keep the trailing spaces their picture clauses give them")
        void theLabelFillersAreWiderThanTheirLiterals() {
            final String block = compact("20240229").render();

            assertThat(block.substring(CODE_WIDTH, CODE_WIDTH + 11)).isEqualTo("Mesg Code: ");
            assertThat(block.substring(36, 45)).isEqualTo("TstDate: ");
            assertThat(block.substring(56, 66)).isEqualTo("Mask used:");
            assertThat(block.substring(RESULT_BLOCK_WIDTH - 4)).isEqualTo("    ");
        }

        @Test
        @DisplayName("the severity, message number, result text, date and mask sit at their offsets")
        void theFieldsSitAtTheirOffsets() {
            final SubprogramResult result = compact("20241301");
            final String block = result.render();

            assertThat(block.substring(0, 4)).isEqualTo(result.severityCode());
            assertThat(block.substring(15, 19)).isEqualTo(result.messageNumber());
            assertThat(block.substring(20, 35)).isEqualTo(result.resultText());
            assertThat(block.substring(45, 55)).isEqualTo(result.testedDate());
            assertThat(block.substring(66, 76)).isEqualTo(result.maskUsed());
        }

        @Test
        @DisplayName("the message segment is the sixty-one character tail of the block")
        void theMessageSegmentIsTheTail() {
            final SubprogramResult result = compact("20240230");

            assertThat(result.messageSegment())
                    .hasSize(MESSAGE_SEGMENT_WIDTH)
                    .isEqualTo(result.render().substring(RESULT_BLOCK_WIDTH - MESSAGE_SEGMENT_WIDTH));
            assertThat(result.messageSegment()).startsWith(" Datevalue error ");
        }

        @Test
        @DisplayName("the result text is always padded to fifteen characters")
        void theResultTextIsFifteenCharacters() {
            assertThat(compact("20240229").resultText()).hasSize(RESULT_TEXT_WIDTH);
            assertThat(compact("").resultText()).hasSize(RESULT_TEXT_WIDTH);
            assertThat(compact("2024XX29").resultText()).hasSize(RESULT_TEXT_WIDTH);
        }

        @Test
        @DisplayName("the severity and message number are zero filled to four characters")
        void theCodesAreZeroFilled() {
            assertThat(compact("20240229").severityCode()).isEqualTo("0000").hasSize(CODE_WIDTH);
            assertThat(compact("").severityCode()).isEqualTo("0003").hasSize(CODE_WIDTH);
            assertThat(compact("20240229").messageNumber()).isEqualTo("0000").hasSize(CODE_WIDTH);
            assertThat(compact("15000101").messageNumber()).isEqualTo("2513").hasSize(CODE_WIDTH);
        }
    }

    // =================================================================================================
    // THE RESULT-BLOCK WIDTH CONTRACT
    // =================================================================================================

    /**
     * Verifies the width guards on {@link SubprogramResult}'s canonical constructor.
     *
     * <p>Each component fills a fixed run of {@code LS-RESULT} positions, so a component of the
     * wrong width would silently shift every position after it. The record refuses such a value
     * rather than rendering a misaligned block.
     */
    @Nested
    @DisplayName("SubprogramResult width guards")
    class WidthGuards {

        private static final String GOOD_CODE = "0003";
        private static final String GOOD_TEXT = "Datevalue error";
        private static final String GOOD_LINKAGE = "20240230  ";

        @Test
        @DisplayName("a well-formed result is accepted")
        void aWellFormedResultIsAccepted() {
            assertThatNoException().isThrownBy(() -> new SubprogramResult(
                    DateFeedback.BAD_DATE_VALUE, GOOD_CODE, "2508", GOOD_TEXT,
                    GOOD_LINKAGE, "YYYYMMDD  "));
        }

        @Test
        @DisplayName("a null feedback is refused")
        void aNullFeedbackIsRefused() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new SubprogramResult(
                            null, GOOD_CODE, "2508", GOOD_TEXT, GOOD_LINKAGE, "YYYYMMDD  "))
                    .withMessage("feedback must not be null");
        }

        @Test
        @DisplayName("a severity code of the wrong width is refused")
        void aShortSeverityIsRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new SubprogramResult(
                            DateFeedback.BAD_DATE_VALUE, "003", "2508", GOOD_TEXT,
                            GOOD_LINKAGE, "YYYYMMDD  "))
                    // The width a PIC X(n) component declares reserves bytes, not characters, so the
                    // guard measures the encoded image and reports the measured byte width. The two
                    // differ for any value outside the single-byte range, which is exactly where a
                    // character-count check would admit a value the field cannot hold, so the wording
                    // records which unit was measured. The offending value is not echoed back.
                    .withMessageContaining("severityCode must be exactly 4 encoded bytes")
                    .withMessageContaining("but measures 3")
                    .withMessageNotContaining("[003]");
        }

        @Test
        @DisplayName("a message number of the wrong width is refused")
        void aLongMessageNumberIsRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new SubprogramResult(
                            DateFeedback.BAD_DATE_VALUE, GOOD_CODE, "25080", GOOD_TEXT,
                            GOOD_LINKAGE, "YYYYMMDD  "))
                    .withMessageContaining("messageNumber must be exactly 4 encoded bytes")
                    .withMessageContaining("but measures 5");
        }

        @Test
        @DisplayName("a result text of the wrong width is refused")
        void anUnpaddedResultTextIsRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new SubprogramResult(
                            DateFeedback.BAD_DATE_VALUE, GOOD_CODE, "2508", "Datevalue err",
                            GOOD_LINKAGE, "YYYYMMDD  "))
                    .withMessageContaining("resultText must be exactly 15 encoded bytes")
                    .withMessageContaining("but measures 13");
        }

        @Test
        @DisplayName("a tested date of the wrong width is refused")
        void anUnpaddedTestedDateIsRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new SubprogramResult(
                            DateFeedback.BAD_DATE_VALUE, GOOD_CODE, "2508", GOOD_TEXT,
                            "20240230", "YYYYMMDD  "))
                    .withMessageContaining("testedDate must be exactly 10 encoded bytes")
                    .withMessageContaining("but measures 8");
        }

        @Test
        @DisplayName("a mask of the wrong width is refused")
        void anUnpaddedMaskIsRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new SubprogramResult(
                            DateFeedback.BAD_DATE_VALUE, GOOD_CODE, "2508", GOOD_TEXT,
                            GOOD_LINKAGE, "YYYYMMDD"))
                    .withMessageContaining("maskUsed must be exactly 10 encoded bytes")
                    .withMessageContaining("but measures 8");
        }

        @Test
        @DisplayName("each fixed-width component refuses null with its own wording")
        void everyComponentRefusesNull() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new SubprogramResult(
                            DateFeedback.BAD_DATE_VALUE, null, "2508", GOOD_TEXT,
                            GOOD_LINKAGE, "YYYYMMDD  "))
                    .withMessage("severityCode must not be null");
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new SubprogramResult(
                            DateFeedback.BAD_DATE_VALUE, GOOD_CODE, null, GOOD_TEXT,
                            GOOD_LINKAGE, "YYYYMMDD  "))
                    .withMessage("messageNumber must not be null");
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new SubprogramResult(
                            DateFeedback.BAD_DATE_VALUE, GOOD_CODE, "2508", null,
                            GOOD_LINKAGE, "YYYYMMDD  "))
                    .withMessage("resultText must not be null");
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new SubprogramResult(
                            DateFeedback.BAD_DATE_VALUE, GOOD_CODE, "2508", GOOD_TEXT,
                            null, "YYYYMMDD  "))
                    .withMessage("testedDate must not be null");
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new SubprogramResult(
                            DateFeedback.BAD_DATE_VALUE, GOOD_CODE, "2508", GOOD_TEXT,
                            GOOD_LINKAGE, null))
                    .withMessage("maskUsed must not be null");
        }

        @Test
        @DisplayName("a component whose width is right but whose value is not still renders eighty")
        void theRenderGuardAgreesWithTheConstructorGuards() {
            // The render guard exists as a belt-and-braces check behind the constructor guards. With
            // every component at its declared width the two agree, which is what this asserts.
            assertThat(new SubprogramResult(DateFeedback.UNRECOGNISED_FEEDBACK, "0003", "0000",
                    "Date is invalid", "          ", "          ").render())
                    .hasSize(RESULT_BLOCK_WIDTH);
        }
    }

    // =================================================================================================
    // THE CALLER-SIDE ACCEPTANCE RULE
    // =================================================================================================

    /**
     * Verifies {@link DateValidationService#isDateAcceptable} against the caller-side rule at lines
     * 396-406 and 416-426 of {@code app/cbl/CORPT00C.cbl}.
     *
     * <p>That program writes {@code IF CSUTLDTC-RESULT-SEV-CD = '0000' CONTINUE ELSE IF
     * CSUTLDTC-RESULT-MSG-NUM NOT = '2513' ...reject... END-IF END-IF}, which means a non-zero
     * severity whose message number is exactly 2513 is tolerated. The tolerated number is the
     * unsupported-range code, so the reporting screen accepts a date the Language Environment
     * considers out of range while rejecting every other complaint.
     */
    @Nested
    @DisplayName("The CORPT00C acceptance rule")
    class AcceptanceRule {

        @Test
        @DisplayName("a zero severity is accepted")
        void zeroSeverityIsAccepted() {
            assertThat(service.isDateAcceptable(compact("20240229"))).isTrue();
        }

        @Test
        @DisplayName("the unsupported-range complaint is tolerated despite its severity")
        void theToleratedNumberIsAccepted() {
            final SubprogramResult outOfRange = compact("15000101");

            assertThat(outOfRange.severityCode()).isEqualTo("0003");
            assertThat(outOfRange.messageNumber()).isEqualTo("2513");
            assertThat(service.isDateAcceptable(outOfRange)).isTrue();
        }

        @Test
        @DisplayName("every other complaint is rejected")
        void otherComplaintsAreRejected() {
            assertThat(service.isDateAcceptable(compact("20240230"))).isFalse();
            assertThat(service.isDateAcceptable(compact(""))).isFalse();
            assertThat(service.isDateAcceptable(compact("20241301"))).isFalse();
            assertThat(service.isDateAcceptable(compact("2024XX29"))).isFalse();
            assertThat(service.isDateAcceptable(compact("00000101"))).isFalse();
            assertThat(service.isDateAcceptable(
                    service.validateDate("20240229", "DDMMYYYY  "))).isFalse();
        }

        @Test
        @DisplayName("a null result is refused rather than silently accepted")
        void aNullResultIsRefused() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> service.isDateAcceptable(null))
                    .withMessage("result must not be null");
        }
    }

    // =================================================================================================
    // THE FLAG GROUP
    // =================================================================================================

    /**
     * Verifies {@link DateEditFlag} and {@link DateEditResult} against
     * {@code app/cpy/CSUTLDWY.cpy} lines 43-57.
     *
     * <p>The three flag bytes are a contiguous {@code PIC X(01)} triple inside a group that carries
     * two level-88 names of its own, so callers test the group image as a whole as well as the
     * individual bytes. {@code app/cbl/COACTUPC.cbl} does exactly that: it copies the group at line
     * 1482 and tests the copy at lines 1538 and 1539. Reproducing the byte images is therefore part
     * of the contract, not an implementation detail.
     */
    @Nested
    @DisplayName("The WS-EDIT-DATE-FLGS group")
    class FlagGroup {

        @Test
        @DisplayName("each flag carries its copybook byte image")
        void theByteImagesMatchTheLevelEightyEights() {
            assertThat(DateEditFlag.VALID.getImage()).isEqualTo('\u0000');
            assertThat(DateEditFlag.NOT_OK.getImage()).isEqualTo('0');
            assertThat(DateEditFlag.BLANK.getImage()).isEqualTo('B');
        }

        @Test
        @DisplayName("the three images are distinct, so the group image is unambiguous")
        void theImagesAreDistinct() {
            assertThat(new char[] {DateEditFlag.VALID.getImage(),
                    DateEditFlag.NOT_OK.getImage(),
                    DateEditFlag.BLANK.getImage()})
                    .doesNotHaveDuplicates();
        }

        @Test
        @DisplayName("the group image of a fully valid result is the level-88 LOW-VALUES")
        void theValidGroupImageIsLowValues() {
            assertThat(cascade("20240229").flagsImage())
                    .isEqualTo(ALL_FLAGS_VALID)
                    .hasSize(3);
        }

        @Test
        @DisplayName("the group image of a wholly rejected result is the level-88 value '000'")
        void theInvalidGroupImageIsThreeZeros() {
            assertThat(cascade("20230229").flagsImage()).isEqualTo(ALL_FLAGS_NOT_OK);
        }

        @Test
        @DisplayName("the group image is ordered year, month, day")
        void theGroupImageIsOrdered() {
            // A blank month between a valid year and a blank day is only distinguishable from the
            // reverse ordering by reading the middle byte.
            final DateEditResult result = cascade("2024  32");

            assertThat(result.flagsImage()).isEqualTo("\u0000B0");
            assertThat(result.yearFlag()).isEqualTo(DateEditFlag.VALID);
            assertThat(result.monthFlag()).isEqualTo(DateEditFlag.BLANK);
            assertThat(result.dayFlag()).isEqualTo(DateEditFlag.NOT_OK);
        }

        @Test
        @DisplayName("the result refuses a null flag or a null message")
        void theResultRefusesNulls() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new DateEditResult(false, null,
                            DateEditFlag.VALID, DateEditFlag.VALID, NO_MESSAGE))
                    .withMessage("yearFlag must not be null");
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new DateEditResult(false, DateEditFlag.VALID,
                            null, DateEditFlag.VALID, NO_MESSAGE))
                    .withMessage("monthFlag must not be null");
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new DateEditResult(false, DateEditFlag.VALID,
                            DateEditFlag.VALID, null, NO_MESSAGE))
                    .withMessage("dayFlag must not be null");
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new DateEditResult(false, DateEditFlag.VALID,
                            DateEditFlag.VALID, DateEditFlag.VALID, null))
                    .withMessage("returnMessage must not be null; use an empty string");
        }

        @Test
        @DisplayName("the result publishes no overall verdict, only the flags and the error switch")
        void thereIsNoSingleVerdictField() {
            // The copybook has no such field either: callers test INPUT-ERROR and the individual
            // flags because a field can be blank without the whole date being unusable. Asserting
            // the two independently is what keeps that distinction visible.
            final DateEditResult blankDay = cascade("202402  ");

            assertThat(blankDay.inputError()).isTrue();
            assertThat(blankDay.dayFlag()).isEqualTo(DateEditFlag.BLANK);
            assertThat(blankDay.yearFlag()).isEqualTo(DateEditFlag.VALID);
            assertThat(blankDay.monthFlag()).isEqualTo(DateEditFlag.VALID);
        }
    }

    // =================================================================================================
    // MANDATORY ARGUMENTS
    // =================================================================================================

    /**
     * Verifies that every entry point refuses a null argument.
     *
     * <p>A COBOL caller cannot pass an absent field: the linkage area always exists and is always
     * the declared width. The Java translation therefore treats null as a programming error rather
     * than as a blank field, and says so in the message — an absent field and a blank field lead to
     * different outcomes, so conflating them would hide a caller's bug behind a plausible
     * validation failure.
     */
    @Nested
    @DisplayName("Mandatory arguments")
    class MandatoryArguments {

        @Test
        @DisplayName("the cascade refuses a null candidate")
        void theCascadeRefusesANullCandidate() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> service.validateCcyymmddDate(null))
                    .withMessageContaining("candidateDate must not be null")
                    .withMessageContaining("an absent field is not a blank field");
        }

        @Test
        @DisplayName("the cascade refuses a null starting message")
        void theCascadeRefusesANullMessage() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> service.validateCcyymmddDate("20240229", null))
                    .withMessageContaining("currentReturnMessage must not be null");
        }

        @Test
        @DisplayName("the date-of-birth range refuses a null candidate, date or message")
        void theBirthRangeRefusesNulls() {
            final LocalDate today = LocalDate.of(2024, 6, 15);

            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> service.validateDateOfBirth(null, today))
                    .withMessageContaining("candidateDate must not be null");
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> service.validateDateOfBirth("19800101", null))
                    .withMessage("currentDate must not be null");
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> service.validateDateOfBirth("19800101", today, null))
                    .withMessageContaining("currentReturnMessage must not be null");
        }

        @Test
        @DisplayName("the subprogram refuses a null candidate under either overload")
        void theSubprogramRefusesANullCandidate() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> service.validateDate(null, DateFormat.YYYYMMDD))
                    .withMessageContaining("candidateDate must not be null");
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> service.validateDate(null, "YYYYMMDD  "))
                    .withMessageContaining("candidateDate must not be null");
        }

        @Test
        @DisplayName("the subprogram refuses a null format under either overload")
        void theSubprogramRefusesANullFormat() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> service.validateDate("20240229", (DateFormat) null))
                    .withMessage("dateFormat must not be null");
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> service.validateDate("20240229", (String) null))
                    .withMessage("formatMask must not be null");
        }
    }
}
