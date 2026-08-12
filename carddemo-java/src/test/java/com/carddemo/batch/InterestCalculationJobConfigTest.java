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
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.carddemo.batch.step.InterestCalculationProcessor;
import com.carddemo.batch.step.StagedGenerationStore;
import com.carddemo.domain.Account;
import com.carddemo.domain.Transaction;
import com.carddemo.domain.TransactionCategoryBalance;
import com.carddemo.exception.AbendException;
import com.carddemo.repository.TransactionCategoryBalanceRepository;
import com.carddemo.service.DateValidationService;
import com.carddemo.service.InterestCalculationService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionListener;
import org.springframework.batch.core.JobInstance;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.JobParametersIncrementer;
import org.springframework.batch.core.JobParametersInvalidException;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.launch.support.RunIdIncrementer;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.StepLocator;
import org.springframework.batch.support.transaction.ResourcelessTransactionManager;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * The interest accrual job configuration, exercised over mocked collaborators.
 *
 * <p>What this specification is about is the <strong>wiring</strong>: the names the job and its step are
 * registered under, the parameter validator that guards the launch boundary, the shape of the flow, the
 * per-execution generation the step writes, and the guards on the two configured resource names.
 * Nothing here re-asserts a behaviour that belongs to a collaborator - the arithmetic, the rate lookup,
 * the fee paragraph, the control break and the record layout each have their own specification - because
 * a duplicated assertion drifts from the one that owns it.
 *
 * <p>The end-to-end assertions that need a real server and the real migrations are in
 * {@code InterestCalculationJobIT}; this file deliberately needs neither.
 *
 * <p>No legacy source text is transcribed here.
 */
@DisplayName("InterestCalculationJobConfig - one named job, one named step, one guarded launch")
class InterestCalculationJobConfigTest {

    /** The launch parameter the legacy step carries, ten characters with no separator. */
    private static final String RUN_DATE = "2022071800";

    /** An account the fixture rows key on. */
    private static final String ACCOUNT_ID = "00000000042";

    /** The source file, for the postures that are textual by nature. */
    private static final Path SOURCE = Path.of("src", "main", "java", "com", "carddemo", "batch",
            "InterestCalculationJobConfig.java");

    /** The driving input, mocked so the read loop can be given an exact stream of rows. */
    private TransactionCategoryBalanceRepository categoryBalances;

    /** The translated program, mocked so this specification asserts wiring and not behaviour. */
    private InterestCalculationService interestCalculationService;

    /** A real registry, so the timers the step records are observable rather than swallowed. */
    private MeterRegistry meterRegistry;

    /** The staging area the generation resolves within. */
    @TempDir
    private Path stagingDirectory;

    /** The configuration under test. */
    private InterestCalculationJobConfig config;

    @BeforeEach
    void buildConfigurationOverMockedCollaborators() {
        this.categoryBalances = mock(TransactionCategoryBalanceRepository.class, invocation -> {
            if (invocation.getMethod().getName().equals("findAfterKey")) {
                final String cursor = (String) invocation.getArgument(0)
                        + invocation.getArgument(1) + invocation.getArgument(2);
                final org.springframework.data.domain.Pageable page = invocation.getArgument(3);
                return this.categoryBalances.findAll(Sort.by(
                                Sort.Order.asc("trancatAcctId"),
                                Sort.Order.asc("trancatTypeCd"),
                                Sort.Order.asc("trancatCd")))
                        .stream()
                        .filter(row -> (row.getTrancatAcctId() + row.getTrancatTypeCd()
                                + row.getTrancatCd()).compareTo(cursor) > 0)
                        .sorted(java.util.Comparator.comparing(row ->
                                row.getTrancatAcctId() + row.getTrancatTypeCd()
                                        + row.getTrancatCd()))
                        .limit(page.getPageSize())
                        .toList();
            }
            return org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation);
        });
        this.interestCalculationService = mock(InterestCalculationService.class);
        this.meterRegistry = new SimpleMeterRegistry();
        this.config = configWith(this.stagingDirectory.toString(), "AWS.M2.CARDDEMO.SYSTRAN");
    }

    /**
     * Builds a configuration over the mocked collaborators and the two supplied resource names.
     *
     * @param staging the staging area
     * @param base the output generation group's logical name
     * @return the configuration
     */
    private InterestCalculationJobConfig configWith(final String staging, final String base) {
        final JobRepository jobRepository = mock(JobRepository.class);
        final PlatformTransactionManager transactionManager = new ResourcelessTransactionManager();
        final Clock fixed = Clock.fixed(Instant.parse("2022-07-19T23:23:05Z"), ZoneOffset.UTC);

        return new InterestCalculationJobConfig(jobRepository, transactionManager,
                new JobParameterValidators(new DateValidationService()),
                this.interestCalculationService, this.categoryBalances, this.meterRegistry, fixed,
                staging, base);
    }

    /** Assembles the job over its step, exactly as the container would. */
    private Job job() {
        final JobParametersIncrementer incrementer = new RunIdIncrementer();
        final JobExecutionListener boundaryListener = mock(JobExecutionListener.class);
        return this.config.interestCalculationJob(
                this.config.interestAccrualStep(),
                incrementer, boundaryListener);
    }

    /** A step execution whose job execution identifier names one generation. */
    private static StepExecution stepExecution(final long jobExecutionId, final String runDate) {
        final JobParameters parameters = runDate == null
                ? new JobParameters()
                : new JobParametersBuilder()
                        .addString(JobParameterValidators.INTEREST_PARM_DATE_KEY, runDate)
                        .toJobParameters();
        final JobExecution jobExecution = new JobExecution(
                new JobInstance(1L, InterestCalculationJobConfig.JOB_NAME), jobExecutionId,
                parameters);
        return jobExecution.createStepExecution(InterestCalculationJobConfig.STEP_NAME);
    }

    /** One category-balance row of the driving input. */
    private static TransactionCategoryBalance row(final String categoryCode) {
        return new TransactionCategoryBalance(ACCOUNT_ID, "01", categoryCode,
                new BigDecimal("1000.00"));
    }

    @Nested
    @DisplayName("The names and figures the launch surface depends on")
    class TheNamesAndFigures {

        @Test
        @DisplayName("the job and step carry stable registered names")
        void theJobAndStepCarryStableNames() {
            assertThat(InterestCalculationJobConfig.JOB_NAME).isEqualTo("interestCalculationJob");
            assertThat(InterestCalculationJobConfig.STEP_NAME).isEqualTo("interestAccrualStep");
            assertThat(job().getName()).isEqualTo(InterestCalculationJobConfig.JOB_NAME);
            assertThat(config.interestAccrualStep().getName())
                    .isEqualTo(InterestCalculationJobConfig.STEP_NAME);
        }

        @Test
        @DisplayName("the legacy identity is re-exported rather than restated")
        void theLegacyIdentityIsReExported() {
            assertThat(InterestCalculationJobConfig.LEGACY_JOB_MEMBER).isEqualTo("INTCALC");
            assertThat(InterestCalculationJobConfig.LEGACY_STEP_NAME).isEqualTo("STEP15");
            assertThat(InterestCalculationJobConfig.LEGACY_PROGRAM_NAME).isEqualTo("CBACT04C");
            assertThat(InterestCalculationJobConfig.LEGACY_JOB_MEMBER)
                    .isEqualTo(InterestCalculationProcessor.LEGACY_JOB);
            assertThat(InterestCalculationJobConfig.LEGACY_STEP_NAME)
                    .isEqualTo(InterestCalculationProcessor.LEGACY_STEP);
            assertThat(InterestCalculationJobConfig.LEGACY_PROGRAM_NAME)
                    .isEqualTo(InterestCalculationProcessor.LEGACY_PROGRAM);
        }

        @Test
        @DisplayName("the parameter key has one spelling in the module, not two")
        void theParameterKeyHasOneSpelling() {
            assertThat(InterestCalculationJobConfig.PARM_DATE_KEY)
                    .isEqualTo(JobParameterValidators.INTEREST_PARM_DATE_KEY)
                    .isEqualTo(InterestCalculationProcessor.PARM_DATE_KEY)
                    .isEqualTo("interestParmDate");
        }

        @Test
        @DisplayName("the data definitions of the legacy step are all named, including the one the "
                + "program never selects")
        void theDataDefinitionsAreNamed() {
            assertThat(InterestCalculationJobConfig.DD_TCATBALF).isEqualTo("TCATBALF");
            assertThat(InterestCalculationJobConfig.DD_XREFFILE).isEqualTo("XREFFILE");
            assertThat(InterestCalculationJobConfig.DD_ACCTFILE).isEqualTo("ACCTFILE");
            assertThat(InterestCalculationJobConfig.DD_DISCGRP).isEqualTo("DISCGRP");
            assertThat(InterestCalculationJobConfig.DD_TRANSACT).isEqualTo("TRANSACT");
            assertThat(InterestCalculationJobConfig.DD_XREFFIL1)
                    .as("the allocated-but-never-referenced definition is recorded, not modelled")
                    .isEqualTo("XREFFIL1")
                    .isEqualTo(InterestCalculationProcessor.LEGACY_UNREFERENCED_DD);
        }

        @Test
        @DisplayName("the output record is the layout's own width")
        void theOutputRecordIsTheLayoutWidth() {
            assertThat(InterestCalculationJobConfig.TRANSACT_RECORD_LENGTH)
                    .isEqualTo(350)
                    .isEqualTo(InterestCalculationProcessor.INTEREST_RECORD_LENGTH);
        }

        @Test
        @DisplayName("both resource names are configuration keys rather than paths")
        void bothResourceNamesAreConfigurationKeys() {
            assertThat(InterestCalculationJobConfig.STAGING_DIRECTORY_PROPERTY)
                    .isEqualTo("carddemo.batch.interest-calculation.staging-directory");
            assertThat(InterestCalculationJobConfig.TRANSACT_DATASET_BASE_PROPERTY)
                    .isEqualTo("carddemo.batch.interest-calculation.transact-dataset-base");
        }
    }

    @Nested
    @DisplayName("The launch boundary")
    class TheLaunchBoundary {

        @Test
        @DisplayName("the run date's validator is attached, and it refuses what the cascade refuses")
        void theRunDateValidatorIsAttached() throws JobParametersInvalidException {
            final Job job = job();

            assertThat(job.getJobParametersValidator()).isNotNull();
            assertThatNoException().isThrownBy(() -> job.getJobParametersValidator()
                    .validate(new JobParametersBuilder()
                            .addString(InterestCalculationJobConfig.PARM_DATE_KEY, RUN_DATE)
                            .toJobParameters()));
            assertThatExceptionOfType(JobParametersInvalidException.class)
                    .isThrownBy(() -> job.getJobParametersValidator().validate(new JobParameters()))
                    .withMessageContaining(InterestCalculationJobConfig.PARM_DATE_KEY);
            assertThatExceptionOfType(JobParametersInvalidException.class)
                    .isThrownBy(() -> job.getJobParametersValidator()
                            .validate(new JobParametersBuilder()
                                    .addString(InterestCalculationJobConfig.PARM_DATE_KEY,
                                            "2022-07-18")
                                    .toJobParameters()));
        }

        @Test
        @DisplayName("the job is restartable and advances to a next instance, as a resubmitted member "
                + "did")
        void theJobAdvancesToANextInstance() {
            final Job job = job();

            assertThat(job.getJobParametersIncrementer()).isNotNull();
            assertThat(job.isRestartable()).isTrue();
        }

        @Test
        @DisplayName("the flow is one step reached by a plain start, because the member declares no "
                + "condition-code gate")
        void theFlowIsOneStepWithNoGate() {
            final Job job = job();

            assertThat(job).isInstanceOf(StepLocator.class);
            assertThat(((StepLocator) job).getStepNames())
                    .containsExactly(InterestCalculationJobConfig.STEP_NAME);
        }
    }

    @Nested
    @DisplayName("The generation the step writes")
    class TheGeneration {

        @Test
        @DisplayName("it keeps the absolute-generation vocabulary at a ten-digit minimum width")
        void itIsNamedInTheLegacyForm() {
            assertThat(config.transactGeneration(7L).getFileName().toString())
                    .isEqualTo("AWS.M2.CARDDEMO.SYSTRAN.G0000000007V00");
        }

        @Test
        @DisplayName("it is distinct per execution, which is what a new generation per run means")
        void itIsDistinctPerExecution() {
            assertThat(config.transactGeneration(7L))
                    .isNotEqualTo(config.transactGeneration(8L));
        }

        @Test
        @DisplayName("its number never wraps onto an earlier execution")
        void itsNumberDoesNotWrap() {
            assertThat(config.transactGeneration(10_000L).getFileName().toString())
                    .isEqualTo("AWS.M2.CARDDEMO.SYSTRAN.G0000010000V00");
            assertThat(config.transactGeneration(10_007L).getFileName().toString())
                    .isEqualTo("AWS.M2.CARDDEMO.SYSTRAN.G0000010007V00")
                    .isNotEqualTo(config.transactGeneration(7L).getFileName().toString());
        }

        @Test
        @DisplayName("it resolves against the configured staging area and carries no path from source")
        void itResolvesAgainstTheConfiguredStagingArea() {
            assertThat(config.transactGeneration(1L).getParent()).isEqualTo(stagingDirectory);
        }
    }

    @Nested
    @DisplayName("The configured resource names are guarded, because a blank one names a directory")
    class TheResourceNamesAreGuarded {

        @Test
        @DisplayName("a blank staging area is refused")
        void aBlankStagingAreaIsRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> configWith("   ", "AWS.M2.CARDDEMO.SYSTRAN"))
                    .withMessageContaining("stagingDirectory");
        }

        @Test
        @DisplayName("a blank generation group name is refused")
        void aBlankGenerationGroupNameIsRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> configWith(stagingDirectory.toString(), ""))
                    .withMessageContaining("transactDatasetBase");
        }

        @Test
        @DisplayName("an absent collaborator is refused rather than degraded")
        void anAbsentCollaboratorIsRefused() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new InterestCalculationJobConfig(null,
                            new ResourcelessTransactionManager(),
                            new JobParameterValidators(new DateValidationService()),
                            interestCalculationService, categoryBalances, meterRegistry,
                            Clock.systemUTC(), stagingDirectory.toString(), "BASE"));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new InterestCalculationJobConfig(mock(JobRepository.class),
                            new ResourcelessTransactionManager(),
                            new JobParameterValidators(new DateValidationService()),
                            interestCalculationService, categoryBalances, meterRegistry, null,
                            stagingDirectory.toString(), "BASE"));
        }
    }

    @Nested
    @DisplayName("The pass, driven without a launcher")
    class ThePass {

        @Test
        @DisplayName("an empty driving input reads nothing, closes no group and leaves an empty "
                + "generation")
        void anEmptyDrivingInputWritesNothing() throws IOException {
            when(categoryBalances.findAll(any(Sort.class))).thenReturn(List.of());

            final StepExecution execution = stepExecution(11L, RUN_DATE);
            assertThat(config.runAccrualPass(execution).recordsRead()).isZero();

            verify(interestCalculationService, never())
                    .calculateGroupInterest(anyString(), anyString(), any(), anyLong(), any(), any());

            final Path generation = config.transactGeneration(11L);
            assertThat(generation).exists();
            assertThat(Files.readAllBytes(generation)).isEmpty();
        }

        @Test
        @DisplayName("the driving input is read in record-key order, which is what the control break "
                + "depends on")
        void theDrivingInputIsReadInRecordKeyOrder() {
            when(categoryBalances.findAll(any(Sort.class))).thenReturn(List.of());

            config.runAccrualPass(stepExecution(12L, RUN_DATE));

            verify(categoryBalances).findAll(Sort.by(Sort.Order.asc("trancatAcctId"),
                    Sort.Order.asc("trancatTypeCd"), Sort.Order.asc("trancatCd")));
        }

        @Test
        @DisplayName("the final group is closed at end of file, and its records reach the generation "
                + "at the layout's own width")
        void theFinalGroupIsClosedAtEndOfFile() throws IOException {
            when(categoryBalances.findAll(any(Sort.class))).thenReturn(List.of(row("0005")));
            // The service writes each record through the writer it is given, at the point the legacy
            // writes it, so the stub must do the same or the wiring under test is never exercised.
            when(interestCalculationService.calculateGroupInterest(anyString(), anyString(), any(),
                    anyLong(), any(), any())).thenAnswer(invocation -> {
                        final InterestCalculationService.GroupInterestResult group = oneGroupResult();
                        final Consumer<Transaction> writer = invocation.getArgument(4);
                        group.interestTransactions().forEach(writer);
                        return group;
                    });

            final StepExecution execution = stepExecution(13L, RUN_DATE);
            assertThat(config.runAccrualPass(execution).recordsRead()).isEqualTo(1L);

            final byte[] artefact = Files.readAllBytes(config.transactGeneration(13L));
            assertThat(artefact)
                    .as("one synthesized transaction, one fixed-length record, no separator")
                    .hasSize(InterestCalculationJobConfig.TRANSACT_RECORD_LENGTH);
            assertThat(new String(artefact, StandardCharsets.US_ASCII))
                    .startsWith(InterestCalculationProcessor.interestTranId(RUN_DATE, 1L));
            assertThat(execution.getExecutionContext()
                    .getInt(InterestCalculationProcessor.CONTEXT_GROUPS_CLOSED))
                    .as("the last account has no successor row, so only the end-of-file break "
                            + "closes it")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("a group that closes mid-stream reaches the generation too, which is what proves "
                + "the writer is bound before the first row rather than at end of file")
        void aMidStreamGroupAlsoReachesTheGeneration() throws IOException {
            final TransactionCategoryBalance otherAccount = new TransactionCategoryBalance(
                    "00000000043", "01", "0005", new BigDecimal("1000.00"));
            when(categoryBalances.findAll(any(Sort.class)))
                    .thenReturn(List.of(row("0005"), otherAccount));
            final AtomicLong suffix = new AtomicLong();
            when(interestCalculationService.calculateGroupInterest(anyString(), anyString(), any(),
                    anyLong(), any(), any())).thenAnswer(invocation -> {
                        final InterestCalculationService.GroupInterestResult group =
                                oneGroupResult(invocation.getArgument(1), suffix.incrementAndGet());
                        final Consumer<Transaction> writer = invocation.getArgument(4);
                        group.interestTransactions().forEach(writer);
                        return group;
                    });

            final StepExecution execution = stepExecution(23L, RUN_DATE);
            assertThat(config.runAccrualPass(execution).recordsRead()).isEqualTo(2L);

            final byte[] artefact = Files.readAllBytes(config.transactGeneration(23L));
            assertThat(artefact)
                    .as("the mid-stream break's record and the end-of-file break's record, both of them")
                    .hasSize(2 * InterestCalculationJobConfig.TRANSACT_RECORD_LENGTH);
            final String rendered = new String(artefact, StandardCharsets.US_ASCII);
            assertThat(rendered)
                    .startsWith(InterestCalculationProcessor.interestTranId(RUN_DATE, 1L));
            assertThat(rendered.substring(InterestCalculationJobConfig.TRANSACT_RECORD_LENGTH))
                    .startsWith(InterestCalculationProcessor.interestTranId(RUN_DATE, 2L));
        }

        @Test
        @DisplayName("a launch without the run date fails the pass rather than minting identifiers "
                + "from nothing")
        void aLaunchWithoutTheRunDateFailsThePass() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> config.runAccrualPass(stepExecution(14L, null)))
                    .withMessageContaining(InterestCalculationJobConfig.PARM_DATE_KEY);
        }

        @Test
        @DisplayName("an absent execution is refused")
        void anAbsentExecutionIsRefused() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> config.runAccrualPass(null));
        }

        @Test
        @DisplayName("a failure while the driving input is being opened abends before any handle "
                + "exists, and the closes are never reached")
        void aFailureOpeningTheDrivingInputAbends() {
            when(categoryBalances.findAll(any(Sort.class)))
                    .thenThrow(new DataAccessResourceFailureException("the master is unreachable"));

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> config.runAccrualPass(stepExecution(16L, RUN_DATE)))
                    .satisfies(abend -> assertThat(abend.getMessage())
                            .contains(InterestCalculationJobConfig.DD_TCATBALF));

            assertThat(config.transactGeneration(16L))
                    .as("the generation is never opened, so no artefact is left behind")
                    .doesNotExist();
        }

        @Test
        @DisplayName("a failure while a group is closing abends, releases the generation handle and "
                + "then discards the working file the pass never sealed")
        void aFailureClosingAGroupReleasesTheHandle() {
            when(categoryBalances.findAll(any(Sort.class)))
                    .thenReturn(List.of(row("0005"), row("0006")));
            when(interestCalculationService.calculateGroupInterest(anyString(), anyString(), any(),
                    anyLong(), any(), any())).thenThrow(new AbendException(AbendException.BATCH_ABEND_CODE,
                            InterestCalculationJobConfig.LEGACY_PROGRAM_NAME,
                            "FILE STATUS 23 operation=READ resource="
                                    + InterestCalculationJobConfig.DD_DISCGRP,
                            "ERROR READING DISCLOSURE GROUP FILE"));

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> config.runAccrualPass(stepExecution(17L, RUN_DATE)));

            // THE ABNORMAL DISPOSITION IS DELETE, NOT KEEP. app/jcl/INTCALC.jcl L37 declares the output
            // DISP=(NEW,CATLG,DELETE): a newly allocated dataset is catalogued when the step ends
            // normally and deleted when it does not. The seal and the registration happen in the caller
            // after the pass returns, so a pass that abends leaves a working file no registry names and
            // the job-boundary cleanup could never identify - which is the residue the template's
            // abnormal end now discards. See docs/decision-log.md entry DL-289.
            assertThat(StagedGenerationStore.workingPath(config.transactGeneration(17L)))
                    .as("the handle was released and the unsealed working file discarded with it")
                    .doesNotExist();
            assertThat(config.transactGeneration(17L))
                    .as("and a failed pass never advertises a completed generation either")
                    .doesNotExist();
        }

        @Test
        @DisplayName("the step is one sequential invocation without an encompassing business "
                + "transaction, timed on the shared batch step timer")
        void theStepIsASingleInvocation() throws Exception {
            when(categoryBalances.findAll(any(Sort.class))).thenReturn(List.of());

            final Step step = config.interestAccrualStep();
            final StepExecution execution = stepExecution(15L, RUN_DATE);
            step.execute(execution);

            assertThat(execution.getCommitCount()).isEqualTo(1);
            assertThat(meterRegistry.find("carddemo.batch.cobol.step")
                    .tag("step", InterestCalculationJobConfig.LEGACY_PROGRAM_NAME)
                    .timers())
                    .as("the pass is measured, and no threshold is stated for it anywhere")
                    .isNotEmpty();
            assertThat(StagedGenerationStore.registeredArtifactCount(execution.getJobExecution()))
                    .as("the step REGISTERS its sealed generation and does not upload it: the shared"
                            + " job-boundary listener publishes registered artefacts once the whole"
                            + " submission has completed, which is what keeps one generation to one"
                            + " canonical key and keeps a failed submission's generation out of the"
                            + " bucket entirely (DL-212)")
                    .isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("The postures that are textual by nature")
    class ThePosturesThatAreTextual {

        @Test
        @DisplayName("the tasklet suppresses an encompassing transaction so closed account groups "
                + "own their commits")
        void itSuppressesTheEncompassingTaskletTransaction() throws IOException {
            assertThat(Files.readString(SOURCE, StandardCharsets.UTF_8))
                    .contains("PROPAGATION_NOT_SUPPORTED")
                    .contains(".transactionAttribute(NO_ENCOMPASSING_TRANSACTION)")
                    .doesNotContain("ResourcelessTransactionManager");
        }

        @Test
        @DisplayName("nothing in it fires when the context starts")
        void nothingFiresWhenTheContextStarts() throws IOException {
            assertThat(Files.readString(SOURCE, StandardCharsets.UTF_8))
                    .doesNotContain("CommandLineRunner")
                    .doesNotContain("ApplicationRunner")
                    .doesNotContain("@PostConstruct")
                    .doesNotContain("SmartLifecycle")
                    .doesNotContain("@EnableScheduling")
                    .doesNotContain("ApplicationListener")
                    .doesNotContain("spring.batch.job.name");
        }

        @Test
        @DisplayName("it does not enable batch processing, which would switch off the "
                + "auto-configuration the module depends on")
        void itDoesNotEnableBatchProcessing() throws IOException {
            assertThat(Files.readString(SOURCE, StandardCharsets.UTF_8))
                    .doesNotContain("@EnableBatchProcessing");
        }

        @Test
        @DisplayName("it states no rounding policy, because the codec owns the only one")
        void itStatesNoRoundingPolicy() throws IOException {
            assertThat(Files.readString(SOURCE, StandardCharsets.UTF_8))
                    .doesNotContain("setScale")
                    .doesNotContain("RoundingMode")
                    .doesNotContain("HALF_EVEN")
                    .doesNotContain("HALF_UP");
        }

        @Test
        @DisplayName("it configures no retry and no backoff, because the legacy has exactly one probe")
        void itConfiguresNoRetryAndNoBackoff() throws IOException {
            assertThat(Files.readString(SOURCE, StandardCharsets.UTF_8))
                    .doesNotContain("RetryPolicy")
                    .doesNotContain("BackOff")
                    .doesNotContain("faultTolerant")
                    .doesNotContain(".retry(")
                    .doesNotContain(".skip(");
        }

        @Test
        @DisplayName("execution is strictly sequential, because the output is ordered records")
        void executionIsStrictlySequential() throws IOException {
            assertThat(Files.readString(SOURCE, StandardCharsets.UTF_8))
                    .doesNotContain("TaskExecutor")
                    .doesNotContain("Partitioner")
                    .doesNotContain(".chunk(")
                    .doesNotContain("split(");
        }

        @Test
        @DisplayName("it spawns no process, although the legacy step ran a program")
        void itSpawnsNoProcess() throws IOException {
            assertThat(Files.readString(SOURCE, StandardCharsets.UTF_8))
                    .doesNotContain("Runtime.getRuntime")
                    .doesNotContain("ProcessBuilder")
                    .doesNotContain("java.lang.reflect")
                    .doesNotContain("Class.forName")
                    .doesNotContain("@SuppressWarnings");
        }

        @Test
        @DisplayName("it issues no schema statement and no assembled query, because the migrations "
                + "own the schema")
        void itIssuesNoSchemaStatement() throws IOException {
            assertThat(Files.readString(SOURCE, StandardCharsets.UTF_8))
                    .doesNotContain("CREATE TABLE")
                    .doesNotContain("DROP TABLE")
                    .doesNotContain("ALTER TABLE")
                    .doesNotContain("createNativeQuery");
        }

        @Test
        @DisplayName("it writes no diagnostic to a stream the module does not own")
        void itWritesNoDiagnosticToAStream() throws IOException {
            assertThat(Files.readString(SOURCE, StandardCharsets.UTF_8))
                    .doesNotContain("System.out")
                    .doesNotContain("System.err")
                    .doesNotContain("printStackTrace")
                    .doesNotContain("javax.")
                    .doesNotContain("com.cardemo");
        }
    }

    /**
     * One group's outcome, shaped exactly as the service shapes it, so the control break accepts it.
     *
     * @return a group that synthesized one transaction from one row
     */
    private InterestCalculationService.GroupInterestResult oneGroupResult() {
        return oneGroupResult(ACCOUNT_ID, 1L);
    }

    /**
     * One accruing group for the named account, whose single record carries the given identifier suffix,
     * so a stream with more than one group renders more than one distinguishable record.
     *
     * @param  accountId the account the group keys on, which the stage checks against the key it closed
     * @param  suffix    the identifier suffix this group's record carries
     * @return the group result the stubbed service reports
     */
    private InterestCalculationService.GroupInterestResult oneGroupResult(final String accountId,
            final long suffix) {
        final BigDecimal interest = new BigDecimal("0.83");
        final Transaction synthesized = new Transaction(
                InterestCalculationProcessor.interestTranId(RUN_DATE, suffix),
                InterestCalculationProcessor.INTEREST_TRAN_TYPE_CD,
                InterestCalculationProcessor.INTEREST_TRAN_CAT_CD,
                InterestCalculationProcessor.INTEREST_TRAN_SOURCE,
                pad(InterestCalculationProcessor.INTEREST_DESCRIPTION_PREFIX + accountId, 100),
                interest, InterestCalculationProcessor.INTEREST_MERCHANT_ID, pad("", 50),
                pad("", 50), pad("", 10), "4111111111111111",
                "2022-07-19-23.23.05.000000", "2022-07-19-23.23.05.000000");
        final Account updated = new Account(accountId, "Y", new BigDecimal("100.83"),
                new BigDecimal("5000.00"), new BigDecimal("1000.00"), "2014-11-20", "2025-05-20",
                "2025-05-20", BigDecimal.ZERO, BigDecimal.ZERO, pad("98101", 10), "DEFAULT   ");
        final InterestCalculationService.CategoryInterest categoryInterest =
                new InterestCalculationService.CategoryInterest(accountId, "01", "0005",
                        new BigDecimal("1000.00"), new BigDecimal("1.00"), interest, false, false,
                        synthesized);

        return new InterestCalculationService.GroupInterestResult(accountId, interest,
                List.of(categoryInterest), List.of(synthesized), updated, true, false, false, 1,
                suffix);
    }

    /** Left-justifies a value into a field of the given width, space padded as the layout is. */
    private static String pad(final String value, final int width) {
        return value + " ".repeat(width - value.length());
    }
}
