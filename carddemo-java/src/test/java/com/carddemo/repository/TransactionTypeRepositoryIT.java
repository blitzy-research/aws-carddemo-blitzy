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

import com.carddemo.domain.TransactionType;
import com.carddemo.support.AbstractPostgresIT;
import com.carddemo.support.TestDataFactory;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.metamodel.Attribute;
import jakarta.persistence.metamodel.EntityType;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
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
import org.springframework.core.env.Environment;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the transaction-type reference lookup against a real PostgreSQL 16 server.
 *
 * <h2>What is under test, and why it is the simplest of the eleven repositories</h2>
 * {@link TransactionTypeRepository} declares nothing: it extends the Spring Data interface and adds no
 * derived finder, no query method and no custom fragment. Everything asserted below therefore
 * exercises an <em>inherited</em> method - {@code count}, {@code findAll}, {@code findById},
 * {@code existsById}, {@code saveAndFlush} - against the migrated schema, which is exactly the point:
 * the primary key is the only access path this table has, so any additional finder would be a second
 * route to the same row. The table it reads is a two-column reference lookup with a single-column
 * primary key, no composite key, no foreign key in either direction, no secondary index and no version
 * column, and each of those five absences is asserted here rather than assumed.
 *
 * <h2>Legacy provenance, cited as metadata only</h2>
 * The row is the Java form of the transaction-type record declared in copybook {@code CVTRA03Y}, whose
 * record length is 60 bytes: a 2-byte type code at offset 0, a 50-byte description at offset 2, and 8
 * trailing filler bytes at offset 52 that carry no information and are consequently not a column. The
 * geometry is corroborated by the provisioning job {@code app/jcl/TRANTYPE.jcl}, which declares a key
 * width of 2 at offset 0 over a fixed 60-byte record on an indexed cluster - so the key is the leading
 * substring of the stored image and is the business key itself, never a surrogate.
 *
 * <h2>A batch-only reference table, so no browse and no paging is asserted</h2>
 * The CICS resource definition {@code app/csd/CARDDEMO.CSD} registers eight files and this cluster is
 * not among them, so it was never reachable from an online transaction. Its one legacy reader is the
 * transaction-report program {@code CBTRN03C}, to which the cataloged procedure
 * {@code app/proc/TRANREPT.prc} supplies the cluster as a reference input of its report step;
 * {@code CVTRA03Y} is included by that single program and by no other, the lowest inclusion count among
 * the eleven entity copybooks. There is therefore no browse cursor, no page size and no alternate index
 * to reproduce, and this class deliberately invents none.
 *
 * <h2>Seeded content</h2>
 * {@code V3__seed_reference_data.sql} seeds exactly 7 rows, one per record of the 427-byte reference
 * fixture {@code app/data/ASCII/trantype.txt} - 7 records at the 60-byte record length plus one line
 * terminator each - keyed by the two-character codes {@code 01} through {@code 07}. In the record image
 * each description occupies the full 50-byte field blank-padded to width; the seeded column holds the
 * same text right-trimmed. Both forms are asserted: the seeded rows are compared against exactly what
 * the migration stores, and a written row is compared at the full 50-character width with its trailing
 * blanks intact. Nothing here trims, strips, folds or normalises a value before comparing it, because a
 * comparison that normalises first cannot detect the padding defect it is meant to catch.
 *
 * <h2>Where the server comes from, and what this class deliberately does not declare</h2>
 * The server is owned by {@link AbstractPostgresIT}, which starts one PostgreSQL 16 instance for the
 * whole run, migrates it to the head of {@code classpath:db/migration} so both seed scripts are applied,
 * and publishes its address so a context-booting subclass inherits it. Honouring that contract, this
 * class declares no container, no container extension, no data-source property source of its own and no
 * context-dirtying annotation. It declares only its own {@code @SpringBootTest} - naming an explicit
 * slice rather than scanning, so no unrelated configuration on the test class path is harvested into it -
 * and the one mutating test rolls itself back rather than resetting the shared server for everyone else.
 *
 * <p>The engine is the real one in every assertion below. No in-memory database is substituted, which is
 * what makes the catalogue assertions meaningful: variable-length text that preserves trailing blanks,
 * a named primary-key constraint with its own backing index, and constraint catalogues that can be
 * interrogated for what is <em>not</em> there are all properties of PostgreSQL specifically.
 *
 * <p><strong>Provenance.</strong> This test has no legacy antecedent - the legacy estate carries no test
 * harness of any kind. The members cited above are read-only reference material at checkout
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. That stamp is a provenance string for the
 * traceability matrix header only, so nothing here asserts it against an individual member, and no
 * legacy source text is reproduced in this file.
 *
 * @see TransactionTypeRepository
 * @see TransactionType
 */
@SpringBootTest(classes = TransactionTypeRepositoryIT.RepositoryUnderTest.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DisplayName("Transaction type repository: the seeded reference lookup on PostgreSQL")
final class TransactionTypeRepositoryIT extends AbstractPostgresIT {

    /** The migrated table this class interrogates, unqualified as the catalogue reports it. */
    private static final String TABLE = "transaction_type";

    /**
     * The primary-key column, named without a code suffix exactly as the legacy field is named.
     *
     * <p>Five sibling columns elsewhere in the schema name a transaction type with a {@code _cd}
     * suffix, and regularising this one to match them would fail start-up under validate-only
     * mapping. {@link #SUFFIXED_KEY_COLUMN} is asserted absent so that drift is caught by name.
     */
    private static final String KEY_COLUMN = "tran_type";

    /** The description column, 50 characters wide, mapped from offset 2 of the record. */
    private static final String DESCRIPTION_COLUMN = "tran_type_desc";

    /**
     * The suffix no column of this table may end with.
     *
     * <p>The sibling tables that classify a transaction spell their type-code column with this
     * suffix; this reference table's own key does not, because the legacy field it maps does not.
     * Asserting the suffix rather than the whole regularised name keeps the forbidden spelling out of
     * this file entirely while still catching it by name.
     */
    private static final String CODE_SUFFIX = "_cd";

    /** The primary-key constraint, whose name is also the name of its backing index. */
    private static final String PRIMARY_KEY_CONSTRAINT = "pk_transaction_type";

    /** The entity attribute mapped to {@link #KEY_COLUMN}, which is the identifier. */
    private static final String KEY_ATTRIBUTE = "tranType";

    /** The entity attribute mapped to {@link #DESCRIPTION_COLUMN}. */
    private static final String DESCRIPTION_ATTRIBUTE = "tranTypeDesc";

    /** Rows the reference-data migration seeds, one per record of the 427-byte fixture. */
    private static final int SEEDED_ROW_COUNT = 7;

    /** Declared width of the type code, in characters, matching the 2-byte field at offset 0. */
    private static final int KEY_WIDTH = 2;

    /** Declared width of the description, in characters, matching the 50-byte field at offset 2. */
    private static final int DESCRIPTION_WIDTH = 50;

    /** The seeded type codes, in key order, each two characters with its leading zero intact. */
    private static final List<String> SEEDED_TYPE_CODES =
            List.of("01", "02", "03", "04", "05", "06", "07");

    /**
     * The seeded descriptions, positionally paired with {@link #SEEDED_TYPE_CODES}.
     *
     * <p>Restated as literals rather than read back from the migration, which is the point: an
     * expectation derived from the thing it describes moves whenever that thing moves and would keep
     * passing after the seed was edited.
     */
    private static final List<String> SEEDED_DESCRIPTIONS = List.of("Purchase", "Payment", "Credit",
            "Authorization", "Refund", "Reversal", "Adjustment");

    /** A well-formed two-character code outside the seeded range, used to probe for absence. */
    private static final String ABSENT_TYPE_CODE = "98";

    /**
     * The first seeded code with its leading zero dropped.
     *
     * <p>Held as text throughout, so this is a different key from {@code "01"} and must resolve to
     * nothing. Were the code carried as a number anywhere on the path, the two would collapse into one.
     */
    private static final String SINGLE_CHARACTER_CODE = "1";

    /** A two-character code reserved for the one mutating test, outside the seeded range. */
    private static final String RESERVED_TYPE_CODE = "99";

    /**
     * A description occupying the full published width, blank-padded exactly as the record image is.
     *
     * <p>Twenty-four characters of text followed by twenty-six blanks. The trailing blanks are the
     * assertion: variable-length text preserves them and fixed-length text would not, and a mapper
     * that trimmed on the way in or out would return something shorter.
     */
    private static final String PADDED_DESCRIPTION = "ROUND TRIP AT FULL WIDTH" + " ".repeat(26);

    /** The setting that decides whether a completed refresh is a mapping-validation result. */
    private static final String DDL_AUTO_PROPERTY = "spring.jpa.hibernate.ddl-auto";

    /** The only value of that setting under which this class's start-up proves anything. */
    private static final String VALIDATE_ONLY = "validate";

    /** How the catalogue spells a variable-length text column; a fixed-length one reads differently. */
    private static final String VARIABLE_LENGTH_TEXT = "character varying";

    /** How the catalogue reports a column that forbids the absent value. */
    private static final String NOT_NULLABLE = "NO";

    /** How the catalogue reports a column the server does not assign for the caller. */
    private static final String NOT_IDENTITY = "NO";

    /** How the catalogue reports a column that is never computed from other columns. */
    private static final String NEVER_GENERATED = "NEVER";

    /**
     * The catalogue pattern matching any sequence this table might have been given.
     *
     * <p>The underscore is escaped because it is a single-character wildcard in a pattern and the
     * server treats a backslash as the escape by default. Bound as a parameter, never assembled into
     * the statement.
     */
    private static final String SEQUENCE_NAME_PATTERN = "transaction\\_type%";

    /** The repository under test, as the slice below assembled it. */
    @Autowired
    private TransactionTypeRepository repository;

    /** Catalogue access, used for schema introspection only and never for a row of business data. */
    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** The persistence unit, whose successful creation is the mapping-validation result. */
    @Autowired
    private EntityManagerFactory entityManagerFactory;

    /** The resolved environment, read for the setting that makes the validation result meaningful. */
    @Autowired
    private Environment environment;

    /**
     * The unit of work the one mutating test writes through.
     *
     * <p>Detaching what has been written is what turns that test's read back into a real one: without
     * it the identifier lookup would be answered from the persistence context and would prove only
     * that an object was remembered, not that a row was stored.
     */
    @PersistenceContext
    private EntityManager entityManager;

    /** Creates the test class. */
    TransactionTypeRepositoryIT() {
    }

    @Test
    @DisplayName("the reference seed holds exactly seven rows, keyed by the two-character codes 01 to 07")
    void theSeedHoldsSevenRowsKeyedByTwoCharacterCodes() {
        assertThat(this.repository.count())
                .as("the migration seeds one row per record of the 427-byte reference fixture, that is "
                        + "7 records at the 60-byte record length plus one line terminator each")
                .isEqualTo(SEEDED_ROW_COUNT);

        assertThat(this.repository.findAll())
                .as("every seeded key is two characters wide with its leading zero intact")
                .extracting(TransactionType::getTranType)
                .containsExactlyInAnyOrderElementsOf(SEEDED_TYPE_CODES);
    }

    @Test
    @DisplayName("every seeded code resolves to the description the migration stores, character for "
            + "character and with no normalisation applied first")
    void everySeededCodeResolvesToItsStoredDescription() {
        for (int index = 0; index < SEEDED_TYPE_CODES.size(); index++) {
            final String code = SEEDED_TYPE_CODES.get(index);
            final String description = SEEDED_DESCRIPTIONS.get(index);

            final Optional<TransactionType> found = this.repository.findById(code);
            assertThat(found).as("code %s is one of the seven seeded rows", code).isPresent();

            final TransactionType stored = found.orElseThrow();
            assertThat(stored.getTranType())
                    .as("the identifier is the business key, returned exactly as stored")
                    .isEqualTo(code)
                    .hasSize(KEY_WIDTH);
            assertThat(stored.getTranTypeDesc())
                    .as("the description column is variable-length text 50 characters wide, not "
                            + "fixed-length, so the seeded value comes back at its own length rather "
                            + "than blank-padded to the field width")
                    .isEqualTo(description)
                    .hasSize(description.length());
            assertThat(this.repository.existsById(code))
                    .as("existence is answered by the same primary key")
                    .isTrue();
        }
    }

    @Test
    @DisplayName("an unseeded code answers an empty result rather than throwing, and a code that has "
            + "lost its leading zero is a different key entirely")
    void anUnseededCodeAnswersEmptyAndALostLeadingZeroIsADifferentKey() {
        assertThat(this.repository.findById(ABSENT_TYPE_CODE))
                .as("the repository's whole expression of record-not-found is the empty result; it "
                        + "declares no exception type and no status value of its own")
                .isEmpty();
        assertThat(this.repository.existsById(ABSENT_TYPE_CODE)).isFalse();

        assertThat(this.repository.findById(SINGLE_CHARACTER_CODE))
                .as("the code is held as text, so dropping the leading zero does not find the row")
                .isEmpty();

        final String firstSeededCode = SEEDED_TYPE_CODES.get(0);
        final TransactionType firstSeeded = this.repository.findById(firstSeededCode).orElseThrow();
        assertThat(firstSeeded.getTranType())
                .isEqualTo(firstSeededCode)
                .isNotEqualTo(SINGLE_CHARACTER_CODE)
                .hasSize(KEY_WIDTH);
        assertThat(firstSeeded)
                .as("entity identity is the exact code, compared with no padding adjustment and no "
                        + "case folding, so the two-character form and the one-character form are "
                        + "never the same row")
                .isNotEqualTo(TestDataFactory.transactionType()
                        .typeCode(SINGLE_CHARACTER_CODE)
                        .description(SEEDED_DESCRIPTIONS.get(0))
                        .build());
    }

    @Test
    @DisplayName("the context refreshes with the mapping validated against the migrated table, and the "
            + "entity declares exactly the two mapped attributes over a scalar identifier")
    void theMappingIsValidatedAgainstTheMigratedTable() throws SQLException {
        assertThat(this.environment.getProperty(DDL_AUTO_PROPERTY))
                .as("a refreshed context is only a mapping-validation result while the posture is "
                        + "validate-only; under a generating posture the provider would have "
                        + "reconciled any drift silently instead of failing start-up")
                .isEqualTo(VALIDATE_ONLY);

        final EntityType<TransactionType> entityType =
                this.entityManagerFactory.getMetamodel().entity(TransactionType.class);
        assertThat(entityType.getAttributes())
                .as("52 of the 60 record bytes carry information, so the entity has exactly two "
                        + "attributes; the 8 trailing filler bytes at offset 52 are not one of them")
                .extracting(Attribute::getName)
                .containsExactlyInAnyOrder(KEY_ATTRIBUTE, DESCRIPTION_ATTRIBUTE);
        assertThat(entityType.getId(String.class).getName())
                .as("this is the one reference table whose key is scalar, so the identifier is a "
                        + "single text attribute and no identifier class exists for it")
                .isEqualTo(KEY_ATTRIBUTE);

        assertThat(applicationTableNames())
                .as("the table is one of the eleven the schema migration creates")
                .contains(TABLE);
    }

    @Test
    @DisplayName("the table carries exactly the two mapped columns, both variable-length text at the "
            + "record widths, and neither a code-suffixed key nor a filler or version column")
    void theTableCarriesExactlyTheTwoMappedColumns() {
        assertThat(columnNames())
                .as("two columns and no more: no column stands for the 8 trailing filler bytes, and "
                        + "this table takes no version column because nothing updates it outside a seed")
                .containsExactly(KEY_COLUMN, DESCRIPTION_COLUMN)
                .allSatisfy(name -> assertThat(name)
                        .as("the key of this reference table is spelled without the code suffix the "
                                + "classifying tables use, and regularising it would fail start-up "
                                + "under validate-only mapping")
                        .doesNotEndWith(CODE_SUFFIX));

        final ColumnFacts key = columnFacts(KEY_COLUMN);
        assertThat(key.dataType()).isEqualTo(VARIABLE_LENGTH_TEXT);
        assertThat(key.maximumLength())
                .as("the 2-byte type code at offset 0 of the record")
                .isEqualTo(KEY_WIDTH);
        assertThat(key.isNullable()).isEqualTo(NOT_NULLABLE);

        final ColumnFacts description = columnFacts(DESCRIPTION_COLUMN);
        assertThat(description.dataType())
                .as("variable-length rather than fixed-length text, which is what lets a value be "
                        + "stored at its own length and lets a padded value keep its trailing blanks")
                .isEqualTo(VARIABLE_LENGTH_TEXT);
        assertThat(description.maximumLength())
                .as("the 50-byte description at offset 2 of the record")
                .isEqualTo(DESCRIPTION_WIDTH);
        assertThat(description.isNullable()).isEqualTo(NOT_NULLABLE);
    }

    @Test
    @DisplayName("the primary key is the only index on the table, and it covers the single key column")
    void thePrimaryKeyIsTheOnlyIndexOnTheTable() {
        assertThat(indexNames())
                .as("the index migration creates three secondary indexes and not one of them touches "
                        + "this table, so the constraint's own backing index is all that exists")
                .containsExactly(PRIMARY_KEY_CONSTRAINT);
        assertThat(nonPrimaryIndexCount())
                .as("read a second way, through the index catalogue rather than its view, so a "
                        + "secondary index could not hide behind a naming assumption")
                .isZero();
        assertThat(primaryKeyColumns())
                .as("a single-column key, unlike the composite keys of the sibling reference tables")
                .containsExactly(KEY_COLUMN);
    }

    @Test
    @DisplayName("no foreign key originates from the reference table and none targets it")
    void noForeignKeyOriginatesFromOrTargetsTheReferenceTable() {
        assertThat(outgoingForeignKeyCount())
                .as("the type code is a classification carried on a record, validated by program "
                        + "logic where at all, so constraining it would change which rows the system "
                        + "accepts and would make the rejection paths unreachable")
                .isZero();
        assertThat(incomingForeignKeyCount())
                .as("no table points at this one either, so the description lookup is a plain read "
                        + "by identifier with no association modelled in either direction")
                .isZero();
        assertThat(schemaForeignKeyCount())
                .as("the two zeros above are read with the same catalogue query that does find the "
                        + "constraints the index migration creates elsewhere, so neither is vacuous")
                .isPositive();
    }

    @Test
    @DisplayName("no sequence, identity or generated column stands behind the business key")
    void noSequenceOrGeneratedColumnStandsBehindTheBusinessKey() {
        assertThat(sequenceCount())
                .as("the key is the leading substring of the record image, so a machine-assigned "
                        + "substitute would break the correspondence between image and row that "
                        + "byte-parity verification of the fixed-width outputs depends on")
                .isZero();

        for (final String column : List.of(KEY_COLUMN, DESCRIPTION_COLUMN)) {
            final ColumnFacts facts = columnFacts(column);
            assertThat(facts.columnDefault())
                    .as("column %s carries no default, so every value comes from the caller", column)
                    .isNull();
            assertThat(facts.isIdentity())
                    .as("column %s is not server-assigned", column)
                    .isEqualTo(NOT_IDENTITY);
            assertThat(facts.isGenerated())
                    .as("column %s is not computed from another column", column)
                    .isEqualTo(NEVER_GENERATED);
        }
    }

    @Test
    @Transactional
    @DisplayName("a written row survives the round trip with its two-character key and its "
            + "fifty-character description unchanged, trailing blanks included")
    void aWrittenRowSurvivesTheRoundTripAtTheFullPublishedWidth() {
        assertThat(PADDED_DESCRIPTION)
                .as("the fixture occupies the full published field width before it is written")
                .hasSize(DESCRIPTION_WIDTH);

        final TransactionType candidate = TestDataFactory.transactionType()
                .typeCode(RESERVED_TYPE_CODE)
                .description(PADDED_DESCRIPTION)
                .build();

        this.repository.saveAndFlush(candidate);
        this.entityManager.clear();

        final Optional<TransactionType> reread = this.repository.findById(RESERVED_TYPE_CODE);
        assertThat(reread)
                .as("the persistence context was detached first, so this lookup is answered from the "
                        + "stored row rather than from what the unit of work still remembered")
                .isPresent();

        final TransactionType stored = reread.orElseThrow();
        assertThat(stored.getTranType())
                .as("a reserved two-character key outside the seeded range, returned intact")
                .isEqualTo(RESERVED_TYPE_CODE)
                .hasSize(KEY_WIDTH);
        assertThat(stored.getTranTypeDesc())
                .as("the description survives at the full width: a fixed-length column would have "
                        + "returned padding of its own and a trimming mapper would have returned less")
                .isEqualTo(PADDED_DESCRIPTION)
                .hasSize(DESCRIPTION_WIDTH);

        assertThat(this.repository.count())
                .as("the write landed alongside the seven seeded rows; this method is transactional, "
                        + "so it is rolled back and the shared server is left seeded for every other "
                        + "test rather than reset for them")
                .isEqualTo(SEEDED_ROW_COUNT + 1L);
    }

    /**
     * Reads the table's column names in declaration order.
     *
     * @return the column names, ordered as the table declares them
     */
    private List<String> columnNames() {
        return this.jdbcTemplate.queryForList("""
                SELECT column_name
                  FROM information_schema.columns
                 WHERE table_schema = 'public' AND table_name = ?
                 ORDER BY ordinal_position
                """, String.class, TABLE);
    }

    /**
     * Reads the declared shape of one column.
     *
     * @param column the column to describe
     * @return the catalogue's own account of that column
     */
    private ColumnFacts columnFacts(final String column) {
        final List<ColumnFacts> rows = this.jdbcTemplate.query("""
                SELECT data_type, character_maximum_length, is_nullable, column_default,
                       is_identity, is_generated
                  FROM information_schema.columns
                 WHERE table_schema = 'public' AND table_name = ? AND column_name = ?
                """,
                (row, rowNumber) -> new ColumnFacts(row.getString("data_type"),
                        row.getInt("character_maximum_length"), row.getString("is_nullable"),
                        row.getString("column_default"), row.getString("is_identity"),
                        row.getString("is_generated")),
                TABLE, column);
        assertThat(rows).as("column %s is declared exactly once", column).hasSize(1);
        return rows.get(0);
    }

    /**
     * Reads every index on the table, whatever created it.
     *
     * @return the index names in name order
     */
    private List<String> indexNames() {
        return this.jdbcTemplate.queryForList("""
                SELECT indexname
                  FROM pg_indexes
                 WHERE schemaname = 'public' AND tablename = ?
                 ORDER BY indexname
                """, String.class, TABLE);
    }

    /**
     * Counts the indexes on the table that do not back its primary key.
     *
     * @return the number of secondary indexes, which must be none
     */
    private int nonPrimaryIndexCount() {
        return catalogueCount("""
                SELECT count(*)
                  FROM pg_index AS index_entry
                  JOIN pg_class AS table_entry ON table_entry.oid = index_entry.indrelid
                  JOIN pg_namespace AS schema_entry ON schema_entry.oid = table_entry.relnamespace
                 WHERE schema_entry.nspname = 'public'
                   AND table_entry.relname = ?
                   AND NOT index_entry.indisprimary
                """, TABLE);
    }

    /**
     * Reads the columns the primary-key constraint covers, in key order.
     *
     * @return the key columns, which must be the single business key
     */
    private List<String> primaryKeyColumns() {
        return this.jdbcTemplate.queryForList("""
                SELECT key_column.column_name
                  FROM information_schema.table_constraints AS table_constraint
                  JOIN information_schema.key_column_usage AS key_column
                    ON key_column.constraint_schema = table_constraint.constraint_schema
                   AND key_column.constraint_name = table_constraint.constraint_name
                 WHERE table_constraint.table_schema = 'public'
                   AND table_constraint.table_name = ?
                   AND table_constraint.constraint_type = 'PRIMARY KEY'
                 ORDER BY key_column.ordinal_position
                """, String.class, TABLE);
    }

    /**
     * Counts the foreign keys declared on the table itself.
     *
     * @return the number of outgoing foreign keys, which must be none
     */
    private int outgoingForeignKeyCount() {
        return catalogueCount("""
                SELECT count(*)
                  FROM pg_constraint AS constraint_entry
                  JOIN pg_class AS table_entry ON table_entry.oid = constraint_entry.conrelid
                  JOIN pg_namespace AS schema_entry ON schema_entry.oid = table_entry.relnamespace
                 WHERE constraint_entry.contype = 'f'
                   AND schema_entry.nspname = 'public'
                   AND table_entry.relname = ?
                """, TABLE);
    }

    /**
     * Counts the foreign keys elsewhere in the schema that reference the table.
     *
     * @return the number of incoming foreign keys, which must be none
     */
    private int incomingForeignKeyCount() {
        return catalogueCount("""
                SELECT count(*)
                  FROM pg_constraint AS constraint_entry
                  JOIN pg_class AS table_entry ON table_entry.oid = constraint_entry.confrelid
                  JOIN pg_namespace AS schema_entry ON schema_entry.oid = table_entry.relnamespace
                 WHERE constraint_entry.contype = 'f'
                   AND schema_entry.nspname = 'public'
                   AND table_entry.relname = ?
                """, TABLE);
    }

    /**
     * Counts every foreign key in the migrated schema, so that the two zeros above are not vacuous.
     *
     * @return the number of foreign keys the schema does declare
     */
    private int schemaForeignKeyCount() {
        return catalogueCount("""
                SELECT count(*)
                  FROM pg_constraint AS constraint_entry
                  JOIN pg_namespace AS schema_entry
                    ON schema_entry.oid = constraint_entry.connamespace
                 WHERE constraint_entry.contype = 'f' AND schema_entry.nspname = 'public'
                """);
    }

    /**
     * Counts the sequences whose name the table could have claimed.
     *
     * @return the number of such sequences, which must be none
     */
    private int sequenceCount() {
        return catalogueCount("""
                SELECT count(*)
                  FROM information_schema.sequences
                 WHERE sequence_schema = 'public' AND sequence_name LIKE ?
                """, SEQUENCE_NAME_PATTERN);
    }

    /**
     * Runs a catalogue query that counts rows.
     *
     * <p>Every statement handed here is a fixed literal and every value it needs is bound as a
     * parameter, so no statement is assembled from text at run time.
     *
     * @param sql       the counting statement
     * @param arguments the values to bind, in the order the statement expects them
     * @return the count the catalogue reported
     */
    private int catalogueCount(final String sql, final Object... arguments) {
        final Integer count = this.jdbcTemplate.queryForObject(sql, Integer.class, arguments);
        assertThat(count).as("a catalogue count is a row, never an absent one").isNotNull();
        return count.intValue();
    }

    /**
     * The catalogue's account of one column, read as typed values rather than as untyped cells.
     *
     * @param dataType      how the server names the column's type
     * @param maximumLength the declared character width
     * @param isNullable    whether the column admits the absent value
     * @param columnDefault the default expression, or {@code null} when there is none
     * @param isIdentity    whether the server assigns the value
     * @param isGenerated   whether the value is computed from other columns
     */
    private record ColumnFacts(String dataType, int maximumLength, String isNullable,
            String columnDefault, String isIdentity, String isGenerated) {
    }

    /**
     * The narrowest context this table needs: a data source, the persistence unit, catalogue access
     * and transaction management, with the repository and the entity registered over them.
     *
     * <p>Named explicitly on {@code @SpringBootTest} rather than discovered by scanning. A scan rooted
     * at the application's base package reaches every type on the class path, and during an
     * integration run that class path also holds this module's test tree with its many nested
     * configurations; naming the slice keeps this context to what the assertions actually need. The
     * data-source address is not declared here either: it arrives from the container the base class
     * owns, at a precedence above every property file.
     */
    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration({DataSourceAutoConfiguration.class, JdbcTemplateAutoConfiguration.class,
            HibernateJpaAutoConfiguration.class, TransactionAutoConfiguration.class})
    @EnableJpaRepositories(basePackageClasses = TransactionTypeRepository.class)
    @EntityScan(basePackageClasses = TransactionType.class)
    static class RepositoryUnderTest {

        /** Creates the configuration. */
        RepositoryUnderTest() {
        }
    }
}
