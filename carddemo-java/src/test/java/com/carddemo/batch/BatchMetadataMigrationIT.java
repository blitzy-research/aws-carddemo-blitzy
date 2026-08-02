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
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import javax.sql.DataSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.repository.support.JobRepositoryFactoryBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

import com.carddemo.support.AbstractPostgresIT;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that {@code V1_1__create_batch_metadata.sql} really provisions the job repository, against a
 * real PostgreSQL server migrated by the real migrations.
 *
 * <h2>Why this test exists</h2>
 * Every profile sets {@code spring.batch.jdbc.initialize-schema: never}, so the framework does not
 * create its own metadata tables anywhere. That makes one migration the sole owner of them, and it makes
 * this test the only thing standing between a framework upgrade and a production deployment whose first
 * job launch fails on a missing relation or an absent column.
 *
 * <p>The situation this guards against already happened once in this module, which is why the guard is
 * this specific. The production overlay declared {@code never} while no migration created the tables at
 * all, and the local and test profiles declared {@code always} - so the framework created them in the
 * two environments able to reveal the gap, and production was the single environment where the gap was
 * fatal. Correcting the setting is not enough on its own: the setting is now uniform, but a transcription
 * of the framework's DDL can still drift from the framework itself on an upgrade, silently, because
 * nothing else in the module reads either.</p>
 *
 * <h2>What is asserted, and why each assertion is independent of the others</h2>
 * <ol>
 *   <li><b>Presence and shape.</b> Exactly six {@code batch_}-prefixed tables and three sequences exist,
 *       and they are additional to - never instead of - the eleven application tables. This catches a
 *       migration that failed to run at all.</li>
 *   <li><b>Fidelity to the framework.</b> The migration's DDL is compared statement by statement against
 *       {@code org/springframework/batch/core/schema-postgresql.sql}, read from the resolved
 *       {@code spring-batch-core} artefact on this very class path. This is the upgrade tripwire: raise
 *       the framework version and change its schema, and this fails with the diff rather than a
 *       deployment failing later.</li>
 *   <li><b>Usability.</b> A real {@link JobRepository} is built over the migrated database and used to
 *       create, update and read back a job execution and a step execution. This is the assertion that a
 *       catalogue comparison cannot make: it exercises the framework's own SQL - its inserts, its
 *       sequence reads, its optimistic-locking updates and its context serialisation - against the
 *       transcribed tables. A column present but too narrow, or a sequence missing, fails here.</li>
 * </ol>
 *
 * <p>Provenance: this test has no legacy antecedent; the legacy estate carries no test harness. It
 * guards the job-repository schema that replaces the z/OS job scheduler's own bookkeeping, for the
 * checkout {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19.</p>
 */
@DisplayName("batch metadata migration, verified against a real database and the framework's own script")
class BatchMetadataMigrationIT extends AbstractPostgresIT {

    /** The framework's own PostgreSQL script, the authority this migration transcribes. */
    private static final String FRAMEWORK_SCRIPT = "org/springframework/batch/core/schema-postgresql.sql";

    /** The migration that must transcribe it. */
    private static final String MIGRATION = "db/migration/V1_1__create_batch_metadata.sql";

    /** The six metadata tables, in catalogue order. */
    private static final List<String> METADATA_TABLES = List.of(
            "batch_job_execution",
            "batch_job_execution_context",
            "batch_job_execution_params",
            "batch_job_instance",
            "batch_step_execution",
            "batch_step_execution_context");

    /** The three sequences, in catalogue order. */
    private static final List<String> METADATA_SEQUENCES = List.of(
            "batch_job_execution_seq",
            "batch_job_seq",
            "batch_step_execution_seq");

    /** The eleven application tables the migration must not disturb. */
    private static final int APPLICATION_TABLE_COUNT = 11;

    @Nested
    @DisplayName("presence and shape")
    final class PresenceAndShape {

        @Test
        @DisplayName("creates exactly the six metadata tables and the three sequences")
        void createsExactlyTheSixTablesAndThreeSequences() throws SQLException {
            assertThat(metadataTables())
                    .as("a job launch reads and writes all six. Any one missing is a missing-relation "
                            + "failure on the first launch, which is what `initialize-schema: never` "
                            + "without an owning migration produced in production")
                    .containsExactlyElementsOf(METADATA_TABLES);

            assertThat(metadataSequences())
                    .as("the framework reads a sequence to allocate each identifier, so a missing "
                            + "sequence fails a launch just as surely as a missing table - and does it "
                            + "in a way a table-only check would not see")
                    .containsExactlyElementsOf(METADATA_SEQUENCES);
        }

        @Test
        @DisplayName("leaves the eleven application tables untouched, so the two families stay distinct")
        void leavesTheApplicationTablesUntouched() throws SQLException {
            assertThat(applicationTableCount())
                    .as("the metadata tables are ADDITIONAL to the eleven, never part of them. That is "
                            + "why they are a separate migration and carry a distinguishing prefix: a "
                            + "count that included them would be counting the wrong thing")
                    .isEqualTo(APPLICATION_TABLE_COUNT);
        }
    }

    @Nested
    @DisplayName("fidelity to the framework's own script")
    final class FidelityToTheFramework {

        @Test
        @DisplayName("transcribes every statement of the resolved framework script, unaltered")
        void transcribesTheFrameworkScriptUnaltered() throws IOException {
            List<String> framework = statementsOf(stripComments(read(FRAMEWORK_SCRIPT)));
            List<String> migration = statementsOf(stripComments(read(MIGRATION)));

            assertThat(migration)
                    .as("the migration is a transcription, not an authored schema. Comparing statements "
                            + "rather than eyeballing them is what makes a framework upgrade a visible "
                            + "diff: change the resolved spring-batch-core version to one whose schema "
                            + "differs and this fails here, at build time, instead of at a deployment's "
                            + "first job launch. If it fails, re-transcribe %s from %s rather than "
                            + "loosening this assertion", MIGRATION, FRAMEWORK_SCRIPT)
                    .containsExactlyElementsOf(framework);
        }
    }

    @Nested
    @DisplayName("usability by a real job repository")
    final class UsabilityByARealJobRepository {

        @Test
        @DisplayName("accepts a job execution and a step execution written and read back by the "
                + "framework's own repository")
        void aRealJobRepositoryRoundTripsAnExecution() throws Exception {
            JobRepository repository = jobRepository();
            JobParameters parameters = new JobParametersBuilder()
                    .addString("probe", "batch-metadata-migration")
                    .toJobParameters();

            JobExecution execution = repository.createJobExecution("metadataProbeJob", parameters);

            assertThat(execution.getId())
                    .as("an identifier is allocated from batch_job_seq, so a null here means the "
                            + "sequence is absent or unreadable however complete the tables look")
                    .isNotNull();
            assertThat(execution.getJobInstance().getId())
                    .as("and the instance identifier comes from batch_job_instance plus its own "
                            + "sequence, through the unique constraint on name and key")
                    .isNotNull();

            StepExecution step = execution.createStepExecution("metadataProbeStep");
            repository.add(step);
            step.setReadCount(7);
            step.setWriteCount(7);
            step.setStatus(BatchStatus.COMPLETED);
            step.getExecutionContext().putString("probe.context", "written through the framework");
            repository.update(step);
            repository.updateExecutionContext(step);

            execution.setStatus(BatchStatus.COMPLETED);
            repository.update(execution);

            StepExecution reloaded = repository.getLastStepExecution(
                    execution.getJobInstance(), "metadataProbeStep");
            assertThat(reloaded)
                    .as("reading it back exercises the repository's select path over "
                            + "batch_step_execution and its context table")
                    .isNotNull();
            assertThat(reloaded.getReadCount()).isEqualTo(7);
            assertThat(reloaded.getStatus()).isEqualTo(BatchStatus.COMPLETED);
            assertThat(reloaded.getExecutionContext().getString("probe.context"))
                    .as("the serialised context is stored in the wider of the two context columns, so "
                            + "a column left too narrow surfaces on this read")
                    .isEqualTo("written through the framework");
        }
    }

    /**
     * Builds the framework's own JDBC-backed job repository over the migrated container database.
     *
     * <p>Constructed directly rather than through an application context: the point is to exercise the
     * framework's SQL against the migrated tables, and interposing the module's own configuration would
     * make a failure ambiguous between the two.</p>
     *
     * @return a usable repository
     * @throws Exception when the repository cannot be initialised
     */
    private static JobRepository jobRepository() throws Exception {
        DataSource dataSource = new SimpleDriverDataSource(
                new org.postgresql.Driver(), jdbcUrl(), databaseUser(), databasePassword());
        JobRepositoryFactoryBean factory = new JobRepositoryFactoryBean();
        factory.setDataSource(dataSource);
        factory.setTransactionManager(new DataSourceTransactionManager(dataSource));
        factory.setTablePrefix("BATCH_");
        factory.afterPropertiesSet();
        return factory.getObject();
    }

    /**
     * Lists the metadata tables the database carries.
     *
     * @return the table names, in catalogue order
     * @throws SQLException when the catalogue read fails
     */
    private static List<String> metadataTables() throws SQLException {
        return queryNames("""
                SELECT table_name FROM information_schema.tables
                 WHERE table_schema = 'public' AND table_name LIKE 'batch\\_%'
                 ORDER BY table_name
                """);
    }

    /**
     * Lists the metadata sequences the database carries.
     *
     * @return the sequence names, in catalogue order
     * @throws SQLException when the catalogue read fails
     */
    private static List<String> metadataSequences() throws SQLException {
        return queryNames("""
                SELECT sequence_name FROM information_schema.sequences
                 WHERE sequence_schema = 'public' AND sequence_name LIKE 'batch\\_%'
                 ORDER BY sequence_name
                """);
    }

    /**
     * Counts the tables that are neither metadata nor the migration history.
     *
     * @return the application table count
     * @throws SQLException when the catalogue read fails
     */
    private static int applicationTableCount() throws SQLException {
        return queryNames("""
                SELECT table_name FROM information_schema.tables
                 WHERE table_schema = 'public'
                   AND table_name NOT LIKE 'batch\\_%'
                   AND table_name <> 'flyway_schema_history'
                 ORDER BY table_name
                """).size();
    }

    /**
     * Runs a fixed single-column catalogue query. Every caller supplies a complete literal statement, so
     * no value and no identifier is concatenated into SQL.
     *
     * @param sql the literal query
     * @return the values of the single projected column
     * @throws SQLException when the read fails
     */
    private static List<String> queryNames(final String sql) throws SQLException {
        List<String> names = new ArrayList<>();
        try (Connection connection = connect();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            while (resultSet.next()) {
                names.add(resultSet.getString(1));
            }
        }
        return names;
    }

    /**
     * Reads a class-path resource as text.
     *
     * @param location the class-path location
     * @return the resource's text
     * @throws IOException when the resource cannot be read
     */
    private static String read(final String location) throws IOException {
        try (InputStream stream = new ClassPathResource(location).getInputStream()) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * Removes whole comment lines, leaving the statements.
     *
     * <p>Applied to BOTH files, which is not symmetry for its own sake: the framework's script opens with
     * its own generated-file notice, and leaving that in would attach it to the first statement and make
     * every comparison fail for a reason that has nothing to do with the schema.</p>
     *
     * <p>Only whole comment lines are removed. No statement in either file carries a trailing comment, so
     * nothing else needs stripping and nothing inside a statement is touched.</p>
     *
     * @param text the DDL text
     * @return the text without comment lines
     */
    private static String stripComments(final String text) {
        return text.lines()
                .filter(line -> !line.stripLeading().startsWith("--"))
                .reduce(new StringBuilder(), (builder, line) -> builder.append(line).append('\n'),
                        StringBuilder::append)
                .toString();
    }

    /**
     * Splits DDL into statements normalised for comparison.
     *
     * <p>Whitespace is collapsed and the terminating semicolon dropped, so the comparison is about the
     * statements and not about the indentation or the blank lines around them.</p>
     *
     * @param ddl the DDL text
     * @return one normalised statement per element, in source order
     */
    private static List<String> statementsOf(final String ddl) {
        List<String> statements = new ArrayList<>();
        for (final String fragment : ddl.split(";")) {
            String normalised = fragment.replaceAll("\\s+", " ").trim();
            if (!normalised.isEmpty()) {
                statements.add(normalised);
            }
        }
        return statements;
    }
}
