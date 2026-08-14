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

    /**
     * The driver property that decides whether a server error's detail reaches the exception's message.
     *
     * <p>It is stated as a literal here for the same reason the category above is: the key names a
     * third-party setting, and reading it back from the file it is asserted against would make the
     * assertion agree with whatever the file happens to say.
     *
     * <p>PostgreSQL reports a CHECK violation with the <em>entire attempted row</em> in the error's detail
     * field, and pgjdbc's default folds that detail into {@code PSQLException}'s own message, from where any
     * sink that prints an exception carries it - including Hibernate's {@code SqlExceptionHelper}, which logs
     * that message at {@code ERROR} and is not this module's to silence by level, because the same logger
     * emits the SQLSTATE line at {@code WARN}. Turning the property off sanitises the exception itself, so
     * the row image is never present to be logged by any sink, for any constraint, in any profile. Recorded
     * in {@code docs/decision-log.md} DL-355.
     */
    private static final String SERVER_ERROR_DETAIL_PROPERTY =
            "spring.datasource.hikari.data-source-properties.logServerErrorDetail";

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

    /** How, and whether, a forwarded address is allowed to replace the connection's own. */
    private static final String KEY_FORWARD_HEADERS_STRATEGY = "server.forward-headers-strategy";

    /** The peers a forwarded address is believed from. */
    private static final String KEY_TRUSTED_PROXIES = "server.tomcat.remoteip.internal-proxies";

    /**
     * The only strategy that consults the peer before believing a forwarded address.
     *
     * <p>The alternative rewrites unconditionally, which is what let a caller choose the address this
     * deployment attributes a request to.
     */
    private static final String TRUSTED_PROXY_AWARE_STRATEGY = "native";

    /** The variable through which a deployment names its own proxies. */
    private static final String TRUSTED_PROXIES_VARIABLE = "CARDDEMO_TRUSTED_PROXIES";

    /** The fail-closed default: the loopback address, escaped as the pattern it is matched as. */
    private static final String LOOPBACK_ADDRESS_PATTERN = "127\\.0\\.0\\.1";

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
     * and pass. {@code AbstractPostgresIT} publishes those three instead.
     *
     * <p>The fourth is the listener's own bind address, and it is local-only for a different reason.
     * Local is the only profile a person starts by hand - {@code spring-boot:run} or {@code java -jar}
     * - on a machine other hosts may be able to route to, and it carries a committed signing secret, a
     * committed operator credential, cleartext transport and ten seeded sign-on identities; on the
     * embedded server's own default of every interface, all of that was published. A suite run creates
     * its server programmatically on an ephemeral port, and the copy of the test overlay under
     * {@code src/test/resources} takes precedence while the suite runs, so a value in the packaged
     * copy would govern nothing. Recorded as DIVERGENCE 4 in the test overlay rather than mirrored,
     * because a setting nothing reads is worse than an asymmetry that is explained.
     */
    private static final List<String> LOCAL_ONLY_KEYS = List.of(
            KEY_DATASOURCE_URL,
            "spring.datasource.username",
            "spring.datasource.password",
            "server.address");

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

    /** The migration location list, which is what excludes the seeds from production. */
    private static final String KEY_FLYWAY_LOCATIONS = "spring.flyway.locations";

    /**
     * The migration target. Production declares the numeric pin the schema location's highest version
     * dictates; the two seeding profiles lift it to {@code latest} for themselves so the seeds apply.
     */
    private static final String KEY_FLYWAY_TARGET = "spring.flyway.target";

    /** Whether the batch framework may create its own metadata tables at start-up. */
    private static final String KEY_BATCH_INITIALIZE_SCHEMA = "spring.batch.jdbc.initialize-schema";

    /**
     * The one location production resolves, carrying the three schema migrations and no seed.
     *
     * <p>THE LOCATION LIST IS THE SEPARATION. The three schema scripts and the two seed scripts sit in
     * sibling directories whose shared parent holds no script at all, so a profile that never names
     * {@link #SEED_LOCATION} resolves no seed - the seeds do not appear in any state at all under that
     * posture, neither applied nor pending nor resolved. The list is the PRIMARY boundary because a
     * location a document never lists is not a value an operator can widen: it produces no script to
     * decline. The numeric ceiling is held ALONGSIDE it rather than in place of it, and is asserted
     * against the versions the schema location actually delivers so that it cannot freeze the schema.
     * See docs/decision-log.md DL-298 for the split and DL-334 for the ceiling.</p>
     */
    private static final String SCHEMA_LOCATION = "classpath:db/migration/schema";

    /** The sibling location only the seeding profiles add, carrying the two seed migrations. */
    private static final String SEED_LOCATION = "classpath:db/migration/seed";

    /**
     * The shared parent of the two delivered locations, which NO document may declare.
     *
     * <p>A Flyway location is scanned RECURSIVELY, so the parent reaches both children: declaring it
     * would resolve all five scripts under every profile and defeat the separation outright. It would
     * also record each script under a name relative to itself, so the history would read
     * {@code schema/V1__create_schema.sql} where every bring-up check reads {@code
     * V1__create_schema.sql}. {@code FlywayConfig} refuses it under every profile, and the assertions
     * below hold every shipped document to the same rule.</p>
     */
    private static final String SHARED_PARENT_LOCATION = "classpath:db/migration";

    /** Class-path folder behind {@link #SCHEMA_LOCATION}, as a resource pattern reads it. */
    private static final String SCHEMA_FOLDER = "db/migration/schema";

    /** Class-path folder behind {@link #SEED_LOCATION}, as a resource pattern reads it. */
    private static final String SEED_FOLDER = "db/migration/seed";

    /** Class-path folder behind {@link #SHARED_PARENT_LOCATION}, which must carry no script itself. */
    private static final String SHARED_PARENT_FOLDER = "db/migration";

    /**
     * The migration target the two SEEDING profiles declare.
     *
     * <p>{@code latest} applies every script the resolved locations carry and stops there, which is what
     * a fixture-bearing profile needs. Production declares {@link #PRODUCTION_TARGET} instead and the
     * code control refuses every alternative to it there, this marker included.</p>
     */
    private static final String ALL_RESOLVED_VERSIONS_TARGET = "latest";

    /**
     * The migration target the shared baseline and the production overlay declare.
     *
     * <p>The highest version {@code classpath:db/migration/schema} delivers. The known objection to a pin
     * - that a number freezes the schema, so a script above it would never be applied while the migration
     * still reported success - is answered by CHECKING the number: {@code FlywayConfigTest} asserts it
     * against the versions the schema location delivers, so raising the schema without raising the pin
     * fails the build. That check is what caught this constant when the invariants script arrived.
     * Recorded in docs/decision-log.md DL-334.</p>
     *
     * <p><strong>It still excludes the two seeds by their number as well as by their directory.</strong>
     * Every schema version sorts below every seed version: 1, 2 and 2.2 are structure and 3 and 4
     * are fixtures. The dotted version is the protected-value invariants, numbered below the seeds
     * deliberately. NUMBERING A SCHEMA SCRIPT 5, above the seeds, breaks three separate controls; DL-343
     * records the numbering rule and DL-349 the
     * invariants script.</p>
     */
    private static final String PRODUCTION_TARGET = "2.2";

    /**
     * The version at which the seeds begin, which is what keeps cross-location apply order correct.
     *
     * <p>Flyway orders by VERSION across every resolved location rather than by location, so a seeding
     * profile applies {@code V1}, {@code V2}, {@code V2_2}, {@code V3}, {@code V4} in that
     * order even though three come from one directory and two from another. A seed numbered below this
     * version would therefore be applied BEFORE the table it inserts into exists. Placement is the
     * production control; this numbering is what keeps the two locations composable in the one direction
     * that matters - no seed before its schema, and no schema script above a seed.</p>
     */
    private static final String FIRST_SEED_VERSION = "3";

    /**
     * Every migration this module delivers, in the order a migration applies them.
     *
     * <p>Three sit in {@link #SCHEMA_LOCATION} and reach every profile - versions 1, 2 and 2.2 -
     * while two sit in {@link #SEED_LOCATION} and reach local and test only. AAP 0.3.1 and 0.4.2 name
     * the first four; the fifth is the protected-value invariants, added by the security remediation
     * recorded in {@code docs/decision-log.md} DL-349, and it is a schema script because production is
     * the profile that needs it most. Version 2.1 is absent: that number is retired with the sign-on
     * attempt ledger of a throttle the legacy transaction has no counterpart for (DL-352), so the gap in
     * the sequence is deliberate.
     *
     * <p><strong>The two sets are separated by their numbers as well as by their directories.</strong>
     * Every schema version sorts below both seed versions, which three separate controls depend on.
     * Numbering a schema script 5, above the seeds, breaks all three; DL-343 records the rule. The
     * directory separation is what {@link #eachLocationCarriesExactlyItsOwnHalf()}
     * and the location assertions above hold.
     */
    private static final List<String> DELIVERED_MIGRATIONS = List.of(
            "V1__create_schema.sql",
            "V2__create_indexes.sql",
            "V3__seed_reference_data.sql",
            "V4__seed_user_security.sql",
            "V2_2__add_protected_value_invariants.sql");

    /** The delivered migrations production applies, being those in the schema location. */
    private static final List<String> SCHEMA_MIGRATIONS = List.of(
            "V1__create_schema.sql",
            "V2__create_indexes.sql",
            "V2_2__add_protected_value_invariants.sql");

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

    /**
     * A placeholder reference and the variable name it reads, outside a comment.
     *
     * <p>The name is everything up to a fallback separator or the closing brace, which is what makes
     * {@code ${NAME}} and {@code ${NAME:fallback}} answer the same variable.
     */
    private static final Pattern PLACEHOLDER_VARIABLE =
            Pattern.compile("\\$\\{([A-Za-z_][A-Za-z_0-9]*)");

    /** Names that make a variable a credential for the purposes of the isolation guard below. */
    private static final Pattern CREDENTIAL_NAMED =
            Pattern.compile("SECRET|PASSWORD|PASSWD|PWD|KEY|TOKEN|CREDENTIAL");

    @Nested
    @DisplayName("no non-production document reads a variable production reads a secret from")
    final class NoNonProductionDocumentSharesAProductionSecretVariable {

        /** Creates the nest. */
        NoNonProductionDocumentSharesAProductionSecretVariable() {
            // Intentionally empty: this nest contributes tests, not state.
        }

        /**
         * The guard, over every shipped non-production document including the shadowed packaged twin.
         *
         * <h2>What went wrong, and why the fallback beside it was no protection</h2>
         *
         * <p>Three documents declared {@code ${CARDDEMO_JWT_SECRET:...}} and
         * {@code ${CARDDEMO_MANAGEMENT_TOKEN:...}} - production's own variable names - each with a
         * self-describing non-production tail. The tail is only consulted when the variable is
         * <em>absent</em>. On any machine where it was present, the profile bound whatever the environment
         * held: a build agent configured to deploy, a developer who had exported a deployment secret, a
         * shell that had sourced an operations profile. The placeholder resolved, the tail was skipped,
         * nothing was logged, and a suite run or a local stack proceeded to sign and verify tokens with a
         * live production credential. Anyone reading the file saw a test-only default and drew the
         * opposite conclusion.
         *
         * <p>Placeholder resolution is by exact key, so separate names are a complete remedy rather than a
         * mitigation, and this test is what holds them separate.
         *
         * <h2>Why the guard lives here rather than beside the property tests</h2>
         *
         * <p>Two documents share the name {@code application-test.yml}, and the packaged one is
         * <em>shadowed</em> on the test class path by the suite copy. A guard that resolved documents by
         * name would therefore never read the deliverable - which is the copy that ships. This class
         * already tells the two apart, so it reads all four: the shared baseline, the local overlay, the
         * packaged test overlay and the suite test overlay.
         *
         * <p>The ban list is derived from the production document rather than written out, so a secret
         * added to production is covered the moment it is added. Comment lines are excluded: the overlays
         * discuss production's variables in prose to explain the asymmetry, and that prose documents the
         * rule rather than breaking it.
         */
        @Test
        @DisplayName("★ neither overlay, nor the shared baseline, nor the packaged twin the suite copy "
                + "shadows, references a production secret variable")
        void noNonProductionDocumentReferencesAProductionSecretVariable() {
            final Set<String> productionSecretVariables = credentialVariablesIn(rawTextOf(PRODUCTION));

            assertThat(productionSecretVariables)
                    .as("the ban list is derived from %s, so it must have found production's secrets - an "
                            + "empty list would make the assertion below pass over nothing", PRODUCTION)
                    .isNotEmpty()
                    .contains("CARDDEMO_JWT_SECRET", "CARDDEMO_MANAGEMENT_TOKEN");

            final Map<String, String> nonProductionDocuments = new LinkedHashMap<>();
            nonProductionDocuments.put(SHARED, rawTextOf(SHARED));
            nonProductionDocuments.put(LOCAL, rawTextOf(LOCAL));
            nonProductionDocuments.put(TEST + " (packaged)", rawTextOf(TEST));
            nonProductionDocuments.put(TEST + " (suite copy)", rawTextOfResource(suiteOverlay()));

            final List<String> shared = new ArrayList<>();
            for (final Map.Entry<String, String> document : nonProductionDocuments.entrySet()) {
                for (final String variable : credentialVariablesIn(document.getValue())) {
                    if (productionSecretVariables.contains(variable)) {
                        shared.add(document.getKey() + " reads " + variable);
                    }
                }
            }

            assertThat(shared)
                    .as("a non-production document reads a variable %s reads a secret from. The fallback "
                            + "beside it is no defence - it is consulted only when the variable is ABSENT, "
                            + "so on any machine holding a deployment credential under that name the "
                            + "credential is what binds, silently and unreported. Give the document its "
                            + "own name: the local overlay reads CARDDEMO_LOCAL_* and the test overlays "
                            + "CARDDEMO_TEST_*. Offending: %s", PRODUCTION, shared)
                    .isEmpty();
        }

        /**
         * The detector finds the variables it is asked about, so its silence above means something.
         *
         * <p>An absence assertion is only as good as the reader behind it. If {@link #PLACEHOLDER_VARIABLE}
         * or {@link #CREDENTIAL_NAMED} stopped matching how this module spells its variables, the guard
         * would report compliance over an empty scan. Both directions are exercised: a reference inside a
         * comment is not a reading, and a non-credential variable is not a secret.
         */
        @Test
        @DisplayName("and the detector behind that absence reads placeholders, ignores prose, and tells a "
                + "credential variable from an ordinary one")
        void theDetectorBehindThatAbsenceIsSound() {
            final String illustration = String.join("\n",
                    "# this line discusses ${CARDDEMO_JWT_SECRET} in prose and reads nothing",
                    "example:",
                    "  secret: ${EXAMPLE_TEST_JWT_SECRET:example-only-not-a-credential}",
                    "  queue: ${EXAMPLE_QUEUE_NAME:example.fifo}",
                    "  bare: ${EXAMPLE_MANAGEMENT_TOKEN}");

            final Set<String> found = credentialVariablesIn(illustration);

            assertThat(found)
                    .as("both credential-named placeholders are found, whether or not they carry a "
                            + "fallback, and the one inside a comment is not")
                    .containsExactlyInAnyOrder("EXAMPLE_TEST_JWT_SECRET", "EXAMPLE_MANAGEMENT_TOKEN")
                    .doesNotContain("CARDDEMO_JWT_SECRET", "EXAMPLE_QUEUE_NAME");
        }
    }

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

        @Test
        @DisplayName("suppresses the driver's server-error detail, because a rejected row's detail is the "
                + "whole attempted row and one of its sinks is the provider's rather than this module's")
        void suppressesServerErrorDetailAtTheDriverBoundary() {
            assertThat(text(SHARED, SERVER_ERROR_DETAIL_PROPERTY))
                    .as("left at the driver's default, a single multi-byte character in ordinary input "
                            + "folds the whole rejected row - a real card number on one path, a freshly "
                            + "computed credential digest on another - into the exception message that "
                            + "the provider's own exception helper logs at ERROR. Sanitising the exception "
                            + "is the only remedy that holds for every sink and every constraint, and it "
                            + "belongs in the shared baseline because it must hold for every profile")
                    .isEqualTo("false");
        }

        @ParameterizedTest(name = "{0} does not restore the driver's server-error detail")
        @ValueSource(strings = {LOCAL, TEST, PRODUCTION})
        @DisplayName("pins that driver property in the shared baseline, so no overlay restores the row "
                + "image by declaring a data-source block of its own")
        void noOverlayRestoresServerErrorDetail(final String overlay) {
            assertThat(resolvedAcrossSharedThen(overlay, SERVER_ERROR_DETAIL_PROPERTY))
                    .as("every overlay declares its own spring.datasource block, and an overlay that "
                            + "reached this key - directly or by replacing the pass-through map it sits "
                            + "in - would reopen the disclosure for that profile alone, which is the "
                            + "hardest form of this defect to notice")
                    .isEqualTo("false");
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
        @DisplayName("and confines the listener to the loopback interface, which is the premise every "
                + "other concession in the overlay rests on")
        void confinesTheListenerToTheLoopbackInterface() {
            // WHY THIS IS A SECURITY ASSERTION AND NOT A TIDINESS ONE. The relaxation asserted above
            // clears the requirement for transport security, the overlay commits a signing secret and an
            // operator credential, it opens anonymous metric scraping, it republishes the interface
            // description, and V4 seeds ten sign-on identities whose password is documented in the estate
            // README. Every one of those is reasoned about on the premise that only this machine can
            // reach the process. The shared baseline declares no server.address, so the embedded server's
            // own default is EVERY interface - and `spring-boot:run` or `java -jar` on a laptop, a shared
            // build agent or a cloud workstation therefore published all of it to anything that could
            // route to the host. Nothing was misconfigured; the wide bind was a framework default and the
            // concessions were this overlay's, and the two were only ever safe together by accident.
            assertThat(resolvedIn(LOCAL, "server.address"))
                    .as("the resolved value, not the reference: an override is available for the one "
                            + "deployment that needs a wider bind, but the DEFAULT decides what an "
                            + "unprepared start does")
                    .isEqualTo("127.0.0.1");
            assertThat(properties(SHARED))
                    .as("and the shared baseline still declares none, so the wide default remains the "
                            + "behaviour of every profile that sits behind an ingress")
                    .doesNotContainKey("server.address");
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

        /**
         * Forwarded headers are honoured only from a peer this deployment names.
         *
         * <p><strong>Why this is a security assertion and not a configuration detail.</strong> A
         * request's address is read as a fact by everything operational - an access log, a diagnostic,
         * an answer to where a request came from. This profile previously selected the framework
         * strategy, which rewrites that address from a caller-supplied header <em>whatever the peer</em>.
         * Because transport security terminates in this process, a client can reach it directly, rotate
         * that header and present every request as a new source, so the deployment reports whatever the
         * caller chose. The native strategy is the trusted-proxy-aware form: the container honours the
         * header only from a peer matching the allow-list, and otherwise leaves the connection's own
         * address in place.
         *
         * <p>Both halves are asserted, because either alone is satisfiable by the wrong configuration.
         * A strategy with no allow-list would trust the framework's broad private-range default; an
         * allow-list under a strategy that rewrites unconditionally would be inert.
         *
         * <p>Recorded as DL-282, whose correction notes that the sign-on attempt governor this setting
         * was first justified by has since been withdrawn as feature expansion (DL-352). The setting
         * stays on its own merits, and so does this assertion: a deployment that believes any caller's
         * headers cannot be trusted to report where anything came from.
         */
        @Test
        @DisplayName("honours forwarded headers only from a named proxy, so a caller cannot choose the "
                + "address this deployment attributes its request to")
        void honoursForwardedHeadersOnlyFromANamedProxy() {
            assertThat(text(PRODUCTION, KEY_FORWARD_HEADERS_STRATEGY))
                    .as("the framework strategy rewrites the request's address from a caller-supplied"
                            + " header whatever the peer, which lets a caller decide what this"
                            + " deployment believes about it. Only the container's trusted-proxy-aware"
                            + " strategy consults the peer first")
                    .isEqualTo(TRUSTED_PROXY_AWARE_STRATEGY);

            String allowList = text(PRODUCTION, KEY_TRUSTED_PROXIES);
            assertThat(allowList)
                    .as("the strategy is only as good as its allow-list, and an absent one would leave"
                            + " the framework's own broad private-range default in force")
                    .isNotNull()
                    .contains(TRUSTED_PROXIES_VARIABLE);
            assertThat(allowList)
                    .as("the allow-list is not a secret, so it carries a default - but a fail-closed"
                            + " one. Loopback alone means that until a deployment names its balancer,"
                            + " a forwarded header is ignored rather than believed. The trade-off - a"
                            + " request attributed to the balancer behind an unnamed one - is recorded"
                            + " in the document and in DL-282")
                    .contains(LOOPBACK_ADDRESS_PATTERN);
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
                // The location LIST is deliberately absent from this list of scalar keys: both copies
                // of the test profile declare a YAML SEQUENCE of two locations, which a property loader
                // flattens into indexed leaves, so a resolver asked for the bare key answers null in
                // both copies and an assertion over it would be vacuous rather than satisfied. The two
                // copies are held to the same location list by
                // bothCopiesResolveTheSameMigrationLocations() instead, which reads the sequence.
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
        @DisplayName("both copies resolve the same two migration locations, so a suite run applies the "
                + "same scripts the packaged profile would")
        void bothCopiesResolveTheSameMigrationLocations() {
            List<String> fromPackaged = locationsIn(propertiesOf(packaged(TEST)));
            List<String> fromOverlay = locationsIn(propertiesOf(suiteOverlay()));

            assertThat(fromPackaged)
                    .as("the packaged copy must declare both delivered locations in apply order. This"
                            + " is read through the sequence-aware reader rather than as a scalar key,"
                            + " because a two-entry list is written as a YAML sequence and a resolver"
                            + " asked for the bare key would answer null in BOTH copies - which would"
                            + " make an equality assertion between two nulls pass while proving"
                            + " nothing")
                    .containsExactly(SCHEMA_LOCATION, SEED_LOCATION);
            assertThat(fromOverlay)
                    .as("and the overlay is the copy in force during a suite run, so an omission of the"
                            + " seed location here would leave every seeded cardinality, every"
                            + " byte-parity comparison and every sign-on test asserting against empty"
                            + " tables")
                    .containsExactly(SCHEMA_LOCATION, SEED_LOCATION);
            assertThat(fromOverlay)
                    .as("the two copies must agree exactly, including order: Flyway resolves the"
                            + " locations in the order given, and a suite that resolved a different set"
                            + " from the artefact would be exercising a different database")
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
                    .as("the file must name all four divergences, so the prose and the assertion"
                            + " describe the same reality")
                    .contains("DIVERGENCE 1")
                    .contains("DIVERGENCE 2")
                    .contains("DIVERGENCE 3")
                    .contains("DIVERGENCE 4");
            assertThat(LOCAL_ONLY_KEYS)
                    .as("DIVERGENCE 1 and DIVERGENCE 4 must really be local-only")
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
                            + " V3__seed_reference_data.sql seeds one hundred customer identifier"
                            + " values - fifty govt_issued_id and fifty cust_ssn - as fixed envelope"
                            + " literals that every non-production profile applies."
                            + " Two divergent development keys - which these documents carried"
                            + " before - make the same seeded row readable under one profile and"
                            + " unreadable under the other, and the failure appears only at the moment"
                            + " something decrypts")
                    .isEqualTo(fromLocal);
            assertThat(fromSuiteOverlay)
                    .as("the overlay is the copy in force during a suite run, so it must agree as"
                            + " well; SeededProtectedIdentifierIT reads its key from here and opens all"
                            + " one hundred seeded values with it")
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
            String seed = contentsOfClassPathScript(SEED_FOLDER, SEED_REFERENCE_DATA);

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
     * <p>Four separate things are asserted here and they fail for different reasons.</p>
     *
     * <p>The first is the exclusion itself, which rests entirely on the PROFILE-SCOPED LOCATION LIST.
     * The three schema scripts ship from {@code classpath:db/migration/schema} and the two seeds from the
     * sibling {@code classpath:db/migration/seed}; their shared parent carries no script at all. The
     * shared baseline and the production overlay declare the schema location ALONE, so a profile silent
     * about seeding resolves no seed - the seed scripts appear in no state whatsoever under that
     * posture, neither applied nor pending nor resolved. The two seeding profiles add the seed location
     * for themselves, which is the only setting that makes {@code V3} and {@code V4} executable at all.
     * Both the declared and the RESOLVED value are asserted, because inheritance is what a running
     * application reads and a per-document reading cannot answer an inheritance question.</p>
     *
     * <p>The location list is the PRIMARY separation, and the production ceiling is held alongside it. A
     * location a document never lists is not a value an operator can widen: it produces no script to
     * decline. A ceiling excludes by ARITHMETIC instead, which reaches a case the location list cannot -
     * a look-alike location presenting a script numbered above the delivered schema. Production pins
     * {@code spring.flyway.target} at the highest version the schema location delivers, currently
     * {@code 2.2}, and the two seeding profiles lift it to {@code latest} for themselves. Left unchecked
     * a pin freezes the schema, because a schema script numbered above it would be resolved, skipped and
     * reported as a successful migration. That failure mode is closed by CHECKING the pin rather than by
     * removing it, so the assertions below hold a location list AND a per-document ceiling, and
     * {@code FlywayConfigTest} holds the pin to the versions the schema location delivers.</p>
     *
     * <p><strong>Both controls exclude the seeds, and neither is redundant.</strong> The delivered
     * schema reaches 2.2 and the seeds are 3 and 4, so the seed versions sit above the pin as well as
     * outside the resolved location. See docs/decision-log.md DL-298 for the split, DL-334 for the
     * ceiling, DL-343 for the numbering rule and DL-349 for the protected-value invariants.</p>
     *
     * <p>The second is the parent, which is the one way this arrangement can be silently defeated. A
     * Flyway location is scanned RECURSIVELY, so {@code classpath:db/migration} reaches BOTH children:
     * a document that named the parent would resolve all five scripts under every profile while looking
     * like a simplification, and would additionally record each script under a name relative to the
     * parent, so the history would read {@code schema/V1__create_schema.sql} where every bring-up check
     * reads {@code V1__create_schema.sql}. {@link #noDocumentDeclaresTheSharedParent(String)} refuses
     * it in a declared value and in an inherited one, and {@link #theSharedParentCarriesNoScriptItself()}
     * refuses the mirror-image defect of a script left behind in the parent - which is exactly what both
     * earlier attempts at this split did, and exactly why both had to be withdrawn.</p>
     *
     * <p>The third is that the placement and the numbering compose into a correct apply order. Flyway
     * orders by VERSION across every resolved location rather than by location, and the property that
     * matters is that no seed is applied before the schema it needs. {@link
     * #thePlacementAndTheNumberingAgree()} therefore requires every script in the seed location to be
     * numbered at or above {@link #FIRST_SEED_VERSION} - a seed numbered {@code V1_2} reads as harmless
     * and would be applied BEFORE the indexes it relies on exist - and requires the schema location to
     * carry every version at or below the seeds. It does <strong>not</strong> require every schema script
     * to sit below the seeds, because version 5 does not: it creates one new table that no seed touches,
     * so applying it after them changes nothing, and {@code FlywayConfigTest} asserts that
     * object-disjointness directly rather than approximating it with an ordering. DL-343.</p>
     *
     * <p>The fourth is truthfulness of the prose. The documents have twice described a topology that had
     * been withdrawn. Correcting such text is never durable on its own, because a correction goes stale
     * the moment a script is added - precisely the moment nobody is reading these comments. So the claim
     * is asserted against the delivered scripts rather than against a second copy of itself: {@link
     * #everyDocumentNamesEveryDeliveredMigration(String)} requires each of the four profile documents to
     * name every delivered script by file name, so adding or removing one fails the build until every
     * document that enumerates them catches up. Adding the fifth script is what exercised that: four
     * documents and two test classes had to be corrected before the build went green again.</p>
     */
    @Nested
    @DisplayName("the documented migration set is the migration set that ships, and the location list "
            + "and the version ceiling both exclude the seeds")
    final class TheDocumentedMigrationSetIsTheDeliveredOne {

        @ParameterizedTest(name = "{0} declares the schema location and nothing else")
        @ValueSource(strings = {SHARED, PRODUCTION})
        @DisplayName("the shared baseline and the production overlay declare the schema location alone, "
                + "so a profile silent about seeding resolves no seed at all")
        void theNonSeedingDocumentsDeclareTheSchemaLocationAlone(final String document) {
            assertThat(declaredLocations(document))
                    .as("%s must resolve exactly one location, %s. This single entry IS the production "
                            + "exclusion: the seeds live in the sibling %s, which this document never "
                            + "names, so they are not applied, not pending and not resolved. A second "
                            + "entry here would seed fifty synthetic customer rows and ten known "
                            + "sign-on identities into production", document, SCHEMA_LOCATION,
                            SEED_LOCATION)
                    .containsExactly(SCHEMA_LOCATION);

            assertThat(resolvedLocations(document))
                    .as("and the value must survive the merge over the baseline, which is the only form "
                            + "of this defect a per-document reading cannot see")
                    .containsExactly(SCHEMA_LOCATION);
        }

        @ParameterizedTest(name = "{0} adds the seed location for itself")
        @ValueSource(strings = {LOCAL, TEST})
        @DisplayName("the two profiles that need the seeds add the seed location explicitly, which is "
                + "the only way a seed script is ever applied")
        void theSeedingDocumentsAddTheSeedLocationThemselves(final String document) {
            assertThat(declaredLocations(document))
                    .as("%s must declare BOTH delivered locations, in apply order, and it must opt in by "
                            + "naming the seed location rather than by inheriting a permissive default. "
                            + "Left at the inherited posture it would leave the seeded cardinalities, "
                            + "the byte-parity comparison and the sign-on tests asserting against an "
                            + "empty database", document)
                    .containsExactly(SCHEMA_LOCATION, SEED_LOCATION);

            assertThat(resolvedLocations(document))
                    .as("and the addition must survive the merge over the baseline. A sequence in an "
                            + "overlay does not merge with a scalar in the baseline - the "
                            + "highest-precedence source wins outright - so the overlay must carry both "
                            + "entries itself, which is what this asserts")
                    .containsExactly(SCHEMA_LOCATION, SEED_LOCATION);
        }

        @Test
        @DisplayName("the suite overlay declares both delivered locations, so a suite run migrates from "
                + "the set the artefact carries")
        void theSuiteOverlayDeclaresBothDeliveredLocations() {
            assertThat(locationsIn(propertiesOf(suiteOverlay())))
                    .as("the overlay is the copy Spring reads during a suite run. It must resolve both "
                            + "delivered locations, which is what gives the fixtures rows to assert "
                            + "against; the packaged copy of the same profile is held to the same list "
                            + "by bothCopiesResolveTheSameMigrationLocations()")
                    .containsExactly(SCHEMA_LOCATION, SEED_LOCATION);
        }

        @Test
        @DisplayName("each delivered location carries exactly its own half of the migration set, and "
                + "neither carries the other's")
        void eachLocationCarriesExactlyItsOwnHalf() {
            assertThat(versionedScriptsIn(SCHEMA_FOLDER))
                    .as("%s must carry exactly the three schema scripts, in apply order. A seed placed "
                            + "here would reach production - it is the location production resolves - "
                            + "and would do so without any document changing", SCHEMA_FOLDER)
                    .containsExactlyElementsOf(SCHEMA_MIGRATIONS);

            assertThat(versionedScriptsIn(SEED_FOLDER))
                    .as("%s must carry exactly the two seeds. A SCHEMA script placed here would stop "
                            + "reaching production silently: the deployment would come up on an "
                            + "incomplete schema and the migration would report success", SEED_FOLDER)
                    .containsExactlyElementsOf(SEED_MIGRATIONS);

            assertThat(recursiveVersionedScriptsIn(SHARED_PARENT_FOLDER))
                    .as("and between them the two locations must account for EVERY delivered script. A "
                            + "recursive scan of the shared parent must find these five and no sixth, "
                            + "because a sixth would be resolved by whichever profile happened to name "
                            + "its directory while appearing in no enumeration anyone reads")
                    .containsExactlyInAnyOrderElementsOf(DELIVERED_MIGRATIONS);
        }

        @Test
        @DisplayName("the shared parent carries no script itself, which is what both earlier attempts "
                + "at this split got wrong")
        void theSharedParentCarriesNoScriptItself() {
            assertThat(versionedScriptsIn(SHARED_PARENT_FOLDER))
                    .as("%s must carry NO script directly. This is the mirror image of declaring the "
                            + "parent, and it is the specific defect that made this split fail twice "
                            + "before: both earlier attempts moved the seeds down a level and left V1 "
                            + "and V2 in the parent, so the parent stayed a real migration source and "
                            + "the separation was never actually in force. A script left here is applied "
                            + "by any profile that names the parent and by no profile that names a "
                            + "child, which is the worst of both", SHARED_PARENT_FOLDER)
                    .isEmpty();
        }

        @ParameterizedTest(name = "{0} declares the ceiling its own posture needs")
        @ValueSource(strings = {SHARED, PRODUCTION, LOCAL, TEST})
        @DisplayName("each document declares the ceiling its own posture needs: the baseline and the "
                + "production overlay pin it, and the two seeding profiles lift it")
        void everyDocumentDeclaresTheCeilingItsPostureNeeds(final String document) {
            final String expected = SHARED.equals(document) || PRODUCTION.equals(document)
                    ? PRODUCTION_TARGET : ALL_RESOLVED_VERSIONS_TARGET;

            assertThat(text(document, KEY_FLYWAY_TARGET))
                    .as("%s must declare %s. The baseline carries the RESTRICTIVE value so a profile "
                            + "silent about migrations inherits the production posture; the two seeding "
                            + "profiles are the only documents that lift it, because a fixture-bearing "
                            + "profile that stopped at the schema would load no fixtures. FlywayConfig "
                            + "refuses any other value under the production profile and LIFTS a low one "
                            + "for a seeding profile", document, expected)
                    .isEqualTo(expected);

            assertThat(resolvedAcrossSharedThen(document, KEY_FLYWAY_TARGET))
                    .as("and the resolved value is what a running application migrates to, so the "
                            + "overlay must still carry it once layered over the baseline")
                    .isEqualTo(expected);
        }

        @Test
        @DisplayName("the locations the documents declare are the locations the code control reads, so "
                + "neither can drift into doing nothing")
        void theDeclaredLocationsAreTheLocationsTheCodeControlReads() {
            assertThat(FlywayConfig.SCHEMA_LOCATION)
                    .as("FlywayConfig admits exactly one location under the production profile and names "
                            + "it itself. Were that constant to name a different directory than these "
                            + "documents declare, the only control between a production migration and "
                            + "ten seeded sign-on identities would be guarding a directory nothing ships "
                            + "from")
                    .isEqualTo(SCHEMA_LOCATION);

            assertThat(FlywayConfig.SEED_LOCATION)
                    .as("and the seed location the code completes for the two seeding profiles must be "
                            + "the one they declare, or a profile that omitted it would be completed to "
                            + "a directory no document names")
                    .isEqualTo(SEED_LOCATION);

            assertThat(FlywayConfig.SHARED_PARENT_LOCATION)
                    .as("and the parent the code REFUSES must be the actual parent of the two, or the "
                            + "refusal would be aimed at a descriptor nobody would ever write")
                    .isEqualTo(SHARED_PARENT_LOCATION);

            assertThat(FlywayConfig.ALL_RESOLVED_VERSIONS_TARGET)
                    .as("and the marker the code lifts a low ceiling to must be the one the two seeding "
                            + "documents declare, or a lifted profile would migrate to a value no "
                            + "document names")
                    .isEqualTo(ALL_RESOLVED_VERSIONS_TARGET);

            assertThat(FlywayConfig.PRODUCTION_TARGET)
                    .as("and the pin the code re-applies under production must be the one the baseline "
                            + "and the production overlay declare, or a deployment would be corrected to "
                            + "a ceiling no document names")
                    .isEqualTo(PRODUCTION_TARGET);

            assertThat(FlywayConfig.PRODUCTION_PROFILE)
                    .as("the code control is scoped by profile name; a name matching no profile would "
                            + "leave the configuration posture unaccompanied")
                    .isEqualTo("prod");
        }

        @Test
        @DisplayName("the delivered set is exactly the five named scripts, across the two locations")
        void theDeliveredScriptsAreExactlyTheFiveNamed() {
            final List<String> delivered = new ArrayList<>(versionedScriptsIn(SCHEMA_FOLDER));
            delivered.addAll(versionedScriptsIn(SEED_FOLDER));

            assertThat(delivered)
                    .as("the two locations together must carry exactly the five named scripts. The "
                            + "comparison is order-insensitive because the two directories are scanned "
                            + "one after the other while Flyway orders by VERSION across both: reading "
                            + "the schema folder then the seed folder yields 1, 2, 5, 3, 4, which is a "
                            + "property of this traversal rather than of the apply order. A script added "
                            + "without a corresponding edit to the profile documents would leave them "
                            + "naming a shorter set than ships")
                    .containsExactlyInAnyOrderElementsOf(DELIVERED_MIGRATIONS);
        }

        @Test
        @DisplayName("the placement and the numbering agree, so the two locations compose into one "
                + "correct apply order")
        void thePlacementAndTheNumberingAgree() {
            assertThat(versionedScriptsIn(SEED_FOLDER))
                    .as("every seed must be numbered at or above %s. Flyway orders by version across "
                            + "every resolved location rather than by location, so a seed numbered V1_2 "
                            + "reads as harmless and would be applied BEFORE V2 creates the indexes it "
                            + "relies on", FIRST_SEED_VERSION)
                    .isNotEmpty()
                    .allMatch(ConfigurationProfileBaselineTest::isAtOrAboveFirstSeedVersion);

            assertThat(versionedScriptsIn(SCHEMA_FOLDER))
                    .as("and the schema location must carry every version at or below the seeds, so "
                            + "nothing a seed depends on can arrive after it. The converse is NOT "
                            + "asserted: a schema script numbered ABOVE the seeds is permitted, because "
                            + "version 5 is one. It creates a single new table that no seed touches, so "
                            + "applying it after them changes nothing, and FlywayConfigTest asserts that "
                            + "object-disjointness directly rather than approximating it here. DL-343")
                    .isNotEmpty()
                    .containsAll(SCHEMA_MIGRATIONS.stream()
                            .filter(script -> !isAtOrAboveFirstSeedVersion(script))
                            .toList());

            assertThat(SEED_MIGRATIONS)
                    .as("and the stated seed split must agree with the arithmetic, so this file cannot "
                            + "claim one boundary while the scripts carry another")
                    .allMatch(ConfigurationProfileBaselineTest::isAtOrAboveFirstSeedVersion);
            assertThat(SCHEMA_MIGRATIONS)
                    .as("while the stated schema split must contain every version below the seeds and "
                            + "may contain versions above them")
                    .containsAll(SCHEMA_MIGRATIONS.stream()
                            .filter(script -> !isAtOrAboveFirstSeedVersion(script))
                            .toList());
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
                                    + "set and states which location each script comes from; a script "
                                    + "missing from that enumeration is a script whose production "
                                    + "applicability nobody stated", document, script)
                            .contains(script));
        }

        @ParameterizedTest(name = "{0} declares neither the shared parent nor an undelivered location")
        @ValueSource(strings = {SHARED, LOCAL, TEST, PRODUCTION})
        @DisplayName("no document reaches for the shared parent or for a directory that ships nothing, "
                + "in a declared value or in an inherited one")
        void noDocumentDeclaresTheSharedParent(final String document) {
            for (final List<String> locations
                    : List.of(declaredLocations(document), resolvedLocations(document))) {
                assertThat(locations)
                        .as("%s must name only delivered locations. The shared parent %s is the one that "
                                + "matters: it is scanned RECURSIVELY, so naming it resolves BOTH "
                                + "children and applies the seeds under every profile while reading as a "
                                + "simplification, and it records each script under a name relative to "
                                + "itself so the history stops matching what every bring-up check reads",
                                document, SHARED_PARENT_LOCATION)
                        .isNotEmpty()
                        .doesNotContain(SHARED_PARENT_LOCATION)
                        .allSatisfy(location -> assertThat(location)
                                .isIn(SCHEMA_LOCATION, SEED_LOCATION));
            }
        }

        @Test
        @DisplayName("the suite overlay is governed by the same rule, because it is read in preference "
                + "to the packaged copy during a suite run")
        void theSuiteOverlayDeclaresNoSharedParent() {
            assertThat(locationsIn(propertiesOf(suiteOverlay())))
                    .doesNotContain(SHARED_PARENT_LOCATION)
                    .containsExactly(SCHEMA_LOCATION, SEED_LOCATION);
        }

        @ParameterizedTest(name = "{0} lets Spring Batch provision its own metadata tables")
        @ValueSource(strings = {SHARED, LOCAL, TEST, PRODUCTION})
        @DisplayName("every profile resolves the batch schema initializer to always, so Spring Batch is "
                + "the single owner of the framework metadata tables in every environment")
        void everyProfileLetsSpringBatchProvisionItsOwnTables(final String document) {
            assertThat(resolvedAcrossSharedThen(document, KEY_BATCH_INITIALIZE_SCHEMA))
                    .as("AAP 0.3.1 assigns the six framework tables and three sequences to Spring Batch "
                            + "and the eleven application tables to V1__create_schema.sql, which is what "
                            + "keeps the delivered migration inventory at exactly five scripts. The value "
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
     * so this class and the guard cannot disagree about what production requires. RESTATING A SUBSET here -
     * three keys, or five - is what leaves the TLS, region and trace-collector settings unasserted.
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
     * Reports whether a delivered migration is numbered at or above the first seed version.
     *
     * <p>This is the ordering the migration tool itself performs, reproduced rather than trusted.
     * Flyway sorts by VERSION across every resolved location rather than by location, so the two
     * sibling directories only compose into a correct apply order while every schema script is numbered
     * below the first seed and every seed at or above it. Comparing numerically matters - {@code V1_1}
     * is version 1.1 and sits below 3, while a lexicographic comparison of the file names would place it
     * after {@code V3}.</p>
     *
     * @param script the migration file name, such as {@code V1_1__create_batch_metadata.sql}
     * @return {@code true} when the script's version is at or above {@link #FIRST_SEED_VERSION}
     * @throws IllegalArgumentException when the name carries no parsable version
     */
    private static boolean isAtOrAboveFirstSeedVersion(final String script) {
        return compareVersions(versionOf(script), FIRST_SEED_VERSION) >= 0;
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
     * Collects the credential-named variables one document actually reads.
     *
     * <p>Comment lines are skipped before matching, so a document that explains production's variables in
     * prose is not reported as reading them. The name is taken up to a fallback separator, so
     * {@code ${NAME}} and {@code ${NAME:fallback}} answer the same variable - which matters because it is
     * precisely the defaulted form that looked safe and was not.
     *
     * @param  document the document's full text, comments included
     * @return the credential-named variables it reads, in first-seen order
     */
    private static Set<String> credentialVariablesIn(final String document) {
        final Set<String> variables = new LinkedHashSet<>();
        for (final String line : document.lines().toList()) {
            if (line.stripLeading().startsWith("#")) {
                continue;
            }
            final Matcher reference = PLACEHOLDER_VARIABLE.matcher(line);
            while (reference.find()) {
                final String name = reference.group(1);
                if (CREDENTIAL_NAMED.matcher(name).find()) {
                    variables.add(name);
                }
            }
        }
        return variables;
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
