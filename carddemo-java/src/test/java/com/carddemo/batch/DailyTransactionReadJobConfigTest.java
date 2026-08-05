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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.function.UnaryOperator;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecutionListener;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.JobParametersIncrementer;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.SimpleJob;
import org.springframework.batch.core.job.flow.FlowJob;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.tasklet.TaskletStep;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.ItemStreamException;
import org.springframework.batch.item.file.FlatFileItemReader;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.ResourceLoader;
import org.springframework.transaction.PlatformTransactionManager;

import com.carddemo.batch.step.FixedWidthFlatFileReaderFactory;
import com.carddemo.domain.DailyTransaction;
import com.carddemo.domain.enums.FileStatus;
import com.carddemo.exception.AbendException;
import com.carddemo.service.DailyTransactionReadService;
import com.carddemo.service.DailyTransactionReadService.DailyTransactionReadResult;
import com.carddemo.util.SensitiveFieldCodec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit test for {@link DailyTransactionReadJobConfig}, the job configuration of the estate's orphan.
 *
 * <h2>What this suite exists to prove</h2>
 *
 * <p>The legacy member behind this job is complete and is invoked by no job member, no cataloged procedure
 * and no online resource definition. That makes three propositions load-bearing, and each of them is a
 * property of the wiring rather than of the translated logic, so this is the suite that has to assert them:
 * the job <strong>is</strong> defined and launchable by name; the job is <strong>absent</strong> from every
 * pipeline, chained from nothing and into nothing; and the job asks for <strong>no</strong> parameter and
 * carries <strong>no</strong> failure-ending transition, because there is no job stream to have supplied
 * either.</p>
 *
 * <h2>Why the six resource bindings are asserted by reader name</h2>
 *
 * <p>Each of the six resources the member names is bound to the reader that owns its record layout, and a
 * reader carries the name the factory gave it. Asserting that name is what proves the <em>right</em> factory
 * method was called, and therefore the right width and the right offsets - a proof that survives the two
 * layouts which are byte-for-byte identical at 350 bytes. Substituting one of those two for the other would
 * parse every record successfully, so a test that only checked "it parsed" could not tell them apart. Each
 * binding is additionally driven over its own delivered fixture, and the fixture's own encoded byte width is
 * asserted as bytes rather than as characters.</p>
 *
 * <h2>Why the failure paths assert an ordering</h2>
 *
 * <p>The member displays its diagnostic and the raw two-character file status <em>before</em> it abends, and
 * that order is the contract: on a mainframe the diagnostic reached the operator whether or not anything
 * survived. The two staged-dataset failure paths are therefore asserted by the order of the captured
 * diagnostics, not merely by the exception, and the abend code is compared against the constant the
 * exception owns rather than against a literal repeated here.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DailyTransactionReadJobConfig - the orphan's job, defined and deliberately unwired")
final class DailyTransactionReadJobConfigTest {

    /** Location prefix of the delivered sequential fixtures. */
    private static final String FIXTURES = "classpath:fixtures/input/";

    /** The staged daily-transaction dataset: 300 records of 350 bytes. */
    private static final String DALYTRAN_FIXTURE = FIXTURES + "dailytran.txt";

    /** The staged customer dataset: 50 records of 500 bytes. */
    private static final String CUSTDATA_FIXTURE = FIXTURES + "custdata.txt";

    /** The staged cross-reference dataset: 50 records of the layout's 36-byte sample width. */
    private static final String CARDXREF_FIXTURE = FIXTURES + "cardxref.txt";

    /** The staged card dataset: 50 records of 150 bytes. */
    private static final String CARDDATA_FIXTURE = FIXTURES + "carddata.txt";

    /** The staged account dataset: 50 records of 300 bytes. */
    private static final String ACCTDATA_FIXTURE = FIXTURES + "acctdata.txt";

    /** A location naming nothing, used to drive the open failure. */
    private static final String ABSENT_FIXTURE = FIXTURES + "no-such-dataset.txt";

    /** The blank value every location defaults to, meaning no dataset is staged for that resource. */
    private static final String UNSTAGED = "";

    /** Record count of the delivered daily-transaction fixture. */
    private static final int DALYTRAN_RECORDS = 300;

    /** Record count of the delivered account, card, customer and cross-reference fixtures. */
    private static final int REFERENCE_RECORDS = 50;

    /** Identity of the first record of the daily-transaction fixture, in file order. */
    private static final String FIRST_DALYTRAN_ID = "0000000000683580";

    /** Identity of the last record of the daily-transaction fixture, in file order. */
    private static final String LAST_DALYTRAN_ID = "0000000996722787";

    /** Verified width of the daily-transaction and posted-transaction layouts, in encoded bytes. */
    private static final int TRANSACTION_WIDTH = 350;

    /** Verified width of the customer layout, in encoded bytes. */
    private static final int CUSTOMER_WIDTH = 500;

    /** Width of the cross-reference layout as the delivered sample dataset carries it. */
    private static final int CARD_XREF_SAMPLE_WIDTH = 36;

    /** Verified width of the card layout, in encoded bytes. */
    private static final int CARD_WIDTH = 150;

    /** Verified width of the account layout, in encoded bytes. */
    private static final int ACCOUNT_WIDTH = 300;

    /** The translated program, which owns all eighteen paragraphs and is never re-implemented. */
    @Mock
    private DailyTransactionReadService readService;

    /** Stands in for the framework's job repository while a job and a step are built. */
    @Mock
    private JobRepository jobRepository;

    /** Stands in for the transaction manager the step runs its tasklet under. */
    @Mock
    private PlatformTransactionManager transactionManager;

    /** Stands in for the shared parameter incrementer the batch infrastructure publishes. */
    @Mock
    private JobParametersIncrementer runIncrementer;

    /** Stands in for the shared job-boundary listener the batch infrastructure publishes. */
    @Mock
    private JobExecutionListener boundaryListener;

    /** Captures the diagnostics the configuration emits, in emission order. */
    @Mock
    private Appender<ILoggingEvent> appender;

    /**
     * Stands in for a reader that is at end of file the moment it opens and refuses to hand its handle
     * back, which is the only way to reach the release path from outside this class.
     */
    @Mock
    private FlatFileItemReader<DailyTransaction> stagedReader;

    /** Stands in for the reader factory when the reader it builds has to be scripted. */
    @Mock
    private FixedWidthFlatFileReaderFactory scriptedReaderFactory;

    /** Captures the ordered records the step hands to the translated program. */
    @Captor
    private ArgumentCaptor<List<DailyTransaction>> handedOverRecords;

    /** The real reader factory: this suite proves the bindings, so nothing about them is simulated. */
    private FixedWidthFlatFileReaderFactory readerFactory;

    /** Resolves a configured location the way a deployment does. */
    private ResourceLoader resourceLoader;

    /** The shared object-store staging boundary used before a local or classpath fallback. */
    @Mock
    private BatchStagingArea stagingArea;

    /** The registry the step timer records on. */
    private MeterRegistry meterRegistry;

    /** The configuration's own logger, which the capture attaches to. */
    private Logger configurationLogger;

    /** The level that logger carried before the capture lowered it. */
    private Level restoreLevel;

    @BeforeEach
    void attachDiagnosticCapture() {
        this.readerFactory = new FixedWidthFlatFileReaderFactory();
        this.resourceLoader = new DefaultResourceLoader();
        this.meterRegistry = new SimpleMeterRegistry();

        final LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        this.configurationLogger = context.getLogger(DailyTransactionReadJobConfig.class);
        this.restoreLevel = this.configurationLogger.getLevel();
        this.configurationLogger.setLevel(Level.DEBUG);
        this.configurationLogger.addAppender(this.appender);
    }

    @AfterEach
    void detachDiagnosticCapture() {
        this.configurationLogger.detachAppender(this.appender);
        this.configurationLogger.setLevel(this.restoreLevel);
    }

    /**
     * Builds the configuration with nothing staged, which is the shipped default for all six resources.
     *
     * @return the configuration
     */
    private DailyTransactionReadJobConfig nothingStaged() {
        return staged(UNSTAGED, UNSTAGED, UNSTAGED, UNSTAGED, UNSTAGED, UNSTAGED);
    }

    /**
     * Builds the configuration with the six locations supplied, in the order the six open paragraphs name
     * their resources.
     *
     * @param dalytran location of the daily-transaction dataset, or blank
     * @param custfile location of the customer dataset, or blank
     * @param xreffile location of the cross-reference dataset, or blank
     * @param cardfile location of the card dataset, or blank
     * @param acctfile location of the account dataset, or blank
     * @param tranfile location of the posted-transaction dataset, or blank
     * @return the configuration
     */
    private DailyTransactionReadJobConfig staged(final String dalytran, final String custfile,
            final String xreffile, final String cardfile, final String acctfile,
            final String tranfile) {
        return new DailyTransactionReadJobConfig(this.jobRepository, this.transactionManager,
                this.readerFactory, this.readService, this.meterRegistry, this.resourceLoader,
                this.stagingArea, dalytran, custfile, xreffile, cardfile, acctfile, tranfile);
    }

    /**
     * A pass outcome that reports nothing read, which is what an empty or unstaged input produces.
     *
     * @return the outcome
     */
    private static DailyTransactionReadResult emptyPass() {
        return new DailyTransactionReadResult(0, 0, 1, 0, 0, List.of(), 0);
    }

    /**
     * Reads every record of a bound reader, in the order the dataset holds them.
     *
     * @param <T> the record type the reader delivers
     * @param bound the binding under test, which must be present
     * @return the records, in dataset order
     * @throws Exception if the reader reports a failure, which fails the test
     */
    private static <T> List<T> readAll(final Optional<FlatFileItemReader<T>> bound) throws Exception {
        assertThat(bound).as("the binding must be present for its records to be read").isPresent();
        final FlatFileItemReader<T> reader = bound.orElseThrow();
        final List<T> items = new ArrayList<>();
        reader.open(new ExecutionContext());
        try {
            T item = reader.read();
            while (item != null) {
                items.add(item);
                item = reader.read();
            }
        } finally {
            reader.close();
        }
        return List.copyOf(items);
    }

    /**
     * Measures the first record of a delivered fixture in encoded bytes, never in characters.
     *
     * @param fixtureLocation the fixture's location
     * @return the encoded byte width of its first record, with the line terminator removed
     * @throws IOException if the fixture cannot be read, which fails the test
     */
    private int firstRecordWidthInBytes(final String fixtureLocation) throws IOException {
        final byte[] content = this.resourceLoader.getResource(fixtureLocation)
                .getInputStream().readAllBytes();
        final String firstLine = new String(content, StandardCharsets.US_ASCII).lines()
                .findFirst().orElseThrow();
        return firstLine.getBytes(StandardCharsets.US_ASCII).length;
    }

    /**
     * The sealing function this suite hands to the customer binding.
     *
     * <p>No cryptography and no key: the body is the value's bytes zero-extended to the envelope's minimum
     * length and Base64-encoded behind the module's marker. The entity checks the shape, which is all this
     * suite needs, and the real sealing operation belongs to the layer that holds a key.
     *
     * @return an envelope-shaped sealing function
     */
    private static UnaryOperator<String> sealer() {
        return DailyTransactionReadJobConfigTest::seal;
    }

    /**
     * Produces one envelope-shaped value.
     *
     * @param cleartext the value exactly as the record image carries it
     * @return an envelope-shaped value
     */
    private static String seal(final String cleartext) {
        final byte[] raw = cleartext.getBytes(StandardCharsets.US_ASCII);
        final byte[] body =
                new byte[Math.max(SensitiveFieldCodec.MINIMUM_ENVELOPE_BODY_BYTES, raw.length)];
        System.arraycopy(raw, 0, body, 0, raw.length);
        return SensitiveFieldCodec.ENVELOPE_PREFIX + Base64.getEncoder().encodeToString(body);
    }

    /**
     * Renders every diagnostic the configuration emitted, in order.
     *
     * @return the captured events
     */
    private List<ILoggingEvent> capturedEvents() {
        return mockingDetails(this.appender).getInvocations().stream()
                .map(invocation -> invocation.<ILoggingEvent>getArgument(0))
                .toList();
    }

    /**
     * Renders the error-level diagnostics the configuration emitted, in order.
     *
     * @return the formatted error texts
     */
    private List<String> errorDiagnostics() {
        return capturedEvents().stream()
                .filter(event -> event.getLevel() == Level.ERROR)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    /**
     * Renders the warning-level diagnostics the configuration emitted, in order.
     *
     * @return the formatted warning texts
     */
    private List<String> warningDiagnostics() {
        return capturedEvents().stream()
                .filter(event -> event.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    /**
     * Counts the passes the shared timer recorded under one outcome.
     *
     * @param outcome the outcome tag value
     * @return the number of recorded passes
     */
    private long timedPasses(final String outcome) {
        return this.meterRegistry.find(DailyTransactionReadJobConfig.STEP_TIMER_NAME)
                .tag(DailyTransactionReadJobConfig.TAG_STEP, DailyTransactionReadJobConfig.PROGRAM_NAME)
                .tag(DailyTransactionReadJobConfig.TAG_OUTCOME, outcome)
                .timers().stream()
                .mapToLong(timer -> timer.count())
                .sum();
    }

    @Nested
    @DisplayName("The job is defined, launchable by name, and wired into nothing")
    class TheJobIsDefinedAndUnwired {

        @Test
        @DisplayName("the job carries the published name, so a launcher resolves it by one value rather "
                + "than by a repeated literal")
        void theJobCarriesThePublishedName() {
            final Job job = job();

            assertThat(job.getName()).isEqualTo(DailyTransactionReadJobConfig.JOB_NAME);
        }

        @Test
        @DisplayName("the job holds exactly one step and chains to nothing, so no pipeline reaches it and "
                + "it reaches no pipeline")
        void theJobHoldsExactlyOneStepAndChainsToNothing() {
            final SimpleJob job = (SimpleJob) job();

            assertThat(job.getStepNames())
                    .as("a second step, or a step of another job, would be a chain this program never had")
                    .containsExactly(DailyTransactionReadJobConfig.STEP_NAME);
        }

        @Test
        @DisplayName("the job is a simple sequence and not a flow, so it carries zero failure-ending "
                + "transitions - the estate holds no condition-code gate for this program")
        void theJobDeclaresNoFailureEndingTransition() {
            final Job job = job();

            assertThat(job).isInstanceOf(SimpleJob.class).isNotInstanceOf(FlowJob.class);
        }

        @Test
        @DisplayName("the job requires no date and no mode parameter, because there is no parameter "
                + "string to reproduce")
        void theJobRequiresNoDateOrModeParameter() {
            final Job job = job();

            assertThatCode(() -> job.getJobParametersValidator().validate(new JobParameters()))
                    .as("an empty parameter set must launch this job")
                    .doesNotThrowAnyException();
            assertThatCode(() -> job.getJobParametersValidator()
                    .validate(new JobParametersBuilder().addLong("run.id", 1L).toJobParameters()))
                    .as("the incrementer's own identifying parameter must also be accepted")
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("the job attaches the shared parameter incrementer, so a resubmission runs again as "
                + "resubmitting a job member did")
        void theJobAttachesTheSharedIncrementer() {
            final Job job = job();

            assertThat(job.getJobParametersIncrementer()).isSameAs(runIncrementer);
        }

        @Test
        @DisplayName("the job is restartable, which is the framework default and is not overridden here")
        void theJobIsRestartable() {
            assertThat(job().isRestartable()).isTrue();
        }

        /**
         * Builds the job with the two shared collaborators attached, as a deployment does.
         *
         * @return the job under test
         */
        private Job job() {
            final DailyTransactionReadJobConfig configuration = nothingStaged();
            return configuration.dailyTransactionReadJob(configuration.dailyTransactionExtractStep(),
                    runIncrementer, boundaryListener);
        }
    }

    @Nested
    @DisplayName("The step is one indivisible pass over the program")
    class TheStep {

        @Test
        @DisplayName("the step carries the published name and is a tasklet step, because a legacy batch "
                + "program is not restartable part way through its file")
        void theStepCarriesThePublishedNameAndIsATaskletStep() {
            final Step step = nothingStaged().dailyTransactionExtractStep();

            assertThat(step.getName()).isEqualTo(DailyTransactionReadJobConfig.STEP_NAME);
            assertThat(step).isInstanceOf(TaskletStep.class);
        }

        @Test
        @DisplayName("the step's tasklet runs one complete pass and reports that it is finished")
        void theStepsTaskletRunsOnePassAndReportsFinished() throws Exception {
            when(readService.execute()).thenReturn(emptyPass());
            final TaskletStep step = (TaskletStep) nothingStaged().dailyTransactionExtractStep();

            final RepeatStatus status = step.getTasklet().execute(null, null);

            assertThat(status).isEqualTo(RepeatStatus.FINISHED);
            verify(readService).execute();
        }

        @Test
        @DisplayName("the tasklet is reachable on its own, so the pass can be driven without a launcher")
        void theTaskletIsReachableOnItsOwn() throws Exception {
            when(readService.execute()).thenReturn(emptyPass());

            final RepeatStatus status =
                    nothingStaged().dailyTransactionExtractTasklet().execute(null, null);

            assertThat(status).isEqualTo(RepeatStatus.FINISHED);
        }
    }

    @Nested
    @DisplayName("The extract pass delegates the whole program and is timed")
    class TheExtractPass {

        @Test
        @DisplayName("with nothing staged the translated program resolves its own ordered input, so the "
                + "step supplies no order of its own")
        void withNothingStagedTheProgramResolvesItsOwnOrderedInput() {
            final DailyTransactionReadResult expected = emptyPass();
            when(readService.execute()).thenReturn(expected);

            final DailyTransactionReadResult actual = nothingStaged().runExtractPass();

            assertThat(actual).isSameAs(expected);
            verify(readService, never()).execute(anyList());
        }

        @Test
        @DisplayName("with a dataset staged every record is handed over in the dataset's own order, which "
                + "is the order the legacy physical sequential read returned them in")
        void withADatasetStagedTheRecordsAreHandedOverInDatasetOrder() {
            when(readService.execute(anyList())).thenReturn(emptyPass());

            staged(DALYTRAN_FIXTURE, UNSTAGED, UNSTAGED, UNSTAGED, UNSTAGED, UNSTAGED).runExtractPass();

            verify(readService).execute(handedOverRecords.capture());
            final List<DailyTransaction> records = handedOverRecords.getValue();
            assertThat(records).hasSize(DALYTRAN_RECORDS);
            assertThat(records.get(0).getDalytranId()).isEqualTo(FIRST_DALYTRAN_ID);
            assertThat(records.get(records.size() - 1).getDalytranId()).isEqualTo(LAST_DALYTRAN_ID);
            verify(readService, never()).execute();
        }

        @Test
        @DisplayName("a completed pass is timed on the batch tier's shared program-lifecycle timer, so "
                + "this program joins one metric family beside the wired ones")
        void aCompletedPassIsTimedOnTheSharedTimer() {
            when(readService.execute()).thenReturn(emptyPass());

            nothingStaged().runExtractPass();

            assertThat(timedPasses(DailyTransactionReadJobConfig.OUTCOME_COMPLETED)).isEqualTo(1L);
            assertThat(timedPasses(DailyTransactionReadJobConfig.OUTCOME_ABENDED)).isZero();
        }

        @Test
        @DisplayName("an abend is timed as abended and rethrown untouched, so the diagnostic the abending "
                + "site already emitted is neither repeated nor displaced")
        void anAbendIsTimedAndRethrownUntouched() {
            final AbendException raised = new AbendException(
                    DailyTransactionReadJobConfig.PROGRAM_NAME, "STATUS 31 READING DALYTRAN");
            when(readService.execute()).thenThrow(raised);
            final DailyTransactionReadJobConfig configuration = nothingStaged();

            final AbendException thrown =
                    catchThrowableOfType(AbendException.class, configuration::runExtractPass);

            assertThat(thrown).isSameAs(raised);
            assertThat(timedPasses(DailyTransactionReadJobConfig.OUTCOME_ABENDED)).isEqualTo(1L);
            assertThat(timedPasses(DailyTransactionReadJobConfig.OUTCOME_COMPLETED)).isZero();
            assertThat(errorDiagnostics())
                    .as("nothing may be logged about a failure the abending site already reported")
                    .isEmpty();
        }

        @Test
        @DisplayName("the completed pass reports its counts, which is the whole product of a program that "
                + "writes nothing")
        void theCompletedPassReportsItsCounts() {
            when(readService.execute()).thenReturn(new DailyTransactionReadResult(
                    DALYTRAN_RECORDS, DALYTRAN_RECORDS, DALYTRAN_RECORDS + 1, 2, 1, List.of(), 0));

            nothingStaged().runExtractPass();

            assertThat(capturedEvents()).extracting(ILoggingEvent::getFormattedMessage)
                    .anySatisfy(message -> assertThat(message)
                            .contains(DailyTransactionReadJobConfig.PROGRAM_NAME)
                            .contains(String.valueOf(DALYTRAN_RECORDS)));
        }

        @Test
        @DisplayName("an unstaged input is announced, so a reader of the log knows which of the two input "
                + "resolutions ran")
        void anUnstagedInputIsAnnounced() {
            when(readService.execute()).thenReturn(emptyPass());

            nothingStaged().runExtractPass();

            assertThat(capturedEvents()).extracting(ILoggingEvent::getFormattedMessage)
                    .anySatisfy(message -> assertThat(message)
                            .contains(DailyTransactionReadJobConfig.DD_DALYTRAN));
        }
    }

    @Nested
    @DisplayName("Six resources are bound, each at its own verified width")
    class TheSixResourceBindings {

        @Test
        @DisplayName("every binding is absent when no dataset is staged for it, which is the shipped "
                + "default and a normal state rather than a misconfiguration")
        void everyBindingIsAbsentWhenNoDatasetIsStaged() {
            final DailyTransactionReadJobConfig configuration = nothingStaged();

            assertThat(configuration.dalytranReader()).isEmpty();
            assertThat(configuration.custfileReader(sealer())).isEmpty();
            assertThat(configuration.xreffileReader()).isEmpty();
            assertThat(configuration.cardfileReader()).isEmpty();
            assertThat(configuration.acctfileReader()).isEmpty();
            assertThat(configuration.tranfileReader()).isEmpty();
        }

        @Test
        @DisplayName("every binding names the reader that owns its layout, which is what proves the right "
                + "width and the right offsets were bound")
        void everyBindingNamesTheReaderThatOwnsItsLayout() {
            final DailyTransactionReadJobConfig configuration = allSixStaged();

            assertThat(configuration.dalytranReader().orElseThrow().getName())
                    .isEqualTo(FixedWidthFlatFileReaderFactory.DAILY_TRANSACTION_READER_NAME);
            assertThat(configuration.custfileReader(sealer()).orElseThrow().getName())
                    .isEqualTo(FixedWidthFlatFileReaderFactory.CUSTOMER_READER_NAME);
            assertThat(configuration.xreffileReader().orElseThrow().getName())
                    .isEqualTo(FixedWidthFlatFileReaderFactory.CARD_CROSS_REFERENCE_READER_NAME);
            assertThat(configuration.cardfileReader().orElseThrow().getName())
                    .isEqualTo(FixedWidthFlatFileReaderFactory.CARD_READER_NAME);
            assertThat(configuration.acctfileReader().orElseThrow().getName())
                    .isEqualTo(FixedWidthFlatFileReaderFactory.ACCOUNT_READER_NAME);
            assertThat(configuration.tranfileReader().orElseThrow().getName())
                    .isEqualTo(FixedWidthFlatFileReaderFactory.TRANSACTION_READER_NAME);
        }

        @Test
        @DisplayName("the daily-transaction and posted-transaction bindings stay distinct despite both "
                + "layouts being byte-for-byte identical at 350 bytes")
        void theTwoIdenticallyWidthedBindingsStayDistinct() {
            final DailyTransactionReadJobConfig configuration = allSixStaged();

            assertThat(configuration.dalytranReader().orElseThrow().getName())
                    .isNotEqualTo(configuration.tranfileReader().orElseThrow().getName());
        }

        @Test
        @DisplayName("the daily-transaction binding parses its 350-byte fixture")
        void theDailyTransactionBindingParsesItsFixture() throws Exception {
            assertThat(firstRecordWidthInBytes(DALYTRAN_FIXTURE)).isEqualTo(TRANSACTION_WIDTH);

            assertThat(readAll(allSixStaged().dalytranReader())).hasSize(DALYTRAN_RECORDS);
        }

        @Test
        @DisplayName("the posted-transaction binding parses a 350-byte image, the width its own layout "
                + "shares with the daily-transaction layout")
        void thePostedTransactionBindingParsesA350ByteImage() throws Exception {
            assertThat(readAll(allSixStaged().tranfileReader())).hasSize(DALYTRAN_RECORDS);
        }

        @Test
        @DisplayName("the customer binding parses its 500-byte fixture through the caller's sealer")
        void theCustomerBindingParsesItsFixture() throws Exception {
            assertThat(firstRecordWidthInBytes(CUSTDATA_FIXTURE)).isEqualTo(CUSTOMER_WIDTH);

            assertThat(readAll(allSixStaged().custfileReader(sealer()))).hasSize(REFERENCE_RECORDS);
        }

        @Test
        @DisplayName("the cross-reference binding parses the delivered sample width of its layout")
        void theCrossReferenceBindingParsesItsFixture() throws Exception {
            assertThat(firstRecordWidthInBytes(CARDXREF_FIXTURE)).isEqualTo(CARD_XREF_SAMPLE_WIDTH);

            assertThat(readAll(allSixStaged().xreffileReader())).hasSize(REFERENCE_RECORDS);
        }

        @Test
        @DisplayName("the card binding parses its 150-byte fixture")
        void theCardBindingParsesItsFixture() throws Exception {
            assertThat(firstRecordWidthInBytes(CARDDATA_FIXTURE)).isEqualTo(CARD_WIDTH);

            assertThat(readAll(allSixStaged().cardfileReader())).hasSize(REFERENCE_RECORDS);
        }

        @Test
        @DisplayName("the account binding parses its 300-byte fixture")
        void theAccountBindingParsesItsFixture() throws Exception {
            assertThat(firstRecordWidthInBytes(ACCTDATA_FIXTURE)).isEqualTo(ACCOUNT_WIDTH);

            assertThat(readAll(allSixStaged().acctfileReader())).hasSize(REFERENCE_RECORDS);
        }

        @Test
        @DisplayName("each call builds a new reader, because a reader holds position and two launches must "
                + "not observe one another")
        void eachCallBuildsANewReader() {
            final DailyTransactionReadJobConfig configuration = allSixStaged();

            assertThat(configuration.dalytranReader().orElseThrow())
                    .isNotSameAs(configuration.dalytranReader().orElseThrow());
        }

        @Test
        @DisplayName("the customer binding refuses a null sealer rather than defaulting one, because a "
                + "defaulted sealer would silently leave two regulated fields unprotected")
        void theCustomerBindingRefusesANullSealer() {
            final DailyTransactionReadJobConfig configuration = allSixStaged();

            assertThatNullPointerException()
                    .isThrownBy(() -> configuration.custfileReader(null))
                    .withMessageContaining("regulatedFieldSealer");
        }

        /**
         * Stages all six resources, using the daily-transaction fixture for both 350-byte layouts because
         * the estate delivers one sequential dataset at that width.
         *
         * @return the configuration
         */
        private DailyTransactionReadJobConfig allSixStaged() {
            return staged(DALYTRAN_FIXTURE, CUSTDATA_FIXTURE, CARDXREF_FIXTURE, CARDDATA_FIXTURE,
                    ACCTDATA_FIXTURE, DALYTRAN_FIXTURE);
        }
    }

    @Nested
    @DisplayName("A staged dataset failure is diagnosed first and abended second")
    class TheStagedDatasetFailurePath {

        @Test
        @DisplayName("a dataset that cannot be opened is reported with its raw status and only then "
                + "abended, carrying the abend code the exception itself owns")
        void aDatasetThatCannotBeOpenedIsReportedThenAbended() {
            final DailyTransactionReadJobConfig configuration =
                    staged(ABSENT_FIXTURE, UNSTAGED, UNSTAGED, UNSTAGED, UNSTAGED, UNSTAGED);

            final AbendException thrown =
                    catchThrowableOfType(AbendException.class, configuration::runExtractPass);

            assertThat(thrown).isNotNull();
            assertThat(thrown.code()).isEqualTo(AbendException.BATCH_ABEND_CODE);
            assertThat(thrown.culprit()).isEqualTo(DailyTransactionReadJobConfig.PROGRAM_NAME);
            assertThat(thrown.reason())
                    .contains(FileStatus.PERMANENT_ERROR.getCode())
                    .contains(DailyTransactionReadJobConfig.DD_DALYTRAN);
            assertThat(errorDiagnostics()).hasSize(2);
            assertThat(errorDiagnostics().get(0))
                    .as("the raw two-character status is reported before the abend, never after")
                    .contains(FileStatus.PERMANENT_ERROR.getCode())
                    .contains(DailyTransactionReadJobConfig.DD_DALYTRAN);
            assertThat(errorDiagnostics().get(1))
                    .contains(AbendException.BATCH_ABEND_CODE)
                    .contains(DailyTransactionReadJobConfig.PROGRAM_NAME);
            verify(readService, never()).execute();
            verify(readService, never()).execute(anyList());
            assertThat(timedPasses(DailyTransactionReadJobConfig.OUTCOME_ABENDED)).isEqualTo(1L);
        }

        @Test
        @DisplayName("a record of the wrong width is reported on the read path and only then abended")
        void aRecordOfTheWrongWidthIsReportedThenAbended(@TempDir final Path stagingDirectory)
                throws IOException {
            final Path malformed = stagingDirectory.resolve("dalytran-malformed.txt");
            Files.writeString(malformed, "0000000000\n", StandardCharsets.US_ASCII);
            final DailyTransactionReadJobConfig configuration = staged("file:" + malformed,
                    UNSTAGED, UNSTAGED, UNSTAGED, UNSTAGED, UNSTAGED);

            final AbendException thrown =
                    catchThrowableOfType(AbendException.class, configuration::runExtractPass);

            assertThat(thrown).isNotNull();
            assertThat(thrown.code()).isEqualTo(AbendException.BATCH_ABEND_CODE);
            assertThat(thrown.reason()).contains("READING");
            assertThat(errorDiagnostics()).hasSize(2);
            assertThat(errorDiagnostics().get(0)).contains(FileStatus.PERMANENT_ERROR.getCode());
            assertThat(errorDiagnostics().get(1)).contains(AbendException.BATCH_ABEND_CODE);
            verify(readService, never()).execute(anyList());
        }

        @Test
        @DisplayName("a failure handing back the handle is retained as a warning and does not displace the "
                + "pass, because releasing a handle is a runtime adaptation and not a translated close")
        void aFailureReleasingTheHandleIsRetainedAsAWarning() throws Exception {
            when(stagedReader.read()).thenReturn(null);
            doThrow(new ItemStreamException("handle refused")).when(stagedReader).close();
            when(scriptedReaderFactory.dailyTransactionReader(any())).thenReturn(stagedReader);
            when(readService.execute(anyList())).thenReturn(emptyPass());
            final DailyTransactionReadJobConfig configuration = new DailyTransactionReadJobConfig(
                    jobRepository, transactionManager, scriptedReaderFactory, readService, meterRegistry,
                    resourceLoader, stagingArea, DALYTRAN_FIXTURE, UNSTAGED, UNSTAGED, UNSTAGED,
                    UNSTAGED, UNSTAGED);

            configuration.runExtractPass();

            assertThat(warningDiagnostics()).hasSize(1);
            assertThat(warningDiagnostics().get(0))
                    .contains(DailyTransactionReadJobConfig.DD_DALYTRAN)
                    .contains(ItemStreamException.class.getName());
            assertThat(errorDiagnostics())
                    .as("handing back a handle emits no legacy diagnostic and never abends")
                    .isEmpty();
            assertThat(timedPasses(DailyTransactionReadJobConfig.OUTCOME_COMPLETED)).isEqualTo(1L);
        }

        @Test
        @DisplayName("an abend raised beneath the read is rethrown untouched, so a failure is diagnosed "
                + "once and reported once rather than reported twice")
        void anAbendRaisedBeneathTheReadIsRethrownUntouched() throws Exception {
            final AbendException raised = new AbendException(
                    DailyTransactionReadJobConfig.PROGRAM_NAME, "STATUS 31 READING DALYTRAN");
            when(stagedReader.read()).thenThrow(raised);
            when(scriptedReaderFactory.dailyTransactionReader(any())).thenReturn(stagedReader);
            final DailyTransactionReadJobConfig configuration = new DailyTransactionReadJobConfig(
                    jobRepository, transactionManager, scriptedReaderFactory, readService, meterRegistry,
                    resourceLoader, stagingArea, DALYTRAN_FIXTURE, UNSTAGED, UNSTAGED, UNSTAGED,
                    UNSTAGED, UNSTAGED);

            final AbendException thrown =
                    catchThrowableOfType(AbendException.class, configuration::runExtractPass);

            assertThat(thrown).isSameAs(raised);
            assertThat(errorDiagnostics())
                    .as("an abend already diagnosed beneath this call must not be reported a second time")
                    .isEmpty();
            assertThat(timedPasses(DailyTransactionReadJobConfig.OUTCOME_ABENDED)).isEqualTo(1L);
            verify(readService, never()).execute(anyList());
        }
    }

    @Nested
    @DisplayName("The constructor refuses an absent collaborator and an absent location")
    class TheConstructorContract {

        @Test
        @DisplayName("every collaborator is required, so a half-wired configuration cannot reach a step")
        void everyCollaboratorIsRequired() {
            assertThatNullPointerException().isThrownBy(() -> new DailyTransactionReadJobConfig(
                    null, transactionManager, readerFactory, readService, meterRegistry, resourceLoader,
                    stagingArea, UNSTAGED, UNSTAGED, UNSTAGED, UNSTAGED, UNSTAGED, UNSTAGED))
                    .withMessageContaining("jobRepository");
            assertThatNullPointerException().isThrownBy(() -> new DailyTransactionReadJobConfig(
                    jobRepository, null, readerFactory, readService, meterRegistry, resourceLoader,
                    stagingArea, UNSTAGED, UNSTAGED, UNSTAGED, UNSTAGED, UNSTAGED, UNSTAGED))
                    .withMessageContaining("transactionManager");
            assertThatNullPointerException().isThrownBy(() -> new DailyTransactionReadJobConfig(
                    jobRepository, transactionManager, null, readService, meterRegistry, resourceLoader,
                    stagingArea, UNSTAGED, UNSTAGED, UNSTAGED, UNSTAGED, UNSTAGED, UNSTAGED))
                    .withMessageContaining("readerFactory");
            assertThatNullPointerException().isThrownBy(() -> new DailyTransactionReadJobConfig(
                    jobRepository, transactionManager, readerFactory, null, meterRegistry, resourceLoader,
                    stagingArea, UNSTAGED, UNSTAGED, UNSTAGED, UNSTAGED, UNSTAGED, UNSTAGED))
                    .withMessageContaining("dailyTransactionReadService");
            assertThatNullPointerException().isThrownBy(() -> new DailyTransactionReadJobConfig(
                    jobRepository, transactionManager, readerFactory, readService, null, resourceLoader,
                    stagingArea, UNSTAGED, UNSTAGED, UNSTAGED, UNSTAGED, UNSTAGED, UNSTAGED))
                    .withMessageContaining("meterRegistry");
            assertThatNullPointerException().isThrownBy(() -> new DailyTransactionReadJobConfig(
                    jobRepository, transactionManager, readerFactory, readService, meterRegistry, null,
                    stagingArea, UNSTAGED, UNSTAGED, UNSTAGED, UNSTAGED, UNSTAGED, UNSTAGED))
                    .withMessageContaining("resourceLoader");
            assertThatNullPointerException().isThrownBy(() -> new DailyTransactionReadJobConfig(
                    jobRepository, transactionManager, readerFactory, readService, meterRegistry,
                    resourceLoader, null, UNSTAGED, UNSTAGED, UNSTAGED, UNSTAGED, UNSTAGED, UNSTAGED))
                    .withMessageContaining("stagingArea");
        }

        @Test
        @DisplayName("each of the six locations is rejected when absent and accepted when blank, because "
                + "blank means no dataset is staged and absent means nothing was bound at all")
        void eachLocationIsRejectedWhenAbsentAndAcceptedWhenBlank() {
            assertThatNullPointerException()
                    .isThrownBy(() -> withLocations(null, UNSTAGED, UNSTAGED, UNSTAGED, UNSTAGED,
                            UNSTAGED))
                    .withMessageContaining(DailyTransactionReadJobConfig.DD_DALYTRAN);
            assertThatNullPointerException()
                    .isThrownBy(() -> withLocations(UNSTAGED, null, UNSTAGED, UNSTAGED, UNSTAGED,
                            UNSTAGED))
                    .withMessageContaining(DailyTransactionReadJobConfig.DD_CUSTFILE);
            assertThatNullPointerException()
                    .isThrownBy(() -> withLocations(UNSTAGED, UNSTAGED, null, UNSTAGED, UNSTAGED,
                            UNSTAGED))
                    .withMessageContaining(DailyTransactionReadJobConfig.DD_XREFFILE);
            assertThatNullPointerException()
                    .isThrownBy(() -> withLocations(UNSTAGED, UNSTAGED, UNSTAGED, null, UNSTAGED,
                            UNSTAGED))
                    .withMessageContaining(DailyTransactionReadJobConfig.DD_CARDFILE);
            assertThatNullPointerException()
                    .isThrownBy(() -> withLocations(UNSTAGED, UNSTAGED, UNSTAGED, UNSTAGED, null,
                            UNSTAGED))
                    .withMessageContaining(DailyTransactionReadJobConfig.DD_ACCTFILE);
            assertThatNullPointerException()
                    .isThrownBy(() -> withLocations(UNSTAGED, UNSTAGED, UNSTAGED, UNSTAGED, UNSTAGED,
                            null))
                    .withMessageContaining(DailyTransactionReadJobConfig.DD_TRANFILE);
            assertThatCode(DailyTransactionReadJobConfigTest.this::nothingStaged)
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("a location carrying surrounding whitespace still resolves, so a configuration edit "
                + "cannot fail on invisible characters")
        void aLocationCarryingWhitespaceStillResolves() throws Exception {
            final DailyTransactionReadJobConfig configuration = staged("  " + DALYTRAN_FIXTURE + "  ",
                    UNSTAGED, UNSTAGED, UNSTAGED, UNSTAGED, UNSTAGED);

            assertThat(readAll(configuration.dalytranReader())).hasSize(DALYTRAN_RECORDS);
        }

        /**
         * Builds the configuration with the six locations supplied and every collaborator present.
         *
         * @param dalytran location of the daily-transaction dataset
         * @param custfile location of the customer dataset
         * @param xreffile location of the cross-reference dataset
         * @param cardfile location of the card dataset
         * @param acctfile location of the account dataset
         * @param tranfile location of the posted-transaction dataset
         * @return the configuration
         */
        private DailyTransactionReadJobConfig withLocations(final String dalytran,
                final String custfile, final String xreffile, final String cardfile,
                final String acctfile, final String tranfile) {
            return staged(dalytran, custfile, xreffile, cardfile, acctfile, tranfile);
        }
    }

    @Nested
    @DisplayName("The published contract keeps its names, because other files resolve them")
    class ThePublishedContract {

        @Test
        @DisplayName("the job and step names are original to this module, since no job member names this "
                + "program and there is nothing to inherit")
        void theJobAndStepNamesArePublished() {
            assertThat(DailyTransactionReadJobConfig.JOB_NAME).isEqualTo("dailyTransactionReadJob");
            assertThat(DailyTransactionReadJobConfig.STEP_NAME)
                    .isEqualTo("dailyTransactionExtractStep");
            assertThat(DailyTransactionReadJobConfig.PROGRAM_NAME).isEqualTo("CBTRN01C");
        }

        @Test
        @DisplayName("the six logical names are the member's own resource names")
        void theSixLogicalNamesAreTheMembersOwn() {
            assertThat(List.of(DailyTransactionReadJobConfig.DD_DALYTRAN,
                    DailyTransactionReadJobConfig.DD_CUSTFILE,
                    DailyTransactionReadJobConfig.DD_XREFFILE,
                    DailyTransactionReadJobConfig.DD_CARDFILE,
                    DailyTransactionReadJobConfig.DD_ACCTFILE,
                    DailyTransactionReadJobConfig.DD_TRANFILE))
                    .containsExactly("DALYTRAN", "CUSTFILE", "XREFFILE", "CARDFILE", "ACCTFILE",
                            "TRANFILE");
        }

        @Test
        @DisplayName("each resource is configured under its own logical name, so relocating a dataset is a "
                + "configuration change and never a code change")
        void eachResourceIsConfiguredUnderItsOwnLogicalName() {
            assertThat(List.of(DailyTransactionReadJobConfig.DALYTRAN_RESOURCE_PROPERTY,
                    DailyTransactionReadJobConfig.CUSTFILE_RESOURCE_PROPERTY,
                    DailyTransactionReadJobConfig.XREFFILE_RESOURCE_PROPERTY,
                    DailyTransactionReadJobConfig.CARDFILE_RESOURCE_PROPERTY,
                    DailyTransactionReadJobConfig.ACCTFILE_RESOURCE_PROPERTY,
                    DailyTransactionReadJobConfig.TRANFILE_RESOURCE_PROPERTY))
                    .allSatisfy(property -> assertThat(property)
                            .startsWith(DailyTransactionReadJobConfig.RESOURCE_PROPERTY_PREFIX))
                    .doesNotHaveDuplicates()
                    .hasSize(6);
        }

        @Test
        @DisplayName("the timer name and its two tag keys match the batch tier's shared program-lifecycle "
                + "timer, so one metric family holds every translated program")
        void theTimerContractMatchesTheBatchTier() {
            assertThat(DailyTransactionReadJobConfig.STEP_TIMER_NAME)
                    .isEqualTo("carddemo.batch.cobol.step");
            assertThat(DailyTransactionReadJobConfig.TAG_STEP).isEqualTo("step");
            assertThat(DailyTransactionReadJobConfig.TAG_OUTCOME).isEqualTo("outcome");
            assertThat(DailyTransactionReadJobConfig.OUTCOME_COMPLETED).isEqualTo("COMPLETED");
            assertThat(DailyTransactionReadJobConfig.OUTCOME_ABENDED).isEqualTo("ABENDED");
        }
    }
}
