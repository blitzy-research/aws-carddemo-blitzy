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
import java.net.URI;
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
 * Verifies that no AWS client assembled under the test profile can address a real AWS account, whether
 * or not the test that assembled it started an emulator.
 *
 * <h2>Why an absent AWS endpoint is more dangerous than an absent database address</h2>
 * The two failure modes are not symmetric, and the asymmetry is the whole reason this test exists.
 *
 * <p>A data source with no address fails closed: resolution fails during context refresh and the run
 * stops. A client with no endpoint override does the opposite - the SDK resolves the region's real
 * public endpoint and sends the request there, on whatever credentials the default chain happens to
 * find. On a developer's machine or a build agent that chain can find real ones. So the outcome of
 * forgetting to point a client at the emulator is not an error; it is a silent, authenticated request
 * against a real account.</p>
 *
 * <p>Two independent mechanisms therefore have to be in place, and this test asserts both:</p>
 *
 * <ul>
 *   <li>A FLOOR in the profile documents. Both copies of {@code application-test.yml} declare the
 *       emulator endpoint for the global setting and for each of the three services, so a client
 *       assembled by a context that never started an emulator still addresses an emulator address
 *       rather than a real one.</li>
 *   <li>A LIFT from {@link AbstractLocalStackIT}. A context whose test class extends that base class
 *       has the floor replaced by the ephemeral address of the emulator actually running, because a
 *       dynamic property source outranks every property file.</li>
 * </ul>
 *
 * <p>Neither mechanism is redundant. Without the floor, a context that forgot the base class reaches
 * real AWS. Without the lift, a context that remembered it reaches the wrong emulator - the long-running
 * one on the fixed port, whose queues belong to other processes.</p>
 *
 * <p>The floor is asserted here against the document that is actually in force during a suite run - the
 * copy on the test class path. That both physical copies of the document carry the same floor, including
 * the packaged one a deployed {@code test}-profile run resolves, is asserted by
 * {@code ConfigurationProfileBaselineTest}, which is the one place that distinguishes the two copies by
 * physical location rather than by class-path name.</p>
 *
 * <h2>Why this is an integration test</h2>
 * The lifted values are read from a started emulator, so the emulator must be running for the assertions
 * to mean anything. Extending {@link AbstractLocalStackIT} starts it, and the shared instance is reused
 * by every other integration test in the same JVM, so this class costs no additional container.
 *
 * <p>Provenance: this test has no legacy antecedent - the legacy estate carries no test harness of any
 * kind. It guards the client configuration for the queue that replaces {@code TDQUEUE(JOBS)} as defined
 * in {@code app/csd/CARDDEMO.CSD}, taken from checkout
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19.</p>
 */
@DisplayName("No AWS client under the test profile can address a real account")
class LocalStackPropertyRegistrationIT extends AbstractLocalStackIT {

    /** The profile document that carries the endpoint floor. */
    private static final String TEST_PROFILE_DOCUMENT = "application-test.yml";

    /** The global endpoint setting and the three per-service restatements of it. */
    private static final List<String> ENDPOINT_KEYS = List.of(
            "spring.cloud.aws.endpoint",
            "spring.cloud.aws.s3.endpoint",
            "spring.cloud.aws.sqs.endpoint",
            "spring.cloud.aws.sns.endpoint");

    /** Everything the registration publishes, in the order it publishes it. */
    private static final List<String> EXPECTED_REGISTRATIONS = List.of(
            "spring.cloud.aws.region.static",
            "spring.cloud.aws.credentials.access-key",
            "spring.cloud.aws.credentials.secret-key",
            "spring.cloud.aws.endpoint",
            "spring.cloud.aws.s3.endpoint",
            "spring.cloud.aws.sqs.endpoint",
            "spring.cloud.aws.sns.endpoint");

    /** The fixed port the emulator publishes inside the development stack. */
    private static final String EMULATOR_PORT = "4566";

    /** The host suffix that would prove a request was bound for a real account. */
    private static final String REAL_AWS_HOST_SUFFIX = "amazonaws.com";

    /**
     * Runs the registration under test and captures what it published.
     *
     * @return the recorded registrations
     */
    private static RecordingPropertyRegistry record() {
        final RecordingPropertyRegistry registry = new RecordingPropertyRegistry();
        registerAwsProperties(registry);
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
    @DisplayName("The lift - what the base class publishes")
    class TheLift {

        @Test
        @DisplayName("exactly the region, the credentials and all four endpoint settings")
        void publishesExactlyTheAwsKeys() {
            final RecordingPropertyRegistry registry = record();

            assertThat(registry.names())
                    .as("the region and credentials pin the client away from the default chain; the"
                            + " four endpoint settings pin it away from the real service")
                    .containsExactlyElementsOf(EXPECTED_REGISTRATIONS);
            assertThat(registry.size()).isEqualTo(EXPECTED_REGISTRATIONS.size());
        }

        @Test
        @DisplayName("every endpoint is the running emulator's ephemeral address")
        void publishesTheRunningEmulatorsAddress() {
            final RecordingPropertyRegistry registry = record();

            assertThat(ENDPOINT_KEYS)
                    .as("the global setting and each per-service restatement must all name the one"
                            + " emulator this JVM started, so dropping any single one of them cannot"
                            + " leave a client pointing elsewhere")
                    .allSatisfy(key -> assertThat(registry.valueOf(key)).isEqualTo(emulatorEndpoint()));
        }

        @Test
        @DisplayName("no published endpoint names a real AWS host")
        void publishesNoRealAwsHost() {
            final RecordingPropertyRegistry registry = record();

            assertThat(ENDPOINT_KEYS).allSatisfy(key -> {
                final String endpoint = registry.valueOf(key);
                assertThat(URI.create(endpoint).getHost())
                        .as("%s must resolve to the loopback interface", key)
                        .isIn("localhost", "127.0.0.1");
                assertThat(endpoint)
                        .as("%s must not name a real service host", key)
                        .doesNotContain(REAL_AWS_HOST_SUFFIX);
            });
        }

        @Test
        @DisplayName("the region and credentials are the emulator's own throwaway values")
        void publishesTheEmulatorsRegionAndCredentials() {
            final RecordingPropertyRegistry registry = record();

            assertThat(registry.valueOf("spring.cloud.aws.region.static")).isEqualTo(emulatorRegion());
            assertThat(registry.valueOf("spring.cloud.aws.credentials.access-key"))
                    .as("registering a credential is what keeps the default chain - which can find a"
                            + " real credential - out of the picture entirely")
                    .isEqualTo(emulatorAccessKey())
                    .isNotBlank();
            assertThat(registry.valueOf("spring.cloud.aws.credentials.secret-key"))
                    .isEqualTo(emulatorSecretKey())
                    .isNotBlank();
        }
    }

    @Nested
    @DisplayName("The floor - what the profile document declares even without a container")
    class TheFloor {

        @Test
        @DisplayName("all four endpoint settings are declared")
        void declaresAllFourEndpointSettings() throws IOException {
            final Properties leaves = leavesOf(new ClassPathResource(TEST_PROFILE_DOCUMENT));

            assertThat(ENDPOINT_KEYS)
                    .as("an absent AWS endpoint does not fail closed - it resolves the region's real"
                            + " public endpoint - so every one of these must be declared")
                    .allSatisfy(key -> assertThat(leaves.getProperty(key)).isNotNull().isNotBlank());
        }

        @Test
        @DisplayName("each declared endpoint names the emulator port and no real AWS host")
        void everyDeclaredEndpointNamesTheEmulator() throws IOException {
            final Properties leaves = leavesOf(new ClassPathResource(TEST_PROFILE_DOCUMENT));

            assertThat(ENDPOINT_KEYS).allSatisfy(key -> assertThat(leaves.getProperty(key))
                    .as("%s must address the emulator", key)
                    .contains(EMULATOR_PORT)
                    .doesNotContain(REAL_AWS_HOST_SUFFIX));
        }

        @Test
        @DisplayName("credentials are declared, so the default chain is never consulted")
        void declaresCredentials() throws IOException {
            final Properties leaves = leavesOf(new ClassPathResource(TEST_PROFILE_DOCUMENT));

            assertThat(leaves.getProperty("spring.cloud.aws.credentials.access-key"))
                    .as("the emulator accepts any non-empty pair and verifies neither; the point of"
                            + " declaring them is that no real credential is ever reached for")
                    .isNotNull()
                    .isNotBlank();
            assertThat(leaves.getProperty("spring.cloud.aws.credentials.secret-key"))
                    .isNotNull()
                    .isNotBlank();
        }

        @Test
        @DisplayName("the floor and the lift cover the same key set")
        void theFloorAndTheLiftCoverTheSameKeys() throws IOException {
            final Properties leaves = leavesOf(new ClassPathResource(TEST_PROFILE_DOCUMENT));
            final List<String> published = record().names();

            assertThat(published)
                    .as("a key the base class lifts but the file does not floor would be unprotected"
                            + " for any context that did not extend the base class")
                    .allSatisfy(key -> assertThat(leaves.getProperty(key))
                            .as("%s must also be declared in the profile document", key)
                            .isNotNull());
        }
    }
}
