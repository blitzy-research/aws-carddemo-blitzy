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

import com.carddemo.domain.TransactionCategoryBalance;
import com.carddemo.domain.id.TransactionCategoryBalanceId;
import com.carddemo.support.AbstractPostgresIT;
import com.carddemo.support.TestDataFactory;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Verifies {@link TransactionCategoryBalanceRepository} against a real PostgreSQL 16 server carrying
 * the migrated schema - the relational form of the 50-byte transaction-category-balance record of
 * {@code app/cpy/CVTRA01Y.cpy}, which replaces the {@code TCATBALF} indexed cluster.
 *
 * <h2>Why this table needs its own integration test</h2>
 * It holds the <strong>widest composite key in the schema</strong>: 17 bytes in three components. Two
 * distinct classes of defect are possible only here, and both would compile, and both would pass a
 * suite written under the same misunderstanding. This class exists to make each of them fail loudly.
 *
 * <h2>Defect class one: the shared key-group name</h2>
 * Copybook {@code CVTRA01Y} and copybook {@code CVTRA04Y} give their key groups the <em>same</em>
 * legacy identifier while describing two entirely unrelated keys:
 *
 * <table border="1">
 *   <caption>The two keys that share one legacy group name</caption>
 *   <tr><th>Owner</th><th>Key width</th><th>Components</th></tr>
 *   <tr><td>{@code transaction_category_balance} - this table</td><td>17 bytes</td>
 *       <td>11-byte account identifier, then 2-byte type code, then 4-byte category code</td></tr>
 *   <tr><td>{@code transaction_category}</td><td>6 bytes</td>
 *       <td>2-byte type code, then 4-byte category code</td></tr>
 * </table>
 *
 * <p>The 6-byte key is <strong>not a prefix</strong> of the 17-byte key. The 17-byte group leads with
 * an 11-digit account identifier that the shorter key does not contain at all, which is attested
 * independently by the batch interest program's file description, by the cluster definition's 17-byte
 * key at offset 0 over a 50-byte record, and by the primary-key column order of the migration. The two
 * layouts therefore align at no offset whatever, and the shared group name is a source-level
 * coincidence rather than a relationship.
 *
 * <p>Conflating them is the highest-probability mapping error in this package, so this class never
 * mentions the shorter key's Java identifier type and instead proves the distinction from the server's
 * own catalogue: see
 * {@link #theSeventeenByteKeyLeadsWithTheAccountIdentifierAndSharesNoLeadingColumnWithTheSixByteKey()}.
 * Merging, reusing, subclassing or cross-referencing the two key definitions is forbidden.
 * {@code docs/decision-log.md} D-37 records the collision itself and DL-192 records the assertion that
 * now holds it, so a later refactor that merged them would fail rather than merely contradict a comment.
 *
 * <h2>Defect class two: the column-prefix asymmetry</h2>
 * The three key columns are {@code trancat_acct_id}, {@code trancat_type_cd} and {@code trancat_cd},
 * while the balance column is {@code tran_cat_bal}. That inconsistency is transcribed from the
 * copybook's own field names and is <strong>asserted rather than regularised</strong>, because the
 * mapping is validated against the migrated schema at start-up and a tidied-up name would fail
 * validation instead of quietly working. That is D-39's reason, applied here to an unevenness inside a
 * single table rather than between two layouts.
 *
 * <h2>What the record layout is, exactly</h2>
 * <table border="1">
 *   <caption>The 50-byte record and the columns it maps onto</caption>
 *   <tr><th>Component</th><th>Bytes</th><th>Offset</th><th>Column</th></tr>
 *   <tr><td>composite key group</td><td>17</td><td>0</td><td>&mdash;</td></tr>
 *   <tr><td>account identifier</td><td>11</td><td>0</td><td>{@code trancat_acct_id}</td></tr>
 *   <tr><td>transaction type code</td><td>2</td><td>11</td><td>{@code trancat_type_cd}</td></tr>
 *   <tr><td>category code</td><td>4</td><td>13</td><td>{@code trancat_cd}</td></tr>
 *   <tr><td>balance</td><td>11</td><td>17</td><td>{@code tran_cat_bal}</td></tr>
 *   <tr><td>trailing filler, not persisted</td><td>22</td><td>28</td><td>&mdash;</td></tr>
 * </table>
 *
 * <p>17 + 11 + 22 = 50. Because the key sits at offset 0 it <em>is</em> the leading substring of the
 * record image, so the persistent identity is that legacy business key and no surrogate identifier
 * exists anywhere - a surrogate would break the record-image-to-table-row correspondence that
 * byte-level output parity depends on.
 *
 * <h2>The create-or-update contract, and why a miss is not a failure</h2>
 * The daily-transaction posting program opens this file for input-output, carries a one-character
 * flag, treats the success status and the record-not-found status <em>alike</em>, and only then
 * branches to either a create or an update operation. Nothing about a missing row is exceptional
 * there, and nothing about it may be exceptional here. The inherited {@code findById} returning an
 * empty {@link Optional}, together with the inherited {@code save}, reproduces both branches through
 * merge semantics, which is why this class asserts the two branches separately and asserts that an
 * absent key throws nothing at all. No upsert, insert or create method is declared anywhere, and no
 * bulk write exists; {@code docs/decision-log.md} DL-192 records why.
 *
 * <h2>Decimal fidelity, and the measured fact that forces a purpose-built fixture</h2>
 * The balance carries nine digits before the implied decimal point and two after, mapped to
 * {@code NUMERIC(11,2)} and to {@link BigDecimal} at scale 2. Across all fifty seeded rows there is
 * <strong>exactly one distinct balance image and it represents zero</strong> - measured over the whole
 * reference fixture, which is 2,550 bytes of 50-byte records. A zero opening balance is the correct
 * pre-posting state, but it means the seed alone can prove nothing about sign or magnitude, so every
 * signed and every large-magnitude assertion here is made against a fixture built for the purpose
 * through {@link TestDataFactory}.
 *
 * <p>A census of the legacy estate found <em>no</em> occurrence of the rounding keyword in any program
 * or copybook, so every store into a two-decimal field truncates toward zero -
 * {@code docs/decision-log.md} DL-013, recorded independently as D-02. This class asserts that outcome
 * numerically rather than naming a rounding mode, which is the stronger form of the check:
 * {@link #anAmountCarryingMoreThanTwoFractionDigitsIsTruncatedTowardZeroRatherThanRounded()} uses a
 * value whose truncated and its nearest-neighbour results differ, so a change of policy cannot pass.
 * No approximate binary numeric type appears anywhere in this file.
 *
 * <h2>Access pattern - stated so that nothing is invented</h2>
 * This table is <strong>batch-only</strong>. It is not among the file resources registered to the
 * online transaction manager, and its Java consumers are the transaction-posting service, the
 * interest-calculation service, the interest-calculation and transaction-validation batch processors,
 * and the file-maintenance service. There is no browse, no paging requirement and no alternate index
 * over it, so this class asserts the absence of a secondary index rather than exercising one, and adds
 * no access path of its own. Every assertion below drives an <em>inherited</em> repository method; the
 * one bounded cursor the interface does declare is covered by {@code BoundedRepositoryCursorIT} and is
 * deliberately not duplicated here.
 *
 * <p>Nor is there an association to traverse. The entity carries no association attribute of any kind
 * and the schema declares no foreign key from this table to a reference table - D-38's decision - so the
 * only referential constraint asserted here is the one that genuinely exists, the account foreign key.
 *
 * <h2>How this class is wired, and what it deliberately does not declare</h2>
 * {@link AbstractPostgresIT} is the single owner of the database server for the whole module, so this
 * class declares no server of its own, no lifecycle for one, and no property source for its address.
 * It contributes a {@code @SpringBootTest} whose configuration is {@link RepositoryUnderTest}, which
 * assembles the real persistence stack - the shipped data source, the shipped Hibernate
 * auto-configuration, every entity of {@code com.carddemo.domain} and every repository of
 * {@code com.carddemo.repository} - against the shared server. The shipped test profile validates the
 * mapping against the migrated schema rather than generating it, so a successful refresh is itself the
 * proof that all four column mappings and the composite identifier binding agree with the migration.
 * No in-memory engine is used and nothing is mocked: a mocked store would prove nothing about what
 * PostgreSQL stores, and generated schema would prove nothing about the migration.
 *
 * <p>Naming that configuration explicitly is a correctness requirement rather than a stylistic
 * preference, and it is the reason every repository test in this package scopes its own context. An
 * unscoped boot component-scans the whole base package, and because a suite run puts the compiled test
 * tree on the same class path as the application, that scan reaches the configuration classes nested
 * inside <em>other</em> test classes. Several of those enable repository scanning over this very
 * package, so two of them contribute the same repository bean definition and the refresh fails with a
 * definition conflict that has nothing to do with the code under test. Scoping the configuration
 * removes the scan, and with it the coupling between unrelated test classes.
 *
 * <p>Nothing here discards the Spring context. Determinism comes instead from two cheaper measures.
 * A single reset at {@link #restoreDeliveredSeed()} returns the eleven application tables to exactly
 * the delivered seed, so the row-count and all-balances-zero assertions hold whatever ran earlier in
 * the shared server's life; and {@link #removeOnlyTheReservedRows()} removes just this class's own
 * reserved keys after every test, so the delivered rows are never disturbed.
 *
 * <p>Test methods are deliberately <strong>not</strong> transactional. A transactional test method
 * would let {@code findById} answer out of the same persistence context's identity map, so a
 * round-trip assertion would compare an object with itself and prove nothing about the stored row.
 * Running without one makes each repository call commit on its own, exactly as the per-record legacy
 * posting does, and makes every read a genuine fresh read from the server.
 *
 * <p>Every reserved key names account {@code 00000000050}, which the seed guarantees to exist, so the
 * account foreign key resolves for every constructed row except the one built to violate it on
 * purpose. The reserved type and category codes sit outside the seeded shape and outside the shapes
 * any other test class constructs, so no two classes contend for a key.
 *
 * @see TransactionCategoryBalanceRepository
 * @see TransactionCategoryBalance
 * @see TransactionCategoryBalanceId
 */
@SpringBootTest(classes = TransactionCategoryBalanceRepositoryIT.RepositoryUnderTest.class)
@DisplayName("Category balance repository: the widest composite key, its exact decimal and its "
        + "create-or-update contract")
final class TransactionCategoryBalanceRepositoryIT extends AbstractPostgresIT {

    private static final String TABLE = "transaction_category_balance";

    private static final String COLLIDING_TABLE = "transaction_category";

    private static final String PARENT_TABLE = "account";

    private static final String PRIMARY_KEY_NAME = "pk_transaction_category_balance";

    private static final String FOREIGN_KEY_NAME = "fk_trancat_balance_account";

    private static final String ACCOUNT_COLUMN = "trancat_acct_id";

    private static final String TYPE_COLUMN = "trancat_type_cd";

    private static final String CATEGORY_COLUMN = "trancat_cd";

    /**
     * Balance column. Note that this one alone carries the {@code tran_cat_} prefix: the asymmetry is
     * the copybook's and is preserved rather than tidied.
     */
    private static final String BALANCE_COLUMN = "tran_cat_bal";

    private static final int ACCOUNT_WIDTH = 11;

    private static final int TYPE_WIDTH = 2;

    private static final int CATEGORY_WIDTH = 4;

    /** Total digit count of the balance column: nine before the implied point and two after. */
    private static final int BALANCE_PRECISION = 11;

    /** Digits after the implied decimal point, in the column and in every stored value. */
    private static final int BALANCE_SCALE = TestDataFactory.MONETARY_SCALE;

    /** Digits before the implied decimal point, which is what bounds the largest storable value. */
    private static final int BALANCE_INTEGER_DIGITS = BALANCE_PRECISION - BALANCE_SCALE;

    private static final int KEY_COLUMN_COUNT = 3;

    private static final int COLLIDING_KEY_COLUMN_COUNT = 2;

    private static final int SEEDED_ROWS = TestDataFactory.SEEDED_FIFTY_ROW_COUNT;

    /** The type code every seeded row carries - measured across the whole reference fixture. */
    private static final String SEEDED_TYPE_CODE = "01";

    /** The category code every seeded row carries - measured across the whole reference fixture. */
    private static final String SEEDED_CATEGORY_CODE = "0001";

    /** The highest seeded account identifier, chosen so the account foreign key always resolves. */
    private static final String RESERVED_ACCOUNT_ID = "00000000050";

    /** Reserved type code, outside the seeded shape, and carrying a leading zero on purpose. */
    private static final String RESERVED_TYPE_CODE = "09";

    private static final String RESERVED_CATEGORY_CREATE = "0005";

    private static final String RESERVED_CATEGORY_SIGNED = "0006";

    private static final String RESERVED_CATEGORY_WIDEST = "0007";

    private static final String RESERVED_CATEGORY_UPDATE = "0008";

    /**
     * Every reserved category code, so cleanup can never fall behind the assertions.
     *
     * <p>Adding a reserved key without adding it here would leave a row behind and make a later
     * row-count assertion fail for a reason unrelated to what it checks.
     */
    private static final List<String> RESERVED_CATEGORY_CODES = List.of(
            RESERVED_CATEGORY_CREATE,
            RESERVED_CATEGORY_SIGNED,
            RESERVED_CATEGORY_WIDEST,
            RESERVED_CATEGORY_UPDATE);

    private static final BigDecimal POSITIVE_BALANCE = new BigDecimal("1234.56");

    private static final BigDecimal NEGATIVE_BALANCE = new BigDecimal("-1234.56");

    /**
     * A negative amount carrying four fraction digits, supplied so the truncation policy is provable.
     *
     * <p>Truncating toward zero yields {@link #TRUNCATED_TOWARD_ZERO}; taking the nearest neighbour
     * instead would yield {@link #NEAREST_NEIGHBOUR_RESULT}. The two differ, so the assertion cannot be
     * satisfied by the wrong policy.
     */
    private static final BigDecimal OVERLONG_NEGATIVE_BALANCE = new BigDecimal("-1234.5678");

    /** What truncation toward zero must produce from {@link #OVERLONG_NEGATIVE_BALANCE}. */
    private static final BigDecimal TRUNCATED_TOWARD_ZERO = new BigDecimal("-1234.56");

    /** What a nearest-neighbour policy would have produced, and must therefore never appear. */
    private static final BigDecimal NEAREST_NEIGHBOUR_RESULT = new BigDecimal("-1234.57");

    /** The largest magnitude the column admits: all nine integer digits, both fraction digits. */
    private static final BigDecimal WIDEST_BALANCE = new BigDecimal("999999999.99");

    /** The plain text form of {@link #WIDEST_BALANCE}, for the no-exponent assertion. */
    private static final String WIDEST_BALANCE_PLAIN = "999999999.99";

    private static final BigDecimal UPDATED_BALANCE = new BigDecimal("77.07");

    /** Zero at the stored scale, which is what all fifty seeded rows must read back as. */
    private static final BigDecimal ZERO_AT_STORED_SCALE = new BigDecimal("0.00");

    /** The repository under test, exercised only through methods it inherits. */
    @Autowired
    private TransactionCategoryBalanceRepository repository;

    /** Used exclusively to read the server's own catalogue, never to reach a mapped row. */
    @Autowired
    private JdbcTemplate jdbcTemplate;

    TransactionCategoryBalanceRepositoryIT() {
    }

    /**
     * The persistence stack this class asserts against, assembled from shipped auto-configuration and
     * nothing else.
     *
     * <p>Four auto-configurations are imported by name rather than being discovered: the data source,
     * the JDBC template used for catalogue reads, the Hibernate integration that validates the mapping
     * against the migrated schema, and transaction management so that each inherited repository call
     * commits on its own. Importing them by name is what keeps this context small enough to be
     * predictable and free of the component scan explained on the enclosing class.
     *
     * <p>Entity scanning and repository scanning are both anchored on a class rather than on a string,
     * so a package rename cannot silently empty either scan. Both are deliberately package-wide rather
     * than narrowed to the one type under test: scanning every entity means the migration is validated
     * against every mapping at refresh, which is a stronger check than validating one.
     *
     * <p>The data source address is not declared here. It arrives from the shared support base, whose
     * published container address outranks every property file, so this configuration cannot reach any
     * server other than the one that base started.
     */
    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration({
            DataSourceAutoConfiguration.class,
            JdbcTemplateAutoConfiguration.class,
            HibernateJpaAutoConfiguration.class,
            TransactionAutoConfiguration.class})
    @EnableJpaRepositories(basePackageClasses = TransactionCategoryBalanceRepository.class)
    @EntityScan(basePackageClasses = TransactionCategoryBalance.class)
    static class RepositoryUnderTest {

        RepositoryUnderTest() {
        }
    }

    /**
     * Returns the shared server to exactly the state the delivered migrations leave it in.
     *
     * <p>The server is shared by every integration test in the module and lives for the whole JVM, so
     * a class that asserts an absolute row count must establish its own starting point rather than
     * inherit whatever the previous class left. This is the base class's own reset, and it is the
     * sanctioned alternative to discarding the Spring context: it empties the eleven application
     * tables and re-applies the two seed migrations, after which this table holds exactly the fifty
     * delivered rows and every one of their balances is zero.
     *
     * @throws SQLException if the shared server cannot be reset
     */
    @BeforeAll
    static void restoreDeliveredSeed() throws SQLException {
        restoreSeededState();
    }

    /**
     * Removes only the rows this class reserves, leaving the delivered seed untouched.
     *
     * <p>Runs after every test so each one starts from the delivered fifty, and runs even when a test
     * fails so a failure cannot cascade into the next assertion. Removal goes through the inherited
     * single-row delete, which is silent when the key is absent, so a test that never inserted its
     * reserved row needs no special case here.
     */
    @AfterEach
    void removeOnlyTheReservedRows() {
        for (final String categoryCode : RESERVED_CATEGORY_CODES) {
            repository.deleteById(reservedKey(categoryCode));
        }
    }

    // -------------------------------------------------------------------------------------------
    // The schema contract: four columns, the prefix asymmetry, the business key, and nothing else.
    // -------------------------------------------------------------------------------------------

    @Test
    @DisplayName("the repository is wired against the migrated schema, and the table carries exactly "
            + "the four mapped columns in record order")
    void theFourColumnMappingsMatchTheMigratedSchema() {
        assertThat(repository)
                .as("a refresh that validated the mapping against the migration must have produced a "
                        + "repository for it")
                .isNotNull();

        assertThat(columnNamesInOrder(TABLE))
                .as("the record maps onto exactly four columns, in the order the 50-byte image lays "
                        + "them out; the 22-byte trailing filler at offset 28 is not a column, and the "
                        + "%s prefix on the three key columns beside %s on the balance is the "
                        + "copybook's own inconsistency, preserved rather than regularised",
                        "trancat_", BALANCE_COLUMN)
                .containsExactly(ACCOUNT_COLUMN, TYPE_COLUMN, CATEGORY_COLUMN, BALANCE_COLUMN);

        assertThat(characterWidthOf(TABLE, ACCOUNT_COLUMN))
                .as("the account identifier occupies 11 bytes at offset 0 of the key")
                .isEqualTo(ACCOUNT_WIDTH);
        assertThat(characterWidthOf(TABLE, TYPE_COLUMN))
                .as("the transaction type code occupies 2 bytes at offset 11 of the key")
                .isEqualTo(TYPE_WIDTH);
        assertThat(characterWidthOf(TABLE, CATEGORY_COLUMN))
                .as("the category code occupies 4 bytes at offset 13 of the key")
                .isEqualTo(CATEGORY_WIDTH);
        assertThat(ACCOUNT_WIDTH + TYPE_WIDTH + CATEGORY_WIDTH)
                .as("and the three widths must sum to the declared key width the cluster definition "
                        + "states at offset 0")
                .isEqualTo(TestDataFactory.CATEGORY_BALANCE_KEY_WIDTH);

        assertThat(numericPrecisionOf(TABLE, BALANCE_COLUMN))
                .as("the balance carries %d digits before the implied decimal point and %d after",
                        BALANCE_INTEGER_DIGITS, BALANCE_SCALE)
                .isEqualTo(BALANCE_PRECISION);
        assertThat(numericScaleOf(TABLE, BALANCE_COLUMN))
                .as("an exact decimal at a declared scale is the only admissible form for a monetary "
                        + "field here")
                .isEqualTo(BALANCE_SCALE);
    }

    @Test
    @DisplayName("the composite key is the legacy business key: three key columns leading with the "
            + "account identifier, and no generated identifier anywhere")
    void theCompositeKeyIsTheBusinessKeyAndNothingIsGenerated() {
        assertThat(primaryKeyColumnsInOrder(TABLE))
                .as("the key is the 17-byte leading substring of the record image, so its columns are "
                        + "the three components in their contractual order under constraint %s",
                        PRIMARY_KEY_NAME)
                .containsExactly(ACCOUNT_COLUMN, TYPE_COLUMN, CATEGORY_COLUMN)
                .hasSize(KEY_COLUMN_COUNT);

        assertThat(identityColumnsOf(TABLE))
                .as("no surrogate identifier exists: a generated key would break the correspondence "
                        + "between the record image and the table row that byte-level parity depends on")
                .isEmpty();
        assertThat(columnsWithADefaultOf(TABLE))
                .as("and no column is defaulted either, so every stored value came from a caller; the "
                        + "version column that optimistic locking needs belongs to the account and card "
                        + "tables alone and is deliberately absent here")
                .isEmpty();
    }

    @Test
    @DisplayName("the 17-byte key leads with the account identifier and shares no leading column with "
            + "the 6-byte key that carries the same legacy group name")
    void theSeventeenByteKeyLeadsWithTheAccountIdentifierAndSharesNoLeadingColumnWithTheSixByteKey() {
        final List<String> wideKey = primaryKeyColumnsInOrder(TABLE);
        final List<String> narrowKey = primaryKeyColumnsInOrder(COLLIDING_TABLE);

        assertThat(wideKey)
                .as("this key has three components and begins with the account identifier")
                .hasSize(KEY_COLUMN_COUNT)
                .first()
                .isEqualTo(ACCOUNT_COLUMN);
        assertThat(narrowKey)
                .as("the reference table's key has two components and holds no account identifier at "
                        + "all, which is why it can never be reused here")
                .hasSize(COLLIDING_KEY_COLUMN_COUNT)
                .noneMatch(column -> column.contains("acct"));

        assertThat(wideKey.get(0))
                .as("the two keys share no leading column, so the 6-byte key is not a prefix of the "
                        + "17-byte key and the two layouts align at no offset; the identical legacy "
                        + "group name is a coincidence and must never be read as a relationship")
                .isNotEqualTo(narrowKey.get(0));

        assertThat(ACCOUNT_WIDTH + TYPE_WIDTH + CATEGORY_WIDTH)
                .as("17 bytes here")
                .isEqualTo(TestDataFactory.CATEGORY_BALANCE_KEY_WIDTH);
        assertThat(TYPE_WIDTH + CATEGORY_WIDTH)
                .as("against 6 bytes there, the difference being exactly the 11-byte account "
                        + "identifier this key leads with")
                .isEqualTo(TestDataFactory.TRANSACTION_CATEGORY_KEY_WIDTH);
        assertThat(TestDataFactory.CATEGORY_BALANCE_KEY_WIDTH
                        - TestDataFactory.TRANSACTION_CATEGORY_KEY_WIDTH)
                .as("and that difference is the account identifier's own width")
                .isEqualTo(ACCOUNT_WIDTH);
    }

    @Test
    @DisplayName("the account foreign key exists by name, points at the account table, and rejects an "
            + "account identifier no account row carries")
    void theAccountForeignKeyExistsByNameAndIsEnforced() {
        assertThat(foreignKeyNamesOf(TABLE))
                .as("the migration declares exactly one foreign key on this table")
                .containsExactly(FOREIGN_KEY_NAME);
        assertThat(referencedTableOf(FOREIGN_KEY_NAME))
                .as("every category balance belongs to an existing account, so %s points at %s",
                        ACCOUNT_COLUMN, PARENT_TABLE)
                .isEqualTo(PARENT_TABLE);

        final long before = repository.count();
        final TransactionCategoryBalanceId orphanKey = new TransactionCategoryBalanceId(
                TestDataFactory.UNKNOWN_ACCOUNT_ID, RESERVED_TYPE_CODE, RESERVED_CATEGORY_CREATE);
        final TransactionCategoryBalance orphan = TestDataFactory.transactionCategoryBalance()
                .accountId(TestDataFactory.UNKNOWN_ACCOUNT_ID)
                .typeCode(RESERVED_TYPE_CODE)
                .categoryCode(RESERVED_CATEGORY_CREATE)
                .balance(POSITIVE_BALANCE)
                .build();

        assertThatExceptionOfType(DataIntegrityViolationException.class)
                .as("the constraint is enforced rather than merely declared, so a balance for an "
                        + "account that does not exist cannot be stored")
                .isThrownBy(() -> repository.saveAndFlush(orphan))
                .withStackTraceContaining(FOREIGN_KEY_NAME);

        assertThat(repository.findById(orphanKey))
                .as("and the rejected row left nothing behind")
                .isEmpty();
        assertThat(repository.count())
                .as("so the row count is exactly what it was before the attempt")
                .isEqualTo(before);
    }

    @Test
    @DisplayName("the table carries no secondary index: its only index is the one its primary key "
            + "needs, because no browse or alternate-index access path exists over it")
    void theTableCarriesNoSecondaryIndex() {
        assertThat(indexNamesOf(TABLE))
                .as("this table is read by the batch tier through the complete key or a sequential "
                        + "scan only. It is not registered to the online transaction manager, it has no "
                        + "alternate index in the legacy estate, and the three secondary indexes the "
                        + "index migration creates belong to the card, cross-reference and transaction "
                        + "tables. An index here would serve an access path nothing takes")
                .containsExactly(PRIMARY_KEY_NAME);
    }

    // -------------------------------------------------------------------------------------------
    // Composite-key mechanics: component order, exact widths, and the fixed-width padding contract.
    // -------------------------------------------------------------------------------------------

    @Test
    @DisplayName("the seeded volume is exactly fifty rows, all fifty composite keys distinct")
    void theSeededVolumeIsFiftyRowsWithFiftyDistinctKeys() {
        assertThat(repository.count())
                .as("the reference fixture is 2,550 bytes of 50-byte records, which is %d rows",
                        SEEDED_ROWS)
                .isEqualTo(SEEDED_ROWS);

        final List<TransactionCategoryBalance> rows = repository.findAll();
        assertThat(rows)
                .as("and a full read returns every one of them")
                .hasSize(SEEDED_ROWS);

        final Set<TransactionCategoryBalanceId> keys = new HashSet<>();
        for (final TransactionCategoryBalance row : rows) {
            keys.add(row.toId());
        }
        assertThat(keys)
                .as("no two rows share a composite key. Equality here compares all three components "
                        + "with no normalisation, so a collapsed or normalised component would show up "
                        + "as a shortfall in this count")
                .hasSize(SEEDED_ROWS);
    }

    @Test
    @DisplayName("a complete composite key resolves its row, and the three components measure eleven, "
            + "two and four characters summing to the seventeen-byte key width")
    void aCompleteCompositeKeyResolvesAndItsComponentsCarryTheDeclaredWidths() {
        final String accountId = RESERVED_ACCOUNT_ID;
        final TransactionCategoryBalanceId key = new TransactionCategoryBalanceId(
                accountId, SEEDED_TYPE_CODE, SEEDED_CATEGORY_CODE);

        final Optional<TransactionCategoryBalance> found = repository.findById(key);
        assertThat(found)
                .as("the complete three-part key is the only read path this table offers, and it must "
                        + "resolve a seeded row")
                .isPresent();

        final TransactionCategoryBalance row = found.orElseThrow();
        assertThat(row.getTrancatAcctId())
                .as("the account identifier component reads back exactly as seeded")
                .isEqualTo(accountId)
                .hasSize(ACCOUNT_WIDTH);
        assertThat(row.getTrancatTypeCd())
                .as("the transaction type code component reads back exactly as seeded, leading zero "
                        + "included")
                .isEqualTo(SEEDED_TYPE_CODE)
                .hasSize(TYPE_WIDTH);
        assertThat(row.getTrancatCd())
                .as("the category code component reads back exactly as seeded, leading zeros included")
                .isEqualTo(SEEDED_CATEGORY_CODE)
                .hasSize(CATEGORY_WIDTH);

        final int measuredKeyWidth = row.getTrancatAcctId().length()
                + row.getTrancatTypeCd().length()
                + row.getTrancatCd().length();
        assertThat(measuredKeyWidth)
                .as("and the three measured widths sum to the key width the cluster definition declares "
                        + "at offset 0 of the 50-byte record")
                .isEqualTo(TestDataFactory.CATEGORY_BALANCE_KEY_WIDTH);

        assertThat(row.toId())
                .as("the identifier the entity derives from those components equals the one the read "
                        + "was made with, which is what keeps the persistence identity map coherent")
                .isEqualTo(key);
    }

    @Test
    @DisplayName("permuting the type and category components resolves nothing, which locks the "
            + "constructor argument order against a future refactor")
    void permutingTheTypeAndCategoryComponentsResolvesNothing() {
        final TransactionCategoryBalanceId correctOrder = new TransactionCategoryBalanceId(
                RESERVED_ACCOUNT_ID, SEEDED_TYPE_CODE, SEEDED_CATEGORY_CODE);
        final TransactionCategoryBalanceId permuted = new TransactionCategoryBalanceId(
                RESERVED_ACCOUNT_ID, SEEDED_CATEGORY_CODE, SEEDED_TYPE_CODE);

        assertThat(repository.findById(correctOrder))
                .as("account identifier, then TYPE code, then CATEGORY code is the contractual order, "
                        + "fixed by the record layout and by the primary-key column order")
                .isPresent();
        assertThat(repository.findById(permuted))
                .as("swapping the second and third arguments must resolve nothing. Were the two ever "
                        + "transposed in the constructor, this assertion is what fails rather than a "
                        + "posting run silently accumulating a balance against the wrong category")
                .isEmpty();
        assertThat(permuted)
                .as("and the two identifiers are not equal, so the swap is visible in the key itself")
                .isNotEqualTo(correctOrder);
    }

    @Test
    @DisplayName("an absent composite key yields an empty result and throws nothing, because a miss is "
            + "an ordinary outcome at this boundary rather than a failure")
    void anAbsentCompositeKeyYieldsAnEmptyResultAndThrowsNothing() {
        final TransactionCategoryBalanceId absent = reservedKey(RESERVED_CATEGORY_CREATE);

        assertThatCode(() -> repository.findById(absent))
                .as("the posting program treats the record-not-found status exactly as it treats "
                        + "success and then branches to a create, so a miss must never surface here as "
                        + "an exception")
                .doesNotThrowAnyException();
        assertThat(repository.findById(absent))
                .as("and the miss is reported as an empty result")
                .isEmpty();
        assertThat(repository.existsById(absent))
                .as("with the existence check agreeing")
                .isFalse();
        assertThat(repository.count())
                .as("a read that found nothing changed nothing")
                .isEqualTo(SEEDED_ROWS);
    }

    @Test
    @DisplayName("fixed-width components keep their padding: a leading-zero account identifier, a "
            + "four-character category code and a leading-zero type code never match unpadded forms")
    void fixedWidthComponentsNeverMatchTheirUnpaddedForms() {
        final TransactionCategoryBalanceId key = reservedKey(RESERVED_CATEGORY_CREATE);
        repository.save(TestDataFactory.transactionCategoryBalance()
                .accountId(RESERVED_ACCOUNT_ID)
                .typeCode(RESERVED_TYPE_CODE)
                .categoryCode(RESERVED_CATEGORY_CREATE)
                .balance(POSITIVE_BALANCE)
                .build());

        final TransactionCategoryBalance stored = repository.findById(key).orElseThrow();
        assertThat(stored.getTrancatAcctId())
                .as("an eleven-character account identifier round-trips with its leading zeros intact")
                .isEqualTo(RESERVED_ACCOUNT_ID);
        assertThat(stored.getTrancatCd())
                .as("a four-character category code round-trips unchanged")
                .isEqualTo(RESERVED_CATEGORY_CREATE);
        assertThat(stored.getTrancatTypeCd())
                .as("and a two-character type code retains its leading zero")
                .isEqualTo(RESERVED_TYPE_CODE);

        assertThat(repository.findById(new TransactionCategoryBalanceId(
                        "50", RESERVED_TYPE_CODE, RESERVED_CATEGORY_CREATE)))
                .as("the unpadded account identifier is a different key: '50' and '%s' are not the same "
                        + "eleven bytes of a record image, and the columns are bounded variable-length "
                        + "rather than blank-padded precisely so that a shorter value cannot pass as the "
                        + "padded one", RESERVED_ACCOUNT_ID)
                .isEmpty();
        assertThat(repository.findById(new TransactionCategoryBalanceId(
                        RESERVED_ACCOUNT_ID, RESERVED_TYPE_CODE, "5")))
                .as("'5' is not the category code '%s'", RESERVED_CATEGORY_CREATE)
                .isEmpty();
        assertThat(repository.findById(new TransactionCategoryBalanceId(
                        RESERVED_ACCOUNT_ID, "9", RESERVED_CATEGORY_CREATE)))
                .as("and '9' is not the type code '%s'", RESERVED_TYPE_CODE)
                .isEmpty();
    }

    // -------------------------------------------------------------------------------------------
    // Decimal fidelity: the all-zero opening image, signed round trips, and truncation toward zero.
    // -------------------------------------------------------------------------------------------

    @Test
    @DisplayName("every one of the fifty seeded balances reads back as zero at the stored scale, which "
            + "is the correct pre-posting opening state and the reason a purpose-built fixture is needed")
    void everySeededBalanceReadsBackAsZeroAtTheStoredScale() {
        final List<TransactionCategoryBalance> rows = repository.findAll();
        assertThat(rows).hasSize(SEEDED_ROWS);

        final Set<BigDecimal> distinctBalances = new HashSet<>();
        for (final TransactionCategoryBalance row : rows) {
            final BigDecimal balance = row.getTranCatBal();
            assertThat(balance)
                    .as("a not-null column holding the legacy zoned field, which carries zeros rather "
                            + "than nothing")
                    .isNotNull();
            assertThat(balance.scale())
                    .as("the declared scale is part of the contract, so the read must report it rather "
                            + "than a value that merely compares equal")
                    .isEqualTo(BALANCE_SCALE);
            assertThat(balance)
                    .as("and every opening balance is exactly zero at that scale")
                    .isEqualTo(ZERO_AT_STORED_SCALE);
            distinctBalances.add(balance);
        }

        assertThat(distinctBalances)
                .as("measured across the whole reference fixture there is exactly one distinct balance "
                        + "image and it represents zero. That is why no signed or large-magnitude claim "
                        + "in this class is made against the seed")
                .containsExactly(ZERO_AT_STORED_SCALE);
    }

    @Test
    @DisplayName("a purpose-built non-zero balance round-trips at the stored scale with all four "
            + "persisted properties intact")
    void aPurposeBuiltNonZeroBalanceRoundTripsWithEveryPropertyIntact() {
        final TransactionCategoryBalanceId key = reservedKey(RESERVED_CATEGORY_CREATE);
        repository.save(TestDataFactory.transactionCategoryBalance()
                .accountId(RESERVED_ACCOUNT_ID)
                .typeCode(RESERVED_TYPE_CODE)
                .categoryCode(RESERVED_CATEGORY_CREATE)
                .balance(POSITIVE_BALANCE)
                .build());

        final TransactionCategoryBalance reloaded = repository.findById(key).orElseThrow();
        assertThat(reloaded.getTrancatAcctId()).isEqualTo(RESERVED_ACCOUNT_ID);
        assertThat(reloaded.getTrancatTypeCd()).isEqualTo(RESERVED_TYPE_CODE);
        assertThat(reloaded.getTrancatCd()).isEqualTo(RESERVED_CATEGORY_CREATE);
        assertThat(reloaded.getTranCatBal())
                .as("the balance survives the round trip as an exact decimal, at the declared scale and "
                        + "with the declared value; strict equality is asserted rather than a numeric "
                        + "comparison, because the scale is part of what must survive")
                .isEqualTo(POSITIVE_BALANCE);
        assertThat(reloaded.getTranCatBal().scale())
                .as("stated separately so a scale drift is reported as a scale drift")
                .isEqualTo(BALANCE_SCALE);
        assertThat(reloaded.getTranCatBal().signum())
                .as("and a positive amount stays positive")
                .isPositive();
    }

    @Test
    @DisplayName("a negative balance keeps its sign through a store-and-reload cycle")
    void aNegativeBalanceKeepsItsSignThroughAStoreAndReloadCycle() {
        final TransactionCategoryBalanceId key = reservedKey(RESERVED_CATEGORY_SIGNED);
        repository.save(TestDataFactory.transactionCategoryBalance()
                .accountId(RESERVED_ACCOUNT_ID)
                .typeCode(RESERVED_TYPE_CODE)
                .categoryCode(RESERVED_CATEGORY_SIGNED)
                .balance(NEGATIVE_BALANCE)
                .build());

        final BigDecimal reloaded = repository.findById(key).orElseThrow().getTranCatBal();
        assertThat(reloaded)
                .as("the legacy field is signed, and a returned purchase drives the balance below zero, "
                        + "so the sign is business data rather than a presentation detail")
                .isEqualTo(NEGATIVE_BALANCE);
        assertThat(reloaded.signum())
                .as("stated separately so a lost sign is reported as a lost sign rather than as a value "
                        + "mismatch")
                .isNegative();
        assertThat(reloaded.abs())
                .as("with the magnitude unchanged, which is what distinguishes a lost sign from a lost "
                        + "value")
                .isEqualTo(POSITIVE_BALANCE);
        assertThat(reloaded.scale())
                .as("and the scale is still the declared one")
                .isEqualTo(BALANCE_SCALE);
    }

    @Test
    @DisplayName("an amount carrying more than two fraction digits is truncated toward zero rather "
            + "than taken to its nearest neighbour")
    void anAmountCarryingMoreThanTwoFractionDigitsIsTruncatedTowardZeroRatherThanRounded() {
        final TransactionCategoryBalanceId key = reservedKey(RESERVED_CATEGORY_SIGNED);
        repository.save(TestDataFactory.transactionCategoryBalance()
                .accountId(RESERVED_ACCOUNT_ID)
                .typeCode(RESERVED_TYPE_CODE)
                .categoryCode(RESERVED_CATEGORY_SIGNED)
                .balance(OVERLONG_NEGATIVE_BALANCE)
                .build());

        final BigDecimal reloaded = repository.findById(key).orElseThrow().getTranCatBal();
        assertThat(reloaded)
                .as("a census of every program and copybook in the legacy estate found no occurrence "
                        + "of the rounding keyword, so an arithmetic store into a two-decimal field "
                        + "truncates toward zero. This is the interest path's own receiving field, and "
                        + "the division that feeds it is exactly where a longer scale arrives")
                .isEqualTo(TRUNCATED_TOWARD_ZERO);
        assertThat(reloaded)
                .as("taking the nearest neighbour instead would differ by one hundredth on roughly half "
                        + "of all interest computations, which is a byte-parity failure invisible to a "
                        + "suite written under the same assumption")
                .isNotEqualTo(NEAREST_NEIGHBOUR_RESULT);
        assertThat(reloaded.scale())
                .as("and truncation lands on the declared scale rather than merely near it")
                .isEqualTo(BALANCE_SCALE);
    }

    @Test
    @DisplayName("a value using all nine integer digits survives without loss or overflow, and no "
            + "stored balance is ever rendered with an exponent")
    void theWidestStorableValueSurvivesAndIsNeverRenderedWithAnExponent() {
        final TransactionCategoryBalanceId key = reservedKey(RESERVED_CATEGORY_WIDEST);
        repository.save(TestDataFactory.transactionCategoryBalance()
                .accountId(RESERVED_ACCOUNT_ID)
                .typeCode(RESERVED_TYPE_CODE)
                .categoryCode(RESERVED_CATEGORY_WIDEST)
                .balance(WIDEST_BALANCE)
                .build());

        final BigDecimal reloaded = repository.findById(key).orElseThrow().getTranCatBal();
        assertThat(reloaded)
                .as("the widest value the legacy field admits is %d integer digits and %d fraction "
                        + "digits, and it must survive the column intact",
                        BALANCE_INTEGER_DIGITS, BALANCE_SCALE)
                .isEqualTo(WIDEST_BALANCE);
        assertThat(reloaded.precision() - reloaded.scale())
                .as("using every one of the integer digits the column declares")
                .isEqualTo(BALANCE_INTEGER_DIGITS);
        assertThat(reloaded.scale()).isEqualTo(BALANCE_SCALE);

        assertThat(reloaded.toPlainString())
                .as("the fixed-width record image is written from the plain text form, so a value that "
                        + "rendered with an exponent would corrupt the 11-byte balance field of a "
                        + "50-byte record")
                .isEqualTo(WIDEST_BALANCE_PLAIN)
                .doesNotContain("E")
                .doesNotContain("e");
        assertThat(reloaded.toString())
                .as("and the ordinary text form agrees, so no caller can obtain an exponent form by "
                        + "accident")
                .isEqualTo(WIDEST_BALANCE_PLAIN);
    }

    // -------------------------------------------------------------------------------------------
    // The create-or-update contract: one read, two branches, and a miss that is not a failure.
    // -------------------------------------------------------------------------------------------

    @Test
    @DisplayName("the create branch: storing an absent composite key inserts a row, the key then "
            + "resolves, and the row count rises by exactly one")
    void theCreateBranchInsertsAndRaisesTheRowCountByExactlyOne() {
        final TransactionCategoryBalanceId key = reservedKey(RESERVED_CATEGORY_CREATE);
        final long before = repository.count();

        assertThat(repository.findById(key))
                .as("the read that precedes the branch finds nothing, which is the condition the "
                        + "posting program's one-character flag records")
                .isEmpty();

        repository.save(TestDataFactory.transactionCategoryBalance()
                .accountId(RESERVED_ACCOUNT_ID)
                .typeCode(RESERVED_TYPE_CODE)
                .categoryCode(RESERVED_CATEGORY_CREATE)
                .balance(POSITIVE_BALANCE)
                .build());

        assertThat(repository.findById(key))
                .as("after the store the key resolves, so the miss was answered by a create rather "
                        + "than by an error")
                .isPresent()
                .get()
                .extracting(TransactionCategoryBalance::getTranCatBal)
                .isEqualTo(POSITIVE_BALANCE);
        assertThat(repository.count())
                .as("exactly one row was added; merge semantics cover the create branch without any "
                        + "insert, upsert or bulk-write method being declared anywhere")
                .isEqualTo(before + 1L);
    }

    @Test
    @DisplayName("the update branch: storing a composite key that already exists rewrites it in place "
            + "and leaves the row count unchanged")
    void theUpdateBranchRewritesInPlaceAndLeavesTheRowCountUnchanged() {
        final TransactionCategoryBalanceId key = reservedKey(RESERVED_CATEGORY_UPDATE);
        repository.save(TestDataFactory.transactionCategoryBalance()
                .accountId(RESERVED_ACCOUNT_ID)
                .typeCode(RESERVED_TYPE_CODE)
                .categoryCode(RESERVED_CATEGORY_UPDATE)
                .balance(POSITIVE_BALANCE)
                .build());

        final long afterInsert = repository.count();
        assertThat(repository.existsById(key))
                .as("the second store meets an existing key, which is the other branch the posting "
                        + "program takes from the same read")
                .isTrue();

        repository.save(TestDataFactory.transactionCategoryBalance()
                .accountId(RESERVED_ACCOUNT_ID)
                .typeCode(RESERVED_TYPE_CODE)
                .categoryCode(RESERVED_CATEGORY_UPDATE)
                .balance(UPDATED_BALANCE)
                .build());

        assertThat(repository.findById(key))
                .as("the balance moved to the new value")
                .isPresent()
                .get()
                .extracting(TransactionCategoryBalance::getTranCatBal)
                .isEqualTo(UPDATED_BALANCE);
        assertThat(repository.count())
                .as("and no row was added: the store rewrote the existing row in place rather than "
                        + "creating a second one under the same key")
                .isEqualTo(afterInsert);

        final List<TransactionCategoryBalance> matching = new ArrayList<>();
        for (final TransactionCategoryBalance row : repository.findAll()) {
            if (key.equals(row.toId())) {
                matching.add(row);
            }
        }
        assertThat(matching)
                .as("stated over a full read as well, so a duplicate under the same composite key "
                        + "could not hide behind an unchanged total")
                .hasSize(1);
    }

    // -------------------------------------------------------------------------------------------
    // Helpers. Every statement below is a complete literal with bound parameters: no value is ever
    // concatenated into SQL, and no statement reaches a mapped row - the catalogue only.
    // -------------------------------------------------------------------------------------------

    /**
     * Builds one of this class's reserved composite keys.
     *
     * <p>The account component is always a seeded account, so the account foreign key resolves; only
     * the category component varies, which is what keeps the reserved range disjoint from the seeded
     * shape and from the shapes other test classes construct.
     *
     * @param categoryCode one of the four reserved category codes
     * @return the reserved composite key for that category
     */
    private static TransactionCategoryBalanceId reservedKey(final String categoryCode) {
        return new TransactionCategoryBalanceId(
                RESERVED_ACCOUNT_ID, RESERVED_TYPE_CODE, categoryCode);
    }

    /**
     * Reads a table's column names in the order the migration declares them.
     *
     * @param table the table to describe
     * @return the column names in declaration order
     */
    private List<String> columnNamesInOrder(final String table) {
        return jdbcTemplate.queryForList(
                "SELECT column_name FROM information_schema.columns"
                        + " WHERE table_schema = 'public' AND table_name = ?"
                        + " ORDER BY ordinal_position",
                String.class, table);
    }

    /**
     * Reads the declared maximum length of a bounded text column.
     *
     * @param table  the table holding the column
     * @param column the column to measure
     * @return the declared maximum length in characters
     */
    private int characterWidthOf(final String table, final String column) {
        final Integer width = jdbcTemplate.queryForObject(
                "SELECT character_maximum_length FROM information_schema.columns"
                        + " WHERE table_schema = 'public' AND table_name = ? AND column_name = ?",
                Integer.class, table, column);
        assertThat(width)
                .as("column %s of table %s must be a bounded text column", column, table)
                .isNotNull();
        return width.intValue();
    }

    /**
     * Reads the total digit count declared for an exact numeric column.
     *
     * @param table  the table holding the column
     * @param column the column to describe
     * @return the declared precision
     */
    private int numericPrecisionOf(final String table, final String column) {
        final Integer precision = jdbcTemplate.queryForObject(
                "SELECT numeric_precision FROM information_schema.columns"
                        + " WHERE table_schema = 'public' AND table_name = ? AND column_name = ?",
                Integer.class, table, column);
        assertThat(precision)
                .as("column %s of table %s must be an exact numeric column", column, table)
                .isNotNull();
        return precision.intValue();
    }

    /**
     * Reads the declared number of fraction digits of an exact numeric column.
     *
     * @param table  the table holding the column
     * @param column the column to describe
     * @return the declared scale
     */
    private int numericScaleOf(final String table, final String column) {
        final Integer scale = jdbcTemplate.queryForObject(
                "SELECT numeric_scale FROM information_schema.columns"
                        + " WHERE table_schema = 'public' AND table_name = ? AND column_name = ?",
                Integer.class, table, column);
        assertThat(scale)
                .as("column %s of table %s must declare its fraction digits", column, table)
                .isNotNull();
        return scale.intValue();
    }

    /**
     * Reads a table's primary-key columns in key order.
     *
     * <p>Ordering is by the position of the column within the key rather than by its position in the
     * table, because it is the key order that the record layout fixes and that a composite identifier
     * must match.
     *
     * @param table the table to describe
     * @return the primary-key column names, in key order
     */
    private List<String> primaryKeyColumnsInOrder(final String table) {
        return jdbcTemplate.queryForList(
                "SELECT attribute.attname FROM pg_constraint constraint_"
                        + " JOIN pg_class relation ON relation.oid = constraint_.conrelid"
                        + " JOIN pg_namespace space ON space.oid = relation.relnamespace"
                        + " JOIN LATERAL unnest(constraint_.conkey)"
                        + "      WITH ORDINALITY AS key_column(attnum, position_) ON TRUE"
                        + " JOIN pg_attribute attribute ON attribute.attrelid = relation.oid"
                        + "      AND attribute.attnum = key_column.attnum"
                        + " WHERE space.nspname = 'public' AND relation.relname = ?"
                        + "   AND constraint_.contype = 'p'"
                        + " ORDER BY key_column.position_",
                String.class, table);
    }

    /**
     * Reads the names of every foreign key declared on a table.
     *
     * @param table the table to describe
     * @return the foreign-key constraint names, sorted
     */
    private List<String> foreignKeyNamesOf(final String table) {
        return jdbcTemplate.queryForList(
                "SELECT constraint_.conname FROM pg_constraint constraint_"
                        + " JOIN pg_class relation ON relation.oid = constraint_.conrelid"
                        + " JOIN pg_namespace space ON space.oid = relation.relnamespace"
                        + " WHERE space.nspname = 'public' AND relation.relname = ?"
                        + "   AND constraint_.contype = 'f'"
                        + " ORDER BY constraint_.conname",
                String.class, table);
    }

    /**
     * Reads the table a named foreign key points at.
     *
     * @param constraintName the foreign-key constraint name
     * @return the referenced table name
     */
    private String referencedTableOf(final String constraintName) {
        return jdbcTemplate.queryForObject(
                "SELECT parent.relname FROM pg_constraint constraint_"
                        + " JOIN pg_class parent ON parent.oid = constraint_.confrelid"
                        + " JOIN pg_namespace space ON space.oid = parent.relnamespace"
                        + " WHERE space.nspname = 'public' AND constraint_.conname = ?"
                        + "   AND constraint_.contype = 'f'",
                String.class, constraintName);
    }

    /**
     * Reads the names of every index on a table, the primary key's own index included.
     *
     * @param table the table to describe
     * @return the index names, sorted
     */
    private List<String> indexNamesOf(final String table) {
        return jdbcTemplate.queryForList(
                "SELECT indexname FROM pg_indexes"
                        + " WHERE schemaname = 'public' AND tablename = ?"
                        + " ORDER BY indexname",
                String.class, table);
    }

    /**
     * Reads the names of any generated-identity columns on a table.
     *
     * @param table the table to describe
     * @return the identity column names, which must be empty for every table in this schema
     */
    private List<String> identityColumnsOf(final String table) {
        return jdbcTemplate.queryForList(
                "SELECT column_name FROM information_schema.columns"
                        + " WHERE table_schema = 'public' AND table_name = ?"
                        + "   AND (is_identity = 'YES' OR is_generated <> 'NEVER')"
                        + " ORDER BY column_name",
                String.class, table);
    }

    /**
     * Reads the names of any columns on a table carrying a declared default.
     *
     * @param table the table to describe
     * @return the names of columns with a default, sorted
     */
    private List<String> columnsWithADefaultOf(final String table) {
        return jdbcTemplate.queryForList(
                "SELECT column_name FROM information_schema.columns"
                        + " WHERE table_schema = 'public' AND table_name = ?"
                        + "   AND column_default IS NOT NULL"
                        + " ORDER BY column_name",
                String.class, table);
    }
}
