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
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Shared base for every integration test that needs the migrated relational schema on a real
 * PostgreSQL server.
 *
 * <h2>What this provides</h2>
 * One PostgreSQL 16 server, migrated by Flyway to the head of {@code db/migration}, reachable
 * through {@link #connect()} and through the three connection accessors. Nothing else: this class
 * declares no test, no lifecycle callback and no fixture, so a subclass owns its own data and its
 * own cleanup exactly as it did before extraction.
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
    private static final String MIGRATION_LOCATION = "classpath:db/migration";

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
     * Starts the server and brings its schema to the head of the migration set.
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
}
