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

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

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
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.mock.env.MockEnvironment;

import com.carddemo.service.SensitiveFieldEncryptionService;

/**
 * Verifies the two guarantees {@link FlywayConfig} exists to enforce, and verifies them the way a
 * deployment would meet them rather than by restating the source.
 *
 * <h2>What is asserted, and why each group is separate</h2>
 *
 * <p>The first group is the co-activation refusal. It is asserted three times over, deliberately: as
 * a pure function of a profile list, through the published bean-factory post-processor, and through a
 * real application context started with the offending profile list. The third is the one that
 * matters - it is the only form that proves the refusal lands where a deployment would meet it - but
 * the first two are what make a failure legible when it happens.
 *
 * <p>The second group is the migration-scope resolution recorded as decision {@code DL-127} in
 * {@code docs/decision-log.md}. The delivered scripts ship from two sibling locations - the schema
 * scripts from one, the seeds from the other, with no script in their shared parent - and production
 * resolves the schema location alone, with the version ceiling retained behind it. The scope therefore
 * has two halves and both are asserted. <strong>The load-bearing assertion is that production refuses
 * the seed location outright</strong>, in every spelling, and refuses the shared parent too, since a
 * location is scanned recursively and the parent would reach the seeds through the child directory: a
 * script that is never resolved cannot be applied by any ceiling. Refusing a ceiling that reaches the
 * seeds is asserted beside it as the retained second control. Both are asserted against the merged,
 * bound values rather than against a document, because inheritance is what a running application
 * resolves; and a non-production profile having both locations completed and its ceiling lifted for it
 * is asserted too, because a fixture-bearing profile that silently seeded nothing is also a defect.
 *
 * <p>The third group is diagnostic hygiene under decision {@code DL-041}. The active-profile list,
 * the location list and the ceiling are all operator-supplied, so a refusal that echoed any of them
 * would let whoever composed it write a chosen line into the start-up log. Every refusal here is
 * checked for the absence of the hostile text it was given and for the absence of a raw line
 * terminator.
 *
 * <p>The fourth group pins the structural facts the guarantees rest on: that the guard is published
 * from a {@code static} factory method, so publishing it cannot put an ordinary bean's construction
 * first, and that the sealing component is restricted to exactly the two profiles that can seed a
 * row.
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
        @DisplayName("the class is a non-proxying configuration that can hold no state and cannot "
                + "be extended")
        void theClassIsANonProxyingConfiguration() {
            assertThat(FlywayConfig.class.getAnnotation(Configuration.class)).isNotNull();
            assertThat(FlywayConfig.class.getAnnotation(Configuration.class).proxyBeanMethods())
                    .isFalse();
            assertThat(Modifier.isFinal(FlywayConfig.class.getModifiers())).isTrue();
            assertThat(FlywayConfig.class.getDeclaredFields())
                    .as("a guard that held state could answer differently on two calls")
                    .allMatch(field -> Modifier.isStatic(field.getModifiers()));
        }

        @Test
        @DisplayName("the guard is published from a static factory method, so publishing it cannot "
                + "put an ordinary bean's construction first")
        void theGuardIsPublishedFromAStaticFactoryMethod() throws NoSuchMethodException {
            final Method factory = FlywayConfig.class
                    .getDeclaredMethod("profileActivationGuard",
                            org.springframework.core.env.Environment.class);

            assertThat(Modifier.isStatic(factory.getModifiers()))
                    .as("a non-static factory would instantiate the configuration class before the "
                            + "post-processor could run")
                    .isTrue();
            assertThat(BeanFactoryPostProcessor.class).isAssignableFrom(factory.getReturnType());
        }

        @Test
        @DisplayName("the sealing component is restricted to exactly the two profiles that can seed "
                + "a row")
        void theSealingComponentIsRestrictedToTheSeedingProfiles() throws NoSuchMethodException {
            final Method factory = FlywayConfig.class.getDeclaredMethod(
                    "seededIdentifierSealingCallback", SensitiveFieldEncryptionService.class);
            final Profile profile = factory.getAnnotation(Profile.class);

            assertThat(profile)
                    .as("production must not carry the component at all, having no row to seal")
                    .isNotNull();
            assertThat(profile.value()).containsExactlyInAnyOrder(
                    FlywayConfig.LOCAL_PROFILE, FlywayConfig.TEST_PROFILE);
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
        void theSeededDatabaseRefusalIsRestrictedToProduction() throws NoSuchMethodException {
            final Method factory =
                    FlywayConfig.class.getDeclaredMethod("productionSeedRejectionCallback");
            final Profile profile = factory.getAnnotation(Profile.class);

            assertThat(profile)
                    .as("an unconditional registration would refuse every local and test start-up, "
                            + "because those profiles deliberately seed the very rows it looks for")
                    .isNotNull();
            assertThat(profile.value()).containsExactly(FlywayConfig.PRODUCTION_PROFILE);
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
