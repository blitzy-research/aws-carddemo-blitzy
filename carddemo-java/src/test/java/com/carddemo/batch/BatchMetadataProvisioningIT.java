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

import org.junit.jupiter.api.BeforeAll;
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
import org.springframework.boot.autoconfigure.batch.BatchDataSourceScriptDatabaseInitializer;
import org.springframework.boot.autoconfigure.batch.BatchProperties;
import org.springframework.boot.sql.init.DatabaseInitializationMode;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

import com.carddemo.support.AbstractPostgresIT;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that Spring Batch really provisions its own job repository under the setting every profile
 * carries, against a real PostgreSQL server migrated by the real migrations.
 *
 * <h2>Why this test exists</h2>
 * Every profile sets {@code spring.batch.jdbc.initialize-schema: always}, so the framework creates its
 * own six metadata tables and three sequences from its own bundled PostgreSQL script - and no delivered
 * migration creates them. AAP 0.3.1 assigns that split deliberately: the job-repository metadata belongs
 * to the framework and changes with its version, while {@code V1__create_schema.sql} owns the eleven
 * application tables and the delivered migration inventory stays at exactly {@code V1}, {@code V2},
 * {@code V3} and {@code V4}.
 *
 * <p>Two failure modes make the guard necessary, and this module has met both. The first is a value that
 * differs between profiles: production once declared one value while local and test declared another, so
 * production became the single environment in which a job launch could fail on a missing relation, and the
 * two environments able to reveal it were the two configured not to. The second is a re-transcription: a
 * migration that copied the framework's DDL would drift from the framework silently on an upgrade,
 * because nothing else in the module reads either. This test therefore asserts both that the framework's
 * provisioning works and that no migration has taken the job over.
 *
 * <h2>What is asserted, and why each assertion is independent of the others</h2>
 * <ol>
 *   <li><b>Presence and shape.</b> The framework's own initialiser - the very component
 *       {@code initialize-schema: always} activates - creates exactly six {@code batch_}-prefixed tables
 *       and three sequences, and they are additional to, never instead of, the eleven application tables.
 *       This catches a framework artefact whose script is missing or unresolvable.</li>
 *   <li><b>Ownership.</b> No delivered migration script mentions a {@code BATCH_} object, and the
 *       framework artefact on this very class path really does carry the PostgreSQL script the initialiser
 *       resolves. This is the tripwire in both directions: it fails if a future author re-transcribes the
 *       framework's DDL into a migration, and it fails if the framework stops shipping the script the
 *       setting depends on.</li>
 *   <li><b>Usability.</b> A real {@link JobRepository} is built over the provisioned database and used to
 *       create, update and read back a job execution and a step execution. This is the assertion a
 *       catalogue comparison cannot make: it exercises the framework's own SQL - its inserts, its sequence
 *       reads, its optimistic-locking updates and its context serialisation - against the tables the
 *       initialiser produced.</li>
 * </ol>
 *
 * <p>Provenance: this test has no legacy antecedent; the legacy estate carries no test harness.
 */
@DisplayName("batch metadata provisioning, verified against a real database and the framework's own script")
class BatchMetadataProvisioningIT extends AbstractPostgresIT {

    /** The framework's own PostgreSQL script, which its initialiser resolves and runs. */
    private static final String FRAMEWORK_SCRIPT = "org/springframework/batch/core/schema-postgresql.sql";

    /** Every delivered migration script, wherever it sits under the two profile-scoped locations. */
    private static final String DELIVERED_MIGRATIONS = "classpath*:db/migration/**/V*__*.sql";

    /** The table prefix every profile declares, and the one the framework is configured with. */
    private static final String TABLE_PREFIX = "BATCH_";

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

    /**
     * The eleven record-layout tables the framework must not disturb.
     *
     * <p>Eleven, and eleven is the whole delivered schema: every table the migrations create carries a
     * verified record layout, so this figure and the delivered inventory are the same figure. This
     * class's subject is the framework's own family, which is additional to those eleven and is excluded
     * by the base class's roster. See {@code docs/decision-log.md} DL-352.</p>
     */
    private static final int APPLICATION_TABLE_COUNT = 11;

    /** Whether the initialiser reported that it did work, captured once for the assertions to read. */
    private static boolean initialiserDidWork;

    /**
     * Provisions the job repository exactly as a starting application does, and records the outcome.
     *
     * <p>The framework's own {@link BatchDataSourceScriptDatabaseInitializer} is constructed directly
     * rather than through an application context, and it is given the same
     * {@link DatabaseInitializationMode#ALWAYS} mode and the same table prefix the shipped profiles
     * declare. Constructing it directly is what makes a failure unambiguous: the question here is whether
     * the framework's script provisions the framework's tables, and interposing the module's own
     * auto-configuration would leave a failure ambiguous between the two.
     *
     * <p>{@link AbstractPostgresIT} deliberately does not create these tables. It migrates the two
     * delivered locations and nothing else, because no migration owns them - so this method is the only
     * thing that puts them there, and the assertions below are therefore about the framework's work and
     * not about a migration's.
     *
     * @throws Exception when the initialiser cannot be prepared
     */
    @BeforeAll
    static void provisionTheJobRepositoryTheWayTheFrameworkDoes() throws Exception {
        BatchProperties.Jdbc jdbc = new BatchProperties.Jdbc();
        jdbc.setInitializeSchema(DatabaseInitializationMode.ALWAYS);
        jdbc.setTablePrefix(TABLE_PREFIX);

        BatchDataSourceScriptDatabaseInitializer initializer =
                new BatchDataSourceScriptDatabaseInitializer(containerDataSource(), jdbc);
        initializer.setResourceLoader(new PathMatchingResourcePatternResolver());

        initialiserDidWork = initializer.initializeDatabase();
    }

    @Nested
    @DisplayName("presence and shape")
    final class PresenceAndShape {

        @Test
        @DisplayName("the framework's own initialiser creates exactly the six metadata tables and the "
                + "three sequences")
        void createsExactlyTheSixTablesAndThreeSequences() throws SQLException {
            assertThat(metadataTables())
                    .as("a job launch reads and writes all six. Any one missing is a missing-relation "
                            + "failure on the first launch, which is what a profile declaring "
                            + "`initialize-schema: never` with no migration owning the tables produced")
                    .containsExactlyElementsOf(METADATA_TABLES);

            assertThat(metadataSequences())
                    .as("the framework reads a sequence to allocate each identifier, so a missing "
                            + "sequence fails a launch just as surely as a missing table - and does it "
                            + "in a way a table-only check would not see")
                    .containsExactlyElementsOf(METADATA_SEQUENCES);
        }

        @Test
        @DisplayName("the initialiser reports that it ran, so the tables above are its work and not a "
                + "leftover from something else")
        void theInitialiserReportsThatItRan() {
            assertThat(initialiserDidWork)
                    .as("a false answer here would mean the tables were already present before this "
                            + "class ran - which, with no migration owning them, could only mean "
                            + "another test had provisioned them, and would make every assertion in "
                            + "this class about somebody else's work. The initialiser is idempotent, so "
                            + "the shared server is unaffected either way")
                    .isTrue();
        }

        @Test
        @DisplayName("leaves the eleven application tables untouched, so the two families stay distinct")
        void leavesTheApplicationTablesUntouched() throws SQLException {
            assertThat(applicationTableCount())
                    .as("the metadata tables are ADDITIONAL to the eleven, never part of them. That is "
                            + "why they carry a distinguishing prefix and belong to the framework: a "
                            + "count that included them would be counting the wrong thing")
                    .isEqualTo(APPLICATION_TABLE_COUNT);
        }
    }

    @Nested
    @DisplayName("ownership of the metadata schema")
    final class OwnershipOfTheMetadataSchema {

        @Test
        @DisplayName("the resolved framework artefact really carries the PostgreSQL script the setting "
                + "depends on, so `always` is not relying on something absent")
        void theFrameworkArtefactCarriesItsPostgresScript() throws IOException {
            String script = read(FRAMEWORK_SCRIPT);

            assertThat(script)
                    .as("this resource is what BatchDataSourceScriptDatabaseInitializer resolves for "
                            + "PostgreSQL. Were a framework upgrade to move or rename it, the setting "
                            + "every profile carries would silently provision nothing and the failure "
                            + "would surface at a deployment's first job launch")
                    .isNotBlank();
            assertThat(statementsOf(stripComments(script)))
                    .as("it must define every metadata table and sequence the repository uses")
                    .hasSizeGreaterThanOrEqualTo(
                            METADATA_TABLES.size() + METADATA_SEQUENCES.size());
        }

        @Test
        @DisplayName("no delivered migration creates a metadata object, so the framework is the single "
                + "owner and the delivered inventory stays at five scripts")
        void noDeliveredMigrationCreatesAMetadataObject() throws IOException {
            List<String> offending = new ArrayList<>();
            for (final Resource migration : deliveredMigrations()) {
                String body = readResource(migration).toUpperCase(java.util.Locale.ROOT);
                if (body.contains(TABLE_PREFIX + "JOB_") || body.contains(TABLE_PREFIX + "STEP_")) {
                    offending.add(String.valueOf(migration.getFilename()));
                }
            }

            assertThat(offending)
                    .as("a migration that re-transcribed the framework's DDL would give the database two "
                            + "owners of one family of objects, and would drift from the framework "
                            + "silently on the next upgrade because nothing in the module reads either. "
                            + "AAP 0.3.1 assigns this metadata to Spring Batch; if the framework's "
                            + "provisioning ever has to be replaced, replace it deliberately and update "
                            + "this test rather than adding a further migration quietly")
                    .isEmpty();
        }

        @Test
        @DisplayName("the delivered migrations are exactly the five named, so the framework taking the "
                + "metadata has not changed the inventory")
        void theDeliveredMigrationsAreExactlyTheFiveNamed() {
            assertThat(deliveredMigrations())
                    .extracting(Resource::getFilename)
                    .as("AAP 0.3.1 and 0.4.2 name the first four scripts, in two profile-scoped "
                            + "locations; the fifth is the protected-value invariants, added as a schema "
                            + "script by the security remediation and recorded in "
                            + "docs/decision-log.md DL-349. A SIXTH would mean something took on work "
                            + "this arrangement assigns elsewhere")
                    .containsExactlyInAnyOrder(
                            "V1__create_schema.sql",
                            "V2__create_indexes.sql",
                            "V3__seed_reference_data.sql",
                            "V4__seed_user_security.sql",
                            "V2_2__add_protected_value_invariants.sql");
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
                    .addString("probe", "batch-metadata-provisioning")
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
     * Builds a data source over the migrated container database.
     *
     * @return a usable data source
     */
    private static javax.sql.DataSource containerDataSource() {
        return new SimpleDriverDataSource(
                new org.postgresql.Driver(), jdbcUrl(), databaseUser(), databasePassword());
    }

    /**
     * Builds the framework's own JDBC-backed job repository over the provisioned container database.
     *
     * <p>Constructed directly rather than through an application context, for the same reason the
     * initialiser is: the point is to exercise the framework's SQL against the provisioned tables, and
     * interposing the module's own configuration would make a failure ambiguous between the two.</p>
     *
     * @return a usable repository
     * @throws Exception when the repository cannot be initialised
     */
    private static JobRepository jobRepository() throws Exception {
        javax.sql.DataSource dataSource = containerDataSource();
        JobRepositoryFactoryBean factory = new JobRepositoryFactoryBean();
        factory.setDataSource(dataSource);
        factory.setTransactionManager(new DataSourceTransactionManager(dataSource));
        factory.setTablePrefix(TABLE_PREFIX);
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
     * Counts the business tables, excluding framework metadata and migration history.
     *
     * @return the application table count
     * @throws SQLException when the catalogue read fails
     */
    private static int applicationTableCount() throws SQLException {
        // Delegated rather than re-queried. The exclusion list this figure depends on has two entries -
        // the framework's own family and the migration history - and a second copy of it drifts from the
        // first the moment a third is added. The base class is the one authority; the local queryNames
        // below still serves the metadata rosters, which are this class's own subject.
        return applicationTableNames().size();
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
     * Resolves every delivered migration script under the two profile-scoped locations.
     *
     * <p>The search is recursive, matching how Flyway resolves a location, so a script placed in a
     * sub-folder of either location is examined here exactly as the migration tool would apply it.</p>
     *
     * @return the delivered migration resources; never {@code null}
     */
    private static List<Resource> deliveredMigrations() {
        try {
            return List.of(new PathMatchingResourcePatternResolver()
                    .getResources(DELIVERED_MIGRATIONS));
        } catch (IOException unreadable) {
            throw new java.io.UncheckedIOException(
                    "the delivered migration locations are unreadable", unreadable);
        }
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
     * Reads a resolved resource as text.
     *
     * @param resource the resource to read
     * @return the resource's text
     * @throws IOException when the resource cannot be read
     */
    private static String readResource(final Resource resource) throws IOException {
        try (InputStream stream = resource.getInputStream()) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * Removes whole comment lines, leaving the statements.
     *
     * <p>The framework's script opens with its own generated-file notice, and leaving that in would
     * attach it to the first statement and make a statement count wrong for a reason that has nothing to
     * do with the schema.</p>
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
     * Splits DDL into statements normalised for counting.
     *
     * <p>Whitespace is collapsed and the terminating semicolon dropped, so the count is about the
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
