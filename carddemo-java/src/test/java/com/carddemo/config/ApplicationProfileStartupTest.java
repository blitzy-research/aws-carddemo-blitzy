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
import static org.assertj.core.api.Assertions.catchThrowable;

import com.carddemo.service.SensitiveFieldEncryptionService;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
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
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * Starts a context per profile and asserts what that context <em>resolves</em>, which is a different
 * question from what a document declares.
 *
 * <h2>Why a started context, when a sibling already reads the same documents</h2>
 *
 * <p>{@link ConfigurationProfileBaselineTest} loads each shipped document as a property source and
 * asserts its values. That is the right level for "this file declares X", and it is deliberately
 * unaffected by binding, by profile activation and by placeholder resolution. Three things it therefore
 * cannot answer are the three that decide what a deployment actually does.
 *
 * <ul>
 *   <li><strong>Which documents a profile activates.</strong> A per-document reading cannot tell whether
 *       {@code spring.profiles.active=prod} reaches {@code application-prod.yml} at all, nor what the
 *       baseline-then-overlay merge produces. Here the profile is set on the environment and the
 *       framework's own configuration-data loading resolves the rest, exactly as it does at start-up.</li>
 *   <li><strong>What binding produces.</strong> A declared value and a bound value are not the same
 *       object: relaxed binding, type conversion and validation all sit between them. The migration
 *       target is asserted off {@link FlywayProperties} - the type the deployed application binds - so
 *       the assertion is about the value the migration tool receives.</li>
 *   <li><strong>Whether a missing secret stops the start.</strong> This is the requirement's actual
 *       wording: production resolves every secret from the environment with no fallback, so a missing
 *       one must fail the start <em>rather than silently binding a placeholder</em>. Only a resolution
 *       can be observed failing; a document cannot.</li>
 * </ul>
 *
 * <h2>What is registered, and what is deliberately not</h2>
 *
 * <p>The contexts here are narrow by construction. No auto-configuration is enabled beyond
 * {@link PropertyPlaceholderAutoConfiguration}, which is the one the deployed application relies on to
 * make {@code @Value} resolution strict. Every other bean registered below is a type this module or the
 * framework ships - {@link FlywayProperties}, {@link JwtProperties}, {@link JwtTokenProvider},
 * {@link JpaAuditConfig}, {@link SensitiveFieldEncryptionService} - so no test-authored
 * configuration-properties type stands in for a shipped one.
 *
 * <p>A full production start is not attempted, and the reason is worth stating rather than leaving as
 * an omission: production points at a real database and a real trace collector, so a full start cannot
 * succeed here for reasons that have nothing to do with configuration. Every value would then be
 * asserted against a context that failed, and "all twelve variables supplied" would be unassertable.
 * The narrow context makes both directions observable.
 *
 * <h2>Three ways a missing production variable manifests, all three covered</h2>
 *
 * <p>The twelve variables production requires are read by different kinds of consumer, and they fail
 * differently. All twelve are covered by the uniform resolution assertion, because strict resolution
 * against the environment is what every consumer of the key ultimately performs. Two are additionally
 * covered at refresh level through a strict {@code @Value} consumer, and one more through a consumer
 * that validates what binding produced:
 *
 * <ul>
 *   <li>{@code carddemo.security.field-encryption.key} is read by
 *       {@link SensitiveFieldEncryptionService}'s constructor as a strict {@code @Value}, so its absence
 *       fails the refresh naming the placeholder.</li>
 *   <li>{@code carddemo.security.jwt.secret} is bound rather than injected, and
 *       configuration-properties binding resolves placeholders <em>leniently</em> - an absent variable
 *       binds the literal text {@code ${CARDDEMO_JWT_SECRET}} and satisfies a not-blank constraint.
 *       That is precisely the silent-placeholder failure the requirement names, and
 *       {@link JwtTokenProvider} is what converts it into a start-up failure, because twenty-two
 *       characters of placeholder text cannot meet the algorithm's key-length floor. Asserting it here
 *       pins the fact that the conversion exists.</li>
 *   <li>The remaining variables - the data source triple, the region, the four transport-security
 *       values, the trace endpoint and the queue name - are read by framework or SDK components that a
 *       narrow context does not build. Their absence is asserted at the resolution the container
 *       performs on their behalf.</li>
 * </ul>
 *
 * <h2>Exhaustiveness rather than a hand-picked sample</h2>
 *
 * <p>{@link ProductionResolvesEverySecretFromTheEnvironment#theListUnderTestIsEveryNoFallbackVariable()}
 * reads the production document and requires the set of variables written without a fallback to equal
 * the set this test exercises. A thirteenth variable added to the document then fails the build rather
 * than joining it untested, which is the failure mode a hand-maintained list always eventually has.
 *
 * <h2>Which {@code application-test.yml} is read</h2>
 *
 * <p>Two documents carry that name: one under {@code src/main/resources} for a deployed test profile and
 * one under {@code src/test/resources} for this suite. Test classes precede main classes on the test
 * class path, so the second is the one resolved here - which is correct, because it is the one every
 * test in this module actually runs under. Both declare the same lifted migration pin, so the
 * assertions below hold for either.
 *
 * <h2>Independent expectations</h2>
 *
 * <p>Every expected value is a hand-typed literal, with one deliberate exception that is itself an
 * assertion: the delivered migration versions are read from the class path, so the claim that the
 * production pin withholds the seeds is made against the migrations that ship rather than against a
 * second copy of their numbering.
 *
 * <h2>Provenance</h2>
 *
 * <p>The configuration under test derives from the CardDemo COBOL estate at checkout
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. No legacy source text is reproduced here.
 *
 * @since 1.0.0
 */
@DisplayName("Shipped configuration profiles, resolved by a started context")
final class ApplicationProfileStartupTest {

    /** The profile whose posture the shared baseline already is. */
    private static final String PRODUCTION = "prod";

    /** The profile bound to the local container stack. */
    private static final String LOCAL = "local";

    /** The profile bound to Testcontainers-provided endpoints. */
    private static final String TEST = "test";

    /** The property that selects a profile, set on the environment before the loader runs. */
    private static final String KEY_ACTIVE_PROFILES = "spring.profiles.active";

    /** The one location every profile migrates from. */
    private static final String MIGRATION_LOCATION = "classpath:db/migration";

    /** The pin that applies the schema and leaves both seeds pending. */
    private static final String SCHEMA_ONLY_TARGET = "2";

    /** The lifted pin, spelled as the migration tool's own head sentinel. */
    private static final String HEAD_TARGET = "latest";

    /** The complete delivered numbering, asserted rather than assumed. */
    private static final List<Integer> EXPECTED_DELIVERED_VERSIONS = List.of(1, 2, 3, 4);

    /** The two migrations a production migration must never apply. */
    private static final List<String> SEEDS_WITHHELD_FROM_PRODUCTION =
            List.of("V3__seed_reference_data.sql", "V4__seed_user_security.sql");

    /** The production document, read as text for the exhaustiveness assertion. */
    private static final String PRODUCTION_DOCUMENT = "application-prod.yml";

    /** A reference written with no fallback: {@code ${NAME}} and nothing more. */
    private static final Pattern NO_FALLBACK_REFERENCE = Pattern.compile("\\$\\{([A-Z0-9_]+)}");

    /** How a versioned migration file name opens. */
    private static final Pattern MIGRATION_VERSION = Pattern.compile("^V(\\d+)__");

    // Configuration keys whose resolution is asserted.

    /** The migration location list. */
    private static final String KEY_FLYWAY_LOCATIONS = "spring.flyway.locations";

    /** The highest migration version a profile applies. */
    private static final String KEY_FLYWAY_TARGET = "spring.flyway.target";

    /** Whether a migration may drop the schema it manages. */
    private static final String KEY_FLYWAY_CLEAN_DISABLED = "spring.flyway.clean-disabled";

    /** The data source location. */
    private static final String KEY_DATASOURCE_URL = "spring.datasource.url";

    /** The schema-management strategy, which must never generate DDL. */
    private static final String KEY_DDL_AUTO = "spring.jpa.hibernate.ddl-auto";

    /** Whether jobs fire at context start. */
    private static final String KEY_BATCH_JOB_ENABLED = "spring.batch.job.enabled";

    /** The published management surface. */
    private static final String KEY_EXPOSURE = "management.endpoints.web.exposure.include";

    /** The health-probe detail setting. */
    private static final String KEY_SHOW_DETAILS = "management.endpoint.health.show-details";

    /** The health-probe component setting. */
    private static final String KEY_SHOW_COMPONENTS = "management.endpoint.health.show-components";

    /** The generated interface description switch. */
    private static final String KEY_API_DOCS_ENABLED = "springdoc.api-docs.enabled";

    /** The interface-description viewer switch. */
    private static final String KEY_SWAGGER_UI_ENABLED = "springdoc.swagger-ui.enabled";

    /** The transport requirement. */
    private static final String KEY_REQUIRE_HTTPS = "carddemo.security.require-https";

    /** A static cloud access key, which production must resolve from no source. */
    private static final String KEY_CLOUD_ACCESS_KEY = "spring.cloud.aws.credentials.access-key";

    /** A static cloud secret key, which production must resolve from no source. */
    private static final String KEY_CLOUD_SECRET_KEY = "spring.cloud.aws.credentials.secret-key";

    /** Endpoint overrides that exist for the emulator and must not reach production. */
    private static final List<String> EMULATOR_ENDPOINT_KEYS = List.of(
            "spring.cloud.aws.endpoint",
            "spring.cloud.aws.s3.endpoint",
            "spring.cloud.aws.sqs.endpoint",
            "spring.cloud.aws.sns.endpoint");

    /** The closed management surface, spelled once. */
    private static final String CLOSED_EXPOSURE = "health,info,prometheus";

    /**
     * One production requirement: an environment variable, the shipped key that reads it, and a value
     * standing in for what a deployment would supply.
     *
     * <p>The stand-in value is never compared against anything the application computes. It is echoed
     * back through the resolved key, which is what proves the reference has no fallback quietly sitting
     * behind it.</p>
     *
     * @param variable the environment variable name as the production document writes it
     * @param key      the configuration key whose value is that reference and nothing else
     * @param supplied the value a deployment would provide
     */
    private record RequiredSecret(String variable, String key, String supplied) {

        @Override
        public String toString() {
            return this.variable;
        }
    }

    /** A 32-byte key, Base64-encoded, which is the width the field-encryption service requires. */
    private static final String SUPPLIED_ENCRYPTION_KEY =
            Base64.getEncoder().encodeToString("supplied-field-encryption-key!!!".getBytes(
                    StandardCharsets.UTF_8));

    /** A signing secret comfortably above the algorithm's key-length floor. */
    private static final String SUPPLIED_JWT_SECRET =
            "supplied-signing-secret-of-more-than-thirty-two-bytes";

    /**
     * Every variable the production document references without a fallback, paired with the key that
     * carries the reference.
     *
     * <p>The list is exercised in both directions - absent and supplied - and its completeness is
     * asserted against the document rather than trusted.</p>
     */
    private static final List<RequiredSecret> PRODUCTION_REQUIRED_SECRETS = List.of(
            new RequiredSecret("CARDDEMO_DB_URL", KEY_DATASOURCE_URL,
                    "jdbc:postgresql://database.internal:5432/carddemo"),
            new RequiredSecret("CARDDEMO_DB_USERNAME", "spring.datasource.username",
                    "carddemo-application"),
            new RequiredSecret("CARDDEMO_DB_PASSWORD", "spring.datasource.password",
                    "supplied-database-password"),
            new RequiredSecret("AWS_REGION", "spring.cloud.aws.region.static", "eu-west-2"),
            new RequiredSecret("CARDDEMO_TLS_KEYSTORE", "server.ssl.key-store",
                    "file:/etc/carddemo/keystore.p12"),
            new RequiredSecret("CARDDEMO_TLS_KEYSTORE_PASSWORD", "server.ssl.key-store-password",
                    "supplied-keystore-password"),
            new RequiredSecret("CARDDEMO_TLS_KEYSTORE_TYPE", "server.ssl.key-store-type", "PKCS12"),
            new RequiredSecret("CARDDEMO_TLS_KEY_ALIAS", "server.ssl.key-alias", "carddemo"),
            new RequiredSecret("OTEL_EXPORTER_OTLP_TRACES_ENDPOINT",
                    "management.otlp.tracing.endpoint",
                    "http://collector.internal:4318/v1/traces"),
            new RequiredSecret("CARDDEMO_SQS_QUEUE", "carddemo.aws.sqs.job-submission-queue",
                    "JOBS.fifo"),
            new RequiredSecret("CARDDEMO_JWT_SECRET", "carddemo.security.jwt.secret",
                    SUPPLIED_JWT_SECRET),
            new RequiredSecret("CARDDEMO_FIELD_ENCRYPTION_KEY",
                    "carddemo.security.field-encryption.key", SUPPLIED_ENCRYPTION_KEY));

    /** Registers the framework's own migration settings type, which the deployed application binds. */
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(FlywayProperties.class)
    static class MigrationSettings {
    }

    /** Registers this module's own token settings type, validation included. */
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(JwtProperties.class)
    static class TokenSettings {
    }

    @Nested
    @DisplayName("the migration target a profile resolves")
    final class TheMigrationTargetAProfileResolves {

        @Test
        @DisplayName("production binds the schema-only pin and the one location, so a production "
                + "migration stops at the schema")
        void productionBindsTheSchemaOnlyPin() {
            runner(MigrationSettings.class, PRODUCTION).run(context -> {
                FlywayProperties bound = context.getBean(FlywayProperties.class);

                assertThat(bound.getTarget())
                        .as("this is the value the migration tool receives; the seeds are withheld by "
                                + "it and by nothing else")
                        .isEqualTo(SCHEMA_ONLY_TARGET);
                assertThat(bound.getLocations())
                        .as("one location, so there is no second place a script could sit outside the "
                                + "pin's reach")
                        .containsExactly(MIGRATION_LOCATION);
                assertThat(bound.isCleanDisabled())
                        .as("a production migration must not be able to drop the schema it manages")
                        .isTrue();
            });
        }

        @Test
        @DisplayName("a profile-less resolution is already the production posture, so an overlay silent "
                + "about migrations inherits the pin rather than the convenience")
        void aProfileLessResolutionIsAlreadyTheProductionPosture() {
            runner(MigrationSettings.class).run(context -> {
                FlywayProperties bound = context.getBean(FlywayProperties.class);

                assertThat(bound.getTarget()).isEqualTo(SCHEMA_ONLY_TARGET);
                assertThat(bound.getLocations()).containsExactly(MIGRATION_LOCATION);
                assertThat(bound.isCleanDisabled()).isTrue();
            });
        }

        @ParameterizedTest(name = "the {0} profile lifts the pin to the head")
        @ValueSource(strings = {LOCAL, TEST})
        @DisplayName("the two profiles that need reference rows resolve the head, which is the only way "
                + "a seed is ever applied")
        void theTwoSeedingProfilesResolveTheHead(final String profile) {
            runner(MigrationSettings.class, profile).run(context -> {
                FlywayProperties bound = context.getBean(FlywayProperties.class);

                assertThat(bound.getTarget())
                        .as("%s must lift the pin explicitly; inheriting a lifted one would mean every "
                                + "profile seeded", profile)
                        .isEqualTo(HEAD_TARGET);
                assertThat(bound.getLocations()).containsExactly(MIGRATION_LOCATION);
                assertThat(bound.isCleanDisabled())
                        .as("%s iterates on migrations, so dropping and re-applying is permitted here "
                                + "and only here", profile)
                        .isFalse();
            });
        }

        @Test
        @DisplayName("the pin production resolves stops below every delivered seed, asserted against "
                + "the migrations that ship rather than against their numbering restated")
        void theResolvedPinStopsBelowEveryDeliveredSeed() {
            List<Integer> delivered = deliveredMigrationVersions();

            assertThat(delivered)
                    .as("the delivered numbering is the basis of every claim below; a migration added "
                            + "or removed must be accounted for here first")
                    .containsExactlyElementsOf(EXPECTED_DELIVERED_VERSIONS);

            runner(MigrationSettings.class, PRODUCTION).run(context -> {
                int pin = Integer.parseInt(context.getBean(FlywayProperties.class).getTarget());

                assertThat(delivered.stream().filter(version -> version <= pin).toList())
                        .as("production applies the schema pair and nothing else")
                        .containsExactly(1, 2);
                assertThat(delivered.stream().filter(version -> version > pin).toList())
                        .as("production leaves %s pending; for the second of those, applying it would "
                                + "mean ten known sign-on identities in production",
                                SEEDS_WITHHELD_FROM_PRODUCTION)
                        .containsExactly(3, 4);
            });
        }

        @Test
        @DisplayName("the pin production resolves is the pin the production profile re-applies in code, "
                + "so neither control is silently doing nothing")
        void theResolvedPinIsThePinTheCodeReapplies() {
            runner(MigrationSettings.class, PRODUCTION).run(context -> assertThat(
                    context.getBean(FlywayProperties.class).getTarget())
                    .as("FlywayConfig re-applies %s whenever the %s profile is active. A configured "
                            + "value naming a different version would leave one of the two controls "
                            + "ineffective", FlywayConfig.SCHEMA_ONLY_TARGET,
                            FlywayConfig.PRODUCTION_PROFILE)
                    .isEqualTo(FlywayConfig.SCHEMA_ONLY_TARGET));

            assertThat(FlywayConfig.PRODUCTION_PROFILE)
                    .as("the code control is scoped by profile name; a name matching no profile would "
                            + "leave the configured pin unaccompanied")
                    .isEqualTo(PRODUCTION);
        }

        @ParameterizedTest(name = "{0} resolves exactly one migration location")
        @ValueSource(strings = {PRODUCTION, LOCAL, TEST})
        @DisplayName("every profile resolves one location, so no profile can see a script another "
                + "cannot")
        void everyProfileResolvesOneLocation(final String profile) {
            runner(MigrationSettings.class, profile).run(context ->
                    assertThat(context.getBean(FlywayProperties.class).getLocations())
                            .containsExactly(MIGRATION_LOCATION));
        }
    }

    @Nested
    @DisplayName("production resolves every secret from the environment and defaults none")
    final class ProductionResolvesEverySecretFromTheEnvironment {

        @ParameterizedTest(name = "{0} has no fallback behind it")
        @MethodSource("com.carddemo.config.ApplicationProfileStartupTest#productionRequiredSecrets")
        @DisplayName("with one variable withheld and every other supplied, the key it carries cannot be "
                + "resolved, and the failure names the variable")
        void theAbsenceOfOneVariableMakesItsKeyUnresolvable(final RequiredSecret withheld) {
            runner(MigrationSettings.class, PRODUCTION, suppliedExcept(withheld)).run(context -> {
                Environment environment = context.getEnvironment();

                Throwable failure = catchThrowable(() -> environment.getProperty(withheld.key()));

                assertThat(failure)
                        .as("%s must resolve from %s alone. A fallback anywhere in the chain - a "
                                + "default in the reference, a value in the shared baseline, a leak "
                                + "from a convenience profile - would make this resolution succeed and "
                                + "would put an unintended value into a production deployment",
                                withheld.key(), withheld.variable())
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining(withheld.variable());
            });
        }

        @Test
        @DisplayName("with every variable supplied, each key resolves to exactly the supplied value, so "
                + "nothing is substituted or decorated on the way through")
        void everySuppliedVariableIsTheResolvedValue() {
            runner(MigrationSettings.class, PRODUCTION, allSupplied()).run(context -> {
                Environment environment = context.getEnvironment();

                for (final RequiredSecret required : PRODUCTION_REQUIRED_SECRETS) {
                    assertThat(environment.getProperty(required.key()))
                            .as("%s must be the environment's value verbatim; a key that transformed "
                                    + "it would break a deployment in a way no configuration review "
                                    + "would show", required.key())
                            .isEqualTo(required.supplied());
                }
            });
        }

        @Test
        @DisplayName("the list under test is every no-fallback variable the production document "
                + "references, so a variable added later cannot join untested")
        void theListUnderTestIsEveryNoFallbackVariable() {
            Set<String> documented = noFallbackVariablesOf(PRODUCTION_DOCUMENT);
            Set<String> underTest = PRODUCTION_REQUIRED_SECRETS.stream()
                    .map(RequiredSecret::variable)
                    .collect(LinkedHashSet::new, Set::add, Set::addAll);

            assertThat(documented)
                    .as("a hand-maintained list of required secrets goes stale the moment a "
                            + "requirement is added. Reading the document instead makes the addition "
                            + "fail the build until it is exercised here too")
                    .containsExactlyInAnyOrderElementsOf(underTest);
        }

        @Test
        @DisplayName("every variable under test is written without a fallback in the document, so none "
                + "of the absence assertions above can be vacuous")
        void everyVariableUnderTestIsWrittenWithoutAFallback() {
            String document = rawTextOf(PRODUCTION_DOCUMENT);

            for (final RequiredSecret required : PRODUCTION_REQUIRED_SECRETS) {
                assertThat(document)
                        .as("%s must appear as a bare reference. Written with a default it would still "
                                + "resolve when withheld, and the assertion that it cannot would then "
                                + "be asserting the wrong thing", required.variable())
                        .contains("${" + required.variable() + "}")
                        .doesNotContain("${" + required.variable() + ":");
            }
        }
    }

    @Nested
    @DisplayName("a missing secret fails the start itself wherever a consumer reads it")
    final class AMissingSecretFailsTheStartItself {

        @Test
        @DisplayName("the field-encryption service refuses to be built without its key, and the failure "
                + "names the placeholder rather than binding it")
        void theFieldEncryptionServiceRefusesToStartWithoutItsKey() {
            runner(SensitiveFieldEncryptionService.class, PRODUCTION).run(context ->
                    assertThat(context)
                            .as("the key is injected as a strict placeholder, so an absent variable "
                                    + "stops the refresh instead of sealing regulated identifiers under "
                                    + "the literal text of its own reference")
                            .hasFailed()
                            .getFailure()
                            .rootCause()
                            .isInstanceOf(IllegalArgumentException.class)
                            .hasMessageContaining("CARDDEMO_FIELD_ENCRYPTION_KEY"));
        }

        @Test
        @DisplayName("the field-encryption service is built once its key is supplied, so the failure "
                + "above is about the missing value and not about the wiring")
        void theFieldEncryptionServiceIsBuiltOnceItsKeyIsSupplied() {
            runner(SensitiveFieldEncryptionService.class, PRODUCTION,
                    "CARDDEMO_FIELD_ENCRYPTION_KEY=" + SUPPLIED_ENCRYPTION_KEY).run(context ->
                    assertThat(context)
                            .hasNotFailed()
                            .hasSingleBean(SensitiveFieldEncryptionService.class));
        }

        @Test
        @DisplayName("binding the token settings without the secret variable yields the placeholder text "
                + "rather than a secret, which is the silent failure the requirement names")
        void bindingWithoutTheSecretVariableYieldsThePlaceholderText() {
            runner(TokenSettings.class, PRODUCTION).run(context -> {
                JwtProperties bound = context.getBean(JwtProperties.class);

                assertThat(bound.secret())
                        .as("configuration-properties binding resolves placeholders leniently, so a "
                                + "not-blank constraint is satisfied by the reference's own text. This "
                                + "is asserted rather than assumed because it is the reason the "
                                + "consumer below has to do the checking")
                        .isEqualTo("${CARDDEMO_JWT_SECRET}");
                assertThat(bound.hasSecret())
                        .as("the presence predicate cannot distinguish a secret from a placeholder; "
                                + "only a length check can")
                        .isTrue();
            });
        }

        @Test
        @DisplayName("the token provider refuses to be built from that placeholder text, which is what "
                + "converts the silent binding into a start-up failure")
        void theTokenProviderRefusesToBeBuiltFromPlaceholderText() {
            runner(new Class<?>[] {TokenSettings.class, JpaAuditConfig.class, JwtTokenProvider.class},
                    PRODUCTION).run(context -> assertThat(context)
                    .as("without this check a production deployment would mint and accept tokens signed "
                            + "with a value printed in the repository")
                    .hasFailed()
                    .getFailure()
                    .rootCause()
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(JwtProperties.PREFIX + ".secret")
                    .hasMessageContaining("32"));
        }

        @Test
        @DisplayName("the token provider is built once the secret variable is supplied, so the failure "
                + "above is about the missing value and not about the wiring")
        void theTokenProviderIsBuiltOnceTheSecretIsSupplied() {
            runner(new Class<?>[] {TokenSettings.class, JpaAuditConfig.class, JwtTokenProvider.class},
                    PRODUCTION, "CARDDEMO_JWT_SECRET=" + SUPPLIED_JWT_SECRET).run(context ->
                    assertThat(context)
                            .hasNotFailed()
                            .hasSingleBean(JwtTokenProvider.class));
        }
    }

    @Nested
    @DisplayName("a profile-less start cannot reach a running state")
    final class AProfileLessStartCannotReachARunningState {

        @Test
        @DisplayName("the shared baseline declares no signing secret, so binding the token settings "
                + "fails and names the constraint")
        void theSharedBaselineDeclaresNoSigningSecret() {
            runner(TokenSettings.class).run(context -> assertThat(context)
                    .as("starting with no profile must not be a usable state: the baseline is the "
                            + "closed posture, and a closed posture has no credentials in it")
                    .hasFailed()
                    .getFailure()
                    .hasMessageContaining(JwtProperties.PREFIX));
        }

        @Test
        @DisplayName("the shared baseline declares no data source location, so nothing resolves one")
        void theSharedBaselineDeclaresNoDataSourceLocation() {
            runner(MigrationSettings.class).run(context ->
                    assertThat(context.getEnvironment().getProperty(KEY_DATASOURCE_URL))
                            .as("a defaulted location would point every profile-less start at whichever "
                                    + "database that default named")
                            .isNull());
        }
    }

    @Nested
    @DisplayName("production inherits no convenience from the profiles that take them")
    final class ProductionInheritsNoConvenience {

        @Test
        @DisplayName("no static cloud credential resolves under production, while the local profile "
                + "resolves both - so the absence is a decision and not an accident")
        void noStaticCloudCredentialResolvesUnderProduction() {
            runner(MigrationSettings.class, PRODUCTION, allSupplied()).run(context -> {
                Environment environment = context.getEnvironment();

                assertThat(environment.getProperty(KEY_CLOUD_ACCESS_KEY))
                        .as("with no static credential present the software development kit resolves a "
                                + "role; with one present it would use it, and the emulator placeholder "
                                + "the local profile carries must never be what it finds")
                        .isNull();
                assertThat(environment.getProperty(KEY_CLOUD_SECRET_KEY)).isNull();
            });

            runner(MigrationSettings.class, LOCAL).run(context -> {
                Environment environment = context.getEnvironment();

                assertThat(environment.getProperty(KEY_CLOUD_ACCESS_KEY))
                        .as("the local profile does declare a static credential, which is what makes "
                                + "the production absence above a meaningful assertion rather than a "
                                + "key nobody ever sets")
                        .isNotBlank();
                assertThat(environment.getProperty(KEY_CLOUD_SECRET_KEY)).isNotBlank();
            });
        }

        @Test
        @DisplayName("no emulator endpoint override resolves under production, while the local profile "
                + "resolves every one of them")
        void noEmulatorEndpointOverrideResolvesUnderProduction() {
            runner(MigrationSettings.class, PRODUCTION, allSupplied()).run(context -> {
                Environment environment = context.getEnvironment();

                for (final String key : EMULATOR_ENDPOINT_KEYS) {
                    assertThat(environment.getProperty(key))
                            .as("%s exists so a client can be redirected at a local emulator. Resolved "
                                    + "in production it would silently send every call somewhere that "
                                    + "is not the cloud", key)
                            .isNull();
                }
            });

            runner(MigrationSettings.class, LOCAL).run(context -> {
                Environment environment = context.getEnvironment();

                for (final String key : EMULATOR_ENDPOINT_KEYS) {
                    assertThat(environment.getProperty(key)).isNotBlank();
                }
            });
        }

        @Test
        @DisplayName("production resolves the closed management and schema posture, restated by its own "
                + "overlay rather than inherited silently")
        void productionResolvesTheClosedPosture() {
            runner(MigrationSettings.class, PRODUCTION, allSupplied()).run(context -> {
                Environment environment = context.getEnvironment();

                assertThat(environment.getProperty(KEY_EXPOSURE)).isEqualTo(CLOSED_EXPOSURE);
                assertThat(environment.getProperty(KEY_SHOW_DETAILS)).isEqualTo("never");
                assertThat(environment.getProperty(KEY_SHOW_COMPONENTS)).isEqualTo("never");
                assertThat(environment.getProperty(KEY_API_DOCS_ENABLED, Boolean.class)).isFalse();
                assertThat(environment.getProperty(KEY_SWAGGER_UI_ENABLED, Boolean.class)).isFalse();
                assertThat(environment.getProperty(KEY_REQUIRE_HTTPS, Boolean.class))
                        .as("a token and a decrypted field both travel over this transport")
                        .isTrue();
                assertThat(environment.getProperty(KEY_FLYWAY_CLEAN_DISABLED, Boolean.class)).isTrue();
                assertThat(environment.getProperty(KEY_DDL_AUTO))
                        .as("schema arrives only through a reviewed migration, never from a mapping")
                        .isEqualTo("validate");
                assertThat(environment.getProperty(KEY_BATCH_JOB_ENABLED, Boolean.class))
                        .as("restarting a deployment must not fire a posting run")
                        .isFalse();
                assertThat(environment.getProperty(KEY_FLYWAY_TARGET)).isEqualTo(SCHEMA_ONLY_TARGET);
                assertThat(environment.getProperty(KEY_FLYWAY_LOCATIONS))
                        .isEqualTo(MIGRATION_LOCATION);
            });
        }

        @Test
        @DisplayName("the local profile resolves each concession it takes, so the closed production "
                + "values above are a posture and not the only value these keys can hold")
        void theLocalProfileResolvesEachConcessionItTakes() {
            runner(MigrationSettings.class, LOCAL).run(context -> {
                Environment environment = context.getEnvironment();

                assertThat(environment.getProperty(KEY_SHOW_DETAILS)).isEqualTo("always");
                assertThat(environment.getProperty(KEY_API_DOCS_ENABLED, Boolean.class)).isTrue();
                assertThat(environment.getProperty(KEY_REQUIRE_HTTPS, Boolean.class)).isFalse();
                assertThat(environment.getProperty(KEY_FLYWAY_CLEAN_DISABLED, Boolean.class)).isFalse();
                assertThat(environment.getProperty(KEY_DDL_AUTO))
                        .as("the one setting the local profile does not relax: even locally, schema "
                                + "arrives through a migration")
                        .isEqualTo("validate");
            });
        }
    }

    // Providers.

    /**
     * The production requirements, one argument per variable.
     *
     * @return one argument per required secret
     */
    private static Stream<Arguments> productionRequiredSecrets() {
        return PRODUCTION_REQUIRED_SECRETS.stream().map(Arguments::of);
    }

    // Helpers.

    /**
     * Builds a runner over the shipped documents with no profile active.
     *
     * @param bean the single bean or configuration class to register
     * @return a runner that has not been started
     */
    private static ApplicationContextRunner runner(final Class<?> bean) {
        return runner(new Class<?>[] {bean}, null);
    }

    /**
     * Builds a runner over the shipped documents with one profile active.
     *
     * @param bean     the single bean or configuration class to register
     * @param profile  the profile to activate
     * @param supplied environment values to make resolvable, as {@code NAME=value} entries
     * @return a runner that has not been started
     */
    private static ApplicationContextRunner runner(final Class<?> bean, final String profile,
                                                   final String... supplied) {
        return runner(new Class<?>[] {bean}, profile, supplied);
    }

    /**
     * Builds a runner over the shipped documents.
     *
     * <p>The configuration-data initialiser is what makes this a start rather than a file read: it
     * performs the framework's own document discovery, profile activation and overlay merging against
     * the environment the context will use. The placeholder auto-configuration is registered because it
     * is what makes {@code @Value} resolution strict in the deployed application; without it an absent
     * variable would be injected as its own reference text and nothing would fail.</p>
     *
     * @param beans    the bean or configuration classes to register
     * @param profile  the profile to activate, or {@code null} for a profile-less resolution
     * @param supplied environment values to make resolvable, as {@code NAME=value} entries
     * @return a runner that has not been started
     */
    private static ApplicationContextRunner runner(final Class<?>[] beans, final String profile,
                                                   final String... supplied) {
        List<String> values = new ArrayList<>();
        if (profile != null) {
            values.add(KEY_ACTIVE_PROFILES + "=" + profile);
        }
        values.addAll(List.of(supplied));

        return new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withConfiguration(AutoConfigurations.of(PropertyPlaceholderAutoConfiguration.class))
                .withUserConfiguration(beans)
                .withPropertyValues(values.toArray(String[]::new));
    }

    /**
     * Returns every production requirement as a resolvable value.
     *
     * @return {@code NAME=value} entries, one per required secret
     */
    private static String[] allSupplied() {
        return PRODUCTION_REQUIRED_SECRETS.stream()
                .map(required -> required.variable() + "=" + required.supplied())
                .toArray(String[]::new);
    }

    /**
     * Returns every production requirement as a resolvable value except one.
     *
     * @param withheld the requirement to leave unresolvable
     * @return {@code NAME=value} entries for every other required secret
     */
    private static String[] suppliedExcept(final RequiredSecret withheld) {
        return PRODUCTION_REQUIRED_SECRETS.stream()
                .filter(required -> !required.equals(withheld))
                .map(required -> required.variable() + "=" + required.supplied())
                .toArray(String[]::new);
    }

    /**
     * Reads the environment variables a document references with no fallback.
     *
     * <p>Comment lines are skipped. The production document explains its own no-fallback convention in
     * prose and writes an illustrative reference while doing so; counting that illustration as a
     * requirement would make this assertion fail for a reason that is not about configuration.</p>
     *
     * @param document the class-path name of the document to read
     * @return the referenced variable names, in the order the document writes them
     */
    private static Set<String> noFallbackVariablesOf(final String document) {
        Set<String> found = new LinkedHashSet<>();
        for (final String line : rawTextOf(document).lines().toList()) {
            if (line.stripLeading().startsWith("#")) {
                continue;
            }
            Matcher reference = NO_FALLBACK_REFERENCE.matcher(line);
            while (reference.find()) {
                found.add(reference.group(1));
            }
        }
        return found;
    }

    /**
     * Reads a shipped configuration document as text, comments included.
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
            throw new UncheckedIOException("shipped configuration document is unreadable: " + document,
                    failure);
        }
    }

    /**
     * Reads the versions of the migrations delivered on the migration location, ascending.
     *
     * <p>Read from the class path rather than the source tree, so the answer is what a migration would
     * find rather than what a directory listing suggests.</p>
     *
     * @return the delivered versions, ascending; never {@code null}
     */
    private static List<Integer> deliveredMigrationVersions() {
        try {
            Resource[] found = new PathMatchingResourcePatternResolver()
                    .getResources("classpath*:db/migration/V*__*.sql");
            return Stream.of(found)
                    .map(Resource::getFilename)
                    .filter(name -> name != null)
                    .map(MIGRATION_VERSION::matcher)
                    .filter(Matcher::find)
                    .map(matcher -> Integer.valueOf(matcher.group(1)))
                    .sorted()
                    .toList();
        } catch (IOException failure) {
            throw new UncheckedIOException("migration location is unreadable", failure);
        }
    }
}
