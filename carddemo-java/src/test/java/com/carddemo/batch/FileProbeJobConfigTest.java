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

import com.carddemo.batch.FileProbeJobConfig.ProbeMode;
import com.carddemo.domain.enums.FileStatus;
import com.carddemo.exception.AbendException;
import com.carddemo.service.DateValidationService;
import com.carddemo.service.FileMaintenanceService;
import com.carddemo.service.FileMaintenanceService.FileReadSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionListener;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.JobParametersIncrementer;
import org.springframework.batch.core.JobParametersInvalidException;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.configuration.annotation.EnableBatchProcessing;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.SimpleJob;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.scope.context.StepContext;
import org.springframework.batch.core.step.tasklet.TaskletStep;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.ApplicationListener;
import org.springframework.context.SmartLifecycle;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * The contract of the collapsed file-probe job: one job, one step, four measured modes, ascending order,
 * a mode that fails rather than defaults, and a cross-reference binding that is not the category balance.
 *
 * <p>Every figure asserted here was measured from the estate at commit
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19: the four members
 * {@code app/jcl/READACCT.jcl}, {@code app/jcl/READCARD.jcl}, {@code app/jcl/READCUST.jcl} and
 * {@code app/jcl/READXREF.jcl}, and the four programs they name.
 *
 * <p>The real parameter validator is used rather than a stand-in, because the launch boundary is part of
 * the contract under test: a job that accepted a mode it cannot probe would be a defect this file has to
 * catch. Everything else is a stand-in, so a failure here names the job configuration rather than a
 * collaborator.
 */
@DisplayName("FileProbeJobConfig :: the four sequential-read verification members, collapsed")
class FileProbeJobConfigTest {

    /** The legal launch values, in the order the enumeration declares them. */
    private static final List<String> LEGAL_MODES =
            List.of("account", "card", "customer", "crossReference");

    /** Width of every legacy data-definition name the four members declare, in encoded bytes. */
    private static final int DATA_DEFINITION_NAME_WIDTH = 8;

    /** The status a completed read loop reports, because any other rejected status abends instead. */
    private static final String TERMINAL_STATUS = FileStatus.END_OF_FILE.getCode();

    /** Roles that would make a bean start work of its own accord. */
    private static final List<Class<?>> SELF_STARTING_ROLES = List.of(CommandLineRunner.class,
            ApplicationRunner.class, SmartLifecycle.class, ApplicationListener.class);

    private JobRepository jobRepository;

    private PlatformTransactionManager transactionManager;

    private JobParameterValidators jobParameterValidators;

    private JobExecutionListener jobBoundaryListener;

    private JobParametersIncrementer jobRunIncrementer;

    private FileMaintenanceService fileMaintenanceService;

    private MeterRegistry meterRegistry;

    private Clock clock;

    private FileProbeJobConfig configuration;

    @BeforeEach
    void setUp() {
        this.jobRepository = Mockito.mock(JobRepository.class);
        this.transactionManager = Mockito.mock(PlatformTransactionManager.class);
        this.jobParameterValidators = new JobParameterValidators(new DateValidationService());
        this.jobBoundaryListener = Mockito.mock(JobExecutionListener.class);
        this.jobRunIncrementer = Mockito.mock(JobParametersIncrementer.class);
        this.fileMaintenanceService = Mockito.mock(FileMaintenanceService.class, invocation -> {
            if (invocation.getMethod().getParameterTypes().length == 1) {
                return switch (invocation.getMethod().getName()) {
                    case "readAccountFile" -> this.fileMaintenanceService.readAccountFile();
                    case "readCardFile" -> this.fileMaintenanceService.readCardFile();
                    case "readCustomerFile" -> this.fileMaintenanceService.readCustomerFile();
                    case "readCardCrossReferenceFile" ->
                            this.fileMaintenanceService.readCardCrossReferenceFile();
                    default -> org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation);
                };
            }
            return org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation);
        });
        this.meterRegistry = new SimpleMeterRegistry();
        this.clock = Clock.systemUTC();
        this.configuration = newConfiguration();
    }

    private FileProbeJobConfig newConfiguration() {
        return new FileProbeJobConfig(this.jobRepository, this.transactionManager,
                this.jobParameterValidators, this.jobBoundaryListener, this.jobRunIncrementer,
                this.fileMaintenanceService, this.meterRegistry, this.clock);
    }

    private static FileReadSummary summary(final String program, final String resource,
            final long recordsRead) {
        return new FileReadSummary(program, resource, recordsRead, TERMINAL_STATUS);
    }

    private static StepExecution stepExecution() {
        return new StepExecution(FileProbeJobConfig.FILE_PROBE_STEP_NAME,
                new JobExecution(1L, new JobParametersBuilder()
                        .addString(JobParameterValidators.FILE_PROBE_MODE_KEY, "account")
                        .toJobParameters()));
    }

    private static ChunkContext chunkContext() {
        return new ChunkContext(new StepContext(stepExecution()));
    }

    private static StepContribution contribution() {
        return new StepContribution(stepExecution());
    }

    /**
     * Binds the tasklet to one mode and runs its single pass.
     *
     * @param  mode the launch value under test
     * @return what the tasklet reported
     * @throws Exception if the pass raises, which the tasklet contract permits
     */
    private RepeatStatus run(final String mode) throws Exception {
        return this.configuration.fileProbeTasklet(mode).execute(contribution(), chunkContext());
    }

    @Nested
    @DisplayName("construction")
    class Construction {

        @Test
        @DisplayName("every collaborator is mandatory, because a probe missing one could not fail in a "
                + "way an operator would recognise")
        void everyCollaboratorIsMandatory() {
            assertThatNullPointerException().isThrownBy(() -> new FileProbeJobConfig(null,
                    transactionManager, jobParameterValidators, jobBoundaryListener,
                    jobRunIncrementer, fileMaintenanceService, meterRegistry, clock));
            assertThatNullPointerException().isThrownBy(() -> new FileProbeJobConfig(jobRepository,
                    null, jobParameterValidators, jobBoundaryListener, jobRunIncrementer,
                    fileMaintenanceService, meterRegistry, clock));
            assertThatNullPointerException().isThrownBy(() -> new FileProbeJobConfig(jobRepository,
                    transactionManager, null, jobBoundaryListener, jobRunIncrementer,
                    fileMaintenanceService, meterRegistry, clock));
            assertThatNullPointerException().isThrownBy(() -> new FileProbeJobConfig(jobRepository,
                    transactionManager, jobParameterValidators, null, jobRunIncrementer,
                    fileMaintenanceService, meterRegistry, clock));
            assertThatNullPointerException().isThrownBy(() -> new FileProbeJobConfig(jobRepository,
                    transactionManager, jobParameterValidators, jobBoundaryListener, null,
                    fileMaintenanceService, meterRegistry, clock));
            assertThatNullPointerException().isThrownBy(() -> new FileProbeJobConfig(jobRepository,
                    transactionManager, jobParameterValidators, jobBoundaryListener,
                    jobRunIncrementer, null, meterRegistry, clock));
            assertThatNullPointerException().isThrownBy(() -> new FileProbeJobConfig(jobRepository,
                    transactionManager, jobParameterValidators, jobBoundaryListener,
                    jobRunIncrementer, fileMaintenanceService, null, clock));
            assertThatNullPointerException().isThrownBy(() -> new FileProbeJobConfig(jobRepository,
                    transactionManager, jobParameterValidators, jobBoundaryListener,
                    jobRunIncrementer, fileMaintenanceService, meterRegistry, null));
        }

        @Test
        @DisplayName("a fully wired configuration is usable")
        void aFullyWiredConfigurationIsUsable() {
            assertThatCode(FileProbeJobConfigTest.this::newConfiguration).doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("the four measured modes")
    class TheFourModes {

        @Test
        @DisplayName("there are exactly four, one per collapsed member, in declaration order")
        void thereAreExactlyFourInDeclarationOrder() {
            assertThat(ProbeMode.values()).hasSize(4);
            assertThat(ProbeMode.parameterValues())
                    .as("the order a rejected launch lists the legal values in must be stable")
                    .containsExactlyElementsOf(LEGAL_MODES);
        }

        @Test
        @DisplayName("the legal values cannot be extended by a caller, so no fifth file can be probed")
        void theLegalValuesAreImmutable() {
            final List<String> published = ProbeMode.parameterValues();

            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> published.add("transactionCategoryBalance"));
            assertThat(ProbeMode.parameterValues()).isSameAs(published);
        }

        @Test
        @DisplayName("each mode names the program its member invokes")
        void eachModeNamesItsProgram() {
            assertThat(ProbeMode.ACCOUNT.legacyProgramName()).isEqualTo("CBACT01C");
            assertThat(ProbeMode.CARD.legacyProgramName()).isEqualTo("CBACT02C");
            assertThat(ProbeMode.CUSTOMER.legacyProgramName()).isEqualTo("CBCUS01C");
            assertThat(ProbeMode.CROSS_REFERENCE.legacyProgramName()).isEqualTo("CBACT03C");
        }

        @Test
        @DisplayName("each mode names the logical resource its member's data definition declared")
        void eachModeNamesItsLogicalResource() {
            assertThat(ProbeMode.ACCOUNT.logicalResourceName()).isEqualTo("ACCTFILE");
            assertThat(ProbeMode.CARD.logicalResourceName()).isEqualTo("CARDFILE");
            assertThat(ProbeMode.CUSTOMER.logicalResourceName()).isEqualTo("CUSTFILE");
            assertThat(ProbeMode.CROSS_REFERENCE.logicalResourceName()).isEqualTo("XREFFILE");
        }

        @ParameterizedTest
        @EnumSource(ProbeMode.class)
        @DisplayName("a logical resource name occupies the eight encoded bytes a data-definition name "
                + "has, measured as bytes and never as characters")
        void aLogicalResourceNameIsEightEncodedBytes(final ProbeMode mode) {
            assertThat(mode.logicalResourceName().getBytes(StandardCharsets.US_ASCII))
                    .hasSize(DATA_DEFINITION_NAME_WIDTH);
        }

        @Test
        @DisplayName("each mode carries the canonical record length of the layout it reads, in encoded "
                + "bytes")
        void eachModeCarriesItsRecordLength() {
            assertThat(ProbeMode.ACCOUNT.recordLength()).isEqualTo(300);
            assertThat(ProbeMode.CARD.recordLength()).isEqualTo(150);
            assertThat(ProbeMode.CUSTOMER.recordLength()).isEqualTo(500);
            assertThat(ProbeMode.CROSS_REFERENCE.recordLength())
                    .as("the cross-reference record is 50 bytes, which is exactly what made a "
                            + "published table confuse it with the category balance")
                    .isEqualTo(50);
        }

        /** The four business keys the four probed clusters are ordered on, ascending, by their finders. */
        private static final java.util.List<String> FILE_PROBE_KEY_PROPERTIES =
                java.util.List.of("acctId", "cardNum", "custId", "xrefCardNum");

        @Test
        @DisplayName("each mode orders its scan ascending on its own business key, because sequential "
                + "access over an indexed file returns ascending primary-key order")
        void eachModeOrdersAscendingOnItsBusinessKey() {
            assertThat(ProbeMode.ACCOUNT.businessKeyProperty()).isEqualTo("acctId");
            assertThat(ProbeMode.CARD.businessKeyProperty()).isEqualTo("cardNum");
            assertThat(ProbeMode.CUSTOMER.businessKeyProperty()).isEqualTo("custId");
            assertThat(ProbeMode.CROSS_REFERENCE.businessKeyProperty()).isEqualTo("xrefCardNum");
        }

        @ParameterizedTest
        @EnumSource(ProbeMode.class)
        @DisplayName("every mode names a key for its pass to be ordered on, and the ordering itself "
                + "belongs to the service that issues the pass")
        void everyModeNamesAKeyForItsPassToBeOrderedOn(final ProbeMode mode) {
            // The name is all a mode carries. The ordering is issued by FileMaintenanceService's derived
            // key-ordered finders, one per cluster, each naming its own key ascending in the finder's own
            // name - so a second representation of the same ordering carried here, alongside the one
            // actually issued, would be the thing that could come to disagree with it.
            assertThat(mode.businessKeyProperty()).isNotBlank();
            assertThat(FILE_PROBE_KEY_PROPERTIES).contains(mode.businessKeyProperty());
        }

        @ParameterizedTest
        @EnumSource(ProbeMode.class)
        @DisplayName("a mode resolves from the exact value it publishes")
        void aModeResolvesFromItsPublishedValue(final ProbeMode mode) {
            assertThat(ProbeMode.ofParameterValue(mode.parameterValue())).isSameAs(mode);
        }

        @ParameterizedTest
        @ValueSource(strings = {"ACCOUNT", "Account", "accounts", "crossreference", "CROSS_REFERENCE",
            "transactionCategoryBalance", " account", "tcatbalf"})
        @DisplayName("an unrecognised value fails and is never defaulted to a mode, because probing a "
                + "file nobody named would report a healthy file the operator never asked about")
        void anUnrecognisedValueFails(final String supplied) {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> ProbeMode.ofParameterValue(supplied))
                    .withMessageContaining("exact and case");
        }

        @ParameterizedTest
        @ValueSource(strings = {"", " ", "\t"})
        @DisplayName("a blank value is reported as absent rather than as a misspelling")
        void aBlankValueIsReportedAsAbsent(final String supplied) {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> ProbeMode.ofParameterValue(supplied))
                    .withMessageContaining("required");
        }

        @Test
        @DisplayName("an absent value is reported as absent")
        void anAbsentValueIsReportedAsAbsent() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> ProbeMode.ofParameterValue(null))
                    .withMessageContaining("required");
        }
    }

    @Nested
    @DisplayName("the one job")
    class TheOneJob {

        @Test
        @DisplayName("it carries the stable published name, never a name assembled from a parameter")
        void itCarriesTheStablePublishedName() {
            assertThat(job().getName()).isEqualTo(FileProbeJobConfig.FILE_PROBE_JOB_NAME);
            assertThat(FileProbeJobConfig.FILE_PROBE_JOB_NAME).isEqualTo("fileProbeJob");
        }

        @Test
        @DisplayName("it holds exactly one step, whose name is the one stable Java step name the four "
                + "identically named legacy steps collapse into")
        void itHoldsExactlyOneStep() {
            final Job built = job();

            assertThat(built).isInstanceOf(SimpleJob.class);
            assertThat(((SimpleJob) built).getStepNames())
                    .containsExactly(FileProbeJobConfig.FILE_PROBE_STEP_NAME);
            assertThat(FileProbeJobConfig.LEGACY_STEP_NAME)
                    .as("the step name all four members declare is recorded so the citation survives")
                    .isEqualTo("STEP05");
        }

        @Test
        @DisplayName("it is a plain sequence and therefore carries no condition-code transition at all, "
                + "because zero gates were measured across the four members")
        void itCarriesNoConditionCodeTransition() {
            assertThat(job())
                    .as("a failure-ending transition would require a flow-based job")
                    .isInstanceOf(SimpleJob.class);
        }

        @Test
        @DisplayName("it attaches the shared incrementer, so an identical resubmission runs again as a "
                + "resubmitted member did")
        void itAttachesTheSharedIncrementer() {
            assertThat(job().getJobParametersIncrementer()).isSameAs(jobRunIncrementer);
        }

        @ParameterizedTest
        @EnumSource(ProbeMode.class)
        @DisplayName("its validator accepts every legal mode")
        void itsValidatorAcceptsEveryLegalMode(final ProbeMode mode) {
            assertThatCode(() -> job().getJobParametersValidator().validate(parameters(
                    mode.parameterValue()))).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("its validator refuses a mode the job cannot probe, at the launch boundary")
        void itsValidatorRefusesAnUnknownMode() {
            assertThatExceptionOfType(JobParametersInvalidException.class)
                    .isThrownBy(() -> job().getJobParametersValidator()
                            .validate(parameters("transactionCategoryBalance")))
                    .withMessageContaining("crossReference");
        }

        @Test
        @DisplayName("its validator refuses a launch that names no mode")
        void itsValidatorRefusesAnAbsentMode() {
            assertThatExceptionOfType(JobParametersInvalidException.class)
                    .isThrownBy(() -> job().getJobParametersValidator()
                            .validate(new JobParameters()));
        }

        @Test
        @DisplayName("the step it starts is mandatory, so a mis-wiring fails at definition time")
        void theStepItStartsIsMandatory() {
            assertThatNullPointerException().isThrownBy(() -> configuration.fileProbeJob(null));
        }

        private Job job() {
            return configuration.fileProbeJob(configuration.fileProbeStep(
                    configuration.fileProbeTasklet(ProbeMode.ACCOUNT.parameterValue())));
        }

        private JobParameters parameters(final String mode) {
            return new JobParametersBuilder()
                    .addString(JobParameterValidators.FILE_PROBE_MODE_KEY, mode)
                    .toJobParameters();
        }
    }

    @Nested
    @DisplayName("the one step")
    class TheOneStep {

        @Test
        @DisplayName("it is a tasklet step, because each program is one indivisible pass over one file")
        void itIsATaskletStep() {
            final Step step = configuration.fileProbeStep(
                    configuration.fileProbeTasklet(ProbeMode.CARD.parameterValue()));

            assertThat(step).isInstanceOf(TaskletStep.class);
            assertThat(step.getName()).isEqualTo(FileProbeJobConfig.FILE_PROBE_STEP_NAME);
        }

        @Test
        @DisplayName("its tasklet is mandatory, so a mis-wiring fails at definition time")
        void itsTaskletIsMandatory() {
            assertThatNullPointerException().isThrownBy(() -> configuration.fileProbeStep(null));
        }
    }

    @Nested
    @DisplayName("the mode is bound at execution time")
    class TheModeIsBoundAtExecutionTime {

        @Test
        @DisplayName("the account mode drives the account reader and nothing else")
        void theAccountModeDrivesTheAccountReader() throws Exception {
            Mockito.when(fileMaintenanceService.readAccountFile())
                    .thenReturn(summary("CBACT01C", "ACCTFILE", 50L));

            assertThat(run(ProbeMode.ACCOUNT.parameterValue())).isEqualTo(RepeatStatus.FINISHED);

            Mockito.verify(fileMaintenanceService)
                    .readAccountFile(Mockito.any(java.util.function.BooleanSupplier.class));
            Mockito.verify(fileMaintenanceService).readAccountFile();
            Mockito.verifyNoMoreInteractions(fileMaintenanceService);
        }

        @Test
        @DisplayName("the card mode drives the card reader and nothing else")
        void theCardModeDrivesTheCardReader() throws Exception {
            Mockito.when(fileMaintenanceService.readCardFile())
                    .thenReturn(summary("CBACT02C", "CARDFILE", 50L));

            assertThat(run(ProbeMode.CARD.parameterValue())).isEqualTo(RepeatStatus.FINISHED);

            Mockito.verify(fileMaintenanceService)
                    .readCardFile(Mockito.any(java.util.function.BooleanSupplier.class));
            Mockito.verify(fileMaintenanceService).readCardFile();
            Mockito.verifyNoMoreInteractions(fileMaintenanceService);
        }

        @Test
        @DisplayName("the customer mode drives the customer reader and nothing else")
        void theCustomerModeDrivesTheCustomerReader() throws Exception {
            Mockito.when(fileMaintenanceService.readCustomerFile())
                    .thenReturn(summary("CBCUS01C", "CUSTFILE", 50L));

            assertThat(run(ProbeMode.CUSTOMER.parameterValue())).isEqualTo(RepeatStatus.FINISHED);

            Mockito.verify(fileMaintenanceService)
                    .readCustomerFile(Mockito.any(java.util.function.BooleanSupplier.class));
            Mockito.verify(fileMaintenanceService).readCustomerFile();
            Mockito.verifyNoMoreInteractions(fileMaintenanceService);
        }

        @Test
        @DisplayName("the tasklet is step scoped and reads the mode from the published parameter key, "
                + "which is what makes one job definition serve four files")
        void theTaskletIsStepScopedOnThePublishedParameterKey() throws Exception {
            final Method beanMethod = FileProbeJobConfig.class
                    .getDeclaredMethod("fileProbeTasklet", String.class);

            assertThat(beanMethod.isAnnotationPresent(StepScope.class))
                    .as("without step scope the mode could not be read from a job parameter")
                    .isTrue();
            assertThat(beanMethod.getParameters()[0].getAnnotation(Value.class))
                    .isNotNull()
                    .extracting(Value::value)
                    .isEqualTo("#{jobParameters['"
                            + JobParameterValidators.FILE_PROBE_MODE_KEY + "']}");
        }

        @Test
        @DisplayName("an unrecognised mode fails when the tasklet is bound, so no pass ever begins "
                + "against a defaulted file")
        void anUnrecognisedModeFailsWhenBound() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> configuration.fileProbeTasklet("tcatbalf"));

            Mockito.verifyNoInteractions(fileMaintenanceService);
        }

        @Test
        @DisplayName("a completed pass records its elapsed time and its record count, and neither "
                + "carries a target figure")
        void aCompletedPassRecordsItsTimeAndCount() throws Exception {
            Mockito.when(fileMaintenanceService.readAccountFile())
                    .thenReturn(summary("CBACT01C", "ACCTFILE", 50L));

            run(ProbeMode.ACCOUNT.parameterValue());

            assertThat(meterRegistry.get("carddemo.batch.fileprobe.step")
                    .tag("mode", "account")
                    .tag("program", "CBACT01C")
                    .tag("resource", "ACCTFILE")
                    .tag("outcome", "COMPLETED")
                    .timer().count()).isEqualTo(1L);
            assertThat(meterRegistry.get("carddemo.batch.fileprobe.records")
                    .tag("mode", "account")
                    .counter().count()).isEqualTo(50.0d);
        }

        @Test
        @DisplayName("a failed pass is timed as an abend and rethrown unchanged, because the raw status "
                + "and the abend announcement were already emitted in that order")
        void aFailedPassIsTimedAsAnAbendAndRethrown() {
            final AbendException raised =
                    new AbendException(AbendException.BATCH_ABEND_CODE, "CBACT01C", "STATUS 31",
                            "ERROR READING ACCTFILE");
            Mockito.when(fileMaintenanceService.readAccountFile()).thenThrow(raised);

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> run(ProbeMode.ACCOUNT.parameterValue()))
                    .isSameAs(raised);

            assertThat(meterRegistry.get("carddemo.batch.fileprobe.step")
                    .tag("outcome", "ABENDED")
                    .timer().count()).isEqualTo(1L);
            assertThat(meterRegistry.find("carddemo.batch.fileprobe.records").counter())
                    .as("no record count is published for a pass that never reached end of file")
                    .isNull();
        }

        @Test
        @DisplayName("a completed pass leaves the step contribution untouched, so a condition-code gate "
                + "elsewhere reads it as an ordinary success")
        void aCompletedPassLeavesTheContributionUntouched() throws Exception {
            Mockito.when(fileMaintenanceService.readCardFile())
                    .thenReturn(summary("CBACT02C", "CARDFILE", 50L));
            final StepContribution contribution = contribution();

            configuration.fileProbeTasklet(ProbeMode.CARD.parameterValue())
                    .execute(contribution, chunkContext());

            assertThat(contribution.getReadCount()).isZero();
            assertThat(contribution.getWriteCount()).isZero();
            assertThat(contribution.getExitStatus().getExitCode()).isEqualTo("EXECUTING");
        }
    }

    @Nested
    @DisplayName("the cross-reference mode, which is the correction this configuration carries")
    class TheCrossReferenceMode {

        @Test
        @DisplayName("it delegates to the one service method that owns the CBACT03C paragraphs")
        void itDelegatesToTheCrossReferenceReader() throws Exception {
            Mockito.when(fileMaintenanceService.readCardCrossReferenceFile())
                    .thenReturn(summary("CBACT03C", "XREFFILE", 3L));

            assertThat(run(ProbeMode.CROSS_REFERENCE.parameterValue()))
                    .isEqualTo(RepeatStatus.FINISHED);

            Mockito.verify(fileMaintenanceService)
                    .readCardCrossReferenceFile(
                            Mockito.any(java.util.function.BooleanSupplier.class));
            Mockito.verify(fileMaintenanceService).readCardCrossReferenceFile();
            Mockito.verifyNoMoreInteractions(fileMaintenanceService);
        }

        @Test
        @DisplayName("it never delegates to the equally wide category-balance reader")
        void itNeverDelegatesToTheCategoryBalanceReader() throws Exception {
            Mockito.when(fileMaintenanceService.readCardCrossReferenceFile())
                    .thenReturn(summary("CBACT03C", "XREFFILE", 3L));

            run(ProbeMode.CROSS_REFERENCE.parameterValue());

            Mockito.verify(fileMaintenanceService, Mockito.never())
                    .readTransactionCategoryBalanceFile(ArgumentMatchers.any());
        }

        @Test
        @DisplayName("every row is delivered and the count is published, which is what proves the loop "
                + "ran to the end of the file")
        void everyRowIsDeliveredAndCounted() throws Exception {
            Mockito.when(fileMaintenanceService.readCardCrossReferenceFile())
                    .thenReturn(summary("CBACT03C", "XREFFILE", 3L));

            run(ProbeMode.CROSS_REFERENCE.parameterValue());

            assertThat(meterRegistry.get("carddemo.batch.fileprobe.records")
                    .tag("mode", "crossReference")
                    .tag("program", "CBACT03C")
                    .tag("resource", "XREFFILE")
                    .counter().count()).isEqualTo(3.0d);
        }

        @Test
        @DisplayName("an empty file completes normally, because end of file is a distinct outcome and "
                + "never an error")
        void anEmptyFileCompletesNormally() {
            Mockito.when(fileMaintenanceService.readCardCrossReferenceFile())
                    .thenReturn(summary("CBACT03C", "XREFFILE", 0L));

            assertThatCode(() -> run(ProbeMode.CROSS_REFERENCE.parameterValue()))
                    .doesNotThrowAnyException();

            assertThat(meterRegistry.get("carddemo.batch.fileprobe.step")
                    .tag("mode", "crossReference")
                    .tag("outcome", "COMPLETED")
                    .timer().count()).isEqualTo(1L);
        }

        @Test
        @DisplayName("a failure of the scan reaches the ordered abend path rather than being reported "
                + "as an empty file")
        void aFailureOfTheScanAbends() {
            final AbendException raised =
                    new AbendException(AbendException.BATCH_ABEND_CODE, "CBACT03C", "STATUS 31",
                            "ERROR READING XREFFILE");
            Mockito.when(fileMaintenanceService.readCardCrossReferenceFile()).thenThrow(raised);

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> run(ProbeMode.CROSS_REFERENCE.parameterValue()))
                    .isSameAs(raised);

            assertThat(meterRegistry.get("carddemo.batch.fileprobe.step")
                    .tag("mode", "crossReference")
                    .tag("outcome", "ABENDED")
                    .timer().count()).isEqualTo(1L);
        }

        @Test
        @DisplayName("a fixed clock is honoured, so the batch timestamps the template emits are "
                + "reproducible")
        void aFixedClockIsHonoured() {
            clock = Clock.fixed(java.time.Instant.parse("2022-07-19T23:23:07Z"), ZoneOffset.UTC);
            configuration = newConfiguration();
            Mockito.when(fileMaintenanceService.readCardCrossReferenceFile())
                    .thenReturn(summary("CBACT03C", "XREFFILE", 3L));

            assertThatCode(() -> run(ProbeMode.CROSS_REFERENCE.parameterValue()))
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("nothing fires when the context starts")
    class NothingFires {

        @Test
        @DisplayName("the configuration is no runner, lifecycle participant or event listener")
        void theConfigurationStartsNothingOfItsOwn() {
            for (final Class<?> selfStarting : SELF_STARTING_ROLES) {
                assertThat(selfStarting.isAssignableFrom(FileProbeJobConfig.class))
                        .as("a job configuration must not be a %s", selfStarting.getSimpleName())
                        .isFalse();
            }
        }

        @Test
        @DisplayName("it declares no construction or shutdown callback")
        void itDeclaresNoLifecycleCallback() {
            for (final Method declared : FileProbeJobConfig.class.getDeclaredMethods()) {
                assertThat(declared.isAnnotationPresent(PostConstruct.class))
                        .as("%s must not run on construction", declared.getName())
                        .isFalse();
                assertThat(declared.isAnnotationPresent(PreDestroy.class))
                        .as("%s must not run on shutdown", declared.getName())
                        .isFalse();
            }
        }

        @Test
        @DisplayName("it carries no enabling annotation, because the batch auto-configuration backs "
                + "off when one is present and no trigger may launch a job unattended")
        void itCarriesNoEnablingAnnotation() {
            assertThat(FileProbeJobConfig.class.isAnnotationPresent(EnableBatchProcessing.class))
                    .isFalse();
            assertThat(FileProbeJobConfig.class.isAnnotationPresent(EnableScheduling.class))
                    .isFalse();
        }
    }
}
