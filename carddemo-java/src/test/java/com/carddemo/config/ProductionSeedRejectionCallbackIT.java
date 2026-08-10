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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNoException;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.callback.Callback;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.carddemo.service.SensitiveFieldEncryptionService;
import com.carddemo.support.AbstractPostgresIT;

/**
 * Drives the production seeded-database refusal against a real PostgreSQL server, through real
 * migrations, and proves each of its three signals fires on the state that signal exists for.
 *
 * <h2>Why this cannot be a unit test</h2>
 *
 * <p>Every one of the three signals is a statement about what a database contains: an applied seed
 * version in the migration history, a reserved sign-on identity in {@code user_security}, and the
 * reference seed's row counts across five tables at once. A mocked result set would answer with
 * whatever this file chose, which is the thing under test. Only migrating the real scripts into a real
 * server and then opening that server under the production scope can show whether the refusal fires.
 *
 * <h2>The three states this exercises, and why all three are needed</h2>
 *
 * <ol>
 *   <li><strong>Seeded, with its history intact.</strong> The ordinary case, and the one the reviewed
 *       defect named: a database migrated under a seeding profile and later opened under the production
 *       profile. Signal one fires from the history.</li>
 *   <li><strong>Seeded, with the seed history entries removed.</strong> What a restored dump looks like
 *       when the history table was not carried across, or when somebody tidied it. Signal one is now
 *       blind; signal two fires from the ten reserved sign-on identities.</li>
 *   <li><strong>Seeded, with the history entries AND the sign-on rows removed.</strong> The reference
 *       seed's personal data is still there while both earlier signals are blind. Signal three fires
 *       from the five simultaneous row counts.</li>
 * </ol>
 *
 * <p>Each state is produced by deleting from the previous one, in that order, which is what makes the
 * three assertions independent: state two proves signal two rather than re-proving signal one, because
 * signal one has been made unevaluable first.
 *
 * <h2>And the case that must NOT be refused</h2>
 *
 * <p>A correctly migrated production database - the schema location alone, with no seed location -
 * must start, and must keep starting on every restart. That assertion is as load-bearing as the three
 * refusals: a control that refused a clean deployment would be worse than no control, and the third
 * signal in particular is the one most capable of a false positive, so it is exercised against a schema
 * that carries the eleven tables and no rows.
 *
 * <h2>And the two claims that only a started context can settle</h2>
 *
 * <p>The refusals above are driven through {@code Flyway.configure()}, which shows that the control
 * fires but not that a deployment is actually stopped by it. Two things therefore need a real Spring
 * context, and {@link #aStartedContextRefusesToRefreshAndCleansNothing()} supplies both.
 *
 * <p>The first is the timing the reviewed finding asks for: the rejection must land <em>before
 * production beans initialize</em>. That test wires the data source, the migration tool and the
 * persistence provider - the beans a deployment builds around this database - and asserts the refresh
 * fails, so no entity manager is ever created over seeded rows. It asserts first that the same wiring
 * refreshes cleanly against a never-seeded database, because otherwise a failed refresh would prove only
 * that the test's own wiring is broken. It also exercises the registration mechanism rather than only
 * the control, since the callback is honoured there solely because Spring Boot's Flyway
 * auto-configuration collects {@code Callback} beans and applies them.
 *
 * <p>The second is that the control <strong>refuses and does not repair</strong>. After the failed
 * refresh the fifty seeded customer rows and ten seeded sign-on identities are asserted still present. A
 * control that deleted what it objected to would destroy the evidence an operator needs, and on a
 * misidentified database would destroy real records; refusing is the only safe direction, and it is
 * asserted rather than merely intended.
 *
 * <h2>Why a database of its own</h2>
 *
 * <p>{@link AbstractPostgresIT} migrates the shared default schema from both delivered locations, so
 * every other integration test in the run reads seeded rows there. This class needs to create, seed,
 * mutilate and drop databases, so it does that beside the shared one on the same server - the server,
 * its version and its credentials are the shared ones, so what is exercised is still the pinned
 * PostgreSQL 16 the module targets - and drops each one afterwards.
 *
 * <p>Provenance: this control has no legacy antecedent. It guards the schema that replaces the ten
 * {@code DEFINE CLUSTER} provisioning job streams in {@code app/jcl} and the in-stream sign-on
 * identities of {@code app/jcl/DUSRSECJ.jcl}, taken from checkout
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. No legacy source text appears here.
 */
@DisplayName("Production seeded-database refusal, driven through real migrations")
class ProductionSeedRejectionCallbackIT extends AbstractPostgresIT {

    /** The database each test creates, mutates and drops. */
    private static final String PROBE_DATABASE = "carddemo_seed_rejection_probe";

    /** Removes every history entry at or above the first seed version. A complete literal statement. */
    private static final String DELETE_SEED_HISTORY =
            "DELETE FROM flyway_schema_history WHERE version IN ('3', '4')";

    /** Removes every seeded sign-on identity. A complete literal statement. */
    private static final String DELETE_SIGN_ON_IDENTITIES = "DELETE FROM user_security";

    /** Counts the seeded customer rows, so a precondition can be asserted rather than assumed. */
    private static final String COUNT_CUSTOMERS = "SELECT count(*) FROM customer";

    /** Counts the seeded sign-on rows, for the same reason. */
    private static final String COUNT_SIGN_ON_IDENTITIES = "SELECT count(*) FROM user_security";

    /** Row count the reference seed puts in the customer table. */
    private static final long SEEDED_CUSTOMER_COUNT = 50L;

    /** Row count the sign-on seed puts in the user-security table. */
    private static final long SEEDED_SIGN_ON_COUNT = 10L;

    /** A field-encryption key of the right shape, so the collaborator bean can be built. */
    private static final String FIELD_ENCRYPTION_KEY =
            "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";

    /**
     * Drops the probe database, so each test starts from nothing and the shared server is left as it
     * was found.
     *
     * @throws SQLException if the drop fails for a reason other than absence
     */
    @AfterEach
    void dropProbeDatabase() throws SQLException {
        try (Connection connection = connect();
                Statement statement = connection.createStatement()) {
            // FORCE terminates any connection a migration left open, which PostgreSQL 16 supports and
            // which keeps the drop from failing on a session this test itself opened.
            statement.executeUpdate("DROP DATABASE IF EXISTS " + PROBE_DATABASE + " WITH (FORCE)");
        }
    }

    @Test
    @DisplayName("a database seeded under a seeding profile is refused when the same database is later "
            + "opened under the production profile, which is the reviewed defect itself")
    void aSeededDatabaseIsRefusedUnderProduction() throws SQLException {
        createProbeDatabase();
        seedingMigration().migrate();
        assertThat(scalar(COUNT_CUSTOMERS)).isEqualTo(SEEDED_CUSTOMER_COUNT);

        assertThatExceptionOfType(FlywayException.class)
                .as("the seeds were applied before this run existed, so no location list and no version "
                        + "location list this run resolves can undo them. Only an inspection of the database "
                        + "itself can see it, and it must refuse rather than report success")
                .isThrownBy(() -> productionMigration().migrate())
                .withMessageContaining(ProductionSeedRejectionCallback.HISTORY_TABLE)
                .withMessageContaining(ProductionSeedRejectionCallback.FIRST_SEED_VERSION);
    }

    @Test
    @DisplayName("a seeded database whose seed history entries were removed is still refused, because "
            + "the ten reserved sign-on identities are the rows a restored dump carries")
    void aSeededDatabaseWithoutItsSeedHistoryIsStillRefused() throws SQLException {
        createProbeDatabase();
        seedingMigration().migrate();
        execute(DELETE_SEED_HISTORY);
        assertThat(scalar(COUNT_SIGN_ON_IDENTITIES))
                .as("the precondition of this test is that the rows survive while the history does not")
                .isEqualTo(SEEDED_SIGN_ON_COUNT);

        assertThatExceptionOfType(FlywayException.class)
                .as("signal one is now blind - the history records versions 1 and 2 only, exactly as a "
                        + "correct production database would - so this refusal can only come from the "
                        + "rows themselves")
                .isThrownBy(() -> productionMigration().migrate())
                .withMessageContaining(ProductionSeedRejectionCallback.SIGN_ON_TABLE)
                .withMessageContaining("administrative");
    }

    @Test
    @DisplayName("a seeded database whose seed history AND sign-on rows were removed is still refused, "
            + "because the reference seed's personal data is still there")
    void aSeededDatabaseWithoutHistoryOrSignOnRowsIsStillRefused() throws SQLException {
        createProbeDatabase();
        seedingMigration().migrate();
        execute(DELETE_SEED_HISTORY);
        execute(DELETE_SIGN_ON_IDENTITIES);
        assertThat(scalar(COUNT_SIGN_ON_IDENTITIES)).isZero();
        assertThat(scalar(COUNT_CUSTOMERS))
                .as("the fifty synthetic customer rows carrying regulated identity data are what is "
                        + "left, and they are what signal three exists for")
                .isEqualTo(SEEDED_CUSTOMER_COUNT);

        assertThatExceptionOfType(FlywayException.class)
                .as("both earlier signals are blind now, so this refusal comes from the five row counts "
                        + "matching the reference seed simultaneously")
                .isThrownBy(() -> productionMigration().migrate())
                .withMessageContaining("row counts");
    }

    @Test
    @DisplayName("a database that was never seeded starts under production, and keeps starting on a "
            + "restart, so the control refuses a seeded database and nothing else")
    void aDatabaseThatWasNeverSeededStartsUnderProduction() throws SQLException {
        createProbeDatabase();

        assertThatNoException()
                .as("a first production migration must succeed: none of the tables the control reads "
                        + "exists yet, and an absent table is not a signal")
                .isThrownBy(() -> productionMigration().migrate());

        assertThat(scalar(COUNT_CUSTOMERS))
                .as("the schema is fully created and empty, which is the state the control must accept")
                .isZero();

        assertThatNoException()
                .as("and a restart must succeed too: the tables now exist and are empty, so the second "
                        + "and third signals are evaluated for real rather than skipped, and both must "
                        + "come back clean. This is the assertion that would catch a volume check made "
                        + "with an `or` where it needed an `and`")
                .isThrownBy(() -> productionMigration().migrate());
    }

    @Test
    @DisplayName("the refusal names no row key, no identifier value and no credential, so a database "
            + "suspected of holding seeded personal data is not copied into a log")
    void theRefusalNamesNoValueReadFromTheDatabase() throws SQLException {
        createProbeDatabase();
        seedingMigration().migrate();

        assertThatExceptionOfType(FlywayException.class)
                .isThrownBy(() -> productionMigration().migrate())
                .satisfies(refusal -> {
                    String message = refusal.getMessage();
                    for (final String identifier
                            : ProductionSeedRejectionCallback.SEEDED_SIGN_ON_IDENTIFIERS) {
                        assertThat(message)
                                .as("the message must not repeat %s: naming a seeded identity in a "
                                        + "diagnostic publishes the very value the refusal exists to "
                                        + "keep out of production - decision DL-041", identifier)
                                .doesNotContain(identifier);
                    }
                    assertThat(message)
                            .as("nor may it carry a control character, which would let a value shape a "
                                    + "log record")
                            .doesNotContain("\r")
                            .doesNotContain("\n");
                });
    }

    @Test
    @DisplayName("the migration tool's own validation does NOT reject a seeded database at all, with "
            + "or without a version ceiling, which is exactly why this control has to exist")
    void flywayValidationDoesNotRejectASeededDatabaseAtAll() throws SQLException {
        createProbeDatabase();
        seedingMigration().migrate();

        Flyway withoutTheCallback = Flyway.configure()
                .dataSource(probeJdbcUrl(), databaseUser(), databasePassword())
                .locations(FlywayConfig.SCHEMA_LOCATION)
                .load();

        assertThatNoException()
                .as("MEASURED, NOT ASSUMED, and it is the opposite of what a reader would expect. With "
                        + "the callback removed, versions 3 and 4 are applied and unresolvable from the "
                        + "location list this run reads - yet the migration REPORTS SUCCESS. Version 11 "
                        + "of the migration tool ignores FUTURE migrations by default: an applied "
                        + "version newer than anything the resolved locations carry is not a validation "
                        + "failure. So the configuration control cannot see the one state it cannot "
                        + "prevent, and nothing except an inspection of the DATABASE sees it - which is "
                        + "what makes this control indispensable rather than defence in depth")
                .isThrownBy(withoutTheCallback::migrate);

        assertThatNoException()
                .as("and imposing a version ceiling as well does not help either, which is the "
                        + "measurement that settles it. An applied version ABOVE the target is not a "
                        + "validation failure any more than a future one is, so neither posture makes "
                        + "the tool notice. Any claim that validate-on-migrate is a line of defence "
                        + "against an already-seeded database would be false, and this assertion is here "
                        + "so that no such claim can be written into the profile documents unchallenged")
                .isThrownBy(() -> Flyway.configure()
                        .dataSource(probeJdbcUrl(), databaseUser(), databasePassword())
                        .locations(FlywayConfig.SCHEMA_LOCATION)
                        .target(MigrationVersion.fromVersion("2"))
                        .load()
                        .migrate());
    }

    @Test
    @DisplayName("the refusal lands before any script is executed, so the database is not written to "
            + "before it is refused")
    void theRefusalLandsBeforeAnyScriptIsExecuted() throws SQLException {
        createProbeDatabase();

        Flyway refusing = Flyway.configure()
                .dataSource(probeJdbcUrl(), databaseUser(), databasePassword())
                .locations(FlywayConfig.SCHEMA_LOCATION)
                .target(FlywayConfig.ALL_RESOLVED_VERSIONS_TARGET)
                .table("carddemo_history")
                .callbacks(new ProductionSeedRejectionCallback())
                .load();

        assertThatExceptionOfType(FlywayException.class)
                .as("a renamed history table is refused, and the refusal must arrive before the first "
                        + "script runs - which is the only reason the assertion below can hold")
                .isThrownBy(refusing::migrate)
                .withMessageContaining(ProductionSeedRejectionCallback.HISTORY_TABLE);

        assertThat(tableCount())
                .as("no application table may exist: the control acts on a pre-execution event, so a "
                        + "refused start-up leaves the database exactly as it found it")
                .isZero();
    }

    @Test
    @DisplayName("a started context carrying the data source, the migration tool and the persistence "
            + "provider refuses to refresh against a seeded database - and cleans nothing")
    void aStartedContextRefusesToRefreshAndCleansNothing() throws SQLException {
        createProbeDatabase();

        productionContext().run(started -> assertThat(started)
                .as("THE CONTROL ASSERTION FOR EVERYTHING BELOW. This exact wiring must be capable of "
                        + "starting, or a failed refresh later would prove only that the wiring is "
                        + "broken. A never-seeded database migrates from the schema location and the "
                        + "context refreshes")
                .hasNotFailed());

        seedingMigration().migrate();
        assertThat(scalar(COUNT_CUSTOMERS))
                .as("the database is now in the state a seeding profile leaves behind, reached without "
                        + "the production deployment's knowledge")
                .isEqualTo(SEEDED_CUSTOMER_COUNT);

        productionContext().run(refused -> {
            assertThat(refused)
                    .as("the refusal must abort the refresh itself. A migration callback that logged and "
                            + "continued, or whose exception the framework swallowed, would leave a "
                            + "running deployment reading seeded credentials")
                    .hasFailed();
            assertThat(refused.getStartupFailure())
                    .as("and the refresh must fail for THIS reason and no other, which is what names the "
                            + "history table and the seed version boundary in the chain")
                    .hasStackTraceContaining(ProductionSeedRejectionCallback.HISTORY_TABLE)
                    .hasStackTraceContaining(ProductionSeedRejectionCallback.FIRST_SEED_VERSION);
        });

        assertThat(scalar(COUNT_CUSTOMERS))
                .as("NEVER SILENTLY CLEAN PRODUCTION DATA. The fifty seeded customer rows must still be "
                        + "there after the refusal: a control that deleted what it objected to would "
                        + "destroy evidence, and on a misidentified database would destroy real records")
                .isEqualTo(SEEDED_CUSTOMER_COUNT);
        assertThat(scalar(COUNT_SIGN_ON_IDENTITIES))
                .as("and so must the ten sign-on identities, for the same reason")
                .isEqualTo(SEEDED_SIGN_ON_COUNT);
    }

    @Test
    @DisplayName("the same inspection fires from an ordinary production singleton, in a context "
            + "carrying NO migration tool at all - which is the path a disabled migration would leave")
    void theInspectionFiresWithNoMigrationToolInTheContext() throws SQLException {
        createProbeDatabase();

        productionContextWithoutMigrations().run(started -> assertThat(started)
                .as("THE CONTROL ASSERTION. This wiring - a data source and a template, and no "
                        + "migration tool whatever - must be capable of starting against a "
                        + "never-migrated database, or the refusal below would prove only that the "
                        + "wiring is broken")
                .hasNotFailed());

        seedingMigration().migrate();
        assertThat(scalar(COUNT_CUSTOMERS))
                .as("the database now holds the seeded state, reached without this deployment's "
                        + "knowledge")
                .isEqualTo(SEEDED_CUSTOMER_COUNT);

        productionContextWithoutMigrations().run(refused -> {
            assertThat(refused)
                    .as("NO MIGRATION TOOL IS PRESENT, so no configuration customizer runs and no "
                            + "callback event is ever offered. Before the always-on singleton existed "
                            + "this context started cleanly over ten known administrative credentials")
                    .hasFailed();
            assertThat(refused.getStartupFailure())
                    .as("and it fails for THIS reason, naming the history table and the seed boundary")
                    .hasStackTraceContaining(ProductionSeedRejectionCallback.HISTORY_TABLE)
                    .hasStackTraceContaining(ProductionSeedRejectionCallback.FIRST_SEED_VERSION);
        });

        assertThat(scalar(COUNT_CUSTOMERS))
                .as("and the refusal deletes nothing, for the same reason the callback deletes nothing")
                .isEqualTo(SEEDED_CUSTOMER_COUNT);
    }

    @Test
    @DisplayName("a production start-up that switched migrations off is refused outright, so the "
            + "bypass cannot be taken in the first place")
    void aProductionStartUpThatSwitchedMigrationsOffIsRefused() throws SQLException {
        createProbeDatabase();

        new ApplicationContextRunner()
                .withUserConfiguration(FlywayConfig.class)
                .withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class,
                        FlywayAutoConfiguration.class))
                .withBean(SensitiveFieldEncryptionService.class,
                        () -> new SensitiveFieldEncryptionService(FIELD_ENCRYPTION_KEY))
                .withPropertyValues(
                        "spring.profiles.active=" + FlywayConfig.PRODUCTION_PROFILE,
                        "spring.datasource.url=" + probeJdbcUrl(),
                        "spring.datasource.username=" + databaseUser(),
                        "spring.datasource.password=" + databasePassword(),
                        "spring.flyway.enabled=false",
                        "spring.flyway.locations=" + FlywayConfig.SCHEMA_LOCATION,
                        "spring.flyway.target=" + FlywayConfig.ALL_RESOLVED_VERSIONS_TARGET)
                .run(refused -> assertThat(refused)
                        .as("switching migrations off is itself refused, before any bean is created, "
                                + "so a deployment cannot reach a state where the migration-lifecycle "
                                + "controls are absent")
                        .hasFailed()
                        .getFailure()
                        .hasMessageContaining("spring.flyway.enabled"));
    }

    // Helpers.

    /**
     * Builds a context runner carrying a data source and a template but NO migration tool, with
     * {@link FlywayConfig} registered and the production profile active.
     *
     * <p>The absence of {@code FlywayAutoConfiguration} is the whole point: it reproduces exactly the
     * state {@code spring.flyway.enabled=false} used to produce, in which no configuration customizer
     * is applied and no callback event is ever offered, so only a control that does not ride on the
     * migration lifecycle can fire. The production profile IS activated here - unlike in
     * {@link #productionContext()} - because the control under test is scoped to that profile, and with
     * only {@code FlywayConfig} registered the profile carries no other guard whose failure could mask
     * this one.
     *
     * @return a runner ready to {@code run}, pointed at the probe database
     */
    private static ApplicationContextRunner productionContextWithoutMigrations() {
        return new ApplicationContextRunner()
                .withUserConfiguration(FlywayConfig.class)
                .withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class,
                        JdbcTemplateAutoConfiguration.class))
                .withBean(SensitiveFieldEncryptionService.class,
                        () -> new SensitiveFieldEncryptionService(FIELD_ENCRYPTION_KEY))
                .withPropertyValues(
                        "spring.profiles.active=" + FlywayConfig.PRODUCTION_PROFILE,
                        "spring.datasource.url=" + probeJdbcUrl(),
                        "spring.datasource.username=" + databaseUser(),
                        "spring.datasource.password=" + databasePassword(),
                        "spring.flyway.enabled=true",
                        "spring.flyway.locations=" + FlywayConfig.SCHEMA_LOCATION,
                        "spring.flyway.target=" + FlywayConfig.ALL_RESOLVED_VERSIONS_TARGET);
    }

    /**
     * Builds a context runner carrying the three auto-configurations a production deployment initializes
     * around this database - the data source, the migration tool and the persistence provider - with the
     * refusal registered as a {@link Callback} bean exactly as {@link FlywayConfig} registers it under
     * the production profile, and with the production profile's own schema location and open target.
     *
     * <p>Registering it as a bean rather than handing it to {@code Flyway.configure()} is deliberate:
     * it exercises the wiring as well as the control, since a callback bean is only honoured because
     * Spring Boot's Flyway auto-configuration collects {@code Callback} beans and applies them. The
     * profile itself is NOT activated, because the production profile carries further guards of its own
     * whose failures would mask this one; that the registration is confined to production is asserted
     * separately, against the bean method's own annotations.
     *
     * @return a runner ready to {@code run}, pointed at the probe database
     */
    private static ApplicationContextRunner productionContext() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class,
                        FlywayAutoConfiguration.class, HibernateJpaAutoConfiguration.class))
                .withBean(Callback.class, ProductionSeedRejectionCallback::new)
                .withPropertyValues(
                        "spring.datasource.url=" + probeJdbcUrl(),
                        "spring.datasource.username=" + databaseUser(),
                        "spring.datasource.password=" + databasePassword(),
                        "spring.flyway.locations=" + FlywayConfig.SCHEMA_LOCATION,
                        "spring.flyway.target=" + FlywayConfig.ALL_RESOLVED_VERSIONS_TARGET,
                        "spring.flyway.validate-on-migrate=true",
                        "spring.flyway.clean-disabled=true",
                        "spring.jpa.hibernate.ddl-auto=none",
                        "spring.jpa.open-in-view=false");
    }

    /**
     * Creates the probe database on the shared server.
     *
     * @throws SQLException if the database cannot be created
     */
    private static void createProbeDatabase() throws SQLException {
        try (Connection connection = connect();
                Statement statement = connection.createStatement()) {
            statement.executeUpdate("DROP DATABASE IF EXISTS " + PROBE_DATABASE + " WITH (FORCE)");
            statement.executeUpdate("CREATE DATABASE " + PROBE_DATABASE);
        }
    }

    /**
     * Builds the migration a seeding profile performs: both delivered locations and no target.
     *
     * <p>No callback is registered, because the control under test is registered for the production
     * profile alone and registering it here would refuse the very state these tests need to create.
     *
     * @return a loaded migration, not yet run
     */
    private static Flyway seedingMigration() {
        return Flyway.configure()
                .dataSource(probeJdbcUrl(), databaseUser(), databasePassword())
                .locations(FlywayConfig.SCHEMA_LOCATION, FlywayConfig.SEED_LOCATION)
                .load();
    }

    /**
     * Builds the migration a production deployment performs: the schema location alone, which is what
     * holds the two seeds out of it, and the refusal registered exactly as {@link FlywayConfig}
     * registers it for production.
     *
     * <p>No {@code target} is set, and that is the delivered posture rather than an omission: the
     * exclusion is the absent seed location, so every version the schema location carries applies. See
     * docs/decision-log.md DL-298.
     *
     * @return a loaded migration, not yet run
     */
    private static Flyway productionMigration() {
        return Flyway.configure()
                .dataSource(probeJdbcUrl(), databaseUser(), databasePassword())
                .locations(FlywayConfig.SCHEMA_LOCATION)
                .callbacks(new ProductionSeedRejectionCallback())
                .load();
    }

    /**
     * Builds the JDBC URL of the probe database on the shared server.
     *
     * @return the URL, carrying the container's mapped port
     */
    private static String probeJdbcUrl() {
        final String shared = jdbcUrl();
        final int lastSeparator = shared.lastIndexOf('/');
        return shared.substring(0, lastSeparator + 1) + PROBE_DATABASE;
    }

    /**
     * Runs one complete literal statement against the probe database.
     *
     * @param sql a complete literal statement declared as a constant of this class
     * @throws SQLException when the statement fails
     */
    private static void execute(final String sql) throws SQLException {
        try (Connection connection = connectProbe();
                Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }

    /**
     * Reads a single numeric value from the probe database.
     *
     * @param sql a complete literal statement declared as a constant of this class
     * @return the projected value, or zero when no row came back
     * @throws SQLException when the read fails
     */
    private static long scalar(final String sql) throws SQLException {
        try (Connection connection = connectProbe();
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(sql)) {
            return rows.next() ? rows.getLong(1) : 0L;
        }
    }

    /**
     * Counts every table in the probe database's own schema, migration history included.
     *
     * @return the table count
     * @throws SQLException when the catalogue read fails
     */
    private static long tableCount() throws SQLException {
        return scalar("SELECT count(*) FROM information_schema.tables"
                + " WHERE table_schema = current_schema()");
    }

    /**
     * Opens a connection to the probe database.
     *
     * @return an open connection the caller must close
     * @throws SQLException if the connection cannot be established
     */
    private static Connection connectProbe() throws SQLException {
        return DriverManager.getConnection(probeJdbcUrl(), databaseUser(), databasePassword());
    }
}
