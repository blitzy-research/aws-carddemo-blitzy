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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves that a Spring context really inherits the running containers' addresses, and that those
 * addresses really outrank the profile documents.
 *
 * <h2>Why the other two registration tests are not enough on their own</h2>
 * {@code PostgresPropertyRegistrationIT} and {@code LocalStackPropertyRegistrationIT} assert what the
 * base classes publish. That is the mapping, and it is the part a maintainer can break by editing a key
 * name. It is not the whole claim. The comments on those classes and in both copies of
 * {@code application-test.yml} make a second claim - that the Spring TestContext Framework discovers the
 * registration and that a dynamic property source outranks every property file - and until it is
 * asserted that is framework behaviour taken on trust. This test asserts it, so no prose in this module
 * describes a precedence that has not been demonstrated.
 *
 * <h2>What is actually being demonstrated, key by key</h2>
 *
 * <ul>
 *   <li>{@code spring.datasource.url} is declared by NO profile document, deliberately, so that a test
 *       which forgot to start a container fails during refresh rather than connecting to a developer's
 *       own database. If the registration were not honoured the property would be absent and this test
 *       would fail - which makes the assertion a direct test of discovery.</li>
 *   <li>{@code spring.cloud.aws.endpoint} IS declared by both profile documents, because an absent AWS
 *       endpoint resolves the region's real public endpoint instead of failing. That declared value is a
 *       floor at the emulator's fixed port. Observing the ephemeral endpoint of the container this JVM
 *       started, and not the floor, is what demonstrates precedence.</li>
 *   <li>{@code management.otlp.tracing.export.enabled} is asserted from the resolved environment rather
 *       than from the file text, so the profile's export posture is confirmed as the value a running
 *       application would see.</li>
 * </ul>
 *
 * <h2>Why the context is deliberately almost empty</h2>
 * The context is loaded from one bare {@code @Configuration} class carrying no {@code @Bean} and no
 * auto-configuration. Everything under test here happens before and outside the bean factory: config
 * data is loaded, the profile is activated, and the dynamic property source is inserted at the front of
 * the environment. Loading the whole application would exercise all of that too, and would additionally
 * make this test fail for a hundred unrelated reasons, so it loads the smallest context in which the
 * question can be asked.
 *
 * <p>This is the only {@code @SpringBootTest} in the module, and it is not a precedent for the tiers
 * above it: every other integration test works directly against a container because that is the cheapest
 * way to assert a record layout or a queue contract. It exists solely because the precedence claim cannot
 * be observed any other way.</p>
 *
 * <p>Provenance: this test has no legacy antecedent - the legacy estate carries no test harness of any
 * kind. Checkout {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19.</p>
 */
@SpringBootTest(classes = ContextInheritsContainerAddressesIT.BareContext.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@DisplayName("A context inherits the running containers, and they outrank the profile documents")
class ContextInheritsContainerAddressesIT extends AbstractPostgresIT {

    /** The port the emulator publishes inside the development stack, and so the value of the floor. */
    private static final String EMULATOR_FIXED_PORT = "4566";

    /** The resolved environment of the loaded context. */
    @Autowired
    private Environment environment;

    /**
     * Adds the emulator's addresses to the same registry the database base class writes into.
     *
     * <p>A test class extends exactly one base, and this one extends the database base so that the
     * database half is a real container. The emulator half is added here by delegating to the other
     * base's own registration method rather than duplicating it, so the values asserted below are
     * produced by the code under test and not by a copy of it. Touching that class also starts its
     * emulator, which is the shared instance every other integration test in this JVM uses.</p>
     *
     * @param registry the registry the Spring TestContext Framework supplies; must not be null
     */
    @DynamicPropertySource
    static void registerEmulatorProperties(final DynamicPropertyRegistry registry) {
        AbstractLocalStackIT.registerAwsProperties(registry);
    }

    /**
     * The smallest context in which config data is loaded, a profile is activated and a dynamic property
     * source is applied.
     *
     * <p>Carries no bean and enables no auto-configuration on purpose: see the class comment.</p>
     */
    @Configuration(proxyBeanMethods = false)
    static class BareContext {
        // Intentionally empty: this test asserts the environment, not any bean.
    }

    @Nested
    @DisplayName("the database address")
    class TheDatabaseAddress {

        @Test
        @DisplayName("is present in the environment even though no document declares it")
        void isPresentAlthoughNoDocumentDeclaresIt() {
            assertThat(environment.getProperty("spring.datasource.url"))
                    .as("no copy of application-test.yml declares this key, so the only way it can be"
                            + " present is that AbstractPostgresIT's registration was discovered and"
                            + " applied. Its absence would mean a context-booting test had no database"
                            + " at all - which is the correct failure for a test that never started a"
                            + " container, and the wrong one for a test that did")
                    .isNotNull()
                    .isEqualTo(jdbcUrl());
        }

        @Test
        @DisplayName("carries the container's credentials, not a document's")
        void carriesTheContainersCredentials() {
            assertThat(environment.getProperty("spring.datasource.username")).isEqualTo(databaseUser());
            assertThat(environment.getProperty("spring.datasource.password"))
                    .isEqualTo(databasePassword());
        }

        @Test
        @DisplayName("is reached through the PostgreSQL driver, which the document pins and the "
                + "registration confirms")
        void isReachedThroughThePostgresDriver() {
            assertThat(environment.getProperty("spring.datasource.driver-class-name"))
                    .as("this key IS declared by the document, and the registration republishes it, so"
                            + " the two must agree - an embedded engine substituted here would not carry"
                            + " the migrated schema")
                    .isEqualTo("org.postgresql.Driver")
                    .isEqualTo(driverClassName());
        }
    }

    @Nested
    @DisplayName("the emulator address")
    class TheEmulatorAddress {

        @Test
        @DisplayName("outranks the floor the profile document declares")
        void outranksTheFloorTheDocumentDeclares() {
            assertThat(environment.getProperty("spring.cloud.aws.endpoint"))
                    .as("the profile document declares this key at the emulator's fixed port %s, and a"
                            + " document value is what a context would see if a dynamic property source"
                            + " did not outrank it. Observing the ephemeral endpoint of the container"
                            + " this JVM started is therefore the precedence proof. The assertion is"
                            + " written as equality with the live endpoint rather than inequality with"
                            + " the floor, because an ephemeral port could in principle be allocated as"
                            + " %s and an inequality would then fail for a reason that is not a defect",
                            EMULATOR_FIXED_PORT, EMULATOR_FIXED_PORT)
                    .isNotNull()
                    .isEqualTo(AbstractLocalStackIT.emulatorEndpoint());
        }

        @Test
        @DisplayName("is applied to every per-service client, not only to the global setting")
        void isAppliedToEveryPerServiceClient() {
            String expected = AbstractLocalStackIT.emulatorEndpoint();

            assertThat(environment.getProperty("spring.cloud.aws.s3.endpoint")).isEqualTo(expected);
            assertThat(environment.getProperty("spring.cloud.aws.sqs.endpoint")).isEqualTo(expected);
            assertThat(environment.getProperty("spring.cloud.aws.sns.endpoint")).isEqualTo(expected);
        }

        @Test
        @DisplayName("comes with the emulator's own region and credentials, so the default credential "
                + "chain is never consulted")
        void comesWithTheEmulatorsRegionAndCredentials() {
            assertThat(environment.getProperty("spring.cloud.aws.region.static"))
                    .isEqualTo(AbstractLocalStackIT.emulatorRegion());
            assertThat(environment.getProperty("spring.cloud.aws.credentials.access-key"))
                    .as("a resolved credential here is what keeps the SDK's default chain - which on a"
                            + " developer machine or a build agent can find a real credential - out of"
                            + " the picture")
                    .isEqualTo(AbstractLocalStackIT.emulatorAccessKey());
            assertThat(environment.getProperty("spring.cloud.aws.credentials.secret-key"))
                    .isEqualTo(AbstractLocalStackIT.emulatorSecretKey());
        }
    }

    @Nested
    @DisplayName("the profile posture a running application would see")
    class TheResolvedProfilePosture {

        @Test
        @DisplayName("the test profile is the active one")
        void theTestProfileIsActive() {
            assertThat(environment.getActiveProfiles())
                    .as("every assertion in this class is about what the test profile resolves to, so"
                            + " the profile being active is the premise")
                    .containsExactly("test");
        }

        @Test
        @DisplayName("trace export is off at the key that decides it, in the resolved environment")
        void traceExportIsOffInTheResolvedEnvironment() {
            assertThat(environment.getProperty("management.otlp.tracing.export.enabled"))
                    .as("asserted from the resolved environment rather than from file text: this is the"
                            + " value the condition behind the OTLP exporter actually reads, and it is"
                            + " read before management.tracing.enabled. A sampling probability cannot"
                            + " substitute for it, because the effective sampler is parent-based and a"
                            + " request carrying an already-sampled trace context is sampled whatever"
                            + " the ratio says")
                    .isEqualTo("false");
        }

        @Test
        @DisplayName("tracing instrumentation is off as well in a suite run")
        void tracingInstrumentationIsOffInASuiteRun() {
            assertThat(environment.getProperty("management.tracing.enabled"))
                    .as("the suite overlay switches instrumentation off outright, which the packaged"
                            + " copy does not; the overlay is the copy in force here")
                    .isEqualTo("false");
        }
    }
}
