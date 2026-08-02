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

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.carddemo.exception.ValidationException;
import com.carddemo.service.DateValidationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.JobParametersInvalidException;
import org.springframework.batch.core.JobParametersValidator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * Verifies {@link JobParameterValidators}, the single supplier of every launch-time parameter contract
 * the CardDemo batch tier carries, together with the diagnostic discipline those contracts are reported
 * under.
 *
 * <h2>The legacy authority behind each expectation</h2>
 *
 * <p>Every figure asserted here was read from the mainframe estate rather than chosen. The interest
 * parameter is the ten-character all-digit literal the interest job stream supplied at
 * {@code [app/jcl/INTCALC.jcl:L22]} into the ten-character linkage date field declared at
 * {@code [app/cbl/CBACT04C.cbl:L178]}. The report window is the pair of ten-character hyphenated sort
 * symbols declared at {@code [app/jcl/TRANREPT.jcl:L43]} and {@code [app/jcl/TRANREPT.jcl:L44]} and
 * filtered inclusively at {@code [app/jcl/TRANREPT.jcl:L47]} and {@code [app/jcl/TRANREPT.jcl:L48]}.
 * The date-parameter record is the twenty-one significant bytes declared from
 * {@code [app/cbl/CBTRN03C.cbl:L122]} through {@code [app/cbl/CBTRN03C.cbl:L125]} inside the
 * eighty-byte record area at {@code [app/cbl/CBTRN03C.cbl:L88]}. The probe mode alone has no legacy
 * antecedent literal, because the legacy distinction between the four sequential-read verification
 * streams was the identity of the job rather than the content of a parameter.</p>
 *
 * <h2>Why the real calendar authority is used and nothing is mocked</h2>
 *
 * <p>The class under test delegates every calendar decision, and the interesting properties of that
 * delegation are properties of the real cascade rather than of a stub. Three of them are asserted here
 * and none of them would survive mocking:</p>
 *
 * <ul>
 *   <li><strong>Strict resolution.</strong> A thirtieth of February must be <em>rejected</em> rather
 *       than normalised onto a nearby valid day. A stub cannot demonstrate that; the real cascade
 *       can.</li>
 *   <li><strong>Inherited acceptance.</strong> Acceptance is the authority's own two-level test, not a
 *       severity comparison restated by the class under test. The value {@code 1582071500} is the
 *       witness: the authority flags it with a non-zero severity yet accepts it, because its message
 *       number is the one condition the legacy callers tolerated. A class that had re-derived acceptance
 *       from the severity code would reject it, so this single value proves the delegation is
 *       genuine.</li>
 *   <li><strong>No slicing before delegation.</strong> The full ten characters, not the leading eight,
 *       are handed over alongside the compact mask. That is observable in the rejection diagnostic the
 *       class logs, which names the mask and the value it delegated, and it is asserted from the log
 *       rather than from a verified mock call.</li>
 * </ul>
 *
 * <p>The authority is stateless with a public no-argument constructor, so constructing a real one costs
 * nothing and removes every question about whether a stub agrees with the thing it stands in for.</p>
 *
 * <h2>The diagnostic-integrity obligation</h2>
 *
 * <p>A launch parameter is caller supplied, a diagnostic about it is written into a log, and parameter
 * substitution escapes nothing. A value carrying a carriage return or a line feed reproduced verbatim
 * into a log record would therefore terminate that record and begin one of the caller's own choosing —
 * a forged entry that no human and no log-processing tool could distinguish from a genuine one. The
 * class under test answers that by rendering every value through one degrading describer, and the
 * {@code DiagnosticIntegrity} group below asserts the property directly: for every reachable rejection
 * path and every reachable accepting path, no message and no log record may contain a character outside
 * printable US-ASCII, and the substitute that replaces one must name the offending character's
 * zero-based position and code point instead.</p>
 *
 * <p>The property is asserted structurally — by scanning the produced text for any character outside
 * the printable range — rather than by matching one expected string, so a future diagnostic added to
 * the class is covered by these tests without them having to be rewritten.</p>
 *
 * <h2>Provenance</h2>
 *
 * <p>Expectations derived from the CardDemo mainframe estate at commit SHA
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. Every legacy fact is cited by member and line
 * number; no COBOL, JCL or cataloged-procedure text is transcribed.</p>
 */
@DisplayName("JobParameterValidators: the four measured launch contracts, the delegated calendar "
        + "cascade and the diagnostic discipline they are reported under")
final class JobParameterValidatorsRuleComplianceTest {

    /**
     * The literal the interest job stream supplied at {@code [app/jcl/INTCALC.jcl:L22]}: an eight-digit
     * calendar date followed by two further digits filling the remaining positions of the ten-character
     * linkage date field.
     */
    private static final String MEASURED_INTEREST_PARM_DATE = "2022071800";

    /**
     * A ten-digit interest parameter whose trailing two positions differ from the measured literal's.
     * It must be accepted, because the compact mask interprets only the leading eight positions, and its
     * acceptance is what proves the trailing pair is outside the calendar interpretation rather than
     * merely happening to be zeroes.
     */
    private static final String INTEREST_PARM_DATE_WITH_OTHER_SUFFIX = "2022071899";

    /**
     * A ten-digit interest parameter the calendar authority flags with a non-zero severity and still
     * accepts, because its message number is the single condition the legacy callers tolerated. The
     * witness that acceptance is inherited rather than re-derived.
     */
    private static final String INTEREST_PARM_DATE_ON_TOLERATED_CONDITION = "1582071500";

    /** The inclusive lower bound declared as a sort symbol at {@code [app/jcl/TRANREPT.jcl:L43]}. */
    private static final String MEASURED_WINDOW_START = "2022-01-01";

    /** The inclusive upper bound declared as a sort symbol at {@code [app/jcl/TRANREPT.jcl:L44]}. */
    private static final String MEASURED_WINDOW_END = "2022-07-06";

    /**
     * The four probe modes standing in for the four legacy sequential-read verification job streams
     * {@code READACCT}, {@code READCARD}, {@code READCUST} and {@code READXREF}. Declared here because
     * the enumeration belongs to the probe job configuration and is supplied to the class under test
     * rather than declared by it.
     */
    private static final List<String> LEGAL_PROBE_MODES =
            List.of("ACCOUNT", "CARD", "CUSTOMER", "XREF");

    /** The significant width of the date-parameter record, in encoded bytes. */
    private static final int DATEPARM_SIGNIFICANT_WIDTH = 21;

    /** The full width of the record area the date-parameter record arrives inside, in encoded bytes. */
    private static final int DATEPARM_IMAGE_WIDTH = 80;

    /** Lowest character any diagnostic this class inspects is allowed to contain. */
    private static final char FIRST_PRINTABLE = 0x20;

    /** Highest character any diagnostic this class inspects is allowed to contain. */
    private static final char LAST_PRINTABLE = 0x7E;

    /** The class under test, reconstructed for every test over a real calendar authority. */
    private JobParameterValidators validators;

    @BeforeEach
    void constructOverTheRealCalendarAuthority() {
        this.validators = new JobParameterValidators(new DateValidationService());
    }

    // =================================================================================================
    // Shared helpers. Each exists so that an expectation below reads as the property it asserts rather
    // than as the plumbing needed to reach it.
    // =================================================================================================

    /**
     * Builds a parameter set from alternating key and value arguments.
     *
     * @param keysAndValues an even-length run of key, value, key, value
     * @return the parameter set
     */
    private static JobParameters parameters(final String... keysAndValues) {
        final JobParametersBuilder builder = new JobParametersBuilder();
        for (int index = 0; index < keysAndValues.length; index += 2) {
            builder.addString(keysAndValues[index], keysAndValues[index + 1]);
        }
        return builder.toJobParameters();
    }

    /**
     * Asserts that a validator rejects a parameter set and returns the diagnostic it produced.
     *
     * @param validator  the validator under test
     * @param parameters the parameter set it must refuse
     * @return the rejection message, never {@code null}
     */
    private static String rejection(final JobParametersValidator validator,
                                    final JobParameters parameters) {

        final JobParametersInvalidException thrown = catchThrowableOfType(
                JobParametersInvalidException.class, () -> validator.validate(parameters));
        assertThat(thrown)
                .as("the validator was expected to reject this parameter set and did not")
                .isNotNull();
        assertThat(thrown.getMessage())
                .as("a rejection must carry a diagnostic, because it is read from a log after the fact")
                .isNotBlank();
        return thrown.getMessage();
    }

    /**
     * Asserts that a validator accepts a parameter set.
     *
     * @param validator  the validator under test
     * @param parameters the parameter set it must admit
     */
    private static void accepts(final JobParametersValidator validator,
                                final JobParameters parameters) {

        assertThatNoException()
                .as("the validator was expected to accept this parameter set")
                .isThrownBy(() -> validator.validate(parameters));
    }

    /**
     * Reports the zero-based position of the first character a diagnostic must not contain.
     *
     * @param text the diagnostic to scan
     * @return the offending position, or {@code -1} when every character is printable US-ASCII
     */
    private static int firstUnprintablePosition(final String text) {
        for (int index = 0; index < text.length(); index++) {
            final char character = text.charAt(index);
            if (character < FIRST_PRINTABLE || character > LAST_PRINTABLE) {
                return index;
            }
        }
        return -1;
    }

    /**
     * Asserts that a diagnostic is safe to write into a log: printable throughout, and therefore
     * incapable of forging a record boundary or of smuggling a control sequence into a terminal.
     *
     * @param diagnostic the text to check
     * @param context    what produced it, for the failure message
     */
    private static void assertSafeToLog(final String diagnostic, final String context) {
        final int offendingPosition = firstUnprintablePosition(diagnostic);
        assertThat(offendingPosition)
                .as("%s produced a diagnostic carrying a character outside printable US-ASCII at "
                        + "zero-based position %s, which would forge a log record: %s", context,
                        offendingPosition, diagnostic.replace("\n", "\\n").replace("\r", "\\r"))
                .isEqualTo(-1);
    }

    /**
     * Asserts that a diagnostic degraded rather than reproducing an unrenderable value, and that it
     * named the offending character precisely enough to be actionable.
     *
     * @param diagnostic        the text to check
     * @param expectedPosition  the zero-based position the diagnostic must name
     * @param expectedCodePoint the code point the diagnostic must name
     */
    private static void assertDegradedAt(final String diagnostic, final int expectedPosition,
                                         final int expectedCodePoint) {

        assertSafeToLog(diagnostic, "a degraded diagnostic");
        assertThat(diagnostic)
                .as("the substitute must say that the value could not be rendered")
                .contains("not printable US-ASCII")
                .as("the substitute must name the offending position, so the caller can find it")
                .contains("zero-based position " + expectedPosition)
                .as("the substitute must name the offending code point, so the caller can identify it")
                .contains("code point " + expectedCodePoint);
    }

    /** Builds a full eighty-byte record image around twenty-one significant bytes of content. */
    private static String paddedRecordImage(final String significantContent, final char padding) {
        return significantContent
                + String.valueOf(padding).repeat(DATEPARM_IMAGE_WIDTH - significantContent.length());
    }

    /** Builds a well-formed date-parameter record from a start and an end date. */
    private static String dateParmRecord(final String startDate, final String endDate) {
        return startDate + " " + endDate;
    }

    // =================================================================================================
    // Construction and the published launch surface.
    // =================================================================================================

    @Nested
    @DisplayName("construction - the calendar authority is mandatory and every accessor is stateless")
    class Construction {

        @Test
        @DisplayName("the calendar authority is required, because every calendar decision is delegated")
        void theCalendarAuthorityIsRequired() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new JobParameterValidators(null))
                    .withMessageContaining("dateValidationService must not be null");
        }

        @Test
        @DisplayName("each accessor returns a validator over the contract it names")
        void eachAccessorReturnsAValidator() {
            final JobParameterValidators subject = JobParameterValidatorsRuleComplianceTest.this.validators;

            assertThat(subject.interestParmDateValidator()).isNotNull();
            assertThat(subject.reportDateRangeValidator()).isNotNull();
            assertThat(subject.fileProbeModeValidator(LEGAL_PROBE_MODES)).isNotNull();
        }

        @Test
        @DisplayName("a validator holds no state, so the same instance decides two launches independently")
        void aValidatorHoldsNoState() throws JobParametersInvalidException {
            final JobParametersValidator validator =
                    JobParameterValidatorsRuleComplianceTest.this.validators.interestParmDateValidator();

            validator.validate(parameters(JobParameterValidators.INTEREST_PARM_DATE_KEY,
                    MEASURED_INTEREST_PARM_DATE));
            rejection(validator, parameters(JobParameterValidators.INTEREST_PARM_DATE_KEY, "20220718"));
            validator.validate(parameters(JobParameterValidators.INTEREST_PARM_DATE_KEY,
                    MEASURED_INTEREST_PARM_DATE));
        }

        @Test
        @DisplayName("an absent parameter set is reported through the framework contract, not as an "
                + "unchecked failure escaping a validator")
        void anAbsentParameterSetIsReportedThroughTheFrameworkContract() {
            final JobParameterValidators subject = JobParameterValidatorsRuleComplianceTest.this.validators;

            for (final JobParametersValidator validator : List.of(subject.interestParmDateValidator(),
                    subject.reportDateRangeValidator(),
                    subject.fileProbeModeValidator(LEGAL_PROBE_MODES))) {

                assertThat(rejection(validator, null))
                        .isEqualTo("No job parameters were supplied, so the launch-time contract of this"
                                + " job cannot be checked");
            }
        }
    }

    @Nested
    @DisplayName("launch keys - one spelling of each parameter name exists in one place")
    class PublishedLaunchKeys {

        @Test
        @DisplayName("the four keys carry the spellings the job configurations and the launch endpoint "
                + "share")
        void theFourKeysCarryTheirPublishedSpellings() {
            assertThat(JobParameterValidators.INTEREST_PARM_DATE_KEY).isEqualTo("interestParmDate");
            assertThat(JobParameterValidators.REPORT_START_DATE_KEY).isEqualTo("reportStartDate");
            assertThat(JobParameterValidators.REPORT_END_DATE_KEY).isEqualTo("reportEndDate");
            assertThat(JobParameterValidators.FILE_PROBE_MODE_KEY).isEqualTo("fileProbeMode");
        }

        @Test
        @DisplayName("the four keys are distinct, so no two contracts can collide on one name")
        void theFourKeysAreDistinct() {
            assertThat(List.of(JobParameterValidators.INTEREST_PARM_DATE_KEY,
                    JobParameterValidators.REPORT_START_DATE_KEY,
                    JobParameterValidators.REPORT_END_DATE_KEY,
                    JobParameterValidators.FILE_PROBE_MODE_KEY)).doesNotHaveDuplicates();
        }
    }

    // =================================================================================================
    // The interest-calculation parameter date.
    // =================================================================================================

    @Nested
    @DisplayName("interest parameter date - ten digits, no separators, strict calendar in the leading "
            + "eight positions, consumed verbatim")
    class InterestParameterDate {

        private JobParametersValidator validator;

        @BeforeEach
        void takeTheValidator() {
            this.validator = JobParameterValidatorsRuleComplianceTest.this.validators.interestParmDateValidator();
        }

        private JobParameters withValue(final String value) {
            return parameters(JobParameterValidators.INTEREST_PARM_DATE_KEY, value);
        }

        @Test
        @DisplayName("the literal the interest job stream supplied is accepted")
        void theMeasuredLiteralIsAccepted() {
            accepts(this.validator, withValue(MEASURED_INTEREST_PARM_DATE));
        }

        @Test
        @DisplayName("the accepted value is readable back unchanged, because its ten characters become "
                + "the literal prefix of every synthesized interest-transaction identifier")
        void theAcceptedValueIsReadableBackUnchanged() throws JobParametersInvalidException {
            final JobParameters supplied = withValue(MEASURED_INTEREST_PARM_DATE);

            this.validator.validate(supplied);

            assertThat(supplied.getString(JobParameterValidators.INTEREST_PARM_DATE_KEY))
                    .as("the validator must neither rewrite nor normalise the parameter")
                    .isEqualTo(MEASURED_INTEREST_PARM_DATE);
        }

        @Test
        @DisplayName("the trailing two positions are outside the calendar interpretation, so a different "
                + "suffix over the same calendar date is accepted too")
        void theTrailingTwoPositionsAreOutsideTheCalendarInterpretation() {
            accepts(this.validator, withValue(INTEREST_PARM_DATE_WITH_OTHER_SUFFIX));
        }

        @Test
        @DisplayName("acceptance is the calendar authority's own two-level test, so a value it flags "
                + "with a non-zero severity on the tolerated message number is still accepted")
        void acceptanceIsInheritedFromTheCalendarAuthority() {
            accepts(this.validator, withValue(INTEREST_PARM_DATE_ON_TOLERATED_CONDITION));
        }

        @Test
        @DisplayName("a leap-day calendar date is accepted in a leap year")
        void aLeapDayIsAcceptedInALeapYear() {
            accepts(this.validator, withValue("2024022900"));
        }

        @Test
        @DisplayName("a missing parameter is reported by name, because the interest run is driven by it")
        void aMissingParameterIsReportedByName() {
            assertThat(rejection(this.validator, new JobParameters()))
                    .isEqualTo("Job parameter [interestParmDate] is required but was not supplied; the"
                            + " interest calculation is driven by it");
        }

        @ParameterizedTest(name = "[{0}] measures {1} encoded bytes rather than ten")
        @MethodSource("com.carddemo.batch.JobParameterValidatorsRuleComplianceTest#wrongWidthInterestParameters")
        @DisplayName("a value that does not fill the ten-byte parameter field is rejected, and the "
                + "measured width is reported")
        void aValueOfTheWrongWidthIsRejected(final String value, final int expectedWidth) {
            assertThat(rejection(this.validator, withValue(value)))
                    .isEqualTo("Job parameter [interestParmDate] value [" + value + "] must be exactly 10"
                            + " encoded bytes to fill the parameter date field, but measures "
                            + expectedWidth);
        }

        @Test
        @DisplayName("the width is checked before the calendar authority is consulted, so an over-long "
                + "value cannot arrive there as its own leading ten characters and be accepted")
        void theWidthIsCheckedBeforeDelegation() {
            assertThat(rejection(this.validator, withValue(MEASURED_INTEREST_PARM_DATE + "9")))
                    .contains("must be exactly 10 encoded bytes")
                    .doesNotContain("valid calendar date");
        }

        @Test
        @DisplayName("a hyphenated ISO date is rejected: the interest parameter is ten digits with no "
                + "separators and the two formats are not interchangeable")
        void aHyphenatedIsoDateIsRejected() {
            assertThat(rejection(this.validator, withValue("2022-07-18")))
                    .isEqualTo("Job parameter [interestParmDate] value [2022-07-18] must be 10 digits with"
                            + " no separators; it is not a hyphenated ISO date");
        }

        @ParameterizedTest(name = "[{0}] is not a calendar date")
        @ValueSource(strings = {"2022023000", "2023022900", "2022043100", "2022131800", "0000071800"})
        @DisplayName("an impossible calendar date is rejected rather than normalised onto a nearby valid "
                + "day")
        void anImpossibleCalendarDateIsRejectedRatherThanNormalised(final String value) {
            assertThat(rejection(this.validator, withValue(value)))
                    .isEqualTo("Job parameter [interestParmDate] value [" + value + "] does not carry a"
                            + " valid calendar date in its leading 8 positions");
        }

        @Test
        @DisplayName("a value carrying a character the parameter field cannot represent is rejected "
                + "before it is encoded, so no replacement byte can stand in for it")
        void aNonRepresentableValueIsRejectedBeforeEncoding() {
            assertThat(rejection(this.validator, withValue("202207180\u00e9")))
                    .contains("carries a character outside the single-byte character set the parameter"
                            + " field reserves bytes for");
        }

        @Test
        @DisplayName("the digit test is an explicit range test, so a digit from another script is "
                + "refused even though it is a digit")
        void theDigitTestIsAnExplicitRangeTest() {
            // U+0660 ARABIC-INDIC DIGIT ZERO answers a general "is this a digit" query affirmatively but
            // cannot occupy a byte of the legacy numeric picture, so it must be refused rather than
            // admitted. It fails at the representability stage, which is the earlier and stricter of the
            // two gates that stand between it and the calendar authority.
            assertThat(rejection(this.validator, withValue("202207180\u0660")))
                    .contains("outside the single-byte character set");
        }
    }

    // =================================================================================================
    // The transaction-report date range.
    // =================================================================================================

    @Nested
    @DisplayName("report date range - two hyphenated ISO bounds, both inclusive, ordered by character "
            + "comparison")
    class ReportDateRange {

        private JobParametersValidator validator;

        @BeforeEach
        void takeTheValidator() {
            this.validator = JobParameterValidatorsRuleComplianceTest.this.validators.reportDateRangeValidator();
        }

        private JobParameters withWindow(final String startDate, final String endDate) {
            return parameters(JobParameterValidators.REPORT_START_DATE_KEY, startDate,
                    JobParameterValidators.REPORT_END_DATE_KEY, endDate);
        }

        @Test
        @DisplayName("the window the report job stream declared as sort symbols is accepted")
        void theMeasuredWindowIsAccepted() {
            accepts(this.validator, withWindow(MEASURED_WINDOW_START, MEASURED_WINDOW_END));
        }

        @Test
        @DisplayName("a window whose start equals its end is accepted, because both bounds are inclusive "
                + "and such a window selects that single day rather than nothing")
        void bothBoundsAreInclusive() {
            accepts(this.validator, withWindow(MEASURED_WINDOW_START, MEASURED_WINDOW_START));
        }

        @Test
        @DisplayName("a start that follows the end is rejected, and both bounds are named")
        void aStartThatFollowsTheEndIsRejected() {
            assertThat(rejection(this.validator, withWindow(MEASURED_WINDOW_END, MEASURED_WINDOW_START)))
                    .isEqualTo("Job parameter [reportStartDate] value [" + MEASURED_WINDOW_END
                            + "] follows [reportEndDate] value [" + MEASURED_WINDOW_START + "]; the report"
                            + " window is filtered with an inclusive lower and an inclusive upper bound,"
                            + " so the start must not be later than the end");
        }

        @Test
        @DisplayName("a missing start bound is reported by name")
        void aMissingStartBoundIsReportedByName() {
            assertThat(rejection(this.validator,
                    parameters(JobParameterValidators.REPORT_END_DATE_KEY, MEASURED_WINDOW_END)))
                    .isEqualTo("Job parameter [reportStartDate] is required but was not supplied; the"
                            + " report window needs both of its bounds");
        }

        @Test
        @DisplayName("a missing end bound is reported by name")
        void aMissingEndBoundIsReportedByName() {
            assertThat(rejection(this.validator,
                    parameters(JobParameterValidators.REPORT_START_DATE_KEY, MEASURED_WINDOW_START)))
                    .isEqualTo("Job parameter [reportEndDate] is required but was not supplied; the report"
                            + " window needs both of its bounds");
        }

        @Test
        @DisplayName("the start bound is reported first when both bounds are bad, because the cascade "
                + "short-circuits exactly as the legacy edits did")
        void theStartBoundIsReportedFirstWhenBothAreBad() {
            assertThat(rejection(this.validator, withWindow("2022-13-01", "2022-02-30")))
                    .contains("[reportStartDate]")
                    .doesNotContain("[reportEndDate]");
        }

        @Test
        @DisplayName("a compact ten-digit date is rejected: the report window carries hyphens because the "
                + "legacy filter is a character comparison")
        void aCompactDateIsRejected() {
            assertThat(rejection(this.validator, withWindow("20220101", "20220706")))
                    .isEqualTo("Job parameter [reportStartDate] value [20220101] must be exactly 10"
                            + " encoded bytes in the form YYYY-MM-DD, but measures 8");
        }

        @ParameterizedTest(name = "[{0}] measures {1} encoded bytes rather than ten")
        @MethodSource("com.carddemo.batch.JobParameterValidatorsRuleComplianceTest#wrongWidthIsoDates")
        @DisplayName("a bound that does not fill the ten-byte field is rejected, and the measured width "
                + "is reported")
        void aBoundOfTheWrongWidthIsRejected(final String value, final int expectedWidth) {
            assertThat(rejection(this.validator, withWindow(value, MEASURED_WINDOW_END)))
                    .isEqualTo("Job parameter [reportStartDate] value [" + value + "] must be exactly 10"
                            + " encoded bytes in the form YYYY-MM-DD, but measures " + expectedWidth);
        }

        @ParameterizedTest(name = "[{0}] is not a hyphenated ISO calendar date")
        @ValueSource(strings = {"2022-13-01", "2022-02-30", "2023-02-29", "2022-04-31", "2022/01/01",
                "aaaa-bb-cc", "0000-01-01"})
        @DisplayName("a bound that is not a strictly valid hyphenated calendar date is rejected, and the "
                + "separator, the shape and the calendar are all decided by the delegated authority")
        void aBoundThatIsNotAValidIsoCalendarDateIsRejected(final String value) {
            assertThat(rejection(this.validator, withWindow(value, MEASURED_WINDOW_END)))
                    .isEqualTo("Job parameter [reportStartDate] value [" + value + "] is not a valid"
                            + " calendar date in the form YYYY-MM-DD");
        }

        @Test
        @DisplayName("a leap day is accepted in a leap year and refused in a common year, which is what "
                + "strict resolution means")
        void strictResolutionDistinguishesLeapYears() {
            accepts(this.validator, withWindow("2024-02-29", "2024-03-01"));
            assertThat(rejection(this.validator, withWindow("2023-02-29", MEASURED_WINDOW_END)))
                    .contains("is not a valid calendar date");
        }

        @Test
        @DisplayName("a bound carrying a character the field cannot represent is rejected before it is "
                + "encoded")
        void aNonRepresentableBoundIsRejectedBeforeEncoding() {
            assertThat(rejection(this.validator, withWindow("2022-01-0\u00e9", MEASURED_WINDOW_END)))
                    .contains("carries a character outside the single-byte character set the field"
                            + " reserves bytes for");
        }

        @Test
        @DisplayName("the ordering test is lexical, so a window spanning a year boundary orders correctly "
                + "without any date conversion")
        void theOrderingTestIsLexical() {
            accepts(this.validator, withWindow("2021-12-31", "2022-01-01"));
            assertThat(rejection(this.validator, withWindow("2022-01-01", "2021-12-31")))
                    .contains("follows");
        }
    }

    // =================================================================================================
    // The file-probe mode, and the boundary its enumeration crosses on the way in.
    // =================================================================================================

    @Nested
    @DisplayName("file-probe mode - exact, case-sensitive matching against the enumeration its owner "
            + "supplies")
    class FileProbeMode {

        private JobParametersValidator validator;

        @BeforeEach
        void takeTheValidator() {
            this.validator =
                    JobParameterValidatorsRuleComplianceTest.this.validators.fileProbeModeValidator(LEGAL_PROBE_MODES);
        }

        private JobParameters withMode(final String value) {
            return parameters(JobParameterValidators.FILE_PROBE_MODE_KEY, value);
        }

        @ParameterizedTest(name = "[{0}]")
        @ValueSource(strings = {"ACCOUNT", "CARD", "CUSTOMER", "XREF"})
        @DisplayName("every mode standing in for a legacy sequential-read job stream is accepted")
        void everyLegalModeIsAccepted(final String mode) {
            accepts(this.validator, withMode(mode));
        }

        @Test
        @DisplayName("a missing mode is reported by name and the legal values are listed, because the "
                + "mode selects which file the probe job reads")
        void aMissingModeIsReportedWithTheLegalValues() {
            assertThat(rejection(this.validator, new JobParameters()))
                    .isEqualTo("Job parameter [fileProbeMode] is required but was not supplied; it selects"
                            + " which file the probe job reads, and the legal values are "
                            + "[ACCOUNT, CARD, CUSTOMER, XREF]");
        }

        @Test
        @DisplayName("a blank mode is reported as blank rather than as unmatched, so the operator is told "
                + "what actually went wrong")
        void aBlankModeIsReportedAsBlank() {
            assertThat(rejection(this.validator, withMode("   ")))
                    .isEqualTo("Job parameter [fileProbeMode] value [   ] is blank; the legal values are"
                            + " [ACCOUNT, CARD, CUSTOMER, XREF]");
        }

        @Test
        @DisplayName("matching is case sensitive, so a near miss is reported rather than guessed at")
        void matchingIsCaseSensitive() {
            assertThat(rejection(this.validator, withMode("account")))
                    .isEqualTo("Job parameter [fileProbeMode] value [account] is not one of the legal"
                            + " probe modes [ACCOUNT, CARD, CUSTOMER, XREF]; matching is exact and case"
                            + " sensitive");
        }

        @Test
        @DisplayName("an unknown mode is rejected and the legal values are listed in declaration order")
        void anUnknownModeIsRejectedAndTheLegalValuesAreListedInOrder() {
            assertThat(rejection(this.validator, withMode("LEDGER")))
                    .contains("[ACCOUNT, CARD, CUSTOMER, XREF]");
        }

        @Test
        @DisplayName("the enumeration is copied defensively, so mutating the caller's collection "
                + "afterwards cannot widen or narrow what the validator admits")
        void theEnumerationIsCopiedDefensively() {
            final List<String> mutable = new ArrayList<>(LEGAL_PROBE_MODES);
            final JobParametersValidator built =
                    JobParameterValidatorsRuleComplianceTest.this.validators.fileProbeModeValidator(mutable);

            mutable.clear();
            mutable.add("LEDGER");

            accepts(built, withMode("ACCOUNT"));
            assertThat(rejection(built, withMode("LEDGER")))
                    .contains("[ACCOUNT, CARD, CUSTOMER, XREF]");
        }

        @Test
        @DisplayName("a duplicated entry collapses, so the diagnostic lists each legal value once")
        void aDuplicatedEntryCollapses() {
            final JobParametersValidator built = JobParameterValidatorsRuleComplianceTest.this.validators
                    .fileProbeModeValidator(List.of("ACCOUNT", "CARD", "ACCOUNT"));

            assertThat(rejection(built, withMode("LEDGER"))).contains("[ACCOUNT, CARD]");
        }
    }

    @Nested
    @DisplayName("probe-mode enumeration boundary - a configuration mistake is refused where it is made")
    class ProbeModeEnumerationBoundary {

        @Test
        @DisplayName("a null enumeration is refused, because the probe job configuration owns it and "
                + "must supply it")
        void aNullEnumerationIsRefused() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() ->
                            JobParameterValidatorsRuleComplianceTest.this.validators.fileProbeModeValidator(null))
                    .withMessageContaining("legalModeNames must not be null");
        }

        @Test
        @DisplayName("an empty enumeration is refused, because a validator built from one would reject "
                + "every launch")
        void anEmptyEnumerationIsRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> JobParameterValidatorsRuleComplianceTest.this.validators
                            .fileProbeModeValidator(List.of()))
                    .withMessage("legalModeNames must not be empty, because a validator built from an"
                            + " empty set would reject every launch");
        }

        @Test
        @DisplayName("a null entry is refused, because no launch could ever match one")
        void aNullEntryIsRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> JobParameterValidatorsRuleComplianceTest.this.validators
                            .fileProbeModeValidator(Arrays.asList("ACCOUNT", null)))
                    .withMessage("legalModeNames must not carry a null or blank entry, because no launch"
                            + " could ever match one");
        }

        @Test
        @DisplayName("a blank entry is refused for the same reason as a null one")
        void aBlankEntryIsRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> JobParameterValidatorsRuleComplianceTest.this.validators
                            .fileProbeModeValidator(List.of("ACCOUNT", "   ")))
                    .withMessageContaining("must not carry a null or blank entry");
        }

        @ParameterizedTest(name = "code point {1} at position {2}")
        @MethodSource("com.carddemo.batch.JobParameterValidatorsRuleComplianceTest#unrenderableProbeModeEntries")
        @DisplayName("an entry carrying a character outside printable US-ASCII is refused at the "
                + "boundary, because every legal value is reproduced verbatim in a rejection diagnostic")
        void anUnrenderableEntryIsRefusedAtTheBoundary(final String entry, final int codePoint,
                                                       final int position) {

            final IllegalArgumentException thrown = catchThrowableOfType(IllegalArgumentException.class,
                    () -> JobParameterValidatorsRuleComplianceTest.this.validators
                            .fileProbeModeValidator(List.of("ACCOUNT", entry)));

            assertThat(thrown).isNotNull();
            assertSafeToLog(thrown.getMessage(), "the enumeration boundary guard");
            assertThat(thrown.getMessage())
                    .as("the guard must state the admissible range")
                    .contains("printable US-ASCII only, that is code points 32 to 126")
                    .as("the guard must name the offending position and code point")
                    .contains("zero-based position " + position)
                    .contains("code point " + codePoint)
                    .as("the guard must not repeat the offending entry, or the report of the problem "
                            + "would itself be the problem")
                    .doesNotContain(entry);
        }

        @Test
        @DisplayName("the boundary guard runs on every entry, not only the first")
        void theBoundaryGuardRunsOnEveryEntry() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> JobParameterValidatorsRuleComplianceTest.this.validators.fileProbeModeValidator(
                            List.of("ACCOUNT", "CARD", "CUSTOMER", "XR\u001bEF")))
                    .withMessageContaining("code point 27");
        }
    }

    // =================================================================================================
    // The date-parameter record: the file route by which the report window reaches the report program.
    // =================================================================================================

    @Nested
    @DisplayName("date-parameter record - twenty-one significant bytes inside an eighty-byte image, "
            + "raising the module's own validation failure rather than the framework's")
    class DateParmRecordParsing {

        private JobParameterValidators.ReportDateWindow parse(final String recordText) {
            return JobParameterValidatorsRuleComplianceTest.this.validators.parseDateParmRecord(recordText);
        }

        private ValidationException refusal(final String recordText) {
            final ValidationException thrown = catchThrowableOfType(ValidationException.class,
                    () -> JobParameterValidatorsRuleComplianceTest.this.validators.parseDateParmRecord(recordText));
            assertThat(thrown)
                    .as("the record was expected to be refused and was not")
                    .isNotNull();
            return thrown;
        }

        @Test
        @DisplayName("a record carrying exactly the twenty-one significant bytes yields the window it "
                + "declares")
        void theSignificantBytesAloneYieldTheWindow() {
            final JobParameterValidators.ReportDateWindow window =
                    parse(dateParmRecord(MEASURED_WINDOW_START, MEASURED_WINDOW_END));

            assertThat(window.startDate()).isEqualTo(MEASURED_WINDOW_START);
            assertThat(window.endDate()).isEqualTo(MEASURED_WINDOW_END);
        }

        @Test
        @DisplayName("the full eighty-byte record image is accepted, because trailing spaces are the "
                + "padding a fixed-width area carries beyond its content")
        void theFullSpacePaddedImageIsAccepted() {
            final String image =
                    paddedRecordImage(dateParmRecord(MEASURED_WINDOW_START, MEASURED_WINDOW_END), ' ');

            assertThat(image).hasSize(DATEPARM_IMAGE_WIDTH);
            assertThat(parse(image))
                    .isEqualTo(new JobParameterValidators.ReportDateWindow(MEASURED_WINDOW_START,
                            MEASURED_WINDOW_END));
        }

        @Test
        @DisplayName("a low-value padded image is accepted too, because both fillers a fixed-width area "
                + "carries are padding rather than data")
        void theLowValuePaddedImageIsAccepted() {
            final String image =
                    paddedRecordImage(dateParmRecord(MEASURED_WINDOW_START, MEASURED_WINDOW_END), '\0');

            assertThat(parse(image).endDate()).isEqualTo(MEASURED_WINDOW_END);
        }

        @Test
        @DisplayName("a mixture of the two fillers is accepted, because either is padding wherever it "
                + "appears beyond the content")
        void aMixtureOfBothFillersIsAccepted() {
            assertThat(parse(dateParmRecord(MEASURED_WINDOW_START, MEASURED_WINDOW_END) + "  \0\0 ")
                    .startDate()).isEqualTo(MEASURED_WINDOW_START);
        }

        @Test
        @DisplayName("an absent record is refused as a missing field, because no window can be "
                + "established without one")
        void anAbsentRecordIsRefusedAsMissing() {
            final ValidationException thrown = refusal(null);

            assertThat(thrown.getMessage())
                    .isEqualTo("DATEPARM record was not supplied, so the report date window cannot be"
                            + " established");
            assertThat(thrown.fieldErrors()).hasSize(1);
            assertThat(thrown.fieldErrors().get(0).field()).isEqualTo("DATEPARM record");
            assertThat(thrown.fieldErrors().get(0).state())
                    .isEqualTo(ValidationException.FieldState.MISSING);
        }

        @ParameterizedTest(name = "encoded width {1}")
        @MethodSource("com.carddemo.batch.JobParameterValidatorsRuleComplianceTest#shortDateParmRecords")
        @DisplayName("a record too short to fill the twenty-one-byte receiving group is refused, and the "
                + "measured width is reported")
        void aRecordTooShortToFillTheGroupIsRefused(final String recordText, final int measured) {
            final ValidationException thrown = refusal(recordText);

            assertThat(thrown.getMessage())
                    .contains("must carry at least " + DATEPARM_SIGNIFICANT_WIDTH + " encoded"
                            + " bytes - two 10-character dates either side of one separator - but measures "
                            + measured);
            assertThat(thrown.fieldErrors().get(0).state())
                    .isEqualTo(ValidationException.FieldState.INVALID);
        }

        /**
         * A bound stated as a minimum is not the same claim as a bound stated as an equality.
         *
         * <p>A sending item too small to fill the receiving group has no legacy reading at all, because
         * the record area is fixed at eighty bytes and a shorter one could not have been read from it.
         * A sending item larger than the group has a perfectly definite legacy reading - the leading
         * bytes - so the bound is a floor and not a width. This test pins the direction, because a
         * refusal phrased as an equality would read identically on every row of the table above while
         * rejecting the eighty-byte image the reporting job actually supplies.</p>
         */
        @Test
        @DisplayName("the width bound is a floor rather than an equality, so non-padding content beyond "
                + "the group is discarded with the rest of the tail rather than refused")
        void nonPaddingContentBeyondTheGroupIsDiscardedRatherThanRefused() {
            final String withTrailingContent =
                    dateParmRecord(MEASURED_WINDOW_START, MEASURED_WINDOW_END) + "X";

            assertThat(withTrailingContent).hasSize(DATEPARM_SIGNIFICANT_WIDTH + 1);
            assertThat(parse(withTrailingContent))
                    .as("the tail is not inspected, so it does not matter that it is not padding")
                    .isEqualTo(new JobParameterValidators.ReportDateWindow(MEASURED_WINDOW_START,
                            MEASURED_WINDOW_END));
        }

        /**
         * Leading padding is not discarded, and the refusal that proves it is the separator check.
         *
         * <p>A leading space does not make the record too short - it makes it one byte longer, and the
         * group move reads the leading twenty-one bytes regardless. What it does is displace every field
         * by one position, so the eleventh byte of the group is no longer the separator. The refusal
         * therefore arrives from the positional check rather than the width check, and that is the
         * stronger evidence of the two: it shows the layout is read by absolute position, which a width
         * complaint would not have shown.</p>
         */
        @Test
        @DisplayName("leading padding is not discarded, because the layout starts its first field in the "
                + "first position, so a leading space displaces every field by one")
        void leadingPaddingIsNotDiscarded() {
            assertThat(refusal(" " + dateParmRecord(MEASURED_WINDOW_START, MEASURED_WINDOW_END))
                    .getMessage())
                    .contains("must carry a single separator character in position 11")
                    .as("the displaced group is what is reported, not the untouched tail")
                    .contains("value [ " + MEASURED_WINDOW_START + " 2022-07-0]");
        }

        /**
         * An all-padding image is neither absent nor short: it is eighty bytes of the wrong content.
         *
         * <p>Padding is not stripped before the width is measured, so an image of eighty spaces measures
         * eighty and fills the receiving group. Nor is it refused at the separator, because the
         * separator this layout declares between the two dates <em>is</em> a space, and position eleven
         * of an all-space group holds one. The record therefore reaches the date cascade, where the
         * ten-space start date is refused - and the refusal is attributed to the record's start-date
         * field rather than to the record as a whole, which is what tells an operator where in the
         * eighty bytes to look.</p>
         */
        @Test
        @DisplayName("an image of nothing but padding fills the group and is refused by the date cascade, "
                + "attributed to the field the bad content occupies")
        void anAllPaddingImageIsRefusedByTheDateCascade() {
            final ValidationException thrown = refusal(" ".repeat(DATEPARM_IMAGE_WIDTH));

            assertThat(thrown.getMessage())
                    .contains("DATEPARM record start date")
                    .contains("is not a valid calendar date in the form YYYY-MM-DD")
                    .as("padding is not stripped before the width is measured, so this is not a width "
                            + "failure")
                    .doesNotContain("must carry at least");
            assertThat(thrown.fieldErrors().get(0).state())
                    .isEqualTo(ValidationException.FieldState.INVALID);
        }

        @Test
        @DisplayName("the eleventh position must carry the separator the layout declares between the two "
                + "dates")
        void theEleventhPositionMustCarryTheSeparator() {
            assertThat(refusal("2022-01-01|2022-07-06").getMessage())
                    .isEqualTo("Job parameter [DATEPARM record] value [2022-01-01|2022-07-06] must carry a"
                            + " single separator character in position 11, between the two dates");
        }

        @Test
        @DisplayName("a record carrying a character the record area cannot represent is refused before it "
                + "is encoded")
        void aNonRepresentableRecordIsRefusedBeforeEncoding() {
            assertThat(refusal(dateParmRecord(MEASURED_WINDOW_START, "2022-07-0\u00e9")).getMessage())
                    .contains("carries a character outside the single-byte character set the record area"
                            + " reserves bytes for");
        }

        @Test
        @DisplayName("a bad start date inside the record is attributed to the record's start-date "
                + "position, not to a job parameter key")
        void aBadStartDateIsAttributedToTheRecordPosition() {
            assertThat(refusal(dateParmRecord("2022-13-01", MEASURED_WINDOW_END)).getMessage())
                    .isEqualTo("Job parameter [DATEPARM record start date] value [2022-13-01] is not a"
                            + " valid calendar date in the form YYYY-MM-DD");
        }

        @Test
        @DisplayName("a bad end date inside the record is attributed to the record's end-date position")
        void aBadEndDateIsAttributedToTheRecordPosition() {
            assertThat(refusal(dateParmRecord(MEASURED_WINDOW_START, "2022-02-30")).getMessage())
                    .isEqualTo("Job parameter [DATEPARM record end date] value [2022-02-30] is not a valid"
                            + " calendar date in the form YYYY-MM-DD");
        }

        @Test
        @DisplayName("the inclusive-ordering test applies on the file route exactly as on the sort-symbol "
                + "route, because one cascade serves both")
        void theOrderingTestAppliesOnTheFileRouteToo() {
            assertThat(refusal(dateParmRecord(MEASURED_WINDOW_END, MEASURED_WINDOW_START)).getMessage())
                    .contains("[DATEPARM record start date]")
                    .contains("follows [DATEPARM record end date]");
            assertThat(parse(dateParmRecord(MEASURED_WINDOW_START, MEASURED_WINDOW_START)).startDate())
                    .isEqualTo(MEASURED_WINDOW_START);
        }

        @Test
        @DisplayName("every refusal on this route is the module's own validation failure, because a "
                + "record is being parsed rather than a parameter set validated")
        void everyRefusalIsTheModulesOwnValidationFailure() {
            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> parse("2022-01-01|2022-07-06"));
        }

        @Test
        @DisplayName("the window a well-formed record yields answers the inclusive membership question")
        void theWindowYieldedAnswersTheMembershipQuestion() {
            final JobParameterValidators.ReportDateWindow window =
                    parse(paddedRecordImage(dateParmRecord(MEASURED_WINDOW_START, MEASURED_WINDOW_END),
                            ' '));

            assertThat(window.includes("2022-04-15")).isTrue();
            assertThat(window.includes("2021-12-31")).isFalse();
        }
    }

    // =================================================================================================
    // The window the record route produces.
    // =================================================================================================

    @Nested
    @DisplayName("report date window - two inclusive bounds held as text, compared lexically")
    class TheReportDateWindow {

        private final JobParameterValidators.ReportDateWindow window =
                new JobParameterValidators.ReportDateWindow(MEASURED_WINDOW_START, MEASURED_WINDOW_END);

        @Test
        @DisplayName("both bounds are mandatory, because the only producer has already run the full "
                + "cascade and a null here could only mean it drifted")
        void bothBoundsAreMandatory() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() ->
                            new JobParameterValidators.ReportDateWindow(null, MEASURED_WINDOW_END))
                    .withMessage("startDate must not be null");
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() ->
                            new JobParameterValidators.ReportDateWindow(MEASURED_WINDOW_START, null))
                    .withMessage("endDate must not be null");
        }

        @Test
        @DisplayName("the bounds are held exactly as supplied, never converted to a date type and back")
        void theBoundsAreHeldExactlyAsSupplied() {
            assertThat(this.window.startDate()).isEqualTo(MEASURED_WINDOW_START);
            assertThat(this.window.endDate()).isEqualTo(MEASURED_WINDOW_END);
        }

        @ParameterizedTest(name = "includes({0}) is {1}")
        @MethodSource("com.carddemo.batch.JobParameterValidatorsRuleComplianceTest#membershipCases")
        @DisplayName("membership is inclusive at both bounds, so a window selects the days at its edges "
                + "rather than excluding them")
        void membershipIsInclusiveAtBothBounds(final String processingDate, final boolean expected) {
            assertThat(this.window.includes(processingDate)).isEqualTo(expected);
        }

        @Test
        @DisplayName("a window whose start equals its end selects exactly that one day")
        void aSingleDayWindowSelectsExactlyThatDay() {
            final JobParameterValidators.ReportDateWindow single =
                    new JobParameterValidators.ReportDateWindow(MEASURED_WINDOW_START,
                            MEASURED_WINDOW_START);

            assertThat(single.includes(MEASURED_WINDOW_START)).isTrue();
            assertThat(single.includes("2022-01-02")).isFalse();
            assertThat(single.includes("2021-12-31")).isFalse();
        }

        @Test
        @DisplayName("an absent processing date is refused rather than compared")
        void anAbsentProcessingDateIsRefused() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> this.window.includes(null))
                    .withMessage("processingDate must not be null");
        }

        @ParameterizedTest(name = "[{0}]")
        @ValueSource(strings = {"2022-01-1", "2022-01-011", "", "2022-01-01 "})
        @DisplayName("a processing date of the wrong width is refused, because a comparison between "
                + "operands of different widths is not the comparison the legacy performs")
        void aProcessingDateOfTheWrongWidthIsRefused(final String processingDate) {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> this.window.includes(processingDate))
                    .withMessageContaining("processingDate must be exactly 10 encoded bytes to be compared"
                            + " against this window");
        }

        @Test
        @DisplayName("a processing date carrying a character the field cannot represent is refused, "
                + "because it could not occupy the ten bytes the comparison assumes")
        void aNonRepresentableProcessingDateIsRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> this.window.includes("2022-01-0\u00e9"));
        }

        @Test
        @DisplayName("the argument is the ten-character date portion, so an untrimmed timestamp is a "
                + "width error rather than a silent comparison")
        void anUntrimmedTimestampIsAWidthError() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> this.window.includes("2022-04-15 11:22:33.123456"));
        }

        @Test
        @DisplayName("two windows over the same bounds are equal and hash alike, and their rendering "
                + "names both components")
        void valueSemanticsHold() {
            final JobParameterValidators.ReportDateWindow same =
                    new JobParameterValidators.ReportDateWindow(MEASURED_WINDOW_START,
                            MEASURED_WINDOW_END);
            final JobParameterValidators.ReportDateWindow different =
                    new JobParameterValidators.ReportDateWindow(MEASURED_WINDOW_START,
                            MEASURED_WINDOW_START);

            assertThat(this.window).isEqualTo(same).hasSameHashCodeAs(same).isNotEqualTo(different);
            assertThat(this.window.toString())
                    .contains("startDate=" + MEASURED_WINDOW_START)
                    .contains("endDate=" + MEASURED_WINDOW_END);
        }
    }

    // =================================================================================================
    // Diagnostic integrity. Every message and every log record must be safe to write into a log, because
    // a launch value is caller supplied and parameter substitution escapes nothing.
    // =================================================================================================

    @Nested
    @DisplayName("diagnostic integrity - no caller value reaches a message or a log record unrenderable")
    class DiagnosticIntegrity {

        /** The logger of the class under test, captured so its diagnostics can be inspected. */
        private Logger subjectLogger;

        /** Records every diagnostic the class under test emits while a test runs. */
        private ListAppender<ILoggingEvent> logRecorder;

        /** The logger's level before this test pinned it, restored afterwards. */
        private Level originalLevel;

        @BeforeEach
        void attachLogRecorder() {
            // The accepting paths log at debug, so capture must not depend on whatever level the ambient
            // logging configuration happens to set. The level is pinned for the duration of the test and
            // restored in the matching teardown.
            this.subjectLogger = (Logger) LoggerFactory.getLogger(JobParameterValidators.class);
            this.originalLevel = this.subjectLogger.getLevel();
            this.logRecorder = new ListAppender<>();
            this.logRecorder.setContext(this.subjectLogger.getLoggerContext());
            this.logRecorder.start();
            this.subjectLogger.addAppender(this.logRecorder);
            this.subjectLogger.setLevel(Level.TRACE);
        }

        @AfterEach
        void detachLogRecorder() {
            this.subjectLogger.detachAppender(this.logRecorder);
            this.logRecorder.stop();
            this.subjectLogger.setLevel(this.originalLevel);
        }

        /** Asserts that nothing the class logged carries a character a log record must not contain. */
        private void assertEveryLogRecordIsSafe(final String context) {
            for (final ILoggingEvent event : this.logRecorder.list) {
                assertSafeToLog(event.getFormattedMessage(), context + " log record");
            }
        }

        @ParameterizedTest(name = "code point {1} at position {2}")
        @MethodSource("com.carddemo.batch.JobParameterValidatorsRuleComplianceTest#hostileInterestParameters")
        @DisplayName("a control character in the interest parameter is neither echoed into the rejection "
                + "message nor into the log record the calendar cascade emits")
        void aControlCharacterInTheInterestParameterIsNeverEchoed(final String value, final int codePoint,
                                                                 final int position) {

            final String diagnostic = rejection(
                    JobParameterValidatorsRuleComplianceTest.this.validators.interestParmDateValidator(),
                    parameters(JobParameterValidators.INTEREST_PARM_DATE_KEY, value));

            assertDegradedAt(diagnostic, position, codePoint);
            assertThat(diagnostic)
                    .as("the raw value must not survive into the message")
                    .doesNotContain(value);
            assertEveryLogRecordIsSafe("the interest cascade");
        }

        @ParameterizedTest(name = "code point {1} at position {2}")
        @MethodSource("com.carddemo.batch.JobParameterValidatorsRuleComplianceTest#hostileIsoDates")
        @DisplayName("a control character in a report bound is neither echoed into the rejection message "
                + "nor into the log record the calendar cascade emits")
        void aControlCharacterInAReportBoundIsNeverEchoed(final String value, final int codePoint,
                                                          final int position) {

            final String diagnostic = rejection(
                    JobParameterValidatorsRuleComplianceTest.this.validators.reportDateRangeValidator(),
                    parameters(JobParameterValidators.REPORT_START_DATE_KEY, value,
                            JobParameterValidators.REPORT_END_DATE_KEY, MEASURED_WINDOW_END));

            assertDegradedAt(diagnostic, position, codePoint);
            assertThat(diagnostic).doesNotContain(value);
            assertEveryLogRecordIsSafe("the report window cascade");
        }

        @Test
        @DisplayName("the log record the calendar cascade emits on a rejection names the mask and the "
                + "degraded value, which proves the full ten characters were delegated unsliced")
        void theCalendarRejectionLogNamesTheMaskAndTheDegradedValue() {
            rejection(JobParameterValidatorsRuleComplianceTest.this.validators.reportDateRangeValidator(),
                    parameters(JobParameterValidators.REPORT_START_DATE_KEY, "2022-\n1-01",
                            JobParameterValidators.REPORT_END_DATE_KEY, MEASURED_WINDOW_END));

            assertThat(this.logRecorder.list)
                    .as("the calendar cascade must record why it rejected the value")
                    .anySatisfy(event -> assertThat(event.getFormattedMessage())
                            .contains("Calendar authority rejected")
                            .contains("under mask [YYYY-MM-DD]")
                            .contains("zero-based position 5")
                            .contains("code point 10"));
            assertEveryLogRecordIsSafe("the calendar cascade");
        }

        @Test
        @DisplayName("the interest cascade delegates the full ten characters with the compact mask, not "
                + "a leading eight-character slice")
        void theInterestCascadeDelegatesTheFullTenCharactersWithTheCompactMask() {
            rejection(JobParameterValidatorsRuleComplianceTest.this.validators.interestParmDateValidator(),
                    parameters(JobParameterValidators.INTEREST_PARM_DATE_KEY, "2022023000"));

            assertThat(this.logRecorder.list)
                    .anySatisfy(event -> assertThat(event.getFormattedMessage())
                            .contains("Calendar authority rejected [2022023000]")
                            .contains("under mask [YYYYMMDD]"));
        }

        @Test
        @DisplayName("a control character in the probe mode is not echoed into the unmatched-value "
                + "diagnostic")
        void aControlCharacterInTheProbeModeIsNeverEchoed() {
            final String diagnostic = rejection(
                    JobParameterValidatorsRuleComplianceTest.this.validators.fileProbeModeValidator(LEGAL_PROBE_MODES),
                    parameters(JobParameterValidators.FILE_PROBE_MODE_KEY, "ACC\nOUNT"));

            assertDegradedAt(diagnostic, 3, 10);
            assertThat(diagnostic).doesNotContain("ACC\nOUNT");
        }

        @ParameterizedTest(name = "{1}")
        @MethodSource("com.carddemo.batch.JobParameterValidatorsRuleComplianceTest#hostileDateParmRecords")
        @DisplayName("a control character anywhere in the date-parameter record is not echoed into the "
                + "refusal, whichever stage of the cascade refuses it")
        void aControlCharacterInTheRecordIsNeverEchoed(final String recordText, final String stage,
                                                       final int codePoint, final int position) {

            final ValidationException thrown = catchThrowableOfType(ValidationException.class,
                    () -> JobParameterValidatorsRuleComplianceTest.this.validators.parseDateParmRecord(recordText));

            assertThat(thrown).as("stage %s was expected to refuse the record", stage).isNotNull();
            assertDegradedAt(thrown.getMessage(), position, codePoint);
            assertThat(thrown.getMessage()).doesNotContain(recordText);
            assertEveryLogRecordIsSafe("the record parser");
        }

        /**
         * A control character beyond the receiving group is defended against by not being read.
         *
         * <p>Every other row of the hostile-record table lands inside the twenty-one bytes the group move
         * reads, so a renderer has to keep it out of the diagnostic. A character in the tail is a
         * different case with a stronger answer: the group move discards the tail before any field is
         * sliced, so the character never reaches a diagnostic because it never reaches the parser. The
         * record is accepted, and what the accepting path logs is composed from slices of the group
         * alone.</p>
         *
         * <p>This is worth pinning precisely because the record is <em>accepted</em>. A row asserting a
         * refusal here would have been asserting the withdrawn "exactly twenty-one" bound rather than
         * anything about diagnostics, and would have passed for the wrong reason.</p>
         */
        @Test
        @DisplayName("a control character beyond the twenty-one-byte group reaches no diagnostic, because "
                + "the group move discards the tail before any field is read")
        void aControlCharacterBeyondTheGroupReachesNoDiagnostic() {
            final String withHostileTail =
                    dateParmRecord(MEASURED_WINDOW_START, MEASURED_WINDOW_END) + "\n";

            final JobParameterValidators.ReportDateWindow window =
                    JobParameterValidatorsRuleComplianceTest.this.validators
                            .parseDateParmRecord(withHostileTail);

            assertThat(window)
                    .isEqualTo(new JobParameterValidators.ReportDateWindow(MEASURED_WINDOW_START,
                            MEASURED_WINDOW_END));
            assertEveryLogRecordIsSafe("the accepting path of the record parser");
            assertThat(this.logRecorder.list)
                    .as("the accepting path must have logged, or this would prove nothing")
                    .isNotEmpty();
        }

        @Test
        @DisplayName("a control character in a processing date is not echoed into the width refusal the "
                + "window raises")
        void aControlCharacterInAProcessingDateIsNeverEchoed() {
            final JobParameterValidators.ReportDateWindow window =
                    new JobParameterValidators.ReportDateWindow(MEASURED_WINDOW_START,
                            MEASURED_WINDOW_END);

            final IllegalArgumentException thrown = catchThrowableOfType(IllegalArgumentException.class,
                    () -> window.includes("2022-01-\n"));

            assertThat(thrown).isNotNull();
            assertDegradedAt(thrown.getMessage(), 8, 10);
            assertThat(thrown.getMessage()).doesNotContain("2022-01-\n");
        }

        @Test
        @DisplayName("the accepting paths log too, and what they log is safe: a legitimate value is "
                + "reproduced verbatim, which is what keeps an ordinary diagnostic actionable")
        void theAcceptingPathsLogSafelyAndVerbatim() throws JobParametersInvalidException {
            final JobParameterValidators subject = JobParameterValidatorsRuleComplianceTest.this.validators;

            subject.interestParmDateValidator().validate(
                    parameters(JobParameterValidators.INTEREST_PARM_DATE_KEY,
                            MEASURED_INTEREST_PARM_DATE));
            subject.reportDateRangeValidator().validate(
                    parameters(JobParameterValidators.REPORT_START_DATE_KEY, MEASURED_WINDOW_START,
                            JobParameterValidators.REPORT_END_DATE_KEY, MEASURED_WINDOW_END));
            subject.fileProbeModeValidator(LEGAL_PROBE_MODES).validate(
                    parameters(JobParameterValidators.FILE_PROBE_MODE_KEY, "ACCOUNT"));
            subject.parseDateParmRecord(dateParmRecord(MEASURED_WINDOW_START, MEASURED_WINDOW_END));

            assertEveryLogRecordIsSafe("an accepting path");
            assertThat(this.logRecorder.list)
                    .as("all four accepting paths must have recorded their outcome")
                    .hasSizeGreaterThanOrEqualTo(4);
            assertThat(this.logRecorder.list)
                    .anySatisfy(event -> assertThat(event.getFormattedMessage())
                            .isEqualTo("Interest parameter date accepted verbatim: ["
                                    + MEASURED_INTEREST_PARM_DATE + "]"))
                    .anySatisfy(event -> assertThat(event.getFormattedMessage())
                            .isEqualTo("Report date window accepted: [" + MEASURED_WINDOW_START
                                    + "] through [" + MEASURED_WINDOW_END + "], both bounds inclusive"))
                    .anySatisfy(event -> assertThat(event.getFormattedMessage())
                            .isEqualTo("File-probe mode accepted: [ACCOUNT]"))
                    .anySatisfy(event -> assertThat(event.getFormattedMessage())
                            .isEqualTo("Report date window read from the DATEPARM record: ["
                                    + MEASURED_WINDOW_START + "] through [" + MEASURED_WINDOW_END
                                    + "], both bounds inclusive"));
        }

        @Test
        @DisplayName("a legitimate value is still named in a rejection, so the substitution costs nothing "
                + "an operator relies on")
        void aLegitimateValueIsStillNamedInARejection() {
            assertThat(rejection(
                    JobParameterValidatorsRuleComplianceTest.this.validators.interestParmDateValidator(),
                    parameters(JobParameterValidators.INTEREST_PARM_DATE_KEY, "2022-07-18")))
                    .contains("value [2022-07-18]");
        }

        @Test
        @DisplayName("the value renderer substitutes for an absent value rather than rendering the word "
                + "null, which is the defensive arm that keeps a later diagnostic from having to "
                + "null-check for itself")
        void theValueRendererSubstitutesForAnAbsentValue() throws ReflectiveOperationException {
            // No reachable call path hands this renderer an absent value today, because every caller
            // reports absence with its own message first. The arm exists so that a diagnostic added later
            // cannot reopen the hole by passing one, and it is exercised directly because that is the only
            // way to demonstrate a defence whose whole purpose is to be unreachable.
            final Method renderer =
                    JobParameterValidators.class.getDeclaredMethod("describeValue", String.class);
            renderer.setAccessible(true);

            assertThat(renderer.invoke(null, (Object) null)).isEqualTo("(absent)");
            assertThat(renderer.invoke(null, "ACCOUNT")).isEqualTo("[ACCOUNT]");
            assertThat(renderer.invoke(null, "")).isEqualTo("[]");
            assertThat((String) renderer.invoke(null, "A\u0007B"))
                    .isEqualTo("(not printable US-ASCII: the character at zero-based position 1 is code"
                            + " point 7)");
        }
    }

    // =================================================================================================
    // Argument providers. Held at the outer level so that a nested group can name them by their fully
    // qualified method reference, and so that a hostile literal is written once.
    // =================================================================================================

    /** Interest parameters whose encoded width is not the ten bytes the field reserves. */
    static Stream<Arguments> wrongWidthInterestParameters() {
        return Stream.of(
                Arguments.of("20220718", 8),
                Arguments.of("202207180", 9),
                Arguments.of("20220718000", 11),
                Arguments.of("202207180000", 12),
                Arguments.of("", 0),
                Arguments.of("   ", 3));
    }

    /** Report bounds whose encoded width is not the ten bytes the sort symbol declares. */
    static Stream<Arguments> wrongWidthIsoDates() {
        return Stream.of(
                Arguments.of("2022-01-1", 9),
                Arguments.of("2022-01-011", 11),
                Arguments.of("2022-1-1", 8),
                Arguments.of("", 0));
    }

    /**
     * Date-parameter records too short to fill the twenty-one-byte receiving group.
     *
     * <p>Every row is <em>shorter</em> than the group on purpose. A record longer than the group is not a
     * width failure at all: the legacy read moves an eighty-byte record area into a twenty-one-byte
     * group, and a COBOL group move keeps the leading bytes of the sender and discards the remainder
     * silently, so a longer record is accepted and its tail is never read. Refusing one would reject
     * input the mainframe accepted, and the withdrawal of that refusal is reasoned in
     * {@code docs/decision-log.md} DL-107. The acceptance tests carry those cases instead.</p>
     */
    static Stream<Arguments> shortDateParmRecords() {
        return Stream.of(
                Arguments.of("2022-01-01 2022-07-0", 20),
                Arguments.of("2022-01-012022-07-06", 20),
                Arguments.of("", 0));
    }

    /** Processing dates and whether the measured window admits each of them. */
    static Stream<Arguments> membershipCases() {
        return Stream.of(
                Arguments.of(MEASURED_WINDOW_START, true),
                Arguments.of(MEASURED_WINDOW_END, true),
                Arguments.of("2022-04-15", true),
                Arguments.of("2022-01-02", true),
                Arguments.of("2022-07-05", true),
                Arguments.of("2021-12-31", false),
                Arguments.of("2022-07-07", false),
                Arguments.of("2023-01-01", false));
    }

    /**
     * Probe-mode entries the configuration boundary must refuse, each with the code point and the
     * zero-based position its guard has to name.
     */
    static Stream<Arguments> unrenderableProbeModeEntries() {
        return Stream.of(
                Arguments.of("AC\nT", 10, 2),
                Arguments.of("AC\rT", 13, 2),
                Arguments.of("AC\u0000T", 0, 2),
                Arguments.of("AC\tT", 9, 2),
                Arguments.of("AC\u007fT", 127, 2),
                Arguments.of("AC\u00e9T", 233, 2));
    }

    /**
     * Ten-byte interest parameters carrying a character that must never be reproduced, each with the code
     * point and the zero-based position the degraded diagnostic has to name. Every one is representable in
     * a single byte, so each reaches a stage that would previously have echoed it.
     */
    static Stream<Arguments> hostileInterestParameters() {
        return Stream.of(
                Arguments.of("2022\n07180", 10, 4),
                Arguments.of("2022\r07180", 13, 4),
                Arguments.of("2022\u000007180", 0, 4),
                Arguments.of("2022\t07180", 9, 4),
                Arguments.of("2022\u001b07180", 27, 4),
                Arguments.of("20220718\u007f0", 127, 8));
    }

    /**
     * Ten-byte report bounds carrying a character that must never be reproduced. Each is representable
     * and of the declared width, so each reaches the delegated calendar cascade and its rejection log.
     */
    static Stream<Arguments> hostileIsoDates() {
        return Stream.of(
                Arguments.of("2022-\n1-01", 10, 5),
                Arguments.of("2022-\r1-01", 13, 5),
                Arguments.of("2022-0\u0000-01", 0, 6),
                Arguments.of("2022-01-0\u001b", 27, 9),
                Arguments.of("\n022-01-01", 10, 0));
    }

    /**
     * Date-parameter records carrying a character that must never be reproduced, each labelled with the
     * cascade stage that refuses it so a failure names the path rather than only the input.
     */
    static Stream<Arguments> hostileDateParmRecords() {
        return Stream.of(
                Arguments.of("2022-01-01\n2022-07-06", "separator", 10, 10),
                Arguments.of("2022-01-01\u00002022-07-06", "separator", 0, 10),
                Arguments.of("2022-01-01 2022-07-0\n", "end date", 10, 9),
                Arguments.of("\n022-01-01 2022-07-06", "start date", 10, 0),
                Arguments.of("2022-01-01 2022-07-0\u00e9", "representability", 233, 20));
    }
}
