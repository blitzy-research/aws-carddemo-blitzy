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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

/**
 * Pins the fail-closed posture of the shipped configuration: that the shared baseline is the
 * production posture rather than a convenient one, that every convenience is opened by the profile
 * that needs it instead of being inherited by every profile, and that the three values without which
 * the application cannot operate are declared in no baseline and defaulted nowhere.
 *
 * <h2>Why this is asserted by a test rather than settled by review</h2>
 *
 * <p>A permissive default is not a defect anyone can see in a diff: adding {@code always} to a health
 * probe or {@code true} to an interface-description switch is a one-word edit that looks like an
 * improvement, and it takes effect on every profile that did not think to override it. The failure
 * mode is silent by construction - nothing breaks, a surface simply becomes reachable - so the only
 * durable protection is a build-time assertion of the shipped values. That is what this class is.
 *
 * <p>It is deliberately stronger protection than a start-up exception would be, because it fails at
 * {@code verify} on the machine that made the edit rather than on the deployment that inherited it.
 *
 * <h2>What is read, and how</h2>
 *
 * <p>The four shipped configuration documents are loaded as property sources from the class path -
 * the same three main documents the runtime loads, plus the test overlay - and their values are
 * asserted directly. Nothing is bound to a configuration-properties type and no application context
 * is started for the value assertions, so the test observes exactly the bytes that ship rather than
 * the result of binding them.
 *
 * <h2>Independent expectations</h2>
 *
 * <p>Every expected value below is a hand-typed literal. No expectation is read out of one document
 * and asserted against another, with one deliberate exception that is itself the claim being made:
 * the production management values are asserted to equal the shared baseline's, because restating
 * them rather than narrowing them is the property under test.
 *
 * <h2>Provenance</h2>
 *
 * <p>The configuration under test derives from the CardDemo COBOL estate at checkout
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. No legacy source text is reproduced here.
 *
 * @since 1.0.0
 */
@DisplayName("Shipped configuration profiles")
final class ConfigurationProfileBaselineTest {

    /** The shared baseline every profile inherits. */
    private static final String SHARED = "application.yml";

    /** The overlay bound to the local Docker Compose stack. */
    private static final String LOCAL = "application-local.yml";

    /** The overlay in which every secret is an environment reference with no fallback. */
    private static final String PRODUCTION = "application-prod.yml";

    /** The overlay bound to Testcontainers-provided endpoints. */
    private static final String TEST = "application-test.yml";

    /** The management surface the baseline publishes: three endpoints, each a cross-file contract. */
    private static final String EXPECTED_CLOSED_EXPOSURE = "health,info,prometheus";

    /** The health-probe detail setting of a closed posture. */
    private static final String EXPECTED_CLOSED_DETAIL = "never";

    /** The health-probe detail setting of a diagnostic posture. */
    private static final String EXPECTED_OPEN_DETAIL = "always";

    /** The actuator base path that the container health check and the scrape job resolve literally. */
    private static final String EXPECTED_ACTUATOR_BASE_PATH = "/actuator";

    /** The published path of the generated interface description. */
    private static final String EXPECTED_API_DOCS_PATH = "/v3/api-docs";

    /** The published path of the interface-description viewer. */
    private static final String EXPECTED_SWAGGER_UI_PATH = "/swagger-ui.html";

    /** The exposure key, spelled once so no test can mistype it into a vacuous pass. */
    private static final String KEY_EXPOSURE = "management.endpoints.web.exposure.include";

    /** The health-probe detail key. */
    private static final String KEY_SHOW_DETAILS = "management.endpoint.health.show-details";

    /** The health-probe component key. */
    private static final String KEY_SHOW_COMPONENTS = "management.endpoint.health.show-components";

    /** The interface-description switch. */
    private static final String KEY_API_DOCS_ENABLED = "springdoc.api-docs.enabled";

    /** The interface-description viewer switch. */
    private static final String KEY_SWAGGER_UI_ENABLED = "springdoc.swagger-ui.enabled";

    /** The transport requirement. */
    private static final String KEY_REQUIRE_HTTPS = "carddemo.security.require-https";

    /** The data source location, which the shared baseline must not supply. */
    private static final String KEY_DATASOURCE_URL = "spring.datasource.url";

    /** The token signing material, which the shared baseline must not supply. */
    private static final String KEY_JWT_SECRET = "carddemo.security.jwt.secret";

    /** The field-encryption key, which the shared baseline must not supply. */
    private static final String KEY_FIELD_ENCRYPTION_KEY =
            "carddemo.security.field-encryption.key";

    /**
     * The three values the application cannot operate without, none of which may be declared or
     * defaulted in the shared baseline.
     *
     * <p>Each one is the reason a profile-less start cannot reach a running state, and each one is a
     * secret or a deployment-specific location that a default would turn into an accident: a data
     * source pointing at a developer's container, signing material that anybody who read the
     * repository could forge a token with, or a key under which regulated identifiers would be sealed
     * irretrievably.
     */
    private static final List<String> REQUIRED_UNDECLARED_KEYS =
            List.of(KEY_DATASOURCE_URL, KEY_JWT_SECRET, KEY_FIELD_ENCRYPTION_KEY);

    /**
     * Management endpoints that must never appear in the shared baseline's exposure list.
     *
     * <p>Each of these describes the running system rather than serving it. The first four enumerate
     * resolved configuration, the bean graph, the request mappings and the applied migrations; the
     * next two enumerate and query every registered meter and let log levels be rewritten at run
     * time; the last three dump memory, dump threads and stop the application.
     */
    private static final List<String> ENDPOINTS_CLOSED_IN_THE_BASELINE = List.of(
            "env", "configprops", "beans", "mappings", "flyway",
            "metrics", "loggers", "heapdump", "threaddump", "shutdown");

    /** Tokens that must not appear as a value anywhere in the shared baseline. */
    private static final List<String> CREDENTIAL_TOKENS = List.of(
            "PASSWORD", "SECRET", "APIKEY", "API-KEY", "PRIVATE-KEY", "BEGIN RSA", "$2A$");

    // ---------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("the shared baseline ships closed")
    final class TheSharedBaselineShipsClosed {

        @Test
        @DisplayName("publishes exactly the three endpoints that a sibling file resolves literally")
        void publishesExactlyThreeEndpoints() {
            assertThat(text(SHARED, KEY_EXPOSURE)).isEqualTo(EXPECTED_CLOSED_EXPOSURE);
        }

        @ParameterizedTest(name = "{0} is not published by the baseline")
        @MethodSource("com.carddemo.config.ConfigurationProfileBaselineTest#closedEndpoints")
        @DisplayName("publishes no endpoint that describes or controls the running system")
        void publishesNoDescribingOrControllingEndpoint(final String endpoint) {
            List<String> published = List.of(text(SHARED, KEY_EXPOSURE).split(","));

            assertThat(published)
                    .as("%s would be inherited by any profile that forgot to narrow the list",
                            endpoint)
                    .doesNotContain(endpoint);
        }

        @Test
        @DisplayName("answers the health probe with an aggregate status and no component detail")
        void answersTheHealthProbeWithAnAggregateStatus() {
            assertThat(text(SHARED, KEY_SHOW_DETAILS)).isEqualTo(EXPECTED_CLOSED_DETAIL);
            assertThat(text(SHARED, KEY_SHOW_COMPONENTS)).isEqualTo(EXPECTED_CLOSED_DETAIL);
        }

        @Test
        @DisplayName("serves neither the interface description nor its viewer")
        void servesNeitherTheDescriptionNorItsViewer() {
            assertThat(text(SHARED, KEY_API_DOCS_ENABLED)).isEqualTo("false");
            assertThat(text(SHARED, KEY_SWAGGER_UI_ENABLED)).isEqualTo("false");
        }

        @Test
        @DisplayName("requires transport security")
        void requiresTransportSecurity() {
            assertThat(text(SHARED, KEY_REQUIRE_HTTPS)).isEqualTo("true");
        }

        @Test
        @DisplayName("publishes no application property through the information endpoint")
        void publishesNoApplicationPropertyThroughInformation() {
            assertThat(text(SHARED, "management.info.env.enabled")).isEqualTo("false");
        }

        @Test
        @DisplayName("never authors or alters a table outside a versioned migration")
        void neverAuthorsATableOutsideAMigration() {
            assertThat(text(SHARED, "spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
        }
    }

    @Nested
    @DisplayName("the shared baseline declares no secret and defaults none")
    final class TheSharedBaselineDeclaresNoSecret {

        @ParameterizedTest(name = "{0} is absent from the shared baseline")
        @MethodSource("com.carddemo.config.ConfigurationProfileBaselineTest#requiredUndeclaredKeys")
        @DisplayName("declares none of the three values the application cannot operate without")
        void declaresNoneOfTheThreeRequiredValues(final String key) {
            assertThat(properties(SHARED))
                    .as("%s must be supplied by a profile, never inherited from the baseline", key)
                    .doesNotContainKey(key);
        }

        @Test
        @DisplayName("declares neither a data source user nor a data source credential")
        void declaresNeitherUserNorCredential() {
            assertThat(properties(SHARED))
                    .doesNotContainKey("spring.datasource.username")
                    .doesNotContainKey("spring.datasource.password");
        }

        @ParameterizedTest(name = "no value contains {0}")
        @MethodSource("com.carddemo.config.ConfigurationProfileBaselineTest#credentialTokens")
        @DisplayName("carries no credential-shaped value at all")
        void carriesNoCredentialShapedValue(final String token) {
            List<String> offending = new ArrayList<>();
            properties(SHARED).forEach((key, value) -> {
                if (String.valueOf(value).toUpperCase(Locale.ROOT).contains(token)) {
                    offending.add(key);
                }
            });

            assertThat(offending)
                    .as("a value containing %s in the shared baseline would be inherited"
                            + " by every profile", token)
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("a profile-less start cannot reach a running state")
    final class AProfilelessStartCannotRun {

        @Test
        @DisplayName("fixes the driver but supplies no location, so no data source can be determined")
        void fixesTheDriverButSuppliesNoLocation() {
            assertThat(text(SHARED, "spring.datasource.driver-class-name"))
                    .as("the driver is fixed here so a slicing test cannot fall back to an"
                            + " embedded database")
                    .isEqualTo("org.postgresql.Driver");
            assertThat(properties(SHARED))
                    .as("with a driver named and no location given, resolution fails during refresh")
                    .doesNotContainKey(KEY_DATASOURCE_URL);
        }

        @ParameterizedTest(name = "{0} supplies every required value")
        @ValueSource(strings = {LOCAL, PRODUCTION})
        @DisplayName("every runnable main profile supplies all three required values itself")
        void everyRunnableMainProfileSuppliesAllThree(final String profileDocument) {
            assertThat(properties(profileDocument).keySet())
                    .as("%s must be self-sufficient, since the baseline supplies none of the three",
                            profileDocument)
                    .containsAll(REQUIRED_UNDECLARED_KEYS);
        }

        @Test
        @DisplayName("the test overlay supplies the two secrets and lets the container supply the url")
        void theTestOverlaySuppliesTheTwoSecrets() {
            assertThat(properties(TEST).keySet())
                    .contains(KEY_JWT_SECRET, KEY_FIELD_ENCRYPTION_KEY);
            assertThat(properties(TEST))
                    .as("a Testcontainers-provided location is injected at run time, so declaring"
                            + " one here would shadow the container that was actually started")
                    .doesNotContainKey(KEY_DATASOURCE_URL);
        }
    }

    @Nested
    @DisplayName("the local overlay widens, and states every concession it takes")
    final class TheLocalOverlayWidens {

        @Test
        @DisplayName("publishes every endpoint the baseline publishes, and more")
        void publishesEveryBaselineEndpointAndMore() {
            List<String> baseline = List.of(text(SHARED, KEY_EXPOSURE).split(","));
            List<String> local = List.of(text(LOCAL, KEY_EXPOSURE).split(","));

            assertThat(local)
                    .as("widening must never drop a cross-file contract")
                    .containsAll(baseline);
            assertThat(local).hasSizeGreaterThan(baseline.size());
        }

        @Test
        @DisplayName("reopens the health component detail that the baseline closed")
        void reopensTheHealthComponentDetail() {
            assertThat(text(LOCAL, KEY_SHOW_DETAILS)).isEqualTo(EXPECTED_OPEN_DETAIL);
            assertThat(text(LOCAL, KEY_SHOW_COMPONENTS)).isEqualTo(EXPECTED_OPEN_DETAIL);
        }

        @Test
        @DisplayName("reopens the interface description and its viewer")
        void reopensTheDescriptionAndItsViewer() {
            assertThat(text(LOCAL, KEY_API_DOCS_ENABLED)).isEqualTo("true");
            assertThat(text(LOCAL, KEY_SWAGGER_UI_ENABLED)).isEqualTo("true");
        }

        @Test
        @DisplayName("relaxes the transport requirement explicitly rather than inheriting a relaxation")
        void relaxesTheTransportRequirementExplicitly() {
            assertThat(text(LOCAL, KEY_REQUIRE_HTTPS)).isEqualTo("false");
        }
    }

    @Nested
    @DisplayName("the production overlay restates the closed posture rather than narrowing to it")
    final class TheProductionOverlayRestates {

        @Test
        @DisplayName("publishes the same three endpoints the baseline publishes")
        void publishesTheSameThreeEndpoints() {
            assertThat(text(PRODUCTION, KEY_EXPOSURE))
                    .as("production must agree with the baseline, so a reopened baseline cannot"
                            + " reopen production by inheritance")
                    .isEqualTo(text(SHARED, KEY_EXPOSURE))
                    .isEqualTo(EXPECTED_CLOSED_EXPOSURE);
        }

        @Test
        @DisplayName("restates the closed health probe and the closed description surfaces")
        void restatesTheClosedSurfaces() {
            assertThat(text(PRODUCTION, KEY_SHOW_DETAILS)).isEqualTo(EXPECTED_CLOSED_DETAIL);
            assertThat(text(PRODUCTION, KEY_SHOW_COMPONENTS)).isEqualTo(EXPECTED_CLOSED_DETAIL);
            assertThat(text(PRODUCTION, KEY_API_DOCS_ENABLED)).isEqualTo("false");
            assertThat(text(PRODUCTION, KEY_SWAGGER_UI_ENABLED)).isEqualTo("false");
        }

        @Test
        @DisplayName("requires transport security")
        void requiresTransportSecurity() {
            assertThat(text(PRODUCTION, KEY_REQUIRE_HTTPS)).isEqualTo("true");
        }

        @ParameterizedTest(name = "{0} is an environment reference with no fallback")
        @MethodSource("com.carddemo.config.ConfigurationProfileBaselineTest#requiredUndeclaredKeys")
        @DisplayName("resolves every required value from the environment with no fallback default")
        void resolvesEveryRequiredValueWithNoFallback(final String key) {
            String declared = text(PRODUCTION, key);

            assertThat(declared)
                    .as("%s must be a bare reference; a fallback would silently bind a placeholder"
                            + " instead of failing start-up", key)
                    .matches("\\$\\{[A-Z0-9_]+\\}");
        }

        @Test
        @DisplayName("resolves the data source user and credential from the environment too")
        void resolvesTheUserAndCredentialFromTheEnvironment() {
            assertThat(text(PRODUCTION, "spring.datasource.username"))
                    .matches("\\$\\{[A-Z0-9_]+\\}");
            assertThat(text(PRODUCTION, "spring.datasource.password"))
                    .matches("\\$\\{[A-Z0-9_]+\\}");
        }

        @Test
        @DisplayName("returns no message, binding detail or stack detail on a framework error page")
        void returnsNoDetailOnAFrameworkErrorPage() {
            assertThat(text(PRODUCTION, "server.error.include-message")).isEqualTo("never");
            assertThat(text(PRODUCTION, "server.error.include-binding-errors")).isEqualTo("never");
            assertThat(text(PRODUCTION, "server.error.include-stacktrace")).isEqualTo("never");
        }
    }

    @Nested
    @DisplayName("the test overlay relaxes transport and nothing else")
    final class TheTestOverlayRelaxesTransportOnly {

        @Test
        @DisplayName("relaxes the transport requirement explicitly")
        void relaxesTheTransportRequirementExplicitly() {
            assertThat(text(TEST, KEY_REQUIRE_HTTPS)).isEqualTo("false");
        }

        @ParameterizedTest(name = "does not declare {0}")
        @ValueSource(strings = {KEY_EXPOSURE, KEY_SHOW_DETAILS, KEY_SHOW_COMPONENTS,
                KEY_API_DOCS_ENABLED, KEY_SWAGGER_UI_ENABLED})
        @DisplayName("widens no management or description surface, so tests observe the shipped posture")
        void widensNoManagementOrDescriptionSurface(final String key) {
            assertThat(properties(TEST))
                    .as("%s must stay inherited, otherwise a test asserting the shipped posture"
                            + " would be asserting a test-only one", key)
                    .doesNotContainKey(key);
        }
    }

    @Nested
    @DisplayName("the paths that sibling files resolve literally are declared once and never moved")
    final class ThePathsAreDeclaredOnceAndNeverMoved {

        @Test
        @DisplayName("the baseline states the actuator base path and both description paths")
        void theBaselineStatesEveryResolvedPath() {
            assertThat(text(SHARED, "management.endpoints.web.base-path"))
                    .isEqualTo(EXPECTED_ACTUATOR_BASE_PATH);
            assertThat(text(SHARED, "springdoc.api-docs.path")).isEqualTo(EXPECTED_API_DOCS_PATH);
            assertThat(text(SHARED, "springdoc.swagger-ui.path"))
                    .isEqualTo(EXPECTED_SWAGGER_UI_PATH);
        }

        @ParameterizedTest(name = "{0} does not move the description paths")
        @ValueSource(strings = {LOCAL, PRODUCTION, TEST})
        @DisplayName("no overlay moves a path a sibling file or a switch depends on")
        void noOverlayMovesAResolvedPath(final String profileDocument) {
            assertThat(properties(profileDocument))
                    .as("a moved path would leave the reopened viewer answering not-found")
                    .doesNotContainKey("springdoc.api-docs.path")
                    .doesNotContainKey("springdoc.swagger-ui.path");
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Providers.
    // ---------------------------------------------------------------------------------------------

    /**
     * The management endpoints that must not appear in the shared baseline's exposure list.
     *
     * @return one argument per endpoint
     */
    private static Stream<Arguments> closedEndpoints() {
        return ENDPOINTS_CLOSED_IN_THE_BASELINE.stream().map(Arguments::of);
    }

    /**
     * The three keys the shared baseline must not declare and the production overlay must resolve
     * from the environment with no fallback.
     *
     * @return one argument per key
     */
    private static Stream<Arguments> requiredUndeclaredKeys() {
        return REQUIRED_UNDECLARED_KEYS.stream().map(Arguments::of);
    }

    /**
     * The credential-shaped tokens that must not occur as a value in the shared baseline.
     *
     * @return one argument per token
     */
    private static Stream<Arguments> credentialTokens() {
        return CREDENTIAL_TOKENS.stream().map(Arguments::of);
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers.
    // ---------------------------------------------------------------------------------------------

    /**
     * Reads one property out of a shipped configuration document as text.
     *
     * <p>The value is converted with {@link String#valueOf(Object)} rather than cast, so a document
     * that spells a switch as a YAML boolean and one that spells it as a quoted string are both
     * asserted against the same literal. That keeps an expectation from passing merely because a
     * type changed.
     *
     * @param document the class-path name of the document to read
     * @param key      the fully qualified property key
     * @return the property's value as text, or the string {@code null} when it is not declared
     */
    private static String text(final String document, final String key) {
        return String.valueOf(properties(document).get(key));
    }

    /**
     * Loads a shipped configuration document into a flat map of key to value.
     *
     * <p>Every document of the file is merged in declaration order, so a document that grows a second
     * YAML document later is still read in full rather than silently truncated to its first.
     *
     * @param document the class-path name of the document to load
     * @return every declared property, flattened; never {@code null}
     */
    private static Map<String, Object> properties(final String document) {
        ClassPathResource resource = new ClassPathResource(document);
        if (!resource.exists()) {
            throw new IllegalStateException("shipped configuration document is missing: " + document);
        }
        Map<String, Object> flattened = new LinkedHashMap<>();
        for (PropertySource<?> source : loadDocuments(document, resource)) {
            if (source instanceof EnumerablePropertySource<?> enumerable) {
                for (String name : enumerable.getPropertyNames()) {
                    flattened.put(name, enumerable.getProperty(name));
                }
            }
        }
        return flattened;
    }

    /**
     * Loads every YAML document of a class-path resource as a property source.
     *
     * @param document the class-path name, used only as the source name
     * @param resource the resolved resource
     * @return one property source per YAML document
     */
    private static List<PropertySource<?>> loadDocuments(final String document,
                                                         final ClassPathResource resource) {
        try {
            return new YamlPropertySourceLoader().load(document, resource);
        } catch (IOException failure) {
            throw new UncheckedIOException("shipped configuration document is unreadable: "
                    + document, failure);
        }
    }

    /**
     * Guards the helper itself, so a silent loading failure cannot make every other assertion vacuous.
     *
     * <p>Every test above asserts either that a key holds a value or that a key is absent. A loader
     * that quietly returned nothing would satisfy every absence assertion and would turn each value
     * assertion into a comparison against the string {@code null}, which the value assertions would
     * catch - but only for the keys they happen to name. This test states the precondition directly:
     * all four documents load, and each carries a substantial number of properties.
     */
    @Test
    @DisplayName("all four shipped documents load and none is empty")
    void allFourShippedDocumentsLoadAndNoneIsEmpty() {
        Set<String> documents = Set.of(SHARED, LOCAL, PRODUCTION, TEST);

        assertThat(documents).hasSize(4);
        documents.forEach(document -> assertThat(properties(document))
                .as("%s must load with properties, otherwise every assertion over it is vacuous",
                        document)
                .isNotEmpty()
                .hasSizeGreaterThan(5));
    }
}
