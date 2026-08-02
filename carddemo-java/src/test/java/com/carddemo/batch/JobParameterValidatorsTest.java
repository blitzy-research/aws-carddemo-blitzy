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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.JobParametersInvalidException;
import org.springframework.batch.core.JobParametersValidator;

import com.carddemo.exception.ValidationException;
import com.carddemo.service.DateValidationService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * Unit test for {@link JobParameterValidators}, the launch-time contract of the migrated batch jobs.
 *
 * <h2>What this test guards</h2>
 *
 * <p>Four launch surfaces and one control record. The interest job takes a ten-character parameter
 * date; the transaction report takes an inclusive two-bound window; the collapsed file-probe job
 * takes a mode naming which file to read; and the report pipeline can instead receive its window
 * from a twenty-one byte control record read out of a sequential dataset. Each has a measured
 * contract, and each rejects for a specific reason in a specific order.</p>
 *
 * <h2>Why the order of the checks is itself under test</h2>
 *
 * <p>Every cascade reports one failure and stops. That means a doubly-invalid value has exactly one
 * correct diagnostic, and which one it is depends on the order the stages run in. Two orderings are
 * load-bearing and are asserted directly rather than left implicit:</p>
 *
 * <ul>
 *   <li><strong>Width before calendar.</strong> The calendar authority moves its input into a
 *       fixed-width linkage field, and such a move silently discards the rightmost excess of an
 *       over-long sender. An eleven-character value delegated first would arrive as its own leading
 *       ten characters and be <em>accepted</em>. The width stage therefore has to run first, and the
 *       test pins the width diagnostic for an over-long value to prove it does.</li>
 *   <li><strong>Start bound before end bound.</strong> A launch supplying two bad bounds is told
 *       about the start bound, matching the legacy edits, whose first failing check short-circuits
 *       the rest.</li>
 * </ul>
 *
 * <h2>Every expected value is hand-authored</h2>
 *
 * <p>The widths, offsets and diagnostics below are written out as literals taken from the legacy
 * declarations and from the class's own documented contract; none is read back from the class under
 * test, and no expectation is produced by calling the method it is meant to check. The two authentic
 * legacy literals are used as the accepting fixtures rather than invented dates:
 * {@code PARM='2022071800'} at {@code [app/jcl/INTCALC.jcl:L22]}, and the window
 * {@code 2022-01-01} through {@code 2022-07-06} carried as sort symbols at
 * {@code [app/jcl/TRANREPT.jcl:L43]} and {@code [app/jcl/TRANREPT.jcl:L44]}.</p>
 *
 * <h2>How the evidence is obtained, and what that does and does not prove</h2>
 *
 * <p>The real {@link DateValidationService} is used rather than a stand-in. It is the strict calendar
 * authority the class delegates every calendar decision to, and substituting a stub would leave the
 * delegation itself unproven &mdash; the interesting failures here are precisely the ones where the
 * authority's verdict decides the outcome, such as the leap-year pair below. Its own cascade is
 * covered by its own suites; what this file proves is that this class consults it, at the right
 * stage, and reports its verdict without re-deriving it.</p>
 *
 * <p>This proves the launch contract in isolation. It does <strong>not</strong> prove that any job
 * configuration attaches these validators: at this checkout no other production source references
 * this class, so the wiring has no code to be asserted against. That is a delivery gap recorded
 * outside this file, not something a unit test can close.</p>
 *
 * <h2>Provenance</h2>
 *
 * <p>Legacy estate at checkout SHA 7756d895ffeb65f7ea72aaa609e356d9899afcec, upstream release stamp
 * CardDemo_v1.0-15-g27d6c6f-68 dated 2022-07-19. The layout of the control record is
 * {@code WS-START-DATE PIC X(10)}, {@code FILLER PIC X(01)}, {@code WS-END-DATE PIC X(10)} at
 * {@code [app/cbl/CBTRN03C.cbl:L123]} through {@code [app/cbl/CBTRN03C.cbl:L125]}; the interest
 * parameter is {@code PARM-DATE PIC X(10)} at {@code [app/cbl/CBACT04C.cbl:L178]}; the report filter
 * is {@code INCLUDE COND=(TRAN-PROC-DT,GE,PARM-START-DATE,AND,TRAN-PROC-DT,LE,PARM-END-DATE)} at
 * {@code [app/jcl/TRANREPT.jcl:L47]}, which is where both bounds being inclusive is measured from.</p>
 */
@DisplayName("JobParameterValidators :: the launch-time contract of the migrated batch jobs")
class JobParameterValidatorsTest {

    // =============================================================================================
    // Independently restated launch keys. These repeat the published spellings as plain literals so
    // that a rename of a key is a visible, deliberate change rather than something the test follows
    // silently.
    // =============================================================================================

    /** Key of the interest-calculation parameter date. */
    private static final String KEY_INTEREST = "interestParmDate";

    /** Key of the inclusive lower bound of the report window. */
    private static final String KEY_REPORT_START = "reportStartDate";

    /** Key of the inclusive upper bound of the report window. */
    private static final String KEY_REPORT_END = "reportEndDate";

    /** Key of the file-probe mode. */
    private static final String KEY_FILE_PROBE_MODE = "fileProbeMode";

    // =============================================================================================
    // Independently restated widths and offsets, every one read from a picture clause or a sort
    // symbol declaration. These are byte reservations, not tuning figures.
    // =============================================================================================

    /** Width of {@code PARM-DATE PIC X(10)} at [app/cbl/CBACT04C.cbl:L178]. */
    private static final int INTEREST_WIDTH = 10;

    /** How many of those ten positions the compact mask interprets as a calendar date. */
    private static final int INTEREST_CALENDAR_WIDTH = 8;

    /** Width of a hyphenated ISO date, and of both report sort symbols. */
    private static final int ISO_WIDTH = 10;

    /** Significant width of the control record: ten, one separator, ten. */
    private static final int DATEPARM_WIDTH = 21;

    /** One-based position of the control record's separator, as the diagnostic reports it. */
    private static final int SEPARATOR_POSITION = 11;

    /** Width of the record area the control record is read into, at [app/cbl/CBTRN03C.cbl:L88]. */
    private static final int RECORD_IMAGE_WIDTH = 80;

    // =============================================================================================
    // The authentic legacy literals, used as the accepting fixtures.
    // =============================================================================================

    /** The interest parameter the job stream actually supplied, at [app/jcl/INTCALC.jcl:L22]. */
    private static final String LEGACY_INTEREST_PARM = "2022071800";

    /** The report window's lower bound, at [app/jcl/TRANREPT.jcl:L43]. */
    private static final String LEGACY_WINDOW_START = "2022-01-01";

    /** The report window's upper bound, at [app/jcl/TRANREPT.jcl:L44]. */
    private static final String LEGACY_WINDOW_END = "2022-07-06";

    // =============================================================================================
    // Diagnostic labels the control-record route attributes failures to. These are record positions
    // rather than job parameter keys, because no parameter is being validated on that route.
    // =============================================================================================

    /** Label of the control record as a whole. */
    private static final String LABEL_RECORD = "DATEPARM record";

    /** Label of the control record's start-date position. */
    private static final String LABEL_RECORD_START = "DATEPARM record start date";

    /** Label of the control record's end-date position. */
    private static final String LABEL_RECORD_END = "DATEPARM record end date";

    // =============================================================================================
    // The probe modes. The four collapsed legacy job streams were READACCT, READCARD, READCUST and
    // READXREF, so these are the files they read. The enumeration belongs to the probe job
    // configuration rather than to the class under test, which is why the test supplies it too.
    // =============================================================================================

    /** The legal probe modes, in report order. */
    private static final List<String> PROBE_MODES =
            List.of("ACCTFILE", "CARDFILE", "CUSTFILE", "XREFFILE");

    /** How the diagnostics render that enumeration, which is a stable insertion order. */
    private static final String PROBE_MODES_RENDERED = "[ACCTFILE, CARDFILE, CUSTFILE, XREFFILE]";

    /** A character that cannot occupy one byte, used to reach the representability stage. */
    private static final String NON_SINGLE_BYTE = "\u20ac";

    /** The subject, built with the real calendar authority. */
    private final JobParameterValidators validators =
            new JobParameterValidators(new DateValidationService());

    /**
     * Renders the common prefix of a diagnostic that names a parameter and its offending value.
     *
     * <p>Restated here rather than borrowed, so a change to the diagnostic shape has to be made in
     * two places deliberately instead of following silently in one.
     *
     * @param name  the parameter key or record-position label
     * @param value the offending value
     * @return the prefix, ending in the space a reason follows
     */
    private static String describe(final String name, final String value) {
        return "Job parameter [" + name + "] value [" + value + "] ";
    }

    /**
     * Composes the diagnostic prefix for a value the composer declines to echo.
     *
     * <p>Every diagnostic this class emits passes its caller-supplied fragments through a guard that
     * echoes printable US-ASCII unchanged and substitutes a description otherwise. The substitution is
     * a log-injection control: a value carrying a control character or a line terminator could
     * otherwise reframe the batch log, so a value that is not printable is replaced by the two facts
     * that make the defect correctable - the zero-based position of the first offending character and
     * its code point - and by nothing else. Neither fact can be used to forge a record, and together
     * they identify exactly one character.</p>
     *
     * <p>Tests use this helper rather than {@link #describe(String, String)} whenever the fixture is
     * deliberately unprintable, so the assertion states the substituted form the guard produces
     * instead of the raw echo it refuses to make. The reason clause that follows is unaffected: the
     * guard changes how the offending value is named, never what the refusal says about it.</p>
     *
     * @param name           the parameter key or record-position label
     * @param offendingIndex zero-based position of the first character outside printable US-ASCII
     * @param codePoint      code point of that character
     * @return the prefix, ending in a space so a reason can follow directly
     */
    private static String describeUnprintable(final String name, final int offendingIndex,
            final int codePoint) {
        return "Job parameter [" + name + "] value [value not printable US-ASCII: the character at"
                + " zero-based position " + offendingIndex + " is code point " + codePoint + "] ";
    }

    /**
     * Builds a parameter set carrying one string parameter, or an empty set when the value is absent.
     *
     * @param key   the parameter key
     * @param value the parameter value, or {@code null} to omit the parameter entirely
     * @return the parameter set
     */
    private static JobParameters parameters(final String key, final String value) {
        final JobParametersBuilder builder = new JobParametersBuilder();
        if (value != null) {
            builder.addString(key, value);
        }
        return builder.toJobParameters();
    }

    /**
     * Builds a parameter set carrying the two report bounds, omitting either when it is absent.
     *
     * @param startDate the start bound, or {@code null} to omit it
     * @param endDate   the end bound, or {@code null} to omit it
     * @return the parameter set
     */
    private static JobParameters reportParameters(final String startDate, final String endDate) {
        final JobParametersBuilder builder = new JobParametersBuilder();
        if (startDate != null) {
            builder.addString(KEY_REPORT_START, startDate);
        }
        if (endDate != null) {
            builder.addString(KEY_REPORT_END, endDate);
        }
        return builder.toJobParameters();
    }

    /**
     * Runs a validator and returns the failure it raised, failing the test when it accepted.
     *
     * @param validator     the validator to exercise
     * @param jobParameters the parameter set to offer it
     * @return the raised failure, never {@code null}
     */
    private static JobParametersInvalidException rejectionOf(final JobParametersValidator validator,
                                                            final JobParameters jobParameters) {
        final JobParametersInvalidException thrown = catchThrowableOfType(
                JobParametersInvalidException.class, () -> validator.validate(jobParameters));
        assertThat(thrown).as("the parameter set was expected to be rejected, but was accepted")
                .isNotNull();
        return thrown;
    }

    /**
     * Pads a control record out to the eighty-byte area it is read into.
     *
     * @param significant the twenty-one significant bytes
     * @param padding     the character the area carries beyond the content
     * @return the padded image
     */
    private static String paddedRecord(final String significant, final char padding) {
        return significant + String.valueOf(padding).repeat(RECORD_IMAGE_WIDTH - significant.length());
    }

    /**
     * Composes a control record from two bounds and the separator the layout requires.
     *
     * @param startDate the start-date position
     * @param endDate   the end-date position
     * @return the twenty-one byte significant content
     */
    private static String record(final String startDate, final String endDate) {
        return startDate + " " + endDate;
    }

    @Nested
    @DisplayName("construction: the calendar authority is mandatory")
    final class Construction {

        @Test
        @DisplayName("a null calendar authority is refused, because every calendar decision is delegated")
        void aNullCalendarAuthorityIsRefused() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new JobParameterValidators(null))
                    .withMessageContaining("dateValidationService must not be null");
        }

        @Test
        @DisplayName("each accessor hands back a usable validator rather than null")
        void eachAccessorHandsBackAUsableValidator() {
            assertThat(validators.interestParmDateValidator()).isNotNull();
            assertThat(validators.reportDateRangeValidator()).isNotNull();
            assertThat(validators.fileProbeModeValidator(PROBE_MODES)).isNotNull();
        }
    }

    @Nested
    @DisplayName("the published launch keys")
    final class TheLaunchKeys {

        @Test
        @DisplayName("each key carries the spelling the launch surface publishes")
        void eachKeyCarriesItsPublishedSpelling() {
            assertThat(JobParameterValidators.INTEREST_PARM_DATE_KEY).isEqualTo(KEY_INTEREST);
            assertThat(JobParameterValidators.REPORT_START_DATE_KEY).isEqualTo(KEY_REPORT_START);
            assertThat(JobParameterValidators.REPORT_END_DATE_KEY).isEqualTo(KEY_REPORT_END);
            assertThat(JobParameterValidators.FILE_PROBE_MODE_KEY).isEqualTo(KEY_FILE_PROBE_MODE);
        }

        @Test
        @DisplayName("the four keys are distinct, so no launch value can be read under two names")
        void theFourKeysAreDistinct() {
            assertThat(List.of(JobParameterValidators.INTEREST_PARM_DATE_KEY,
                            JobParameterValidators.REPORT_START_DATE_KEY,
                            JobParameterValidators.REPORT_END_DATE_KEY,
                            JobParameterValidators.FILE_PROBE_MODE_KEY))
                    .doesNotHaveDuplicates()
                    .hasSize(4);
        }
    }

    @Nested
    @DisplayName("the interest parameter date: ten digits whose leading eight are a calendar date")
    final class TheInterestParameterDate {

        @Test
        @DisplayName("the literal the legacy job stream actually supplied is accepted")
        void theLiteralTheLegacyJobStreamSuppliedIsAccepted() {
            assertThat(LEGACY_INTEREST_PARM)
                    .as("the fixture must be the measured legacy literal at its declared width")
                    .hasSize(INTEREST_WIDTH);

            assertThat(catchThrowableOfType(JobParametersInvalidException.class,
                    () -> validators.interestParmDateValidator()
                            .validate(parameters(KEY_INTEREST, LEGACY_INTEREST_PARM))))
                    .as("the authentic legacy parameter must be accepted")
                    .isNull();
        }

        @Test
        @DisplayName("the trailing two positions are outside the calendar interpretation, so any "
                + "digits may fill them")
        void theTrailingTwoPositionsAreOutsideTheCalendarInterpretation() {
            // The leading eight are the calendar date; the remaining two fill the rest of the
            // ten-character linkage field and are not interpreted. A value whose trailing pair is
            // nonsense as a date component must therefore still be accepted.
            final String trailingNines = "2022071899";

            assertThat(trailingNines.substring(0, INTEREST_CALENDAR_WIDTH))
                    .as("the fixture's calendar portion must be the valid part")
                    .isEqualTo("20220718");

            assertThat(catchThrowableOfType(JobParametersInvalidException.class,
                    () -> validators.interestParmDateValidator()
                            .validate(parameters(KEY_INTEREST, trailingNines))))
                    .as("the uninterpreted trailing positions must not affect acceptance")
                    .isNull();
        }

        @Test
        @DisplayName("the calendar authority decides leap years, so the twenty-ninth of February is "
                + "accepted in a leap year and refused in a common year")
        void theCalendarAuthorityDecidesLeapYears() {
            // The most direct evidence that the verdict is delegated rather than re-derived: the two
            // values differ only in the year, and nothing in this class knows the leap rule.
            assertThat(catchThrowableOfType(JobParametersInvalidException.class,
                    () -> validators.interestParmDateValidator()
                            .validate(parameters(KEY_INTEREST, "2024022900"))))
                    .as("the twenty-ninth of February 2024 is a real date and must be accepted")
                    .isNull();

            assertThat(rejectionOf(validators.interestParmDateValidator(),
                    parameters(KEY_INTEREST, "2023022900")))
                    .hasMessage(describe(KEY_INTEREST, "2023022900")
                            + "does not carry a valid calendar date in its leading "
                            + INTEREST_CALENDAR_WIDTH + " positions");
        }

        @Test
        @DisplayName("an absent parameter is reported as required, naming what it drives")
        void anAbsentParameterIsReportedAsRequired() {
            assertThat(rejectionOf(validators.interestParmDateValidator(),
                    parameters(KEY_INTEREST, null)))
                    .hasMessage("Job parameter [" + KEY_INTEREST + "] is required but was not"
                            + " supplied; the interest calculation is driven by it");
        }

        @Test
        @DisplayName("an over-long value is refused for its width, not silently truncated into a "
                + "valid date")
        void anOverLongValueIsRefusedForItsWidth() {
            // The ordering assertion. Were the calendar stage to run first, the eleven characters
            // would be moved into a ten-character field, arrive as "2022071800", and be accepted.
            final String overLong = LEGACY_INTEREST_PARM + "0";

            assertThat(overLong.substring(0, INTEREST_WIDTH))
                    .as("the fixture's leading ten characters must be a value that would be accepted,"
                            + " or this test would not detect a truncating delegation")
                    .isEqualTo(LEGACY_INTEREST_PARM);

            assertThat(rejectionOf(validators.interestParmDateValidator(),
                    parameters(KEY_INTEREST, overLong)))
                    .hasMessage(describe(KEY_INTEREST, overLong) + "must be exactly " + INTEREST_WIDTH
                            + " encoded bytes to fill the parameter date field, but measures "
                            + overLong.length());
        }

        @ParameterizedTest(name = "the {0}-character value [{1}] is refused for its width")
        @CsvSource({
            "7, 2022071",
            "8, 20220718",
            "9, 202207180",
            "11, 20220718000",
        })
        @DisplayName("only exactly ten encoded bytes fill the parameter field")
        void onlyExactlyTenEncodedBytesFillTheParameterField(final int width, final String value) {
            assertThat(value).hasSize(width);

            assertThat(rejectionOf(validators.interestParmDateValidator(),
                    parameters(KEY_INTEREST, value)))
                    .hasMessage(describe(KEY_INTEREST, value) + "must be exactly " + INTEREST_WIDTH
                            + " encoded bytes to fill the parameter date field, but measures " + width);
        }

        @Test
        @DisplayName("a hyphenated ISO date is refused, because this parameter is ten digits and "
                + "carries no separators")
        void aHyphenatedIsoDateIsRefused() {
            // The two forms are the same width, so only the digit stage can tell them apart. This is
            // the mistake a reader of the key name is most likely to make.
            final String hyphenated = "2022-07-18";

            assertThat(hyphenated).hasSize(INTEREST_WIDTH);

            assertThat(rejectionOf(validators.interestParmDateValidator(),
                    parameters(KEY_INTEREST, hyphenated)))
                    .hasMessage(describe(KEY_INTEREST, hyphenated) + "must be " + INTEREST_WIDTH
                            + " digits with no separators; it is not a hyphenated ISO date");
        }

        @Test
        @DisplayName("a value carrying a character that cannot occupy one byte is refused before it "
                + "is ever encoded")
        void aValueOutsideTheSingleByteSetIsRefused() {
            // Refused before encoding on purpose: encoding first would substitute a replacement byte
            // and yield a value of the right width holding the wrong content.
            final String euroBearing = "20220718" + NON_SINGLE_BYTE + "0";

            assertThat(euroBearing)
                    .as("the fixture must be ten characters, so only representability can reject it")
                    .hasSize(INTEREST_WIDTH);

            assertThat(rejectionOf(validators.interestParmDateValidator(),
                    parameters(KEY_INTEREST, euroBearing)))
                    .as("the offending value is described rather than echoed, because echoing a "
                            + "value that is not printable would let it reframe the batch log")
                    .hasMessage(describeUnprintable(KEY_INTEREST, 8, 0x20ac)
                            + "carries a character outside the single-byte character set the parameter"
                            + " field reserves bytes for, so it cannot occupy the declared positions");
        }

        @Test
        @DisplayName("an all-zero value is refused, because year zero is not a calendar year")
        void anAllZeroValueIsRefused() {
            final String allZeroes = "0".repeat(INTEREST_WIDTH);

            assertThat(rejectionOf(validators.interestParmDateValidator(),
                    parameters(KEY_INTEREST, allZeroes)))
                    .hasMessage(describe(KEY_INTEREST, allZeroes)
                            + "does not carry a valid calendar date in its leading "
                            + INTEREST_CALENDAR_WIDTH + " positions");
        }

        @Test
        @DisplayName("an absent parameter set is reported through the framework contract rather than "
                + "as an unchecked failure out of a validator")
        void anAbsentParameterSetIsReportedThroughTheFrameworkContract() {
            assertThatExceptionOfType(JobParametersInvalidException.class)
                    .isThrownBy(() -> validators.interestParmDateValidator().validate(null))
                    .withMessage("No job parameters were supplied, so the launch-time contract of this"
                            + " job cannot be checked");
        }
    }

    @Nested
    @DisplayName("the report date range: two inclusive bounds in hyphenated ISO form")
    final class TheReportDateRange {

        @Test
        @DisplayName("the window the legacy sort symbols actually carried is accepted")
        void theWindowTheLegacySortSymbolsCarriedIsAccepted() {
            assertThat(LEGACY_WINDOW_START).hasSize(ISO_WIDTH);
            assertThat(LEGACY_WINDOW_END).hasSize(ISO_WIDTH);

            assertThat(catchThrowableOfType(JobParametersInvalidException.class,
                    () -> validators.reportDateRangeValidator()
                            .validate(reportParameters(LEGACY_WINDOW_START, LEGACY_WINDOW_END))))
                    .as("the authentic legacy window must be accepted")
                    .isNull();
        }

        @Test
        @DisplayName("a window whose start equals its end is accepted, because both bounds are "
                + "inclusive and it selects that one day")
        void aWindowWhoseStartEqualsItsEndIsAccepted() {
            // Measured from the sort filter, which tests greater-or-equal against the start and
            // less-or-equal against the end. An exclusive bound would make this select nothing.
            assertThat(catchThrowableOfType(JobParametersInvalidException.class,
                    () -> validators.reportDateRangeValidator()
                            .validate(reportParameters(LEGACY_WINDOW_START, LEGACY_WINDOW_START))))
                    .as("a single-day window must be accepted")
                    .isNull();
        }

        @Test
        @DisplayName("a start that follows the end is refused, and the diagnostic names both bounds")
        void aStartThatFollowsTheEndIsRefused() {
            assertThat(rejectionOf(validators.reportDateRangeValidator(),
                    reportParameters(LEGACY_WINDOW_END, LEGACY_WINDOW_START)))
                    .hasMessage("Job parameter [" + KEY_REPORT_START + "] value [" + LEGACY_WINDOW_END
                            + "] follows [" + KEY_REPORT_END + "] value [" + LEGACY_WINDOW_START
                            + "]; the report window is filtered with an inclusive lower and an"
                            + " inclusive upper bound, so the start must not be later than the end");
        }

        @Test
        @DisplayName("either absent bound is reported as required, naming that the window needs both")
        void eitherAbsentBoundIsReportedAsRequired() {
            assertThat(rejectionOf(validators.reportDateRangeValidator(),
                    reportParameters(null, LEGACY_WINDOW_END)))
                    .hasMessage("Job parameter [" + KEY_REPORT_START + "] is required but was not"
                            + " supplied; the report window needs both of its bounds");

            assertThat(rejectionOf(validators.reportDateRangeValidator(),
                    reportParameters(LEGACY_WINDOW_START, null)))
                    .hasMessage("Job parameter [" + KEY_REPORT_END + "] is required but was not"
                            + " supplied; the report window needs both of its bounds");
        }

        @Test
        @DisplayName("when both bounds are bad the start bound is reported, because the cascade "
                + "short-circuits exactly as the legacy edits did")
        void whenBothBoundsAreBadTheStartBoundIsReported() {
            // The ordering assertion for this cascade. Both values below are invalid, and only one
            // diagnostic may be produced; it must be the start bound's.
            final JobParametersInvalidException thrown = rejectionOf(
                    validators.reportDateRangeValidator(), reportParameters("2022-13-01", "2022-02-30"));

            assertThat(thrown)
                    .hasMessage(describe(KEY_REPORT_START, "2022-13-01")
                            + "is not a valid calendar date in the form YYYY-MM-DD");
            assertThat(thrown.getMessage())
                    .as("the end bound must not be mentioned: only one failure is reported")
                    .doesNotContain(KEY_REPORT_END)
                    .doesNotContain("2022-02-30");
        }

        @Test
        @DisplayName("an impossible day in a real month is refused on the end bound too")
        void anImpossibleDayIsRefusedOnTheEndBoundToo() {
            assertThat(rejectionOf(validators.reportDateRangeValidator(),
                    reportParameters(LEGACY_WINDOW_START, "2022-02-30")))
                    .hasMessage(describe(KEY_REPORT_END, "2022-02-30")
                            + "is not a valid calendar date in the form YYYY-MM-DD");
        }

        @ParameterizedTest(name = "the malformed bound [{0}] is refused for its width, measuring {1}")
        @CsvSource({
            "2022-1-1, 8",
            "22-07-06, 8",
            "2022-07-006, 11",
        })
        @DisplayName("a bound that is not exactly ten encoded bytes is refused for its width")
        void aBoundThatIsNotTenEncodedBytesIsRefusedForItsWidth(final String value, final int width) {
            assertThat(value).hasSize(width);

            assertThat(rejectionOf(validators.reportDateRangeValidator(),
                    reportParameters(value, LEGACY_WINDOW_END)))
                    .hasMessage(describe(KEY_REPORT_START, value) + "must be exactly " + ISO_WIDTH
                            + " encoded bytes in the form YYYY-MM-DD, but measures " + width);
        }

        @ParameterizedTest(name = "the ten-character but non-ISO bound [{0}] is refused as a calendar date")
        @ValueSource(strings = {"2022/07/06", "2022.07.06", "20220706AB", "2022-07-XX"})
        @DisplayName("a bound of the right width but the wrong shape is refused by the calendar "
                + "authority, which is what resolves the separator")
        void aBoundOfTheRightWidthButWrongShapeIsRefused(final String value) {
            // No separator test is written in the class under test: the authority resolves strictly
            // against the hyphenated mask, so a wrong separator, a missing one and a non-digit in a
            // digit position are all its verdict rather than a restated rule.
            assertThat(value).hasSize(ISO_WIDTH);

            assertThat(rejectionOf(validators.reportDateRangeValidator(),
                    reportParameters(value, LEGACY_WINDOW_END)))
                    .hasMessage(describe(KEY_REPORT_START, value)
                            + "is not a valid calendar date in the form YYYY-MM-DD");
        }

        @Test
        @DisplayName("a bound carrying a character outside the single-byte set is refused before "
                + "encoding")
        void aBoundOutsideTheSingleByteSetIsRefused() {
            final String euroBearing = "2022-07-0" + NON_SINGLE_BYTE;

            assertThat(euroBearing).hasSize(ISO_WIDTH);

            assertThat(rejectionOf(validators.reportDateRangeValidator(),
                    reportParameters(euroBearing, LEGACY_WINDOW_END)))
                    .as("the offending value is described rather than echoed")
                    .hasMessage(describeUnprintable(KEY_REPORT_START, 9, 0x20ac)
                            + "carries a character outside the single-byte character set the field"
                            + " reserves bytes for, so it cannot occupy the declared positions");
        }

        @Test
        @DisplayName("an absent parameter set is reported through the framework contract")
        void anAbsentParameterSetIsReportedThroughTheFrameworkContract() {
            assertThatExceptionOfType(JobParametersInvalidException.class)
                    .isThrownBy(() -> validators.reportDateRangeValidator().validate(null))
                    .withMessage("No job parameters were supplied, so the launch-time contract of this"
                            + " job cannot be checked");
        }
    }

    @Nested
    @DisplayName("the file-probe mode: exact, case-sensitive membership of the owner's enumeration")
    final class TheFileProbeMode {

        @Test
        @DisplayName("a legal mode is accepted")
        void aLegalModeIsAccepted() {
            final JobParametersValidator validator = validators.fileProbeModeValidator(PROBE_MODES);

            for (final String mode : PROBE_MODES) {
                assertThat(catchThrowableOfType(JobParametersInvalidException.class,
                        () -> validator.validate(parameters(KEY_FILE_PROBE_MODE, mode))))
                        .as("the legal mode [%s] must be accepted", mode)
                        .isNull();
            }
        }

        @Test
        @DisplayName("matching is case sensitive, so a near miss is reported rather than guessed at")
        void matchingIsCaseSensitive() {
            assertThat(rejectionOf(validators.fileProbeModeValidator(PROBE_MODES),
                    parameters(KEY_FILE_PROBE_MODE, "acctfile")))
                    .hasMessage(describe(KEY_FILE_PROBE_MODE, "acctfile")
                            + "is not one of the legal probe modes " + PROBE_MODES_RENDERED
                            + "; matching is exact and case sensitive");
        }

        @Test
        @DisplayName("an absent mode is reported as required, and the diagnostic lists the legal "
                + "values in the owner's declaration order")
        void anAbsentModeIsReportedAsRequired() {
            assertThat(rejectionOf(validators.fileProbeModeValidator(PROBE_MODES),
                    parameters(KEY_FILE_PROBE_MODE, null)))
                    .hasMessage("Job parameter [" + KEY_FILE_PROBE_MODE + "] is required but was not"
                            + " supplied; it selects which file the probe job reads, and the legal"
                            + " values are " + PROBE_MODES_RENDERED);
        }

        @ParameterizedTest(name = "the blank mode [{0}] is refused as blank")
        @ValueSource(strings = {"", " ", "   "})
        @DisplayName("a blank mode is refused as blank rather than as a failed match, because the "
                + "two are different launch mistakes")
        void aBlankModeIsRefusedAsBlank(final String value) {
            assertThat(rejectionOf(validators.fileProbeModeValidator(PROBE_MODES),
                    parameters(KEY_FILE_PROBE_MODE, value)))
                    .hasMessage(describe(KEY_FILE_PROBE_MODE, value)
                            + "is blank; the legal values are " + PROBE_MODES_RENDERED);
        }

        /**
         * A tab is blank for the purpose of the check and unprintable for the purpose of the echo.
         *
         * <p>It is separated from the three space-only cases above because the two facts about it are
         * independent and both matter. It is refused for the same reason they are - the value selects
         * nothing - so the reason clause is identical; but a tab is a control character, so the guard
         * substitutes a description of it rather than writing it into the record. Keeping it in the
         * parameterised set would have forced that set to carry two different expected messages, which
         * would have obscured that the refusal itself is the same one.</p>
         */
        @Test
        @DisplayName("a tab-only mode is refused as blank, and the tab is described rather than "
                + "written into the diagnostic")
        void aTabOnlyModeIsRefusedAsBlankAndDescribedRatherThanEchoed() {
            assertThat(rejectionOf(validators.fileProbeModeValidator(PROBE_MODES),
                    parameters(KEY_FILE_PROBE_MODE, "\t")))
                    .hasMessage(describeUnprintable(KEY_FILE_PROBE_MODE, 0, '\t')
                            + "is blank; the legal values are " + PROBE_MODES_RENDERED);
        }

        @Test
        @DisplayName("the enumeration is copied defensively, so mutating the caller's collection "
                + "afterwards cannot widen what the validator accepts")
        void theEnumerationIsCopiedDefensively() {
            final List<String> mutable = new ArrayList<>(PROBE_MODES);
            final JobParametersValidator validator = validators.fileProbeModeValidator(mutable);

            mutable.add("SMUGGLED");

            assertThat(rejectionOf(validator, parameters(KEY_FILE_PROBE_MODE, "SMUGGLED")))
                    .as("a value added after configuration must not become acceptable")
                    .hasMessage(describe(KEY_FILE_PROBE_MODE, "SMUGGLED")
                            + "is not one of the legal probe modes " + PROBE_MODES_RENDERED
                            + "; matching is exact and case sensitive");
        }

        @Test
        @DisplayName("a null enumeration is refused at configuration time, where the mistake was made")
        void aNullEnumerationIsRefusedAtConfigurationTime() {
            assertThatNullPointerException()
                    .isThrownBy(() -> validators.fileProbeModeValidator(null))
                    .withMessageContaining("legalModeNames must not be null");
        }

        @Test
        @DisplayName("an empty enumeration is refused at configuration time, because the validator "
                + "it produced would reject every launch")
        void anEmptyEnumerationIsRefusedAtConfigurationTime() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> validators.fileProbeModeValidator(List.of()))
                    .withMessage("legalModeNames must not be empty, because a validator built from an"
                            + " empty set would reject every launch");
        }

        @Test
        @DisplayName("an enumeration carrying a null or blank entry is refused at configuration "
                + "time, because no launch could ever match one")
        void anEnumerationCarryingANullOrBlankEntryIsRefused() {
            for (final List<String> illegal : List.of(
                    Arrays.asList("ACCTFILE", null),
                    Arrays.asList("ACCTFILE", ""),
                    Arrays.asList("ACCTFILE", "   "))) {
                assertThatIllegalArgumentException()
                        .as("the enumeration %s must be refused", illegal)
                        .isThrownBy(() -> validators.fileProbeModeValidator(illegal))
                        .withMessage("legalModeNames must not carry a null or blank entry, because no"
                                + " launch could ever match one");
            }
        }

        @Test
        @DisplayName("an absent parameter set is reported through the framework contract")
        void anAbsentParameterSetIsReportedThroughTheFrameworkContract() {
            assertThatExceptionOfType(JobParametersInvalidException.class)
                    .isThrownBy(() -> validators.fileProbeModeValidator(PROBE_MODES).validate(null))
                    .withMessage("No job parameters were supplied, so the launch-time contract of this"
                            + " job cannot be checked");
        }
    }

    @Nested
    @DisplayName("the control record: twenty-one significant bytes read from a sequential dataset")
    final class TheControlRecord {

        @Test
        @DisplayName("the layout is ten characters, one separator and ten characters, which is "
                + "twenty-one significant bytes")
        void theLayoutIsTenOneTen() {
            // Restated arithmetic rather than a borrowed constant: the group is WS-START-DATE X(10),
            // FILLER X(01), WS-END-DATE X(10).
            assertThat(ISO_WIDTH + 1 + ISO_WIDTH).isEqualTo(DATEPARM_WIDTH);
            assertThat(record(LEGACY_WINDOW_START, LEGACY_WINDOW_END)).hasSize(DATEPARM_WIDTH);
        }

        @Test
        @DisplayName("a record carrying exactly its significant content yields the window it holds")
        void aRecordCarryingExactlyItsSignificantContentYieldsItsWindow() {
            final JobParameterValidators.ReportDateWindow window = validators.parseDateParmRecord(
                    record(LEGACY_WINDOW_START, LEGACY_WINDOW_END));

            assertThat(window.startDate()).isEqualTo(LEGACY_WINDOW_START);
            assertThat(window.endDate()).isEqualTo(LEGACY_WINDOW_END);
        }

        @ParameterizedTest(name = "an eighty-byte image padded with {0} is accepted")
        @CsvSource({"spaces, ' '", "low values, '\u0000'"})
        @DisplayName("the eighty-byte record area is accepted whichever filler it carries beyond "
                + "its content, because both are padding rather than data")
        void theEightyByteImageIsAcceptedWithEitherFiller(final String label, final char padding) {
            final String image = paddedRecord(record(LEGACY_WINDOW_START, LEGACY_WINDOW_END), padding);

            assertThat(image)
                    .as("the fixture must be the full record area, padded with %s", label)
                    .hasSize(RECORD_IMAGE_WIDTH);

            final JobParameterValidators.ReportDateWindow window = validators.parseDateParmRecord(image);

            assertThat(window.startDate()).isEqualTo(LEGACY_WINDOW_START);
            assertThat(window.endDate()).isEqualTo(LEGACY_WINDOW_END);
        }

        @Test
        @DisplayName("an absent record is reported as missing rather than invalid, because nothing "
                + "was supplied to be wrong")
        void anAbsentRecordIsReportedAsMissing() {
            final ValidationException thrown = catchThrowableOfType(ValidationException.class,
                    () -> validators.parseDateParmRecord(null));

            assertThat(thrown).isNotNull();
            assertThat(thrown).hasMessage(LABEL_RECORD + " was not supplied, so the report date window"
                    + " cannot be established");
            assertThat(thrown.fieldErrors()).hasSize(1);
            assertThat(thrown.fieldErrors().get(0).field()).isEqualTo(LABEL_RECORD);
            assertThat(thrown.fieldErrors().get(0).state())
                    .as("an absent record is MISSING, never INVALID")
                    .isEqualTo(ValidationException.FieldState.MISSING);
        }

        /**
         * Content past position twenty-one is discarded without diagnostic, reproducing the move.
         *
         * <p>The legacy program reads an eighty-byte record area and moves it into a twenty-one byte
         * group. A group move of a longer sending field into a shorter receiving one keeps the leading
         * bytes of the sender and discards the remainder silently - there is no truncation diagnostic
         * in the language and none in the program. Refusing the surplus instead would be a behavioural
         * change: a record that the legacy job processed successfully would stop being processed. The
         * window is therefore read from the leading twenty-one bytes and the surplus is dropped, and
         * this test pins both halves of that - the parse succeeds, and it yields exactly the window the
         * leading bytes describe rather than one influenced by what followed them.</p>
         *
         * <p>What protects the layout is not a surplus check but the position checks that follow the
         * move: the separator is verified at its declared position within the group, and each bound is
         * put through the calendar cascade. A record whose content is misaligned therefore still fails,
         * and fails naming the position that is wrong.</p>
         */
        @Test
        @DisplayName("content past position twenty-one is discarded without diagnostic, and the "
                + "window is read from the leading twenty-one bytes")
        void aRecordWhoseContentRunsPastItsLayoutIsTruncatedRatherThanRefused() {
            final String stray = record(LEGACY_WINDOW_START, LEGACY_WINDOW_END) + "X";

            assertThat(stray).hasSize(DATEPARM_WIDTH + 1);

            final JobParameterValidators.ReportDateWindow window =
                    validators.parseDateParmRecord(stray);

            assertThat(window.startDate())
                    .as("the leading bytes are what the receiving group gets")
                    .isEqualTo(LEGACY_WINDOW_START);
            assertThat(window.endDate())
                    .as("the surplus byte is discarded rather than shifting the end bound")
                    .isEqualTo(LEGACY_WINDOW_END);
            assertThat(window)
                    .as("a record with surplus content yields the same window as one without")
                    .isEqualTo(validators.parseDateParmRecord(
                            record(LEGACY_WINDOW_START, LEGACY_WINDOW_END)));
        }

        /**
         * A record that cannot fill the group is refused; one padded to the area is not short.
         *
         * <p>The width check asks a single question: are there at least twenty-one bytes for the group
         * move to take? A record of twenty bytes cannot fill the group, so it is refused before any
         * position is read, and the diagnostic names the width it measured.</p>
         *
         * <p>Padding is not stripped first, and stripping it would be wrong. The record area is
         * fixed-width, so a space at position twenty-one is a byte the layout reserves and the move
         * takes it like any other. A record padded out to the area therefore satisfies the width check
         * and fails - if it fails at all - at the position whose content is wrong, which is the more
         * useful diagnostic. The second half of this test proves exactly that: the same twenty
         * significant characters padded to the full area are refused for their end bound rather than
         * for their width, and the message names the end-date position.</p>
         */
        @Test
        @DisplayName("a record too short to fill the group is refused on width, while one padded to "
                + "the record area is refused on the position whose content is wrong")
        void aRecordShortOfItsSignificantWidthIsRefused() {
            final String shortContent = "2022-01-01 2022-07-0";

            assertThat(catchThrowableOfType(ValidationException.class,
                    () -> validators.parseDateParmRecord(shortContent)))
                    .as("twenty bytes cannot fill the twenty-one byte group")
                    .hasMessage(describe(LABEL_RECORD, shortContent) + "must carry at least "
                            + DATEPARM_WIDTH + " encoded bytes - two " + ISO_WIDTH
                            + "-character dates either side of one separator - but measures "
                            + shortContent.length());

            final String image = paddedRecord(shortContent, ' ');
            assertThat(image).hasSize(RECORD_IMAGE_WIDTH);

            assertThat(catchThrowableOfType(ValidationException.class,
                    () -> validators.parseDateParmRecord(image)))
                    .as("the padding is a byte the fixed-width area reserves, so the record is wide "
                            + "enough and fails on the bound whose content is incomplete")
                    .hasMessage(describe(LABEL_RECORD_END, "2022-07-0 ")
                            + "is not a valid calendar date in the form YYYY-MM-DD");
        }

        @Test
        @DisplayName("the separator position is checked by position, so a record of the right width "
                + "with the separator elsewhere is refused")
        void theSeparatorIsCheckedByPosition() {
            final String hyphenated = LEGACY_WINDOW_START + "-" + LEGACY_WINDOW_END;

            assertThat(hyphenated)
                    .as("the fixture must be the right width, so only the separator can reject it")
                    .hasSize(DATEPARM_WIDTH);

            assertThat(catchThrowableOfType(ValidationException.class,
                    () -> validators.parseDateParmRecord(hyphenated)))
                    .hasMessage(describe(LABEL_RECORD, hyphenated)
                            + "must carry a single separator character in position "
                            + SEPARATOR_POSITION + ", between the two dates");
        }

        @Test
        @DisplayName("leading padding is content in the wrong place, so it shifts the layout and is "
                + "refused rather than absorbed")
        void leadingPaddingIsNotAbsorbed() {
            // Only trailing padding is discarded. A leading space shifts every field one position
            // right, and the failure it produces is the separator's, because the width still matches.
            final String shifted = " " + "2022-01-01 2022-07-0";

            assertThat(shifted).hasSize(DATEPARM_WIDTH);

            assertThat(catchThrowableOfType(ValidationException.class,
                    () -> validators.parseDateParmRecord(shifted)))
                    .hasMessage(describe(LABEL_RECORD, shifted)
                            + "must carry a single separator character in position "
                            + SEPARATOR_POSITION + ", between the two dates");
        }

        @Test
        @DisplayName("a record carrying a character outside the single-byte set is refused before "
                + "any width is measured")
        void aRecordOutsideTheSingleByteSetIsRefused() {
            final String euroBearing = "2022-01-01 2022-07-0" + NON_SINGLE_BYTE;

            assertThat(catchThrowableOfType(ValidationException.class,
                    () -> validators.parseDateParmRecord(euroBearing)))
                    .as("the offending record is described rather than echoed")
                    .hasMessage(describeUnprintable(LABEL_RECORD, 20, 0x20ac)
                            + "carries a character outside the single-byte character set the record"
                            + " area reserves bytes for, so it cannot occupy the declared record"
                            + " positions");
        }

        @Test
        @DisplayName("a date position that fails the window cascade is attributed to that position "
                + "of the record, not to a job parameter key")
        void aFailingDatePositionIsAttributedToTheRecordPosition() {
            // The record route reuses the same cascade as the validator route, so the diagnostic has
            // to name a record position instead of a launch key: no parameter is being validated.
            final String reversed = record(LEGACY_WINDOW_END, LEGACY_WINDOW_START);

            final ValidationException thrown = catchThrowableOfType(ValidationException.class,
                    () -> validators.parseDateParmRecord(reversed));

            assertThat(thrown).isNotNull();
            assertThat(thrown).hasMessage("Job parameter [" + LABEL_RECORD_START + "] value ["
                    + LEGACY_WINDOW_END + "] follows [" + LABEL_RECORD_END + "] value ["
                    + LEGACY_WINDOW_START + "]; the report window is filtered with an inclusive lower"
                    + " and an inclusive upper bound, so the start must not be later than the end");
            assertThat(thrown.getMessage())
                    .as("no launch key may appear: this route has no job parameter")
                    .doesNotContain(KEY_REPORT_START)
                    .doesNotContain(KEY_REPORT_END);
        }

        @Test
        @DisplayName("an invalid calendar date in a record position is refused, naming that position")
        void anInvalidCalendarDateInARecordPositionIsRefused() {
            assertThat(catchThrowableOfType(ValidationException.class,
                    () -> validators.parseDateParmRecord(record("2022-02-30", LEGACY_WINDOW_END))))
                    .hasMessage(describe(LABEL_RECORD_START, "2022-02-30")
                            + "is not a valid calendar date in the form YYYY-MM-DD");
        }

        @Test
        @DisplayName("a record whose two dates are equal is accepted, preserving the inclusive "
                + "single-day window on this route too")
        void aRecordWhoseDatesAreEqualIsAccepted() {
            final JobParameterValidators.ReportDateWindow window =
                    validators.parseDateParmRecord(record(LEGACY_WINDOW_START, LEGACY_WINDOW_START));

            assertThat(window.startDate()).isEqualTo(LEGACY_WINDOW_START);
            assertThat(window.endDate()).isEqualTo(LEGACY_WINDOW_START);
        }
    }

    @Nested
    @DisplayName("the report window: an inclusive lexical containment test")
    final class TheReportWindow {

        /** The window under test, built from the authentic legacy bounds. */
        private final JobParameterValidators.ReportDateWindow window =
                validators.parseDateParmRecord(record(LEGACY_WINDOW_START, LEGACY_WINDOW_END));

        @ParameterizedTest(name = "the processing date {0} falls inside the window: {1}")
        @CsvSource({
            "2021-12-31, false",
            "2022-01-01, true",
            "2022-03-15, true",
            "2022-07-06, true",
            "2022-07-07, false",
            "2023-01-01, false",
        })
        @DisplayName("both bounds are inclusive and everything outside them is excluded")
        void bothBoundsAreInclusive(final String processingDate, final boolean expected) {
            // Measured from INCLUDE COND=(TRAN-PROC-DT,GE,PARM-START-DATE,AND,TRAN-PROC-DT,LE,
            // PARM-END-DATE). The two boundary rows are the ones an exclusive bound would break.
            assertThat(window.includes(processingDate))
                    .as("the date [%s] must%s be selected", processingDate, expected ? "" : " not")
                    .isEqualTo(expected);
        }

        @Test
        @DisplayName("a single-day window selects exactly that day")
        void aSingleDayWindowSelectsExactlyThatDay() {
            final JobParameterValidators.ReportDateWindow oneDay =
                    validators.parseDateParmRecord(record(LEGACY_WINDOW_START, LEGACY_WINDOW_START));

            assertThat(oneDay.includes(LEGACY_WINDOW_START)).isTrue();
            assertThat(oneDay.includes("2022-01-02")).isFalse();
            assertThat(oneDay.includes("2021-12-31")).isFalse();
        }

        @Test
        @DisplayName("the whole processing timestamp is refused, because taking its date portion is "
                + "the caller's job and a mismatched width is not the legacy comparison")
        void theWholeProcessingTimestampIsRefused() {
            // The legacy compares the leading positions of a wider timestamp field. Silently
            // comparing an untrimmed timestamp would compare operands of different widths.
            final String timestamp = "2022-07-06 00.00.00";

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> window.includes(timestamp))
                    .withMessage("processingDate must be exactly " + ISO_WIDTH + " encoded bytes to be"
                            + " compared against this window, but [" + timestamp + "] is not");
        }

        @Test
        @DisplayName("a null processing date is refused rather than treated as outside the window")
        void aNullProcessingDateIsRefused() {
            assertThatNullPointerException()
                    .isThrownBy(() -> window.includes(null))
                    .withMessageContaining("processingDate must not be null");
        }

        @Test
        @DisplayName("a processing date carrying a character outside the single-byte set is refused")
        void aProcessingDateOutsideTheSingleByteSetIsRefused() {
            final String euroBearing = "2022-07-0" + NON_SINGLE_BYTE;

            assertThat(euroBearing).hasSize(ISO_WIDTH);

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> window.includes(euroBearing))
                    .withMessageContaining("must be exactly " + ISO_WIDTH + " encoded bytes");
        }

        @Test
        @DisplayName("both bounds are mandatory on construction, so no window can exist half formed")
        void bothBoundsAreMandatoryOnConstruction() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new JobParameterValidators.ReportDateWindow(
                            null, LEGACY_WINDOW_END))
                    .withMessageContaining("startDate must not be null");

            assertThatNullPointerException()
                    .isThrownBy(() -> new JobParameterValidators.ReportDateWindow(
                            LEGACY_WINDOW_START, null))
                    .withMessageContaining("endDate must not be null");
        }

        @Test
        @DisplayName("the bounds are held exactly as supplied, never reformatted through a date type")
        void theBoundsAreHeldExactlyAsSupplied() {
            // Holding them as text is deliberate: the legacy filter is a character comparison against
            // a character field, so converting to a date type and back could reintroduce a different
            // rendering of the same day.
            assertThat(window.startDate()).isEqualTo(LEGACY_WINDOW_START);
            assertThat(window.endDate()).isEqualTo(LEGACY_WINDOW_END);
            assertThat(window.startDate()).hasSize(ISO_WIDTH);
            assertThat(window.endDate()).hasSize(ISO_WIDTH);
        }

        @Test
        @DisplayName("two windows over the same bounds are equal, so a window can be compared by value")
        void twoWindowsOverTheSameBoundsAreEqual() {
            final JobParameterValidators.ReportDateWindow same =
                    new JobParameterValidators.ReportDateWindow(LEGACY_WINDOW_START, LEGACY_WINDOW_END);
            final JobParameterValidators.ReportDateWindow different =
                    new JobParameterValidators.ReportDateWindow(LEGACY_WINDOW_START, "2022-07-07");

            assertThat(window).isEqualTo(same).hasSameHashCodeAs(same);
            assertThat(window).isNotEqualTo(different);
        }
    }
}
