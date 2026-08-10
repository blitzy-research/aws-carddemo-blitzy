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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentMatchers;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;

import com.carddemo.service.SensitiveFieldEncryptionService;

/**
 * Asserts the two controls that make production's migration isolation independent of the migration
 * tool having run.
 *
 * <h2>Why these two controls exist at all</h2>
 *
 * <p>{@link FlywayConfig} resolves production's migration location and target inside a
 * {@code FlywayConfigurationCustomizer}, and it refuses an already-seeded database inside a Flyway
 * {@code Callback}. Every one of those three refusals is reached <em>through the migration tool</em>.
 * {@code spring.flyway.enabled=false} builds no migration tool, so it applies no customizer and offers
 * no callback event: one property switches three controls off at once, silently, and the application
 * otherwise starts normally against whatever the database already holds - fifty synthetic customer rows
 * carrying regulated identity data and ten known sign-on identities, five of them administrative, whose
 * stored credentials are all digests of one well-known value.
 *
 * <p>So this class asserts two members that do not depend on the migration tool existing:
 *
 * <ul>
 *   <li>{@link FlywayConfig#requireCanonicalProductionMigrationSource} - reads
 *       {@code spring.flyway.enabled}, {@code spring.flyway.target} and
 *       {@code spring.flyway.locations} straight out of the environment and requires all three to be
 *       stated and canonical. Published as a {@link BeanFactoryPostProcessor}, so the refusal lands
 *       before any bean is instantiated.</li>
 *   <li>{@link FlywayConfig#requireUnseededProductionDatabase} - runs
 *       {@link ProductionSeedRejectionCallback}'s own inspection from an ordinary production singleton,
 *       so the database is examined on every production start-up whatever the migration settings
 *       say.</li>
 * </ul>
 *
 * <h2>What is asserted here and what is asserted against a real database</h2>
 *
 * <p>Everything about the configuration half is asserted here, because it reads only an
 * {@link org.springframework.core.env.Environment} and needs no database. The database half is asserted
 * here for its three decision branches - no template, an unreadable database, and an inspection that
 * fires - using a connection stubbed to the exact catalogue and history reads the inspection issues.
 * That it fires against a genuinely seeded PostgreSQL database, in a context carrying no migration tool
 * at all, is asserted by {@code ProductionSeedRejectionCallbackIT}, which is where a real database
 * belongs.
 *
 * <p>Every refusal is additionally checked for the property of decision {@code DL-041}: it names the
 * keys and literals the class itself declares and never the operator-supplied value it rejected, since
 * a start-up log that echoed a supplied value would let whoever supplied it write a chosen line into
 * it.
 *
 * <p>Traceability: the migration set these controls protect stands in for the ten
 * {@code DEFINE CLUSTER} provisioning job streams of the legacy estate at checkout
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. No legacy source text is reproduced here.
 */
@DisplayName("Production migration isolation that does not depend on the migration tool running")
final class ProductionMigrationSourceGuardTest {

    /** The profile the controls act on. */
    private static final String PRODUCTION = "prod";

    /** The property that switches the migration tool on. */
    private static final String ENABLED_KEY = "spring.flyway.enabled";

    /**
     * The property carrying the migration target.
     *
     * <p>Production must declare it as the open marker or not at all; a NUMBER is refused whatever its
     * value. A number once excluded the seeds by arithmetic and it also froze the schema at that
     * version, so the exclusion moved to the location list and a number here now has no work to do.
     * See docs/decision-log.md DL-298.
     */
    private static final String TARGET_KEY = "spring.flyway.target";

    /** The property carrying the location list. */
    private static final String LOCATIONS_KEY = "spring.flyway.locations";

    /** A field-encryption key of the right shape, so the collaborator bean can be built. */
    private static final String TEST_KEY = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";

    /** The first column of a projection, as JDBC numbers them. */
    private static final int FIRST_COLUMN = 1;

    /** A recorded history version that reaches the seeds. */
    private static final String APPLIED_SEED_VERSION = "3";

    /** Carriage return, which a diagnostic may not carry. */
    private static final char CARRIAGE_RETURN = '\r';

    /** Line feed, which a diagnostic may not carry. */
    private static final char LINE_FEED = '\n';

    /** Creates the test class. */
    ProductionMigrationSourceGuardTest() {
    }

    @Nested
    @DisplayName("the migration-source guard, which reads the environment and needs no database")
    final class TheMigrationSourceGuard {

        /** Creates the nest. */
        TheMigrationSourceGuard() {
        }

        @Test
        @DisplayName("a production environment stating all three canonical settings is accepted")
        void aCanonicalProductionEnvironmentIsAccepted() {
            assertThatNoException().isThrownBy(() ->
                    FlywayConfig.requireCanonicalProductionMigrationSource(canonicalProduction()));
        }

        @Test
        @DisplayName("a production environment with the migration tool switched off is refused, which "
                + "is the whole point: one property must not be able to switch three controls off")
        void aProductionEnvironmentWithMigrationsDisabledIsRefused() {
            final MockEnvironment disabled = canonicalProduction();
            disabled.setProperty(ENABLED_KEY, "false");

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() ->
                            FlywayConfig.requireCanonicalProductionMigrationSource(disabled))
                    .withMessageContaining(ENABLED_KEY)
                    .withMessageContaining(PRODUCTION)
                    .satisfies(refusal -> assertCarriesNoRawTerminator(refusal.getMessage()));
        }

        @ParameterizedTest(name = "the migration tool declared [{0}] is refused")
        @ValueSource(strings = {"false", "FALSE", "False", "no", "0", "off", "not-a-boolean", " "})
        @DisplayName("every value that is not a true boolean is refused, because a value the binder "
                + "cannot read as true is not a statement that migrations run")
        void everyValueThatIsNotTrueIsRefused(final String declared) {
            final MockEnvironment environment = canonicalProduction();
            environment.setProperty(ENABLED_KEY, declared);

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() ->
                            FlywayConfig.requireCanonicalProductionMigrationSource(environment))
                    .withMessageContaining(ENABLED_KEY);
        }

        @Test
        @DisplayName("an absent enablement setting is refused rather than defaulted to the "
                + "framework's true, because a production migration source is stated, not inferred")
        void anAbsentEnablementSettingIsRefused() {
            final MockEnvironment silent = new MockEnvironment();
            silent.setActiveProfiles(PRODUCTION);
            silent.setProperty(TARGET_KEY, FlywayConfig.PRODUCTION_TARGET);
            silent.setProperty(LOCATIONS_KEY, FlywayConfig.SCHEMA_LOCATION);

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() ->
                            FlywayConfig.requireCanonicalProductionMigrationSource(silent))
                    .withMessageContaining(ENABLED_KEY);
        }

        @ParameterizedTest(name = "the target [{0}] is refused")
        @ValueSource(strings = {"1", "1.1", "3", "4", "latest", "current", "not-a-version"})
        @DisplayName("every target other than the pin is refused, in both directions and including the "
                + "open marker, from the same resolution the customizer applies")
        void everyTargetOtherThanThePinIsRefused(final String target) {
            final MockEnvironment environment = canonicalProduction();
            environment.setProperty(TARGET_KEY, target);

            assertThatExceptionOfType(IllegalStateException.class)
                    .as("a target of %s is refused. BELOW the pin the migration stops before the indexes "
                            + "and constraints are created and reports success anyway. ABOVE it - and the "
                            + "open marker is above it - the migration applies whatever a resolved "
                            + "location carries past the delivered schema, and the seed scripts are "
                            + "numbered there. The pin itself is asserted against the delivered scripts, "
                            + "so it cannot freeze a later release", target)
                    .isThrownBy(() ->
                            FlywayConfig.requireCanonicalProductionMigrationSource(environment))
                    .withMessageContaining(FlywayConfig.PRODUCTION_TARGET)
                    .satisfies(refusal -> assertCarriesNoRawTerminator(refusal.getMessage()));
        }

        @Test
        @DisplayName("an absent target is ACCEPTED, because the resolution corrects silence to the pin "
                + "rather than refusing a deployment that inherited the right posture")
        void anAbsentTargetIsAccepted() {
            final MockEnvironment silent = new MockEnvironment();
            silent.setActiveProfiles(PRODUCTION);
            silent.setProperty(ENABLED_KEY, "true");
            silent.setProperty(LOCATIONS_KEY, FlywayConfig.SCHEMA_LOCATION);

            assertThatNoException()
                    .as("silence is accepted because the resolution corrects it to the pin: a migration "
                            + "tool with no target migrates to the latest version, so silence left alone "
                            + "would be the dangerous case, and refusing it would stop a deployment that "
                            + "had inherited the right posture from the shared baseline. What is still "
                            + "refused is the location being anything but the packaged schema directory, "
                            + "and the enablement setting being absent")
                    .isThrownBy(() ->
                            FlywayConfig.requireCanonicalProductionMigrationSource(silent));

            silent.setProperty(LOCATIONS_KEY, FlywayConfig.SEED_LOCATION);

            assertThatExceptionOfType(IllegalStateException.class)
                    .as("and the same silent target over the SEED location is refused, which is what "
                            + "shows the acceptance above rests on the location and not on the target")
                    .isThrownBy(() ->
                            FlywayConfig.requireCanonicalProductionMigrationSource(silent))
                    .withMessageContaining(FlywayConfig.SCHEMA_LOCATION);
        }

        @ParameterizedTest(name = "the location [{0}] is refused")
        @ValueSource(strings = {
            "filesystem:/tmp/attacker/db/migration",
            "filesystem:src/main/resources/db/migration",
            "classpath:db/migration/",
            "classpath:db/migration/regional",
            "db/migration",
            "classpath:db/seed"
        })
        @DisplayName("every location other than the packaged one is refused, so a file-system "
                + "look-alike cannot become the source a production migration reads")
        void everyLocationOtherThanThePackagedOneIsRefused(final String location) {
            final MockEnvironment environment = canonicalProduction();
            environment.setProperty(LOCATIONS_KEY, location);

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() ->
                            FlywayConfig.requireCanonicalProductionMigrationSource(environment))
                    .withMessageContaining(FlywayConfig.SCHEMA_LOCATION)
                    .satisfies(refusal -> assertCarriesNoRawTerminator(refusal.getMessage()));
        }

        @Test
        @DisplayName("the location refusal names the one accepted value and never the rejected "
                + "descriptor, so an operator-supplied path cannot write itself into the start-up log")
        void theLocationRefusalNamesNoRejectedDescriptor() {
            final MockEnvironment environment = canonicalProduction();
            environment.setProperty(LOCATIONS_KEY, "filesystem:/var/tmp/planted/db/migration");

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() ->
                            FlywayConfig.requireCanonicalProductionMigrationSource(environment))
                    .satisfies(refusal -> assertThat(refusal.getMessage())
                            .contains(FlywayConfig.SCHEMA_LOCATION)
                            .doesNotContain("/var/tmp/planted")
                            .doesNotContain("planted"));
        }

        @Test
        @DisplayName("the enablement refusal names no supplied value either, which reading the setting "
                + "as text rather than converting it to a boolean is what buys")
        void theEnablementRefusalNamesNoSuppliedValue() {
            final MockEnvironment environment = canonicalProduction();
            environment.setProperty(ENABLED_KEY, "planted-value");

            assertThatExceptionOfType(IllegalStateException.class)
                    .as("asking the environment for a Boolean here would raise a conversion failure "
                            + "whose own message quotes the offending value")
                    .isThrownBy(() ->
                            FlywayConfig.requireCanonicalProductionMigrationSource(environment))
                    .satisfies(refusal -> assertThat(refusal.getMessage())
                            .contains(ENABLED_KEY)
                            .doesNotContain("planted-value"));
        }

        @Test
        @DisplayName("the target refusal names no supplied value either")
        void theTargetRefusalNamesNoSuppliedValue() {
            final MockEnvironment environment = canonicalProduction();
            environment.setProperty(TARGET_KEY, "planted-version");

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() ->
                            FlywayConfig.requireCanonicalProductionMigrationSource(environment))
                    .satisfies(refusal -> assertThat(refusal.getMessage())
                            .contains(FlywayConfig.PRODUCTION_TARGET)
                            .doesNotContain("planted-version"));
        }

        @Test
        @DisplayName("an absent location list is refused, so a production deployment cannot inherit "
                + "its migration source from a framework default")
        void anAbsentLocationListIsRefused() {
            final MockEnvironment silent = new MockEnvironment();
            silent.setActiveProfiles(PRODUCTION);
            silent.setProperty(ENABLED_KEY, "true");
            silent.setProperty(TARGET_KEY, FlywayConfig.PRODUCTION_TARGET);

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() ->
                            FlywayConfig.requireCanonicalProductionMigrationSource(silent))
                    .withMessageContaining(FlywayConfig.SCHEMA_LOCATION);
        }

        @Test
        @DisplayName("a second location supplied comma-separated is refused, because the guard counts "
                + "the entries the binder produces rather than reading only the first")
        void aSecondLocationSuppliedCommaSeparatedIsRefused() {
            final MockEnvironment two = canonicalProduction();
            two.setProperty(LOCATIONS_KEY,
                    FlywayConfig.SCHEMA_LOCATION + ",filesystem:/tmp/attacker");

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() ->
                            FlywayConfig.requireCanonicalProductionMigrationSource(two))
                    .withMessageContaining(FlywayConfig.SCHEMA_LOCATION);
        }

        @Test
        @DisplayName("a location list supplied as indexed entries is read, so an indexed override "
                + "cannot slip past unexamined - which is why the binder is used and not getProperty")
        void aLocationListSuppliedAsIndexedEntriesIsRead() {
            final MockEnvironment indexed = new MockEnvironment();
            indexed.setActiveProfiles(PRODUCTION);
            indexed.setProperty(ENABLED_KEY, "true");
            indexed.setProperty(TARGET_KEY, FlywayConfig.PRODUCTION_TARGET);
            indexed.setProperty(LOCATIONS_KEY + "[0]", "filesystem:/tmp/attacker/db/migration");

            assertThatExceptionOfType(IllegalStateException.class)
                    .as("read with getProperty this list would resolve to nothing at all, and a guard "
                            + "that saw nothing would have had nothing to refuse")
                    .isThrownBy(() ->
                            FlywayConfig.requireCanonicalProductionMigrationSource(indexed))
                    .withMessageContaining(FlywayConfig.SCHEMA_LOCATION);

            final MockEnvironment canonical = new MockEnvironment();
            canonical.setActiveProfiles(PRODUCTION);
            canonical.setProperty(ENABLED_KEY, "true");
            canonical.setProperty(TARGET_KEY, FlywayConfig.PRODUCTION_TARGET);
            canonical.setProperty(LOCATIONS_KEY + "[0]", FlywayConfig.SCHEMA_LOCATION);

            assertThatNoException()
                    .as("and the canonical value supplied the same way is accepted, so the indexed "
                            + "form is read rather than merely rejected")
                    .isThrownBy(() ->
                            FlywayConfig.requireCanonicalProductionMigrationSource(canonical));
        }

        @Test
        @DisplayName("a second indexed entry is refused even when the first is the packaged location, "
                + "because the second entry is a second migration source whatever the first is")
        void aSecondIndexedEntryIsRefused() {
            final MockEnvironment two = new MockEnvironment();
            two.setActiveProfiles(PRODUCTION);
            two.setProperty(ENABLED_KEY, "true");
            two.setProperty(TARGET_KEY, FlywayConfig.PRODUCTION_TARGET);
            two.setProperty(LOCATIONS_KEY + "[0]", FlywayConfig.SCHEMA_LOCATION);
            two.setProperty(LOCATIONS_KEY + "[1]", "filesystem:/tmp/attacker/db/migration");

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() ->
                            FlywayConfig.requireCanonicalProductionMigrationSource(two));
        }

        @ParameterizedTest(name = "the {0} profile is left alone")
        @ValueSource(strings = {"local", "test", "dev", ""})
        @DisplayName("a profile that is not production is left alone entirely, because the seeding "
                + "profiles exist to apply the seeds and are governed by the resolutions instead")
        void aProfileThatIsNotProductionIsLeftAlone(final String profile) {
            final MockEnvironment other = new MockEnvironment();
            if (!profile.isEmpty()) {
                other.setActiveProfiles(profile);
            }
            other.setProperty(ENABLED_KEY, "false");
            other.setProperty(TARGET_KEY, FlywayConfig.PRODUCTION_TARGET);
            other.setProperty(LOCATIONS_KEY, "filesystem:/tmp/anywhere");

            assertThatNoException().isThrownBy(() ->
                    FlywayConfig.requireCanonicalProductionMigrationSource(other));
        }

        @Test
        @DisplayName("the guard is published as a bean-factory post-processor, so the refusal lands "
                + "before any bean is instantiated rather than part-way through a start-up")
        void theGuardIsPublishedAsABeanFactoryPostProcessor() {
            final MockEnvironment disabled = canonicalProduction();
            disabled.setProperty(ENABLED_KEY, "false");
            final BeanFactoryPostProcessor guard =
                    FlywayConfig.productionMigrationSourceGuard(disabled);

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() ->
                            guard.postProcessBeanFactory(new DefaultListableBeanFactory()))
                    .withMessageContaining(ENABLED_KEY);

            assertThatNoException()
                    .as("and the same hook accepts a canonical production environment, so the guard "
                            + "is a gate rather than an unconditional refusal")
                    .isThrownBy(() -> FlywayConfig
                            .productionMigrationSourceGuard(canonicalProduction())
                            .postProcessBeanFactory(new DefaultListableBeanFactory()));
        }

        @Test
        @DisplayName("a real production context with the migration tool switched off does not reach a "
                + "running state, which is the form the guarantee has to hold in")
        void aRealProductionContextWithMigrationsDisabledDoesNotStart() {
            contextRunner()
                    .withPropertyValues(
                            "spring.profiles.active=" + PRODUCTION,
                            ENABLED_KEY + "=false",
                            TARGET_KEY + "=" + FlywayConfig.PRODUCTION_TARGET,
                            LOCATIONS_KEY + "=" + FlywayConfig.SCHEMA_LOCATION)
                    .run(context -> assertThat(context)
                            .hasFailed()
                            .getFailure()
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining(ENABLED_KEY));
        }

        @Test
        @DisplayName("a real production context with a file-system migration source does not reach a "
                + "running state either")
        void aRealProductionContextWithAFileSystemSourceDoesNotStart() {
            contextRunner()
                    .withPropertyValues(
                            "spring.profiles.active=" + PRODUCTION,
                            ENABLED_KEY + "=true",
                            TARGET_KEY + "=" + FlywayConfig.PRODUCTION_TARGET,
                            LOCATIONS_KEY + "=filesystem:/tmp/attacker/db/migration")
                    .run(context -> assertThat(context)
                            .hasFailed()
                            .getFailure()
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining(FlywayConfig.SCHEMA_LOCATION));
        }

        @Test
        @DisplayName("a real production context stating all three canonical settings starts, so the "
                + "guard does not simply refuse every production start-up")
        void aRealCanonicalProductionContextStarts() {
            contextRunner()
                    .withPropertyValues(
                            "spring.profiles.active=" + PRODUCTION,
                            ENABLED_KEY + "=true",
                            TARGET_KEY + "=" + FlywayConfig.PRODUCTION_TARGET,
                            LOCATIONS_KEY + "=" + FlywayConfig.SCHEMA_LOCATION)
                    .run(context -> assertThat(context).hasNotFailed());
        }
    }

    @Nested
    @DisplayName("the seeded-database guard, which runs whatever the migration settings say")
    final class TheSeededDatabaseGuard {

        /** Creates the nest. */
        TheSeededDatabaseGuard() {
        }

        @Test
        @DisplayName("a context publishing no template inspects nothing and does not refuse, because "
                + "no template means no data source and so no database to hold a seeded row")
        void aContextPublishingNoTemplateInspectsNothing() {
            assertThatNoException()
                    .isThrownBy(() -> FlywayConfig.requireUnseededProductionDatabase(null));
        }

        @Test
        @DisplayName("a database that cannot be read refuses the start-up, because a state that could "
                + "not be established is not a state that may be assumed clean")
        void aDatabaseThatCannotBeReadRefusesTheStartUp() {
            final JdbcTemplate unreadable = mock(JdbcTemplate.class);
            final DataAccessResourceFailureException failure =
                    new DataAccessResourceFailureException("connection refused");
            when(unreadable.execute(ArgumentMatchers.<ConnectionCallback<Void>>any()))
                    .thenThrow(failure);

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() ->
                            FlywayConfig.requireUnseededProductionDatabase(unreadable))
                    .withMessageContaining("refused")
                    .withCause(failure)
                    .satisfies(refusal -> assertCarriesNoRawTerminator(refusal.getMessage()));
        }

        @Test
        @DisplayName("a seeded database refuses the start-up through this path, with no migration "
                + "tool involved at any point")
        void aSeededDatabaseRefusesTheStartUpThroughThisPath() {
            assertThatExceptionOfType(FlywayException.class)
                    .as("the refusal is the inspection's own, which is what proves this path reaches "
                            + "the same three signals rather than holding a second copy of them")
                    .isThrownBy(() -> FlywayConfig.requireUnseededProductionDatabase(
                            templateOver(seededConnection())))
                    .withMessageContaining(ProductionSeedRejectionCallback.HISTORY_TABLE)
                    .withMessageContaining(ProductionSeedRejectionCallback.FIRST_SEED_VERSION);
        }

        @Test
        @DisplayName("a database with no migration history at all is accepted, so a first production "
                + "deployment against an empty database is not refused")
        void aDatabaseWithNoHistoryAtAllIsAccepted() {
            assertThatNoException()
                    .isThrownBy(() -> FlywayConfig.requireUnseededProductionDatabase(
                            templateOver(emptyConnection())));
        }

        @Test
        @DisplayName("the production singleton performs the inspection itself, so a seeded database "
                + "stops the context refresh with no migration tool in the context at all")
        void theProductionSingletonPerformsTheInspectionItself() {
            contextRunner()
                    .withBean(JdbcTemplate.class, () -> templateOver(seededConnection()))
                    .withPropertyValues(canonicalProductionSettings())
                    .run(context -> assertThat(context)
                            .as("the bean must DO the inspection, not merely exist: a bean whose body "
                                    + "was empty would leave a deployment running against a database "
                                    + "holding ten known administrative credentials")
                            .hasFailed()
                            .getFailure()
                            .hasStackTraceContaining(ProductionSeedRejectionCallback.HISTORY_TABLE)
                            .hasStackTraceContaining(
                                    ProductionSeedRejectionCallback.FIRST_SEED_VERSION));
        }

        @Test
        @DisplayName("the same wiring over a never-seeded database starts, which is the control "
                + "assertion the refusal above depends on")
        void theSameWiringOverANeverSeededDatabaseStarts() {
            contextRunner()
                    .withBean(JdbcTemplate.class, () -> templateOver(emptyConnection()))
                    .withPropertyValues(canonicalProductionSettings())
                    .run(context -> assertThat(context)
                            .as("without this, a failed refresh above would prove only that the wiring "
                                    + "is broken")
                            .hasNotFailed());
        }

        @Test
        @DisplayName("the guard is published for production alone, so the two seeding profiles - "
                + "whose seeded rows are the point - are not refused by it")
        void theGuardIsPublishedForProductionAlone() {
            contextRunner()
                    .withPropertyValues(
                            "spring.profiles.active=" + PRODUCTION,
                            ENABLED_KEY + "=true",
                            TARGET_KEY + "=" + FlywayConfig.PRODUCTION_TARGET,
                            LOCATIONS_KEY + "=" + FlywayConfig.SCHEMA_LOCATION)
                    .run(context -> assertThat(context)
                            .hasNotFailed()
                            .hasSingleBean(InitializingBean.class));

            contextRunner()
                    .withPropertyValues("spring.profiles.active=local")
                    .run(context -> assertThat(context)
                            .hasNotFailed()
                            .doesNotHaveBean(InitializingBean.class));
        }
    }

    // Helpers.

    /**
     * Builds a production environment stating all three settings canonically.
     *
     * @return an environment a production start-up would be accepted from
     */
    private static MockEnvironment canonicalProduction() {
        final MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(PRODUCTION);
        environment.setProperty(ENABLED_KEY, "true");
        environment.setProperty(TARGET_KEY, FlywayConfig.PRODUCTION_TARGET);
        environment.setProperty(LOCATIONS_KEY, FlywayConfig.SCHEMA_LOCATION);
        return environment;
    }

    /**
     * Returns the three migration settings a production start-up must have stated.
     *
     * @return the property assignments, in the form a context runner takes them
     */
    private static String[] canonicalProductionSettings() {
        return new String[] {
            "spring.profiles.active=" + PRODUCTION,
            ENABLED_KEY + "=true",
            TARGET_KEY + "=" + FlywayConfig.PRODUCTION_TARGET,
            LOCATIONS_KEY + "=" + FlywayConfig.SCHEMA_LOCATION,
        };
    }

    /**
     * Builds a context runner over the configuration class and the one collaborator it needs.
     *
     * @return a runner that has not been started
     */
    private static ApplicationContextRunner contextRunner() {
        return new ApplicationContextRunner()
                .withUserConfiguration(FlywayConfig.class)
                .withBean(SensitiveFieldEncryptionService.class,
                        () -> new SensitiveFieldEncryptionService(TEST_KEY));
    }

    /**
     * Wraps one connection in a template that hands it to whatever callback it is given.
     *
     * <p>A stub rather than a mock of the template's own behaviour: the point is to drive the real
     * {@code ConnectionCallback} the guard passes, so the assertion is about the inspection the guard
     * performs and not about the template.
     *
     * @param connection the connection every callback receives
     * @return a template that executes a connection callback against that connection
     */
    private static JdbcTemplate templateOver(final Connection connection) {
        final JdbcTemplate template = mock(JdbcTemplate.class);
        when(template.execute(ArgumentMatchers.<ConnectionCallback<Void>>any()))
                .thenAnswer(invocation -> {
                    final ConnectionCallback<Void> callback = invocation.getArgument(0);
                    return callback.doInConnection(connection);
                });
        return template;
    }

    /**
     * Builds a connection that answers as a database whose migration history records an applied seed
     * migration.
     *
     * <p>Stubbed at exactly the two reads the first signal issues: the parameterised catalogue lookup
     * that asks whether the history table exists, and the literal projection of applied versions.
     *
     * @return a connection standing for a seeded database
     */
    private static Connection seededConnection() {
        try {
            final Connection connection = mock(Connection.class);

            final ResultSet tablePresent = mock(ResultSet.class);
            when(tablePresent.next()).thenReturn(true);
            when(tablePresent.getLong(FIRST_COLUMN)).thenReturn(1L);
            final PreparedStatement catalogue = mock(PreparedStatement.class);
            when(catalogue.executeQuery()).thenReturn(tablePresent);
            when(connection.prepareStatement(any(String.class))).thenReturn(catalogue);

            final ResultSet appliedVersions = mock(ResultSet.class);
            when(appliedVersions.next()).thenReturn(true, false);
            when(appliedVersions.getString(FIRST_COLUMN)).thenReturn(APPLIED_SEED_VERSION);
            final Statement versions = mock(Statement.class);
            when(versions.executeQuery(any(String.class))).thenReturn(appliedVersions);
            when(connection.createStatement()).thenReturn(versions);

            return connection;
        } catch (final SQLException impossible) {
            throw new IllegalStateException("stubbing a mock cannot fail", impossible);
        }
    }

    /**
     * Builds a connection that answers as a database holding none of the inspected tables.
     *
     * @return a connection standing for an empty database
     */
    private static Connection emptyConnection() {
        try {
            final Connection connection = mock(Connection.class);
            final ResultSet tableAbsent = mock(ResultSet.class);
            when(tableAbsent.next()).thenReturn(true);
            when(tableAbsent.getLong(FIRST_COLUMN)).thenReturn(0L);
            final PreparedStatement catalogue = mock(PreparedStatement.class);
            when(catalogue.executeQuery()).thenReturn(tableAbsent);
            when(connection.prepareStatement(any(String.class))).thenReturn(catalogue);
            return connection;
        } catch (final SQLException impossible) {
            throw new IllegalStateException("stubbing a mock cannot fail", impossible);
        }
    }

    /**
     * Asserts a diagnostic carries no raw line terminator, so it cannot forge a log entry.
     *
     * @param message the diagnostic to inspect
     */
    private static void assertCarriesNoRawTerminator(final String message) {
        assertThat(message)
                .as("a diagnostic may not carry a raw line terminator")
                .doesNotContain(String.valueOf(CARRIAGE_RETURN))
                .doesNotContain(String.valueOf(LINE_FEED));
    }
}
