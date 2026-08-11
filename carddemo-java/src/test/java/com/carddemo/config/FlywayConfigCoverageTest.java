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
 * <h2>Why placement AND a version</h2>
 *
 * <p>Two controls hold the production exclusion, and they answer different questions. PLACEMENT decides
 * which scripts are resolved: the two halves of the migration set ship from two sibling locations,
 * production resolves one of them, and a location a profile never lists is not a value an operator can
 * widen - it produces no script to decline. THE CEILING decides how far a resolved list may be applied:
 * {@code spring.flyway.target: 2} is the highest version the schema location delivers, so a
 * seed-numbered script presented by some other location would still not be applied.
 *
 * <p>The known objection to a ceiling is real and is answered rather than avoided. A number written down
 * and never checked freezes the schema: the day a {@code V5} schema script shipped, production would
 * apply nothing above the pin and report success. {@code FlywayConfigTest} therefore asserts the pin
 * against the versions the schema location delivers, so raising the schema without raising the pin fails
 * the build. So the assertions here hold a location list, hold production to the pin, and hold it to
 * REFUSING every alternative - the open marker included. See docs/decision-log.md DL-298 and DL-334.
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

    /** The target the two seeding profiles declare, typed independently of the class under test. */
    private static final String EXPECTED_SEEDING_TARGET = "latest";

    /**
     * The ceiling the shared baseline and the production overlay declare, typed independently of the
     * class under test so a change to the constant cannot silently change what is asserted about it.
     */
    private static final String EXPECTED_TARGET = "2.2";

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

    /**
     * The scripts that build the schema, which ship from the schema location.
     *
     * <p>Three, and the last takes a dotted version between the indexes and the fixtures: the
     * protected-value invariants at 2.2. Every schema version sorts below every seed version, which is an
     * invariant three controls rest on - see {@code docs/decision-log.md} DL-343 and DL-349.
     */
    private static final List<String> SCHEMA_MIGRATIONS = List.of(
            "V1__create_schema.sql",
            "V2__create_indexes.sql",
            "V2_2__add_protected_value_invariants.sql");

    /** The scripts that seed rows, which ship from the seed location. */
    private static final List<String> SEED_MIGRATIONS =
            List.of("V3__seed_reference_data.sql", "V4__seed_user_security.sql");

    /**
     * The ceiling production is pinned at, which a seeding profile must have lifted for itself.
     */
    private static final String SCHEMA_CEILING = "2.2";

    /**
     * A ceiling that stops short of the seed versions, which is the case the lift exists for.
     *
     * <p>It is the value the shared baseline pins, which is the value a seeding profile inherits when it
     * declares no ceiling of its own. That is the case the lift exists for: a fixture-bearing profile
     * left at the production ceiling would migrate the schema and load no fixtures. The same lift also
     * covers a low ceiling an operator supplies - on the command line, in an environment variable, in a
     * merged property source.
     *
     * <p>Held as its own constant rather than written as {@link #SCHEMA_CEILING} so the assertions below
     * say which property of the value they depend on: this one is used because it stops below the seeds,
     * and it would still be the right value to start from if the pin moved above them.
     */
    private static final String CEILING_BELOW_THE_SEEDS = SCHEMA_CEILING;

    /**
     * A numeric ceiling that reaches the seed versions and is refused under production.
     *
     * <p>Now BELOW the pin rather than above it, which is the same refusal reached from the other
     * direction: production refuses every value but the pin, and a ceiling of 4 would stop before the
     * protected-value invariants while reporting a successful migration. It reaches the seeds too, which
     * is what the name says, but that is no longer why it is refused.
     */
    private static final String SEED_REACHING_CEILING = "4";

    @Nested
    @DisplayName("the published boundary")
    final class ThePublishedBoundary {

        @Test
        @DisplayName("names both published targets: the pin production is held to, and the marker a "
                + "seeding profile is lifted to")
        void namesBothPublishedTargets() {
            assertThat(FlywayConfig.PRODUCTION_TARGET)
                    .as("the published pin must be the highest version the schema location delivers, "
                            + "which is what makes it a boundary rather than an arbitrary stop")
                    .isEqualTo(EXPECTED_TARGET);
            assertThat(deliveredMigrations(EXPECTED_SCHEMA_PATH))
                    .as("and it must be measured against the delivered scripts rather than written down: "
                            + "a pin that is never checked freezes the schema, so a script added here "
                            + "above the pin has to fail a build - which is what caught this constant "
                            + "when the protected-value invariants arrived")
                    .isNotEmpty()
                    .allSatisfy(script -> assertThat(versionOf(script)
                            .compareTo(MigrationVersion.fromVersion(FlywayConfig.PRODUCTION_TARGET)))
                            .isLessThanOrEqualTo(0));

            assertThat(FlywayConfig.ALL_RESOLVED_VERSIONS_TARGET)
                    .as("the marker a seeding profile is lifted to must be the apply-everything marker, "
                            + "so a fixture-bearing profile reaches every delivered version")
                    .isEqualTo(EXPECTED_SEEDING_TARGET);
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
                    .as("while the schema location must carry its four scripts DIRECTLY, so that a "
                            + "recursive scan and a listing of the directory agree")
                    .containsExactlyInAnyOrderElementsOf(SCHEMA_MIGRATIONS);
            assertThat(scriptsSittingDirectlyIn(EXPECTED_SEED_PATH))
                    .as("and the seed location likewise")
                    .containsExactlyInAnyOrderElementsOf(SEED_MIGRATIONS);
        }

        @Test
        @DisplayName("the delivered inventory is exactly the five delivered scripts, split "
                + "across the two locations with nothing interleaved and nothing hidden below")
        void theDeliveredInventoryIsExactlyTheSixNamedScripts() {
            assertThat(deliveredMigrations(EXPECTED_SHARED_PARENT_PATH))
                    .as("a recursive scan of the whole migration tree must find exactly the five "
                            + "delivered scripts and no sixth: an extra script is what would make the "
                            + "delivered inventory stop describing what a profile applies")
                    .containsExactlyInAnyOrderElementsOf(Stream.concat(SCHEMA_MIGRATIONS.stream(),
                            SEED_MIGRATIONS.stream()).toList());

            MigrationVersion first = versionOf("V1__create_schema.sql");
            MigrationVersion second = versionOf("V2__create_indexes.sql");
            MigrationVersion firstSeed = versionOf("V3__seed_reference_data.sql");
            MigrationVersion invariants = versionOf("V2_2__add_protected_value_invariants.sql");

            assertThat(first)
                    .as("the delivered set is flatly numbered; a dotted version such as 1.1 would sort "
                            + "between two of them and would be read as version 1 by anything that took "
                            + "one leading integer")
                    .isEqualTo(MigrationVersion.fromVersion("1"))
                    .isLessThan(second);
            assertThat(second).isEqualTo(MigrationVersion.fromVersion("2"));

            assertThat(second)
                    .as("the SCHEMA scripts a seed depends on must be numbered below every seed. Flyway "
                            + "orders by VERSION across every resolved location rather than by location, "
                            + "so a seed numbered V1_2 would be applied BEFORE the indexes it relies on")
                    .isLessThan(firstSeed);
            assertThat(invariants)
                    .as("and the invariants script is numbered below them too, by a DOTTED version "
                            + "between the indexes and the fixtures. An earlier revision numbered a "
                            + "schema script above the "
                            + "seeds on the reasoning that a dotted version beneath already-applied "
                            + "seeds would be out-of-order. That traded one problem for three: the "
                            + "production ceiling stopped excluding the seeds by number, the "
                            + "database-level seed refusal - which fires on a successful history row at "
                            + "or above the first seed version - refused a correctly migrated production "
                            + "database, and a schema-only database later resolving the seed location "
                            + "found the seeds pending BELOW an applied schema version. DL-343")
                    .isLessThan(firstSeed);
        }

        @Test
        @DisplayName("the placement alone excludes the seeds, so a seed renumbered downwards is still "
                + "excluded even though the ceiling would also have declined it")
        void placementRatherThanNumberingIsTheExclusion() {
            for (final String seedMigration : SEED_MIGRATIONS) {
                assertThat(deliveredMigrations(EXPECTED_SCHEMA_PATH))
                        .as("%s must not be resolvable from the location production reads. That is the "
                                + "whole of the exclusion, and it does not depend on the version the "
                                + "seed carries: renumber it to V1_5 and it is still excluded, while "
                                + "the ceiling of %s declines both seed versions as they stand - so the "
                                + "two controls act independently and the placement is the one that acts "
                                + "whatever the number says",
                                seedMigration, SCHEMA_CEILING)
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

        @ParameterizedTest(name = "a declared ceiling of {0} refuses the production start-up")
        @ValueSource(strings = {SEED_REACHING_CEILING, "1", "99", EXPECTED_SEEDING_TARGET})
        @DisplayName("refuses every declared ceiling but the pin under production rather than replacing "
                + "it, in both directions and including the head marker")
        void refusesEveryDeclaredCeilingButThePinUnderProduction(final String ceiling) {
            // Declared as a PROPERTY rather than only bound on a configuration object. The migration
            // tool's own default ceiling is its head marker, so a value read off a bound configuration
            // cannot distinguish a document that declared the marker from one that declared nothing - and
            // those two are treated differently: the first is refused and the second is corrected. The
            // refusal therefore has to be driven by what the environment actually states, which also
            // means it lands during the refresh rather than on a later customize() call.
            runner(EXPECTED_PROFILE).withPropertyValues("spring.flyway.target=" + ceiling)
                    .run(context -> assertThat(context)
                            .as("%s is refused. Replacing it silently would leave the misconfiguration "
                                    + "in the source to survive into the next deployment; refusing makes "
                                    + "the operator remove it. Below the pin of %s the migration stops "
                                    + "before the indexes and constraints are created and reports success "
                                    + "anyway; above it - the head marker included - it applies whatever "
                                    + "a resolved location carries past the delivered schema",
                                    ceiling, SCHEMA_CEILING)
                            .hasFailed()
                            .getFailure()
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining(EXPECTED_PROFILE)
                            .hasMessageContaining("ceiling"));
        }

        @Test
        @DisplayName("accepts an absent ceiling under production and re-applies the pin, so a document "
                + "that forgot the property still stops at the delivered schema")
        void acceptsAnAbsentCeilingUnderProductionAndReAppliesThePin() {
            runner(EXPECTED_PROFILE).run(context -> {
                FluentConfiguration configuration =
                        Flyway.configure().locations(EXPECTED_SCHEMA_LOCATION);

                context.getBean(FlywayConfigurationCustomizer.class).customize(configuration);

                assertThat(configuration.getTarget())
                        .as("silence is the dangerous case, because a migration tool with no ceiling "
                                + "migrates to the latest version. It is corrected rather than refused: "
                                + "refusing would stop a deployment that had inherited the right posture "
                                + "from the shared baseline, and correcting stops at the pin")
                        .isEqualTo(MigrationVersion.fromVersion(EXPECTED_TARGET));
                assertThat(Stream.of(configuration.getLocations()).map(Object::toString).toList())
                        .as("and the location list is still not rewritten - it is checked")
                        .containsExactly(EXPECTED_SCHEMA_LOCATION);
            });
        }

        @Test
        @DisplayName("accepts the declared pin and changes nothing else, so it cannot quietly alter "
                + "another migration setting on the way past")
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

                assertThat(configuration.getTarget())
                        .isEqualTo(MigrationVersion.fromVersion(EXPECTED_TARGET));
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

                assertThat(configuration.getTarget())
                        .isEqualTo(MigrationVersion.fromVersion(EXPECTED_TARGET));
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
                // Started from a ceiling that stops BELOW the seeds, because that is the case the lift
                // exists for - and it is the case a seeding profile reaches by INHERITANCE, since the
                // shared baseline pins exactly this value. Every schema version sorts below every seed
                // version, so the inherited pin always stops short of the fixtures.
                FluentConfiguration configuration = Flyway.configure()
                        .locations(EXPECTED_SCHEMA_LOCATION)
                        .target(MigrationVersion.fromVersion(CEILING_BELOW_THE_SEEDS));

                context.getBean(FlywayConfigurationCustomizer.class).customize(configuration);

                assertThat(Stream.of(configuration.getLocations()).map(Object::toString).toList())
                        .as("%s starts from the production posture here - the schema location alone, as "
                                + "the shared baseline declares it - and the seed location must be "
                                + "APPENDED rather than substituted, or the profile would seed into a "
                                + "schema it no longer creates", profile)
                        .containsExactly(EXPECTED_SCHEMA_LOCATION, EXPECTED_SEED_LOCATION);
                assertThat(configuration.getTarget())
                        .as("and a ceiling of %s under %s would migrate the schema and none of the "
                                + "fixtures the profile exists to load, so it is lifted to the head",
                                CEILING_BELOW_THE_SEEDS, profile)
                        .isEqualTo(MigrationVersion.LATEST);
                for (final String seedMigration : SEED_MIGRATIONS) {
                    assertThat(versionOf(seedMigration))
                            .as("%s must be reachable once the scope is completed", seedMigration)
                            .isLessThanOrEqualTo(configuration.getTarget());
                }
            });
        }

        @ParameterizedTest(name = "the {0} profile keeps a ceiling that already reaches every seed")
        @ValueSource(strings = {"local", "test"})
        @DisplayName("leaves a seeding profile's ceiling alone when it already reaches the seeds, so the "
                + "lift is conditional rather than unconditional")
        void leavesASeedingProfilesReachingCeilingAlone(final String profile) {
            // A ceiling that already reaches both seeds. No shipped profile declares one - the baseline
            // pin stops below them - so this value is supplied here to exercise the branch an operator
            // reaches by raising the ceiling themselves. The lift must be a no-op then: raising a
            // ceiling that is already high enough would be a change with no purpose, and it would mask
            // whether the customizer distinguishes the two cases at all.
            final MigrationVersion alreadyReachingTheSeeds = SEED_MIGRATIONS.stream()
                    .map(FlywayConfigCoverageTest::versionOf)
                    .max(MigrationVersion::compareTo)
                    .orElseThrow();

            runner(profile).run(context -> {
                FluentConfiguration configuration = Flyway.configure()
                        .locations(EXPECTED_SCHEMA_LOCATION)
                        .target(alreadyReachingTheSeeds);

                context.getBean(FlywayConfigurationCustomizer.class).customize(configuration);

                assertThat(configuration.getTarget())
                        .as("a ceiling of %s under %s already reaches every seed version, so it is "
                                + "returned unchanged rather than lifted to the head",
                                alreadyReachingTheSeeds, profile)
                        .isEqualTo(alreadyReachingTheSeeds);
                for (final String seedMigration : SEED_MIGRATIONS) {
                    assertThat(versionOf(seedMigration))
                            .as("%s must already be reachable at that ceiling, which is the reason it is "
                                    + "left alone", seedMigration)
                            .isLessThanOrEqualTo(configuration.getTarget());
                }
                assertThat(Stream.of(configuration.getLocations()).map(Object::toString).toList())
                        .as("and the location completion still happens: the ceiling and the location "
                                + "list are independent halves of the same resolution")
                        .containsExactly(EXPECTED_SCHEMA_LOCATION, EXPECTED_SEED_LOCATION);
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
                        .target(MigrationVersion.fromVersion(EXPECTED_SEEDING_TARGET));

                assertThatExceptionOfType(IllegalStateException.class)
                        .as("the parent resolves the same five scripts under %s, so it looks like a "
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
     * of the parent finds all five scripts because both children are beneath it; a direct listing of the
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
