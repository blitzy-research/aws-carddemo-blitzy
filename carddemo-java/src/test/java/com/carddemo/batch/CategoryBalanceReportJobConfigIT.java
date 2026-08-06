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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.awspring.cloud.s3.S3Operations;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.configuration.JobRegistry;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.job.SimpleJob;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.batch.BatchAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.metrics.CompositeMeterRegistryAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.metrics.MetricsAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.metrics.export.simple.SimpleMetricsExportAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.observation.ObservationAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.observation.batch.BatchObservationAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

import com.carddemo.batch.step.FixedWidthFlatFileReaderFactory;
import com.carddemo.batch.step.StagedGenerationStore;
import com.carddemo.config.BatchConfig;
import com.carddemo.config.JpaAuditConfig;
import com.carddemo.domain.TransactionCategoryBalance;
import com.carddemo.repository.TransactionCategoryBalanceRepository;
import com.carddemo.service.AbendService;
import com.carddemo.service.FileMaintenanceService;
import com.carddemo.support.AbstractPostgresIT;

/**
 * The category-balance report job, run end to end against a real PostgreSQL 16 server with the delivered
 * migrations applied.
 *
 * <p>The subject is the translation of the legacy job stream {@code app/jcl/PRTCATBL.jcl} at checkout
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. Six measured facts are asserted, each of which
 * a translation can get wrong while still compiling:
 *
 * <ol>
 * <li>The member declares three steps and no condition-code dependency on any of them, so the job runs
 *     all three unconditionally and carries no failure-ending transition.</li>
 * <li>The first step's disposition removes a previous run's output and allocates it when absent, so
 *     clearing an output that does not exist must succeed.</li>
 * <li>The unload emits fifty-byte records - the category-balance layout, which is a different file from
 *     the equally fifty-byte card cross-reference the planning material confused it with.</li>
 * <li>The reprojection emits forty-byte records, thirty-two content bytes and eight blanks, never the
 *     forty-one its own filler run would have produced.</li>
 * <li>The ordering is account identifier, then type code, then category code, all ascending, with the
 *     two zoned-decimal keys compared as signed numbers and the character key lexicographically. Rows
 *     carrying an overpunched category code separate the two readings: lexicographically {@code 0001}
 *     precedes {@code 000A} precedes {@code 000J}, while numerically {@code 000J} is minus one and
 *     sorts first of the three.</li>
 * <li>The balance is not a sort key, which the same rows prove by carrying balances whose own order
 *     contradicts the key order.</li>
 * </ol>
 *
 * <p>The job takes no date parameter and applies no date filter, because the comment at line 41 of the
 * member claims a date filter and a card-number ordering that its control stream does not contain. That
 * absence is asserted rather than assumed.
 */
@DisplayName("category-balance report job, run against a real server: three steps, no gate, "
        + "fifty-byte unload, forty-byte report")
final class CategoryBalanceReportJobConfigIT extends AbstractPostgresIT {

    /** Rows the reference seed loads from {@code app/data/ASCII/tcatbal.txt}. */
    private static final int SEEDED_ROWS = 50;

    /** Additional rows this class inserts to separate numeric from lexicographic key ordering. */
    private static final int DISCRIMINATING_ROWS = 4;

    /** Every row the unload and the report must carry once this class has inserted its own. */
    private static final int EXPECTED_ROWS = SEEDED_ROWS + DISCRIMINATING_ROWS;

    /** A seeded account identifier, so the foreign key to the account master resolves. */
    private static final String SEEDED_ACCOUNT = "00000000001";

    /** Logical name of the report dataset, as the configuration resolves it by default. */
    private static final String REPORT_DATASET = "AWS.M2.CARDDEMO.TCATBALF.REPT";

    /** Logical name prefix of a backup generation, as the configuration resolves it by default. */
    private static final String GENERATION_PREFIX = "AWS.M2.CARDDEMO.TCATBALF.BKUP.G";

    /** Width of the key prefix a report line carries: the three keys and their two separators. */
    private static final int KEY_PREFIX_WIDTH = 19;

    /** Staging directory this run's datasets resolve within. */
    @TempDir
    private Path stagingDirectory;

    /** Creates the test class. */
    CategoryBalanceReportJobConfigIT() {
    }

    /**
     * The job's collaborators, assembled directly rather than through the application entry point so
     * that a defect elsewhere in the bean graph can neither mask nor manufacture a result here.
     */
    @Configuration(proxyBeanMethods = false)
    @EnableJpaRepositories(basePackageClasses = TransactionCategoryBalanceRepository.class)
    @EntityScan(basePackageClasses = TransactionCategoryBalance.class)
    @Import({BatchConfig.class, JpaAuditConfig.class, CategoryBalanceReportJobConfig.class,
            FixedWidthFlatFileReaderFactory.class, StagedGenerationStore.class,
            FileMaintenanceService.class, AbendService.class})
    static class JobUnderTest {

        /** Creates the configuration. */
        JobUnderTest() {
        }

        /**
         * Keeps this PostgreSQL-focused integration test deterministic at the object-store boundary.
         * The real staging store and job listener remain active; {@code BatchAwsIntegrationIT} covers
         * the same upload operations against LocalStack.
         *
         * @return an object store that accepts uploads and reports no older retained generations
         */
        @Bean
        S3Operations objectStore() {
            final S3Operations objectStore = mock(S3Operations.class);
            when(objectStore.listObjects(anyString(), anyString())).thenReturn(List.of());
            return objectStore;
        }
    }

    /**
     * Inserts the rows that separate a numeric key comparison from a lexicographic one, and whose
     * balances are deliberately ordered against their keys.
     *
     * @throws SQLException if the server cannot be reached
     */
    @BeforeEach
    void insertDiscriminatingRows() throws SQLException {
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM transaction_category_balance"
                    + " WHERE trancat_cd IN ('000A','000J') OR trancat_type_cd IN ('0A','0B')");
            statement.executeUpdate("INSERT INTO transaction_category_balance"
                    + " (trancat_acct_id, trancat_type_cd, trancat_cd, tran_cat_bal) VALUES"
                    + " ('" + SEEDED_ACCOUNT + "', '01', '000J', 900.00),"
                    + " ('" + SEEDED_ACCOUNT + "', '01', '000A', 500.00),"
                    + " ('" + SEEDED_ACCOUNT + "', '0A', '0001', 100.00),"
                    + " ('" + SEEDED_ACCOUNT + "', '0B', '0001', 0.05)");
        }
    }

    @Test
    @DisplayName("the whole job runs twice over: three steps every time, the clear succeeds whether or "
            + "not a prior report exists, and each execution owns its own generation")
    void theJobRunsEndToEnd() throws Exception {
        final long executionsBefore = jobExecutionRows();

        runner().run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(jobExecutionRows())
                    .as("bringing the context up must not fire a run")
                    .isEqualTo(executionsBefore);

            final JobRegistry registry = context.getBean(JobRegistry.class);
            assertThat(registry.getJobNames())
                    .as("the batch controller launches by name, so the job must be registered under one")
                    .contains(CategoryBalanceReportJobConfig.JOB_NAME);

            final Job job = registry.getJob(CategoryBalanceReportJobConfig.JOB_NAME);
            assertThat(job)
                    .as("a flow job is the shape a condition-code gate produces, and the member declares "
                            + "no gate on any step")
                    .isInstanceOf(SimpleJob.class);
            assertThat(((SimpleJob) job).getStepNames())
                    .containsExactly(
                            CategoryBalanceReportJobConfig.CLEAR_PRIOR_REPORT_STEP_NAME,
                            CategoryBalanceReportJobConfig.UNLOAD_STEP_NAME,
                            CategoryBalanceReportJobConfig.SORT_AND_REPROJECT_STEP_NAME)
                    .doesNotHaveDuplicates()
                    .hasSize(CategoryBalanceReportJobConfig.STEP_COUNT);

            final JobOperator operator = context.getBean(JobOperator.class);
            final JobExplorer explorer = context.getBean(JobExplorer.class);
            final BatchStagingArea stagingArea = context.getBean(BatchStagingArea.class);

            assertThat(report())
                    .as("the first run must clear an output that does not exist yet")
                    .doesNotExist();
            final JobExecution first = run(operator, explorer);
            assertCompleted(first);
            assertNoDateParameter(first);
            verifyReport();
            assertThat(generations()).hasSize(1);
            verifyUnload(generations().get(0));

            assertThat(report())
                    .as("the second run must clear an output that does exist")
                    .exists();
            final JobExecution second = run(operator, explorer);
            assertCompleted(second);
            assertThat(second.getId()).isNotEqualTo(first.getId());
            verifyReport();

            assertThat(generations())
                    .as("a new generation per execution, which is what the relative generation the "
                            + "member names resolves to")
                    .hasSize(2);
            for (final Path generation : generations()) {
                verifyUnload(generation);
                verify(stagingArea).publish(generation);
            }
            verify(stagingArea, times(2)).publish(report());

            assertThat(explorer.findRunningJobExecutions(CategoryBalanceReportJobConfig.JOB_NAME))
                    .as("nothing may be left running")
                    .isEmpty();
        });
    }

    // ------------------------------------------------------------------ the runtime

    /**
     * The context this job runs in: the framework's own auto-configurations over the migrated container,
     * plus exactly the collaborators the job declares.
     *
     * @return the runner
     */
    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        DataSourceAutoConfiguration.class,
                        DataSourceTransactionManagerAutoConfiguration.class,
                        JdbcTemplateAutoConfiguration.class,
                        HibernateJpaAutoConfiguration.class,
                        BatchAutoConfiguration.class,
                        ObservationAutoConfiguration.class,
                        BatchObservationAutoConfiguration.class,
                        MetricsAutoConfiguration.class,
                        CompositeMeterRegistryAutoConfiguration.class,
                        SimpleMetricsExportAutoConfiguration.class))
                .withUserConfiguration(JobUnderTest.class)
                .withBean(BatchStagingArea.class, () -> mock(BatchStagingArea.class))
                .withPropertyValues(
                        "spring.datasource.url=" + jdbcUrl(),
                        "spring.datasource.username=" + databaseUser(),
                        "spring.datasource.password=" + databasePassword(),
                        "spring.jpa.hibernate.ddl-auto=validate",
                        "spring.jpa.open-in-view=false",
                        "spring.batch.job.enabled=false",
                        "spring.batch.jdbc.initialize-schema=always",
                        "carddemo.aws.s3.batch-staging-bucket=category-balance-it",
                        "carddemo.batch.category-balance-report.staging-directory="
                                + this.stagingDirectory.toAbsolutePath());
    }

    /**
     * Launches the job by name and advances it to its next instance, which is how the batch controller
     * starts one.
     *
     * @param operator the framework's operator
     * @param explorer the framework's explorer
     * @return the execution that ran
     * @throws Exception if the launch is refused
     */
    private static JobExecution run(final JobOperator operator, final JobExplorer explorer)
            throws Exception {
        return explorer.getJobExecution(
                operator.startNextInstance(CategoryBalanceReportJobConfig.JOB_NAME));
    }

    /**
     * Asserts an execution completed with all three steps completed, so no transition short-circuited it.
     *
     * @param execution the execution to inspect
     */
    private static void assertCompleted(final JobExecution execution) {
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(execution.getStepExecutions())
                .as("all three steps run, because no step carries a condition-code gate")
                .hasSize(CategoryBalanceReportJobConfig.STEP_COUNT)
                .allSatisfy(step -> assertThat(step.getStatus()).isEqualTo(BatchStatus.COMPLETED));
        assertThat(execution.getStepExecutions())
                .extracting(StepExecution::getStepName)
                .doesNotHaveDuplicates();
    }

    /**
     * Asserts the launch surface carries no date parameter, guarding the legacy comment that claims a
     * date filter its control stream does not contain.
     *
     * @param execution the execution to inspect
     */
    private static void assertNoDateParameter(final JobExecution execution) {
        final List<String> keys =
                new ArrayList<>(execution.getJobParameters().getParameters().keySet());

        assertThat(keys)
                .as("a date parameter here would be the one the line-41 comment describes and the "
                        + "control stream refuses")
                .allSatisfy(key -> assertThat(key.toLowerCase(java.util.Locale.ROOT))
                        .doesNotContain("date"))
                .hasSizeLessThanOrEqualTo(1);
    }

    // ------------------------------------------------------------------ the staged datasets

    /**
     * The report dataset this run writes.
     *
     * @return the report path
     */
    private Path report() {
        return this.stagingDirectory.resolve(REPORT_DATASET);
    }

    /**
     * Every backup generation present in the staging directory, in name order.
     *
     * @return the generations
     * @throws java.io.IOException if the directory cannot be listed
     */
    private List<Path> generations() throws java.io.IOException {
        try (Stream<Path> entries = Files.list(this.stagingDirectory)) {
            return entries
                    .filter(path -> path.getFileName().toString().startsWith(GENERATION_PREFIX))
                    .sorted()
                    .toList();
        }
    }

    /**
     * Asserts one generation carries every row at the category-balance record width.
     *
     * @param generation the generation to inspect
     * @throws java.io.IOException if it cannot be read
     */
    private static void verifyUnload(final Path generation) throws java.io.IOException {
        final List<String> records = Files.readAllLines(generation, StandardCharsets.US_ASCII);

        assertThat(records).hasSize(EXPECTED_ROWS);
        assertThat(records).allSatisfy(record ->
                assertThat(record.getBytes(StandardCharsets.US_ASCII).length)
                        .as("the unload is the fifty-byte category-balance layout")
                        .isEqualTo(CategoryBalanceReportJobConfig.UNLOAD_RECORD_LENGTH));
    }

    /**
     * Asserts the report's width contract and its ordering.
     *
     * @throws java.io.IOException if it cannot be read
     */
    private void verifyReport() throws java.io.IOException {
        final List<String> lines = Files.readAllLines(report(), StandardCharsets.US_ASCII);

        assertThat(lines).hasSize(EXPECTED_ROWS);
        assertThat(lines).allSatisfy(line -> {
            assertThat(line.getBytes(StandardCharsets.US_ASCII).length)
                    .as("the declared record length is the dataset contract, and the reprojection's own "
                            + "filler run would have produced one byte more")
                    .isEqualTo(CategoryBalanceReportJobConfig.REPORT_RECORD_LENGTH)
                    .isNotEqualTo(41);
            assertThat(line.substring(CategoryBalanceReportJobConfig.REPORT_CONTENT_LENGTH))
                    .as("eight trailing blanks, not nine")
                    .isEqualTo(" ".repeat(CategoryBalanceReportJobConfig.REPORT_TRAILING_FILLER_LENGTH));
            assertThat(line.charAt(11)).isEqualTo(' ');
            assertThat(line.charAt(14)).isEqualTo(' ');
            assertThat(line.charAt(19)).isEqualTo(' ');
        });

        final List<String> keys = new ArrayList<>();
        lines.forEach(line -> keys.add(line.substring(0, KEY_PREFIX_WIDTH)));

        assertThat(keys.subList(0, 5))
                .as("account identifier ascending first; then the character type code "
                        + "lexicographically, so 01 precedes 0A precedes 0B; and within one type code "
                        + "the zoned-decimal category code numerically, so the overpunched minus one "
                        + "sorts ahead of plus one - which a lexicographic comparison would reverse")
                .containsExactly(
                        SEEDED_ACCOUNT + " 01 000J",
                        SEEDED_ACCOUNT + " 01 0001",
                        SEEDED_ACCOUNT + " 01 000A",
                        SEEDED_ACCOUNT + " 0A 0001",
                        SEEDED_ACCOUNT + " 0B 0001");

        assertThat(keys.subList(0, 5))
                .as("the balance is not a key: these five carry 900.00, 0.00, 500.00, 100.00 and 0.05, "
                        + "whose own order contradicts the key order above")
                .isNotEqualTo(List.of(
                        SEEDED_ACCOUNT + " 01 0001",
                        SEEDED_ACCOUNT + " 0B 0001",
                        SEEDED_ACCOUNT + " 0A 0001",
                        SEEDED_ACCOUNT + " 01 000A",
                        SEEDED_ACCOUNT + " 01 000J"));

        // EDIT=(TTTTTTTTT.TT) is written entirely from the always-print digit selector, so every one
        // of the eleven digit positions carries a digit for every value and nothing is ever blanked.
        assertThat(amountOf(lines.get(0)))
                .as("every declared integer digit position is emitted, leading zeros included")
                .isEqualTo("000000900.00");
        assertThat(amountOf(lines.get(1)))
                .as("a balance of exactly zero renders nine zeros, the point and two more zeros - it "
                        + "does NOT blank the field, which is what the zero-suppressing selector would "
                        + "have done and this specification does not use it")
                .isEqualTo("000000000.00");
        assertThat(amountOf(lines.get(4)))
                .as("a magnitude below one carries nine integer zeros, the point and both fractional "
                        + "digits")
                .isEqualTo("000000000.05");
        assertThat(lines)
                .as("no line may carry a blank anywhere inside the twelve-character mask")
                .allSatisfy(line -> assertThat(amountOf(line)).doesNotContain(" "));

        // Beyond the five discriminating rows every key is plainly zero padded, so for that tail - and
        // ONLY for that tail - a character comparison and a zoned-decimal one agree. The whole file is
        // deliberately NOT in character order: the overpunched category code above is exactly the row
        // that separates the two readings, and a file that were in character order throughout would be
        // evidence that the zoned-decimal typing had been lost.
        final List<String> plainlyPaddedKeys = new ArrayList<>(keys.subList(5, keys.size()));
        final List<String> ascending = new ArrayList<>(plainlyPaddedKeys);
        ascending.sort(null);
        assertThat(plainlyPaddedKeys)
                .as("every remaining key is zero padded, so the tail is ascending under either reading")
                .isEqualTo(ascending)
                .hasSize(SEEDED_ROWS - 1);
        assertThat(keys)
                .as("the file as a whole is NOT in character order, which is what proves the "
                        + "zoned-decimal keys were compared as numbers")
                .isNotEqualTo(keys.stream().sorted().toList());
    }

    /**
     * The edited balance a report line carries.
     *
     * @param line the report line
     * @return the twelve-character mask
     */
    private static String amountOf(final String line) {
        return line.substring(KEY_PREFIX_WIDTH + 1,
                KEY_PREFIX_WIDTH + 1 + CategoryBalanceReportJobConfig.BALANCE_MASK_WIDTH);
    }

    // ------------------------------------------------------------------ framework metadata

    /**
     * Rows the framework's execution table holds, or zero before the framework has created it.
     *
     * @return the row count
     * @throws SQLException if the server cannot be reached
     */
    private static long jobExecutionRows() throws SQLException {
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            try (ResultSet present = statement.executeQuery(
                    "SELECT count(*) FROM information_schema.tables WHERE table_schema = 'public'"
                            + " AND table_name = 'batch_job_execution'")) {
                assertThat(present.next()).isTrue();
                if (present.getLong(1) == 0L) {
                    return 0L;
                }
            }
            try (ResultSet rows =
                    statement.executeQuery("SELECT count(*) FROM batch_job_execution")) {
                assertThat(rows.next()).isTrue();
                return rows.getLong(1);
            }
        }
    }
}
