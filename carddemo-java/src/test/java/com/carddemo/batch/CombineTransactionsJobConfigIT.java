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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import com.carddemo.batch.step.AdvisoryGenerationPublicationLock;
import com.carddemo.batch.step.FixedWidthFlatFileReaderFactory;
import com.carddemo.batch.step.StagedGenerationStore;
import com.carddemo.config.AwsProperties;
import com.carddemo.config.BatchConfig;
import com.carddemo.domain.Transaction;
import com.carddemo.repository.RecordWriter;
import com.carddemo.repository.TransactionRepository;
import com.carddemo.service.AbendService;
import com.carddemo.service.DateValidationService;
import com.carddemo.service.InterestCalculationService;
import com.carddemo.service.InterestGroupTransactionBoundary;
import com.carddemo.service.PostingRecordTransactionBoundary;
import com.carddemo.service.TransactionPostingService;
import com.carddemo.support.AbstractPostgresIT;
import com.carddemo.support.IsolatedStagingRoot;
import com.carddemo.util.ExternalStringSorter;

import io.awspring.cloud.s3.S3Operations;
import io.awspring.cloud.sns.core.SnsOperations;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Clock;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Properties;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobInstance;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.JobParametersIncrementer;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.configuration.JobRegistry;
import org.springframework.batch.core.converter.DefaultJobParametersConverter;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.job.SimpleJob;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.step.tasklet.TaskletStep;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.actuate.autoconfigure.tracing.prometheus.PrometheusExemplarsAutoConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Integration specification for the two-step transaction-combine job against the shared real
 * PostgreSQL 16 server.
 *
 * <h2>Pipeline position and setup</h2>
 *
 * <p>The transaction master begins empty, and all fifty seeded category-balance rows begin at zero.
 * This specification therefore launches posting and then interest by their registered names before it
 * launches the combine job. Posting gives interest a non-zero balance to accrue; interest then creates
 * the second fixed-width input. This is setup for the job under test, not another pipeline golden-file
 * test.
 *
 * <p>The relationship with the preceding backup job is intentional: backup archives the transaction
 * master and then empties it, while this job reloads that master from the archive followed by the newly
 * synthesized interest generation. The second step is consequently a real reload through the
 * repository, not a redundant copy.
 *
 * <h2>Ordering and deterministic equality</h2>
 *
 * <p>The first input is the backup and the second is the synthesized generation. Their concatenation
 * order is contractual because equal identifiers have no secondary key. The implementation deliberately
 * makes that otherwise-unspecified case deterministic with a stable ordering, so an equal-key backup
 * record remains ahead of an equal-key synthesized record.
 *
 * <p>The comparator belongs to this job alone. The estate has no internal ordering verb; its four
 * external ordering specifications are independent, and the same bytes beginning at one-based position
 * 263 are character data in the statement job but zoned-decimal data in the report job. Sharing a
 * comparator would therefore transfer one job's type semantics into another without a compilation
 * failure.
 *
 * <h2>Copy translation, record width and audit boundary</h2>
 *
 * <p>The generic copy-utility step becomes an ordinary read-and-write step. The module-wide
 * process-spawn count is zero, and this specification audits the production tree because this job is
 * the translation most likely to violate that boundary. The write path remains repository-backed and
 * contains neither native query construction nor reflective mapping.
 *
 * <p>Each logical record image is exactly 350 US-ASCII bytes. The local combined file adds one explicit
 * line-feed byte after each image because a local line-oriented file stands in for a record-formatted
 * dataset whose access method supplied the boundary. Assertions therefore measure every image and the
 * concatenated image payload at 350-byte multiples without trimming; the separator is verified
 * separately and is never counted as record content.
 *
 * <p>Provenance: matrix-header reference only, from checkout SHA
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} (2022-07-19). The stamp is not asserted against an individual
 * legacy member, and no legacy source text is transcribed here.
 */
@SpringBootTest(classes = CombineTransactionsJobConfigIT.JobContext.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.batch.job.enabled=false",
                "spring.flyway.enabled=false",
                "spring.main.banner-mode=off",
                "management.endpoint.health.validate-group-membership=false",
                "management.tracing.enabled=false",
                CombineTransactionsJobConfig.BACKUP_RESOURCE_PROPERTY
                        + "=" + CombineTransactionsJobConfigIT.BACKUP_DATASET,
                CombineTransactionsJobConfig.SYNTHESIZED_RESOURCE_PROPERTY
                        + "=" + CombineTransactionsJobConfigIT.SYNTHESIZED_DATASET,
                PostTransactionJobConfig.DALYTRAN_DATASET_PROPERTY
                        + "=" + CombineTransactionsJobConfigIT.POSTING_DATASET,
                PostTransactionJobConfig.DALYREJS_DATASET_BASE_PROPERTY
                        + "=" + CombineTransactionsJobConfigIT.REJECT_DATASET_BASE,
                InterestCalculationJobConfig.TRANSACT_DATASET_BASE_PROPERTY
                        + "=" + CombineTransactionsJobConfigIT.INTEREST_DATASET_BASE,
                StagedGenerationStore.BATCH_STAGING_BUCKET_PROPERTY
                        + "=combine-transactions-config-it"})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("CombineTransactionsJobConfigIT - ordered concatenation and repository reload")
class CombineTransactionsJobConfigIT extends AbstractPostgresIT {

    /** Encoded width of one transaction record image. */
    private static final int RECORD_BYTES = 350;

    /**
     * The stride one record occupies in the sealed generation, which is the record width itself.
     *
     * <p>Nothing is written between two records: the legacy DD declares the combined dataset
     * record-format blocked, so the boundary is the declared width and no separator exists. Kept as its
     * own name because the stride is the thing this class walks the generation on. See
     * {@code docs/decision-log.md} entry DL-213.
     */
    private static final int LOCAL_RECORD_BYTES = RECORD_BYTES;

    /** Scale of every persisted monetary amount. */
    private static final int MONETARY_SCALE = 2;

    /** Positive overpunch characters for final digits zero through nine. */
    private static final String POSITIVE_OVERPUNCH = "{ABCDEFGHI";

    /** Negative overpunch characters for final digits zero through nine. */
    private static final String NEGATIVE_OVERPUNCH = "}JKLMNOPQR";

    /**
     * This specification's label within this process's private staging namespace.
     *
     * <p>It named a directory directly beneath the platform temporary directory, which every run of this
     * specification and every sibling clone on the host resolved identically; it is now one segment beneath
     * a namespace unique to this process. See {@link IsolatedStagingRoot}.
     */
    static final String STAGING_LABEL = "combine-transactions-config-it";

    /** Deployment-owned logical name of the posting input. */
    static final String POSTING_DATASET = "combine-posting-input.txt";

    /** Deployment-owned logical name of the first combine input. */
    static final String BACKUP_DATASET = "combine-transaction-backup.dat";

    /** Deployment-owned logical name of the second combine input. */
    static final String SYNTHESIZED_DATASET = "combine-synthesized-transactions.dat";

    /** Logical generation base for posting rejects. */
    static final String REJECT_DATASET_BASE = "combine-posting-rejects";

    /** Logical generation base produced by the setup interest run. */
    static final String INTEREST_DATASET_BASE = "combine-interest-transactions";

    /** Identifier reserved for the transaction accepted by the posting setup run. */
    private static final String POSTED_TRANSACTION_ID = "9880000000000001";

    /** Identifier deliberately shared by both origins in the stability scenario. */
    private static final String SHARED_TRANSACTION_ID = "9880000000000002";

    /**
     * Prefix of every synthetic identifier minted for the spill-scale and generation-resolution cases.
     *
     * <p>Distinct from the two reserved identifiers above, so a synthetic record can never collide with
     * the posted fixture or with the two-record equal-key fixture.
     */
    private static final String SYNTHETIC_IDENTIFIER_PREFIX = "9884";

    /** First key ordinal the synthetic streams use, leaving the lower ordinals unassigned. */
    private static final long SYNTHETIC_KEY_ORIGIN = 1000L;

    /** Seeded card whose account is open and unexpired at the fixed business date. */
    private static final String SEEDED_CARD_NUMBER = "0500024453765740";

    /** Ten-character interest parameter derived from the base class's pinned business date. */
    private static final String PINNED_INTEREST_DATE =
            PINNED_BUSINESS_DATE.format(DateTimeFormatter.BASIC_ISO_DATE) + "00";

    /** Framework-owned identifying parameter minted for every launch. */
    private static final String RUN_ID_PARAMETER = "run.id";

    /** Production source root, resolved from the Maven module's working directory. */
    private static final Path PRODUCTION_SOURCE_ROOT = Path.of("src", "main", "java");

    /** Source file whose private comparator and sequential builder calls are inspected. */
    private static final Path COMBINE_CONFIGURATION_SOURCE = PRODUCTION_SOURCE_ROOT.resolve(
            Path.of("com", "carddemo", "batch", "CombineTransactionsJobConfig.java"));

    /** Converts strongly typed parameters into the operator's property representation. */
    private static final DefaultJobParametersConverter JOB_PARAMETERS_CONVERTER =
            new DefaultJobParametersConverter();

    /** Identifier field at offset zero. */
    private static final FieldSpan IDENTIFIER = new FieldSpan("identifier", 0, 16);

    /** Transaction type field. */
    private static final FieldSpan TYPE_CODE = new FieldSpan("type code", 16, 2);

    /** Transaction category field. */
    private static final FieldSpan CATEGORY_CODE = new FieldSpan("category code", 18, 4);

    /** Transaction source field. */
    private static final FieldSpan SOURCE = new FieldSpan("source", 22, 10);

    /** Transaction description field. */
    private static final FieldSpan DESCRIPTION = new FieldSpan("description", 32, 100);

    /** Signed zoned-decimal amount field. */
    private static final FieldSpan AMOUNT = new FieldSpan("amount", 132, 11);

    /** Merchant identifier field. */
    private static final FieldSpan MERCHANT_ID = new FieldSpan("merchant identifier", 143, 9);

    /** Merchant name field. */
    private static final FieldSpan MERCHANT_NAME = new FieldSpan("merchant name", 152, 50);

    /** Merchant city field. */
    private static final FieldSpan MERCHANT_CITY = new FieldSpan("merchant city", 202, 50);

    /** Merchant postal-code field. */
    private static final FieldSpan MERCHANT_ZIP = new FieldSpan("merchant postal code", 252, 10);

    /** Card-number field. */
    private static final FieldSpan CARD_NUMBER = new FieldSpan("card number", 262, 16);

    /** Origination timestamp field. */
    private static final FieldSpan ORIGINAL_TIMESTAMP =
            new FieldSpan("origination timestamp", 278, 26);

    /** Processing timestamp field. */
    private static final FieldSpan PROCESSING_TIMESTAMP =
            new FieldSpan("processing timestamp", 304, 26);

    /** Trailing layout filler. */
    private static final FieldSpan TRAILING_FILLER = new FieldSpan("trailing filler", 330, 20);

    /** Complete documented transaction layout in record-image order. */
    private static final List<FieldSpan> TRANSACTION_LAYOUT = List.of(
            IDENTIFIER,
            TYPE_CODE,
            CATEGORY_CODE,
            SOURCE,
            DESCRIPTION,
            AMOUNT,
            MERCHANT_ID,
            MERCHANT_NAME,
            MERCHANT_CITY,
            MERCHANT_ZIP,
            CARD_NUMBER,
            ORIGINAL_TIMESTAMP,
            PROCESSING_TIMESTAMP,
            TRAILING_FILLER);

    /** Registry populated by the framework and addressed by stable job name. */
    private final JobRegistry jobRegistry;

    /** Operator used for every explicit setup and subject launch. */
    private final JobOperator jobOperator;

    /** Metadata reader used to advance run identity and inspect executions. */
    private final JobExplorer jobExplorer;

    /** Shared server-side incrementer that mints each launch identity. */
    private final JobParametersIncrementer runIncrementer;

    /** Transaction master read through an explicit ascending sort. */
    private final TransactionRepository transactionRepository;

    /** Setup configuration used only to locate the interest generation it produced. */
    private final InterestCalculationJobConfig interestConfiguration;

    /** Registry carrying the framework's batch-step timing observations. */
    private final MeterRegistry meterRegistry;

    /** Effective configuration used to prove launch-on-start remains disabled. */
    private final Environment environment;

    /**
     * Creates the specification with the same named infrastructure used by an operational launch.
     *
     * @param jobRegistry framework job registry
     * @param jobOperator framework job operator
     * @param jobExplorer framework metadata reader
     * @param runIncrementer shared run-identity incrementer
     * @param transactionRepository transaction master
     * @param interestConfiguration setup interest configuration
     * @param meterRegistry application meter registry
     * @param environment effective test environment
     */
    @Autowired
    CombineTransactionsJobConfigIT(
            final JobRegistry jobRegistry,
            final JobOperator jobOperator,
            final JobExplorer jobExplorer,
            @Qualifier("batchJobRunIncrementer") final JobParametersIncrementer runIncrementer,
            final TransactionRepository transactionRepository,
            final InterestCalculationJobConfig interestConfiguration,
            final MeterRegistry meterRegistry,
            final Environment environment) {
        this.jobRegistry = jobRegistry;
        this.jobOperator = jobOperator;
        this.jobExplorer = jobExplorer;
        this.runIncrementer = runIncrementer;
        this.transactionRepository = transactionRepository;
        this.interestConfiguration = interestConfiguration;
        this.meterRegistry = meterRegistry;
        this.environment = environment;
    }

    /**
     * Starts from the exact reference seed and from an empty private staging namespace.
     *
     * @throws SQLException if the shared server cannot be restored
     * @throws IOException if staged files cannot be removed
     */
    @BeforeAll
    static void restoreReferenceStateBeforeRuns() throws SQLException, IOException {
        restoreSeededState();
        clearStagingDirectory();
    }

    /**
     * Restores the shared server even when a job failed after committing an earlier record.
     *
     * @throws SQLException if the shared server cannot be restored
     * @throws IOException if staged files cannot be removed
     */
    @AfterAll
    static void restoreReferenceStateAfterRuns() throws SQLException, IOException {
        try {
            restoreSeededState();
        } finally {
            clearStagingDirectory();
        }
    }

    @Test
    @Order(1)
    @DisplayName("the registered job is inert, has two plain sequential steps, and declares no date")
    void registeredShapeIsInertAndStrictlySequential() throws Exception {
        final long instancesAtFirstAssertion =
                this.jobExplorer.getJobInstanceCount(CombineTransactionsJobConfig.JOB_NAME);

        assertThat(this.jobRegistry.getJobNames())
                .as("the operator launches the combine job by its published name")
                .contains(CombineTransactionsJobConfig.JOB_NAME);
        assertThat(this.environment.getProperty("spring.batch.job.enabled", Boolean.class))
                .as("refreshing the context must not launch any batch job")
                .isFalse();
        assertThat(this.environment.containsProperty("spring.batch.job.name"))
                .as("no start-up job selection is configured")
                .isFalse();
        assertThat(this.jobExplorer.findRunningJobExecutions(CombineTransactionsJobConfig.JOB_NAME))
                .as("the context did not leave an automatically started execution running")
                .isEmpty();

        final Job registered =
                this.jobRegistry.getJob(CombineTransactionsJobConfig.JOB_NAME);
        assertThat(registered)
                .as("a plain start-then-next sequence has no failure-ending flow transition")
                .isExactlyInstanceOf(SimpleJob.class);
        final SimpleJob sequential = (SimpleJob) registered;
        assertThat(sequential.getStepNames())
                .containsExactly(
                        CombineTransactionsJobConfig.ORDER_STEP_NAME,
                        CombineTransactionsJobConfig.LOAD_STEP_NAME)
                .hasSize(2);

        final Step orderStep = sequential.getStep(CombineTransactionsJobConfig.ORDER_STEP_NAME);
        final Step loadStep = sequential.getStep(CombineTransactionsJobConfig.LOAD_STEP_NAME);
        assertThat(orderStep).isExactlyInstanceOf(TaskletStep.class);
        assertThat(loadStep).isExactlyInstanceOf(TaskletStep.class);

        final String configurationSource = Files.readString(
                COMBINE_CONFIGURATION_SOURCE, StandardCharsets.UTF_8);
        assertThat(configurationSource)
                .doesNotContain(
                        ".task" + "Executor(",
                        ".partitioner(",
                        ".split(",
                        "CommandLine" + "Runner",
                        "Application" + "Runner",
                        "Smart" + "Lifecycle",
                        "Application" + "Listener",
                        "@Post" + "Construct");
        assertThat(this.jobExplorer.getJobInstanceCount(CombineTransactionsJobConfig.JOB_NAME))
                .as("inspecting the context and job shape creates no execution")
                .isEqualTo(instancesAtFirstAssertion);
    }

    @Test
    @Order(2)
    @DisplayName("posting and interest feed a byte-exact ascending reload of both input streams")
    void setupJobsFeedACompleteAscendingReload() throws Exception {
        final long combineInstancesBeforeSetup =
                this.jobExplorer.getJobInstanceCount(CombineTransactionsJobConfig.JOB_NAME);
        final PreparedInputs inputs = prepareInputsThroughPostingAndInterest();

        assertThat(this.jobExplorer.getJobInstanceCount(CombineTransactionsJobConfig.JOB_NAME))
                .as("running the two setup jobs must not implicitly launch the combine job")
                .isEqualTo(combineInstancesBeforeSetup);

        final long backupRows = inputs.backupImages().size();
        final long synthesizedRows = inputs.synthesizedImages().size();
        final long expectedRows = backupRows + synthesizedRows;

        this.transactionRepository.deleteAllInBatch();
        assertThat(this.transactionRepository.count())
                .as("the archived master is empty before the reload begins")
                .isZero();

        final JobExecution execution = launchByName(
                CombineTransactionsJobConfig.JOB_NAME, new JobParameters());
        assertCompletedCombineExecution(execution, expectedRows);
        assertNoCallerDateParameter(execution);

        // Read from the sealed local generation, named in the legacy generation form beneath the legacy
        // base the measured member declares - not from an intercepted publication and not from a
        // job-scoped key of this translation's invention. A completed submission leaves the local
        // generation in place, which is what lets it be read here (DL-212).
        final Path combinedGeneration = StagedGenerationStore.generationPath(stagingDirectory(),
                CombineTransactionsJobConfig.COMBINED_DATASET_BASE, execution.getId());
        assertThat(combinedGeneration)
                .as("the combined generation is named for the legacy generation group, so it falls under"
                        + " the same one canonical key shape and the same retention pass as every other"
                        + " artefact this package publishes")
                .exists();

        final List<String> actualImages = combinedRecordImages(
                Files.readString(combinedGeneration, StandardCharsets.US_ASCII),
                Math.toIntExact(expectedRows));
        final List<String> expectedImages = inputs.inConcatenationOrder();
        expectedImages.sort(Comparator.comparing(image -> slice(image, IDENTIFIER)));

        assertThat(actualImages)
                .as("the stable character-key ordering is applied to the concatenated stream")
                .containsExactlyElementsOf(expectedImages);
        assertThat(actualImages)
                .extracting(image -> slice(image, IDENTIFIER))
                .isSortedAccordingTo(Comparator.naturalOrder());
        assertImagePayloadIsARecordMultiple(actualImages);

        final List<Transaction> persisted = this.transactionRepository.findAll(
                Sort.by(Sort.Direction.ASC, "tranId"));
        assertThat(this.transactionRepository.count())
                .as("the reload count is backup rows plus synthesized rows")
                .isEqualTo(backupRows + synthesizedRows);
        assertThat(persisted).hasSize(Math.toIntExact(expectedRows));

        final List<String> persistedImages = persisted.stream()
                .map(RecordValues::from)
                .map(CombineTransactionsJobConfigIT::render)
                .toList();
        assertThat(persistedImages)
                .as("every ordered image reaches the repository without field loss")
                .containsExactlyElementsOf(expectedImages);

        final List<String> expectedIdentifiers = expectedImages.stream()
                .map(image -> slice(image, IDENTIFIER))
                .toList();
        assertThat(persisted)
                .extracting(Transaction::getTranId)
                .as("the record-image identifier remains the primary key; no generated key replaces it")
                .containsExactlyElementsOf(expectedIdentifiers);

        final String postedExpected = inputs.backupImages().get(0);
        final int postedPosition = expectedImages.indexOf(postedExpected);
        assertThat(postedPosition)
                .as("the posted backup image must be present in the ordered generation")
                .isNotNegative();
        assertRoundTripByDocumentedOffsets(postedExpected, actualImages.get(postedPosition));
        assertRoundTripByDocumentedOffsets(postedExpected, persistedImages.get(postedPosition));

        for (final Transaction transaction : persisted) {
            assertStoredAmountContract(transaction.getTranAmt());
        }
        assertBatchStepTimersPresent();
    }

    @Test
    @Order(3)
    @DisplayName("equal identifiers retain backup-first order before the keyed reload refuses the second")
    void equalIdentifiersRetainConcatenationOrder() throws Exception {
        restoreSeededState();
        final RecordValues backup = duplicateRecord(
                "BACKUP    ", "BACKUP ORIGIN", new BigDecimal("12.34"));
        final RecordValues synthesized = duplicateRecord(
                "SYSTRAN   ", "SYNTHESIZED ORIGIN", new BigDecimal("-5.67"));
        final String backupImage = render(backup);
        final String synthesizedImage = render(synthesized);

        writeFixedUnblockedDataset(BACKUP_DATASET, List.of(backupImage));
        writeFixedUnblockedDataset(SYNTHESIZED_DATASET, List.of(synthesizedImage));

        final JobExecution execution = launchByName(
                CombineTransactionsJobConfig.JOB_NAME, new JobParameters());
        assertThat(execution.getStatus())
                .as("the second insert of one business key is a keyed-load failure")
                .isEqualTo(BatchStatus.FAILED);
        assertThat(execution.getStepExecutions())
                .extracting(StepExecution::getStepName, StepExecution::getStatus)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                CombineTransactionsJobConfig.ORDER_STEP_NAME,
                                BatchStatus.COMPLETED),
                        org.assertj.core.groups.Tuple.tuple(
                                CombineTransactionsJobConfig.LOAD_STEP_NAME,
                                BatchStatus.FAILED));

        // The submission did not complete, so its local generation is the abnormal disposition of an
        // allocation the stream did not end normally with and is discarded rather than left behind
        // (DL-211). What the ordering step wrote is therefore asserted through the one row the load step
        // did commit before the duplicate was refused: backup-first stable ordering means the BACKUP
        // image is the survivor, which the retained record below is.
        assertThat(StagedGenerationStore.generationPath(stagingDirectory(),
                CombineTransactionsJobConfig.COMBINED_DATASET_BASE, execution.getId()))
                .as("a failed submission leaves no local generation for anything to read, publish or"
                        + " prune")
                .doesNotExist();

        assertThat(this.transactionRepository.count())
                .as("record-at-a-time loading commits the first key before the duplicate is refused")
                .isEqualTo(1L);
        final Transaction retained = this.transactionRepository.findById(SHARED_TRANSACTION_ID)
                .orElseThrow(() -> new AssertionError(
                        "the backup-origin record was not retained under its supplied identifier"));
        assertThat(render(RecordValues.from(retained))).isEqualTo(backupImage);
        assertThat(retained.getTranId()).isEqualTo(SHARED_TRANSACTION_ID);
        assertStoredAmountContract(retained.getTranAmt());
    }

    @Test
    @Order(4)
    @DisplayName("the production path uses repository code without process, native-query, or reflection")
    void productionPathKeepsTheModuleWideNegativeBoundaries() throws IOException {
        assertThat(PRODUCTION_SOURCE_ROOT)
                .as("the production Java source root required for the negative audit")
                .isDirectory();
        final List<Path> javaSources;
        try (Stream<Path> paths = Files.walk(PRODUCTION_SOURCE_ROOT)) {
            javaSources = paths
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                    .sorted()
                    .toList();
        }
        assertThat(javaSources).isNotEmpty();

        final StringBuilder production = new StringBuilder();
        for (final Path source : javaSources) {
            production.append(Files.readString(source, StandardCharsets.UTF_8));
        }
        assertThat(production)
                .doesNotContain(
                        "Runtime" + ".getRuntime",
                        "Process" + "Builder",
                        "exec" + "(",
                        "createNative" + "Query",
                        "java.lang." + "reflect",
                        "Class" + ".forName");

        final String configuration = Files.readString(
                COMBINE_CONFIGURATION_SOURCE, StandardCharsets.UTF_8);
        assertThat(configuration)
                .contains(
                        "private static final Comparator<Transaction> TRAN_ID_ASCENDING",
                        "Comparator.comparing(Transaction::getTranId)",
                        "transactionRepository.insertAndFlush(record)")
                .doesNotContain(
                        "public static final Comparator<Transaction> TRAN_ID_ASCENDING",
                        "protected static final Comparator<Transaction> TRAN_ID_ASCENDING",
                        "Long.parseLong",
                        "BigInteger");
    }

    @Test
    @Order(5)
    @DisplayName("a configured name with no exact staged file resolves to the highest local generation "
            + "of that base, through the real generation store and the real trust check")
    void aConfiguredBaseResolvesToItsHighestLocalGeneration() throws Exception {
        restoreSeededState();
        clearStagingDirectory();
        final String supersededImage = render(syntheticRecord(
                syntheticIdentifier(1L), "SUPERSEDED GENERATION", new BigDecimal("11.11")));
        final String currentFirstImage = render(syntheticRecord(
                syntheticIdentifier(2L), "CURRENT GENERATION FIRST", new BigDecimal("22.22")));
        final String currentSecondImage = render(syntheticRecord(
                syntheticIdentifier(3L), "CURRENT GENERATION SECOND", new BigDecimal("33.33")));
        final String synthesizedImage = render(syntheticRecord(
                syntheticIdentifier(4L), "EXACT STAGED NAME", new BigDecimal("44.44")));

        // No file is written under the configured name itself, so the exact-name rung has nothing to
        // find and resolution has to reach the generation scan. Two generations are staged so that
        // "current" is a choice rather than the only candidate.
        writeLocalGeneration(BACKUP_DATASET, 2L, List.of(supersededImage));
        writeLocalGeneration(BACKUP_DATASET, 5L, List.of(currentFirstImage, currentSecondImage));
        writeFixedUnblockedDataset(SYNTHESIZED_DATASET, List.of(synthesizedImage));
        assertThat(stagingPath(BACKUP_DATASET))
                .as("the exact configured name must be absent, or the generation scan is never reached")
                .doesNotExist();

        this.transactionRepository.deleteAllInBatch();
        final JobExecution execution = launchByName(
                CombineTransactionsJobConfig.JOB_NAME, new JobParameters());

        assertCompletedCombineExecution(execution, 3L);
        final List<String> loaded = this.transactionRepository
                .findAll(Sort.by(Sort.Direction.ASC, "tranId")).stream()
                .map(RecordValues::from)
                .map(CombineTransactionsJobConfigIT::render)
                .toList();
        assertThat(loaded)
                .as("the current generation of the base is read, the superseded one is not, and the"
                        + " second input still resolves by its exact staged name")
                .containsExactly(currentFirstImage, currentSecondImage, synthesizedImage);
        assertThat(loaded)
                .as("a generation the store has superseded contributes no record")
                .doesNotContain(supersededImage);

        final Path combinedGeneration = StagedGenerationStore.generationPath(stagingDirectory(),
                CombineTransactionsJobConfig.COMBINED_DATASET_BASE, execution.getId());
        assertThat(combinedRecordImages(
                Files.readString(combinedGeneration, StandardCharsets.US_ASCII), 3))
                .as("the sealed generation carries the same three records in ascending identifier order")
                .containsExactly(currentFirstImage, currentSecondImage, synthesizedImage);
    }

    @Test
    @Order(6)
    @DisplayName("equal identifiers keep backup-first order when the ordering pass spills into several "
            + "runs and merges them, not only when both records fit inside one run")
    void equalIdentifiersRetainConcatenationOrderAcrossSpilledRuns() throws Exception {
        restoreSeededState();
        clearStagingDirectory();
        final int runSize = ExternalStringSorter.DEFAULT_RECORDS_PER_RUN;
        // Sized so that the concatenation spills into three runs and one merge draws from all three:
        // the backup stream alone exceeds one run, and the synthesized stream both completes the second
        // run and opens the third.
        final int backupRecords = runSize + 88;
        final int synthesizedRecords = runSize - 12;
        final int totalRecords = backupRecords + synthesizedRecords;
        assertThat((totalRecords + runSize - 1) / runSize)
                .as("the ordering pass must spill into three runs for this case to mean anything")
                .isEqualTo(3);

        // The shared identifier is low in the key range but late in the second stream, which is the
        // combination that puts the two equal records in the first and the third run. A merge that
        // ignored which run a head came from could therefore serve them in either order.
        final int backupPositionOfSharedKey = 5;
        final int synthesizedPositionOfSharedKey = synthesizedRecords - 50;
        assertThat(backupRecords + synthesizedPositionOfSharedKey)
                .as("the second copy of the shared identifier must be added after the second run closes")
                .isGreaterThanOrEqualTo(2 * runSize);
        final String sharedIdentifier =
                syntheticIdentifier(backupKeyOrdinal(backupPositionOfSharedKey));

        final List<String> backupImages = new ArrayList<>(backupRecords);
        for (int position = 0; position < backupRecords; position++) {
            backupImages.add(render(syntheticRecord(
                    syntheticIdentifier(backupKeyOrdinal(position)),
                    "BACKUP ORIGIN " + position, new BigDecimal("12.34"))));
        }
        final List<String> synthesizedImages = new ArrayList<>(synthesizedRecords);
        for (int position = 0; position < synthesizedRecords; position++) {
            final String identifier = position == synthesizedPositionOfSharedKey
                    ? sharedIdentifier
                    : syntheticIdentifier(synthesizedKeyOrdinal(position));
            synthesizedImages.add(render(syntheticRecord(
                    identifier, "SYNTHESIZED ORIGIN " + position, new BigDecimal("-5.67"))));
        }
        final String backupCopyOfSharedKey = backupImages.get(backupPositionOfSharedKey);
        final String synthesizedCopyOfSharedKey =
                synthesizedImages.get(synthesizedPositionOfSharedKey);
        assertThat(slice(backupCopyOfSharedKey, IDENTIFIER)).isEqualTo(sharedIdentifier);
        assertThat(slice(synthesizedCopyOfSharedKey, IDENTIFIER)).isEqualTo(sharedIdentifier);
        assertThat(synthesizedCopyOfSharedKey)
                .as("the two equal-key records must be distinguishable by content")
                .isNotEqualTo(backupCopyOfSharedKey);

        writeFixedUnblockedDataset(BACKUP_DATASET, backupImages);
        writeFixedUnblockedDataset(SYNTHESIZED_DATASET, synthesizedImages);
        assertFixedUnblockedDataset(stagingPath(BACKUP_DATASET), backupRecords);
        assertFixedUnblockedDataset(stagingPath(SYNTHESIZED_DATASET), synthesizedRecords);
        this.transactionRepository.deleteAllInBatch();

        final JobExecution execution = launchByName(
                CombineTransactionsJobConfig.JOB_NAME, new JobParameters());

        assertThat(execution.getStatus())
                .as("the ordering pass completes over every run; the second insert of one business key"
                        + " is what fails")
                .isEqualTo(BatchStatus.FAILED);
        assertThat(execution.getStepExecutions())
                .extracting(StepExecution::getStepName, StepExecution::getStatus)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                CombineTransactionsJobConfig.ORDER_STEP_NAME,
                                BatchStatus.COMPLETED),
                        org.assertj.core.groups.Tuple.tuple(
                                CombineTransactionsJobConfig.LOAD_STEP_NAME,
                                BatchStatus.FAILED));

        // Every identifier below the shared one, then the shared one once: the count is what the
        // ordering established, because record-at-a-time loading commits in generation order and stops
        // at the first refusal.
        final long lowerIdentifiers = backupPositionOfSharedKey + synthesizedRecordsBelow(
                sharedIdentifier, synthesizedImages, synthesizedPositionOfSharedKey);
        assertThat(this.transactionRepository.count())
                .as("the committed prefix is every lower identifier followed by the shared one, which"
                        + " pins where the ordering placed the equal-key group")
                .isEqualTo(lowerIdentifiers + 1L);

        final Transaction retained = this.transactionRepository.findById(sharedIdentifier)
                .orElseThrow(() -> new AssertionError(
                        "no record was retained under the shared identifier " + sharedIdentifier));
        assertThat(render(RecordValues.from(retained)))
                .as("the backup copy is the survivor, so the record added in the first run still"
                        + " precedes the equal-key record added in the third")
                .isEqualTo(backupCopyOfSharedKey);
        assertThat(render(RecordValues.from(retained)))
                .isNotEqualTo(synthesizedCopyOfSharedKey);
        assertStoredAmountContract(retained.getTranAmt());

        assertThat(StagedGenerationStore.generationPath(stagingDirectory(),
                CombineTransactionsJobConfig.COMBINED_DATASET_BASE, execution.getId()))
                .as("a failed submission leaves no local generation behind (DL-211)")
                .doesNotExist();
    }

    /**
     * Counts the records of the second stream whose identifier sorts below the shared one.
     *
     * @param  sharedIdentifier   the identifier both streams carry
     * @param  synthesizedImages  the second stream, in stream order
     * @param  sharedPosition     the position the shared identifier occupies in that stream
     * @return the number of second-stream records the ordering places before the shared group
     */
    private static long synthesizedRecordsBelow(final String sharedIdentifier,
            final List<String> synthesizedImages, final int sharedPosition) {
        long below = 0L;
        for (int position = 0; position < synthesizedImages.size(); position++) {
            if (position != sharedPosition
                    && slice(synthesizedImages.get(position), IDENTIFIER)
                            .compareTo(sharedIdentifier) < 0) {
                below++;
            }
        }
        return below;
    }

    /** The key ordinal of one first-stream position: the even ordinals, so the streams interleave. */
    private static long backupKeyOrdinal(final int position) {
        return SYNTHETIC_KEY_ORIGIN + 2L * position;
    }

    /** The key ordinal of one second-stream position: the odd ordinals. */
    private static long synthesizedKeyOrdinal(final int position) {
        return SYNTHETIC_KEY_ORIGIN + 2L * position + 1L;
    }

    /**
     * Renders a synthetic transaction identifier at the layout's sixteen digits.
     *
     * <p>The prefix keeps every synthetic identifier clear of the two reserved ones this specification
     * already uses, so a synthetic record can never collide with the posted or the equal-key fixture.
     *
     * @param  ordinal the identifier's ordinal within the synthetic range
     * @return the identifier, sixteen digits
     */
    private static String syntheticIdentifier(final long ordinal) {
        return String.format(Locale.ROOT, "%s%012d", SYNTHETIC_IDENTIFIER_PREFIX, ordinal);
    }

    /**
     * Returns a complete synthetic record, distinguished only by identifier and description.
     *
     * @param  identifier  the sixteen-digit identifier
     * @param  description the origin marker carried in the description field
     * @param  amount      the signed amount, exercising the independent overpunch encoder
     * @return complete record values, ready to render
     */
    private static RecordValues syntheticRecord(final String identifier, final String description,
            final BigDecimal amount) {
        return new RecordValues(
                identifier,
                "01",
                "0001",
                "POS TERM  ",
                description,
                amount,
                "000123456",
                "SPILL SCALE MERCHANT",
                "SEATTLE",
                "98101-0001",
                SEEDED_CARD_NUMBER,
                "2022-06-10 19:27:53.000000",
                "2022-06-10-19.27.53.000000");
    }

    /**
     * Writes one local generation of a logical base, named by the store rather than by this class.
     *
     * <p>The name is composed through {@link StagedGenerationStore#generationPath} so that a case cannot
     * pass by agreeing with a spelling this specification invented: if the store's naming changed, this
     * fixture would follow it and the resolution it exercises would still be the production one.
     *
     * @param  base       the logical generation base, which is also the configured location
     * @param  generation the generation number
     * @param  images     the record images the generation holds, in dataset order
     * @throws IOException if the generation cannot be written
     */
    private static void writeLocalGeneration(final String base, final long generation,
            final List<String> images) throws IOException {
        Files.createDirectories(stagingDirectory());
        final Path generationPath =
                StagedGenerationStore.generationPath(stagingDirectory(), base, generation);
        final Path generationName = Objects.requireNonNull(generationPath.getFileName(),
                "a staged generation is always named");
        writeFixedUnblockedDataset(generationName.toString(), images);
        assertFixedUnblockedDataset(generationPath, images.size());
    }

    /**
     * Runs the two required predecessor jobs and stages their outputs under the combine job's configured
     * logical names.
     *
     * @return independently rendered backup and synthesized images
     * @throws Exception if either setup job or any staged-file operation fails
     */
    private PreparedInputs prepareInputsThroughPostingAndInterest() throws Exception {
        final String postingImage = render(postingRecord());
        writePostingDataset(postingImage);

        final JobExecution posting =
                launchByName(PostTransactionJobConfig.JOB_NAME, new JobParameters());
        assertThat(posting.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        final List<Transaction> posted = this.transactionRepository.findAll(
                Sort.by(Sort.Direction.ASC, "tranId"));
        assertThat(posted)
                .as("the setup posting input contributes exactly one archived transaction")
                .singleElement()
                .extracting(Transaction::getTranId)
                .isEqualTo(POSTED_TRANSACTION_ID);
        final List<String> backupImages = posted.stream()
                .map(RecordValues::from)
                .map(CombineTransactionsJobConfigIT::render)
                .toList();
        writeFixedUnblockedDataset(BACKUP_DATASET, backupImages);

        final JobParameters interestParameters = new JobParametersBuilder()
                .addString(InterestCalculationJobConfig.PARM_DATE_KEY, PINNED_INTEREST_DATE)
                .toJobParameters();
        final JobExecution interest =
                launchByName(InterestCalculationJobConfig.JOB_NAME, interestParameters);
        assertThat(interest.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        final Long interestExecutionId = Objects.requireNonNull(
                interest.getId(), "the completed interest execution has no identifier");
        final Path interestGeneration =
                this.interestConfiguration.transactGeneration(interestExecutionId.longValue());
        assertThat(interestGeneration)
                .as("the setup interest generation expected at %s", interestGeneration)
                .isRegularFile();

        final List<String> rawSynthesized = readFixedUnblockedDataset(interestGeneration);
        assertThat(rawSynthesized)
                .as("one synthesized row is written for each of the fifty seeded balances")
                .hasSize(50);
        final List<RecordValues> synthesizedValues = rawSynthesized.stream()
                .map(RecordValues::fromImage)
                .toList();
        assertThat(synthesizedValues)
                .extracting(RecordValues::identifier)
                .allSatisfy(identifier ->
                        assertThat(identifier).startsWith(PINNED_INTEREST_DATE));
        assertThat(synthesizedValues)
                .extracting(RecordValues::amount)
                .anySatisfy(amount -> assertThat(amount).isGreaterThan(BigDecimal.ZERO));

        final List<String> synthesizedImages = synthesizedValues.stream()
                .map(CombineTransactionsJobConfigIT::render)
                .toList();
        writeFixedUnblockedDataset(SYNTHESIZED_DATASET, synthesizedImages);

        assertFixedUnblockedDataset(stagingPath(BACKUP_DATASET), backupImages.size());
        assertFixedUnblockedDataset(
                stagingPath(SYNTHESIZED_DATASET), synthesizedImages.size());
        return new PreparedInputs(backupImages, synthesizedImages);
    }

    /**
     * Launches one registered job by name after adding only the server-owned run identity to the
     * caller's closed parameter set.
     *
     * @param jobName registered job name
     * @param callerParameters allow-listed caller parameters
     * @return completed or failed execution returned by the synchronous operator
     * @throws Exception if registration, validation or launch fails
     */
    private JobExecution launchByName(final String jobName, final JobParameters callerParameters)
            throws Exception {
        final Job registered = this.jobRegistry.getJob(jobName);
        assertThat(registered.getName()).isEqualTo(jobName);
        assertThat(registered.getJobParametersIncrementer()).isNotNull();
        assertThat(callerParameters.getParameters()).doesNotContainKey(RUN_ID_PARAMETER);

        final JobParameters advanced =
                this.runIncrementer.getNext(previousParameters(jobName));
        final Long runId = advanced.getLong(RUN_ID_PARAMETER);
        assertThat(runId)
                .as("the shared incrementer must mint a positive server run identity")
                .isNotNull()
                .isPositive();

        final JobParameters launchParameters = new JobParametersBuilder(callerParameters)
                .addLong(RUN_ID_PARAMETER, runId, true)
                .toJobParameters();
        final Properties properties = JOB_PARAMETERS_CONVERTER.getProperties(launchParameters);
        final Long executionId = this.jobOperator.start(jobName, properties);
        return Objects.requireNonNull(
                this.jobExplorer.getJobExecution(executionId),
                "the operator returned an execution identifier absent from the job repository");
    }

    /**
     * Returns the latest parameter set for one registered name, or an empty set before its first run.
     *
     * @param jobName registered job name
     * @return latest parameters or an empty set
     */
    private JobParameters previousParameters(final String jobName) {
        final JobInstance instance = this.jobExplorer.getLastJobInstance(jobName);
        if (instance == null) {
            return new JobParameters();
        }
        final JobExecution execution = this.jobExplorer.getLastJobExecution(instance);
        return execution == null ? new JobParameters() : execution.getJobParameters();
    }

    /**
     * Asserts both ungated steps completed and transferred the full arithmetic row count.
     *
     * @param execution combine execution
     * @param expectedRows sum of both input streams
     */
    private static void assertCompletedCombineExecution(
            final JobExecution execution, final long expectedRows) {
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(execution.getStepExecutions())
                .hasSize(2)
                .allSatisfy(step -> {
                    assertThat(step.getStatus()).isEqualTo(BatchStatus.COMPLETED);
                    assertThat(step.getReadCount()).isEqualTo(expectedRows);
                    assertThat(step.getWriteCount()).isEqualTo(expectedRows);
                    assertThat(step.getFilterCount()).isZero();
                });
        assertThat(execution.getStepExecutions())
                .extracting(StepExecution::getStepName)
                .containsExactly(
                        CombineTransactionsJobConfig.ORDER_STEP_NAME,
                        CombineTransactionsJobConfig.LOAD_STEP_NAME);
    }

    /**
     * Confirms that the only execution parameter is the framework-owned run identity.
     *
     * @param execution combine execution
     */
    private static void assertNoCallerDateParameter(final JobExecution execution) {
        assertThat(execution.getJobParameters().getParameters().keySet())
                .containsExactly(RUN_ID_PARAMETER)
                .allSatisfy(key ->
                        assertThat(key.toLowerCase(Locale.ROOT)).doesNotContain("date"));
    }

    /** Confirms the framework exposed batch-step observations as timers, without asserting a target. */
    private void assertBatchStepTimersPresent() {
        final List<Meter.Id> stepTimers = this.meterRegistry.getMeters().stream()
                .map(Meter::getId)
                .filter(identifier -> "spring.batch.step".equals(identifier.getName()))
                .toList();
        assertThat(stepTimers)
                .as("the framework's batch-step observations are present")
                .isNotEmpty()
                .allSatisfy(identifier ->
                        assertThat(identifier.getType()).isEqualTo(Meter.Type.TIMER));
    }

    /**
     * Writes the one newline-delimited daily-transaction image consumed by the posting setup job.
     *
     * @param image complete 350-byte daily-transaction image
     * @throws IOException if the input cannot be staged
     */
    private static void writePostingDataset(final String image) throws IOException {
        assertAsciiWidth(image, RECORD_BYTES, "posting input record");
        Files.createDirectories(stagingDirectory());
        Files.writeString(
                stagingPath(POSTING_DATASET), image + '\n', StandardCharsets.US_ASCII);
    }

    /**
     * Writes one fixed-unblocked dataset with no delimiter between its record images.
     *
     * @param logicalName configured logical dataset name
     * @param images record images in physical input order
     * @throws IOException if the dataset cannot be staged
     */
    private static void writeFixedUnblockedDataset(
            final String logicalName, final List<String> images) throws IOException {
        Files.createDirectories(stagingDirectory());
        final StringBuilder dataset = new StringBuilder(images.size() * RECORD_BYTES);
        for (final String image : images) {
            assertAsciiWidth(image, RECORD_BYTES, logicalName + " record");
            dataset.append(image);
        }
        Files.writeString(
                stagingPath(logicalName), dataset.toString(), StandardCharsets.US_ASCII);
    }

    /**
     * Reads a fixed-unblocked dataset in exact 350-byte units.
     *
     * @param path dataset path
     * @return immutable record-image list
     * @throws IOException if the dataset cannot be read
     */
    private static List<String> readFixedUnblockedDataset(final Path path) throws IOException {
        assertThat(path).as("required fixed-unblocked dataset at %s", path).isRegularFile();
        final byte[] content = Files.readAllBytes(path);
        assertThat(content.length % RECORD_BYTES)
                .as("encoded dataset bytes at %s form complete records", path)
                .isZero();
        final List<String> images = new ArrayList<>(content.length / RECORD_BYTES);
        for (int offset = 0; offset < content.length; offset += RECORD_BYTES) {
            images.add(new String(content, offset, RECORD_BYTES, StandardCharsets.US_ASCII));
        }
        return List.copyOf(images);
    }

    /**
     * Asserts a staged input contains exactly the expected number of fixed-unblocked records.
     *
     * @param path staged dataset
     * @param recordCount expected record count
     * @throws IOException if the dataset cannot be read
     */
    private static void assertFixedUnblockedDataset(
            final Path path, final int recordCount) throws IOException {
        assertThat(path).as("configured input dataset at %s", path).isRegularFile();
        final byte[] bytes = Files.readAllBytes(path);
        assertThat(bytes).hasSize(recordCount * RECORD_BYTES);
        assertThat(bytes.length % RECORD_BYTES).isZero();
    }

    /**
     * Separates the local combined file into logical record images while verifying every explicit
     * line-feed boundary.
     *
     * @param content captured local file content
     * @param expectedRecords expected logical record count
     * @return record images without their local separators
     */
    private static List<String> combinedRecordImages(
            final String content, final int expectedRecords) {
        final int encodedLength = content.getBytes(StandardCharsets.US_ASCII).length;
        assertThat(encodedLength).isEqualTo(expectedRecords * LOCAL_RECORD_BYTES);
        assertThat(new String(
                content.getBytes(StandardCharsets.US_ASCII), StandardCharsets.US_ASCII))
                .as("the combined local file contains US-ASCII only")
                .isEqualTo(content);
        assertThat(content)
                .as("and it carries no record separator at all, so a consumer frames it by width")
                .doesNotContain("\n");

        final List<String> records = new ArrayList<>(expectedRecords);
        for (int index = 0; index < expectedRecords; index++) {
            final int start = index * LOCAL_RECORD_BYTES;
            final String image = content.substring(start, start + RECORD_BYTES);
            assertAsciiWidth(image, RECORD_BYTES, "combined record " + index);
            records.add(image);
        }
        return List.copyOf(records);
    }

    /**
     * Asserts the record-only portion of an artifact is an exact encoded multiple of 350.
     *
     * @param images logical record images
     */
    private static void assertImagePayloadIsARecordMultiple(final List<String> images) {
        final String payload = String.join("", images);
        final int encodedLength = payload.getBytes(StandardCharsets.US_ASCII).length;
        assertThat(encodedLength)
                .isEqualTo(images.size() * RECORD_BYTES);
        assertThat(encodedLength % RECORD_BYTES).isZero();
        images.forEach(image ->
                assertAsciiWidth(image, RECORD_BYTES, "combined logical record"));
    }

    /**
     * Compares every documented field slice without removing padding.
     *
     * @param expected independently rendered source image
     * @param actual reloaded or captured image
     */
    private static void assertRoundTripByDocumentedOffsets(
            final String expected, final String actual) {
        assertAsciiWidth(expected, RECORD_BYTES, "expected transaction image");
        assertAsciiWidth(actual, RECORD_BYTES, "actual transaction image");
        for (final FieldSpan field : TRANSACTION_LAYOUT) {
            assertThat(slice(actual, field))
                    .as("%s survives unchanged at offset %s width %s",
                            field.name(), field.offset(), field.width())
                    .isEqualTo(slice(expected, field));
        }
    }

    /**
     * Returns one exact field image after validating the enclosing record width.
     *
     * @param image complete transaction image
     * @param field field span
     * @return untrimmed field image
     */
    private static String slice(final String image, final FieldSpan field) {
        assertAsciiWidth(image, RECORD_BYTES, "transaction image");
        return image.substring(field.offset(), field.offset() + field.width());
    }

    /**
     * Confirms a stored monetary value already has the receiving field's scale and remains unchanged
     * under the estate's truncating mode.
     *
     * @param amount persisted amount
     */
    private static void assertStoredAmountContract(final BigDecimal amount) {
        assertThat(amount).isNotNull();
        assertThat(amount.scale()).isEqualTo(MONETARY_SCALE);
        assertThat(amount.divide(BigDecimal.ONE, MONETARY_SCALE, RoundingMode.DOWN))
                .isEqualByComparingTo(amount);
    }

    /**
     * Asserts a value's encoded US-ASCII width.
     *
     * @param value value to measure
     * @param width expected byte width
     * @param role assertion description
     */
    private static void assertAsciiWidth(
            final String value, final int width, final String role) {
        assertThat(value).as(role).isNotNull();
        assertThat(value.getBytes(StandardCharsets.US_ASCII).length)
                .as("%s encoded width", role)
                .isEqualTo(width);
        assertThat(new String(
                value.getBytes(StandardCharsets.US_ASCII), StandardCharsets.US_ASCII))
                .as("%s contains US-ASCII only", role)
                .isEqualTo(value);
    }

    /**
     * Returns the deterministic daily transaction consumed by the posting setup run.
     *
     * @return complete field values before fixed-width rendering
     */
    private static RecordValues postingRecord() {
        return new RecordValues(
                POSTED_TRANSACTION_ID,
                "01",
                "0001",
                "POS TERM  ",
                "COMBTRAN SETUP PURCHASE",
                new BigDecimal("1.00"),
                "800000000",
                "SETUP MERCHANT",
                "SEATTLE",
                "98101",
                SEEDED_CARD_NUMBER,
                "2022-06-10 19:27:53.000000",
                " ".repeat(26));
    }

    /**
     * Returns one side of the deliberate equal-key pair.
     *
     * @param source origin marker carried in the ten-character source field
     * @param description origin marker carried in the description
     * @param amount signed amount used to exercise the independent overpunch encoder
     * @return complete equal-key record values
     */
    private static RecordValues duplicateRecord(
            final String source, final String description, final BigDecimal amount) {
        return new RecordValues(
                SHARED_TRANSACTION_ID,
                "01",
                "0001",
                source,
                description,
                amount,
                "000123456",
                "EQUAL KEY MERCHANT",
                "SEATTLE",
                "98101-0001",
                SEEDED_CARD_NUMBER,
                "2022-06-10 19:27:53.000000",
                "2022-06-10-19.27.53.000000");
    }

    /**
     * Independently renders all thirteen business fields and the trailing filler.
     *
     * @param values field values
     * @return complete 350-byte record image
     */
    private static String render(final RecordValues values) {
        final StringBuilder image = new StringBuilder(RECORD_BYTES);
        image.append(digits(values.identifier(), IDENTIFIER.width()));
        image.append(digits(values.typeCode(), TYPE_CODE.width()));
        image.append(digits(values.categoryCode(), CATEGORY_CODE.width()));
        image.append(alphanumeric(values.source(), SOURCE.width()));
        image.append(alphanumeric(values.description(), DESCRIPTION.width()));
        image.append(encodeAmount(values.amount()));
        image.append(digits(values.merchantId(), MERCHANT_ID.width()));
        image.append(alphanumeric(values.merchantName(), MERCHANT_NAME.width()));
        image.append(alphanumeric(values.merchantCity(), MERCHANT_CITY.width()));
        image.append(alphanumeric(values.merchantZip(), MERCHANT_ZIP.width()));
        image.append(digits(values.cardNumber(), CARD_NUMBER.width()));
        image.append(alphanumeric(values.originalTimestamp(), ORIGINAL_TIMESTAMP.width()));
        image.append(alphanumeric(values.processingTimestamp(), PROCESSING_TIMESTAMP.width()));
        image.append(" ".repeat(TRAILING_FILLER.width()));

        final String rendered = image.toString();
        assertAsciiWidth(rendered, RECORD_BYTES, "independently rendered transaction image");
        return rendered;
    }

    /**
     * Renders an alphanumeric field with space fill and right truncation.
     *
     * @param value source value
     * @param width receiving width
     * @return exact-width field image
     */
    private static String alphanumeric(final String value, final int width) {
        final String rendered =
                String.format(Locale.ROOT, "%-" + width + "." + width + "s", value);
        assertAsciiWidth(rendered, width, "alphanumeric field");
        return rendered;
    }

    /**
     * Renders a digit field with leading zero fill.
     *
     * @param value source digits
     * @param width receiving width
     * @return exact-width digit image
     */
    private static String digits(final String value, final int width) {
        assertThat(value).matches("[0-9]+");
        final String rendered =
                String.format(Locale.ROOT, "%1$" + width + "s", value).replace(' ', '0');
        assertAsciiWidth(rendered, width, "digit field");
        return rendered;
    }

    /**
     * Encodes an exact scale-two amount into the eleven-byte signed zoned field.
     *
     * @param amount exact decimal amount
     * @return independently overpunched field image
     */
    private static String encodeAmount(final BigDecimal amount) {
        assertStoredAmountContract(amount);
        final BigInteger cents = amount.movePointRight(MONETARY_SCALE).toBigIntegerExact();
        final String magnitude =
                String.format(Locale.ROOT, "%011d", cents.abs());
        assertAsciiWidth(magnitude, AMOUNT.width(), "amount magnitude");

        final int finalDigit = Character.digit(magnitude.charAt(AMOUNT.width() - 1), 10);
        assertThat(finalDigit).isBetween(0, 9);
        final String alphabet =
                amount.signum() < 0 ? NEGATIVE_OVERPUNCH : POSITIVE_OVERPUNCH;
        final String encoded = magnitude.substring(0, AMOUNT.width() - 1)
                + alphabet.charAt(finalDigit);
        assertAsciiWidth(encoded, AMOUNT.width(), "overpunched amount");
        return encoded;
    }

    /**
     * Decodes the independently observed overpunch so an upstream setup image can be independently
     * re-rendered before it becomes an expectation.
     *
     * @param image complete transaction image
     * @return exact scale-two amount
     */
    private static BigDecimal decodeAmount(final String image) {
        final String field = slice(image, AMOUNT);
        final char overpunch = field.charAt(AMOUNT.width() - 1);
        int digit = POSITIVE_OVERPUNCH.indexOf(overpunch);
        boolean negative = false;
        if (digit < 0) {
            digit = NEGATIVE_OVERPUNCH.indexOf(overpunch);
            negative = true;
        }
        assertThat(digit)
                .as("the amount's final byte is a recognised signed overpunch")
                .isBetween(0, 9);

        final String magnitude = field.substring(0, AMOUNT.width() - 1) + digit;
        assertThat(magnitude).matches("[0-9]{11}");
        final String decimal =
                magnitude.substring(0, 9) + "." + magnitude.substring(9);
        final BigDecimal decoded = new BigDecimal((negative ? "-" : "") + decimal);
        assertStoredAmountContract(decoded);
        return decoded;
    }

    /**
     * Returns this run's own configured staging directory, which is the very path the context was given.
     *
     * @return normalized local staging directory, private to this run
     */
    private static Path stagingDirectory() {
        return IsolatedStagingRoot.forSpecification(STAGING_LABEL);
    }

    /**
     * Binds the staging directory to a root private to this process, before the context is created.
     *
     * <p>A property callback rather than an entry in the annotation above, because the value cannot be a
     * compile-time constant: it carries the process identifier so that no other run and no sibling clone
     * resolves the same absolute path.
     *
     * @param registry the registry the framework supplies
     */
    @DynamicPropertySource
    static void registerIsolatedStagingDirectory(final DynamicPropertyRegistry registry) {
        registry.add(StagedGenerationStore.SHARED_STAGING_DIRECTORY_PROPERTY,
                () -> IsolatedStagingRoot.pathFor(STAGING_LABEL));
    }

    /**
     * Resolves one deployment-owned logical name under the configured staging directory.
     *
     * @param logicalName simple dataset name
     * @return resolved local path
     */
    private static Path stagingPath(final String logicalName) {
        return stagingDirectory().resolve(logicalName);
    }

    /**
     * Removes only this specification's private staging namespace.
     *
     * <p>"Private" is now a property of the path rather than a claim about it: the root carries this
     * process's own namespace, so the removal cannot reach a file another run or another clone staged. It
     * previously named a directory every one of them shared.
     */
    private static void clearStagingDirectory() {
        IsolatedStagingRoot.discard(stagingDirectory());
    }

    /**
     * One documented fixed-width field span.
     *
     * @param name assertion label
     * @param offset zero-based offset
     * @param width encoded width
     */
    private record FieldSpan(String name, int offset, int width) {
    }

    /**
     * The thirteen business fields shared by the daily and posted transaction layouts.
     *
     * @param identifier transaction identifier
     * @param typeCode transaction type
     * @param categoryCode transaction category
     * @param source transaction source
     * @param description description
     * @param amount exact amount
     * @param merchantId merchant identifier
     * @param merchantName merchant name
     * @param merchantCity merchant city
     * @param merchantZip merchant postal code
     * @param cardNumber card number
     * @param originalTimestamp origination timestamp
     * @param processingTimestamp processing timestamp
     */
    private record RecordValues(
            String identifier,
            String typeCode,
            String categoryCode,
            String source,
            String description,
            BigDecimal amount,
            String merchantId,
            String merchantName,
            String merchantCity,
            String merchantZip,
            String cardNumber,
            String originalTimestamp,
            String processingTimestamp) {

        /**
         * Copies a persisted entity into the independent renderer's value carrier.
         *
         * @param transaction persisted transaction
         * @return complete field values
         */
        private static RecordValues from(final Transaction transaction) {
            return new RecordValues(
                    transaction.getTranId(),
                    transaction.getTranTypeCd(),
                    transaction.getTranCatCd(),
                    transaction.getTranSource(),
                    transaction.getTranDesc(),
                    transaction.getTranAmt(),
                    transaction.getMerchantId(),
                    transaction.getMerchantName(),
                    transaction.getMerchantCity(),
                    transaction.getMerchantZip(),
                    transaction.getTranCardNum(),
                    transaction.getTranOrigTs(),
                    transaction.getTranProcTs());
        }

        /**
         * Parses a setup image only through this specification's documented field spans.
         *
         * @param image complete image
         * @return complete field values
         */
        private static RecordValues fromImage(final String image) {
            assertAsciiWidth(image, RECORD_BYTES, "setup transaction image");
            return new RecordValues(
                    slice(image, IDENTIFIER),
                    slice(image, TYPE_CODE),
                    slice(image, CATEGORY_CODE),
                    slice(image, SOURCE),
                    slice(image, DESCRIPTION),
                    decodeAmount(image),
                    slice(image, MERCHANT_ID),
                    slice(image, MERCHANT_NAME),
                    slice(image, MERCHANT_CITY),
                    slice(image, MERCHANT_ZIP),
                    slice(image, CARD_NUMBER),
                    slice(image, ORIGINAL_TIMESTAMP),
                    slice(image, PROCESSING_TIMESTAMP));
        }
    }

    /**
     * The two staged combine inputs, retained separately so their concatenation order stays explicit.
     *
     * @param backupImages first input
     * @param synthesizedImages second input
     */
    private record PreparedInputs(
            List<String> backupImages, List<String> synthesizedImages) {

        private PreparedInputs {
            backupImages = List.copyOf(backupImages);
            synthesizedImages = List.copyOf(synthesizedImages);
        }

        /**
         * Returns a mutable copy in the physical concatenation order.
         *
         * @return backup images followed by synthesized images
         */
        private List<String> inConcatenationOrder() {
            final List<String> images =
                    new ArrayList<>(backupImages.size() + synthesizedImages.size());
            images.addAll(backupImages);
            images.addAll(synthesizedImages);
            return images;
        }
    }

    /**
     * Narrow context containing the three jobs, their real persistence collaborators and deterministic
     * external boundaries.
     */
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = PrometheusExemplarsAutoConfiguration.class)
    @Import({
            CombineTransactionsJobConfig.class,
            PostTransactionJobConfig.class,
            InterestCalculationJobConfig.class,
            BatchConfig.class,
            JobParameterValidators.class,
            FixedWidthFlatFileReaderFactory.class,
            StagedGenerationStore.class,
            AdvisoryGenerationPublicationLock.class,
            DateValidationService.class,
            InterestCalculationService.class,
            InterestGroupTransactionBoundary.class,
            TransactionPostingService.class,
            PostingRecordTransactionBoundary.class,
            RecordWriter.class,
            AbendService.class})
    @EnableConfigurationProperties(AwsProperties.class)
    @EnableJpaRepositories(basePackageClasses = TransactionRepository.class)
    @EntityScan(basePackageClasses = Transaction.class)
    static class JobContext {

        /**
         * Publishes the fixed clock shared by every date-sensitive setup collaborator.
         *
         * @return fixed deterministic clock
         */
        @Bean
        Clock fixedClock() {
            return FIXED_CLOCK;
        }

        /**
         * Supplies a local-fallback staging boundary.
         *
         * <p>A read boundary only. The combined generation is registered rather than uploaded by the
         * ordering step, so the local sealed file survives the step that wrote it and can be read
         * directly by this specification; nothing here needs to intercept a publication (DL-212).
         *
         * @return observable staging boundary
         */
        @Bean
        BatchStagingArea batchStagingArea() {
            final BatchStagingArea staging = mock(BatchStagingArea.class);
            when(staging.holds(anyString())).thenReturn(false);
            return staging;
        }

        /**
         * Keeps durable-generation publication real up to the external object-store boundary.
         *
         * @return object-store edge accepting uploads and reporting no prior generations
         */
        @Bean
        S3Operations objectStore() {
            final S3Operations objectStore = mock(S3Operations.class);
            when(objectStore.listObjects(anyString(), anyString())).thenReturn(List.of());
            return objectStore;
        }

        /**
         * Prevents unrelated terminal notification delivery from becoming a database-test dependency.
         *
         * @return inert notification edge
         */
        @Bean
        SnsOperations notifications() {
            return mock(SnsOperations.class);
        }
    }
}
