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

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentMatchers;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.JobParametersInvalidException;
import org.springframework.batch.core.JobParametersValidator;

import com.carddemo.exception.ValidationException;
import com.carddemo.service.DateValidationService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * Unit test for {@link JobParameterValidators}, the single supplier of every launch-time parameter
 * contract the migrated batch tier carries.
 *
 * <h2>Tier, and why it is this tier</h2>
 *
 * <p>A pure unit test. The class under test reaches no database, no queue and no object store, so it
 * belongs to the fast in-process tier: the subject is constructed with {@code new} and its one
 * collaborator is a Mockito mock. Nothing here starts a container or an application context, which is
 * what keeps the class inside the unit include set rather than the integration one.</p>
 *
 * <h2>Why the collaborator is mocked, and what that buys</h2>
 *
 * <p>Every calendar decision the class reaches is delegated to the strict calendar authority. Mocking
 * that authority is what makes the delegation itself <em>observable</em>, and three properties of the
 * contract can only be proven by observing it:</p>
 *
 * <ul>
 *   <li><strong>The candidate crosses the boundary unaltered.</strong> The interest parameter's ten
 *       characters are handed over verbatim, alongside the compact mask, rather than sliced down to
 *       their calendar portion first. That is asserted on the recorded arguments.</li>
 *   <li><strong>The verdict is reported, never re-derived.</strong> Stubbing the authority to reject a
 *       value it would otherwise accept flips the outcome, and stubbing it to accept a value it would
 *       otherwise reject flips it back. One input, two outcomes, decided solely by the collaborator, is
 *       proof that no second calendar rule is hidden in the subject.</li>
 *   <li><strong>The width stage runs before the delegation.</strong> An over-long value produces
 *       <em>no</em> interaction at all, which is the only way to show it was refused on width instead of
 *       being quietly truncated into a value the authority would have accepted.</li>
 * </ul>
 *
 * <p>So that a stub cannot also become the thing under test, the mock's default behaviour answers
 * exactly as the real authority would. Strict resolution therefore remains genuine here: an impossible
 * calendar date is refused rather than rolled onto a neighbouring valid day, which is the property that
 * replaces the legacy language-environment date routine. The narrower hand-written verdicts are used
 * only in the delegation group, where the point is precisely to make the authority disagree with itself.
 * No clock is involved anywhere: not one of the four contracts is relative to the current date, so
 * nothing here has a reason to read one.</p>
 *
 * <h2>Every expectation is authored here</h2>
 *
 * <p>Widths, offsets, keys and diagnostics below are written out as literals restating the measured
 * legacy declarations and the subject's own published contract. None is read back from the class under
 * test, and no expected value is produced by calling the method it is meant to check. Widths are
 * asserted on the <em>encoded</em> image, because every legacy declaration reserves bytes rather than
 * characters. The two authentic legacy literals serve as the accepting fixtures: the ten-character
 * all-digit interest parameter the interest job stream supplied at {@code [app/jcl/INTCALC.jcl:L22]},
 * and the report window bounds carried as sort symbols at {@code [app/jcl/TRANREPT.jcl:L43]} and
 * {@code [app/jcl/TRANREPT.jcl:L44]}.</p>
 *
 * <h2>Two delivery routes for one report window</h2>
 *
 * <p>The report pipeline receives its window twice over, and both routes are exercised. The sort step
 * takes the two dates as sort symbols, declared at {@code [app/jcl/TRANREPT.jcl:L43]} and
 * {@code [app/jcl/TRANREPT.jcl:L44]}; the report program instead reads them from a sequential file
 * assigned to the control data definition, pointed at a catalogued dataset with shared disposition at
 * {@code [app/jcl/TRANREPT.jcl:L73]}. Because the dataset is catalogued rather than supplied in stream,
 * no fixture file exists or is created for it: the record is composed in the test as a string.</p>
 *
 * <h2>Why the hyphenated form is a contract and not a preference</h2>
 *
 * <p>The legacy filter never converts a date. It compares the record's processing-date field as a
 * <em>character</em> field, declared at offset 305 for ten bytes as character data at
 * {@code [app/jcl/TRANREPT.jcl:L42]}, and the report program applies the same character comparison over
 * the leading ten positions of the processing timestamp at {@code [app/cbl/CBTRN03C.cbl:L173]} and
 * {@code [app/cbl/CBTRN03C.cbl:L174]}. Character ordering coincides with chronological ordering only
 * because the form is zero-padded with hyphens; a rendering such as a single-digit month, or a
 * day-first form, would order wrongly under the very comparison the report depends on. Both are
 * therefore asserted to be <em>refused</em> rather than merely tolerated.</p>
 *
 * <h2>Both bounds are inclusive, and that is measured</h2>
 *
 * <p>The sort filter tests the processing date with greater-or-equal against the start value and
 * less-or-equal against the end value at {@code [app/jcl/TRANREPT.jcl:L47]} and
 * {@code [app/jcl/TRANREPT.jcl:L48]}, and the report program applies the identical pair at
 * {@code [app/cbl/CBTRN03C.cbl:L173]} and {@code [app/cbl/CBTRN03C.cbl:L174]}. A window whose start
 * equals its end therefore selects that one day, and the single-day case is asserted on both delivery
 * routes so neither can drift to an exclusive bound.</p>
 *
 * <h2>Source anomalies recorded here, propagated nowhere</h2>
 *
 * <p>Four oddities in the legacy members behind these contracts are recorded as candidates for the
 * migration decision log. None is reproduced in code, and none is silently corrected:</p>
 *
 * <ul>
 *   <li>The cataloged report procedure declares its internal label with the same name the unload
 *       procedure uses, at {@code [app/proc/TRANREPT.prc:L1]}, and its own first step then invokes that
 *       name at {@code [app/proc/TRANREPT.prc:L21]} - self-invocation as literally written. The member
 *       name resolves, so the internal label is dead and the member is effectively dead with it. This
 *       sits outside the fourteen-item anomaly register.</li>
 *   <li>The report job stream names two different steps identically, at
 *       {@code [app/jcl/TRANREPT.jcl:L23]} and {@code [app/jcl/TRANREPT.jcl:L37]}, where the cataloged
 *       form uses three distinct names. Distinct step names are generated in the target, so the
 *       collision is not carried across.</li>
 *   <li>The statement job stream carries no program parameter on any of its five steps. No date
 *       parameter is therefore invented for the statement job and no statement-job parameter contract
 *       appears in this test; the similarly named sort symbols belong to the report job alone.</li>
 *   <li>The customer-probe job stream misspells the system-user notification symbol at
 *       {@code [app/jcl/READCUST.jcl:L2]}. It has no equivalent in the target, so no notification
 *       configuration key is invented to carry it, and none is asserted.</li>
 * </ul>
 *
 * <h2>Scope</h2>
 *
 * <p>This proves the launch contract in isolation. It does not prove that any particular job attaches
 * these validators; that wiring is the business of the job configurations and of their own tests.</p>
 *
 * <h2>Provenance</h2>
 *
 * <p>Legacy estate at checkout SHA 7756d895ffeb65f7ea72aaa609e356d9899afcec, upstream release stamp
 * CardDemo_v1.0-15-g27d6c6f-68 dated 2022-07-19 - a matrix-header provenance string for the estate as a
 * whole, never a per-member assertion. The legacy antecedents behind these contracts are the interest
 * calculator {@code [app/cbl/CBACT04C.cbl]} with its job stream {@code [app/jcl/INTCALC.jcl]}, the
 * transaction report {@code [app/cbl/CBTRN03C.cbl]} with its job stream {@code [app/jcl/TRANREPT.jcl]}
 * and cataloged procedure {@code [app/proc/TRANREPT.prc]}, the statement job stream
 * {@code [app/jcl/CREASTMT.JCL]}, and the customer-probe job stream {@code [app/jcl/READCUST.jcl]}.</p>
 */
@DisplayName("JobParameterValidators :: the launch-time contract of the migrated batch jobs")
class JobParameterValidatorsTest {

    // =================================================================================================
    // Independently restated launch keys. These repeat the published spellings as plain literals, so a
    // rename of a key is a visible, deliberate change here rather than something the test follows
    // silently.
    // =================================================================================================

    /** Key of the interest-calculation parameter date. */
    private static final String KEY_INTEREST = "interestParmDate";

    /** Key of the inclusive lower bound of the report window. */
    private static final String KEY_REPORT_START = "reportStartDate";

    /** Key of the inclusive upper bound of the report window. */
    private static final String KEY_REPORT_END = "reportEndDate";

    /** Key of the file-probe mode. */
    private static final String KEY_PROBE_MODE = "fileProbeMode";

    // =================================================================================================
    // Independently restated widths and offsets. Every figure is a byte reservation read from a legacy
    // declaration, never a tuning figure, and every assertion below measures the encoded image.
    // =================================================================================================

    /** Width of the interest parameter's date field, at [app/cbl/CBACT04C.cbl:L178]. */
    private static final int INTEREST_WIDTH = 10;

    /** How many of those ten positions the compact mask interprets as a calendar date. */
    private static final int INTEREST_CALENDAR_WIDTH = 8;

    /** Width of the six-digit incrementing suffix declared at [app/cbl/CBACT04C.cbl:L173]. */
    private static final int INTEREST_SUFFIX_WIDTH = 6;

    /** Width of a hyphenated date, and of both report sort symbols. */
    private static final int ISO_WIDTH = 10;

    /** Significant width of the control record: ten, one separator, ten. */
    private static final int RECORD_SIGNIFICANT_WIDTH = 21;

    /** One-based position of the control record's separator, as its diagnostic reports it. */
    private static final int SEPARATOR_POSITION = 11;

    /** Width of the record area the control record is read into, at [app/cbl/CBTRN03C.cbl:L88]. */
    private static final int RECORD_IMAGE_WIDTH = 80;

    /** Width of the processing timestamp whose leading positions the report window is compared against. */
    private static final int PROCESSING_TIMESTAMP_WIDTH = 26;

    // =================================================================================================
    // The authentic legacy literals, used as the accepting fixtures rather than invented dates.
    // =================================================================================================

    /** The interest parameter the job stream actually supplied, at [app/jcl/INTCALC.jcl:L22]. */
    private static final String LEGACY_INTEREST_PARM = "2022071800";

    /** The report window's inclusive lower bound, at [app/jcl/TRANREPT.jcl:L43]. */
    private static final String LEGACY_WINDOW_START = "2022-01-01";

    /** The report window's inclusive upper bound, at [app/jcl/TRANREPT.jcl:L44]. */
    private static final String LEGACY_WINDOW_END = "2022-07-06";

    // =================================================================================================
    // The two masks the subject hands to the calendar authority, restated so the recorded arguments can
    // be asserted against a value this test authored.
    // =================================================================================================

    /** The compact mask, selecting an eight-position calendar interpretation. */
    private static final String COMPACT_MASK = "YYYYMMDD";

    /** The hyphenated mask, selecting a ten-position calendar interpretation. */
    private static final String HYPHENATED_MASK = "YYYY-MM-DD";

    // =================================================================================================
    // Diagnostic labels the control-record route attributes failures to. They are record positions
    // rather than launch keys, because no launch parameter is being validated on that route.
    // =================================================================================================

    /** Label of the control record as a whole. */
    private static final String LABEL_RECORD = "DATEPARM record";

    /** Label of the control record's start-date position. */
    private static final String LABEL_RECORD_START = "DATEPARM record start date";

    /** Label of the control record's end-date position. */
    private static final String LABEL_RECORD_END = "DATEPARM record end date";

    // =================================================================================================
    // Widths of the calendar authority's result block, restated so the hand-written verdicts below are
    // built to the block's own reservations rather than to a guess.
    // =================================================================================================

    /** Width of the severity and message-number positions of the result block. */
    private static final int VERDICT_CODE_WIDTH = 4;

    /** Width of the result-text position of the result block. */
    private static final int VERDICT_TEXT_WIDTH = 15;

    /** Width of the tested-date and mask positions of the result block. */
    private static final int VERDICT_LINKAGE_WIDTH = 10;

    /** The severity the authority reports for an accepted date. */
    private static final String SEVERITY_ACCEPTED = "0000";

    /** The severity the authority reports for every refusal it declares. */
    private static final String SEVERITY_REFUSED = "0003";

    /** The message number the authority reports alongside an accepted date. */
    private static final String MESSAGE_NUMBER_NONE = "0000";

    /** The message number the authority reports for a bad date value. */
    private static final String MESSAGE_NUMBER_BAD_VALUE = "2508";

    /** Text placed in the result block's text position; immaterial to every contract asserted here. */
    private static final String VERDICT_STUB_TEXT = "stub verdict";

    /** A character that cannot occupy a single byte, used to reach the representability stage. */
    private static final String NON_SINGLE_BYTE = "\u20ac";

    // =================================================================================================
    // The fixture. A fresh mock and a fresh subject per test method, because the recorded interactions
    // of one test must never be visible to another.
    // =================================================================================================

    /** The mocked calendar authority, answering by default exactly as the real authority would. */
    private final DateValidationService calendarAuthority = calendarFaithfulMock();

    /** The subject, constructed directly and given the mocked authority. */
    private final JobParameterValidators validators = new JobParameterValidators(calendarAuthority);

    /**
     * Builds the mocked calendar authority used by every test.
     *
     * <p>It is a Mockito mock, so each delegation is recorded and can be asserted on, and its default
     * answer forwards to a real authority instance, so the verdicts it returns are the real strict ones.
     * That combination is deliberate: a hand-written default would quietly become the thing under test,
     * and strict resolution - refusing an impossible calendar date instead of normalising it - would stop
     * being demonstrated by anything. Individual tests narrow the behaviour where disagreement is the
     * point.</p>
     *
     * @return a fresh mock, never {@code null}
     */
    private static DateValidationService calendarFaithfulMock() {
        final DateValidationService realAuthority = new DateValidationService();
        final DateValidationService mocked = Mockito.mock(DateValidationService.class);
        Mockito.when(mocked.validateDate(ArgumentMatchers.anyString(), ArgumentMatchers.anyString()))
                .thenAnswer(invocation -> realAuthority.validateDate(
                        invocation.getArgument(0, String.class),
                        invocation.getArgument(1, String.class)));
        Mockito.when(mocked.isDateAcceptable(ArgumentMatchers.any()))
                .thenAnswer(invocation -> realAuthority.isDateAcceptable(
                        invocation.getArgument(0, DateValidationService.SubprogramResult.class)));
        return mocked;
    }

    // =================================================================================================
    // Helpers. Each composes an expectation or a fixture with plain string operations, so no expected
    // value is ever produced by the class under test.
    // =================================================================================================

    /**
     * Measures a value in encoded bytes, which is the unit every legacy declaration reserves.
     *
     * <p>The single width primitive of this test. Character counting is deliberately never used, because
     * a picture clause reserves bytes: a value whose characters do not each encode to one byte would
     * satisfy a character count and still overrun the field it is declared for.</p>
     *
     * @param value the value to measure
     * @return the encoded width in bytes
     */
    private static int encodedWidth(final String value) {
        return value.getBytes(StandardCharsets.US_ASCII).length;
    }

    /**
     * Renders the common prefix of a diagnostic that names an offending name and its offending value.
     *
     * <p>Restated here rather than borrowed from the subject, so a change to the diagnostic shape has to
     * be made deliberately in two places instead of following silently in one.</p>
     *
     * @param name  the launch key or record-position label
     * @param value the offending value
     * @return the prefix, ending in the space a reason follows
     */
    private static String describe(final String name, final String value) {
        return "Job parameter [" + name + "] value [" + value + "] ";
    }

    /**
     * Builds a parameter set carrying one string parameter, or an empty set when the value is absent.
     *
     * @param key   the parameter key
     * @param value the parameter value, or {@code null} to omit the parameter entirely
     * @return the parameter set, never {@code null}
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
     * @return the parameter set, never {@code null}
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
     * The legal probe modes, taken from the configuration that owns the enumeration.
     *
     * <p>Read from the owner rather than restated, because the identity of the owner is itself part of
     * the contract: the subject validates against an enumeration handed to it and defines none of its
     * own. Restating the values here would let the two drift apart without any test noticing.</p>
     *
     * @return the legal launch values in declaration order, never empty
     */
    private static List<String> legalProbeModes() {
        return FileProbeJobConfig.ProbeMode.parameterValues();
    }

    /**
     * Renders the legal probe modes the way a diagnostic lists them, composed independently.
     *
     * @return the rendered enumeration in declaration order
     */
    private static String renderedLegalProbeModes() {
        return "[" + String.join(", ", legalProbeModes()) + "]";
    }

    /**
     * Runs a validator and returns the failure it raised, failing the test when it accepted instead.
     *
     * @param validator     the validator to exercise
     * @param jobParameters the parameter set to offer it
     * @return the raised failure, never {@code null}
     */
    private static JobParametersInvalidException rejectionOf(final JobParametersValidator validator,
            final JobParameters jobParameters) {

        final JobParametersInvalidException thrown = catchThrowableOfType(
                JobParametersInvalidException.class, () -> validator.validate(jobParameters));
        assertThat(thrown)
                .as("the parameter set was expected to be rejected, but the validator accepted it")
                .isNotNull();
        return thrown;
    }

    /**
     * Composes the significant content of a control record from two bounds and one separator.
     *
     * @param startDate the start-date position
     * @param separator the single character occupying the separator position
     * @param endDate   the end-date position
     * @return the composed content
     */
    private static String controlRecord(final String startDate, final char separator,
            final String endDate) {

        return startDate + separator + endDate;
    }

    /**
     * Composes a well-formed control record, whose separator position carries the space the layout
     * declares.
     *
     * @param startDate the start-date position
     * @param endDate   the end-date position
     * @return the composed content, exactly the significant width
     */
    private static String controlRecord(final String startDate, final String endDate) {
        return controlRecord(startDate, ' ', endDate);
    }

    /**
     * Pads content out to the width of the record area a control record is read into.
     *
     * <p>Padding is measured in encoded bytes for the same reason every other width here is: the area is
     * a byte reservation.</p>
     *
     * @param significant the content occupying the declared positions
     * @param padding     the character the area carries beyond that content
     * @return the padded image, exactly the record-area width
     */
    private static String paddedImage(final String significant, final char padding) {
        return significant + String.valueOf(padding).repeat(RECORD_IMAGE_WIDTH
                - encodedWidth(significant));
    }

    /**
     * Pads text on the right out to a declared byte width, for building a result block by hand.
     *
     * @param text  the content
     * @param width the declared width in encoded bytes
     * @return the padded value, exactly {@code width} encoded bytes
     */
    private static String padToWidth(final String text, final int width) {
        return text + " ".repeat(width - encodedWidth(text));
    }

    /**
     * Builds the verdict the calendar authority returns for a date it accepts.
     *
     * <p>Every position is composed to the block's own declared width, so the carrier is the shape a real
     * verdict has. Its text position is immaterial to every contract asserted here and deliberately holds
     * a value naming itself as a stub rather than reproducing the authority's own wording.</p>
     *
     * @param testedDate the date the verdict was reached on, exactly the linkage width
     * @param mask       the mask it was reached under, exactly the linkage width
     * @return the verdict, never {@code null}
     */
    private static DateValidationService.SubprogramResult acceptedVerdict(final String testedDate,
            final String mask) {

        return new DateValidationService.SubprogramResult(
                DateValidationService.DateFeedback.DATE_IS_VALID,
                padToWidth(SEVERITY_ACCEPTED, VERDICT_CODE_WIDTH),
                padToWidth(MESSAGE_NUMBER_NONE, VERDICT_CODE_WIDTH),
                padToWidth(VERDICT_STUB_TEXT, VERDICT_TEXT_WIDTH),
                padToWidth(testedDate, VERDICT_LINKAGE_WIDTH),
                padToWidth(mask, VERDICT_LINKAGE_WIDTH));
    }

    /**
     * Builds the verdict the calendar authority returns for a date it refuses as a bad value.
     *
     * @param testedDate the date the verdict was reached on, exactly the linkage width
     * @param mask       the mask it was reached under, exactly the linkage width
     * @return the verdict, never {@code null}
     */
    private static DateValidationService.SubprogramResult refusedVerdict(final String testedDate,
            final String mask) {

        return new DateValidationService.SubprogramResult(
                DateValidationService.DateFeedback.BAD_DATE_VALUE,
                padToWidth(SEVERITY_REFUSED, VERDICT_CODE_WIDTH),
                padToWidth(MESSAGE_NUMBER_BAD_VALUE, VERDICT_CODE_WIDTH),
                padToWidth(VERDICT_STUB_TEXT, VERDICT_TEXT_WIDTH),
                padToWidth(testedDate, VERDICT_LINKAGE_WIDTH),
                padToWidth(mask, VERDICT_LINKAGE_WIDTH));
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
            assertThat(validators.fileProbeModeValidator(legalProbeModes())).isNotNull();
        }

        @Test
        @DisplayName("constructing the subject consults nothing, so no validator is decided in advance")
        void constructingTheSubjectConsultsNothing() {
            validators.interestParmDateValidator();
            validators.reportDateRangeValidator();
            validators.fileProbeModeValidator(legalProbeModes());

            Mockito.verifyNoInteractions(calendarAuthority);
        }
    }

    @Nested
    @DisplayName("the published launch keys")
    final class ThePublishedLaunchKeys {

        @Test
        @DisplayName("each key carries the spelling the launch surface publishes")
        void eachKeyCarriesItsPublishedSpelling() {
            assertThat(JobParameterValidators.INTEREST_PARM_DATE_KEY).isEqualTo(KEY_INTEREST);
            assertThat(JobParameterValidators.REPORT_START_DATE_KEY).isEqualTo(KEY_REPORT_START);
            assertThat(JobParameterValidators.REPORT_END_DATE_KEY).isEqualTo(KEY_REPORT_END);
            assertThat(JobParameterValidators.FILE_PROBE_MODE_KEY).isEqualTo(KEY_PROBE_MODE);
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
        @DisplayName("the ten-character literal the interest job stream supplied is accepted")
        void theLiteralTheInterestJobStreamSuppliedIsAccepted() {
            assertThat(encodedWidth(LEGACY_INTEREST_PARM)).isEqualTo(INTEREST_WIDTH);

            assertThatCode(() -> validators.interestParmDateValidator()
                    .validate(parameters(KEY_INTEREST, LEGACY_INTEREST_PARM)))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("the accepted value is consumed verbatim: nothing is reformatted, inserted or trimmed")
        void theAcceptedValueIsConsumedVerbatim() {
            final JobParameters launched = parameters(KEY_INTEREST, LEGACY_INTEREST_PARM);

            assertThatCode(() -> validators.interestParmDateValidator().validate(launched))
                    .doesNotThrowAnyException();

            // The launch value is unchanged, still exactly ten encoded bytes, and still separator free.
            assertThat(launched.getString(KEY_INTEREST)).isEqualTo(LEGACY_INTEREST_PARM);
            assertThat(encodedWidth(launched.getString(KEY_INTEREST))).isEqualTo(INTEREST_WIDTH);
            assertThat(launched.getString(KEY_INTEREST)).doesNotContain("-").doesNotContain("/");

            // The value that crossed the boundary to the calendar authority is the same ten characters:
            // the full parameter, not a slice of it, and not a rendering of it in some other form.
            Mockito.verify(calendarAuthority).validateDate(LEGACY_INTEREST_PARM, COMPACT_MASK);
        }

        @Test
        @DisplayName("the ten characters remain usable as the prefix of the synthesized transaction id")
        void theTenCharactersRemainUsableAsTheTransactionIdPrefix() {
            assertThatCode(() -> validators.interestParmDateValidator()
                    .validate(parameters(KEY_INTEREST, LEGACY_INTEREST_PARM)))
                    .doesNotThrowAnyException();

            // Composed here rather than obtained from any production collaborator: the accepted ten
            // characters followed by the six-digit incrementing suffix. This is why reformatting the
            // parameter is forbidden - every synthesized identifier would change without any check
            // failing.
            final String suffix = String.format(Locale.ROOT, "%0" + INTEREST_SUFFIX_WIDTH + "d", 1);
            final String synthesizedId = LEGACY_INTEREST_PARM + suffix;

            assertThat(encodedWidth(suffix)).isEqualTo(INTEREST_SUFFIX_WIDTH);
            assertThat(encodedWidth(synthesizedId))
                    .isEqualTo(INTEREST_WIDTH + INTEREST_SUFFIX_WIDTH);
            assertThat(synthesizedId).startsWith(LEGACY_INTEREST_PARM);
        }

        @ParameterizedTest(name = "the trailing two positions may hold [{0}] without affecting acceptance")
        @ValueSource(strings = {"2022071800", "2022071801", "2022071812", "2022071899"})
        @DisplayName("only the leading eight positions are a calendar date, so the trailing two are free")
        void onlyTheLeadingEightPositionsAreACalendarDate(final String value) {
            assertThat(encodedWidth(value)).isEqualTo(INTEREST_WIDTH);
            assertThat(value).startsWith(LEGACY_INTEREST_PARM.substring(0, INTEREST_CALENDAR_WIDTH));

            assertThatCode(() -> validators.interestParmDateValidator()
                    .validate(parameters(KEY_INTEREST, value)))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("no separate length parameter is required, because the source imposes no range check")
        void noSeparateLengthParameterIsRequired() {
            final JobParameters onlyTheDate = parameters(KEY_INTEREST, LEGACY_INTEREST_PARM);

            assertThat(onlyTheDate.getParameters()).containsOnlyKeys(KEY_INTEREST);
            assertThatCode(() -> validators.interestParmDateValidator().validate(onlyTheDate))
                    .doesNotThrowAnyException();
        }

        @ParameterizedTest(name = "the {1}-byte value [{0}] is refused on width")
        @CsvSource({
            "202207180, 9",
            "20220718000, 11",
            "2022071, 7",
            "202207180012, 12"
        })
        @DisplayName("only exactly ten encoded bytes fill the parameter date field")
        void onlyExactlyTenEncodedBytesFillTheField(final String value, final int width) {
            assertThat(encodedWidth(value)).isEqualTo(width);

            assertThat(rejectionOf(validators.interestParmDateValidator(),
                    parameters(KEY_INTEREST, value)))
                    .hasMessage(describe(KEY_INTEREST, value) + "must be exactly " + INTEREST_WIDTH
                            + " encoded bytes to fill the parameter date field, but measures " + width);
        }

        @Test
        @DisplayName("an over-long value is refused on width rather than truncated into a valid ten")
        void anOverLongValueIsRefusedRatherThanTruncated() {
            final String overLong = LEGACY_INTEREST_PARM + "0";

            // Its leading ten characters are precisely the value the job stream supplied, so a validator
            // that truncated before checking would accept this and the interest run would proceed on a
            // parameter nobody supplied.
            assertThat(overLong).startsWith(LEGACY_INTEREST_PARM);
            assertThat(encodedWidth(overLong)).isEqualTo(INTEREST_WIDTH + 1);

            assertThat(rejectionOf(validators.interestParmDateValidator(),
                    parameters(KEY_INTEREST, overLong)))
                    .hasMessageContaining("must be exactly " + INTEREST_WIDTH + " encoded bytes");
        }

        @ParameterizedTest(name = "the non-digit value [{0}] is refused")
        @ValueSource(strings = {"O022071800", "2022O71800", "202207180O", "2022-71800", "2022 71800"})
        @DisplayName("every one of the ten positions must hold a digit, so no separator is admitted")
        void everyPositionMustHoldADigit(final String value) {
            assertThat(encodedWidth(value)).isEqualTo(INTEREST_WIDTH);

            assertThat(rejectionOf(validators.interestParmDateValidator(),
                    parameters(KEY_INTEREST, value)))
                    .hasMessage(describe(KEY_INTEREST, value) + "must be " + INTEREST_WIDTH
                            + " digits with no separators; it is not a hyphenated ISO date");
        }

        @Test
        @DisplayName("a hyphenated date of the right width is refused, no separator being admitted")
        void aHyphenatedDateOfTheRightWidthIsRefused() {
            final String hyphenated = "2022-07-18";
            assertThat(encodedWidth(hyphenated)).isEqualTo(INTEREST_WIDTH);

            assertThat(rejectionOf(validators.interestParmDateValidator(),
                    parameters(KEY_INTEREST, hyphenated)))
                    .hasMessageContaining("digits with no separators")
                    .hasMessageContaining("it is not a hyphenated ISO date");
        }

        @Test
        @DisplayName("an impossible February date is refused, never normalised onto the first of March")
        void anImpossibleFebruaryDateIsRefusedRatherThanNormalised() {
            final String impossible = "2022023000";
            assertThat(encodedWidth(impossible)).isEqualTo(INTEREST_WIDTH);

            final JobParametersInvalidException thrown = rejectionOf(
                    validators.interestParmDateValidator(), parameters(KEY_INTEREST, impossible));

            assertThat(thrown).hasMessage(describe(KEY_INTEREST, impossible)
                    + "does not carry a valid calendar date in its leading "
                    + INTEREST_CALENDAR_WIDTH + " positions");
            // Strict resolution is what replaces the legacy language-environment date routine: the
            // impossible day is refused, and no neighbouring valid day is substituted for it anywhere.
            assertThat(thrown.getMessage()).doesNotContain("20220301");
        }

        @ParameterizedTest(name = "the twenty-ninth of February in {1} is accepted: {0}")
        @CsvSource({
            "2020022977, 2020, true",
            "2024022977, 2024, true",
            "2021022977, 2021, false",
            "2022022977, 2022, false"
        })
        @DisplayName("the calendar authority decides leap years, and its verdict is simply reported")
        void theCalendarAuthorityDecidesLeapYears(final String value, final int year,
                final boolean accepted) {

            assertThat(encodedWidth(value)).isEqualTo(INTEREST_WIDTH);
            assertThat(value).startsWith(String.valueOf(year));

            if (accepted) {
                assertThatCode(() -> validators.interestParmDateValidator()
                        .validate(parameters(KEY_INTEREST, value)))
                        .doesNotThrowAnyException();
            } else {
                assertThat(rejectionOf(validators.interestParmDateValidator(),
                        parameters(KEY_INTEREST, value)))
                        .hasMessageContaining("does not carry a valid calendar date");
            }
        }

        @Test
        @DisplayName("an absent parameter is reported as required, naming what it drives")
        void anAbsentParameterIsReportedAsRequired() {
            assertThat(rejectionOf(validators.interestParmDateValidator(),
                    parameters(KEY_INTEREST, null)))
                    .hasMessage("Job parameter [" + KEY_INTEREST + "] is required but was not supplied;"
                            + " the interest calculation is driven by it");
        }

        @Test
        @DisplayName("a value carrying a character wider than one byte is refused before it is measured")
        void aValueOutsideTheSingleByteSetIsRefused() {
            final String unrepresentable = NON_SINGLE_BYTE + "022071800";

            assertThat(rejectionOf(validators.interestParmDateValidator(),
                    parameters(KEY_INTEREST, unrepresentable)))
                    .hasMessageContaining("carries a character outside the single-byte character set");
        }

        @Test
        @DisplayName("an absent parameter set is reported through the framework contract")
        void anAbsentParameterSetIsReportedThroughTheFrameworkContract() {
            assertThat(rejectionOf(validators.interestParmDateValidator(), null))
                    .hasMessage("No job parameters were supplied, so the launch-time contract of this"
                            + " job cannot be checked");
        }
    }

    @Nested
    @DisplayName("the report date range: two inclusive bounds in hyphenated form")
    final class TheReportDateRange {

        @Test
        @DisplayName("the window the report sort symbols actually carried is accepted")
        void theWindowTheReportSortSymbolsCarriedIsAccepted() {
            assertThat(encodedWidth(LEGACY_WINDOW_START)).isEqualTo(ISO_WIDTH);
            assertThat(encodedWidth(LEGACY_WINDOW_END)).isEqualTo(ISO_WIDTH);

            assertThatCode(() -> validators.reportDateRangeValidator()
                    .validate(reportParameters(LEGACY_WINDOW_START, LEGACY_WINDOW_END)))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("a window whose start equals its end is accepted, both bounds being inclusive")
        void aWindowWhoseStartEqualsItsEndIsAccepted() {
            // The legacy filter tests the processing date with greater-or-equal against the start and
            // less-or-equal against the end, so a one-day window selects that day rather than nothing.
            // Narrowing either bound to exclusive would make this launch impossible to express.
            assertThatCode(() -> validators.reportDateRangeValidator()
                    .validate(reportParameters(LEGACY_WINDOW_START, LEGACY_WINDOW_START)))
                    .doesNotThrowAnyException();
            assertThatCode(() -> validators.reportDateRangeValidator()
                    .validate(reportParameters(LEGACY_WINDOW_END, LEGACY_WINDOW_END)))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("both bounds are consumed verbatim, so neither is reformatted on the way through")
        void bothBoundsAreConsumedVerbatim() {
            final JobParameters launched = reportParameters(LEGACY_WINDOW_START, LEGACY_WINDOW_END);

            assertThatCode(() -> validators.reportDateRangeValidator().validate(launched))
                    .doesNotThrowAnyException();

            assertThat(launched.getString(KEY_REPORT_START)).isEqualTo(LEGACY_WINDOW_START);
            assertThat(launched.getString(KEY_REPORT_END)).isEqualTo(LEGACY_WINDOW_END);
            Mockito.verify(calendarAuthority).validateDate(LEGACY_WINDOW_START, HYPHENATED_MASK);
            Mockito.verify(calendarAuthority).validateDate(LEGACY_WINDOW_END, HYPHENATED_MASK);
        }

        @Test
        @DisplayName("a start that follows the end is refused, and the diagnostic names both bounds")
        void aStartThatFollowsTheEndIsRefused() {
            assertThat(rejectionOf(validators.reportDateRangeValidator(),
                    reportParameters(LEGACY_WINDOW_END, LEGACY_WINDOW_START)))
                    .hasMessage("Job parameter [" + KEY_REPORT_START + "] value ["
                            + LEGACY_WINDOW_END + "] follows [" + KEY_REPORT_END + "] value ["
                            + LEGACY_WINDOW_START + "]; the report window is filtered with an inclusive"
                            + " lower and an inclusive upper bound, so the start must not be later than"
                            + " the end");
        }

        @ParameterizedTest(name = "the non-hyphenated rendering [{0}] is refused")
        @ValueSource(strings = {"01/01/2022", "2022.01.01", "2022/01/01", "20220101  ", "  20220101"})
        @DisplayName("the hyphenated form is enforced, because the legacy filter compares characters")
        void theHyphenatedFormIsEnforced(final String value) {
            // The legacy filter never converts a date: it compares the record's processing-date field as
            // character data. Character ordering agrees with chronological ordering only for a
            // zero-padded, hyphenated rendering, so any other rendering of the same day would order
            // wrongly under the very comparison the report depends on and is refused rather than
            // tolerated.
            assertThat(encodedWidth(value)).isEqualTo(ISO_WIDTH);

            assertThat(rejectionOf(validators.reportDateRangeValidator(),
                    reportParameters(value, LEGACY_WINDOW_END)))
                    .hasMessage(describe(KEY_REPORT_START, value)
                            + "is not a valid calendar date in the form YYYY-MM-DD");
        }

        @ParameterizedTest(name = "the {1}-byte bound [{0}] is refused on width")
        @CsvSource({
            "2022-1-01, 9",
            "2022-01-1, 9",
            "2022-001-01, 11",
            "2022-01-011, 11"
        })
        @DisplayName("a bound that is not exactly ten encoded bytes is refused on width")
        void aBoundThatIsNotTenEncodedBytesIsRefusedOnWidth(final String value, final int width) {
            // A single-digit month or day is a valid day rendered wrongly. It is refused here on width,
            // which is the earlier of the two stages that can catch it, and never zero padded into the
            // form the report expects.
            assertThat(encodedWidth(value)).isEqualTo(width);

            assertThat(rejectionOf(validators.reportDateRangeValidator(),
                    reportParameters(value, LEGACY_WINDOW_END)))
                    .hasMessage(describe(KEY_REPORT_START, value) + "must be exactly " + ISO_WIDTH
                            + " encoded bytes in the form YYYY-MM-DD, but measures " + width);
        }

        @Test
        @DisplayName("an impossible February date is refused on either bound, never normalised")
        void anImpossibleFebruaryDateIsRefusedOnEitherBound() {
            final String impossible = "2022-02-30";
            assertThat(encodedWidth(impossible)).isEqualTo(ISO_WIDTH);

            final JobParametersInvalidException onStart = rejectionOf(
                    validators.reportDateRangeValidator(),
                    reportParameters(impossible, LEGACY_WINDOW_END));
            assertThat(onStart).hasMessage(describe(KEY_REPORT_START, impossible)
                    + "is not a valid calendar date in the form YYYY-MM-DD");
            assertThat(onStart.getMessage()).doesNotContain("2022-03-01");

            final JobParametersInvalidException onEnd = rejectionOf(
                    validators.reportDateRangeValidator(),
                    reportParameters(LEGACY_WINDOW_START, impossible));
            assertThat(onEnd).hasMessage(describe(KEY_REPORT_END, impossible)
                    + "is not a valid calendar date in the form YYYY-MM-DD");
            assertThat(onEnd.getMessage()).doesNotContain("2022-03-01");
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
        @DisplayName("when both bounds are bad the start bound is reported, the cascade short-circuiting")
        void whenBothBoundsAreBadTheStartBoundIsReported() {
            final String badStart = "2022-13-01";
            final String badEnd = "2022-02-30";

            final JobParametersInvalidException thrown = rejectionOf(
                    validators.reportDateRangeValidator(), reportParameters(badStart, badEnd));

            assertThat(thrown).hasMessageContaining(describe(KEY_REPORT_START, badStart));
            assertThat(thrown.getMessage()).doesNotContain(KEY_REPORT_END);
        }

        @Test
        @DisplayName("a bound carrying a character wider than one byte is refused before it is measured")
        void aBoundOutsideTheSingleByteSetIsRefused() {
            final String unrepresentable = NON_SINGLE_BYTE + "022-01-01";

            assertThat(rejectionOf(validators.reportDateRangeValidator(),
                    reportParameters(unrepresentable, LEGACY_WINDOW_END)))
                    .hasMessageContaining("carries a character outside the single-byte character set");
        }

        @Test
        @DisplayName("an absent parameter set is reported through the framework contract")
        void anAbsentParameterSetIsReportedThroughTheFrameworkContract() {
            assertThat(rejectionOf(validators.reportDateRangeValidator(), null))
                    .hasMessage("No job parameters were supplied, so the launch-time contract of this"
                            + " job cannot be checked");
        }
    }

    @Nested
    @DisplayName("the control record: twenty-one significant bytes inside an eighty-byte record area")
    final class TheControlRecord {

        @Test
        @DisplayName("the layout measures ten bytes, one separator byte and ten bytes - twenty-one in all")
        void theLayoutMeasuresTenOneTen() {
            final String composed = controlRecord(LEGACY_WINDOW_START, LEGACY_WINDOW_END);

            // Every figure is asserted on the encoded image, because the declaration reserves bytes.
            assertThat(encodedWidth(LEGACY_WINDOW_START)).isEqualTo(ISO_WIDTH);
            assertThat(encodedWidth(LEGACY_WINDOW_END)).isEqualTo(ISO_WIDTH);
            assertThat(encodedWidth(composed)).isEqualTo(RECORD_SIGNIFICANT_WIDTH);
            assertThat(encodedWidth(composed)).isEqualTo(ISO_WIDTH + 1 + ISO_WIDTH);

            // The separator occupies exactly one byte, at the eleventh position of the record.
            final byte[] image = composed.getBytes(StandardCharsets.US_ASCII);
            assertThat(image[SEPARATOR_POSITION - 1]).isEqualTo((byte) ' ');
            assertThat(encodedWidth(composed.substring(0, SEPARATOR_POSITION - 1))).isEqualTo(ISO_WIDTH);
            assertThat(encodedWidth(composed.substring(SEPARATOR_POSITION))).isEqualTo(ISO_WIDTH);
        }

        @Test
        @DisplayName("a record carrying exactly its significant content yields the window it holds")
        void aRecordCarryingExactlyItsSignificantContentYieldsItsWindow() {
            final String composed = controlRecord(LEGACY_WINDOW_START, LEGACY_WINDOW_END);
            assertThat(encodedWidth(composed)).isEqualTo(RECORD_SIGNIFICANT_WIDTH);

            final JobParameterValidators.ReportDateWindow window =
                    validators.parseDateParmRecord(composed);

            // Compared byte for byte against values this test composed, with no trimming of either side.
            assertThat(window.startDate()).isEqualTo(LEGACY_WINDOW_START);
            assertThat(window.endDate()).isEqualTo(LEGACY_WINDOW_END);
            assertThat(encodedWidth(window.startDate())).isEqualTo(ISO_WIDTH);
            assertThat(encodedWidth(window.endDate())).isEqualTo(ISO_WIDTH);
        }

        @ParameterizedTest(name = "an eighty-byte image padded with {0} yields the same window")
        @CsvSource({
            "a space, ' '",
            "a zero, '0'",
            "a full stop, '.'"
        })
        @DisplayName("trailing filler in the record area does not disturb the parse")
        void trailingFillerDoesNotDisturbTheParse(final String description, final char padding) {
            final String significant = controlRecord(LEGACY_WINDOW_START, LEGACY_WINDOW_END);
            final String image = paddedImage(significant, padding);

            assertThat(description).isNotBlank();
            assertThat(encodedWidth(image)).isEqualTo(RECORD_IMAGE_WIDTH);

            final JobParameterValidators.ReportDateWindow window = validators.parseDateParmRecord(image);

            assertThat(window.startDate()).isEqualTo(LEGACY_WINDOW_START);
            assertThat(window.endDate()).isEqualTo(LEGACY_WINDOW_END);
            assertThat(window).isEqualTo(validators.parseDateParmRecord(significant));
        }

        @ParameterizedTest(name = "a record whose eleventh byte is [{0}] is refused")
        @ValueSource(chars = {'-', '/', ':', '0', 'T', '|'})
        @DisplayName("the separator position is checked by position, so no other character may occupy it")
        void theSeparatorPositionIsCheckedByPosition(final char separator) {
            final String malformed = controlRecord(LEGACY_WINDOW_START, separator, LEGACY_WINDOW_END);
            assertThat(encodedWidth(malformed)).isEqualTo(RECORD_SIGNIFICANT_WIDTH);

            assertThat(catchThrowableOfType(ValidationException.class,
                    () -> validators.parseDateParmRecord(malformed)))
                    .hasMessage(describe(LABEL_RECORD, malformed)
                            + "must carry a single separator character in position "
                            + SEPARATOR_POSITION + ", between the two dates");
        }

        @Test
        @DisplayName("a twenty-byte record with no separator at all is refused, being short of the group")
        void aTwentyByteRecordWithNoSeparatorIsRefused() {
            final String tooShort = LEGACY_WINDOW_START + LEGACY_WINDOW_END;
            assertThat(encodedWidth(tooShort)).isEqualTo(RECORD_SIGNIFICANT_WIDTH - 1);

            assertThat(catchThrowableOfType(ValidationException.class,
                    () -> validators.parseDateParmRecord(tooShort)))
                    .hasMessage(describe(LABEL_RECORD, tooShort) + "must carry at least "
                            + RECORD_SIGNIFICANT_WIDTH + " encoded bytes - two " + ISO_WIDTH
                            + "-character dates either side of one separator - but measures "
                            + (RECORD_SIGNIFICANT_WIDTH - 1));
        }

        @Test
        @DisplayName("a twenty-two byte layout with two separators displaces the end date and is refused")
        void aTwentyTwoByteLayoutWithTwoSeparatorsIsRefused() {
            // Two separators is a malformed layout rather than trailing filler: it pushes the end date one
            // position to the right, so the end-date position picks up the second separator and loses the
            // last character of the date. The refusal is attributed to that position, and it is a genuine
            // refusal - unlike filler past the declared positions, which the legacy group move discards.
            final String malformed = LEGACY_WINDOW_START + "  " + LEGACY_WINDOW_END;
            assertThat(encodedWidth(malformed)).isEqualTo(RECORD_SIGNIFICANT_WIDTH + 1);

            final String displacedEndDate = malformed.substring(SEPARATOR_POSITION,
                    RECORD_SIGNIFICANT_WIDTH);
            assertThat(encodedWidth(displacedEndDate)).isEqualTo(ISO_WIDTH);
            assertThat(displacedEndDate).startsWith(" ");

            assertThat(catchThrowableOfType(ValidationException.class,
                    () -> validators.parseDateParmRecord(malformed)))
                    .hasMessage(describe(LABEL_RECORD_END, displacedEndDate)
                            + "is not a valid calendar date in the form YYYY-MM-DD");
        }

        @Test
        @DisplayName("a space-padded date position is refused rather than trimmed into a valid date")
        void aSpacePaddedDatePositionIsRefusedRatherThanTrimmed() {
            // Padding inside a declared position is content in the wrong place. Trimming it would accept a
            // record the layout cannot express, so the position is compared exactly as it stands.
            final String shiftedStart = " " + LEGACY_WINDOW_START.substring(1);
            final String malformed = controlRecord(shiftedStart, LEGACY_WINDOW_END);

            assertThat(encodedWidth(shiftedStart)).isEqualTo(ISO_WIDTH);
            assertThat(encodedWidth(malformed)).isEqualTo(RECORD_SIGNIFICANT_WIDTH);

            assertThat(catchThrowableOfType(ValidationException.class,
                    () -> validators.parseDateParmRecord(malformed)))
                    .hasMessage(describe(LABEL_RECORD_START, shiftedStart)
                            + "is not a valid calendar date in the form YYYY-MM-DD");
        }

        @Test
        @DisplayName("an absent record is reported as missing rather than as invalid")
        void anAbsentRecordIsReportedAsMissing() {
            final ValidationException thrown = catchThrowableOfType(ValidationException.class,
                    () -> validators.parseDateParmRecord(null));

            assertThat(thrown).isNotNull();
            assertThat(thrown).hasMessage(LABEL_RECORD + " was not supplied, so the report date window"
                    + " cannot be established");
            assertThat(thrown.hasFieldErrors()).isTrue();
            assertThat(thrown.fieldErrors()).singleElement()
                    .satisfies(fieldError -> {
                        assertThat(fieldError.field()).isEqualTo(LABEL_RECORD);
                        assertThat(fieldError.state())
                                .isEqualTo(ValidationException.FieldState.MISSING);
                    });
        }

        @Test
        @DisplayName("a record whose two dates are equal is accepted, the inclusive window being one day")
        void aRecordWhoseTwoDatesAreEqualIsAccepted() {
            final String composed = controlRecord(LEGACY_WINDOW_START, LEGACY_WINDOW_START);

            final JobParameterValidators.ReportDateWindow window =
                    validators.parseDateParmRecord(composed);

            assertThat(window.startDate()).isEqualTo(LEGACY_WINDOW_START);
            assertThat(window.endDate()).isEqualTo(LEGACY_WINDOW_START);
            assertThat(window.includes(LEGACY_WINDOW_START)).isTrue();
        }

        @Test
        @DisplayName("a reversed window in the record is refused, and the diagnostic names both positions")
        void aReversedWindowInTheRecordIsRefused() {
            final String reversed = controlRecord(LEGACY_WINDOW_END, LEGACY_WINDOW_START);

            final ValidationException thrown = catchThrowableOfType(ValidationException.class,
                    () -> validators.parseDateParmRecord(reversed));

            assertThat(thrown).isNotNull();
            assertThat(thrown.getMessage())
                    .contains(LABEL_RECORD_START)
                    .contains(LABEL_RECORD_END)
                    .contains(LEGACY_WINDOW_START)
                    .contains(LEGACY_WINDOW_END)
                    .contains("inclusive lower and an inclusive upper bound");
            assertThat(thrown.fieldErrors()).singleElement()
                    .satisfies(fieldError -> assertThat(fieldError.state())
                            .isEqualTo(ValidationException.FieldState.INVALID));
        }

        @Test
        @DisplayName("an invalid date in a record position is attributed to that position, not to a key")
        void anInvalidDateInARecordPositionIsAttributedToThatPosition() {
            final String impossible = "2022-02-30";
            final String malformed = controlRecord(LEGACY_WINDOW_START, impossible);

            final ValidationException thrown = catchThrowableOfType(ValidationException.class,
                    () -> validators.parseDateParmRecord(malformed));

            assertThat(thrown).isNotNull();
            assertThat(thrown).hasMessage(describe(LABEL_RECORD_END, impossible)
                    + "is not a valid calendar date in the form YYYY-MM-DD");
            // The record route is not a launch-parameter route, so no launch key appears in its diagnostic.
            assertThat(thrown.getMessage())
                    .doesNotContain(KEY_REPORT_START)
                    .doesNotContain(KEY_REPORT_END);
        }

        @Test
        @DisplayName("a record carrying a character wider than one byte is refused before it is measured")
        void aRecordOutsideTheSingleByteSetIsRefused() {
            final String unrepresentable = controlRecord(NON_SINGLE_BYTE + "022-01-01",
                    LEGACY_WINDOW_END);

            assertThat(catchThrowableOfType(ValidationException.class,
                    () -> validators.parseDateParmRecord(unrepresentable)))
                    .hasMessageContaining("carries a character outside the single-byte character set");
        }

        @Test
        @DisplayName("the file route and the sort-symbol route agree on one and the same window")
        void theFileRouteAndTheSortSymbolRouteAgree() {
            // One window, two delivery routes. The sort step receives the two dates as sort symbols and
            // the report program reads them from a sequential file; a launch that is legal on one route
            // must be legal on the other, or the pipeline would filter on two different windows.
            final JobParameters asSortSymbols = reportParameters(LEGACY_WINDOW_START, LEGACY_WINDOW_END);
            assertThatCode(() -> validators.reportDateRangeValidator().validate(asSortSymbols))
                    .doesNotThrowAnyException();

            final JobParameterValidators.ReportDateWindow asFileRecord = validators.parseDateParmRecord(
                    controlRecord(LEGACY_WINDOW_START, LEGACY_WINDOW_END));

            assertThat(asFileRecord.startDate()).isEqualTo(asSortSymbols.getString(KEY_REPORT_START));
            assertThat(asFileRecord.endDate()).isEqualTo(asSortSymbols.getString(KEY_REPORT_END));
        }
    }

    @Nested
    @DisplayName("the file-probe mode: exact membership of the enumeration its owner supplies")
    final class TheFileProbeMode {

        @ParameterizedTest(name = "the mode {0} its owner declares is accepted")
        @EnumSource(FileProbeJobConfig.ProbeMode.class)
        @DisplayName("every mode the probe job configuration owns validates successfully")
        void everyModeTheOwnerDeclaresValidatesSuccessfully(final FileProbeJobConfig.ProbeMode mode) {
            assertThatCode(() -> validators.fileProbeModeValidator(legalProbeModes())
                    .validate(parameters(KEY_PROBE_MODE, mode.parameterValue())))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("the enumeration validated against is exactly the four the owner declares")
        void theEnumerationIsExactlyTheFourTheOwnerDeclares() {
            // The four collapsed sequential-read job streams were the account, card, customer and
            // cross-reference probes, so there are four modes and the validator is configured with all of
            // them. The enumeration is read from its owner rather than restated, because a definition kept
            // in two places would let the launch surface and the job drift apart unnoticed.
            assertThat(legalProbeModes()).hasSize(4).doesNotHaveDuplicates();
            assertThat(legalProbeModes()).containsExactlyElementsOf(
                    List.of(FileProbeJobConfig.ProbeMode.ACCOUNT.parameterValue(),
                            FileProbeJobConfig.ProbeMode.CARD.parameterValue(),
                            FileProbeJobConfig.ProbeMode.CUSTOMER.parameterValue(),
                            FileProbeJobConfig.ProbeMode.CROSS_REFERENCE.parameterValue()));
        }

        @ParameterizedTest(name = "the unrecognised mode [{0}] fails validation")
        @ValueSource(strings = {"ledger", "transaction", "accounts", "cross-reference", "0"})
        @DisplayName("an unrecognised mode fails validation instead of defaulting to a mode")
        void anUnrecognisedModeFailsValidation(final String value) {
            final JobParametersInvalidException thrown = rejectionOf(
                    validators.fileProbeModeValidator(legalProbeModes()),
                    parameters(KEY_PROBE_MODE, value));

            // Refused as a failed membership test, never silently substituted: probing a file the launch
            // did not name would report a healthy file nobody asked about.
            assertThat(thrown).hasMessage(describe(KEY_PROBE_MODE, value)
                    + "is not one of the legal probe modes " + renderedLegalProbeModes()
                    + "; matching is exact and case sensitive");
        }

        @ParameterizedTest(name = "the differently cased mode [{0}] fails validation")
        @ValueSource(strings = {"ACCOUNT", "Account", "CARD", "CrossReference", "CROSSREFERENCE"})
        @DisplayName("matching is case sensitive, so a near miss is reported rather than guessed at")
        void matchingIsCaseSensitive(final String value) {
            assertThat(legalProbeModes()).doesNotContain(value);

            assertThat(rejectionOf(validators.fileProbeModeValidator(legalProbeModes()),
                    parameters(KEY_PROBE_MODE, value)))
                    .hasMessageContaining("is not one of the legal probe modes")
                    .hasMessageContaining("matching is exact and case sensitive");
        }

        @Test
        @DisplayName("an absent mode is reported as required, and the diagnostic lists the legal values")
        void anAbsentModeIsReportedAsRequired() {
            assertThat(rejectionOf(validators.fileProbeModeValidator(legalProbeModes()),
                    parameters(KEY_PROBE_MODE, null)))
                    .hasMessage("Job parameter [" + KEY_PROBE_MODE + "] is required but was not supplied;"
                            + " it selects which file the probe job reads, and the legal values are "
                            + renderedLegalProbeModes());
        }

        @ParameterizedTest(name = "the blank mode [{0}] is refused as blank")
        @ValueSource(strings = {" ", "   ", "\t"})
        @DisplayName("a blank mode is refused as blank rather than as a failed match")
        void aBlankModeIsRefusedAsBlank(final String value) {
            assertThat(rejectionOf(validators.fileProbeModeValidator(legalProbeModes()),
                    parameters(KEY_PROBE_MODE, value)))
                    .hasMessageContaining("is blank; the legal values are "
                            + renderedLegalProbeModes());
        }

        @Test
        @DisplayName("the enumeration is copied defensively, so later mutation cannot widen the validator")
        void theEnumerationIsCopiedDefensively() {
            final List<String> supplied = new ArrayList<>(legalProbeModes());
            final JobParametersValidator validator = validators.fileProbeModeValidator(supplied);

            supplied.add("ledger");

            assertThat(rejectionOf(validator, parameters(KEY_PROBE_MODE, "ledger")))
                    .hasMessageContaining("is not one of the legal probe modes")
                    .hasMessageContaining(renderedLegalProbeModes());
        }

        @Test
        @DisplayName("a null enumeration is refused at configuration time, where the mistake was made")
        void aNullEnumerationIsRefusedAtConfigurationTime() {
            assertThatNullPointerException()
                    .isThrownBy(() -> validators.fileProbeModeValidator(null))
                    .withMessageContaining("legalModeNames must not be null");
        }

        @Test
        @DisplayName("an empty enumeration is refused, a validator built from one rejecting every launch")
        void anEmptyEnumerationIsRefused() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> validators.fileProbeModeValidator(List.of()))
                    .withMessageContaining("legalModeNames must not be empty");
        }

        @Test
        @DisplayName("an enumeration carrying a blank entry is refused, no launch being able to match one")
        void anEnumerationCarryingABlankEntryIsRefused() {
            final List<String> withBlank = new ArrayList<>(legalProbeModes());
            withBlank.add("   ");

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> validators.fileProbeModeValidator(withBlank))
                    .withMessageContaining("must not carry a null or blank entry");
        }

        @Test
        @DisplayName("an enumeration carrying a null entry is refused for the same reason")
        void anEnumerationCarryingANullEntryIsRefused() {
            final List<String> withNull = new ArrayList<>(legalProbeModes());
            withNull.add(null);

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> validators.fileProbeModeValidator(withNull))
                    .withMessageContaining("must not carry a null or blank entry");
        }

        @Test
        @DisplayName("the probe mode reaches no calendar decision, having no date in it")
        void theProbeModeReachesNoCalendarDecision() {
            assertThatCode(() -> validators.fileProbeModeValidator(legalProbeModes())
                    .validate(parameters(KEY_PROBE_MODE,
                            FileProbeJobConfig.ProbeMode.ACCOUNT.parameterValue())))
                    .doesNotThrowAnyException();

            Mockito.verifyNoInteractions(calendarAuthority);
        }

        @Test
        @DisplayName("an absent parameter set is reported through the framework contract")
        void anAbsentParameterSetIsReportedThroughTheFrameworkContract() {
            assertThat(rejectionOf(validators.fileProbeModeValidator(legalProbeModes()), null))
                    .hasMessage("No job parameters were supplied, so the launch-time contract of this"
                            + " job cannot be checked");
        }
    }

    @Nested
    @DisplayName("failure signalling: the framework's exception inside a validator, the module's outside")
    final class FailureSignalling {

        @Test
        @DisplayName("every validator reports a bad parameter set through the framework's own exception")
        void everyValidatorReportsThroughTheFrameworksOwnException() {
            assertThat(catchThrowableOfType(JobParametersInvalidException.class,
                    () -> validators.interestParmDateValidator()
                            .validate(parameters(KEY_INTEREST, "nonsense"))))
                    .isInstanceOf(JobParametersInvalidException.class);

            assertThat(catchThrowableOfType(JobParametersInvalidException.class,
                    () -> validators.reportDateRangeValidator()
                            .validate(reportParameters("nonsense", LEGACY_WINDOW_END))))
                    .isInstanceOf(JobParametersInvalidException.class);

            assertThat(catchThrowableOfType(JobParametersInvalidException.class,
                    () -> validators.fileProbeModeValidator(legalProbeModes())
                            .validate(parameters(KEY_PROBE_MODE, "nonsense"))))
                    .isInstanceOf(JobParametersInvalidException.class);

        }

        @ParameterizedTest(name = "the rejection of [{1}] under {0} names both the key and the value")
        @CsvSource({
            "interestParmDate, 202207180",
            "interestParmDate, 2022O71800",
            "interestParmDate, 2022023000",
            "reportStartDate, 2022.01.01",
            "reportStartDate, 2022-02-30",
            "fileProbeMode, ledger"
        })
        @DisplayName("a rejection always names the offending key and the offending value")
        void aRejectionAlwaysNamesTheKeyAndTheValue(final String key, final String value) {
            final JobParametersValidator validator = switch (key) {
                case KEY_INTEREST -> validators.interestParmDateValidator();
                case KEY_REPORT_START -> validators.reportDateRangeValidator();
                case KEY_PROBE_MODE -> validators.fileProbeModeValidator(legalProbeModes());
                default -> throw new IllegalStateException("no validator is declared for key [" + key
                        + "]; the fixture names a key this test does not cover");
            };
            final JobParameters launched = KEY_REPORT_START.equals(key)
                    ? reportParameters(value, LEGACY_WINDOW_END)
                    : parameters(key, value);

            assertThat(rejectionOf(validator, launched))
                    .hasMessageContaining("[" + key + "]")
                    .hasMessageContaining("[" + value + "]");
        }

        @Test
        @DisplayName("the record parser reports through the module's own failure, not the framework's")
        void theRecordParserReportsThroughTheModulesOwnFailure() {
            // No parameter set is being validated on the record route, so the framework's validator
            // contract does not apply to it and its exception would be the wrong signal to raise.
            final ValidationException thrown = catchThrowableOfType(ValidationException.class,
                    () -> validators.parseDateParmRecord(controlRecord("2022-13-01", LEGACY_WINDOW_END)));

            assertThat(thrown).isNotNull();
            assertThat(thrown).isNotInstanceOf(JobParametersInvalidException.class);
            assertThat(thrown).isInstanceOf(RuntimeException.class);
            assertThat(thrown.fieldErrors()).singleElement()
                    .satisfies(fieldError -> assertThat(fieldError.state())
                            .isEqualTo(ValidationException.FieldState.INVALID));
        }
    }

    @Nested
    @DisplayName("delegation: the calendar authority decides, and the subject only reports")
    final class DelegationToTheCalendarAuthority {

        @Test
        @DisplayName("the interest cascade delegates the whole ten characters under the compact mask")
        void theInterestCascadeDelegatesTheWholeTenCharacters() {
            assertThatCode(() -> validators.interestParmDateValidator()
                    .validate(parameters(KEY_INTEREST, LEGACY_INTEREST_PARM)))
                    .doesNotThrowAnyException();

            // The full parameter crosses the boundary, not its leading eight positions: the mask governs
            // how many positions are interpreted, exactly as the legacy ten-character linkage field did.
            Mockito.verify(calendarAuthority).validateDate(LEGACY_INTEREST_PARM, COMPACT_MASK);
            Mockito.verify(calendarAuthority, Mockito.never())
                    .validateDate(LEGACY_INTEREST_PARM, HYPHENATED_MASK);
            Mockito.verify(calendarAuthority, Mockito.never()).validateDate(
                    LEGACY_INTEREST_PARM.substring(0, INTEREST_CALENDAR_WIDTH), COMPACT_MASK);
        }

        @Test
        @DisplayName("the report cascade delegates each bound under the hyphenated mask, start bound first")
        void theReportCascadeDelegatesEachBoundUnderTheHyphenatedMask() {
            assertThatCode(() -> validators.reportDateRangeValidator()
                    .validate(reportParameters(LEGACY_WINDOW_START, LEGACY_WINDOW_END)))
                    .doesNotThrowAnyException();

            final InOrder inOrder = Mockito.inOrder(calendarAuthority);
            inOrder.verify(calendarAuthority).validateDate(LEGACY_WINDOW_START, HYPHENATED_MASK);
            inOrder.verify(calendarAuthority).validateDate(LEGACY_WINDOW_END, HYPHENATED_MASK);
            Mockito.verify(calendarAuthority, Mockito.never())
                    .validateDate(LEGACY_WINDOW_START, COMPACT_MASK);
        }

        @Test
        @DisplayName("the record route delegates through the same cascade as the sort-symbol route")
        void theRecordRouteDelegatesThroughTheSameCascade() {
            validators.parseDateParmRecord(controlRecord(LEGACY_WINDOW_START, LEGACY_WINDOW_END));

            Mockito.verify(calendarAuthority).validateDate(LEGACY_WINDOW_START, HYPHENATED_MASK);
            Mockito.verify(calendarAuthority).validateDate(LEGACY_WINDOW_END, HYPHENATED_MASK);
        }

        @Test
        @DisplayName("a refusing verdict is reported, so no second calendar rule is hidden in the subject")
        void aRefusingVerdictIsReported() {
            // The authority is made to refuse the one value it would otherwise accept. The subject must
            // follow it: a validator that re-derived calendar validity of its own would accept anyway.
            final DateValidationService.SubprogramResult verdict =
                    refusedVerdict(LEGACY_INTEREST_PARM, COMPACT_MASK);
            Mockito.doReturn(verdict).when(calendarAuthority)
                    .validateDate(LEGACY_INTEREST_PARM, COMPACT_MASK);
            Mockito.doReturn(false).when(calendarAuthority).isDateAcceptable(verdict);

            assertThat(rejectionOf(validators.interestParmDateValidator(),
                    parameters(KEY_INTEREST, LEGACY_INTEREST_PARM)))
                    .hasMessage(describe(KEY_INTEREST, LEGACY_INTEREST_PARM)
                            + "does not carry a valid calendar date in its leading "
                            + INTEREST_CALENDAR_WIDTH + " positions");
        }

        @Test
        @DisplayName("an accepting verdict is reported even for a date the authority would normally refuse")
        void anAcceptingVerdictIsReported() {
            // The mirror image of the test above, on the impossible February date. Acceptance is the
            // authority's decision and nothing else's, which is what keeps the two-level acceptance rule -
            // including the one flagged condition the legacy callers tolerate - from being re-derived here
            // out of the severity code.
            final String impossible = "2022023000";
            final DateValidationService.SubprogramResult verdict =
                    refusedVerdict(impossible, COMPACT_MASK);
            Mockito.doReturn(verdict).when(calendarAuthority).validateDate(impossible, COMPACT_MASK);
            Mockito.doReturn(true).when(calendarAuthority).isDateAcceptable(verdict);

            assertThatCode(() -> validators.interestParmDateValidator()
                    .validate(parameters(KEY_INTEREST, impossible)))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("the acceptance question is always asked, never answered from the verdict's severity")
        void theAcceptanceQuestionIsAlwaysAsked() {
            final DateValidationService.SubprogramResult verdict =
                    acceptedVerdict(LEGACY_INTEREST_PARM, COMPACT_MASK);
            Mockito.doReturn(verdict).when(calendarAuthority)
                    .validateDate(LEGACY_INTEREST_PARM, COMPACT_MASK);

            assertThatCode(() -> validators.interestParmDateValidator()
                    .validate(parameters(KEY_INTEREST, LEGACY_INTEREST_PARM)))
                    .doesNotThrowAnyException();

            Mockito.verify(calendarAuthority).isDateAcceptable(verdict);
        }

        @ParameterizedTest(name = "the pre-calendar refusal of [{0}] reaches the authority not at all")
        @ValueSource(strings = {"202207180", "20220718000", "2022O71800", "2022-07-18"})
        @DisplayName("a stage before the delegation refuses without consulting the authority at all")
        void aStageBeforeTheDelegationRefusesWithoutConsulting(final String value) {
            // This is the only way to show that an over-long value is refused on width rather than
            // truncated and then delegated: a truncating validator would have consulted the authority with
            // the leading ten characters and been told they are fine.
            assertThat(rejectionOf(validators.interestParmDateValidator(),
                    parameters(KEY_INTEREST, value)))
                    .isNotNull();

            Mockito.verifyNoInteractions(calendarAuthority);
        }

        @Test
        @DisplayName("an absent parameter reaches the authority not at all")
        void anAbsentParameterReachesTheAuthorityNotAtAll() {
            assertThat(rejectionOf(validators.interestParmDateValidator(),
                    parameters(KEY_INTEREST, null))).isNotNull();
            assertThat(rejectionOf(validators.reportDateRangeValidator(),
                    reportParameters(null, LEGACY_WINDOW_END))).isNotNull();

            Mockito.verifyNoInteractions(calendarAuthority);
        }

        @Test
        @DisplayName("a bound refused on width stops the cascade, so the second bound is never delegated")
        void aBoundRefusedOnWidthStopsTheCascade() {
            assertThat(rejectionOf(validators.reportDateRangeValidator(),
                    reportParameters("2022-1-01", LEGACY_WINDOW_END))).isNotNull();

            Mockito.verify(calendarAuthority, Mockito.never())
                    .validateDate(LEGACY_WINDOW_END, HYPHENATED_MASK);
            Mockito.verifyNoInteractions(calendarAuthority);
        }
    }

    @Nested
    @DisplayName("the staged-input location boundary canonicalises accepted names and refuses escapes")
    final class TheStagedInputLocationBoundary {

        /** The one object-store bucket the batch staging boundary owns. */
        private static final String STAGING_BUCKET = "carddemo-batch-staging";

        /** Name of the location parameter in refusal diagnostics. */
        private static final String LOCATION_KEY = "transactionBackupCurrentGeneration";

        @Test
        @DisplayName("an object in the configured bucket is returned in canonical object-store form")
        void anObjectInTheConfiguredBucketIsAccepted() {
            assertThat(JobParameterValidators.requireStagedInputLocation(
                    "s3://" + STAGING_BUCKET + "/combine/backup.G0001V00",
                    LOCATION_KEY,
                    STAGING_BUCKET,
                    System.getProperty("java.io.tmpdir")))
                    .isEqualTo("s3://" + STAGING_BUCKET + "/combine/backup.G0001V00");
        }

        @Test
        @DisplayName("a relative name is resolved beneath the configured local staging root")
        void aRelativeNameIsCanonicalisedBeneathTheStagingRoot() {
            final String stagingRoot = System.getProperty("java.io.tmpdir");
            final String canonical = JobParameterValidators.requireStagedInputLocation(
                    "transaction-backup.G0001V00",
                    LOCATION_KEY,
                    STAGING_BUCKET,
                    stagingRoot);

            assertThat(canonical).isEqualTo(Path.of(stagingRoot).toAbsolutePath().normalize()
                    .resolve("transaction-backup.G0001V00").toUri().toString());
        }

        @Test
        @DisplayName("another bucket is refused with the parameter and both bucket names identified")
        void anotherBucketIsRefused() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> JobParameterValidators.requireStagedInputLocation(
                            "s3://other-bucket/combine/backup.G0001V00",
                            LOCATION_KEY,
                            STAGING_BUCKET,
                            System.getProperty("java.io.tmpdir")))
                    .withMessageContaining(LOCATION_KEY)
                    .withMessageContaining("other-bucket")
                    .withMessageContaining(STAGING_BUCKET);
        }
    }

    @Nested
    @DisplayName("the returned window: an inclusive containment test over both bounds")
    final class TheReturnedWindow {

        /** The window the report sort symbols carried, built directly rather than parsed. */
        private final JobParameterValidators.ReportDateWindow window =
                new JobParameterValidators.ReportDateWindow(LEGACY_WINDOW_START, LEGACY_WINDOW_END);

        @ParameterizedTest(name = "the processing date {0} falls inside the window: {1}")
        @CsvSource({
            "2021-12-31, false",
            "2022-01-01, true",
            "2022-03-04, true",
            "2022-07-06, true",
            "2022-07-07, false"
        })
        @DisplayName("both bounds are inclusive and everything outside them is excluded")
        void bothBoundsAreInclusive(final String processingDate, final boolean expected) {
            assertThat(encodedWidth(processingDate)).isEqualTo(ISO_WIDTH);
            assertThat(window.includes(processingDate)).isEqualTo(expected);
        }

        @Test
        @DisplayName("a single-day window selects exactly that day and nothing either side of it")
        void aSingleDayWindowSelectsExactlyThatDay() {
            final JobParameterValidators.ReportDateWindow oneDay =
                    new JobParameterValidators.ReportDateWindow(LEGACY_WINDOW_START,
                            LEGACY_WINDOW_START);

            assertThat(oneDay.includes(LEGACY_WINDOW_START)).isTrue();
            assertThat(oneDay.includes("2021-12-31")).isFalse();
            assertThat(oneDay.includes("2022-01-02")).isFalse();
        }

        @Test
        @DisplayName("the bounds are held exactly as supplied, never round-tripped through a date type")
        void theBoundsAreHeldExactlyAsSupplied() {
            assertThat(window.startDate()).isEqualTo(LEGACY_WINDOW_START);
            assertThat(window.endDate()).isEqualTo(LEGACY_WINDOW_END);
            assertThat(encodedWidth(window.startDate())).isEqualTo(ISO_WIDTH);
            assertThat(encodedWidth(window.endDate())).isEqualTo(ISO_WIDTH);
        }

        @Test
        @DisplayName("the whole processing timestamp is refused, its date portion being the caller's")
        void theWholeProcessingTimestampIsRefused() {
            final String timestamp = LEGACY_WINDOW_START + "-04.05.06.123456";
            assertThat(encodedWidth(timestamp)).isEqualTo(PROCESSING_TIMESTAMP_WIDTH);

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> window.includes(timestamp))
                    .withMessageContaining("must be exactly " + ISO_WIDTH + " encoded bytes");
        }

        @Test
        @DisplayName("a null processing date is refused rather than treated as outside the window")
        void aNullProcessingDateIsRefused() {
            assertThatNullPointerException()
                    .isThrownBy(() -> window.includes(null))
                    .withMessageContaining("processingDate must not be null");
        }

        @Test
        @DisplayName("both bounds are mandatory, so no window can exist half formed")
        void bothBoundsAreMandatory() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new JobParameterValidators.ReportDateWindow(null,
                            LEGACY_WINDOW_END))
                    .withMessageContaining("startDate must not be null");
            assertThatNullPointerException()
                    .isThrownBy(() -> new JobParameterValidators.ReportDateWindow(LEGACY_WINDOW_START,
                            null))
                    .withMessageContaining("endDate must not be null");
        }

        @Test
        @DisplayName("two windows over the same bounds are equal, so a window compares by value")
        void twoWindowsOverTheSameBoundsAreEqual() {
            assertThat(new JobParameterValidators.ReportDateWindow(LEGACY_WINDOW_START,
                    LEGACY_WINDOW_END)).isEqualTo(window);
            assertThat(new JobParameterValidators.ReportDateWindow(LEGACY_WINDOW_START,
                    LEGACY_WINDOW_START)).isNotEqualTo(window);
        }
    }
}
