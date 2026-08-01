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
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.PropertySourcesPropertyResolver;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

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

    /**
     * The viewer address key. No shipped document may declare it: the browser asset bundle that would
     * render an interactive page is excluded from the starter in {@code pom.xml}, so an address for it
     * would describe a page that answers not-found. See {@code docs/decision-log.md} DL-088.
     */
    private static final String KEY_SWAGGER_UI_PATH = "springdoc.swagger-ui.path";

    /**
     * The viewer bundle-version key. No shipped document may declare it, and not only because the page
     * is absent: this build defines no such property and filters no resource, so a document naming one
     * would ship the unresolved build token verbatim as its value.
     */
    private static final String KEY_SWAGGER_UI_VERSION = "springdoc.swagger-ui.version";

    /** A viewer display option; one of the keys that would describe a page that cannot render. */
    private static final String KEY_SWAGGER_UI_OPERATIONS_SORTER =
            "springdoc.swagger-ui.operations-sorter";

    /** The second viewer display option, asserted for the same reason. */
    private static final String KEY_SWAGGER_UI_TAGS_SORTER = "springdoc.swagger-ui.tags-sorter";

    /**
     * Matches a Maven resource-filtering token used as a property value, anywhere in the value.
     *
     * <p>An environment reference of the {@code ${NAME}} form is legitimate and deliberate throughout
     * these documents; a {@code @name@} token is not, because this build declares no resource-filtering
     * block at all and would therefore ship the token itself as the value.
     */
    private static final String UNRESOLVED_BUILD_TOKEN = ".*@[A-Za-z0-9._-]+@.*";

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

    /** The destination queue for the online-to-batch job-submission bridge. */
    private static final String KEY_JOB_SUBMISSION_QUEUE =
            "carddemo.aws.sqs.job-submission-queue";

    /** The strategy applied when the destination queue cannot be resolved. */
    private static final String KEY_QUEUE_NOT_FOUND_STRATEGY =
            "spring.cloud.aws.sqs.queue-not-found-strategy";

    /** The queue endpoint, declared by the local overlay alongside the inherited strategy. */
    private static final String KEY_SQS_ENDPOINT = "spring.cloud.aws.sqs.endpoint";

    /** A withdrawn key: the record width, now a constant the publisher enforces. */
    private static final String KEY_RECORD_LENGTH = "carddemo.aws.sqs.record-length";

    /** A withdrawn key: the failure tolerance, now the publisher's own structure. */
    private static final String KEY_FAIL_ON_ERROR = "carddemo.aws.sqs.fail-on-error";

    /** The migration location every profile reads, production included. */
    private static final String KEY_FLYWAY_LOCATIONS = "spring.flyway.locations";

    /** The location carrying the schema and index migrations. */
    private static final String MIGRATION_LOCATION = "classpath:db/migration";

    /** The location reserved for reference rows and sign-on identities, which production never lists. */
    private static final String SEED_LOCATION = "classpath:db/seed";

    /** Class-path folder behind {@link #MIGRATION_LOCATION}, as a resource pattern reads it. */
    private static final String MIGRATION_FOLDER = "db/migration";

    /** Class-path folder behind {@link #SEED_LOCATION}. */
    private static final String SEED_FOLDER = "db/seed";

    /**
     * How a shipped document states the highest version a migration currently reaches.
     *
     * <p>The version number is appended by the assertion from the delivered scripts rather than
     * written here, so the expectation is the delivered state and not a second copy of the claim.
     */
    private static final String DELIVERED_VERSION_CLAIM = "currently ends at V";

    /** The legacy transient-data queue's name, carrying the suffix the queue service requires. */
    private static final String EXPECTED_JOB_SUBMISSION_QUEUE = "JOBS.fifo";

    /** The only strategy that keeps queue provisioning outside the application. */
    private static final String EXPECTED_QUEUE_NOT_FOUND_STRATEGY = "FAIL";

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
        @DisplayName("reopens the interface description, and reopens no viewer because none can render")
        void reopensTheDescriptionAndNoViewer() {
            assertThat(text(LOCAL, KEY_API_DOCS_ENABLED)).isEqualTo("true");
            assertThat(properties(LOCAL))
                    .as("the browser asset bundle is excluded from the starter, so a viewer switch "
                            + "reopened here would advertise an address with no assets to serve")
                    .doesNotContainKey(KEY_SWAGGER_UI_ENABLED);
            assertThat(text(SHARED, KEY_SWAGGER_UI_ENABLED))
                    .as("and the value this overlay therefore inherits is the closed one")
                    .isEqualTo("false");
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
        @DisplayName("the baseline states the actuator base path and the description path")
        void theBaselineStatesEveryResolvedPath() {
            assertThat(text(SHARED, "management.endpoints.web.base-path"))
                    .isEqualTo(EXPECTED_ACTUATOR_BASE_PATH);
            assertThat(text(SHARED, "springdoc.api-docs.path")).isEqualTo(EXPECTED_API_DOCS_PATH);
        }

        @ParameterizedTest(name = "{0} does not move the description path")
        @ValueSource(strings = {LOCAL, PRODUCTION, TEST})
        @DisplayName("no overlay moves a path a sibling file or a switch depends on")
        void noOverlayMovesAResolvedPath(final String profileDocument) {
            assertThat(properties(profileDocument))
                    .as("a moved path would leave the reopened description answering not-found "
                            + "while the baseline still named the old address")
                    .doesNotContainKey("springdoc.api-docs.path");
        }
    }

    @Nested
    @DisplayName("no document advertises a surface the artefact does not carry")
    final class NoDocumentAdvertisesAnAbsentSurface {

        @ParameterizedTest(name = "{0} addresses no interactive viewer")
        @ValueSource(strings = {SHARED, LOCAL, PRODUCTION, TEST})
        @DisplayName("no shipped document addresses, versions or configures the interactive viewer")
        void noDocumentAddressesTheViewer(final String document) {
            assertThat(properties(document))
                    .as("org.webjars:swagger-ui is excluded from the starter in pom.xml, so each of "
                            + "these keys would describe a page that cannot render")
                    .doesNotContainKey(KEY_SWAGGER_UI_PATH)
                    .doesNotContainKey(KEY_SWAGGER_UI_VERSION)
                    .doesNotContainKey(KEY_SWAGGER_UI_OPERATIONS_SORTER)
                    .doesNotContainKey(KEY_SWAGGER_UI_TAGS_SORTER);
        }

        @ParameterizedTest(name = "{0} carries no unresolved build token as a value")
        @ValueSource(strings = {SHARED, LOCAL, PRODUCTION, TEST})
        @DisplayName("no shipped value is a build token, which nothing in this build would substitute")
        void noDocumentCarriesAnUnresolvedBuildToken(final String document) {
            properties(document).forEach((key, value) -> assertThat(String.valueOf(value))
                    .as("%s declares %s, and this build filters no resource, so a @name@ token "
                            + "would reach the artefact verbatim as the value", document, key)
                    .doesNotMatch(UNRESOLVED_BUILD_TOKEN));
        }

        @Test
        @DisplayName("the viewer switch is held closed in every main profile, never left to a default")
        void theViewerSwitchIsNeverLeftToTheLibraryDefault() {
            assertThat(text(SHARED, KEY_SWAGGER_UI_ENABLED))
                    .as("the library's own default is true, so an unstated switch would route an "
                            + "address whose assets were excluded from the build")
                    .isEqualTo("false");
            assertThat(text(PRODUCTION, KEY_SWAGGER_UI_ENABLED)).isEqualTo("false");
        }
    }

    /**
     * Holds every declared request-authorization setting to a consumer that reads it.
     *
     * <p>A review finding recorded that this block published a token issuer, a token lifetime and a
     * mandatory-transport rule while no binder, provider, filter or channel rule consumed any of them - so
     * the settings described a posture the running system did not take, and the framework's own generated
     * user was the actual authentication mechanism. The repair was to build those consumers. What these
     * assertions prevent is the reverse drift: a key added here later with nothing reading it, or a key a
     * consumer requires going undeclared where it is required.</p>
     *
     * <p>The bound set is read from the property class itself rather than restated, so the two cannot be
     * edited apart.</p>
     */
    @Nested
    @DisplayName("every declared security setting has a consumer that reads it")
    final class EverySecuritySettingHasAConsumer {

        /** Keys the token settings class binds, relative to its own prefix. */
        private static final List<String> BOUND_TOKEN_KEYS = List.of("secret", "issuer", "expiration");

        /**
         * Collects the token-setting keys a document declares, relative to the bound prefix.
         *
         * @param document configuration document to inspect
         * @return the relative key names declared there
         */
        private List<String> declaredTokenKeys(final String document) {
            final String prefix = JwtProperties.PREFIX + ".";
            return properties(document).keySet().stream()
                    .filter(key -> key.startsWith(prefix))
                    .map(key -> key.substring(prefix.length()))
                    .sorted()
                    .toList();
        }

        @ParameterizedTest(name = "{0} declares only keys that are bound")
        @ValueSource(strings = {SHARED, LOCAL, PRODUCTION, TEST})
        @DisplayName("declares no token setting the binder does not read, so a key here cannot be a "
                + "statement about behaviour with nothing behind it")
        void declaresNoUnboundTokenSetting(final String document) {
            assertThat(declaredTokenKeys(document))
                    .as("%s declares a %s.* key that %s does not bind", document,
                            JwtProperties.PREFIX, JwtProperties.class.getSimpleName())
                    .isSubsetOf(BOUND_TOKEN_KEYS);
        }

        @Test
        @DisplayName("declares the issuer and the lifetime in the shared baseline, where every profile "
                + "inherits them, and the signing value in none of it")
        void declaresIssuerAndLifetimeSharedAndSecretNowhereShared() {
            assertThat(declaredTokenKeys(SHARED)).contains("issuer", "expiration");
            assertThat(properties(SHARED)).doesNotContainKey(KEY_JWT_SECRET);
        }

        @ParameterizedTest(name = "{0} supplies a signing value")
        @ValueSource(strings = {LOCAL, PRODUCTION, TEST})
        @DisplayName("supplies the signing value in every profile that can start, because a chain with "
                + "nothing to verify tokens with must not reach a running state")
        void everyStartableProfileSuppliesASigningValue(final String document) {
            assertThat(properties(document))
                    .as("%s must supply %s or the context cannot start", document, KEY_JWT_SECRET)
                    .containsKey(KEY_JWT_SECRET);
        }

        @Test
        @DisplayName("resolves a usable lifetime for each startable profile, so the bound duration is a "
                + "duration rather than a token that survived unresolved")
        void resolvesAUsableLifetimeEverywhere() {
            for (final String overlay : List.of(LOCAL, PRODUCTION, TEST)) {
                final String lifetime =
                        resolvedAcrossSharedThen(overlay, JwtProperties.PREFIX + ".expiration");

                assertThat(Duration.parse(lifetime))
                        .as("%s resolves an unusable token lifetime", overlay)
                        .isPositive();
            }
        }

        @ParameterizedTest(name = "{0} states a transport posture")
        @ValueSource(strings = {SHARED, LOCAL, PRODUCTION, TEST})
        @DisplayName("states the transport rule explicitly in every document, because the chain reads it "
                + "with no default of its own and an absent value would stop the start")
        void everyDocumentStatesTheTransportRule(final String document) {
            assertThat(properties(document))
                    .as("%s must state %s rather than leave it to be inferred", document,
                            KEY_REQUIRE_HTTPS)
                    .containsKey(KEY_REQUIRE_HTTPS);
        }

        @Test
        @DisplayName("requires transport security wherever the deployment is not a loopback one, and "
                + "relaxes it only in the two profiles that are")
        void requiresTransportSecurityExceptOnLoopback() {
            assertThat(resolvedIn(SHARED, KEY_REQUIRE_HTTPS)).isEqualTo("true");
            assertThat(resolvedAcrossSharedThen(PRODUCTION, KEY_REQUIRE_HTTPS)).isEqualTo("true");
            assertThat(resolvedAcrossSharedThen(LOCAL, KEY_REQUIRE_HTTPS)).isEqualTo("false");
            assertThat(resolvedAcrossSharedThen(TEST, KEY_REQUIRE_HTTPS)).isEqualTo("false");
        }

        @Test
        @DisplayName("keeps the interface-description switch and the address in agreement with the chain, "
                + "which binds both to decide whether the document may be fetched anonymously")
        void keepsTheDescriptionSwitchBindable() {
            assertThat(properties(SHARED))
                    .containsKey(KEY_API_DOCS_ENABLED)
                    .containsKey("springdoc.api-docs.path");
            assertThat(resolvedIn(SHARED, "springdoc.api-docs.path"))
                    .isEqualTo(EXPECTED_API_DOCS_PATH);
        }
    }

    @Nested
    @DisplayName("the job-submission queue is named and resolved as the legacy resource")
    final class TheJobSubmissionQueueIsTheLegacyResource {

        @ParameterizedTest(name = "{0} resolves the queue to its legacy resource name")
        @ValueSource(strings = {SHARED, LOCAL, TEST})
        @DisplayName("every document that fixes the queue resolves it to the legacy name plus the "
                + "required suffix")
        void everyDocumentThatFixesTheQueueResolvesToTheLegacyName(final String document) {
            assertThat(resolvedIn(document, KEY_JOB_SUBMISSION_QUEUE))
                    .as("the queue replaces the transient-data queue named JOBS, and the emulator "
                            + "bootstrap provisions %s; a namespaced name here would be valid, would "
                            + "name nothing that exists, and would be created silently were the "
                            + "not-found strategy left at its library default",
                            EXPECTED_JOB_SUBMISSION_QUEUE)
                    .isEqualTo(EXPECTED_JOB_SUBMISSION_QUEUE);
        }

        @Test
        @DisplayName("production fixes no queue name, resolving it from the environment with no "
                + "fallback")
        void productionResolvesTheQueueFromTheEnvironment() {
            assertThat(text(PRODUCTION, KEY_JOB_SUBMISSION_QUEUE))
                    .as("a deployment names its own queue; a default here would be a placeholder "
                            + "that started cleanly and published nowhere")
                    .doesNotContain(EXPECTED_JOB_SUBMISSION_QUEUE)
                    .contains("${");
        }

        @Test
        @DisplayName("the shared baseline refuses to create a queue it cannot find")
        void theSharedBaselineRefusesToCreateAMissingQueue() {
            assertThat(text(SHARED, KEY_QUEUE_NOT_FOUND_STRATEGY))
                    .as("the messaging library leaves this unset and its template then defaults to "
                            + "creating the queue, so omitting the key is not neutral: a valid but "
                            + "wrong name would be created on first publish and the submission would "
                            + "sit in a queue nothing consumes. The legacy queue pre-existed first "
                            + "use, so provisioning belongs outside the application")
                    .isEqualTo(EXPECTED_QUEUE_NOT_FOUND_STRATEGY);
        }

        @Test
        @DisplayName("the local overlay's own queue settings do not displace the inherited strategy")
        void theLocalOverlayDoesNotDisplaceTheInheritedStrategy() {
            assertThat(properties(LOCAL))
                    .as("the overlay declares an endpoint under the same parent node as the "
                            + "strategy, and the strategy must be inherited rather than restated")
                    .containsKey(KEY_SQS_ENDPOINT)
                    .doesNotContainKey(KEY_QUEUE_NOT_FOUND_STRATEGY);

            assertThat(resolvedAcrossSharedThen(LOCAL, KEY_QUEUE_NOT_FOUND_STRATEGY))
                    .as("documents are flattened to individual keys before they become property "
                            + "sources, so a sibling key in the overlay cannot shadow this one; were "
                            + "the parent node replaced wholesale instead, this would resolve to null "
                            + "and the effective strategy would silently revert to creating queues")
                    .isEqualTo(EXPECTED_QUEUE_NOT_FOUND_STRATEGY);

            assertThat(resolvedAcrossSharedThen(LOCAL, KEY_SQS_ENDPOINT))
                    .as("the overlay's own value must still win where it declares one")
                    .isEqualTo(resolvedIn(LOCAL, KEY_SQS_ENDPOINT));
        }

        @ParameterizedTest(name = "{0} declares neither inert queue key")
        @ValueSource(strings = {SHARED, LOCAL, PRODUCTION, TEST})
        @DisplayName("no document re-declares the record width or the failure tolerance, because "
                + "nothing binds them and both are enforced in code")
        void noDocumentReDeclaresTheInertQueueKeys(final String document) {
            assertThat(properties(document))
                    .as("both values are fixed by the legacy queue definition rather than chosen by "
                            + "a deployment. Declared here they read as adjustable while nothing "
                            + "reads them, and binding them to a validator admitting only one value "
                            + "would relocate that pretence rather than remove it. The width is the "
                            + "constant the publisher checks each card against; the tolerance is the "
                            + "publisher catching and reporting instead of rethrowing")
                    .doesNotContainKey(KEY_RECORD_LENGTH)
                    .doesNotContainKey(KEY_FAIL_ON_ERROR);
        }
    }

    /**
     * Holds the documented migration set to the migration set that ships.
     *
     * <p>Two separate things are asserted here and they fail for different reasons.</p>
     *
     * <p>The first is the separation itself: reference rows and sign-on identities live in a location
     * production does not list, so a production migration cannot inherit them. That is structural rather
     * than conditional - there is no flag to leave in the wrong position - and it is asserted against the
     * merged environment as well as against each document, because inheritance is what a running
     * application resolves and a per-document reading cannot answer an inheritance question.</p>
     *
     * <p>The second is truthfulness. The documents previously described the seed location as supplying
     * sample rows and sign-on identities and referred to a migration reaching a version the delivered
     * scripts do not reach: the location is declared and carries no script, so a migration ends earlier
     * than the text implied. Correcting the text is not durable on its own, because the correction
     * becomes wrong again the moment a script is added - which is the point at which nobody is reading
     * these comments. So the claim is asserted against the delivered scripts rather than against a
     * second copy of itself: {@link #everyDocumentCitesTheHighestDeliveredVersion()} reads the highest
     * version present under either location and requires both documents to cite that number, so adding
     * a migration fails the build until the text catches up.</p>
     */
    @Nested
    @DisplayName("the documented migration set is the migration set that ships")
    final class TheDocumentedMigrationSetIsTheDeliveredOne {

        @Test
        @DisplayName("the shared baseline lists the schema location alone, so no profile inherits the "
                + "seeds by forgetting to exclude them")
        void theSharedBaselineListsTheSchemaLocationAlone() {
            assertThat(text(SHARED, KEY_FLYWAY_LOCATIONS))
                    .as("the seeds are excluded by not being listed rather than by being switched off, "
                            + "which is what makes their exclusion survive an overlay that copies this "
                            + "block and edits one line of it")
                    .isEqualTo(MIGRATION_LOCATION)
                    .doesNotContain(SEED_LOCATION);
        }

        @Test
        @DisplayName("the production overlay restates the schema location rather than relying on "
                + "inheritance, and resolves without the seeds even when layered")
        void theProductionOverlayNeverReachesTheSeeds() {
            assertThat(text(PRODUCTION, KEY_FLYWAY_LOCATIONS))
                    .isEqualTo(MIGRATION_LOCATION);

            assertThat(resolvedAcrossSharedThen(PRODUCTION, KEY_FLYWAY_LOCATIONS))
                    .as("the resolved value is what the running application migrates from; a baseline "
                            + "that had listed the seeds would reach production through this merge no "
                            + "matter what the overlay said about them")
                    .isEqualTo(MIGRATION_LOCATION)
                    .doesNotContain(SEED_LOCATION);
        }

        @ParameterizedTest(name = "{0} adds the seed location for itself")
        @ValueSource(strings = {LOCAL, TEST})
        @DisplayName("the two profiles that need reference rows add the seed location themselves, "
                + "keeping the schema location alongside it")
        void theProfilesThatNeedSeedsAddTheLocationThemselves(final String document) {
            assertThat(text(document, KEY_FLYWAY_LOCATIONS))
                    .as("a seed location without the schema location would seed a database with no "
                            + "tables, so both must be listed rather than one replacing the other")
                    .contains(MIGRATION_LOCATION)
                    .contains(SEED_LOCATION);
        }

        @Test
        @DisplayName("the schema location carries the delivered migrations and the seed location is "
                + "declared while carrying none, which is the state the documents must describe")
        void theDeliveredScriptsAreTheOnesTheDocumentsName() {
            assertThat(versionedScriptsIn(MIGRATION_FOLDER))
                    .as("the schema and its indexes are the delivered set; a script added here without "
                            + "a corresponding edit to the documents would leave them naming a shorter "
                            + "set than ships")
                    .containsExactly("V1__create_schema.sql", "V2__create_indexes.sql");

            assertThat(versionedScriptsIn(SEED_FOLDER))
                    .as("the reference seed is written into a place production already cannot see, "
                            + "because production lists the schema location alone; a seed script added "
                            + "to the schema location instead would reach a production migration")
                    .containsExactly("V3__seed_reference_data.sql");
        }

        @ParameterizedTest(name = "{0} cites the version its migrations actually reach")
        @ValueSource(strings = {SHARED, LOCAL})
        @DisplayName("every document that states how far a migration reaches cites the highest version "
                + "actually delivered, so adding a migration cannot leave the claim stale")
        void everyDocumentCitesTheHighestDeliveredVersion(final String document) {
            int highest = highestDeliveredVersion();

            assertThat(rawTextOf(document))
                    .as("%s must state that a migration currently ends at V%d, because that is the "
                            + "highest version delivered under %s or %s. If a migration was just "
                            + "added, this claim is now stale and the comment that carries it must be "
                            + "updated or removed", document, highest, MIGRATION_FOLDER, SEED_FOLDER)
                    .contains(DELIVERED_VERSION_CLAIM + highest);
        }

        @Test
        @DisplayName("no document describes the seed location as already supplying rows, which is the "
                + "overstatement being removed")
        void noDocumentDescribesTheSeedsAsAlreadySupplied() {
            for (final String document : List.of(SHARED, LOCAL, PRODUCTION, TEST)) {
                assertThat(rawTextOf(document))
                        .as("%s must not describe rows the seed location does not yet carry", document)
                        .doesNotContain("db/seed        sample rows")
                        .doesNotContain("reach V4")
                        .doesNotContain("reaches V4");
            }
        }
    }

    // Providers.

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

    // Helpers.

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
     * Resolves a key the way the running application would, with an overlay layered over the shared
     * baseline.
     *
     * <p>This exists because a per-document assertion cannot answer an inheritance question. Reading
     * the overlay alone shows only what the overlay declares; reading the baseline alone shows only
     * what the baseline declares. What matters for a key declared in one and neighboured by a sibling
     * in the other is what the merged environment yields, and that is what this reproduces: the two
     * documents are added as property sources in the same precedence order the framework uses, the
     * overlay ahead of the baseline, and the key is resolved against the result.
     *
     * <p>The resolver is the framework's own, over the framework's own property sources, so the
     * merge semantics under test are the real ones rather than an imitation of them.
     *
     * <p>Resolution also substitutes placeholders, which is why these assertions compare against a
     * resolved value rather than against declared text. A value written as an environment override
     * with a fallback yields its fallback here, because the only property sources present are the two
     * documents: no environment source is added, so an ambient variable of the same name cannot reach
     * the result and the outcome is the same on every machine. A value written as an override with no
     * fallback cannot resolve at all, which is what makes the production profile's absence of
     * defaults observable rather than merely asserted.
     *
     * @param overlay the profile document, taking precedence
     * @param key     the fully qualified property key
     * @return the resolved value, or {@code null} when neither document declares it
     */
    private static String resolvedAcrossSharedThen(final String overlay, final String key) {
        MutablePropertySources merged = new MutablePropertySources();
        loadDocuments(overlay, new ClassPathResource(overlay)).forEach(merged::addLast);
        loadDocuments(SHARED, new ClassPathResource(SHARED)).forEach(merged::addLast);
        return new PropertySourcesPropertyResolver(merged).getProperty(key);
    }

    /**
     * Resolves a key against one document alone, substituting placeholder fallbacks.
     *
     * <p>Used where the question is what a single document fixes rather than what a layered pair
     * yields. As above, no environment source is added, so a declared fallback is what resolves.
     *
     * @param document the class-path name of the document to read
     * @param key      the fully qualified property key
     * @return the resolved value, or {@code null} when the document does not declare it
     */
    private static String resolvedIn(final String document, final String key) {
        MutablePropertySources sources = new MutablePropertySources();
        loadDocuments(document, new ClassPathResource(document)).forEach(sources::addLast);
        return new PropertySourcesPropertyResolver(sources).getProperty(key);
    }

    /**
     * Returns the versioned migration scripts a class-path folder carries, in name order.
     *
     * <p>Read from the class path rather than from the source tree, so the answer is what a running
     * application would find on its own migration location and not what a directory listing of the
     * repository suggests. A folder that exists and carries no script, and a folder that does not exist
     * at all, both yield an empty list; the distinction is not one a migration can act on.</p>
     *
     * @param folder the class-path folder, such as {@code db/migration}
     * @return the versioned script file names, sorted; never {@code null}
     */
    private static List<String> versionedScriptsIn(final String folder) {
        try {
            Resource[] found = new PathMatchingResourcePatternResolver()
                    .getResources("classpath*:" + folder + "/V*__*.sql");
            return Stream.of(found)
                    .map(Resource::getFilename)
                    .filter(name -> name != null)
                    .sorted()
                    .toList();
        } catch (IOException failure) {
            throw new UncheckedIOException("migration location is unreadable: " + folder, failure);
        }
    }

    /**
     * Returns the highest migration version delivered under either location.
     *
     * <p>Both locations are read because a version number orders the whole migration history rather than
     * one folder of it: a seed script numbered above the schema scripts is the next version a migration
     * reaches, and a claim about how far a migration goes has to account for it.</p>
     *
     * @return the highest delivered version, or zero when no script is delivered
     */
    private static int highestDeliveredVersion() {
        Pattern version = Pattern.compile("^V(\\d+)__");
        return Stream.concat(versionedScriptsIn(MIGRATION_FOLDER).stream(),
                        versionedScriptsIn(SEED_FOLDER).stream())
                .map(version::matcher)
                .filter(Matcher::find)
                .map(matcher -> Integer.valueOf(matcher.group(1)))
                .mapToInt(Integer::intValue)
                .max()
                .orElse(0);
    }

    /**
     * Reads a shipped configuration document as text, comments included.
     *
     * <p>Every other helper here reads properties, which is the right level for a question about a
     * value. A question about what a document <em>states</em> cannot be answered that way, because the
     * statement lives in a comment that no property loader retains. Two of these assertions are about
     * exactly that, so they read the shipped bytes.</p>
     *
     * @param document the class-path name of the document to read
     * @return the document's full text
     */
    private static String rawTextOf(final String document) {
        ClassPathResource resource = new ClassPathResource(document);
        if (!resource.exists()) {
            throw new IllegalStateException("shipped configuration document is missing: " + document);
        }
        try {
            return resource.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new UncheckedIOException("shipped configuration document is unreadable: "
                    + document, failure);
        }
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
