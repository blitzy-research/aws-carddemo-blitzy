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
import static org.junit.jupiter.params.provider.Arguments.arguments;

import com.carddemo.CardDemoApplication;
import com.carddemo.config.ProductionConfigurationValidator.RequiredSetting;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

/**
 * Proves that an incomplete production environment stops the container <em>before</em> a single bean is
 * created, and that no other profile is affected by the check.
 *
 * <h2>Why a real container is required here</h2>
 *
 * <p>{@code ProductionConfigurationValidatorTest} proves what the check decides. It cannot prove
 * <em>when</em> the check runs, and "when" is the whole point of the finding this test closes: a
 * placeholder that is discovered while the data source is opening a connection, or while the embedded
 * server is reading a key store, has already been handed to infrastructure. So this class starts a real
 * application context on the production profile, with the real profile documents loaded, and observes
 * two things at once - that the start fails, and that a bean which would have reached infrastructure was
 * never constructed.
 *
 * <p>The observation is made by a stand-in bean that counts its own construction. It is an ordinary
 * eager singleton, so it is created in the same phase as the data source, the migration runner and the
 * configuration-properties objects. A count of zero after a failed start is therefore direct evidence
 * that the failure preceded all of them.
 *
 * <h2>How the context is assembled, and why each choice was made</h2>
 *
 * <ul>
 *   <li><strong>No auto-configuration.</strong> Only the validator and the stand-in are registered. A
 *       full auto-configured context would need a reachable database and a key store to succeed, which
 *       would turn a configuration test into an infrastructure test.</li>
 *   <li><strong>Real profile documents.</strong> {@link ConfigDataApplicationContextInitializer} loads
 *       {@code application.yml} and the profile overlay exactly as a deployment does, so the keys, the
 *       variable names and the fallback tails under test are the delivered ones.</li>
 *   <li><strong>No settings from the machine.</strong> Both system-backed property sources are removed
 *       from the environment before anything is loaded. A build agent that exports {@code AWS_REGION} -
 *       which agents in that ecosystem routinely do - would otherwise satisfy one of the twelve by
 *       accident and quietly turn a negative case positive.</li>
 *   <li><strong>Variables supplied through a property source rather than through inlined test
 *       properties.</strong> The inlined mechanism trims what it is given, so it cannot express a
 *       whitespace-only value, and whitespace is one of the three forms this check has to reject.</li>
 * </ul>
 *
 * @see ProductionConfigurationValidator
 * @see ProductionConfigurationValidatorTest
 */
@DisplayName("An incomplete production environment stops before infrastructure is touched")
final class ProductionInfrastructureIsUntouchedTest {

    /** Bean name the validator's factory method publishes the check under. */
    private static final String GUARD_BEAN = "productionConfigurationGuard";

    /** Profile whose overlay carries developer defaults for the same keys. */
    private static final String LOCAL_PROFILE = "local";

    /** Profile the suite itself runs under. */
    private static final String TEST_PROFILE = "test";

    /**
     * Supplies every required variable with a usable value.
     *
     * <p>Each value is derived from its own variable name, because the required-settings check judges
     * <em>usability</em> - declared, resolved, non-blank - rather than shape, so nothing
     * credential-shaped needs to appear in this file.
     *
     * <p><strong>Two variables are exceptions, and the exception is the point rather than a
     * convenience.</strong> The guard published by this configuration now performs a second check that
     * the required-settings sweep does not: the job-submission queue must be a destination this
     * deployment can have meant, and that is judged against the region the deployment declares. A
     * name-derived value is not a queue destination at all, so those two carry realistic synthetic
     * values - a bare first-in-first-out queue name, which is the preferred form because a name carries
     * no destination for anything to redirect, and a region. Neither is a credential and neither names
     * any real resource.
     *
     * <p>The outbound-trust rule itself is exercised in full by {@code ProductionOutboundTrustTest};
     * what this file needs from it is only that a complete environment satisfies it, so that a refusal
     * anywhere else in this class is attributable to what that test removed.
     *
     * @return a mutable map from variable name to value
     */
    private static Map<String, String> everyRequiredVariable() {
        final Map<String, String> variables = new LinkedHashMap<>();
        for (final RequiredSetting setting : ProductionConfigurationValidator.REQUIRED_SETTINGS) {
            variables.put(setting.environmentVariable(),
                    "supplied-by-this-test-for-" + setting.environmentVariable());
        }
        variables.put("CARDDEMO_SQS_QUEUE", "JOBS.fifo");
        variables.put("AWS_REGION", "eu-west-2");
        return variables;
    }

    /** @return one argument pair per required setting: the property key and its variable */
    private static Stream<Arguments> requiredSettings() {
        return ProductionConfigurationValidator.REQUIRED_SETTINGS.stream()
                .map(setting -> arguments(setting.propertyKey(), setting.environmentVariable()));
    }

    /**
     * Builds the exact report line for one setting.
     *
     * <p>Compared as a whole line rather than as a key, because three of the twelve keys are prefixes of
     * each other - the key store, its credential and its format.
     *
     * @param propertyKey key to build the line for
     * @return the report line's leading text, unique to that setting
     */
    private static String reportLineFor(final String propertyKey) {
        return ProductionConfigurationValidator.REQUIRED_SETTINGS.stream()
                .filter(setting -> setting.propertyKey().equals(propertyKey))
                .findFirst()
                .map(setting -> "  " + setting.propertyKey() + " <- " + setting.environmentVariable()
                        + ":")
                .orElseThrow(() -> new AssertionError("No required setting declares " + propertyKey));
    }

    /**
     * Creates a context whose environment carries nothing from the machine it runs on.
     *
     * <p>{@code AnnotationConfigApplicationContext} propagates a replaced environment to the annotated
     * bean reader as well as to itself, so the profile condition on the validator is evaluated against
     * this environment rather than against the one the constructor made.
     *
     * @return an unrefreshed context with an empty environment
     */
    private static ConfigurableApplicationContext hermeticContext() {
        final AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        final StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources()
                .remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment.getPropertySources()
                .remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        context.setEnvironment(environment);
        return context;
    }

    /**
     * Prepares a container that activates one profile with a given set of environment variables.
     *
     * <p>The two initializers are ordered deliberately: the variables are published first so that a
     * document loaded afterwards can resolve them, and the profile documents are loaded second. The
     * active profile itself is supplied as an inlined property, which the runner applies before either
     * initializer.
     *
     * @param profile   profile to activate, or {@code null} to start with no profile at all
     * @param variables environment variables to make resolvable
     * @return a prepared runner
     */
    private static ApplicationContextRunner containerFor(final String profile,
            final Map<String, String> variables) {
        ApplicationContextRunner runner = new ApplicationContextRunner(
                ProductionInfrastructureIsUntouchedTest::hermeticContext)
                .withInitializer(context -> context.getEnvironment().getPropertySources()
                        .addFirst(new MapPropertySource("variables-supplied-by-this-test",
                                new LinkedHashMap<String, Object>(variables))))
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(ProductionConfigurationValidator.class,
                        InfrastructureStandIn.Registration.class);
        if (profile != null) {
            runner = runner.withPropertyValues("spring.profiles.active=" + profile);
        }
        return runner;
    }

    @BeforeEach
    void forgetEarlierConstructions() {
        InfrastructureStandIn.forget();
    }

    @Nested
    @DisplayName("A complete production environment starts")
    class ACompleteProductionEnvironmentStarts {

        @Test
        @DisplayName("the context refreshes and the stand-in for infrastructure is constructed")
        void theContextRefreshes() {
            containerFor("prod", everyRequiredVariable()).run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).hasSingleBean(InfrastructureStandIn.class);
                assertThat(InfrastructureStandIn.constructions())
                        .as("an eager singleton is constructed once a context refreshes, which is what "
                                + "makes a count of zero elsewhere in this class meaningful")
                        .isOne();
            });
        }

        @Test
        @DisplayName("the check is published as a bean-factory post-processor, not as an ordinary bean")
        void theCheckIsPublishedAsAPostProcessor() {
            containerFor("prod", everyRequiredVariable()).run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context.containsBean(GUARD_BEAN)).isTrue();
                assertThat(context.getBean(GUARD_BEAN))
                        .as("the post-processor kind is what guarantees the check runs before the "
                                + "singleton phase; an ordinary bean would carry no such guarantee")
                        .isInstanceOf(BeanFactoryPostProcessor.class);
            });
        }
    }

    @Nested
    @DisplayName("An incomplete production environment stops before any bean is created")
    class AnIncompleteProductionEnvironmentStops {

        static Stream<Arguments> settings() {
            return requiredSettings();
        }

        @ParameterizedTest(name = "{1} is not set, so {0} stops the start")
        @MethodSource("settings")
        @DisplayName("omitting one variable fails the refresh before the stand-in is constructed")
        void omittingOneVariableStopsTheRefresh(final String propertyKey, final String variable) {
            final Map<String, String> variables = everyRequiredVariable();
            variables.remove(variable);

            containerFor("prod", variables).run(context -> {
                assertThat(context).hasFailed().getFailure()
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining(reportLineFor(propertyKey))
                        .hasMessageContaining("${" + variable + "}");
                assertThat(InfrastructureStandIn.constructions())
                        .as("the refresh must stop before the singleton phase, so nothing that would "
                                + "reach infrastructure may have been constructed")
                        .isZero();
            });
        }

        @ParameterizedTest(name = "{1} is empty, so {0} stops the start")
        @MethodSource("settings")
        @DisplayName("an empty variable fails the refresh before the stand-in is constructed")
        void anEmptyVariableStopsTheRefresh(final String propertyKey, final String variable) {
            final Map<String, String> variables = everyRequiredVariable();
            variables.put(variable, "");

            containerFor("prod", variables).run(context -> {
                assertThat(context).hasFailed().getFailure()
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining(reportLineFor(propertyKey))
                        .hasMessageContaining("set but empty");
                assertThat(InfrastructureStandIn.constructions()).isZero();
            });
        }

        @ParameterizedTest(name = "{1} is whitespace, so {0} stops the start")
        @MethodSource("settings")
        @DisplayName("a whitespace-only variable fails the refresh before the stand-in is constructed")
        void aWhitespaceOnlyVariableStopsTheRefresh(final String propertyKey, final String variable) {
            final Map<String, String> variables = everyRequiredVariable();
            variables.put(variable, "   ");

            containerFor("prod", variables).run(context -> {
                assertThat(context).hasFailed().getFailure()
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining(reportLineFor(propertyKey))
                        .hasMessageContaining("whitespace only");
                assertThat(InfrastructureStandIn.constructions()).isZero();
            });
        }

        @Test
        @DisplayName("an empty environment reports every fault and still creates nothing")
        void anEmptyEnvironmentReportsEveryFault() {
            containerFor("prod", Map.of()).run(context -> {
                final int required = ProductionConfigurationValidator.REQUIRED_SETTINGS.size();
                assertThat(context).hasFailed().getFailure()
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining(required + " of " + required + " required settings")
                        .hasMessageContaining("docs/decision-log.md DL-105");
                assertThat(InfrastructureStandIn.constructions()).isZero();
            });
        }
    }

    @Nested
    @DisplayName("No other profile is affected")
    class NoOtherProfileIsAffected {

        @Test
        @DisplayName("the local profile starts with no production variable set")
        void theLocalProfileStarts() {
            containerFor(LOCAL_PROFILE, Map.of()).run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).doesNotHaveBean(GUARD_BEAN);
                assertThat(InfrastructureStandIn.constructions()).isOne();
            });
        }

        @Test
        @DisplayName("the test profile starts with no production variable set")
        void theTestProfileStarts() {
            containerFor(TEST_PROFILE, Map.of()).run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).doesNotHaveBean(GUARD_BEAN);
                assertThat(InfrastructureStandIn.constructions()).isOne();
            });
        }

        @Test
        @DisplayName("a start with no profile at all is unaffected")
        void aProfilelessStartIsUnaffected() {
            containerFor(null, Map.of()).run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).doesNotHaveBean(GUARD_BEAN);
                assertThat(InfrastructureStandIn.constructions()).isOne();
            });
        }

        @Test
        @DisplayName("activating the local profile alongside production does not smuggle its defaults "
                + "past the check")
        void theLocalOverlayCannotSatisfyTheProductionRequirement() {
            containerFor(LOCAL_PROFILE + ",prod", Map.of()).run(context -> {
                assertThat(context).hasFailed().getFailure()
                        .as("the production overlay is applied last, so its bare references win over "
                                + "the developer defaults the local overlay carries for the same keys")
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining(reportLineFor("spring.datasource.url"))
                        .hasMessageContaining(reportLineFor("carddemo.security.jwt.secret"));
                assertThat(InfrastructureStandIn.constructions()).isZero();
            });
        }
    }

    @Nested
    @DisplayName("The application's own component scan finds the guard")
    class TheApplicationsOwnComponentScanFindsTheGuard {

        /** Bean name the annotation-driven name generator gives the configuration class. */
        private static final String VALIDATOR_BEAN = "productionConfigurationValidator";

        /**
         * Scans the configuration package the way the application entry point does and returns the bean
         * definitions the scan registered.
         *
         * <p>The context is never refreshed. Scanning registers definitions and evaluates the profile
         * condition on its own, so the question "would the application's scan find this class" is
         * answerable without creating any of the beans the package declares - several of which need a
         * web security builder, a servlet context or a reachable database.
         *
         * @param profile profile to activate before scanning
         * @return every bean definition name the scan registered
         */
        private List<String> definitionsFoundUnder(final String profile) {
            try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
                final StandardEnvironment environment = new StandardEnvironment();
                environment.setActiveProfiles(profile);
                context.setEnvironment(environment);
                context.scan(ProductionConfigurationValidator.class.getPackageName());
                return List.of(context.getBeanDefinitionNames());
            }
        }

        @Test
        @DisplayName("the guard sits inside the package tree the entry point scans")
        void theGuardSitsInsideTheScannedPackageTree() {
            assertThat(ProductionConfigurationValidator.class.getPackageName())
                    .as("the entry point carries no explicit component-scan base package, so the scan"
                            + " starts at its own package; a guard outside that tree would never be"
                            + " registered and would silently protect nothing")
                    .startsWith(CardDemoApplication.class.getPackageName() + ".");
        }

        @Test
        @DisplayName("a scan with production active registers the guard")
        void aProductionScanRegistersTheGuard() {
            assertThat(definitionsFoundUnder("prod"))
                    .as("component scanning, not an explicit registration, is how the running"
                            + " application picks the guard up")
                    .contains(VALIDATOR_BEAN);
        }

        @Test
        @DisplayName("a scan with the test profile active does not register the guard")
        void aTestScanDoesNotRegisterTheGuard() {
            assertThat(definitionsFoundUnder(TEST_PROFILE))
                    .as("the profile condition is evaluated while scanning, so a non-production start"
                            + " never carries the guard at all")
                    .doesNotContain(VALIDATOR_BEAN);
        }

        @Test
        @DisplayName("a scan with the local profile active does not register the guard")
        void aLocalScanDoesNotRegisterTheGuard() {
            assertThat(definitionsFoundUnder(LOCAL_PROFILE)).doesNotContain(VALIDATOR_BEAN);
        }
    }

    /**
     * Stands in for a bean that would reach infrastructure the moment it is constructed.
     *
     * <p>Counting constructions on a static field rather than inspecting the context afterwards is
     * deliberate: a failed refresh yields no usable context to inspect, so the evidence has to be
     * recorded as it happens. The count is reset before every test.
     */
    static final class InfrastructureStandIn {

        /** Number of times this stand-in has been constructed since the last reset. */
        private static final AtomicInteger CONSTRUCTIONS = new AtomicInteger();

        /** Records one construction. */
        InfrastructureStandIn() {
            CONSTRUCTIONS.incrementAndGet();
        }

        /** @return how many times the stand-in has been constructed since the last reset */
        static int constructions() {
            return CONSTRUCTIONS.get();
        }

        /** Forgets every earlier construction. */
        static void forget() {
            CONSTRUCTIONS.set(0);
        }

        /** Registers the stand-in as an ordinary eager singleton. */
        @Configuration(proxyBeanMethods = false)
        static final class Registration {

            /** Creates the registration. */
            Registration() {
            }

            /** @return the stand-in, constructed during the singleton phase of a refresh */
            @Bean
            InfrastructureStandIn infrastructureStandIn() {
                return new InfrastructureStandIn();
            }
        }
    }
}
