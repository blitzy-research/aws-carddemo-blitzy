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
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.PropertySourcesPropertyResolver;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import com.carddemo.service.SensitiveFieldEncryptionService;
import com.carddemo.util.SensitiveFieldCodec;

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
 * <h2>Why a document is resolved by its PHYSICAL copy and never by its bare class-path name</h2>
 *
 * <p>{@code application-test.yml} exists twice, and both copies are named deliverables:
 * {@code src/main/resources/application-test.yml} is packaged inside the artefact and is the only
 * definition of the profile outside a suite run, while {@code src/test/resources/application-test.yml}
 * exists only while the suite runs and specialises it. The test class path places
 * {@code target/test-classes} AHEAD of {@code target/classes}, so a lookup by bare name -
 * {@code new ClassPathResource("application-test.yml")} - answers with the suite overlay and the
 * packaged deliverable is never read at all. A suite that asserted through such a lookup would report
 * the overlay's posture as though it were the artefact's, which is false assurance rather than
 * coverage: an edit to the packaged document could not fail any assertion.
 *
 * <p>Every helper here therefore resolves {@code classpath*:} - which yields EVERY copy - and selects
 * one by origin: the copy whose location carries {@link #SUITE_ORIGIN} is the suite overlay, and any
 * other copy is the packaged document. The four profile constants always name the PACKAGED document,
 * so every assertion in this class is an assertion about what ships. The overlay is asserted
 * separately, by {@link TheTestProfileExistsTwiceAndBothCopiesAreAsserted}, which also pins the
 * precedence itself so the duplicate name cannot silently start hiding a deliverable again.
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

    /**
     * Verbosity key of the framework's own job launcher.
     *
     * <p>Named here as a literal because that is what makes this file an independent oracle: the category
     * is a third-party class name, and reading it from the class under test would let a silent rename
     * disable the assertion instead of failing it.
     */
    private static final String JOB_LAUNCHER_LOG_CATEGORY =
            "logging.level.org.springframework.batch.core.launch.support.SimpleJobOperator";

    /** The shared baseline every profile inherits. */
    private static final String SHARED = "application.yml";

    /** The one mounted local root through which every file-producing batch job stages output. */
    private static final String KEY_BATCH_STAGING_DIRECTORY =
            "carddemo.batch.staging-directory";

    /**
     * The shared staging root remains environment-overridable, and otherwise resolves to a NAMED
     * SUBDIRECTORY of the JVM temporary root rather than to that root itself.
     *
     * <p>The distinction is the control, not a tidiness preference. The platform temporary directory is
     * world-writable and sticky by design and is shared with the rest of the host, so a staging root
     * placed directly in it cannot be made owner-only without the module changing the mode of a path it
     * does not own. A named subdirectory is created by the module, which is what lets
     * {@code com.carddemo.util.SecureStagedFiles} create it owner-only and keep the generation names
     * within it unreadable to other accounts.
     */
    private static final String EXPECTED_BATCH_STAGING_DIRECTORY =
            "${CARDDEMO_BATCH_STAGING_DIRECTORY:${java.io.tmpdir}/carddemo-batch-staging}";

    /** The overlay bound to the local Docker Compose stack. */
    private static final String LOCAL = "application-local.yml";

    /** The overlay in which every secret is an environment reference with no fallback. */
    private static final String PRODUCTION = "application-prod.yml";

    /**
     * The overlay bound to Testcontainers-provided endpoints.
     *
     * <p>Wherever this constant is used it names the PACKAGED copy - the one under
     * {@code src/main/resources} - because that is the deliverable and the only definition of the
     * profile outside a suite run. The suite-only copy of the same name is reached through
     * {@link #suiteOverlay()} instead.
     */
    private static final String TEST = "application-test.yml";

    /**
     * Class-path location segment that identifies the copy of a document present only while the suite
     * runs. Maven compiles {@code src/test/resources} to {@code target/test-classes}, so this segment
     * distinguishes the suite overlay from the packaged document without either file having to
     * announce which one it is.
     */
    private static final String SUITE_ORIGIN = "/test-classes/";

    /**
     * The management surface the baseline publishes: four endpoints.
     *
     * <p>Three are cross-file contracts - the health probe the image health check reads, the scrape
     * endpoint the Prometheus job resolves, and the build identifier. The fourth, {@code metrics}, is
     * the surface the performance baseline is read from, and it is required rather than optional: a
     * scrape interval cannot resolve a single job run. Publishing it does not open it, because
     * {@code SecurityConfig} authenticates every management sub-path except the health probe and the
     * scrape endpoint.</p>
     */
    private static final String EXPECTED_CLOSED_EXPOSURE = "health,info,metrics,prometheus";

    /** The health-probe detail setting of a closed posture. */
    private static final String EXPECTED_CLOSED_DETAIL = "never";

    /** The local health detail posture: diagnostic only after authentication. */
    private static final String EXPECTED_AUTHORIZED_DETAIL = "when-authorized";

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

    /** Whether the metrics scrape endpoint answers a collector that presents no credential. */
    private static final String KEY_ANONYMOUS_SCRAPE = "carddemo.security.anonymous-metrics-scrape";

    /** The exact-origin browser allow-list, empty in the shipped posture. */
    private static final String KEY_CORS_ALLOWED_ORIGINS =
            WebMvcConfig.CORS_ALLOWED_ORIGINS_PROPERTY;

    /** The generic request-body ceiling enforced by the MVC request-body advice. */
    private static final String KEY_MAX_REQUEST_BODY_SIZE =
            WebMvcConfig.MAX_REQUEST_BODY_SIZE_PROPERTY;

    /** The combined request-line and header-block ceiling. */
    private static final String KEY_MAX_REQUEST_HEADER_SIZE =
            "server.max-http-request-header-size";

    /** The form-body ceiling applied by the embedded container. */
    private static final String KEY_MAX_FORM_POST_SIZE =
            "server.tomcat.max-http-form-post-size";

    /** The combined query and form parameter-count ceiling. */
    private static final String KEY_MAX_PARAMETER_COUNT =
            "server.tomcat.max-parameter-count";

    /** The maximum rejected-body remainder the container will discard. */
    private static final String KEY_MAX_SWALLOW_SIZE = "server.tomcat.max-swallow-size";

    /** The request-line delivery timeout. */
    private static final String KEY_CONNECTION_TIMEOUT = "server.tomcat.connection-timeout";

    /** Multipart parsing is disabled because the module publishes no upload operation. */
    private static final String KEY_MULTIPART_ENABLED = "spring.servlet.multipart.enabled";

    /** The data source location, which the shared baseline must not supply. */
    private static final String KEY_DATASOURCE_URL = "spring.datasource.url";

    /** The token signing material, which the shared baseline must not supply. */
    private static final String KEY_JWT_SECRET = "carddemo.security.jwt.secret";

    /** The field-encryption key, which the shared baseline must not supply. */
    private static final String KEY_FIELD_ENCRYPTION_KEY =
            "carddemo.security.field-encryption.key";

    /** The destination queue for the online-to-batch job-submission bridge. */
    private static final String KEY_JOB_QUEUE = "carddemo.aws.sqs.job-queue";

    /** The strategy applied when the destination queue cannot be resolved. */
    private static final String KEY_QUEUE_NOT_FOUND_STRATEGY =
            "spring.cloud.aws.sqs.queue-not-found-strategy";

    /** The queue endpoint, declared by the local overlay alongside the inherited strategy. */
    private static final String KEY_SQS_ENDPOINT = "spring.cloud.aws.sqs.endpoint";

    /**
     * The one key that decides whether traces are exported.
     *
     * <p>Boot resolves this before {@code management.tracing.enabled} when deciding whether to create
     * the OTLP exporter at all, which is why it - and not the sampling probability - is what the test
     * profile has to set.
     */
    private static final String KEY_OTLP_EXPORT_ENABLED =
            "management.otlp.tracing.export.enabled";

    /**
     * The sampling probability, which is emphatically NOT an export control.
     *
     * <p>The effective sampler is parent-based over the ratio sampler, so the probability configures
     * only the root decision: a request arriving with an already-sampled trace context is sampled
     * whatever the ratio says, and its spans reach the exporter. The profile documents once claimed
     * this key switched export off; it does not, and this constant exists so a test can pin the
     * distinction rather than leave it to a comment.
     */
    private static final String KEY_TRACING_SAMPLING_PROBABILITY =
            "management.tracing.sampling.probability";

    /**
     * Every AWS endpoint setting the test profile must declare - the global one and one per service.
     *
     * <p>Declaring all four is not redundancy. An absent data-source address fails closed; an absent
     * AWS endpoint does NOT - the SDK resolves the region's real public endpoint and sends the request
     * there on whatever credentials the default chain finds. So each of these has to be present for
     * the corresponding client to be pinned at the emulator, and the global setting is restated per
     * service so that dropping any one of them cannot leave a client addressing a real account.
     */
    private static final List<String> AWS_ENDPOINT_KEYS = List.of(
            "spring.cloud.aws.endpoint",
            "spring.cloud.aws.s3.endpoint",
            KEY_SQS_ENDPOINT,
            "spring.cloud.aws.sns.endpoint");

    /** The host suffix that would prove an endpoint was bound for a real AWS account. */
    private static final String REAL_AWS_HOST_SUFFIX = "amazonaws.com";

    /**
     * The keys the local overlay declares and the packaged test overlay deliberately does not.
     *
     * <p>Local addresses one fixed compose stack, so an address written there is correct. A
     * container's port is ephemeral, so an address written in the test overlay could only be wrong or
     * dangerous - a test that never started a container would connect to the developer's own database
     * and pass. {@code AbstractPostgresIT} publishes these three instead.
     */
    private static final List<String> LOCAL_ONLY_KEYS = List.of(
            KEY_DATASOURCE_URL,
            "spring.datasource.username",
            "spring.datasource.password");

    /**
     * The keys the packaged test overlay declares and the local overlay deliberately does not.
     *
     * <p>The interactive description surface is closed explicitly in a suite run rather than by
     * inheritance, so its absence is provable there. Trace export is switched off in a suite run
     * because there is no collector, while local exports to the one the compose stack runs and so
     * wants the shipped default.
     */
    private static final List<String> TEST_ONLY_KEYS = List.of(
            KEY_SWAGGER_UI_ENABLED,
            KEY_OTLP_EXPORT_ENABLED);

    /** A withdrawn key: the record width, now a constant the publisher enforces. */
    private static final String KEY_RECORD_LENGTH = "carddemo.aws.sqs.record-length";

    /** A withdrawn key: the failure tolerance, now the publisher's own structure. */
    private static final String KEY_FAIL_ON_ERROR = "carddemo.aws.sqs.fail-on-error";

    /** The migration location every profile reads, production included. */
    private static final String KEY_FLYWAY_LOCATIONS = "spring.flyway.locations";

    /** The version ceiling that excludes the seeds from production. */
    private static final String KEY_FLYWAY_TARGET = "spring.flyway.target";

    /** Whether the batch framework may create its own metadata tables at start-up. */
    private static final String KEY_BATCH_INITIALIZE_SCHEMA = "spring.batch.jdbc.initialize-schema";

    /**
     * The one location all four delivered migrations share, which EVERY profile declares.
     *
     * <p>The delivered directory is flat: {@code V1} through {@code V4} sit side by side in it and no
     * subdirectory exists. A directory-scoped location list therefore cannot separate the two seeds
     * from the two schema scripts, which is why {@link #PRODUCTION_VERSION_CEILING} rather than the
     * location list is the control that keeps the seeds out of production. Two earlier revisions
     * reached for profile-scoped locations and had to withdraw them; the flat directory is the
     * load-bearing fact, and the version ceiling is the only control that respects it.
     */
    private static final String MIGRATION_LOCATION = "classpath:db/migration";

    /** Class-path folder behind {@link #MIGRATION_LOCATION}, as a resource pattern reads it. */
    private static final String MIGRATION_FOLDER = "db/migration";

    /** The production version ceiling, above which a script is never resolved. */
    private static final String PRODUCTION_VERSION_CEILING = "2";

    /**
     * The ceiling the two seeding profiles raise for themselves so the seed scripts apply.
     *
     * <p>The shared baseline declares the restrictive ceiling, so a profile that says nothing about
     * seeding inherits a schema-only migration; a profile that wants the seeds lifts the ceiling to
     * this value explicitly. The direction of that default is the control: a forgotten override then
     * withholds seed data rather than depositing seeded credentials unnoticed.</p>
     */
    private static final String SEEDING_TARGET = "latest";

    /**
     * Every migration this module delivers, in the order a migration applies them.
     *
     * <p>All four sit flat in one shared location and are told apart by VERSION alone. The first two
     * are at or below the production ceiling and reach every profile; the last two are above it and
     * reach local and test only. AAP 0.3.1 and 0.4.2 name exactly these four.
     */
    private static final List<String> DELIVERED_MIGRATIONS = List.of(
            "V1__create_schema.sql",
            "V2__create_indexes.sql",
            "V3__seed_reference_data.sql",
            "V4__seed_user_security.sql");

    /** The delivered migrations production applies, being those in the schema location. */
    private static final List<String> SCHEMA_MIGRATIONS = List.of(
            "V1__create_schema.sql",
            "V2__create_indexes.sql");

    /** The delivered migrations only local and test apply, being those in the seed location. */
    private static final List<String> SEED_MIGRATIONS = List.of(
            "V3__seed_reference_data.sql",
            "V4__seed_user_security.sql");

    /** The seed migration whose customer rows carry sealed government-issued identifiers. */
    private static final String SEED_REFERENCE_DATA = "V3__seed_reference_data.sql";

    /** The marker every protected value carries, matching the codec's envelope prefix. */
    private static final String PROTECTED_VALUE_MARKER = SensitiveFieldCodec.ENVELOPE_PREFIX;

    /** Customer rows the seed loads, each of which must carry one sealed identifier. */
    private static final int SEEDED_CUSTOMER_ROWS = 50;

    /** Width of the government-issued identifier in the legacy customer record. */
    private static final int SEALED_IDENTIFIER_WIDTH = 20;

    /**
     * Width of the payload each seeded literal actually seals.
     *
     * <p>Not the identifier's width. Every value in that column is sealed <em>bound to the column</em>,
     * so the payload is the column's binding name, the one-character unit separator, and the identifier.
     * The binding is what lets the application read the seed at all: every reader of the column opens it
     * through the field-bound reveal, which refuses an envelope sealed for another column or for none, so
     * an unbound literal authenticates under the key and is then refused on every request that reads a
     * customer. Deriving the width from the binding name keeps this file's pattern correct without
     * restating a figure.</p>
     */
    private static final int SEALED_IDENTIFIER_PAYLOAD_WIDTH =
            SensitiveFieldEncryptionService.CUSTOMER_GOVT_ISSUED_ID_FIELD.length()
                    + 1 + SEALED_IDENTIFIER_WIDTH;

    /** Decoded length the field-encryption key must have, as AES-256 requires. */
    private static final int FIELD_ENCRYPTION_KEY_BYTES = SensitiveFieldCodec.KEY_LENGTH_BYTES;

    /**
     * A complete sealed identifier literal, as the seed embeds one.
     *
     * <p>The body length is taken from the codec rather than written down, so the pattern stays correct
     * if the envelope layout ever changes and stops matching if a literal is truncated. Counting
     * complete literals rather than bare occurrences of the marker matters: the seed also names the
     * marker in a comment and uses it in its own self-check, and both would inflate a naive count.</p>
     */
    private static final Pattern SEALED_IDENTIFIER_LITERAL = Pattern.compile(
            "'" + Pattern.quote(PROTECTED_VALUE_MARKER) + "[A-Za-z0-9+/=]{"
                    + (SensitiveFieldCodec.envelopeLengthFor(SEALED_IDENTIFIER_PAYLOAD_WIDTH)
                            - SensitiveFieldCodec.ENVELOPE_PREFIX.length()) + "}'");

    /** A quoted run of digits at the legacy identifier width, which no seeded row may carry. */
    private static final Pattern CLEARTEXT_IDENTIFIER_LITERAL =
            Pattern.compile("'\\d{" + SEALED_IDENTIFIER_WIDTH + "}'");

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
     * <p>Each of these describes or controls the running system rather than serving it. The first five
     * enumerate resolved configuration, the bean graph, the request mappings and the applied
     * migrations, and let log levels be rewritten at run time; the last three dump memory, dump
     * threads and stop the application.
     *
     * <p>{@code metrics} was on this list and has been REMOVED from it, because it is required to be
     * published. The reasoning that put it here - that it enumerates every registered meter and so
     * describes the shape of the running system - was true but not decisive: the endpoint is
     * authenticated, and withholding it left the performance baseline with no surface to be read
     * from. Its presence is now asserted positively through the expected exposure list instead.
     */
    private static final List<String> ENDPOINTS_CLOSED_IN_THE_BASELINE = List.of(
            "env", "configprops", "beans", "mappings", "flyway",
            "loggers", "heapdump", "threaddump", "shutdown");

    /** Tokens that must not appear as a value anywhere in the shared baseline. */
    private static final List<String> CREDENTIAL_TOKENS = List.of(
            "PASSWORD", "SECRET", "APIKEY", "API-KEY", "PRIVATE-KEY", "BEGIN RSA", "$2A$");

    @Nested
    @DisplayName("the shared baseline ships closed")
    final class TheSharedBaselineShipsClosed {

        @Test
        @DisplayName("publishes exactly the four endpoints that a sibling file or this module's own "
                + "performance baseline resolves")
        void publishesExactlyFourEndpoints() {
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
        @DisplayName("closes anonymous metrics scraping, so a profile that says nothing about it "
                + "inherits a closed endpoint rather than an open one")
        void closesAnonymousMetricsScraping() {
            assertThat(text(SHARED, KEY_ANONYMOUS_SCRAPE)).isEqualTo("false");
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

        @Test
        @DisplayName("silences the framework's job launcher below the level at which it logs a whole "
                + "parameter map, because this module's launch surface is reached over HTTP")
        void silencesTheFrameworksJobLauncherParameterLine() {
            assertThat(text(SHARED, JOB_LAUNCHER_LOG_CATEGORY))
                    .as("at INFO that class announces every launch with the values a caller sent, which "
                            + "is exactly what every diagnostic this module authors withholds")
                    .isEqualTo("WARN");
        }

        @ParameterizedTest(name = "{0} does not raise the job launcher back to INFO")
        @ValueSource(strings = {LOCAL, TEST, PRODUCTION})
        @DisplayName("pins that category in the shared baseline, so no overlay can reach it by raising "
                + "its parent")
        void noOverlayRaisesTheJobLauncherCategory(final String overlay) {
            assertThat(resolvedAcrossSharedThen(overlay, JOB_LAUNCHER_LOG_CATEGORY))
                    .as("an overlay that named the parent category alone must not affect this one")
                    .isEqualTo("WARN");
        }
    }

    @Nested
    @DisplayName("every profile inherits one bounded HTTP transport posture")
    final class EveryProfileInheritsOneBoundedHttpTransportPosture {

        @Test
        @DisplayName("the shared baseline declares every request ceiling and disables multipart parsing")
        void theSharedBaselineDeclaresEveryRequestCeiling() {
            assertThat(resolvedIn(SHARED, KEY_MAX_REQUEST_HEADER_SIZE)).isEqualTo("8KB");
            assertThat(resolvedIn(SHARED, KEY_MAX_REQUEST_BODY_SIZE)).isEqualTo("64KB");
            assertThat(resolvedIn(SHARED, KEY_MAX_FORM_POST_SIZE)).isEqualTo("64KB");
            assertThat(resolvedIn(SHARED, KEY_MAX_PARAMETER_COUNT)).isEqualTo("64");
            assertThat(resolvedIn(SHARED, KEY_MAX_SWALLOW_SIZE)).isEqualTo("64KB");
            assertThat(resolvedIn(SHARED, KEY_CONNECTION_TIMEOUT)).isEqualTo("10s");
            assertThat(resolvedIn(SHARED, KEY_MULTIPART_ENABLED)).isEqualTo("false");
        }

        @Test
        @DisplayName("the shipped browser policy resolves to an empty exact-origin list")
        void theShippedBrowserPolicyDeniesEveryOrigin() {
            assertThat(resolvedIn(SHARED, KEY_CORS_ALLOWED_ORIGINS)).isEmpty();
        }

        @ParameterizedTest(name = "{0} does not widen a shared request boundary")
        @ValueSource(strings = {LOCAL, PRODUCTION, TEST})
        @DisplayName("profile overlays inherit rather than restate or widen the transport boundaries")
        void profileOverlaysDoNotOverrideTheTransportBoundaries(final String overlay) {
            assertThat(properties(overlay)).doesNotContainKeys(
                    KEY_CORS_ALLOWED_ORIGINS,
                    KEY_MAX_REQUEST_BODY_SIZE,
                    KEY_MAX_REQUEST_HEADER_SIZE,
                    KEY_MAX_FORM_POST_SIZE,
                    KEY_MAX_PARAMETER_COUNT,
                    KEY_MAX_SWALLOW_SIZE,
                    KEY_CONNECTION_TIMEOUT,
                    KEY_MULTIPART_ENABLED);
        }

        @Test
        @DisplayName("the suite-only test overlay also inherits the shared transport boundaries")
        void theSuiteOverlayDoesNotOverrideTheTransportBoundaries() {
            assertThat(propertiesOf(suiteOverlay())).doesNotContainKeys(
                    KEY_CORS_ALLOWED_ORIGINS,
                    KEY_MAX_REQUEST_BODY_SIZE,
                    KEY_MAX_REQUEST_HEADER_SIZE,
                    KEY_MAX_FORM_POST_SIZE,
                    KEY_MAX_PARAMETER_COUNT,
                    KEY_MAX_SWALLOW_SIZE,
                    KEY_CONNECTION_TIMEOUT,
                    KEY_MULTIPART_ENABLED);
        }

        @ParameterizedTest(name = "{0} resolves the same bounded posture")
        @ValueSource(strings = {LOCAL, PRODUCTION, TEST})
        @DisplayName("every runtime profile resolves the shared ceilings unchanged")
        void everyRuntimeProfileResolvesTheSharedCeilings(final String overlay) {
            assertThat(resolvedAcrossSharedThen(overlay, KEY_MAX_REQUEST_HEADER_SIZE))
                    .isEqualTo("8KB");
            assertThat(resolvedAcrossSharedThen(overlay, KEY_MAX_REQUEST_BODY_SIZE))
                    .isEqualTo("64KB");
            assertThat(resolvedAcrossSharedThen(overlay, KEY_MAX_FORM_POST_SIZE))
                    .isEqualTo("64KB");
            assertThat(resolvedAcrossSharedThen(overlay, KEY_MAX_PARAMETER_COUNT))
                    .isEqualTo("64");
            assertThat(resolvedAcrossSharedThen(overlay, KEY_MAX_SWALLOW_SIZE))
                    .isEqualTo("64KB");
            assertThat(resolvedAcrossSharedThen(overlay, KEY_CONNECTION_TIMEOUT))
                    .isEqualTo("10s");
            assertThat(resolvedAcrossSharedThen(overlay, KEY_MULTIPART_ENABLED))
                    .isEqualTo("false");
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
            assertThat(propertiesOf(packaged(TEST)).keySet())
                    .contains(KEY_JWT_SECRET, KEY_FIELD_ENCRYPTION_KEY);
            assertThat(propertiesOf(suiteOverlay()).keySet())
                    .as("the overlay the suite actually loads must supply them too, otherwise the"
                            + " packaged declaration is never the one in force")
                    .contains(KEY_JWT_SECRET, KEY_FIELD_ENCRYPTION_KEY);
        }

        @ParameterizedTest(name = "neither copy of the test profile declares {0}")
        @ValueSource(strings = {KEY_DATASOURCE_URL, "spring.datasource.username",
                "spring.datasource.password"})
        @DisplayName("neither copy of the test profile declares a data source, so a context that was "
                + "not wired to a container fails closed instead of reaching a developer's own server")
        void neitherCopyOfTheTestProfileDeclaresADataSource(final String key) {
            assertThat(propertiesOf(packaged(TEST)))
                    .as("a Testcontainers-provided location is registered at run time by the shared"
                            + " support base class; a declaration here would either shadow the"
                            + " container that was actually started or, worse, answer with a"
                            + " reachable developer address when no container was started at all")
                    .doesNotContainKey(key);
            assertThat(propertiesOf(suiteOverlay()))
                    .as("the overlay the suite loads must fail closed for the same reason, and it is"
                            + " the copy that wins during a suite run")
                    .doesNotContainKey(key);
        }
    }

    @Nested
    @DisplayName("the local overlay takes only the concessions required for local execution")
    final class TheLocalOverlayTakesOnlyRequiredConcessions {

        @Test
        @DisplayName("publishes exactly the four endpoints required by health and Gate 3")
        void publishesOnlyTheBaselineManagementEndpoints() {
            assertThat(text(LOCAL, KEY_EXPOSURE))
                    .as("loopback binding does not justify environment, bean, mapping, migration or "
                            + "writable-logger endpoints")
                    .isEqualTo(EXPECTED_CLOSED_EXPOSURE)
                    .isEqualTo(text(SHARED, KEY_EXPOSURE));
        }

        @Test
        @DisplayName("makes health detail available only to an authenticated diagnostic request")
        void protectsTheHealthComponentDetail() {
            assertThat(text(LOCAL, KEY_SHOW_DETAILS)).isEqualTo(EXPECTED_AUTHORIZED_DETAIL);
            assertThat(text(LOCAL, KEY_SHOW_COMPONENTS)).isEqualTo(EXPECTED_AUTHORIZED_DETAIL);
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

        @Test
        @DisplayName("opens anonymous metrics scraping explicitly, because the Compose collector runs "
                + "on this machine and presents no credential - without which the gate has no data")
        void opensAnonymousMetricsScrapingExplicitly() {
            assertThat(text(LOCAL, KEY_ANONYMOUS_SCRAPE)).isEqualTo("true");
        }
    }

    @Nested
    @DisplayName("the production overlay restates the closed posture rather than narrowing to it")
    final class TheProductionOverlayRestates {

        @Test
        @DisplayName("publishes the same four endpoints the baseline publishes")
        void publishesTheSameFourEndpoints() {
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

        @Test
        @DisplayName("restates the closed scrape posture rather than inheriting it, so reopening the "
                + "shared baseline cannot reopen production by inheritance")
        void restatesTheClosedScrapePosture() {
            assertThat(text(PRODUCTION, KEY_ANONYMOUS_SCRAPE)).isEqualTo("false");
        }

        @ParameterizedTest(name = "{0} is a bare reference to {1}")
        @MethodSource("com.carddemo.config.ConfigurationProfileBaselineTest"
                + "#requiredProductionSettings")
        @DisplayName("declares every required value as the exact bare reference the guard watches")
        void declaresEveryRequiredValueAsTheReferenceTheGuardWatches(final String key,
                final String variable) {
            assertThat(text(PRODUCTION, key))
                    .as("%s must be written as a bare reference to %s. A fallback would put a usable"
                            + " value in the repository, and a different variable name would leave the"
                            + " guard watching a key that nothing supplies", key, variable)
                    .isEqualTo("${" + variable + "}");
        }

        @ParameterizedTest(name = "{0} resolves to nothing usable until {1} is supplied")
        @MethodSource("com.carddemo.config.ConfigurationProfileBaselineTest"
                + "#requiredProductionSettings")
        @DisplayName("resolves every required value to nothing usable until the environment supplies it")
        void resolvesEveryRequiredValueToNothingUsable(final String key, final String variable) {
            String resolved = productionEnvironmentWithNothingSupplied()
                    .resolvePlaceholders("${" + key + "}");

            assertThat(resolved)
                    .as("this is what a running application OBSERVES for %s while %s is unset: the"
                            + " reference survives resolution instead of failing it. Asserting the"
                            + " document's shape alone could never have shown that, which is the whole"
                            + " reason the guard exists", key, variable)
                    .isEqualTo("${" + variable + "}");
        }

        @Test
        @DisplayName("refuses to start when the environment supplies nothing, naming every variable")
        void refusesToStartWhenTheEnvironmentSuppliesNothing() {
            ConfigurableEnvironment environment = productionEnvironmentWithNothingSupplied();

            assertThatExceptionOfType(IllegalStateException.class)
                    .as("an unsupplied production environment must stop the start rather than bind"
                            + " placeholder text into a data source, a key store and a signing secret")
                    .isThrownBy(() -> ProductionConfigurationValidator
                            .validateRequiredSettings(environment))
                    .satisfies(failure -> ProductionConfigurationValidator.REQUIRED_SETTINGS
                            .forEach(setting -> assertThat(failure.getMessage())
                                    .as("the report must name %s and the variable that supplies it",
                                            setting.propertyKey())
                                    .contains(setting.propertyKey())
                                    .contains(setting.environmentVariable())));
        }

        @Test
        @DisplayName("the bare reference on its own fails nothing, which is why the guard is needed")
        void theBareReferenceOnItsOwnFailsNothing() {
            ConfigurableEnvironment environment = productionEnvironmentWithNothingSupplied();

            JwtProperties bound = Binder.get(environment)
                    .bind(JwtProperties.PREFIX, JwtProperties.class)
                    .get();

            assertThat(bound.secret())
                    .as("a configuration-properties binding resolves placeholders leniently, so with"
                            + " no variable supplied the signing secret binds as the text of its own"
                            + " placeholder rather than failing")
                    .isEqualTo("${" + variableFor(KEY_JWT_SECRET) + "}");
            assertThat(bound.hasSecret())
                    .as("the module's own predicate cannot tell the difference either: placeholder"
                            + " text is not blank")
                    .isTrue();

            try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
                assertThat(factory.getValidator().validate(bound))
                        .as("neither can the @NotBlank constraint already declared on the secret."
                                + " This is the falsifiability control for the two assertions above:"
                                + " if a future revision made the bare form fail on its own, this"
                                + " assertion would break and the guard could be reconsidered")
                        .isEmpty();
            }
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

        @Test
        @DisplayName("opens anonymous metrics scraping explicitly, because the suite reads the "
                + "exposition within its own process")
        void opensAnonymousMetricsScrapingExplicitly() {
            assertThat(text(TEST, KEY_ANONYMOUS_SCRAPE)).isEqualTo("true");
        }

        @ParameterizedTest(name = "does not widen {0}")
        @MethodSource("com.carddemo.config.ConfigurationProfileBaselineTest#closedSurfaceExpectations")
        @DisplayName("widens no management or description surface, so tests observe the shipped posture")
        void widensNoManagementOrDescriptionSurface(final String key, final String closedValue) {
            assertThat(resolvedAcrossSharedThen(TEST, key))
                    .as("the packaged test profile may restate %s at the value the baseline closes it"
                            + " to - which it does, so that a widening becomes a conscious edit here -"
                            + " but it must never resolve to anything else, or a test asserting the"
                            + " shipped posture would be asserting a test-only one", key)
                    .isEqualTo(closedValue);

            assertThat(propertiesOf(suiteOverlay()).keySet())
                    .as("the overlay the suite loads must not declare %s at all: it inherits the"
                            + " packaged value, and a second declaration is a second place to widen"
                            + " from", key)
                    .doesNotContain(key);
        }
    }

    @Nested
    @DisplayName("the test profile exists twice, and both copies are asserted")
    final class TheTestProfileExistsTwiceAndBothCopiesAreAsserted {

        /**
         * The keys whose value is a contract shared by both copies rather than a suite convenience.
         *
         * <p>Each of these is spoken by something outside the configuration - the queue service, the
         * publisher, the token binder, the field codec, the migration tool - so the two copies
         * disagreeing on any of them would mean a suite run exercised a different contract from the one
         * the artefact carries. That is precisely the class of divergence a duplicate file name makes
         * easy and a diff makes hard to see.
         */
        private static final List<String> CONTRACTUAL_KEYS = List.of(
                KEY_JOB_QUEUE,
                "carddemo.aws.sqs.message-group-id",
                "carddemo.aws.sns.job-notification-topic",
                "carddemo.aws.s3.batch-staging-bucket",
                "carddemo.aws.endpoint-override",
                "carddemo.security.jwt.issuer",
                KEY_FIELD_ENCRYPTION_KEY,
                KEY_JWT_SECRET,
                KEY_REQUIRE_HTTPS,
                KEY_ANONYMOUS_SCRAPE,
                // A scalar rather than an indexed sequence, because the four delivered scripts sit flat
                // in ONE location. An earlier revision listed [0] and [1] here, which is exactly the
                // trace a returning directory split would leave in this file.
                KEY_FLYWAY_LOCATIONS,
                KEY_FLYWAY_TARGET,
                "spring.jpa.hibernate.ddl-auto");

        @Test
        @DisplayName("carries exactly two physical copies - one packaged, one suite-only - because "
                + "both are named deliverables")
        void carriesExactlyTwoPhysicalCopies() {
            List<Resource> copies = physicalCopiesOf(TEST);

            assertThat(copies)
                    .as("%s is delivered under src/main/resources AND src/test/resources; a third copy"
                            + " would mean a stray resource root, and a single copy would mean one of"
                            + " the two deliverables was deleted", TEST)
                    .hasSize(2);
            assertThat(copies.stream().filter(ConfigurationProfileBaselineTest::isSuiteOverlay))
                    .as("exactly one copy must come from src/test/resources")
                    .hasSize(1);
            assertThat(copies.stream()
                    .filter(resource -> !ConfigurationProfileBaselineTest.isSuiteOverlay(resource)))
                    .as("exactly one copy must be the packaged deliverable, which is what a"
                            + " `test`-profile run of the built artefact loads")
                    .hasSize(1);
        }

        @Test
        @DisplayName("resolves the suite-only copy for a bare class-path name, which is why nothing "
                + "here asks for a document that way")
        void aBareClassPathNameResolvesTheSuiteCopy() {
            String resolvedByName = physicalLocationOf(new ClassPathResource(TEST));

            assertThat(resolvedByName)
                    .as("this is the shadowing itself, pinned rather than assumed: the test class path"
                            + " puts target/test-classes first, so a lookup by name answers with the"
                            + " overlay. Every assertion in this class resolves classpath*: and picks a"
                            + " copy by origin precisely because of this. If this ever stops holding,"
                            + " the ordering changed and the packaged copy would start answering"
                            + " suite-scoped questions")
                    .contains(SUITE_ORIGIN);
            assertThat(resolvedByName)
                    .as("and it is the same file as the copy this class classifies as the overlay, so"
                            + " the two ways of naming it cannot diverge")
                    .isEqualTo(physicalLocationOf(suiteOverlay()));
            assertThat(resolvedByName)
                    .as("which is emphatically NOT the packaged deliverable - the distinction this"
                            + " whole nested class exists to keep visible")
                    .isNotEqualTo(physicalLocationOf(packaged(TEST)));
        }

        @Test
        @DisplayName("loads both copies with properties, so neither is a stub that makes an assertion "
                + "over it vacuous")
        void bothCopiesLoadWithProperties() {
            assertThat(propertiesOf(packaged(TEST)))
                    .as("the packaged copy is the complete definition of the profile")
                    .isNotEmpty()
                    .hasSizeGreaterThan(20);
            assertThat(propertiesOf(suiteOverlay()))
                    .as("the overlay specialises the packaged copy; an empty overlay would mean the"
                            + " suite silently ran on the packaged values alone")
                    .isNotEmpty()
                    .hasSizeGreaterThan(20);
        }

        @ParameterizedTest(name = "both copies agree on {0}")
        @MethodSource("contractualKeys")
        @DisplayName("both copies resolve every contractual value identically, so a suite run "
                + "exercises the contract the artefact carries")
        void bothCopiesAgreeOnEveryContractualValue(final String key) {
            String fromPackaged = resolvedInResource(packaged(TEST), key);
            String fromOverlay = resolvedInResource(suiteOverlay(), key);

            assertThat(fromPackaged)
                    .as("the packaged copy must declare %s: it is the only definition of the profile"
                            + " outside a suite run", key)
                    .isNotNull();
            assertThat(fromOverlay)
                    .as("the overlay must declare %s too, because it is the copy in force during a"
                            + " suite run and an omission here silently swaps the contract", key)
                    .isNotNull();
            assertThat(fromOverlay)
                    .as("%s is a contract rather than a suite convenience, so the two copies must"
                            + " resolve it to the same value", key)
                    .isEqualTo(fromPackaged);
        }

        @Test
        @DisplayName("both copies switch trace export off at the property that switches it off, not at "
                + "the sampling probability, which cannot")
        void bothCopiesDisableTraceExportAtTheExportProperty() {
            for (Resource copy : physicalCopiesOf(TEST)) {
                assertThat(resolvedInResource(copy, KEY_OTLP_EXPORT_ENABLED))
                        .as("%s must set %s to false. Boot resolves this key before"
                                + " management.tracing.enabled when it decides whether to create the"
                                + " OTLP exporter, so setting it here means no exporter bean exists and"
                                + " nothing can open a connection to a collector that a suite run does"
                                + " not have", physicalLocationOf(copy), KEY_OTLP_EXPORT_ENABLED)
                        .isEqualTo("false");
            }
        }

        @Test
        @DisplayName("a zero sampling probability is not mistaken for the export control")
        void aZeroSamplingProbabilityIsNotTheExportControl() {
            Resource packagedCopy = packaged(TEST);

            assertThat(resolvedInResource(packagedCopy, KEY_TRACING_SAMPLING_PROBABILITY))
                    .as("the packaged copy does declare a zero probability, and that is fine as an"
                            + " optimisation - it suppresses ROOT spans and so keeps recording cost off"
                            + " several hundred context refreshes")
                    .isEqualTo("0.0");
            assertThat(resolvedInResource(packagedCopy, KEY_OTLP_EXPORT_ENABLED))
                    .as("but it must NOT be the only thing standing between the suite and a collector."
                            + " The effective sampler is parent-based over the ratio sampler, so a"
                            + " request carrying an already-sampled trace context is sampled whatever"
                            + " the ratio says and its spans reach the exporter. An earlier revision of"
                            + " this profile relied on the probability alone and documented it as"
                            + " disabling export; it does not, and this assertion is what stops that"
                            + " belief coming back")
                    .isEqualTo("false");
        }

        @ParameterizedTest(name = "both copies pin {0} at the emulator")
        @MethodSource("awsEndpointKeys")
        @DisplayName("both copies redirect every AWS client at the emulator, because an absent endpoint "
                + "resolves the REAL service rather than failing")
        void bothCopiesRedirectEveryAwsClientAtTheEmulator(final String key) {
            for (Resource copy : physicalCopiesOf(TEST)) {
                String endpoint = resolvedInResource(copy, key);

                assertThat(endpoint)
                        .as("%s must declare %s. Unlike an absent data-source address, an absent AWS"
                                + " endpoint does not fail closed: the SDK resolves the region's real"
                                + " public endpoint and sends the request there on whatever credentials"
                                + " the default chain finds. An earlier revision of the suite overlay"
                                + " declared none of these and relied on the support base classes,"
                                + " which only ever bound the clients they built themselves",
                                physicalLocationOf(copy), key)
                        .isNotNull()
                        .isNotBlank();
                assertThat(endpoint)
                        .as("%s in %s must not name a real service host", key, physicalLocationOf(copy))
                        .doesNotContain(REAL_AWS_HOST_SUFFIX);
                assertThat(URI.create(endpoint).getHost())
                        .as("%s in %s must address the emulator over the loopback interface", key,
                                physicalLocationOf(copy))
                        .isIn("localhost", "127.0.0.1");
            }
        }

        /**
         * The contractual keys both copies must agree on.
         *
         * @return one argument per key
         */
        static Stream<Arguments> contractualKeys() {
            return CONTRACTUAL_KEYS.stream().map(Arguments::of);
        }

        /**
         * The AWS endpoint settings both copies must declare.
         *
         * @return one argument per key
         */
        static Stream<Arguments> awsEndpointKeys() {
            return AWS_ENDPOINT_KEYS.stream().map(Arguments::of);
        }
    }

    @Nested
    @DisplayName("the local overlay and the packaged test overlay carry the same key set, and every "
            + "divergence is a declared one")
    final class TheLocalAndTestOverlaysCarryTheSameKeySet {

        @Test
        @DisplayName("neither overlay has gained or lost a leaf the other does not account for")
        void neitherOverlayHasDriftedFromTheOther() {
            Set<String> localKeys = properties(LOCAL).keySet();
            Set<String> testKeys = properties(TEST).keySet();

            assertThat(difference(localKeys, testKeys))
                    .as("the local overlay declares these and the packaged test overlay does not. The"
                            + " set is fixed by design and recorded as DIVERGENCE 1 in both files: a"
                            + " new entry here means one overlay gained a key the other never got, and"
                            + " the two profiles stopped reading a job parameter, a resource name or a"
                            + " schema location identically")
                    .containsExactlyInAnyOrderElementsOf(LOCAL_ONLY_KEYS);
            assertThat(difference(testKeys, localKeys))
                    .as("and these are the packaged test overlay's own, recorded as DIVERGENCE 2 and"
                            + " DIVERGENCE 3. Same reasoning in the other direction")
                    .containsExactlyInAnyOrderElementsOf(TEST_ONLY_KEYS);
        }

        @Test
        @DisplayName("the shared part of the key set is the bulk of both overlays, so the comparison is "
                + "not vacuous")
        void theSharedPartIsTheBulkOfBoth() {
            Set<String> localKeys = properties(LOCAL).keySet();
            Set<String> testKeys = properties(TEST).keySet();
            Set<String> shared = new LinkedHashSet<>(localKeys);
            shared.retainAll(testKeys);

            assertThat(shared)
                    .as("an assertion that two nearly-empty key sets agree would prove nothing; this"
                            + " pins that the agreement covers most of both documents")
                    .hasSizeGreaterThan(30);
            assertThat(shared.size())
                    .as("the shared part must dominate the divergences by an order of magnitude")
                    .isGreaterThan((LOCAL_ONLY_KEYS.size() + TEST_ONLY_KEYS.size()) * 5);
        }

        @Test
        @DisplayName("no leaf count is asserted, because a number in a comment cannot survive an edit")
        void noLeafCountIsAsserted() {
            String testDocument = rawTextOf(TEST);

            assertThat(testDocument)
                    .as("an earlier revision of this file claimed a leaf total in prose - 'the same"
                            + " forty-five leaf property paths' - and the number had already stopped"
                            + " being true of either document. The claim is now a key-set comparison"
                            + " asserted above, and the prose must not reintroduce a count")
                    .doesNotContain("forty-five leaf")
                    .doesNotContain("same forty-five");
            assertThat(testDocument)
                    .as("and it must still SAY that the agreement is asserted rather than counted, so a"
                            + " reader knows where the real check lives")
                    .contains("ASSERTED RATHER THAN COUNTED");
        }

        @Test
        @DisplayName("every divergence the files document is a divergence that exists")
        void everyDocumentedDivergenceExists() {
            String testDocument = rawTextOf(TEST);
            Set<String> localKeys = properties(LOCAL).keySet();
            Set<String> testKeys = properties(TEST).keySet();

            assertThat(testDocument)
                    .as("the file must name all three divergences, so the prose and the assertion"
                            + " describe the same reality")
                    .contains("DIVERGENCE 1")
                    .contains("DIVERGENCE 2")
                    .contains("DIVERGENCE 3");
            assertThat(LOCAL_ONLY_KEYS)
                    .as("DIVERGENCE 1 must really be local-only")
                    .allSatisfy(key -> {
                        assertThat(localKeys).contains(key);
                        assertThat(testKeys).doesNotContain(key);
                    });
            assertThat(TEST_ONLY_KEYS)
                    .as("DIVERGENCE 2 and DIVERGENCE 3 must really be test-only")
                    .allSatisfy(key -> {
                        assertThat(testKeys).contains(key);
                        assertThat(localKeys).doesNotContain(key);
                    });
        }
    }

    @Nested
    @DisplayName("the non-production profiles bind ONE field-encryption key, because the seed is sealed "
            + "under it")
    final class TheNonProductionProfilesShareOneFieldEncryptionKey {

        /**
         * The three documents that must resolve the field-encryption key to the same value.
         *
         * <p>Production is deliberately absent: it binds an environment reference with no fallback, so
         * it has no resolvable value here and must never acquire one.</p>
         */
        private static final List<String> NON_PRODUCTION_DOCUMENTS = List.of(LOCAL, TEST);

        @Test
        @DisplayName("every non-production document resolves the key to the same value, so one seeded "
                + "envelope opens under all of them")
        void everyNonProductionDocumentResolvesTheSameKey() {
            String fromLocal = resolvedIn(LOCAL, KEY_FIELD_ENCRYPTION_KEY);
            String fromPackagedTest = resolvedInResource(packaged(TEST), KEY_FIELD_ENCRYPTION_KEY);
            String fromSuiteOverlay = resolvedInResource(suiteOverlay(), KEY_FIELD_ENCRYPTION_KEY);

            assertThat(fromLocal)
                    .as("%s must resolve %s: a local run seals and opens the same columns a test run"
                            + " does", LOCAL, KEY_FIELD_ENCRYPTION_KEY)
                    .isNotNull()
                    .isNotBlank();
            assertThat(fromPackagedTest)
                    .as("the packaged %s must resolve it too - it is the profile a `test`-profile run"
                            + " of the built artefact loads", TEST)
                    .isNotNull()
                    .isNotBlank();

            assertThat(fromPackagedTest)
                    .as("an AES-GCM envelope opens under exactly ONE key, and"
                            + " V3__seed_reference_data.sql seeds fifty customer.govt_issued_id values"
                            + " as fixed envelope literals that every non-production profile applies."
                            + " Two divergent development keys - which these documents carried"
                            + " before - make the same seeded row readable under one profile and"
                            + " unreadable under the other, and the failure appears only at the moment"
                            + " something decrypts")
                    .isEqualTo(fromLocal);
            assertThat(fromSuiteOverlay)
                    .as("the overlay is the copy in force during a suite run, so it must agree as"
                            + " well; SeededProtectedIdentifierIT reads its key from here and opens all"
                            + " fifty seeded values with it")
                    .isEqualTo(fromLocal);
        }

        @ParameterizedTest(name = "{0} binds a key that decodes to thirty-two bytes")
        @ValueSource(strings = {LOCAL, TEST})
        @DisplayName("the shared value is Base64 of exactly the thirty-two bytes AES-256 requires, so "
                + "binding it cannot fail at start-up")
        void theSharedKeyDecodesToThirtyTwoBytes(final String document) {
            String resolved = resolvedIn(document, KEY_FIELD_ENCRYPTION_KEY);

            assertThat(resolved).as("%s must resolve %s", document, KEY_FIELD_ENCRYPTION_KEY)
                    .isNotNull();
            assertThat(Base64.getDecoder().decode(resolved.trim()))
                    .as("the service that binds %s refuses anything other than thirty-two decoded"
                            + " bytes and names the property when it does, so a wrong length here is a"
                            + " start-up failure rather than a runtime one",
                            KEY_FIELD_ENCRYPTION_KEY)
                    .hasSize(FIELD_ENCRYPTION_KEY_BYTES);
        }

        @Test
        @DisplayName("the seed really does carry sealed values, which is what makes the agreement "
                + "load-bearing rather than tidiness")
        void theSeedCarriesSealedValuesRatherThanCleartext() {
            String seed = contentsOfClassPathScript(MIGRATION_FOLDER, SEED_REFERENCE_DATA);

            assertThat(countSealedLiterals(seed))
                    .as("%s must seal every one of the %d customer rows it loads. A single cleartext"
                            + " identifier here would satisfy the schema's NOT NULL and violate the"
                            + " envelope contract that V1, the Customer entity and the encryption"
                            + " service all declare", SEED_REFERENCE_DATA, SEEDED_CUSTOMER_ROWS)
                    .isEqualTo(SEEDED_CUSTOMER_ROWS);
            assertThat(CLEARTEXT_IDENTIFIER_LITERAL.matcher(seed).results().count())
                    .as("and no literal of the legacy identifier width may remain - a run of %d"
                            + " digits in quotes is exactly what the defective revision seeded",
                            SEALED_IDENTIFIER_WIDTH)
                    .isZero();
            assertThat(seed)
                    .as("the key itself must never appear in a migration - what is embedded is"
                            + " ciphertext, not key material")
                    .doesNotContain(resolvedIn(LOCAL, KEY_FIELD_ENCRYPTION_KEY));
        }

        @Test
        @DisplayName("production is not one of them: it binds an environment reference with no "
                + "fallback and applies no seed")
        void productionIsExcludedFromTheSharedKey() {
            assertThat(NON_PRODUCTION_DOCUMENTS)
                    .as("the shared fixture key is a non-production concession and must stay one")
                    .doesNotContain(PRODUCTION);
            assertThat(text(PRODUCTION, KEY_FIELD_ENCRYPTION_KEY))
                    .as("%s must declare %s as a bare environment reference: a fallback would let a"
                            + " deployment encrypt under something accidental", PRODUCTION,
                            KEY_FIELD_ENCRYPTION_KEY)
                    .startsWith("${")
                    .endsWith("}")
                    .doesNotContain(":");
            assertThatThrownBy(() -> resolvedIn(PRODUCTION, KEY_FIELD_ENCRYPTION_KEY))
                    .as("so resolving it with the variable unset must FAIL rather than fall back to"
                            + " the fixture value; the message names the variable an operator has to"
                            + " supply")
                    .hasMessageContaining("CARDDEMO_FIELD_ENCRYPTION_KEY");
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

        @Test
        @DisplayName("the baseline declares one environment-overridable staging root for every "
                + "file-producing batch job")
        void theBaselineDeclaresTheSharedBatchStagingRoot() {
            assertThat(text(SHARED, KEY_BATCH_STAGING_DIRECTORY))
                    .as("the container mount is supplied through CARDDEMO_BATCH_STAGING_DIRECTORY,"
                            + " while a non-container process must retain a usable local fallback")
                    .isEqualTo(EXPECTED_BATCH_STAGING_DIRECTORY);
        }

        @Test
        @DisplayName("and the fallback names a subdirectory of the temporary root rather than the "
                + "temporary root itself, which no process can make owner-only")
        void theFallbackStagingRootIsAModuleOwnedSubdirectory() {
            final String declared = text(SHARED, KEY_BATCH_STAGING_DIRECTORY);

            assertThat(declared)
                    .as("staging directly in the shared, world-writable platform temporary directory "
                            + "would leave the generation listing readable by every account on the host")
                    .doesNotEndWith("${java.io.tmpdir}}")
                    .contains("${java.io.tmpdir}/");
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

        @ParameterizedTest(name = "{0} inherits the shared batch staging root")
        @ValueSource(strings = {LOCAL, PRODUCTION, TEST})
        @DisplayName("no overlay forks the local staging root away from the container's one mounted "
                + "directory")
        void noOverlayMovesTheSharedBatchStagingRoot(final String profileDocument) {
            assertThat(properties(profileDocument))
                    .as("a profile-specific staging root would let one job write outside the mounted"
                            + " path and return the durability gap the review found")
                    .doesNotContainKey(KEY_BATCH_STAGING_DIRECTORY);
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

        @ParameterizedTest(name = "{0} states a scrape posture")
        @ValueSource(strings = {SHARED, LOCAL, PRODUCTION, TEST})
        @DisplayName("states the scrape rule explicitly in every document, because the chain reads it "
                + "with no default of its own and an absent value would stop the start")
        void everyDocumentStatesTheScrapeRule(final String document) {
            assertThat(properties(document))
                    .as("%s must state %s rather than leave it to be inferred", document,
                            KEY_ANONYMOUS_SCRAPE)
                    .containsKey(KEY_ANONYMOUS_SCRAPE);
        }

        @Test
        @DisplayName("closes anonymous metrics scraping wherever the collector is not on the same "
                + "machine, and opens it only in the two profiles where it is")
        void closesAnonymousScrapingExceptWhereTheCollectorIsLocal() {
            assertThat(resolvedIn(SHARED, KEY_ANONYMOUS_SCRAPE)).isEqualTo("false");
            assertThat(resolvedAcrossSharedThen(PRODUCTION, KEY_ANONYMOUS_SCRAPE)).isEqualTo("false");
            assertThat(resolvedAcrossSharedThen(LOCAL, KEY_ANONYMOUS_SCRAPE)).isEqualTo("true");
            assertThat(resolvedAcrossSharedThen(TEST, KEY_ANONYMOUS_SCRAPE)).isEqualTo("true");
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
            assertThat(resolvedIn(document, KEY_JOB_QUEUE))
                    .as("the plan names this resource JOBS, the emulator bootstrap provisions %s, "
                            + "and a divergent name here would be valid, would name nothing that "
                            + "exists, and would be created silently were the not-found strategy left "
                            + "at its library default",
                            EXPECTED_JOB_SUBMISSION_QUEUE)
                    .isEqualTo(EXPECTED_JOB_SUBMISSION_QUEUE);
        }

        @Test
        @DisplayName("production fixes no queue name, resolving it from the environment with no "
                + "fallback")
        void productionResolvesTheQueueFromTheEnvironment() {
            assertThat(text(PRODUCTION, KEY_JOB_QUEUE))
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
     * Holds the documented migration set to the migration set that ships, and holds the production
     * exclusion of the seeds to the one mechanism that actually performs it.
     *
     * <p>Three separate things are asserted here and they fail for different reasons.</p>
     *
     * <p>The first is the exclusion itself, which rests entirely on a PROFILE-SCOPED VERSION CEILING.
     * All four scripts ship flat from {@code classpath:db/migration}, which every profile declares, so
     * a directory-scoped location list has nothing to scope: it cannot separate {@code V3} and
     * {@code V4} from {@code V1} and {@code V2} when all four sit in one folder. The shared baseline
     * declares the restrictive ceiling {@code spring.flyway.target: 2}, so a profile silent about
     * seeding inherits a schema-only migration; the production overlay re-declares it so the guarantee
     * is visible in the document that depends on it; and the two seeding profiles lift it for
     * themselves. The ceiling is asserted against the merged environment as well as against the
     * document, because inheritance is what a running application resolves and a per-document reading
     * cannot answer an inheritance question.</p>
     *
     * <p>Two earlier revisions reached instead for profile-scoped locations, and both had to withdraw
     * them: a location is scanned RECURSIVELY, so a parent reaches its children, and duplicating
     * {@code V1} and {@code V2} into a second directory is the only way a split can be made to work at
     * all. The flat directory is the load-bearing fact and both seed-migration specifications forbid
     * creating a subdirectory, so the assertions below hold the layout flat rather than holding a split
     * in place.</p>
     *
     * <p>The second is that the placement and the arithmetic agree: {@link
     * #theCeilingSeparatesSchemaFromSeedByArithmetic()} requires every schema script to be at or below
     * the ceiling and every seed to be strictly above it. That is the assertion that would catch a seed
     * numbered {@code V1_2} or a required schema script numbered {@code V5} - each of which reads as
     * harmless and silently defeats the only control there is.</p>
     *
     * <p>The third is truthfulness of the prose. The documents once described a profile-scoped seed
     * location that no longer exists. Correcting such text is never durable on its own, because a
     * correction goes stale the moment a script is added - precisely the moment nobody is reading these
     * comments. So the claim is asserted against the delivered scripts rather than against a second
     * copy of itself: {@link #everyDocumentNamesEveryDeliveredMigration(String)} requires each of the
     * four profile documents to name every delivered script by file name, so adding or removing one
     * fails the build until every document that enumerates them catches up.</p>
     */
    @Nested
    @DisplayName("the documented migration set is the migration set that ships, and the version ceiling "
            + "alone is what excludes the seeds")
    final class TheDocumentedMigrationSetIsTheDeliveredOne {

        @ParameterizedTest(name = "{0} declares the one shared migration location")
        @ValueSource(strings = {SHARED, PRODUCTION, LOCAL, TEST})
        @DisplayName("every profile document declares the same single flat location, because the four "
                + "scripts share it and no profile has a second location to name")
        void everyDocumentDeclaresTheOneSharedLocation(final String document) {
            assertThat(declaredLocations(document))
                    .as("%s must resolve exactly one location, %s. The delivered directory is flat, so "
                            + "a per-profile location list would have to duplicate V1 and V2 into a "
                            + "second directory to mean anything - which is why the ceiling, not the "
                            + "location list, is the control", document, MIGRATION_LOCATION)
                    .containsExactly(MIGRATION_LOCATION);

            assertThat(resolvedLocations(document))
                    .as("and the value must survive the merge over the baseline, which is the only form "
                            + "of this defect a per-document reading cannot see")
                    .containsExactly(MIGRATION_LOCATION);
        }

        @Test
        @DisplayName("the suite overlay declares the same single location, so a suite run migrates from "
                + "the set the artefact carries")
        void theSuiteOverlayDeclaresTheOneDeliveredLocation() {
            assertThat(locationsIn(propertiesOf(suiteOverlay())))
                    .as("the overlay is the copy Spring reads during a suite run. It resolves the one "
                            + "shared location and lifts the ceiling, which together are what give the "
                            + "fixtures rows to assert against")
                    .containsExactly(MIGRATION_LOCATION);
        }

        @Test
        @DisplayName("no script is hidden in a subdirectory, so the version ceiling stays the whole of "
                + "the boundary")
        void noScriptIsHiddenInASubdirectory() {
            assertThat(versionedScriptsIn(MIGRATION_FOLDER))
                    .as("every delivered script must sit DIRECTLY in %s, so that the non-recursive "
                            + "listing and the recursive one agree", MIGRATION_FOLDER)
                    .containsExactlyInAnyOrderElementsOf(DELIVERED_MIGRATIONS);
            assertThat(recursiveVersionedScriptsIn(MIGRATION_FOLDER))
                    .as("a recursive scan must find the SAME four and no fifth. A script one level down "
                            + "is a regression towards the split arrangement that both seed-migration "
                            + "specifications prohibit, and it would be applied by every profile while "
                            + "appearing in no directory listing anyone reads")
                    .containsExactlyInAnyOrderElementsOf(DELIVERED_MIGRATIONS);
        }

        @Test
        @DisplayName("the shared baseline declares the restrictive ceiling, so a profile silent about "
                + "seeding inherits a schema-only migration rather than a seeding one")
        void theSharedBaselineDeclaresTheRestrictiveCeiling() {
            assertThat(text(SHARED, KEY_FLYWAY_TARGET))
                    .as("the direction of this default is the control: an unpinned baseline would seed "
                            + "sample personal data and ten known sign-on identities into every profile "
                            + "that did not think to pin it")
                    .isEqualTo(PRODUCTION_VERSION_CEILING);
        }

        @Test
        @DisplayName("the production overlay declares the version ceiling itself, and it is 2")
        void productionDeclaresTheVersionCeiling() {
            assertThat(text(PRODUCTION, KEY_FLYWAY_TARGET))
                    .as("this single line is the entire production exclusion: it applies versions at or "
                            + "below %s and does not resolve anything above it",
                            PRODUCTION_VERSION_CEILING)
                    .isEqualTo(PRODUCTION_VERSION_CEILING);

            assertThat(resolvedAcrossSharedThen(PRODUCTION, KEY_FLYWAY_TARGET))
                    .as("the resolved value is what the running deployment migrates to; the overlay must "
                            + "still carry the ceiling once layered over the baseline")
                    .isEqualTo(PRODUCTION_VERSION_CEILING);
        }

        @Test
        @DisplayName("the ceiling the documents declare is the ceiling the code control reads, so neither "
                + "can drift into doing nothing")
        void theDeclaredCeilingIsTheCeilingTheCodeControlReads() {
            assertThat(FlywayConfig.SCHEMA_ONLY_TARGET)
                    .as("FlywayConfig refuses a production profile whose resolved ceiling reaches the "
                            + "seeds, and it names the boundary itself. Were that constant to name a "
                            + "different version than these documents declare, the only control between "
                            + "a production migration and ten seeded sign-on identities would be "
                            + "silently ineffective")
                    .isEqualTo(PRODUCTION_VERSION_CEILING);

            assertThat(FlywayConfig.SEEDING_TARGET)
                    .as("and the lifted ceiling the two seeding profiles declare must be the one the code "
                            + "control completes for them, or a profile that omitted the property would be "
                            + "lifted to a value no document names")
                    .isEqualTo(SEEDING_TARGET);

            assertThat(FlywayConfig.PRODUCTION_PROFILE)
                    .as("the code control is scoped by profile name; a name matching no profile would "
                            + "leave the configuration ceiling unaccompanied")
                    .isEqualTo("prod");

            assertThat(FlywayConfig.MIGRATION_LOCATION)
                    .as("and the one location the code control admits under production must be the one "
                            + "location every document declares, or the guard would be protecting a "
                            + "directory nothing ships from")
                    .isEqualTo(MIGRATION_LOCATION);
        }

        @ParameterizedTest(name = "{0} raises the ceiling for itself")
        @ValueSource(strings = {LOCAL, TEST})
        @DisplayName("the two profiles that need the seeds raise the ceiling explicitly, which is the "
                + "only way a seed script is ever applied")
        void theSeedingProfilesRaiseTheCeilingThemselves(final String document) {
            assertThat(text(document, KEY_FLYWAY_TARGET))
                    .as("%s must apply every delivered script, and it opts in by lifting the ceiling "
                            + "rather than by inheriting a permissive one. Left at the inherited value "
                            + "it would leave the seeded cardinalities, the byte-parity comparison and "
                            + "the sign-on tests asserting against an empty database", document)
                    .isEqualTo(SEEDING_TARGET);

            assertThat(resolvedAcrossSharedThen(document, KEY_FLYWAY_TARGET))
                    .as("and the lift must survive the merge over the baseline, otherwise the fixtures "
                            + "would run against empty reference tables")
                    .isEqualTo(SEEDING_TARGET);
        }

        @Test
        @DisplayName("the delivered set is exactly the four named scripts, flat in one location")
        void theDeliveredScriptsSitFlatAndAreExactlyTheFourNamed() {
            assertThat(versionedScriptsIn(MIGRATION_FOLDER))
                    .as("the one shared location must carry exactly the four named scripts in apply "
                            + "order. A script added without a corresponding edit to the profile "
                            + "documents would leave them naming a shorter set than ships")
                    .containsExactlyElementsOf(DELIVERED_MIGRATIONS);
        }

        @Test
        @DisplayName("the numbering and the ceiling agree, so the one control separates schema from seed "
                + "on the delivered scripts and not merely on a documented list")
        void theCeilingSeparatesSchemaFromSeedByArithmetic() {
            assertThat(SCHEMA_MIGRATIONS)
                    .as("every script production applies must sit at or below %s. One numbered above it "
                            + "would silently stop being applied in production",
                            PRODUCTION_VERSION_CEILING)
                    .isNotEmpty()
                    .noneMatch(ConfigurationProfileBaselineTest::isAboveCeiling);

            assertThat(SEED_MIGRATIONS)
                    .as("and every seed must sit STRICTLY ABOVE %s. With all four scripts in one flat "
                            + "location the numbering is the ONLY thing that holds them out of "
                            + "production, so a seed at or below the ceiling defeats the control "
                            + "outright", PRODUCTION_VERSION_CEILING)
                    .isNotEmpty()
                    .allMatch(ConfigurationProfileBaselineTest::isAboveCeiling);

            final List<String> delivered = versionedScriptsIn(MIGRATION_FOLDER);

            assertThat(delivered)
                    .as("and the statement must agree with what actually ships: the two schema scripts "
                            + "must be the ones at or below the ceiling")
                    .filteredOn(script -> !isAboveCeiling(script))
                    .containsExactlyInAnyOrderElementsOf(SCHEMA_MIGRATIONS);
            assertThat(delivered)
                    .as("and the two seeds must be the ones above it. A seed numbered V1_2 reads as "
                            + "harmless and would be applied by a production deployment")
                    .filteredOn(ConfigurationProfileBaselineTest::isAboveCeiling)
                    .containsExactlyInAnyOrderElementsOf(SEED_MIGRATIONS);
        }

        @ParameterizedTest(name = "{0} names every delivered migration")
        @ValueSource(strings = {SHARED, LOCAL, TEST, PRODUCTION})
        @DisplayName("every profile document names every delivered script by file name, so adding one "
                + "cannot leave a document describing a set that no longer ships")
        void everyDocumentNamesEveryDeliveredMigration(final String document) {
            String text = rawTextOf(document);

            assertThat(DELIVERED_MIGRATIONS)
                    .allSatisfy(script -> assertThat(text)
                            .as("%s must name %s. Each of these four documents enumerates the migration "
                                    + "set and states which side of the ceiling each script falls on; a "
                                    + "script missing from that enumeration is a script whose production "
                                    + "applicability nobody stated", document, script)
                            .contains(script));
        }

        @ParameterizedTest(name = "{0} declares no profile-scoped migration location")
        @ValueSource(strings = {SHARED, LOCAL, TEST, PRODUCTION})
        @DisplayName("no document reaches for a subdirectory that does not exist, in a declared value "
                + "or in an inherited one")
        void noDocumentDeclaresAProfileScopedSubdirectory(final String document) {
            for (final List<String> locations
                    : List.of(declaredLocations(document), resolvedLocations(document))) {
                assertThat(locations)
                        .as("%s must name the flat location and nothing beneath it. A subdirectory "
                                + "descriptor would name a directory that does not exist, so the "
                                + "migration would resolve no script at all and the deployment would "
                                + "come up against an empty database reporting success", document)
                        .isNotEmpty()
                        .allSatisfy(location -> assertThat(location)
                                .isEqualTo(MIGRATION_LOCATION));
            }
        }

        @Test
        @DisplayName("the suite overlay is governed by the same rule, because it is read in preference "
                + "to the packaged copy during a suite run")
        void theSuiteOverlayDeclaresNoProfileScopedSubdirectory() {
            assertThat(locationsIn(propertiesOf(suiteOverlay())))
                    .containsExactly(MIGRATION_LOCATION);
        }

        @ParameterizedTest(name = "{0} lets Spring Batch provision its own metadata tables")
        @ValueSource(strings = {SHARED, LOCAL, TEST, PRODUCTION})
        @DisplayName("every profile resolves the batch schema initializer to always, so Spring Batch is "
                + "the single owner of the framework metadata tables in every environment")
        void everyProfileLetsSpringBatchProvisionItsOwnTables(final String document) {
            assertThat(resolvedAcrossSharedThen(document, KEY_BATCH_INITIALIZE_SCHEMA))
                    .as("AAP 0.3.1 assigns the six framework tables and three sequences to Spring Batch "
                            + "and the eleven application tables to V1__create_schema.sql, which is what "
                            + "keeps the delivered migration inventory at exactly four scripts. The value "
                            + "must be the SAME under every profile: setting a different one for "
                            + "production alone is what previously made production the single environment "
                            + "in which a job launch could fail on a missing relation, with local and test "
                            + "configured not to reveal it")
                    .isEqualTo("always");
        }

        @Test
        @DisplayName("the suite overlay resolves the initializer the same way, so the copy Spring reads "
                + "during a suite run exercises the provisioning path a deployment uses")
        void theSuiteOverlayResolvesTheInitializerTheSameWay() {
            assertThat(propertiesOf(suiteOverlay()).get(KEY_BATCH_INITIALIZE_SCHEMA))
                    .as("the overlay shadows the packaged copy during a suite run, so a different value "
                            + "here would leave the suite exercising a provisioning path no deployment "
                            + "uses, however the packaged copy is configured")
                    .isEqualTo("always");
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
     * Every setting the production profile requires from its environment, taken from the guard itself.
     *
     * <p>Read from {@link ProductionConfigurationValidator#REQUIRED_SETTINGS} rather than restated here,
     * so this class and the guard cannot disagree about what production requires. An earlier revision
     * did restate a subset - three keys, then five - and the TLS, region and trace-collector settings
     * went unasserted as a direct result.
     *
     * @return one argument pair per required setting: the property key and the variable that supplies it
     */
    private static Stream<Arguments> requiredProductionSettings() {
        return ProductionConfigurationValidator.REQUIRED_SETTINGS.stream()
                .map(setting -> Arguments.of(setting.propertyKey(), setting.environmentVariable()));
    }

    /**
     * Names the environment variable the production profile references for a key.
     *
     * @param key the fully qualified property key
     * @return the variable name recorded against that key
     * @throws IllegalStateException when the key is not one the guard watches
     */
    private static String variableFor(final String key) {
        return ProductionConfigurationValidator.REQUIRED_SETTINGS.stream()
                .filter(setting -> setting.propertyKey().equals(key))
                .map(ProductionConfigurationValidator.RequiredSetting::environmentVariable)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "no required production setting declares " + key));
    }

    /**
     * The credential-shaped tokens that must not occur as a value in the shared baseline.
     *
     * @return one argument per token
     */
    private static Stream<Arguments> credentialTokens() {
        return CREDENTIAL_TOKENS.stream().map(Arguments::of);
    }

    /**
     * The five surfaces the shared baseline closes, each paired with the value it closes them to.
     *
     * <p>Paired rather than listed, because the question the test profile raises is not whether a key
     * is declared - restating a closed value is legitimate and deliberate - but whether the value that
     * resolves is still the closed one.
     *
     * @return one argument pair per surface
     */
    private static Stream<Arguments> closedSurfaceExpectations() {
        return Stream.of(
                Arguments.of(KEY_EXPOSURE, EXPECTED_CLOSED_EXPOSURE),
                Arguments.of(KEY_SHOW_DETAILS, EXPECTED_CLOSED_DETAIL),
                Arguments.of(KEY_SHOW_COMPONENTS, EXPECTED_CLOSED_DETAIL),
                Arguments.of(KEY_API_DOCS_ENABLED, "false"),
                Arguments.of(KEY_SWAGGER_UI_ENABLED, "false"));
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
        return propertiesOf(packaged(document));
    }

    /**
     * Loads one physical configuration resource into a flat map of key to value.
     *
     * <p>Separated from {@link #properties(String)} so that the suite overlay - which shares its name
     * with a packaged deliverable and therefore cannot be addressed by name - is read through exactly
     * the same flattening as everything else.
     *
     * @param resource the resolved resource to load
     * @return every declared property, flattened; never {@code null}
     */
    private static Map<String, Object> propertiesOf(final Resource resource) {
        Map<String, Object> flattened = new LinkedHashMap<>();
        for (PropertySource<?> source : loadDocuments(resource.getDescription(), resource)) {
            if (source instanceof EnumerablePropertySource<?> enumerable) {
                for (String name : enumerable.getPropertyNames()) {
                    flattened.put(name, enumerable.getProperty(name));
                }
            }
        }
        return flattened;
    }

    /**
     * Returns the keys present in the first set and absent from the second, in encounter order.
     *
     * <p>Written out rather than expressed as a stream filter because the result is asserted with
     * {@code containsExactlyInAnyOrderElementsOf}, and a stable encounter order makes a failure message
     * read in document order rather than in hash order.
     *
     * @param subject   the set whose exclusive members are wanted
     * @param reference the set to subtract
     * @return the difference; never {@code null}
     */
    private static Set<String> difference(final Set<String> subject, final Set<String> reference) {
        Set<String> exclusive = new LinkedHashSet<>(subject);
        exclusive.removeAll(reference);
        return exclusive;
    }

    /**
     * Returns every physical copy of a configuration document that the test class path carries.
     *
     * <p>Resolved with the {@code classpath*:} prefix rather than {@code classpath:}, which is the
     * whole point: the single-copy form stops at the first match and would hide the packaged
     * deliverable behind the suite overlay of the same name.
     *
     * @param document the class-path name of the document
     * @return every copy found, in class-path order; never {@code null} and never empty for a
     *         document this module ships
     */
    private static List<Resource> physicalCopiesOf(final String document) {
        try {
            Resource[] found = new PathMatchingResourcePatternResolver()
                    .getResources("classpath*:" + document);
            List<Resource> copies = Stream.of(found).filter(Resource::exists).toList();
            if (copies.isEmpty()) {
                throw new IllegalStateException(
                        "shipped configuration document is missing: " + document);
            }
            return copies;
        } catch (IOException failure) {
            throw new UncheckedIOException("configuration document is unreadable: " + document,
                    failure);
        }
    }

    /**
     * Resolves the copy of a document that is packaged inside the artefact.
     *
     * <p>This is what every profile assertion in this class reads, because the packaged copy is the
     * deliverable: it is what a deployment loads and, for the test profile, it is the only definition
     * that exists once the suite has finished.
     *
     * @param document the class-path name of the document
     * @return the packaged copy
     * @throws IllegalStateException when no packaged copy exists, which would mean the document was
     *                               written under {@code src/test/resources} alone
     */
    private static Resource packaged(final String document) {
        return physicalCopiesOf(document).stream()
                .filter(resource -> !isSuiteOverlay(resource))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "no packaged copy of " + document + " exists; every copy on the class path is"
                                + " suite-only, so nothing would ship"));
    }

    /**
     * Resolves the copy of the test profile that exists only while the suite runs.
     *
     * @return the suite overlay
     * @throws IllegalStateException when the overlay is absent
     */
    private static Resource suiteOverlay() {
        return physicalCopiesOf(TEST).stream()
                .filter(ConfigurationProfileBaselineTest::isSuiteOverlay)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "no suite-only copy of " + TEST + " exists on the test class path"));
    }

    /**
     * Reports whether a resolved resource is the suite-only copy rather than the packaged one.
     *
     * @param resource the resource to classify
     * @return {@code true} when the resource was compiled from {@code src/test/resources}
     */
    private static boolean isSuiteOverlay(final Resource resource) {
        return physicalLocationOf(resource).contains(SUITE_ORIGIN);
    }

    /**
     * Reports where a resource physically lives, normalised to forward slashes.
     *
     * <p>The location is read from the resolved URL rather than from {@code getDescription()}. The two
     * agree for a resource obtained by pattern resolution, but they do not agree for one constructed
     * from a bare class-path name: that description repeats the name it was given and reveals nothing
     * about which of two same-named copies the class loader actually answered with. Since telling
     * those two copies apart is the whole purpose of this method, it uses the only value that always
     * carries the answer.
     *
     * @param resource the resource to locate
     * @return the resource's absolute location, using {@code /} as the separator
     * @throws IllegalStateException when the resource cannot be resolved to a URL
     */
    private static String physicalLocationOf(final Resource resource) {
        try {
            return resource.getURL().toString().replace('\\', '/');
        } catch (final IOException cause) {
            throw new IllegalStateException(
                    "cannot determine the physical location of " + resource.getDescription(), cause);
        }
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
        loadDocuments(overlay, packaged(overlay)).forEach(merged::addLast);
        loadDocuments(SHARED, packaged(SHARED)).forEach(merged::addLast);
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
        return resolvedInResource(packaged(document), key);
    }

    /**
     * Assembles the environment a production start-up sees when the deployment supplies nothing.
     *
     * <p>Three properties of this environment make it the right instrument for a fail-fast assertion,
     * and each is deliberate.
     *
     * <p><strong>It is a real {@link org.springframework.core.env.Environment}, not a bare resolver.</strong>
     * {@link #resolvedIn} and {@link #resolvedAcrossSharedThen} resolve strictly and raise a
     * placeholder-resolution failure on the first unsatisfied reference, which answers "is anything
     * missing" but never "what does the application hold instead". An environment resolves leniently
     * through {@code resolvePlaceholders}, so the unsatisfied reference survives as text and can be
     * compared against the variable name the profile declares.
     *
     * <p><strong>The production overlay is layered above the shared baseline,</strong> in that order, so
     * a key declared in both resolves to the production declaration exactly as a deployment resolves it.
     *
     * <p><strong>Both system-backed property sources are removed.</strong> A build agent that exports
     * {@code AWS_REGION} - which agents hosted in that ecosystem routinely do - would otherwise satisfy
     * one of the required settings by accident, and an assertion about an unsupplied environment would
     * pass or fail according to where it ran.
     *
     * @return an environment carrying the two delivered documents and nothing from the machine
     */
    private static ConfigurableEnvironment productionEnvironmentWithNothingSupplied() {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources()
                .remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment.getPropertySources()
                .remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        loadDocuments(PRODUCTION, packaged(PRODUCTION))
                .forEach(environment.getPropertySources()::addLast);
        loadDocuments(SHARED, packaged(SHARED))
                .forEach(environment.getPropertySources()::addLast);
        return environment;
    }

    /**
     * Resolves a key against one physical resource alone, substituting placeholder fallbacks.
     *
     * @param resource the resolved resource to read
     * @param key      the fully qualified property key
     * @return the resolved value, or {@code null} when the resource does not declare it
     */
    private static String resolvedInResource(final Resource resource, final String key) {
        MutablePropertySources sources = new MutablePropertySources();
        loadDocuments(resource.getDescription(), resource).forEach(sources::addLast);
        return new PropertySourcesPropertyResolver(sources).getProperty(key);
    }

    /**
     * Reads one delivered migration script in full, from the class path.
     *
     * <p>Read from the class path rather than from the source tree for the same reason
     * {@link #versionedScriptsIn(String)} is: the answer is then what a running application would find
     * on its own migration location, not what a directory listing of the repository suggests. A stale
     * copy under {@code target/} therefore fails an assertion here rather than hiding one.</p>
     *
     * @param folder   the class-path folder, such as {@code db/migration}
     * @param fileName the script's file name
     * @return the script's full text
     * @throws IllegalStateException if the script is not on the class path
     * @throws UncheckedIOException  if the script cannot be read
     */
    private static String contentsOfClassPathScript(final String folder, final String fileName) {
        Resource script = new ClassPathResource(folder + "/" + fileName);
        if (!script.exists()) {
            throw new IllegalStateException(
                    "migration " + fileName + " is not on the class path under " + folder);
        }
        try {
            return new String(script.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            throw new UncheckedIOException(fileName + " is unreadable", unreadable);
        }
    }

    /**
     * Counts complete sealed identifier literals in a migration script.
     *
     * @param script the script text to scan
     * @return the number of complete envelope literals the script embeds
     */
    private static long countSealedLiterals(final String script) {
        return SEALED_IDENTIFIER_LITERAL.matcher(script).results().count();
    }

    /**
     * Returns the versioned migration scripts a class-path folder carries, in the order a migration
     * applies them.
     *
     * <p>Read from the class path rather than from the source tree, so the answer is what a running
     * application would find on its own migration location and not what a directory listing of the
     * repository suggests. A folder that exists and carries no script, and a folder that does not exist
     * at all, both yield an empty list; the distinction is not one a migration can act on.</p>
     *
     * <p>Ordered by VERSION, numerically, and not by file name. The two disagree as soon as a dotted
     * version exists: {@code V1_1__} sorts before {@code V1__} as text, because {@code 1} precedes
     * {@code _}, while the migration tool applies 1 before 1.1. Sorting by name would therefore have
     * made an assertion about apply order assert the wrong order.</p>
     *
     * @param folder the class-path folder, such as {@code db/migration}
     * @return the versioned script file names in apply order; never {@code null}
     */
    private static List<String> versionedScriptsIn(final String folder) {
        try {
            Resource[] found = new PathMatchingResourcePatternResolver()
                    .getResources("classpath*:" + folder + "/V*__*.sql");
            return Stream.of(found)
                    .map(Resource::getFilename)
                    .filter(name -> name != null)
                    .sorted(Comparator
                            .comparing(ConfigurationProfileBaselineTest::versionOf,
                                    ConfigurationProfileBaselineTest::compareVersions)
                            .thenComparing(Comparator.naturalOrder()))
                    .toList();
        } catch (IOException failure) {
            throw new UncheckedIOException("migration location is unreadable: " + folder, failure);
        }
    }

    /**
     * Reads the migration location list a shipped document declares.
     *
     * <p>Both YAML spellings are accepted, and deliberately so. Every shipped document currently declares
     * a scalar - a single descriptor in the two non-seeding documents and a comma-separated pair in the
     * two seeding ones - but a location list is equally well written as a YAML sequence, which a property
     * loader flattens into indexed leaves. A reader that understood only one spelling would answer
     * {@code null} the moment a document was reformatted, and would make every assertion over it vacuous
     * at exactly the moment one of them mattered.
     *
     * @param document the class-path name of the document to read
     * @return the declared location descriptors in declared order; empty when the document declares none
     */
    private static List<String> declaredLocations(final String document) {
        return locationsIn(properties(document));
    }

    /**
     * Reads the migration location list a shipped document resolves once layered over the baseline.
     *
     * <p>A sequence in an overlay does not merge with a scalar in the baseline - the highest-precedence
     * source that contributes the property wins outright - so an overlay that declares its own list
     * resolves that list, and one that declares none inherits the baseline's.
     *
     * @param overlay the class-path name of the overlay to read
     * @return the resolved location descriptors in order; never {@code null}
     */
    private static List<String> resolvedLocations(final String overlay) {
        List<String> own = declaredLocations(overlay);
        return own.isEmpty() ? locationsIn(properties(SHARED)) : own;
    }

    /**
     * Extracts the migration location list from a flattened property map.
     *
     * @param properties the flattened properties of one document
     * @return the location descriptors in declared order; empty when none is declared
     */
    private static List<String> locationsIn(final Map<String, Object> properties) {
        Object scalar = properties.get(KEY_FLYWAY_LOCATIONS);
        if (scalar != null) {
            return Stream.of(String.valueOf(scalar).split(","))
                    .map(String::strip)
                    .filter(descriptor -> !descriptor.isEmpty())
                    .toList();
        }
        List<String> indexed = new ArrayList<>();
        for (int index = 0; properties.containsKey(indexedLocationKey(index)); index++) {
            indexed.add(String.valueOf(properties.get(indexedLocationKey(index))).strip());
        }
        return List.copyOf(indexed);
    }

    /**
     * Names the flattened key a sequence entry of the location list occupies.
     *
     * @param index the zero-based position in the sequence
     * @return the flattened key, such as {@code spring.flyway.locations[0]}
     */
    private static String indexedLocationKey(final int index) {
        return KEY_FLYWAY_LOCATIONS + "[" + index + "]";
    }

    /**
     * Returns the versioned scripts under a class-path folder and every folder beneath it.
     *
     * <p>Recursive, because that is how Flyway resolves a location: a script in a sub-folder of a
     * declared location is applied exactly as one sitting directly in it. Used to prove that the two
     * profile-scoped folders account for every delivered script.
     *
     * @param folder the class-path folder to search, such as {@code db/migration}
     * @return the versioned script file names, sorted by name; never {@code null}
     */
    private static List<String> recursiveVersionedScriptsIn(final String folder) {
        try {
            Resource[] found = new PathMatchingResourcePatternResolver()
                    .getResources("classpath*:" + folder + "/**/V*__*.sql");
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
     * Reports whether a delivered migration sits above the production version ceiling.
     *
     * <p>This is the arithmetic the migration tool itself performs, reproduced rather than trusted: a
     * version is read from the file name, its parts are compared numerically against the ceiling, and a
     * script is above the ceiling exactly when the tool would decline to resolve it under
     * {@code spring.flyway.target}. Comparing numerically matters - {@code V1_1} is version 1.1 and sits
     * below 2, while a lexicographic comparison of the file names would place it after {@code V2}.</p>
     *
     * @param script the migration file name, such as {@code V1_1__create_batch_metadata.sql}
     * @return {@code true} when the script's version exceeds {@link #PRODUCTION_VERSION_CEILING}
     * @throws IllegalArgumentException when the name carries no parsable version
     */
    private static boolean isAboveCeiling(final String script) {
        return compareVersions(versionOf(script), PRODUCTION_VERSION_CEILING) > 0;
    }

    /**
     * Extracts a migration's dotted version from its file name.
     *
     * @param script the migration file name
     * @return the version, with {@code _} normalised to {@code .}
     * @throws IllegalArgumentException when the name carries no parsable version
     */
    private static String versionOf(final String script) {
        Matcher matcher = Pattern.compile("^V(\\d+(?:[._]\\d+)*)__").matcher(script);
        if (!matcher.find()) {
            throw new IllegalArgumentException("not a versioned migration file name: " + script);
        }
        return matcher.group(1).replace('_', '.');
    }

    /**
     * Compares two dotted migration versions part by part, numerically.
     *
     * <p>A missing part is treated as zero, so {@code 2} and {@code 2.0} compare equal exactly as the
     * migration tool treats them.</p>
     *
     * @param left  the first version
     * @param right the second version
     * @return a negative value, zero or a positive value as {@code left} is below, equal to or above
     *         {@code right}
     */
    private static int compareVersions(final String left, final String right) {
        String[] leftParts = left.split("\\.");
        String[] rightParts = right.split("\\.");
        for (int index = 0; index < Math.max(leftParts.length, rightParts.length); index++) {
            int leftPart = index < leftParts.length ? Integer.parseInt(leftParts[index]) : 0;
            int rightPart = index < rightParts.length ? Integer.parseInt(rightParts[index]) : 0;
            if (leftPart != rightPart) {
                return Integer.compare(leftPart, rightPart);
            }
        }
        return 0;
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
        return rawTextOfResource(packaged(document));
    }

    /**
     * Reads one physical resource as text, comments included.
     *
     * @param resource the resolved resource to read
     * @return the resource's full text
     */
    private static String rawTextOfResource(final Resource resource) {
        try {
            return resource.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new UncheckedIOException("configuration document is unreadable: "
                    + resource.getDescription(), failure);
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
                                                         final Resource resource) {
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
