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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import jakarta.persistence.EntityManagerFactory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.annotation.MergedAnnotations;
import org.springframework.core.annotation.MergedAnnotations.SearchStrategy;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.auditing.DateTimeProvider;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.orm.jpa.JpaVendorAdapter;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.transaction.PlatformTransactionManager;

import com.carddemo.support.SchemaColumnCatalog;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the JPA configuration, which contributes one clock and nothing else.
 *
 * <p>The clock is UTC so a timestamp written by one host reads back identically on another, and it
 * is published as a bean precisely so a test can substitute a fixed one - a service reading the
 * system clock directly could not be pinned. The suite also asserts what this configuration does
 * <em>not</em> contribute: no data source, entity manager, vendor adapter or transaction manager, and
 * no entity scan, so importing it cannot map anything or open a connection.
 */
@DisplayName("JPA configuration: one pinnable UTC clock, and no audit column for it to write into")
class JpaAuditConfigTest {
    private static final String CLOCK_BEAN_NAME = "systemClock";

    private static final String CONFIGURATION_BEAN_NAME = "jpaAuditConfig";

    private static final String FRAMEWORK_BEAN_PREFIX = "org.springframework.";

    private static final String SHARED_CONFIGURATION = "application.yml";

    private static final String DDL_AUTO_KEY = "spring.jpa.hibernate.ddl-auto";

    private static final String JDBC_TIME_ZONE_KEY = "spring.jpa.properties.hibernate.jdbc.time_zone";

    private static final String EXPECTED_DDL_AUTO = "validate";

    private static final String EXPECTED_JDBC_TIME_ZONE = "UTC";

    private static final String MIGRATION_NAME = "db/migration/V1__create_schema.sql";

    private static final List<String> FORBIDDEN_AUDIT_COLUMNS =
            List.of("created_at", "created_by", "modified_at", "modified_by", "last_updated");

    private static final String OPTIMISTIC_LOCK_COLUMN = "version";

    private static final String OPTIMISTIC_LOCK_TYPE = "BIGINT";

    private static final List<String> OPTIMISTICALLY_LOCKED_TABLES = List.of("account", "card");

    private static final Instant PINNED = Instant.parse("2022-07-19T23:15:58.123456Z");

    private static final ZoneOffset NON_UTC_OFFSET = ZoneOffset.ofHours(-5);

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(JpaAuditConfig.class);

    @Nested
    @DisplayName("The published clock")
    class PublishedClock {
        @Test
        @DisplayName("is fixed to UTC, so a timestamp written by one host reads back identically on "
                + "another")
        void isFixedToUtc() {
            final Clock clock = new JpaAuditConfig().systemClock();

            assertThat(clock).as("the configuration must publish a clock").isNotNull();
            assertThat(clock.getZone())
                    .as("the published zone is part of the output contract, not a preference")
                    .isEqualTo(ZoneOffset.UTC);
            assertThat(clock.getZone().getRules().isFixedOffset())
                    .as("a region zone could shift with daylight saving and move an emitted timestamp; "
                            + "the published zone must be a fixed offset")
                    .isTrue();
            assertThat(clock.getZone().getRules().getOffset(PINNED))
                    .as("the published zone must resolve to a zero offset at every instant. The host "
                            + "this test runs on resolves to %s, and the bean must not adopt it",
                            ZoneId.systemDefault().getRules().getOffset(PINNED))
                    .isEqualTo(ZoneOffset.UTC);
            assertThat(clock.getZone().getRules().getOffset(Instant.EPOCH))
                    .as("zero offset holds at every instant, not merely at the pinned one")
                    .isEqualTo(ZoneOffset.UTC);
        }

        @Test
        @DisplayName("is the platform UTC clock rather than a clock built from a business or host zone")
        void isThePlatformUtcClock() {
            final Clock clock = new JpaAuditConfig().systemClock();

            assertThat(clock)
                    .as("must be the platform UTC clock")
                    .isEqualTo(Clock.systemUTC());
            assertThat(clock)
                    .as("must not be a clock in a non-zero offset such as %s, which would make the "
                            + "emitted bytes a function of where the job ran", NON_UTC_OFFSET)
                    .isNotEqualTo(Clock.system(NON_UTC_OFFSET));
            assertThat(clock.getZone())
                    .as("the zone identifier must be the canonical zero offset, which no region "
                            + "identifier ever equals")
                    .isEqualTo(ZoneOffset.UTC)
                    .isNotEqualTo(NON_UTC_OFFSET);
        }

        @Test
        @DisplayName("is produced identically on every call, so two injection points cannot disagree")
        void isProducedIdenticallyOnEveryCall() {
            final JpaAuditConfig configuration = new JpaAuditConfig();

            final Clock first = configuration.systemClock();
            final Clock second = configuration.systemClock();

            assertThat(first).isEqualTo(second);
            assertThat(first.getZone()).isEqualTo(second.getZone());
        }

        @Test
        @DisplayName("reads real time rather than being frozen, which is what a pinned clock has to "
                + "replace")
        void readsRealTime() {
            final Clock clock = new JpaAuditConfig().systemClock();

            assertReportsThePresentMoment(clock, "the published clock");

            final Instant first = clock.instant();
            final Instant second = clock.instant();

            assertThat(first)
                    .as("successive reads of a live clock never move backwards")
                    .isBeforeOrEqualTo(second);
        }
    }

    @Nested
    @DisplayName("Container wiring")
    class ContainerWiring {
        @Test
        @DisplayName("contributes exactly one clock, under the name every substitution relies on")
        void contributesExactlyOneClock() {
            runner.run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context)
                        .as("a second clock would let one component stamp a record in a different zone "
                                + "from another")
                        .hasSingleBean(Clock.class);
                assertThat(context).hasBean(CLOCK_BEAN_NAME);
                assertThat(context.getBean(CLOCK_BEAN_NAME, Clock.class).getZone())
                        .isEqualTo(ZoneOffset.UTC);
            });
        }

        @Test
        @DisplayName("contributes nothing but the clock, so it can be imported anywhere without "
                + "dragging infrastructure along")
        void contributesNothingButTheClock() {
            runner.run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(applicationOwnedBeanNames(context.getBeanDefinitionNames()))
                        .as("beside container infrastructure, the slice must hold only the "
                                + "configuration itself and its clock")
                        .containsExactlyInAnyOrder(CONFIGURATION_BEAN_NAME, CLOCK_BEAN_NAME);
            });
        }

        @Test
        @DisplayName("contributes no data source, entity manager, vendor adapter or transaction "
                + "manager, which auto-configuration owns")
        void contributesNoPersistenceInfrastructure() {
            runner.run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).doesNotHaveBean(DataSource.class);
                assertThat(context).doesNotHaveBean(EntityManagerFactory.class);
                assertThat(context).doesNotHaveBean(LocalContainerEntityManagerFactoryBean.class);
                assertThat(context).doesNotHaveBean(JpaVendorAdapter.class);
                assertThat(context).doesNotHaveBean(PlatformTransactionManager.class);
            });
        }

        @Test
        @DisplayName("scans no entity package and enables no repository factory, so it cannot map a "
                + "column the migrations never created")
        void scansNoEntityPackageAndEnablesNoRepositoryFactory() {
            runner.run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context)
                        .as("an entity manager appearing here would mean an entity scan had been added")
                        .doesNotHaveBean(EntityManagerFactory.class);
                assertThat(context.getBeanNamesForType(DataSource.class))
                        .as("a repository factory cannot be wired without a data source, so the absence "
                                + "of one is the behavioural proof")
                        .isEmpty();
            });

            final MergedAnnotations declared =
                    MergedAnnotations.from(JpaAuditConfig.class, SearchStrategy.TYPE_HIERARCHY);

            assertThat(declared.isPresent(EnableJpaRepositories.class))
                    .as("secondary guard: enabling repositories here would register a factory into "
                            + "every context that imports this configuration")
                    .isFalse();
            assertThat(declared.isPresent(EntityScan.class))
                    .as("secondary guard: an entity scan here would build a persistence unit in a "
                            + "context that has no data source")
                    .isFalse();
        }

        @Test
        @DisplayName("registers no auditing collaborator, because no persistent property is audited")
        void registersNoAuditingCollaborator() {
            runner.run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context)
                        .as("an auditor supplier is consulted only by auditing infrastructure that is "
                                + "intentionally absent, so a bean here would be dead configuration")
                        .doesNotHaveBean(AuditorAware.class);
                assertThat(context)
                        .as("an auditing date-time provider would be the mechanism by which an audit "
                                + "column got written; there is no such column")
                        .doesNotHaveBean(DateTimeProvider.class);
            });

            assertThat(MergedAnnotations.from(JpaAuditConfig.class, SearchStrategy.TYPE_HIERARCHY)
                    .isPresent(EnableJpaAuditing.class))
                    .as("secondary guard: switching auditing on would demand audit columns the "
                            + "migration deliberately does not create")
                    .isFalse();
        }

        @Test
        @DisplayName("hands the very same clock instance to a component that injects it by type")
        void handsTheSameClockToAnInjectingConsumer() {
            runner.withUserConfiguration(TimestampingConsumer.class).run(context -> {
                assertThat(context).hasNotFailed();
                final TimestampingConsumer consumer = context.getBean(TimestampingConsumer.class);

                assertThat(consumer.clock())
                        .as("one shared instance, not a copy per injection point")
                        .isSameAs(context.getBean(CLOCK_BEAN_NAME, Clock.class));
                assertThat(consumer.clock().getZone()).isEqualTo(ZoneOffset.UTC);
            });
        }
    }

    @Nested
    @DisplayName("Pinning the clock, which is the reason the abstraction exists")
    class PinnedClockDeterminism {
        @Test
        @DisplayName("a pinned clock never advances, so a value derived from it is reproducible")
        void aPinnedClockNeverAdvances() {
            final Clock pinned = Clock.fixed(PINNED, ZoneOffset.UTC);

            assertThat(pinned.instant())
                    .as("first read must be the pinned value exactly")
                    .isEqualTo(PINNED);
            assertThat(pinned.instant())
                    .as("a second read must return the same value; anything else makes every derived "
                            + "assertion depend on when the test ran")
                    .isEqualTo(pinned.instant())
                    .isEqualTo(PINNED);
            assertThat(pinned.getZone())
                    .as("a pinned clock keeps the production zone, so pinning changes the moment and "
                            + "nothing else")
                    .isEqualTo(ZoneOffset.UTC);
        }

        @Test
        @DisplayName("a clock supplied alongside the production one takes precedence for every consumer")
        void aSuppliedClockTakesPrecedence() {
            runner.withUserConfiguration(TimestampingConsumer.class, PinnedClockConfiguration.class)
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        final Clock injected = context.getBean(TimestampingConsumer.class).clock();

                        assertThat(injected.instant())
                                .as("the consumer must observe the pinned moment, never wall time")
                                .isEqualTo(PINNED);
                        assertThat(injected.instant())
                                .as("and must keep observing it on every read")
                                .isEqualTo(PINNED);
                        assertThat(injected.getZone()).isEqualTo(ZoneOffset.UTC);
                    });
        }

        @Test
        @DisplayName("a clock registered under the production bean name replaces it outright, leaving "
                + "one clock still")
        void aClockRegisteredUnderTheProductionNameReplacesIt() {
            runner.withAllowBeanDefinitionOverriding(true)
                    .withBean(CLOCK_BEAN_NAME, Clock.class, () -> Clock.fixed(PINNED, ZoneOffset.UTC))
                    .withUserConfiguration(TimestampingConsumer.class)
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        assertThat(context)
                                .as("substituting by name must replace the clock rather than add a "
                                        + "second one, which is what keeps the wiring honest")
                                .hasSingleBean(Clock.class);

                        final Clock injected = context.getBean(TimestampingConsumer.class).clock();

                        assertThat(injected.instant()).isEqualTo(PINNED);
                        assertThat(injected).isSameAs(context.getBean(CLOCK_BEAN_NAME, Clock.class));
                    });
        }

        @Test
        @DisplayName("the production clock is unaffected once the substitution goes out of scope")
        void theProductionClockSurvivesSubstitution() {
            runner.withUserConfiguration(PinnedClockConfiguration.class).run(context -> {
                assertThat(context).hasNotFailed();
                assertReportsThePresentMoment(
                        context.getBean(CLOCK_BEAN_NAME, Clock.class),
                        "the production bean, still live even while a pinned one is preferred for "
                                + "injection");
            });

            runner.run(context -> {
                assertThat(context).hasNotFailed();
                assertReportsThePresentMoment(
                        context.getBean(Clock.class),
                        "a later context's clock, which the earlier substitution must not have reached");
                assertThat(context.getBean(Clock.class).getZone()).isEqualTo(ZoneOffset.UTC);
            });
        }
    }

    @Nested
    @DisplayName("Schema compatibility: auditing has nothing to write into, and must not acquire it")
    class SchemaCompatibilityGuard {
        @Test
        @DisplayName("no table declares an audit column, so no audited property can ever be mapped to "
                + "one")
        void noTableDeclaresAnAuditColumn() {
            final SchemaColumnCatalog schema = SchemaColumnCatalog.load();
            final List<String> offenders = new ArrayList<>();

            for (final String table : schema.tableNames()) {
                for (final String column : schema.columnNames(table)) {
                    if (FORBIDDEN_AUDIT_COLUMNS.contains(column)) {
                        offenders.add(table + "." + column);
                    }
                }
            }

            assertThat(schema.tableNames())
                    .as("%s must declare tables, otherwise the scan for audit columns is vacuous",
                            MIGRATION_NAME)
                    .isNotEmpty();
            assertThat(offenders)
                    .as("%s declares the audit column or columns %s. Remove them: the schema check runs "
                            + "in %s mode, so an audited property mapped to a column the migration does "
                            + "not create fails the check and aborts start-up, and the migration "
                            + "reproduces the legacy record layouts without adding a field the estate "
                            + "did not have. The forbidden names are %s",
                            MIGRATION_NAME, offenders, EXPECTED_DDL_AUTO, FORBIDDEN_AUDIT_COLUMNS)
                    .isEmpty();
        }

        @Test
        @DisplayName("the schema check runs in validate mode, which is the mechanism that turns an "
                + "invented audit column into a start-up failure")
        void theSchemaCheckRunsInValidateMode() {
            assertThat(shippedProperty(SHARED_CONFIGURATION, DDL_AUTO_KEY))
                    .as("%s must resolve %s to %s. Any other mode would let the provider create or "
                            + "alter a table outside a versioned migration, and the audit-column guard "
                            + "above would stop being enforceable at run time",
                            SHARED_CONFIGURATION, DDL_AUTO_KEY, EXPECTED_DDL_AUTO)
                    .isEqualTo(EXPECTED_DDL_AUTO);
        }

        @Test
        @DisplayName("the JDBC layer is pinned to the same zone the clock publishes, so a timestamp "
                + "column round-trips unchanged")
        void theJdbcLayerIsPinnedToTheClockZone() {
            final String configuredZone = shippedProperty(SHARED_CONFIGURATION, JDBC_TIME_ZONE_KEY);

            assertThat(configuredZone)
                    .as("%s must resolve %s to %s, matching the zone the published clock carries",
                            SHARED_CONFIGURATION, JDBC_TIME_ZONE_KEY, EXPECTED_JDBC_TIME_ZONE)
                    .isEqualTo(EXPECTED_JDBC_TIME_ZONE);
            assertThat(ZoneId.of(configuredZone).getRules().getOffset(PINNED))
                    .as("the configured JDBC zone and the published clock must agree on the offset, "
                            + "otherwise a written timestamp differs from the one read back")
                    .isEqualTo(new JpaAuditConfig().systemClock().getZone().getRules().getOffset(PINNED))
                    .isEqualTo(ZoneOffset.UTC);
        }

        @Test
        @DisplayName("the optimistic-locking column sits on exactly the two versioned tables, which is "
                + "the deliberate counterpart to the absent audit columns")
        void theLockColumnSitsOnExactlyTheVersionedTables() {
            final SchemaColumnCatalog schema = SchemaColumnCatalog.load();
            final List<String> declaring = new ArrayList<>();

            for (final String table : schema.tableNames()) {
                if (schema.columnNames(table).contains(OPTIMISTIC_LOCK_COLUMN)) {
                    declaring.add(table);
                }
            }

            assertThat(declaring)
                    .as("%s must declare %s on exactly %s and on no other table. A version counter "
                            + "replaces the legacy before-and-after image comparison and is a strict "
                            + "improvement on it; an audit column, by contrast, has no legacy "
                            + "counterpart at all and is forbidden",
                            MIGRATION_NAME, OPTIMISTIC_LOCK_COLUMN, OPTIMISTICALLY_LOCKED_TABLES)
                    .containsExactlyInAnyOrderElementsOf(OPTIMISTICALLY_LOCKED_TABLES);

            for (final String table : OPTIMISTICALLY_LOCKED_TABLES) {
                assertThat(schema.declaredType(table, OPTIMISTIC_LOCK_COLUMN))
                        .as("%s.%s must be a counter rather than text, so a version check can compare "
                                + "it numerically", table, OPTIMISTIC_LOCK_COLUMN)
                        .isEqualTo(OPTIMISTIC_LOCK_TYPE);
                assertThat(schema.isNullable(table, OPTIMISTIC_LOCK_COLUMN))
                        .as("%s.%s must not be nullable; an absent counter cannot detect a conflict",
                                table, OPTIMISTIC_LOCK_COLUMN)
                        .isFalse();
            }
        }
    }

    private static void assertReportsThePresentMoment(final Clock clock, final String what) {
        final Instant before = Instant.now();
        final Instant observed = clock.instant();
        final Instant after = Instant.now();

        assertThat(observed)
                .as("%s must report the present moment, so its reading has to fall inside a window "
                        + "measured around the read itself", what)
                .isBetween(before, after);
        assertThat(Clock.fixed(PINNED, ZoneOffset.UTC).instant())
                .as("the window just used must be narrow enough to exclude a frozen clock, or the "
                        + "check above would be satisfied by one")
                .isBefore(before);
    }

    private static List<String> applicationOwnedBeanNames(final String[] beanDefinitionNames) {
        final List<String> owned = new ArrayList<>();
        for (final String name : beanDefinitionNames) {
            if (!name.startsWith(FRAMEWORK_BEAN_PREFIX)) {
                owned.add(name);
            }
        }
        return owned;
    }

    private static String shippedProperty(final String document, final String key) {
        final Map<String, Object> properties = shippedProperties(document);
        final Object value = properties.get(key);
        if (value == null) {
            throw new IllegalStateException(document + " declares no value for " + key
                    + ", so the setting it controls is at a framework default rather than at the "
                    + "value this module requires; it declares " + properties.keySet());
        }
        return String.valueOf(value);
    }

    private static Map<String, Object> shippedProperties(final String document) {
        final ClassPathResource resource = new ClassPathResource(document);
        if (!resource.exists()) {
            throw new IllegalStateException("shipped configuration document is missing: " + document);
        }
        final Map<String, Object> flattened = new LinkedHashMap<>();
        for (final PropertySource<?> source : loadYamlDocuments(document, resource)) {
            if (source instanceof EnumerablePropertySource<?> enumerable) {
                for (final String name : enumerable.getPropertyNames()) {
                    flattened.put(name, enumerable.getProperty(name));
                }
            }
        }
        return flattened;
    }

    private static List<PropertySource<?>> loadYamlDocuments(final String document,
                                                             final ClassPathResource resource) {
        try {
            return new YamlPropertySourceLoader().load(document, resource);
        } catch (final IOException failure) {
            throw new UncheckedIOException(
                    "shipped configuration document is unreadable: " + document, failure);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class TimestampingConsumer {
        private final Clock clock;

        TimestampingConsumer(final Clock clock) {
            this.clock = clock;
        }

        Clock clock() {
            return this.clock;
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class PinnedClockConfiguration {
        @Bean
        @Primary
        Clock pinnedClock() {
            return Clock.fixed(PINNED, ZoneOffset.UTC);
        }
    }
}
