/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.carddemo.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.batch.BackupTransactionJobConfig;
import com.carddemo.batch.BatchLaunchCoordinator;
import com.carddemo.batch.BatchStagingArea;
import com.carddemo.batch.CombineTransactionsJobConfig;
import com.carddemo.batch.CreateStatementJobConfig;
import com.carddemo.batch.InterestCalculationJobConfig;
import com.carddemo.batch.JobParameterValidators;
import com.carddemo.batch.PostTransactionJobConfig;
import com.carddemo.batch.TransactionReportJobConfig;
import com.carddemo.batch.step.AdvisoryGenerationPublicationLock;
import com.carddemo.batch.step.FixedWidthFlatFileReaderFactory;
import com.carddemo.batch.step.StagedGenerationStore;
import com.carddemo.config.AwsConfig;
import com.carddemo.config.AwsProperties;
import com.carddemo.config.BatchConfig;
import com.carddemo.config.JpaAuditConfig;
import com.carddemo.domain.Transaction;
import com.carddemo.repository.RecordWriter;
import com.carddemo.repository.TransactionRepository;
import com.carddemo.service.AbendService;
import com.carddemo.service.DateValidationService;
import com.carddemo.service.InterestCalculationService;
import com.carddemo.service.InterestGroupTransactionBoundary;
import com.carddemo.service.PostingRecordTransactionBoundary;
import com.carddemo.service.SensitiveFieldEncryptionService;
import com.carddemo.service.StatementDataAccessService;
import com.carddemo.service.StatementGenerationService;
import com.carddemo.service.TransactionPostingService;
import com.carddemo.service.TransactionReportService;
import com.carddemo.support.AbstractPostgresAndLocalStackIT;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.autoconfigure.tracing.prometheus.PrometheusExemplarsAutoConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.autoconfigure.orm.jpa.JpaProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Gate 1 and Gate 4 of the migration plan, executed: the delivered pipeline is driven end to end from
 * the committed production-representative input, on a real PostgreSQL 16 server and a real object
 * store, and every artefact it produces is compared to its committed golden file on <strong>raw
 * bytes</strong>.
 *
 * <h2>Why this class exists</h2>
 * The module committed four golden files and, until this class, nothing compared any of them with
 * anything the code produced. Two suites opened them - {@code ExpectedOutputFixtureContractTest} and
 * {@code ExpectedHtmlStatementFixtureContractTest} - but both re-emit one record at a time through the
 * formatter that owns its layout, which proves a formatter and says nothing about a pipeline. A wrong
 * balance written by an accrual run, a value escaped on its way into an HTML cell, and a record
 * separator that no dataset definition allows are all invisible to a record-level comparison and all
 * three were shipped. This class is the assertion that catches them: one run, four whole files, byte
 * arrays compared with nothing in between.
 *
 * <h2>No mocked input or output anywhere</h2>
 * Gate 1 states that mocked I/O does not satisfy it, so nothing here is mocked. The relational store is
 * the containerised PostgreSQL 16 server the shared base migrates; the object store is the containerised
 * emulator the shared base provisions, with the versioned staging bucket the publication path writes
 * into; the sequential input is the estate's own committed sample data, staged on the filesystem as the
 * job's landing dataset; and every artefact asserted below is read back off the filesystem after the job
 * that composed it has closed it. The graph is assembled explicitly rather than by scanning, which
 * bounds it to the six jobs under test - that is a narrower <em>bean set</em>, not a substituted
 * boundary.
 *
 * <h2>The clock is pinned, and it has to be</h2>
 * The instant is fixed at {@value #PINNED_REPORT_INSTANT_TEXT}. Two separate things depend on it. Every
 * timestamp the accrual run writes into a synthesised interest transaction is read from the clock, so an
 * unpinned run writes a different image on every execution and no byte comparison is possible. And the
 * processing date those transactions carry is what the reporting window filters on - so a system clock
 * puts all fifty of them outside {@value #REPORT_START_DATE} to {@value #REPORT_END_DATE} and the report
 * silently comes out sixty-eight records short, having failed nothing. The pinned instant is the one the
 * committed goldens were produced under.
 *
 * <h2>The order of the six launches is not arbitrary</h2>
 * The accrual run does <strong>not</strong> write its synthesised transactions into the master; it writes
 * them to its own sequential dataset, exactly as the member it translates does. Only the consolidation
 * run merges that dataset into the master. So a report or a statement produced before the consolidation
 * is missing every interest transaction - which is not a small difference: it is fifty data records, the
 * page breaks they create, and fifty statement lines. The pipeline order asserted here is therefore the
 * plan's own order, and the two reading jobs run last.
 *
 * <h2>Shared state is restored</h2>
 * The server and the emulator are shared by every integration test in the run, and a batch job commits on
 * its own connections, so nothing here can be rolled back. The seeded state is restored before the first
 * test and again after the last one whatever the outcome, and the staging root this class binds is its
 * own directory, emptied before the run.
 *
 * <p>Provenance: checkout SHA {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. Dataset names, record widths and record counts
 * are metadata rather than source text, and no legacy source line is transcribed. See
 * {@code docs/decision-log.md} entries DL-213 and DL-219.
 */
@SpringBootTest(classes = BatchPipelineE2ETest.PipelineContext.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {"spring.flyway.enabled=false", "spring.main.banner-mode=off",
                "spring.jpa.hibernate.ddl-auto=none", "spring.batch.job.enabled=false",
                "management.endpoint.health.validate-group-membership=false",
                "management.tracing.enabled=false",
                // ONE property serves all six jobs: every job configuration falls back to the shared
                // staging root before it falls back to the platform temporary directory, so binding the
                // shared key is what makes the pipeline chain through one filesystem location exactly as
                // it does in the application. It is a fixed expression rather than a temporary directory
                // because the bindings are resolved while the context starts, which is before any
                // per-test directory could exist.
                "carddemo.batch.staging-directory="
                        + BatchPipelineE2ETest.STAGING_DIRECTORY_EXPRESSION})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("Gate 1 and Gate 4 executed: the committed input through the delivered pipeline, and all "
        + "four goldens compared byte for byte")
class BatchPipelineE2ETest extends AbstractPostgresAndLocalStackIT {

    // -----------------------------------------------------------------------------------------------
    // THE RUN'S OWN SETTINGS
    // -----------------------------------------------------------------------------------------------

    /** Staging root this specification owns, as the property expression the context binds. */
    static final String STAGING_DIRECTORY_EXPRESSION =
            "${java.io.tmpdir}/carddemo-batch-pipeline-e2e";

    /**
     * The instant the committed goldens were produced under, as text so it can annotate a Javadoc.
     *
     * <p>It falls inside the reporting window on purpose; see the class comment.
     */
    static final String PINNED_REPORT_INSTANT_TEXT = "2022-07-06T12:00:00Z";

    /** The pinned clock every collaborator in the graph reads. */
    private static final Clock PINNED_CLOCK =
            Clock.fixed(Instant.parse(PINNED_REPORT_INSTANT_TEXT), ZoneOffset.UTC);

    /** Inclusive lower bound of the reporting window, as the golden's own header states it. */
    static final String REPORT_START_DATE = "2022-01-01";

    /** Inclusive upper bound of the reporting window, as the golden's own header states it. */
    static final String REPORT_END_DATE = "2022-07-06";

    /**
     * The accrual run's date parameter, which is also the literal leading characters of every
     * transaction identifier the run synthesises - {@code 2022071800000050} and its forty-nine peers
     * appear in the committed report and statement goldens.
     */
    static final String INTEREST_PARM_DATE = "2022071800";

    // -----------------------------------------------------------------------------------------------
    // THE LEGACY NAMES AND FIGURES, RESTATED HERE RATHER THAN IMPORTED.
    //
    // Every dataset name, record width and record count below is written as its own literal. None is
    // read from the production class that also produces it, because an expectation that borrows the
    // subject's own constant proves only that the subject agrees with itself.
    // -----------------------------------------------------------------------------------------------

    /** The landing dataset the posting run reads sequentially. */
    private static final String LANDING_DATASET = "AWS.M2.CARDDEMO.DALYTRAN.PS";

    /** Logical generation base of the reject dataset. */
    private static final String REJECT_BASE = "AWS.M2.CARDDEMO.DALYREJS";

    /** Logical generation base of the transaction report. */
    private static final String REPORT_BASE = "AWS.M2.CARDDEMO.TRANREPT";

    /** Logical generation base of the text statement. */
    private static final String STATEMENT_BASE = "AWS.M2.CARDDEMO.STATEMNT.PS";

    /** Logical generation base of the HTML statement. */
    private static final String STATEMENT_HTML_BASE = "AWS.M2.CARDDEMO.STATEMNT.HTML";

    /** Reject record width: a 350-byte source image plus an 80-byte trailer. */
    private static final int REJECT_WIDTH = 430;

    /** Report record width, from the report dataset's declared record length. */
    private static final int REPORT_WIDTH = 133;

    /** Text statement record width. */
    private static final int STATEMENT_WIDTH = 80;

    /** HTML statement record width, from the step that runs the program rather than the delete step. */
    private static final int STATEMENT_HTML_WIDTH = 100;

    /** Records the committed sample input holds. */
    private static final int INPUT_RECORD_COUNT = 300;

    /** Records the posting run refuses, each producing one reject record. */
    private static final int REJECTED_RECORD_COUNT = 38;

    /** Records the posting run accepts and writes to the master. */
    private static final int POSTED_RECORD_COUNT = 262;

    /** Interest transactions the accrual run synthesises into its own sequential dataset. */
    private static final int SYNTHESISED_RECORD_COUNT = 50;

    /** Master rows after the consolidation merges the synthesised dataset into it. */
    private static final int CONSOLIDATED_RECORD_COUNT =
            POSTED_RECORD_COUNT + SYNTHESISED_RECORD_COUNT;

    /**
     * The bucket the emulator is provisioned with, restated here as its own literal so the assertion
     * that the publication path addresses it is not satisfied by reading the same field it checks.
     */
    private static final String PROVISIONED_STAGING_BUCKET = "carddemo-batch-staging";

    /** Class-path location of the committed sample input. */
    private static final String INPUT_FIXTURE = "/fixtures/input/dailytran.txt";

    /** Class-path directory holding the committed goldens. */
    private static final String GOLDEN_DIRECTORY = "/fixtures/expected/";

    /** The one byte that must not appear in any produced artefact. */
    private static final byte LINE_FEED = 0x0A;

    /**
     * The artefacts the run produced, keyed by logical base, captured by the first test so that each
     * later test asserts one golden and names it in its own failure.
     */
    private static final Map<String, byte[]> PRODUCED = new LinkedHashMap<>();

    /** The executions the run performed, in launch order, for the pipeline-status assertion. */
    private static final List<JobExecution> EXECUTIONS = new ArrayList<>();

    @Autowired
    private BatchLaunchCoordinator launcher;

    @Autowired
    private JobExplorer jobExplorer;

    /** Every registered job, keyed by bean name - which is the job name each configuration declares. */
    @Autowired
    private Map<String, Job> jobs;

    @Autowired
    private TransactionRepository transactions;

    @Autowired
    private AwsProperties awsProperties;

    /** Creates the specification. */
    BatchPipelineE2ETest() {
        super();
    }

    // -----------------------------------------------------------------------------------------------
    // LIFECYCLE
    // -----------------------------------------------------------------------------------------------

    /**
     * Empties this specification's staging root and returns the shared server to its seeded state.
     *
     * @throws IOException  if the staging root cannot be prepared
     * @throws SQLException if the seeded state cannot be restored
     */
    @BeforeAll
    static void prepareSharedState() throws IOException, SQLException {
        clearStagingRoot();
        restoreSeededState();
    }

    /**
     * Returns the shared server to its seeded state and removes what this run staged, whatever the
     * outcome of the tests above.
     *
     * @throws IOException  if the staging root cannot be emptied
     * @throws SQLException if the seeded state cannot be restored
     */
    @AfterAll
    static void restoreSharedState() throws IOException, SQLException {
        removeOwnJobInstances();
        restoreSeededState();
        clearStagingRoot();
        PRODUCED.clear();
        EXECUTIONS.clear();
    }

    /**
     * Removes the framework metadata rows this class's own launches created, and nothing else.
     *
     * <p>The metadata store is shared by every integration test in the run and no restore covers it, so
     * a specification that asserts how many instances of a job exist is asserting a figure this class
     * can move. Deletion is by the instance identifiers this class recorded - never by job name - so an
     * instance another specification created is untouched whichever order the two ran in.
     *
     * <p>The order of the six statements is the order the foreign keys require and is not
     * interchangeable.
     *
     * @throws SQLException if the metadata cannot be amended
     */
    private static void removeOwnJobInstances() throws SQLException {
        if (EXECUTIONS.isEmpty()) {
            return;
        }
        try (Connection connection =
                DriverManager.getConnection(jdbcUrl(), databaseUser(), databasePassword())) {
            connection.setAutoCommit(false);
            for (final JobExecution execution : EXECUTIONS) {
                final Long instanceId = execution.getJobInstance().getId();
                final Long executionId = execution.getId();
                if (instanceId == null || executionId == null) {
                    continue;
                }
                deleteBy(connection,
                        "DELETE FROM batch_step_execution_context WHERE step_execution_id IN "
                                + "(SELECT step_execution_id FROM batch_step_execution "
                                + "WHERE job_execution_id = ?)", executionId);
                deleteBy(connection,
                        "DELETE FROM batch_step_execution WHERE job_execution_id = ?", executionId);
                deleteBy(connection,
                        "DELETE FROM batch_job_execution_context WHERE job_execution_id = ?",
                        executionId);
                deleteBy(connection,
                        "DELETE FROM batch_job_execution_params WHERE job_execution_id = ?",
                        executionId);
                deleteBy(connection,
                        "DELETE FROM batch_job_execution WHERE job_execution_id = ?", executionId);
                deleteBy(connection,
                        "DELETE FROM batch_job_instance WHERE job_instance_id = ?", instanceId);
            }
            connection.commit();
        }
    }

    /**
     * Runs one fixed delete statement bound to one identifier.
     *
     * <p>Every statement is a complete literal and the identifier is bound, so no value is concatenated
     * into SQL.
     *
     * @param connection the open connection
     * @param sql        the complete statement, carrying exactly one placeholder
     * @param identifier the identifier to bind
     * @throws SQLException if the statement fails
     */
    private static void deleteBy(final Connection connection, final String sql, final Long identifier)
            throws SQLException {
        try (PreparedStatement delete = connection.prepareStatement(sql)) {
            delete.setLong(1, identifier.longValue());
            delete.executeUpdate();
        }
    }

    // -----------------------------------------------------------------------------------------------
    // GATE 1 AND GATE 4
    // -----------------------------------------------------------------------------------------------

    @Test
    @Order(1)
    @DisplayName("the committed 300-record sample input drives all six jobs of the delivered pipeline "
            + "to completion against a real server and a real object store")
    void theCommittedInputDrivesTheWholePipeline() throws Exception {
        final String bucket = this.awsProperties.s3().batchStagingBucket();
        assertThat(bucket)
                .as("the publication path must be pointed at the emulator's provisioned bucket, or "
                        + "this run would prove nothing about a real store")
                .isEqualTo(PROVISIONED_STAGING_BUCKET);
        createStagingBucket(bucket);

        final byte[] input = classpathBytes(INPUT_FIXTURE);
        assertThat(input)
                .as("the sample input is the estate's own newline-delimited rendering: %d records of "
                        + "350 bytes plus one terminator each", INPUT_RECORD_COUNT)
                .hasSize(INPUT_RECORD_COUNT * (350 + 1));
        Files.write(stagingRoot().resolve(LANDING_DATASET), input);

        // 1. Posting. Reads the landing dataset, writes the master and the reject dataset.
        final JobExecution posting = launch("postTransactionJob", Map.of());
        assertThat(posting.getStepExecutions())
                .singleElement()
                .satisfies(step -> {
                    assertThat(step.getReadCount()).isEqualTo(INPUT_RECORD_COUNT);
                    assertThat(step.getWriteCount()).isEqualTo(REJECTED_RECORD_COUNT);
                });
        assertThat(this.transactions.count())
                .as("the posting run accepts %d of the %d records", POSTED_RECORD_COUNT,
                        INPUT_RECORD_COUNT)
                .isEqualTo(POSTED_RECORD_COUNT);

        // 2. Accrual. Writes its synthesised transactions to its OWN dataset, not to the master.
        launch("interestCalculationJob", Map.of("interestParmDate", INTEREST_PARM_DATE));
        assertThat(this.transactions.count())
                .as("the accrual run writes to a sequential dataset, so the master is unchanged - this "
                        + "is why the two reading jobs must run after the consolidation and not before")
                .isEqualTo(POSTED_RECORD_COUNT);

        // 3. Archive, then 4. consolidate. The consolidation is what merges the two into the master.
        launch("backupTransactionJob", Map.of());
        launch("combineTransactionsJob", Map.of());
        assertThat(this.transactions.count())
                .as("the consolidation merges the %d archived and the %d synthesised records",
                        POSTED_RECORD_COUNT, SYNTHESISED_RECORD_COUNT)
                .isEqualTo(CONSOLIDATED_RECORD_COUNT);

        // 5. Report and 6. statements, both readers of the consolidated master.
        launch("transactionReportJob", Map.of(
                "reportStartDate", REPORT_START_DATE, "reportEndDate", REPORT_END_DATE));
        launch("createStatementJob", Map.of());

        assertThat(EXECUTIONS)
                .as("all six jobs of the plan's pipeline ran")
                .hasSize(6)
                .allSatisfy(execution -> assertThat(execution.getStatus())
                        .as("%s must complete", execution.getJobInstance().getJobName())
                        .isEqualTo(BatchStatus.COMPLETED));

        capture(REJECT_BASE);
        capture(REPORT_BASE);
        capture(STATEMENT_BASE);
        capture(STATEMENT_HTML_BASE);
        assertThat(PRODUCED)
                .as("the run produced one generation for each of the four goldens")
                .hasSize(4);
    }

    @Test
    @Order(2)
    @DisplayName("the reject dataset the run produced is byte-identical to daily-reject.txt")
    void theRejectDatasetMatchesItsGolden() throws IOException {
        assertGolden(REJECT_BASE, "daily-reject.txt", REJECT_WIDTH, REJECTED_RECORD_COUNT);
    }

    @Test
    @Order(3)
    @DisplayName("the transaction report the run produced is byte-identical to transaction-report.txt")
    void theTransactionReportMatchesItsGolden() throws IOException {
        assertGolden(REPORT_BASE, "transaction-report.txt", REPORT_WIDTH, 519);
    }

    @Test
    @Order(4)
    @DisplayName("the text statement the run produced is byte-identical to statement.txt")
    void theTextStatementMatchesItsGolden() throws IOException {
        assertGolden(STATEMENT_BASE, "statement.txt", STATEMENT_WIDTH, 1262);
    }

    @Test
    @Order(5)
    @DisplayName("the HTML statement the run produced is byte-identical to statement-html.txt")
    void theHtmlStatementMatchesItsGolden() throws IOException {
        assertGolden(STATEMENT_HTML_BASE, "statement-html.txt", STATEMENT_HTML_WIDTH, 6632);
    }

    @Test
    @Order(6)
    @DisplayName("every artefact the run produced carries no record separator, because every dataset "
            + "it stands for is declared fixed-length")
    void noProducedArtefactCarriesASeparator() {
        assertThat(PRODUCED).isNotEmpty();
        PRODUCED.forEach((base, image) -> {
            assertThat(new String(image, StandardCharsets.US_ASCII))
                    .as("%s must carry no line feed", base)
                    .doesNotContain(String.valueOf((char) LINE_FEED));
            assertThat(new String(image, StandardCharsets.US_ASCII))
                    .as("%s must carry no carriage return", base)
                    .doesNotContain("\r");
        });
    }

    @Test
    @Order(7)
    @DisplayName("the landing dataset the run consumed is left exactly as it was staged, because an "
            + "input is read and never rewritten")
    void theLandingDatasetIsUnchanged() throws IOException {
        assertThat(Files.readAllBytes(stagingRoot().resolve(LANDING_DATASET)))
                .isEqualTo(classpathBytes(INPUT_FIXTURE));
    }

    // -----------------------------------------------------------------------------------------------
    // HELPERS
    // -----------------------------------------------------------------------------------------------

    /**
     * Launches one registered job and records its execution.
     *
     * @param  jobName    the job's registered name
     * @param  parameters the caller parameters the job declares
     * @return the completed execution
     * @throws Exception if the launcher refuses the launch
     */
    private JobExecution launch(final String jobName, final Map<String, String> parameters)
            throws Exception {
        final Job job = Objects.requireNonNull(this.jobs.get(jobName),
                () -> "no job is registered under the name " + jobName);

        // Launched through the production coordinator rather than the framework launcher, for two
        // reasons that both matter here. It is the path an operator's request actually takes, so this
        // specification exercises the launch boundary as well as the pipeline. And the coordinator adds
        // a server-side run identifier, so this class's instances are its own: launching a job with an
        // empty parameter set would otherwise share an identity with any other specification that
        // launches the same job the same way, and whichever ran second would silently reuse the first
        // one's completed instance. The instances are removed again in the teardown below.
        final long executionId = this.launcher.start(job, parameters);
        final JobExecution execution = Objects.requireNonNull(
                this.jobExplorer.getJobExecution(Long.valueOf(executionId)),
                () -> "the coordinator reported execution " + executionId + " but the store has none");
        EXECUTIONS.add(execution);
        assertThat(execution.getStatus())
                .as("%s must complete; failures were %s", jobName, execution.getAllFailureExceptions())
                .isEqualTo(BatchStatus.COMPLETED);
        return execution;
    }

    /**
     * Reads the newest generation of one logical base out of the staging root and remembers it.
     *
     * @param  logicalBase the generation base whose newest local generation is wanted
     * @throws IOException if the staging root cannot be listed or the artefact cannot be read
     */
    private static void capture(final String logicalBase) throws IOException {
        final Path newest;
        try (Stream<Path> entries = Files.list(stagingRoot())) {
            newest = entries.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().startsWith(logicalBase + ".G"))
                    .max(Comparator.comparing(path -> path.getFileName().toString()))
                    .orElseThrow(() -> new AssertionError(
                            "the run produced no generation for logical base " + logicalBase));
        }
        PRODUCED.put(logicalBase, Files.readAllBytes(newest));
    }

    /**
     * Compares one produced artefact with its committed golden on raw bytes, and states the geometry
     * both sides must satisfy so that a length difference is reported as records rather than as bytes.
     *
     * @param  logicalBase  the base whose captured artefact is compared
     * @param  goldenName   the committed golden's file name
     * @param  recordWidth  the record width, which is the whole stride
     * @param  recordCount  the record count both sides must carry
     * @throws IOException if the golden cannot be read
     */
    private static void assertGolden(final String logicalBase, final String goldenName,
            final int recordWidth, final int recordCount) throws IOException {
        final byte[] produced = PRODUCED.get(logicalBase);
        assertThat(produced)
                .as("the pipeline test must have run first and captured %s", logicalBase)
                .isNotNull();
        final byte[] golden = classpathBytes(GOLDEN_DIRECTORY + goldenName);

        assertThat(golden)
                .as("%s is %d records of %d bytes with no separator", goldenName, recordCount,
                        recordWidth)
                .hasSize(recordCount * recordWidth);
        assertThat(produced.length / recordWidth)
                .as("the run produced %d records where %s holds %d",
                        produced.length / recordWidth, goldenName, recordCount)
                .isEqualTo(recordCount);
        assertThat(produced.length % recordWidth)
                .as("the produced artefact must be an exact multiple of its %d-byte record width",
                        recordWidth)
                .isZero();

        // The comparison itself: raw bytes, nothing in between, and reported record by record so a
        // failure names the record rather than an offset.
        for (int index = 0; index < recordCount; index++) {
            final String expected =
                    new String(golden, index * recordWidth, recordWidth, StandardCharsets.US_ASCII);
            final String actual =
                    new String(produced, index * recordWidth, recordWidth, StandardCharsets.US_ASCII);
            assertThat(actual)
                    .as("%s record %d must be reproduced byte for byte", goldenName, index)
                    .isEqualTo(expected);
        }
        assertThat(produced)
                .as("%s must match whole, not only record by record", goldenName)
                .isEqualTo(golden);
    }

    /**
     * Reads a class-path resource whole.
     *
     * @param  resource the class-path location
     * @return its bytes
     * @throws IOException if the resource cannot be read
     */
    private static byte[] classpathBytes(final String resource) throws IOException {
        try (InputStream stream = BatchPipelineE2ETest.class.getResourceAsStream(resource)) {
            assertThat(stream).as("%s must be on the test class path", resource).isNotNull();
            return stream.readAllBytes();
        }
    }

    /**
     * Resolves the staging root the context bound, from the same two settings the expression names.
     *
     * @return the staging root, created if absent
     * @throws IOException if it cannot be created
     */
    private static Path stagingRoot() throws IOException {
        final Path root = Path.of(System.getProperty("java.io.tmpdir"),
                "carddemo-batch-pipeline-e2e");
        Files.createDirectories(root);
        return root;
    }

    /**
     * Empties this specification's staging root without touching anything above it.
     *
     * @throws IOException if the root cannot be emptied
     */
    private static void clearStagingRoot() throws IOException {
        final Path root = stagingRoot();
        try (Stream<Path> entries = Files.list(root)) {
            for (final Path entry : entries.toList()) {
                if (Files.isRegularFile(entry)) {
                    Files.delete(entry);
                }
            }
        }
    }

    /**
     * The pipeline under test: the six job configurations, the collaborators they need, and the real
     * object-store clients bound to the emulator this run started.
     *
     * <p>Assembled explicitly rather than by scanning, so the graph is exactly the pipeline and a
     * reader can see in one place what took part. Two beans are declared here. The clock is the pinned
     * one, marked as the preferred candidate rather than replacing the auditing configuration - that
     * configuration belongs to the production graph and is imported whole. The publication lock is the
     * real one, because this slice has the server it needs.
     *
     * <p><strong>Nothing is stubbed.</strong> The object store and the notification clients come from
     * the shipped {@code AwsConfig}, pointed at the emulator by the shared base's property source.
     */
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = PrometheusExemplarsAutoConfiguration.class)
    @Import({PostTransactionJobConfig.class, InterestCalculationJobConfig.class,
            BackupTransactionJobConfig.class, CombineTransactionsJobConfig.class,
            TransactionReportJobConfig.class, CreateStatementJobConfig.class,
            BatchConfig.class, JpaAuditConfig.class, JobParameterValidators.class, AwsConfig.class,
            BatchLaunchCoordinator.class,
            BatchStagingArea.class, StagedGenerationStore.class,
            AdvisoryGenerationPublicationLock.class, FixedWidthFlatFileReaderFactory.class,
            TransactionPostingService.class, PostingRecordTransactionBoundary.class,
            InterestCalculationService.class, InterestGroupTransactionBoundary.class,
            TransactionReportService.class, StatementGenerationService.class,
            StatementDataAccessService.class, SensitiveFieldEncryptionService.class,
            AbendService.class, DateValidationService.class, RecordWriter.class})
    @EnableConfigurationProperties({AwsProperties.class, JpaProperties.class})
    @EnableJpaRepositories(basePackageClasses = TransactionRepository.class)
    @EntityScan(basePackageClasses = Transaction.class)
    static class PipelineContext {

        /** Creates the configuration. */
        PipelineContext() {
            // Intentionally empty: this slice contributes beans, not state.
        }

        /**
         * The pinned clock every collaborator in the graph reads.
         *
         * @return a clock frozen at the instant the committed goldens were produced under
         */
        @Bean
        @Primary
        Clock pinnedClock() {
            return PINNED_CLOCK;
        }
    }
}
