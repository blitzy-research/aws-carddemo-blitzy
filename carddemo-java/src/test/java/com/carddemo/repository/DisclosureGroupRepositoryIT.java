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
import com.carddemo.domain.DisclosureGroup;
import com.carddemo.domain.id.DisclosureGroupId;
import com.carddemo.support.AbstractPostgresIT;
import com.carddemo.support.TestDataFactory;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Integration contract for {@link DisclosureGroupRepository}, read through the repository bean itself
 * against a real PostgreSQL 16 server carrying the migrated schema and the delivered reference seed.
 *
 * <p>This is the most parity-critical repository in the package, and the reason is narrow: two of its
 * three key components are short character values whose <em>padding</em> and whose <em>position</em>
 * decide which row a lookup returns, and a defect in either is invisible. A transposed component still
 * compiles, a shortened identifier still reads like the value a human would type, and both resolve
 * <em>nothing</em> at run time rather than resolving the wrong thing loudly. The interest-accrual tier
 * treats an empty result as "fall back to the default group", so a lookup broken in either of those two
 * ways degrades into a silently different interest run instead of a failure. Every assertion below
 * exists to make one of those two defects impossible to introduce without turning this file red.
 *
 * <h2>The record this table came from</h2>
 * The legacy disclosure-group record is 50 bytes. Its key is 16 bytes beginning at offset 0, composed of
 * a 10-byte account-group identifier at offset 0, a 2-byte transaction-type code at offset 10 and a
 * 4-byte transaction-category code at offset 12. The interest rate occupies 6 bytes at offset 16 as a
 * signed external-decimal value with two implied decimal places, and a 28-byte filler at offset 22 is
 * <strong>not</strong> persisted - it has no column and no attribute. The arithmetic closes exactly:
 * 10 + 2 + 4 = 16 for the key, and 16 + 6 + 28 = 50 for the record. The legacy indexed cluster is
 * provisioned with a declared key length of 16 at offset 0 over a 50-byte record, which independently
 * confirms both that the key is the leading substring of the image and that these three components in
 * this order make up that substring.
 *
 * <h2>Trap one - the ten-character group identifier is padded, and the padding is the key</h2>
 * The seed loads three group identifiers, each occupying the full ten characters:
 * {@code "A000000000"} needs no padding, while the seven-character fallback and zero-rate identifiers
 * are each followed by three spaces. Seventeen rows carry each identifier, so the table holds exactly
 * fifty-one.
 *
 * <p>The padded form is not an artefact of how the seed was written - it is what the accrual program
 * actually looks up. That program assigns a seven-character literal into the 10-byte alphanumeric
 * group-identifier field, and a legacy alphanumeric assignment into a longer field left-justifies and
 * space-fills, so the value the retry searches for is the ten-character padded one. The schema column is
 * therefore a bounded variable-length character type and deliberately <em>not</em> a blank-padded fixed
 * one: a blank-padded column compares with implicit padding and would make the padded and shortened
 * forms equal, erasing the distinction these tests exist to prove. Accordingly <strong>no assertion in
 * this file trims, strips, pads, folds or normalises any key component</strong>, and the tests assert
 * both directions - the padded form resolves a row and the shortened form resolves none.
 *
 * <h2>Trap two - assignment order is not key order</h2>
 * The accrual program populates its lookup key with three consecutive assignments whose textual order is
 * group, then <em>category</em>, then <em>type</em>. That is category-before-type and it is not the order
 * of the key. The key's order is group, then <strong>type</strong>, then <strong>category</strong>, fixed
 * three independent ways: by the copybook's declaration order and widths, by the cluster's declared key
 * length taken with those widths, and by the accrual program's own file-section declaration. The
 * primary-key column order of the table agrees, and this file asserts that column order from the
 * catalogue so the claim is measured rather than restated.
 *
 * <p>{@link DisclosureGroupId}'s constructor takes group, type, category, and that order must never be
 * "corrected" to follow the assignment sequence. Three assignments to three distinct named fields carry
 * no ordering semantics at all. Two tests below lock the ordering: one shows that supplying the
 * components in assignment order resolves nothing, and the other shows that the two short slots are not
 * interchangeable, because the seeded row whose type carries the significant digit and the seeded row
 * whose category carries it hold <em>different</em> rates.
 *
 * <h2>Why no foreign key may ever be created from the account table to this one</h2>
 * The account table's group identifier is not a foreign key here and cannot usefully become one, for two
 * independent reasons, and both are asserted rather than asserted-in-prose.
 *
 * <ul>
 *   <li><strong>It is a partial composite prefix and is nonunique.</strong> The group identifier is only
 *       the first of three key components and recurs once per type-and-category combination - seventeen
 *       times per group in the verified reference data. A single-column reference to the leading part of
 *       a three-column composite key is not expressible as a foreign key at all.</li>
 *   <li><strong>It would reject every seeded account.</strong> All fifty seeded accounts carry ten
 *       spaces in that field, and ten spaces match none of the three seeded group identifiers. A foreign
 *       key would refuse the entire seed and would remove the very unmatched-group case the accrual
 *       fallback exists to absorb.</li>
 * </ul>
 *
 * <p>Resolution is a run-time lookup with a bounded retry, not referential integrity. The cascade itself
 * - the two file statuses normalised as one non-error outcome, the substitution of the padded default
 * identifier on record-not-found, the single retry whose own miss is terminal, and the guard that
 * computes interest only for a non-zero rate - belongs entirely to the accrual service. None of it is
 * reproduced here. This repository's whole contribution to that cascade is a negative result, so an
 * absent key yielding an empty {@link Optional} without throwing is asserted as the contract it is.
 *
 * <h2>Decimal fidelity</h2>
 * The rate column is the only exact numeric column in the eleven-table schema declared with a precision
 * of six; every other amount column is wider, so this precision must never be copied from a sibling
 * entity. That uniqueness is asserted from the catalogue. An approximate binary numeric type is
 * prohibited throughout the migration and appears nowhere in this file: no inexact binary numeric type,
 * primitive or boxed, is named, declared or used anywhere in it, and every amount here is an exact
 * decimal.
 *
 * <p>The rounding policy is the load-bearing part. A census of the legacy estate found no rounding
 * clause on any arithmetic statement in any program or copybook, so every store into a two-decimal field
 * truncates toward zero. One test writes a three-decimal rate and asserts the stored value truncates
 * rather than rounds, which is what distinguishes the correct policy from the conventional Java default:
 * the value chosen would round <em>up</em> under either half-up or half-even and therefore fails this
 * assertion if the policy ever drifts. Scaling itself is the fixed-width codec's sole responsibility and
 * is not reimplemented here.
 *
 * <h2>What this file deliberately does not declare</h2>
 * {@link AbstractPostgresIT} is the single owner of the database container for the whole module.
 * Honouring the contract that class documents, this test declares no container, no container extension,
 * no data-source property source of its own and no context-dirtying annotation; it inherits the server
 * address and the active profile from that base. It supplies only its own bootstrap.
 *
 * <p>That bootstrap is {@code @SpringBootTest} over an explicit slice rather than over the application,
 * and both halves of that choice are deliberate. {@code @SpringBootTest} rather than the persistence
 * slicing annotation, because that annotation substitutes an embedded database unless it is paired with
 * an explicit instruction not to, and a repository parity test that silently ran against an in-memory
 * engine would assert nothing about the migrated schema. An explicit slice rather than application
 * discovery because
 * the failsafe classpath carries the test tree, whose nested configurations would then be harvested into
 * the context and collide - the mechanism the application start-up test documents and defends against.
 * The slice is narrow on purpose: a data source, a template, the persistence provider and transaction
 * management, which is everything a repository contract needs and nothing else. No web tier is requested
 * because no endpoint is exercised here; a mocked one would serve equally well and would only cost a
 * servlet context nothing in this file reads.
 *
 * <p>Writes are confined to a reserved key range and are removed both before and after every test, which
 * is the cheap deterministic reset the base class's contract asks for in place of discarding the
 * context. The seeded rows are never modified, because most assertions here are about them.
 *
 * <p>Isolation is stronger than the legacy baseline and that is intentional rather than a regression:
 * every application file definition in the legacy resource definition declares uncommitted-read
 * integrity, no recovery and no journalling, leaving correctness to a locking update model plus each
 * program's own before-and-after image comparison. This server runs PostgreSQL's default read-committed
 * isolation. The record belongs in {@code docs/decision-log.md}.
 *
 * <p>Provenance: this test has no legacy antecedent - the estate carries no test harness of any kind. The
 * layout, cluster definition, seed contents and accrual behaviour it asserts were read from checkout
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. That stamp is a provenance string for the
 * traceability matrix header only; it is not carried by every legacy member, so nothing here asserts it
 * against one. No legacy source text of any kind is transcribed in this file: the estate is cited by
 * record width, byte offset, row count, table name and column name alone.
 *
 * @see DisclosureGroupRepository
 * @see DisclosureGroup
 * @see DisclosureGroupId
 * @since 1.0.0
 */
@SpringBootTest(classes = DisclosureGroupRepositoryIT.RepositoryUnderTest.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "spring.main.banner-mode=off")
@DisplayName("disclosure group repository :: padded keys, component order and the two-decimal rate")
class DisclosureGroupRepositoryIT extends AbstractPostgresIT {

    /** The migrated table this file is the contract for. */
    private static final String DISCLOSURE_GROUP_TABLE = "disclosure_group";

    /** The table whose group identifier must never gain a foreign key to the one above. */
    private static final String ACCOUNT_TABLE = "account";

    /** The account column that must never gain that foreign key. */
    private static final String ACCOUNT_GROUP_ID_COLUMN = "acct_group_id";

    /** The only index the table carries: the one the primary-key constraint creates. */
    private static final String PRIMARY_KEY_INDEX = "pk_disclosure_group";

    /**
     * The declared width of the account-group identifier, in the record image and in the column alike.
     *
     * <p>Asserted against retrieved values rather than assumed, because a value shorter than this is
     * precisely the defect trap one describes.
     */
    private static final int GROUP_ID_WIDTH = 10;

    /** The declared width of the transaction-type code. */
    private static final int TYPE_CODE_WIDTH = 2;

    /** The declared width of the transaction-category code. */
    private static final int CATEGORY_CODE_WIDTH = 4;

    /**
     * The group identifier that needs no padding, taken from the shared factory so that this file and
     * the fixtures it builds can never name different values.
     */
    private static final String DIRECT_HIT_GROUP_ID = TestDataFactory.DIRECT_HIT_DISCLOSURE_GROUP_ID;

    /** The ten-character fallback group identifier: seven characters followed by three spaces. */
    private static final String FALLBACK_GROUP_ID = TestDataFactory.FALLBACK_DISCLOSURE_GROUP_ID;

    /** The ten-character zero-rate group identifier: seven characters followed by three spaces. */
    private static final String ZERO_RATE_GROUP_ID = TestDataFactory.ZERO_RATE_DISCLOSURE_GROUP_ID;

    /**
     * The seven-character form of the fallback identifier - the value a reader would type, and the value
     * that must resolve nothing.
     *
     * <p>Written as its own literal rather than derived from the padded constant by removing spaces,
     * because a derivation would use exactly the trimming operation this file forbids.
     */
    private static final String SHORTENED_FALLBACK_GROUP_ID = "DEFAULT";

    /** The seven-character form of the zero-rate identifier, for the same reason. */
    private static final String SHORTENED_ZERO_RATE_GROUP_ID = "ZEROAPR";

    /** A transaction-type code present in all three seeded groups, retaining its leading zero. */
    private static final String SEEDED_TYPE_CODE = "01";

    /** A transaction-category code present under that type in all three seeded groups. */
    private static final String SEEDED_CATEGORY_CODE = "0001";

    /**
     * The type code whose seeded row under {@link #SEEDED_CATEGORY_CODE} carries a rate that differs
     * from the row reached by exchanging the two significant digits. Used for the positional proof.
     */
    private static final String OTHER_SEEDED_TYPE_CODE = "04";

    /** The category code that completes the other half of that positional pair. */
    private static final String OTHER_SEEDED_CATEGORY_CODE = "0004";

    /** The measured rate of the unpadded group under type {@code 01} and category {@code 0004}. */
    private static final BigDecimal RATE_OF_TYPE_ONE_CATEGORY_FOUR = new BigDecimal("25.00");

    /** The measured rate of the unpadded group under type {@code 04} and category {@code 0001}. */
    private static final BigDecimal RATE_OF_TYPE_FOUR_CATEGORY_ONE = new BigDecimal("15.00");

    /** The measured rate the fallback group carries on the type and category every balance uses. */
    private static final BigDecimal FALLBACK_RATE = new BigDecimal("15.00");

    /**
     * The measured rate the unpadded group carries on that same type and category - the value a direct
     * probe resolves when an account genuinely names that group.
     */
    private static final BigDecimal DIRECT_HIT_RATE = new BigDecimal("15.00");

    /**
     * An arbitrary in-range rate for rows this class writes under its reserved key, where the assertion is
     * about a key component rather than about the rate. Distinct from every seeded rate so that a row
     * under test cannot be confused with a seeded one in a failure message.
     */
    private static final BigDecimal RESERVED_ROW_RATE = new BigDecimal("9.99");

    /** The measured rate the zero-rate group carries, at the scale the column stores. */
    private static final BigDecimal ZERO_RATE = new BigDecimal("0.00");

    /** The number of fraction digits every stored amount in this schema carries. */
    private static final int STORED_SCALE = TestDataFactory.MONETARY_SCALE;

    /**
     * The group identifier this test writes under, reserved for this class and absent from the seed.
     *
     * <p>Exactly ten characters, because a shorter value would be a different key and would make the
     * write tests assert something other than what the accrual tier reads. It is deliberately unlike the
     * three seeded identifiers so that a stray row could not be mistaken for a seeded one, and it is
     * removed before and after every test so the fifty-one-row cardinality assertion stays true whatever
     * order the tests run in.
     */
    private static final String RESERVED_GROUP_ID = "ITRESVGRP0";

    /**
     * The account key this test writes under, reserved for this class.
     *
     * <p>Chosen outside every range the sibling integration tests reserve, and outside the seeded range,
     * so no two classes can delete each other's rows.
     */
    private static final String RESERVED_ACCOUNT_ID = "99000000061";

    /** A category code that is absent from the seed, used for the leading-zero round trip. */
    private static final String RESERVED_CATEGORY_CODE = "0005";

    /** The same category code with its leading zeros removed - the value that must resolve nothing. */
    private static final String NUMERIC_CATEGORY_CODE = "5";

    /** The type code with its leading zero removed - likewise required to resolve nothing. */
    private static final String NUMERIC_TYPE_CODE = "1";

    /**
     * A three-decimal rate whose correct stored form distinguishes truncation from rounding.
     *
     * <p>Truncating toward zero yields {@code 12.34}; both half-up and half-even yield {@code 12.35}. So
     * the round-trip assertion fails if the estate's rounding policy is ever relaxed to a conventional
     * Java default, which is the whole point of choosing this value rather than one whose third digit is
     * below five.
     */
    private static final BigDecimal UNROUNDED_RATE = new BigDecimal("12.346");

    /** The stored form of {@link #UNROUNDED_RATE} once the truncating policy has been applied. */
    private static final String TRUNCATED_RATE_TEXT = "12.34";

    /**
     * A rate written in scientific notation, to prove the stored and retrieved forms are plain.
     *
     * <p>Its unscaled representation carries a negative scale, which is exactly the shape that renders
     * with an exponent if it is ever passed through {@code toString()} instead of {@code toPlainString()}.
     */
    private static final BigDecimal SCIENTIFIC_RATE = new BigDecimal("1.5E+1");

    /** The plain stored form of {@link #SCIENTIFIC_RATE}. */
    private static final String SCIENTIFIC_RATE_PLAIN_TEXT = "15.00";

    /** The exponent marker that must appear in no plain rendering of a stored rate. */
    private static final String EXPONENT_MARKER = "E";

    /**
     * Names every index a table carries, so the absence of a secondary index is measured rather than
     * assumed. Bound by table name; nothing is assembled into the statement.
     */
    private static final String INDEX_NAMES_SQL = """
            SELECT indexname FROM pg_indexes
             WHERE schemaname = 'public'
               AND tablename = ?
             ORDER BY indexname
            """;

    /**
     * Counts the foreign keys that touch a table in either direction - those declared on it and those
     * declared elsewhere and pointing at it. The table name is bound twice rather than interpolated once.
     */
    private static final String FOREIGN_KEYS_TOUCHING_TABLE_SQL = """
            SELECT count(*) FROM pg_constraint fk
              JOIN pg_class child ON child.oid = fk.conrelid
              JOIN pg_class parent ON parent.oid = fk.confrelid
             WHERE fk.contype = 'f'
               AND (child.relname = ? OR parent.relname = ?)
            """;

    /** Counts the foreign keys constraining one named column of one named table. */
    private static final String FOREIGN_KEYS_ON_COLUMN_SQL = """
            SELECT count(*) FROM information_schema.table_constraints tc
              JOIN information_schema.key_column_usage kcu
                ON kcu.constraint_name = tc.constraint_name
               AND kcu.constraint_schema = tc.constraint_schema
             WHERE tc.constraint_type = 'FOREIGN KEY'
               AND tc.table_schema = 'public'
               AND tc.table_name = ?
               AND kcu.column_name = ?
            """;

    /**
     * Lists a table's primary-key columns in the order the constraint declares them, which is the
     * structural statement of component order that trap two turns on.
     */
    private static final String PRIMARY_KEY_COLUMNS_SQL = """
            SELECT kcu.column_name FROM information_schema.table_constraints tc
              JOIN information_schema.key_column_usage kcu
                ON kcu.constraint_name = tc.constraint_name
               AND kcu.constraint_schema = tc.constraint_schema
             WHERE tc.constraint_type = 'PRIMARY KEY'
               AND tc.table_schema = 'public'
               AND tc.table_name = ?
             ORDER BY kcu.ordinal_position
            """;

    /**
     * Renders one column's declared type and size as a single string, so an assertion reads as the
     * declaration does. A character column reports its maximum length; an exact numeric column reports
     * its precision and scale.
     */
    private static final String COLUMN_SHAPE_SQL = """
            SELECT data_type || '(' || coalesce(character_maximum_length::text,
                       numeric_precision::text || ',' || numeric_scale::text) || ')'
              FROM information_schema.columns
             WHERE table_schema = 'public'
               AND table_name = ?
               AND column_name = ?
            """;

    /**
     * Lists every exact numeric column in the schema declared with a precision of six and a scale of
     * two, which must be the rate column and nothing else.
     *
     * <p>The framework's own metadata tables and the migration history table are excluded by name, for
     * the same reason the shared base excludes them from its table inventory: they are real and expected,
     * they are provisioned by something other than a migration, and counting them would fail this
     * assertion for a reason unrelated to the record schema. Nothing else is excluded, so an unexpected
     * name here is a genuine schema regression rather than noise.
     */
    private static final String SIX_DIGIT_TWO_SCALE_NUMERIC_COLUMNS_SQL = """
            SELECT table_name || '.' || column_name
              FROM information_schema.columns
             WHERE table_schema = 'public'
               AND data_type = 'numeric'
               AND numeric_precision = 6
               AND numeric_scale = 2
               AND table_name NOT LIKE 'batch%'
               AND table_name <> 'flyway_schema_history'
             ORDER BY 1
            """;

    /** The repository under test, reached as the application reaches it. */
    private final DisclosureGroupRepository disclosureGroups;

    /**
     * The account repository, needed for the cross-table facts that make the fallback reachable and the
     * direct hit unreachable from seeded data alone.
     */
    private final AccountRepository accounts;

    /**
     * A template over the same data source, used only to read the catalogue. Every statement it runs is
     * a complete literal above with its values bound as parameters.
     */
    private final JdbcTemplate catalogue;

    /**
     * Receives the collaborators from the sliced context.
     *
     * <p>Constructor injection rather than field injection, so each collaborator is final and a context
     * that failed to supply one fails at construction with the type named.
     *
     * <p>The annotation is required rather than decorative. A test class's constructor is autowired only
     * when the framework is told to do so, because the default constructor-autowiring mode for test
     * classes is "annotated" - so without it the extension declines to resolve these three parameters and
     * every test in the class fails at instantiation before any assertion runs. Annotating the
     * constructor is preferred here over switching the whole class to the "all" mode, which would be a
     * broader instruction than this one constructor needs.
     *
     * @param disclosureGroups the repository under test
     * @param accounts the account repository, for the cross-table group-identifier facts
     * @param catalogue a template over the same server, for reading the schema catalogue
     */
    @Autowired
    DisclosureGroupRepositoryIT(final DisclosureGroupRepository disclosureGroups,
            final AccountRepository accounts,
            final JdbcTemplate catalogue) {
        this.disclosureGroups = disclosureGroups;
        this.accounts = accounts;
        this.catalogue = catalogue;
    }

    /**
     * Removes this class's reserved rows before every test.
     *
     * <p>Runs before as well as after so that a run whose predecessor was interrupted still starts from
     * the delivered seed, which the cardinality assertions depend on. It is the cheap deterministic reset
     * the shared base asks for in place of discarding the Spring context, and it touches nothing outside
     * the reserved range - the seeded rows are the subject of most assertions here and are never
     * modified.
     */
    @BeforeEach
    void removeReservedRowsBeforeEachTest() {
        removeReservedRows();
    }

    /** Removes this class's reserved rows after every test, so nothing leaks into a sibling class. */
    @AfterEach
    void removeReservedRowsAfterEachTest() {
        removeReservedRows();
    }


    @Test
    @DisplayName("the seed is fifty-one rows composed of three complete groups of seventeen, and nothing "
            + "outside those three groups")
    void theSeedHoldsFiftyOneRowsAsThreeGroupsOfSeventeen() {
        assertThat(disclosureGroups.count())
                .as("the reference fixture measures 2,601 bytes at a 50-byte record length, so the seed "
                        + "is exactly fifty-one rows; a different count means the seed was edited or a "
                        + "sibling test leaked a row")
                .isEqualTo(TestDataFactory.SEEDED_DISCLOSURE_GROUP_COUNT);

        for (final String groupId : TestDataFactory.SEEDED_DISCLOSURE_GROUP_IDS) {
            assertThat(seededRowsIn(groupId))
                    .as("group '%s' must carry a complete set of seventeen type-and-category rows",
                            groupId)
                    .isEqualTo(TestDataFactory.DISCLOSURE_GROUP_ROWS_PER_GROUP);
        }

        assertThat(TestDataFactory.SEEDED_DISCLOSURE_GROUP_IDS.size()
                        * TestDataFactory.DISCLOSURE_GROUP_ROWS_PER_GROUP)
                .as("three groups of seventeen must account for the whole table, leaving no row "
                        + "unattributed to a group")
                .isEqualTo(TestDataFactory.SEEDED_DISCLOSURE_GROUP_COUNT);
    }

    @Test
    @DisplayName("every stored group identifier is ten characters wide and is one of the three seeded "
            + "values, read back without trimming")
    void everyStoredGroupIdentifierIsTenCharactersWide() {
        final List<DisclosureGroup> rows = disclosureGroups.findAll();

        assertThat(rows)
                .as("the enumeration inherited from the repository must return the whole seeded table")
                .hasSize(TestDataFactory.SEEDED_DISCLOSURE_GROUP_COUNT);

        for (final DisclosureGroup row : rows) {
            assertThat(row.getDisAcctGroupId())
                    .as("the group identifier occupies the full ten characters of its field, and the "
                            + "read path must not shorten it: %s", row)
                    .hasSize(GROUP_ID_WIDTH);
            assertThat(row.getDisTranTypeCd())
                    .as("the type code occupies its full two characters: %s", row)
                    .hasSize(TYPE_CODE_WIDTH);
            assertThat(row.getDisTranCatCd())
                    .as("the category code occupies its full four characters: %s", row)
                    .hasSize(CATEGORY_CODE_WIDTH);
        }

        assertThat(rows.stream().map(DisclosureGroup::getDisAcctGroupId).distinct().sorted().toList())
                .as("exactly three distinct group identifiers are seeded, and two of them carry trailing "
                        + "spaces that are part of the key rather than incidental whitespace")
                .containsExactly(DIRECT_HIT_GROUP_ID, FALLBACK_GROUP_ID, ZERO_RATE_GROUP_ID);
    }

    @Test
    @DisplayName("the padded fallback identifier resolves a row while its seven-character form resolves "
            + "nothing")
    void thePaddedFallbackIdentifierResolvesAndItsShortenedFormDoesNot() {
        final Optional<DisclosureGroup> padded = disclosureGroups.findById(
                new DisclosureGroupId(FALLBACK_GROUP_ID, SEEDED_TYPE_CODE, SEEDED_CATEGORY_CODE));

        assertThat(padded)
                .as("the accrual retry assigns a seven-character literal into a ten-character "
                        + "alphanumeric field, which left-justifies and space-fills, so the padded form "
                        + "is the key that must resolve")
                .isPresent();
        assertThat(padded.orElseThrow().getDisAcctGroupId())
                .as("the stored identifier is returned exactly as written, trailing spaces included")
                .isEqualTo(FALLBACK_GROUP_ID)
                .hasSize(GROUP_ID_WIDTH);
        assertThat(padded.orElseThrow().getDisIntRate())
                .as("the fallback group carries this rate on the type and category every seeded balance "
                        + "uses, at the scale the column stores")
                .isEqualTo(FALLBACK_RATE);

        assertThat(disclosureGroups.findById(new DisclosureGroupId(
                        SHORTENED_FALLBACK_GROUP_ID, SEEDED_TYPE_CODE, SEEDED_CATEGORY_CODE)))
                .as("the seven-character form is a DIFFERENT key and must resolve nothing; the column is "
                        + "bounded variable-length text rather than blank-padded text precisely so that "
                        + "this distinction survives into the database")
                .isEmpty();
    }

    @Test
    @DisplayName("the padded zero-rate identifier resolves a row while its seven-character form resolves "
            + "nothing")
    void thePaddedZeroRateIdentifierResolvesAndItsShortenedFormDoesNot() {
        final Optional<DisclosureGroup> padded = disclosureGroups.findById(
                new DisclosureGroupId(ZERO_RATE_GROUP_ID, SEEDED_TYPE_CODE, SEEDED_CATEGORY_CODE));

        assertThat(padded)
                .as("the zero-rate group is seeded at the full ten characters like the fallback group, so "
                        + "the same padded-key rule governs it")
                .isPresent();
        assertThat(padded.orElseThrow().getDisAcctGroupId())
                .as("the stored identifier is returned exactly as written, trailing spaces included")
                .isEqualTo(ZERO_RATE_GROUP_ID)
                .hasSize(GROUP_ID_WIDTH);

        assertThat(disclosureGroups.findById(new DisclosureGroupId(
                        SHORTENED_ZERO_RATE_GROUP_ID, SEEDED_TYPE_CODE, SEEDED_CATEGORY_CODE)))
                .as("the seven-character form of the zero-rate identifier must resolve nothing either, so "
                        + "the rule is proven on both padded groups rather than on one")
                .isEmpty();
    }

    @Test
    @DisplayName("the group identifier that needs no padding resolves exactly as written")
    void theUnpaddedGroupIdentifierResolvesExactlyAsWritten() {
        final Optional<DisclosureGroup> row = disclosureGroups.findById(
                new DisclosureGroupId(DIRECT_HIT_GROUP_ID, SEEDED_TYPE_CODE, SEEDED_CATEGORY_CODE));

        assertThat(row)
                .as("the zero-filled identifier already fills its field, so no padding question arises "
                        + "and the key resolves directly")
                .isPresent();
        assertThat(row.orElseThrow().getDisAcctGroupId())
                .as("a ten-character value with no trailing space is returned unchanged too, which shows "
                        + "the read path neither pads nor trims")
                .isEqualTo(DIRECT_HIT_GROUP_ID)
                .hasSize(GROUP_ID_WIDTH);
    }

    @Test
    @DisplayName("supplying the components in the accrual program's assignment order resolves nothing in "
            + "any of the three seeded groups")
    void supplyingTheComponentsInAssignmentOrderResolvesNothing() {
        for (final String groupId : TestDataFactory.SEEDED_DISCLOSURE_GROUP_IDS) {
            assertThat(disclosureGroups.findById(
                            new DisclosureGroupId(groupId, SEEDED_TYPE_CODE, SEEDED_CATEGORY_CODE)))
                    .as("group '%s' in KEY order - group, then type, then category - must resolve, so "
                            + "this test cannot pass by finding nothing either way", groupId)
                    .isPresent();

            assertThat(disclosureGroups.findById(
                            new DisclosureGroupId(groupId, SEEDED_CATEGORY_CODE, SEEDED_TYPE_CODE)))
                    .as("group '%s' in the accrual program's ASSIGNMENT order - group, then category, "
                            + "then type - must resolve nothing. Three assignments to three distinct "
                            + "named fields carry no ordering semantics, so the constructor must never "
                            + "be reordered to follow them", groupId)
                    .isEmpty();
        }
    }

    @Test
    @DisplayName("the type and category slots are not interchangeable, because exchanging the significant "
            + "digit lands on a row with a different rate")
    void theTypeAndCategorySlotsAreNotInterchangeable() {
        final Optional<DisclosureGroup> typeOneCategoryFour = disclosureGroups.findById(
                new DisclosureGroupId(
                        DIRECT_HIT_GROUP_ID, SEEDED_TYPE_CODE, OTHER_SEEDED_CATEGORY_CODE));
        final Optional<DisclosureGroup> typeFourCategoryOne = disclosureGroups.findById(
                new DisclosureGroupId(
                        DIRECT_HIT_GROUP_ID, OTHER_SEEDED_TYPE_CODE, SEEDED_CATEGORY_CODE));

        assertThat(typeOneCategoryFour).as("both rows are seeded, so both keys must resolve").isPresent();
        assertThat(typeFourCategoryOne).as("both rows are seeded, so both keys must resolve").isPresent();

        assertThat(typeOneCategoryFour.orElseThrow().getDisIntRate())
                .as("the row whose CATEGORY carries the significant digit holds this rate")
                .isEqualTo(RATE_OF_TYPE_ONE_CATEGORY_FOUR);
        assertThat(typeFourCategoryOne.orElseThrow().getDisIntRate())
                .as("the row whose TYPE carries the significant digit holds a different one")
                .isEqualTo(RATE_OF_TYPE_FOUR_CATEGORY_ONE);
        assertThat(typeOneCategoryFour.orElseThrow().getDisIntRate())
                .as("the two RETRIEVED rates must differ, which is what makes the positional binding of "
                        + "the two short components observable in the data rather than a matter of faith: "
                        + "moving the significant digit from one slot to the other lands on a genuinely "
                        + "different row")
                .isNotEqualByComparingTo(typeFourCategoryOne.orElseThrow().getDisIntRate());
    }

    @Test
    @DisplayName("an absent composite key yields an empty result and throws nothing, which is the only "
            + "contribution this repository makes to the accrual fallback")
    void anAbsentCompositeKeyYieldsAnEmptyResultAndThrowsNothing() {
        final DisclosureGroupId absent =
                new DisclosureGroupId(RESERVED_GROUP_ID, SEEDED_TYPE_CODE, SEEDED_CATEGORY_CODE);

        assertThatCode(() -> disclosureGroups.findById(absent))
                .as("a miss is a normal outcome the accrual tier acts on, not an error condition; the "
                        + "legacy record-not-found file status is likewise not an error there")
                .doesNotThrowAnyException();
        assertThat(disclosureGroups.findById(absent))
                .as("the empty result is the analogue of that file status, and the substitution, the "
                        + "single bounded retry and the terminal miss all belong to the accrual service "
                        + "rather than here")
                .isEmpty();
        assertThat(disclosureGroups.existsById(absent))
                .as("the inherited existence check must agree with the inherited retrieval")
                .isFalse();
    }


    @Test
    @DisplayName("a written rate round-trips at two fraction digits, truncated toward zero rather than "
            + "rounded, because the estate declares no rounding anywhere")
    void aWrittenRateRoundTripsTruncatedTowardZero() {
        final DisclosureGroup reloaded =
                storeAndReload(SEEDED_TYPE_CODE, SEEDED_CATEGORY_CODE, UNROUNDED_RATE);

        assertThat(reloaded.getDisIntRate().scale())
                .as("the column holds two fraction digits, so a value that arrived with three must be "
                        + "stored at two and must come back at two - no scale drift in either direction")
                .isEqualTo(STORED_SCALE);
        assertThat(reloaded.getDisIntRate().toPlainString())
                .as("truncation toward zero yields '%s'; both half-up and half-even would yield a cent "
                        + "more, so this assertion is what fails the build if the estate's rounding "
                        + "policy is ever relaxed to a conventional Java default",
                        TRUNCATED_RATE_TEXT)
                .isEqualTo(TRUNCATED_RATE_TEXT);
        assertThat(reloaded.getDisIntRate())
                .as("stated a second time as an exact decimal comparison, so a failure reports the value "
                        + "as a number and not only as text")
                .isEqualTo(new BigDecimal(TRUNCATED_RATE_TEXT));
    }

    @Test
    @DisplayName("a zero rate survives a write and a read at two fraction digits instead of collapsing "
            + "to an unscaled zero")
    void aZeroRateSurvivesAtTwoFractionDigits() {
        final DisclosureGroup seeded = disclosureGroups.findById(
                        new DisclosureGroupId(ZERO_RATE_GROUP_ID, SEEDED_TYPE_CODE, SEEDED_CATEGORY_CODE))
                .orElseThrow(() -> new AssertionError(
                        "the zero-rate group is seeded and its row on the common type and category must "
                                + "exist, otherwise the accrual skip branch has nothing to reach"));

        assertThat(seeded.getDisIntRate().scale())
                .as("a seeded zero is stored at the column's scale like any other amount")
                .isEqualTo(STORED_SCALE);
        assertThat(seeded.getDisIntRate())
                .as("and equals zero at that scale exactly, which is what the non-zero guard in the "
                        + "accrual tier tests against")
                .isEqualTo(ZERO_RATE);
        assertThat(seeded.getDisIntRate().signum())
                .as("a genuine zero rate exists in the reference data, so the skip branch is reachable "
                        + "rather than hypothetical")
                .isZero();

        final DisclosureGroup written =
                storeAndReload(SEEDED_TYPE_CODE, SEEDED_CATEGORY_CODE, BigDecimal.ZERO);

        assertThat(written.getDisIntRate().scale())
                .as("an unscaled zero handed to the write path is normalised to the column's scale, so a "
                        + "row written by application code is indistinguishable from a seeded one")
                .isEqualTo(STORED_SCALE);
        assertThat(written.getDisIntRate().toPlainString())
                .as("and renders with both fraction digits present")
                .isEqualTo(ZERO_RATE.toPlainString());
    }

    @Test
    @DisplayName("a rate written in scientific notation is stored and rendered plainly, so no exponent "
            + "can reach a fixed-width output")
    void aRateWrittenInScientificNotationIsStoredPlainly() {
        final DisclosureGroup reloaded =
                storeAndReload(SEEDED_TYPE_CODE, SEEDED_CATEGORY_CODE, SCIENTIFIC_RATE);

        assertThat(reloaded.getDisIntRate().toPlainString())
                .as("the value arrived with a negative scale, which is the shape that renders with an "
                        + "exponent; setting the column's scale removes that shape entirely")
                .isEqualTo(SCIENTIFIC_RATE_PLAIN_TEXT)
                .doesNotContain(EXPONENT_MARKER);
        assertThat(reloaded.getDisIntRate().scale())
                .as("and it lands at the column's scale like every other stored amount")
                .isEqualTo(STORED_SCALE);
    }

    @Test
    @DisplayName("every seeded rate is a plain signed decimal with exactly two ASCII fraction digits")
    void everySeededRateIsAPlainTwoDigitDecimal() {
        for (final DisclosureGroup row : disclosureGroups.findAll()) {
            assertThat(row.getDisIntRate().scale())
                    .as("row %s must be stored at the column's scale", row)
                    .isEqualTo(STORED_SCALE);
            assertThat(row.getDisIntRate().toPlainString())
                    .as("row %s must render as ASCII digits around a single point, with no exponent and "
                            + "no grouping separator, because the legacy field is a fixed-width image",
                            row)
                    .doesNotContain(EXPONENT_MARKER)
                    .matches("-?[0-9]+\\.[0-9]{2}");
        }
    }

    @Test
    @DisplayName("a four-character category code keeps its leading zeros, and the same value without them "
            + "resolves nothing")
    void aFourCharacterCategoryCodeKeepsItsLeadingZeros() {
        final DisclosureGroup reloaded =
                storeAndReload(SEEDED_TYPE_CODE, RESERVED_CATEGORY_CODE, RESERVED_ROW_RATE);

        assertThat(reloaded.getDisTranCatCd())
                .as("the legacy field is a zero-filled external-decimal value carried as text precisely "
                        + "so its width and its leading zeros survive; a numeric mapping would render it "
                        + "without them and the sixteen-byte key image would no longer reconstruct")
                .isEqualTo(RESERVED_CATEGORY_CODE)
                .hasSize(CATEGORY_CODE_WIDTH);

        assertThat(disclosureGroups.findById(new DisclosureGroupId(
                        RESERVED_GROUP_ID, SEEDED_TYPE_CODE, NUMERIC_CATEGORY_CODE)))
                .as("the numeric form of the same category is a different key and must resolve nothing")
                .isEmpty();
    }

    @Test
    @DisplayName("the two-character type code keeps its leading zero, and the same value without it "
            + "resolves nothing")
    void theTwoCharacterTypeCodeKeepsItsLeadingZero() {
        final DisclosureGroup seeded = disclosureGroups.findById(
                        new DisclosureGroupId(DIRECT_HIT_GROUP_ID, SEEDED_TYPE_CODE, SEEDED_CATEGORY_CODE))
                .orElseThrow(() -> new AssertionError("the row on the common type and category is "
                        + "seeded in every group and must exist"));

        assertThat(seeded.getDisTranTypeCd())
                .as("the type code is stored and returned at its full two characters, leading zero intact")
                .isEqualTo(SEEDED_TYPE_CODE)
                .hasSize(TYPE_CODE_WIDTH);

        assertThat(disclosureGroups.findById(new DisclosureGroupId(
                        DIRECT_HIT_GROUP_ID, NUMERIC_TYPE_CODE, SEEDED_CATEGORY_CODE)))
                .as("the type code without its leading zero is a different key and must resolve nothing")
                .isEmpty();
    }

    @Test
    @DisplayName("all fifty seeded accounts carry a ten-space group identifier that matches none of the "
            + "three seeded groups, which is what makes the fallback the only seeded path")
    void allSeededAccountsCarryATenSpaceGroupIdentifier() {
        final List<Account> blankGroupAccounts = accounts.findAll().stream()
                .filter(account ->
                        TestDataFactory.SEEDED_ACCOUNT_GROUP_ID.equals(account.getAcctGroupId()))
                .toList();

        assertThat(blankGroupAccounts)
                .as("all fifty seeded accounts leave the group identifier blank. The reference fixture "
                        + "puts the group-shaped value at the postal-code offset instead, ten bytes "
                        + "earlier, which is why the group field is empty rather than populated. The set "
                        + "is filtered rather than counted whole so that a sibling class's reserved "
                        + "account cannot fail this assertion for an unrelated reason")
                .hasSize(TestDataFactory.SEEDED_FIFTY_ROW_COUNT);

        for (final Account account : blankGroupAccounts) {
            assertThat(account.getAcctGroupId())
                    .as("account %s must carry the field at its full declared width, unshortened",
                            account.getAcctId())
                    .hasSize(GROUP_ID_WIDTH);
            assertThat(TestDataFactory.SEEDED_DISCLOSURE_GROUP_IDS)
                    .as("account %s must match no seeded disclosure group, so its first probe misses and "
                            + "the accrual tier substitutes the padded default identifier",
                            account.getAcctId())
                    .doesNotContain(account.getAcctGroupId());
        }

        assertThat(disclosureGroups.findById(new DisclosureGroupId(
                        TestDataFactory.SEEDED_ACCOUNT_GROUP_ID, SEEDED_TYPE_CODE, SEEDED_CATEGORY_CODE)))
                .as("and the miss itself: a lookup on the ten-space identifier resolves nothing, which is "
                        + "this repository's entire contribution to the fallback")
                .isEmpty();
    }

    @Test
    @DisplayName("a purpose-built account naming a real group resolves its disclosure row directly, which "
            + "seeded data alone cannot reach")
    void aPurposeBuiltAccountResolvesItsDisclosureRowDirectly() {
        accounts.saveAndFlush(TestDataFactory.directHitDisclosureAccount()
                .acctId(RESERVED_ACCOUNT_ID)
                .build());

        final Account reloaded = accounts.findById(RESERVED_ACCOUNT_ID)
                .orElseThrow(() -> new AssertionError(
                        "the reserved account was written and must read back, otherwise the cross-table "
                                + "fact below is asserted against nothing"));

        assertThat(reloaded.getAcctGroupId())
                .as("the fixture is mandatory rather than convenient: with every seeded account blank in "
                        + "this field, the direct-hit branch is unreachable without an account "
                        + "constructed to name a real group")
                .isEqualTo(DIRECT_HIT_GROUP_ID)
                .hasSize(GROUP_ID_WIDTH);

        final Optional<DisclosureGroup> resolvedDirectly = disclosureGroups.findById(
                new DisclosureGroupId(
                        reloaded.getAcctGroupId(), SEEDED_TYPE_CODE, SEEDED_CATEGORY_CODE));

        assertThat(resolvedDirectly)
                .as("the account's own group identifier, taken from the row as stored and used unchanged "
                        + "as the first key component, resolves a disclosure row on the first probe")
                .isPresent();
        assertThat(resolvedDirectly.orElseThrow().getDisIntRate())
                .as("and it is the rate that group seeds for this type and category")
                .isEqualTo(DIRECT_HIT_RATE);
    }


    @Test
    @DisplayName("the primary key declares the three components in key order - group, then type, then "
            + "category - which is the structural statement trap two turns on")
    void thePrimaryKeyDeclaresTheThreeComponentsInKeyOrder() {
        assertThat(catalogue.queryForList(PRIMARY_KEY_COLUMNS_SQL, String.class, DISCLOSURE_GROUP_TABLE))
                .as("read from the catalogue rather than restated, so the ordering claim is measured. It "
                        + "agrees with the copybook's declaration order, with the cluster's declared key "
                        + "length of sixteen at offset zero taken against the component widths, and with "
                        + "the accrual program's own file-section structure - three independent sources "
                        + "for one order")
                .containsExactly("dis_acct_group_id", "dis_tran_type_cd", "dis_tran_cat_cd");
    }

    @Test
    @DisplayName("the three key columns are bounded variable-length text at the declared widths, not "
            + "blank-padded text, so significant padding is stored exactly as written")
    void theThreeKeyColumnsAreBoundedVariableLengthText() {
        assertThat(columnShapeOf(DISCLOSURE_GROUP_TABLE, "dis_acct_group_id"))
                .as("a blank-padded fixed-length column compares its values with implicit padding and "
                        + "would silently make the padded and shortened group identifiers equal, erasing "
                        + "the distinction the accrual fallback depends on")
                .isEqualTo("character varying(" + GROUP_ID_WIDTH + ")");
        assertThat(columnShapeOf(DISCLOSURE_GROUP_TABLE, "dis_tran_type_cd"))
                .as("the type code is two bytes at offset ten of the record image")
                .isEqualTo("character varying(" + TYPE_CODE_WIDTH + ")");
        assertThat(columnShapeOf(DISCLOSURE_GROUP_TABLE, "dis_tran_cat_cd"))
                .as("the category code is four bytes at offset twelve, carried as text so its leading "
                        + "zeros survive")
                .isEqualTo("character varying(" + CATEGORY_CODE_WIDTH + ")");
        assertThat(columnShapeOf(ACCOUNT_TABLE, ACCOUNT_GROUP_ID_COLUMN))
                .as("the account side of the unenforced relationship is declared at the same width, which "
                        + "is why a ten-space value fits there and matches nothing here")
                .isEqualTo("character varying(" + GROUP_ID_WIDTH + ")");
    }

    @Test
    @DisplayName("the rate column is the only exact numeric in the schema at six digits and two decimals, "
            + "so its precision can never be copied from a sibling entity")
    void theRateColumnIsTheOnlySixDigitTwoScaleExactNumeric() {
        assertThat(columnShapeOf(DISCLOSURE_GROUP_TABLE, "dis_int_rate"))
                .as("six digits of which two are fractional, matching a signed external-decimal field of "
                        + "four integer and two decimal digits occupying six bytes at offset sixteen. An "
                        + "approximate binary numeric type would not reproduce that representation and is "
                        + "prohibited throughout the migration")
                .isEqualTo("numeric(6,2)");

        assertThat(catalogue.queryForList(SIX_DIGIT_TWO_SCALE_NUMERIC_COLUMNS_SQL, String.class))
                .as("every other amount column in the schema is wider - the five account amounts at "
                        + "twelve digits, and the transaction, landing-transaction and category-balance "
                        + "amounts at eleven - so this one is unique and a sibling's precision must never "
                        + "be reused for it")
                .containsExactly(DISCLOSURE_GROUP_TABLE + ".dis_int_rate");
    }

    @Test
    @DisplayName("the table carries no secondary index: the only index on it is the one its primary key "
            + "creates")
    void theTableCarriesNoSecondaryIndex() {
        assertThat(catalogue.queryForList(INDEX_NAMES_SQL, String.class, DISCLOSURE_GROUP_TABLE))
                .as("the legacy cluster is defined with a single index component and no alternate index, "
                        + "and the accrual job's only access path to it is a keyed read - so there is "
                        + "nothing for a secondary index to serve. The repository interface declares no "
                        + "member for the same reason, and must keep declaring none")
                .containsExactly(PRIMARY_KEY_INDEX);
    }

    @Test
    @DisplayName("no foreign key touches the table in either direction, and the account group identifier "
            + "carries none either")
    void noForeignKeyTouchesTheTable() {
        assertThat(catalogue.queryForObject(FOREIGN_KEYS_TOUCHING_TABLE_SQL, Integer.class,
                        DISCLOSURE_GROUP_TABLE, DISCLOSURE_GROUP_TABLE))
                .as("neither declared on this table nor pointing at it. The two short components are key "
                        + "parts rather than references to the type and category reference tables, and "
                        + "the legacy design carries no such constraint")
                .isZero();

        assertThat(catalogue.queryForObject(FOREIGN_KEYS_ON_COLUMN_SQL, Integer.class,
                        ACCOUNT_TABLE, ACCOUNT_GROUP_ID_COLUMN))
                .as("the account group identifier must never become a foreign key to this table, for two "
                        + "independent reasons. It corresponds to only the FIRST of three key components "
                        + "and is nonunique on its own - it recurs once per type-and-category "
                        + "combination, seventeen times per group in the verified reference data - so a "
                        + "single-column reference to a partial composite prefix is not expressible as a "
                        + "foreign key at all. And it would reject the entire seed, because all fifty "
                        + "seeded accounts carry ten spaces there and ten spaces match no seeded group, "
                        + "removing the very unmatched-group case the accrual fallback exists to absorb")
                .isZero();
    }

    @Test
    @DisplayName("the mapping is validated against the migrated schema, and every persisted attribute of "
            + "a seeded row is readable through the repository")
    void theMappingIsValidatedAgainstTheMigratedSchema() {
        assertThat(disclosureGroups)
                .as("the context refreshed with schema validation switched on, which means all four "
                        + "mapped attributes - the three key columns and the rate - matched the migrated "
                        + "schema. A drifted mapping fails the refresh before any assertion runs, so the "
                        + "presence of these beans is itself the validation result")
                .isNotNull();
        assertThat(accounts).as("the account repository resolved from the same slice").isNotNull();
        assertThat(catalogue).as("the template over the same server resolved too").isNotNull();

        final DisclosureGroupId key =
                new DisclosureGroupId(DIRECT_HIT_GROUP_ID, SEEDED_TYPE_CODE, SEEDED_CATEGORY_CODE);
        final DisclosureGroup row = disclosureGroups.findById(key)
                .orElseThrow(() -> new AssertionError(
                        "the seeded row addressed by " + key + " must exist for the mapping to be "
                                + "demonstrated against real data rather than against an empty table"));

        assertThat(row.getDisAcctGroupId()).as("key part one is mapped and populated").isNotNull();
        assertThat(row.getDisTranTypeCd()).as("key part two is mapped and populated").isNotNull();
        assertThat(row.getDisTranCatCd()).as("key part three is mapped and populated").isNotNull();
        assertThat(row.getDisIntRate())
                .as("the one non-key attribute is mapped and populated; the trailing twenty-eight-byte "
                        + "filler of the record image has no attribute and no column, so there is nothing "
                        + "else to read")
                .isNotNull();
        assertThat(row.toId())
                .as("the entity's own key projection must equal the key it was retrieved by, comparing "
                        + "all three components byte for byte with no trimming on either side")
                .isEqualTo(key);
    }

    /**
     * Counts the rows carrying one exact group identifier, by enumerating the table and filtering in
     * memory.
     *
     * <p>Filtered here rather than served by a derived finder on purpose: the repository interface
     * declares no member and must keep declaring none, because the legacy design exposes exactly one
     * access path to this cluster and the schema carries no secondary index for anything else to exploit.
     * The table is fifty-one rows, so enumerating it costs nothing.
     *
     * <p>Comparison is exact equality against the ten-character value. Nothing is trimmed, so a row whose
     * identifier merely starts with the argument is not counted.
     *
     * @param groupId the ten-character group identifier to count, compared exactly
     * @return the number of rows carrying that identifier
     */
    private long seededRowsIn(final String groupId) {
        return disclosureGroups.findAll().stream()
                .filter(row -> groupId.equals(row.getDisAcctGroupId()))
                .count();
    }

    /**
     * Writes a row under this class's reserved group identifier and reads it back through a fresh
     * retrieval, so the assertion that follows measures what the server actually stored.
     *
     * <p>The write is flushed and committed, and the read is a separate repository call outside any
     * ambient transaction, so it cannot be satisfied from a persistence context that still holds the
     * instance just written. That is what makes this a genuine round trip rather than an identity check.
     *
     * <p>The entity is constructed directly rather than through the shared factory's builder, and
     * deliberately so: that builder normalises the rate as it builds, which would apply the truncating
     * policy before the entity ever saw the value and would leave the write path's own normalisation
     * unexercised. Passing the raw value here is what puts the production write path under test.
     *
     * @param typeCode the two-character type code, key part two
     * @param categoryCode the four-character category code, key part three
     * @param rate the rate to hand to the write path, at whatever scale the caller chose
     * @return the row as read back from the server
     */
    private DisclosureGroup storeAndReload(final String typeCode, final String categoryCode,
            final BigDecimal rate) {
        final DisclosureGroupId key = new DisclosureGroupId(RESERVED_GROUP_ID, typeCode, categoryCode);
        disclosureGroups.saveAndFlush(
                new DisclosureGroup(RESERVED_GROUP_ID, typeCode, categoryCode, rate));
        return disclosureGroups.findById(key)
                .orElseThrow(() -> new AssertionError(
                        "the row just written under reserved key " + key + " could not be read back, so "
                                + "the write path and the read path disagree about the key"));
    }

    /**
     * Reads one column's declared type and size from the catalogue as a single string.
     *
     * @param tableName the table to inspect, bound as a parameter
     * @param columnName the column to inspect, bound as a parameter
     * @return the declaration, rendered as the type name followed by its size in parentheses
     */
    private String columnShapeOf(final String tableName, final String columnName) {
        return catalogue.queryForObject(COLUMN_SHAPE_SQL, String.class, tableName, columnName);
    }

    /**
     * Deletes this class's reserved rows and nothing else.
     *
     * <p>Reserved disclosure rows are found by enumerating and filtering on the exact reserved identifier
     * rather than by naming each key, so a row written under any type and category combination is removed
     * whichever test wrote it. Deleting an absent key is a no-op in this repository contract, so the
     * account removal needs no guard beyond the retrieval it already performs.
     */
    private void removeReservedRows() {
        disclosureGroups.deleteAll(disclosureGroups.findAll().stream()
                .filter(row -> RESERVED_GROUP_ID.equals(row.getDisAcctGroupId()))
                .toList());
        accounts.findById(RESERVED_ACCOUNT_ID).ifPresent(accounts::delete);
    }

    /**
     * The narrowest context a repository contract needs: a data source, a template over it, the
     * persistence provider and transaction management.
     *
     * <p>The four auto-configurations are named explicitly rather than enabled wholesale. The failsafe
     * classpath carries the test tree, so discovering the application's own configuration would
     * component-scan that tree and harvest its nested configurations into this context, where they
     * collide - the hazard the application start-up test documents and installs a type-exclusion filter
     * to avoid. Naming what this slice needs sidesteps the question rather than defending against it, and
     * it keeps the context small enough to refresh once and be reused for the whole class.
     *
     * <p>What is deliberately <em>not</em> named here matters as much. No migration auto-configuration:
     * the shared base has already migrated the server to the head of the delivered set, and a second
     * migration path in a test would be a second place for the schema to come from. No embedded-database
     * substitution is possible either, because nothing here replaces the data source the base class
     * published - which is exactly the trap the slicing annotation for persistence tests sets when it is
     * used without an explicit instruction to keep the real database.
     *
     * <p>Schema validation stays switched on, inherited from the test profile. That is not incidental: it
     * means a mapping that has drifted from the migrated schema fails this context's refresh instead of
     * being silently reconciled by generated data-definition statements, so every assertion in this class
     * runs against a mapping the server has already agreed with.
     */
    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration({DataSourceAutoConfiguration.class, JdbcTemplateAutoConfiguration.class,
            HibernateJpaAutoConfiguration.class, TransactionAutoConfiguration.class})
    @EnableJpaRepositories(basePackageClasses = DisclosureGroupRepository.class)
    @EntityScan(basePackageClasses = DisclosureGroup.class)
    static class RepositoryUnderTest {

        /** Creates the configuration; it declares no bean of its own. */
        RepositoryUnderTest() {
            // Intentionally empty: every bean in this slice comes from the imports above.
        }
    }
}
