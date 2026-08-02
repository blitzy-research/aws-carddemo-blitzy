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
import java.sql.SQLException;

import org.flywaydb.core.Flyway;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

import com.carddemo.config.FlywayConfig;

/**
 * Shared base for every integration test that needs the migrated relational schema on a real
 * PostgreSQL server.
 *
 * <h2>What this provides</h2>
 * One PostgreSQL 16 server, migrated by Flyway to the head of {@code db/migration} - so the schema,
 * the indexes and both seed migrations are applied - reachable through {@link #connect()} and through
 * the three connection accessors. Nothing else: this class
 * declares no test, no lifecycle callback and no fixture, so a subclass owns its own data and its
 * own cleanup exactly as it did before extraction.
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
 * <h2>Why the migration runs to the head, and not to the production ceiling</h2>
 * The five delivered migrations share one location: {@code V1}, {@code V1_1} and {@code V2} create the
 * schema, the batch metadata and the indexes, and {@code V3} and {@code V4} seed sample reference rows
 * and ten sign-on identities. Production applies the first three only - {@code spring.flyway.target: 2}
 * in the shipped configuration, with {@link FlywayConfig} refusing a production
 * profile whose resolved ceiling or location list reaches further. <strong>This base reproduces the
 * TEST profile rather than the production one</strong>, because that is the posture the module actually
 * ships for tests: {@code src/test/resources/application-test.yml} lifts the ceiling to the head, and
 * the container-backed tier asserts against the seeded rows themselves - the fifty seeded customers and
 * their protected identifiers, the fifty seeded accounts, and the seventeen rows of each disclosure
 * group are read directly from this server by several subclasses. Pinning the shared server to the
 * production ceiling would leave those assertions reading empty result sets and failing for a reason
 * unrelated to what they test.
 *
 * <p>The production-shaped posture is nonetheless proven, and proven better than a pin here could prove
 * it: {@code SeedMigrationIT} migrates into a schema of its own, once to the ceiling and once to the
 * head, and asserts what each run applied and what it left pending. That keeps the claim about
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
 * <p>Provenance: this support type has no legacy antecedent - the legacy estate carries no test
 * harness of any kind. It exists to serve tests of the migrated schema derived from the record
 * layouts in {@code app/cpy}, taken from checkout {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec},
 * upstream release stamp {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19.</p>
 */
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

    /** Location Flyway scans, matching the module's own migration path. */
    protected static final String MIGRATION_LOCATION = "classpath:db/migration";

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
     * <p>No ceiling is set, so all five delivered migrations are applied in version order and the
     * seeded reference rows and sign-on identities are present. That matches the test profile the
     * module ships and is what the container-backed assertions read.
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
}
