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

import com.carddemo.domain.Account;
import com.carddemo.support.AbstractPostgresIT;
import com.carddemo.support.TestDataFactory;
import jakarta.persistence.TransactionRequiredException;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Integration test for {@link AccountRepository} against real PostgreSQL 16, covering the migrated row
 * shape, the decimal contract of the five monetary columns, the constraint surface of the
 * {@code account} table, and - as its headline responsibility - optimistic locking.
 *
 * <h2>Why optimistic locking is proven here and not somewhere else</h2>
 * The account entity and the card entity are the only two in the module that carry a version
 * attribute, because they are the only two records the legacy tier rewrote in place after comparing a
 * before image against an after image. The single explicit rollback of the entire estate sits on the
 * online account-update path, so this table is where a lost update would have been silently possible
 * and where the replacement guarantee therefore has to be demonstrated rather than assumed.
 *
 * <p><strong>What this class asserts, and what it deliberately does not.</strong> A conflict observed
 * at the repository boundary is the persistence provider's own failure, surfaced through the framework
 * as {@link ObjectOptimisticLockingFailureException}. The module's domain-specific
 * {@code OptimisticLockConflictException} is a leaf type that imports neither the framework nor the
 * persistence API, and the translation into it happens in the SERVICE layer - this repository declares
 * no lock mode for its online reads, no transaction demarcation of its own for them, and no exception
 * mapping. Asserting the domain type here would therefore assert something unreachable from this
 * boundary. The wrapped type is the subject of the service-layer and boundary tests instead; this
 * class pins the untranslated failure those tests then translate, so that the two halves of the
 * contract cannot drift apart unnoticed.
 *
 * <h2>Why the key is the business key</h2>
 * The legacy file description splits the 300-byte record into an 11-byte account identifier followed
 * by a 289-byte data remainder, and the cluster definition fixes that same key width at offset 0 over
 * the same record size. The key is therefore the leading substring of the record image rather than a
 * separate attribute, which is why the JPA identity is the business key itself and never a generated
 * surrogate: the schema creates no sequence, no auto-numbered column and no generated column for this
 * table, and this class reads the catalogue back to prove it. The identifier is an eleven-character
 * zero-filled lexeme, so its leading zeros are contractual and a padded key is not the same key as its
 * unpadded form.
 *
 * <h2>What the seeded population is, and why its uniformity matters</h2>
 * The reference seed loads exactly fifty rows, after the customer table and before the card table, and
 * under the non-production profiles only. Two of its measured characteristics are load-bearing rather
 * than incidental. Every one of the fifty rows carries a group identifier of exactly ten spaces, which
 * matches none of the three seeded disclosure-group identifiers, so every seeded account drives the
 * interest program's default-group fallback rather than a direct rate hit. And every one of the fifty
 * carries the same value in the postcode position, which reads like a group identifier and is not one -
 * it is a fixture characteristic at the postcode offset, and it is neither normalised nor rejected.
 * Both are asserted verbatim: nothing here trims or strips a fixed-width value, because the schema uses
 * bounded variable-length columns precisely so that padding survives a round trip, where a blank-padded
 * fixed-length column would have erased the distinction these assertions rest on.
 *
 * <h2>Structural facts a reader should not have to rediscover</h2>
 * The group identifier is only a non-unique leading portion of the composite disclosure-group key,
 * recurring seventeen times per group across the fifty-one seeded disclosure rows, so it cannot be a
 * foreign key and none is defined; a constraint would additionally reject the ten-space value on all
 * fifty accounts. No foreign key originates from this table at all. Those that exist point AT it, and
 * this class proves the two the migration plan names are enforced by watching a child insert be
 * refused. The table is also one of the eight file resources the legacy online transaction manager
 * registers, so it carries a genuine online access path as well as a batch one - which is why the
 * conflict guarantee matters at all. No JPA association is declared anywhere in this module, and the
 * view-scoped persistence context is disabled, so nothing here needs or may add one.
 *
 * <h2>Environment</h2>
 * The PostgreSQL server, its migration to the head of the delivered set, and the data-source
 * properties this context is assembled from all come from {@link AbstractPostgresIT}, which is the
 * module's single owner of that container. Following that contract, this class declares no container,
 * no container extension, no data-source property source and no context-discarding annotation; it
 * declares only its own {@code @SpringBootTest} slice. Because several of the tests below genuinely
 * commit, the delivered rows are put back through the base class's own reset helper rather than by
 * discarding the context.
 *
 * <p>Provenance: the account copybook, its cluster definition, the sequential reader's file section,
 * the online update program, the posting program, the interest program and the CICS resource
 * definition, read at checkout SHA {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release
 * stamp {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. Every legacy fact above is cited as
 * metadata - a width, an offset, a record length, a row count or a resource name. No legacy source
 * statement is transcribed.
 *
 * <h2>Where the DATABASE guard is proven, as distinct from the entity guard</h2>
 *
 * <p>This class writes through the shipped entity and repository, so the rules it observes are
 * enforced twice over: once by the entity before the write and once by a named {@code CHECK}
 * constraint in {@code V1__create_schema.sql}. That means a specification at this level passes
 * whether or not the database guard exists, and deleting the guard would break nothing here.
 * ck_account_acct_id_digits are therefore exercised by RAW JDBC in
 * {@code SchemaConstraintNegativeProofIT}, which bypasses the entity layer entirely and asserts the
 * exact constraint name PostgreSQL reports. That is the shape of the writer these constraints exist
 * to catch - a bulk load or a migration script that never constructs a record image.
 */
@SpringBootTest(classes = AccountRepositoryIT.AccountPersistenceSlice.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {"spring.main.banner-mode=off",
                "spring.jpa.hibernate.ddl-auto=validate",
                "spring.jpa.open-in-view=false"})
@DisplayName("Account repository: migrated row shape, decimal contract, constraints and version guard")
final class AccountRepositoryIT extends AbstractPostgresIT {

    /** A delivered account identifier, the first row of the reference seed. */
    private static final String SEEDED_ACCOUNT = "00000000001";

    /** Account key reserved for the posting-rewrite test and absent from the delivered seed. */
    private static final String RESERVED_ACCOUNT = "99000000042";

    /** An absent key used to exercise the legacy invalid-key outcome and the refused child inserts. */
    private static final String ABSENT_ACCOUNT = "99000000043";

    /** Account key reserved for the thirteen-property round trip. */
    private static final String ROUND_TRIP_ACCOUNT = "99000000045";

    /** Account key reserved for the decimal-fidelity assertions. */
    private static final String DECIMAL_ACCOUNT = "99000000046";

    /** Account key reserved for the version-advance assertions. */
    private static final String VERSION_ACCOUNT = "99000000047";

    /** Account key reserved for the two-context conflict. */
    private static final String CONFLICT_ACCOUNT = "99000000048";

    /**
     * A reserved key whose leading zeros are significant. Outside the seeded range, which runs from
     * {@code 00000000001} to {@code 00000000050}, so it cannot collide with a delivered row.
     */
    private static final String LEADING_ZERO_ACCOUNT = "00099000049";

    /** The same digits without their leading zeros: a different key, and never a match. */
    private static final String UNPADDED_LEADING_ZERO_ACCOUNT = "99000049";

    /** Zero-based position of the expiration-date field within the record layout. */
    private static final int EXPIRATION_FIELD_INDEX = 6;

    /** The correctly spelled expiration-date column, which the migration declares and the entity maps. */
    private static final String EXPIRATION_COLUMN = "acct_expiration_date";

    /** Record length of the account image in bytes. */
    private static final int RECORD_LENGTH = 300;

    /** Bytes the twelve persisted fields occupy, from offset zero. */
    private static final int PERSISTED_DATA_LENGTH = 122;

    /** Bytes of trailing filler, beginning at {@link #PERSISTED_DATA_LENGTH} and never persisted. */
    private static final int FILLER_LENGTH = 178;

    /** Width of the business key, at offset zero of the record image. */
    private static final int KEY_WIDTH = 11;

    /** Width of the data remainder the legacy file section declares after the key. */
    private static final int DATA_REMAINDER_WIDTH = 289;

    /** Persisted fields the record layout carries, filler excluded. */
    private static final int PERSISTED_FIELD_COUNT = 12;

    /** Columns the migrated table carries: the twelve persisted fields plus the version counter. */
    private static final int MAPPED_COLUMN_COUNT = 13;

    /** Fraction digits every monetary column holds. */
    private static final int MONEY_SCALE = 2;

    /** Version a freshly inserted or freshly seeded row carries. */
    private static final long INITIAL_VERSION = 0L;

    /**
     * The thirteen mappings, in declaration order, as the server itself reports them.
     *
     * <p>Held as one ordered list rather than thirteen separate assertions so that a column added,
     * removed, renamed, retyped, resized, reordered or made nullable is a single legible failure. The
     * five monetary columns are twelve digits of which two are fractional, matching the zoned-decimal
     * source fields exactly; the version counter is the only column with no legacy antecedent.</p>
     */
    private static final List<String> EXPECTED_COLUMN_CONTRACT = List.of(
            "1 acct_id character varying(11) NOT NULL",
            "2 acct_active_status character varying(1) NOT NULL",
            "3 acct_curr_bal numeric(12,2) NOT NULL",
            "4 acct_credit_limit numeric(12,2) NOT NULL",
            "5 acct_cash_credit_limit numeric(12,2) NOT NULL",
            "6 acct_open_date character varying(10) NOT NULL",
            "7 " + EXPIRATION_COLUMN + " character varying(10) NOT NULL",
            "8 acct_reissue_date character varying(10) NOT NULL",
            "9 acct_curr_cyc_credit numeric(12,2) NOT NULL",
            "10 acct_curr_cyc_debit numeric(12,2) NOT NULL",
            "11 acct_addr_zip character varying(10) NOT NULL",
            "12 acct_group_id character varying(10) NOT NULL",
            "13 version bigint NOT NULL");

    /**
     * Zero-based offset and width of each persisted field, in record order.
     *
     * <p>THE FIELD-ORDER TRAP this guards: the five monetary fields are not contiguous. The balance and
     * the two credit limits occupy 12 through 47, the three dates occupy 48 through 77, and the two
     * cycle amounts occupy 78 through 101. Collecting all five amounts together still yields a
     * three-hundred byte image, which is what makes the mistake silent, so the placement is asserted
     * positionally rather than left implied.</p>
     */
    private static final List<String> EXPECTED_FIELD_PLACEMENT = List.of(
            "0/11", "11/1", "12/12", "24/12", "36/12", "48/10",
            "58/10", "68/10", "78/12", "90/12", "102/10", "112/10");

    /**
     * Every foreign key that points AT this table, with the child table it originates from.
     *
     * <p>The migration plan names two of the three by name, and both are additionally proven to be
     * enforced rather than merely declared. The third is the cross-reference row's account column,
     * which points here for the same reason. None of the three originates here.</p>
     */
    private static final List<String> EXPECTED_INBOUND_FOREIGN_KEYS = List.of(
            "fk_card_account card",
            "fk_card_xref_account card_cross_reference",
            "fk_trancat_balance_account transaction_category_balance");

    /** The only index this table carries: the unique tree backing its primary key. */
    private static final String EXPECTED_ONLY_INDEX =
            "pk_account CREATE UNIQUE INDEX pk_account ON public.account USING btree (acct_id)";

    /** The foreign key the child card row must be refused by. */
    private static final String CARD_ACCOUNT_FOREIGN_KEY = "fk_card_account";

    /** The foreign key the child category-balance row must be refused by. */
    private static final String CATEGORY_BALANCE_ACCOUNT_FOREIGN_KEY = "fk_trancat_balance_account";

    /**
     * A sixteen-character card number for the refused child insert. Valid in width and in class, so the
     * only thing that can refuse the row is the foreign key.
     */
    private static final String CHILD_CARD_NUMBER = "9900000000000045";

    /** A two-character transaction type code for the refused child insert. */
    private static final String CHILD_TYPE_CODE = "01";

    /** A four-character transaction category code for the refused child insert. */
    private static final String CHILD_CATEGORY_CODE = "0001";

    /**
     * Reads the shape of the table back from the server, one descriptor per column in declared order.
     *
     * <p>Rendered by the server's own type formatter rather than assembled from catalogue integers, so
     * a descriptor reads exactly as the declaration does and cannot disagree with it through a
     * formatting choice made here.</p>
     */
    private static final String COLUMN_CONTRACT_SQL = """
            SELECT attribute.attnum || ' ' || attribute.attname || ' '
                   || format_type(attribute.atttypid, attribute.atttypmod)
                   || CASE WHEN attribute.attnotnull THEN ' NOT NULL' ELSE ' NULL' END
              FROM pg_attribute attribute
             WHERE attribute.attrelid = 'public.account'::regclass
               AND attribute.attnum > 0
               AND NOT attribute.attisdropped
             ORDER BY attribute.attnum
            """;

    /** Reads the default expression of one column of this table. */
    private static final String COLUMN_DEFAULT_SQL = """
            SELECT pg_get_expr(fallback.adbin, fallback.adrelid)
              FROM pg_attrdef fallback
              JOIN pg_attribute attribute
                ON attribute.attrelid = fallback.adrelid
               AND attribute.attnum = fallback.adnum
             WHERE fallback.adrelid = 'public.account'::regclass
               AND attribute.attname = ?
            """;

    /** Reads the primary-key columns of this table, in key order. */
    private static final String PRIMARY_KEY_SQL = """
            SELECT usage.column_name
              FROM information_schema.table_constraints constraints
              JOIN information_schema.key_column_usage usage
                ON usage.constraint_name = constraints.constraint_name
               AND usage.table_schema = constraints.table_schema
             WHERE constraints.table_schema = 'public'
               AND constraints.table_name = 'account'
               AND constraints.constraint_type = 'PRIMARY KEY'
             ORDER BY usage.ordinal_position
            """;

    /**
     * Counts the columns of this table that would be filled by the database rather than by the record
     * image: an auto-numbered column, a generated column, or one defaulted from a sequence.
     *
     * <p>Scoped to this table on purpose. The schema-wide sequence count is not zero and must not be
     * asserted to be: the batch framework provisions three sequences of its own for its metadata, and
     * counting those would fail an assertion about the record schema for a reason that has nothing to do
     * with it.</p>
     */
    private static final String SURROGATE_KEY_SQL = """
            SELECT count(*)
              FROM information_schema.columns
             WHERE table_schema = 'public'
               AND table_name = 'account'
               AND (is_identity = 'YES'
                    OR is_generated <> 'NEVER'
                    OR coalesce(column_default, '') LIKE 'nextval%')
            """;

    /** Reads every foreign key that ORIGINATES from this table. */
    private static final String OUTBOUND_FOREIGN_KEY_SQL = """
            SELECT constraint_definition.conname || ' -> '
                   || constraint_definition.confrelid::regclass::text
              FROM pg_constraint constraint_definition
             WHERE constraint_definition.conrelid = 'public.account'::regclass
               AND constraint_definition.contype = 'f'
             ORDER BY constraint_definition.conname
            """;

    /** Reads every constraint of any kind that joins this table to the disclosure-group table. */
    private static final String DISCLOSURE_GROUP_REFERENCE_SQL = """
            SELECT constraint_definition.conname
              FROM pg_constraint constraint_definition
             WHERE constraint_definition.conrelid = 'public.account'::regclass
               AND constraint_definition.confrelid = 'public.disclosure_group'::regclass
             ORDER BY constraint_definition.conname
            """;

    /** Reads every foreign key that POINTS AT this table, with its child table. */
    private static final String INBOUND_FOREIGN_KEY_SQL = """
            SELECT constraint_definition.conname || ' '
                   || constraint_definition.conrelid::regclass::text
              FROM pg_constraint constraint_definition
             WHERE constraint_definition.confrelid = 'public.account'::regclass
               AND constraint_definition.contype = 'f'
             ORDER BY constraint_definition.conname
            """;

    /** Reads every index on this table, with its full definition. */
    private static final String INDEX_SQL = """
            SELECT indexname || ' ' || indexdef
              FROM pg_indexes
             WHERE schemaname = 'public'
               AND tablename = 'account'
             ORDER BY indexname
            """;

    /** Inserts a child card row, which can only succeed against an account that exists. */
    private static final String INSERT_CHILD_CARD_SQL = """
            INSERT INTO card (card_num, card_acct_id, card_cvv_cd, card_embossed_name,
                              card_expiration_date, card_active_status)
            VALUES (?, ?, ?, ?, ?, ?)
            """;

    /** Inserts a child category-balance row, which can only succeed against an account that exists. */
    private static final String INSERT_CHILD_CATEGORY_BALANCE_SQL = """
            INSERT INTO transaction_category_balance (trancat_acct_id, trancat_type_cd, trancat_cd,
                                                      tran_cat_bal)
            VALUES (?, ?, ?, ?)
            """;

    /** The repository under test, exercised through its inherited and its two declared operations. */
    @Autowired
    private AccountRepository repository;

    /** The real transaction manager, from which each unit of work below is opened. */
    @Autowired
    private PlatformTransactionManager transactionManager;

    /** Used only to read the catalogue back; never to read or write an account row. */
    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** Creates the test class. */
    AccountRepositoryIT() {
        // Intentionally empty: every collaborator is injected.
    }

    /**
     * The persistence slice this class boots: the migrated repositories and entities over the shared
     * server, and nothing else.
     *
     * <p>Exactly three auto-configurations are imported - the data source, the persistence provider and
     * the template used to read the catalogue - which is the same set the sibling repository tests
     * assemble. The remainder of the application is deliberately absent: a web tier, a message tier and
     * a metrics tier would each add a prerequisite this test does not need, and one of them would add an
     * external service the repository contract has nothing to do with. The persistence provider supplies
     * the real transaction manager, so the conflict below is observed through the same machinery the
     * application uses.</p>
     */
    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration({DataSourceAutoConfiguration.class,
            JdbcTemplateAutoConfiguration.class,
            HibernateJpaAutoConfiguration.class})
    @EnableJpaRepositories(basePackageClasses = AccountRepository.class)
    @EntityScan(basePackageClasses = Account.class)
    static class AccountPersistenceSlice {

        /** Creates the slice. */
        AccountPersistenceSlice() {
            // Intentionally empty: the slice contributes no bean of its own.
        }
    }

    /**
     * Puts the delivered rows back before the class runs.
     *
     * <p>Two of the assertions below are about the delivered population itself - that it is exactly
     * fifty rows, and that every one of them still stands at the initial version - and the server is
     * shared with every other integration test in the run. A batch job that advanced an account's
     * version, or a test that left a row behind, would otherwise make those assertions fail for a reason
     * unrelated to this repository. This is the base class's own reset helper rather than a
     * context-discarding annotation, which the base class forbids for the reason recorded there.</p>
     *
     * @throws SQLException if the delivered rows cannot be restored
     */
    @BeforeAll
    static void restoreDeliveredRowsBeforeTheClass() throws SQLException {
        restoreSeededState();
    }

    /**
     * Puts the delivered rows back after the class runs.
     *
     * <p>Several tests below commit, and one of them deliberately leaves a conflict behind, so the class
     * hands the shared server on in exactly the state a fresh migration leaves it in rather than in
     * whatever state its last test produced.</p>
     *
     * @throws SQLException if the delivered rows cannot be restored
     */
    @AfterAll
    static void restoreDeliveredRowsAfterTheClass() throws SQLException {
        restoreSeededState();
    }

    /**
     * The migrated row shape: thirteen mappings, twelve byte ranges, one business key and no surrogate.
     *
     * <p>A record layout survives a migration only if every field kept its type, its width and its
     * place. These assertions read the shape back from the server rather than from the migration text,
     * so a mapping that drifted from the schema, or a schema that drifted from the layout, is caught at
     * the only point both are visible at once.</p>
     */
    @Nested
    @DisplayName("the migrated row shape")
    final class TheMigratedRowShape {

        /** Creates the nest. */
        TheMigratedRowShape() {
        }

        @Test
        @DisplayName("carries exactly thirteen columns, in declared order, each with its declared type, "
                + "width, scale and nullability")
        void carriesExactlyThirteenMappedColumns() {
            assertThat(columnContract())
                    .as("the twelve persisted fields plus the version counter, and nothing else")
                    .hasSize(MAPPED_COLUMN_COUNT)
                    .containsExactlyElementsOf(EXPECTED_COLUMN_CONTRACT);
        }

        @Test
        @DisplayName("counts the version column as the only mapping with no legacy antecedent, and "
                + "defaults it to the initial version")
        void countsTheVersionColumnAsTheOnlyAdditionalMapping() {
            assertThat(MAPPED_COLUMN_COUNT - PERSISTED_FIELD_COUNT)
                    .as("one column beyond the record image: the optimistic version")
                    .isOne();
            assertThat(jdbcTemplate.queryForObject(COLUMN_DEFAULT_SQL, String.class, "version"))
                    .as("a row written by any writer, including one that never constructs an entity, "
                            + "starts at the initial version")
                    .isEqualTo(String.valueOf(INITIAL_VERSION));
        }

        @Test
        @DisplayName("places the twelve persisted fields at their verified offsets and leaves the "
                + "178-byte trailing filler unpersisted")
        void placesTheTwelvePersistedFieldsAtTheirVerifiedOffsets() {
            final List<String> placement = TestDataFactory.ACCOUNT.fields().stream()
                    .map(field -> field.offset() + "/" + field.width())
                    .toList();

            assertThat(placement)
                    .as("the five monetary fields are NOT contiguous; grouping them still yields a "
                            + "three-hundred byte image, which is what makes the mistake silent")
                    .containsExactlyElementsOf(EXPECTED_FIELD_PLACEMENT);
            assertThat(TestDataFactory.ACCOUNT.recordLength()).isEqualTo(RECORD_LENGTH);
            assertThat(TestDataFactory.ACCOUNT.dataLength()).isEqualTo(PERSISTED_DATA_LENGTH);
            assertThat(TestDataFactory.ACCOUNT.fillerLength())
                    .as("%d persisted bytes plus %d filler bytes account for the whole record",
                            PERSISTED_DATA_LENGTH, FILLER_LENGTH)
                    .isEqualTo(FILLER_LENGTH);
            assertThat(TestDataFactory.ACCOUNT.hasFiller()).isTrue();
            assertThat(PERSISTED_DATA_LENGTH + FILLER_LENGTH).isEqualTo(RECORD_LENGTH);
            assertThat(TestDataFactory.ACCOUNT.fields())
                    .as("the filler is not a field and therefore not a column")
                    .hasSize(PERSISTED_FIELD_COUNT);
        }

        @Test
        @DisplayName("keys the row on the business identifier itself, with no sequence, auto-numbered "
                + "or generated column anywhere on this table")
        void keysTheRowOnTheBusinessIdentifierAlone() {
            assertThat(jdbcTemplate.queryForList(PRIMARY_KEY_SQL, String.class))
                    .as("the key is the leading substring of the record image, not a separate attribute")
                    .containsExactly("acct_id");
            assertThat(KEY_WIDTH + DATA_REMAINDER_WIDTH)
                    .as("the legacy file section splits the record into an %d-byte key and a %d-byte "
                            + "data remainder", KEY_WIDTH, DATA_REMAINDER_WIDTH)
                    .isEqualTo(RECORD_LENGTH);
            assertThat(TestDataFactory.ACCOUNT.fields().get(0).offset()).isZero();
            assertThat(TestDataFactory.ACCOUNT.fields().get(0).width()).isEqualTo(KEY_WIDTH);
            assertThat(jdbcTemplate.queryForObject(SURROGATE_KEY_SQL, Integer.class))
                    .as("a surrogate key would break the record-image-to-row correspondence that "
                            + "byte-level output parity depends on")
                    .isZero();
        }

        @Test
        @DisplayName("declares the expiration-date column with the dropped letter restored, while its "
                + "byte range stays exactly where the record image puts it")
        void restoresTheDroppedLetterWithoutMovingTheByteRange() {
            final var expirationField = TestDataFactory.ACCOUNT.fields().get(EXPIRATION_FIELD_INDEX);

            assertThat(columnContract().get(EXPIRATION_FIELD_INDEX))
                    .as("the migration and the entity both spell the column correctly")
                    .isEqualTo("7 " + EXPIRATION_COLUMN + " character varying(10) NOT NULL");
            assertThat(expirationField.cobolName().toLowerCase(Locale.ROOT).replace('-', '_'))
                    .as("the source field name is transcribed as found, dropped letter and all, which "
                            + "is precisely why it is not the column name")
                    .isNotEqualTo(EXPIRATION_COLUMN);
            assertThat(expirationField.offset())
                    .as("correcting the spelling must not move the field: the record image stays "
                            + "byte-compatible")
                    .isEqualTo(58);
            assertThat(expirationField.width()).isEqualTo(10);
        }
    }

    /**
     * The decimal contract of the five monetary columns.
     *
     * <p>All five derive from a zoned-decimal source field of ten integer digits and two fraction
     * digits plus a sign, and all five are carried as an exact decimal at that scale rather than as an
     * approximate binary numeric type. The rounding policy is not a matter of taste: a census of the
     * legacy estate found no rounding clause on any arithmetic statement anywhere, so every store into a
     * two-decimal field discards what does not fit rather than carrying it into the last digit kept.
     * These assertions pin that, both signs, and pin that a value never comes back in exponent form.</p>
     */
    @Nested
    @DisplayName("the decimal contract of the five monetary columns")
    final class TheDecimalContract {

        /** Creates the nest. */
        TheDecimalContract() {
        }

        @Test
        @DisplayName("round-trips all five amounts at two fraction digits, with a negative amount "
                + "keeping its sign")
        void roundTripsAllFiveAmountsAtTwoFractionDigits() {
            try {
                final Account fixture = accountFixture(DECIMAL_ACCOUNT);
                fixture.setAcctCurrBal(new BigDecimal("194.01"));
                fixture.setAcctCreditLimit(new BigDecimal("2020.02"));
                fixture.setAcctCashCreditLimit(new BigDecimal("1020.03"));
                fixture.setAcctCurrCycCredit(new BigDecimal("55.04"));
                fixture.setAcctCurrCycDebit(new BigDecimal("-66.05"));
                repository.saveAndFlush(fixture);

                final Account reloaded = reload(DECIMAL_ACCOUNT);

                assertThat(reloaded.getAcctCurrBal()).isEqualTo(new BigDecimal("194.01"));
                assertThat(reloaded.getAcctCreditLimit()).isEqualTo(new BigDecimal("2020.02"));
                assertThat(reloaded.getAcctCashCreditLimit()).isEqualTo(new BigDecimal("1020.03"));
                assertThat(reloaded.getAcctCurrCycCredit()).isEqualTo(new BigDecimal("55.04"));
                assertThat(reloaded.getAcctCurrCycDebit())
                        .as("a negative cycle debit keeps its sign through the round trip")
                        .isEqualTo(new BigDecimal("-66.05"));
                assertThat(reloaded.getAcctCurrCycDebit().signum()).isNegative();
                assertThat(List.of(reloaded.getAcctCurrBal().scale(),
                        reloaded.getAcctCreditLimit().scale(),
                        reloaded.getAcctCashCreditLimit().scale(),
                        reloaded.getAcctCurrCycCredit().scale(),
                        reloaded.getAcctCurrCycDebit().scale()))
                        .as("no scale drift on any of the five: an exact decimal, never an "
                                + "approximate binary numeric type")
                        .containsOnly(MONEY_SCALE);
            } finally {
                removeReserved(DECIMAL_ACCOUNT);
            }
        }

        @Test
        @DisplayName("carries the full ten integer digits on every amount, positive and negative, "
                + "without loss or overflow")
        void carriesTheFullTenIntegerDigits() {
            final BigDecimal widest = new BigDecimal("9999999999.99");
            try {
                final Account fixture = accountFixture(DECIMAL_ACCOUNT);
                fixture.setAcctCurrBal(widest);
                fixture.setAcctCreditLimit(widest);
                fixture.setAcctCashCreditLimit(widest);
                fixture.setAcctCurrCycCredit(widest);
                fixture.setAcctCurrCycDebit(widest.negate());
                repository.saveAndFlush(fixture);

                final Account reloaded = reload(DECIMAL_ACCOUNT);

                assertThat(reloaded.getAcctCurrBal()).isEqualTo(widest);
                assertThat(reloaded.getAcctCreditLimit()).isEqualTo(widest);
                assertThat(reloaded.getAcctCashCreditLimit()).isEqualTo(widest);
                assertThat(reloaded.getAcctCurrCycCredit()).isEqualTo(widest);
                assertThat(reloaded.getAcctCurrCycDebit()).isEqualTo(widest.negate());
                assertThat(reloaded.getAcctCurrBal().precision())
                        .as("twelve digits in total, of which two are fractional")
                        .isEqualTo(12);
            } finally {
                removeReserved(DECIMAL_ACCOUNT);
            }
        }

        @Test
        @DisplayName("discards a third fraction digit toward zero rather than carrying it into the "
                + "second, on both signs")
        void discardsAThirdFractionDigitTowardZero() {
            try {
                final Account fixture = accountFixture(DECIMAL_ACCOUNT);
                fixture.setAcctCurrBal(new BigDecimal("194.999"));
                fixture.setAcctCurrCycDebit(new BigDecimal("-194.999"));
                repository.saveAndFlush(fixture);

                final Account reloaded = reload(DECIMAL_ACCOUNT);

                assertThat(reloaded.getAcctCurrBal())
                        .as("no rounding clause exists on any arithmetic statement in the estate, so a "
                                + "store into a two-decimal field truncates; a carrying policy would "
                                + "have produced 195.00 and diverged by a cent")
                        .isEqualTo(new BigDecimal("194.99"));
                assertThat(reloaded.getAcctCurrCycDebit())
                        .as("truncation is toward zero, so the negative side loses magnitude too")
                        .isEqualTo(new BigDecimal("-194.99"));
                assertThat(TestDataFactory.storedAmount(new BigDecimal("194.999")))
                        .as("the fixture builder applies the same policy the write boundary applies")
                        .isEqualTo(new BigDecimal("194.99"));
            } finally {
                removeReserved(DECIMAL_ACCOUNT);
            }
        }

        @Test
        @DisplayName("returns a plain decimal even when the value was supplied in exponent form")
        void returnsAPlainDecimalRatherThanExponentForm() {
            try {
                final Account fixture = accountFixture(DECIMAL_ACCOUNT);
                fixture.setAcctCurrBal(new BigDecimal("1E+3"));
                repository.saveAndFlush(fixture);

                final BigDecimal reloaded = reload(DECIMAL_ACCOUNT).getAcctCurrBal();

                assertThat(reloaded.toPlainString()).isEqualTo("1000.00");
                assertThat(reloaded.toString())
                        .as("a stored amount is rendered digit for digit; an exponent marker in an "
                                + "output field would be a parity defect at the byte level")
                        .doesNotContain("E")
                        .isEqualTo(reloaded.toPlainString());
                assertThat(reloaded.scale()).isEqualTo(MONEY_SCALE);
            } finally {
                removeReserved(DECIMAL_ACCOUNT);
            }
        }
    }

    /**
     * The delivered population, and keyed retrieval over it.
     *
     * <p>The reference seed is the closest thing this module has to production-representative input, so
     * its size and its measured uniformities are asserted rather than assumed. Two of those uniformities
     * would look like defects to a reader who had not measured them, and both are therefore stated
     * positively here: the group identifier that names no group, and the value at the postcode offset
     * that looks like a group identifier.</p>
     */
    @Nested
    @DisplayName("the delivered population and keyed retrieval")
    final class TheDeliveredPopulation {

        /** Creates the nest. */
        TheDeliveredPopulation() {
        }

        @Test
        @DisplayName("holds exactly the fifty rows the reference seed loads")
        void holdsExactlyTheFiftySeededRows() {
            assertThat(repository.count())
                    .as("fifty rows, seeded after the customer table and before the card table, and "
                            + "under the non-production profiles only")
                    .isEqualTo(TestDataFactory.SEEDED_FIFTY_ROW_COUNT);
        }

        @Test
        @DisplayName("resolves a seeded eleven-character key to the row the seed declares")
        void resolvesASeededKeyToTheSeededRow() {
            final Optional<Account> found = repository.findById(SEEDED_ACCOUNT);

            assertThat(found).isPresent();
            final Account account = found.orElseThrow();
            assertThat(account.getAcctId()).isEqualTo(SEEDED_ACCOUNT);
            assertThat(account.getAcctActiveStatus()).isEqualTo("Y");
            assertThat(account.getAcctCurrBal()).isEqualTo(new BigDecimal("194.00"));
            assertThat(account.getAcctCreditLimit()).isEqualTo(new BigDecimal("2020.00"));
            assertThat(account.getAcctCashCreditLimit()).isEqualTo(new BigDecimal("1020.00"));
            assertThat(account.getAcctOpenDate()).isEqualTo("2014-11-20");
            assertThat(account.getAcctExpirationDate()).isEqualTo("2025-05-20");
            assertThat(account.getAcctReissueDate()).isEqualTo("2025-05-20");
            assertThat(account.getAcctCurrCycCredit()).isEqualTo(new BigDecimal("0.00"));
            assertThat(account.getAcctCurrCycDebit()).isEqualTo(new BigDecimal("0.00"));
            assertThat(account.getAcctAddrZip()).isEqualTo(TestDataFactory.SEEDED_ACCOUNT_ADDRESS_ZIP);
            assertThat(account.getAcctGroupId()).isEqualTo(TestDataFactory.SEEDED_ACCOUNT_GROUP_ID);
        }

        @Test
        @DisplayName("answers an empty result for a key no row carries, and raises nothing")
        void answersAnEmptyResultForAnAbsentKey() {
            assertThat(repository.findById(TestDataFactory.UNKNOWN_ACCOUNT_ID))
                    .as("record absence is an empty result, which is how the legacy invalid-key "
                            + "condition reaches a caller rather than as a raised failure")
                    .isEmpty();
            assertThat(repository.findById(ABSENT_ACCOUNT)).isEmpty();
            assertThat(repository.existsById(TestDataFactory.UNKNOWN_ACCOUNT_ID)).isFalse();
        }

        @Test
        @DisplayName("treats a padded key and its unpadded form as different keys")
        void treatsAPaddedKeyAndItsUnpaddedFormAsDifferentKeys() {
            try {
                repository.saveAndFlush(accountFixture(LEADING_ZERO_ACCOUNT));

                assertThat(repository.findById(LEADING_ZERO_ACCOUNT))
                        .as("the leading zeros are part of the identifier, so the padded form resolves")
                        .isPresent()
                        .get()
                        .satisfies(account -> assertThat(account.getAcctId())
                                .isEqualTo(LEADING_ZERO_ACCOUNT)
                                .hasSize(KEY_WIDTH));
                assertThat(repository.findById(UNPADDED_LEADING_ZERO_ACCOUNT))
                        .as("a numeric key type would have discarded the padding and made these two "
                                + "the same row; they are not")
                        .isEmpty();
            } finally {
                removeReserved(LEADING_ZERO_ACCOUNT);
            }
        }

        @Test
        @DisplayName("carries a group identifier of exactly ten spaces on every seeded row, matching "
                + "none of the three seeded disclosure groups")
        void carriesATenSpaceGroupIdentifierOnEverySeededRow() {
            final List<Account> delivered = repository.findAll();

            assertThat(delivered).hasSize(TestDataFactory.SEEDED_FIFTY_ROW_COUNT);
            assertThat(delivered).allSatisfy(account -> {
                assertThat(account.getAcctGroupId())
                        .isEqualTo(TestDataFactory.SEEDED_ACCOUNT_GROUP_ID)
                        .hasSize(10)
                        .matches(" {10}");
                assertThat(TestDataFactory.SEEDED_DISCLOSURE_GROUP_IDS)
                        .as("this is why every seeded account drives the interest program's "
                                + "default-group fallback rather than a direct rate lookup")
                        .doesNotContain(account.getAcctGroupId());
            });
            assertThat(TestDataFactory.SEEDED_DISCLOSURE_GROUP_IDS)
                    .containsExactly(TestDataFactory.DIRECT_HIT_DISCLOSURE_GROUP_ID,
                            TestDataFactory.FALLBACK_DISCLOSURE_GROUP_ID,
                            TestDataFactory.ZERO_RATE_DISCLOSURE_GROUP_ID);
        }

        @Test
        @DisplayName("returns the uniform value at the postcode offset unchanged, neither normalised "
                + "nor rejected")
        void returnsThePostcodeArtefactUnchanged() {
            assertThat(repository.findAll()).allSatisfy(account ->
                    assertThat(account.getAcctAddrZip())
                            .as("it reads like a group identifier and is not one: it sits at the "
                                    + "postcode offset, and it is a characteristic of the fixture")
                            .isEqualTo(TestDataFactory.SEEDED_ACCOUNT_ADDRESS_ZIP)
                            .hasSize(10));
            assertThat(TestDataFactory.SEEDED_ACCOUNT_ADDRESS_ZIP)
                    .as("the value at the postcode offset is byte-identical to the first disclosure "
                            + "group identifier, which is exactly why neither may be inferred from "
                            + "the other")
                    .isEqualTo(TestDataFactory.DIRECT_HIT_DISCLOSURE_GROUP_ID);
        }

        @Test
        @DisplayName("stands at the initial version on every seeded row")
        void standsAtTheInitialVersionOnEverySeededRow() {
            assertThat(repository.findAll()).allSatisfy(account ->
                    assertThat(account.getVersion())
                            .as("a seeded row has never been rewritten, so its counter has never moved")
                            .isEqualTo(INITIAL_VERSION));
        }

        @Test
        @DisplayName("round-trips all thirteen persisted properties through the inherited save and "
                + "keyed read")
        void roundTripsAllThirteenPersistedProperties() {
            try {
                final Account written = TestDataFactory.account()
                        .acctId(ROUND_TRIP_ACCOUNT)
                        .activeStatus("N")
                        .currentBalance(new BigDecimal("1234567890.12"))
                        .creditLimit(new BigDecimal("2345678901.23"))
                        .cashCreditLimit(new BigDecimal("3456789012.34"))
                        .openDate("1999-12-31")
                        .expirationDate("2098-11-30")
                        .reissueDate("2097-10-29")
                        .cycleCredit(new BigDecimal("456789012.45"))
                        .cycleDebit(new BigDecimal("-567890123.56"))
                        .addressZip("ZIP0000001")
                        .groupId("ROUNDTRIP ")
                        .build();
                repository.saveAndFlush(written);

                final Account reloaded = reload(ROUND_TRIP_ACCOUNT);

                assertThat(reloaded.getAcctId()).isEqualTo(ROUND_TRIP_ACCOUNT);
                assertThat(reloaded.getAcctActiveStatus()).isEqualTo("N");
                assertThat(reloaded.getAcctCurrBal()).isEqualTo(new BigDecimal("1234567890.12"));
                assertThat(reloaded.getAcctCreditLimit()).isEqualTo(new BigDecimal("2345678901.23"));
                assertThat(reloaded.getAcctCashCreditLimit())
                        .isEqualTo(new BigDecimal("3456789012.34"));
                assertThat(reloaded.getAcctOpenDate()).isEqualTo("1999-12-31");
                assertThat(reloaded.getAcctExpirationDate()).isEqualTo("2098-11-30");
                assertThat(reloaded.getAcctReissueDate()).isEqualTo("2097-10-29");
                assertThat(reloaded.getAcctCurrCycCredit()).isEqualTo(new BigDecimal("456789012.45"));
                assertThat(reloaded.getAcctCurrCycDebit()).isEqualTo(new BigDecimal("-567890123.56"));
                assertThat(reloaded.getAcctAddrZip()).isEqualTo("ZIP0000001");
                assertThat(reloaded.getAcctGroupId())
                        .as("the trailing pad of a fixed-width value survives verbatim, because the "
                                + "column is bounded variable-length rather than blank-padded")
                        .isEqualTo("ROUNDTRIP ")
                        .hasSize(10);
                assertThat(reloaded.getVersion()).isEqualTo(INITIAL_VERSION);
            } finally {
                removeReserved(ROUND_TRIP_ACCOUNT);
            }
        }
    }

    /**
     * The constraint surface: what refers to this table, what it refers to, and what indexes it carries.
     *
     * <p>The absences here are decisions rather than omissions, so they are asserted as firmly as the
     * presences. Nothing refers out of this table - in particular the group identifier does not, and
     * cannot, refer to the disclosure group - and the table carries no index beyond the one backing its
     * key, because the three alternate indexes the legacy estate maintained were over the card,
     * cross-reference and transaction clusters, not this one.</p>
     */
    @Nested
    @DisplayName("the constraint surface")
    final class TheConstraintSurface {

        /** Creates the nest. */
        TheConstraintSurface() {
        }

        @Test
        @DisplayName("originates no foreign key at all, and in particular none to the disclosure group")
        void originatesNoForeignKey() {
            assertThat(jdbcTemplate.queryForList(OUTBOUND_FOREIGN_KEY_SQL, String.class))
                    .as("no foreign key originates from this table; those that exist point at it")
                    .isEmpty();
            assertThat(jdbcTemplate.queryForList(DISCLOSURE_GROUP_REFERENCE_SQL, String.class))
                    .as("the group identifier is only a non-unique leading portion of the composite "
                            + "disclosure key, recurring seventeen times per group across the %d "
                            + "seeded rows, and a constraint would additionally reject the ten-space "
                            + "value on all fifty accounts",
                            TestDataFactory.SEEDED_DISCLOSURE_GROUP_COUNT)
                    .isEmpty();
        }

        @Test
        @DisplayName("is pointed at by exactly three named foreign keys")
        void isPointedAtByExactlyThreeNamedForeignKeys() {
            assertThat(jdbcTemplate.queryForList(INBOUND_FOREIGN_KEY_SQL, String.class))
                    .containsExactlyElementsOf(EXPECTED_INBOUND_FOREIGN_KEYS);
        }

        @Test
        @DisplayName("refuses a card row whose owning account exists in no row of this table")
        void refusesACardRowWithoutAnOwningAccount() {
            assertThatExceptionOfType(DataIntegrityViolationException.class)
                    .as("declared is not the same as enforced, so the refusal itself is observed")
                    .isThrownBy(() -> jdbcTemplate.update(INSERT_CHILD_CARD_SQL,
                            CHILD_CARD_NUMBER, ABSENT_ACCOUNT, "123", "FOREIGN KEY PROBE",
                            "2099-12-31", "Y"))
                    .withMessageContaining(CARD_ACCOUNT_FOREIGN_KEY);
            assertThat(repository.existsById(ABSENT_ACCOUNT))
                    .as("the refused insert created nothing on either side")
                    .isFalse();
        }

        @Test
        @DisplayName("refuses a category-balance row whose owning account exists in no row of this table")
        void refusesACategoryBalanceRowWithoutAnOwningAccount() {
            assertThatExceptionOfType(DataIntegrityViolationException.class)
                    .isThrownBy(() -> jdbcTemplate.update(INSERT_CHILD_CATEGORY_BALANCE_SQL,
                            ABSENT_ACCOUNT, CHILD_TYPE_CODE, CHILD_CATEGORY_CODE,
                            new BigDecimal("1.00")))
                    .withMessageContaining(CATEGORY_BALANCE_ACCOUNT_FOREIGN_KEY);
        }

        @Test
        @DisplayName("carries no secondary index: the only index is the unique tree backing the key")
        void carriesNoSecondaryIndex() {
            assertThat(jdbcTemplate.queryForList(INDEX_SQL, String.class))
                    .as("the three alternate indexes the migration creates are over the card, "
                            + "cross-reference and transaction tables, never over this one")
                    .containsExactly(EXPECTED_ONLY_INDEX);
        }
    }

    /**
     * The optimistic version guard, which is this class's headline responsibility.
     *
     * <p>The legacy online update path read an account, held the image it had read, and compared that
     * before image against the record on file before rewriting it - and its single explicit rollback is
     * what happened when the two disagreed. The version attribute is the faithful replacement for that
     * comparison, and these two tests are what make the replacement real rather than declarative: the
     * counter advances by exactly one per accepted rewrite, and a second writer holding the same before
     * image is refused rather than allowed to overwrite the first silently.
     *
     * <p>The refusal is raised by the persistence provider and reaches this boundary as the framework's
     * own optimistic-locking failure. That is deliberately what is asserted here. The module's
     * domain-specific conflict type is produced one layer up, in the service that owns the rollback, and
     * is asserted by that layer's own tests - see the note on this class.
     *
     * <p>Read-committed isolation plus this counter is STRICTLY STRONGER than the legacy baseline, whose
     * file definitions specified uncommitted-read integrity with no recovery and no journalling and
     * rested solely on a locking update model plus each program's own image comparison. The stronger
     * guarantee is an intentional, documented posture change and not a behavioural regression; the
     * record is in the decision log.
     */
    @Nested
    @DisplayName("the optimistic version guard")
    final class TheOptimisticVersionGuard {

        /** The amount the accepted rewrite stores. */
        private static final BigDecimal ACCEPTED_BALANCE = new BigDecimal("111.11");

        /** The amount the refused rewrite tries to store and must never manage to. */
        private static final BigDecimal REFUSED_BALANCE = new BigDecimal("222.22");

        /** Creates the nest. */
        TheOptimisticVersionGuard() {
        }

        @Test
        @DisplayName("starts a freshly written row at the initial version and advances it by exactly "
                + "one on each accepted update")
        void advancesTheVersionByExactlyOnePerAcceptedUpdate() {
            try {
                final Account inserted = repository.saveAndFlush(accountFixture(VERSION_ACCOUNT));
                assertThat(inserted.getVersion())
                        .as("an inserted row starts where a seeded row starts")
                        .isEqualTo(INITIAL_VERSION);

                inserted.setAcctCurrBal(new BigDecimal("101.00"));
                final Account afterFirstUpdate = repository.saveAndFlush(inserted);
                assertThat(afterFirstUpdate.getVersion()).isEqualTo(INITIAL_VERSION + 1L);

                afterFirstUpdate.setAcctCurrBal(new BigDecimal("102.00"));
                final Account afterSecondUpdate = repository.saveAndFlush(afterFirstUpdate);
                assertThat(afterSecondUpdate.getVersion())
                        .as("exactly one per accepted update, never two and never none")
                        .isEqualTo(INITIAL_VERSION + 2L);

                final Account settled = reload(VERSION_ACCOUNT);
                assertThat(settled.getVersion()).isEqualTo(INITIAL_VERSION + 2L);
                assertThat(settled.getAcctCurrBal()).isEqualTo(new BigDecimal("102.00"));
            } finally {
                removeReserved(VERSION_ACCOUNT);
            }
        }

        @Test
        @DisplayName("refuses the second of two persistence contexts that read the same version, and "
                + "leaves the refused change unwritten")
        void refusesTheSecondOfTwoContextsHoldingTheSameVersion() {
            try {
                repository.saveAndFlush(accountFixture(CONFLICT_ACCOUNT));
                final TransactionTemplate unitOfWork = unitOfWork();

                // Both reads happen before either write, each in its own unit of work and therefore in
                // its own persistence context. That ordering is the whole point: reading the second view
                // after the first write would simply observe the advanced row and there would be no
                // conflict left to refuse.
                final Account firstView = unitOfWork.execute(status -> reload(CONFLICT_ACCOUNT));
                final Account secondView = unitOfWork.execute(status -> reload(CONFLICT_ACCOUNT));

                assertThat(firstView).isNotNull();
                assertThat(secondView).isNotNull();
                assertThat(firstView)
                        .as("two contexts, so two instances; one instance would mean one context and "
                                + "no conflict to observe")
                        .isNotSameAs(secondView);
                assertThat(firstView.getVersion()).isEqualTo(INITIAL_VERSION);
                assertThat(secondView.getVersion())
                        .as("both hold the same before image, which is the situation the legacy "
                                + "comparison existed to detect")
                        .isEqualTo(firstView.getVersion());

                firstView.setAcctCurrBal(ACCEPTED_BALANCE);
                final Account accepted = unitOfWork.execute(status -> repository.saveAndFlush(firstView));
                assertThat(accepted).isNotNull();
                assertThat(accepted.getVersion()).isEqualTo(INITIAL_VERSION + 1L);

                secondView.setAcctCurrBal(REFUSED_BALANCE);
                assertThatExceptionOfType(ObjectOptimisticLockingFailureException.class)
                        .as("the provider refuses the stale write; the service layer is what turns this "
                                + "failure into the module's own conflict type, and its own tests assert "
                                + "that translation")
                        .isThrownBy(() -> unitOfWork.execute(
                                status -> repository.saveAndFlush(secondView)));

                final Account settled = reload(CONFLICT_ACCOUNT);
                assertThat(settled.getAcctCurrBal())
                        .as("the accepted rewrite is the outcome that survives")
                        .isEqualTo(ACCEPTED_BALANCE);
                assertThat(settled.getVersion())
                        .as("the refused attempt advanced nothing, so the counter moved exactly once")
                        .isEqualTo(INITIAL_VERSION + 1L);
                assertThat(settled.getAcctCurrBal()).isNotEqualByComparingTo(REFUSED_BALANCE);
                assertThat(secondView.getVersion())
                        .as("the refused view still carries the before image it was refused for")
                        .isEqualTo(INITIAL_VERSION);
            } finally {
                removeReserved(CONFLICT_ACCOUNT);
            }
        }
    }

    /**
     * The posting rewrite: its own row status, the three balances it writes, and its compare-and-set.
     *
     * <p>The posting program distinguishes a successful account rewrite from its invalid-key arm by the
     * write operation's status, so the relational translation has to return the affected-row count from
     * the update itself, write only the balances that paragraph changes, and advance the version in the
     * same statement. Those properties depend on query parsing, provider execution and database row-count
     * reporting, none of which a unit test can prove.
     */
    @Nested
    @DisplayName("the posting rewrite")
    final class ThePostingRewrite {

        /** Creates the nest. */
        ThePostingRewrite() {
        }

        @Test
        @DisplayName("returns its row status, writes all three balances and increments the version "
                + "exactly once")
        void reportsItsOwnStatusAndAdvancesTheVersion() {
            try {
                final Account stored = repository.saveAndFlush(accountFixture(RESERVED_ACCOUNT));
                final long versionBeforeRewrite = stored.getVersion();

                final int rewritten = repository.rewritePostingBalances(
                        RESERVED_ACCOUNT,
                        versionBeforeRewrite,
                        new BigDecimal("125.25"),
                        new BigDecimal("30.25"),
                        new BigDecimal("-5.00"));

                assertThat(rewritten).isOne();
                assertThat(repository.findById(RESERVED_ACCOUNT))
                        .get()
                        .satisfies(account -> {
                            assertThat(account.getAcctCurrBal())
                                    .isEqualByComparingTo(new BigDecimal("125.25"));
                            assertThat(account.getAcctCurrCycCredit())
                                    .isEqualByComparingTo(new BigDecimal("30.25"));
                            assertThat(account.getAcctCurrCycDebit())
                                    .isEqualByComparingTo(new BigDecimal("-5.00"));
                            assertThat(account.getVersion()).isEqualTo(versionBeforeRewrite + 1L);
                        });

                assertThat(repository.rewritePostingBalances(
                        ABSENT_ACCOUNT,
                        INITIAL_VERSION,
                        new BigDecimal("1.00"),
                        new BigDecimal("1.00"),
                        new BigDecimal("0.00")))
                        .as("zero rows is the REWRITE INVALID KEY outcome that becomes reason 109")
                        .isZero();

                // The version the first rewrite consumed is now stale. Offering it again must change
                // nothing: this is the compare-and-set that stops a concurrent online write from being
                // silently overwritten, and it is why a zero-row outcome no longer means "absent".
                assertThat(repository.rewritePostingBalances(
                        RESERVED_ACCOUNT,
                        versionBeforeRewrite,
                        new BigDecimal("999.99"),
                        new BigDecimal("999.99"),
                        new BigDecimal("-999.99")))
                        .as("a stale version rewrites no row even though the row is present")
                        .isZero();
                assertThat(repository.findById(RESERVED_ACCOUNT))
                        .get()
                        .satisfies(account -> {
                            assertThat(account.getAcctCurrBal())
                                    .as("the earlier write survives the stale attempt untouched")
                                    .isEqualByComparingTo(new BigDecimal("125.25"));
                            assertThat(account.getVersion())
                                    .as("and the counter did not advance a second time")
                                    .isEqualTo(versionBeforeRewrite + 1L);
                        });
                assertThat(repository.existsById(RESERVED_ACCOUNT))
                        .as("the row is present, which is how the caller tells a lost race from an "
                                + "absent account")
                        .isTrue();
            } finally {
                removeReserved(RESERVED_ACCOUNT);
            }
        }
    }

    /**
     * The held keyed read that settles the invalid-key question before the rewrite runs.
     *
     * <p>A lock annotation that silently did nothing would leave the very timing window it exists to
     * close, and would look identical to a working one in every unit test: the mock would still be
     * asked, and would still answer. So the hold is proven here against a real server - that it takes
     * the row, that it keeps it for the length of the unit of work, that it refuses rather than
     * downgrading when there is no unit of work to keep it for, and that a competing writer therefore
     * cannot invert the answer between the read and the rewrite that consumes it.
     */
    @Nested
    @DisplayName("the held read settles presence before the rewrite and keeps it settled")
    final class TheHeldReadBeforeTheRewrite {

        /** How long a competing writer is given to prove it is blocked. */
        private static final long BLOCKED_WINDOW_MILLIS = 750L;

        /** How long a thread is given to finish once it is no longer blocked. */
        private static final long RELEASED_WINDOW_SECONDS = 30L;

        /** Account key reserved for this nest, distinct from the one the rewrite test uses. */
        private static final String HELD_ACCOUNT = "99000000044";

        /** Creates the nest. */
        TheHeldReadBeforeTheRewrite() {
        }

        @Test
        @DisplayName("finds the row inside a unit of work and reports the version the rewrite will "
                + "have to match")
        void findsTheRowInsideAUnitOfWork() {
            try {
                final long storedVersion =
                        repository.saveAndFlush(accountFixture(HELD_ACCOUNT)).getVersion();

                final Optional<Account> held = unitOfWork()
                        .execute(status -> repository.findByIdForUpdate(HELD_ACCOUNT));

                assertThat(held).isNotNull();
                assertThat(held).isPresent();
                assertThat(held.orElseThrow().getAcctId()).isEqualTo(HELD_ACCOUNT);
                assertThat(held.orElseThrow().getVersion()).isEqualTo(storedVersion);
            } finally {
                removeReserved(HELD_ACCOUNT);
            }
        }

        @Test
        @DisplayName("answers empty for a key no row carries, so the legacy invalid-key condition "
                + "stays an empty result rather than becoming a raised failure")
        void answersEmptyForAnAbsentKey() {
            final Optional<Account> absent = unitOfWork()
                    .execute(status -> repository.findByIdForUpdate(ABSENT_ACCOUNT));

            assertThat(absent).isNotNull();
            assertThat(absent).isEmpty();
        }

        @Test
        @DisplayName("REFUSES to run outside a unit of work rather than reading without the lock, "
                + "because a silent downgrade would reopen the window it exists to close")
        void refusesToRunOutsideAUnitOfWork() {
            assertThatExceptionOfType(InvalidDataAccessApiUsageException.class)
                    .isThrownBy(() -> repository.findByIdForUpdate(ABSENT_ACCOUNT))
                    .withRootCauseInstanceOf(TransactionRequiredException.class);
        }

        @Test
        @DisplayName("a competing DELETE cannot slip between the held read and the rewrite: its "
                + "statement is blocked while the row is held, and refused once it is released")
        void aCompetingDeleteCannotSlipBetweenTheReadAndTheRewrite()
                throws InterruptedException, ExecutionException, TimeoutException {
            final TransactionTemplate template = unitOfWork();
            final CountDownLatch posterHasTheRow = new CountDownLatch(1);
            final CountDownLatch deleterHasIssuedIt = new CountDownLatch(1);
            final CountDownLatch posterMayFinish = new CountDownLatch(1);
            final ExecutorService threads = Executors.newFixedThreadPool(2);
            try {
                final long storedVersion =
                        repository.saveAndFlush(accountFixture(HELD_ACCOUNT)).getVersion();

                final Future<Integer> poster = threads.submit(() -> template.execute(status -> {
                    final boolean present =
                            repository.findByIdForUpdate(HELD_ACCOUNT).isPresent();
                    posterHasTheRow.countDown();
                    awaitOrFail(deleterHasIssuedIt);
                    awaitOrFail(posterMayFinish);
                    // Present under the hold, so the rewrite below must find exactly one row and a
                    // zero count could only mean a version mismatch. That is the whole property.
                    assertThat(present).isTrue();
                    return repository.rewritePostingBalances(HELD_ACCOUNT, storedVersion,
                            new BigDecimal("777.77"), new BigDecimal("77.77"),
                            new BigDecimal("-7.77"));
                }));
                assertThat(posterHasTheRow.await(RELEASED_WINDOW_SECONDS, TimeUnit.SECONDS))
                        .as("the posting thread must hold the row before the delete competes")
                        .isTrue();

                // The competing unit reads the row unblocked - a snapshot read never waits - and is
                // stopped at the DELETE statement itself, which is the statement that would have
                // made the presence answer stale.
                final Future<Boolean> deleter = threads.submit(() -> template.execute(status -> {
                    repository.findById(HELD_ACCOUNT).orElseThrow();
                    deleterHasIssuedIt.countDown();
                    repository.deleteById(HELD_ACCOUNT);
                    repository.flush();
                    return Boolean.TRUE;
                }));
                assertThat(deleterHasIssuedIt.await(RELEASED_WINDOW_SECONDS, TimeUnit.SECONDS))
                        .as("the delete must actually have been reached, or the timeout below "
                                + "would pass for the wrong reason")
                        .isTrue();
                assertThatExceptionOfType(TimeoutException.class)
                        .as("the delete cannot take effect while the row is held; if it could, "
                                + "the presence answer would be stale by the time it is used")
                        .isThrownBy(() -> deleter.get(BLOCKED_WINDOW_MILLIS,
                                TimeUnit.MILLISECONDS));

                posterMayFinish.countDown();
                assertThat(poster.get(RELEASED_WINDOW_SECONDS, TimeUnit.SECONDS))
                        .as("the rewrite ran against the row it had established was there")
                        .isOne();

                // Released, the delete runs - and is refused, because it was decided against the
                // image the rewrite has since superseded. So the row the poster classified as
                // present neither vanished under it nor was silently removed behind it.
                assertThatExceptionOfType(ExecutionException.class)
                        .isThrownBy(() -> deleter.get(RELEASED_WINDOW_SECONDS, TimeUnit.SECONDS))
                        .withCauseInstanceOf(ObjectOptimisticLockingFailureException.class);
                assertThat(repository.findById(HELD_ACCOUNT))
                        .as("the rewrite the hold protected is the outcome that survives")
                        .get()
                        .satisfies(account -> assertThat(account.getAcctCurrBal())
                                .isEqualByComparingTo(new BigDecimal("777.77")));
            } finally {
                posterMayFinish.countDown();
                deleterHasIssuedIt.countDown();
                threads.shutdownNow();
                assertThat(threads.awaitTermination(RELEASED_WINDOW_SECONDS, TimeUnit.SECONDS))
                        .as("no locking thread may outlive this test")
                        .isTrue();
                removeReserved(HELD_ACCOUNT);
            }
        }

        /**
         * Waits for a latch, failing the calling thread rather than returning early on interruption.
         *
         * @param latch the latch to wait on
         */
        private static void awaitOrFail(final CountDownLatch latch) {
            try {
                if (!latch.await(RELEASED_WINDOW_SECONDS, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("the release signal never arrived");
                }
            } catch (final InterruptedException interruption) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while waiting to proceed", interruption);
            }
        }
    }

    /**
     * Opens one unit of work per call over the context's own transaction manager.
     *
     * <p>Each {@code execute} therefore runs in its own transaction and its own persistence context,
     * which is what lets the conflict test above hold two independent views of the same row.</p>
     *
     * @return a template bound to the real transaction manager
     */
    private TransactionTemplate unitOfWork() {
        return new TransactionTemplate(transactionManager);
    }

    /**
     * Reads the shape of the table back from the server, one descriptor per column in declared order.
     *
     * @return the column descriptors, ordered by their position in the table
     */
    private List<String> columnContract() {
        return jdbcTemplate.queryForList(COLUMN_CONTRACT_SQL, String.class);
    }

    /**
     * Re-reads an account through the inherited keyed lookup, requiring it to be there.
     *
     * @param  acctId the eleven-character business key
     * @return the account currently on the server
     */
    private Account reload(final String acctId) {
        return repository.findById(acctId).orElseThrow();
    }

    /**
     * Removes a reserved row if it is present, so the shared server is left as it was found.
     *
     * <p>Guarded by a presence check because several callers run inside a {@code finally} block that is
     * reached whether or not the row was ever written.</p>
     *
     * @param acctId the eleven-character business key
     */
    private void removeReserved(final String acctId) {
        if (repository.existsById(acctId)) {
            repository.deleteById(acctId);
            repository.flush();
        }
    }

    /**
     * Builds one complete, valid account row under a reserved key.
     *
     * <p>Every value satisfies the entity's own pre-write rules, so a failure observed in a test below
     * can only be the property that test is about. The group identifier is a real ten-character value
     * rather than the seed's ten spaces, which keeps a reserved row distinguishable from a delivered
     * one at a glance.</p>
     *
     * @param  acctId the eleven-digit business key to build under
     * @return an account carrying that key
     */
    private static Account accountFixture(final String acctId) {
        return TestDataFactory.account()
                .acctId(acctId)
                .activeStatus("Y")
                .currentBalance(new BigDecimal("100.00"))
                .creditLimit(new BigDecimal("99999.00"))
                .cashCreditLimit(new BigDecimal("500.00"))
                .openDate("2020-01-01")
                .expirationDate("2099-01-01")
                .reissueDate("2020-01-01")
                .cycleCredit(new BigDecimal("5.00"))
                .cycleDebit(new BigDecimal("0.00"))
                .addressZip("12345")
                .groupId("GROUP01   ")
                .build();
    }
}
