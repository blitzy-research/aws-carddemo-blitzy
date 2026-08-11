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

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import org.flywaydb.core.Flyway;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

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
 * The five delivered migrations ship from two sibling locations: {@code db/migration/schema} carries
 * {@code V1} and {@code V2}, which create the schema and the indexes, and {@code V2_2},
 * which adds the protected-value invariants; {@code db/migration/seed}
 * carries {@code V3} and {@code V4}, which seed sample reference rows and ten sign-on identities. Two
 * controls separate them - production resolves the schema location alone AND pins
 * {@code spring.flyway.target: 2.2}, so the two seed scripts are neither resolved nor reachable there.
 * <strong>This base reproduces the
 * TEST profile rather than the production one</strong>, because that is the posture the module actually
 * ships for tests: {@code src/test/resources/application-test.yml} declares both locations and
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
     * The pinned server image, named by tag <em>and</em> by content digest.
     *
     * <p><strong>Why the digest is part of the reference.</strong> A tag is a mutable pointer. The
     * container stack definition and the continuous-integration workflow both pin this image by digest
     * already, and this reference did not - so the tests could silently run against a different server from
     * the one the stack and the pipeline were verified on, after a rebuild of the tag upstream. That
     * divergence is invisible: the tests would pass or fail against an image nobody chose, and a
     * reproduction on a developer machine would not reproduce. Naming the same digest here is what makes
     * "PostgreSQL 16.14" one server across the stack, the pipeline and the suite.
     *
     * <p>The tag is kept alongside the digest rather than replaced by it, because a reader needs to know
     * which release the digest denotes, and because the two together fail loudly if they ever disagree.
     *
     * <p>Held as a constant so a subclass can assert against it rather than restating the reference.
     */
    protected static final String POSTGRES_IMAGE = "postgres:16.14-bookworm@sha256:"
            + "92620daddcd947f8d5ab5ba66e848702fe443d87fed30c4cea8e389fd78dfc55";

    /** The repository the pinned reference denotes, named for the container library's compatibility check. */
    private static final String POSTGRES_REPOSITORY = "postgres";

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
     * The schema location every shipped profile declares, holding the two schema migrations.
     *
     * <p>This base exists to reproduce a shipped profile rather than to invent a third arrangement, so
     * it declares exactly what the test profile declares: this location together with
     * {@link #SEED_MIGRATION_LOCATION}, and no other. A production-shaped run declares THIS ONE ALONE
     * and differs in nothing else, which is why {@code SeedMigrationIT} proves that posture by removing
     * the seed location from the list rather than by imposing a version ceiling.
     *
     * <p>Their shared parent {@code classpath:db/migration} is deliberately NOT used, even though it
     * would resolve the same five scripts: Flyway records a script under a name relative to its
     * location, so migrating from the parent would write {@code schema/V1__create_schema.sql} into the
     * history where every shipped profile writes {@code V1__create_schema.sql} - and a context booted
     * by a subclass, which migrates from the two children, would then validate against a history that
     * names its scripts differently. See docs/decision-log.md DL-298.</p>
     */
    protected static final String MIGRATION_LOCATION = "classpath:db/migration/schema";

    /**
     * The seed location the two non-production profiles add, holding the two seed migrations.
     *
     * <p>Declared here because the fixtures every subclass asserts against are the rows these two
     * scripts load. A production-shaped run omits it, which is the whole of the production exclusion.
     */
    protected static final String SEED_MIGRATION_LOCATION = "classpath:db/migration/seed";

    /**
     * The seed scripts a reset re-applies, in the order their versions deliver them.
     *
     * <p>Classpath resources rather than versions, because {@link #restoreSeededState()} re-applies the
     * seed <em>rows</em> and has no business re-applying the seed <em>versions</em>. The distinction is
     * the whole reason this roster is script paths: see that method for what depended on it.
     */
    private static final List<String> SEED_SCRIPTS = List.of(
            "db/migration/seed/V3__seed_reference_data.sql",
            "db/migration/seed/V4__seed_user_security.sql");

    /**
     * Concurrent client ceiling the one shared server is started with.
     *
     * <p>A capacity setting for the integration suite, not a tuning figure for the application: nothing
     * the module ships reads it, and no assertion anywhere depends on its value. It is raised above the
     * server image's own default of one hundred because of how this base is shared. Every integration
     * class reaches the same server, and each class that boots a context over its own explicit slice -
     * which the repository suites deliberately do, so that a scan cannot sweep the test tree into the
     * context - is a distinct context that the Spring TestContext Framework caches for the whole JVM,
     * holding its connection pool open long after its own class has finished. The clients therefore
     * accumulate across classes rather than being released between them, and a suite that has simply
     * grown a class starts failing on {@code sorry, too many clients already} in whichever class happens
     * to run once the ceiling is crossed - a failure that names capacity rather than the contract under
     * test, and that appears in classes whose own assertions are sound.</p>
     *
     * <p>Raising the ceiling on the server is the narrowest answer available. Bounding each pool instead
     * would put a connection-pool figure into a shipped profile, which the plan excludes outright, and
     * would risk starving the assertions that deliberately hold two sessions at once to prove that a
     * row-level hold is exclusive.</p>
     */
    private static final int MAX_CONCURRENT_CLIENTS = 400;

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
     * {@link #applicationTableNames()} for why counting either here would be wrong. Nothing else is
     * absent, because the delivered schema creates no other table: {@link #OPERATIONAL_TABLES} is
     * empty and {@link #operationalTableNames()} holds the server to that.</p>
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
     * The operational tables the schema migration creates, which are not record layouts.
     *
     * <p><strong>Empty, and deliberately kept rather than deleted.</strong> One such table existed - a
     * sign-on attempt ledger holding a per-subject failure count for a deployment-wide sign-on throttle.
     * The throttle was removed as feature expansion, because the legacy transaction it translates has no
     * attempt counter, and its table went with it ({@code docs/decision-log.md} DL-352). The delivered
     * schema therefore creates the eleven record-layout tables and nothing else.
     *
     * <p>The roster stays because an empty roster is an assertion: {@link #operationalTableNames()} reads
     * back every delivered table that is not one of the eleven and not a framework's, and comparing that
     * against this list fails the moment a table with no record layout reappears. Deleting the concept
     * would have left that reappearance to be noticed by a reader.
     */
    protected static final List<String> OPERATIONAL_TABLES = List.of();

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
     * <p>Both delivered locations are declared and no ceiling is set, so all five delivered
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
        // The compatibility declaration is required by the digest, not by the image: the container library
        // recognises a bare `postgres:<tag>` reference on its own but treats `postgres:<tag>@sha256:<digest>`
        // as an unknown substitute, because it compares the whole reference against the name it was written
        // for. Naming `postgres` here says what the digest denotes. It widens nothing - the digest is the
        // narrower statement of the two, and it is the one this suite runs against.
        final PostgreSQLContainer<?> container =
                new PostgreSQLContainer<>(DockerImageName.parse(POSTGRES_IMAGE)
                        .asCompatibleSubstituteFor(POSTGRES_REPOSITORY))
                        .withDatabaseName(DATABASE_NAME)
                        .withUsername(DATABASE_USER)
                        .withPassword(DATABASE_PASSWORD)
                        .withCommand("postgres", "-c",
                                "max_connections=" + MAX_CONCURRENT_CLIENTS);
        container.start();
        Flyway.configure()
                .dataSource(container.getJdbcUrl(), container.getUsername(), container.getPassword())
                .locations(MIGRATION_LOCATION, SEED_MIGRATION_LOCATION)
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
     * <p>Two exclusions, and making them is the whole point of this method. Spring Batch provisions
     * its own job-repository tables from its bundled script because every shipped profile asks it to,
     * and the migration tool keeps a history table of its own; both are real and expected and neither
     * belongs to the eleven-table business inventory. Counting either here would fail an assertion for a
     * reason that has nothing to do with the record schema.
     *
     * <p><strong>Nothing this module itself delivers is excluded, and that is stronger than it was.</strong>
     * A third exclusion once named the {@code sign_on_attempt} ledger of the withdrawn sign-on throttle;
     * with that table gone, every table the migrations create is a record layout, so this method now
     * returns the delivered schema in full and any unexpected table fails here as well as in
     * {@link #operationalTableNames()}.</p>
     *
     * <p>Every exclusion is written against lower-case names because the server folds unquoted
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
     * Reads back the operational tables that actually exist on the shared server, in name order.
     *
     * <p>The counterpart of {@link #applicationTableNames()} for a table that carries no record layout:
     * every table the server holds that is neither one of the eleven nor a framework's. The delivered
     * schema creates none, so the expected answer is empty - and asking the question is what makes that
     * emptiness a <em>measured</em> property rather than an assumption. A reinstated throttle ledger, or
     * any other operational table, appears here immediately.
     *
     * <p>The roster is inlined from {@link #APPLICATION_TABLES} rather than restated, so the two cannot
     * drift; only identifiers from that roster are interpolated and no caller input reaches the query.
     *
     * <p>Compare against {@link #OPERATIONAL_TABLES}.
     *
     * @return the names of the operational tables present on the shared server
     * @throws SQLException if the catalogue cannot be read
     */
    protected static List<String> operationalTableNames() throws SQLException {
        final String recordTables = APPLICATION_TABLES.stream()
                .map(table -> "'" + table + "'")
                .collect(Collectors.joining(", "));
        return queryOneColumn(String.format(Locale.ROOT, """
                SELECT table_name FROM information_schema.tables
                 WHERE table_schema = 'public'
                   AND table_name NOT LIKE 'batch\\_%%'
                   AND table_name <> 'flyway_schema_history'
                   AND table_name NOT IN (%s)
                 ORDER BY table_name
                """, recordTables));
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
     * silently - so the tables must be emptied <em>before</em> the seeds are re-applied.</p>
     *
     * <p><strong>The seed scripts are executed directly, and the migration history is never touched.
     * That is a correction.</strong> This method used to delete the seed versions' history rows and run
     * the migration tool again to make it re-apply them. That worked only while every schema version sat
     * below every seed version: once a delivered schema script was numbered 5 - above the
     * already-applied seeds, for the reason {@code docs/decision-log.md} DL-343
     * records - forgetting versions 3 and 4 left them pending <em>below</em> an applied 5. The tool
     * reported that as a validation failure and applied nothing, so every seeded row went missing and the
     * next assertion failed for a reason unrelated to what it tested. Allowing it instead, by declaring
     * the re-migration out-of-order, applied the rows but recorded the two versions in the
     * {@code OUT_OF_ORDER} state rather than {@code SUCCESS} - which then falsified every assertion that
     * reads the tool's own view of what is applied, and permanently rather than once.</p>
     *
     * <p>Both of those are consequences of using migration history as the mechanism for a job it was
     * never the mechanism for. This method wants the seeded <em>rows</em> back; it has no interest in the
     * seeded <em>versions</em>, which are applied, correct and should stay exactly as they are. Running
     * the two scripts is the direct expression of that, and it leaves history, checksums and every
     * migration state untouched. Neither script carries a placeholder or any other construct that needs
     * the tool to interpret it, so running them is not an approximation of migrating them.</p>
     *
     * <p>{@link #truncateOperationalTables()} is called for completeness and is a no-op while
     * {@link #OPERATIONAL_TABLES} is empty, which it is: the delivered schema creates no table outside
     * the eleven record layouts. It stays on this path so that a future operational table is emptied
     * with the rest rather than leaking state between tests.</p>
     *
     * <p>Afterwards {@link #appliedMigrationVersions()} again reports every delivered version as
     * successfully applied.</p>
     *
     * @throws SQLException if the tables cannot be emptied or the history cannot be amended
     */
    protected static void restoreSeededState() throws SQLException {
        truncateApplicationTables();
        truncateOperationalTables();
        applySeedScripts();
    }

    /**
     * Empties the operational tables, leaving the schema and the migration history untouched.
     *
     * <p>Separate from {@link #truncateApplicationTables()} rather than folded into it, so that method
     * keeps meaning exactly what its name and its Javadoc say: the eleven record-layout tables. A test
     * clearing business rows is not necessarily asking to clear throttle state, and one clearing
     * throttle state is not necessarily asking to delete every account.
     *
     * @throws SQLException if the tables cannot be emptied
     */
    protected static void truncateOperationalTables() throws SQLException {
        if (OPERATIONAL_TABLES.isEmpty()) {
            // No delivered table sits outside the eleven record layouts, so there is nothing to empty.
            // Returning is the correct answer rather than a skipped step: a TRUNCATE naming no table is
            // a syntax error, and reporting one here would read as a schema fault.
            return;
        }
        // Identifiers come from the roster above; no value and no caller input is interpolated.
        final String truncate = "TRUNCATE TABLE " + String.join(", ", OPERATIONAL_TABLES);
        try (Connection connection = connect();
                Statement statement = connection.createStatement()) {
            statement.execute(truncate);
        }
    }

    /**
     * Runs the delivered seed scripts against the shared server, in version order.
     *
     * <p>The text is the shipped script, read from the same classpath location the migration tool
     * resolves it from, so a reset cannot drift from what a migration delivers: there is no second copy
     * of the seed data to keep in step. Nothing is interpolated into it - the scripts are constants on
     * the classpath and carry no placeholder - so no caller input reaches a statement.</p>
     *
     * @throws SQLException if a script cannot be read or applied
     */
    private static void applySeedScripts() throws SQLException {
        for (final String script : SEED_SCRIPTS) {
            final String sql = readClasspathScript(script);
            try (Connection connection = connect();
                    Statement statement = connection.createStatement()) {
                statement.execute(sql);
            }
        }
    }

    /**
     * Reads a delivered SQL script from the classpath.
     *
     * @param  resource the classpath-relative path of the script
     * @return the script text
     * @throws SQLException if the script is absent or unreadable, reported as a data-access failure
     *                      because that is what every caller of this class already handles
     */
    private static String readClasspathScript(final String resource) throws SQLException {
        try (InputStream stream =
                AbstractPostgresIT.class.getClassLoader().getResourceAsStream(resource)) {
            if (stream == null) {
                throw new SQLException("the delivered seed script " + resource + " is not on the test"
                        + " classpath, so the seeded state cannot be restored from the shipped"
                        + " definition");
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (final IOException failure) {
            throw new SQLException("the delivered seed script " + resource + " could not be read",
                    failure);
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
