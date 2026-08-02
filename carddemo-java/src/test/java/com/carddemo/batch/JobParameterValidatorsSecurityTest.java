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
package com.carddemo.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.carddemo.exception.ValidationException;
import com.carddemo.service.DateValidationService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.JobParametersInvalidException;
import org.springframework.batch.core.JobParametersValidator;

/**
 * Guards the launch-time diagnostics of {@link JobParameterValidators} against log forging.
 *
 * <p>Every message this class produces is composed from a value that arrived at launch time and that no
 * code in this module authored - a job parameter, or a line of a {@code DATEPARM} record. A batch
 * diagnostic is read after the fact, from a log file, by an operator deciding what happened; a value
 * that can end the log record early can therefore make one rejection appear as two entries, and the
 * operator has no way to tell which of them the job emitted. The recorded runtime proof of the defect
 * was a probe mode of {@code ACCT\nFORGED}, which produced a two-line exception message.
 *
 * <p>The fix guards the shared composer rather than the ten call sites that use it, so these tests are
 * written to prove two things at once: that no control character reaches any diagnostic through any of
 * the four public factories or the record parser, and that a <em>legitimate</em> value still renders
 * verbatim. The second half matters as much as the first. A guard that replaced every value with a
 * placeholder would also pass a forging test, while making every real batch failure unactionable - so
 * each group below pins the legible rendering alongside the safe one.
 *
 * <p>The real {@link DateValidationService} is used rather than a mock. It has a no-argument constructor
 * and takes every calendar decision this class delegates, so substituting a mock would prove only that
 * the validators call it, not that a hostile value is refused before its verdict is composed into a
 * message.
 */
@DisplayName("JobParameterValidators :: launch-time diagnostics cannot be forged")
class JobParameterValidatorsSecurityTest {

    /** A line feed: ends a log record, so text after it appears to be a separate entry. */
    private static final char LINE_FEED = '\n';

    /** A carriage return: returns the cursor, so text after it overwrites the record so far. */
    private static final char CARRIAGE_RETURN = '\r';

    /** A tab: a control character that is not a line terminator, kept distinct for that reason. */
    private static final char TAB = '\t';

    /** A NUL: truncates the record for any consumer that reads a C string. */
    private static final char NUL = '\0';

    /** An escape: can reposition a terminal cursor and overwrite records already written. */
    private static final char ESCAPE = 0x1B;

    /** A delete: the one control code above the printable range rather than below it. */
    private static final char DELETE = 0x7F;

    /** Every control character the guard must refuse, exercised one at a time. */
    private static final char[] CONTROL_CHARACTERS =
            {LINE_FEED, CARRIAGE_RETURN, TAB, NUL, ESCAPE, DELETE};

    /** Text a forged log record would carry, so a leak is visible rather than inferred. */
    private static final String FORGED = "FORGEDAUDITENTRY";

    /** A legitimate ten-digit interest parameter: an eight-digit date and two trailing digits. */
    private static final String VALID_INTEREST_PARM_DATE = "2022070600";

    /** A legitimate hyphenated ISO start bound. */
    private static final String VALID_START_DATE = "2022-01-01";

    /** A legitimate hyphenated ISO end bound. */
    private static final String VALID_END_DATE = "2022-07-06";

    /** A legitimate probe mode, and the only member of the legal set these tests configure. */
    private static final String VALID_PROBE_MODE = "ACCTFILE";

    /** The legal probe-mode enumeration a probe job would supply. */
    private static final List<String> LEGAL_PROBE_MODES = List.of(VALID_PROBE_MODE, "CARDFILE");

    /**
     * A legitimate {@code DATEPARM} record: ten characters, a space separator, ten more characters.
     */
    private static final String VALID_DATEPARM_RECORD = VALID_START_DATE + " " + VALID_END_DATE;

    private JobParameterValidators validators;

    @BeforeEach
    void setUp() {
        this.validators = new JobParameterValidators(new DateValidationService());
    }

    private static JobParameters oneParameter(final String key, final String value) {
        return new JobParametersBuilder().addString(key, value).toJobParameters();
    }

    private static JobParameters reportWindow(final String startDate, final String endDate) {
        return new JobParametersBuilder()
                .addString(JobParameterValidators.REPORT_START_DATE_KEY, startDate)
                .addString(JobParameterValidators.REPORT_END_DATE_KEY, endDate)
                .toJobParameters();
    }

    /**
     * Asserts that a diagnostic carries no raw control character and does not echo the forged marker.
     *
     * <p>Both halves are required. The control-character scan proves the record cannot be reframed; the
     * marker check proves the value was not echoed at all, which is what a scan alone would miss if the
     * guard stripped the control character and printed the rest.
     *
     * @param message the diagnostic to inspect
     */
    private static void assertIsSafeDiagnostic(final String message) {
        assertThat(message).as("a rejection must carry a message").isNotNull();
        for (int index = 0; index < message.length(); index++) {
            final char character = message.charAt(index);
            assertThat(Character.isISOControl(character))
                    .as("diagnostic must carry no raw control character, but position %d holds code"
                            + " point %d; message was [%s]", index, (int) character, message)
                    .isFalse();
        }
        assertThat(message)
                .as("a diagnostic must not echo the hostile payload")
                .doesNotContain(FORGED);
        assertThat(message)
                .as("a reframed diagnostic must name the defect it found")
                .contains("not printable US-ASCII");
    }

    @Nested
    @DisplayName("the file-probe mode validator")
    class TheFileProbeModeValidator {

        @Test
        @DisplayName("refuses the recorded runtime proof, a probe mode carrying a line feed, and its"
                + " diagnostic stays on one line")
        void refusesTheRecordedRuntimeProof() {
            // The exact value from the review's runtime proof. Before the fix this produced a two-line
            // exception message, the second line of which an operator would read as a separate event.
            final JobParametersValidator validator =
                    validators.fileProbeModeValidator(LEGAL_PROBE_MODES);
            final JobParameters parameters =
                    oneParameter(JobParameterValidators.FILE_PROBE_MODE_KEY, "ACCT\nFORGED");

            assertThatExceptionOfType(JobParametersInvalidException.class)
                    .isThrownBy(() -> validator.validate(parameters))
                    .satisfies(thrown -> {
                        assertThat(thrown.getMessage()).doesNotContain("\n").doesNotContain("\r");
                        assertThat(thrown.getMessage())
                                .contains("zero-based position 4")
                                .contains("code point " + (int) LINE_FEED);
                    });
        }

        @Test
        @DisplayName("refuses every control character, naming its position and code point rather than"
                + " the mode")
        void refusesEveryControlCharacter() {
            final JobParametersValidator validator =
                    validators.fileProbeModeValidator(LEGAL_PROBE_MODES);

            for (final char control : CONTROL_CHARACTERS) {
                final JobParameters parameters = oneParameter(
                        JobParameterValidators.FILE_PROBE_MODE_KEY, VALID_PROBE_MODE + control + FORGED);

                assertThatExceptionOfType(JobParametersInvalidException.class)
                        .as("code point %d must be refused in a probe mode", (int) control)
                        .isThrownBy(() -> validator.validate(parameters))
                        .satisfies(thrown -> {
                            final String message = thrown.getMessage();
                            assertThat(message)
                                    .contains("zero-based position " + VALID_PROBE_MODE.length())
                                    .contains("code point " + (int) control)
                                    .doesNotContain(FORGED);
                            for (int index = 0; index < message.length(); index++) {
                                assertThat(Character.isISOControl(message.charAt(index)))
                                        .as("message must hold no control character: [%s]", message)
                                        .isFalse();
                            }
                        });
            }
        }

        @Test
        @DisplayName("reports an invisible character as such rather than as a spelling mistake, which"
                + " is the difference between a correctable report and a misleading one")
        void reportsAnInvisibleCharacterAsSuchRatherThanAsASpellingMistake() {
            // The membership test alone would also reject this value, but it would report it as "not
            // one of the legal probe modes" and send an operator to check a spelling when the real
            // defect is a character that does not render. Pinning the more specific diagnostic keeps
            // the explicit representability check from being removed as redundant.
            final JobParametersValidator validator =
                    validators.fileProbeModeValidator(LEGAL_PROBE_MODES);
            final JobParameters parameters = oneParameter(
                    JobParameterValidators.FILE_PROBE_MODE_KEY, VALID_PROBE_MODE + TAB);

            assertThatExceptionOfType(JobParametersInvalidException.class)
                    .isThrownBy(() -> validator.validate(parameters))
                    .withMessageContaining("outside printable US-ASCII")
                    .withMessageNotContaining("is not one of the legal probe modes");
        }

        @Test
        @DisplayName("still names an unrecognised but printable mode verbatim, so the guard has not"
                + " cost the diagnostic its legibility")
        void stillNamesAnUnrecognisedButPrintableModeVerbatim() {
            final JobParametersValidator validator =
                    validators.fileProbeModeValidator(LEGAL_PROBE_MODES);
            final JobParameters parameters =
                    oneParameter(JobParameterValidators.FILE_PROBE_MODE_KEY, "CUSTFILE");

            assertThatExceptionOfType(JobParametersInvalidException.class)
                    .isThrownBy(() -> validator.validate(parameters))
                    .withMessageContaining("value [CUSTFILE]")
                    .withMessageContaining("is not one of the legal probe modes");
        }

        @Test
        @DisplayName("accepts a legal mode, so the guard refuses nothing legitimate")
        void acceptsALegalMode() {
            final JobParametersValidator validator =
                    validators.fileProbeModeValidator(LEGAL_PROBE_MODES);

            assertThatCode(() -> validator.validate(
                    oneParameter(JobParameterValidators.FILE_PROBE_MODE_KEY, VALID_PROBE_MODE)))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("refuses a configured legal mode carrying a control character at construction,"
                + " because an accepted mode is written to the log on the success path")
        void refusesAConfiguredLegalModeCarryingAControlCharacter() {
            // A control character in a configured mode name cannot be caught at the launch-parameter
            // boundary, because it did not arrive as a launch parameter. It would be logged by a
            // launch that succeeded, so it has to be refused where the enumeration is declared.
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> validators.fileProbeModeValidator(
                            List.of(VALID_PROBE_MODE + LINE_FEED + FORGED)))
                    .withMessageContaining("outside printable US-ASCII")
                    .withMessageContaining("code point " + (int) LINE_FEED)
                    .withMessageNotContaining(FORGED);
        }
    }

    @Nested
    @DisplayName("the interest-parameter date validator")
    class TheInterestParameterDateValidator {

        @Test
        @DisplayName("refuses every control character without echoing the parameter")
        void refusesEveryControlCharacterWithoutEchoingTheParameter() {
            final JobParametersValidator validator = validators.interestParmDateValidator();

            for (final char control : CONTROL_CHARACTERS) {
                final JobParameters parameters = oneParameter(
                        JobParameterValidators.INTEREST_PARM_DATE_KEY,
                        VALID_INTEREST_PARM_DATE + control + FORGED);

                assertThatExceptionOfType(JobParametersInvalidException.class)
                        .as("code point %d must be refused in the interest parameter", (int) control)
                        .isThrownBy(() -> validator.validate(parameters))
                        .satisfies(thrown -> assertIsSafeDiagnostic(thrown.getMessage()));
            }
        }

        @Test
        @DisplayName("refuses a control character that keeps the declared width, so width is not what"
                + " rejects it")
        void refusesAControlCharacterThatKeepsTheDeclaredWidth() {
            // A ten-byte value passes the width stage and reaches the digit stage, which is a different
            // call site of the shared composer. Exercising it proves the guard is at the composer and
            // not at one stage of the cascade.
            final JobParametersValidator validator = validators.interestParmDateValidator();
            final String sameWidth = "202207060" + LINE_FEED;
            final JobParameters parameters =
                    oneParameter(JobParameterValidators.INTEREST_PARM_DATE_KEY, sameWidth);

            assertThat(sameWidth.length()).as("the value must keep the declared width").isEqualTo(10);

            assertThatExceptionOfType(JobParametersInvalidException.class)
                    .isThrownBy(() -> validator.validate(parameters))
                    .satisfies(thrown -> {
                        assertThat(thrown.getMessage()).doesNotContain("\n");
                        assertThat(thrown.getMessage()).contains("not printable US-ASCII");
                    });
        }

        @Test
        @DisplayName("still names a printable but invalid parameter verbatim")
        void stillNamesAPrintableButInvalidParameterVerbatim() {
            final JobParametersValidator validator = validators.interestParmDateValidator();
            final JobParameters parameters =
                    oneParameter(JobParameterValidators.INTEREST_PARM_DATE_KEY, "2022139900");

            assertThatExceptionOfType(JobParametersInvalidException.class)
                    .isThrownBy(() -> validator.validate(parameters))
                    .withMessageContaining("value [2022139900]");
        }

        @Test
        @DisplayName("accepts a legitimate ten-digit parameter")
        void acceptsALegitimateTenDigitParameter() {
            final JobParametersValidator validator = validators.interestParmDateValidator();

            assertThatCode(() -> validator.validate(oneParameter(
                    JobParameterValidators.INTEREST_PARM_DATE_KEY, VALID_INTEREST_PARM_DATE)))
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("the report date-range validator")
    class TheReportDateRangeValidator {

        @Test
        @DisplayName("refuses every control character in the start bound")
        void refusesEveryControlCharacterInTheStartBound() {
            final JobParametersValidator validator = validators.reportDateRangeValidator();

            for (final char control : CONTROL_CHARACTERS) {
                final JobParameters parameters =
                        reportWindow(VALID_START_DATE + control + FORGED, VALID_END_DATE);

                assertThatExceptionOfType(JobParametersInvalidException.class)
                        .as("code point %d must be refused in the start bound", (int) control)
                        .isThrownBy(() -> validator.validate(parameters))
                        .satisfies(thrown -> assertIsSafeDiagnostic(thrown.getMessage()));
            }
        }

        @Test
        @DisplayName("refuses every control character in the end bound, so the second parameter is not"
                + " left unguarded by the first one's check")
        void refusesEveryControlCharacterInTheEndBound() {
            final JobParametersValidator validator = validators.reportDateRangeValidator();

            for (final char control : CONTROL_CHARACTERS) {
                final JobParameters parameters =
                        reportWindow(VALID_START_DATE, VALID_END_DATE + control + FORGED);

                assertThatExceptionOfType(JobParametersInvalidException.class)
                        .as("code point %d must be refused in the end bound", (int) control)
                        .isThrownBy(() -> validator.validate(parameters))
                        .satisfies(thrown -> assertIsSafeDiagnostic(thrown.getMessage()));
            }
        }

        @Test
        @DisplayName("renders both bounds verbatim in the window-ordering diagnostic, which is the one"
                + " message composed without the shared prefix")
        void rendersBothBoundsVerbatimInTheWindowOrderingDiagnostic() {
            // Both bounds have already passed the ISO cascade when this message is composed, so both
            // are printable and must render in full - the whole point of the ordering diagnostic is to
            // show the operator which way round the two dates were supplied.
            final JobParametersValidator validator = validators.reportDateRangeValidator();
            final JobParameters reversed = reportWindow(VALID_END_DATE, VALID_START_DATE);

            assertThatExceptionOfType(JobParametersInvalidException.class)
                    .isThrownBy(() -> validator.validate(reversed))
                    .withMessageContaining("value [" + VALID_END_DATE + "]")
                    .withMessageContaining("value [" + VALID_START_DATE + "]")
                    .withMessageContaining("the start must not be later than the end");
        }

        @Test
        @DisplayName("accepts an ordered window")
        void acceptsAnOrderedWindow() {
            final JobParametersValidator validator = validators.reportDateRangeValidator();

            assertThatCode(() ->
                    validator.validate(reportWindow(VALID_START_DATE, VALID_END_DATE)))
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("the DATEPARM record parser")
    class TheDateParmRecordParser {

        @Test
        @DisplayName("refuses every control character without echoing the record")
        void refusesEveryControlCharacterWithoutEchoingTheRecord() {
            for (final char control : CONTROL_CHARACTERS) {
                final String hostile = VALID_START_DATE + control + VALID_END_DATE;

                assertThatExceptionOfType(ValidationException.class)
                        .as("code point %d must be refused in a DATEPARM record", (int) control)
                        .isThrownBy(() -> validators.parseDateParmRecord(hostile))
                        .satisfies(thrown -> {
                            final String message = thrown.getMessage();
                            assertThat(message).as("a rejection must carry a message").isNotNull();
                            for (int index = 0; index < message.length(); index++) {
                                assertThat(Character.isISOControl(message.charAt(index)))
                                        .as("message must hold no control character: [%s]", message)
                                        .isFalse();
                            }
                        });
            }
        }

        @Test
        @DisplayName("refuses a control character carried inside a date field rather than at the"
                + " separator, so the whole record is scanned and not only its fixed positions")
        void refusesAControlCharacterCarriedInsideADateField() {
            final String hostile = "2022-01-0" + LINE_FEED + " " + VALID_END_DATE;

            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> validators.parseDateParmRecord(hostile))
                    .satisfies(thrown ->
                            assertThat(thrown.getMessage()).doesNotContain("\n").doesNotContain("\r"));
        }

        @Test
        @DisplayName("still names a printable but malformed record verbatim")
        void stillNamesAPrintableButMalformedRecordVerbatim() {
            final String malformed = "2022-13-99 2022-07-06";

            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> validators.parseDateParmRecord(malformed))
                    .satisfies(thrown -> assertThat(thrown.getMessage()).contains("2022-13-99"));
        }

        @Test
        @DisplayName("parses a legitimate record into its two bounds")
        void parsesALegitimateRecordIntoItsTwoBounds() {
            final JobParameterValidators.ReportDateWindow window =
                    validators.parseDateParmRecord(VALID_DATEPARM_RECORD);

            assertThat(window.startDate()).isEqualTo(VALID_START_DATE);
            assertThat(window.endDate()).isEqualTo(VALID_END_DATE);
        }
    }
}
