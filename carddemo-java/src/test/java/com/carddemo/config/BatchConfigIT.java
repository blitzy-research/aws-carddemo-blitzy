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
package com.carddemo.config;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import org.springframework.batch.core.JobExecutionListener;
import org.springframework.batch.core.JobParametersIncrementer;
import org.springframework.batch.core.configuration.JobRegistry;
import org.springframework.batch.core.configuration.annotation.BatchObservabilityBeanPostProcessor;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.boot.actuate.autoconfigure.metrics.CompositeMeterRegistryAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.metrics.MetricsAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.metrics.export.simple.SimpleMetricsExportAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.observation.ObservationAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.observation.batch.BatchObservationAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.batch.BatchAutoConfiguration;
import org.springframework.boot.autoconfigure.batch.JobLauncherApplicationRunner;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.carddemo.support.AbstractPostgresIT;
import com.carddemo.support.SchemaColumnCatalog;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boots the batch runtime the way a starting application boots it &mdash; the real batch
 * auto-configuration, the real {@link BatchConfig}, and a real PostgreSQL 16 server migrated by the real
 * migrations &mdash; and proves the three things only a started context can prove.
 *
 * <h2>Why a container is needed and a unit test will not do</h2>
 *
 * <p>The unit suite beside this one asserts what {@code BatchConfig} publishes in isolation. Isolation is
 * exactly what it cannot assert: every risk this class exists for arises from {@code BatchConfig} and the
 * framework's own batch auto-configuration being present <em>together</em> over a real data source.</p>
 *
 * <ol>
 *   <li><b>Nothing duplicated.</b> The job repository, launcher, explorer, registry and operator all
 *       arrive from the auto-configuration. A second definition of any of them would either abort the
 *       context on a definition clash or, worse, silently shadow the framework's wiring. Each is asserted
 *       to resolve to exactly one bean.</li>
 *   <li><b>Nothing fired.</b> A legacy job was submitted deliberately, so bringing a context up must add
 *       no job execution. Asserted by counting the framework's own execution rows across the start rather
 *       than by asserting an absolute zero, because the shared server is migrated once and reused: an
 *       absolute count would pass or fail on which test ran first, while a delta is order-independent and
 *       is the property actually being claimed.</li>
 *   <li><b>Nothing suppressed.</b> The post-processor that hands the observation registry to every job
 *       and step is what makes step-level timings reachable at the metrics scrape endpoint. It is
 *       published by the management auto-configuration only while no other bean of its type exists, so
 *       adding the batch enabling annotation or registering a second registry would defeat it. Both its
 *       presence and the single meter registry are asserted.</li>
 * </ol>
 *
 * <h2>What is deliberately not booted</h2>
 *
 * <p>The web, security, contract-publication and cloud auto-configurations are left out. None of them
 * participates in any risk above, and pulling them in would make this class depend on a running cloud
 * emulator, so a failure here would stop being attributable to the batch wiring. The two batch
 * <em>schema</em> settings the shipped documents declare are supplied verbatim, so the framework
 * provisions its metadata exactly as it does in a deployment.</p>
 *
 * <p>Provenance: no legacy antecedent; the migrated estate carries no test harness. Guards the batch
 * infrastructure of the checkout {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19.</p>
 */
@DisplayName("batch runtime, booted against a real server: nothing duplicated, nothing fired, nothing suppressed")
class BatchConfigIT extends AbstractPostgresIT {

    /** The metadata-table prefix every shipped document declares. */
    private static final String TABLE_PREFIX = "BATCH_";

    /** The framework's execution table, whose row count must not move when a context starts. */
    private static final String JOB_EXECUTION_TABLE = "batch_job_execution";

    /** The six metadata tables the framework's own script provisions. */
    private static final List<String> METADATA_TABLES = List.of(
            "batch_job_execution",
            "batch_job_execution_context",
            "batch_job_execution_params",
            "batch_job_instance",
            "batch_step_execution",
            "batch_step_execution_context");

    /** The bean name the shared job-boundary listener is published under. */
    private static final String LISTENER_BEAN_NAME = "batchJobBoundaryListener";

    /** The bean name the shared parameter incrementer is published under. */
    private static final String INCREMENTER_BEAN_NAME = "batchJobRunIncrementer";

    /**
     * The batch runtime as a deployment assembles it: the framework's own auto-configurations, the real
     * configuration under test, and the two schema settings the shipped documents declare.
     */
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    DataSourceAutoConfiguration.class,
                    DataSourceTransactionManagerAutoConfiguration.class,
                    BatchAutoConfiguration.class,
                    ObservationAutoConfiguration.class,
                    BatchObservationAutoConfiguration.class,
                    MetricsAutoConfiguration.class,
                    CompositeMeterRegistryAutoConfiguration.class,
                    SimpleMetricsExportAutoConfiguration.class))
            .withUserConfiguration(BatchConfig.class)
            .withPropertyValues(
                    "spring.datasource.url=" + jdbcUrl(),
                    "spring.datasource.username=" + databaseUser(),
                    "spring.datasource.password=" + databasePassword(),
                    "spring.datasource.driver-class-name=" + driverClassName(),
                    "spring.batch.job.enabled=false",
                    "spring.batch.jdbc.initialize-schema=always",
                    "spring.batch.jdbc.table-prefix=" + TABLE_PREFIX);

    /**
     * The number of rows in the framework's execution table, or zero while the table does not yet exist.
     *
     * @return the current execution row count
     * @throws SQLException if the server cannot be read
     */
    private static long jobExecutionRowCount() throws SQLException {
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            try (ResultSet counted = statement.executeQuery(
                    "SELECT count(*) FROM information_schema.tables WHERE table_schema = 'public'"
                            + " AND table_name = '" + JOB_EXECUTION_TABLE + "'")) {
                assertThat(counted.next()).isTrue();
                if (counted.getLong(1) == 0L) {
                    return 0L;
                }
            }
            try (ResultSet rows = statement.executeQuery(
                    "SELECT count(*) FROM " + JOB_EXECUTION_TABLE)) {
                assertThat(rows.next()).isTrue();
                return rows.getLong(1);
            }
        }
    }

    /**
     * Every table in the public schema, lower-cased, as the server reports it.
     *
     * @return the table names present
     * @throws SQLException if the server cannot be read
     */
    private static List<String> publicTableNames() throws SQLException {
        final List<String> present = new ArrayList<>();
        try (Connection connection = connect();
                Statement statement = connection.createStatement();
                ResultSet tables = statement.executeQuery(
                        "SELECT table_name FROM information_schema.tables"
                                + " WHERE table_schema = 'public' ORDER BY table_name")) {
            while (tables.next()) {
                present.add(tables.getString(1));
            }
        }
        return present;
    }

    @Nested
    @DisplayName("Nothing the framework already publishes is duplicated")
    class NothingDuplicated {

        @Test
        @DisplayName("the context starts, so no definition clash, ambiguous bean or duplicate enabling "
                + "annotation reaches a deployment")
        void theContextStarts() {
            runner.run(context -> assertThat(context).hasNotFailed());
        }

        @Test
        @DisplayName("the job repository, launcher, explorer, registry and operator each resolve to "
                + "exactly one bean, so the auto-configuration is intact and unshadowed")
        void theFrameworkWiringResolvesToExactlyOneBeanEach() {
            runner.run(context -> {
                assertThat(context).hasSingleBean(JobRepository.class);
                assertThat(context).hasSingleBean(JobLauncher.class);
                assertThat(context).hasSingleBean(JobExplorer.class);
                assertThat(context).hasSingleBean(JobRegistry.class);
                assertThat(context).hasSingleBean(JobOperator.class);
            });
        }

        @Test
        @DisplayName("the two shared collaborators are the ones this configuration published, so a job "
                + "configuration injecting them gets these and not a framework default")
        void theTwoSharedCollaboratorsAreThePublishedOnes() {
            runner.run(context -> {
                assertThat(context).hasSingleBean(JobExecutionListener.class);
                assertThat(context).hasSingleBean(JobParametersIncrementer.class);
                assertThat(context.getBean(LISTENER_BEAN_NAME))
                        .isSameAs(context.getBean(JobExecutionListener.class));
                assertThat(context.getBean(INCREMENTER_BEAN_NAME))
                        .isSameAs(context.getBean(JobParametersIncrementer.class));
            });
        }

        @Test
        @DisplayName("the job registry holds no job, because batch infrastructure defines none - the nine "
                + "job configurations do")
        void theJobRegistryHoldsNoJob() {
            runner.run(context -> assertThat(context.getBean(JobRegistry.class).getJobNames())
                    .as("a job registered from here would be a job defined in the infrastructure layer")
                    .isEmpty());
        }
    }

    @Nested
    @DisplayName("Starting the context runs no job")
    class NothingFired {

        @Test
        @DisplayName("the framework's execution row count is unchanged across the start, so no job ran")
        void noJobExecutionIsRecordedByStartingUp() throws SQLException {
            long before = jobExecutionRowCount();

            runner.run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(jobExecutionRowCount())
                        .as("restarting a deployment must not fire a posting run")
                        .isEqualTo(before);
            });

            assertThat(jobExecutionRowCount())
                    .as("and closing the context must not fire one either")
                    .isEqualTo(before);
        }

        @Test
        @DisplayName("no execution is running immediately after the context is up")
        void noExecutionIsRunningAfterTheContextIsUp() {
            runner.run(context -> {
                JobExplorer explorer = context.getBean(JobExplorer.class);
                for (String jobName : explorer.getJobNames()) {
                    assertThat(explorer.findRunningJobExecutions(jobName))
                            .as("job %s must not be running merely because a context came up", jobName)
                            .isEmpty();
                }
            });
        }

        @Test
        @DisplayName("the framework's own start-up runner is absent, which is what the shipped "
                + "launch-on-start setting achieves")
        void theFrameworkStartUpRunnerIsAbsent() {
            runner.run(context -> assertThat(context).doesNotHaveBean(JobLauncherApplicationRunner.class));
        }
    }

    @Nested
    @DisplayName("Metadata provisioning stays with the framework")
    class MetadataProvisioning {

        @Test
        @DisplayName("starting the context provisions the framework's own metadata tables, additional to "
                + "the eleven application tables a migration created")
        void theFrameworkProvisionsItsOwnMetadataTables() {
            runner.run(context -> {
                assertThat(context).hasNotFailed();

                List<String> present = publicTableNames();
                assertThat(present)
                        .as("the framework's script, not a migration, creates these")
                        .containsAll(METADATA_TABLES);
                assertThat(present)
                        .as("and it disturbs none of the application tables the migrations own")
                        .containsAll(SchemaColumnCatalog.load().tableNames());
                assertThat(present.stream().filter(name -> !name.startsWith("batch_")
                                && !name.startsWith("flyway_")).toList())
                        .as("a business-table census excludes framework and migration-history "
                                + "tables; exactly eleven record-layout tables remain")
                        .hasSize(SchemaColumnCatalog.load().tableNames().size());
            });
        }
    }

    @Nested
    @DisplayName("Step-level timing is not suppressed")
    class TimingNotSuppressed {

        @Test
        @DisplayName("the observation post-processor that reaches every job and step is present exactly "
                + "once, so step timings reach the scrape endpoint")
        void theObservationPostProcessorIsPresentExactlyOnce() {
            runner.run(context -> {
                assertThat(context).hasSingleBean(BatchObservabilityBeanPostProcessor.class);
                assertThat(context).hasSingleBean(ObservationRegistry.class);
            });
        }

        @Test
        @DisplayName("exactly one meter registry exists, because this configuration registers none")
        void exactlyOneMeterRegistryExists() {
            runner.run(context -> assertThat(context.getBeanNamesForType(MeterRegistry.class))
                    .as("a second registry would split the exposition the performance baseline is read "
                            + "from")
                    .hasSize(1));
        }
    }
}
