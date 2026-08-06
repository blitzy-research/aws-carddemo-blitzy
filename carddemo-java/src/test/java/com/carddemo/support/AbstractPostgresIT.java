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
package com.carddemo.support;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import org.flywaydb.core.Flyway;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Shared base for every integration test that needs the migrated relational schema on a real
 * PostgreSQL server.
 *
 * <h2>What this provides</h2>
 * One PostgreSQL 16 server, migrated by Flyway to the head of both delivered migration locations - so
 * the schema,
 * the indexes and both seed migrations are applied - reachable through {@link #connect()} and through
 * the three connection accessors. Alongside it, the determinism and inspection members every
 * container-backed test needs: a pinned {@link #FIXED_CLOCK}, pinned job parameters, the
 * {@link #APPLICATION_TABLES} roster with a live {@link #applicationTableNames()} counterpart,
 * {@link #appliedMigrationVersions()} for asserting what the migration tool actually did, and the
 * opt-in reset pair {@link #truncateApplicationTables()} and {@link #restoreSeededState()}.
 *
 * <p>This class declares no test and <strong>no lifecycle callback</strong>. That is deliberate and is
 * explained under the reset contract below: a subclass owns when its data is cleared, because most
 * subclasses here assert against the seeded rows and an automatic reset would delete the very rows
 * under test.</p>
 *
 * <h2>THE SUBCLASS CONTRACT - what a subclass MUST NOT declare</h2>
 * This type is the single owner of the database container for the whole module, so a subclass
 * <strong>must not</strong> declare any of the following. Each one either competes with what is
 * centralised here or silently breaks sharing for every other subclass:
 * <ul>
 *   <li>{@code @Testcontainers} - the extension's per-class {@code afterAll} would stop the shared
 *       server after the first subclass finished, leaving every later subclass on a dead server.</li>
 *   <li>a {@code @Container} field - same per-class lifecycle, same consequence.</li>
 *   <li>a {@code PostgreSQLContainer} of its own - a second server per class, which multiplies
 *       start-up cost and lets two tests disagree about the same schema.</li>
 *   <li>its own {@code @DynamicPropertySource} for the data source - the three keys published by
 *       {@link #registerDataSourceProperties(DynamicPropertyRegistry)} are the contract, and a
 *       competing source makes which address wins depend on declaration order.</li>
 *   <li>{@code @DirtiesContext} - it discards the Spring context and defeats the reuse that keeps the
 *       integration phase fast. Use the reset pair below instead; supplying a cheaper deterministic
 *       alternative is precisely why those two methods exist.</li>
 * </ul>
 * A subclass declares only what is specific to itself: its own {@code @SpringBootTest} (so it may
 * choose its own web environment), its own fixtures, and its own assertions.
 *
 * <h2>How a subclass that boots a Spring context reaches the same server</h2>
 * {@link #registerDataSourceProperties(DynamicPropertyRegistry)} publishes the started container's
 * real address, role and password as {@code spring.datasource.*} properties. Neither copy of
 * {@code application-test.yml} declares those three keys, and that absence is deliberate: an
 * ephemeral container port is unknowable ahead of the run, and a reachable default written into a
 * file would let a test that forgot to extend this class connect to the developer's own database and
 * pass silently. With the keys absent, a context that is not given a container fails during refresh
 * and names the missing property; a context whose test class extends this type is given the
 * container instead, because a dynamic property source outranks every property file.
 *
 * <p>Almost every integration test in the module works directly against the container through
 * {@link #connect()} rather than through a context, because that is the cheapest way to assert a
 * record layout or a migration. This registration is what makes a context-booting test correct by
 * construction instead of by remembering to wire it, and it is verified from both ends:
 * {@code PostgresPropertyRegistrationIT} calls it with a recording registry and asserts that each key
 * resolves to the running container's own value, and {@code ContextInheritsContainerAddressesIT} boots
 * a real context and asserts that the address it observes is the container's - which is only possible
 * if the framework discovered this method at all. See {@code docs/decision-log.md} DL-104.</p>
 *
 * <h2>Why the container is started in a static initialiser rather than declared with
 * {@code @Container}</h2>
 * The JUnit integration that {@code @Container} depends on is driven by {@code @Testcontainers},
 * and that extension runs its {@code beforeAll} and {@code afterAll} callbacks once per test
 * <em>class</em>. A static {@code @Container} field inherited by several test classes is therefore
 * started before the first subclass and <em>stopped after it</em>, leaving every later subclass
 * pointing at a dead server. Starting the container here, once, in a static initialiser sidesteps
 * that lifecycle entirely: the server lives for the JVM, every subclass shares it, and the
 * Testcontainers resource reaper removes it when the JVM exits. This class deliberately carries
 * neither {@code @Testcontainers} nor {@code @Container} so that no subclass can reintroduce the
 * per-class teardown by inheriting the annotation.
 *
 * <h2>Why the migration runs here too</h2>
 * The schema is a property of the server, not of a test class, so migrating it beside the start-up
 * keeps the two facts in one place and removes the identical {@code @BeforeAll} that each
 * integration test previously repeated. Flyway is idempotent, but running it once rather than once
 * per class also removes a source of ordering surprise.
 *
 * <h2>Why the migration runs to the head and is not pinned</h2>
 * All four delivered migrations sit FLAT in one location, {@code db/migration}: {@code V1} and
 * {@code V2} create the schema and the indexes, and {@code V3} and {@code V4} seed sample reference
 * rows and ten sign-on identities. No directory separates them, so a location list cannot either -
 * the VERSION is what separates them, and production sets {@code spring.flyway.target: 2} so the two
 * scripts numbered above it are never applied. <strong>This base reproduces the
 * TEST profile rather than the production one</strong>, because that is the posture the module actually
 * ships for tests: {@code src/test/resources/application-test.yml} declares that same one location and
 * lifts the ceiling to the head, and
 * the container-backed tier asserts against the seeded rows themselves - the fifty seeded customers and
 * their protected identifiers, the fifty seeded accounts, and the seventeen rows of each disclosure
 * group are read directly from this server by several subclasses. Pinning the shared server to the
 * production ceiling would leave those assertions reading empty result sets and failing for a reason
 * unrelated to what they test.
 *
 * <p>The production-shaped posture is nonetheless proven, and proven better than a pin here could prove
 * it: {@code SeedMigrationIT} migrates into a schema of its own, twice from this same location - once
 * under the production ceiling and once under the head - and asserts what each run applied and what it
 * left unapplied. That keeps the claim about
 * production in a test that is about production, and keeps this shared server predictable for every
 * subclass. A subclass that must observe an empty table therefore reserves a key range of its own and
 * asserts emptiness within that range - which is what every subclass here already does - rather than
 * relying on the whole table being untouched.
 *
 * <h2>Why the image tag is pinned</h2>
 * The migrated schema is validated against the server version the module targets, so a floating tag
 * would let the assertion surface move underneath the tests. The tag matches the service declared in
 * {@code carddemo-java/docker-compose.yml}, so a local run and a continuous-integration run exercise
 * the same server.
 *
 * <h2>The reset contract, and why nothing is reset automatically</h2>
 * A subclass is forbidden {@code @DirtiesContext}, so this class owes it a cheaper deterministic
 * alternative. Two are offered, both <strong>opt-in</strong>:
 * {@link #truncateApplicationTables()} empties the eleven application tables, and
 * {@link #restoreSeededState()} empties them and then puts the delivered seed rows back.
 *
 * <p>Neither runs from a lifecycle callback, and that is a correctness requirement rather than a
 * preference. Most subclasses here assert <em>against</em> the seeded rows - the fifty seeded
 * accounts, the fifty seeded customers and their protected identifiers, the seventeen rows of each
 * disclosure group, the ten sign-on identities. A {@code @BeforeEach} that truncated would delete the
 * rows under test and every one of those assertions would fail for a reason unrelated to what it
 * checks. The convention the estate already follows is to reserve a key range per subclass and assert
 * within it; a subclass that genuinely needs a pristine database calls one of these two methods
 * explicitly.</p>
 *
 * <p>Prefer transactional rollback where it suffices: a {@code @Transactional} test method on a
 * context-booting subclass rolls its own writes back and needs neither method. It is <em>not</em>
 * sufficient for a batch job, which commits per chunk on its own connections - such a test wants
 * {@link #restoreSeededState()}.</p>
 *
 * <h2>Isolation compared with the legacy baseline</h2>
 * Worth stating so that stronger isolation is not misread as a behavioural regression: every
 * application file definition in the legacy resource definition is declared with uncommitted read
 * integrity, no recovery and no journalling, leaving correctness to a locking update model plus each
 * program's own before-and-after image comparison. This server runs PostgreSQL's default READ
 * COMMITTED, and the migrated schema carries a version column on the account and card tables for
 * optimistic locking. That is a <strong>strict improvement</strong> over the baseline, not a change in
 * behaviour. The full record belongs in {@code docs/decision-log.md}.
 *
 * <p>Provenance: this support type has no legacy antecedent - the legacy estate carries no test
 * harness of any kind. It exists to serve tests of the migrated schema derived from the record
 * layouts in {@code app/cpy}, taken from checkout {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec},
 * upstream release stamp {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. That stamp is a
 * provenance string for the traceability matrix header only: it is not carried by every legacy
 * member, so nothing here asserts it against one.</p>
 */
@ActiveProfiles("test")
public abstract class AbstractPostgresIT {

    /**
     * The pinned server image. Held as a constant so a subclass can assert against it rather than
     * restating the tag.
     */
    protected static final String POSTGRES_IMAGE = "postgres:16.14-bookworm";

    /** The database name the module's own configuration uses. */
    protected static final String DATABASE_NAME = "carddemo";

    /** The database role the module's own configuration uses. */
    protected static final String DATABASE_USER = "carddemo";

    /**
     * The throwaway password for the containerised server. It is not a credential of the migrated
     * application: no user of the system authenticates with it, and it never leaves the test JVM.
     */
    protected static final String DATABASE_PASSWORD = "carddemo";

    /**
     * The one location every shipped profile declares, holding all four delivered migrations flat.
     *
     * <p>This base exists to reproduce a shipped profile rather than to invent a third arrangement, so
     * it declares exactly what the test profile declares: this location, and no other. A
     * production-shaped run declares the same one and differs only in its version ceiling, which is
     * why {@code SeedMigrationIT} proves that posture by changing the ceiling rather than the list.</p>
     */
    protected static final String MIGRATION_LOCATION = "classpath:db/migration";

    /**
     * The highest migration version that belongs to the schema rather than to the seeds.
     *
     * <p>This is the same boundary the production profile enforces as a Flyway target, which is what
     * makes it the right boundary for {@link #restoreSeededState()} to re-run from: everything above it
     * is seed data, everything at or below it is structure that a reseed must leave alone.</p>
     */
    private static final int SCHEMA_CEILING_VERSION = 2;

    /**
     * The instant every date-sensitive assertion is anchored to, and the reason it is this instant.
     *
     * <p>All three hundred records of the delivered daily-transaction fixture carry one and the same
     * original timestamp - measured across the whole file, exactly one distinct value. Anchoring here
     * means a service that reads the clock agrees with the seeded data instead of drifting away from it
     * one day at a time.</p>
     *
     * <p>The fixture field carries no zone, and the build pins the test JVM to UTC, so UTC is the
     * reading that keeps a run reproducible on any host.</p>
     */
    protected static final Instant PINNED_INSTANT = Instant.parse("2022-06-10T19:27:53Z");

    /**
     * A clock frozen at {@link #PINNED_INSTANT}, for injection into anything that reads the time.
     *
     * <p>Nothing in this class or in a subclass should call a system clock. A fixed clock is what makes
     * an assertion about an age, a window or a derived date mean the same thing on every run and on
     * every host.</p>
     */
    protected static final Clock FIXED_CLOCK = Clock.fixed(PINNED_INSTANT, ZoneOffset.UTC);

    /**
     * The business date of {@link #PINNED_INSTANT}, derived rather than restated so the two can never
     * disagree. Its {@code toString()} is the ten-character ISO form the job parameter contract uses.
     */
    protected static final LocalDate PINNED_BUSINESS_DATE =
            LocalDate.ofInstant(PINNED_INSTANT, ZoneOffset.UTC);

    /**
     * Inclusive lower bound of the pinned reporting window: the first day of the fixture's month.
     *
     * <p>The window deliberately contains {@link #PINNED_BUSINESS_DATE} strictly inside itself, so a
     * subclass can assert both that an in-range record is selected and that each bound is treated as
     * inclusive, which is the behaviour the legacy report filter had.</p>
     */
    protected static final LocalDate PINNED_WINDOW_START_DATE = PINNED_BUSINESS_DATE.withDayOfMonth(1);

    /** Inclusive upper bound of the pinned reporting window: the last day of the fixture's month. */
    protected static final LocalDate PINNED_WINDOW_END_DATE =
            PINNED_BUSINESS_DATE.withDayOfMonth(PINNED_BUSINESS_DATE.lengthOfMonth());

    /**
     * The eleven application tables the schema migration creates, in record-layout order.
     *
     * <p>Their record images are 300, 150, 500, 50 with 36 data bytes, 350, 350, 50, 50, 60, 60 and 80
     * bytes respectively, in this order. The list is what an assertion about the shape of the schema
     * should be written against; {@link #applicationTableNames()} is the live counterpart read back
     * from the running server.</p>
     *
     * <p>The job-repository tables and the migration history table are deliberately absent - see
     * {@link #applicationTableNames()} for why counting them would be wrong.</p>
     */
    protected static final List<String> APPLICATION_TABLES = List.of(
            "account",
            "card",
            "customer",
            "card_cross_reference",
            "transaction",
            "daily_transaction",
            "transaction_category_balance",
            "disclosure_group",
            "transaction_type",
            "transaction_category",
            "user_security");

    /**
     * The one server every subclass shares, started and migrated before any subclass is constructed.
     */
    private static final PostgreSQLContainer<?> POSTGRES = startMigratedServer();

    /**
     * Restricts construction to subclasses. A test class extends this type; nothing instantiates it
     * directly.
     */
    protected AbstractPostgresIT() {
        // Intentionally empty: this base holds no per-instance state.
    }

    /**
     * Starts the server and brings it to the head of the migration set.
     *
     * <p>Both delivered locations are declared and no ceiling is set, so all four delivered
     * migrations are applied in version order and the seeded reference rows and sign-on identities are
     * present. That matches the test profile the module ships and is what the container-backed
     * assertions read.
     *
     * <p>The {@code BATCH_}-prefixed job-repository tables are deliberately NOT created here. They
     * belong to Spring Batch, which provisions them from its own bundled script when a context starts
     * with {@code spring.batch.jdbc.initialize-schema: always} - the value every shipped profile
     * carries. A subclass that boots a context therefore finds them; a subclass that only reads the
     * migrated schema does not need them, and creating them here would put a second, non-shipped
     * provisioning path into the test estate.
     *
     * @return the started, migrated container
     */
    private static PostgreSQLContainer<?> startMigratedServer() {
        final PostgreSQLContainer<?> container =
                new PostgreSQLContainer<>(POSTGRES_IMAGE)
                        .withDatabaseName(DATABASE_NAME)
                        .withUsername(DATABASE_USER)
                        .withPassword(DATABASE_PASSWORD);
        container.start();
        Flyway.configure()
                .dataSource(container.getJdbcUrl(), container.getUsername(), container.getPassword())
                .locations(MIGRATION_LOCATION)
                .load()
                .migrate();
        return container;
    }

    /**
     * Publishes the running server's address into the environment of any subclass that boots a
     * Spring context.
     *
     * <p>The three keys registered here are exactly the three that neither copy of
     * {@code application-test.yml} declares, so this is the only place a context can obtain them and
     * there is no file-declared value to be silently preferred. The driver class is registered too,
     * for one narrow reason: it removes any dependence on ordering between this source and the
     * property file that pins the same driver, so a context assembled from this source alone is
     * still complete.</p>
     *
     * <p>Values are supplied lazily. The container is already started by the time this class is
     * loaded, so laziness is not required for correctness here, but it keeps the registration honest
     * about what it publishes - the address as the container reports it at the moment the context
     * asks, never a copy taken earlier.</p>
     *
     * @param registry the registry the Spring TestContext Framework supplies; must not be null
     */
    @DynamicPropertySource
    protected static void registerDataSourceProperties(final DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", AbstractPostgresIT::jdbcUrl);
        registry.add("spring.datasource.username", AbstractPostgresIT::databaseUser);
        registry.add("spring.datasource.password", AbstractPostgresIT::databasePassword);
        registry.add("spring.datasource.driver-class-name", AbstractPostgresIT::driverClassName);
    }

    /**
     * Opens a connection to the migrated database.
     *
     * @return an open connection the caller must close
     * @throws SQLException if the connection cannot be established
     */
    protected static Connection connect() throws SQLException {
        return DriverManager.getConnection(jdbcUrl(), databaseUser(), databasePassword());
    }

    /**
     * Returns the JDBC URL of the shared server, including the mapped port.
     *
     * @return the JDBC URL
     */
    protected static String jdbcUrl() {
        return POSTGRES.getJdbcUrl();
    }

    /**
     * Returns the role the shared server accepts.
     *
     * @return the database user name
     */
    protected static String databaseUser() {
        return POSTGRES.getUsername();
    }

    /**
     * Returns the password the shared server accepts.
     *
     * @return the database password
     */
    protected static String databasePassword() {
        return POSTGRES.getPassword();
    }

    /**
     * Returns the JDBC driver class the shared server is reached through.
     *
     * @return the fully qualified driver class name
     */
    protected static String driverClassName() {
        return POSTGRES.getDriverClassName();
    }

    /**
     * Builds job parameters carrying the pinned business date under the parameter name the caller's job
     * declares.
     *
     * <p>The name is an argument rather than a constant here on purpose. Each job configuration owns the
     * name of its own parameter, and a subclass already imports that constant to launch its job; taking
     * it as an argument means this helper supplies only the part it is entitled to supply - the pinned
     * value - and cannot drift away from a name it does not own.</p>
     *
     * <p>The value is the ten-character ISO form of {@link #PINNED_BUSINESS_DATE}, matching the width
     * and shape of the legacy date card. It is produced by {@code LocalDate.toString()}, which is
     * locale-independent, so no formatter and no locale needs to be chosen.</p>
     *
     * @param parameterKey the job's own date parameter name; must not be null or blank
     * @return job parameters holding exactly that one pinned date
     * @throws IllegalArgumentException if {@code parameterKey} is null or blank
     */
    protected static JobParameters pinnedParmDateParameters(final String parameterKey) {
        return new JobParametersBuilder()
                .addString(requireKey(parameterKey, "parameterKey"), PINNED_BUSINESS_DATE.toString())
                .toJobParameters();
    }

    /**
     * Builds job parameters carrying the pinned inclusive reporting window under the two parameter names
     * the caller's job declares.
     *
     * <p>Both values are ten-character ISO dates, and the window is inclusive at both bounds, which is
     * the behaviour of the legacy report filter.</p>
     *
     * <p><strong>Measured caveat - the seeded fixture cannot exercise a PROCESSING-date window.</strong>
     * Across all three hundred records of the delivered daily-transaction fixture, the twenty-six
     * character processing-timestamp field is entirely blank; only the original timestamp carries a
     * value. A window applied to the processing date therefore selects nothing from seeded data, and a
     * test written that way would appear to pass while filtering nothing. A subclass that needs to prove
     * processing-date filtering must build a record for it - that is what the test data factory is for -
     * and must not "fix" the problem by editing the seed.</p>
     *
     * @param startDateKey the job's own window-start parameter name; must not be null or blank
     * @param endDateKey the job's own window-end parameter name; must not be null or blank
     * @return job parameters holding the two pinned bounds
     * @throws IllegalArgumentException if either name is null or blank
     */
    protected static JobParameters pinnedDateWindowParameters(final String startDateKey,
            final String endDateKey) {
        return new JobParametersBuilder()
                .addString(requireKey(startDateKey, "startDateKey"),
                        PINNED_WINDOW_START_DATE.toString())
                .addString(requireKey(endDateKey, "endDateKey"), PINNED_WINDOW_END_DATE.toString())
                .toJobParameters();
    }

    /**
     * Reads back the application tables that actually exist on the shared server, in name order.
     *
     * <p>Two families of table are excluded, and excluding them is the whole point of this method.
     * Spring Batch provisions its own job-repository tables from its bundled script because every
     * shipped profile asks it to, and the migration tool keeps a history table of its own. Both are
     * real and expected, and neither belongs to the eleven-table business inventory - so a count that
     * included them would fail for a reason that has nothing to do with the record schema. Nothing
     * else is excluded: the migrations create exactly the eleven business tables and no operational
     * table of any kind, so an unrecognised name here is a genuine schema regression.</p>
     *
     * <p>The exclusion is written against lower-case names because the server folds unquoted
     * identifiers, so the job-repository tables land lower-cased however they were declared.</p>
     *
     * <p>Compare against {@link #APPLICATION_TABLES} without regard to order: this result is sorted by
     * name, while the roster is in record-layout order.</p>
     *
     * @return the names of the application tables present on the shared server
     * @throws SQLException if the catalogue cannot be read
     */
    protected static List<String> applicationTableNames() throws SQLException {
        return queryOneColumn("""
                SELECT table_name FROM information_schema.tables
                 WHERE table_schema = 'public'
                   AND table_name NOT LIKE 'batch\\_%'
                   AND table_name <> 'flyway_schema_history'
                 ORDER BY table_name
                """);
    }

    /**
     * Reads back the versions the migration tool has successfully applied, in the order it applied them.
     *
     * <p>Only successful rows are returned, so a migration that failed cannot masquerade as applied: it
     * simply does not appear, and an assertion naming the expected versions fails. Repeatable
     * migrations, which carry no version, are excluded.</p>
     *
     * @return the applied version strings in application order
     * @throws SQLException if the history table cannot be read
     */
    protected static List<String> appliedMigrationVersions() throws SQLException {
        return queryOneColumn("""
                SELECT version FROM flyway_schema_history
                 WHERE success = TRUE
                   AND version IS NOT NULL
                 ORDER BY installed_rank
                """);
    }

    /**
     * Empties the eleven application tables, leaving the schema, the migration history and the
     * job-repository tables untouched.
     *
     * <p>Opt-in: nothing calls this for a subclass. See the reset contract on this class for why no
     * lifecycle callback does.</p>
     *
     * <p>All eleven tables are named in a single statement, which makes foreign-key ordering irrelevant
     * - the server empties them together, so none of the six foreign keys between them can be violated
     * part-way through. No identity restart is needed because the schema has no sequence: every key in
     * this schema is the business key taken from the record image.</p>
     *
     * <p>{@code CASCADE} is deliberately <em>not</em> used. Every table that references one of these
     * eleven is itself one of the eleven, so it is unnecessary today; and were a future table to
     * reference one of them without being added to {@link #APPLICATION_TABLES}, its absence should
     * surface as a loud failure here rather than as a silent extra truncation. That is the same
     * fail-loudly stance the delivered seed scripts take.</p>
     *
     * @throws SQLException if the tables cannot be emptied
     */
    protected static void truncateApplicationTables() throws SQLException {
        // Identifiers come from the private roster above; no value and no caller input is interpolated.
        final String truncate = "TRUNCATE TABLE " + String.join(", ", APPLICATION_TABLES);
        try (Connection connection = connect();
                Statement statement = connection.createStatement()) {
            statement.execute(truncate);
        }
    }

    /**
     * Returns the shared server to exactly the state a fresh migration leaves it in: the eleven
     * application tables emptied and the delivered seed rows applied again.
     *
     * <p>Opt-in, and the reset a batch test wants. A transactional test method rolls its own writes back
     * and needs nothing; a batch job commits per chunk on its own connections, so rollback cannot reach
     * it and this method is the way back to a known state.</p>
     *
     * <p>The order matters and is not interchangeable. The seed scripts carry no conflict clause by
     * design - applying one to a table that already holds rows is meant to fail loudly rather than merge
     * silently - so the tables must be emptied <em>before</em> the seeds are re-applied. The history of
     * the seed versions is then removed so the migration tool sees them as pending; only rows above the
     * schema ceiling are removed, so the schema versions keep their history and their checksums are
     * still validated. The history table itself is never truncated.</p>
     *
     * <p>Afterwards {@link #appliedMigrationVersions()} again reports every delivered version as
     * successfully applied.</p>
     *
     * @throws SQLException if the tables cannot be emptied or the history cannot be amended
     */
    protected static void restoreSeededState() throws SQLException {
        truncateApplicationTables();
        forgetSeedMigrationHistory();
        Flyway.configure()
                .dataSource(jdbcUrl(), databaseUser(), databasePassword())
                .locations(MIGRATION_LOCATION)
                .load()
                .migrate();
    }

    /**
     * Removes the history rows of the seed migrations so they are pending again.
     *
     * <p>Bound as a parameter against the numeric value of the version column, so the boundary is stated
     * once as {@link #SCHEMA_CEILING_VERSION} and no version literal is assembled into the statement.
     * Every delivered version is a whole number, so the numeric reading is well defined.</p>
     *
     * @throws SQLException if the history cannot be amended
     */
    private static void forgetSeedMigrationHistory() throws SQLException {
        try (Connection connection = connect();
                PreparedStatement delete = connection.prepareStatement(
                        "DELETE FROM flyway_schema_history WHERE CAST(version AS numeric) > ?")) {
            delete.setInt(1, SCHEMA_CEILING_VERSION);
            delete.executeUpdate();
        }
    }

    /**
     * Runs a fixed single-column query and collects the column.
     *
     * <p>Every caller passes a complete literal statement, so no value is ever concatenated into SQL.</p>
     *
     * @param sql the literal query
     * @return the values of the projected column, in the order the query returned them
     * @throws SQLException if the query cannot be run
     */
    private static List<String> queryOneColumn(final String sql) throws SQLException {
        final List<String> values = new ArrayList<>();
        try (Connection connection = connect();
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) {
                values.add(rows.getString(1));
            }
        }
        return List.copyOf(values);
    }

    /**
     * Validates a caller-supplied job parameter name, naming the argument in the diagnostic so a failure
     * says which one was missing rather than only that something was.
     *
     * @param key the value to check
     * @param argumentName the name of the argument being checked, for the message
     * @return the validated key
     * @throws IllegalArgumentException if the key is null or blank
     */
    private static String requireKey(final String key, final String argumentName) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException(
                    "A job parameter name is required: " + argumentName + " was "
                            + (key == null ? "null" : "blank")
                            + ". Pass the parameter name declared by the job configuration under test.");
        }
        return key;
    }
}
