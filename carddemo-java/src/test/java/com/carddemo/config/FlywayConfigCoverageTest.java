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
 * Checks the version ceiling that keeps the seed migrations out of a production deployment against the
 * migration scripts actually delivered.
 *
 * <h2>What this file adds that the guard's own tests do not</h2>
 *
 * <p>{@link FlywayConfigTest} exercises the resolution rules exhaustively: which location list and which
 * ceiling each profile combination produces, and which combinations are refused. What it does not do is
 * ask whether the declared ceiling is the <em>right number</em> for the scripts on disk. Every script
 * ships from one location, so the ceiling is the only thing separating the schema from the seeds, and a
 * ceiling that is internally consistent can still be wrong: renumber a seed and the ceiling silently
 * starts applying it.
 *
 * <p>This file therefore reads the delivered {@code .sql} files off the classpath and compares each
 * one's version against the published ceiling using Flyway's own version ordering. The set is asserted
 * first, so a script added or removed has to be accounted for here before any claim about the ceiling is
 * made, and the schema and seed halves are then named separately: every schema script at or below the
 * ceiling, every seed script strictly above it.
 *
 * <h2>Flyway's ordering, not a regular expression</h2>
 *
 * <p>Versions are parsed with {@link MigrationVersion#fromVersion(String)} and compared with its own
 * {@code compareTo}, because the delivered set is not flatly numbered: {@code V1_1} is version
 * {@code 1.1}, which sorts after {@code 1} and before {@code 2}. A pattern that read one leading integer
 * would either fail to match that file or mis-order it, and either way the comparison it fed would be
 * meaningless.
 *
 * <h2>The control is a refusal, not a silent re-pin</h2>
 *
 * <p>The customizer this file exercises does not overwrite a bound ceiling under production. It
 * <em>refuses</em> the combination, so a deployment that carried a seed-reaching ceiling fails to start
 * rather than starting with a ceiling the operator did not ask for. That is the stronger of the two
 * available designs: overwriting hides the misconfiguration, and a hidden misconfiguration is one that
 * survives into the next deployment. Under a non-production profile the same customizer completes the
 * scope instead, because a seeding profile that migrated the schema alone would load none of the
 * fixtures it exists to load.
 *
 * <h2>Independent expectations</h2>
 *
 * <p>The expected ceiling is the literal {@code 2} and the expected profile the literal {@code prod},
 * both typed here rather than read from the class under test.
 *
 * <h2>Provenance</h2>
 *
 * <p>The migration set under test derives from the CardDemo COBOL estate at checkout
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. No legacy source text is reproduced here.
 *
 * @since 1.0.0
 */
@DisplayName("Production migration ceiling, checked against the delivered scripts")
final class FlywayConfigCoverageTest {

    /** The ceiling, typed independently of the class under test. */
    private static final String EXPECTED_TARGET = "2";

    /** The profile the refusal is scoped to. */
    private static final String EXPECTED_PROFILE = "prod";

    /** The one location every delivered script ships from. */
    private static final String EXPECTED_LOCATION = "classpath:db/migration";

    /**
     * A Base64 key decoding to exactly thirty-two bytes, matching the shape the encryption service
     * requires. It is a test constant and protects nothing.
     */
    private static final String TEST_KEY = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";

    /** The scripts that build the schema, which the ceiling must reach. */
    private static final List<String> SCHEMA_MIGRATIONS = List.of(
            "V1__create_schema.sql",
            "V1_1__create_batch_metadata.sql",
            "V2__create_indexes.sql");

    /** The scripts that seed rows, which the ceiling must stop below. */
    private static final List<String> SEED_MIGRATIONS =
            List.of("V3__seed_reference_data.sql", "V4__seed_user_security.sql");

    /** A ceiling no profile declares, used to prove the customizer acts rather than defaults. */
    private static final String UNPINNED_TARGET = "4";

    @Nested
    @DisplayName("the published boundary")
    final class ThePublishedBoundary {

        @Test
        @DisplayName("names the last schema migration, so a seed added later is placed above it by "
                + "reading one constant")
        void namesTheLastSchemaMigration() {
            assertThat(FlywayConfig.SCHEMA_ONLY_TARGET).isEqualTo(EXPECTED_TARGET);
        }

        @Test
        @DisplayName("is scoped to the production profile by name")
        void isScopedToTheProductionProfile() {
            assertThat(FlywayConfig.PRODUCTION_PROFILE).isEqualTo(EXPECTED_PROFILE);
        }

        @Test
        @DisplayName("names the one location every script ships from, so the ceiling is the only "
                + "separation there is")
        void namesTheOneDeliveredLocation() {
            assertThat(FlywayConfig.SCHEMA_LOCATION).isEqualTo(EXPECTED_LOCATION);
        }

        @Test
        @DisplayName("lifts to a seeding ceiling that is above every delivered script, so a seeding "
                + "profile loads all of them")
        void theSeedingCeilingIsAboveEveryDeliveredScript() {
            assertThat(FlywayConfig.SEEDING_TARGET).isEqualTo("latest");
            assertThat(MigrationVersion.fromVersion(FlywayConfig.SEEDING_TARGET))
                    .as("the latest marker is above every concrete version by construction")
                    .isEqualTo(MigrationVersion.LATEST);
        }

        @Test
        @DisplayName("reaches every schema migration and stops below every seed migration, asserted "
                + "against the delivered numbering")
        void reachesTheSchemaAndStopsBelowTheSeeds() {
            MigrationVersion ceiling = MigrationVersion.fromVersion(FlywayConfig.SCHEMA_ONLY_TARGET);

            assertThat(deliveredMigrations())
                    .as("every claim below is about the delivered set, so a migration added or removed "
                            + "must be accounted for here first")
                    .containsExactlyInAnyOrderElementsOf(Stream.concat(SCHEMA_MIGRATIONS.stream(),
                            SEED_MIGRATIONS.stream()).toList());

            for (final String schemaMigration : SCHEMA_MIGRATIONS) {
                assertThat(versionOf(schemaMigration))
                        .as("%s must be applied in production; a ceiling short of it would leave a "
                                + "deployment without a table or an index the application requires",
                                schemaMigration)
                        .isLessThanOrEqualTo(ceiling);
            }
            for (final String seedMigration : SEED_MIGRATIONS) {
                assertThat(versionOf(seedMigration))
                        .as("%s must never run in production; for the sign-on seed that would mean ten "
                                + "known identities in a production deployment", seedMigration)
                        .isGreaterThan(ceiling);
            }
        }

        @Test
        @DisplayName("the interleaved schema script sorts between the two it was inserted between, "
                + "which is why Flyway's own ordering is used rather than a leading integer")
        void theInterleavedSchemaScriptSortsWhereItsNameSaysItDoes() {
            MigrationVersion first = versionOf("V1__create_schema.sql");
            MigrationVersion interleaved = versionOf("V1_1__create_batch_metadata.sql");
            MigrationVersion second = versionOf("V2__create_indexes.sql");

            assertThat(interleaved)
                    .as("a reader that took one leading integer would have read this as version 1 and "
                            + "made it indistinguishable from the script before it")
                    .isEqualTo(MigrationVersion.fromVersion("1.1"))
                    .isGreaterThan(first)
                    .isLessThan(second);
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
                    .as("under %s the seeds must be applied, and it is this same bean that lifts the "
                            + "inherited ceiling far enough to reach them; scoping the bean to "
                            + "production would leave a seeding profile migrating the schema alone",
                            profile)
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
                        .locations(EXPECTED_LOCATION)
                        .target(MigrationVersion.fromVersion(UNPINNED_TARGET));

                context.getBean(FlywayConfigurationCustomizer.class).customize(configuration);

                assertThat(configuration.getTarget())
                        .as("no profile selects a resolution, so nothing is imposed")
                        .isEqualTo(MigrationVersion.fromVersion(UNPINNED_TARGET));
            });
        }

        @Test
        @DisplayName("refuses a bound ceiling that reaches the seeds rather than replacing it, so an "
                + "edited or overridden value stops the deployment instead of being silently corrected")
        void refusesABoundCeilingThatReachesTheSeeds() {
            runner(EXPECTED_PROFILE).run(context -> {
                FluentConfiguration configuration = Flyway.configure()
                        .locations(EXPECTED_LOCATION)
                        .target(MigrationVersion.fromVersion(UNPINNED_TARGET));

                assertThat(configuration.getTarget())
                        .as("the starting state is deliberately a ceiling that would apply both seeds, "
                                + "so a customizer that did nothing would be visible here")
                        .isEqualTo(MigrationVersion.fromVersion(UNPINNED_TARGET));

                assertThatExceptionOfType(IllegalStateException.class)
                        .as("replacing the value would let the misconfiguration survive into the next "
                                + "deployment; refusing it makes the operator correct the source")
                        .isThrownBy(() -> context.getBean(FlywayConfigurationCustomizer.class)
                                .customize(configuration))
                        .withMessageContaining(EXPECTED_PROFILE)
                        .withMessageContaining("ceiling");
            });
        }

        @Test
        @DisplayName("refuses an absent ceiling as well, because a migration tool with no ceiling set "
                + "migrates to the latest version and silence is therefore the dangerous case")
        void refusesAnAbsentCeiling() {
            runner(EXPECTED_PROFILE).run(context -> {
                FluentConfiguration configuration = Flyway.configure().locations(EXPECTED_LOCATION);

                assertThatExceptionOfType(IllegalStateException.class)
                        .isThrownBy(() -> context.getBean(FlywayConfigurationCustomizer.class)
                                .customize(configuration))
                        .withMessageContaining(EXPECTED_PROFILE);
            });
        }

        @Test
        @DisplayName("accepts the declared ceiling and changes nothing else, so it cannot quietly "
                + "alter another migration setting on the way past")
        void acceptsTheDeclaredCeilingAndChangesNothingElse() {
            runner(EXPECTED_PROFILE).run(context -> {
                FluentConfiguration configuration = Flyway.configure()
                        .locations(EXPECTED_LOCATION)
                        .target(MigrationVersion.fromVersion(FlywayConfig.SCHEMA_ONLY_TARGET));
                boolean cleanDisabledBefore = configuration.isCleanDisabled();
                boolean validateBefore = configuration.isValidateOnMigrate();
                boolean baselineBefore = configuration.isBaselineOnMigrate();
                List<String> locationsBefore =
                        Stream.of(configuration.getLocations()).map(Object::toString).toList();

                context.getBean(FlywayConfigurationCustomizer.class).customize(configuration);

                assertThat(configuration.getTarget())
                        .isEqualTo(MigrationVersion.fromVersion(FlywayConfig.SCHEMA_ONLY_TARGET));
                assertThat(configuration.isCleanDisabled()).isEqualTo(cleanDisabledBefore);
                assertThat(configuration.isValidateOnMigrate()).isEqualTo(validateBefore);
                assertThat(configuration.isBaselineOnMigrate()).isEqualTo(baselineBefore);
                assertThat(Stream.of(configuration.getLocations()).map(Object::toString).toList())
                        .as("a customizer that also rewrote the location list would move the exclusion "
                                + "back onto a directory split without saying so")
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
                        .locations(EXPECTED_LOCATION)
                        .target(MigrationVersion.fromVersion(FlywayConfig.SCHEMA_ONLY_TARGET));

                customizer.customize(configuration);
                customizer.customize(configuration);

                assertThat(configuration.getTarget())
                        .isEqualTo(MigrationVersion.fromVersion(FlywayConfig.SCHEMA_ONLY_TARGET));
                assertThat(Stream.of(configuration.getLocations()).map(Object::toString).toList())
                        .containsExactly(EXPECTED_LOCATION);
            });
        }

        @ParameterizedTest(name = "the {0} profile has its ceiling lifted to reach the seeds")
        @ValueSource(strings = {"local", "test"})
        @DisplayName("lifts a seeding profile's inherited ceiling above every delivered script, which "
                + "is the other half of the same resolution")
        void liftsASeedingProfilesInheritedCeiling(final String profile) {
            runner(profile).run(context -> {
                FluentConfiguration configuration = Flyway.configure()
                        .locations(EXPECTED_LOCATION)
                        .target(MigrationVersion.fromVersion(FlywayConfig.SCHEMA_ONLY_TARGET));

                context.getBean(FlywayConfigurationCustomizer.class).customize(configuration);

                assertThat(configuration.getTarget())
                        .as("inheriting the production ceiling into %s would migrate the schema and "
                                + "none of the fixtures the profile exists to load", profile)
                        .isEqualTo(MigrationVersion.LATEST);
                for (final String seedMigration : SEED_MIGRATIONS) {
                    assertThat(versionOf(seedMigration))
                            .as("%s must be reachable once the ceiling is lifted", seedMigration)
                            .isLessThanOrEqualTo(configuration.getTarget());
                }
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
                        () -> new SensitiveFieldEncryptionService(TEST_KEY));
        return profiles.length == 0 ? runner : runner.withPropertyValues(
                "spring.profiles.active=" + String.join(",", profiles));
    }

    /**
     * Returns the versioned migrations delivered on the migration location.
     *
     * @return the delivered file names; never {@code null}
     */
    private static List<String> deliveredMigrations() {
        try {
            Resource[] found = new PathMatchingResourcePatternResolver()
                    .getResources("classpath*:db/migration/V*__*.sql");
            return Stream.of(found)
                    .map(Resource::getFilename)
                    .filter(name -> name != null)
                    .sorted()
                    .toList();
        } catch (IOException failure) {
            throw new UncheckedIOException("migration location is unreadable", failure);
        }
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
                .as("%s must be a versioned migration; an unversioned script is applied by no ceiling "
                        + "and excluded by none either", fileName)
                .isGreaterThan(prefix);
        return MigrationVersion.fromVersion(
                fileName.substring(prefix, separator).replace('_', '.'));
    }
}
