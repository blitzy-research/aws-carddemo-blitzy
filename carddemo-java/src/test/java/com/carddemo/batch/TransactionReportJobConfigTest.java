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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.Locale;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionListener;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.JobParametersInvalidException;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.job.SimpleJob;
import org.springframework.batch.core.launch.support.RunIdIncrementer;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.scope.context.StepContext;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.PlatformTransactionManager;

import com.carddemo.batch.step.FixedWidthFlatFileReaderFactory;
import com.carddemo.batch.step.TransactionReportProcessor;
import com.carddemo.domain.Transaction;
import com.carddemo.repository.TransactionRepository;
import com.carddemo.repository.TransactionScanRepository;
import com.carddemo.service.DateValidationService;
import com.carddemo.service.ReportTransactionSource;
import com.carddemo.service.TransactionReportService;
import com.carddemo.service.TransactionReportService.TransactionReportResult;
import com.carddemo.util.ReportLineFormatter;
import com.carddemo.util.TransactionRecordMapper;
import com.carddemo.support.OrderedTransactionScan;

/**
 * What the transaction report job configuration publishes, and what its own documentation commits it
 * to.
 *
 * <p>The subject is the translation of the job member {@code app/jcl/TRANREPT.jcl} and its cataloged
 * form {@code app/proc/TRANREPT.prc} at checkout {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec},
 * upstream release stamp {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. Five facts measured
 * from that member are asserted here because getting any of them wrong compiles cleanly and fails only
 * at the byte level: the member declares <strong>three</strong> steps under only two distinct names, it
 * declares <strong>no</strong> condition-code dependency on any of them, its ordering key is the card
 * number typed <strong>zoned decimal</strong> where the statement job types the very same bytes as
 * character, its record-inclusion predicate is <strong>inclusive at both ends</strong>, and its report
 * record length is <strong>{@value ReportLineFormatter#REPORT_RECORD_WIDTH}</strong> bytes.
 *
 * <p><strong>Fixture note.</strong> Every one of the 300 records in the estate's own daily
 * transaction sample carries the same processing date and the same timestamp, so that sample
 * <em>cannot</em> exercise date-window filtering: a filter that ignored its bounds entirely would pass
 * against it. The window assertions below therefore run against a <em>separately constructed</em>
 * fixture spanning several dates, including one record on each bound.
 *
 * <p>No container is needed for anything asserted here: the transaction master is stubbed, the report
 * generator is stubbed where only its output shape matters, and the staged generations are written to a
 * temporary directory. A container-backed test may additionally drive the whole job through a launcher
 * against a real server; every assertion it would need is expressible because each of the three
 * program lifecycles is reachable on its own.
 */
@DisplayName("transaction report job: three steps, no gate, zoned-decimal ordering, inclusive window")
final class TransactionReportJobConfigTest {

    /** Logical name of the transaction backup generation base, as the legacy stream names it. */
    private static final String BACKUP_BASE = "AWS.M2.CARDDEMO.TRANSACT.BKUP";

    /** Logical name of the filtered transaction generation base. */
    private static final String FILTERED_BASE = "AWS.M2.CARDDEMO.TRANSACT.DALY";

    /** Logical name of the report generation base. */
    private static final String REPORT_BASE = "AWS.M2.CARDDEMO.TRANREPT";

    /**
     * The sixteen bytes that complete a batch-form timestamp once a ten-character date precedes them,
     * giving the twenty-six-byte image the record layout declares.
     */
    private static final String BATCH_TIMESTAMP_TAIL = "-00.00.00.000000";

    /** Lower bound of the reporting window used throughout. */
    private static final String WINDOW_START = "2022-01-01";

    /** Upper bound of the reporting window used throughout. */
    private static final String WINDOW_END = "2022-07-06";

    /** A date inside the window but on neither bound. */
    private static final String INSIDE_WINDOW = "2022-03-15";

    /** A date one day before the lower bound. */
    private static final String BEFORE_WINDOW = "2021-12-31";

    /** A date one day after the upper bound. */
    private static final String AFTER_WINDOW = "2022-07-07";

    /** Job execution identifier every directly driven lifecycle is attributed to. */
    private static final long JOB_EXECUTION_ID = 1L;

    /** Staging area the generations of a test resolve within, fresh for every test method. */
    @TempDir
    private Path stagingDirectory;

    /** Creates the test class. */
    TransactionReportJobConfigTest() {
    }

    // -----------------------------------------------------------------------------------------------
    // Fixtures.
    // -----------------------------------------------------------------------------------------------

    /**
     * Builds the configuration under test.
     *
     * <p>The metric registry, the clock, the reader factory and the parameter owner are real because
     * they are cheap and a stub of any of them would prove less. The metadata repository, the
     * transaction manager and the boundary listener are stubs because nothing here reaches a database
     * or a launcher.
     *
     * @param repository the transaction master to read
     * @param reportService the report generator to delegate to
     * @return the configuration
     */
    private TransactionReportJobConfig configuration(final TransactionRepository repository,
            final TransactionReportService reportService) {
        final TransactionScanRepository scanRepository = new OrderedTransactionScan(
                () -> repository.findAll(Sort.by(Sort.Direction.ASC, "tranId")));

        return new TransactionReportJobConfig(
                mock(JobRepository.class),
                mock(PlatformTransactionManager.class),
                mock(JobExecutionListener.class),
                new RunIdIncrementer(),
                new JobParameterValidators(new DateValidationService()),
                scanRepository,
                repository,
                new FixedWidthFlatFileReaderFactory(),
                reportService,
                new SimpleMeterRegistry(),
                Clock.system(ZoneOffset.UTC),
                this.stagingDirectory.toString(),
                BACKUP_BASE,
                FILTERED_BASE,
                REPORT_BASE);
    }

    /**
     * Builds the configuration under test with a transaction master that holds the supplied records in
     * the order given, which is the order a cluster-key read would deliver them in.
     *
     * @param records the records the master holds
     * @return the configuration
     */
    private TransactionReportJobConfig configurationHolding(final List<Transaction> records) {
        final TransactionRepository repository = mock(TransactionRepository.class);
        when(repository.findAll(any(Sort.class))).thenReturn(records);
        when(repository.count()).thenReturn((long) records.size());
        // The window selection the report's second step now issues. The double reproduces the query's
        // declared contract - an inclusive comparison over the ten leading characters of the processing
        // timestamp, ordered by card number then identifier - so this class exercises the step's own
        // ordering and its window re-check. That the QUERY itself has that contract is proved against a
        // real server by TransactionRepositoryIT, not asserted here.
        when(repository.findByProcessingDateWindowOrderedByCardNumber(anyString(), anyString()))
                .thenAnswer(invocation -> selectWindow(records, invocation.getArgument(0),
                        invocation.getArgument(1)));
        return configuration(repository, mock(TransactionReportService.class));
    }

    /** Characters of the processing timestamp the window predicate addresses, as the sort symbol does. */
    private static final int PROCESSING_DATE_WIDTH = 10;

    /**
     * Applies the window selection's declared contract to a held record set.
     *
     * @param records   the records the master holds
     * @param startDate the inclusive lower bound
     * @param endDate   the inclusive upper bound
     * @return the admitted records, ordered by card number then identifier, both ascending
     */
    private static List<Transaction> selectWindow(final List<Transaction> records,
            final String startDate, final String endDate) {

        return records.stream()
                .filter(record -> {
                    final String processingDate = record.getTranProcTs()
                            .substring(0, PROCESSING_DATE_WIDTH);
                    return startDate.compareTo(processingDate) <= 0
                            && processingDate.compareTo(endDate) <= 0;
                })
                .sorted(Comparator.comparing(Transaction::getTranCardNum)
                        .thenComparing(Transaction::getTranId))
                .toList();
    }

    /**
     * Builds one transaction fixture.
     *
     * @param identifier the sixteen-character identifier
     * @param cardNumber the sixteen-byte card-number field image
     * @param processingDate the ten-character processing date the timestamp carries
     * @param amount the amount at scale two
     * @return the fixture
     */
    private static Transaction transaction(final String identifier, final String cardNumber,
            final String processingDate, final String amount) {

        final String timestamp = processingDate + BATCH_TIMESTAMP_TAIL;
        return new Transaction(identifier, "01", "0005", "POS TERM  ", "report fixture",
                new BigDecimal(amount), "000000001", "merchant", "city", "zip       ",
                cardNumber, timestamp, timestamp);
    }

    /**
     * Builds a chunk context carrying the two launch bounds.
     *
     * @param startDate the lower bound, or {@code null} to omit the parameter
     * @param endDate the upper bound, or {@code null} to omit the parameter
     * @return the chunk context
     */
    private static ChunkContext chunkContext(final String startDate, final String endDate) {
        final JobParametersBuilder parameters = new JobParametersBuilder();
        if (startDate != null) {
            parameters.addString(JobParameterValidators.REPORT_START_DATE_KEY, startDate);
        }
        if (endDate != null) {
            parameters.addString(JobParameterValidators.REPORT_END_DATE_KEY, endDate);
        }
        return chunkContext(parameters.toJobParameters());
    }

    /**
     * Builds a chunk context carrying the supplied launch parameters.
     *
     * @param parameters the launch parameters
     * @return the chunk context
     */
    private static ChunkContext chunkContext(final JobParameters parameters) {
        final StepExecution stepExecution = new StepExecution(
                TransactionReportJobConfig.EMIT_STEP_NAME,
                new JobExecution(JOB_EXECUTION_ID, parameters));
        return new ChunkContext(new StepContext(stepExecution));
    }

    /**
     * Reads a staged generation back as its record images, framing it the way its DD declares.
     *
     * <p>Framed by width and never by line. Every generation this job writes is fixed-length with
     * nothing between two records, so a line-oriented read would return the whole generation as one
     * enormous string - which is exactly what this helper used to do, and exactly why the framing
     * regression it hid was invisible here. The byte length is proved to be a whole number of records
     * before any record is handed back, so a stray separator fails this helper rather than shifting a
     * later assertion. See {@code docs/decision-log.md} entry DL-213.
     *
     * @param  generation   the generation to read
     * @param  recordLength the declared record length of that generation
     * @return the record images in emission order
     * @throws IOException if the generation cannot be read
     */
    private static List<String> recordsOf(final Path generation, final int recordLength)
            throws IOException {
        final byte[] image = Files.readAllBytes(generation);
        assertThat(image.length % recordLength)
                .as("a fixed-length generation carries no separator, so its length is a whole number"
                        + " of %s-byte records; it measured %s bytes", recordLength, image.length)
                .isZero();
        assertThat(new String(image, StandardCharsets.US_ASCII).indexOf('\n'))
                .as("no line feed may appear anywhere in a fixed-length generation")
                .isEqualTo(-1);
        final List<String> records = new ArrayList<>(image.length / recordLength);
        for (int offset = 0; offset < image.length; offset += recordLength) {
            records.add(new String(image, offset, recordLength, StandardCharsets.US_ASCII));
        }
        return records;
    }

    /**
     * Reads the card-number field out of a staged record image, at the position the sort specification
     * declares for it.
     *
     * @param recordImage the record image
     * @return the sixteen-byte field
     */
    private static String cardNumberOf(final String recordImage) {
        return recordImage.substring(TransactionRecordMapper.TRAN_CARD_NUM_OFFSET,
                TransactionRecordMapper.TRAN_CARD_NUM_OFFSET
                        + TransactionRecordMapper.TRAN_CARD_NUM_LENGTH);
    }

    /**
     * Runs the unload and then the filter-and-order pass over the supplied master, and hands back the
     * card-number fields of the filtered generation in emission order.
     *
     * @param records the records the master holds
     * @param startDate the inclusive lower bound
     * @param endDate the inclusive upper bound
     * @return the card numbers of the surviving records, in emission order
     * @throws IOException if a generation cannot be read
     */
    private List<String> filteredCardNumbers(final List<Transaction> records,
            final String startDate, final String endDate) throws IOException {

        final TransactionReportJobConfig configuration = configurationHolding(records);
        configuration.newUnloadProgram(configuration.backupGeneration(JOB_EXECUTION_ID)).run();
        configuration.newFilterAndOrderProgram(
                configuration.filteredGeneration(JOB_EXECUTION_ID),
                configuration.reportDateWindow(chunkContext(startDate, endDate))).run();

        final List<String> cardNumbers = new ArrayList<>();
        for (final String image : recordsOf(configuration.filteredGeneration(JOB_EXECUTION_ID),
                    TransactionReportJobConfig.UNLOAD_RECORD_LENGTH)) {
            cardNumbers.add(cardNumberOf(image));
        }
        return cardNumbers;
    }

    // -----------------------------------------------------------------------------------------------
    // The measured contracts.
    // -----------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("the measured contracts of the legacy member")
    final class MeasuredContracts {

        /** Creates the nest. */
        MeasuredContracts() {
        }

        @Test
        @DisplayName("three steps, and not one condition-code gate")
        void threeStepsAndNoGate() {
            assertThat(TransactionReportJobConfig.STEP_COUNT).isEqualTo(3);
            assertThat(TransactionReportJobConfig.CONDITION_CODE_GATE_COUNT)
                    .as("the member declares no condition-code dependency on any step, so wiring a "
                            + "failure-ending transition would change behaviour")
                    .isZero();
        }

        @Test
        @DisplayName("the two staged generations carry the measured 350-byte record length and the "
                + "report carries the measured 133-byte record length")
        void theRecordLengthsAreTheMeasuredOnes() {
            assertThat(TransactionReportJobConfig.UNLOAD_RECORD_LENGTH).isEqualTo(350);
            assertThat(TransactionReportJobConfig.REPORT_RECORD_LENGTH).isEqualTo(133);
            assertThat(TransactionReportJobConfig.UNLOAD_RECORD_LENGTH)
                    .as("the record length has one authority, the record layout in the utility layer")
                    .isEqualTo(TransactionRecordMapper.RECORD_LENGTH);
            assertThat(TransactionReportJobConfig.REPORT_RECORD_LENGTH)
                    .isEqualTo(ReportLineFormatter.REPORT_RECORD_WIDTH);
        }

        @Test
        @DisplayName("the page bound is the measured twenty records, taken from the report layout "
                + "rather than restated")
        void thePageBoundIsTheMeasuredOne() {
            assertThat(TransactionReportJobConfig.PAGE_SIZE).isEqualTo(20);
            assertThat(TransactionReportJobConfig.PAGE_SIZE)
                    .isEqualTo(ReportLineFormatter.PAGE_SIZE);
        }

        @Test
        @DisplayName("both sort symbols sit at the positions and lengths the specification declares, "
                + "derived from the record layout so the two can never drift")
        void bothSortSymbolsAreAtTheDeclaredPositions() {
            assertThat(TransactionReportJobConfig.CARD_NUMBER_SORT_POSITION).isEqualTo(263);
            assertThat(TransactionReportJobConfig.CARD_NUMBER_SORT_LENGTH).isEqualTo(16);
            assertThat(TransactionReportJobConfig.PROCESSING_DATE_SORT_POSITION).isEqualTo(305);
            assertThat(TransactionReportJobConfig.PROCESSING_DATE_SORT_LENGTH).isEqualTo(10);

            assertThat(TransactionReportJobConfig.CARD_NUMBER_SORT_POSITION)
                    .as("the specification states a one-based position and the layout a zero-based "
                            + "offset, and one must be derived from the other")
                    .isEqualTo(TransactionRecordMapper.TRAN_CARD_NUM_OFFSET + 1);
            assertThat(TransactionReportJobConfig.PROCESSING_DATE_SORT_POSITION)
                    .isEqualTo(TransactionRecordMapper.TRAN_PROC_TS_OFFSET + 1);
        }

        @Test
        @DisplayName("the report generation limit resolves to the later and more specific of the two "
                + "divergent declarations, and the superseded one stays visible")
        void theGenerationLimitConflictIsResolvedAndRecorded() {
            assertThat(TransactionReportJobConfig.TRANSACTION_BACKUP_GENERATION_LIMIT).isEqualTo(5);
            assertThat(TransactionReportJobConfig.FILTERED_TRANSACTION_GENERATION_LIMIT).isEqualTo(5);
            assertThat(TransactionReportJobConfig.REPORT_GENERATION_LIMIT).isEqualTo(10);
            assertThat(TransactionReportJobConfig.SUPERSEDED_REPORT_GENERATION_LIMIT)
                    .as("the earlier declaration must remain visible so the conflict is recorded "
                            + "rather than silently resolved")
                    .isEqualTo(5)
                    .isNotEqualTo(TransactionReportJobConfig.REPORT_GENERATION_LIMIT);
        }
    }

    // -----------------------------------------------------------------------------------------------
    // The job and its three steps.
    // -----------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("the job and the three steps it sequences")
    final class TheJobAndItsThreeSteps {

        /** Creates the nest. */
        TheJobAndItsThreeSteps() {
        }

        @Test
        @DisplayName("the job is registered under a stable name")
        void theJobIsRegisteredUnderAStableName() {
            assertThat(TransactionReportJobConfig.JOB_NAME).isEqualTo("transactionReportJob");
        }

        @Test
        @DisplayName("the duplicate legacy step name is resolved: three distinct step names, none of "
                + "them the colliding legacy identifier")
        void theDuplicateLegacyStepNameIsResolved() {
            final List<String> names = List.of(TransactionReportJobConfig.UNLOAD_STEP_NAME,
                    TransactionReportJobConfig.FILTER_AND_ORDER_STEP_NAME,
                    TransactionReportJobConfig.EMIT_STEP_NAME);

            assertThat(names).doesNotHaveDuplicates()
                    .hasSize(TransactionReportJobConfig.STEP_COUNT);
            assertThat(names)
                    .as("the legacy member gives two different steps one name, which two batch steps "
                            + "may not share")
                    .doesNotContain(TransactionReportProcessor.LEGACY_SORT_STEP);
        }

        @Test
        @DisplayName("each step bean is published under its own name")
        void eachStepIsPublishedUnderItsOwnName() {
            final TransactionReportJobConfig configuration =
                    configuration(mock(TransactionRepository.class),
                            mock(TransactionReportService.class));

            assertThat(configuration.transactionReportUnloadStep().getName())
                    .isEqualTo(TransactionReportJobConfig.UNLOAD_STEP_NAME);
            assertThat(configuration.transactionReportFilterAndOrderStep().getName())
                    .isEqualTo(TransactionReportJobConfig.FILTER_AND_ORDER_STEP_NAME);
            assertThat(configuration
                    .transactionReportEmitStep(configuration.transactionReportProcessor())
                    .getName())
                    .isEqualTo(TransactionReportJobConfig.EMIT_STEP_NAME);
        }

        @Test
        @DisplayName("the report stage is published, because it carries no stereotype of its own")
        void theReportStageIsPublished() {
            final TransactionReportJobConfig configuration =
                    configuration(mock(TransactionRepository.class),
                            mock(TransactionReportService.class));

            assertThat(configuration.transactionReportProcessor()).isNotNull();
        }

        @Test
        @DisplayName("the job is a plain sequential job carrying its three steps in the declared "
                + "order, so it holds no flow and therefore no failure-ending transition at all")
        void theJobIsPlainlySequential() {
            final Job job = job();

            assertThat(job.getName()).isEqualTo(TransactionReportJobConfig.JOB_NAME);
            assertThat(job)
                    .as("a flow job is the shape a condition-code gate produces, and the measured "
                            + "member declares no gate on any step")
                    .isInstanceOf(SimpleJob.class);
            assertThat(((SimpleJob) job).getStepNames()).containsExactly(
                    TransactionReportJobConfig.UNLOAD_STEP_NAME,
                    TransactionReportJobConfig.FILTER_AND_ORDER_STEP_NAME,
                    TransactionReportJobConfig.EMIT_STEP_NAME);
        }

        @Test
        @DisplayName("the job carries the shared incrementer, so it can be resubmitted with the same "
                + "parameter set exactly as the legacy member could be")
        void theJobCarriesTheSharedIncrementer() {
            assertThat(job().getJobParametersIncrementer()).isNotNull();
        }

        @Test
        @DisplayName("the job carries the report date-range validator, so a launch without a usable "
                + "window is refused before any step runs")
        void theJobCarriesTheReportDateRangeValidator() {
            final Job job = job();

            assertThat(job.getJobParametersValidator()).isNotNull();
            assertThatExceptionOfType(JobParametersInvalidException.class)
                    .as("an empty parameter set names no window, and the report program cannot be "
                            + "reached without one")
                    .isThrownBy(() ->
                            job.getJobParametersValidator().validate(new JobParameters()));
            assertThatExceptionOfType(JobParametersInvalidException.class)
                    .as("an inverted window would report a range the legacy stream could not express")
                    .isThrownBy(() -> job.getJobParametersValidator().validate(
                            new JobParametersBuilder()
                                    .addString(JobParameterValidators.REPORT_START_DATE_KEY,
                                            WINDOW_END)
                                    .addString(JobParameterValidators.REPORT_END_DATE_KEY,
                                            WINDOW_START)
                                    .toJobParameters()));
        }

        @Test
        @DisplayName("a well-formed window is accepted, including one whose bounds are the same day")
        void aWellFormedWindowIsAccepted() throws JobParametersInvalidException {
            final Job job = job();

            job.getJobParametersValidator().validate(new JobParametersBuilder()
                    .addString(JobParameterValidators.REPORT_START_DATE_KEY, WINDOW_START)
                    .addString(JobParameterValidators.REPORT_END_DATE_KEY, WINDOW_END)
                    .toJobParameters());
            job.getJobParametersValidator().validate(new JobParametersBuilder()
                    .addString(JobParameterValidators.REPORT_START_DATE_KEY, INSIDE_WINDOW)
                    .addString(JobParameterValidators.REPORT_END_DATE_KEY, INSIDE_WINDOW)
                    .toJobParameters());
        }

        @Test
        @DisplayName("the configuration declares no startup runner and no scheduling, so nothing fires "
                + "when the context comes up")
        void nothingFiresAtContextStart() {
            assertThat(TransactionReportJobConfig.class.getInterfaces())
                    .as("a runner interface here would launch this job by the act of starting the "
                            + "application, which a legacy job stream never did")
                    .isEmpty();
            assertThat(TransactionReportJobConfig.class.getAnnotations())
                    .hasSize(1);
            assertThat(TransactionReportJobConfig.class.getDeclaredMethods())
                    .allSatisfy(method -> assertThat(method.getName())
                            .isNotEqualTo("afterPropertiesSet"));
        }

        /**
         * Builds the job from freshly built steps, exactly as the container would.
         *
         * @return the job
         */
        private Job job() {
            final TransactionReportJobConfig configuration =
                    configuration(mock(TransactionRepository.class),
                            mock(TransactionReportService.class));
            final Step unload = configuration.transactionReportUnloadStep();
            final Step filterAndOrder = configuration.transactionReportFilterAndOrderStep();
            final Step emit = configuration.transactionReportEmitStep(
                    configuration.transactionReportProcessor());
            return configuration.transactionReportJob(unload, filterAndOrder, emit);
        }
    }

    // -----------------------------------------------------------------------------------------------
    // The ordering specification: zoned decimal, ascending, and job-local.
    // -----------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("the ordering: card number ascending, typed zoned decimal, and private to this job")
    final class TheOrderingSpecification {

        /** Creates the nest. */
        TheOrderingSpecification() {
        }

        @Test
        @DisplayName("the ordering is by card number ascending, and it is the zoned-decimal typing that "
                + "decides - not the character ordering the statement job's own specification declares "
                + "for the very same bytes")
        void theOrderingAppliesTheZonedDecimalTyping() throws IOException {
            // Four card numbers chosen so that the two typings disagree. The last byte of a zoned
            // field carries both a digit and a sign: 'I' is a positively signed nine and 'J' a
            // negatively signed one, so the numeric values are -1, 9, 9 and 10 while the character
            // values put the two overpunched images last.
            final List<String> zonedDecimalOrder = filteredCardNumbers(List.of(
                    transaction("0000000000000001", "0000000000000010", INSIDE_WINDOW, "10.00"),
                    transaction("0000000000000002", "0000000000000009", INSIDE_WINDOW, "20.00"),
                    transaction("0000000000000003", "000000000000000I", INSIDE_WINDOW, "30.00"),
                    transaction("0000000000000004", "000000000000000J", INSIDE_WINDOW, "40.00")),
                    WINDOW_START, WINDOW_END);

            assertThat(zonedDecimalOrder).containsExactly(
                    "000000000000000J",
                    "0000000000000009",
                    "000000000000000I",
                    "0000000000000010");

            final List<String> characterOrder = new ArrayList<>(zonedDecimalOrder);
            characterOrder.sort(Comparator.naturalOrder());
            assertThat(zonedDecimalOrder)
                    .as("if these two agreed the assertion above would be vacuous, and a comparator "
                            + "shared with the statement job would pass unnoticed")
                    .isNotEqualTo(characterOrder);
        }

        @Test
        @DisplayName("records sharing a card number keep identifier order, because the specification "
                + "declares no secondary key and the selection makes the tie stable")
        void tiesKeepTheSequenceTheUnloadDelivered() throws IOException {
            final TransactionReportJobConfig configuration = configurationHolding(List.of(
                    transaction("0000000000000007", "4111111111111111", INSIDE_WINDOW, "10.00"),
                    transaction("0000000000000008", "4111111111111111", INSIDE_WINDOW, "20.00"),
                    transaction("0000000000000009", "4111111111111111", INSIDE_WINDOW, "30.00")));

            configuration.newUnloadProgram(configuration.backupGeneration(JOB_EXECUTION_ID)).run();
            configuration.newFilterAndOrderProgram(
                    configuration.filteredGeneration(JOB_EXECUTION_ID),
                    configuration.reportDateWindow(chunkContext(WINDOW_START, WINDOW_END))).run();

            final List<String> identifiers = new ArrayList<>();
            for (final String image : recordsOf(configuration.filteredGeneration(JOB_EXECUTION_ID),
                    TransactionReportJobConfig.UNLOAD_RECORD_LENGTH)) {
                identifiers.add(image.substring(TransactionRecordMapper.TRAN_ID_OFFSET,
                        TransactionRecordMapper.TRAN_ID_OFFSET
                                + TransactionRecordMapper.TRAN_ID_LENGTH));
            }

            assertThat(identifiers).containsExactly("0000000000000007", "0000000000000008",
                    "0000000000000009");
        }

        @Test
        @DisplayName("every comparator this class holds is private, static and final, because the "
                + "statement job types the same field differently and a shared comparator would "
                + "silently apply one job's typing to the other job's data")
        void everyComparatorIsJobLocal() throws NoSuchFieldException {
            final Field field = TransactionReportJobConfig.class
                    .getDeclaredField("CARD_NUMBER_ZONED_DECIMAL_ASCENDING");

            assertThat(Modifier.isPrivate(field.getModifiers()))
                    .as("the comparator must not be reachable from another job")
                    .isTrue();
            assertThat(Modifier.isStatic(field.getModifiers())).isTrue();
            assertThat(Modifier.isFinal(field.getModifiers())).isTrue();

            assertThat(TransactionReportJobConfig.class.getDeclaredFields())
                    .filteredOn(declared -> Comparator.class.isAssignableFrom(declared.getType()))
                    .hasSize(1)
                    .allSatisfy(declared ->
                            assertThat(Modifier.isPrivate(declared.getModifiers())).isTrue());
            assertThat(TransactionReportJobConfig.class.getDeclaredMethods())
                    .filteredOn(method -> Comparator.class.isAssignableFrom(method.getReturnType()))
                    .allSatisfy(method ->
                            assertThat(Modifier.isPrivate(method.getModifiers())).isTrue());
        }

        @Test
        @DisplayName("no member of the public surface hands a comparator out")
        void noPublicMemberExposesAComparator() {
            assertThat(TransactionReportJobConfig.class.getMethods())
                    .allSatisfy(method -> assertThat(
                            Comparator.class.isAssignableFrom(method.getReturnType())).isFalse());
        }

        @Test
        @DisplayName("a card-number field that is not a zoned-decimal image is refused rather than "
                + "quietly ordered as characters")
        void aFieldThatIsNotAZonedImageIsRefused() {
            // Two records, so the field genuinely takes part in a comparison: a work area holding one
            // record is emitted without any key being consulted, exactly as an ordering of one record
            // needs no comparison.
            final TransactionReportJobConfig configuration = configurationHolding(List.of(
                    transaction("0000000000000001", "41111111111111  ", INSIDE_WINDOW, "10.00"),
                    transaction("0000000000000002", "4111111111111112", INSIDE_WINDOW, "20.00")));
            configuration.newUnloadProgram(configuration.backupGeneration(JOB_EXECUTION_ID)).run();

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("a short, space-padded field would order differently under the two typings, "
                            + "so it must fail loudly rather than reorder the report")
                    .isThrownBy(() -> configuration.newFilterAndOrderProgram(
                            configuration.filteredGeneration(JOB_EXECUTION_ID),
                            configuration.reportDateWindow(
                                    chunkContext(WINDOW_START, WINDOW_END))).run())
                    .withMessageContaining("TRAN-CARD-NUM");
        }
    }

    // -----------------------------------------------------------------------------------------------
    // The record-inclusion predicate: inclusive at both ends.
    // -----------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("the record-inclusion predicate, which is inclusive at both ends")
    final class TheInclusiveWindow {

        /** Creates the nest. */
        TheInclusiveWindow() {
        }

        @Test
        @DisplayName("a record on the lower bound and a record on the upper bound are BOTH included, "
                + "and the days either side of the window are both excluded")
        void bothBoundsAreInclusive() throws IOException {
            // Constructed deliberately across five distinct dates: the estate's own daily sample
            // carries one date for all of its records and so cannot tell an inclusive bound from an
            // exclusive one, nor a filter from no filter at all.
            final List<String> surviving = filteredCardNumbers(List.of(
                    transaction("0000000000000001", "0000000000000001", BEFORE_WINDOW, "10.00"),
                    transaction("0000000000000002", "0000000000000002", WINDOW_START, "20.00"),
                    transaction("0000000000000003", "0000000000000003", INSIDE_WINDOW, "30.00"),
                    transaction("0000000000000004", "0000000000000004", WINDOW_END, "40.00"),
                    transaction("0000000000000005", "0000000000000005", AFTER_WINDOW, "50.00")),
                    WINDOW_START, WINDOW_END);

            assertThat(surviving)
                    .as("the record on the start bound and the record on the end bound must both "
                            + "survive; narrowing either bound to an exclusive comparison would drop "
                            + "one of them")
                    .containsExactly("0000000000000002", "0000000000000003", "0000000000000004");
        }

        @Test
        @DisplayName("a window whose bounds are the same day selects that day, rather than nothing")
        void aSingleDayWindowSelectsThatDay() throws IOException {
            final List<String> surviving = filteredCardNumbers(List.of(
                    transaction("0000000000000001", "0000000000000001", BEFORE_WINDOW, "10.00"),
                    transaction("0000000000000002", "0000000000000002", INSIDE_WINDOW, "20.00"),
                    transaction("0000000000000003", "0000000000000003", AFTER_WINDOW, "30.00")),
                    INSIDE_WINDOW, INSIDE_WINDOW);

            assertThat(surviving).containsExactly("0000000000000002");
        }

        @Test
        @DisplayName("the predicate answers to the window and not to the record count, so a window "
                + "matching nothing produces an empty generation rather than an unfiltered one")
        void aWindowMatchingNothingProducesAnEmptyGeneration() throws IOException {
            final List<String> surviving = filteredCardNumbers(List.of(
                    transaction("0000000000000001", "0000000000000001", BEFORE_WINDOW, "10.00"),
                    transaction("0000000000000002", "0000000000000002", AFTER_WINDOW, "20.00")),
                    WINDOW_START, WINDOW_END);

            assertThat(surviving).isEmpty();
        }

        @Test
        @DisplayName("the excluded records are counted, so a run that filtered everything is "
                + "distinguishable from a run that read nothing")
        void theExcludedRecordsAreCounted() {
            final TransactionReportJobConfig configuration = configurationHolding(List.of(
                    transaction("0000000000000001", "0000000000000001", BEFORE_WINDOW, "10.00"),
                    transaction("0000000000000002", "0000000000000002", INSIDE_WINDOW, "20.00"),
                    transaction("0000000000000003", "0000000000000003", AFTER_WINDOW, "30.00")));
            configuration.newUnloadProgram(configuration.backupGeneration(JOB_EXECUTION_ID)).run();

            final TransactionReportJobConfig.FilterAndOrderProgram program =
                    configuration.newFilterAndOrderProgram(
                            configuration.filteredGeneration(JOB_EXECUTION_ID),
                            configuration.reportDateWindow(
                                    chunkContext(WINDOW_START, WINDOW_END)));
            program.run();

            assertThat(program.recordsExcluded()).isEqualTo(2L);
            final List<String> ordered = new ArrayList<>();
            program.forEachOrderedRecord(ordered::add);
            assertThat(ordered).hasSize(1);
            assertThat(ordered)
                    .allSatisfy(image -> assertThat(
                            image.getBytes(StandardCharsets.US_ASCII).length)
                            .isEqualTo(TransactionReportJobConfig.UNLOAD_RECORD_LENGTH));
        }
    }

    // -----------------------------------------------------------------------------------------------
    // The staged generations, and the fact that no process is spawned to produce them.
    // -----------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("the staged generations the first two steps produce")
    final class TheStagedGenerations {

        /** Creates the nest. */
        TheStagedGenerations() {
        }

        @Test
        @DisplayName("the unload copies the whole master, in cluster-key sequence, at exactly 350 "
                + "encoded bytes per record")
        void theUnloadWritesTheMeasuredRecordLength() throws IOException {
            final TransactionReportJobConfig configuration = configurationHolding(List.of(
                    transaction("0000000000000001", "4111111111111111", INSIDE_WINDOW, "10.00"),
                    transaction("0000000000000002", "4111111111111112", INSIDE_WINDOW, "20.00")));

            final TransactionReportJobConfig.TransactionUnloadProgram program =
                    configuration.newUnloadProgram(configuration.backupGeneration(JOB_EXECUTION_ID));
            program.run();

            assertThat(program.recordsUnloaded()).isEqualTo(2L);
            final List<String> records =
                    recordsOf(configuration.backupGeneration(JOB_EXECUTION_ID),
                    TransactionReportJobConfig.UNLOAD_RECORD_LENGTH);
            assertThat(records).hasSize(2);
            assertThat(records).allSatisfy(image ->
                    assertThat(image.getBytes(StandardCharsets.US_ASCII).length)
                            .as("the record length is a byte contract, so it is measured on encoded "
                                    + "bytes and never on a character count")
                            .isEqualTo(TransactionReportJobConfig.UNLOAD_RECORD_LENGTH));
            assertThat(cardNumberOf(records.get(0))).isEqualTo("4111111111111111");
            assertThat(cardNumberOf(records.get(1))).isEqualTo("4111111111111112");
        }

        @Test
        @DisplayName("an empty master unloads an empty generation rather than failing, because the "
                + "legacy read reports end of file and the copy simply produces nothing")
        void anEmptyMasterUnloadsAnEmptyGeneration() throws IOException {
            final TransactionReportJobConfig configuration = configurationHolding(List.of());

            final TransactionReportJobConfig.TransactionUnloadProgram program =
                    configuration.newUnloadProgram(configuration.backupGeneration(JOB_EXECUTION_ID));
            program.run();

            assertThat(program.recordsUnloaded()).isZero();
            assertThat(recordsOf(configuration.backupGeneration(JOB_EXECUTION_ID),
                    TransactionReportJobConfig.UNLOAD_RECORD_LENGTH)).isEmpty();
        }

        @Test
        @DisplayName("the unload reads the master in ascending cluster-key sequence, which is the "
                + "sequence the ordering step then relies on as its tie-break")
        void theUnloadReadsInClusterKeySequence() {
            final TransactionRepository repository = mock(TransactionRepository.class);
            when(repository.findAll(any(Sort.class))).thenReturn(List.of());
            final TransactionReportJobConfig configuration =
                    configuration(repository, mock(TransactionReportService.class));

            configuration.newUnloadProgram(configuration.backupGeneration(JOB_EXECUTION_ID)).run();

            final ArgumentCaptor<Sort> requested = ArgumentCaptor.forClass(Sort.class);
            verify(repository).findAll(requested.capture());
            assertThat(requested.getValue())
                    .as("the copy utility reads a keyed cluster in key order, so the ordering step's "
                            + "stable sort has a defined sequence to preserve for a tie")
                    .isEqualTo(Sort.by(Sort.Direction.ASC, "tranId"));
        }

        @Test
        @DisplayName("a backup generation that cannot be opened abends, and the handle the unload "
                + "never obtained is released without a second failure")
        void aBackupGenerationThatCannotBeOpenedAbends() throws IOException {
            final TransactionReportJobConfig configuration = configurationHolding(List.of(
                    transaction("0000000000000001", "4111111111111111", INSIDE_WINDOW, "10.00")));
            final Path generation = configuration.backupGeneration(JOB_EXECUTION_ID);
            Files.createDirectories(generation);

            assertThatExceptionOfType(RuntimeException.class)
                    .isThrownBy(() -> configuration.newUnloadProgram(generation).run());
        }

        @Test
        @DisplayName("each of the three tasklets runs its own lifecycle over the generations its job "
                + "execution owns, so the three steps agree on one set of generations without passing "
                + "state between them")
        void theThreeTaskletsAgreeOnOneSetOfGenerations() throws IOException {
            final TransactionReportJobConfig configuration = configurationHolding(List.of(
                    transaction("0000000000000001", "4111111111111112", INSIDE_WINDOW, "10.00"),
                    transaction("0000000000000002", "4111111111111111", INSIDE_WINDOW, "20.00")));
            final ChunkContext chunkContext = chunkContext(WINDOW_START, WINDOW_END);

            assertThat(configuration.unloadTransactionMaster(null, chunkContext))
                    .isEqualTo(RepeatStatus.FINISHED);
            assertThat(configuration.filterAndOrderTransactions(null, chunkContext))
                    .isEqualTo(RepeatStatus.FINISHED);

            final List<String> filtered =
                    recordsOf(configuration.filteredGeneration(JOB_EXECUTION_ID),
                    TransactionReportJobConfig.UNLOAD_RECORD_LENGTH);
            assertThat(filtered).hasSize(2);
            assertThat(cardNumberOf(filtered.get(0)))
                    .as("the second tasklet must read the generation the first one wrote, and order it")
                    .isEqualTo("4111111111111111");
        }

        @Test
        @DisplayName("the report tasklet writes the report generation its job execution owns")
        void theReportTaskletWritesItsOwnGeneration() throws IOException {
            final TransactionReportService reportService = mock(TransactionReportService.class);
            stubReportEmission(reportService,
                    ReportLineFormatter.buildHeaderBlock(WINDOW_START, WINDOW_END),
                    new BigDecimal("0.00"), 0, 4L, 0);
            final TransactionReportJobConfig configuration =
                    configuration(mock(TransactionRepository.class), reportService);
            Files.writeString(configuration.filteredGeneration(JOB_EXECUTION_ID), "",
                    StandardCharsets.US_ASCII);

            assertThat(configuration.emitReport(configuration.transactionReportProcessor(),
                    chunkContext(WINDOW_START, WINDOW_END)))
                    .isEqualTo(RepeatStatus.FINISHED);

            assertThat(recordsOf(configuration.reportGeneration(JOB_EXECUTION_ID),
                    TransactionReportJobConfig.REPORT_RECORD_LENGTH))
                    .hasSize(ReportLineFormatter.HEADER_BLOCK_RECORD_COUNT)
                    .allSatisfy(record -> assertThat(
                            record.getBytes(StandardCharsets.US_ASCII).length)
                            .isEqualTo(TransactionReportJobConfig.REPORT_RECORD_LENGTH));
        }

        @Test
        @DisplayName("each generation is a distinct resource per job execution, resolved against the "
                + "configured staging area from a logical name")
        void eachGenerationIsDistinctPerJobExecution() {
            final TransactionReportJobConfig configuration =
                    configuration(mock(TransactionRepository.class),
                            mock(TransactionReportService.class));

            final Path first = configuration.backupGeneration(1L);
            final Path second = configuration.backupGeneration(2L);

            assertThat(first).isNotEqualTo(second);
            assertThat(first.getParent()).isEqualTo(stagingDirectory);
            assertThat(first.getFileName().toString()).startsWith(BACKUP_BASE).endsWith("V00");
            assertThat(configuration.filteredGeneration(1L).getFileName().toString())
                    .startsWith(FILTERED_BASE);
            assertThat(configuration.reportGeneration(1L).getFileName().toString())
                    .startsWith(REPORT_BASE);
            assertThat(List.of(configuration.backupGeneration(1L),
                    configuration.filteredGeneration(1L), configuration.reportGeneration(1L)))
                    .as("the three generations of one execution must not collide with each other")
                    .doesNotHaveDuplicates();
        }

        @Test
        @DisplayName("a generation number is never reduced modulo an earlier execution")
        void aGenerationNumberDoesNotWrap() {
            final TransactionReportJobConfig configuration =
                    configuration(mock(TransactionRepository.class),
                            mock(TransactionReportService.class));

            final String name = configuration.reportGeneration(1_234_567L).getFileName().toString();

            assertThat(name).isEqualTo(REPORT_BASE + ".G0001234567V00")
                    .isNotEqualTo(configuration.reportGeneration(4567L).getFileName().toString());
        }
    }

    /**
     * Stubs a report generator to offer the supplied records to the sink it is given.
     *
     * <p>The generator streams: it hands each record to the caller's destination as it composes it and
     * reports only how many it offered. A stub that returned a list would not exercise the path the
     * emitter actually takes. See {@code docs/decision-log.md} entry DL-176.
     *
     * @param reportService     the mocked generator
     * @param emitted           the records to offer, in emission order
     * @param grandTotal        the grand total to report
     * @param pageCount         the page-total emissions to report
     * @param lineCount         the line-counter value to report
     * @param accountBreakCount the account-total emissions to report
     */
    private static void stubReportEmission(final TransactionReportService reportService,
            final List<String> emitted, final BigDecimal grandTotal, final int pageCount,
            final long lineCount, final int accountBreakCount) {

        when(reportService.generateReportFromDateParameterCard(
                any(ReportTransactionSource.class), any(), anyString()))
                .thenAnswer(invocation -> {
                    final Consumer<String> sink = invocation.getArgument(1);
                    emitted.forEach(sink);
                    return new TransactionReportResult(emitted.size(), grandTotal, pageCount,
                            lineCount, accountBreakCount);
                });
    }

    // -----------------------------------------------------------------------------------------------
    // The report emission.
    // -----------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("the report emission, at 133 encoded bytes per record")
    final class TheReportEmission {

        /** Creates the nest. */
        TheReportEmission() {
        }

        @Test
        @DisplayName("every record that reaches the report generation is exactly 133 encoded bytes, in "
                + "the order the report generator produced them")
        void everyReportRecordIsTheMeasuredWidth() throws IOException {
            final List<String> emitted = reportLines();
            final TransactionReportJobConfig configuration = configurationEmitting(emitted);

            final TransactionReportJobConfig.ReportEmitProgram program = configuration.newEmitProgram(
                    configuration.transactionReportProcessor(),
                    configuration.dateParameterCard(chunkContext(WINDOW_START, WINDOW_END)),
                    configuration.filteredGeneration(JOB_EXECUTION_ID),
                    configuration.reportGeneration(JOB_EXECUTION_ID));
            program.run();

            final List<String> written =
                    recordsOf(configuration.reportGeneration(JOB_EXECUTION_ID),
                    TransactionReportJobConfig.REPORT_RECORD_LENGTH);

            assertThat(program.recordsWritten()).isEqualTo(emitted.size());
            assertThat(program.result().reportRecordCount()).isEqualTo(emitted.size());
            assertThat(written).containsExactlyElementsOf(emitted);
            assertThat(written).allSatisfy(record ->
                    assertThat(record.getBytes(StandardCharsets.US_ASCII).length)
                            .as("the report record length is a byte contract, so it is measured on "
                                    + "encoded bytes and never on a character count")
                            .isEqualTo(TransactionReportJobConfig.REPORT_RECORD_LENGTH));
        }

        @Test
        @DisplayName("the emitter walks the filtered generation once, forward only, and what a run "
                + "produced is unaffected by a later change to the backing file")
        void theFilteredGenerationIsWalkedOnceForwardOnly() throws IOException {
            final Transaction first =
                    transaction("0000000000000001", "4111111111111111", INSIDE_WINDOW, "10.00");
            final Transaction second =
                    transaction("0000000000000002", "4222222222222222", INSIDE_WINDOW, "20.00");
            final Transaction replacement =
                    transaction("0000000000000003", "4333333333333333", INSIDE_WINDOW, "30.00");
            final List<String> identifiersSeen = new ArrayList<>();
            final TransactionReportService reportService = mock(TransactionReportService.class);
            // The generator drives the walk itself, which is the only way to observe a streaming
            // source: it is read during the run and holds nothing afterwards. Each record it sees
            // becomes one report record, so the report content depends on the generation's content.
            when(reportService.generateReportFromDateParameterCard(
                    any(ReportTransactionSource.class), any(), anyString()))
                    .thenAnswer(invocation -> {
                        final ReportTransactionSource source = invocation.getArgument(0);
                        final Consumer<String> sink = invocation.getArgument(1);
                        int position = 0;
                        Optional<Transaction> next = source.readAt(position);
                        while (next.isPresent()) {
                            identifiersSeen.add(next.get().getTranId());
                            sink.accept(detailRecordFor(next.get()));
                            position++;
                            next = source.readAt(position);
                        }
                        // Reading the same position twice returns the same answer without a further
                        // read, and a position already left behind cannot be revisited.
                        assertThat(source.readAt(position)).isEmpty();
                        assertThatExceptionOfType(IllegalStateException.class)
                                .isThrownBy(() -> source.readAt(0))
                                .withMessageContaining("read forward");
                        assertThatExceptionOfType(IllegalArgumentException.class)
                                .isThrownBy(() -> source.readAt(-1));
                        return new TransactionReportResult(identifiersSeen.size(),
                                new BigDecimal("30.00"), 1, 7L, 1);
                    });
            final TransactionReportJobConfig configuration =
                    configuration(mock(TransactionRepository.class), reportService);
            final Path filtered = configuration.filteredGeneration(JOB_EXECUTION_ID);
            // Written the way the filter step writes it: fixed-length, nothing between two records
            // (DL-213). A separator here would shift every record after the first.
            Files.writeString(filtered,
                    TransactionRecordMapper.toRecord(first)
                            + TransactionRecordMapper.toRecord(second),
                    StandardCharsets.US_ASCII);

            final TransactionReportJobConfig.ReportEmitProgram program = configuration.newEmitProgram(
                    configuration.transactionReportProcessor(),
                    configuration.dateParameterCard(chunkContext(WINDOW_START, WINDOW_END)),
                    filtered, configuration.reportGeneration(JOB_EXECUTION_ID));
            program.run();

            assertThat(identifiersSeen)
                    .containsExactly(first.getTranId(), second.getTranId());
            final List<String> produced =
                    recordsOf(configuration.reportGeneration(JOB_EXECUTION_ID),
                    TransactionReportJobConfig.REPORT_RECORD_LENGTH);
            assertThat(produced)
                    .containsExactly(detailRecordFor(first), detailRecordFor(second));

            Files.writeString(filtered, TransactionRecordMapper.toRecord(replacement),
                    StandardCharsets.US_ASCII);

            assertThat(recordsOf(configuration.reportGeneration(JOB_EXECUTION_ID),
                    TransactionReportJobConfig.REPORT_RECORD_LENGTH))
                    .as("the run has finished; a later change to its input cannot alter what it wrote")
                    .isEqualTo(produced);
        }

        /**
         * One report detail record derived from a transaction, at the declared report record width.
         *
         * @param transaction the transaction the record reports
         * @return the record image
         */
        private String detailRecordFor(final Transaction transaction) {
            return ReportLineFormatter.buildTransactionDetailLine(transaction.getTranId(),
                    "00000000001", "01", "Purchase", 5, "Restaurant", "POS TERM  ",
                    transaction.getTranAmt());
        }

        @Test
        @DisplayName("the figures the run reports are the generator's own, so this configuration "
                + "introduces no arithmetic of its own into the report path")
        void theReportedFiguresAreTheGeneratorsOwn() {
            // The grand total supplied here is deliberately not the sum of the detail amounts. The
            // legacy program feeds its grand total ONLY from page totals, and with truncating
            // arithmetic that indirect route can differ from a direct sum - so a configuration that
            // recomputed the figure would contradict the program it stands in for.
            final TransactionReportJobConfig configuration =
                    configurationEmitting(reportLines(), new BigDecimal("1.23"), 1, 7L, 1);

            final TransactionReportJobConfig.ReportEmitProgram program = configuration.newEmitProgram(
                    configuration.transactionReportProcessor(),
                    configuration.dateParameterCard(chunkContext(WINDOW_START, WINDOW_END)),
                    configuration.filteredGeneration(JOB_EXECUTION_ID),
                    configuration.reportGeneration(JOB_EXECUTION_ID));
            program.run();

            assertThat(program.result()).isNotNull();
            assertThat(program.result().grandTotal())
                    .as("the grand total is carried across untouched, because it is reached only by "
                            + "way of page totals inside the generator")
                    .isEqualTo(new BigDecimal("1.23"));
            assertThat(program.result().pageCount()).isEqualTo(1);
            assertThat(program.result().accountBreakCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("an empty parameter dataset produces no report record at all rather than a "
                + "failure, because the legacy read reports end of file and the driving loop then "
                + "never iterates")
        void anEmptyParameterDatasetProducesNoReportRecord() throws IOException {
            final TransactionReportJobConfig configuration = configurationEmitting(reportLines());

            final TransactionReportJobConfig.ReportEmitProgram program = configuration.newEmitProgram(
                    configuration.transactionReportProcessor(), null,
                    configuration.filteredGeneration(JOB_EXECUTION_ID),
                    configuration.reportGeneration(JOB_EXECUTION_ID));
            program.run();

            assertThat(program.result()).isNull();
            assertThat(program.recordsWritten()).isZero();
            assertThat(recordsOf(configuration.reportGeneration(JOB_EXECUTION_ID),
                    TransactionReportJobConfig.REPORT_RECORD_LENGTH)).isEmpty();
        }

        @Test
        @DisplayName("a report generation that cannot be opened abends after its status has been "
                + "reported, and the handle it never obtained is released without a second failure")
        void aGenerationThatCannotBeOpenedAbends() throws IOException {
            final TransactionReportJobConfig configuration = configurationEmitting(reportLines());
            final Path generation = configuration.reportGeneration(JOB_EXECUTION_ID);
            Files.createDirectories(generation);

            assertThatExceptionOfType(RuntimeException.class)
                    .as("the open family arms its sentinel, reports the status and abends; it does not "
                            + "carry on with an unopened generation")
                    .isThrownBy(() -> configuration.newEmitProgram(
                            configuration.transactionReportProcessor(),
                            configuration.dateParameterCard(
                                    chunkContext(WINDOW_START, WINDOW_END)),
                            configuration.filteredGeneration(JOB_EXECUTION_ID),
                            generation).run());
        }

        @Test
        @DisplayName("the date-parameter record the report program receives is the 21-byte structured "
                + "record its reader contract declares, built and validated by its shared owners")
        void theDateParameterRecordIsTheStructuredRecord() {
            final TransactionReportJobConfig configuration =
                    configuration(mock(TransactionRepository.class),
                            mock(TransactionReportService.class));

            final String card =
                    configuration.dateParameterCard(chunkContext(WINDOW_START, WINDOW_END));

            assertThat(card.getBytes(StandardCharsets.US_ASCII).length)
                    .as("the significant group is measured in encoded bytes, never in characters")
                    .isEqualTo(ReportLineFormatter.DATE_PARAMETER_STRUCTURED_WIDTH);
            assertThat(ReportLineFormatter.readStartDate(card)).isEqualTo(WINDOW_START);
            assertThat(ReportLineFormatter.readEndDate(card)).isEqualTo(WINDOW_END);
            assertThat(configuration.reportDateWindow(chunkContext(WINDOW_START, WINDOW_END))
                    .startDate()).isEqualTo(WINDOW_START);
            assertThat(configuration.reportDateWindow(chunkContext(WINDOW_START, WINDOW_END))
                    .endDate()).isEqualTo(WINDOW_END);
        }

        /**
         * Builds a configuration whose report generator emits the supplied records to its sink.
         *
         * @param emitted the records the generator offers to the sink, in emission order
         * @return the configuration
         */
        private TransactionReportJobConfig configurationEmitting(final List<String> emitted) {
            return configurationEmitting(emitted, new BigDecimal("30.00"), 1, 7L, 1);
        }

        /**
         * Builds a configuration whose report generator emits the supplied records to its sink and
         * reports the supplied figures.
         *
         * @param emitted           the records the generator offers to the sink, in emission order
         * @param grandTotal        the grand total the generator reports
         * @param pageCount         the page-total emissions the generator reports
         * @param lineCount         the line-counter value the generator reports
         * @param accountBreakCount the account-total emissions the generator reports
         * @return the configuration
         */
        private TransactionReportJobConfig configurationEmitting(final List<String> emitted,
                final BigDecimal grandTotal, final int pageCount, final long lineCount,
                final int accountBreakCount) {

            final TransactionReportService reportService = mock(TransactionReportService.class);
            stubReportEmission(reportService, emitted, grandTotal, pageCount, lineCount,
                    accountBreakCount);
            final TransactionReportJobConfig configuration =
                    configuration(mock(TransactionRepository.class), reportService);
            try {
                Files.writeString(configuration.filteredGeneration(JOB_EXECUTION_ID), "",
                        StandardCharsets.US_ASCII);
            } catch (IOException failure) {
                throw new java.io.UncheckedIOException(failure);
            }
            return configuration;
        }


        /**
         * Builds the record images of a small report: the four-record header block, two detail
         * records, a page-total record and a grand-total record.
         *
         * @return the record images in emission order
         */
        private List<String> reportLines() {
            final List<String> lines =
                    new ArrayList<>(ReportLineFormatter.buildHeaderBlock(WINDOW_START, WINDOW_END));
            lines.add(ReportLineFormatter.buildTransactionDetailLine("0000000000000001",
                    "00000000001", "01", "Purchase", 5, "Restaurant", "POS TERM  ",
                    new BigDecimal("10.00")));
            lines.add(ReportLineFormatter.buildTransactionDetailLine("0000000000000002",
                    "00000000001", "01", "Purchase", 5, "Restaurant", "POS TERM  ",
                    new BigDecimal("20.00")));
            lines.add(ReportLineFormatter.buildPageTotalLine(new BigDecimal("30.00")));
            lines.add(ReportLineFormatter.buildGrandTotalLine(new BigDecimal("30.00")));
            return List.copyOf(lines);
        }
    }

    // -----------------------------------------------------------------------------------------------
    // Launch parameters and logical names: nothing defaulted that would change what a run reports.
    // -----------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("launch parameters and logical names")
    final class LaunchParametersAndLogicalNames {

        /** Creates the nest. */
        LaunchParametersAndLogicalNames() {
        }

        @Test
        @DisplayName("an absent lower bound is refused by name rather than defaulted to some "
                + "conventional date")
        void anAbsentLowerBoundIsRefused() {
            final TransactionReportJobConfig configuration =
                    configuration(mock(TransactionRepository.class),
                            mock(TransactionReportService.class));

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() ->
                            configuration.dateParameterCard(chunkContext(null, WINDOW_END)))
                    .withMessageContaining(JobParameterValidators.REPORT_START_DATE_KEY);
        }

        @Test
        @DisplayName("an absent upper bound is refused by name for the same reason")
        void anAbsentUpperBoundIsRefused() {
            final TransactionReportJobConfig configuration =
                    configuration(mock(TransactionRepository.class),
                            mock(TransactionReportService.class));

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() ->
                            configuration.dateParameterCard(chunkContext(WINDOW_START, null)))
                    .withMessageContaining(JobParameterValidators.REPORT_END_DATE_KEY);
        }

        @Test
        @DisplayName("a blank bound is refused as firmly as an absent one")
        void aBlankBoundIsRefused() {
            final TransactionReportJobConfig configuration =
                    configuration(mock(TransactionRepository.class),
                            mock(TransactionReportService.class));

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() ->
                            configuration.dateParameterCard(chunkContext("   ", WINDOW_END)))
                    .withMessageContaining(JobParameterValidators.REPORT_START_DATE_KEY);
        }

        @Test
        @DisplayName("an inverted window is refused by the same cascade the launch validator runs, so "
                + "the report program cannot be reached with a range the legacy stream could not "
                + "express")
        void anInvertedWindowIsRefused() {
            final TransactionReportJobConfig configuration =
                    configuration(mock(TransactionRepository.class),
                            mock(TransactionReportService.class));

            assertThatExceptionOfType(RuntimeException.class)
                    .isThrownBy(() ->
                            configuration.dateParameterCard(chunkContext(WINDOW_END, WINDOW_START)));
        }

        @Test
        @DisplayName("a blank logical name is refused, because it would resolve to the staging "
                + "directory itself rather than to a generation")
        void aBlankLogicalNameIsRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new TransactionReportJobConfig(
                            mock(JobRepository.class),
                            mock(PlatformTransactionManager.class),
                            mock(JobExecutionListener.class),
                            new RunIdIncrementer(),
                            new JobParameterValidators(new DateValidationService()),
                            mock(TransactionScanRepository.class),
                            mock(TransactionRepository.class),
                            new FixedWidthFlatFileReaderFactory(),
                            mock(TransactionReportService.class),
                            new SimpleMeterRegistry(),
                            Clock.system(ZoneOffset.UTC),
                            stagingDirectory.toString(),
                            BACKUP_BASE,
                            FILTERED_BASE,
                            "   "))
                    .withMessageContaining("reportBase");
        }

        @Test
        @DisplayName("the width proof measures encoded bytes and refuses a record of the wrong width, "
                + "naming the data definition it was written to")
        void theWidthProofRefusesTheWrongWidth() {
            TransactionReportJobConfig.requireEncodedWidth("x".repeat(133), 133, "TRANREPT");

            assertThatExceptionOfType(IllegalStateException.class)
                    .as("a record of the wrong width would leave every downstream reader of that "
                            + "generation reading the wrong field")
                    .isThrownBy(() -> TransactionReportJobConfig
                            .requireEncodedWidth("x".repeat(132), 133, "TRANREPT"))
                    .withMessageContaining("TRANREPT")
                    .withMessageContaining("132")
                    .withMessageContaining("133");
        }

        @Test
        @DisplayName("the width proof measures the ENCODED array, and it is purity - owned upstream - "
                + "that keeps that measurement trustworthy")
        void theWidthProofMeasuresTheEncodedArray() {
            final String record = "\u00e9".repeat(133);

            assertThat(record).hasSize(133);
            assertThat(record.getBytes(StandardCharsets.US_ASCII))
                    .as("the encoder substitutes one replacement byte for a character it cannot "
                            + "represent, which keeps the count right and makes the content wrong - so "
                            + "the width proof cannot be the thing that catches impurity, and it is "
                            + "proved upstream by the layers that build these records instead")
                    .hasSize(133);
            TransactionReportJobConfig.requireEncodedWidth(record, 133, "TRANREPT");
        }

        @Test
        @DisplayName("releasing a handle after a failure never raises and never emits a legacy "
                + "diagnostic, whether the handle is absent or refuses to close")
        void releasingAHandleNeverRaises() {
            TransactionReportJobConfig.releaseQuietly(null, "FILEOUT");
            TransactionReportJobConfig.releaseQuietly(() -> {
                throw new IOException("the enclave would have released this itself");
            }, "FILEOUT");
        }

        @Test
        @DisplayName("an absent collaborator is refused at construction, so a partially built "
                + "configuration is not representable")
        void anAbsentCollaboratorIsRefused() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new TransactionReportJobConfig(
                            mock(JobRepository.class),
                            mock(PlatformTransactionManager.class),
                            mock(JobExecutionListener.class),
                            new RunIdIncrementer(),
                            new JobParameterValidators(new DateValidationService()),
                            mock(TransactionScanRepository.class),
                            mock(TransactionRepository.class),
                            new FixedWidthFlatFileReaderFactory(),
                            null,
                            new SimpleMeterRegistry(),
                            Clock.system(ZoneOffset.UTC),
                            stagingDirectory.toString(),
                            BACKUP_BASE,
                            FILTERED_BASE,
                            REPORT_BASE))
                    .withMessageContaining("reportService");
        }
    }
}
