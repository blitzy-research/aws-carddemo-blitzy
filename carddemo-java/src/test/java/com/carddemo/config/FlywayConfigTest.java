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
import static org.assertj.core.api.Assertions.assertThatNoException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.Location;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.callback.Callback;
import org.flywaydb.core.api.callback.Event;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.boot.autoconfigure.flyway.FlywayConfigurationCustomizer;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.MergedAnnotations;
import org.springframework.core.annotation.MergedAnnotations.SearchStrategy;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.mock.env.MockEnvironment;

import com.carddemo.service.SeededIdentifierSealingCallback;
import com.carddemo.service.SensitiveFieldEncryptionService;

/**
 * Verifies the migration layout and production seed-exclusion guarantees enforced by
 * {@link FlywayConfig}, using the shipped configuration and resources rather than restating the
 * implementation.
 *
 * <h3>Delivered migration contract</h3>
 *
 * <p>The only versioned scripts are {@code V1__create_schema.sql},
 * {@code V2__create_indexes.sql}, {@code V3__seed_reference_data.sql} and
 * {@code V4__seed_user_security.sql}. The first two create the application schema and ship from
 * {@code classpath:db/migration/schema}; the latter two provide non-production fixtures and ship from
 * the sibling {@code classpath:db/migration/seed}. Their shared parent {@code classpath:db/migration}
 * holds no script at all and is refused as a location under every profile, because Flyway scans a
 * location recursively.
 *
 * <p>Production excludes the seed scripts by NOT RESOLVING THE LOCATION THEY LIVE IN, and that is the
 * only mechanism. A version ceiling of {@code 2} used to be a second one; it is now refused, because it
 * excluded the seeds by arithmetic and froze the schema at the same version - a {@code V5} schema script
 * would never have been applied and the migration would still have reported success. A location a
 * profile never lists is not a value an operator can widen and it constrains no future version, so the
 * assertions here hold the location list and hold production to declaring no number at all. See
 * docs/decision-log.md DL-298.
 *
 * <h3>Security rationale</h3>
 *
 * <p>The reference-data migration supplies 626 fixture rows across nine tables, including fifty
 * synthetic customer records. The sign-on migration supplies ten known identities, five of them
 * administrator-capable, whose BCrypt digests derive from one well-known source credential. Either
 * script reaching production would therefore be a credential and privacy incident, not a harmless
 * data-quality defect. The location-versus-ceiling decision and its historical divergence are recorded
 * in {@code docs/decision-log.md}, at decision {@code DL-298} which supersedes {@code DL-102},
 * {@code DL-108}, {@code DL-111}, {@code DL-116}, {@code DL-119} and {@code DL-127}; this test encodes
 * the delivered control rather than admitting either mechanism.
 *
 * <h3>Suite boundary</h3>
 *
 * <p>This surefire-tier test reads configuration and SQL as text and never starts a database or runs a
 * migration. {@link SeedMigrationIT} owns applied versions, schema history and seeded volumes against a
 * real PostgreSQL server, {@link ProductionSeedRejectionCallbackIT} owns the production refusal driven
 * through real migrations, and {@code com.carddemo.batch.BatchMetadataProvisioningIT} together with the
 * repository integration tier owns runtime table counts.
 * {@link JpaAuditConfigTest} owns audit and version-column shape; {@code BatchConfigTest} owns batch
 * metadata initialization; {@code ObservabilityConfigTest} owns migration logger configuration; and
 * {@code JwtPropertiesTest} owns the complete production-placeholder sweep. Those outcomes are
 * cross-referenced here rather than duplicated.
 */
@DisplayName("Schema-evolution guard: seeds are unreachable from production, and cleartext is sealed")
class FlywayConfigTest {

    /**
     * A Base64 key decoding to exactly thirty-two bytes, matching the shape the encryption service
     * requires. It is a test constant and protects nothing.
     */
    private static final String TEST_KEY = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";

    /** Text a hostile profile list or location would carry, so an echo is unmistakable. */
    private static final String HOSTILE_MARKER = "ZZ-INJECTED-MARKER-ZZ";

    /** A carriage return: the first half of a forged log record. */
    private static final char CARRIAGE_RETURN = '\r';

    /** A line feed: what a log reader treats as ending a record. */
    private static final char LINE_FEED = '\n';

    /**
     * Asserts that a message carries no raw line terminator, so it cannot end one log record and
     * begin another.
     *
     * @param message the diagnostic to inspect
     */
    private static void assertCarriesNoRawTerminator(final String message) {
        assertThat(message)
                .as("a diagnostic may not carry a raw line terminator")
                .doesNotContain(String.valueOf(CARRIAGE_RETURN))
                .doesNotContain(String.valueOf(LINE_FEED));
    }

    /**
     * Builds a context runner over the class under test with the one collaborator it needs, and with
     * the three migration settings a production start-up must declare.
     *
     * <p>The three settings are supplied because {@code ApplicationContextRunner} loads no profile
     * document, so a runner-built production context carries none of them - and the guard published by
     * the class under test refuses a production start-up that has not stated all three. Supplying them
     * here is what lets the co-activation assertions below test co-activation rather than repeatedly
     * re-testing the migration-source guard, which has its own nest.
     *
     * @return a runner that registers the configuration, an encryption service and the canonical
     *         production migration settings
     */
    private static ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withUserConfiguration(FlywayConfig.class)
                .withBean(SensitiveFieldEncryptionService.class,
                        () -> new SensitiveFieldEncryptionService(TEST_KEY))
                .withPropertyValues(
                        "spring.flyway.enabled=true",
                        "spring.flyway.target=" + FlywayConfig.PRODUCTION_TARGET,
                        "spring.flyway.locations=" + FlywayConfig.SCHEMA_LOCATION);
    }

    @Nested
    @DisplayName("the delivered migration layout is the complete four-script contract")
    class TheDeliveredMigrationLayout {

        @Test
        @DisplayName("exactly the four canonical versioned SQL filenames are delivered")
        void exactlyTheFourCanonicalVersionedSqlFilenamesAreDelivered() {
            final List<String> actualPaths = migrationResourcePaths();
            final List<String> actualNames = actualPaths.stream()
                    .map(path -> path.substring(path.lastIndexOf('/') + 1))
                    .sorted()
                    .toList();

            assertThat(actualPaths)
                    .as("expected exactly four SQL resources beneath db/migration, but resolved %s",
                            actualPaths)
                    .hasSize(4);
            assertThat(actualNames)
                    .as("expected the four canonical filenames, including each double underscore; "
                            + "actual resource paths were %s", actualPaths)
                    .containsExactlyInAnyOrder(
                            "V1__create_schema.sql",
                            "V2__create_indexes.sql",
                            "V3__seed_reference_data.sql",
                            "V4__seed_user_security.sql");
            assertThat(actualNames)
                    .as("every delivered migration resource must have the .sql extension; actual "
                            + "filenames were %s", actualNames)
                    .allMatch(name -> name.endsWith(".sql"));
        }

        @Test
        @DisplayName("every script sits one level down, in the schema location or the seed location, "
                + "and never in their shared parent")
        void everyScriptSitsInOneOfTheTwoDeliveredLocations() {
            final List<String> actualPaths = migrationResourcePaths();

            assertThat(actualPaths)
                    .as("expected every migration script inside one of the two delivered "
                            + "sub-directories; actual paths were %s", actualPaths)
                    .isNotEmpty()
                    .allSatisfy(path -> assertThat(path)
                            .as("%s must sit in schema/ or seed/. A script left in the shared parent "
                                    + "is the defect that made this split ineffective twice before: "
                                    + "the parent stays a real migration source, so it is applied by "
                                    + "any profile naming the parent and by no profile naming a child",
                                    path)
                            .matches("^(schema|seed)/[^/]+$"));

            assertThat(actualPaths.stream().filter(path -> path.startsWith("schema/")).sorted().toList())
                    .as("the schema location carries the two scripts production applies")
                    .containsExactly("schema/V1__create_schema.sql", "schema/V2__create_indexes.sql");
            assertThat(actualPaths.stream().filter(path -> path.startsWith("seed/")).sorted().toList())
                    .as("and the seed location carries the two only local and test apply. A SEED in "
                            + "schema/ would reach production without any document changing; a SCHEMA "
                            + "script in seed/ would silently stop reaching it")
                    .containsExactly("seed/V3__seed_reference_data.sql",
                            "seed/V4__seed_user_security.sql");
        }

        @Test
        @DisplayName("db/migration holds exactly the two delivered sub-directories and no script of "
                + "its own")
        void theSharedParentHoldsTwoSubdirectoriesAndNoScript() {
            final Path migrationDirectory =
                    Path.of("src", "main", "resources", "db", "migration");

            assertThat(migrationDirectory)
                    .as("the migration directory must exist at %s", migrationDirectory)
                    .isDirectory();

            try (Stream<Path> entries = Files.list(migrationDirectory)) {
                final List<String> names = entries
                        .map(entry -> entry.getFileName().toString()
                                + (Files.isDirectory(entry) ? "/" : ""))
                        .sorted()
                        .toList();

                assertThat(names)
                        .as("db/migration is a parent and nothing else: exactly the two delivered "
                                + "sub-directories, no script and no third directory. A script here "
                                + "would be reached only by a profile declaring the parent - which "
                                + "FlywayConfig refuses under every profile - so it would be applied by "
                                + "nothing while appearing to be delivered. Found %s", names)
                        .containsExactly("schema/", "seed/");
            } catch (final IOException exception) {
                throw new AssertionError(
                        "unable to list " + migrationDirectory + " while asserting the split layout",
                        exception);
            }
        }

        @Test
        @DisplayName("the banned stale seed filename is absent from the migration classpath")
        void theBannedStaleSeedFilenameIsAbsent() {
            final List<String> staleResources =
                    resourceDescriptions("classpath*:db/migration/**/V3__seed_data.sql");

            assertThat(staleResources)
                    .as("V3__seed_data.sql is a banned stale prior-run name; expected no matching "
                            + "classpath resource, but found %s", staleResources)
                    .isEmpty();
        }

        @Test
        @DisplayName("Flyway alone owns schema initialization")
        void flywayAloneOwnsSchemaInitialization() {
            final List<String> sqlInitializationKeys = new ArrayList<>();
            for (final String document : List.of(
                    "application.yml",
                    "application-prod.yml",
                    "application-local.yml",
                    "application-test.yml")) {
                yamlProperties(document).keySet().stream()
                        .filter(key -> key.startsWith("spring.sql.init"))
                        .map(key -> document + ":" + key)
                        .forEach(sqlInitializationKeys::add);
            }

            assertThat(new ClassPathResource("schema.sql").exists())
                    .as("expected no classpath schema.sql because versioned Flyway scripts own schema "
                            + "creation")
                    .isFalse();
            assertThat(new ClassPathResource("data.sql").exists())
                    .as("expected no classpath data.sql because versioned Flyway scripts own data "
                            + "initialization")
                    .isFalse();
            assertThat(sqlInitializationKeys)
                    .as("expected no shipped profile document to declare spring.sql.init keys; "
                            + "actual document:key matches were %s", sqlInitializationKeys)
                    .isEmpty();
        }

        @Test
        @DisplayName("no repeatable or undo migration bypasses the four-version inventory")
        void noRepeatableOrUndoMigrationBypassesTheInventory() {
            final List<String> actualPaths = migrationResourcePaths();

            assertThat(actualPaths)
                    .as("expected no R__ repeatable or U__ undo migration; actual paths were %s",
                            actualPaths)
                    .noneMatch(path -> {
                        final String name = path.substring(path.lastIndexOf('/') + 1);
                        return name.startsWith("R__") || name.startsWith("U__");
                    });
        }
    }

    @Nested
    @DisplayName("the shipped profile documents never disable migration safeguards")
    class TheProfileDocumentsNeverDisableMigrations {

        @Test
        @DisplayName("Flyway is enabled or default-enabled and baselining is never enabled")
        void flywayIsNeverDisabledAndBaseliningIsNeverEnabled() {
            for (final String document : List.of(
                    "application.yml",
                    "application-prod.yml",
                    "application-local.yml",
                    "application-test.yml")) {
                final Map<String, Object> properties = yamlProperties(document);
                final Object enabled = properties.get("spring.flyway.enabled");
                final Object baseline = properties.get("spring.flyway.baseline-on-migrate");

                assertThat(enabled == null || booleanValue(enabled))
                        .as("expected spring.flyway.enabled in %s to be true or absent, but it was %s",
                                document, enabled)
                        .isTrue();
                assertThat(baseline == null || !booleanValue(baseline))
                        .as("expected spring.flyway.baseline-on-migrate in %s to be false or absent, "
                                + "but it was %s", document, baseline)
                        .isTrue();
            }
        }

        @Test
        @DisplayName("the two non-seeding documents declare the schema location alone and the two "
                + "seeding ones add the seed location, and none declares their shared parent")
        void everyDocumentDeclaresTheLocationsItsProfileNeeds() {
            for (final String document : List.of("application.yml", "application-prod.yml")) {
                final List<String> actual = declaredFlywayLocations(yamlProperties(document));

                assertThat(actual)
                        .as("%s must declare exactly %s. That single entry IS the production exclusion: "
                                + "the seeds ship from the sibling %s, which this document never names; "
                                + "actual locations were %s", document, FlywayConfig.SCHEMA_LOCATION,
                                FlywayConfig.SEED_LOCATION, actual)
                        .containsExactly(FlywayConfig.SCHEMA_LOCATION);
            }

            for (final String document : List.of("application-local.yml", "application-test.yml")) {
                final List<String> actual = declaredFlywayLocations(yamlProperties(document));

                assertThat(actual)
                        .as("%s must declare BOTH delivered locations, in apply order. Naming the seed "
                                + "location is the whole of the opt-in, and dropping it leaves every "
                                + "fixture asserting against an empty result set; actual locations were "
                                + "%s", document, actual)
                        .containsExactly(FlywayConfig.SCHEMA_LOCATION, FlywayConfig.SEED_LOCATION);
            }

            for (final String document : List.of(
                    "application.yml",
                    "application-prod.yml",
                    "application-local.yml",
                    "application-test.yml")) {
                assertThat(declaredFlywayLocations(yamlProperties(document)))
                        .as("and no document may declare the shared parent %s. Scanning is recursive, "
                                + "so it reaches both children - and it records every script under a "
                                + "name relative to itself, breaking the migration names the bring-up "
                                + "check reads out of the history table",
                                FlywayConfig.SHARED_PARENT_LOCATION)
                        .doesNotContain(FlywayConfig.SHARED_PARENT_LOCATION);
            }
        }

        @Test
        @DisplayName("the location list separates production from seeded, and each document declares "
                + "the ceiling its own posture needs")
        void theLocationListSeparatesProductionFromSeededProfiles() {
            final Map<String, String> expectedTargets = Map.of(
                    "application.yml", FlywayConfig.PRODUCTION_TARGET,
                    "application-prod.yml", FlywayConfig.PRODUCTION_TARGET,
                    "application-local.yml", FlywayConfig.ALL_RESOLVED_VERSIONS_TARGET,
                    "application-test.yml", FlywayConfig.ALL_RESOLVED_VERSIONS_TARGET);
            for (final Map.Entry<String, String> expected : expectedTargets.entrySet()) {
                assertThat(declaredFlywayTarget(expected.getKey()))
                        .as("%s must declare the ceiling %s. The shared baseline carries the RESTRICTIVE "
                                + "value so a profile silent about migrations inherits the production "
                                + "posture, the production overlay re-states it, and the two seeding "
                                + "profiles are the only documents that lift it - which is the direction "
                                + "the default has to run in", expected.getKey(), expected.getValue())
                        .isEqualTo(expected.getValue());
            }

            assertThat(declaredFlywayLocations(yamlProperties("application.yml")))
                    .as("and the SEPARATION is here: the shared baseline declares the schema location "
                            + "by itself, so a profile silent about seeding inherits the production "
                            + "posture rather than a seeding one. The direction of that default is the "
                            + "control")
                    .containsExactly(FlywayConfig.SCHEMA_LOCATION)
                    .doesNotContain(FlywayConfig.SEED_LOCATION);
        }
    }

    @Nested
    @DisplayName("the configuration contributes policy but no migration machinery")
    class TheConfigurationContributesNoMigrationMachinery {

        @ParameterizedTest(name = "the {0} profile contributes policy and no infrastructure")
        @ValueSource(strings = {"prod", "local", "test"})
        @DisplayName("every profile contributes the customizer without constructing Flyway, a "
                + "strategy or a data source")
        void everyProfileContributesPolicyAndNoInfrastructure(final String profile) {
            runner().withPropertyValues("spring.profiles.active=" + profile).run(context -> {
                assertThat(context)
                        .as("%s must start with the policy configuration itself available", profile)
                        .hasNotFailed()
                        .hasSingleBean(FlywayConfig.class)
                        .hasSingleBean(FlywayConfigurationCustomizer.class);
                assertThat(context)
                        .as("%s must leave Flyway construction to auto-configuration", profile)
                        .doesNotHaveBean(Flyway.class);
                assertThat(context)
                        .as("%s must not replace Flyway's migration lifecycle", profile)
                        .doesNotHaveBean(FlywayMigrationStrategy.class);
                assertThat(context)
                        .as("%s must not construct a data source merely to enforce migration policy",
                                profile)
                        .doesNotHaveBean(javax.sql.DataSource.class);
            });
        }
    }

    @Nested
    @DisplayName("the seed-exclusion security control is in force")
    class TheSeedExclusionControlIsInForce {

        @Test
        @DisplayName("production scans the schema location alone while local and test scan both, which "
                + "is the whole of the separation")
        void productionScansTheSchemaLocationWhileSeedingProfilesScanBoth() {
            final FluentConfiguration production =
                    effectiveConfiguration("prod", "application-prod.yml");

            assertThat(configurationLocations(production))
                    .as("THE SEED-EXCLUSION MECHANISM IS THIS LIST. The effective production scan must "
                            + "be exactly %s: the seed scripts ship from the sibling %s, and a scan that "
                            + "reached it would apply fifty synthetic customer rows and ten known "
                            + "sign-on identities. Reconcile against docs/decision-log.md DL-298",
                            FlywayConfig.SCHEMA_LOCATION, FlywayConfig.SEED_LOCATION)
                    .containsExactly(FlywayConfig.SCHEMA_LOCATION);
            assertThat(configurationLocations(production))
                    .as("and the shared parent must be absent, because it is scanned recursively and "
                            + "would reach the seed directory through its own name")
                    .doesNotContain(FlywayConfig.SHARED_PARENT_LOCATION, FlywayConfig.SEED_LOCATION);
            assertThat(production.getTarget())
                    .as("while the ceiling is the second control rather than a restatement of the first: "
                            + "pinned at %s, the highest version the schema location delivers, so a "
                            + "seed-numbered script would be declined by its NUMBER even if some other "
                            + "location ever presented one", FlywayConfig.PRODUCTION_TARGET)
                    .isEqualTo(MigrationVersion.fromVersion(FlywayConfig.PRODUCTION_TARGET));

            for (final Map.Entry<String, String> profile : Map.of(
                    "local", "application-local.yml",
                    "test", "application-test.yml").entrySet()) {
                final FluentConfiguration effective =
                        effectiveConfiguration(profile.getKey(), profile.getValue());

                assertThat(configurationLocations(effective))
                        .as("%s must scan BOTH delivered locations, which is what distinguishes it from "
                                + "production. Scanning only the schema location would leave every "
                                + "fixture asserting against an empty result set", profile.getKey())
                        .containsExactly(FlywayConfig.SCHEMA_LOCATION, FlywayConfig.SEED_LOCATION);
                assertThat(configurationLocations(effective))
                        .as("%s must not reach for the parent either: it resolves the same four scripts "
                                + "under names relative to itself", profile.getKey())
                        .doesNotContain(FlywayConfig.SHARED_PARENT_LOCATION);
            }
        }

        @Test
        @DisplayName("the contributed code refuses a production scan that reaches the seed location, "
                + "rather than merely documenting that it should not")
        void theContributedCodeEnforcesRatherThanMerelyDeclaresTheControl() {
            final MockEnvironment production = new MockEnvironment();
            production.setActiveProfiles("prod");
            final FluentConfiguration unsafe = new FluentConfiguration()
                    .locations(FlywayConfig.SCHEMA_LOCATION, FlywayConfig.SEED_LOCATION)
                    .target(FlywayConfig.PRODUCTION_TARGET);
            boolean refused = false;

            try {
                new FlywayConfig().migrationScopeResolvingCustomizer(production).customize(unsafe);
            } catch (IllegalStateException refusal) {
                refused = true;
                assertCarriesNoRawTerminator(refusal.getMessage());
            }

            assertThat(refused)
                    .as("The code-level production control did not refuse a scan that reaches the seed "
                            + "location, which is the ONLY separating mechanism this layout has. It must "
                            + "refuse rather than silently rewrite the list: rewriting would leave the "
                            + "misconfiguration in the source to survive into the next deployment. "
                            + "Production must not inherit sample data or seeded credentials; see "
                            + "docs/decision-log.md DL-298")
                    .isTrue();

            final FluentConfiguration viaParent = new FluentConfiguration()
                    .locations(FlywayConfig.SHARED_PARENT_LOCATION)
                    .target(FlywayConfig.PRODUCTION_TARGET);

            assertThatExceptionOfType(IllegalStateException.class)
                    .as("and it must refuse the parent too, which is the same widening spelled as a "
                            + "simplification")
                    .isThrownBy(() -> new FlywayConfig()
                            .migrationScopeResolvingCustomizer(production).customize(viaParent))
                    .satisfies(refusal -> assertCarriesNoRawTerminator(refusal.getMessage()));
        }

        @Test
        @DisplayName("the scope is re-stated in code rather than left to the document, and one "
                + "customizer does it so no second bean can repair what this one must refuse")
        void theScopeIsRestatedInCodeByTheOneCustomizer() {
            final MockEnvironment production = new MockEnvironment();
            production.setActiveProfiles(FlywayConfig.PRODUCTION_PROFILE);
            final FluentConfiguration bound = new FluentConfiguration()
                    .locations(FlywayConfig.SCHEMA_LOCATION)
                    .target(FlywayConfig.PRODUCTION_TARGET);

            new FlywayConfig().migrationScopeResolvingCustomizer(production).customize(bound);

            assertThat(bound.getTarget())
                    .as("the target must be set by this code path and not merely inherited from the "
                            + "overlay that supplied it")
                    .isEqualTo(MigrationVersion.fromVersion(FlywayConfig.PRODUCTION_TARGET));
            assertThat(configurationLocations(bound))
                    .as("while the location list is CHECKED and never rewritten: a customizer that "
                            + "added a location here would widen the very control it exists to enforce")
                    .containsExactly(FlywayConfig.SCHEMA_LOCATION);
            assertThat(Arrays.stream(FlywayConfig.class.getDeclaredMethods())
                    .filter(method -> FlywayConfigurationCustomizer.class
                            .isAssignableFrom(method.getReturnType()))
                    .count())
                    .as("a second customizer bean would be applied in an unspecified order relative "
                            + "to this one, and if it ran first it would quietly repair a hostile "
                            + "ceiling that this one exists to refuse")
                    .isEqualTo(1L);
        }
    }

    @Nested
    @DisplayName("the delivered scripts state the layout-adjacent guarantees themselves")
    class TheDeliveredScriptsSaySoThemselves {

        @Test
        @DisplayName("V1 creates the eleven application tables without foreign or surrogate keys")
        void v1CreatesOnlyTheBusinessKeySchema() {
            final String sql = migrationSql("V1__create_schema.sql")
                    .toLowerCase(Locale.ROOT);
            final List<String> tableNames = capturedGroups(
                    Pattern.compile("\\bcreate\\s+table\\s+([a-z_]+)\\s*\\("), sql);
            final List<String> surrogateKeyMarkers = capturedGroups(Pattern.compile(
                    "\\b(smallserial|serial|bigserial|identity|generated|sequence|uuid)\\b"), sql);

            assertThat(tableNames)
                    .as("expected V1__create_schema.sql to create exactly the eleven named "
                            + "application tables; actual CREATE TABLE targets were %s", tableNames)
                    .containsExactlyInAnyOrder(
                            "account",
                            "card",
                            "customer",
                            "card_cross_reference",
                            "transaction",
                            "daily_transaction",
                            "transaction_category_balance",
                            "disclosure_group",
                            "transaction_type",
                            "transaction_category",
                            "user_security");
            assertThat(Pattern.compile("\\bforeign\\s+key\\b").matcher(sql).find())
                    .as("expected V1__create_schema.sql to declare no FOREIGN KEY; those constraints "
                            + "belong to V2__create_indexes.sql")
                    .isFalse();
            assertThat(Pattern.compile("\\breferences\\b").matcher(sql).find())
                    .as("expected V1__create_schema.sql to contain no REFERENCES clause")
                    .isFalse();
            assertThat(surrogateKeyMarkers)
                    .as("expected V1__create_schema.sql to use business keys only; surrogate-key "
                            + "markers found after comment stripping were %s", surrogateKeyMarkers)
                    .isEmpty();
        }

        @Test
        @DisplayName("V2 declares exactly three nonunique B-tree indexes and six foreign keys")
        void v2DeclaresTheExactIndexAndConstraintSet() {
            final String sql = migrationSql("V2__create_indexes.sql")
                    .toLowerCase(Locale.ROOT);
            final List<String> indexNames = capturedGroups(
                    Pattern.compile("\\bcreate\\s+index\\s+([a-z_]+)\\b"), sql);
            final List<String> constraintNames = capturedGroups(Pattern.compile(
                    "\\badd\\s+constraint\\s+([a-z_]+)\\s+foreign\\s+key\\b"), sql);
            final int btreeDeclarations = occurrenceCount(
                    Pattern.compile("\\busing\\s+btree\\b"), sql);

            assertThat(indexNames)
                    .as("expected exactly the three nonunique index names in "
                            + "V2__create_indexes.sql; actual names were %s", indexNames)
                    .containsExactlyInAnyOrder(
                            "idx_card_card_acct_id",
                            "idx_card_cross_reference_xref_acct_id",
                            "idx_transaction_tran_proc_ts");
            assertThat(Pattern.compile("\\bcreate\\s+unique\\s+index\\b").matcher(sql).find())
                    .as("expected all three V2 indexes to remain nonunique")
                    .isFalse();
            assertThat(btreeDeclarations)
                    .as("expected one explicit USING btree clause for each of the three indexes")
                    .isEqualTo(3);
            assertThat(constraintNames)
                    .as("expected exactly the six named foreign keys in V2__create_indexes.sql; "
                            + "actual names were %s", constraintNames)
                    .containsExactlyInAnyOrder(
                            "fk_card_account",
                            "fk_card_xref_card",
                            "fk_card_xref_account",
                            "fk_card_xref_customer",
                            "fk_transaction_card",
                            "fk_trancat_balance_account");
        }

        @Test
        @DisplayName("V2 gives both account-keyed indexes the base cluster key as a trailing column")
        void v2AlternateKeyIndexesCarryTheBaseKeyTieBreak() {
            final String sql = migrationSql("V2__create_indexes.sql")
                    .toLowerCase(Locale.ROOT);
            final List<String> indexColumnLists = capturedGroups(Pattern.compile(
                    "\\bcreate\\s+index\\s+[a-z_]+\\s+on\\s+[a-z_]+\\s+using\\s+btree\\s*"
                            + "\\(([a-z_,\\s]+)\\)"), sql);

            // The two nonunique alternate-key paths return a duplicate group in base-key sequence,
            // and both migrated finders are declared to order that way, so the base key has to be a
            // column of the index rather than a sort applied after it. The third index is the
            // inclusive processing-date range and imposes no order inside the range, so it carries
            // the alternate key alone; asserting all three together is what keeps that distinction
            // deliberate rather than accidental.
            assertThat(indexColumnLists)
                    .as("expected V2__create_indexes.sql to declare the two account-keyed indexes as "
                            + "(alternate key, base cluster key) and the batch-only timestamp index "
                            + "as the alternate key alone; actual column lists were %s",
                            indexColumnLists)
                    .containsExactly(
                            "card_acct_id, card_num",
                            "xref_acct_id, xref_card_num",
                            "tran_proc_ts");
        }

        @Test
        @DisplayName("V2 keeps default no-action semantics and omits the rejected relationships")
        void v2ContainsNoCascadeOrRejectedForeignKey() {
            final String sql = migrationSql("V2__create_indexes.sql")
                    .toLowerCase(Locale.ROOT);

            assertThat(Pattern.compile("\\bcascade\\b").matcher(sql).find())
                    .as("expected no cascade clause in V2__create_indexes.sql; all six foreign keys "
                            + "must retain default NO ACTION semantics")
                    .isFalse();
            assertThat(sql)
                    .as("daily_transaction is the unvalidated landing table and must have no foreign "
                            + "key on either side")
                    .doesNotContain("alter table daily_transaction")
                    .doesNotContain("references daily_transaction");
            assertThat(sql)
                    .as("account.acct_group_id must not reference disclosure_group; the source data "
                            + "does not establish that relationship")
                    .doesNotContain("acct_group_id");
        }

        @Test
        @DisplayName("V3 seeds exactly nine reference tables and no transaction or sign-on row")
        void v3SeedsOnlyTheNineReferenceTables() {
            final String sql = migrationSql("V3__seed_reference_data.sql")
                    .toLowerCase(Locale.ROOT);
            final List<String> insertTargets = capturedGroups(
                    Pattern.compile("\\binsert\\s+into\\s+([a-z_]+)\\b"), sql);

            assertThat(insertTargets)
                    .as("expected V3__seed_reference_data.sql to insert into exactly the nine "
                            + "reference-data tables; actual targets were %s", insertTargets)
                    .containsExactlyInAnyOrder(
                            "account",
                            "card",
                            "card_cross_reference",
                            "customer",
                            "daily_transaction",
                            "disclosure_group",
                            "transaction_category",
                            "transaction_category_balance",
                            "transaction_type");
            assertThat(insertTargets)
                    .as("V3 must leave posted transactions and sign-on identities empty")
                    .doesNotContain("transaction", "user_security");
        }

        @Test
        @DisplayName("V4 inserts ten sign-on rows and every credential is a BCrypt digest")
        void v4ContainsOnlyBcryptCredentials() {
            final String sql = migrationSql("V4__seed_user_security.sql");
            final String lowerSql = sql.toLowerCase(Locale.ROOT);
            final List<String> insertTargets = capturedGroups(
                    Pattern.compile("\\binsert\\s+into\\s+([a-z_]+)\\b"), lowerSql);
            final Matcher values = Pattern.compile(
                    "(?is)insert\\s+into\\s+user_security\\s*\\([^;]*?\\)\\s*values\\s*(.*?);")
                    .matcher(sql);

            assertThat(values.find())
                    .as("expected V4__seed_user_security.sql to contain one readable "
                            + "INSERT INTO user_security VALUES block")
                    .isTrue();
            final List<String> credentials = capturedGroups(Pattern.compile(
                    "(?m)^\\s*\\('[^']*',\\s*'[^']*',\\s*'[^']*',\\s*'([^']*)',"
                            + "\\s*'[^']*'\\)\\s*,?\\s*$"), values.group(1));

            assertThat(insertTargets)
                    .as("expected V4__seed_user_security.sql to insert only into user_security; "
                            + "actual INSERT targets were %s", insertTargets)
                    .containsExactly("user_security");
            assertThat(credentials.size())
                    .as("expected exactly ten credential fields in the V4 VALUES block")
                    .isEqualTo(10);
            credentials.forEach(credential -> {
                assertThat(credential.length())
                        .as("every V4 credential must be exactly 60 characters")
                        .isEqualTo(60);
                assertThat(Pattern.compile(
                        "\\$2[aby]\\$[0-9]{2}\\$[./A-Za-z0-9]{53}")
                        .matcher(credential).matches())
                        .as("every V4 credential must have a BCrypt prefix and shape; the value is "
                                + "intentionally not included in this diagnostic")
                        .isTrue();
            });
        }
    }

    @Nested
    @DisplayName("a production profile activated alongside a non-production one is refused")
    class CoActivationIsRefused {

        @ParameterizedTest(name = "[{0}] is accepted")
        @ValueSource(strings = {"prod", "local", "test", "", "dev", "prod-eu"})
        @DisplayName("a list holding one profile, or none of the conflicting pair, is accepted")
        void aSingleProfileIsAccepted(final String profile) {
            assertThatNoException().isThrownBy(() ->
                    FlywayConfig.requireExclusiveProfileActivation(List.of(profile)));
        }

        @Test
        @DisplayName("an absent or empty profile list is accepted, because a profile-less start "
                + "migrates the schema alone")
        void anAbsentOrEmptyListIsAccepted() {
            assertThatNoException().isThrownBy(() ->
                    FlywayConfig.requireExclusiveProfileActivation(null));
            assertThatNoException().isThrownBy(() ->
                    FlywayConfig.requireExclusiveProfileActivation(List.of()));
        }

        @Test
        @DisplayName("the two non-production profiles together are accepted, because neither carries "
                + "a production value")
        void theTwoNonProductionProfilesTogetherAreAccepted() {
            assertThatNoException().isThrownBy(() -> FlywayConfig.requireExclusiveProfileActivation(
                    List.of(FlywayConfig.LOCAL_PROFILE, FlywayConfig.TEST_PROFILE)));
        }

        @ParameterizedTest(name = "the list {0} is refused")
        @DisplayName("production with local or with test is refused in either activation order, "
                + "because both documents are loaded either way")
        @org.junit.jupiter.params.provider.MethodSource(
                "com.carddemo.config.FlywayConfigTest#conflictingProfileLists")
        void productionWithANonProductionProfileIsRefused(final List<String> activeProfiles,
                final String expectedNonProduction) {
            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() ->
                            FlywayConfig.requireExclusiveProfileActivation(activeProfiles))
                    .withMessageContaining("'" + FlywayConfig.PRODUCTION_PROFILE + "'")
                    .withMessageContaining("'" + expectedNonProduction + "'")
                    .withMessageContaining("in either activation order")
                    .satisfies(refusal -> assertCarriesNoRawTerminator(refusal.getMessage()));
        }

        @Test
        @DisplayName("comparison ignores case and surrounding whitespace, because a profile list is "
                + "commonly one comma-separated environment variable")
        void comparisonIgnoresCaseAndSurroundingWhitespace() {
            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> FlywayConfig.requireExclusiveProfileActivation(
                            List.of("  PROD ", " Local  ")))
                    .withMessageContaining("'" + FlywayConfig.LOCAL_PROFILE + "'");
        }

        @Test
        @DisplayName("a list holding a null entry is tolerated rather than failing on the null")
        void aListHoldingANullEntryIsTolerated() {
            final List<String> withNull = new ArrayList<>();
            withNull.add(null);
            withNull.add(FlywayConfig.PRODUCTION_PROFILE);

            assertThatNoException().isThrownBy(() ->
                    FlywayConfig.requireExclusiveProfileActivation(withNull));

            withNull.add(FlywayConfig.TEST_PROFILE);
            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> FlywayConfig.requireExclusiveProfileActivation(withNull));
        }

        @Test
        @DisplayName("the published post-processor performs the refusal, so it lands before any bean "
                + "is instantiated")
        void thePublishedPostProcessorPerformsTheRefusal() {
            final MockEnvironment conflicting = new MockEnvironment();
            conflicting.setActiveProfiles(
                    FlywayConfig.PRODUCTION_PROFILE, FlywayConfig.LOCAL_PROFILE);
            final BeanFactoryPostProcessor guard =
                    FlywayConfig.profileActivationGuard(conflicting);

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> guard.postProcessBeanFactory(
                            new org.springframework.beans.factory.support.DefaultListableBeanFactory()))
                    .withMessageContaining("'" + FlywayConfig.LOCAL_PROFILE + "'");
        }

        @Test
        @DisplayName("the published post-processor passes a single production profile through")
        void thePublishedPostProcessorPassesASingleProfileThrough() {
            final MockEnvironment production = new MockEnvironment();
            production.setActiveProfiles(FlywayConfig.PRODUCTION_PROFILE);

            assertThatNoException().isThrownBy(() ->
                    FlywayConfig.profileActivationGuard(production).postProcessBeanFactory(
                            new org.springframework.beans.factory.support.DefaultListableBeanFactory()));
        }

        @ParameterizedTest(name = "a context started with spring.profiles.active={0} fails")
        @ValueSource(strings = {"prod,local", "local,prod", "prod,test", "test,prod"})
        @DisplayName("a real context started with the offending list does not reach a running state, "
                + "which is the form the guarantee has to hold in")
        void aRealContextStartedWithTheOffendingListFails(final String activeProfiles) {
            runner().withPropertyValues("spring.profiles.active=" + activeProfiles)
                    .run(context -> assertThat(context)
                            .hasFailed()
                            .getFailure()
                            .as("the refusal is raised by the post-processor itself and reaches the "
                                    + "caller unwrapped, because no bean was being created when it "
                                    + "fired - which is the whole point of the hook")
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("'" + FlywayConfig.PRODUCTION_PROFILE + "'")
                            .hasMessageContaining("Activate exactly one of them"));
        }

        @ParameterizedTest(name = "a context started with spring.profiles.active={0} starts")
        @ValueSource(strings = {"prod", "local", "test", "local,test"})
        @DisplayName("a real context started with an acceptable list reaches a running state")
        void aRealContextStartedWithAnAcceptableListStarts(final String activeProfiles) {
            runner().withPropertyValues("spring.profiles.active=" + activeProfiles)
                    .run(context -> assertThat(context).hasNotFailed());
        }
    }

    @Nested
    @DisplayName("the migration location is resolved from the active profiles")
    class TheMigrationLocationIsResolvedFromTheProfiles {

        @Test
        @DisplayName("production migrating from the one packaged schema location is returned unchanged")
        void productionMigratingFromTheSchemaLocationAloneIsUnchanged() {
            assertThat(FlywayConfig.resolveLocations(
                    List.of(FlywayConfig.PRODUCTION_PROFILE),
                    List.of(FlywayConfig.SCHEMA_LOCATION)))
                    .containsExactly(FlywayConfig.SCHEMA_LOCATION);
        }

        @ParameterizedTest(name = "production accepts the schema location written [{0}]")
        @ValueSource(strings = {
            "classpath:db/migration/schema",
            " classpath:db/migration/schema",
            "classpath:db/migration/schema ",
            "\tclasspath:db/migration/schema\t"
        })
        @DisplayName("production accepts the packaged schema location and nothing more forgiving than "
                + "surrounding whitespace, because a property value can pick up padding in transit")
        void productionAcceptsTheSchemaLocationAndOnlySurroundingWhitespace(final String written) {
            assertThatNoException().isThrownBy(() -> FlywayConfig.resolveLocations(
                    List.of(FlywayConfig.PRODUCTION_PROFILE), List.of(written)));
        }

        @ParameterizedTest(name = "production refuses the near-spelling [{0}]")
        @ValueSource(strings = {
            "classpath:db/migration/schema/",
            "classpath:db/migration/schema/regional",
            "classpath:db/migration/seed",
            "filesystem:src/main/resources/db/migration/schema",
            "filesystem:/tmp/attacker/db/migration/schema",
            "db/migration/schema",
            "classpath:/db/migration/schema",
            "CLASSPATH:db/migration/schema"
        })
        @DisplayName("production refuses every near-spelling of the packaged schema location, because a "
                + "containment test cannot separate the packaged directory from a look-alike on disk")
        void productionRefusesEveryNearSpellingOfTheSchemaLocation(final String nearSpelling) {
            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> FlywayConfig.resolveLocations(
                            List.of(FlywayConfig.PRODUCTION_PROFILE), List.of(nearSpelling)))
                    .withMessageContaining(FlywayConfig.SCHEMA_LOCATION)
                    .satisfies(refusal -> assertCarriesNoRawTerminator(refusal.getMessage()));
        }

        @Test
        @DisplayName("production refuses a file-system descriptor that ends in the packaged folder "
                + "name, because an operator-writable directory can present its own V1 and V2")
        void productionRefusesAFileSystemLookAlike() {
            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> FlywayConfig.resolveLocations(
                            List.of(FlywayConfig.PRODUCTION_PROFILE),
                            List.of("filesystem:/var/tmp/anywhere/db/migration/schema")))
                    .satisfies(refusal -> assertThat(refusal.getMessage())
                            .as("the refusal names the one accepted value and never the rejected one")
                            .contains(FlywayConfig.SCHEMA_LOCATION)
                            .doesNotContain("/var/tmp/anywhere"));
        }

        @ParameterizedTest(name = "every profile refuses the shared parent spelled [{0}]")
        @ValueSource(strings = {
            "classpath:db/migration",
            " classpath:db/migration ",
            "classpath:db/migration/",
            "filesystem:src/main/resources/db/migration",
            "db/migration"
        })
        @DisplayName("EVERY profile refuses the shared parent of the two delivered locations, because "
                + "scanning is recursive and the parent reaches both children")
        void everyProfileRefusesTheSharedParent(final String spelling) {
            for (final List<String> profiles : List.of(
                    List.of(FlywayConfig.PRODUCTION_PROFILE),
                    List.of(FlywayConfig.LOCAL_PROFILE),
                    List.of(FlywayConfig.TEST_PROFILE),
                    List.<String>of())) {
                assertThatExceptionOfType(IllegalStateException.class)
                        .as("the parent must be refused under %s as well. Under production it would "
                                + "apply the seeds; under a seeding profile it would apply the SAME four "
                                + "scripts and record each one under a name relative to itself - "
                                + "schema/V1__create_schema.sql instead of V1__create_schema.sql - so "
                                + "the history stops matching what the bring-up check reads. That is why "
                                + "the refusal is not scoped to a profile", profiles)
                        .isThrownBy(() -> FlywayConfig.resolveLocations(profiles, List.of(spelling)))
                        .withMessageContaining(FlywayConfig.SHARED_PARENT_LOCATION)
                        .withMessageContaining(FlywayConfig.SCHEMA_LOCATION)
                        .satisfies(refusal -> assertCarriesNoRawTerminator(refusal.getMessage()));
            }
        }

        @Test
        @DisplayName("the shared parent is refused even beside a correct entry, and even where the "
                + "seeds are wanted, because one entry is enough to widen the scan")
        void theSharedParentIsRefusedBesideACorrectEntry() {
            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> FlywayConfig.resolveLocations(
                            List.of(FlywayConfig.LOCAL_PROFILE),
                            List.of(FlywayConfig.SCHEMA_LOCATION, FlywayConfig.SEED_LOCATION,
                                    FlywayConfig.SHARED_PARENT_LOCATION)))
                    .withMessageContaining(FlywayConfig.SHARED_PARENT_LOCATION);
        }

        @ParameterizedTest(name = "production refuses the seed location spelled [{0}]")
        @ValueSource(strings = {
            "classpath:db/migration/seed",
            "classpath:db/migration/seed/",
            "filesystem:src/main/resources/db/migration/seed",
            "db/migration/seed"
        })
        @DisplayName("production refuses the seed location however it is spelled, which is the whole of "
                + "the exclusion stated as a refusal")
        void productionRefusesTheSeedLocation(final String spelling) {
            assertThatExceptionOfType(IllegalStateException.class)
                    .as("this is the single most important refusal in the class: the seed scripts "
                            + "insert fifty synthetic customer rows holding regulated identity data and "
                            + "ten known sign-on identities whose stored credentials are digests of one "
                            + "well-known value")
                    .isThrownBy(() -> FlywayConfig.resolveLocations(
                            List.of(FlywayConfig.PRODUCTION_PROFILE), List.of(spelling)))
                    .withMessageContaining(FlywayConfig.SCHEMA_LOCATION)
                    .satisfies(refusal -> assertCarriesNoRawTerminator(refusal.getMessage()));

            assertThatExceptionOfType(IllegalStateException.class)
                    .as("and it is refused beside the schema location too, because a second entry is a "
                            + "second migration source whatever it holds")
                    .isThrownBy(() -> FlywayConfig.resolveLocations(
                            List.of(FlywayConfig.PRODUCTION_PROFILE),
                            List.of(FlywayConfig.SCHEMA_LOCATION, spelling)))
                    .withMessageContaining(FlywayConfig.SCHEMA_LOCATION);
        }

        @ParameterizedTest(name = "production refuses the location [{0}]")
        @ValueSource(strings = {
            "classpath:db/migration/schemas",
            "classpath:db/migrations",
            "classpath:db/seed",
            "classpath:db/fixtures",
            "filesystem:/tmp/extra",
            "classpath:migration"
        })
        @DisplayName("production refuses any location beyond the packaged schema one, because a second "
                + "source carries scripts this module never shipped")
        void productionRefusesAnyForeignLocation(final String foreignLocation) {
            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> FlywayConfig.resolveLocations(
                            List.of(FlywayConfig.PRODUCTION_PROFILE),
                            List.of(FlywayConfig.SCHEMA_LOCATION, foreignLocation)))
                    .withMessageContaining(FlywayConfig.SCHEMA_LOCATION)
                    .withMessageContaining(FlywayConfig.SEED_LOCATION)
                    .satisfies(refusal -> assertCarriesNoRawTerminator(refusal.getMessage()));
        }

        @Test
        @DisplayName("production refuses a foreign location wherever it sits in the list, because a "
                + "merged environment decides the order")
        void productionRefusesAForeignLocationWhereverItSits() {
            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> FlywayConfig.resolveLocations(
                            List.of(FlywayConfig.PRODUCTION_PROFILE),
                            List.of("classpath:db/fixtures", FlywayConfig.SCHEMA_LOCATION)));
        }

        @ParameterizedTest(name = "{0} has both delivered locations completed for it")
        @ValueSource(strings = {"local", "test"})
        @DisplayName("a non-production profile that resolved no location at all has BOTH delivered "
                + "locations appended, because it would otherwise migrate nothing and seed nothing")
        void aNonProductionProfileWithNoLocationHasBothAppended(final String profile) {
            assertThat(FlywayConfig.resolveLocations(List.of(profile), List.of()))
                    .as("completing only one half would leave %s either without a schema to seed into "
                            + "or without the fixtures it exists to load", profile)
                    .containsExactly(FlywayConfig.SCHEMA_LOCATION, FlywayConfig.SEED_LOCATION);
        }

        @ParameterizedTest(name = "{0} has the seed location appended to a schema-only list")
        @ValueSource(strings = {"local", "test"})
        @DisplayName("a non-production profile that inherited the production posture has the seed "
                + "location appended, which is the completion the two seeding profiles depend on")
        void aNonProductionProfileWithTheSchemaLocationHasTheSeedOneAppended(final String profile) {
            assertThat(FlywayConfig.resolveLocations(
                    List.of(profile), List.of(FlywayConfig.SCHEMA_LOCATION)))
                    .as("the shared baseline declares the schema location alone, so this is exactly "
                            + "what %s inherits when its own overlay says nothing", profile)
                    .containsExactly(FlywayConfig.SCHEMA_LOCATION, FlywayConfig.SEED_LOCATION);
        }

        @ParameterizedTest(name = "{0} keeps both locations it already declared")
        @ValueSource(strings = {"local", "test"})
        @DisplayName("a non-production profile that already resolved both locations is left exactly "
                + "alone, so nothing is appended twice")
        void aNonProductionProfileWithBothLocationsIsLeftAlone(final String profile) {
            final List<String> declared =
                    List.of(FlywayConfig.SCHEMA_LOCATION, FlywayConfig.SEED_LOCATION);

            assertThat(FlywayConfig.resolveLocations(List.of(profile), declared))
                    .containsExactlyElementsOf(declared);
        }

        @ParameterizedTest(name = "{0} keeps a declared order that puts the seed location first")
        @ValueSource(strings = {"local", "test"})
        @DisplayName("a non-production profile's own ordering survives, because the completion appends "
                + "rather than rewrites")
        void aNonProductionProfileKeepsItsOwnOrdering(final String profile) {
            final List<String> declared =
                    List.of(FlywayConfig.SEED_LOCATION, FlywayConfig.SCHEMA_LOCATION);

            assertThat(FlywayConfig.resolveLocations(List.of(profile), declared))
                    .as("Flyway orders the SCRIPTS by version rather than by location, so the order of "
                            + "the list is the operator's business and this method does not impose one")
                    .containsExactlyElementsOf(declared);
        }

        @ParameterizedTest(name = "a non-production profile recognises the schema spelling [{0}]")
        @ValueSource(strings = {
            "classpath:db/migration/schema",
            "classpath:db/migration/schema/",
            "classpath:db/migration/schema/regional",
            "filesystem:src/main/resources/db/migration/schema",
            "db/migration/schema"
        })
        @DisplayName("a non-production profile recognises every spelling that addresses the schema "
                + "path, so the completion step never appends a duplicate schema entry")
        void aNonProductionProfileRecognisesEverySpellingOfTheSchemaPath(final String spelling) {
            assertThat(FlywayConfig.resolveLocations(
                    List.of(FlywayConfig.LOCAL_PROFILE),
                    List.of(spelling, FlywayConfig.SEED_LOCATION)))
                    .as("the completion predicate is deliberately looser than the production one: its "
                            + "only effect is to avoid appending a duplicate, and neither profile it "
                            + "serves ever runs against a production database")
                    .containsExactly(spelling, FlywayConfig.SEED_LOCATION);
        }

        @ParameterizedTest(name = "a non-production profile recognises the seed spelling [{0}]")
        @ValueSource(strings = {
            "classpath:db/migration/seed",
            "classpath:db/migration/seed/",
            "filesystem:src/main/resources/db/migration/seed",
            "db/migration/seed"
        })
        @DisplayName("and it recognises every spelling that addresses the seed path, for the same "
                + "reason read the other way")
        void aNonProductionProfileRecognisesEverySpellingOfTheSeedPath(final String spelling) {
            assertThat(FlywayConfig.resolveLocations(
                    List.of(FlywayConfig.TEST_PROFILE),
                    List.of(FlywayConfig.SCHEMA_LOCATION, spelling)))
                    .containsExactly(FlywayConfig.SCHEMA_LOCATION, spelling);
        }

        @ParameterizedTest(name = "{0} has both locations appended after a foreign entry")
        @ValueSource(strings = {"local", "test"})
        @DisplayName("a non-production profile that declared only a foreign location keeps it and has "
                + "both delivered locations appended, so the declared order survives")
        void aNonProductionProfileWithAForeignLocationHasBothAppended(final String profile) {
            assertThat(FlywayConfig.resolveLocations(
                    List.of(profile), List.of("classpath:db/extra")))
                    .containsExactly("classpath:db/extra", FlywayConfig.SCHEMA_LOCATION,
                            FlywayConfig.SEED_LOCATION);
        }

        @Test
        @DisplayName("no active profile leaves the bound list alone, because the shared baseline "
                + "declares the schema location by itself")
        void noActiveProfileLeavesTheBoundListAlone() {
            assertThat(FlywayConfig.resolveLocations(
                    List.of(), List.of(FlywayConfig.SCHEMA_LOCATION)))
                    .containsExactly(FlywayConfig.SCHEMA_LOCATION);
            assertThat(FlywayConfig.resolveLocations(null, List.of(FlywayConfig.SCHEMA_LOCATION)))
                    .containsExactly(FlywayConfig.SCHEMA_LOCATION);
        }

        @ParameterizedTest(name = "production refuses a location list that addresses nothing: {0}")
        @MethodSource("com.carddemo.config.FlywayConfigTest#emptyLocationLists")
        @DisplayName("production refuses a list that addresses no location at all, because migrating "
                + "from nowhere applies neither the schema nor the indexes")
        void productionRefusesAListThatAddressesNothing(final String description,
                final List<String> declared) {
            assertThatExceptionOfType(IllegalStateException.class)
                    .as("case: %s", description)
                    .isThrownBy(() -> FlywayConfig.resolveLocations(
                            List.of(FlywayConfig.PRODUCTION_PROFILE), declared))
                    .withMessageContaining(FlywayConfig.SCHEMA_LOCATION);
        }

        @Test
        @DisplayName("an absent location list resolves to no location rather than to a null when no "
                + "profile is active, so a profile-less start is not handed a null")
        void anAbsentLocationListResolvesToNoLocation() {
            assertThat(FlywayConfig.resolveLocations(List.of(), null)).isEmpty();
            assertThat(FlywayConfig.resolveLocations(null, null)).isEmpty();
        }

        @Test
        @DisplayName("production refuses an absent location list, because a production migration "
                + "source is stated rather than inherited from a framework default")
        void productionRefusesAnAbsentLocationList() {
            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> FlywayConfig.resolveLocations(
                            List.of(FlywayConfig.PRODUCTION_PROFILE), null))
                    .withMessageContaining(FlywayConfig.SCHEMA_LOCATION);
            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> FlywayConfig.resolveLocations(
                            List.of(FlywayConfig.PRODUCTION_PROFILE), List.of()))
                    .withMessageContaining(FlywayConfig.SCHEMA_LOCATION);
        }

        @Test
        @DisplayName("production refuses a blank or null entry alongside the packaged location, "
                + "because a second entry is a second migration source whatever it holds")
        void productionRefusesABlankOrNullEntryAlongsideTheSchemaLocation() {
            final List<String> declared = new ArrayList<>();
            declared.add(FlywayConfig.SCHEMA_LOCATION);
            declared.add("   ");
            declared.add(null);

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> FlywayConfig.resolveLocations(
                            List.of(FlywayConfig.PRODUCTION_PROFILE), declared))
                    .withMessageContaining(FlywayConfig.SCHEMA_LOCATION);
        }

        @Test
        @DisplayName("production refuses the packaged location listed twice, because the guard "
                + "counts entries rather than distinct values")
        void productionRefusesTheSchemaLocationListedTwice() {
            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> FlywayConfig.resolveLocations(
                            List.of(FlywayConfig.PRODUCTION_PROFILE),
                            List.of(FlywayConfig.SCHEMA_LOCATION,
                                    FlywayConfig.SCHEMA_LOCATION)));
        }
    }

    @Nested
    @DisplayName("the migration target is resolved from the active profiles")
    class TheMigrationTargetIsResolvedFromTheProfiles {

        @Test
        @DisplayName("production declaring the pin is returned unchanged, in either equivalent spelling")
        void productionDeclaringThePinIsUnchanged() {
            assertThat(FlywayConfig.resolveTarget(
                    List.of(FlywayConfig.PRODUCTION_PROFILE), FlywayConfig.PRODUCTION_TARGET))
                    .isEqualTo(FlywayConfig.PRODUCTION_TARGET);
            assertThat(FlywayConfig.resolveTarget(List.of(FlywayConfig.PRODUCTION_PROFILE), "2.0"))
                    .as("the comparison is made on the parsed VERSION rather than on the text, because "
                            + "2 and 2.0 are the same ceiling and a deployment supplying either has "
                            + "declared the pin")
                    .isEqualTo(FlywayConfig.PRODUCTION_TARGET);
            assertThat(FlywayConfig.resolveTarget(List.of(FlywayConfig.PRODUCTION_PROFILE), "  2  "))
                    .as("and surrounding whitespace is a property of how an environment variable was "
                            + "written, not of the ceiling it names")
                    .isEqualTo(FlywayConfig.PRODUCTION_TARGET);
        }

        @ParameterizedTest(name = "production refuses the ceiling [{0}]")
        @ValueSource(strings = {"1", "1.1", "0", "1.9999", "3", "4", "99", "latest", "LATEST"})
        @DisplayName("production refuses every ceiling but the pin, in BOTH directions, because a "
                + "higher one applies scripts it was never measured against and a lower one leaves the "
                + "indexes and constraints uncreated")
        void productionRefusesEveryCeilingButThePin(final String ceiling) {
            assertThatExceptionOfType(IllegalStateException.class)
                    .as("a ceiling of %s is refused. A LOW one stops before the indexes and "
                            + "constraints are created and reports success anyway. A HIGH one - and the "
                            + "open marker is a high one - applies whatever a resolved location carries "
                            + "above the delivered schema, and the seed scripts are numbered there. The "
                            + "pin itself cannot go stale, because it is asserted against the versions "
                            + "the schema location delivers", ceiling)
                    .isThrownBy(() -> FlywayConfig.resolveTarget(
                            List.of(FlywayConfig.PRODUCTION_PROFILE), ceiling))
                    .withMessageContaining(FlywayConfig.PRODUCTION_TARGET)
                    .satisfies(refusal -> assertCarriesNoRawTerminator(refusal.getMessage()));
        }

        @ParameterizedTest(name = "production refuses the unreadable target [{0}]")
        @ValueSource(strings = {"current", "next", "not-a-version", "latest-ish"})
        @DisplayName("production refuses a marker or an unreadable value as well, because anything but "
                + "the one accepted version is a scope nothing in this class measured")
        void productionRefusesAMarkerOrAnUnreadableTarget(final String target) {
            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> FlywayConfig.resolveTarget(
                            List.of(FlywayConfig.PRODUCTION_PROFILE), target))
                    .withMessageContaining(FlywayConfig.PRODUCTION_TARGET)
                    .satisfies(refusal -> assertCarriesNoRawTerminator(refusal.getMessage()));
        }

        @Test
        @DisplayName("production ACCEPTS an absent target and returns the pin, so a profile that forgot "
                + "the property still migrates the delivered schema and no further")
        void productionAcceptsAnAbsentTargetAndReturnsThePin() {
            assertThat(FlywayConfig.resolveTarget(List.of(FlywayConfig.PRODUCTION_PROFILE), null))
                    .as("silence is corrected rather than refused. A migration tool with no target "
                            + "migrates to the latest version, so silence left alone would be the "
                            + "dangerous case; refusing it instead would stop a deployment that had "
                            + "inherited the right posture from the shared baseline. Correcting it does "
                            + "neither")
                    .isEqualTo(FlywayConfig.PRODUCTION_TARGET);
            assertThat(FlywayConfig.resolveTarget(List.of(FlywayConfig.PRODUCTION_PROFILE), "   "))
                    .as("and a blank value is silence written down")
                    .isEqualTo(FlywayConfig.PRODUCTION_TARGET);
        }

        @Test
        @DisplayName("the pin is the highest version the schema location actually delivers, which is "
                + "what stops it freezing a later release")
        void thePinIsTheHighestDeliveredSchemaVersion() {
            final MigrationVersion pin = MigrationVersion.fromVersion(FlywayConfig.PRODUCTION_TARGET);
            final List<String> schemaVersions = deliveredMigrationSchemaVersions();

            assertThat(schemaVersions)
                    .as("the schema location must deliver at least one script, or the pin is measured "
                            + "against nothing")
                    .isNotEmpty();
            assertThat(schemaVersions.stream()
                    .map(MigrationVersion::fromVersion)
                    .max(MigrationVersion::compareTo)
                    .orElseThrow())
                    .as("THIS is the assertion that makes pinning safe rather than a trap. A number "
                            + "written down and never checked freezes the schema: a V5 script added to "
                            + "%s would never be applied and the migration would still report success. "
                            + "Checked here, adding it without raising FlywayConfig.PRODUCTION_TARGET "
                            + "fails the BUILD instead, so raising the schema and raising the pin are "
                            + "one commit. Delivered schema versions: %s",
                            FlywayConfig.SCHEMA_LOCATION, schemaVersions)
                    .isEqualTo(pin);
            assertThat(deliveredMigrationSeedVersions())
                    .as("and every seed version must sit strictly above the pin, or the pin would admit "
                            + "one")
                    .isNotEmpty()
                    .allSatisfy(seed -> assertThat(
                            MigrationVersion.fromVersion(seed).compareTo(pin))
                            .isPositive());
        }

        @ParameterizedTest(name = "{0} has an inherited low ceiling lifted for it")
        @ValueSource(strings = {"local", "test"})
        @DisplayName("a seeding profile that inherited a ceiling short of the seeds has it lifted, "
                + "because it would otherwise migrate none of its fixtures")
        void aSeedingProfileHasAnInheritedLowCeilingLifted(final String profile) {
            assertThat(FlywayConfig.resolveTarget(List.of(profile), "2"))
                    .as("%s is LIFTED rather than refused. A fixture-bearing profile that stopped at "
                            + "the schema would load no fixtures, so completing is the useful outcome "
                            + "there where refusing is the useful outcome under production", profile)
                    .isEqualTo(FlywayConfig.ALL_RESOLVED_VERSIONS_TARGET);
            assertThat(FlywayConfig.resolveTarget(List.of(profile), "1"))
                    .isEqualTo(FlywayConfig.ALL_RESOLVED_VERSIONS_TARGET);
        }

        @ParameterizedTest(name = "{0} keeps a target that already reaches the seeds")
        @ValueSource(strings = {"local", "test"})
        @DisplayName("a seeding profile declaring a target that already reaches every delivered "
                + "version is returned unchanged")
        void aSeedingProfileDeclaringAReachingTargetIsUnchanged(final String profile) {
            assertThat(FlywayConfig.resolveTarget(
                    List.of(profile), FlywayConfig.ALL_RESOLVED_VERSIONS_TARGET))
                    .isEqualTo(FlywayConfig.ALL_RESOLVED_VERSIONS_TARGET);
            assertThat(FlywayConfig.resolveTarget(List.of(profile), "4"))
                    .as("a number that reaches the last delivered seed is left as declared rather than "
                            + "rewritten, because it already applies everything the profile needs")
                    .isEqualTo("4");
            assertThat(FlywayConfig.resolveTarget(List.of(profile), null))
                    .as("and an absent target is left absent, because Flyway's own default already "
                            + "applies everything the resolved locations carry")
                    .isNull();
        }

        @Test
        @DisplayName("no active profile leaves the bound target alone")
        void noActiveProfileLeavesTheBoundTargetAlone() {
            assertThat(FlywayConfig.resolveTarget(
                    List.of(), FlywayConfig.ALL_RESOLVED_VERSIONS_TARGET))
                    .isEqualTo(FlywayConfig.ALL_RESOLVED_VERSIONS_TARGET);
            assertThat(FlywayConfig.resolveTarget(
                    null, FlywayConfig.ALL_RESOLVED_VERSIONS_TARGET))
                    .isEqualTo(FlywayConfig.ALL_RESOLVED_VERSIONS_TARGET);
            assertThat(FlywayConfig.resolveTarget(List.of(), "2"))
                    .as("a profile-less start is neither refused nor completed: it migrates whatever "
                            + "the shared baseline declares")
                    .isEqualTo("2");
        }

        @Test
        @DisplayName("the published customizer completes a non-production profile's scope: the seed "
                + "location is appended AND the low ceiling is lifted")
        void thePublishedCustomizerCompletesTheSeedingScope() {
            final MockEnvironment local = new MockEnvironment();
            local.setActiveProfiles(FlywayConfig.LOCAL_PROFILE);
            final FluentConfiguration configuration = new FluentConfiguration()
                    .locations(FlywayConfig.SCHEMA_LOCATION)
                    .target("2");

            new FlywayConfig().migrationScopeResolvingCustomizer(local).customize(configuration);

            assertThat(configuration.getTarget()).isEqualTo(MigrationVersion.LATEST);
            assertThat(Arrays.stream(configuration.getLocations())
                    .map(Location::getDescriptor)
                    .toList())
                    .as("the seed scripts sit in a location the production posture does not carry, so "
                            + "appending it is the substance of the completion; lifting the ceiling "
                            + "alone would leave a seeding profile with nothing to lift a ceiling over")
                    .containsExactly(FlywayConfig.SCHEMA_LOCATION, FlywayConfig.SEED_LOCATION);
        }

        @Test
        @DisplayName("the migration tool discards the shared parent when a child is already declared, "
                + "so the refusal in code is what catches a parent declared on its own")
        void theToolAbsorbsAChildBesideItsParentSoTheCodeRefusalIsWhatCatchesIt() {
            final FluentConfiguration configuration = new FluentConfiguration()
                    .locations(FlywayConfig.SHARED_PARENT_LOCATION, FlywayConfig.SCHEMA_LOCATION);

            assertThat(Arrays.stream(configuration.getLocations())
                    .map(Location::getDescriptor)
                    .toList())
                    .as("the tool keeps the PARENT and discards the child, because it scans recursively "
                            + "and the parent already covers it. That is precisely why the parent cannot "
                            + "be left to the tool: what survives is the widest entry, not the "
                            + "narrowest")
                    .containsExactly(FlywayConfig.SHARED_PARENT_LOCATION);

            final MockEnvironment production = new MockEnvironment();
            production.setActiveProfiles(FlywayConfig.PRODUCTION_PROFILE);

            assertThatExceptionOfType(IllegalStateException.class)
                    .as("so the customizer has to refuse what the tool handed it")
                    .isThrownBy(() -> new FlywayConfig()
                            .migrationScopeResolvingCustomizer(production).customize(configuration))
                    .withMessageContaining(FlywayConfig.SHARED_PARENT_LOCATION);
        }

        @Test
        @DisplayName("a parent handed straight to the resolver is still refused, because the resolver "
                + "is also called by the guard that runs before any tool exists")
        void aParentHandedStraightToTheResolverIsStillRefused() {
            assertThatExceptionOfType(IllegalStateException.class)
                    .as("the migration-source guard reads the bound property list before a migration "
                            + "tool has been built, so it never benefits from the tool's own absorption "
                            + "and has to reject the parent on its own")
                    .isThrownBy(() -> FlywayConfig.resolveLocations(
                            List.of(FlywayConfig.PRODUCTION_PROFILE),
                            List.of(FlywayConfig.SHARED_PARENT_LOCATION)))
                    .withMessageContaining(FlywayConfig.SHARED_PARENT_LOCATION);
        }

        @Test
        @DisplayName("the published customizer refuses a bound ceiling other than the production pin, "
                + "the open marker included")
        void thePublishedCustomizerRefusesAForeignCeilingUnderProduction() {
            for (final String foreign : List.of(FlywayConfig.ALL_RESOLVED_VERSIONS_TARGET, "1", "4")) {
                // Declared in the ENVIRONMENT rather than only on the bound configuration, because that
                // is what a document, a command-line property or a co-activated overlay actually
                // produces - and because the migration tool's own default ceiling is its head marker, so
                // a bound value alone cannot distinguish "declared latest" from "declared nothing". The
                // first must be refused and the second corrected to the pin.
                final MockEnvironment production = new MockEnvironment();
                production.setActiveProfiles(FlywayConfig.PRODUCTION_PROFILE);
                production.setProperty("spring.flyway.target", foreign);
                final FlywayConfigurationCustomizer customizer =
                        new FlywayConfig().migrationScopeResolvingCustomizer(production);
                final FluentConfiguration configuration = new FluentConfiguration()
                        .locations(FlywayConfig.SCHEMA_LOCATION)
                        .target(foreign);

                assertThatExceptionOfType(IllegalStateException.class)
                        .as("a declared ceiling of %s is refused: above the pin it applies scripts this "
                                + "deployment was never measured against, below it the indexes and "
                                + "constraints are never created", foreign)
                        .isThrownBy(() -> customizer.customize(configuration))
                        .withMessageContaining(FlywayConfig.PRODUCTION_TARGET);
            }
        }

        @Test
        @DisplayName("the published customizer corrects an UNDECLARED ceiling to the pin under "
                + "production, because the migration tool's own default is its head marker")
        void thePublishedCustomizerCorrectsAnUndeclaredCeilingToThePin() {
            final MockEnvironment production = new MockEnvironment();
            production.setActiveProfiles(FlywayConfig.PRODUCTION_PROFILE);
            final FluentConfiguration undeclared = new FluentConfiguration()
                    .locations(FlywayConfig.SCHEMA_LOCATION);

            new FlywayConfig().migrationScopeResolvingCustomizer(production).customize(undeclared);

            assertThat(undeclared.getTarget())
                    .as("a configuration nothing declared a ceiling for arrives here carrying the tool's "
                            + "own head marker, which would apply every version a resolved location "
                            + "carried. It is CORRECTED rather than refused, because refusing would stop "
                            + "a deployment that stated nothing wrong; the environment-reading guard is "
                            + "what refuses a value that was actually declared")
                    .isEqualTo(MigrationVersion.fromVersion(FlywayConfig.PRODUCTION_TARGET));
        }

        @Test
        @DisplayName("the published customizer refuses the seed location under production")
        void thePublishedCustomizerRefusesTheSeedLocationUnderProduction() {
            final MockEnvironment production = new MockEnvironment();
            production.setActiveProfiles(FlywayConfig.PRODUCTION_PROFILE);
            final FluentConfiguration configuration = new FluentConfiguration()
                    .locations(FlywayConfig.SEED_LOCATION)
                    .target(FlywayConfig.PRODUCTION_TARGET);
            final FlywayConfigurationCustomizer customizer =
                    new FlywayConfig().migrationScopeResolvingCustomizer(production);

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> customizer.customize(configuration))
                    .withMessageContaining(FlywayConfig.SCHEMA_LOCATION);
        }

        @Test
        @DisplayName("the published customizer refuses a foreign location under production")
        void thePublishedCustomizerRefusesAForeignLocationUnderProduction() {
            final MockEnvironment production = new MockEnvironment();
            production.setActiveProfiles(FlywayConfig.PRODUCTION_PROFILE);
            final FluentConfiguration configuration = new FluentConfiguration()
                    .locations(FlywayConfig.SCHEMA_LOCATION, "classpath:db/fixtures")
                    .target(FlywayConfig.PRODUCTION_TARGET);
            final FlywayConfigurationCustomizer customizer =
                    new FlywayConfig().migrationScopeResolvingCustomizer(production);

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> customizer.customize(configuration))
                    .withMessageContaining(FlywayConfig.SCHEMA_LOCATION);
        }

        @Test
        @DisplayName("the published customizer leaves an already-correct configuration untouched")
        void thePublishedCustomizerLeavesACorrectConfigurationUntouched() {
            final MockEnvironment production = new MockEnvironment();
            production.setActiveProfiles(FlywayConfig.PRODUCTION_PROFILE);
            final FluentConfiguration configuration = new FluentConfiguration()
                    .locations(FlywayConfig.SCHEMA_LOCATION)
                    .target(FlywayConfig.PRODUCTION_TARGET);

            new FlywayConfig().migrationScopeResolvingCustomizer(production)
                    .customize(configuration);

            assertThat(Arrays.stream(configuration.getLocations())
                    .map(Location::getDescriptor)
                    .toList())
                    .containsExactly(FlywayConfig.SCHEMA_LOCATION);
            assertThat(configuration.getTarget())
                    .isEqualTo(MigrationVersion.fromVersion(FlywayConfig.PRODUCTION_TARGET));
        }
    }

    @Nested
    @DisplayName("no refusal repeats what it was given - decision DL-041")
    class NoRefusalRepeatsWhatItWasGiven {

        @Test
        @DisplayName("the co-activation refusal names the two matched literals and never the "
                + "supplied profile list")
        void theCoActivationRefusalNamesTheLiteralsOnly() {
            final String hostileProfile =
                    FlywayConfig.LOCAL_PROFILE + HOSTILE_MARKER + CARRIAGE_RETURN + LINE_FEED;

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> FlywayConfig.requireExclusiveProfileActivation(
                            List.of(FlywayConfig.PRODUCTION_PROFILE, FlywayConfig.LOCAL_PROFILE,
                                    hostileProfile)))
                    .withMessageNotContaining(HOSTILE_MARKER)
                    .satisfies(refusal -> assertCarriesNoRawTerminator(refusal.getMessage()));
        }

        @Test
        @DisplayName("the foreign-location refusal names the packaged location and never the "
                + "offending descriptor")
        void theForeignLocationRefusalNamesThePathsOnly() {
            final String hostileLocation =
                    "classpath:db/fixtures/" + HOSTILE_MARKER + CARRIAGE_RETURN + LINE_FEED;

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> FlywayConfig.resolveLocations(
                            List.of(FlywayConfig.PRODUCTION_PROFILE),
                            List.of(FlywayConfig.SCHEMA_LOCATION, hostileLocation)))
                    .withMessageNotContaining(HOSTILE_MARKER)
                    .satisfies(refusal -> assertCarriesNoRawTerminator(refusal.getMessage()));
        }

        @Test
        @DisplayName("the ceiling refusal names the version literals and never the offending value")
        void theCeilingRefusalNamesTheVersionsOnly() {
            final String hostileCeiling = "latest" + HOSTILE_MARKER + CARRIAGE_RETURN + LINE_FEED;

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> FlywayConfig.resolveTarget(
                            List.of(FlywayConfig.PRODUCTION_PROFILE), hostileCeiling))
                    .withMessageNotContaining(HOSTILE_MARKER)
                    .satisfies(refusal -> assertCarriesNoRawTerminator(refusal.getMessage()));
        }
    }

    @Nested
    @DisplayName("the structural facts the guarantees rest on")
    class TheStructuralFacts {

        @Test
        @DisplayName("the class is discovered as one non-proxying policy configuration")
        void theClassIsANonProxyingConfiguration() {
            runner().withPropertyValues("spring.profiles.active=prod").run(context ->
                    assertThat(context)
                            .as("the configuration must be discovered behaviourally and publish one "
                                    + "policy customizer")
                            .hasNotFailed()
                            .hasSingleBean(FlywayConfig.class)
                            .hasSingleBean(FlywayConfigurationCustomizer.class));

            final MergedAnnotations annotations =
                    MergedAnnotations.from(FlywayConfig.class, SearchStrategy.TYPE_HIERARCHY);
            assertThat(annotations.isPresent(Configuration.class))
                    .as("secondary guard: the behaviour above is published by @Configuration")
                    .isTrue();
            assertThat(annotations.get(Configuration.class).getBoolean("proxyBeanMethods"))
                    .as("secondary guard: policy bean methods need no proxying")
                    .isFalse();
        }

        @Test
        @DisplayName("the guard is published from a static factory method, so publishing it cannot "
                + "put an ordinary bean's construction first")
        void theGuardRunsBeforeAnOrdinaryBeanIsConstructed() {
            final AtomicBoolean ordinaryBeanConstructed = new AtomicBoolean();

            new ApplicationContextRunner()
                    .withUserConfiguration(FlywayConfig.class)
                    .withBean(SensitiveFieldEncryptionService.class, () -> {
                        ordinaryBeanConstructed.set(true);
                        return new SensitiveFieldEncryptionService(TEST_KEY);
                    })
                    .withPropertyValues(
                            "spring.profiles.active=prod,local",
                            "spring.flyway.enabled=true",
                            "spring.flyway.target=2",
                            "spring.flyway.locations=classpath:db/migration")
                    .run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(ordinaryBeanConstructed)
                                .as("the profile guard must refuse startup before any ordinary bean "
                                        + "supplier is invoked")
                                .isFalse();
                    });
        }

        @Test
        @DisplayName("this configuration publishes no sealing callback, because the sealing component "
                + "self-registers from the service package it belongs to")
        void thisConfigurationPublishesNoSealingCallback() {
            assertThat(FlywayConfig.class.getDeclaredMethods())
                    .as("a factory method here would make this configuration depend on the service "
                            + "package, which is the dependency direction the layering forbids; "
                            + "Boot's Flyway auto-configuration collects every Callback bean "
                            + "whatever package declares it, so the component registers itself")
                    .noneMatch(method -> Callback.class.isAssignableFrom(method.getReturnType())
                            && method.getName().toLowerCase(Locale.ROOT).contains("sealing"));
            for (final String profile : List.of("local", "test", "prod")) {
                runner().withPropertyValues("spring.profiles.active=" + profile).run(context ->
                        assertThat(context)
                                .as("%s must not obtain a sealing callback from this configuration",
                                        profile)
                                .hasNotFailed()
                                .doesNotHaveBean(SeededIdentifierSealingCallback.class));
            }
        }

        @Test
        @DisplayName("the seeded-database refusal is restricted to the production profile alone, "
                + "because under local and test the seeded rows are the point")
        void theSeededDatabaseRefusalIsRestrictedToProduction() {
            runner().withPropertyValues("spring.profiles.active=prod").run(context ->
                    assertThat(context)
                            .as("production must publish the seeded-database rejection callback")
                            .hasNotFailed()
                            .hasSingleBean(ProductionSeedRejectionCallback.class));
            for (final String profile : List.of("local", "test")) {
                runner().withPropertyValues("spring.profiles.active=" + profile).run(context ->
                        assertThat(context)
                                .as("%s deliberately seeds rows and must not reject their presence",
                                        profile)
                                .hasNotFailed()
                                .doesNotHaveBean(ProductionSeedRejectionCallback.class));
            }
        }

        @Test
        @DisplayName("the published seeded-database refusal acts before a script is executed, which is "
                + "what keeps a refused database unwritten to")
        void thePublishedSeededDatabaseRefusalActsBeforeExecution() {
            final Callback callback = new FlywayConfig().productionSeedRejectionCallback();

            assertThat(callback.supports(Event.BEFORE_VALIDATE, null))
                    .as("both pre-execution events are handled, so this class's own diagnosis reaches "
                            + "the operator whichever the tool raises first")
                    .isTrue();
            assertThat(callback.supports(Event.BEFORE_MIGRATE, null)).isTrue();
            assertThat(callback.supports(Event.AFTER_MIGRATE, null))
                    .as("acting after execution would mean acting on a database it had already allowed "
                            + "to be written to")
                    .isFalse();
            assertThat(callback.getCallbackName())
                    .isEqualTo(ProductionSeedRejectionCallback.CALLBACK_NAME);
        }

        @Test
        @DisplayName("the two controls agree about where the seeds begin, so neither refuses what the "
                + "other allows")
        void theTwoControlsAgreeAboutWhereTheSeedsBegin() {
            assertThat(MigrationVersion.fromVersion(
                    ProductionSeedRejectionCallback.FIRST_SEED_VERSION).isAtLeast("3"))
                    .as("the database-level refusal draws its boundary at the first seed version. It is "
                            + "the second control and the only one that inspects the DATABASE: the "
                            + "location list decides what THIS run would apply and is therefore blind to "
                            + "a database seeded before this run existed - a re-pointed connection "
                            + "string, or a development dump restored into a production instance")
                    .isTrue();

            assertThat(deliveredMigrationSeedVersions())
                    .as("and the version the callback watches for must be the version the SEED LOCATION "
                            + "actually begins at. The two controls are otherwise independent - one "
                            + "reads a directory, the other reads the history table - so a "
                            + "disagreement here would leave the callback watching for a version no "
                            + "delivered seed carries and refusing nothing at all")
                    .isNotEmpty()
                    .allSatisfy(version -> assertThat(MigrationVersion.fromVersion(version)
                            .isAtLeast(ProductionSeedRejectionCallback.FIRST_SEED_VERSION))
                            .as("delivered seed version %s must be at or above the watched version %s",
                                    version, ProductionSeedRejectionCallback.FIRST_SEED_VERSION)
                            .isTrue());
            assertThat(deliveredMigrationSchemaVersions())
                    .as("while no version the SCHEMA location carries may reach it, or a production "
                            + "migration would refuse its own schema")
                    .isNotEmpty()
                    .allSatisfy(version -> assertThat(MigrationVersion.fromVersion(version)
                            .isAtLeast(ProductionSeedRejectionCallback.FIRST_SEED_VERSION))
                            .as("delivered schema version %s must sit below the watched version %s",
                                    version, ProductionSeedRejectionCallback.FIRST_SEED_VERSION)
                            .isFalse());
        }

        @Test
        @DisplayName("the two delivered locations are siblings under a parent that is refused, and the "
                + "target is the open marker rather than a version")
        void theDeliveredLocationsAndTargetAreTheShippedOnes() {
            assertThat(FlywayConfig.SCHEMA_LOCATION).isEqualTo("classpath:db/migration/schema");
            assertThat(new Location(FlywayConfig.SCHEMA_LOCATION).getPath())
                    .isEqualTo("db/migration/schema");
            assertThat(FlywayConfig.SEED_LOCATION).isEqualTo("classpath:db/migration/seed");
            assertThat(new Location(FlywayConfig.SEED_LOCATION).getPath())
                    .isEqualTo("db/migration/seed");
            assertThat(FlywayConfig.SHARED_PARENT_LOCATION).isEqualTo("classpath:db/migration");
            assertThat(new Location(FlywayConfig.SHARED_PARENT_LOCATION).getPath())
                    .isEqualTo("db/migration");

            assertThat(FlywayConfig.SCHEMA_LOCATION)
                    .as("neither delivered location may sit inside the other, or a profile resolving "
                            + "one would reach the other by recursion")
                    .doesNotStartWith(FlywayConfig.SEED_LOCATION + "/");
            assertThat(FlywayConfig.SEED_LOCATION)
                    .doesNotStartWith(FlywayConfig.SCHEMA_LOCATION + "/");
            assertThat(FlywayConfig.SCHEMA_LOCATION)
                    .as("while both must sit inside the parent, which is what makes the parent the one "
                            + "descriptor that has to be refused")
                    .startsWith(FlywayConfig.SHARED_PARENT_LOCATION + "/");
            assertThat(FlywayConfig.SEED_LOCATION)
                    .startsWith(FlywayConfig.SHARED_PARENT_LOCATION + "/");

            assertThat(FlywayConfig.ALL_RESOLVED_VERSIONS_TARGET).isEqualTo("latest");
            assertThat(MigrationVersion.fromVersion(FlywayConfig.ALL_RESOLVED_VERSIONS_TARGET))
                    .as("the marker the two seeding profiles declare must be the tool's own head "
                            + "marker, so no delivered or future version is above it")
                    .isEqualTo(MigrationVersion.LATEST);
        }

        @Test
        @DisplayName("the three profile names are the three the shipped documents are named after")
        void theThreeProfileNamesAreTheShippedOnes() {
            assertThat(FlywayConfig.PRODUCTION_PROFILE).isEqualTo("prod");
            assertThat(FlywayConfig.LOCAL_PROFILE).isEqualTo("local");
            assertThat(FlywayConfig.TEST_PROFILE).isEqualTo("test");
        }
    }

    /**
     * Enumerates every delivered SQL migration beneath the shared class-path parent.
     *
     * <p>The pattern stays recursive on purpose even though the delivered layout is flat: discovering
     * a script that had been hidden in a subdirectory is exactly how the layout assertions catch a
     * regression back towards the split arrangement. This helper only reports what was packaged; the
     * layout assertions decide which paths are acceptable.
     *
     * @return the delivered SQL resources, never {@code null}
     */
    private static List<Resource> migrationResources() {
        try {
            return Arrays.stream(new PathMatchingResourcePatternResolver()
                    .getResources("classpath*:db/migration/**/*.sql"))
                    .toList();
        } catch (IOException failure) {
            throw new UncheckedIOException("delivered migration resources are unreadable", failure);
        }
    }

    /**
     * Returns the paths of all delivered migrations relative to {@code db/migration}.
     *
     * @return sorted, forward-slash relative paths
     */
    private static List<String> migrationResourcePaths() {
        return migrationResources().stream()
                .map(FlywayConfigTest::migrationRelativePath)
                .sorted()
                .toList();
    }

    /**
     * Returns the versions the SCHEMA location's delivered scripts carry, ascending.
     *
     * @return the delivered schema versions, such as {@code [1, 2]}; never {@code null}
     */
    private static List<String> deliveredMigrationSchemaVersions() {
        return deliveredVersionsUnder("schema/");
    }

    /**
     * Returns the versions the SEED location's delivered scripts carry, ascending.
     *
     * @return the delivered seed versions, such as {@code [3, 4]}; never {@code null}
     */
    private static List<String> deliveredMigrationSeedVersions() {
        return deliveredVersionsUnder("seed/");
    }

    /**
     * Reads the versions of the delivered scripts sitting under one relative sub-directory.
     *
     * <p>Read off the class path rather than restated, so a script moved between the two locations
     * fails an assertion here rather than silently changing which profile applies it.</p>
     *
     * @param relativePrefix the relative path prefix, such as {@code schema/}
     * @return the versions, in file-name order; never {@code null}
     */
    private static List<String> deliveredVersionsUnder(final String relativePrefix) {
        return migrationResourcePaths().stream()
                .filter(path -> path.startsWith(relativePrefix))
                .map(path -> path.substring(path.lastIndexOf('/') + 1))
                .map(name -> {
                    final int separator = name.indexOf("__");
                    assertThat(separator)
                            .as("%s must be a versioned migration", name)
                            .isGreaterThan(1);
                    return name.substring(1, separator).replace('_', '.');
                })
                .sorted()
                .toList();
    }

    /**
     * Converts one class-path migration resource to its path beneath {@code db/migration}.
     *
     * @param resource the migration resource to describe
     * @return a forward-slash relative path
     */
    private static String migrationRelativePath(final Resource resource) {
        try {
            final String description =
                    resource.getURL().toExternalForm().replace('\\', '/');
            final String marker = "db/migration/";
            final int markerPosition = description.lastIndexOf(marker);
            if (markerPosition < 0) {
                throw new IllegalStateException(
                        "resolved SQL resource is outside db/migration: "
                                + resource.getDescription());
            }
            return description.substring(markerPosition + marker.length());
        } catch (IOException failure) {
            throw new UncheckedIOException("migration resource URL is unreadable", failure);
        }
    }

    /**
     * Describes every resource matching a class-path pattern without reading its contents.
     *
     * @param pattern the Spring resource pattern to resolve
     * @return sorted resource descriptions
     */
    private static List<String> resourceDescriptions(final String pattern) {
        try {
            return Arrays.stream(new PathMatchingResourcePatternResolver().getResources(pattern))
                    .map(Resource::getDescription)
                    .sorted()
                    .toList();
        } catch (IOException failure) {
            throw new UncheckedIOException("classpath resource pattern is unreadable", failure);
        }
    }

    /**
     * Reads one uniquely named migration and removes SQL comments before returning it.
     *
     * <p>Removing comments is essential because the migration documentation names rejected foreign
     * keys and surrogate-key mechanisms that the executable SQL deliberately does not declare.
     *
     * @param fileName the exact migration filename
     * @return executable SQL text with line and block comments removed
     */
    private static String migrationSql(final String fileName) {
        final List<Resource> matches = migrationResources().stream()
                .filter(resource -> fileName.equals(resource.getFilename()))
                .toList();
        assertThat(matches)
                .as("expected exactly one delivered migration named %s, but resolved %s",
                        fileName, matches.stream().map(Resource::getDescription).toList())
                .hasSize(1);

        try {
            return stripSqlComments(
                    matches.getFirst().getContentAsString(StandardCharsets.UTF_8));
        } catch (IOException failure) {
            throw new UncheckedIOException("migration text is unreadable: " + fileName, failure);
        }
    }

    /**
     * Removes line and block comments while preserving quoted SQL literals and line boundaries.
     *
     * @param sql the raw migration text
     * @return the same SQL with comments removed
     */
    private static String stripSqlComments(final String sql) {
        final StringBuilder stripped = new StringBuilder(sql.length());
        boolean inLiteral = false;
        boolean inLineComment = false;
        boolean inBlockComment = false;

        for (int index = 0; index < sql.length(); index++) {
            final char current = sql.charAt(index);
            final char next = index + 1 < sql.length() ? sql.charAt(index + 1) : '\0';

            if (inLineComment) {
                if (current == LINE_FEED) {
                    inLineComment = false;
                    stripped.append(current);
                }
            } else if (inBlockComment) {
                if (current == '*' && next == '/') {
                    inBlockComment = false;
                    index++;
                } else if (current == LINE_FEED) {
                    stripped.append(current);
                }
            } else if (inLiteral) {
                stripped.append(current);
                if (current == '\'' && next == '\'') {
                    stripped.append(next);
                    index++;
                } else if (current == '\'') {
                    inLiteral = false;
                }
            } else if (current == '\'') {
                inLiteral = true;
                stripped.append(current);
            } else if (current == '-' && next == '-') {
                inLineComment = true;
                index++;
            } else if (current == '/' && next == '*') {
                inBlockComment = true;
                index++;
            } else {
                stripped.append(current);
            }
        }

        if (inLiteral || inBlockComment) {
            throw new IllegalArgumentException(
                    "migration text contains an unterminated SQL literal or block comment");
        }
        return stripped.toString();
    }

    /**
     * Loads one shipped YAML resource and flattens all of its documents into property names.
     *
     * @param document the class-path YAML filename
     * @return an immutable map of flattened property names to values
     */
    private static Map<String, Object> yamlProperties(final String document) {
        final Resource resource = new ClassPathResource(document);
        assertThat(resource.exists())
                .as("expected shipped configuration document %s to exist", document)
                .isTrue();

        final List<PropertySource<?>> documents;
        try {
            documents = new YamlPropertySourceLoader().load(document, resource);
        } catch (IOException failure) {
            throw new UncheckedIOException(
                    "shipped configuration document is unreadable: " + document, failure);
        }

        final Map<String, Object> properties = new LinkedHashMap<>();
        for (final PropertySource<?> propertySource : documents) {
            if (!(propertySource instanceof EnumerablePropertySource<?> enumerable)) {
                throw new IllegalStateException(
                        "YAML loader returned a non-enumerable property source for " + document);
            }
            for (final String propertyName : enumerable.getPropertyNames()) {
                properties.put(propertyName, enumerable.getProperty(propertyName));
            }
        }
        assertThat(properties)
                .as("expected %s to load at least one flattened property", document)
                .isNotEmpty();
        return Map.copyOf(properties);
    }

    /**
     * Reads the comma-separated Flyway locations from a flattened YAML property map.
     *
     * @param properties the YAML properties
     * @return trimmed location descriptors in declaration order
     */
    private static List<String> declaredFlywayLocations(
            final Map<String, Object> properties) {
        final Object declared = properties.get("spring.flyway.locations");
        if (declared != null) {
            return Arrays.stream(String.valueOf(declared).split(","))
                    .map(String::strip)
                    .filter(location -> !location.isEmpty())
                    .toList();
        }
        // Both YAML spellings are read, and deliberately so. The two non-seeding documents declare one
        // location as a scalar; the two seeding documents declare two as a SEQUENCE, which a property
        // loader flattens into spring.flyway.locations[0] and [1]. A reader that understood only the
        // scalar would answer an empty list for those documents and make every assertion over them
        // vacuous at exactly the moment one of them mattered.
        final List<String> indexed = new ArrayList<>();
        for (int index = 0; properties.containsKey(indexedLocationKey(index)); index++) {
            final String entry = String.valueOf(properties.get(indexedLocationKey(index))).strip();
            if (!entry.isEmpty()) {
                indexed.add(entry);
            }
        }
        return List.copyOf(indexed);
    }

    /**
     * Names the flattened key one entry of a declared location sequence occupies.
     *
     * @param index the zero-based position in the sequence
     * @return the flattened key, such as {@code spring.flyway.locations[0]}
     */
    private static String indexedLocationKey(final int index) {
        return "spring.flyway.locations[" + index + "]";
    }

    /**
     * Reads the declared Flyway version ceiling from one shipped profile document.
     *
     * @param document the YAML resource name
     * @return the trimmed target, or {@code null} when the document declares none
     */
    private static String declaredFlywayTarget(final String document) {
        final Object declared = yamlProperties(document).get("spring.flyway.target");
        return declared == null ? null : String.valueOf(declared).strip();
    }

    /**
     * Interprets one YAML scalar as a boolean without relying on the process locale.
     *
     * @param value the scalar value
     * @return {@code true} only for the boolean spelling {@code true}
     */
    private static boolean booleanValue(final Object value) {
        return Boolean.parseBoolean(String.valueOf(value).strip());
    }

    /**
     * Builds the effective Flyway configuration for one profile from the shared and profile YAML,
     * then applies the production configuration class's contributed customizer.
     *
     * @param profile the active profile
     * @param profileDocument the profile-specific YAML resource
     * @return the directly inspectable effective Flyway configuration
     */
    private static FluentConfiguration effectiveConfiguration(final String profile,
            final String profileDocument) {
        final Map<String, Object> merged = new LinkedHashMap<>(
                yamlProperties("application.yml"));
        merged.putAll(yamlProperties(profileDocument));

        final List<String> locations = declaredFlywayLocations(merged);
        final FluentConfiguration configuration = new FluentConfiguration();
        if (!locations.isEmpty()) {
            configuration.locations(locations.toArray(String[]::new));
        }
        final Object target = merged.get("spring.flyway.target");
        if (target != null) {
            configuration.target(String.valueOf(target).strip());
        }

        final MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profile);
        new FlywayConfig().migrationScopeResolvingCustomizer(environment)
                .customize(configuration);
        return configuration;
    }

    /**
     * Reads location descriptors back from a directly constructed Flyway configuration.
     *
     * @param configuration the configuration to inspect
     * @return location descriptors in effective order
     */
    private static List<String> configurationLocations(
            final FluentConfiguration configuration) {
        return Arrays.stream(configuration.getLocations())
                .map(Location::getDescriptor)
                .toList();
    }

    /**
     * Collects capture group one from every match of a pattern.
     *
     * @param pattern the compiled structural SQL pattern
     * @param text the comment-free migration text
     * @return captured values in source order
     */
    private static List<String> capturedGroups(final Pattern pattern, final String text) {
        final Matcher matcher = pattern.matcher(text);
        final List<String> captures = new ArrayList<>();
        while (matcher.find()) {
            captures.add(matcher.group(1));
        }
        return List.copyOf(captures);
    }

    /**
     * Counts non-overlapping matches of a structural SQL pattern.
     *
     * @param pattern the pattern to count
     * @param text the comment-free migration text
     * @return the number of matches
     */
    private static int occurrenceCount(final Pattern pattern, final String text) {
        final Matcher matcher = pattern.matcher(text);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    /**
     * The four offending profile lists, paired with the non-production profile each one conflicts on.
     *
     * @return one argument pair per list, covering both activation orders of both combinations
     */
    static List<org.junit.jupiter.params.provider.Arguments> conflictingProfileLists() {
        return List.of(
                org.junit.jupiter.params.provider.Arguments.of(
                        List.of(FlywayConfig.PRODUCTION_PROFILE, FlywayConfig.LOCAL_PROFILE),
                        FlywayConfig.LOCAL_PROFILE),
                org.junit.jupiter.params.provider.Arguments.of(
                        List.of(FlywayConfig.LOCAL_PROFILE, FlywayConfig.PRODUCTION_PROFILE),
                        FlywayConfig.LOCAL_PROFILE),
                org.junit.jupiter.params.provider.Arguments.of(
                        List.of(FlywayConfig.PRODUCTION_PROFILE, FlywayConfig.TEST_PROFILE),
                        FlywayConfig.TEST_PROFILE),
                org.junit.jupiter.params.provider.Arguments.of(
                        List.of(FlywayConfig.TEST_PROFILE, FlywayConfig.PRODUCTION_PROFILE),
                        FlywayConfig.TEST_PROFILE));
    }

    /**
     * The four location lists that address no location at all.
     *
     * <p>Each is a different way for a merged configuration to end up migrating from nowhere: an
     * absent property, a property bound to an empty list, a property whose only entry is blank, and a
     * property whose only entry is {@code null}. All four have to be refused under production, because
     * a deployment that migrates nothing applies neither the schema nor the indexes.
     *
     * @return one argument pair per list, each carrying a description used in the failure message
     */
    static List<org.junit.jupiter.params.provider.Arguments> emptyLocationLists() {
        final List<String> onlyNull = new ArrayList<>();
        onlyNull.add(null);
        return List.of(
                org.junit.jupiter.params.provider.Arguments.of("an absent property", null),
                org.junit.jupiter.params.provider.Arguments.of("an empty list", List.of()),
                org.junit.jupiter.params.provider.Arguments.of("a single blank entry",
                        List.of("   ")),
                org.junit.jupiter.params.provider.Arguments.of("a single null entry", onlyNull));
    }
}
