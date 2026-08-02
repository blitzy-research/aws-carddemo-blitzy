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
 * Unit tests for {@link JpaAuditConfig}, the module's single time source.
 *
 * <p>Two contracts are owned here and nowhere else. The first is the clock abstraction: one injected
 * {@link Clock}, fixed to UTC, that every component needing the current date or time receives through
 * its constructor, replacing the date-and-time work area {@code app/cpy/CSDAT01Y.cpy} that all
 * seventeen online programs included textually. Counted with its three sibling wide-fan-out
 * copybooks - the communication area, the screen title and the common message catalog, each likewise
 * included by all seventeen - that family accounts for sixty-eight textual inclusions, every one of
 * which carried its own storage and filled it independently, and all sixty-eight collapse into the
 * one bean asserted below. The second contract is the guard that JPA auditing introduces no
 * schema-breaking audit column, which is what keeps the deliberate decision not to enable auditing
 * from being quietly reversed by a later change.</p>
 *
 * <p>The zone is part of the contract rather than a preference. {@code application.yml} pins the
 * persistence layer's own time zone to UTC, so a clock in any other zone would make a persisted
 * twenty-six-character timestamp text disagree with the value the JDBC layer reads back, and would
 * make the same batch job emit different bytes on two hosts whose regional settings differ - while
 * byte-equivalent output has to be reproducible from the input alone. The zone is consequently
 * asserted as an exact value rather than approximately, and the pairing between the bean's zone and
 * the configured JDBC zone is asserted as a cross-file guard rather than left as a comment. Recorded
 * as decision log entry D-35.</p>
 *
 * <p>The estate carries two timestamp layouts. Both occupy exactly twenty-six characters, both
 * discard the zone offset the current-date intrinsic returns, and both land in twenty-six-character
 * text columns. They are <em>not interchangeable</em>: emitting one where the other is expected
 * yields a value of the right length that is wrong byte for byte, which is the hardest kind of parity
 * defect to notice. The online image [{@code app/cpy/CSDAT01Y.cpy}] is
 * {@code YYYY-MM-DD HH:MM:SS.mmmmmm} - a space between the date and the time, colons between the time
 * parts, and a fraction of six genuine digits. The batch image [{@code app/cbl/CBTRN02C.cbl},
 * identically {@code app/cbl/CBACT04C.cbl}] is {@code YYYY-MM-DD-HH.MM.SS.mm0000} - a hyphen where
 * the online form has a space, dots between the time parts, and a fraction of only two significant
 * digits followed by a four-character constant tail, because the item feeding the fraction is itself
 * only two bytes wide. Neither shape is asserted here: rendering is the obligation of whichever
 * component owns the records the image appears on and is covered by that component's own tests, and
 * the layouts are described in this one place so every owner has a single authoritative description
 * to implement against. What this file establishes is the property those tests depend on, that the
 * clock behind them can be pinned, so an expected image never depends on when a test happened to
 * run.</p>
 *
 * <p>Auditing is off, and that is a strict improvement. {@code V1__create_schema.sql} defines no
 * creation-timestamp, modification-timestamp, creating-principal or modifying-principal column on any
 * application table, and {@code application.yml} runs the provider's schema check in validate mode,
 * so a persistent property mapped to a column the migrations never create aborts start-up rather than
 * degrading quietly. The only columns without a legacy counterpart are the optimistic-locking version
 * columns on the account and card tables, and those exist for a reason worth labelling: every
 * application file in the legacy resource definition was declared with uncommitted read integrity, no
 * recovery and no journaling, with correctness resting solely on the locking update model plus each
 * program's own before-and-after image comparison. PostgreSQL READ COMMITTED combined with a JPA
 * version check is therefore strictly stronger than the verified baseline - an improvement, not a
 * behavioral regression - and {@code docs/decision-log.md} says so explicitly so that a reviewer does
 * not read the stronger isolation as a change in behaviour.</p>
 *
 * <p>What this file deliberately does not assert: migration file layout and the production
 * seed-exclusion mechanism belong to the Flyway configuration suite; the applied-version set, the
 * application table count and the outcome of the provider's schema check belong to the application
 * integration suite; timestamp rendering belongs to the batch and service suites; version-conflict
 * behaviour at run time belongs to the repository integration tier. The schema is read here for
 * exactly two facts: that no audit column exists on any declared table, and that the version column
 * exists on precisely the two tables that carry optimistic locking.</p>
 *
 * <p>It is a plain unit test on the fast tier: no container, no connection, no bound port, and every
 * context built below holds only the class under test plus, where a substitution is being proved, a
 * local test-only configuration. Every expected value is a literal declared in this file, so the
 * oracle is independent of what it judges.</p>
 */
@DisplayName("JPA configuration: one pinnable UTC clock, and no audit column for it to write into")
class JpaAuditConfigTest {

    private static final String CLOCK_BEAN_NAME = "systemClock";

    private static final String CONFIGURATION_BEAN_NAME = "jpaAuditConfig";

    private static final String FRAMEWORK_BEAN_PREFIX = "org.springframework.";

    private static final String SHARED_CONFIGURATION = "application.yml";

    private static final String DDL_AUTO_KEY = "spring.jpa.hibernate.ddl-auto";

    private static final String JDBC_TIME_ZONE_KEY = "spring.jpa.properties.hibernate.jdbc.time_zone";

    /** The only schema-check mode that turns an unmapped column into a start-up failure. */
    private static final String EXPECTED_DDL_AUTO = "validate";

    /** The zone the JDBC layer is pinned to, spelled as {@code application.yml} spells it. */
    private static final String EXPECTED_JDBC_TIME_ZONE = "UTC";

    private static final String MIGRATION_NAME = "db/migration/V1__create_schema.sql";

    /**
     * Column names that framework-managed auditing would want and that the migration must never
     * declare, because none of them has a legacy counterpart.
     */
    private static final List<String> FORBIDDEN_AUDIT_COLUMNS =
            List.of("created_at", "created_by", "modified_at", "modified_by", "last_updated");

    /** The optimistic-locking counter, the one column family that legitimately has no legacy source. */
    private static final String OPTIMISTIC_LOCK_COLUMN = "version";

    private static final String OPTIMISTIC_LOCK_TYPE = "BIGINT";

    /** The only two application tables that carry the optimistic-locking counter. */
    private static final List<String> OPTIMISTICALLY_LOCKED_TABLES = List.of("account", "card");

    /**
     * A pinned instant, chosen as the moment of the upstream release stamp and carrying a non-zero
     * microsecond component so that a consumer accidentally reading wall time is caught rather than
     * coincidentally right.
     */
    private static final Instant PINNED = Instant.parse("2022-07-19T23:15:58.123456Z");

    /**
     * A deliberately non-zero offset, the discriminator that distinguishes the published clock from one
     * built on a business or host zone. It is the offset the upstream release stamp's local time
     * carried, so the value is provenance rather than an arbitrary pick.
     */
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

    /**
     * Asserts that a clock reports the present moment, by bracketing one reading of it between two
     * readings of the system clock taken immediately either side.
     *
     * <p>This deliberately is not a comparison against a fixed calendar instant. Such a comparison
     * carries two defects at once. It is satisfied by a clock frozen at any moment later than the
     * instant chosen, which is exactly the condition it purports to exclude, so it does not
     * discriminate. And its truth is a property of the calendar rather than of the clock, so it decays
     * into a statement that nothing can falsify.</p>
     *
     * <p>A window measured around the reading has neither weakness. It is as narrow as the two
     * surrounding reads allow, and it means the same thing on every future day it runs. Both bounds are
     * inclusive, so a reading that coincides with either edge is accepted; only a reading that lies
     * genuinely outside the interval fails.</p>
     *
     * <p>The window is shown to discriminate in the same breath rather than merely asserted to: a clock
     * frozen at {@link #PINNED} is read through the identical accessor and its reading must fall
     * strictly before the window opens. That companion claim is what keeps the check from being
     * satisfiable by a window so wide it accepts anything.</p>
     *
     * @param clock what to read
     * @param what  how to name the clock in a failure message
     */
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

    /**
     * Returns the bean definition names a slice contributes on top of container infrastructure.
     * Framework-internal definitions are filtered by their fully qualified prefix rather than by an
     * enumerated list, so a framework upgrade that adds or renames an internal processor cannot turn
     * this into a false failure while still leaving a genuinely new application bean visible.
     */
    private static List<String> applicationOwnedBeanNames(final String[] beanDefinitionNames) {
        final List<String> owned = new ArrayList<>();
        for (final String name : beanDefinitionNames) {
            if (!name.startsWith(FRAMEWORK_BEAN_PREFIX)) {
                owned.add(name);
            }
        }
        return owned;
    }

    /**
     * Reads one property out of a shipped configuration document. The document is read from the class
     * path exactly as the running application reads it, so the value asserted is the value that would
     * take effect rather than a copy of it maintained here.
     *
     * @throws IllegalStateException if the document declares no value for the key, which would make an
     *                               equality assertion over it silently vacuous
     */
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

    /**
     * Loads a shipped configuration document into a flat map of key to value. Every YAML document
     * inside the file is merged in declaration order, so a file that later grows a second document is
     * still read in full rather than silently truncated to its first.
     */
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

    /**
     * Loads every YAML document of a class-path resource as a property source.
     */
    private static List<PropertySource<?>> loadYamlDocuments(final String document,
                                                             final ClassPathResource resource) {
        try {
            return new YamlPropertySourceLoader().load(document, resource);
        } catch (final IOException failure) {
            throw new UncheckedIOException(
                    "shipped configuration document is unreadable: " + document, failure);
        }
    }

    /**
     * A minimal stand-in for any component that needs the current moment. It covers two things a
     * bean-definition assertion cannot: that the clock is reachable by constructor injection, and that
     * a pinned clock reaches a real consumer unchanged.
     */
    @Configuration(proxyBeanMethods = false)
    static class TimestampingConsumer {

        /** The injected time source, required on the only construction path. */
        private final Clock clock;

        TimestampingConsumer(final Clock clock) {
            this.clock = clock;
        }

        Clock clock() {
            return this.clock;
        }
    }

    /**
     * Supplies a pinned clock preferred over the configuration's own, which is how a test fixes the
     * moment without touching production wiring.
     */
    @Configuration(proxyBeanMethods = false)
    static class PinnedClockConfiguration {

        /**
         * The pinned clock every consumer receives while this configuration is active.
         */
        @Bean
        @Primary
        Clock pinnedClock() {
            return Clock.fixed(PINNED, ZoneOffset.UTC);
        }
    }
}
