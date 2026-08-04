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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;

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
 * {@code V4__seed_user_security.sql}. The first two create the application schema; the latter two
 * provide non-production fixtures. Production must therefore exclude the seed scripts through at
 * least one independently observable mechanism: a location list that cannot resolve them or a
 * version ceiling at {@code 2}. The current implementation deliberately retains both controls.
 *
 * <h3>Security rationale</h3>
 *
 * <p>The reference-data migration supplies 626 fixture rows across nine tables, including fifty
 * synthetic customer records. The sign-on migration supplies ten known identities, five of them
 * administrator-capable, whose BCrypt digests derive from one well-known source credential. Either
 * script reaching production would therefore be a credential and privacy incident, not a harmless
 * data-quality defect. The location-versus-ceiling decision and its historical divergence are
 * recorded in {@code docs/decision-log.md}, especially decision {@code DL-127}; this test encodes the
 * control so either safe mechanism remains valid.
 *
 * <h3>Suite boundary</h3>
 *
 * <p>This surefire-tier test reads configuration and SQL as text and never starts a database or runs a
 * migration. {@code CardDemoApplicationIT} and the repository integration tier own applied versions,
 * schema history, runtime table counts and production execution against PostgreSQL.
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
                        "spring.flyway.target=" + FlywayConfig.SCHEMA_ONLY_TARGET,
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
        @DisplayName("scripts are flat or one mechanism directory deep, never hidden more deeply")
        void scriptsAreNeverHiddenMoreDeeplyThanOneMechanismDirectory() {
            final List<String> actualPaths = migrationResourcePaths();

            assertThat(actualPaths)
                    .as("expected migration scripts directly below db/migration or one level below "
                            + "in schema or seed; actual paths were %s", actualPaths)
                    .isNotEmpty()
                    .allSatisfy(path -> {
                        final long separators = path.chars().filter(character -> character == '/')
                                .count();
                        assertThat(separators)
                                .as("expected %s to be flat or one directory deep, but the actual "
                                        + "relative path was %s", path, path)
                                .isLessThanOrEqualTo(1L);
                        if (separators == 1L) {
                            assertThat(path.substring(0, path.indexOf('/')))
                                    .as("the only delivered mechanism directories are schema and seed; "
                                            + "actual path was %s", path)
                                    .isIn("schema", "seed");
                        }
                    });
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
        @DisplayName("every declared location is delivered and none names the recursive parent")
        void everyDeclaredLocationIsDeliveredAndNeverTheSharedParent() {
            final Set<String> deliveredLocations = Set.of(
                    "classpath:db/migration/schema",
                    "classpath:db/migration/seed");

            for (final String document : List.of(
                    "application.yml",
                    "application-prod.yml",
                    "application-local.yml",
                    "application-test.yml")) {
                final List<String> actual =
                        declaredFlywayLocations(yamlProperties(document));

                assertThat(actual)
                        .as("expected %s to name only the delivered schema and seed locations; actual "
                                + "locations were %s", document, actual)
                        .isNotEmpty()
                        .allMatch(deliveredLocations::contains);
                assertThat(actual)
                        .as("expected %s never to name recursive parent classpath:db/migration; actual "
                                + "locations were %s", document, actual)
                        .doesNotContain("classpath:db/migration");
            }
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
                        .doesNotHaveBean(DataSource.class);
            });
        }
    }

    @Nested
    @DisplayName("the seed-exclusion security control is in force")
    class TheSeedExclusionControlIsInForce {

        @Test
        @DisplayName("production excludes seeds by location or target while local and test do neither")
        void productionHasAControlAndSeedingProfilesHaveNeither() {
            final FluentConfiguration production =
                    effectiveConfiguration("prod", "application-prod.yml");
            final boolean productionLocationsExcludeSeeds =
                    locationsExcludeSeedMigrations(configurationLocations(production));
            final boolean productionTargetCapsSeeds =
                    targetCapsAtSchema(production.getTarget());

            assertThat(productionLocationsExcludeSeeds || productionTargetCapsSeeds)
                    .as("Neither seed-exclusion mechanism is in force: resolved "
                            + "spring.flyway.locations can reach the seed migrations and the effective "
                            + "target ceiling is not capped at version 2. Production must not inherit "
                            + "sample data or seeded credentials; reconcile both mechanisms against "
                            + "docs/decision-log.md")
                    .isTrue();

            for (final Map.Entry<String, String> profile : Map.of(
                    "local", "application-local.yml",
                    "test", "application-test.yml").entrySet()) {
                final FluentConfiguration effective =
                        effectiveConfiguration(profile.getKey(), profile.getValue());
                final List<String> locations = configurationLocations(effective);

                assertThat(locationsExcludeSeedMigrations(locations))
                        .as("%s must resolve seed migrations; effective locations were %s",
                                profile.getKey(), locations)
                        .isFalse();
                assertThat(targetCapsAtSchema(effective.getTarget()))
                        .as("%s must not retain the production target ceiling; effective target was %s",
                                profile.getKey(), effective.getTarget())
                        .isFalse();
            }
        }

        @Test
        @DisplayName("the contributed code refuses or repairs a scope with neither control")
        void theContributedCodeEnforcesRatherThanMerelyDeclaresTheControl() {
            final MockEnvironment production = new MockEnvironment();
            production.setActiveProfiles("prod");
            final FluentConfiguration unsafe = new FluentConfiguration()
                    .locations("classpath:db/migration")
                    .target(MigrationVersion.LATEST);
            boolean refused = false;

            try {
                new FlywayConfig().migrationScopeResolvingCustomizer(production).customize(unsafe);
            } catch (IllegalStateException refusal) {
                refused = true;
                assertCarriesNoRawTerminator(refusal.getMessage());
            }

            assertThat(refused
                    || locationsExcludeSeedMigrations(configurationLocations(unsafe))
                    || targetCapsAtSchema(unsafe.getTarget()))
                    .as("The code-level production control did not enforce either mechanism: it "
                            + "neither refused/restricted the migration locations nor imposed the "
                            + "version-2 target ceiling. Production must not inherit sample data or "
                            + "seeded credentials; see docs/decision-log.md")
                    .isTrue();
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
        @DisplayName("production migrating from the delivered location alone is returned unchanged")
        void productionMigratingFromTheSchemaLocationAloneIsUnchanged() {
            assertThat(FlywayConfig.resolveLocations(
                    List.of(FlywayConfig.PRODUCTION_PROFILE),
                    List.of(FlywayConfig.SCHEMA_LOCATION)))
                    .containsExactly(FlywayConfig.SCHEMA_LOCATION);
        }

        @ParameterizedTest(name = "production accepts the delivered location written [{0}]")
        @ValueSource(strings = {
            "classpath:db/migration/schema",
            " classpath:db/migration/schema",
            "classpath:db/migration/schema ",
            "\tclasspath:db/migration/schema\t"
        })
        @DisplayName("production accepts the delivered location and nothing more forgiving than "
                + "surrounding whitespace, because a property value can pick up padding in transit")
        void productionAcceptsTheDeliveredLocationAndOnlySurroundingWhitespace(final String written) {
            assertThatNoException().isThrownBy(() -> FlywayConfig.resolveLocations(
                    List.of(FlywayConfig.PRODUCTION_PROFILE), List.of(written)));
        }

        @ParameterizedTest(name = "production refuses the near-spelling [{0}]")
        @ValueSource(strings = {
            "classpath:db/migration/schema/",
            "classpath:db/migration/schema/regional",
            "classpath:db/migration",
            "classpath:db/migration/seed",
            "filesystem:src/main/resources/db/migration/schema",
            "filesystem:/tmp/attacker/db/migration/schema",
            "db/migration/schema",
            "classpath:/db/migration/schema",
            "CLASSPATH:db/migration/schema"
        })
        @DisplayName("production refuses every near-spelling of the delivered location, because a "
                + "containment test cannot separate the packaged directory from a look-alike on disk")
        void productionRefusesEveryNearSpellingOfTheDeliveredLocation(final String nearSpelling) {
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

        @ParameterizedTest(name = "production refuses the seed location spelled [{0}]")
        @ValueSource(strings = {
            "classpath:db/migration/seed",
            "classpath:db/migration/seed/",
            "filesystem:src/main/resources/db/migration/seed",
            "db/migration/seed"
        })
        @DisplayName("production refuses the seed location in every spelling, which is the mechanism "
                + "itself: a script that is never resolved cannot be applied by any ceiling")
        void productionRefusesTheSeedLocationInEverySpelling(final String spelling) {
            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> FlywayConfig.resolveLocations(
                            List.of(FlywayConfig.PRODUCTION_PROFILE),
                            List.of(FlywayConfig.SCHEMA_LOCATION, spelling)))
                    .withMessageContaining(FlywayConfig.SCHEMA_LOCATION)
                    .withMessageContaining(FlywayConfig.SEED_LOCATION)
                    .satisfies(refusal -> assertCarriesNoRawTerminator(refusal.getMessage()));
        }

        @ParameterizedTest(name = "production refuses the shared parent spelled [{0}]")
        @ValueSource(strings = {
            "classpath:db/migration/",
            "filesystem:src/main/resources/db/migration",
            "db/migration"
        })
        @DisplayName("production refuses the shared parent of the two delivered locations, because a "
                + "location is scanned recursively and the parent reaches the seeds through the child")
        void productionRefusesTheSharedParent(final String spelling) {
            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> FlywayConfig.resolveLocations(
                            List.of(FlywayConfig.PRODUCTION_PROFILE), List.of(spelling)))
                    .withMessageContaining(FlywayConfig.SCHEMA_LOCATION)
                    .satisfies(refusal -> assertCarriesNoRawTerminator(refusal.getMessage()));
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
        @DisplayName("production refuses any location outside the schema location, because a location "
                + "the retained ceiling never measured can carry a script it does not cap")
        void productionRefusesAnyForeignLocation(final String foreignLocation) {
            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> FlywayConfig.resolveLocations(
                            List.of(FlywayConfig.PRODUCTION_PROFILE),
                            List.of(FlywayConfig.SCHEMA_LOCATION, foreignLocation)))
                    .withMessageContaining(FlywayConfig.SCHEMA_LOCATION)
                    .withMessageContaining(FlywayConfig.SCHEMA_ONLY_TARGET)
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

        @ParameterizedTest(name = "{0} has BOTH delivered locations completed for it")
        @ValueSource(strings = {"local", "test"})
        @DisplayName("a non-production profile that resolved no location at all has both delivered "
                + "locations appended, because it would otherwise migrate nothing and seed nothing")
        void aNonProductionProfileWithNoLocationHasTheDeliveredOneAppended(final String profile) {
            assertThat(FlywayConfig.resolveLocations(List.of(profile), List.of()))
                    .containsExactly(FlywayConfig.SCHEMA_LOCATION, FlywayConfig.SEED_LOCATION);
        }

        @ParameterizedTest(name = "{0} has the seed location completed when it declared the schema one")
        @ValueSource(strings = {"local", "test"})
        @DisplayName("a non-production profile that resolved the schema location alone has the seed "
                + "location appended, because it would otherwise build an empty schema")
        void aNonProductionProfileWithTheDeliveredLocationIsLeftAlone(final String profile) {
            assertThat(FlywayConfig.resolveLocations(
                    List.of(profile), List.of(FlywayConfig.SCHEMA_LOCATION)))
                    .containsExactly(FlywayConfig.SCHEMA_LOCATION, FlywayConfig.SEED_LOCATION);
        }

        @ParameterizedTest(name = "{0} has the schema location completed when it declared the seed one")
        @ValueSource(strings = {"local", "test"})
        @DisplayName("a non-production profile that resolved the seed location alone has the schema "
                + "location appended, because seeding a database with no tables cannot succeed")
        void aNonProductionProfileWithTheSeedLocationAloneHasTheSchemaOneAppended(
                final String profile) {
            assertThat(FlywayConfig.resolveLocations(
                    List.of(profile), List.of(FlywayConfig.SEED_LOCATION)))
                    .containsExactly(FlywayConfig.SEED_LOCATION, FlywayConfig.SCHEMA_LOCATION);
        }

        @ParameterizedTest(name = "{0} keeps both locations it already declared, in its own order")
        @ValueSource(strings = {"local", "test"})
        @DisplayName("a non-production profile that already resolved both delivered locations is left "
                + "exactly alone, so nothing is appended twice")
        void aNonProductionProfileWithBothLocationsIsLeftAlone(final String profile) {
            final List<String> declared =
                    List.of(FlywayConfig.SCHEMA_LOCATION, FlywayConfig.SEED_LOCATION);

            assertThat(FlywayConfig.resolveLocations(List.of(profile), declared))
                    .containsExactlyElementsOf(declared);
        }

        @Test
        @DisplayName("a non-production profile already listing both delivered locations is returned "
                + "unchanged, so the declared order survives alongside a foreign entry")
        void aNonProductionProfileListingTheDeliveredLocationIsUnchanged() {
            final List<String> declared = List.of(FlywayConfig.SCHEMA_LOCATION,
                    "classpath:db/extra", FlywayConfig.SEED_LOCATION);

            assertThat(FlywayConfig.resolveLocations(List.of(FlywayConfig.LOCAL_PROFILE), declared))
                    .containsExactlyElementsOf(declared);
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
        @DisplayName("production refuses a blank or null entry alongside the delivered location, "
                + "because a second entry is a second migration source whatever it holds")
        void productionRefusesABlankOrNullEntryAlongsideTheDeliveredLocation() {
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
        @DisplayName("production refuses the delivered location listed twice, because the guard "
                + "counts entries rather than distinct values")
        void productionRefusesTheDeliveredLocationListedTwice() {
            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> FlywayConfig.resolveLocations(
                            List.of(FlywayConfig.PRODUCTION_PROFILE),
                            List.of(FlywayConfig.SCHEMA_LOCATION, FlywayConfig.SCHEMA_LOCATION)));
        }
    }

    @Nested
    @DisplayName("the version ceiling is resolved from the active profiles")
    class TheVersionCeilingIsResolvedFromTheProfiles {

        @Test
        @DisplayName("production declaring the schema-only ceiling is returned unchanged")
        void productionDeclaringTheCeilingIsUnchanged() {
            assertThat(FlywayConfig.resolveTarget(
                    List.of(FlywayConfig.PRODUCTION_PROFILE), FlywayConfig.SCHEMA_ONLY_TARGET))
                    .isEqualTo(FlywayConfig.SCHEMA_ONLY_TARGET);
        }

        @ParameterizedTest(name = "production refuses the under-migrating ceiling [{0}]")
        @ValueSource(strings = {"1", "1.1", "0", "1.9999", "2.0"})
        @DisplayName("production refuses a ceiling below the schema-only one, because it stops before "
                + "the indexes and constraints are created and reports success anyway")
        void productionRefusesACeilingBelowTheSchemaOnlyOne(final String ceiling) {
            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> FlywayConfig.resolveTarget(
                            List.of(FlywayConfig.PRODUCTION_PROFILE), ceiling))
                    .withMessageContaining(FlywayConfig.SCHEMA_ONLY_TARGET)
                    .satisfies(refusal -> assertCarriesNoRawTerminator(refusal.getMessage()));
        }

        @ParameterizedTest(name = "production refuses the ceiling [{0}]")
        @ValueSource(strings = {"latest", "LATEST", "3", "4", "current", "next", "not-a-version"})
        @DisplayName("production refuses any ceiling that reaches the seeds, including a predefined "
                + "marker and an unreadable value")
        void productionRefusesAnyCeilingThatReachesTheSeeds(final String ceiling) {
            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> FlywayConfig.resolveTarget(
                            List.of(FlywayConfig.PRODUCTION_PROFILE), ceiling))
                    .withMessageContaining(FlywayConfig.SCHEMA_ONLY_TARGET)
                    .satisfies(refusal -> assertCarriesNoRawTerminator(refusal.getMessage()));
        }

        @Test
        @DisplayName("production refuses an absent ceiling, because Flyway migrates to the latest "
                + "version when none is set")
        void productionRefusesAnAbsentCeiling() {
            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> FlywayConfig.resolveTarget(
                            List.of(FlywayConfig.PRODUCTION_PROFILE), null));
            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> FlywayConfig.resolveTarget(
                            List.of(FlywayConfig.PRODUCTION_PROFILE), "   "));
        }

        @ParameterizedTest(name = "{0} has the inherited ceiling lifted for it")
        @ValueSource(strings = {"local", "test"})
        @DisplayName("a seeding profile that inherited the schema-only ceiling has it lifted, "
                + "because it would otherwise migrate none of its fixtures")
        void aSeedingProfileHasTheInheritedCeilingLifted(final String profile) {
            assertThat(FlywayConfig.resolveTarget(
                    List.of(profile), FlywayConfig.SCHEMA_ONLY_TARGET))
                    .isEqualTo(FlywayConfig.SEEDING_TARGET);
        }

        @ParameterizedTest(name = "{0} keeps a ceiling that already reaches the seeds")
        @ValueSource(strings = {"local", "test"})
        @DisplayName("a seeding profile declaring the seeding ceiling itself is returned unchanged")
        void aSeedingProfileDeclaringTheSeedingCeilingIsUnchanged(final String profile) {
            assertThat(FlywayConfig.resolveTarget(
                    List.of(profile), FlywayConfig.SEEDING_TARGET))
                    .isEqualTo(FlywayConfig.SEEDING_TARGET);
            assertThat(FlywayConfig.resolveTarget(List.of(profile), null)).isNull();
        }

        @Test
        @DisplayName("no active profile leaves the bound ceiling alone")
        void noActiveProfileLeavesTheBoundCeilingAlone() {
            assertThat(FlywayConfig.resolveTarget(List.of(), FlywayConfig.SCHEMA_ONLY_TARGET))
                    .isEqualTo(FlywayConfig.SCHEMA_ONLY_TARGET);
            assertThat(FlywayConfig.resolveTarget(null, FlywayConfig.SCHEMA_ONLY_TARGET))
                    .isEqualTo(FlywayConfig.SCHEMA_ONLY_TARGET);
        }

        @Test
        @DisplayName("the published customizer completes BOTH halves for a non-production profile: it "
                + "lifts the ceiling and appends the seed location")
        void thePublishedCustomizerLiftsTheCeiling() {
            final MockEnvironment local = new MockEnvironment();
            local.setActiveProfiles(FlywayConfig.LOCAL_PROFILE);
            final FluentConfiguration configuration = new FluentConfiguration()
                    .locations(FlywayConfig.SCHEMA_LOCATION)
                    .target(FlywayConfig.SCHEMA_ONLY_TARGET);

            new FlywayConfig().migrationScopeResolvingCustomizer(local).customize(configuration);

            assertThat(configuration.getTarget()).isEqualTo(MigrationVersion.LATEST);
            assertThat(Arrays.stream(configuration.getLocations())
                    .map(Location::getDescriptor)
                    .toList())
                    .as("both halves are needed: the seed location makes the seeds resolvable and the "
                            + "lifted ceiling makes them apply, and either alone leaves an unseeded "
                            + "database")
                    .containsExactly(FlywayConfig.SCHEMA_LOCATION, FlywayConfig.SEED_LOCATION);
        }

        @Test
        @DisplayName("the published customizer refuses the seed location under production, which is "
                + "the whole mechanism: the seeds are never resolved rather than merely not applied")
        void thePublishedCustomizerRefusesTheSeedLocationUnderProduction() {
            final MockEnvironment production = new MockEnvironment();
            production.setActiveProfiles(FlywayConfig.PRODUCTION_PROFILE);
            final FluentConfiguration configuration = new FluentConfiguration()
                    .locations(FlywayConfig.SCHEMA_LOCATION, FlywayConfig.SEED_LOCATION)
                    .target(FlywayConfig.SCHEMA_ONLY_TARGET);
            final FlywayConfigurationCustomizer customizer =
                    new FlywayConfig().migrationScopeResolvingCustomizer(production);

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> customizer.customize(configuration))
                    .withMessageContaining(FlywayConfig.SEED_LOCATION);
        }

        @Test
        @DisplayName("the published customizer refuses a bound configuration that reaches the seeds "
                + "under production")
        void thePublishedCustomizerRefusesTheSeedsUnderProduction() {
            final MockEnvironment production = new MockEnvironment();
            production.setActiveProfiles(FlywayConfig.PRODUCTION_PROFILE);
            final FluentConfiguration configuration = new FluentConfiguration()
                    .locations(FlywayConfig.SCHEMA_LOCATION)
                    .target(FlywayConfig.SEEDING_TARGET);
            final FlywayConfigurationCustomizer customizer =
                    new FlywayConfig().migrationScopeResolvingCustomizer(production);

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> customizer.customize(configuration))
                    .withMessageContaining(FlywayConfig.SCHEMA_ONLY_TARGET);
        }

        @Test
        @DisplayName("the published customizer refuses a foreign location under production")
        void thePublishedCustomizerRefusesAForeignLocationUnderProduction() {
            final MockEnvironment production = new MockEnvironment();
            production.setActiveProfiles(FlywayConfig.PRODUCTION_PROFILE);
            final FluentConfiguration configuration = new FluentConfiguration()
                    .locations(FlywayConfig.SCHEMA_LOCATION, "classpath:db/fixtures")
                    .target(FlywayConfig.SCHEMA_ONLY_TARGET);
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
                    .target(FlywayConfig.SCHEMA_ONLY_TARGET);

            new FlywayConfig().migrationScopeResolvingCustomizer(production)
                    .customize(configuration);

            assertThat(Arrays.stream(configuration.getLocations())
                    .map(Location::getDescriptor)
                    .toList())
                    .containsExactly(FlywayConfig.SCHEMA_LOCATION);
            assertThat(configuration.getTarget())
                    .isEqualTo(MigrationVersion.fromVersion(FlywayConfig.SCHEMA_ONLY_TARGET));
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
        @DisplayName("the foreign-location refusal names the two delivered locations and never the "
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
                            "spring.flyway.locations=classpath:db/migration/schema")
                    .run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(ordinaryBeanConstructed)
                                .as("the profile guard must refuse startup before any ordinary bean "
                                        + "supplier is invoked")
                                .isFalse();
                    });
        }

        @Test
        @DisplayName("the sealing component is restricted to exactly the two profiles that can seed "
                + "a row")
        void theSealingComponentIsRestrictedToTheSeedingProfiles() {
            for (final String profile : List.of("local", "test")) {
                runner().withPropertyValues("spring.profiles.active=" + profile).run(context ->
                        assertThat(context)
                                .as("%s deliberately seeds rows and must publish the sealing callback",
                                        profile)
                                .hasNotFailed()
                                .hasSingleBean(SeededIdentifierSealingCallback.class)
                                .doesNotHaveBean(ProductionSeedRejectionCallback.class));
            }
            runner().withPropertyValues("spring.profiles.active=prod").run(context ->
                    assertThat(context)
                            .as("production has no seeded row to seal")
                            .hasNotFailed()
                            .doesNotHaveBean(SeededIdentifierSealingCallback.class));
        }

        @Test
        @DisplayName("the published sealing component acts on the after-migrate event and names "
                + "itself")
        void thePublishedSealingComponentActsOnAfterMigrate() {
            final Callback callback = new FlywayConfig().seededIdentifierSealingCallback(
                    new SensitiveFieldEncryptionService(TEST_KEY));

            assertThat(callback.supports(Event.AFTER_MIGRATE, null)).isTrue();
            assertThat(callback.getCallbackName())
                    .isEqualTo(SeededIdentifierSealingCallback.CALLBACK_NAME);
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
        @DisplayName("the three controls agree about where the seeds begin, so none of them refuses "
                + "what another allows")
        void theThreeControlsAgreeAboutWhereTheSeedsBegin() {
            assertThat(MigrationVersion.fromVersion(
                    ProductionSeedRejectionCallback.FIRST_SEED_VERSION).isAtLeast("3"))
                    .as("the database-level refusal draws its boundary at the first seed version, and "
                            + "the two configuration controls draw theirs immediately below it; a "
                            + "disagreement would leave one control refusing what another allowed")
                    .isTrue();
            assertThat(MigrationVersion.fromVersion(FlywayConfig.SCHEMA_ONLY_TARGET).isAtLeast(
                    ProductionSeedRejectionCallback.FIRST_SEED_VERSION))
                    .isFalse();
        }

        @Test
        @DisplayName("the two locations are the two the delivered scripts occupy, they are siblings "
                + "rather than nested, and the two ceilings sit either side of the first seed version")
        void theDeliveredLocationAndCeilingsAreTheShippedOnes() {
            assertThat(FlywayConfig.SCHEMA_LOCATION).isEqualTo("classpath:db/migration/schema");
            assertThat(new Location(FlywayConfig.SCHEMA_LOCATION).getPath())
                    .isEqualTo("db/migration/schema");
            assertThat(FlywayConfig.SEED_LOCATION).isEqualTo("classpath:db/migration/seed");
            assertThat(new Location(FlywayConfig.SEED_LOCATION).getPath())
                    .isEqualTo("db/migration/seed");
            assertThat(new Location(FlywayConfig.SEED_LOCATION).getPath())
                    .as("neither delivered location may contain the other, or a single listing would "
                            + "resolve both and the location list would stop being a boundary")
                    .doesNotStartWith(new Location(FlywayConfig.SCHEMA_LOCATION).getPath());
            assertThat(new Location(FlywayConfig.SCHEMA_LOCATION).getPath())
                    .doesNotStartWith(new Location(FlywayConfig.SEED_LOCATION).getPath());
            assertThat(FlywayConfig.SCHEMA_ONLY_TARGET).isEqualTo("2");
            assertThat(FlywayConfig.SEEDING_TARGET).isEqualTo("latest");
            assertThat(MigrationVersion.fromVersion(FlywayConfig.SCHEMA_ONLY_TARGET)
                    .isAtLeast("3")).isFalse();
            assertThat(MigrationVersion.fromVersion(FlywayConfig.SEEDING_TARGET)
                    .isAtLeast("3")).isTrue();
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
     * <p>The recursive form intentionally supports both layouts recorded in the decision log: scripts
     * directly in the parent and scripts one mechanism directory below it. The layout assertions
     * decide which paths are acceptable; this helper only discovers what was packaged.
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
        if (declared == null) {
            return List.of();
        }
        return Arrays.stream(String.valueOf(declared).split(","))
                .map(String::strip)
                .filter(location -> !location.isEmpty())
                .toList();
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
     * Determines whether a location list makes both supported seed layouts unreachable.
     *
     * <p>The flat layout resolves seeds through {@code classpath:db/migration}; the split layout
     * resolves them through {@code classpath:db/migration/seed}. An empty list is not treated as a
     * security control because it also makes the application schema unreachable.
     *
     * @param locations the effective location descriptors
     * @return {@code true} when neither seed-bearing location can be scanned
     */
    private static boolean locationsExcludeSeedMigrations(final List<String> locations) {
        if (locations.isEmpty()) {
            return false;
        }
        return locations.stream().noneMatch(location -> {
            if (location == null) {
                return true;
            }
            String normalized = location.strip();
            while (normalized.endsWith("/") && normalized.length() > 1) {
                normalized = normalized.substring(0, normalized.length() - 1);
            }
            return "classpath:db/migration".equals(normalized)
                    || "classpath:db/migration/seed".equals(normalized);
        });
    }

    /**
     * Determines whether an effective target is the schema-only version ceiling.
     *
     * @param target the Flyway target, possibly {@code null}
     * @return {@code true} only for version {@code 2}
     */
    private static boolean targetCapsAtSchema(final MigrationVersion target) {
        return MigrationVersion.fromVersion("2").equals(target);
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
