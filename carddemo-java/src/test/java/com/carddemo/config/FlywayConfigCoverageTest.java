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

import com.carddemo.service.SensitiveFieldEncryptionService;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.stream.Stream;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.flyway.FlywayConfigurationCustomizer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * Checks the control that keeps the seed migrations out of a production deployment - the profile-scoped
 * LOCATION LIST - against the migration scripts actually delivered, and checks that no version ceiling
 * has crept back in beside it.
 *
 * <h2>What this file adds that the guard's own tests do not</h2>
 *
 * <p>{@link FlywayConfigTest} exercises the resolution rules exhaustively: which location list and which
 * target each profile combination produces, and which combinations are refused. What it does not do is
 * ask whether the declared values are the <em>right ones</em> for the scripts on disk. The control can be
 * internally consistent and still wrong: put a seed in the schema directory and the location list
 * silently exposes it, put a schema script in the seed directory and production silently stops applying
 * it, or leave a script in the shared parent and the separation was never in force at all.
 *
 * <p>This file therefore reads the delivered {@code .sql} files off each location separately and checks
 * both facts about every one of them - which directory it ships from, and which version it carries. Each
 * location's set is asserted first, so a script added, removed or moved has to be accounted for here
 * before any claim about the control is made.
 *
 * <h2>Why placement rather than a version</h2>
 *
 * <p>The production exclusion used to be {@code spring.flyway.target: 2}, a numeric ceiling. It did hold
 * the seeds out, and it also froze the schema: the day a {@code V5} schema script shipped, production
 * would have applied nothing above the pin and reported success, and the resolution refused every value
 * but {@code 2} so the pin could not be raised without editing code. A ceiling is the wrong instrument
 * for a boundary that must never move AND the wrong instrument for a sequence that must keep growing,
 * and it was serving as both. The two halves of the migration set now ship from two sibling locations,
 * production resolves one of them, and a location a profile never lists is not a value an operator can
 * widen. So the assertions here hold a location list, hold every profile to the open target, and hold
 * production to REFUSING a number - the exact reverse of what this file used to require. See
 * docs/decision-log.md DL-298.
 *
 * <h2>The parent is the way this can be silently defeated</h2>
 *
 * <p>A Flyway location is scanned RECURSIVELY, so {@code classpath:db/migration} reaches both children.
 * Two facts are therefore asserted rather than assumed: that no script sits in the parent itself - the
 * defect that made two earlier attempts at this split ineffective - and that the parent is refused as a
 * location whatever profile is active, including because it would record every script under a name
 * relative to itself and break the migration names the bring-up check reads out of the history table.
 *
 * <h2>Flyway's ordering, not a regular expression</h2>
 *
 * <p>Versions are parsed with {@link MigrationVersion#fromVersion(String)} and compared with its own
 * {@code compareTo}, because it is the authority on what a migration file name means - including the
 * dotted form {@code V1_1__}, which no delivered script uses today and which this file asserts stays
 * absent. A pattern that read one leading integer could not make that distinction, and the comparison it
 * fed would be meaningless.
 *
 * <h2>The control is a refusal, not a silent re-pin</h2>
 *
 * <p>Under production the customizer does not rewrite the location list and does not tolerate a numeric
 * target. It <em>refuses</em> the combination, so a deployment that carried either fails to start rather
 * than starting with a scope the operator did not ask for. That is the stronger of the two available
 * designs: overwriting hides the misconfiguration, and a hidden misconfiguration is one that survives
 * into the next deployment. Under a non-production profile the same customizer COMPLETES both instead,
 * because a seeding profile that migrated the schema alone would load none of the fixtures it exists to
 * load.
 *
 * <h2>Independent expectations</h2>
 *
 * <p>The expected locations, the expected target and the expected profile are all typed here rather than
 * read from the class under test.
 *
 * <h2>Provenance</h2>
 *
 * <p>The migration set under test derives from the CardDemo COBOL estate at checkout
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. No legacy source text is reproduced here.
 *
 * @since 1.0.0
 */
@DisplayName("Production migration scope, checked against the delivered scripts")
final class FlywayConfigCoverageTest {

    /** The target every profile declares, typed independently of the class under test. */
    private static final String EXPECTED_TARGET = "latest";

    /** The profile the refusal is scoped to. */
    private static final String EXPECTED_PROFILE = "prod";

    /** The one location production resolves, typed independently of the class under test. */
    private static final String EXPECTED_SCHEMA_LOCATION = "classpath:db/migration/schema";

    /** The sibling location only the seeding profiles resolve. */
    private static final String EXPECTED_SEED_LOCATION = "classpath:db/migration/seed";

    /** The shared parent of the two, which no profile may resolve. */
    private static final String EXPECTED_SHARED_PARENT_LOCATION = "classpath:db/migration";

    /** Class-path path behind {@link #EXPECTED_SCHEMA_LOCATION}, as a pattern reads it. */
    private static final String EXPECTED_SCHEMA_PATH = "db/migration/schema";

    /** Class-path path behind {@link #EXPECTED_SEED_LOCATION}, as a pattern reads it. */
    private static final String EXPECTED_SEED_PATH = "db/migration/seed";

    /** Class-path path behind {@link #EXPECTED_SHARED_PARENT_LOCATION}, which carries no script. */
    private static final String EXPECTED_SHARED_PARENT_PATH = "db/migration";

    /**
     * A Base64 key decoding to exactly thirty-two bytes, matching the shape the encryption service
     * requires. It is a test constant and protects nothing.
     */
    private static final String TEST_KEY = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";

    /** The scripts that build the schema, which ship from the schema location. */
    private static final List<String> SCHEMA_MIGRATIONS = List.of(
            "V1__create_schema.sql",
            "V2__create_indexes.sql");

    /** The scripts that seed rows, which ship from the seed location. */
    private static final List<String> SEED_MIGRATIONS =
            List.of("V3__seed_reference_data.sql", "V4__seed_user_security.sql");

    /**
     * The ceiling that used to be the production control, retained here as the value production must
     * now REFUSE and a seeding profile must have lifted.
     */
    private static final String WITHDRAWN_SCHEMA_CEILING = "2";

    /** A numeric ceiling that reaches the seed versions, refused under production for the same reason. */
    private static final String SEED_REACHING_CEILING = "4";

    @Nested
    @DisplayName("the published boundary")
    final class ThePublishedBoundary {

        @Test
        @DisplayName("names the open target rather than a version, so no future schema script is frozen "
                + "out by the constant that used to exclude the seeds")
        void namesTheOpenTargetRatherThanAVersion() {
            assertThat(FlywayConfig.ALL_RESOLVED_VERSIONS_TARGET)
                    .as("the published target must be the apply-everything marker. A numeric constant "
                            + "here would be the defect this arrangement removed: it excluded the seeds "
                            + "by arithmetic and froze the schema at the same version, so a V5 schema "
                            + "script would never be applied and the migration would still report "
                            + "success")
                    .isEqualTo(EXPECTED_TARGET);

            assertThat(MigrationVersion.fromVersion(FlywayConfig.ALL_RESOLVED_VERSIONS_TARGET))
                    .as("and the migration tool must read it as its own latest marker, which is above "
                            + "every concrete version by construction rather than by comparison")
                    .isEqualTo(MigrationVersion.LATEST);
        }

        @Test
        @DisplayName("is scoped to the production profile by name")
        void isScopedToTheProductionProfile() {
            assertThat(FlywayConfig.PRODUCTION_PROFILE).isEqualTo(EXPECTED_PROFILE);
        }

        @Test
        @DisplayName("names the two delivered locations and the shared parent it refuses, so the "
                + "boundary is a directory the operator cannot widen")
        void namesTheTwoDeliveredLocationsAndTheParentItRefuses() {
            assertThat(FlywayConfig.SCHEMA_LOCATION)
                    .as("the location production resolves must be the packaged schema directory. This "
                            + "single value IS the exclusion: the seeds are not in it, so they are not "
                            + "applied, not pending and not resolved under that profile")
                    .isEqualTo(EXPECTED_SCHEMA_LOCATION)
                    .isEqualTo("classpath:" + EXPECTED_SCHEMA_PATH);

            assertThat(FlywayConfig.SEED_LOCATION)
                    .as("the sibling seed location must be a SIBLING and not a descendant, or a profile "
                            + "resolving the schema location would reach the seeds through it")
                    .isEqualTo(EXPECTED_SEED_LOCATION)
                    .isEqualTo("classpath:" + EXPECTED_SEED_PATH);
            assertThat(EXPECTED_SEED_LOCATION)
                    .doesNotStartWith(EXPECTED_SCHEMA_LOCATION + "/");
            assertThat(EXPECTED_SCHEMA_LOCATION)
                    .doesNotStartWith(EXPECTED_SEED_LOCATION + "/");

            assertThat(FlywayConfig.SHARED_PARENT_LOCATION)
                    .as("and the parent the class refuses must be the actual parent of the two, or the "
                            + "refusal would be aimed at a descriptor nobody would write")
                    .isEqualTo(EXPECTED_SHARED_PARENT_LOCATION);
            assertThat(EXPECTED_SCHEMA_LOCATION)
                    .startsWith(EXPECTED_SHARED_PARENT_LOCATION + "/");
            assertThat(EXPECTED_SEED_LOCATION)
                    .startsWith(EXPECTED_SHARED_PARENT_LOCATION + "/");
        }

        @Test
        @DisplayName("each location carries exactly its own half of the delivered set, asserted against "
                + "the scripts on the class path")
        void eachLocationCarriesExactlyItsOwnHalf() {
            assertThat(deliveredMigrations(EXPECTED_SCHEMA_PATH))
                    .as("every claim below is about the delivered set, so a migration added, removed or "
                            + "moved must be accounted for here first. A SEED placed in the schema "
                            + "location would reach production without any document changing")
                    .containsExactlyInAnyOrderElementsOf(SCHEMA_MIGRATIONS);

            assertThat(deliveredMigrations(EXPECTED_SEED_PATH))
                    .as("and a SCHEMA script placed in the seed location would silently stop reaching "
                            + "production: the deployment would come up on an incomplete schema and the "
                            + "migration would report success")
                    .containsExactlyInAnyOrderElementsOf(SEED_MIGRATIONS);
        }

        @Test
        @DisplayName("the shared parent carries no script of its own, which is what both earlier "
                + "attempts at this split got wrong")
        void theSharedParentCarriesNoScriptOfItsOwn() {
            assertThat(scriptsSittingDirectlyIn(EXPECTED_SHARED_PARENT_PATH))
                    .as("%s must carry NO script directly. This is the specific defect that made this "
                            + "split ineffective twice before: the seeds were moved down a level and V1 "
                            + "and V2 were left in the parent, so the parent stayed a real migration "
                            + "source and the separation was never actually in force. A script left "
                            + "here is applied by any profile naming the parent and by no profile "
                            + "naming a child", EXPECTED_SHARED_PARENT_PATH)
                    .isEmpty();

            assertThat(scriptsSittingDirectlyIn(EXPECTED_SCHEMA_PATH))
                    .as("while the schema location must carry its two scripts DIRECTLY, so that a "
                            + "recursive scan and a listing of the directory agree")
                    .containsExactlyInAnyOrderElementsOf(SCHEMA_MIGRATIONS);
            assertThat(scriptsSittingDirectlyIn(EXPECTED_SEED_PATH))
                    .as("and the seed location likewise")
                    .containsExactlyInAnyOrderElementsOf(SEED_MIGRATIONS);
        }

        @Test
        @DisplayName("the delivered inventory is exactly the four scripts the frozen plan names, split "
                + "across the two locations with nothing interleaved and nothing hidden below")
        void theDeliveredInventoryIsExactlyTheFourNamedScripts() {
            assertThat(deliveredMigrations(EXPECTED_SHARED_PARENT_PATH))
                    .as("a recursive scan of the whole migration tree must find exactly the four "
                            + "delivered scripts and no fifth: an extra script is what would make the "
                            + "delivered inventory stop describing what a profile applies")
                    .containsExactlyInAnyOrderElementsOf(Stream.concat(SCHEMA_MIGRATIONS.stream(),
                            SEED_MIGRATIONS.stream()).toList());

            MigrationVersion first = versionOf("V1__create_schema.sql");
            MigrationVersion second = versionOf("V2__create_indexes.sql");
            MigrationVersion firstSeed = versionOf("V3__seed_reference_data.sql");

            assertThat(first)
                    .as("the schema half is flatly numbered 1 then 2; a dotted version such as 1.1 "
                            + "would sort between them and would be read as version 1 by anything that "
                            + "took one leading integer")
                    .isEqualTo(MigrationVersion.fromVersion("1"))
                    .isLessThan(second);
            assertThat(second).isEqualTo(MigrationVersion.fromVersion("2"));

            assertThat(second)
                    .as("and the numbering must agree with the placement. Flyway orders by VERSION "
                            + "across every resolved location rather than by location, so the two "
                            + "sibling directories only compose into a correct apply order while every "
                            + "schema script is numbered below every seed - a seed numbered V1_2 would "
                            + "be applied BEFORE the indexes it relies on")
                    .isLessThan(firstSeed);
        }

        @Test
        @DisplayName("the placement rather than the numbering is what excludes the seeds, so a seed "
                + "renumbered downwards is still excluded")
        void placementRatherThanNumberingIsTheExclusion() {
            for (final String seedMigration : SEED_MIGRATIONS) {
                assertThat(deliveredMigrations(EXPECTED_SCHEMA_PATH))
                        .as("%s must not be resolvable from the location production reads. That is the "
                                + "whole of the exclusion, and it does not depend on the version the "
                                + "seed carries: renumber it to V1_5 and it is still excluded, whereas "
                                + "under the withdrawn ceiling of %s it would have been applied",
                                seedMigration, WITHDRAWN_SCHEMA_CEILING)
                        .doesNotContain(seedMigration);
            }
            for (final String schemaMigration : SCHEMA_MIGRATIONS) {
                assertThat(deliveredMigrations(EXPECTED_SEED_PATH))
                        .as("and %s must be resolvable from that location and from nowhere else, so "
                                + "production applies it and a seeding profile does not apply it twice",
                                schemaMigration)
                        .doesNotContain(schemaMigration);
            }
        }
    }

    @Nested
    @DisplayName("the customizer that resolves the migration scope")
    final class TheCustomizerThatResolvesTheMigrationScope {

        @Test
        @DisplayName("exists under the production profile")
        void existsUnderTheProductionProfile() {
            runner(EXPECTED_PROFILE).run(context -> assertThat(context)
                    .hasNotFailed()
                    .hasSingleBean(FlywayConfigurationCustomizer.class));
        }

        @ParameterizedTest(name = "the customizer also exists under the {0} profile")
        @ValueSource(strings = {"local", "test"})
        @DisplayName("exists under every profile rather than only under production, because the "
                + "non-production half of the resolution is a completion the seeding profiles need")
        void existsUnderTheSeedingProfilesToo(final String profile) {
            runner(profile).run(context -> assertThat(context)
                    .as("under %s the seeds must be applied, and it is this same bean that adds the "
                            + "seed location to the inherited list; scoping the bean to production "
                            + "would leave a seeding profile migrating the schema alone", profile)
                    .hasNotFailed()
                    .hasSingleBean(FlywayConfigurationCustomizer.class));
        }

        @Test
        @DisplayName("exists with no profile active as well, and then leaves the bound scope alone, so "
                + "a profile-less start migrates whatever the shared baseline declares")
        void existsWithNoProfileActiveAndChangesNothing() {
            runner().run(context -> {
                assertThat(context).hasNotFailed().hasSingleBean(FlywayConfigurationCustomizer.class);

                FluentConfiguration configuration = Flyway.configure()
                        .locations(EXPECTED_SCHEMA_LOCATION)
                        .target(MigrationVersion.fromVersion(SEED_REACHING_CEILING));

                context.getBean(FlywayConfigurationCustomizer.class).customize(configuration);

                assertThat(configuration.getTarget())
                        .as("no profile selects a resolution, so nothing is imposed")
                        .isEqualTo(MigrationVersion.fromVersion(SEED_REACHING_CEILING));
                assertThat(Stream.of(configuration.getLocations()).map(Object::toString).toList())
                        .as("and no location is added either: completing the list belongs to the two "
                                + "seeding profiles, and a profile-less start is not one of them")
                        .containsExactly(EXPECTED_SCHEMA_LOCATION);
            });
        }

        @ParameterizedTest(name = "a bound ceiling of {0} is refused under production")
        @ValueSource(strings = {WITHDRAWN_SCHEMA_CEILING, SEED_REACHING_CEILING, "1", "99"})
        @DisplayName("refuses ANY numeric ceiling under production rather than replacing it, because a "
                + "number freezes the schema at a version and the seeds are already held out by the "
                + "location list")
        void refusesAnyNumericCeilingUnderProduction(final String ceiling) {
            runner(EXPECTED_PROFILE).run(context -> {
                FluentConfiguration configuration = Flyway.configure()
                        .locations(EXPECTED_SCHEMA_LOCATION)
                        .target(MigrationVersion.fromVersion(ceiling));

                assertThat(configuration.getTarget())
                        .as("the starting state is deliberately a numeric ceiling, so a customizer that "
                                + "did nothing would be visible here")
                        .isEqualTo(MigrationVersion.fromVersion(ceiling));

                assertThatExceptionOfType(IllegalStateException.class)
                        .as("%s is refused whatever its value. Replacing it silently would leave the "
                                + "misconfiguration in the source to survive into the next deployment; "
                                + "refusing makes the operator remove it. %s in particular is the value "
                                + "that USED to be the production control, and it is refused now for the "
                                + "defect it always carried: it stops the sequence for ever, so a schema "
                                + "script added later is never applied and the migration still reports "
                                + "success", ceiling, WITHDRAWN_SCHEMA_CEILING)
                        .isThrownBy(() -> context.getBean(FlywayConfigurationCustomizer.class)
                                .customize(configuration))
                        .withMessageContaining(EXPECTED_PROFILE)
                        .withMessageContaining("ceiling");
            });
        }

        @Test
        @DisplayName("accepts an absent ceiling under production and re-applies the open target, which "
                + "is the reverse of what the withdrawn arrangement required")
        void acceptsAnAbsentCeilingUnderProductionAndReAppliesTheOpenTarget() {
            runner(EXPECTED_PROFILE).run(context -> {
                FluentConfiguration configuration =
                        Flyway.configure().locations(EXPECTED_SCHEMA_LOCATION);

                context.getBean(FlywayConfigurationCustomizer.class).customize(configuration);

                assertThat(configuration.getTarget())
                        .as("silence used to be the dangerous case, because a migration tool with no "
                                + "ceiling migrates to the latest version and the ceiling was the "
                                + "exclusion. It is now the CORRECT case: the exclusion is the location "
                                + "list, and applying every version the resolved location carries is "
                                + "exactly what a production deployment must do")
                        .isEqualTo(MigrationVersion.LATEST);
                assertThat(Stream.of(configuration.getLocations()).map(Object::toString).toList())
                        .as("and the location list is still not rewritten - it is checked")
                        .containsExactly(EXPECTED_SCHEMA_LOCATION);
            });
        }

        @Test
        @DisplayName("accepts the declared open target and changes nothing else, so it cannot quietly "
                + "alter another migration setting on the way past")
        void acceptsTheDeclaredTargetAndChangesNothingElse() {
            runner(EXPECTED_PROFILE).run(context -> {
                FluentConfiguration configuration = Flyway.configure()
                        .locations(EXPECTED_SCHEMA_LOCATION)
                        .target(MigrationVersion.fromVersion(EXPECTED_TARGET));
                boolean cleanDisabledBefore = configuration.isCleanDisabled();
                boolean validateBefore = configuration.isValidateOnMigrate();
                boolean baselineBefore = configuration.isBaselineOnMigrate();
                List<String> locationsBefore =
                        Stream.of(configuration.getLocations()).map(Object::toString).toList();

                context.getBean(FlywayConfigurationCustomizer.class).customize(configuration);

                assertThat(configuration.getTarget()).isEqualTo(MigrationVersion.LATEST);
                assertThat(configuration.isCleanDisabled()).isEqualTo(cleanDisabledBefore);
                assertThat(configuration.isValidateOnMigrate()).isEqualTo(validateBefore);
                assertThat(configuration.isBaselineOnMigrate()).isEqualTo(baselineBefore);
                assertThat(Stream.of(configuration.getLocations()).map(Object::toString).toList())
                        .as("under production the location list is checked and never rewritten: a "
                                + "customizer that added a location here would widen the very control "
                                + "it exists to enforce, and would do so without saying so")
                        .isEqualTo(locationsBefore);
            });
        }

        @Test
        @DisplayName("is idempotent on an acceptable configuration, so a second application leaves the "
                + "same scope")
        void isIdempotentOnAnAcceptableConfiguration() {
            runner(EXPECTED_PROFILE).run(context -> {
                FlywayConfigurationCustomizer customizer =
                        context.getBean(FlywayConfigurationCustomizer.class);
                FluentConfiguration configuration = Flyway.configure()
                        .locations(EXPECTED_SCHEMA_LOCATION)
                        .target(MigrationVersion.fromVersion(EXPECTED_TARGET));

                customizer.customize(configuration);
                customizer.customize(configuration);

                assertThat(configuration.getTarget()).isEqualTo(MigrationVersion.LATEST);
                assertThat(Stream.of(configuration.getLocations()).map(Object::toString).toList())
                        .containsExactly(EXPECTED_SCHEMA_LOCATION);
            });
        }

        @ParameterizedTest(name = "the {0} profile has the seed location added and its ceiling lifted")
        @ValueSource(strings = {"local", "test"})
        @DisplayName("completes a seeding profile's inherited scope - the seed location AND the open "
                + "target - which is the other half of the same resolution")
        void completesASeedingProfilesInheritedScope(final String profile) {
            runner(profile).run(context -> {
                FluentConfiguration configuration = Flyway.configure()
                        .locations(EXPECTED_SCHEMA_LOCATION)
                        .target(MigrationVersion.fromVersion(WITHDRAWN_SCHEMA_CEILING));

                context.getBean(FlywayConfigurationCustomizer.class).customize(configuration);

                assertThat(Stream.of(configuration.getLocations()).map(Object::toString).toList())
                        .as("%s starts from the production posture here - the schema location alone, as "
                                + "the shared baseline declares it - and the seed location must be "
                                + "APPENDED rather than substituted, or the profile would seed into a "
                                + "schema it no longer creates", profile)
                        .containsExactly(EXPECTED_SCHEMA_LOCATION, EXPECTED_SEED_LOCATION);
                assertThat(configuration.getTarget())
                        .as("and an inherited ceiling reaching %s would migrate the schema and none of "
                                + "the fixtures the profile exists to load", profile)
                        .isEqualTo(MigrationVersion.LATEST);
                for (final String seedMigration : SEED_MIGRATIONS) {
                    assertThat(versionOf(seedMigration))
                            .as("%s must be reachable once the scope is completed", seedMigration)
                            .isLessThanOrEqualTo(configuration.getTarget());
                }
            });
        }

        @ParameterizedTest(name = "the shared parent is refused under the {0} profile")
        @ValueSource(strings = {"local", "test"})
        @DisplayName("refuses the shared parent even where the seeds are wanted, because it applies the "
                + "same scripts under names the bring-up check does not read")
        void refusesTheSharedParentEvenWhereTheSeedsAreWanted(final String profile) {
            runner(profile).run(context -> {
                FluentConfiguration configuration = Flyway.configure()
                        .locations(EXPECTED_SHARED_PARENT_LOCATION)
                        .target(MigrationVersion.fromVersion(EXPECTED_TARGET));

                assertThatExceptionOfType(IllegalStateException.class)
                        .as("the parent resolves the same four scripts under %s, so it looks like a "
                                + "harmless simplification. It is not: Flyway records a script under a "
                                + "name relative to its location, so the history would read "
                                + "schema/V1__create_schema.sql where the bring-up check reads "
                                + "V1__create_schema.sql", profile)
                        .isThrownBy(() -> context.getBean(FlywayConfigurationCustomizer.class)
                                .customize(configuration))
                        .withMessageContaining(EXPECTED_SHARED_PARENT_LOCATION)
                        .withMessageContaining(EXPECTED_SCHEMA_LOCATION);
            });
        }
    }

    // Helpers.

    /**
     * Builds a runner over the configuration class alone, with the given profiles active.
     *
     * <p>No configuration document is loaded: the question here is which beans a profile publishes and
     * what the published customizer does to a configuration handed to it, and a resolved property would
     * not change either answer. The field-encryption service is supplied because one of the profile-
     * scoped beans consumes it, and a missing collaborator would fail the context for a reason that has
     * nothing to do with the migration scope.</p>
     *
     * @param profiles the profiles to activate, possibly none
     * @return a runner that has not been started
     */
    private static ApplicationContextRunner runner(final String... profiles) {
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withUserConfiguration(FlywayConfig.class)
                .withBean(SensitiveFieldEncryptionService.class,
                        () -> new SensitiveFieldEncryptionService(TEST_KEY))
                // The three settings a production start-up must have stated. A context runner loads no
                // profile document, so without them a production context is refused by the
                // migration-source guard before any bean is created - which is that guard's whole
                // point, and is asserted in its own nest rather than here. The location is the SCHEMA
                // location and not its parent: the parent is refused under every profile.
                .withPropertyValues(
                        "spring.flyway.enabled=true",
                        "spring.flyway.target=" + EXPECTED_TARGET,
                        "spring.flyway.locations=" + EXPECTED_SCHEMA_LOCATION);
        return profiles.length == 0 ? runner : runner.withPropertyValues(
                "spring.profiles.active=" + String.join(",", profiles));
    }

    /**
     * Returns the versioned migrations delivered under one class-path path, searched recursively.
     *
     * <p>The search is recursive because that is how Flyway resolves a location, so a script placed in a
     * sub-directory of either location is found here exactly as the migration tool would find it. Read
     * from the class path rather than from the source tree, so the answer is what a running application
     * would resolve rather than what a directory listing of the repository suggests.</p>
     *
     * @param path the class-path path to search, such as {@code db/migration/schema}
     * @return the delivered file names, sorted; never {@code null}
     */
    private static List<String> deliveredMigrations(final String path) {
        return Stream.of(migrationResources(path))
                .map(Resource::getFilename)
                .filter(name -> name != null)
                .sorted()
                .toList();
    }

    /**
     * Returns the versioned migrations that sit DIRECTLY in one class-path path rather than in a
     * directory beneath it.
     *
     * <p>This is what tells the two delivered locations apart from their shared parent. A recursive scan
     * of the parent finds all four scripts because both children are beneath it; a direct listing of the
     * parent must find none. The distinction is the load-bearing one, because a script left in the
     * parent is applied by any profile that names the parent and by no profile that names a child -
     * which is precisely the state two earlier attempts at this split were left in.</p>
     *
     * @param path the class-path path to list, such as {@code db/migration}
     * @return the file names sitting directly in that path, sorted; never {@code null}
     */
    private static List<String> scriptsSittingDirectlyIn(final String path) {
        final String directoryPrefix = path + "/";
        return Stream.of(migrationResources(path))
                .filter(resource -> {
                    final String location = describeResource(resource);
                    final int start = location.indexOf(directoryPrefix);
                    // A script sitting directly in the directory carries no further separator after the
                    // directory prefix; one inside a sub-directory does.
                    return start >= 0
                            && !location.substring(start + directoryPrefix.length()).contains("/");
                })
                .map(Resource::getFilename)
                .filter(name -> name != null)
                .sorted()
                .toList();
    }

    /**
     * Resolves the versioned migration resources under one class-path path.
     *
     * @param path the class-path path to search
     * @return the resources found; never {@code null}
     */
    private static Resource[] migrationResources(final String path) {
        try {
            return new PathMatchingResourcePatternResolver()
                    .getResources("classpath*:" + path + "/**/V*__*.sql");
        } catch (IOException failure) {
            throw new UncheckedIOException("migration location is unreadable", failure);
        }
    }

    /**
     * Describes a resource by its class-path-style location, using a forward-slash spelling so the
     * comparison is the same on every host.
     *
     * @param resource the resource to describe
     * @return the description, never {@code null}
     */
    private static String describeResource(final Resource resource) {
        return resource.getDescription().replace('\\', '/');
    }

    /**
     * Reads the version a migration file name carries, using the migration tool's own parser.
     *
     * <p>The tool's parser is used rather than a pattern because it is the authority on what a name
     * means: it reads {@code V1_1__} as version {@code 1.1} and orders it accordingly, which a reader
     * that took one leading integer could not do.</p>
     *
     * @param fileName the migration file name
     * @return the version the name declares
     */
    private static MigrationVersion versionOf(final String fileName) {
        int prefix = fileName.startsWith("V") ? 1 : 0;
        int separator = fileName.indexOf("__");
        assertThat(separator)
                .as("%s must be a versioned migration; an unversioned script is ordered by nothing and "
                        + "placed by nothing", fileName)
                .isGreaterThan(prefix);
        return MigrationVersion.fromVersion(
                fileName.substring(prefix, separator).replace('_', '.'));
    }
}
