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
 * <p>The second group is the migration-scope resolution recorded as decision {@code DL-102} in
 * {@code docs/decision-log.md}. Every delivered script ships from one directory and the seeds are
 * excluded by a version ceiling, so the scope has two halves and both are asserted. Production
 * refusing a ceiling that reaches the seeds, and refusing a location outside the delivered one, are
 * both asserted against the merged, bound values rather than against a document, because inheritance
 * is what a running application resolves; and a non-production profile having its ceiling lifted for
 * it is asserted too, because a fixture-bearing profile that silently migrated no fixtures is also a
 * defect.
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
     * Builds a context runner over the class under test with the one collaborator it needs.
     *
     * @return a runner that registers the configuration and an encryption service
     */
    private static ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withUserConfiguration(FlywayConfig.class)
                .withBean(SensitiveFieldEncryptionService.class,
                        () -> new SensitiveFieldEncryptionService(TEST_KEY));
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

        @ParameterizedTest(name = "production accepts the delivered location spelled [{0}]")
        @ValueSource(strings = {
            "classpath:db/migration",
            "classpath:db/migration/",
            "classpath:db/migration/regional",
            "filesystem:src/main/resources/db/migration",
            "db/migration"
        })
        @DisplayName("production accepts every spelling of the delivered location, so a legitimate "
                + "descriptor is not refused on a formatting difference")
        void productionAcceptsEverySpellingOfTheDeliveredLocation(final String spelling) {
            assertThatNoException().isThrownBy(() -> FlywayConfig.resolveLocations(
                    List.of(FlywayConfig.PRODUCTION_PROFILE), List.of(spelling)));
        }

        @ParameterizedTest(name = "production refuses the location [{0}]")
        @ValueSource(strings = {
            "classpath:db/seed",
            "classpath:db/migrations",
            "classpath:db/fixtures",
            "filesystem:/tmp/extra",
            "db/seed"
        })
        @DisplayName("production refuses any location outside the delivered one, because a second "
                + "location carries scripts no version ceiling caps")
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
                            List.of("classpath:db/seed", FlywayConfig.SCHEMA_LOCATION)));
        }

        @ParameterizedTest(name = "{0} has the delivered location completed for it")
        @ValueSource(strings = {"local", "test"})
        @DisplayName("a non-production profile that resolved no location at all has the delivered "
                + "one appended, because it would otherwise migrate nothing")
        void aNonProductionProfileWithNoLocationHasTheDeliveredOneAppended(final String profile) {
            assertThat(FlywayConfig.resolveLocations(List.of(profile), List.of()))
                    .containsExactly(FlywayConfig.SCHEMA_LOCATION);
        }

        @Test
        @DisplayName("a non-production profile already listing the delivered location is returned "
                + "unchanged, so the declared order survives")
        void aNonProductionProfileListingTheDeliveredLocationIsUnchanged() {
            final List<String> declared =
                    List.of(FlywayConfig.SCHEMA_LOCATION, "classpath:db/extra");

            assertThat(FlywayConfig.resolveLocations(List.of(FlywayConfig.LOCAL_PROFILE), declared))
                    .containsExactlyElementsOf(declared);
        }

        @Test
        @DisplayName("no active profile leaves the bound list alone, because the shared baseline "
                + "declares the delivered location by itself")
        void noActiveProfileLeavesTheBoundListAlone() {
            assertThat(FlywayConfig.resolveLocations(
                    List.of(), List.of(FlywayConfig.SCHEMA_LOCATION)))
                    .containsExactly(FlywayConfig.SCHEMA_LOCATION);
            assertThat(FlywayConfig.resolveLocations(null, List.of(FlywayConfig.SCHEMA_LOCATION)))
                    .containsExactly(FlywayConfig.SCHEMA_LOCATION);
        }

        @Test
        @DisplayName("an absent location list resolves to no location rather than to a null")
        void anAbsentLocationListResolvesToNoLocation() {
            assertThat(FlywayConfig.resolveLocations(List.of(FlywayConfig.PRODUCTION_PROFILE), null))
                    .isEmpty();
        }

        @Test
        @DisplayName("a blank or null location entry is ignored rather than refused as a foreign one")
        void aBlankOrNullLocationEntryIsIgnored() {
            final List<String> declared = new ArrayList<>();
            declared.add(FlywayConfig.SCHEMA_LOCATION);
            declared.add("   ");
            declared.add(null);

            assertThatNoException().isThrownBy(() -> FlywayConfig.resolveLocations(
                    List.of(FlywayConfig.PRODUCTION_PROFILE), declared));
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

        @Test
        @DisplayName("production declaring a ceiling below the seeds is accepted, so a later schema "
                + "version is not refused for being new")
        void productionDeclaringALowerCeilingIsAccepted() {
            assertThat(FlywayConfig.resolveTarget(
                    List.of(FlywayConfig.PRODUCTION_PROFILE), "1.1"))
                    .isEqualTo("1.1");
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
        @DisplayName("the published customizer lifts the ceiling for a non-production profile")
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
                    .containsExactly(FlywayConfig.SCHEMA_LOCATION);
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
                    .locations(FlywayConfig.SCHEMA_LOCATION, "classpath:db/seed")
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
        @DisplayName("the foreign-location refusal names the delivered location and the ceiling and "
                + "never the offending descriptor")
        void theForeignLocationRefusalNamesThePathsOnly() {
            final String hostileLocation =
                    "classpath:db/seed/" + HOSTILE_MARKER + CARRIAGE_RETURN + LINE_FEED;

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
        @DisplayName("the one location is the one the delivered scripts occupy, and the two ceilings "
                + "sit either side of the first seed version")
        void theDeliveredLocationAndCeilingsAreTheShippedOnes() {
            assertThat(FlywayConfig.SCHEMA_LOCATION).isEqualTo("classpath:db/migration");
            assertThat(new Location(FlywayConfig.SCHEMA_LOCATION).getPath())
                    .isEqualTo("db/migration");
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
}
