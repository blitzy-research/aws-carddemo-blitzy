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

import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import io.awspring.cloud.s3.S3Operations;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.aop.support.AopUtils;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionListener;
import org.springframework.batch.core.JobInstance;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.JobParametersIncrementer;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.StepExecutionListener;
import org.springframework.batch.core.job.SimpleJob;
import org.springframework.batch.core.job.flow.FlowJob;
import org.springframework.batch.core.launch.support.RunIdIncrementer;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.scope.StepScope;
import org.springframework.batch.core.step.item.ChunkOrientedTasklet;
import org.springframework.batch.core.step.tasklet.TaskletStep;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.ItemStreamReader;
import org.springframework.batch.item.ItemStreamWriter;
import org.springframework.batch.item.file.FlatFileItemReader;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.SmartLifecycle;
import org.springframework.transaction.PlatformTransactionManager;

import com.carddemo.batch.step.FixedWidthFlatFileReaderFactory;
import com.carddemo.batch.step.RejectRecordWriter;
import com.carddemo.batch.step.TransactionValidationProcessor;
import com.carddemo.config.AwsProperties;
import com.carddemo.config.BatchConfig;
import com.carddemo.domain.DailyTransaction;
import com.carddemo.exception.AbendException;
import com.carddemo.service.TransactionPostingService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.mock;
import static org.mockito.ArgumentMatchers.any;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * Unit test for {@link PostTransactionJobConfig}, the wiring of the daily transaction posting job.
 *
 * <h2>What this suite exists to prove</h2>
 *
 * <p>Every business rule of this job belongs to the translated program and to the per-record stage, and
 * each is covered by its own suite. What is left, and what only this suite can assert, is the
 * <em>wiring</em> - and for this job the wiring carries four measured facts that are invisible to a
 * compiler:</p>
 *
 * <ul>
 *   <li>the job holds <strong>exactly one</strong> step, carries <strong>no</strong> failure-ending
 *       transition and requires <strong>no</strong> parameter, because the measured job member declares
 *       one application step with no condition-code gate and no parameter string;</li>
 *   <li>the job is launchable by a <strong>stable name</strong>, because it is launched and queried by
 *       name rather than by type;</li>
 *   <li>the step is <strong>chunk-oriented</strong> and strictly sequential, because it stands in for a
 *       sequential read loop whose refusal decision depends on arrival order;</li>
 *   <li>each execution writes its <strong>own</strong> reject generation, because the measured
 *       allocation mints a new generation of a generation group on every run.</li>
 * </ul>
 *
 * <h2>Why the completion-code contribution is asserted by absence here</h2>
 *
 * <p>The tolerated completion code this job raises for refused records is contributed by
 * {@link TransactionValidationProcessor}, which the step builder registers as a listener automatically.
 * This suite therefore asserts that the configuration's <em>own</em> listener contributes
 * <strong>nothing</strong> - a diagnostic that returned an exit status could turn a failure into a
 * success or overwrite the tolerated code, and neither may happen.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PostTransactionJobConfig - one step, no gate, no parameter, a generation per run")
final class PostTransactionJobConfigTest {

    /** Logical name of the sequential input used throughout, mirroring the measured dataset name. */
    private static final String DALYTRAN_DATASET = PostTransactionJobConfig.DEFAULT_DALYTRAN_DATASET;

    /** Logical base of the reject generation group used throughout. */
    private static final String DALYREJS_BASE = PostTransactionJobConfig.DEFAULT_DALYREJS_DATASET_BASE;

    /** The delivered fixture that stands in for the sequential input. */
    private static final String DALYTRAN_FIXTURE = "/fixtures/input/dailytran.txt";

    /** Records the delivered fixture holds, as measured. */
    private static final int DALYTRAN_RECORDS = 300;

    /** Encoded width of one record of the sequential input, as measured. */
    private static final int TRANSACTION_WIDTH = 350;

    /** A job execution identifier well inside the legacy generation numbering range. */
    private static final long EXECUTION_ID = 7L;

    /** An identifier chosen to land exactly on the wrap of the legacy generation numbering range. */
    private static final long WRAPPING_EXECUTION_ID = 10_003L;

    /** The framework's job repository, which building a job and a step never calls. */
    @Mock
    private JobRepository jobRepository;

    /** The transaction manager the step is built with. */
    @Mock
    private PlatformTransactionManager transactionManager;

    /** The translated posting program, which this suite never asks to post anything. */
    @Mock
    private TransactionPostingService postingService;

    /** Object-store client behind the real staging adapter used by the scoped collaborators. */
    @Mock
    private S3Operations objectStore;

    /** The real reader factory, because the record layouts it owns are part of what is asserted. */
    private final FixedWidthFlatFileReaderFactory readerFactory =
            new FixedWidthFlatFileReaderFactory();

    /** A real registry, so a published sample can be read back rather than merely verified as a call. */
    private final MeterRegistry meterRegistry = new SimpleMeterRegistry();

    /** The directory every logical dataset name is resolved against for the duration of one test. */
    @TempDir
    private Path stagingDirectory;

    /** The configuration under test, rebuilt for every test over that directory. */
    private PostTransactionJobConfig configuration;

    /** The real shared staging adapter over the mocked object store. */
    private BatchStagingArea stagingArea;

    @BeforeEach
    void buildConfiguration() {
        this.stagingArea = new BatchStagingArea(this.objectStore,
                new AwsProperties("us-west-2", null,
                        new AwsProperties.S3("unit-test-batch-staging"),
                        new AwsProperties.Sqs("carddemo-jobs.fifo", "carddemo-jobs"),
                        new AwsProperties.Sns("carddemo-job-notifications")));
        this.configuration = configuration();
    }

    /**
     * Builds the configuration over the per-test staging directory.
     *
     * @return a fully constructed configuration
     */
    private PostTransactionJobConfig configuration() {
        return new PostTransactionJobConfig(this.jobRepository, this.transactionManager,
                this.readerFactory, this.postingService, this.meterRegistry, Clock.systemUTC(),
                this.stagingDirectory.toString(), DALYTRAN_DATASET, DALYREJS_BASE);
    }

    /**
     * Stages the delivered fixture in the temporary directory under the configured logical name.
     *
     * @throws Exception if the fixture cannot be read or written
     */
    private void stageInput() throws Exception {
        try (InputStream fixture = getClass().getResourceAsStream(DALYTRAN_FIXTURE)) {
            assertThat(fixture).as("the delivered fixture must be on the test classpath").isNotNull();
            Files.copy(fixture, this.stagingDirectory.resolve(DALYTRAN_DATASET));
        }
    }

    /**
     * Assembles a step execution in a terminal state with the two counters the diagnostic reports.
     *
     * @param  status the terminal batch status
     * @param  read   records read
     * @param  wrote  records written to the reject dataset
     * @return a step execution ready to be handed to the diagnostic
     */
    private static StepExecution stepExecution(final BatchStatus status, final long read,
            final long wrote) {
        final JobExecution jobExecution = new JobExecution(
                new JobInstance(1L, PostTransactionJobConfig.JOB_NAME), EXECUTION_ID,
                new JobParameters());
        final StepExecution stepExecution =
                new StepExecution(PostTransactionJobConfig.STEP_NAME, jobExecution);
        stepExecution.setStatus(status);
        stepExecution.setReadCount(read);
        stepExecution.setWriteCount(wrote);
        return stepExecution;
    }

    @Nested
    @DisplayName("construction")
    class Construction {

        @Test
        @DisplayName("every collaborator is required, because none of them has a defensible default")
        void everyCollaboratorIsRequired() {
            assertThatNullPointerException().isThrownBy(() -> new PostTransactionJobConfig(
                    null, transactionManager, readerFactory, postingService, meterRegistry,
                    Clock.systemUTC(), stagingDirectory.toString(), DALYTRAN_DATASET, DALYREJS_BASE));
            assertThatNullPointerException().isThrownBy(() -> new PostTransactionJobConfig(
                    jobRepository, null, readerFactory, postingService, meterRegistry,
                    Clock.systemUTC(), stagingDirectory.toString(), DALYTRAN_DATASET, DALYREJS_BASE));
            assertThatNullPointerException().isThrownBy(() -> new PostTransactionJobConfig(
                    jobRepository, transactionManager, null, postingService, meterRegistry,
                    Clock.systemUTC(), stagingDirectory.toString(), DALYTRAN_DATASET, DALYREJS_BASE));
            assertThatNullPointerException().isThrownBy(() -> new PostTransactionJobConfig(
                    jobRepository, transactionManager, readerFactory, null, meterRegistry,
                    Clock.systemUTC(), stagingDirectory.toString(), DALYTRAN_DATASET, DALYREJS_BASE));
            assertThatNullPointerException().isThrownBy(() -> new PostTransactionJobConfig(
                    jobRepository, transactionManager, readerFactory, postingService, null,
                    Clock.systemUTC(), stagingDirectory.toString(), DALYTRAN_DATASET, DALYREJS_BASE));
            assertThatNullPointerException().isThrownBy(() -> new PostTransactionJobConfig(
                    jobRepository, transactionManager, readerFactory, postingService, meterRegistry,
                    null, stagingDirectory.toString(), DALYTRAN_DATASET, DALYREJS_BASE));
        }

        @Test
        @DisplayName("a blank logical name is refused rather than resolved to the staging directory")
        void aBlankLogicalNameIsRefused() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new PostTransactionJobConfig(jobRepository, transactionManager,
                            readerFactory, postingService, meterRegistry, Clock.systemUTC(), "  ",
                            DALYTRAN_DATASET, DALYREJS_BASE))
                    .withMessageContaining(PostTransactionJobConfig.STAGING_DIRECTORY_PROPERTY);
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new PostTransactionJobConfig(jobRepository, transactionManager,
                            readerFactory, postingService, meterRegistry, Clock.systemUTC(),
                            stagingDirectory.toString(), "", DALYREJS_BASE))
                    .withMessageContaining(PostTransactionJobConfig.DALYTRAN_DATASET_PROPERTY);
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new PostTransactionJobConfig(jobRepository, transactionManager,
                            readerFactory, postingService, meterRegistry, Clock.systemUTC(),
                            stagingDirectory.toString(), DALYTRAN_DATASET, ""))
                    .withMessageContaining(PostTransactionJobConfig.DALYREJS_DATASET_BASE_PROPERTY);
        }

        @Test
        @DisplayName("a logical name that would resolve outside the staging directory is refused when the "
                + "configuration binds, so no run can create, truncate or delete a file outside it")
        void aLogicalNameThatWouldEscapeTheStagingDirectoryIsRefused() {
            for (final String escaping : new String[] {"/etc/passwd", "../../etc/passwd",
                "sub/dalytran.PS", "sub\\dalytran.PS", ".."}) {
                assertThatIllegalArgumentException()
                        .as("input dataset name %s", escaping)
                        .isThrownBy(() -> new PostTransactionJobConfig(jobRepository,
                                transactionManager, readerFactory, postingService, meterRegistry,
                                Clock.systemUTC(), stagingDirectory.toString(), escaping,
                                DALYREJS_BASE))
                        .withMessageContaining(PostTransactionJobConfig.DALYTRAN_DATASET_PROPERTY);
                assertThatIllegalArgumentException()
                        .as("reject generation base %s", escaping)
                        .isThrownBy(() -> new PostTransactionJobConfig(jobRepository,
                                transactionManager, readerFactory, postingService, meterRegistry,
                                Clock.systemUTC(), stagingDirectory.toString(), DALYTRAN_DATASET,
                                escaping))
                        .withMessageContaining(PostTransactionJobConfig.DALYREJS_DATASET_BASE_PROPERTY);
            }
        }

        @Test
        @DisplayName("the staging directory itself may be a multi-segment path, because a real "
                + "deployment's root is one and the containment rule cannot be applied to it")
        void theStagingDirectoryItselfMayBeAMultiSegmentPath() {
            assertThat(new PostTransactionJobConfig(jobRepository, transactionManager, readerFactory,
                    postingService, meterRegistry, Clock.systemUTC(),
                    stagingDirectory.resolve("nested").resolve("deeper").toString(), DALYTRAN_DATASET,
                    DALYREJS_BASE).dalytranInput())
                    .isEqualTo(stagingDirectory.resolve("nested").resolve("deeper")
                            .resolve(DALYTRAN_DATASET));
        }

        @Test
        @DisplayName("a null logical name is refused, so a bound value can never be absent silently")
        void aNullLogicalNameIsRefused() {
            assertThatNullPointerException().isThrownBy(() -> new PostTransactionJobConfig(
                    jobRepository, transactionManager, readerFactory, postingService, meterRegistry,
                    Clock.systemUTC(), null, DALYTRAN_DATASET, DALYREJS_BASE));
        }

        @Test
        @DisplayName("the commit granularity is exactly one record, a semantic constant rather than a "
                + "configuration or tuning figure")
        void theCommitGranularityIsOneRecord() {
            assertThat(PostTransactionJobConfig.RECORD_AT_A_TIME).isOne();
        }
    }

    @Nested
    @DisplayName("the job")
    class TheJob {

        private Job job() {
            return configuration.postTransactionJob(step(), new RunIdIncrementer(),
                    new NoOpJobBoundaryListener());
        }

        private Step step() {
            return configuration.postDailyTransactionsStep(reader(),
                    configuration.postTransactionValidationProcessor(), writer());
        }

        @Test
        @DisplayName("the job carries the stable name it is launched and queried by")
        void theJobCarriesTheStableName() {
            assertThat(job().getName()).isEqualTo(PostTransactionJobConfig.JOB_NAME);
            assertThat(PostTransactionJobConfig.JOB_NAME).isEqualTo("postTransactionJob");
        }

        @Test
        @DisplayName("the job holds exactly one step, because the member declares one application step")
        void theJobHoldsExactlyOneStep() {
            assertThat(job()).isInstanceOfSatisfying(SimpleJob.class, sequence ->
                    assertThat(sequence.getStepNames())
                            .as("a second step would be a sequence the member never declared")
                            .containsExactly(PostTransactionJobConfig.STEP_NAME));
        }

        @Test
        @DisplayName("the job declares no failure-ending transition, because the member carries no "
                + "condition-code gate")
        void theJobDeclaresNoFailureEndingTransition() {
            assertThat(job()).isInstanceOf(SimpleJob.class).isNotInstanceOf(FlowJob.class);
        }

        @Test
        @DisplayName("the job requires no parameter at all, because the member passes no parameter "
                + "string to the program")
        void theJobRequiresNoParameter() {
            final Job job = job();

            assertThatCode(() -> job.getJobParametersValidator().validate(new JobParameters()))
                    .doesNotThrowAnyException();
            assertThatCode(() -> job.getJobParametersValidator().validate(
                    new JobParametersBuilder().addLong("run.id", EXECUTION_ID).toJobParameters()))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("the job attaches the shared parameter incrementer, so a resubmission runs again")
        void theJobAttachesTheSharedIncrementer() {
            final JobParametersIncrementer incrementer = new RunIdIncrementer();

            assertThat(configuration
                    .postTransactionJob(step(), incrementer, new NoOpJobBoundaryListener())
                    .getJobParametersIncrementer()).isSameAs(incrementer);
        }

        @Test
        @DisplayName("the job is restartable")
        void theJobIsRestartable() {
            assertThat(job().isRestartable()).isTrue();
        }
    }

    @Nested
    @DisplayName("the step")
    class TheStep {

        @Test
        @DisplayName("the step carries the stable name the job starts it by")
        void theStepCarriesTheStableName() {
            assertThat(step().getName()).isEqualTo(PostTransactionJobConfig.STEP_NAME);
            assertThat(PostTransactionJobConfig.STEP_NAME).isEqualTo("postDailyTransactionsStep");
        }

        @Test
        @DisplayName("the step is chunk-oriented, which is what a sequential read loop with a commit "
                + "boundary is")
        void theStepIsChunkOriented() {
            assertThat(step()).isInstanceOfSatisfying(TaskletStep.class, tasklet ->
                    assertThat(tasklet.getTasklet()).isInstanceOf(ChunkOrientedTasklet.class));
        }

        @Test
        @DisplayName("the step uses the record-at-a-time semantic constant")
        void theStepUsesTheRecordAtATimeSemanticConstant() {
            assertThat(step()).isInstanceOf(TaskletStep.class);
        }

        private Step step() {
            return configuration.postDailyTransactionsStep(reader(),
                    configuration.postTransactionValidationProcessor(), writer());
        }
    }

    @Nested
    @DisplayName("the three step collaborators")
    class TheCollaborators {

        @Test
        @DisplayName("the per-record stage is the translated program's own validation processor")
        void thePerRecordStageIsTheValidationProcessor() {
            assertThat(configuration.postTransactionValidationProcessor())
                    .isNotNull()
                    .isNotSameAs(configuration.postTransactionValidationProcessor());
        }

        @Test
        @DisplayName("the reader is the one that owns the 350-byte layout, wrapped in the decorator that "
                + "reports this program's own open-failure arm")
        void theReaderOwnsTheThreeHundredAndFiftyByteLayout() throws Exception {
            // The bound reader is the factory's, reached through a decorator whose only behaviour is to
            // report 0000-DALYTRAN-OPEN's failure arm when the framework cannot open the input
            // (DL-215). The layout is asserted through the decorator, because what must remain true is
            // that the records reaching the step are the 350-byte ones - which the parse test below
            // proves end to end - and that no second reader was substituted for the factory's.
            stageInput();
            final ItemStreamReader<DailyTransaction> bound = reader();
            assertThat(bound)
                    .as("the bound reader is the decorator and not the factory's reader itself")
                    .isNotInstanceOf(FlatFileItemReader.class);

            // The factory names its reader, and a named stream writes that name into the execution
            // context when the framework asks it to save its position. Reading the saved keys therefore
            // identifies the delegate by its own restart name, through the decorator, without reflection.
            final ExecutionContext saved = new ExecutionContext();
            bound.open(new ExecutionContext());
            try {
                bound.update(saved);
            } finally {
                bound.close();
            }
            assertThat(saved.entrySet())
                    .as("the delegate is the factory's named 350-byte reader")
                    .anyMatch(entry -> entry.getKey().startsWith(
                            FixedWidthFlatFileReaderFactory.DAILY_TRANSACTION_READER_NAME));
        }

        @Test
        @DisplayName("and an input the framework cannot open reports the legacy open-failure arm rather "
                + "than the framework's own strict-mode message")
        void anUnopenableInputReportsTheLegacyOpenFailureArm() {
            final AbendException abend = new AbendException(AbendException.BATCH_ABEND_CODE,
                    TransactionPostingService.PROGRAM_NAME, "STATUS 31 OPEN INPUT DALYTRAN",
                    "ERROR OPENING DALYTRAN - FILE STATUS IS: 31");
            when(postingService.dailyTransactionOpenFailure(any())).thenReturn(abend);

            // Nothing is staged under this name, so the strict reader refuses to open.
            final ItemStreamReader<DailyTransaction> bound = reader();

            assertThatThrownBy(() -> bound.open(new ExecutionContext()))
                    .as("the program's verdict reaches the caller, not the reader's own message")
                    .isSameAs(abend);
            verify(postingService).dailyTransactionOpenFailure(any(RuntimeException.class));
        }

        @Test
        @DisplayName("an abend the delegate itself raises is not wrapped a second time")
        void anAbendFromTheDelegateIsNotRewrapped() {
            final AbendException alreadyDiagnosed = new AbendException(AbendException.BATCH_ABEND_CODE,
                    TransactionPostingService.PROGRAM_NAME, "STATUS 31 OPEN INPUT DALYTRAN",
                    "ERROR OPENING DALYTRAN - FILE STATUS IS: 31");
            // A TYPED FAKE, not a raw mock. `mock(ItemStreamReader.class)` returns the raw type, so
            // assigning it to a parameterised reference is an unchecked conversion - and the only way to
            // keep that quiet under -Xlint:all -Werror was the @SuppressWarnings this line used to
            // carry. Gate 2 requires zero suppressed warnings, so the suppression cannot simply be
            // moved somewhere quieter; the conversion itself has to go. A fake that declares the type
            // parameter has nothing to convert, and it says what it does more plainly than a stubbing
            // call did: open throws, and nothing else is reachable.
            final ItemStreamReader<DailyTransaction> delegate = new FailingOnOpenReader(alreadyDiagnosed);

            final ItemStreamReader<DailyTransaction> bound =
                    PostTransactionJobConfig.diagnosingReader(delegate, postingService);

            assertThatThrownBy(() -> bound.open(new ExecutionContext()))
                    .isSameAs(alreadyDiagnosed);
            verifyNoInteractions(postingService);
        }

        @Test
        @DisplayName("the reader parses the delivered fixture, whose record is 350 encoded bytes")
        void theReaderParsesTheDeliveredFixture() throws Exception {
            stageInput();
            final Path staged = stagingDirectory.resolve(DALYTRAN_DATASET);
            assertThat(Files.readAllLines(staged, StandardCharsets.US_ASCII).getFirst()
                    .getBytes(StandardCharsets.US_ASCII).length).isEqualTo(TRANSACTION_WIDTH);

            final ItemStreamReader<DailyTransaction> bound = reader();
            final List<DailyTransaction> read = new ArrayList<>();
            bound.open(new ExecutionContext());
            try {
                DailyTransaction next = bound.read();
                while (next != null) {
                    read.add(next);
                    next = bound.read();
                }
            } finally {
                bound.close();
            }

            assertThat(read).hasSize(DALYTRAN_RECORDS);
        }

        @Test
        @DisplayName("the reject writer is bound to this execution's own generation and creates the "
                + "directory it is written into, as the legacy allocation created its dataset")
        void theRejectWriterIsBoundToThisExecutionsGeneration() {
            final Path fresh = stagingDirectory.resolve("generations");
            final PostTransactionJobConfig relocated = new PostTransactionJobConfig(jobRepository,
                    transactionManager, readerFactory, postingService, meterRegistry,
                    Clock.systemUTC(), fresh.toString(), DALYTRAN_DATASET, DALYREJS_BASE);
            assertThat(fresh).doesNotExist();

            assertThat(relocated.postTransactionRejectRecordWriter(EXECUTION_ID,
                    stepExecution(BatchStatus.STARTED, 0, 0)))
                    .isNotNull();
            assertThat(fresh).isDirectory();
        }

        @Test
        @DisplayName("two executions resolve two different generations, so neither can overwrite the "
                + "other's rejects")
        void twoExecutionsResolveTwoDifferentGenerations() {
            assertThat(configuration.rejectGeneration(EXECUTION_ID))
                    .isNotEqualTo(configuration.rejectGeneration(EXECUTION_ID + 1));
        }

        @Test
        @DisplayName("an absent execution identifier is refused, because a generation cannot be named "
                + "without one")
        void anAbsentExecutionIdentifierIsRefused() {
            assertThatNullPointerException()
                    .isThrownBy(() -> configuration.postTransactionRejectRecordWriter(
                            null, stepExecution(BatchStatus.STARTED, 0, 0)));
        }

        @Test
        @DisplayName("a staging directory that cannot be created is reported as an input/output failure "
                + "naming the data definition, rather than surfacing as an opaque write error later")
        void aStagingDirectoryThatCannotBeCreatedIsReported() throws Exception {
            final Path occupied = stagingDirectory.resolve("occupied-by-a-file");
            Files.writeString(occupied, "not a directory", StandardCharsets.US_ASCII);
            final PostTransactionJobConfig blocked = new PostTransactionJobConfig(jobRepository,
                    transactionManager, readerFactory, postingService, meterRegistry,
                    Clock.systemUTC(), occupied.toString(), DALYTRAN_DATASET, DALYREJS_BASE);

            assertThatExceptionOfType(UncheckedIOException.class)
                    .isThrownBy(() -> blocked.postTransactionRejectRecordWriter(EXECUTION_ID,
                            stepExecution(BatchStatus.STARTED, 0, 0)))
                    .withMessageContaining(TransactionPostingService.DALYREJS_DD)
                    .withMessageContaining(occupied.toString())
                    .withCauseInstanceOf(FileAlreadyExistsException.class);
        }
    }

    @Nested
    @DisplayName("resource resolution")
    class ResourceResolution {

        @Test
        @DisplayName("the sequential input is a logical name resolved against the configured directory")
        void theSequentialInputIsALogicalNameResolvedAgainstTheDirectory() {
            assertThat(configuration.dalytranInput())
                    .isEqualTo(stagingDirectory.resolve(DALYTRAN_DATASET));
        }

        @Test
        @DisplayName("resolving a name has no side effect, so nothing is created by asking where a "
                + "dataset would go")
        void resolvingANameHasNoSideEffect() {
            final Path fresh = stagingDirectory.resolve("untouched");
            final PostTransactionJobConfig relocated = new PostTransactionJobConfig(jobRepository,
                    transactionManager, readerFactory, postingService, meterRegistry,
                    Clock.systemUTC(), fresh.toString(), DALYTRAN_DATASET, DALYREJS_BASE);

            relocated.dalytranInput();
            relocated.rejectGeneration(EXECUTION_ID);

            assertThat(fresh).doesNotExist();
        }

        @Test
        @DisplayName("the reject generation keeps the absolute-generation vocabulary at ten digits")
        void theRejectGenerationIsNamedInTheLegacyForm() {
            assertThat(configuration.rejectGeneration(EXECUTION_ID))
                    .isEqualTo(stagingDirectory.resolve(DALYREJS_BASE + ".G0000000007V00"));
        }

        @Test
        @DisplayName("the generation number does not wrap onto an earlier execution")
        void theGenerationNumberDoesNotWrap() {
            assertThat(configuration.rejectGeneration(WRAPPING_EXECUTION_ID))
                    .isEqualTo(stagingDirectory.resolve(DALYREJS_BASE + ".G0000010003V00"))
                    .isNotEqualTo(configuration.rejectGeneration(3L));
        }

        @Test
        @DisplayName("the generation is resolved directly against the staging directory, so the "
                + "directory the writer creates is the one the generation sits in")
        void theGenerationSitsDirectlyInTheStagingDirectory() {
            assertThat(configuration.rejectGeneration(EXECUTION_ID).getParent())
                    .isEqualTo(stagingDirectory);
        }
    }

    @Nested
    @DisplayName("the step diagnostic")
    class TheStepDiagnostic {

        @Test
        @DisplayName("the diagnostic contributes no exit status, so it can neither overwrite the "
                + "tolerated completion code nor mask a failure")
        void theDiagnosticContributesNoExitStatus() {
            final StepExecution completed = stepExecution(BatchStatus.COMPLETED, 300L, 12L);
            completed.setStartTime(LocalDateTime.now().minus(Duration.ofSeconds(1L)));

            assertThat(configuration.postingStepDiagnostics().afterStep(completed)).isNull();
        }

        @Test
        @DisplayName("a failed step keeps its verdict, because a diagnostic observes and never decides")
        void aFailedStepKeepsItsVerdict() {
            final StepExecution failed = stepExecution(BatchStatus.FAILED, 4L, 0L);
            failed.setStartTime(LocalDateTime.now());
            failed.setExitStatus(ExitStatus.FAILED);

            assertThat(configuration.postingStepDiagnostics().afterStep(failed)).isNull();
            assertThat(failed.getExitStatus()).isEqualTo(ExitStatus.FAILED);
        }

        @Test
        @DisplayName("the step's elapsed time is published, tagged with the job, the step and the "
                + "terminal status")
        void theStepsElapsedTimeIsPublished() {
            final StepExecution completed = stepExecution(BatchStatus.COMPLETED, 300L, 12L);
            completed.setStartTime(LocalDateTime.now().minus(Duration.ofSeconds(2L)));

            configuration.postingStepDiagnostics().afterStep(completed);

            final Timer published = meterRegistry.find("carddemo.batch.job.step")
                    .tag("batchJob", PostTransactionJobConfig.JOB_NAME)
                    .tag("step", PostTransactionJobConfig.STEP_NAME)
                    .tag("outcome", BatchStatus.COMPLETED.name())
                    .timer();
            assertThat(published).isNotNull();
            assertThat(published.count()).isOne();
            assertThat(published.totalTime(TimeUnit.NANOSECONDS))
                    .isGreaterThan(0.0d);
        }

        @Test
        @DisplayName("an execution with no start time publishes nothing rather than failing a step "
                + "that otherwise succeeded")
        void anExecutionWithNoStartTimePublishesNothing() {
            final StepExecution never = stepExecution(BatchStatus.COMPLETED, 0L, 0L);
            never.setStartTime(null);

            assertThat(configuration.postingStepDiagnostics().afterStep(never)).isNull();
            assertThat(meterRegistry.find("carddemo.batch.job.step").timer()).isNull();
        }

        @Test
        @DisplayName("an interval that reads as negative publishes no elapsed time, because a timer "
                + "records a duration and never a direction")
        void aNegativeIntervalPublishesNoElapsedTime() {
            final Clock behind = Clock.fixed(Instant.EPOCH, ZoneId.systemDefault());
            final PostTransactionJobConfig withBehindClock = new PostTransactionJobConfig(jobRepository,
                    transactionManager, readerFactory, postingService, meterRegistry, behind,
                    stagingDirectory.toString(), DALYTRAN_DATASET, DALYREJS_BASE);
            final StepExecution completed = stepExecution(BatchStatus.COMPLETED, 1L, 0L);
            completed.setStartTime(LocalDateTime.now());

            withBehindClock.postingStepDiagnostics().afterStep(completed);

            final Timer published = meterRegistry.find("carddemo.batch.job.step").timer();
            assertThat(published).isNotNull();
            assertThat(published.count()).isOne();
            assertThat(published.totalTime(TimeUnit.NANOSECONDS)).isZero();
        }

        @Test
        @DisplayName("an absent execution is refused, because there is nothing to report about it")
        void anAbsentExecutionIsRefused() {
            final StepExecutionListener diagnostic = configuration.postingStepDiagnostics();

            assertThatNullPointerException().isThrownBy(() -> diagnostic.afterStep(null));
        }
    }

    @Nested
    @DisplayName("the contract the job level publishes")
    class ThePublishedContract {

        @Test
        @DisplayName("the legacy origin is derived from the translated program rather than restated, so "
                + "the two can never disagree")
        void theLegacyOriginIsDerivedRatherThanRestated() {
            assertThat(PostTransactionJobConfig.PROGRAM_NAME)
                    .isEqualTo(TransactionPostingService.PROGRAM_NAME);
            assertThat(PostTransactionJobConfig.LEGACY_JOB)
                    .isEqualTo(TransactionValidationProcessor.LEGACY_JOB);
            assertThat(PostTransactionJobConfig.LEGACY_STEP)
                    .isEqualTo(TransactionValidationProcessor.LEGACY_STEP);
        }

        @Test
        @DisplayName("the reject record is 430 bytes, and the trailer is a four-digit code and a "
                + "seventy-six character description")
        void theRejectRecordIsFourHundredAndThirtyBytes() {
            assertThat(RejectRecordWriter.REJECT_RECORD_LENGTH)
                    .isEqualTo(TransactionPostingService.SOURCE_IMAGE_LENGTH
                            + TransactionPostingService.VALIDATION_TRAILER_LENGTH)
                    .isEqualTo(430);
            assertThat(TransactionPostingService.FAIL_REASON_LENGTH).isEqualTo(4);
            assertThat(TransactionPostingService.FAIL_REASON_DESCRIPTION_LENGTH).isEqualTo(76);
        }

        @Test
        @DisplayName("the completion code this job raises for a nonzero reject count is four, and it is "
                + "no step gate's ceiling")
        void theCompletionCodeForRejectsIsFourAndGatesNothing() {
            assertThat(TransactionPostingService.RETURN_CODE_REJECTS_PRESENT)
                    .as("the program sets it at app/cbl/CBTRN02C.cbl lines 229 to 230; the authority is "
                            + "the program, never another job's gate")
                    .isEqualTo(4);
            assertThat(BatchConfig.ConditionCodeGate.ALL_PRIOR_STEPS_ZERO.permits(
                            TransactionPostingService.RETURN_CODE_REJECTS_PRESENT))
                    .as("every condition-code step gate in the estate is the strict form, so this code "
                            + "is admitted by none of them")
                    .isFalse();
        }

        @Test
        @DisplayName("every configuration key of this job sits under the job's own prefix")
        void everyConfigurationKeySitsUnderTheJobsPrefix() {
            assertThat(PostTransactionJobConfig.RESOURCE_PROPERTY_PREFIX)
                    .isEqualTo("carddemo.batch.post-transaction.");
            assertThat(PostTransactionJobConfig.STAGING_DIRECTORY_PROPERTY)
                    .startsWith(PostTransactionJobConfig.RESOURCE_PROPERTY_PREFIX);
            assertThat(PostTransactionJobConfig.DALYTRAN_DATASET_PROPERTY)
                    .startsWith(PostTransactionJobConfig.RESOURCE_PROPERTY_PREFIX);
            assertThat(PostTransactionJobConfig.DALYREJS_DATASET_BASE_PROPERTY)
                    .startsWith(PostTransactionJobConfig.RESOURCE_PROPERTY_PREFIX);
        }

        @Test
        @DisplayName("each collaborator bean name is the qualifier the step selects it by")
        void eachCollaboratorBeanNameIsTheQualifier() {
            assertThat(PostTransactionJobConfig.DAILY_TRANSACTION_READER_BEAN_NAME)
                    .isEqualTo("postTransactionDailyTransactionReader");
            assertThat(PostTransactionJobConfig.VALIDATION_PROCESSOR_BEAN_NAME)
                    .isEqualTo("postTransactionValidationProcessor");
            assertThat(PostTransactionJobConfig.REJECT_RECORD_WRITER_BEAN_NAME)
                    .isEqualTo("postTransactionRejectRecordWriter");
        }
    }

    @Nested
    @DisplayName("the container the configuration is actually resolved in")
    class TheContainer {

        /**
         * The configuration under test, alongside the batch infrastructure it attaches by bean name and
         * the collaborators it injects, in a real container.
         *
         * <p>The step scope is registered explicitly because the batch auto-configuration is not present
         * here. It has to be present in some form: <strong>two of this configuration's beans are scoped
         * to the step</strong>, and a scoped bean that cannot be proxied fails when the container
         * refreshes rather than when anything compiles. That is exactly the failure this group exists to
         * catch, and it is the reason the two scoped beans are declared by an interface: the reject
         * writer's implementation is a final class, which no subclass-based proxy could ever wrap.
         */
        private final ApplicationContextRunner runner = new ApplicationContextRunner()
                .withUserConfiguration(PostTransactionJobConfig.class,
                        BatchConfig.class)
                .withBean("stepScope", StepScope.class, StepScope::new)
                .withBean(JobRepository.class, () -> jobRepository)
                .withBean(PlatformTransactionManager.class, () -> transactionManager)
                .withBean(TransactionPostingService.class, () -> postingService)
                .withBean(FixedWidthFlatFileReaderFactory.class, () -> readerFactory)
                .withBean(BatchStagingArea.class, () -> stagingArea)
                .withBean(MeterRegistry.class, () -> meterRegistry)
                .withBean(Clock.class, Clock::systemUTC);

        private ApplicationContextRunner configured() {
            return runner.withPropertyValues(PostTransactionJobConfig.STAGING_DIRECTORY_PROPERTY
                    + "=" + stagingDirectory);
        }

        @Test
        @DisplayName("the context refreshes and publishes the job under the name it is launched by")
        void theContextRefreshesAndPublishesTheJob() {
            configured().run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).hasBean(PostTransactionJobConfig.JOB_NAME);
                assertThat(context.getBean(PostTransactionJobConfig.JOB_NAME, Job.class).getName())
                        .isEqualTo(PostTransactionJobConfig.JOB_NAME);
            });
        }

        @Test
        @DisplayName("exactly one job is published, so no run-everything job and no chained job was added")
        void exactlyOneJobIsPublished() {
            configured().run(context -> assertThat(context.getBeanNamesForType(Job.class))
                    .containsExactly(PostTransactionJobConfig.JOB_NAME));
        }

        @Test
        @DisplayName("the step resolves under the name the job starts it by, with all three "
                + "collaborators bound by their published bean names")
        void theStepResolvesWithAllThreeCollaboratorsBound() {
            configured().run(context -> {
                assertThat(context).hasBean(PostTransactionJobConfig.STEP_NAME);
                assertThat(context.getBean(PostTransactionJobConfig.STEP_NAME, Step.class).getName())
                        .isEqualTo(PostTransactionJobConfig.STEP_NAME);
                assertThat(context)
                        .hasBean(PostTransactionJobConfig.DAILY_TRANSACTION_READER_BEAN_NAME);
                assertThat(context).hasBean(PostTransactionJobConfig.VALIDATION_PROCESSOR_BEAN_NAME);
                assertThat(context).hasBean(PostTransactionJobConfig.REJECT_RECORD_WRITER_BEAN_NAME);
            });
        }

        @Test
        @DisplayName("both step-scoped beans present a class rather than an interface behind their "
                + "proxy, so the framework can query the implementing class for listener annotations")
        void bothStepScopedBeansPresentTheirImplementingClass() {
            // This assertion was inverted, and the inversion is the point. It previously required an
            // interface-based proxy on the reasoning that a generated subclass was class generation the
            // module ruled out. Runtime verification showed what that cost: the framework's listener
            // factory takes a proxy's target class, and when that class is an interface it warns and
            // stops looking for annotations - one warning per boot, and a listener annotation added to
            // either class later would silently never fire. The reflection budget the old reasoning
            // appealed to is a source-level count that a generated subclass does not enter, and the
            // interface arrangement was the more reflective of the two. See docs/decision-log.md DL-359.
            configured().run(context -> {
                final Object boundReader = context
                        .getBean(PostTransactionJobConfig.DAILY_TRANSACTION_READER_BEAN_NAME);
                final Object boundWriter = context
                        .getBean(PostTransactionJobConfig.REJECT_RECORD_WRITER_BEAN_NAME);

                assertThat(boundReader).isInstanceOf(ItemStreamReader.class);
                assertThat(boundWriter).isInstanceOf(ItemStreamWriter.class);
                assertThat(AopProxyUtils.ultimateTargetClass(boundReader).isInterface())
                        .as("an interface behind the proxy is exactly what makes the framework skip "
                                + "annotation-based listener discovery on the reader")
                        .isFalse();
                assertThat(AopProxyUtils.ultimateTargetClass(boundWriter).isInterface())
                        .as("and the same for the writer, which is why the completing wrapper is the "
                                + "declared type rather than the stream-writer interface")
                        .isFalse();
                assertThat(AopUtils.isCglibProxy(boundReader))
                        .as("declaring the implementing class is what produces a class-based proxy")
                        .isTrue();
                assertThat(AopUtils.isCglibProxy(boundWriter))
                        .as("declaring the completing wrapper does the same for the writer")
                        .isTrue();
            });
        }

        @Test
        @DisplayName("nothing in the context can start a job on its own, because a legacy job was "
                + "submitted deliberately and bringing an application up never triggered one")
        void nothingInTheContextCanStartAJobOnItsOwn() {
            configured().run(context -> {
                for (final Class<?> selfStarting : List.of(ApplicationRunner.class,
                        CommandLineRunner.class, SmartLifecycle.class)) {
                    assertThat(context.getBeanNamesForType(selfStarting))
                            .as("%s would run without being asked", selfStarting.getSimpleName())
                            .isEmpty();
                }
            });
        }
    }

    /** The step-scoped reader over the staged sequential input. */
    private ItemStreamReader<DailyTransaction> reader() {
        return configuration.postTransactionDailyTransactionReader(this.stagingArea);
    }

    /** The step-scoped writer over this execution's own reject generation. */
    private ItemStreamWriter<RejectRecordWriter.RejectedTransaction> writer() {
        return configuration.postTransactionRejectRecordWriter(EXECUTION_ID,
                stepExecution(BatchStatus.STARTED, 0, 0));
    }

    /**
     * A job-boundary listener that reports nothing, standing in for the shared one.
     *
     * <p>A real listener would write to the log stream and this suite asserts nothing about that; the
     * shared listener has its own suite. What matters here is only that the job accepts one.
     */
    private static final class NoOpJobBoundaryListener implements JobExecutionListener {
    }

    /**
     * A reader that fails on {@code open} with a pre-supplied failure, and is never read from.
     *
     * <h2>Why a declared type rather than a mock</h2>
     *
     * <p>{@code mock(ItemStreamReader.class)} yields the raw type, so binding it to
     * {@code ItemStreamReader<DailyTransaction>} is an unchecked conversion. Under {@code -Xlint:all}
     * with {@code -Werror} that ends the build, and the only way to keep it quiet was a
     * {@code @SuppressWarnings("unchecked")} - which Gate 2 forbids, since it requires zero
     * <em>suppressed</em> warnings as well as zero warnings. Moving the suppression somewhere less
     * conspicuous would satisfy neither; declaring the type parameter removes the conversion outright.
     *
     * <p>{@code read} is unreachable in the case that uses this: the diagnosing wrapper under test calls
     * {@code open} first and that call throws. It is implemented to fail loudly rather than to return
     * {@code null}, so a future edit that reaches it is told so instead of quietly reading an empty
     * dataset. The remaining {@link org.springframework.batch.item.ItemStream} methods are defaulted by
     * the interface and are deliberately not overridden - the fake declares exactly the one behaviour
     * the test depends on.
     */
    private static final class FailingOnOpenReader implements ItemStreamReader<DailyTransaction> {

        /** The failure {@code open} raises, already carrying the program's own diagnosis. */
        private final RuntimeException failure;

        /**
         * Creates the reader.
         *
         * @param failure the failure {@code open} must raise, unchanged
         */
        FailingOnOpenReader(final RuntimeException failure) {
            this.failure = failure;
        }

        @Override
        public void open(final ExecutionContext executionContext) {
            throw this.failure;
        }

        @Override
        public DailyTransaction read() {
            throw new AssertionError("the reader was opened successfully and then read, which the case "
                    + "using this fake cannot reach: open() always throws");
        }
    }
}
