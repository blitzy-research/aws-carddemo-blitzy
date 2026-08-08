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

package com.carddemo.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.lang.reflect.Method;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.carddemo.domain.Card;
import com.carddemo.support.AbstractPostgresIT;
import com.carddemo.support.SensitiveValues;
import com.carddemo.support.TestDataFactory;

/**
 * Container-backed verification of {@link CardRepository} and of the migrated {@code card} table it
 * reads, against a real PostgreSQL 16 server rather than against a stand-in for one.
 *
 * <h2>What this class is responsible for, and what it deliberately leaves to its neighbours</h2>
 * Six properties of the card master can only be established against a live server, and this class owns
 * all six:
 * <ul>
 *   <li>the record layout as the database actually holds it - seven mapped columns at their declared
 *       widths, and the trailing filler bytes of the legacy image mapped to no column at all;</li>
 *   <li>identity - that the key is the business value taken from the record image and that nothing on
 *       this table is server-generated;</li>
 *   <li>the access path - that the planner reaches the owning-account predicate through the named index
 *       that replaces the legacy alternate index, rather than through some other structure that happens
 *       to answer the same question;</li>
 *   <li>referential integrity - that both foreign keys touching this table are enforced by the server
 *       and not merely declared in a migration script;</li>
 *   <li>the seven-row screen fill and its two browse directions, where <em>order</em> is behaviour and
 *       not presentation; and</li>
 *   <li>the optimistic-locking counter, whose whole purpose is to refuse a second writer - a refusal a
 *       stand-in cannot exhibit, because a stand-in has no second persistence context to conflict
 *       with.</li>
 * </ul>
 *
 * <p>Neighbouring classes own the adjacent questions and are not repeated here:
 * {@code CardBrowseRepositoryIT} covers the ordered first-match rule and the tiling of successive
 * keyset windows, {@code ProviderOptimisticLockConflictIT} carries a version conflict onward into the
 * boundary advice that answers it, and {@code AccountRepositoryIT} covers the sibling versioned
 * entity's compare-and-set rewrite.
 *
 * <h2>The record layout this asserts against</h2>
 * The legacy card record is 150 bytes: six persisted fields occupying 91 of them - a 16-byte card
 * number at offset 0, an 11-byte owning-account identifier at 16, a 3-byte verification code at 27, a
 * 50-byte embossed name at 30, a 10-byte expiry date at 80 and a 1-byte active indicator at 90 - and
 * then a 59-byte filler at offset 91 that carries no data and becomes no column, because 91 + 59 is
 * exactly 150. The base cluster keys the record at width 16 from offset 0, which is why the primary key
 * here is the card number itself and never a generated surrogate: the key is the leading substring of
 * the record image, and severing that correspondence would sever byte-level output parity with it.
 *
 * <p>The legacy field name at offset 80 is misspelled in the copybook. The corrected spelling is used
 * for the Java property and for the column while the record mapper's byte offset is unchanged, so the
 * layout stays byte-compatible and the defect is recorded in {@code docs/decision-log.md} instead of
 * being propagated. Nothing in this file repeats the misspelling.
 *
 * <h2>Why the account finder returns a collection and never a single value</h2>
 * The legacy alternate index over the owning-account identifier - key width 11 at offset 16 of the
 * 150-byte record - is declared non-unique with upgrade, given a path definition and then built, and
 * the legacy resource definition registers that path as an online file in its own right. So it carries
 * online traffic and an account may legitimately own several cards. A single-valued finder would raise
 * an incorrect-result-size failure the moment a second card existed, which is a failure the legacy
 * keyed read of a duplicate-bearing path cannot produce. The migration reproduces the index as the
 * non-unique B-tree {@code idx_card_card_acct_id}.
 *
 * <p>That access path is genuinely consumed, so the finder is not dead code: the four legacy programs
 * that declare it become the account-update, account-view, card-detail and card-update services.
 *
 * <h2>Why the seven-row screen is paged on the card number and not on the account</h2>
 * The card-list program declares both the base cluster and the alternate index and then never
 * references the index: every one of its browse operations positions on a retained card number, and the
 * account filter is applied afterwards by a separate paragraph. Its Java counterpart is therefore the
 * inherited {@code findAll(Pageable)} carrying an explicit sort on the card number, plus the two
 * bounded keyset finders for the forward and backward walks. Routing that screen through an
 * account-scoped finder would silently reorder it. The page size of seven comes from the screen's own
 * row table and belongs to the caller: it is supplied through a {@code Pageable} or a {@code Limit} and
 * appears nowhere in the repository interface, which is why this class proves the repository honours
 * whatever bound it is handed rather than a bound of its own.
 *
 * <p>The delivered interface declares four finders and no offset-paged overload of the account finder,
 * for the reason its own contract sets out: the legacy browse retains a record identifier and
 * repositions on it, which is a keyset read rather than an offset page. Nothing is added to that
 * interface here; this class exercises the four it declares plus what is inherited.
 *
 * <h2>Why the context is assembled rather than booted whole</h2>
 * A repository slice is assembled here - the data source, the template and the persistence provider,
 * with this package's repositories and the domain entities registered - which is the arrangement every
 * sibling repository test in this module uses. It supplies exactly what these assertions need: real
 * repository proxies whose derived queries are parsed while the context refreshes, a real transaction
 * manager for the two-writer conflict, and a real template for catalogue and plan inspection.
 *
 * <p>Booting the whole application instead was tried and is <strong>not viable from this package</strong>:
 * the delivered entry point component-scans the base package, the integration tier holds nested
 * repository-registering configurations of its own inside that same package, and a whole-application
 * scan therefore reaches them and fails on a duplicate repository bean definition before any assertion
 * runs. The one class in the module that does boot the whole application has to install a
 * classpath-restricting initializer to avoid exactly that. Assembling the slice is the pattern the
 * module settled on, and it keeps the bootstrap guarantee this class needs intact: a derived-query
 * property name that no longer matches the entity fails the refresh, not a screen.
 *
 * <h2>The exception type this class asserts, and the one it deliberately does not</h2>
 * A stale write is refused by the persistence provider, so what crosses the repository boundary is
 * {@link ObjectOptimisticLockingFailureException} or a subtype of it. That is what is asserted here.
 * {@code com.carddemo.exception.OptimisticLockConflictException} is <em>not</em> asserted, and its
 * absence is the point: the repository declares no lock mode and no transactional demarcation, so the
 * translation into that type happens in the service tier, and the service-tier test is where the
 * wrapped type belongs. Asserting it at this boundary would assert something unreachable from it.
 *
 * <h2>Sensitive values</h2>
 * The card primary account number and the verification code carry no field-level encryption, no
 * tokenisation and no masking in the legacy design, and none is added: that gap is carried forward as
 * an explicit unclosed finding in {@code docs/decision-log.md} rather than closed by unrequested work.
 * Consequently no delivered card number is written into this file as a literal - the one it needs is
 * looked up through the finder under test - no card number or verification code appears in a display
 * name, and nothing here logs or prints either value.
 *
 * <h2>Isolation compared with the legacy baseline</h2>
 * Every application file in the legacy resource definition is declared with uncommitted read
 * integrity, no recovery and no journalling, leaving correctness to a locking update model plus each
 * program's own before-and-after image comparison. This server runs the default read-committed
 * isolation and the migrated table carries a version counter. That is a deliberate strict improvement
 * over the baseline rather than a change in behaviour, and it is recorded as such.
 *
 * <p>Rules: {@code review_rules} reports that no user-specified rules were provided for this project,
 * confirmed by a complete read. No file enters scope by rule, and the absence is not treated as licence
 * to lower the standard - the module's enterprise standards apply instead, which is why this class
 * declares no server of its own, mocks no store, pins every fixture and leaves the shared server
 * exactly as it found it.
 *
 * <p>Provenance: behaviour is that of {@code app/cpy/CVACT02Y.cpy}, {@code app/jcl/CARDFILE.jcl},
 * {@code app/csd/CARDDEMO.CSD}, {@code app/cbl/COCRDLIC.cbl}, {@code app/cbl/COCRDSLC.cbl},
 * {@code app/cbl/COCRDUPC.cbl}, {@code app/cbl/COACTUPC.cbl}, {@code app/cbl/COACTVWC.cbl} and
 * {@code app/cbl/CBACT02C.cbl}, with seeded volumes measured from {@code app/data/ASCII/carddata.txt};
 * all read as read-only reference at checkout SHA
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. No legacy source statement is transcribed.
 *
 * <h2>Where the DATABASE guard is proven, as distinct from the entity guard</h2>
 *
 * <p>This class writes through the shipped entity and repository, so the rules it observes are
 * enforced twice over: once by the entity before the write and once by a named {@code CHECK}
 * constraint in {@code V1__create_schema.sql}. That means a specification at this level passes
 * whether or not the database guard exists, and deleting the guard would break nothing here.
 * ck_card_card_num_width and ck_card_card_acct_id_digits are therefore exercised by RAW JDBC in
 * {@code SchemaConstraintNegativeProofIT}, which bypasses the entity layer entirely and asserts the
 * exact constraint name PostgreSQL reports. That is the shape of the writer these constraints exist
 * to catch - a bulk load or a migration script that never constructs a record image.
 */
@DisplayName("Card repository: record layout, the account access path, the seven-row fill and the "
        + "version counter")
final class CardRepositoryIT extends AbstractPostgresIT {

    /** Rows the delivered reference fixture seeds into this table: 7,550 bytes at 150 bytes each. */
    private static final int SEEDED_CARD_COUNT = 50;

    /** Rows of the card-list display array, from the screen's own seven-occurrence row table. */
    private static final int SCREEN_ROWS = 7;

    /** A narrower bound than the screen's, used to prove the repository honours the caller's figure. */
    private static final int NARROWER_BOUND = 3;

    /** Mapped columns of the 150-byte record: six persisted fields plus the version counter. */
    private static final int MAPPED_COLUMN_COUNT = 7;

    /**
     * A seeded account identifier whose single card this class reads.
     *
     * <p>Chosen away from the identifiers the neighbouring integration classes reserve, so that two
     * classes sharing one server cannot disturb each other's row counts.
     */
    private static final String SEEDED_ACCOUNT = "00000000027";

    /** An eleven-digit account identifier the seed does not carry, for the not-found arms. */
    private static final String ABSENT_ACCOUNT = "97000000999";

    /** Prefix of every card number this class reserves; fifteen characters plus one position digit. */
    private static final String RESERVED_PREFIX = "970000000000000";

    /** Exclusive lower bound of the reserved range, below every reserved card number. */
    private static final String RESERVED_RANGE_START = "9700000000000000";

    /** Exclusive upper bound of the reserved range, above every reserved card number. */
    private static final String RESERVED_RANGE_END = "9700000000000008";

    /** How many extra cards are issued to the seeded account, taking it from one card to eight. */
    private static final int EXTRA_CARDS_ON_ONE_ACCOUNT = 7;

    /** Card number reserved for the version-counter assertions. */
    private static final String VERSIONED_CARD = "9700000000000042";

    /** Card number reserved for the whole-record round trip. */
    private static final String ROUND_TRIP_CARD = "9700000000000055";

    /** Card number reserved for the verification-code round trip. */
    private static final String VERIFICATION_CARD = "9700000000000077";

    /** Card number reserved for the foreign-key refusal; it must never become a row. */
    private static final String ORPHANED_CARD = "9700000000000091";

    /** A sixteen-character key whose leading positions are zeros, reserved for the padding proof. */
    private static final String ZERO_PADDED_CARD = "0000000000000097";

    /** The same value with its leading zeros removed, which must not resolve to the padded row. */
    private static final String UNPADDED_CARD_FORM = "97";

    /** A sixteen-character key the seed does not carry, for the keyed not-found arm. */
    private static final String ABSENT_CARD = "9700000000000000";

    /** Active indicator the winning writer stores, distinct from the losing writer's. */
    private static final String WINNING_INDICATOR = "N";

    /** Active indicator the losing writer attempts, so a silent overwrite would be visible. */
    private static final String LOSING_INDICATOR = "Y";

    /** A three-character verification code whose leading zeros are the point of the assertion. */
    private static final String LEADING_ZERO_CODE = "007";

    /** The same code with its padding removed, which the stored value must not degrade into. */
    private static final String UNPADDED_CODE_FORM = "7";

    /** Ten-character expiry date carried by every reserved fixture, far outside the seeded range. */
    private static final String RESERVED_EXPIRY_DATE = "2099-12-31";

    /** Query naming the table's columns in the order the migration declared them. */
    private static final String COLUMN_NAMES_SQL =
            "SELECT column_name FROM information_schema.columns"
                    + " WHERE table_schema = 'public' AND table_name = 'card'"
                    + " ORDER BY ordinal_position";

    /** Query reading one column's declared character width. */
    private static final String COLUMN_WIDTH_SQL =
            "SELECT character_maximum_length FROM information_schema.columns"
                    + " WHERE table_schema = 'public' AND table_name = 'card' AND column_name = ?";

    /** Query reading one column's declared type. */
    private static final String COLUMN_TYPE_SQL =
            "SELECT data_type FROM information_schema.columns"
                    + " WHERE table_schema = 'public' AND table_name = 'card' AND column_name = ?";

    /** Query reading whether one column admits an absent value. */
    private static final String COLUMN_NULLABILITY_SQL =
            "SELECT is_nullable FROM information_schema.columns"
                    + " WHERE table_schema = 'public' AND table_name = 'card' AND column_name = ?";

    /** Query reading one column's declared default, which is absent for every mapped field. */
    private static final String COLUMN_DEFAULT_SQL =
            "SELECT column_default FROM information_schema.columns"
                    + " WHERE table_schema = 'public' AND table_name = 'card' AND column_name = ?";

    /** Query reading whether one column is generated by the server. */
    private static final String COLUMN_IDENTITY_SQL =
            "SELECT is_identity FROM information_schema.columns"
                    + " WHERE table_schema = 'public' AND table_name = 'card' AND column_name = ?";

    /** Query naming the columns of the table's primary key. */
    private static final String PRIMARY_KEY_COLUMNS_SQL =
            "SELECT kcu.column_name FROM information_schema.table_constraints tc"
                    + " JOIN information_schema.key_column_usage kcu"
                    + " ON kcu.constraint_name = tc.constraint_name"
                    + " AND kcu.table_schema = tc.table_schema"
                    + " WHERE tc.table_schema = 'public' AND tc.table_name = 'card'"
                    + " AND tc.constraint_type = 'PRIMARY KEY' ORDER BY kcu.ordinal_position";

    /**
     * Query naming one index's leading key column.
     *
     * <p>Asked for by key position rather than by joining the column catalogue, because a join on
     * column identity returns the table's own column order and not the index's - and for this index the
     * two differ, which is exactly the difference that matters here.
     */
    private static final String INDEX_LEADING_KEY_SQL =
            "SELECT pg_get_indexdef(i.indexrelid, 1, TRUE) FROM pg_index i"
                    + " JOIN pg_class c ON c.oid = i.indexrelid WHERE c.relname = ?";

    /** Query reading whether one index admits only distinct keys. */
    private static final String INDEX_IS_UNIQUE_SQL =
            "SELECT i.indisunique FROM pg_index i"
                    + " JOIN pg_class c ON c.oid = i.indexrelid WHERE c.relname = ?";

    /** Query reading one index's full definition, which states its key columns in key order. */
    private static final String INDEX_DEFINITION_SQL =
            "SELECT indexdef FROM pg_indexes"
                    + " WHERE schemaname = 'public' AND tablename = 'card' AND indexname = ?";

    /** Query naming the table a foreign key refers to. */
    private static final String FOREIGN_KEY_TARGET_SQL =
            "SELECT DISTINCT ccu.table_name FROM information_schema.table_constraints tc"
                    + " JOIN information_schema.constraint_column_usage ccu"
                    + " ON ccu.constraint_name = tc.constraint_name"
                    + " WHERE tc.constraint_type = 'FOREIGN KEY' AND tc.constraint_name = ?";

    /** Query naming the table a foreign key is declared on. */
    private static final String FOREIGN_KEY_OWNER_SQL =
            "SELECT tc.table_name FROM information_schema.table_constraints tc"
                    + " WHERE tc.constraint_type = 'FOREIGN KEY' AND tc.constraint_name = ?";

    /** Plan request for the owning-account lookup, with the identifier bound rather than assembled. */
    private static final String EXPLAIN_ACCOUNT_LOOKUP_SQL =
            "EXPLAIN (COSTS OFF) SELECT card_num, card_acct_id, card_cvv_cd, card_embossed_name,"
                    + " card_expiration_date, card_active_status, version FROM card"
                    + " WHERE card_acct_id = ?";

    /** Reads the stored keys in ascending order, independently of the persistence provider. */
    private static final String KEYS_ASCENDING_SQL = "SELECT card_num FROM card ORDER BY card_num ASC";

    /** Reads the stored keys in descending order, independently of the persistence provider. */
    private static final String KEYS_DESCENDING_SQL =
            "SELECT card_num FROM card ORDER BY card_num DESC";

    /** Removes one row by key, used only to prove the cross-reference foreign key refuses it. */
    private static final String DELETE_BY_KEY_SQL = "DELETE FROM card WHERE card_num = ?";

    /** Counts cards naming an account the seed did not create; the seed order makes this zero. */
    private static final String CARDS_WITHOUT_ACCOUNT_SQL =
            "SELECT count(*) FROM card c"
                    + " LEFT JOIN account a ON a.acct_id = c.card_acct_id WHERE a.acct_id IS NULL";

    /** Counts cross-reference rows describing a card the seed did not create; also zero. */
    private static final String CROSS_REFERENCES_WITHOUT_CARD_SQL =
            "SELECT count(*) FROM card_cross_reference x"
                    + " LEFT JOIN card c ON c.card_num = x.xref_card_num WHERE c.card_num IS NULL";

    /** Counts the cards a cross-reference row describes, which is every delivered card. */
    private static final String DESCRIBED_CARD_COUNT_SQL =
            "SELECT count(*) FROM card c"
                    + " JOIN card_cross_reference x ON x.xref_card_num = c.card_num";

    /** The non-unique index that replaces the legacy alternate index over the account identifier. */
    private static final String ACCOUNT_INDEX_NAME = "idx_card_card_acct_id";

    /** The foreign key binding a card to the account that owns it. */
    private static final String OWNING_ACCOUNT_FOREIGN_KEY = "fk_card_account";

    /** The foreign key binding a cross-reference row to the card it describes. */
    private static final String CROSS_REFERENCE_FOREIGN_KEY = "fk_card_xref_card";

    /** Creates the test class. */
    CardRepositoryIT() {
    }

    /**
     * Registers this package's repositories and the domain entities against the shared server.
     *
     * <p>Declared here rather than scanned from the entry point, for the reason set out on the class:
     * a whole-application scan of the base package reaches the integration tier's own nested
     * repository-registering configurations and fails on a duplicate bean definition.
     */
    @Configuration(proxyBeanMethods = false)
    @EnableJpaRepositories(basePackageClasses = CardRepository.class)
    @EntityScan(basePackageClasses = Card.class)
    static class RepositoryUnderTest {

        /** Creates the configuration. */
        RepositoryUnderTest() {
        }
    }

    /**
     * Returns the shared server to the delivered seeded state before this class asserts against it.
     *
     * <p>The seeded row count and the seeded version counter are both asserted here, so the starting
     * state has to be the delivered one rather than whatever an earlier class happened to leave. The
     * base class's reset pair is the sanctioned way to obtain that; a context-discarding annotation is
     * forbidden and would in any case reset the wrong thing, since what needs restoring is data on a
     * server shared across contexts.
     *
     * @throws SQLException if the shared server cannot be restored
     */
    @BeforeAll
    static void restoreDeliveredStateBeforeAsserting() throws SQLException {
        restoreSeededState();
    }

    /**
     * Leaves the shared server exactly as it was found, for every class that runs after this one.
     *
     * <p>Each test below already removes its own rows in a {@code finally} arm, so this is a second line
     * of defence rather than the primary one: it guarantees the delivered state even if an assertion
     * fails part-way through a fixture.
     *
     * @throws SQLException if the shared server cannot be restored
     */
    @AfterAll
    static void restoreDeliveredStateAfterAsserting() throws SQLException {
        restoreSeededState();
    }

    // -----------------------------------------------------------------------------------------------
    // The record layout, as the database actually holds it.
    // -----------------------------------------------------------------------------------------------

    /**
     * Proves the migrated table is the 150-byte record image and nothing else.
     *
     * <p>Six persisted fields occupy 91 bytes and the trailing 59 filler bytes carry no data, so the
     * correct column count is seven - the six fields plus the version counter - and a filler column
     * would be a defect rather than a harmless extra. Widths are asserted individually because a
     * bounded character column states a maximum, and a maximum one short of the layout would truncate a
     * value a valid record image can legitimately carry.
     */
    @Test
    @DisplayName("the table carries exactly the seven mapped columns at their declared widths, and the "
            + "trailing filler bytes of the record image become no column at all")
    void theTableCarriesExactlyTheSevenMappedColumns() {
        runner().run(context -> {
            final JdbcTemplate template = context.getBean(JdbcTemplate.class);
            final List<String> columns = template.queryForList(COLUMN_NAMES_SQL, String.class);

            assertThat(columns)
                    .as("six persisted fields in layout order, then the version counter")
                    .containsExactly(
                            "card_num",
                            "card_acct_id",
                            "card_cvv_cd",
                            "card_embossed_name",
                            "card_expiration_date",
                            "card_active_status",
                            "version")
                    .hasSize(MAPPED_COLUMN_COUNT);

            assertThat(declaredWidth(template, "card_num")).as("offset 0, width 16").isEqualTo(16);
            assertThat(declaredWidth(template, "card_acct_id")).as("offset 16, width 11").isEqualTo(11);
            assertThat(declaredWidth(template, "card_cvv_cd")).as("offset 27, width 3").isEqualTo(3);
            assertThat(declaredWidth(template, "card_embossed_name"))
                    .as("offset 30, width 50").isEqualTo(50);
            assertThat(declaredWidth(template, "card_expiration_date"))
                    .as("offset 80, width 10").isEqualTo(10);
            assertThat(declaredWidth(template, "card_active_status"))
                    .as("offset 90, width 1").isEqualTo(1);

            for (final String column : columns) {
                assertThat(nullability(template, column))
                        .as("every mapped field of a fixed-width image is always present: %s", column)
                        .isEqualTo("NO");
            }
        });
    }

    /**
     * Proves the version counter is a whole-number column that starts every row at zero.
     *
     * <p>The counter has no legacy antecedent - it replaces each program's own before-and-after image
     * comparison - so its shape is asserted here rather than derived from a copybook. A server-side
     * default of zero is what lets a bulk load or a migration script insert a row without knowing about
     * optimistic locking at all.
     */
    @Test
    @DisplayName("the version counter is a whole-number column that is never absent and defaults to zero")
    void theVersionCounterIsAWholeNumberColumnDefaultingToZero() {
        runner().run(context -> {
            final JdbcTemplate template = context.getBean(JdbcTemplate.class);

            assertThat(declaredType(template, "version")).isEqualTo("bigint");
            assertThat(nullability(template, "version")).isEqualTo("NO");
            assertThat(declaredDefault(template, "version"))
                    .as("a writer that never constructs an entity still gets a valid starting counter")
                    .isEqualTo("0");
            assertThat(declaredWidth(template, "version"))
                    .as("a whole-number column declares no character width")
                    .isNull();
        });
    }

    /**
     * Proves identity is the business key taken from the record image, never a generated value.
     *
     * <p>The base cluster keys the record at width 16 from offset 0, so the primary key is that same
     * card number. A generated identifier would sever the correspondence between a record image and a
     * row, which is the correspondence byte-level output parity rests on - so the absence of a default
     * and of server generation on the key column is part of the contract, not an implementation detail.
     */
    @Test
    @DisplayName("the primary key is the business card number, with no default and nothing generated")
    void thePrimaryKeyIsTheBusinessKeyAndNothingIsGenerated() {
        runner().run(context -> {
            final JdbcTemplate template = context.getBean(JdbcTemplate.class);

            assertThat(template.queryForList(PRIMARY_KEY_COLUMNS_SQL, String.class))
                    .as("a single-column key, the leading substring of the record image")
                    .containsExactly("card_num");
            assertThat(declaredDefault(template, "card_num"))
                    .as("no default, so nothing supplies a key on the writer's behalf")
                    .isNull();
            assertThat(identityDeclaration(template, "card_num"))
                    .as("no server-generated identity anywhere on this table")
                    .isEqualTo("NO");
        });
    }

    // -----------------------------------------------------------------------------------------------
    // The account access path that replaces the legacy alternate index.
    // -----------------------------------------------------------------------------------------------

    /**
     * Proves every declared finder resolves while the context refreshes.
     *
     * <p>A derived query is parsed against the entity while the repository proxy is built, so a property
     * name that no longer matches fails the refresh rather than a screen. Each finder is then invoked,
     * because parsing a name and executing the statement it produces are two separate opportunities to
     * be wrong.
     */
    @Test
    @DisplayName("every declared finder resolves while the context refreshes, and each one then executes "
            + "against the delivered rows")
    void everyDeclaredFinderResolvesWhileTheContextRefreshes() {
        runner().run(context -> {
            final CardRepository repository = context.getBean(CardRepository.class);

            assertThat(repository)
                    .as("the context refreshed, which is where an unmatched derived-query property name "
                            + "would already have failed")
                    .isNotNull();

            assertThat(repository.findByCardAcctId(SEEDED_ACCOUNT)).hasSize(1);
            assertThat(repository.findFirstByCardAcctIdOrderByCardNumAsc(SEEDED_ACCOUNT)).isPresent();
            assertThat(repository.findByCardNumGreaterThanOrderByCardNumAsc(
                    RESERVED_RANGE_START, Limit.of(SCREEN_ROWS))).isNotNull();
            assertThat(repository.findByCardNumLessThanOrderByCardNumDesc(
                    RESERVED_RANGE_END, Limit.of(SCREEN_ROWS))).isNotEmpty();
        });
    }

    /**
     * Proves the account finder is declared as a collection rather than as a single value.
     *
     * <p>The legacy alternate index is non-unique, so a single-valued finder would raise an
     * incorrect-result-size failure the moment an account owned two cards - a failure the legacy keyed
     * read of a duplicate-bearing path cannot produce. The declared return type is therefore part of the
     * contract and is asserted directly.
     *
     * <p>The reflective read is confined to this test file. The module's unsafe-code audit is scoped to
     * the production tree, where the reflection budget is zero; asserting a declared signature is
     * exactly the kind of contract check that scope exists to permit here and forbid there.
     */
    @Test
    @DisplayName("the account finder is declared to return a collection and not a single value, because "
            + "the access path it reproduces is non-unique")
    void theAccountFinderIsDeclaredAsACollection() {
        runner().run(context -> {
            final Method declared =
                    CardRepository.class.getDeclaredMethod("findByCardAcctId", String.class);

            assertThat(declared.getReturnType())
                    .as("a single-valued declaration would fail as soon as one account owned two cards")
                    .isEqualTo(List.class)
                    .isNotEqualTo(Optional.class);

            // The compiler states the same thing a second way: this assignment stays legal only while
            // the declared type remains a collection.
            final List<Card> matches =
                    context.getBean(CardRepository.class).findByCardAcctId(SEEDED_ACCOUNT);
            assertThat(matches).isNotNull();
        });
    }

    /**
     * Proves the delivered one-to-one relation reads back as exactly one row, at full declared width.
     *
     * <p>The delivered fixture issues one card per account across all fifty accounts, so a seeded
     * account owns exactly one card. The owning-account identifier is read back at its full eleven
     * characters and compared as stored: nothing here adjusts surrounding space, because the schema uses
     * bounded variable-length columns deliberately and a value that needed adjusting on the way out
     * would mean it was stored wrongly on the way in.
     */
    @Test
    @DisplayName("a delivered account owns exactly one card, whose owning-account identifier reads back "
            + "at its full eleven characters exactly as stored")
    void aDeliveredAccountOwnsExactlyOneCardAtFullWidth() {
        runner().run(context -> {
            final CardRepository repository = context.getBean(CardRepository.class);
            final List<Card> matches = repository.findByCardAcctId(SEEDED_ACCOUNT);

            assertThat(matches).hasSize(1);
            final Card only = matches.get(0);
            assertThat(only.getCardAcctId())
                    .as("eleven characters, compared as stored")
                    .hasSize(11)
                    .isEqualTo(SEEDED_ACCOUNT);
            assertThat(only.getCardNum().length()).isEqualTo(16);
            assertThat(only.getVersion()).as("a delivered row starts at the initial counter").isZero();
        });
    }

    /**
     * Proves an account owning no card yields an empty collection rather than an absent one or a failure.
     *
     * <p>Emptiness is the analogue of the legacy not-found response and is not an error at this
     * boundary; whether absence is an error belongs to the service that asked. Returning an absent
     * collection instead would push a presence check into every caller.
     */
    @Test
    @DisplayName("an account owning no card yields an empty collection rather than an absent one, and "
            + "raises nothing")
    void anAccountOwningNoCardYieldsAnEmptyCollection() {
        runner().run(context -> {
            final CardRepository repository = context.getBean(CardRepository.class);

            assertThat(repository.findByCardAcctId(ABSENT_ACCOUNT)).isNotNull().isEmpty();
            assertThat(catchThrowable(() -> repository.findByCardAcctId(ABSENT_ACCOUNT)))
                    .as("a miss is a result, not a failure")
                    .isNull();
        });
    }

    /**
     * Proves the collection finder returns every card of an account once the account owns more than one.
     *
     * <p>This is the assertion the delivered fixture cannot make on its own. The seed is strictly one
     * card per account, so no seeded account can distinguish a collection finder from a single-valued
     * one; the distinction only appears once an account owns several cards, which is the situation the
     * non-unique access path exists to represent. Seven further cards are therefore issued against one
     * delivered account, taking it to eight, and removed again afterwards.
     */
    @Test
    @DisplayName("every card of an account is returned once the account owns eight of them, which the "
            + "one-to-one seed cannot show on its own")
    void everyCardOfAnAccountIsReturnedOnceItOwnsMoreThanOne() {
        runner().run(context -> {
            final CardRepository repository = context.getBean(CardRepository.class);
            final String deliveredCard = deliveredCardNumber(repository);
            try {
                repository.saveAllAndFlush(extraCardsOnDeliveredAccount());

                final List<Card> owned = repository.findByCardAcctId(SEEDED_ACCOUNT);

                assertThat(owned)
                        .as("one delivered card plus the seven issued here")
                        .hasSize(EXTRA_CARDS_ON_ONE_ACCOUNT + 1);
                assertThat(owned).extracting(Card::getCardAcctId).containsOnly(SEEDED_ACCOUNT);
                assertThat(owned).extracting(Card::getCardNum)
                        .as("the delivered card is among them and every key is distinct")
                        .contains(deliveredCard)
                        .doesNotHaveDuplicates()
                        .containsAll(reservedCardNumbers());
                assertThat(repository.findFirstByCardAcctIdOrderByCardNumAsc(SEEDED_ACCOUNT))
                        .get()
                        .extracting(Card::getCardNum)
                        .as("a keyed read of the non-unique path yields the lowest base key, which is "
                                + "the delivered card because every reserved key sorts above it")
                        .isEqualTo(deliveredCard);
            } finally {
                removeReservedRange(repository);
            }

            assertThat(repository.findByCardAcctId(SEEDED_ACCOUNT))
                    .as("the delivered one-to-one relation is restored")
                    .hasSize(1);
        });
    }

    /**
     * Proves the owning-account predicate is answered through the named index, not another structure.
     *
     * <p>Only the plan's <em>shape</em> is inspected - which node the planner chose and which index it
     * named. No cost, no elapsed time and no threshold of any kind is read, because a plan is a
     * statement about structure and a timing is a statement about the machine.
     *
     * <p><strong>Why the whole-relation option is withdrawn first, and why that makes the assertion
     * stronger rather than weaker.</strong> Measured on this table: with the delivered fifty rows the
     * whole relation occupies a single page, so once statistics exist the planner correctly prefers to
     * read that one page outright. An assertion against the unconstrained plan would therefore be an
     * assertion about how small the fixture is, and it would flip between runs depending on whether the
     * statistics collector had visited yet. Withdrawing the whole-relation option asks the precise
     * question this test means to ask - when the planner must reach these rows through an index, is
     * there an index over the owning-account identifier for it to reach them through - and answers it
     * identically whatever the statistics say.
     */
    @Test
    @DisplayName("the owning-account predicate is answered through the index that replaces the legacy "
            + "alternate index, inspected as plan shape only")
    void theOwningAccountPredicateIsAnsweredThroughTheNamedIndex() {
        runner().run(context -> {
            final JdbcTemplate template = context.getBean(JdbcTemplate.class);

            assertThat(template.queryForObject(INDEX_LEADING_KEY_SQL, String.class, ACCOUNT_INDEX_NAME))
                    .as("the index leads on the owning-account identifier at offset 16")
                    .isEqualTo("card_acct_id");
            assertThat(template.queryForObject(INDEX_IS_UNIQUE_SQL, Boolean.class, ACCOUNT_INDEX_NAME))
                    .as("non-unique, because an account may own several cards")
                    .isFalse();
            assertThat(template.queryForObject(INDEX_DEFINITION_SQL, String.class, ACCOUNT_INDEX_NAME))
                    .as("a balanced-tree index over the account identifier, then the base key")
                    .contains("btree (card_acct_id, card_num)");

            final List<String> plan = accountLookupPlan(template, SEEDED_ACCOUNT);

            assertThat(plan).as("the server returned a plan").isNotEmpty();
            assertThat(plan.get(0))
                    .as("the chosen node reads through the named index")
                    .contains("Index")
                    .contains(ACCOUNT_INDEX_NAME);
            assertThat(String.join(" ", plan))
                    .as("and it reaches the rows by the owning-account identifier rather than filtering "
                            + "them after the fact")
                    .contains("Index Cond")
                    .contains("card_acct_id");
        });
    }

    /**
     * Proves a card cannot name an account that does not exist.
     *
     * <p>The legacy estate had no such guarantee: correctness rested on each program reading the account
     * before writing the card. The migration states it once as a foreign key, and a declared constraint
     * the server does not enforce would look identical to an enforced one in every test that never tried
     * to break it - so this one tries.
     */
    @Test
    @DisplayName("a card naming an account that does not exist is refused by the owning-account foreign "
            + "key, and no row is left behind")
    void aCardNamingAnAbsentAccountIsRefused() {
        runner().run(context -> {
            final JdbcTemplate template = context.getBean(JdbcTemplate.class);
            final CardRepository repository = context.getBean(CardRepository.class);

            assertThat(template.queryForObject(FOREIGN_KEY_OWNER_SQL, String.class,
                    OWNING_ACCOUNT_FOREIGN_KEY))
                    .as("the constraint is declared on the card table itself")
                    .isEqualTo("card");
            assertThat(template.queryForObject(FOREIGN_KEY_TARGET_SQL, String.class,
                    OWNING_ACCOUNT_FOREIGN_KEY))
                    .as("and it refers to the account table")
                    .isEqualTo("account");

            try {
                final Card orphaned = reservedCard(ORPHANED_CARD, ABSENT_ACCOUNT, "101",
                        "ORPHANED FIXTURE", RESERVED_EXPIRY_DATE, LOSING_INDICATOR);

                final Throwable raised = catchThrowable(() -> repository.saveAndFlush(orphaned));

                assertThat(raised)
                        .as("the server refuses the write rather than accepting an unresolvable owner")
                        .isInstanceOf(DataIntegrityViolationException.class);
                assertThat(raised).rootCause().hasMessageContaining(OWNING_ACCOUNT_FOREIGN_KEY);
                assertThat(repository.findById(ORPHANED_CARD))
                        .as("and nothing is left behind by the refused write")
                        .isEmpty();
            } finally {
                repository.deleteById(ORPHANED_CARD);
                repository.flush();
            }
        });
    }

    /**
     * Proves the cross-reference foreign key points at this table and is enforced in that direction too.
     *
     * <p>The cross-reference row is what resolves a card to its customer and its account, so it must
     * describe a card that exists. Every delivered card is referenced by one, which means removing a
     * delivered card has to be refused - and the refusal is asserted rather than assumed, because a
     * constraint declared without enforcement would leave a cross-reference pointing at nothing.
     */
    @Test
    @DisplayName("the cross-reference foreign key refers to the card table and refuses the removal of a "
            + "card a cross-reference row still describes")
    void theCrossReferenceForeignKeyPointsAtThisTableAndIsEnforced() {
        runner().run(context -> {
            final JdbcTemplate template = context.getBean(JdbcTemplate.class);
            final CardRepository repository = context.getBean(CardRepository.class);

            assertThat(template.queryForObject(FOREIGN_KEY_OWNER_SQL, String.class,
                    CROSS_REFERENCE_FOREIGN_KEY))
                    .as("the constraint is declared on the cross-reference table")
                    .isEqualTo("card_cross_reference");
            assertThat(template.queryForObject(FOREIGN_KEY_TARGET_SQL, String.class,
                    CROSS_REFERENCE_FOREIGN_KEY))
                    .as("and it refers to this table")
                    .isEqualTo("card");

            final String deliveredCard = deliveredCardNumber(repository);
            final Throwable raised =
                    catchThrowable(() -> template.update(DELETE_BY_KEY_SQL, deliveredCard));

            assertThat(raised)
                    .as("a described card cannot be removed while its cross-reference row remains")
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThat(raised).rootCause().hasMessageContaining(CROSS_REFERENCE_FOREIGN_KEY);
            assertThat(repository.findById(deliveredCard))
                    .as("the refused removal left the row untouched")
                    .isPresent();
            assertThat(repository.count()).isEqualTo(SEEDED_CARD_COUNT);
        });
    }


    // -----------------------------------------------------------------------------------------------
    // The seven-row screen fill, and the direction of the browse that fills it.
    // -----------------------------------------------------------------------------------------------

    /**
     * Proves the screen's first page is seven rows in card-number order over the whole table.
     *
     * <p>The card-list program browses the base cluster by card number and applies its account filter
     * afterwards, so the screen's counterpart here is the inherited page read carrying an explicit sort
     * on the card number - not an account-scoped finder, which would silently reorder it. The page's
     * contents are compared against an independently ordered read of the same keys, so this asserts the
     * right seven rows in the right sequence rather than merely seven rows that happen to be sorted.
     */
    @Test
    @DisplayName("the first page of the card-list screen holds exactly seven rows in card-number order "
            + "and reports the whole table's size")
    void theFirstScreenPageHoldsSevenRowsInCardNumberOrder() {
        runner().run(context -> {
            final CardRepository repository = context.getBean(CardRepository.class);
            final JdbcTemplate template = context.getBean(JdbcTemplate.class);

            final long total = repository.count();
            final List<String> expected = template.queryForList(KEYS_ASCENDING_SQL, String.class);

            final Page<Card> page =
                    repository.findAll(PageRequest.of(0, SCREEN_ROWS, Sort.by("cardNum")));

            assertThat(page.getContent()).hasSize(SCREEN_ROWS);
            assertThat(page.getContent()).extracting(Card::getCardNum)
                    .as("the first seven keys, ascending, and in that order")
                    .containsExactlyElementsOf(expected.subList(0, SCREEN_ROWS));
            assertThat(page.getTotalElements())
                    .as("the page reports the whole relation, not the page")
                    .isEqualTo(total);
            assertThat(page.getTotalPages())
                    .as("seven rows to a screen over %d rows", total)
                    .isEqualTo(pagesOf(total));
            assertThat(page.getNumber()).isZero();
            assertThat(page.hasNext()).isTrue();
        });
    }

    /**
     * Proves a descending sort returns rows in descending order and not merely the same set.
     *
     * <p>The legacy browse reads backwards as well as forwards, and the backward direction is behaviour:
     * a page assembled from rows delivered in the wrong sequence presents the operator with a different
     * screen. Order is therefore asserted as an ordered sequence, never as a set.
     */
    @Test
    @DisplayName("a descending sort returns the rows in descending order, asserted as a sequence rather "
            + "than as a set")
    void aDescendingSortReturnsRowsInDescendingOrder() {
        runner().run(context -> {
            final CardRepository repository = context.getBean(CardRepository.class);
            final JdbcTemplate template = context.getBean(JdbcTemplate.class);
            final List<String> expected = template.queryForList(KEYS_DESCENDING_SQL, String.class);

            final Page<Card> page = repository.findAll(
                    PageRequest.of(0, SCREEN_ROWS, Sort.by(Sort.Direction.DESC, "cardNum")));

            assertThat(page.getContent()).hasSize(SCREEN_ROWS);
            assertThat(page.getContent()).extracting(Card::getCardNum)
                    .containsExactlyElementsOf(expected.subList(0, SCREEN_ROWS));
            assertThat(page.getContent()).extracting(Card::getCardNum)
                    .as("descending, and every neighbouring pair confirms it")
                    .isSortedAccordingTo(Comparator.reverseOrder());
            assertThat(page.getContent().get(0).getCardNum())
                    .as("the highest key heads a descending page")
                    .isGreaterThan(page.getContent().get(SCREEN_ROWS - 1).getCardNum());
        });
    }

    /**
     * Proves the bound on a browse window is the caller's and the direction is the finder's.
     *
     * <p>The page size of seven belongs to the screen, so the repository must accept whatever bound it is
     * handed rather than carrying one: the same finder is asked for a narrower window and returns exactly
     * that many rows. Both directions are exercised over the reserved key range, where the expected
     * sequence is known exactly, so the assertion is about order and not only about size.
     */
    @Test
    @DisplayName("both browse directions honour the bound the caller supplies - seven for the screen, "
            + "and any narrower figure just as faithfully")
    void bothBrowseDirectionsHonourTheCallersBound() {
        runner().run(context -> {
            final CardRepository repository = context.getBean(CardRepository.class);
            try {
                repository.saveAllAndFlush(extraCardsOnDeliveredAccount());
                final List<String> reserved = reservedCardNumbers();

                final List<Card> forward = repository.findByCardNumGreaterThanOrderByCardNumAsc(
                        RESERVED_RANGE_START, Limit.of(SCREEN_ROWS));
                assertThat(forward).hasSize(SCREEN_ROWS);
                assertThat(forward).extracting(Card::getCardNum)
                        .as("the reserved range, ascending, in that order")
                        .containsExactlyElementsOf(reserved);

                final List<Card> backward = repository.findByCardNumLessThanOrderByCardNumDesc(
                        RESERVED_RANGE_END, Limit.of(SCREEN_ROWS));
                assertThat(backward).hasSize(SCREEN_ROWS);
                assertThat(backward).extracting(Card::getCardNum)
                        .as("the same range read backwards is delivered highest key first")
                        .containsExactlyElementsOf(reserved.reversed());

                final List<Card> narrower = repository.findByCardNumGreaterThanOrderByCardNumAsc(
                        RESERVED_RANGE_START, Limit.of(NARROWER_BOUND));
                assertThat(narrower)
                        .as("the bound is the caller's, so a narrower request is honoured exactly")
                        .hasSize(NARROWER_BOUND);
                assertThat(narrower).extracting(Card::getCardNum)
                        .containsExactlyElementsOf(reserved.subList(0, NARROWER_BOUND));
            } finally {
                removeReservedRange(repository);
            }
        });
    }

    // -----------------------------------------------------------------------------------------------
    // The optimistic-locking counter.
    // -----------------------------------------------------------------------------------------------

    /**
     * Proves the counter starts every row at its initial value and advances by exactly one per rewrite.
     *
     * <p>Advancing by one is the whole mechanism: a counter that advanced by two, or that reset, would
     * make a stale write look current. The row is reserved for this test and removed afterwards, because
     * the server is shared with the rest of the integration tier.
     */
    @Test
    @DisplayName("a newly stored row starts at the initial version and advances by exactly one for each "
            + "successful rewrite")
    void aNewRowStartsAtTheInitialVersionAndAdvancesByOne() {
        runner().run(context -> {
            final CardRepository repository = context.getBean(CardRepository.class);
            try {
                final Card stored = repository.saveAndFlush(versionedCard());
                assertThat(stored.getVersion()).as("a fresh row starts at the initial counter").isZero();

                stored.setCardActiveStatus(WINNING_INDICATOR);
                final Card afterFirstRewrite = repository.saveAndFlush(stored);
                assertThat(afterFirstRewrite.getVersion()).isEqualTo(1L);

                afterFirstRewrite.setCardActiveStatus(LOSING_INDICATOR);
                final Card afterSecondRewrite = repository.saveAndFlush(afterFirstRewrite);
                assertThat(afterSecondRewrite.getVersion())
                        .as("one rewrite, one advance - never two and never a reset")
                        .isEqualTo(2L);

                assertThat(repository.findById(VERSIONED_CARD))
                        .get()
                        .extracting(Card::getVersion)
                        .as("and the server holds the same counter a fresh read observes")
                        .isEqualTo(2L);
            } finally {
                repository.deleteById(VERSIONED_CARD);
                repository.flush();
            }
        });
    }

    /**
     * Proves that of two persistence contexts holding the same row, the second write is refused.
     *
     * <p>The row is read twice, each read inside its own unit of work, so both instances are distinct
     * objects carrying the same starting counter - which is the situation two operators on the same
     * screen produce, and the situation a repository stand-in cannot produce at all because it has no
     * second context to conflict with. The first write succeeds and advances the counter; the second is
     * then stale.
     *
     * <p>What crosses this boundary is the provider's failure, translated by the framework into
     * {@link ObjectOptimisticLockingFailureException} or a subtype. The module's own conflict type is
     * deliberately not expected here: the repository declares no lock mode and no transactional
     * demarcation, so the translation into it belongs to the service tier, and the service-tier test is
     * where that wrapped type is asserted.
     *
     * <p>The final read is the assertion that matters most. A conflict that was raised but not enforced
     * would leave the loser's value in the row, so both the stored content and the counter are checked to
     * confirm that only the winner's write survived.
     */
    @Test
    @DisplayName("of two persistence contexts holding the same row, the second write is refused and its "
            + "change is not stored")
    void theSecondOfTwoPersistenceContextsIsRefusedAndItsChangeIsNotStored() {
        runner().run(context -> {
            final CardRepository repository = context.getBean(CardRepository.class);
            final TransactionTemplate template = transactionTemplate(context);
            try {
                repository.saveAndFlush(versionedCard());

                final Card firstWriter = template.execute(status -> loadVersionedCard(repository));
                final Card secondWriter = template.execute(status -> loadVersionedCard(repository));

                assertThat(firstWriter).isNotNull();
                assertThat(secondWriter)
                        .as("two units of work produce two instances, which is what makes a conflict "
                                + "possible at all")
                        .isNotNull()
                        .isNotSameAs(firstWriter);
                assertThat(firstWriter.getVersion()).isZero();
                assertThat(secondWriter.getVersion())
                        .as("both writers start from the same counter")
                        .isEqualTo(firstWriter.getVersion());

                firstWriter.setCardActiveStatus(WINNING_INDICATOR);
                final Card winner = template.execute(status -> repository.saveAndFlush(firstWriter));
                assertThat(winner).isNotNull();
                assertThat(winner.getVersion())
                        .as("the winning write advances the counter")
                        .isEqualTo(1L);

                // The second writer still carries the counter it read, so its write is now stale. It must
                // be refused rather than quietly replacing the value the first writer committed.
                secondWriter.setCardActiveStatus(LOSING_INDICATOR);
                assertThatExceptionOfType(ObjectOptimisticLockingFailureException.class).isThrownBy(
                        () -> template.execute(status -> repository.saveAndFlush(secondWriter)));

                assertThat(repository.findById(VERSIONED_CARD)).get().satisfies(current -> {
                    assertThat(current.getCardActiveStatus())
                            .as("the loser's change was not stored")
                            .isEqualTo(WINNING_INDICATOR);
                    assertThat(current.getVersion())
                            .as("and the counter advanced once, for the winner only")
                            .isEqualTo(1L);
                });
            } finally {
                repository.deleteById(VERSIONED_CARD);
                repository.flush();
            }
        });
    }

    // -----------------------------------------------------------------------------------------------
    // Stored-value fidelity, and the delivered volume.
    // -----------------------------------------------------------------------------------------------

    /**
     * Proves the verification code is held as characters, so its leading zeros cannot be lost.
     *
     * <p>The legacy field is three positions wide and the delivered fixture carries codes whose leading
     * position is a zero. Holding the value as a number would silently discard that position and produce
     * a shorter code that no longer matches the record image, so both the stored value and the column's
     * declared type are asserted.
     */
    @Test
    @DisplayName("the verification code is a three-character value, and its leading zeros survive the "
            + "round trip intact")
    void theVerificationCodeKeepsItsLeadingZeros() {
        runner().run(context -> {
            final CardRepository repository = context.getBean(CardRepository.class);
            final JdbcTemplate template = context.getBean(JdbcTemplate.class);
            try {
                repository.saveAndFlush(reservedCard(VERIFICATION_CARD, SEEDED_ACCOUNT,
                        LEADING_ZERO_CODE, "VERIFICATION FIXTURE", RESERVED_EXPIRY_DATE,
                        LOSING_INDICATOR));

                assertThat(repository.findById(VERIFICATION_CARD)).get().satisfies(stored ->
                        assertThat(stored.getCardCvvCd())
                                .as("three characters, and the same three that were written")
                                .hasSize(3)
                                .isEqualTo(LEADING_ZERO_CODE)
                                .isNotEqualTo(UNPADDED_CODE_FORM));
                assertThat(declaredType(template, "card_cvv_cd"))
                        .as("held as characters, so no numeric conversion can drop a leading position")
                        .isEqualTo("character varying");
            } finally {
                repository.deleteById(VERIFICATION_CARD);
                repository.flush();
            }
        });
    }

    /**
     * Proves the key's leading zeros are part of the key rather than decoration.
     *
     * <p>The key is the leading sixteen bytes of the record image, so a shortened form names a different
     * value and must not resolve to the padded row. Two legacy jobs also sort these same bytes with
     * different typings - one as characters and one as digits - and those two orderings coincide only
     * while every stored key is exactly sixteen characters wide, so the padding is load-bearing beyond
     * identity alone.
     */
    @Test
    @DisplayName("a sixteen-character key whose leading positions are zeros survives the round trip and "
            + "is not reachable from its shortened form")
    void aZeroPaddedKeySurvivesAndIsNotReachableUnpadded() {
        runner().run(context -> {
            final CardRepository repository = context.getBean(CardRepository.class);
            try {
                repository.saveAndFlush(reservedCard(ZERO_PADDED_CARD, SEEDED_ACCOUNT, "111",
                        "PADDED KEY FIXTURE", RESERVED_EXPIRY_DATE, LOSING_INDICATOR));

                assertThat(repository.findById(ZERO_PADDED_CARD)).get().satisfies(stored -> {
                    assertThat(stored.getCardNum().length()).isEqualTo(16);
                    assertThat(SensitiveValues.fingerprint(stored.getCardNum())).isEqualTo(SensitiveValues.fingerprint(ZERO_PADDED_CARD));
                });
                assertThat(repository.findById(UNPADDED_CARD_FORM))
                        .as("the padding belongs to the key, so the shortened form names no row")
                        .isEmpty();
                assertThat(repository.existsById(UNPADDED_CARD_FORM)).isFalse();
            } finally {
                repository.deleteById(ZERO_PADDED_CARD);
                repository.flush();
            }
        });
    }

    /**
     * Proves every persisted property of the record survives a round trip through the store.
     *
     * <p>Each field is given a value distinct from every other field's, so a mapping that crossed two
     * columns cannot pass unnoticed. Values are compared as written, with no adjustment on the way out:
     * the entity is a passive carrier, and re-padding a field to its full record width belongs to the
     * record mapper rather than to persistence.
     */
    @Test
    @DisplayName("every persisted property of the record, and the version counter with it, survives a "
            + "round trip through the store")
    void everyPersistedPropertySurvivesARoundTrip() {
        runner().run(context -> {
            final CardRepository repository = context.getBean(CardRepository.class);
            final String verificationCode = "013";
            final String embossedName = "ROUND TRIP FIXTURE";
            final String expiryDate = "2098-11-30";
            try {
                repository.saveAndFlush(reservedCard(ROUND_TRIP_CARD, SEEDED_ACCOUNT, verificationCode,
                        embossedName, expiryDate, WINNING_INDICATOR));

                assertThat(repository.findById(ROUND_TRIP_CARD)).get().satisfies(stored -> {
                    assertThat(SensitiveValues.fingerprint(stored.getCardNum())).isEqualTo(SensitiveValues.fingerprint(ROUND_TRIP_CARD));
                    assertThat(stored.getCardAcctId()).isEqualTo(SEEDED_ACCOUNT);
                    assertThat(SensitiveValues.fingerprint(stored.getCardCvvCd())).isEqualTo(SensitiveValues.fingerprint(verificationCode));
                    assertThat(stored.getCardEmbossedName()).isEqualTo(embossedName);
                    assertThat(stored.getCardExpirationDate()).isEqualTo(expiryDate);
                    assertThat(stored.getCardActiveStatus()).isEqualTo(WINNING_INDICATOR);
                    assertThat(stored.getVersion()).isZero();
                });
            } finally {
                repository.deleteById(ROUND_TRIP_CARD);
                repository.flush();
            }
        });
    }

    /**
     * Proves the delivered volume and both arms of keyed access.
     *
     * <p>Fifty rows is a measured figure rather than a chosen one: the reference fixture is 7,550 bytes
     * of 150-byte records. Absence is reported as an empty result rather than raised, which is the
     * analogue of the legacy not-found response; whether absence is an error belongs to the caller.
     */
    @Test
    @DisplayName("the delivered fixture seeds fifty rows, a stored key resolves, and a key the seed does "
            + "not carry reports absence rather than raising")
    void theDeliveredVolumeIsFiftyAndKeyedAccessResolvesOrReportsAbsence() {
        runner().run(context -> {
            final CardRepository repository = context.getBean(CardRepository.class);

            assertThat(APPLICATION_TABLES)
                    .as("this table is one of the eleven the schema creates")
                    .contains("card");
            assertThat(repository.count()).isEqualTo(SEEDED_CARD_COUNT);
            assertThat(repository.findAll()).hasSize(SEEDED_CARD_COUNT);

            final String deliveredCard = deliveredCardNumber(repository);
            assertThat(deliveredCard).hasSize(16);
            assertThat(repository.findById(deliveredCard)).isPresent();
            assertThat(repository.existsById(deliveredCard)).isTrue();

            final Optional<Card> absent = repository.findById(ABSENT_CARD);
            assertThat(absent).as("an empty result, never an absent one").isNotNull().isEmpty();
            assertThat(repository.existsById(ABSENT_CARD)).isFalse();
            assertThat(catchThrowable(() -> repository.findById(ABSENT_CARD)))
                    .as("a miss is a result, not a failure")
                    .isNull();
        });
    }

    /**
     * Proves the seed applied this table between the two tables it sits between.
     *
     * <p>Ordering within the seed is not cosmetic: every card names an account, and every cross-reference
     * row names a card, so the account rows must exist before the card rows and the card rows before the
     * cross-reference rows. Rather than reading the script, this asserts the consequence - that no card
     * names an account the seed did not create, and no cross-reference row describes a card it did not.
     */
    @Test
    @DisplayName("the seed applied this table after the account rows that its cards name and before the "
            + "cross-reference rows that describe them")
    void theSeedAppliedThisTableInReferentialOrder() {
        runner().run(context -> {
            final JdbcTemplate template = context.getBean(JdbcTemplate.class);

            assertThat(template.queryForObject(CARDS_WITHOUT_ACCOUNT_SQL, Long.class))
                    .as("every delivered card names an account the seed had already created")
                    .isZero();
            assertThat(template.queryForObject(CROSS_REFERENCES_WITHOUT_CARD_SQL, Long.class))
                    .as("every delivered cross-reference row describes a card already created")
                    .isZero();
            assertThat(template.queryForObject(DESCRIBED_CARD_COUNT_SQL, Long.class))
                    .as("all fifty delivered cards are described, which is why removing one is refused")
                    .isEqualTo(SEEDED_CARD_COUNT);
        });
    }

    // -----------------------------------------------------------------------------------------------
    // Context assembly, fixtures and catalogue helpers.
    // -----------------------------------------------------------------------------------------------

    /**
     * Assembles a repository-only context against the shared migrated server.
     *
     * <p>The data source, the template and the persistence provider, with this package's repositories and
     * the domain entities registered. Mapping validation is on and no schema is generated, so a column
     * renamed or retyped in a migration fails the refresh rather than being quietly reshaped. The
     * open-in-view behaviour is switched off to match every shipped profile.
     *
     * @return a runner over the shared server
     */
    private static ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        DataSourceAutoConfiguration.class,
                        JdbcTemplateAutoConfiguration.class,
                        HibernateJpaAutoConfiguration.class))
                .withUserConfiguration(RepositoryUnderTest.class)
                .withPropertyValues(
                        "spring.datasource.url=" + jdbcUrl(),
                        "spring.datasource.username=" + databaseUser(),
                        "spring.datasource.password=" + databasePassword(),
                        "spring.jpa.hibernate.ddl-auto=validate",
                        "spring.jpa.open-in-view=false");
    }

    /**
     * Builds a transaction template over the assembled context's own transaction manager.
     *
     * <p>Each call to {@code execute} opens one unit of work, which is what gives two reads of the same
     * row two separate persistence contexts.
     *
     * @param  context the running repository context
     * @return a template that opens one unit of work per call
     */
    private static TransactionTemplate transactionTemplate(final ApplicationContext context) {
        return new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
    }

    /**
     * Reads the card number of the account this class reads, through the finder under test.
     *
     * <p>Looked up rather than written down. The delivered card numbers are primary account numbers, and
     * the one value this class needs is obtainable from the store, so none is carried as a literal here.
     *
     * @param  repository the repository under test
     * @return the sixteen-character key of the single card the delivered account owns
     */
    private static String deliveredCardNumber(final CardRepository repository) {
        final List<Card> owned = repository.findByCardAcctId(SEEDED_ACCOUNT);
        assertThat(owned).as("the delivered account must own exactly one card").hasSize(1);
        return owned.get(0).getCardNum();
    }

    /**
     * Builds the seven extra cards this class issues against the delivered account.
     *
     * <p>Every key sits inside the reserved range and above every delivered key, so the ordering the
     * browse assertions expect is known in advance and no delivered row moves.
     *
     * @return the reserved cards, in ascending key order
     */
    private static List<Card> extraCardsOnDeliveredAccount() {
        final List<Card> extras = new ArrayList<>();
        for (int position = 1; position <= EXTRA_CARDS_ON_ONE_ACCOUNT; position++) {
            extras.add(reservedCard(
                    RESERVED_PREFIX + position,
                    SEEDED_ACCOUNT,
                    "10" + position,
                    "MULTI CARD FIXTURE " + position,
                    RESERVED_EXPIRY_DATE,
                    LOSING_INDICATOR));
        }
        return List.copyOf(extras);
    }

    /**
     * Names the keys {@link #extraCardsOnDeliveredAccount()} issues, in ascending order.
     *
     * @return the reserved keys, ascending
     */
    private static List<String> reservedCardNumbers() {
        final List<String> keys = new ArrayList<>();
        for (int position = 1; position <= EXTRA_CARDS_ON_ONE_ACCOUNT; position++) {
            keys.add(RESERVED_PREFIX + position);
        }
        return List.copyOf(keys);
    }

    /**
     * Removes every row of the reserved range, whether or not each one was created.
     *
     * @param repository the repository under test
     */
    private static void removeReservedRange(final CardRepository repository) {
        for (final String key : reservedCardNumbers()) {
            repository.deleteById(key);
        }
        repository.flush();
    }

    /**
     * Builds the row reserved for the version-counter assertions.
     *
     * <p>Every field is valid, so the only failure either version test can observe is the one it is
     * about.
     *
     * @return a complete card carrying the reserved key
     */
    private static Card versionedCard() {
        return reservedCard(VERSIONED_CARD, SEEDED_ACCOUNT, "121", "VERSION FIXTURE",
                RESERVED_EXPIRY_DATE, LOSING_INDICATOR);
    }

    /**
     * Reads the reserved version row, failing loudly if it is absent.
     *
     * <p>Called inside a unit of work so that each call observes the row through its own persistence
     * context, which is what gives two writers the same starting counter.
     *
     * @param  repository the repository under test
     * @return the stored card
     */
    private static Card loadVersionedCard(final CardRepository repository) {
        return repository.findById(VERSIONED_CARD).orElseThrow(() -> new IllegalStateException(
                "the reserved row must be present before two writers can contend for it"));
    }

    /**
     * Builds a complete card from explicit values, so no fixture depends on a default.
     *
     * @param  cardNumber       the sixteen-character key
     * @param  accountId        the eleven-digit owning account
     * @param  verificationCode the three-character code
     * @param  embossedName     the embossed name, written exactly as supplied
     * @param  expiryDate       the ten-character expiry date
     * @param  activeStatus     the one-character active indicator
     * @return the card
     */
    private static Card reservedCard(final String cardNumber, final String accountId,
            final String verificationCode, final String embossedName, final String expiryDate,
            final String activeStatus) {
        return TestDataFactory.card()
                .cardNumber(cardNumber)
                .accountId(accountId)
                .verificationCode(verificationCode)
                .embossedName(embossedName)
                .expirationDate(expiryDate)
                .activeStatus(activeStatus)
                .build();
    }

    /**
     * Reads the plan the server would use for the owning-account lookup, with whole-relation reads
     * withdrawn.
     *
     * <p>Both statements run on one connection, which is why this is written as a connection callback
     * rather than as two template calls: a pooled connection could otherwise serve the second statement
     * from a session that never saw the first. The setting is restored before the connection returns to
     * the pool, in a {@code finally} arm so that a failed plan read cannot leave it behind.
     *
     * @param  template  the template to borrow a connection from
     * @param  accountId the owning-account identifier to plan for, bound rather than assembled
     * @return the plan lines, in the order the server produced them
     */
    private static List<String> accountLookupPlan(final JdbcTemplate template, final String accountId) {
        final ConnectionCallback<List<String>> planReader = connection -> {
            try (Statement withdraw = connection.createStatement()) {
                withdraw.execute("SET enable_seqscan = off");
            }
            try {
                final List<String> lines = new ArrayList<>();
                try (PreparedStatement explain =
                        connection.prepareStatement(EXPLAIN_ACCOUNT_LOOKUP_SQL)) {
                    explain.setString(1, accountId);
                    try (ResultSet rows = explain.executeQuery()) {
                        while (rows.next()) {
                            lines.add(rows.getString(1));
                        }
                    }
                }
                return List.copyOf(lines);
            } finally {
                try (Statement restore = connection.createStatement()) {
                    restore.execute("RESET enable_seqscan");
                }
            }
        };
        final List<String> plan = template.execute(planReader);
        return plan == null ? List.of() : plan;
    }

    /**
     * Reads one column's declared character width.
     *
     * @param  template the template to read the catalogue with
     * @param  column   the column name, bound rather than assembled
     * @return the declared width, or {@code null} for a column that declares none
     */
    private static Integer declaredWidth(final JdbcTemplate template, final String column) {
        return template.queryForObject(COLUMN_WIDTH_SQL, Integer.class, column);
    }

    /**
     * Reads one column's declared type.
     *
     * @param  template the template to read the catalogue with
     * @param  column   the column name, bound rather than assembled
     * @return the declared type as the catalogue names it
     */
    private static String declaredType(final JdbcTemplate template, final String column) {
        return template.queryForObject(COLUMN_TYPE_SQL, String.class, column);
    }

    /**
     * Reads whether one column admits an absent value.
     *
     * @param  template the template to read the catalogue with
     * @param  column   the column name, bound rather than assembled
     * @return the catalogue's own wording for the answer
     */
    private static String nullability(final JdbcTemplate template, final String column) {
        return template.queryForObject(COLUMN_NULLABILITY_SQL, String.class, column);
    }

    /**
     * Reads one column's declared default.
     *
     * @param  template the template to read the catalogue with
     * @param  column   the column name, bound rather than assembled
     * @return the declared default, or {@code null} where none is declared
     */
    private static String declaredDefault(final JdbcTemplate template, final String column) {
        return template.queryForObject(COLUMN_DEFAULT_SQL, String.class, column);
    }

    /**
     * Reads whether one column is generated by the server.
     *
     * @param  template the template to read the catalogue with
     * @param  column   the column name, bound rather than assembled
     * @return the catalogue's own wording for the answer
     */
    private static String identityDeclaration(final JdbcTemplate template, final String column) {
        return template.queryForObject(COLUMN_IDENTITY_SQL, String.class, column);
    }

    /**
     * Counts the screens a relation of the given size fills at the card-list row count.
     *
     * @param  rows the number of rows in the relation
     * @return the number of screens, counting a partly filled last screen
     */
    private static int pagesOf(final long rows) {
        return (int) ((rows + SCREEN_ROWS - 1) / SCREEN_ROWS);
    }

}
