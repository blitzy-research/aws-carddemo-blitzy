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
import java.util.List;
import java.util.Properties;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that {@link AbstractPostgresIT} really publishes the running container's address, and that
 * the test profile really leaves the same keys undeclared.
 *
 * <h2>Why this pairing is the thing under test</h2>
 * Two halves have to hold together, and each is worthless without the other.
 *
 * <p>The first half is that no property file declares a database address for the test profile. If one
 * did, and it were reachable, a test that forgot to extend {@link AbstractPostgresIT} would connect to
 * whatever that address named - in practice the developer's own compose stack - and would pass while
 * exercising the wrong database. That is not a hypothetical: an earlier revision of the profile
 * defaulted the address, user and password to the development stack for the sake of always having
 * something to bind.</p>
 *
 * <p>The second half is that a test which <em>does</em> extend this class must still be able to boot a
 * context. Removing the file-declared address alone would leave a context with no address at all, so
 * the base class has to supply one, and it has to supply the ephemeral address of the container that is
 * actually running rather than a fixed guess.</p>
 *
 * <p>Together they give the property that matters: a context is either handed the container it was
 * given or it fails during refresh naming the missing property. There is no third outcome in which it
 * silently succeeds against something else. This test asserts both halves, because asserting either one
 * alone would leave the other free to regress.</p>
 *
 * <h2>Why this is an integration test</h2>
 * The registered values are read from a started container, so the container must be running for the
 * assertions to mean anything. Extending {@link AbstractPostgresIT} starts it, and the shared instance
 * is reused by every other integration test in the same JVM, so this class costs no additional
 * container.
 *
 * <p>Provenance: this test has no legacy antecedent - the legacy estate carries no test harness of any
 * kind. Checkout {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19.</p>
 */
@DisplayName("The database container's address is published to a context, and declared nowhere else")
class PostgresPropertyRegistrationIT extends AbstractPostgresIT {

    /** The profile document whose silence about an address this test depends on. */
    private static final String TEST_PROFILE_DOCUMENT = "application-test.yml";

    /** The three keys a data source cannot resolve without, and which no profile document declares. */
    private static final List<String> ADDRESS_KEYS = List.of(
            "spring.datasource.url",
            "spring.datasource.username",
            "spring.datasource.password");

    /** Everything the registration publishes, in the order it publishes it. */
    private static final List<String> EXPECTED_REGISTRATIONS = List.of(
            "spring.datasource.url",
            "spring.datasource.username",
            "spring.datasource.password",
            "spring.datasource.driver-class-name");

    /**
     * Runs the registration under test and captures what it published.
     *
     * @return the recorded registrations
     */
    private static RecordingPropertyRegistry record() {
        final RecordingPropertyRegistry registry = new RecordingPropertyRegistry();
        registerDataSourceProperties(registry);
        return registry;
    }

    /**
     * Reads a YAML document from the class path and flattens it to leaf property paths.
     *
     * @param resource the document to read
     * @return the flattened properties
     * @throws IOException if the document cannot be read
     */
    private static Properties leavesOf(final Resource resource) throws IOException {
        try (InputStream stream = resource.getInputStream()) {
            final YamlPropertiesFactoryBean factory = new YamlPropertiesFactoryBean();
            factory.setResources(new InputStreamResource(stream));
            factory.afterPropertiesSet();
            final Properties properties = factory.getObject();
            assertThat(properties).as("the profile document must parse").isNotNull();
            return properties;
        }
    }

    @Nested
    @DisplayName("What the base class publishes")
    class WhatIsPublished {

        @Test
        @DisplayName("exactly the four data-source keys, in a stable order")
        void publishesExactlyTheFourDataSourceKeys() {
            final RecordingPropertyRegistry registry = record();

            assertThat(registry.names())
                    .as("the registration must publish exactly the data-source keys a context needs,"
                            + " and nothing else - an extra key here would silently override a profile"
                            + " setting for every context-booting test")
                    .containsExactlyElementsOf(EXPECTED_REGISTRATIONS);
            assertThat(registry.size()).isEqualTo(EXPECTED_REGISTRATIONS.size());
        }

        @Test
        @DisplayName("the address is the running container's own, mapped port included")
        void publishesTheRunningContainersAddress() {
            final RecordingPropertyRegistry registry = record();

            assertThat(registry.valueOf("spring.datasource.url"))
                    .as("a context must be handed the address of the container that is running, not a"
                            + " fixed address that could name a different server")
                    .isEqualTo(jdbcUrl())
                    .startsWith("jdbc:postgresql://")
                    .contains("/" + DATABASE_NAME);
        }

        @Test
        @DisplayName("the credentials are the running container's own")
        void publishesTheRunningContainersCredentials() {
            final RecordingPropertyRegistry registry = record();

            assertThat(registry.valueOf("spring.datasource.username")).isEqualTo(databaseUser());
            assertThat(registry.valueOf("spring.datasource.password")).isEqualTo(databasePassword());
        }

        @Test
        @DisplayName("the driver is the PostgreSQL driver, so no embedded engine can be substituted")
        void publishesThePostgresDriver() {
            final RecordingPropertyRegistry registry = record();

            assertThat(registry.valueOf("spring.datasource.driver-class-name"))
                    .as("an auto-detected embedded engine would not carry the migrated schema")
                    .isEqualTo(driverClassName())
                    .isEqualTo("org.postgresql.Driver");
        }

        @Test
        @DisplayName("values are resolved through the supplier, not copied at registration time")
        void resolvesThroughTheSupplier() {
            final RecordingPropertyRegistry registry = record();

            assertThat(registry.valueOf("spring.datasource.url"))
                    .as("resolving twice must give the same live answer")
                    .isEqualTo(registry.valueOf("spring.datasource.url"))
                    .isEqualTo(jdbcUrl());
        }
    }

    @Nested
    @DisplayName("What the profile document deliberately does not declare")
    class WhatTheProfileOmits {

        @Test
        @DisplayName("the suite overlay declares no address, user or password")
        void theSuiteOverlayDeclaresNoAddress() throws IOException {
            final Properties leaves = leavesOf(new ClassPathResource(TEST_PROFILE_DOCUMENT));

            assertThat(ADDRESS_KEYS)
                    .allSatisfy(key -> assertThat(leaves.getProperty(key))
                            .as("%s must be absent: a reachable value here would let a test that never"
                                    + " started a container connect to a real server and pass", key)
                            .isNull());
        }

        @Test
        @DisplayName("the driver IS declared, because pinning it prevents an embedded substitution")
        void theSuiteOverlayDoesDeclareTheDriver() throws IOException {
            final Properties leaves = leavesOf(new ClassPathResource(TEST_PROFILE_DOCUMENT));

            assertThat(leaves.getProperty("spring.datasource.driver-class-name"))
                    .as("the driver is safe to declare - it names an engine, not a server")
                    .isEqualTo("org.postgresql.Driver");
        }

        @Test
        @DisplayName("every key the overlay omits is a key the base class supplies")
        void theRegistrationCoversExactlyWhatTheOverlayOmits() throws IOException {
            final Properties leaves = leavesOf(new ClassPathResource(TEST_PROFILE_DOCUMENT));
            final List<String> published = record().names();

            assertThat(ADDRESS_KEYS)
                    .as("the two halves must be complementary: what the file withholds so that a"
                            + " forgetful test fails closed is exactly what the base class provides so"
                            + " that a correct test still boots")
                    .allSatisfy(key -> {
                        assertThat(leaves.getProperty(key)).isNull();
                        assertThat(published).contains(key);
                    });
        }
    }
}
