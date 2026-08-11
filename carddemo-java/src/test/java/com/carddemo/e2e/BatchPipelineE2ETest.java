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
package com.carddemo.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.batch.BackupTransactionJobConfig;
import com.carddemo.batch.BatchLaunchCoordinator;
import com.carddemo.batch.BatchStagingArea;
import com.carddemo.batch.CombineTransactionsJobConfig;
import com.carddemo.batch.CreateStatementJobConfig;
import com.carddemo.batch.DailyTransactionReadJobConfig;
import com.carddemo.batch.InterestCalculationJobConfig;
import com.carddemo.batch.JobParameterValidators;
import com.carddemo.batch.PostTransactionJobConfig;
import com.carddemo.batch.TransactionReportJobConfig;
import com.carddemo.batch.step.AdvisoryGenerationPublicationLock;
import com.carddemo.batch.step.FixedWidthFlatFileReaderFactory;
import com.carddemo.batch.step.RejectRecordWriter;
import com.carddemo.batch.step.StagedGenerationStore;
import com.carddemo.config.AwsConfig;
import com.carddemo.config.AwsProperties;
import com.carddemo.config.BatchConfig;
import com.carddemo.config.JpaAuditConfig;
import com.carddemo.domain.Transaction;
import com.carddemo.domain.enums.RejectReason;
import com.carddemo.repository.RecordWriter;
import com.carddemo.repository.TransactionRepository;
import com.carddemo.service.AbendService;
import com.carddemo.service.DailyTransactionReadService;
import com.carddemo.service.DateValidationService;
import com.carddemo.service.InterestCalculationService;
import com.carddemo.service.InterestGroupTransactionBoundary;
import com.carddemo.service.PostingStageTransactionBoundary;
import com.carddemo.service.SensitiveFieldEncryptionService;
import com.carddemo.service.StatementDataAccessService;
import com.carddemo.service.StatementGenerationService;
import com.carddemo.service.TransactionPostingService;
import com.carddemo.service.TransactionReportService;
import com.carddemo.support.AbstractLocalStackIT.ObjectVersionRef;
import com.carddemo.support.AbstractPostgresAndLocalStackIT;
import com.carddemo.support.GateEvidenceProvenance;
import com.carddemo.support.IsolatedStagingRoot;
import com.carddemo.support.LegacyRejectReasons;
import com.carddemo.support.RunScopedPerformanceRecorder;
import com.carddemo.support.SensitiveValues;
import com.carddemo.support.TestDataFactory;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.StepExecution;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Gate 1, Gate 3 and the pipeline half of Gate 4, executed: the delivered pipeline is driven end to
 * end from the committed production-representative input, on a real PostgreSQL 16 server and a real
 * object store, and every artefact it produces is compared to its committed golden file on
 * <strong>raw bytes</strong>.
 *
 * <h2>Why this class exists</h2>
 * The module committed four golden files and, until this class, nothing compared any of them with
 * anything the code produced. Two suites opened them - {@code ExpectedOutputFixtureContractTest} and
 * {@code ExpectedHtmlStatementFixtureContractTest} - but both re-emit one record at a time through the
 * formatter that owns its layout, which proves a formatter and says nothing about a pipeline. A wrong
 * balance written by an accrual run, a value escaped on its way into an HTML cell, and a record
 * separator that no dataset definition allows are all invisible to a record-level comparison. This
 * class is the assertion that catches them: one run, four whole files, byte arrays compared with
 * nothing in between, and then one focused assertion per parity trap so that a failure names the
 * behaviour that broke rather than only the offset that moved.
 *
 * <h2>The oracles are independent, and that is the whole point</h2>
 * The four goldens are statically authored byte constants. <strong>No expectation in this file is
 * produced by the class it checks.</strong> The record writers, the line formatters, the statement
 * templates, the statement and report services, the decimal codec and all eleven record mappers are
 * the <em>subject</em> of the comparison and appear nowhere as its source - an expectation that
 * borrows the subject's own encoder proves only that the subject agrees with itself. Every width,
 * offset, count, code and contract literal below is written out here as its own constant for the same
 * reason.
 *
 * <h2>No mocked input or output anywhere</h2>
 * Gate 1 states that mocked I/O does not satisfy it, so nothing here is mocked. The relational store is
 * the containerised PostgreSQL 16 server the shared base migrates; the object store is the containerised
 * emulator the shared base provisions, with the versioned staging bucket the publication path writes
 * into; the sequential input is the estate's own committed sample data, staged on the filesystem as the
 * job's landing dataset; and every artefact asserted below is read back off the filesystem after the job
 * that composed it has closed it. The graph is assembled explicitly rather than by scanning, which
 * bounds it to the seven jobs under test - that is a narrower <em>bean set</em>, not a substituted
 * boundary.
 *
 * <h2>The clock is pinned, and it has to be</h2>
 * The clock is the shared base's own {@code FIXED_CLOCK}, frozen at the instant every record of the
 * committed daily-transaction fixture carries. Two separate things depend on it. Every timestamp the
 * accrual run writes into a synthesised interest transaction is read from the clock, so an unpinned run
 * writes a different image on every execution and no byte comparison is possible. And the processing
 * date those transactions carry is what the reporting window filters on - so a system clock puts all
 * fifty of them outside {@value #REPORT_START_DATE} to {@value #REPORT_END_DATE} and the report
 * silently comes out sixty-eight records short, having failed nothing. That the pinned instant falls
 * inside the window is asserted rather than assumed.
 *
 * <h2>Two contract values are legacy literals rather than derived dates</h2>
 * The accrual run's date parameter and the reporting window are external contract values, not clock
 * readings. The accrual parameter is the literal the job member carries, and it is also the leading ten
 * characters of every transaction identifier the run synthesises. The reporting window is the pair the
 * cataloged procedure's sort symbols carry, and the report prints it in its own name header. Deriving
 * either from the pinned clock would change the bytes the goldens hold, so both are stated here as the
 * literals the estate states them as.
 *
 * <h2>The order of the launches is not arbitrary</h2>
 * Before posting, every seeded category balance is the single byte image of positive zero, so an accrual
 * run placed first would compute zero interest for every account and truncation - the single most
 * important behaviour this gate exists to prove - would be unobservable. The accrual run then does
 * <strong>not</strong> write its synthesised transactions into the master; it writes them to its own
 * sequential dataset, exactly as the member it translates does, and only the consolidation run merges
 * that dataset into the master. So a report or a statement produced before the consolidation is missing
 * every interest transaction - fifty data records, the page breaks they create, and fifty statement
 * lines. The pipeline order asserted here is therefore the plan's own order, and the two reading jobs
 * run last.
 *
 * <h2>Shared state is restored</h2>
 * The server and the emulator are shared by every integration test in the run, and a batch job
 * commits on its own connections, so nothing here can be rolled back. The seeded state is restored
 * before the first test and again after the last one whatever the outcome, and the staging root this
 * class binds is its own directory, emptied before the run.
 *
 * <h2>Gate 3 establishes a baseline and asserts no threshold</h2>
 * No performance baseline exists anywhere in the estate - not in the source, the job control, the
 * resource definitions or the specification - so this gate records the first Java figures rather than
 * testing against a number. Nothing here asserts an elapsed time, a throughput, a latency or a heap
 * ceiling, and no JVM sizing is set anywhere. The measurements are published for the evidence page and
 * the only assertions made over the instruments are that they exist and that their shape agrees with the
 * record counts the run actually processed.
 *
 * <p>Provenance: checkout SHA {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. Dataset names, record widths, record counts and
 * external-contract literals are metadata rather than source text, and no legacy source line is
 * transcribed. No user-specified rules were provided for this engagement - the project's rules document
 * states exactly that - so the work is held to the module's enterprise standards instead, and the
 * divergences this class pins down are recorded in {@code docs/decision-log.md}.
 */
@SpringBootTest(classes = BatchPipelineE2ETest.PipelineContext.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {"spring.flyway.enabled=false", "spring.main.banner-mode=off",
                "spring.jpa.hibernate.ddl-auto=none", "spring.batch.job.enabled=false",
                "management.endpoint.health.validate-group-membership=false",
                // ONE property serves every job: each job configuration falls back to the shared
                // staging root before it falls back to the platform temporary directory, so binding the
                // shared key is what makes the pipeline chain through one filesystem location exactly as
                // it does in the application. It is registered from registerIsolatedStagingDirectory
                // rather than named here, because the bindings are resolved while the context starts and
                // the root has to carry this process's own identity, which no compile-time constant can.
                "management.tracing.enabled=false"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("Gates 1, 3 and 4 executed: the committed input through the delivered pipeline, all four "
        + "goldens compared byte for byte, and every parity trap named")
class BatchPipelineE2ETest extends AbstractPostgresAndLocalStackIT {

    // ===============================================================================================
    // THE RUN'S OWN SETTINGS
    // ===============================================================================================

    /**
     * This specification's label within this process's private staging namespace.
     *
     * <p>It was a fixed expression naming a directory directly beneath the platform temporary directory,
     * so every run of this specification and every sibling clone on the host resolved the same absolute
     * path. It is now one segment beneath a namespace unique to this process, bound from a property
     * callback. See {@code support/IsolatedStagingRoot}.
     */
    static final String STAGING_LABEL = "batch-pipeline-e2e";

    /** The one staging-directory key every job in the pipeline falls back to. */
    private static final String STAGING_DIRECTORY_PROPERTY = "carddemo.batch.staging-directory";

    /**
     * Inclusive lower bound of the reporting window: the literal the cataloged procedure's sort symbols
     * carry, which the report also prints in its own name header.
     */
    static final String REPORT_START_DATE = "2022-01-01";

    /** Inclusive upper bound of the same window, from the same place. */
    static final String REPORT_END_DATE = "2022-07-06";

    /**
     * The accrual run's date parameter, exactly as the job member's parameter string carries it.
     *
     * <p>It is also the literal leading ten characters of every transaction identifier the run
     * synthesises - the identifier is that value followed by a six-digit suffix, sixteen bytes in all -
     * so the committed report and statement goldens contain it fifty times. It is a contract literal
     * rather than a formatted date, which is why it is not derived from the pinned clock.
     */
    static final String INTEREST_PARM_DATE = "2022071800";

    // ===============================================================================================
    // THE LEGACY NAMES AND FIGURES, RESTATED HERE RATHER THAN IMPORTED.
    //
    // Every dataset name, record width, record count, reject code and contract literal below is written
    // as its own constant. None is read from the production class that also produces it, because an
    // expectation that borrows the subject's own constant proves only that the subject agrees with
    // itself. Each figure was measured from the committed bytes.
    // ===============================================================================================

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

    /** Width of the source image a reject record carries unchanged in its leading bytes. */
    private static final int SOURCE_IMAGE_WIDTH = 350;

    /** Width of the four-digit reject reason that opens the trailer. */
    private static final int REJECT_REASON_WIDTH = 4;

    /** Width of the reject reason description that follows it. */
    private static final int REJECT_DESCRIPTION_WIDTH = 76;

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

    /** Statements the generator emits: one per cross-reference row. */
    private static final int STATEMENT_COUNT = 50;

    /** Records the transaction report holds. */
    private static final int REPORT_RECORD_COUNT = 519;

    /** Pages the report breaks into, each opening with a four-record header block. */
    private static final int REPORT_PAGE_COUNT = 18;

    /** Lines the report allows a page before the page test fires, counting every written record. */
    private static final int REPORT_PAGE_SIZE = 20;

    /** 0-based offset of the edited amount in every report group that carries one. */
    private static final int REPORT_AMOUNT_OFFSET = 97;

    /** Width of that edited amount, sign position included. */
    private static final int REPORT_AMOUNT_WIDTH = 15;

    /** Records the text statement file holds. */
    private static final int STATEMENT_RECORD_COUNT = 1262;

    /** Records the HTML statement file holds. */
    private static final int STATEMENT_HTML_RECORD_COUNT = 6632;

    /**
     * The bucket the emulator is provisioned with, restated here as its own literal so the assertion
     * that the publication path addresses it is not satisfied by reading the same field it checks.
     */
    private static final String PROVISIONED_STAGING_BUCKET = "carddemo-batch-staging";

    /** Class-path directory holding the committed sequential inputs. */
    private static final String INPUT_DIRECTORY = "/fixtures/input/";

    /** Class-path directory holding the committed goldens. */
    private static final String GOLDEN_DIRECTORY = "/fixtures/expected/";

    /** File name of the committed sample input the posting run consumes. */
    private static final String INPUT_FIXTURE_NAME = "dailytran.txt";

    /** Class-path location of that input. */
    private static final String INPUT_FIXTURE = INPUT_DIRECTORY + INPUT_FIXTURE_NAME;

    /** The one byte that must not appear in any produced artefact. */
    private static final byte LINE_FEED = 0x0A;

    /**
     * The extract no job stream invokes, which this tree launches by name or nothing does.
     *
     * <p>Named here because setup launches it and cleanup must delete it, and those two places have to
     * agree on the name for the metadata row to be accounted for.
     */
    private static final String ORPHAN_JOB_NAME = "dailyTransactionReadJob";

    /** Name of the file the Gate 1 comparison report is published under. */
    private static final String COMPARISON_REPORT_FILE = "gate1-byte-equivalence.md";

    /** Name of the file the Gate 3 baseline is published under. */
    private static final String BASELINE_REPORT_FILE = "gate3-pipeline-baseline.md";

    /**
     * The tables whose row counts are observed BEFORE the first job is launched.
     *
     * <p>Every one of them is a statement about what the run started from, and none is observable once it
     * has finished - the master is empty at the start and holds the consolidated rows at the end, and the
     * daily-transaction landing table is what the posting job consumes.
     */
    private static final List<String> PRE_RUN_COUNTED_TABLES = List.of(
            "account", "card", "customer", "card_cross_reference", "transaction_category_balance",
            "disclosure_group", "transaction_category", "transaction_type", "daily_transaction",
            "transaction");

    /** The four generation bases the run produces, in the order the goldens are compared. */
    private static final List<String> PRODUCED_BASES =
            List.of(REJECT_BASE, REPORT_BASE, STATEMENT_BASE, STATEMENT_HTML_BASE);


    /**
     * The four fixed-width contracts the pipeline emits, each naming its generation base, its committed
     * golden and the geometry both sides must satisfy.
     *
     * <p>Declared as data so that setup can build all four comparison rows in one loop, which is what
     * makes the comparison report complete regardless of how many tests were selected.
     */
    private static final List<GoldenContract> GOLDEN_CONTRACTS = List.of(
            new GoldenContract(REJECT_BASE, "daily-reject.txt", REJECT_WIDTH, REJECTED_RECORD_COUNT),
            new GoldenContract(REPORT_BASE, "transaction-report.txt", REPORT_WIDTH,
                    REPORT_RECORD_COUNT),
            new GoldenContract(STATEMENT_BASE, "statement.txt", STATEMENT_WIDTH,
                    STATEMENT_RECORD_COUNT),
            new GoldenContract(STATEMENT_HTML_BASE, "statement-html.txt", STATEMENT_HTML_WIDTH,
                    STATEMENT_HTML_RECORD_COUNT));

    // ===============================================================================================
    // THE RUN, AS AN IMMUTABLE SNAPSHOT TAKEN ONCE IN FIXTURE SETUP
    //
    // -----------------------------------------------------------------------------------------------
    // WHY THIS IS NOT A TEST THAT LATER TESTS DEPEND ON.
    // -----------------------------------------------------------------------------------------------
    // The pipeline used to be driven by an ORDERED TEST which wrote its results into static mutable
    // collections that some thirty later tests then read. That made the class runnable only in its
    // entirety and only in its declared order: selecting one method to diagnose a failure, or letting a
    // runner reorder the methods, produced a cascade of failures whose cause was the harness rather than
    // the code - and the helpers said so in as many words, failing with "the pipeline test must have run
    // first".
    //
    // The run now happens exactly once, in fixture setup, and everything it observed is published as one
    // immutable snapshot. Every test below reads only that snapshot, so each is valid on its own and in
    // any order. The setup OBSERVES and RECORDS; the tests ASSERT. That split is deliberate: putting the
    // assertions in setup would make every failure surface as "initialisation error" against the whole
    // class instead of naming the behaviour that broke, which is the property this class is built around.
    //
    // Two consequences of the split are worth stating because they are easy to get wrong:
    //
    //   * The PRE-RUN observations - the applied migrations, the seeded row counts, the all-zero
    //     category balances - are captured BEFORE the first job is launched and are asserted from the
    //     snapshot afterwards. They could not be asserted live once the run moved into setup, and
    //     dropping them would have lost the evidence that the accrual job cannot be placed first.
    //   * The four golden COMPARISON ROWS are built in setup too, rather than accumulated by the four
    //     comparison tests as they ran. Accumulation was the second ordering dependency in this class:
    //     the report test asserted that four rows existed, which was true only if the four tests before
    //     it had run. Building them up front makes the report complete however few tests are selected.
    //
    // The lifecycle is PER_CLASS so that setup can be an instance method and reach the injected
    // collaborators. The ordering annotation is retained, but for readability of the report only - no
    // assertion below depends on it any longer.
    //
    // The full reasoning, including why the snapshot is a class rather than a record, is recorded as
    // DL-281 in docs/decision-log.md.
    // ===============================================================================================

    /**
     * Everything the run observed and produced, published once by setup and never mutated afterwards.
     *
     * <p>Null only outside the class's lifecycle. {@link #run()} is the accessor every test uses, and it
     * fails with a readable message rather than a null dereference if setup did not complete.
     */
    private PipelineRun run;

    /**
     * The Gate 3 recorder: three figures per measured run, and no threshold anywhere.
     *
     * <p>An instance field rather than a static one, so it holds the figures of exactly this class's own
     * run and is discarded with the instance.
     */
    private final RunScopedPerformanceRecorder performance = new RunScopedPerformanceRecorder();

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

    @Autowired
    private MeterRegistry meterRegistry;

    /**
     * Every framework execution identifier this class has caused to exist, recorded as it is issued.
     *
     * <p><strong>Why this is a field and not read off the snapshot.</strong> The snapshot is published to
     * {@link #run} only once {@link #executeDeliveredPipeline()} has returned, which is after the seventh
     * and last launch. A setup that fails at any point before that - a job that does not complete, a
     * golden that cannot be read, a capture that finds no artefact - leaves the snapshot null, and the
     * teardown that removes this class's metadata rows used to take the snapshot as its argument and
     * return immediately when it was null. Every row the launches before the failure had already created
     * was then left in the shared metadata store, where the specification that counts instances of a job
     * would attribute them to itself.
     *
     * <p>The identifier is added the instant the coordinator issues it, inside {@link #launch(String,
     * java.util.Map)} and before that method asserts anything, because the rows exist from that moment
     * whatever the execution's outcome. Teardown then works from this ledger unconditionally, and a
     * partially completed setup is cleaned up exactly as far as it got.
     *
     * <p>The residual case is a launch the coordinator refuses outright: it throws instead of returning
     * an identifier, so there is nothing to record - and nothing to clean, because a refused launch is
     * refused before an execution is created.
     */
    private final List<Long> ownedExecutionIds = new ArrayList<>();

    /**
     * How long one launched job is given to reach a terminal state before this class calls it stalled.
     *
     * <p>A stall budget rather than a performance target: the recorded figures this class publishes are the
     * ones the meters report, and none of them is compared against this. It is generous because the
     * pipeline's own jobs read the whole seeded estate on a shared server.
     */
    private static final long LAUNCH_COMPLETION_BUDGET_MILLIS = 300_000L;

    /** How often the framework's metadata is re-read while a launched job runs. */
    private static final long LAUNCH_POLL_INTERVAL_MILLIS = 50L;

    /**
     * Every object version and delete marker the shared staging bucket held before this run began.
     *
     * <p>The baseline of a delta, not a cleanup list. This class publishes into a bucket every other
     * specification shares, so it may only remove what it added; and it cannot learn what it added by
     * listing the bucket afterwards, because those bases carry generations from earlier runs. Each entry is
     * a {@code (key, versionId)} pair - an identity the service issues once and never reuses - so
     * subtracting this set from the set present afterwards names precisely this run's own additions.
     *
     * <p><strong>The whole bucket, deliberately, not an enumerated list of bases.</strong> An earlier form
     * of this listed the four bases whose bytes a golden covers plus the archive base, and that list was
     * wrong: the pipeline publishes eight generations across five of its executions, because the datasets
     * the jobs hand to one another are published as generations too - the accrual job's transaction
     * dataset and the report job's filtered dataset among them. A base omitted from such a list is a base
     * whose residue the next specification measures as its own, and the omission is silent. Differencing
     * the whole bucket cannot omit one. It is safe to do so because the bucket is emulator-local to this
     * JVM and the specifications in it run one at a time, so nothing else is adding to it between the two
     * listings.
     */
    private final Set<ObjectVersionRef> durableBaseline = new LinkedHashSet<>();

    /**
     * Whether the durable baseline above was taken, so teardown knows whether a delta is computable.
     *
     * <p>Taken as the very first act of setup, before any job runs. If taking it fails, no job has run,
     * nothing has been published, and there is therefore nothing for teardown to remove - which is why a
     * missing baseline is a reason to skip the durable cleanup rather than to guess at one.
     */
    private boolean durableBaselineTaken;

    /** Creates the specification. */
    BatchPipelineE2ETest() {
        super();
    }

    // ===============================================================================================
    // LIFECYCLE
    // ===============================================================================================

    /**
     * Empties this specification's staging root and returns the shared server to its seeded state.
     *
     * <p>Ordered exactly as written: the snapshot is discarded and the staging root emptied BEFORE the
     * seed is restored and the run begins, so nothing a previous class or a previous run of this one left
     * behind can be mistaken for this run's output.
     *
     * @throws Exception if the staging root cannot be prepared, the seeded state cannot be restored, or
     *                   any job of the pipeline fails to complete
     */
    @BeforeAll
    void driveTheDeliveredPipelineOnce() throws Exception {
        this.run = null;
        this.ownedExecutionIds.clear();
        // First, before anything can publish: what the shared versioned bucket already held beneath this
        // class's bases. Everything that appears beneath them from here on is this run's, and is this
        // class's to remove.
        recordDurableBaseline();
        clearStagingRoot();
        restoreSeededState();
        this.run = executeDeliveredPipeline();
    }

    /**
     * Publishes the two evidence artefacts, returns the shared server to its seeded state, removes what
     * this run staged and discards the snapshot - whatever the outcome of the tests above.
     *
     * <p>The evidence is written first and unconditionally, because a failed comparison is exactly the
     * case in which the report is worth having. It is written from the SNAPSHOT rather than from rows the
     * tests accumulated, so the report is complete even when a single method was selected.
     *
     * <h2>Four restores, all attempted, and every failure reported</h2>
     *
     * <p>This class writes to four things it does not own: the shared metadata store, the shared versioned
     * staging bucket, the shared seeded database, and the host filesystem. Each restore below therefore
     * runs even when an earlier one failed - abandoning three because the first raised would turn one leak
     * into four, and the specification that inherits them cannot explain any of them. Each is attempted,
     * each failure is recorded against the thing that was not restored, and the collected list is asserted
     * empty at the end so an unrestored share fails the class that caused it rather than the next one.
     *
     * <p>Each restore works from a ledger this class populated as it went, never from the snapshot: a
     * setup that failed part way through left a partial footprint, and a partial footprint is exactly the
     * one that has to be removed.
     *
     * @throws IOException if the evidence cannot be written
     */
    @AfterAll
    void publishEvidenceAndRestoreSharedState() throws IOException {
        try {
            publishComparisonReport(this.run);
            if (!this.performance.baselines().isEmpty()) {
                this.performance.publish(BASELINE_REPORT_FILE);
            }
        } finally {
            final List<String> unrestored = new ArrayList<>();
            attemptRestore(unrestored, "the framework metadata rows of this run's own launches",
                    this::removeOwnJobInstances);
            attemptRestore(unrestored, "the durable generations this run published into the shared"
                    + " staging bucket", this::removeOwnDurableGenerations);
            attemptRestore(unrestored, "the seeded database state",
                    BatchPipelineE2ETest::restoreSeededState);
            // Discarded outright rather than emptied and re-created: at the end of the run there is
            // nothing left to stage, and an empty root left behind once per process accumulates on
            // the host for no purpose. The set-up callback still empties-and-prepares, because it
            // needs a root that exists and is empty.
            attemptRestore(unrestored, "this run's local staging root",
                    () -> IsolatedStagingRoot.discard(stagingRoot()));
            this.run = null;

            assertThat(unrestored)
                    .as("this specification writes to state it shares with every other specification in "
                            + "the run, and the list below names each share it could not put back. A "
                            + "share left as this class left it is measured by whichever specification "
                            + "runs next, whose failure would then have nothing to do with its own "
                            + "subject")
                    .isEmpty();
        }
    }

    /**
     * Attempts one restore, recording rather than raising when it fails.
     *
     * <p>{@code Exception} is caught rather than a narrower type on purpose. The restores raise between
     * them a checked database exception, a checked filesystem exception and unchecked object-store
     * failures, and this method's entire reason for existing is that the next restore must be attempted
     * whichever of those arrived. Nothing is swallowed: the failure is rendered into the list the caller
     * asserts on, so it surfaces as a named unrestored share.
     *
     * @param unrestored the collector of shares that could not be put back
     * @param share      what this restore is responsible for, for the diagnostic
     * @param restore    the restore to attempt
     */
    private static void attemptRestore(final List<String> unrestored, final String share,
            final SharedStateRestore restore) {

        try {
            restore.run();
        } catch (final Exception failure) {
            unrestored.add(share + " - " + failure);
        }
    }

    /** One restore of one piece of shared state, permitted to raise anything. */
    @FunctionalInterface
    private interface SharedStateRestore {

        /**
         * Puts one shared thing back the way this class found it.
         *
         * @throws Exception if it cannot be put back
         */
        void run() throws Exception;
    }

    /**
     * The snapshot of this class's run, insisting that setup completed.
     *
     * @return the snapshot
     */
    private PipelineRun run() {
        assertThat(this.run)
                .as("the delivered pipeline is driven once in fixture setup and published as an "
                        + "immutable snapshot; a null snapshot here means that setup did not complete, "
                        + "which is a harness failure rather than a contract failure")
                .isNotNull();
        return this.run;
    }

    /**
     * Drives the six jobs of the plan's pipeline once, in the plan's own order, and returns everything
     * observed.
     *
     * <p>Observation only: this method asserts nothing about the figures it records beyond what it must
     * to fail fast and legibly - each launch insists its job COMPLETED, because a snapshot taken from a
     * failed run would produce thirty confusing failures instead of one clear one. Every other assertion
     * belongs to a named test below.
     *
     * @return the immutable snapshot
     * @throws Exception if a job cannot be launched, an artefact cannot be read, or a golden is missing
     */
    private PipelineRun executeDeliveredPipeline() throws Exception {
        // THE PRE-RUN OBSERVATIONS. Taken before the first launch, because they are statements about
        // what the run STARTED from and none of them is observable once it has finished.
        final List<String> migrations = List.copyOf(appliedMigrationVersions());
        final List<String> tables = List.copyOf(applicationTableNames());
        final Map<String, Long> seededCounts = new LinkedHashMap<>();
        for (final String table : PRE_RUN_COUNTED_TABLES) {
            seededCounts.put(table, Long.valueOf(rowCount(table)));
        }
        final List<String> seededBalances = List.copyOf(queryColumn(
                "SELECT DISTINCT tran_cat_bal::text FROM transaction_category_balance"));

        final byte[] input = classpathBytes(INPUT_FIXTURE);
        Files.write(stagingRoot().resolve(LANDING_DATASET), input);

        final List<JobExecution> executions = new ArrayList<>();

        // 1. Posting. Reads the landing dataset, writes the master and the reject dataset. Measured for
        // Gate 3 over the three hundred records it processes - a recorded figure, never a threshold.
        final JobExecution posting = this.performance.measure("postTransactionJob",
                INPUT_RECORD_COUNT + " daily-transaction records of " + SOURCE_IMAGE_WIDTH
                        + " bytes, against 50 accounts, 50 cards, 50 cross-references and 50 seeded "
                        + "category balances",
                execution -> readCountOf(execution),
                () -> launch("postTransactionJob", Map.of()));
        executions.add(posting);
        final long masterAfterPosting = this.transactions.count();

        // 2. Accrual. Writes its synthesised transactions to its OWN dataset, not to the master.
        executions.add(this.performance.measure("interestCalculationJob",
                "50 accounts and the category-balance rows posting left behind them, against three "
                        + "17-row disclosure groups",
                // The accrual step's own read count is the category-balance rows it consumed, which the
                // step's counter reports directly; the execution carries the same figure across its
                // steps and either is the run's own evidence.
                accrual -> Math.round(counterTotal("carddemo.batch.interest.rows")),
                () -> launch("interestCalculationJob",
                        Map.of(JobParameterValidators.INTEREST_PARM_DATE_KEY, INTEREST_PARM_DATE))));
        final long masterAfterAccrual = this.transactions.count();

        // 3. Archive, then 4. consolidate. The consolidation is what merges the two into the master.
        executions.add(launch("backupTransactionJob", Map.of()));
        executions.add(launch("combineTransactionsJob", Map.of()));
        final long masterAfterConsolidation = this.transactions.count();

        // 5. Report and 6. statements, both readers of the consolidated master.
        executions.add(launch("transactionReportJob", Map.of(
                JobParameterValidators.REPORT_START_DATE_KEY, REPORT_START_DATE,
                JobParameterValidators.REPORT_END_DATE_KEY, REPORT_END_DATE)));
        executions.add(launch("createStatementJob", Map.of()));

        final Map<String, byte[]> produced = new LinkedHashMap<>();
        for (final String base : PRODUCED_BASES) {
            produced.put(base, capture(base));
        }

        // THE ORPHAN PROBE, launched after the six and after the captures, exactly where the ordered test
        // used to launch it. It is recorded on the snapshot rather than left to a test method for one
        // concrete reason: the framework metadata store is shared by every integration test in the run,
        // and this class deletes only the instance identifiers it recorded. A launch performed inside a
        // test would leave a row nothing here could account for, and the specification that asserts the
        // orphan has never been launched would fail depending on which of the two ran first.
        final JobExecution orphan = launch(ORPHAN_JOB_NAME, Map.of());
        final long masterAfterOrphan = this.transactions.count();

        // THE FOUR COMPARISON ROWS, built here rather than accumulated by the four comparison tests as
        // they ran. Accumulation was this class's second ordering dependency: the report test asserted
        // four rows existed, which held only if the four tests before it had run.
        final List<ContractComparison> comparisons = new ArrayList<>(GOLDEN_CONTRACTS.size());
        for (final GoldenContract contract : GOLDEN_CONTRACTS) {
            comparisons.add(compare(contract, produced.get(contract.logicalBase())));
        }

        final StepExecution postingStep = posting.getStepExecutions().iterator().next();
        return new PipelineRun(migrations, tables, seededCounts, seededBalances,
                postingStep.getReadCount(), postingStep.getWriteCount(), masterAfterPosting,
                masterAfterAccrual, masterAfterConsolidation, input.length,
                this.awsProperties.s3().batchStagingBucket(), executions, orphan, masterAfterOrphan,
                produced, comparisons);
    }

    /**
     * Removes the framework metadata rows this class's own launches created, and nothing else.
     *
     * <p>The metadata store is shared by every integration test in the run and no restore covers it, so
     * a specification that asserts how many instances of a job exist is asserting a figure this class
     * can move. Deletion is by the execution identifiers this class recorded - never by job name - so an
     * instance another specification created is untouched whichever order the two ran in.
     *
     * <p><strong>The ledger, not the snapshot.</strong> This used to take the completed
     * {@link PipelineRun} and return immediately when it was null, which it is whenever setup did not
     * finish. Ownership is recorded in {@link #ownedExecutionIds} as each identifier is issued instead, so
     * a setup that failed after three launches still has its three rows removed. It covers the orphan
     * probe for the same reason it always did: that is a launch this class performed, and deleting the six
     * while leaving the seventh is what makes the orphan's own specification fail on ordering.
     *
     * <p>The instance identifier is read back from the metadata by execution identifier rather than taken
     * from a {@code JobExecution} object, because the ledger holds only what the coordinator issued - and
     * that is deliberately all it holds, since it must be writable at the instant of issue.
     *
     * <p>The order of the six statements is the order the foreign keys require and is not
     * interchangeable, and the instance identifier is resolved before the execution row it is read from is
     * deleted.
     *
     * @throws SQLException if the metadata cannot be amended
     */
    private void removeOwnJobInstances() throws SQLException {
        if (this.ownedExecutionIds.isEmpty()) {
            return;
        }
        try (Connection connection =
                DriverManager.getConnection(jdbcUrl(), databaseUser(), databasePassword())) {
            connection.setAutoCommit(false);
            for (final Long executionId : List.copyOf(this.ownedExecutionIds)) {
                final Long instanceId = instanceIdOf(connection, executionId);
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
                if (instanceId != null) {
                    deleteBy(connection,
                            "DELETE FROM batch_job_instance WHERE job_instance_id = ?", instanceId);
                }
            }
            connection.commit();
        }
        this.ownedExecutionIds.clear();
    }

    /**
     * Reads the instance one recorded execution belongs to, before its execution row is deleted.
     *
     * @param  connection  the open metadata connection
     * @param  executionId the execution identifier this class recorded
     * @return the instance identifier, or {@code null} when the store holds no such execution
     * @throws SQLException if the metadata cannot be read
     */
    private static Long instanceIdOf(final Connection connection, final Long executionId)
            throws SQLException {

        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT job_instance_id FROM batch_job_execution WHERE job_execution_id = ?")) {
            statement.setLong(1, executionId.longValue());
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Long.valueOf(rows.getLong(1)) : null;
            }
        }
    }

    // ===============================================================================================
    // RESTORING THE SHARED DURABLE STORE
    // ===============================================================================================

    /**
     * Records what the shared staging bucket held before this run published anything into it.
     *
     * <p>Called as the first act of setup. Every version and every delete marker is listed, because on a
     * versioned bucket those are two forms of the same thing and both are state a later specification can
     * observe.
     */
    private void recordDurableBaseline() {
        this.durableBaseline.clear();
        this.durableBaseline.addAll(stagedObjectVersionsUnder(stagingBucket(), ""));
        this.durableBaselineTaken = true;
    }

    /**
     * Removes exactly the object versions and delete markers this run added, and proves it removed them.
     *
     * <p><strong>The problem this closes.</strong> The pipeline's jobs publish their completed generations
     * into {@code carddemo-batch-staging}, which is shared by every specification in the run and carries
     * object versioning. This class asserted its output from the local staging root, discarded that root
     * in teardown, restored the database - and left the durable side untouched. Five bases therefore
     * accumulated one generation per execution of this class, and because the bucket is versioned, even a
     * later ordinary delete would not have removed them.
     *
     * <p><strong>Why a delta and not a prefix sweep.</strong> Emptying the bases outright would delete
     * generations this class did not create, and the retention behaviour of the store means a base can
     * legitimately hold generations from earlier runs. The set difference against the baseline recorded in
     * setup names precisely this run's additions: each entry is a {@code (key, versionId)} pair, and a
     * version identifier is issued once and never reused, so a pair absent from the baseline and present
     * now can only have been added since.
     *
     * <p><strong>Why it is deleted by identifier.</strong> An ordinary delete against a versioned bucket
     * adds a delete marker and removes nothing, so the residue would survive the cleanup and a marker
     * would be added to it. Every removal here names its version - and the markers this run's own
     * publications produced are themselves in the delta and are removed with it. See
     * {@code docs/decision-log.md} entry DL-287.
     *
     * <p>The read-back is not ceremony. It is the only thing that distinguishes "removed" from
     * "delete-markered", and a delta that is still non-empty after the removal is a condition the next
     * specification would inherit and could not explain - so it fails here, in the class that caused it.
     */
    private void removeOwnDurableGenerations() {
        if (!this.durableBaselineTaken) {
            // No baseline means setup failed before the first launch, so nothing was published and there
            // is no delta to compute. Guessing at one would mean deleting generations this class did not
            // create.
            return;
        }
        final String bucket = stagingBucket();
        final List<ObjectVersionRef> added = durableDelta(bucket);
        deleteStagedObjectVersions(bucket, added);

        final List<ObjectVersionRef> remaining = durableDelta(bucket);
        this.durableBaseline.clear();
        this.durableBaselineTaken = false;
        if (!remaining.isEmpty()) {
            throw new IllegalStateException("this run added " + added.size() + " object version(s) or"
                    + " delete marker(s) to the shared staging bucket " + bucket + " and " + remaining
                    + " of them are still there after a version-qualified delete of every one. The"
                    + " bucket cannot be left holding this specification's output: the next"
                    + " specification to measure generations beneath these bases would attribute it to"
                    + " itself.");
        }
    }

    /**
     * What the shared bucket holds now that it did not hold when the baseline was taken.
     *
     * @param  bucket the shared staging bucket
     * @return the versions and delete markers added since setup began, in listing order
     */
    private List<ObjectVersionRef> durableDelta(final String bucket) {
        return stagedObjectVersionsUnder(bucket, "").stream()
                .filter(version -> !this.durableBaseline.contains(version))
                .toList();
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

    // ===============================================================================================
    // PHASE A - THE HARNESS THE REST OF THIS CLASS RESTS ON
    // ===============================================================================================

    @Test
    @Order(1)
    @DisplayName("the schema is the migrations' own: exactly the six delivered versions applied, and "
            + "exactly the eleven application tables they create")
    void theMigratedSchemaIsTheOneTheGoldensWereProducedAgainst() {
        // From the snapshot, so this is the schema the run ACTUALLY read from rather than the schema as it
        // stands now - which is the claim the goldens depend on.
        assertThat(run().appliedMigrations())
                .as("the six delivered migrations, and no seventh version, are what the run read from. "
                        + "Versions 1, 2, 2.1 and 2.2 are schema and 3 and 4 are seeds, so every "
                        + "schema version sorts below every seed version - the sign-on attempt ledger "
                        + "and the protected-value invariants are both schema scripts taking a dotted "
                        + "version below the seeds, per docs/decision-log.md DL-343 and DL-349")
                .containsExactly("1", "2", "2.1", "2.2", "3", "4");
        assertThat(run().applicationTables())
                .as("the framework's own metadata tables and the migration history are excluded by the "
                        + "shared base, so this count is the record schema and nothing else")
                .hasSize(11)
                .containsExactlyInAnyOrderElementsOf(APPLICATION_TABLES);
    }

    @Test
    @Order(2)
    @DisplayName("the pinned instant falls inside the reporting window, without which the report would "
            + "come out empty and fail nothing")
    void thePinnedClockFallsInsideTheReportingWindow() {
        final LocalDate pinned = PINNED_BUSINESS_DATE;
        assertThat(pinned)
                .as("the posting run stamps the processing timestamp from this clock and the report "
                        + "filters on that field, so an instant outside %s..%s selects nothing",
                        REPORT_START_DATE, REPORT_END_DATE)
                .isAfterOrEqualTo(LocalDate.parse(REPORT_START_DATE))
                .isBeforeOrEqualTo(LocalDate.parse(REPORT_END_DATE));
        assertThat(FIXED_CLOCK.instant())
                .as("the clock the graph reads is the shared base's pinned one, never a system clock")
                .isEqualTo(PINNED_INSTANT);
    }

    // ===============================================================================================
    // GATE 4 - THE PIPELINE HALF: THE NINE NAMED INPUTS, DRIVEN
    // ===============================================================================================

    @Test
    @Order(3)
    @DisplayName("Gate 4 - all nine named validation artefacts are present by exact file name at the "
            + "byte count, record count and record length they were measured at")
    void theNineNamedInputsArePresentAtTheirMeasuredGeometry() throws IOException {
        for (final InputFixture fixture : NAMED_INPUTS) {
            final byte[] image = classpathBytes(INPUT_DIRECTORY + fixture.fileName());
            assertThat(image)
                    .as("%s is %d bytes", fixture.fileName(), fixture.byteCount())
                    .hasSize(fixture.byteCount());
            assertThat(image[image.length - 1])
                    .as("%s ends with one line feed, because the estate's rendering is "
                            + "newline-delimited", fixture.fileName())
                    .isEqualTo(LINE_FEED);
            assertThat(image.length % (fixture.recordLength() + 1))
                    .as("%s is an exact multiple of its %d-byte record plus one terminator",
                            fixture.fileName(), fixture.recordLength())
                    .isZero();
            assertThat(image.length / (fixture.recordLength() + 1))
                    .as("%s holds %d records", fixture.fileName(), fixture.recordCount())
                    .isEqualTo(fixture.recordCount());
        }
        assertThat(NAMED_INPUTS).as("nine artefacts are named, not eight and not ten").hasSize(9);
    }

    @Test
    @Order(4)
    @DisplayName("Gate 4 - the cross-reference record is 36 data bytes, so the 14-byte filler its "
            + "encoded twin carries is absent and must never be padded back in")
    void theCrossReferenceRecordCarriesNoFiller() throws IOException {
        final InputFixture crossReference = namedInput("cardxref.txt");
        assertThat(crossReference.recordLength())
                .as("the layout declares 50 bytes of which 36 are data; the delivered rendering holds "
                        + "the 36 and stops")
                .isEqualTo(36);
        final List<String> records = inputRecords(crossReference);
        assertThat(records).allSatisfy(record -> assertThat(record.charAt(35))
                .as("a cross-reference record ends on a digit, never on filler")
                .isBetween('0', '9'));
        assertThat(records.stream().map(record -> record.substring(0, 16)).toList())
                .as("the cross-reference is ascending by card number, which is what lets the statement "
                        + "generator leave its scan early")
                .isSorted()
                .doesNotHaveDuplicates()
                .hasSize(STATEMENT_COUNT);
    }

    @Test
    @Order(5)
    @DisplayName("Gate 4 - the filler character is measured per artefact and is not uniform: space for "
            + "four of them, the digit zero for four, and none at all for the cross-reference")
    void theFillerCharacterContractHoldsPerArtefact() throws IOException {
        for (final InputFixture fixture : NAMED_INPUTS) {
            final Set<Character> terminals = new LinkedHashSet<>();
            for (final String record : inputRecords(fixture)) {
                terminals.add(Character.valueOf(record.charAt(record.length() - 1)));
            }
            switch (fixture.fillerKind()) {
                case SPACE -> assertThat(terminals)
                        .as("%s pads with spaces", fixture.fileName())
                        .containsExactly(Character.valueOf(' '));
                case ZERO_DIGIT -> assertThat(terminals)
                        .as("%s pads with the digit zero, and converting it to a space would change "
                                + "the record image", fixture.fileName())
                        .containsExactly(Character.valueOf('0'));
                case NONE -> assertThat(terminals)
                        .as("%s carries no filler at all", fixture.fileName())
                        .allSatisfy(terminal -> assertThat(terminal).isBetween('0', '9'));
                default -> throw new AssertionError("unhandled filler kind " + fixture.fillerKind());
            }
        }
    }

    @Test
    @Order(6)
    @DisplayName("Gate 4 - the daily-transaction input is representative: 250 point-of-sale purchases "
            + "and 50 operator returns, one origination stamp, a blank processing stamp, and all "
            + "twenty overpunch codes")
    void theDailyTransactionInputIsRepresentative() throws IOException {
        final List<String> records = inputRecords(namedInput(INPUT_FIXTURE_NAME));
        assertThat(records).hasSize(INPUT_RECORD_COUNT);

        // 0-based slices of the 350-byte layout: source 22..31, type 16..17, category 18..21,
        // amount 132..142 with the sign overpunched onto its final byte, card 262..277,
        // origination stamp 278..303, processing stamp 304..329.
        assertThat(records.stream().map(record -> record.substring(22, 32)).toList())
                .as("the source marker splits the file exactly as it was measured")
                .containsOnly("POS TERM  ", "OPERATOR  ")
                .filteredOn("POS TERM  "::equals)
                .hasSize(250);
        assertThat(records.stream().filter(record -> "OPERATOR  ".equals(record.substring(22, 32)))
                .toList())
                .as("fifty operator-originated returns, which is what puts a negative amount through "
                        + "the balance computation")
                .hasSize(50);
        assertThat(records.stream()
                .map(record -> record.substring(16, 18) + ',' + record.substring(18, 22)).toList())
                .as("the two key pairs the accrual run then looks rates up under")
                .containsOnly("01,0001", "03,0001")
                .filteredOn("03,0001"::equals)
                .hasSize(50);

        assertThat(new LinkedHashSet<>(records.stream().map(record -> record.substring(278, 304))
                .toList()))
                .as("one origination stamp across the whole file, which is why the pinned clock has to "
                        + "agree with it rather than drift a day at a time")
                .containsExactly("2022-06-10 19:27:53.000000");
        assertThat(records).allSatisfy(record -> assertThat(record.substring(304, 350))
                .as("the processing stamp and the filler behind it are blank on every record, so no "
                        + "processing-date window can be exercised from seeded input")
                .isEqualTo(" ".repeat(46)));

        final Set<Character> overpunches = new LinkedHashSet<>();
        for (final String record : records) {
            overpunches.add(Character.valueOf(record.charAt(142)));
        }
        assertThat(overpunches)
                .as("all twenty overpunch codes occur, positive and negative zero among them, which is "
                        + "exactly why the comparison below is over bytes and never over numbers")
                .hasSize(20)
                .contains(Character.valueOf('{'), Character.valueOf('}'), Character.valueOf('A'),
                        Character.valueOf('I'), Character.valueOf('J'), Character.valueOf('R'));

        final Map<String, Integer> perCard = new LinkedHashMap<>();
        for (final String record : records) {
            perCard.merge(record.substring(262, 278), Integer.valueOf(1),
                    (left, right) -> Integer.valueOf(left.intValue() + right.intValue()));
        }
        assertThat(perCard)
                .as("fifty distinct cards, six transactions each")
                .hasSize(STATEMENT_COUNT)
                .allSatisfy((card, count) -> assertThat(count).isEqualTo(Integer.valueOf(6)));
    }

    @Test
    @Order(7)
    @DisplayName("Gate 4 - the disclosure input carries three complete groups under their full "
            + "ten-character keys, so both the default fallback and the zero-rate skip are reachable")
    void theDisclosureInputCarriesThreeFullyPaddedGroups() throws IOException {
        final List<String> records = inputRecords(namedInput("discgrp.txt"));
        assertThat(records).hasSize(51);
        final Map<String, Integer> perGroup = new LinkedHashMap<>();
        for (final String record : records) {
            perGroup.merge(record.substring(0, 10), Integer.valueOf(1),
                    (left, right) -> Integer.valueOf(left.intValue() + right.intValue()));
        }
        assertThat(perGroup)
                .as("the keys are space-padded to their declared width and must never be trimmed - the "
                        + "seven-character default literal is stored as ten characters")
                .containsOnlyKeys("A000000000", "DEFAULT   ", "ZEROAPR   ")
                .allSatisfy((group, count) -> assertThat(count).isEqualTo(Integer.valueOf(17)));
    }

    @Test
    @Order(8)
    @DisplayName("Gate 4 - the eight non-sequential artefacts reach the run as seeded rows, at the row "
            + "counts their files hold, and the master starts empty")
    void theSeededServerHoldsWhatTheEightRemainingArtefactsHold() {
        // READ FROM THE SNAPSHOT, NOT FROM THE SERVER. These are statements about what the run STARTED
        // from, and the last of them - an empty master - stops being true the moment the run posts its
        // first record. Asserting them live once the run moved into fixture setup would assert the
        // finished state and call it the starting state, which is how a passing test can describe the
        // opposite of what it claims.
        final PipelineRun completed = run();
        assertThat(completed.seededRowCount("account")).isEqualTo(50L);
        assertThat(completed.seededRowCount("card")).isEqualTo(50L);
        assertThat(completed.seededRowCount("customer")).isEqualTo(50L);
        assertThat(completed.seededRowCount("card_cross_reference")).isEqualTo(50L);
        assertThat(completed.seededRowCount("transaction_category_balance")).isEqualTo(50L);
        assertThat(completed.seededRowCount("disclosure_group")).isEqualTo(51L);
        assertThat(completed.seededRowCount("transaction_category")).isEqualTo(18L);
        assertThat(completed.seededRowCount("transaction_type")).isEqualTo(7L);
        assertThat(completed.seededRowCount("daily_transaction")).isEqualTo((long) INPUT_RECORD_COUNT);
        assertThat(completed.seededRowCount("transaction"))
                .as("the master started empty, so every row the goldens describe was written by this run")
                .isZero();
    }

    @Test
    @Order(9)
    @DisplayName("Gate 4 - every seeded category balance is positive zero before posting, which is the "
            + "reason the accrual run cannot be placed first")
    void everySeededCategoryBalanceIsPositiveZero() {
        // From the snapshot, for the same reason as the test above: posting moves these balances, so the
        // pre-run observation is the only one that can support the claim.
        final List<String> balances = run().seededCategoryBalances();
        assertThat(balances)
                .as("one distinct balance across all fifty rows; an accrual run placed before posting "
                        + "would multiply every rate by it and truncation would be unobservable")
                .hasSize(1);
        assertThat(new BigDecimal(balances.get(0)))
                .as("and that one value is zero")
                .isEqualByComparingTo(BigDecimal.ZERO);
    }

    // ===============================================================================================
    // GATE 1 - THE PIPELINE RUN, AND GATE 3 - THE BASELINE TAKEN FROM IT
    // ===============================================================================================

    @Test
    @Order(10)
    @DisplayName("Gate 1 - the committed 300-record input drove the whole delivered pipeline to "
            + "completion against a real server and a real object store, in the plan's own order")
    void theCommittedInputDrivesTheWholePipeline() {
        final PipelineRun completed = run();

        assertThat(completed.stagingBucket())
                .as("the publication path addresses the bucket the emulator was provisioned with, so "
                        + "the object store this run wrote into is the real one")
                .isEqualTo(PROVISIONED_STAGING_BUCKET);
        assertThat(completed.inputByteCount())
                .as("the sample input is the estate's own newline-delimited rendering: %d records of "
                        + "%d bytes plus one terminator each", INPUT_RECORD_COUNT, SOURCE_IMAGE_WIDTH)
                .isEqualTo(INPUT_RECORD_COUNT * (SOURCE_IMAGE_WIDTH + 1));

        // 1. POSTING. Reads the landing dataset, writes the master and the reject dataset.
        assertThat(completed.postingReadCount())
                .as("the posting step read the whole committed input")
                .isEqualTo(INPUT_RECORD_COUNT);
        assertThat(completed.postingWriteCount())
                .as("and wrote one reject record per refused record")
                .isEqualTo(REJECTED_RECORD_COUNT);
        assertThat(completed.masterCountAfterPosting())
                .as("the posting run accepts %d of the %d records", POSTED_RECORD_COUNT,
                        INPUT_RECORD_COUNT)
                .isEqualTo(POSTED_RECORD_COUNT);

        // 2. ACCRUAL. Writes its synthesised transactions to its OWN dataset, not to the master.
        assertThat(completed.masterCountAfterAccrual())
                .as("the accrual run writes to a sequential dataset, so the master is unchanged - this "
                        + "is why the two reading jobs must run after the consolidation and not before")
                .isEqualTo(POSTED_RECORD_COUNT);

        // 3. ARCHIVE, then 4. CONSOLIDATE. The consolidation is what merges the two into the master.
        assertThat(completed.masterCountAfterConsolidation())
                .as("the consolidation merges the %d archived and the %d synthesised records",
                        POSTED_RECORD_COUNT, SYNTHESISED_RECORD_COUNT)
                .isEqualTo(CONSOLIDATED_RECORD_COUNT);

        // 5. REPORT and 6. STATEMENTS, both readers of the consolidated master.
        assertThat(completed.executions())
                .as("all six jobs of the plan's pipeline ran")
                .hasSize(6)
                .allSatisfy(execution -> assertThat(execution.getStatus())
                        .as("%s must complete", execution.getJobInstance().getJobName())
                        .isEqualTo(BatchStatus.COMPLETED));
        assertThat(completed.executions().stream()
                        .map(execution -> execution.getJobInstance().getJobName())
                        .toList())
                .as("and in the plan's own order, which is not interchangeable: the accrual writes to "
                        + "its own dataset, so both readers must follow the consolidation")
                .containsExactly("postTransactionJob", "interestCalculationJob",
                        "backupTransactionJob", "combineTransactionsJob", "transactionReportJob",
                        "createStatementJob");

        assertThat(completed.artefactBases())
                .as("the run produced one generation for each of the four goldens")
                .containsExactlyInAnyOrderElementsOf(PRODUCED_BASES);
    }

    @Test
    @Order(11)
    @DisplayName("Gate 1 - the reject dataset the run produced is byte-identical to daily-reject.txt")
    void theRejectDatasetMatchesItsGolden() throws IOException {
        assertGolden(goldenContract(REJECT_BASE));
    }

    @Test
    @Order(11)
    @DisplayName("Gate 1 - the leading 350 bytes of every reject record are a LINE OF THE STAGED INPUT, "
            + "byte for byte, so the image survives the reader, the validation and the writer")
    void everyRejectRecordEchoesALineOfTheStagedInput() throws IOException {
        final byte[] staged = Files.readAllBytes(stagingRoot().resolve(LANDING_DATASET));
        final Set<String> inputRecords = new LinkedHashSet<>();
        for (int offset = 0; offset + SOURCE_IMAGE_WIDTH <= staged.length;
                offset += SOURCE_IMAGE_WIDTH + 1) {
            inputRecords.add(new String(staged, offset, SOURCE_IMAGE_WIDTH, StandardCharsets.US_ASCII));
        }
        assertThat(inputRecords)
                .as("the staged dataset really is %s distinct records of %s bytes plus a terminator each",
                        Integer.valueOf(INPUT_RECORD_COUNT), Integer.valueOf(SOURCE_IMAGE_WIDTH))
                .hasSize(INPUT_RECORD_COUNT);

        final byte[] rejects = run().artefact(REJECT_BASE);
        assertThat(rejects.length % REJECT_WIDTH).isZero();
        assertThat(rejects.length / REJECT_WIDTH).isPositive();

        // THE END-TO-END BYTE COPY. The legacy write is MOVE DALYTRAN-RECORD TO REJECT-TRAN-DATA
        // [app/cbl/CBTRN02C.cbl:L447] - the record area the READ filled. So each reject record's leading
        // segment must be an input line VERBATIM, not a rendering of the fields it decoded to. A single
        // defensive copy, re-map or reload anywhere between the reader and the writer would break this
        // and no unit test of the writer alone could see it. Recorded as DL-295.
        for (int record = 0; record < rejects.length / REJECT_WIDTH; record++) {
            final String segment = new String(rejects, record * REJECT_WIDTH, SOURCE_IMAGE_WIDTH,
                    StandardCharsets.US_ASCII);
            assertThat(inputRecords)
                    .as("reject record %s carries a leading segment that is not any line of the staged "
                            + "input, so something between the reader and the writer altered it",
                            Integer.valueOf(record))
                    .contains(segment);
        }
    }

    @Test
    @Order(12)
    @DisplayName("Gate 1 - the transaction report the run produced is byte-identical to "
            + "transaction-report.txt")
    void theTransactionReportMatchesItsGolden() throws IOException {
        assertGolden(goldenContract(REPORT_BASE));
    }

    @Test
    @Order(13)
    @DisplayName("Gate 1 - the text statement the run produced is byte-identical to statement.txt")
    void theTextStatementMatchesItsGolden() throws IOException {
        assertGolden(goldenContract(STATEMENT_BASE));
    }

    @Test
    @Order(14)
    @DisplayName("Gate 1 - the HTML statement the run produced is byte-identical to statement-html.txt")
    void theHtmlStatementMatchesItsGolden() throws IOException {
        assertGolden(goldenContract(STATEMENT_HTML_BASE));
    }

    @Test
    @Order(15)
    @DisplayName("Gate 1 - every artefact the run produced carries no record separator, because every "
            + "dataset it stands for is declared fixed-length")
    void noProducedArtefactCarriesASeparator() {
        assertThat(run().artefactBases()).isNotEmpty();
        run().artefactBases().forEach(base -> {
            final String text = new String(run().artefact(base), StandardCharsets.US_ASCII);
            assertThat(text)
                    .as("%s must carry no line feed", base)
                    .doesNotContain(String.valueOf((char) LINE_FEED));
            assertThat(text)
                    .as("%s must carry no carriage return", base)
                    .doesNotContain("\r");
        });
    }

    @Test
    @Order(16)
    @DisplayName("Gate 1 - the landing dataset the run consumed is left exactly as it was staged, "
            + "because an input is read and never rewritten")
    void theLandingDatasetIsUnchanged() throws IOException {
        assertThat(Files.readAllBytes(stagingRoot().resolve(LANDING_DATASET)))
                .isEqualTo(classpathBytes(INPUT_FIXTURE));
    }

    @Test
    @Order(17)
    @DisplayName("the extract job no job stream invokes is launched here, because nothing in the "
            + "delivered application will ever call it")
    void theOrphanExtractJobIsLaunchedByThisTreeOrByNothing() {
        final JobExecution extract = run().orphanExecution();
        assertThat(extract.getJobInstance().getJobName())
                .as("the probe launched the orphan by name, which is the only way it is reachable")
                .isEqualTo(ORPHAN_JOB_NAME);
        assertThat(extract.getStatus())
                .as("launching it by name completes it, so being unwired is not being unusable")
                .isEqualTo(BatchStatus.COMPLETED);
        assertThat(extract.getStepExecutions())
                .as("the member is one indivisible pass, so it is one step - it carries no condition "
                        + "gate, no parameter and no place in any sequence, and those absences are the "
                        + "job rather than gaps in it")
                .singleElement()
                .satisfies(step -> assertThat(step.getStatus()).isEqualTo(BatchStatus.COMPLETED));
        assertThat(run().masterCountAfterOrphan())
                .as("the extract reads and reports; it writes nothing, so the master is untouched")
                .isEqualTo(CONSOLIDATED_RECORD_COUNT);
        assertThat(run().executions())
                .as("the orphan is a probe beside the pipeline, never a step within it, so it must not "
                        + "appear among the six the pipeline performed")
                .noneSatisfy(execution -> assertThat(execution.getJobInstance().getJobName())
                        .isEqualTo(ORPHAN_JOB_NAME));
    }

    // ===============================================================================================
    // THE PARITY TRAPS - ONE FOCUSED ASSERTION EACH, OVER WHAT THE RUN PRODUCED
    //
    // Each of these holds already if the byte comparison above passed. They are here so that a failure
    // names the behaviour that broke instead of only the offset that moved, and so that a future edit
    // cannot "improve" one of them without a named test going red.
    // ===============================================================================================

    @Test
    @Order(20)
    @DisplayName("trap - the reject reason is four digits with its leading zero preserved, and its "
            + "description fills the whole 76-byte field")
    void theRejectReasonIsFourDigitsAndItsDescriptionFillsTheField() {
        final List<String> rejects = producedRecords(REJECT_BASE, REJECT_WIDTH);
        assertThat(rejects).hasSize(REJECTED_RECORD_COUNT);

        // The expectation is the HAND TRANSCRIPTION of the legacy source, not the shipped enumeration.
        // Reading the enumeration here would assert the implementation against itself: a code recorded
        // as 0104 would produce an expectation of 0104 and this gate would pass over a file no consumer
        // could read.
        final LegacyRejectReasons.Reason overlimit = LegacyRejectReasons.requireByCode(
                LegacyRejectReasons.OVERLIMIT_TRANSACTION_CODE);

        for (final String reject : rejects) {
            final String reason = reject.substring(SOURCE_IMAGE_WIDTH,
                    SOURCE_IMAGE_WIDTH + REJECT_REASON_WIDTH);
            final String description = reject.substring(SOURCE_IMAGE_WIDTH + REJECT_REASON_WIDTH);
            assertThat(reason)
                    .as("the field is four digits wide, so the overlimit reason is written 0102 and "
                            + "never 102")
                    .isEqualTo("0102")
                    .isEqualTo(overlimit.fourDigitCode());
            assertThat(description)
                    .as("the description is 76 bytes: 21 of text and 55 of space")
                    .hasSize(REJECT_DESCRIPTION_WIDTH)
                    .isEqualTo("OVERLIMIT TRANSACTION" + " ".repeat(55))
                    .isEqualTo(overlimit.paddedDescription());
        }
    }

    @Test
    @Order(21)
    @DisplayName("trap - the other four reject reasons are emitted on no record, which is a measured "
            + "property of the unmodified input and not a gap to be filled with a synthetic path - and "
            + "the trailer contract is still measured for all five")
    void theOtherRejectReasonsAreNotEmitted() {
        final List<String> reasons = producedRecords(REJECT_BASE, REJECT_WIDTH).stream()
                .map(reject -> reject.substring(SOURCE_IMAGE_WIDTH,
                        SOURCE_IMAGE_WIDTH + REJECT_REASON_WIDTH))
                .toList();
        // 0100 needs a card the cross-reference does not hold, and every card in the input is held.
        // 0101 needs the account read to report nothing, and every cross-referenced account is held -
        // and in the migrated schema the cross-reference table's foreign key makes a dangling row
        // impossible, so no INPUT can reach it. That is a statement about the state, not about the code
        // path: the path IS reached, positively, in RejectReasonArmsIT.
        // 0103 needs an account expiring before the single origination date the input carries, and none
        // expires that early - and because its unguarded block runs after the overlimit block, a
        // doubly-invalid record would surface as 0103 rather than 0102, so its absence here is also
        // evidence that no record is doubly invalid.
        // 0109 is assigned inside the account rewrite, which is only reached once the reason is already
        // zero, and nothing re-tests it afterwards: set, never written. Also reached positively in
        // RejectReasonArmsIT, which watches the run set it and post the record regardless.
        //
        // THE FOUR ABSENT CODES ARE NAMED FROM THE TRANSCRIPTION, NOT FROM FOUR LITERALS AND NOT FROM
        // THE SHIPPED ENUMERATION. A bare doesNotContain over hand-typed literals passes vacuously the
        // moment a code drifts - the drifted code is not among the literals, so nothing is contained and
        // nothing fails. Deriving the four from LegacyRejectReasons removes that escape: whatever the
        // legacy source sets, this asserts about.
        final List<String> absentCodes = LegacyRejectReasons.REASONS.stream()
                .filter(reason -> reason.code() != LegacyRejectReasons.OVERLIMIT_TRANSACTION_CODE)
                .map(LegacyRejectReasons.Reason::fourDigitCode)
                .toList();
        assertThat(absentCodes)
                .as("four of the five transcribed reasons, the overlimit one excepted")
                .containsExactly("0100", "0101", "0103", "0109");
        assertThat(reasons)
                .as("no synthetic path is invented to reach these inside the byte-parity gate, and no"
                        + " fifth expected file exists: the delivered input is processed UNMODIFIED,"
                        + " which is the whole point of this gate")
                .doesNotContainAnyElementsOf(absentCodes);

        // AND THE TRAILER CONTRACT IS CARRIED FOR ALL FIVE, NOT ONLY FOR THE ONE THIS RUN EMITS. The
        // run's output legitimately carries one reason, because the delivered input reaches one. That
        // does not licence leaving the other four unmeasured here: each is put through the PRODUCTION
        // trailer assembler and compared against the eighty characters the legacy source's own values
        // produce, so a drift in any of the five fails this gate rather than only the one in use.
        assertThat(LegacyRejectReasons.REASONS).hasSize(5);
        for (final LegacyRejectReasons.Reason transcribed : LegacyRejectReasons.REASONS) {
            final RejectReason shipped = RejectReason.byReasonCode(transcribed.code())
                    .orElseThrow(() -> new AssertionError("the legacy source sets reason "
                            + transcribed.fourDigitCode() + " at " + transcribed.sourceLocation()
                            + ", but the shipped enumeration recognises no such code"));
            assertThat(shipped.name())
                    .as("%s must be the reason that arises when %s: the two account-not-found reasons"
                            + " share one description and are told apart by nothing but these four"
                            + " digits, so a transposition would be invisible to a code-only check",
                            transcribed.fourDigitCode(), transcribed.role())
                    .isEqualTo(transcribed.shippedConstantName());
            assertThat(RejectRecordWriter.validationTrailer(shipped))
                    .as("the eighty-character trailer for %s: four digits zero-filled on the left,"
                            + " then the description the source moves at %s blank-padded on the right",
                            transcribed.fourDigitCode(), transcribed.sourceLocation())
                    .isEqualTo(transcribed.trailer())
                    .hasSize(REJECT_REASON_WIDTH + REJECT_DESCRIPTION_WIDTH);
        }
    }

    @Test
    @Order(22)
    @DisplayName("trap - a reject record carries the source image unchanged in its leading 350 bytes: "
            + "not re-padded, not re-encoded, and not regenerated through a mapper")
    void aRejectRecordCarriesTheSourceImageUnchanged() throws IOException {
        final Set<String> staged = new LinkedHashSet<>(inputRecords(namedInput(INPUT_FIXTURE_NAME)));
        final List<String> prefixes = producedRecords(REJECT_BASE, REJECT_WIDTH).stream()
                .map(reject -> reject.substring(0, SOURCE_IMAGE_WIDTH))
                .toList();
        assertThat(prefixes)
                .as("every prefix is one of the staged 350-byte images, byte for byte - the sign "
                        + "overpunch, the space filler and the blank processing stamp all included")
                .hasSize(REJECTED_RECORD_COUNT)
                .allSatisfy(prefix -> assertThat(staged).contains(prefix));
    }

    @Test
    @Order(23)
    @DisplayName("trap - the statement banners are asymmetric on purpose: 31 stars around an "
            + "18-character opening and 32 around a 16-character close")
    void theStatementBannersAreAsymmetric() {
        final List<String> records = producedRecords(STATEMENT_BASE, STATEMENT_WIDTH);
        final String opening = "*".repeat(31) + "START OF STATEMENT" + "*".repeat(31);
        final String closing = "*".repeat(32) + "END OF STATEMENT" + "*".repeat(32);
        assertThat(opening).hasSize(STATEMENT_WIDTH);
        assertThat(closing).hasSize(STATEMENT_WIDTH);
        assertThat(records.stream().filter(opening::equals).toList())
                .as("one opening banner per statement, and it is not regularised to match the close")
                .hasSize(STATEMENT_COUNT);
        assertThat(records.stream().filter(closing::equals).toList())
                .as("one closing banner per statement")
                .hasSize(STATEMENT_COUNT);
    }

    @Test
    @Order(24)
    @DisplayName("trap - the statement write order repeats records rather than de-duplicating them: "
            + "six identical separator lines per statement, and 19 fixed records around each body")
    void theStatementWriteOrderRepeatsRecords() {
        final List<String> records = producedRecords(STATEMENT_BASE, STATEMENT_WIDTH);
        final String separator = "-".repeat(STATEMENT_WIDTH);
        assertThat(records.stream().filter(separator::equals).toList())
                .as("the initialise that precedes each statement leaves filler items alone, so every "
                        + "separator caption survives and the same 80-byte line is written six times "
                        + "per statement")
                .hasSize(6 * STATEMENT_COUNT);
        assertThat(records)
                .as("16 header records and 3 tail records per statement, plus one record per "
                        + "transaction: %d = %d x 19 + %d", STATEMENT_RECORD_COUNT, STATEMENT_COUNT,
                        CONSOLIDATED_RECORD_COUNT)
                .hasSize(STATEMENT_COUNT * 19 + CONSOLIDATED_RECORD_COUNT);
    }

    @Test
    @Order(25)
    @DisplayName("trap - the current balance prints its leading zeros while the transaction amount "
            + "suppresses them, and neither is ever blanked when zero")
    void theCurrentBalancePrintsLeadingZerosAndTheAmountSuppressesThem() {
        for (final List<String> block : statementBlocks()) {
            final String balanceField = block.get(9).substring(20, 33);
            assertThat(balanceField)
                    .as("the balance picture has no suppression character, so all nine integer "
                            + "positions print, and its sign position trails the value")
                    .hasSize(13)
                    .matches("[0-9]{9}\\.[0-9]{2}[ -]");
            for (final String detail : detailRecordsOf(block)) {
                assertThat(detail.substring(67))
                        .as("the transaction amount does suppress its integer positions - all nine of "
                                + "them, the units digit included - yet it always prints its decimal "
                                + "point and its cents, because no blank-when-zero clause is specified "
                                + "anywhere in the estate")
                        .hasSize(13)
                        .matches(" *[0-9]*\\.[0-9]{2}[ -]")
                        .contains(".");
            }
        }
    }

    @Test
    @Order(26)
    @DisplayName("trap - the synthesised interest description is 24 characters followed by 25 spaces, "
            + "because the concatenation writes 24 bytes and space-fills nothing")
    void theSynthesisedInterestDescriptionIsNotSpaceFilledByTheConcatenation() {
        final List<String> interestLines = producedRecords(STATEMENT_BASE, STATEMENT_WIDTH).stream()
                .filter(record -> record.startsWith(INTEREST_PARM_DATE))
                .toList();
        assertThat(interestLines)
                .as("one synthesised transaction per account reaches the statement")
                .hasSize(SYNTHESISED_RECORD_COUNT);
        for (final String line : interestLines) {
            final String description = line.substring(17, 66);
            assertThat(description).hasSize(49);
            assertThat(description.substring(0, 24))
                    .as("13 characters of caption and the 11-digit account identifier, and the "
                            + "concatenation stops there")
                    .isEqualTo("Int. for a/c " + description.substring(13, 24));
            assertThat(description.substring(13, 24)).matches("[0-9]{11}");
            assertThat(description.substring(24))
                    .as("the 25 bytes behind it were never touched by the concatenation")
                    .isEqualTo(" ".repeat(25));
        }
    }

    @Test
    @Order(27)
    @DisplayName("trap - exactly one account's current balance excludes its own interest while its "
            + "total includes it, because the branch that would have applied it cannot be reached")
    void exactlyOneAccountBalanceExcludesItsOwnInterest() throws IOException {
        // The opening balances come from the committed account fixture, decoded through the test
        // support factory's own overpunch codec. That codec is an independent implementation: the
        // production codec, which is one of the things this comparison is checking, is deliberately not
        // consulted anywhere in this file.
        final Map<String, BigDecimal> openingBalances = new LinkedHashMap<>();
        for (final String account : inputRecords(namedInput("acctdata.txt"))) {
            openingBalances.put(account.substring(0, 11),
                    TestDataFactory.decodeZonedDecimal(account.substring(12, 24)));
        }
        assertThat(openingBalances).hasSize(STATEMENT_COUNT);

        final List<String> diverging = new ArrayList<>();
        for (final List<String> block : statementBlocks()) {
            final String accountId = block.get(8).substring(20, 31);
            final BigDecimal balance = trailingSignAmount(block.get(9).substring(20, 33));
            final List<String> details = detailRecordsOf(block);
            final BigDecimal interest = details.stream()
                    .filter(detail -> detail.startsWith(INTEREST_PARM_DATE))
                    .map(detail -> trailingSignAmount(detail.substring(67)))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            final BigDecimal posted = details.stream()
                    .filter(detail -> !detail.startsWith(INTEREST_PARM_DATE))
                    .map(detail -> trailingSignAmount(detail.substring(67)))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            final BigDecimal opening = openingBalances.get(accountId);
            assertThat(opening).as("account %s is one of the seeded fifty", accountId).isNotNull();

            final BigDecimal withInterest = opening.add(posted).add(interest);
            final BigDecimal withoutInterest = opening.add(posted);
            if (balance.compareTo(withInterest) == 0) {
                continue;
            }
            assertThat(balance)
                    .as("account %s must either include its interest or exclude it, never differ by "
                            + "some third amount", accountId)
                    .isEqualByComparingTo(withoutInterest);
            assertThat(interest)
                    .as("an account can only be seen to exclude its interest if it had some")
                    .isNotEqualByComparingTo(BigDecimal.ZERO);
            diverging.add(accountId);
        }
        assertThat(diverging)
                .as("the accrual run applies an account's accumulated interest on the control break "
                        + "into the NEXT account, and the branch that would have applied it to the last "
                        + "account sits in an else the loop's test-before evaluation can never enter. "
                        + "The balance of the highest-keyed account therefore omits the interest its "
                        + "own statement lists and totals. This is reproduced deliberately: it is a "
                        + "defect of the member, and correcting it here would be a behavioural change")
                .containsExactly("00000000050");
    }

    @Test
    @Order(28)
    @DisplayName("trap - no fee amount appears anywhere, because the paragraph the rate gate invokes "
            + "alongside interest has an empty body")
    void noFeeAmountAppearsAnywhere() {
        for (final List<String> block : statementBlocks()) {
            final List<String> details = detailRecordsOf(block);
            final BigDecimal listed = details.stream()
                    .map(detail -> trailingSignAmount(detail.substring(67)))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            assertThat(trailingSignAmount(block.get(block.size() - 2).substring(67)))
                    .as("the total equals the posted and synthesised lines with nothing added, so no "
                            + "fee was invented to fill the empty paragraph")
                    .isEqualByComparingTo(listed);
            assertThat(details.stream()
                    .filter(detail -> detail.startsWith(INTEREST_PARM_DATE))
                    .toList())
                    .as("the rate gate produces at most one synthesised line per account, and never a "
                            + "second one for a fee")
                    .hasSizeLessThanOrEqualTo(1);
        }
    }

    @Test
    @Order(30)
    @DisplayName("trap - the double space inside the HTML table tag survives; it is the canary for "
            + "every kind of whitespace cleanup")
    void theDoubleSpaceInsideTheTableTagSurvives() {
        final List<String> openers = producedRecords(STATEMENT_HTML_BASE, STATEMENT_HTML_WIDTH).stream()
                .filter(record -> record.startsWith("<table"))
                .toList();
        assertThat(openers)
                .as("one table opener per document")
                .hasSize(STATEMENT_COUNT);
        assertThat(new LinkedHashSet<>(openers))
                .as("and every one of them is the same 100-byte record")
                .hasSize(1);
        for (final String opener : openers) {
            assertThat(opener)
                    .as("two spaces follow the tag name; collapsing them to one would be a "
                            + "normalisation, and normalisation is what this assertion exists to catch")
                    .startsWith("<table  align=")
                    .doesNotContain("<table align=");
            assertPaddedRecord(opener, 85, STATEMENT_HTML_WIDTH);
        }
    }

    @Test
    @Order(31)
    @DisplayName("trap - ten cell openers, two spacing behaviours: the four spanning variants carry no "
            + "space before the colour and the six proportional variants do")
    void theTwoCellSpacingBehavioursAreBothPreserved() {
        final List<String> cells = producedRecords(STATEMENT_HTML_BASE, STATEMENT_HTML_WIDTH).stream()
                .filter(record -> record.startsWith("<td "))
                .toList();
        final Set<String> spanning = new LinkedHashSet<>();
        final Set<String> proportional = new LinkedHashSet<>();
        for (final String cell : cells) {
            if (cell.contains("colspan=\"3\"")) {
                spanning.add(cell);
                assertThat(cell)
                        .as("a spanning cell writes the colour immediately after the semicolon")
                        .contains(";background-color:")
                        .doesNotContain("; background-color:");
            } else {
                proportional.add(cell);
                assertThat(cell)
                        .as("a proportional cell writes a space before the colour")
                        .contains("; background-color:")
                        .contains("width:");
            }
        }
        assertThat(spanning)
                .as("four spanning variants, normalised in neither direction")
                .hasSize(4);
        assertThat(proportional)
                .as("six proportional variants, normalised in neither direction")
                .hasSize(6);
    }

    @Test
    @Order(32)
    @DisplayName("trap - no bare cell opener is ever emitted although the closer is, so the markup "
            + "stays unbalanced exactly as the member leaves it")
    void noBareCellOpenerIsEverEmitted() {
        final List<String> records = producedRecords(STATEMENT_HTML_BASE, STATEMENT_HTML_WIDTH);
        assertThat(records.stream().filter(record -> isPaddedLiteral(record, "<td>")).toList())
                .as("the constant that would produce it is declared and never set, and adding one to "
                        + "balance the markup would be an invention")
                .isEmpty();
        assertThat(records.stream().filter(record -> isPaddedLiteral(record, "</td>")).toList())
                .as("the closer, by contrast, is emitted")
                .isNotEmpty();
        assertThat(records.stream().filter(record -> isPaddedLiteral(record, "<tr>")).toList())
                .as("row openers and closers do balance")
                .hasSameSizeAs(records.stream().filter(record -> isPaddedLiteral(record, "</tr>"))
                        .toList());
    }

    @Test
    @Order(33)
    @DisplayName("trap - the document scaffold repeats once per account, so one file holds fifty "
            + "complete documents and is not hoisted into one")
    void theDocumentScaffoldRepeatsOncePerAccount() {
        final List<String> records = producedRecords(STATEMENT_HTML_BASE, STATEMENT_HTML_WIDTH);
        assertThat(records.stream().filter(record -> isPaddedLiteral(record, "<!DOCTYPE html>"))
                .toList())
                .as("one document declaration per account")
                .hasSize(STATEMENT_COUNT);
        assertThat(records.stream().filter(record -> isPaddedLiteral(record, "</html>")).toList())
                .as("and one closing element per account")
                .hasSize(STATEMENT_COUNT);
        assertThat(records)
                .as("64 scaffold records per account and 11 per transaction: %d = %d x 64 + 11 x %d",
                        STATEMENT_HTML_RECORD_COUNT, STATEMENT_COUNT, CONSOLIDATED_RECORD_COUNT)
                .hasSize(STATEMENT_COUNT * 64 + 11 * CONSOLIDATED_RECORD_COUNT);
    }

    @Test
    @Order(34)
    @DisplayName("trap - the account heading keeps the nine interior spaces the wider field left "
            + "behind, inside the element rather than after it")
    void theAccountHeadingKeepsItsInteriorSpaces() {
        final List<String> headings = producedRecords(STATEMENT_HTML_BASE, STATEMENT_HTML_WIDTH)
                .stream()
                .filter(record -> record.startsWith("<h3>Statement for Account Number:"))
                .toList();
        assertThat(headings).hasSize(STATEMENT_COUNT);
        for (final String heading : headings) {
            final int closing = heading.indexOf("</h3>");
            assertThat(closing).as("the heading closes its element").isEqualTo(54);
            assertThat(heading.substring(34, 45))
                    .as("eleven digits of account identifier")
                    .matches("[0-9]{11}");
            assertThat(heading.substring(45, closing))
                    .as("the nine spaces belong inside the element: the identifier was moved into a "
                            + "twenty-character field and the closing tag follows the whole field")
                    .isEqualTo(" ".repeat(9));
            assertPaddedRecord(heading, 59, STATEMENT_HTML_WIDTH);
        }
    }

    @Test
    @Order(40)
    @DisplayName("trap - the report's separator line is 133 hyphens at its native width, its three "
            + "total leaders show the measured dot-run asymmetry, and its column heading ends with "
            + "eight spaces before the word")
    void theReportFurnitureKeepsItsMeasuredWidths() {
        final List<String> records = producedRecords(REPORT_BASE, REPORT_WIDTH);
        // One separator closes each header block, one closes each page-total block, and one closes each
        // account-total block - so the count is a consequence of the page and control-break structure
        // rather than a figure of its own.
        final int expectedSeparators =
                REPORT_PAGE_COUNT + REPORT_PAGE_COUNT + (STATEMENT_COUNT - 1);
        assertThat(records.stream().filter(record -> "-".repeat(REPORT_WIDTH).equals(record)).toList())
                .as("the separator is declared at the record width itself, so it is not a shorter "
                        + "literal padded out")
                .hasSize(expectedSeparators);
        assertDotRun(records, "Page Total ", 11, 86);
        assertDotRun(records, "Account Total", 13, 84);
        assertDotRun(records, "Grand Total", 11, 86);
        for (final String heading : records.stream()
                .filter(record -> record.startsWith("Transaction ID")).toList()) {
            assertThat(heading.substring(98, 114))
                    .as("the last heading field is sixteen bytes carrying eight leading spaces, the "
                            + "word, and two trailing spaces")
                    .isEqualTo("        Amount  ");
            assertThat(heading.substring(114))
                    .as("and the group is 114 bytes moved into a 133-byte record")
                    .isEqualTo(" ".repeat(19));
        }
    }

    @Test
    @Order(41)
    @DisplayName("trap - every report amount is fifteen characters, and a value of exactly zero renders "
            + "as fifteen spaces here while the statement prints its cents")
    void aZeroReportAmountRendersAsSpaces() {
        final List<String> details = reportRecordsOfKind(ReportRecordKind.DETAIL);
        assertThat(details).hasSize(CONSOLIDATED_RECORD_COUNT);
        int blanked = 0;
        for (final String detail : details) {
            final String amount = reportAmountOf(detail);
            assertThat(amount).hasSize(REPORT_AMOUNT_WIDTH);
            if (" ".repeat(REPORT_AMOUNT_WIDTH).equals(amount)) {
                blanked++;
            } else {
                assertThat(amount)
                        .as("the detail mask carries a fixed leading sign position that prints a space "
                                + "when the value is not negative, and suppresses every integer "
                                + "position behind it")
                        .matches("[ -] *[0-9,]*\\.[0-9]{2}");
            }
        }
        assertThat(blanked)
                .as("the detail picture suppresses every leading position, so an amount of exactly "
                        + "zero disappears entirely - which the 80-byte and 100-byte contracts never do")
                .isEqualTo(1);
        for (final ReportRecordKind kind : List.of(ReportRecordKind.PAGE_TOTAL,
                ReportRecordKind.ACCOUNT_TOTAL, ReportRecordKind.GRAND_TOTAL)) {
            assertThat(reportRecordsOfKind(kind))
                    .as("a total always carries an explicit sign, so it is never blanked")
                    .isNotEmpty()
                    .allSatisfy(total -> assertThat(reportAmountOf(total))
                            .hasSize(REPORT_AMOUNT_WIDTH)
                            .matches("[+-] *[0-9,]*\\.[0-9]{2}"));
        }
    }

    @Test
    @Order(42)
    @DisplayName("trap - page totals roll into the grand total and account totals do not, which is why "
            + "the two sums differ")
    void onlyPageTotalsRollIntoTheGrandTotal() {
        final BigDecimal pageTotals = sumOfReportAmounts(ReportRecordKind.PAGE_TOTAL);
        final BigDecimal accountTotals = sumOfReportAmounts(ReportRecordKind.ACCOUNT_TOTAL);
        final BigDecimal grandTotal = sumOfReportAmounts(ReportRecordKind.GRAND_TOTAL);
        assertThat(grandTotal)
                .as("the grand total accumulates from the page total and from nowhere else")
                .isEqualByComparingTo(pageTotals);
        assertThat(accountTotals)
                .as("the account total is reset on the control break without being added anywhere, so "
                        + "it does not reconcile with the grand total and must not be made to")
                .isNotEqualByComparingTo(grandTotal);
    }

    @Test
    @Order(43)
    @DisplayName("trap - the last amount is counted twice at end of file, so the page and grand totals "
            + "exceed the sum of the detail lines by exactly that amount")
    void theLastAmountIsDoubleCountedAtEndOfFile() {
        final List<String> details = reportRecordsOfKind(ReportRecordKind.DETAIL);
        final BigDecimal detailSum = details.stream()
                .map(detail -> editedAmount(reportAmountOf(detail)))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        final BigDecimal lastDetail =
                editedAmount(reportAmountOf(details.get(details.size() - 1)));
        assertThat(sumOfReportAmounts(ReportRecordKind.PAGE_TOTAL).subtract(detailSum))
                .as("the end-of-file branch adds the amount still held from the last record into both "
                        + "running totals before writing them, so the overstatement is exactly the last "
                        + "detail amount and no other figure")
                .isEqualByComparingTo(lastDetail);
    }

    @Test
    @Order(44)
    @DisplayName("trap - pagination counts every written line and not every detail line, so a total "
            + "block that steps over a page boundary misses the break entirely")
    void paginationCountsEveryWrittenLine() {
        final List<String> records = producedRecords(REPORT_BASE, REPORT_WIDTH);
        final List<Integer> pageTotalLines = new ArrayList<>();
        final List<Integer> headingLines = new ArrayList<>();
        for (int index = 0; index < records.size(); index++) {
            final ReportRecordKind kind = kindOf(records.get(index));
            if (kind == ReportRecordKind.PAGE_TOTAL) {
                pageTotalLines.add(Integer.valueOf(index));
            } else if (kind == ReportRecordKind.NAME_HEADER) {
                headingLines.add(Integer.valueOf(index));
            }
        }
        assertThat(pageTotalLines).as("the report broke into pages at all").isNotEmpty();
        assertThat(pageTotalLines.subList(0, pageTotalLines.size() - 1))
                .as("every page break but the last one is taken because the line counter reached a "
                        + "multiple of the twenty-line page, counting headers, totals and details alike")
                .allSatisfy(line -> assertThat(line.intValue() % REPORT_PAGE_SIZE).isZero());
        assertThat(pageTotalLines.get(pageTotalLines.size() - 1).intValue() % REPORT_PAGE_SIZE)
                .as("the final page total is written unconditionally at end of file rather than by the "
                        + "page test, so it does not land on a boundary")
                .isNotZero();

        final List<Integer> gaps = new ArrayList<>();
        for (int index = 1; index < headingLines.size(); index++) {
            gaps.add(Integer.valueOf(
                    headingLines.get(index).intValue() - headingLines.get(index - 1).intValue()));
        }
        assertThat(gaps)
                .as("a two-line account-total block written just before the page test can carry the "
                        + "counter past the multiple of twenty, and the test then does not fire until "
                        + "the next one - so some pages run to forty lines. That is a defect of the "
                        + "member and it is reproduced rather than corrected")
                .contains(Integer.valueOf(2 * REPORT_PAGE_SIZE))
                .allSatisfy(gap -> assertThat(gap.intValue()).isGreaterThanOrEqualTo(REPORT_PAGE_SIZE));
    }

    @Test
    @Order(45)
    @DisplayName("trap - the first record writes no account total, so fifty accounts produce forty-nine "
            + "of them, and the account identifier is carried forward between records of one card")
    void theFirstRecordWritesNoAccountTotal() {
        final List<String> details = reportRecordsOfKind(ReportRecordKind.DETAIL);
        assertThat(new LinkedHashSet<>(details.stream().map(row -> row.substring(17, 28)).toList()))
                .as("every seeded account reaches the report")
                .hasSize(STATEMENT_COUNT);
        assertThat(reportRecordsOfKind(ReportRecordKind.ACCOUNT_TOTAL))
                .as("the control break writes the previous account's total, and on the very first "
                        + "record there is no previous account")
                .hasSize(STATEMENT_COUNT - 1);
        assertThat(details)
                .as("the account identifier comes from a lookup that fires only when the card number "
                        + "changes, so it is carried forward and never blank")
                .allSatisfy(row -> assertThat(row.substring(17, 28)).matches("[0-9]{11}"));
    }

    @Test
    @Order(46)
    @DisplayName("trap - the synthesised interest lines reach the report under the category and source "
            + "the member assigns, with a sixteen-byte identifier of the parameter date and a suffix")
    void theSynthesisedInterestLinesReachTheReport() {
        final List<String> interest = reportRecordsOfKind(ReportRecordKind.DETAIL).stream()
                .filter(row -> row.startsWith(INTEREST_PARM_DATE))
                .toList();
        assertThat(interest).hasSize(SYNTHESISED_RECORD_COUNT);
        for (final String row : interest) {
            assertThat(row.substring(0, 16))
                    .as("ten characters of parameter date and a six-digit suffix, sixteen bytes with no "
                            + "padding")
                    .hasSize(16)
                    .matches(INTEREST_PARM_DATE + "[0-9]{6}");
            assertThat(row.substring(29, 31)).as("transaction type").isEqualTo("01");
            assertThat(row.substring(48, 52)).as("transaction category").isEqualTo("0005");
            assertThat(row.substring(53, 82))
                    .as("the category description resolves, so the lookup does not abend and the "
                            + "interest lines are genuinely reachable")
                    .isEqualTo("Interest Amount" + " ".repeat(14));
            assertThat(row.substring(83, 93))
                    .as("the source is a six-character literal in a ten-character field")
                    .isEqualTo("System    ");
        }
    }

    /**
     * The accrual run resolves every rate through the padded default group, and the zero-rate gate closes
     * on some of those rows.
     *
     * <p><strong>What this cannot show, said here rather than left to be inferred.</strong> Because every
     * seeded account's group identifier is ten spaces, every rate in this run is resolved by the fallback.
     * The zero-rate gate therefore closes on a rate the fallback supplied, and a rate of zero reached
     * through a <em>direct</em> group hit - the account's own group identifier resolving a row that
     * discloses zero - is not exercised anywhere in this run and is not claimed to be. Reaching it needs an
     * account constructed with the padded zero-rate group plus a matching category balance, which is
     * outside what a seed-driven pipeline run can do; {@code batch/InterestCalculationJobConfigIT} installs
     * exactly that and proves it. The distinction matters because the two paths differ in the resolver and
     * not only in the gate.
     */
    @Test
    @Order(47)
    @DisplayName("trap - the accrual run takes the padded default disclosure group for every row, so the "
            + "zero-rate gate closes on a FALLBACK rate here and the direct-hit half of that branch is "
            + "proven by batch/InterestCalculationJobConfigIT instead")
    void theAccrualRunTakesTheDefaultGroupForEveryRow() {
        final double rows = counterTotal("carddemo.batch.interest.rows");
        final double synthesised = counterTotal("carddemo.batch.interest.transactions");
        final double skipped = counterTotal("carddemo.batch.interest.rate.gate.skips");
        final double fallbacks = counterTotal("carddemo.batch.interest.default.group.fallbacks");

        assertThat(synthesised)
                .as("one synthesised transaction per account, which is what the two reading goldens "
                        + "then carry")
                .isEqualTo(SYNTHESISED_RECORD_COUNT);
        assertThat(rows)
                .as("every row read either accrued or was gated, and nothing else can happen to one")
                .isEqualTo(synthesised + skipped);
        assertThat(skipped)
                .as("the padded default group's zero-rate rows are reachable from seeded data, so the "
                        + "gate that skips both the computation and the fee invocation closes during this "
                        + "run. What this run CANNOT show is the other half of that branch: every rate "
                        + "here is resolved by the fallback, so a rate of zero reached through a DIRECT "
                        + "group hit is not exercised by any seeded row and is not claimed to be. That "
                        + "half needs an account constructed with the padded zero-rate group and is "
                        + "proven by batch/InterestCalculationJobConfigIT")
                .isGreaterThan(0.0d);
        assertThat(fallbacks)
                .as("the account group identifier is ten spaces on every seeded account, so every "
                        + "lookup misses and every row takes the not-found fallback to the padded "
                        + "default group - a different group would have produced a different rate on at "
                        + "least one key, and therefore different bytes")
                .isEqualTo(rows);
    }

    // ===============================================================================================
    // GATE 3 - THE BASELINE. PRESENCE AND SHAPE ONLY; NO MAGNITUDE IS ASSERTED ANYWHERE.
    // ===============================================================================================

    @Test
    @Order(50)
    @DisplayName("Gate 3 - the run's own instruments exist and their counts agree with what it "
            + "processed; no elapsed time, throughput or heap figure is asserted")
    void theRunIsInstrumentedAndItsCountsAgreeWithWhatItProcessed() {
        final List<Timer> stepTimers = List.copyOf(
                this.meterRegistry.find("carddemo.batch.job.step").timers());
        assertThat(stepTimers)
                .as("the step boundary is timed, which is where the elapsed figure comes from")
                .isNotEmpty();
        assertThat(stepTimers)
                .as("a duration is a measurement: it has to be readable and finite, and nothing here "
                        + "says it has to be small")
                .allSatisfy(timer -> {
                    assertThat(timer.count()).isPositive();
                    assertThat(timer.totalTime(TimeUnit.NANOSECONDS))
                            .isNotNegative()
                            .isFinite();
                });

        assertThat(counterTotal("carddemo.batch.posting.records"))
                .as("the posting counters account for every record the run read")
                .isEqualTo(INPUT_RECORD_COUNT);
        assertThat(timerCount("carddemo.batch.posting.record"))
                .as("and the per-record timer fired once for each of them")
                .isEqualTo(INPUT_RECORD_COUNT);
        assertThat(counterTotal("carddemo.batch.reject.records"))
                .as("the reject counter agrees with the reject dataset the run wrote")
                .isEqualTo(REJECTED_RECORD_COUNT);
        assertThat(counterTotal("carddemo.batch.report.records"))
                .as("the report counter agrees with every record the report carries - the furniture as "
                        + "well as the detail lines, because the emitter counts what it wrote")
                .isEqualTo(REPORT_RECORD_COUNT);
        assertThat(counterTotal("carddemo.batch.statement.statements"))
                .as("the statement counter agrees with the number of statements emitted")
                .isEqualTo(STATEMENT_COUNT);
        assertThat(List.copyOf(this.meterRegistry.find("carddemo.batch.cobol.step").timers()))
                .as("the orphan extract is instrumented on the same template as the wired steps")
                .isNotEmpty();
    }

    @Test
    @Order(51)
    @DisplayName("Gate 3 - the baseline is recorded with the fixture volumes it was measured over, "
            + "because a figure without its volumes cannot be compared to anything later")
    void theBaselineIsRecordedWithTheVolumesItWasMeasuredOver() {
        final List<RunScopedPerformanceRecorder.RunBaseline> baselines =
                this.performance.baselines();
        assertThat(baselines)
                .as("the posting run and the accrual run were both measured")
                .hasSize(2)
                .extracting(RunScopedPerformanceRecorder.RunBaseline::label)
                .containsExactly("postTransactionJob", "interestCalculationJob");
        assertThat(baselines).allSatisfy(baseline -> {
            assertThat(baseline.fixtureNote())
                    .as("no recorded figure may be quoted without the volumes behind it")
                    .isNotBlank();
            assertThat(baseline.records())
                    .as("a record count was read from the run itself")
                    .isPositive();
            assertThat(baseline.elapsedNanos()).as("elapsed time is readable").isNotNegative();
            assertThat(baseline.peakHeapBytes())
                    .as("peak heap is read from the platform's own accounting rather than from a "
                            + "sampled series, and no JVM sizing is imposed anywhere to make it so")
                    .isNotNegative();
            assertThat(baseline.recordsPerSecond())
                    .as("the derived rate is the run's own quotient and is recorded, not gated")
                    .isNotNull();
        });
        assertThat(baselines.get(0).records())
                .as("the posting baseline was measured over the whole committed input")
                .isEqualTo(INPUT_RECORD_COUNT);
    }

    // ===============================================================================================
    // THE GATE 1 DELIVERABLE - A COMPARISON REPORT
    // ===============================================================================================

    @Test
    @Order(60)
    @DisplayName("Gate 1 - the comparison report names the input, the golden, both byte lengths, both "
            + "record counts and the match status, one row per contract")
    void theComparisonReportCoversAllFourContracts() throws IOException {
        // The rows are built in fixture setup, one per declared contract, so this holds whichever tests
        // were selected. Accumulating them as the four comparison tests ran was the second ordering
        // dependency in this class: this assertion was then a statement about which tests had executed
        // rather than about what the run produced.
        final List<ContractComparison> comparisons = run().comparisons();
        assertThat(comparisons)
                .as("one row per fixed-width contract the pipeline emits")
                .hasSize(4)
                .extracting(ContractComparison::recordWidth)
                .containsExactly(REJECT_WIDTH, REPORT_WIDTH, STATEMENT_WIDTH, STATEMENT_HTML_WIDTH);
        assertThat(comparisons).allSatisfy(row -> {
            assertThat(row.inputResource()).isEqualTo(INPUT_DIRECTORY + INPUT_FIXTURE_NAME);
            assertThat(row.goldenResource()).startsWith(GOLDEN_DIRECTORY);
            assertThat(row.matched()).as("%s must match", row.goldenResource()).isTrue();
            assertThat(row.actualByteCount()).isEqualTo(row.expectedByteCount());
            assertThat(row.actualRecordCount()).isEqualTo(row.expectedRecordCount());
        });
        final Path published = publishComparisonReport(run());
        assertThat(published)
                .as("the report is written where the evidence page can take it up")
                .isNotNull()
                .exists();
        assertThat(Files.readString(published, StandardCharsets.UTF_8))
                .as("and it carries the provenance the matrix and the decision log cite")
                .contains("7756d895ffeb65f7ea72aaa609e356d9899afcec")
                .contains("CardDemo_v1.0-15-g27d6c6f-68");
    }

    // ===============================================================================================
    // LAUNCHING
    // ===============================================================================================

    /**
     * Launches one registered job, waits for it to reach a terminal state, and records its execution.
     *
     * <p><strong>The wait is part of the launch contract rather than a concession to timing.</strong> The
     * coordinator reserves the execution on the calling thread and runs the job on its own bounded workers,
     * so the identifier comes back while the run is still in flight - which is exactly what stops a long
     * job from holding a request thread. A caller that needs the outcome asks the store for it, and that is
     * what this does: it polls the framework's own metadata until the execution is no longer running, which
     * is the same thing the status endpoint an operator would use reports.
     *
     * @param  jobName    the job's registered name
     * @param  parameters the caller parameters the job declares
     * @return the completed execution
     * @throws Exception if the launcher refuses the launch or the run does not finish in time
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
        // one's completed instance. The instances are removed again in the teardown above.
        final long executionId = this.launcher.start(job, parameters);
        // Recorded HERE, before anything below can throw. The metadata rows exist from the moment the
        // coordinator issues this identifier, so this is the first instant at which the row is owed - and
        // the assertion two statements down, the explorer lookup above it, and every later step of setup
        // can all fail. Teardown works from this ledger, so a setup that gets three launches in has three
        // launches cleaned up.
        this.ownedExecutionIds.add(Long.valueOf(executionId));
        final JobExecution execution = awaitTerminalState(jobName, executionId);
        assertThat(execution.getStatus())
                .as("%s must complete; failures were %s", jobName, execution.getAllFailureExceptions())
                .isEqualTo(BatchStatus.COMPLETED);
        return execution;
    }

    /**
     * Reads one execution out of the framework's metadata until it is no longer running.
     *
     * <p>Polls rather than waits on the launcher, because polling is the only thing a client of the launch
     * surface can do: the coordinator hands the job to a worker and publishes nothing else to wait on. The
     * budget is generous rather than tight - it is the point at which this class concludes the run has
     * stalled, not a duration any job is asked to meet - and the interval is short so that a fast job is
     * not held up by the polling itself.
     *
     * @param  jobName     the job's registered name, for the diagnostic
     * @param  executionId the identifier the launch answered with
     * @return the execution in its terminal state
     * @throws InterruptedException if the wait is interrupted
     */
    private JobExecution awaitTerminalState(final String jobName, final long executionId)
            throws InterruptedException {

        final long deadline = System.nanoTime()
                + TimeUnit.MILLISECONDS.toNanos(LAUNCH_COMPLETION_BUDGET_MILLIS);
        JobExecution execution = Objects.requireNonNull(
                this.jobExplorer.getJobExecution(Long.valueOf(executionId)),
                () -> "the coordinator reported execution " + executionId + " but the store has none");
        while (execution.isRunning() && System.nanoTime() < deadline) {
            TimeUnit.MILLISECONDS.sleep(LAUNCH_POLL_INTERVAL_MILLIS);
            execution = Objects.requireNonNull(
                    this.jobExplorer.getJobExecution(Long.valueOf(executionId)),
                    () -> "execution " + executionId + " disappeared from the store while it ran");
        }
        assertThat(execution.isRunning())
                .as("%s (execution %d) was still running after %d ms, so the launch worker never"
                                + " finished it", jobName, Long.valueOf(executionId),
                        Long.valueOf(LAUNCH_COMPLETION_BUDGET_MILLIS))
                .isFalse();
        return execution;
    }

    /**
     * Sums the records every step of one execution read, for the recorded baseline.
     *
     * @param  execution the completed execution
     * @return the records its steps read
     */
    private static long readCountOf(final JobExecution execution) {
        long read = 0L;
        for (final StepExecution step : execution.getStepExecutions()) {
            read += step.getReadCount();
        }
        return read;
    }

    // ===============================================================================================
    // READING WHAT THE RUN PRODUCED
    // ===============================================================================================

    /**
     * Reads the newest generation of one logical base out of the staging root and remembers it.
     *
     * @param  logicalBase the generation base whose newest local generation is wanted
     * @throws IOException if the staging root cannot be listed or the artefact cannot be read
     */
    private static byte[] capture(final String logicalBase) throws IOException {
        final Path newest;
        try (Stream<Path> entries = Files.list(stagingRoot())) {
            newest = entries.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().startsWith(logicalBase + ".G"))
                    .max(Comparator.comparing(path -> path.getFileName().toString()))
                    .orElseThrow(() -> new AssertionError(
                            "the run produced no generation for logical base " + logicalBase));
        }
        return Files.readAllBytes(newest);
    }

    /**
     * The declared contract of one generation base.
     *
     * @param  logicalBase the generation base
     * @return its contract
     */
    private static GoldenContract goldenContract(final String logicalBase) {
        return GOLDEN_CONTRACTS.stream()
                .filter(contract -> contract.logicalBase().equals(logicalBase))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "no fixed-width contract is declared for " + logicalBase));
    }

    /**
     * Builds the comparison row of one contract, without asserting anything about it.
     *
     * <p>Called from setup for all four contracts, so the Gate 1 report is complete however few tests are
     * selected. The row records what was expected and what was produced and whether the two matched; the
     * ASSERTION that they matched belongs to {@link #assertGolden(GoldenContract)} and to the report test.
     *
     * @param  contract the contract to compare
     * @param  produced the artefact the run produced for it
     * @return the comparison row
     * @throws IOException if the committed golden cannot be read
     */
    private static ContractComparison compare(final GoldenContract contract, final byte[] produced)
            throws IOException {
        final String goldenResource = GOLDEN_DIRECTORY + contract.goldenName();
        final byte[] golden = classpathBytes(goldenResource);
        return new ContractComparison(contract.logicalBase(), INPUT_DIRECTORY + INPUT_FIXTURE_NAME,
                goldenResource, contract.recordWidth(), contract.recordCount(), golden.length,
                produced.length / contract.recordWidth(), produced.length,
                Arrays.equals(produced, golden));
    }

    /**
     * Splits one captured artefact into its fixed-width records.
     *
     * <p>The split is by stride alone and nothing is removed from a record: no terminator to drop and
     * no padding to strip, because a fixed-length dataset has neither.
     *
     * @param  logicalBase the base whose captured artefact is wanted
     * @param  recordWidth the record width, which is the whole stride
     * @return the records, in the order the artefact holds them
     */
    private List<String> producedRecords(final String logicalBase, final int recordWidth) {
        final byte[] image = run().artefact(logicalBase);
        assertThat(image.length % recordWidth)
                .as("%s must be an exact multiple of its %d-byte record width", logicalBase,
                        recordWidth)
                .isZero();
        final List<String> records = new ArrayList<>(image.length / recordWidth);
        for (int offset = 0; offset < image.length; offset += recordWidth) {
            records.add(new String(image, offset, recordWidth, StandardCharsets.US_ASCII));
        }
        return List.copyOf(records);
    }

    /**
     * Compares one produced artefact with its committed golden on raw bytes.
     *
     * <p>The geometry both sides must satisfy is stated first, so a length difference is reported as
     * records rather than as bytes. The comparison itself is over the byte arrays with nothing in
     * between - no decoding, no normalising, no trimming - and a difference is reported with the record
     * it falls in and the offset it falls at, both within the record and within the whole artefact.
     *
     * @param  contract the artefact to compare, naming the base whose captured output is read, the
     *                  committed golden's file name, the record width that is the whole stride, and the
     *                  record count both sides must carry
     * @throws IOException if the golden cannot be read
     */
    private void assertGolden(final GoldenContract contract) throws IOException {
        final String goldenName = contract.goldenName();
        final int recordWidth = contract.recordWidth();
        final int recordCount = contract.recordCount();
        final byte[] produced = run().artefact(contract.logicalBase());
        final byte[] golden = classpathBytes(GOLDEN_DIRECTORY + goldenName);

        assertThat(golden)
                .as("%s is %d records of %d bytes with no separator", goldenName, recordCount,
                        recordWidth)
                .hasSize(recordCount * recordWidth);
        assertThat(produced.length % recordWidth)
                .as("the produced artefact must be an exact multiple of its %d-byte record width",
                        recordWidth)
                .isZero();
        assertThat(produced.length / recordWidth)
                .as("the run produced %d records where %s holds %d",
                        produced.length / recordWidth, goldenName, recordCount)
                .isEqualTo(recordCount);

        // A MISMATCH IS LOCATED, NOT RENDERED. Both assertions below used to print their subjects: the
        // first the whole differing record from each side, the second the whole artefact as a byte
        // array. These are production-representative records - a transaction carries a card number and
        // an amount, a statement carries a customer's name and address - so a single parity regression
        // published up to a hundred and twenty-nine kilobytes of representative customer data into the
        // build log, and did so in CI where the log outlives the run.
        //
        // Everything a person needs to diagnose the difference is a coordinate and two bytes: which
        // record, which offset inside it, which absolute offset, and what each side holds there. The
        // fingerprints identify the two records without disclosing either, and are decisive for
        // "these differ" because equal records fingerprint equally.
        final int difference = firstDifferingOffset(produced, golden);
        if (difference >= 0) {
            final int recordIndex = difference / recordWidth;
            final int columnIndex = difference % recordWidth;
            final String producedRecord = new String(produced, recordIndex * recordWidth, recordWidth,
                    StandardCharsets.US_ASCII);
            final String goldenRecord = new String(golden, recordIndex * recordWidth, recordWidth,
                    StandardCharsets.US_ASCII);
            assertThat(SensitiveValues.fingerprint(producedRecord))
                    .as("%s: first difference in record %d at byte offset %d within the record "
                            + "(1-based column %d, absolute offset %d); expected byte 0x%02X, produced "
                            + "0x%02X. Record %d is %s in the golden and %s in the run - re-run with the "
                            + "artefact in hand to read it, because the record itself is "
                            + "production-representative and is deliberately not printed here",
                            goldenName, recordIndex, columnIndex, columnIndex + 1,
                            difference, Byte.valueOf(golden[difference]),
                            Byte.valueOf(produced[difference]), recordIndex,
                            SensitiveValues.describe(goldenRecord),
                            SensitiveValues.describe(producedRecord))
                    .isEqualTo(SensitiveValues.fingerprint(goldenRecord));
        }
        // Equivalent to array equality and prints an integer instead of two artefacts: the geometry
        // assertions above have already fixed both lengths at recordCount * recordWidth, and a scan
        // that finds no differing offset over equal lengths has compared every byte.
        assertThat(produced.length)
                .as("%s must be reproduced byte for byte, whole, so the two lengths agree first",
                        goldenName)
                .isEqualTo(golden.length);
        assertThat(firstDifferingOffset(produced, golden))
                .as("%s must be reproduced byte for byte, whole: no offset may differ, and -1 is what a "
                        + "complete scan over equal lengths returns", goldenName)
                .isEqualTo(-1);

        // AND the row the snapshot recorded for this contract agrees with what was just measured. The row
        // is what the Gate 1 report publishes, so a row that disagreed with the comparison would publish
        // a status no test had established.
        final ContractComparison row = run().comparison(contract.logicalBase());
        assertThat(row.matched())
                .as("the comparison row the snapshot carries for %s records a match", goldenName)
                .isTrue();
        assertThat(row.actualByteCount()).isEqualTo(produced.length);
        assertThat(row.expectedByteCount()).isEqualTo(golden.length);
        assertThat(row.actualRecordCount()).isEqualTo(recordCount);
        assertThat(row.goldenResource()).isEqualTo(GOLDEN_DIRECTORY + goldenName);
    }

    /**
     * Returns the offset of the first byte at which two images differ, or a negative value if they are
     * identical over their common length and equal in length.
     *
     * @param  produced the produced image
     * @param  golden   the committed image
     * @return the first differing offset, or {@code -1}
     */
    private static int firstDifferingOffset(final byte[] produced, final byte[] golden) {
        final int common = Math.min(produced.length, golden.length);
        for (int offset = 0; offset < common; offset++) {
            if (produced[offset] != golden[offset]) {
                return offset;
            }
        }
        return produced.length == golden.length ? -1 : common - 1;
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
            assertThat(stream)
                    .as("%s must be on the test class path; a missing artefact is a gate failure and "
                            + "never a skipped test", resource)
                    .isNotNull();
            return stream.readAllBytes();
        }
    }

    // ===============================================================================================
    // READING THE COMMITTED INPUTS
    // ===============================================================================================

    /**
     * Splits one committed input into its records, dropping only the newline the estate's rendering adds
     * between them.
     *
     * @param  fixture the input whose records are wanted
     * @return the records, each exactly the declared record length
     * @throws IOException if the input cannot be read
     */
    private static List<String> inputRecords(final InputFixture fixture) throws IOException {
        final byte[] image = classpathBytes(INPUT_DIRECTORY + fixture.fileName());
        final int stride = fixture.recordLength() + 1;
        final List<String> records = new ArrayList<>(fixture.recordCount());
        for (int offset = 0; offset + stride <= image.length; offset += stride) {
            assertThat(image[offset + fixture.recordLength()])
                    .as("%s separates records with one line feed and nothing else", fixture.fileName())
                    .isEqualTo(LINE_FEED);
            records.add(new String(image, offset, fixture.recordLength(), StandardCharsets.US_ASCII));
        }
        return List.copyOf(records);
    }

    /**
     * Resolves one named input by file name.
     *
     * @param  fileName the exact file name
     * @return its declared geometry
     */
    private static InputFixture namedInput(final String fileName) {
        return NAMED_INPUTS.stream()
                .filter(fixture -> fixture.fileName().equals(fileName))
                .findFirst()
                .orElseThrow(() -> new AssertionError(fileName + " is not one of the named inputs"));
    }

    // ===============================================================================================
    // READING THE TEXT STATEMENT
    // ===============================================================================================

    /**
     * Splits the produced text statement into one list of records per statement.
     *
     * <p>A statement ends at its closing banner, so the split needs no count and no marker of this
     * class's own.
     *
     * @return the statements, in the order the artefact holds them
     */
    private List<List<String>> statementBlocks() {
        final String closing = "*".repeat(32) + "END OF STATEMENT" + "*".repeat(32);
        final List<List<String>> blocks = new ArrayList<>(STATEMENT_COUNT);
        List<String> current = new ArrayList<>();
        for (final String record : producedRecords(STATEMENT_BASE, STATEMENT_WIDTH)) {
            current.add(record);
            if (closing.equals(record)) {
                blocks.add(List.copyOf(current));
                current = new ArrayList<>();
            }
        }
        assertThat(current).as("the last statement is closed by its banner").isEmpty();
        assertThat(blocks).as("one block per statement").hasSize(STATEMENT_COUNT);
        return List.copyOf(blocks);
    }

    /**
     * Returns the transaction records of one statement: everything between its fixed 16-record header
     * and its fixed 3-record tail.
     *
     * @param  block one statement's records
     * @return its transaction records
     */
    private static List<String> detailRecordsOf(final List<String> block) {
        assertThat(block)
                .as("a statement is 16 header records, at least one transaction, and 3 tail records")
                .hasSizeGreaterThan(19);
        return List.copyOf(block.subList(16, block.size() - 3));
    }

    /**
     * Reads an edited amount whose sign position trails the value, as the 80-byte and 100-byte contracts
     * write it.
     *
     * <p>The spaces removed here are the suppression the picture performed, and this is arithmetic over
     * an edited field rather than a comparison: no byte comparison in this class removes anything.
     *
     * @param  field the edited field, sign position included
     * @return the value it carries
     */
    private static BigDecimal trailingSignAmount(final String field) {
        final boolean negative = field.endsWith("-");
        final String digits =
                (negative ? field.substring(0, field.length() - 1) : field).replace(" ", "");
        final BigDecimal magnitude = new BigDecimal(digits);
        return negative ? magnitude.negate() : magnitude;
    }

    // ===============================================================================================
    // READING THE TRANSACTION REPORT
    // ===============================================================================================

    /** What one report record is, decided by the leading bytes each group writes. */
    private enum ReportRecordKind {

        /** The report's own name header, which opens every page. */
        NAME_HEADER,

        /** The blank line that follows it. */
        BLANK_LINE,

        /** The column heading. */
        COLUMN_HEADING,

        /** The separator declared at the full record width. */
        SEPARATOR,

        /** A page total, written by the page test or once more at end of file. */
        PAGE_TOTAL,

        /** An account total, written on the control break. */
        ACCOUNT_TOTAL,

        /** The single grand total. */
        GRAND_TOTAL,

        /** A transaction detail line. */
        DETAIL
    }

    /**
     * Classifies one report record.
     *
     * @param  record the 133-byte record
     * @return what it is
     */
    private static ReportRecordKind kindOf(final String record) {
        if ("-".repeat(REPORT_WIDTH).equals(record)) {
            return ReportRecordKind.SEPARATOR;
        }
        if (" ".repeat(REPORT_WIDTH).equals(record)) {
            return ReportRecordKind.BLANK_LINE;
        }
        if (record.startsWith("DALYREPT")) {
            return ReportRecordKind.NAME_HEADER;
        }
        if (record.startsWith("Transaction ID")) {
            return ReportRecordKind.COLUMN_HEADING;
        }
        if (record.startsWith("Page Total")) {
            return ReportRecordKind.PAGE_TOTAL;
        }
        if (record.startsWith("Account Total")) {
            return ReportRecordKind.ACCOUNT_TOTAL;
        }
        if (record.startsWith("Grand Total")) {
            return ReportRecordKind.GRAND_TOTAL;
        }
        return ReportRecordKind.DETAIL;
    }

    /**
     * Returns every produced report record of one kind, in order.
     *
     * @param  kind the kind wanted
     * @return the records of that kind
     */
    private List<String> reportRecordsOfKind(final ReportRecordKind kind) {
        return producedRecords(REPORT_BASE, REPORT_WIDTH).stream()
                .filter(record -> kindOf(record) == kind)
                .toList();
    }

    /**
     * Sums the edited amounts of every produced report record of one kind.
     *
     * @param  kind the kind whose amounts are summed
     * @return their sum
     */
    private BigDecimal sumOfReportAmounts(final ReportRecordKind kind) {
        return reportRecordsOfKind(kind).stream()
                .map(record -> editedAmount(reportAmountOf(record)))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * Slices the edited amount out of one report record.
     *
     * @param  record the 133-byte record
     * @return its fifteen-character edited amount field, exactly as written
     */
    private static String reportAmountOf(final String record) {
        return record.substring(REPORT_AMOUNT_OFFSET,
                REPORT_AMOUNT_OFFSET + REPORT_AMOUNT_WIDTH);
    }

    /**
     * Reads an edited amount from the 133-byte contract, whose sign leads and whose thousands are
     * grouped.
     *
     * <p>A field the picture suppressed away entirely reads as zero, which is the behaviour being
     * asserted rather than a convenience. As above, this is arithmetic over an edited field and not a
     * comparison.
     *
     * @param  field the fifteen-character edited field
     * @return the value it carries
     */
    private static BigDecimal editedAmount(final String field) {
        final String compact = field.replace(",", "").replace(" ", "");
        if (compact.isEmpty()) {
            return BigDecimal.ZERO;
        }
        if (compact.startsWith("-")) {
            return new BigDecimal(compact.substring(1)).negate();
        }
        if (compact.startsWith("+")) {
            return new BigDecimal(compact.substring(1));
        }
        return new BigDecimal(compact);
    }

    /**
     * Asserts that every produced report record opening with one total label carries the label at its
     * declared width followed by a run of dots, and that the two together fill the 112 bytes before the
     * amount.
     *
     * @param records    every produced report record
     * @param label      the label as it is written, padded to its declared field width
     * @param labelWidth the declared width of the label field
     * @param dotCount   the length of the dot run that follows it
     */
    private static void assertDotRun(final List<String> records, final String label,
            final int labelWidth, final int dotCount) {
        assertThat(label).as("the label field is %d bytes wide", labelWidth).hasSize(labelWidth);
        assertThat(labelWidth + dotCount)
                .as("label and dot run together fill the 97 bytes before the amount, whichever way they "
                        + "are divided, and the fifteen-character amount takes the group to 112")
                .isEqualTo(REPORT_AMOUNT_OFFSET);
        final List<String> matching = records.stream()
                .filter(record -> record.startsWith(label)).toList();
        assertThat(matching).as("the leader labelled '%s' is written at least once", label).isNotEmpty();
        for (final String record : matching) {
            assertThat(record.substring(labelWidth, REPORT_AMOUNT_OFFSET))
                    .as("the dot run is %d long here and a different length on the other leaders, and "
                            + "the asymmetry is theirs rather than an accident of this assertion",
                            dotCount)
                    .isEqualTo(".".repeat(dotCount));
        }
    }

    // ===============================================================================================
    // READING THE HTML STATEMENT
    // ===============================================================================================

    /**
     * Reports whether one record is exactly one literal left-justified in the HTML record width.
     *
     * <p>Written as an equality against the literal plus its own padding rather than by removing the
     * padding, so nothing in this class ever strips a fixed-width record.
     *
     * @param  record  the 100-byte record
     * @param  literal the literal it should carry and nothing else
     * @return whether it does
     */
    private static boolean isPaddedLiteral(final String record, final String literal) {
        return record.length() == STATEMENT_HTML_WIDTH
                && record.startsWith(literal)
                && record.substring(literal.length())
                        .equals(" ".repeat(STATEMENT_HTML_WIDTH - literal.length()));
    }

    /**
     * Asserts that one record carries content of a stated length and space padding behind it.
     *
     * @param record        the record
     * @param contentLength the length of the literal it carries
     * @param recordWidth   the record width it is padded to
     */
    private static void assertPaddedRecord(final String record, final int contentLength,
            final int recordWidth) {
        assertThat(record).hasSize(recordWidth);
        assertThat(record.substring(contentLength))
                .as("the literal is %d bytes and the record is %d, so the remainder is space padding "
                        + "and carries nothing else", contentLength, recordWidth)
                .isEqualTo(" ".repeat(recordWidth - contentLength));
    }

    // ===============================================================================================
    // READING THE SERVER AND THE INSTRUMENTS
    // ===============================================================================================

    /**
     * Counts the rows of one application table.
     *
     * <p>The table name is not concatenated from a caller's value: it is matched against the shared
     * base's own roster first, so only a name the schema declares can reach a statement.
     *
     * @param  table the table to count
     * @return its row count
     * @throws SQLException if the count cannot be read
     */
    private static long rowCount(final String table) throws SQLException {
        assertThat(APPLICATION_TABLES)
                .as("%s must be one of the tables the migrations create", table)
                .contains(table);
        final List<String> counted =
                queryColumn("SELECT count(*)::text FROM " + table);
        assertThat(counted).hasSize(1);
        return Long.parseLong(counted.get(0));
    }

    /**
     * Runs one complete literal query and collects its single projected column.
     *
     * @param  sql the complete query
     * @return the projected values, in the order the query returned them
     * @throws SQLException if the query cannot be run
     */
    private static List<String> queryColumn(final String sql) throws SQLException {
        final List<String> values = new ArrayList<>();
        try (Connection connection = connect();
                PreparedStatement query = connection.prepareStatement(sql);
                ResultSet rows = query.executeQuery()) {
            while (rows.next()) {
                values.add(rows.getString(1));
            }
        }
        return List.copyOf(values);
    }

    /**
     * Sums one counter across every tag combination it was registered under.
     *
     * @param  name the meter name
     * @return the total the run recorded on it
     */
    private double counterTotal(final String name) {
        final List<Counter> counters = List.copyOf(this.meterRegistry.find(name).counters());
        assertThat(counters).as("%s must be registered", name).isNotEmpty();
        double total = 0.0d;
        for (final Counter counter : counters) {
            total += counter.count();
        }
        return total;
    }

    /**
     * Sums the sample count of one timer across every tag combination it was registered under.
     *
     * @param  name the meter name
     * @return the samples the run recorded on it
     */
    private double timerCount(final String name) {
        final List<Timer> timers = List.copyOf(this.meterRegistry.find(name).timers());
        assertThat(timers).as("%s must be registered", name).isNotEmpty();
        double samples = 0.0d;
        for (final Timer timer : timers) {
            samples += timer.count();
        }
        return samples;
    }

    // ===============================================================================================
    // THE STAGING ROOT
    // ===============================================================================================

    /**
     * Resolves the staging root the context bound, which is the one this run published.
     *
     * @return the staging root, created if absent
     * @throws IOException if it cannot be created
     */
    private static Path stagingRoot() {
        return IsolatedStagingRoot.forSpecification(STAGING_LABEL);
    }

    /**
     * Binds the shared staging key to a root private to this process, before the context is created.
     *
     * <p>A property callback rather than an entry in the annotation above, because the value cannot be a
     * compile-time constant: it carries the process identifier so that no other run of this specification,
     * and no sibling clone sharing this host, resolves the same absolute path. The callback runs before the
     * context starts, which is what a binding resolved during start-up requires.
     *
     * @param registry the registry the framework supplies
     */
    @DynamicPropertySource
    static void registerIsolatedStagingDirectory(final DynamicPropertyRegistry registry) {
        registry.add(STAGING_DIRECTORY_PROPERTY, () -> IsolatedStagingRoot.pathFor(STAGING_LABEL));
    }

    /**
     * Removes this specification's own staging root and prepares an empty one.
     *
     * <p>The root is a tree this process owns, so the removal cannot reach a file another run or another
     * clone staged. The sweep that stood here removed every regular file beneath a directory all of them
     * shared, which could take away a file a concurrently running sibling was still composing.
     */
    private static void clearStagingRoot() {
        IsolatedStagingRoot.discard(stagingRoot());
        IsolatedStagingRoot.forSpecification(STAGING_LABEL);
    }

    // ===============================================================================================
    // THE COMPARISON REPORT
    // ===============================================================================================

    /**
     * Writes the Gate 1 comparison report into the build output, one row per contract compared.
     *
     * @return the file written
     * @throws IOException if it cannot be written
     */
    private static Path publishComparisonReport(final PipelineRun completed) throws IOException {
        final String newline = System.lineSeparator();
        final StringBuilder rendered = new StringBuilder(1024);
        rendered.append("<!-- Gate 1, measured by BatchPipelineE2ETest. Copy into docs/gate-evidence.md")
                .append(newline)
                .append("     with the date and the machine the run was taken on. Source estate:")
                .append(newline)
                .append("     SHA 7756d895ffeb65f7ea72aaa609e356d9899afcec, upstream release stamp")
                .append(newline)
                .append("     CardDemo_v1.0-15-g27d6c6f-68 dated 2022-07-19. -->")
                .append(newline).append(newline)
                // The estate provenance above is a constant of this migration and is identical in every
                // file this module will ever emit. This line is the part that differs: which build and
                // which run produced this comparison, so a published bundle can be attributed to the
                // code it compared. See docs/decision-log.md DL-315.
                .append(GateEvidenceProvenance.stamp())
                .append(newline).append(newline)
                .append("| Contract | Input | Expected output | Width | Expected records | ")
                .append("Actual records | Expected bytes | Actual bytes | Status |").append(newline)
                .append("| --- | --- | --- | ---: | ---: | ---: | ---: | ---: | :---: |")
                .append(newline);
        final List<ContractComparison> rows =
                completed == null ? List.of() : completed.comparisons();
        for (final ContractComparison row : rows) {
            rendered.append(row.asTableRow()).append(newline);
        }
        if (rows.isEmpty()) {
            rendered.append("| _no contract was compared: the pipeline run did not reach the ")
                    .append("comparison_ | | | | | | | | FAIL |").append(newline);
        }
        final Path target =
                RunScopedPerformanceRecorder.EVIDENCE_DIRECTORY.resolve(COMPARISON_REPORT_FILE);
        try {
            Files.createDirectories(RunScopedPerformanceRecorder.EVIDENCE_DIRECTORY);
            Files.writeString(target, rendered.toString(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
        } catch (final IOException failure) {
            throw new UncheckedIOException("the Gate 1 comparison report could not be written to "
                    + target, failure);
        }
        return target;
    }

    // ===============================================================================================
    // NESTED VALUE TYPES
    // ===============================================================================================

    /**
     * One fixed-width contract the pipeline emits: where its output lands, which committed golden it is
     * compared against, and the geometry both sides must satisfy.
     *
     * @param logicalBase the generation base the run writes it under
     * @param goldenName  the committed golden's file name
     * @param recordWidth the record width, which is the whole stride
     * @param recordCount the record count both sides must carry
     */
    private record GoldenContract(String logicalBase, String goldenName, int recordWidth,
            int recordCount) {
    }

    /**
     * Everything one run of the delivered pipeline observed and produced, as an immutable value.
     *
     * <h4>Why this is a class and not a record</h4>
     * A record's accessor hands back the component itself, and two of these components are mutable in a
     * way an unmodifiable wrapper does not fix: a {@code Map<String, byte[]>} can be wrapped, but the
     * arrays inside it stay writable, so any test could rewrite the run's output and the next test would
     * assert against the rewrite. {@link #artefact(String)} therefore returns a copy, and the map is not
     * exposed at all. That is the whole point of publishing a snapshot rather than a scratchpad.
     *
     * <p>Recorded as DL-281 in {@code docs/decision-log.md}.
     */
    private static final class PipelineRun {

        /** The migration versions applied to the server the run read from. */
        private final List<String> appliedMigrations;

        /** The application tables those migrations created. */
        private final List<String> applicationTables;

        /** Row count per table, observed BEFORE the first job was launched. */
        private final Map<String, Long> seededRowCounts;

        /** The distinct category balances observed before the run, as text. */
        private final List<String> seededCategoryBalances;

        /** Records the posting step read. */
        private final long postingReadCount;

        /** Records the posting step wrote, which is the refused ones. */
        private final long postingWriteCount;

        /** Master row count immediately after posting. */
        private final long masterCountAfterPosting;

        /** Master row count immediately after the accrual run. */
        private final long masterCountAfterAccrual;

        /** Master row count immediately after the consolidation. */
        private final long masterCountAfterConsolidation;

        /** Byte length of the staged input image. */
        private final int inputByteCount;

        /** The object-store bucket the publication path addressed. */
        private final String stagingBucket;

        /** The executions the run performed, in launch order. */
        private final List<JobExecution> executions;

        /**
         * The launch of the orphan extract, which is a probe beside the pipeline and not a part of it.
         *
         * <p>Held apart from {@link #executions} because that list is the pipeline, and one assertion
         * counts it. Held at all because this class launches the orphan, and a launch this class does not
         * record is a metadata row it cannot clean up.
         */
        private final JobExecution orphanExecution;

        /** Master row count immediately after the orphan probe, which must not have written. */
        private final long masterCountAfterOrphan;

        /** The artefacts the run produced, keyed by logical base. */
        private final Map<String, byte[]> produced;

        /** One comparison row per fixed-width contract, built in setup. */
        private final List<ContractComparison> comparisons;

        /**
         * Publishes one run's observations.
         *
         * @param appliedMigrations             the migration versions applied
         * @param applicationTables             the application tables
         * @param seededRowCounts               row count per table before the run
         * @param seededCategoryBalances        distinct category balances before the run
         * @param postingReadCount              records the posting step read
         * @param postingWriteCount             records the posting step wrote
         * @param masterCountAfterPosting       master rows after posting
         * @param masterCountAfterAccrual       master rows after the accrual run
         * @param masterCountAfterConsolidation master rows after the consolidation
         * @param inputByteCount                byte length of the staged input
         * @param stagingBucket                 the object-store bucket addressed
         * @param executions                    the executions performed, in launch order
         * @param orphanExecution               the launch of the orphan extract probe
         * @param masterCountAfterOrphan        master rows after the orphan probe
         * @param produced                      the artefacts produced, keyed by logical base
         * @param comparisons                   one comparison row per contract
         */
        PipelineRun(final List<String> appliedMigrations, final List<String> applicationTables,
                final Map<String, Long> seededRowCounts, final List<String> seededCategoryBalances,
                final long postingReadCount, final long postingWriteCount,
                final long masterCountAfterPosting, final long masterCountAfterAccrual,
                final long masterCountAfterConsolidation, final int inputByteCount,
                final String stagingBucket, final List<JobExecution> executions,
                final JobExecution orphanExecution, final long masterCountAfterOrphan,
                final Map<String, byte[]> produced, final List<ContractComparison> comparisons) {
            this.appliedMigrations = List.copyOf(appliedMigrations);
            this.applicationTables = List.copyOf(applicationTables);
            this.seededRowCounts = Map.copyOf(seededRowCounts);
            this.seededCategoryBalances = List.copyOf(seededCategoryBalances);
            this.postingReadCount = postingReadCount;
            this.postingWriteCount = postingWriteCount;
            this.masterCountAfterPosting = masterCountAfterPosting;
            this.masterCountAfterAccrual = masterCountAfterAccrual;
            this.masterCountAfterConsolidation = masterCountAfterConsolidation;
            this.inputByteCount = inputByteCount;
            this.stagingBucket = stagingBucket;
            this.executions = List.copyOf(executions);
            this.orphanExecution = orphanExecution;
            this.masterCountAfterOrphan = masterCountAfterOrphan;
            final Map<String, byte[]> copied = new LinkedHashMap<>();
            produced.forEach((base, image) -> copied.put(base, image.clone()));
            this.produced = Collections.unmodifiableMap(copied);
            this.comparisons = List.copyOf(comparisons);
        }

        /**
         * The migration versions applied to the server the run read from.
         *
         * @return the versions, in order
         */
        List<String> appliedMigrations() {
            return this.appliedMigrations;
        }

        /**
         * The application tables those migrations created.
         *
         * @return the table names
         */
        List<String> applicationTables() {
            return this.applicationTables;
        }

        /**
         * The row count one table carried before the first job was launched.
         *
         * @param  table the table name
         * @return its pre-run row count
         */
        long seededRowCount(final String table) {
            final Long count = this.seededRowCounts.get(table);
            assertThat(count)
                    .as("the snapshot records no pre-run row count for %s; add it to the counted "
                            + "tables if a test needs it", table)
                    .isNotNull();
            return count.longValue();
        }

        /**
         * The distinct category balances observed before the run, as text.
         *
         * @return the distinct values
         */
        List<String> seededCategoryBalances() {
            return this.seededCategoryBalances;
        }

        /**
         * Records the posting step read.
         *
         * @return the read count
         */
        long postingReadCount() {
            return this.postingReadCount;
        }

        /**
         * Records the posting step wrote, which is the refused ones.
         *
         * @return the write count
         */
        long postingWriteCount() {
            return this.postingWriteCount;
        }

        /**
         * Master row count immediately after posting.
         *
         * @return the count
         */
        long masterCountAfterPosting() {
            return this.masterCountAfterPosting;
        }

        /**
         * Master row count immediately after the accrual run.
         *
         * @return the count
         */
        long masterCountAfterAccrual() {
            return this.masterCountAfterAccrual;
        }

        /**
         * Master row count immediately after the consolidation.
         *
         * @return the count
         */
        long masterCountAfterConsolidation() {
            return this.masterCountAfterConsolidation;
        }

        /**
         * Byte length of the staged input image.
         *
         * @return the byte count
         */
        int inputByteCount() {
            return this.inputByteCount;
        }

        /**
         * The object-store bucket the publication path addressed.
         *
         * @return the bucket name
         */
        String stagingBucket() {
            return this.stagingBucket;
        }

        /**
         * The executions the run performed, in launch order.
         *
         * @return the executions
         */
        List<JobExecution> executions() {
            return this.executions;
        }

        /**
         * The launch of the orphan extract, kept out of {@link #executions()} because it is not pipeline.
         *
         * @return the orphan's execution
         */
        JobExecution orphanExecution() {
            return this.orphanExecution;
        }

        /**
         * Master row count immediately after the orphan probe.
         *
         * @return the count
         */
        long masterCountAfterOrphan() {
            return this.masterCountAfterOrphan;
        }

        /**
         * The logical bases the run produced an artefact for.
         *
         * @return the bases
         */
        Set<String> artefactBases() {
            return this.produced.keySet();
        }

        /**
         * One produced artefact, as a copy.
         *
         * <p>A copy, so a test that indexes into the bytes cannot alter what the next test sees. Cheap at
         * these sizes and the only way a snapshot of arrays is genuinely immutable.
         *
         * @param  logicalBase the base whose artefact is wanted
         * @return its bytes
         */
        byte[] artefact(final String logicalBase) {
            final byte[] image = this.produced.get(logicalBase);
            assertThat(image)
                    .as("the run produced no artefact for logical base %s", logicalBase)
                    .isNotNull();
            return image.clone();
        }

        /**
         * One comparison row per fixed-width contract, in contract order.
         *
         * @return the rows
         */
        List<ContractComparison> comparisons() {
            return this.comparisons;
        }

        /**
         * The comparison row of one contract.
         *
         * @param  logicalBase the contract's generation base
         * @return its comparison row
         */
        ContractComparison comparison(final String logicalBase) {
            return this.comparisons.stream()
                    .filter(row -> row.contract().equals(logicalBase))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(
                            "the snapshot carries no comparison row for " + logicalBase));
        }
    }

    /**
     * One contract's comparison, in the shape the Gate 1 evidence page carries.
     *
     * @param contract            the logical dataset the artefact stands for
     * @param inputResource       the input the run was driven from
     * @param goldenResource      the expected output it was compared with
     * @param recordWidth         the record width both sides carry
     * @param expectedRecordCount records the expected output holds
     * @param expectedByteCount   bytes the expected output holds
     * @param actualRecordCount   records the run produced
     * @param actualByteCount     bytes the run produced
     * @param matched             whether the two images are identical
     */
    private record ContractComparison(String contract, String inputResource, String goldenResource,
            int recordWidth, int expectedRecordCount, int expectedByteCount, int actualRecordCount,
            int actualByteCount, boolean matched) {

        /**
         * Renders this comparison as one table row.
         *
         * @return the row, with an explicit locale so the figures do not follow the ambient default
         */
        String asTableRow() {
            return String.format(Locale.ROOT, "| %s | %s | %s | %d | %d | %d | %d | %d | %s |",
                    this.contract, this.inputResource, this.goldenResource,
                    Integer.valueOf(this.recordWidth), Integer.valueOf(this.expectedRecordCount),
                    Integer.valueOf(this.actualRecordCount), Integer.valueOf(this.expectedByteCount),
                    Integer.valueOf(this.actualByteCount), this.matched ? "PASS" : "FAIL");
        }
    }

    /** What an input pads its records with, measured rather than assumed uniform. */
    private enum FillerKind {

        /** Padded with spaces. */
        SPACE,

        /** Padded with the digit zero, which is a different byte and must not be interchanged. */
        ZERO_DIGIT,

        /** Not padded at all: the record ends on data. */
        NONE
    }

    /**
     * One named validation artefact and the geometry it was measured at.
     *
     * @param fileName     the exact file name, which Gate 4 requires to be named
     * @param byteCount    bytes the file holds, terminators included
     * @param recordCount  records it holds
     * @param recordLength the declared record length, terminator excluded
     * @param fillerKind   what it pads a record with
     */
    private record InputFixture(String fileName, int byteCount, int recordCount, int recordLength,
            FillerKind fillerKind) {
    }

    /**
     * The nine named validation artefacts, by exact file name and at their measured geometry.
     *
     * <p>The cross-reference entry is the one to read twice: its layout declares fifty bytes of which
     * thirty-six are data, and the delivered rendering holds the thirty-six and stops. Padding it back
     * out to fifty would change every record image.
     */
    private static final List<InputFixture> NAMED_INPUTS = List.of(
            new InputFixture("acctdata.txt", 15_050, 50, 300, FillerKind.SPACE),
            new InputFixture("carddata.txt", 7_550, 50, 150, FillerKind.SPACE),
            new InputFixture("cardxref.txt", 1_850, 50, 36, FillerKind.NONE),
            new InputFixture("custdata.txt", 25_050, 50, 500, FillerKind.SPACE),
            new InputFixture("dailytran.txt", 105_300, 300, 350, FillerKind.SPACE),
            new InputFixture("discgrp.txt", 2_601, 51, 50, FillerKind.ZERO_DIGIT),
            new InputFixture("tcatbal.txt", 2_550, 50, 50, FillerKind.ZERO_DIGIT),
            new InputFixture("trancatg.txt", 1_098, 18, 60, FillerKind.ZERO_DIGIT),
            new InputFixture("trantype.txt", 427, 7, 60, FillerKind.ZERO_DIGIT));

    // ===============================================================================================
    // THE SPRING SLICE
    // ===============================================================================================

    /**
     * The pipeline under test: the seven job configurations, the collaborators they need, and the real
     * object-store clients bound to the emulator this run started.
     *
     * <p>Assembled explicitly rather than by scanning, so the graph is exactly the pipeline and a
     * reader can see in one place what took part. Two beans are declared here. The clock is the shared
     * base's pinned one, marked as the preferred candidate rather than replacing the auditing
     * configuration - that configuration belongs to the production graph and is imported whole. The
     * publication lock is the real one, because this slice has the server it needs.
     *
     * <p><strong>Nothing is stubbed.</strong> The object store and the notification clients come from
     * the shipped {@code AwsConfig}, pointed at the emulator by the shared base's property source.
     */
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = PrometheusExemplarsAutoConfiguration.class)
    @Import({PostTransactionJobConfig.class, InterestCalculationJobConfig.class,
            BackupTransactionJobConfig.class, CombineTransactionsJobConfig.class,
            TransactionReportJobConfig.class, CreateStatementJobConfig.class,
            DailyTransactionReadJobConfig.class,
            BatchConfig.class, JpaAuditConfig.class, JobParameterValidators.class, AwsConfig.class,
            BatchLaunchCoordinator.class,
            BatchStagingArea.class, StagedGenerationStore.class,
            AdvisoryGenerationPublicationLock.class, FixedWidthFlatFileReaderFactory.class,
            TransactionPostingService.class, PostingStageTransactionBoundary.class,
            InterestCalculationService.class, InterestGroupTransactionBoundary.class,
            TransactionReportService.class, StatementGenerationService.class,
            StatementDataAccessService.class, DailyTransactionReadService.class,
            SensitiveFieldEncryptionService.class,
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
         * @return the shared base's clock, frozen at the instant the committed input carries
         */
        @Bean
        @Primary
        Clock pinnedClock() {
            return FIXED_CLOCK;
        }
    }
}
