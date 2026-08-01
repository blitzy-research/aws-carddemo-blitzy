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
 * <p>Two contracts are owned here and nowhere else. The first is the <strong>clock
 * abstraction</strong>: one injected {@link Clock}, fixed to UTC, that every component needing the
 * current date or time receives through its constructor. The second is the <strong>guard that JPA
 * auditing introduces no schema-breaking audit column</strong>, which is what keeps the deliberate
 * decision not to enable auditing from being quietly reversed by a later change.
 *
 * <h2>Provenance, cited never copied</h2>
 * The class under test replaces the current-date and current-time work fields of
 * {@code app/cpy/CSDAT01Y.cpy}, whose {@code WS-DATE-TIME} group item is declared on line 17 of that
 * copybook and was included <strong>textually by all seventeen</strong> online programs of the legacy
 * estate. Counted together with its three sibling wide-fan-out copybooks &mdash; the communication
 * area, the screen title and the common message catalog, each likewise included by all seventeen
 * &mdash; that family accounts for <strong>sixty-eight</strong> textual inclusions, every one of which
 * carried its own storage and filled it independently from the language's current-date intrinsic. All
 * sixty-eight collapse into the one bean asserted below. Member names, field names, declared picture
 * widths, paragraph names and line numbers are cited as metadata; no COBOL, JCL, screen-map, copybook
 * or CICS resource-definition source line is reproduced anywhere in this file. Legacy checkout SHA
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19.
 *
 * <h2>Why the zone is part of the contract and not a preference</h2>
 * {@code application.yml} pins the persistence layer's own time zone to UTC through
 * {@code spring.jpa.properties.hibernate.jdbc.time_zone}. A clock in any other zone would therefore
 * make a persisted twenty-six-character timestamp text disagree with the value the JDBC layer reads
 * back, and would make the same batch job emit different bytes on two hosts whose regional settings
 * differ &mdash; while byte-equivalent output has to be reproducible from the input alone. The zone is
 * consequently asserted as an exact value rather than approximately, and the pairing between the
 * bean's zone and the configured JDBC zone is asserted as a cross-file guard rather than left as a
 * comment. Recorded as decision log entry D-35 in {@code docs/decision-log.md}.
 *
 * <h2>The two twenty-six-character timestamp images &mdash; metadata only</h2>
 * The estate carries two timestamp layouts. Both occupy exactly twenty-six characters, both discard
 * the zone offset the current-date intrinsic returns, and both land in {@code VARCHAR(26)} columns.
 * They are <strong>not interchangeable</strong>: emitting one where the other is expected yields a
 * value of the right length that is wrong byte for byte, which is the hardest kind of parity defect to
 * notice.
 * <ul>
 *   <li><strong>Online</strong>, from the {@code WS-TIMESTAMP} view at {@code app/cpy/CSDAT01Y.cpy}
 *       lines 42-55, shaped {@code YYYY-MM-DD HH:MM:SS.mmmmmm}: a <strong>space</strong> between the
 *       date and the time, <strong>colons</strong> between the time parts, and a fraction of
 *       <strong>six genuine digits</strong>, because the trailing item {@code WS-TIMESTAMP-TM-MS6} on
 *       line 55 is declared six digits wide. The neighbouring {@code WS-CURTIME} view on lines 24-28
 *       is coarser still: its {@code WS-CURTIME-MILSEC} item is only two digits wide.</li>
 *   <li><strong>Batch</strong>, from the {@code PIC X(26)} item declared at
 *       {@code app/cbl/CBTRN02C.cbl} line 159 and given its field-by-field shape by the redefinition
 *       that follows it, shaped {@code YYYY-MM-DD-HH.MM.SS.mm0000}: a <strong>hyphen</strong> where
 *       the online form has a space, <strong>dots</strong> between the time parts, and a fraction of
 *       only <strong>two significant digits</strong> followed by a four-character constant tail,
 *       because the item feeding the fraction is itself only two bytes wide. It is built by the
 *       paragraph {@code Z-GET-DB2-FORMAT-TIMESTAMP} at lines 692-705 of that program, which appears
 *       identically in {@code app/cbl/CBACT04C.cbl} from line 613.</li>
 * </ul>
 * Neither shape is asserted here. Rendering is the obligation of whichever component owns the records
 * the image appears on, and it is covered by that component's own tests; the layouts are described in
 * this one place so every owner has a single authoritative description to implement against. What this
 * file proves is the property those tests depend on: that the clock behind them can be
 * <strong>pinned</strong>, so an expected image never depends on when a test happened to run.
 *
 * <h2>Why auditing is off, and why that is a strict improvement</h2>
 * {@code V1__create_schema.sql} defines no creation-timestamp, modification-timestamp,
 * creating-principal or modifying-principal column on any application table, and
 * {@code application.yml} runs the provider's schema check in validate mode &mdash; so a persistent
 * property mapped to a column the migrations never create aborts start-up rather than degrading
 * quietly. The only columns without a legacy counterpart are the optimistic-locking version columns on
 * the account and card tables, and those exist for a reason worth labelling. Every application file in
 * the legacy CICS resource definition was declared with uncommitted read integrity, no recovery and no
 * journaling, with correctness resting solely on the locking update model plus each program's own
 * before-and-after image comparison. PostgreSQL READ COMMITTED combined with a JPA version check is
 * therefore <strong>strictly stronger</strong> than the verified baseline: an improvement, not a
 * behavioral regression, and {@code docs/decision-log.md} says so explicitly so that a reviewer does
 * not read the stronger isolation as a change in behaviour.
 *
 * <h2>What this file deliberately does not assert</h2>
 * Migration file layout and the production seed-exclusion mechanism belong to the Flyway configuration
 * suite; the applied-version set, the application table count and the outcome of the provider's schema
 * check belong to the application integration suite; timestamp rendering belongs to the batch and
 * service suites; version-conflict behaviour at run time belongs to the repository integration tier.
 * The schema is read here for exactly two facts: that no audit column exists on any declared table,
 * and that the version column exists on precisely the two tables that carry optimistic locking.
 *
 * <p>It is a plain unit test on the fast tier: no container, no connection, no bound port, and every
 * context built below holds only the class under test plus, where a substitution is being proved, a
 * local test-only configuration. Every expected value is a literal declared in this file, so the
 * oracle is independent of what it judges.
 */
@DisplayName("JPA configuration: one pinnable UTC clock, and no audit column for it to write into")
class JpaAuditConfigTest {

    /** Bean name the configuration publishes its clock under. */
    private static final String CLOCK_BEAN_NAME = "systemClock";

    /** Bean name the container gives the configuration class itself. */
    private static final String CONFIGURATION_BEAN_NAME = "jpaAuditConfig";

    /** Prefix every framework-internal bean definition name carries in a sliced context. */
    private static final String FRAMEWORK_BEAN_PREFIX = "org.springframework.";

    /** Class-path name of the shared configuration document the three profile overlays inherit. */
    private static final String SHARED_CONFIGURATION = "application.yml";

    /** Property key selecting how the persistence provider treats the existing schema. */
    private static final String DDL_AUTO_KEY = "spring.jpa.hibernate.ddl-auto";

    /** Property key pinning the time zone the JDBC layer reads and writes timestamps in. */
    private static final String JDBC_TIME_ZONE_KEY = "spring.jpa.properties.hibernate.jdbc.time_zone";

    /** The only schema-check mode that turns an unmapped column into a start-up failure. */
    private static final String EXPECTED_DDL_AUTO = "validate";

    /** The zone the JDBC layer is pinned to, spelled as {@code application.yml} spells it. */
    private static final String EXPECTED_JDBC_TIME_ZONE = "UTC";

    /** Class-path name of the schema-creating migration, used only in failure diagnostics. */
    private static final String MIGRATION_NAME = "db/migration/V1__create_schema.sql";

    /**
     * Column names that framework-managed auditing would want and that the migration must never
     * declare, because none of them has a legacy counterpart.
     */
    private static final List<String> FORBIDDEN_AUDIT_COLUMNS =
            List.of("created_at", "created_by", "modified_at", "modified_by", "last_updated");

    /** The optimistic-locking counter, the one column family that legitimately has no legacy source. */
    private static final String OPTIMISTIC_LOCK_COLUMN = "version";

    /** Declared type of the optimistic-locking counter. */
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
     * A deliberately non-zero offset, used as the discriminator that proves the published clock is not
     * built from a business or host zone. It is the offset the upstream release stamp's local time
     * carried, so the value is provenance rather than an arbitrary pick.
     */
    private static final ZoneOffset NON_UTC_OFFSET = ZoneOffset.ofHours(-5);

    /** A context holding nothing but the class under test. */
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

            final Instant first = clock.instant();
            final Instant second = clock.instant();

            assertThat(first)
                    .as("a live clock reports a moment after the release the estate was captured at; a "
                            + "clock frozen at a fixed value would not")
                    .isAfter(PINNED);
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
                assertThat(context.getBean(CLOCK_BEAN_NAME, Clock.class).instant())
                        .as("the production bean is still the live clock even while a pinned one is "
                                + "preferred for injection")
                        .isAfter(PINNED);
            });

            runner.run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context.getBean(Clock.class).instant())
                        .as("a later context must be unaffected by the earlier substitution")
                        .isAfter(PINNED);
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
     * Returns the bean definition names a slice contributes on top of container infrastructure.
     *
     * <p>Framework-internal definitions are filtered by their fully qualified prefix rather than by an
     * enumerated list, so a framework upgrade that adds or renames an internal processor cannot turn
     * this into a false failure while still leaving a genuinely new application bean visible.</p>
     *
     * @param beanDefinitionNames every name the context holds
     * @return the names the application itself contributed, in encounter order
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
     * Reads one property out of a shipped configuration document.
     *
     * <p>The document is read from the class path exactly as the running application reads it, so the
     * value asserted is the value that would take effect rather than a copy of it maintained here.</p>
     *
     * @param document the class-path name of the document
     * @param key      the fully qualified property key
     * @return the declared value as text
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
     * Loads a shipped configuration document into a flat map of key to value.
     *
     * <p>Every YAML document inside the file is merged in declaration order, so a file that later grows
     * a second document is still read in full rather than silently truncated to its first.</p>
     *
     * @param document the class-path name of the document
     * @return every declared property, flattened; never {@code null}
     * @throws IllegalStateException if the document is absent from the class path
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
     *
     * @param document the class-path name, used as the source name and in diagnostics
     * @param resource the resolved resource
     * @return one property source per YAML document inside the file
     * @throws UncheckedIOException if the resource cannot be read
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
     * A minimal stand-in for any component that needs the current moment.
     *
     * <p>It exists to prove two things a bean-definition assertion cannot: that the clock is reachable
     * by constructor injection, and that a pinned clock reaches a real consumer unchanged.</p>
     */
    @Configuration(proxyBeanMethods = false)
    static class TimestampingConsumer {

        /** The injected time source, required on the only construction path. */
        private final Clock clock;

        /**
         * Injects the module's clock.
         *
         * @param clock the injected time source
         */
        TimestampingConsumer(final Clock clock) {
            this.clock = clock;
        }

        /**
         * Returns the injected clock.
         *
         * @return the clock this consumer was built with
         */
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
         *
         * @return a fixed UTC clock reading the pinned instant
         */
        @Bean
        @Primary
        Clock pinnedClock() {
            return Clock.fixed(PINNED, ZoneOffset.UTC);
        }
    }
}
