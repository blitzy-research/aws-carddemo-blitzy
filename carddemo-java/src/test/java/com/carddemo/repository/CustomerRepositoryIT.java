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

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import com.carddemo.domain.Customer;
import com.carddemo.support.AbstractPostgresIT;
import com.carddemo.support.TestDataFactory;
import com.carddemo.util.SensitiveFieldCodec;

/**
 * Verifies the relational contract of the widest record in the estate against a real PostgreSQL 16
 * server: the five-hundred byte customer record, its eighteen persisted fields, and the inherited
 * repository operations every caller of {@link CustomerRepository} reaches it through.
 *
 * <h2>What this class proves, and why each part of it needs a real server</h2>
 * <ol>
 *   <li><strong>The mapping is complete and the column names are the migration's, not a tidied-up
 *       version of them.</strong> Eighteen legacy fields occupy 332 contiguous bytes from offset zero
 *       and are followed by 168 bytes of trailing filler that is deliberately not persisted, which is
 *       what accounts for all five hundred. Each field's own column is read back out of the live
 *       catalogue and compared against the copybook-derived geometry, so a rename, a re-ordering or a
 *       narrowed width fails here rather than at run time.</li>
 *   <li><strong>The national identifier is the one nullable column in the whole eleven-table
 *       schema.</strong> It is far wider than its legacy field because it stores application-produced
 *       ciphertext, and absence has to survive storage as a genuine null.</li>
 *   <li><strong>Nothing at the persistence layer constrains the credit score.</strong> Twenty-one of
 *       the fifty delivered rows carry a score below the value the update screen enforces, so a range
 *       constraint here would refuse data the legacy system stored.</li>
 *   <li><strong>The delivered seed is exactly fifty rows, and the one foreign key that touches this
 *       table points at it rather than out of it.</strong></li>
 *   <li><strong>Stored text is returned byte for byte.</strong> Trailing pad survives a round trip,
 *       narrow codes keep their exact widths, and nothing is trimmed, folded or reformatted on either
 *       side of the boundary.</li>
 *   <li><strong>The unversioned compare-and-set the account-update path depends on behaves as its
 *       declaration promises</strong> - it rewrites a matching row exactly once, declines a row that
 *       moved after the read, and refuses a replacement carrying a different key.</li>
 * </ol>
 *
 * <h2>Encryption at rest is a documented improvement, not a parity change</h2>
 * The legacy record carries the national identifier as nine cleartext characters and the
 * government-issued identifier as twenty, both entirely unprotected: no field-level encryption,
 * masking or redaction exists anywhere in the legacy design, and every legacy file definition is
 * declared with uncommitted read integrity, no recovery and no journalling. This schema stores both
 * as authenticated ciphertext in a 255-character column instead, which is why neither column width
 * equals its legacy field width. That is a deliberate divergence in favour of the credential and
 * privacy constraints, it changes no record image and no output byte, and it is recorded as an
 * improvement over the baseline rather than as a regression. Nothing here asserts a cleartext value,
 * prints one, or embeds one in a message: the assertions are about shape, width and nullity only.
 *
 * <h2>Seed ordering, and the direction of the one foreign key</h2>
 * The reference migration inserts this table <em>first</em> - before the account, the card and the
 * card cross-reference - because {@code fk_card_xref_customer} points <em>at</em> {@code customer}
 * from {@code card_cross_reference}. <strong>No foreign key originates from this table at all</strong>,
 * so nothing has to exist before a customer row can be written, and both facts are asserted below.
 *
 * <h2>How a customer is reached, and why there is no object graph</h2>
 * A card resolves to a customer through the cross-reference - card number to customer identifier, then
 * a keyed read - and there is no direct card-to-customer path. That relationship is enforced by the
 * database as a constraint and is deliberately not modelled as an association: this module declares no
 * association mapping of any kind, no fetch graph and no join fetch, and the open-in-view setting is
 * off in every profile. Scalar business keys mirror the record image, and an association would change
 * the column name the provider expects. Nothing here adds one.
 *
 * <h2>One entity for two copybooks</h2>
 * A second legacy copybook describes the same five hundred bytes at the same offsets and differs only
 * in the spelling of the date-of-birth field name. It is live for the statement-generation program and
 * is mapped by {@code com.carddemo.util.CustomerRecordMapper} as an alternate view of the same record -
 * <strong>one entity, one table, one repository</strong>. No second entity and no second repository
 * exists, and no assertion here reaches for the alternate field name: at the persistence layer there
 * is one column, and the mapper is where the two spellings meet.
 *
 * <h2>No optimistic-locking column</h2>
 * Only the account and card tables carry a version column in this schema. {@code customer} has none,
 * which is precisely why {@link CustomerRepository#compareAndSet(Customer, Customer)} exists and why
 * nothing here asserts optimistic locking.
 *
 * <h2>How the context is assembled</h2>
 * The class extends {@link AbstractPostgresIT}, the module's single owner of the database server, and
 * declares no server, no server lifecycle and no data-source registration of its own - the base
 * publishes the started server's real address, and the active profile, into this context. What is
 * declared here is only what belongs to this class: a narrow configuration carrying the customer
 * entity and the repository package, and the four auto-configurations that turn them into a working
 * persistence layer. The slice is narrow on purpose. A repository-tier test may depend on the domain
 * layer and on nothing above it, so no service, controller, batch or messaging bean is loaded, and the
 * dependency direction the production packages observe is observed here too.
 *
 * <p>The engine is real PostgreSQL 16 with the delivered migrations applied from the single flat
 * migration location. No in-memory engine is substituted, no repository, entity manager or data source
 * is mocked, and the schema is <em>validated</em> rather than generated - so the eighteen mappings are
 * checked against the migrated schema at every context refresh, before a single assertion runs. That
 * validation is also how the entity side of the mapping is proven without reflection: nothing here
 * reads an annotation, loads a class by name or touches the reflection API. What the provider
 * validates, the catalogue reads confirm from the other side, and a genuine eighteen-property round
 * trip confirms end to end.
 *
 * <h2>How this class keeps out of every other class's way</h2>
 * A context-discarding annotation is not used - it would throw the shared context away and defeat the
 * reuse that keeps the integration phase quick. Instead this class follows the convention the estate
 * already uses: it reserves a key range of its own, disjoint from the delivered seed and from every
 * other class's range, and removes exactly those rows after each test. The delivered fifty rows, and
 * the fifty cross-reference rows that point at them, are never deleted. The one test that must observe
 * a rejected write does so inside an explicit database transaction that it rolls back, so the seeded
 * row it borrows is put back by the server itself.
 *
 * <p>Provenance: translated from the read-only legacy estate at checkout
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. The record geometry comes from copybook
 * {@code CVCUS01Y}, its alternate view from {@code CUSTREC}, the key and record length from job
 * {@code CUSTFILE}, and the cross-reference geometry from {@code CVACT03Y}. No legacy source text is
 * reproduced here: members, field names, widths, offsets and row counts are cited by reference only,
 * and the release stamp is a provenance string for the traceability matrix header rather than
 * something asserted against a member.
 *
 * @see CustomerRepository
 * @see Customer
 */
@SpringBootTest(classes = CustomerRepositoryIT.RepositoryUnderTest.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DisplayName("Customer repository: the five-hundred byte record's relational contract on PostgreSQL")
final class CustomerRepositoryIT extends AbstractPostgresIT {

    /** The table the entity is bound to. */
    private static final String TABLE = "customer";

    /** The referencing table of the one foreign key that touches this table. */
    private static final String REFERENCING_TABLE = "card_cross_reference";

    /** The name of that foreign key, as the index migration declares it. */
    private static final String CUSTOMER_FOREIGN_KEY = "fk_card_xref_customer";

    /** The only index the schema gives this table: the one backing its primary key. */
    private static final String PRIMARY_KEY_INDEX = "pk_customer";

    /** The check constraint over the business key alone. */
    private static final String KEY_DIGIT_CONSTRAINT = "ck_customer_cust_id_digits";

    /** The name of the encoding rule, which every text column of this table appears inside. */
    private static final String BYTE_REPERTOIRE_CONSTRAINT = "ck_customer_single_byte_text";

    /** The invariant requiring the nullable regulated identifier to be a protected envelope. DL-349. */
    private static final String SSN_PROTECTED_CONSTRAINT = "ck_customer_cust_ssn_protected";

    /** The invariant requiring the mandatory regulated identifier to be a protected envelope. DL-349. */
    private static final String GOVT_PROTECTED_CONSTRAINT = "ck_customer_govt_issued_id_protected";

    /** The type every column of this table is declared as, as the catalogue spells it. */
    private static final String BOUNDED_TEXT_TYPE = "character varying";

    /** Width of the two columns that hold ciphertext rather than the legacy cleartext. */
    private static final int PROTECTED_COLUMN_WIDTH = 255;

    /** How many application tables the schema migration creates. */
    private static final int APPLICATION_TABLE_COUNT = 11;

    /**
     * The three secondary indexes the index migration creates, named with their owning table.
     *
     * <p>One per legacy alternate index and nothing else. None of them is on this table, which is the
     * point of holding the list here: an index added to {@code customer} would appear in the live reading
     * and fail the comparison, and an index moved off one of these three tables would fail it as well.
     */
    private static final List<String> SECONDARY_INDEXES = List.of(
            "card -> idx_card_card_acct_id",
            "card_cross_reference -> idx_card_cross_reference_xref_acct_id",
            "transaction -> idx_transaction_tran_proc_ts");

    // =============================================================================================
    // THE EIGHTEEN MAPPINGS
    // =============================================================================================

    /**
     * One verified mapping: a legacy field, the byte range it occupies, the column that carries it, and
     * that column's own declared width.
     *
     * <p>Both widths are recorded because for sixteen of the eighteen they are the same figure and for
     * two of them they are not. The national identifier and the government-issued identifier hold
     * ciphertext, so their columns are 255 characters wide while their legacy fields are nine and twenty
     * bytes. Recording the two separately is what lets one assertion check the record geometry and
     * another check the column declaration without either quietly standing in for the other.
     *
     * @param legacyName   the legacy field name, exactly as the copybook spells it
     * @param columnName   the column the schema migration creates for it
     * @param legacyOffset zero-based byte offset of the field within the record image
     * @param legacyWidth  width of the field in the record image, in bytes
     * @param columnWidth  the column's declared maximum length in characters
     * @param nullable     whether the column admits a null
     */
    private record Mapping(String legacyName, String columnName, int legacyOffset, int legacyWidth,
            int columnWidth, boolean nullable) {
    }

    /**
     * Every mapping the migrated customer table carries, in record order.
     *
     * <p><strong>The column names are not uniformly prefixed and must not be regularized.</strong> Every
     * legacy field name in this record carries the entity prefix, but only three columns keep it -
     * {@code cust_id}, {@code cust_ssn} and {@code cust_dob}. The other fifteen drop it. That asymmetry
     * is transcribed from the migration that owns the table, the provider validates against that
     * migration rather than generating it, and a column renamed here to look consistent aborts start-up.
     * It is asserted below rather than tidied away.
     *
     * <p>Where a legacy name is multi-part its column separates the trailing digit with an underscore -
     * three address lines and two telephone numbers - and nothing may collapse that separator.
     */
    private static final List<Mapping> MAPPINGS = List.of(
            new Mapping("CUST-ID", "cust_id", 0, 9, 9, false),
            new Mapping("CUST-FIRST-NAME", "first_name", 9, 25, 25, false),
            new Mapping("CUST-MIDDLE-NAME", "middle_name", 34, 25, 25, false),
            new Mapping("CUST-LAST-NAME", "last_name", 59, 25, 25, false),
            new Mapping("CUST-ADDR-LINE-1", "addr_line_1", 84, 50, 50, false),
            new Mapping("CUST-ADDR-LINE-2", "addr_line_2", 134, 50, 50, false),
            new Mapping("CUST-ADDR-LINE-3", "addr_line_3", 184, 50, 50, false),
            new Mapping("CUST-ADDR-STATE-CD", "addr_state_cd", 234, 2, 2, false),
            new Mapping("CUST-ADDR-COUNTRY-CD", "addr_country_cd", 236, 3, 3, false),
            new Mapping("CUST-ADDR-ZIP", "addr_zip", 239, 10, 10, false),
            new Mapping("CUST-PHONE-NUM-1", "phone_num_1", 249, 15, 15, false),
            new Mapping("CUST-PHONE-NUM-2", "phone_num_2", 264, 15, 15, false),
            new Mapping("CUST-SSN", "cust_ssn", 279, 9, PROTECTED_COLUMN_WIDTH, true),
            new Mapping("CUST-GOVT-ISSUED-ID", "govt_issued_id", 288, 20, PROTECTED_COLUMN_WIDTH, false),
            new Mapping("CUST-DOB-YYYY-MM-DD", "cust_dob", 308, 10, 10, false),
            new Mapping("CUST-EFT-ACCOUNT-ID", "eft_account_id", 318, 10, 10, false),
            new Mapping("CUST-PRI-CARD-HOLDER-IND", "pri_card_holder_ind", 328, 1, 1, false),
            new Mapping("CUST-FICO-CREDIT-SCORE", "fico_credit_score", 329, 3, 3, false));

    /** The business key's column, held once so the key assertions read the one name. */
    private static final String KEY_COLUMN = MAPPINGS.get(0).columnName();

    /** The national identifier's column: the one nullable column in the whole schema. */
    private static final String NATIONAL_IDENTIFIER_COLUMN = MAPPINGS.get(12).columnName();

    /** The government-issued identifier's column: protected in the same way, but mandatory. */
    private static final String GOVERNMENT_IDENTIFIER_COLUMN = MAPPINGS.get(13).columnName();

    /** The credit-score column, whose deliberate lack of a range constraint is asserted below. */
    private static final String CREDIT_SCORE_COLUMN = MAPPINGS.get(17).columnName();

    /** The prefix the legacy field names all carry and that only three columns keep. */
    private static final String KEPT_PREFIX = "cust_";

    /** The three columns that keep that prefix, in record order. */
    private static final List<String> PREFIXED_COLUMNS =
            List.of("cust_id", "cust_ssn", "cust_dob");

    /** The five columns whose legacy name is multi-part, in record order. */
    private static final List<String> DIGIT_SUFFIXED_COLUMNS = List.of(
            "addr_line_1", "addr_line_2", "addr_line_3", "phone_num_1", "phone_num_2");

    // =============================================================================================
    // MEASURED FACTS ABOUT THE DELIVERED SEED
    // =============================================================================================

    /** The first delivered customer, and the record the fixture builder defaults to. */
    private static final String SEEDED_FIRST_ID = "000000001";

    /** The last delivered customer, whose sealed identifier the compare-and-set fixtures copy. */
    private static final String SEEDED_LAST_ID = "000000050";

    /** The delivered customer carrying the lowest credit score of the fifty. */
    private static final String SEEDED_LOWEST_SCORE_ID = "000000026";

    /** That row's score: three characters, well below the value the update screen enforces. */
    private static final String SEEDED_LOWEST_SCORE = "001";

    /** How many of the fifty delivered rows carry a score below the screen's lower bound. */
    private static final int SEEDED_SCORES_BELOW_SCREEN_MINIMUM = 21;

    /**
     * The first delivered row's eighteenth field, asserted so the exact seeded form is pinned.
     *
     * <p>Also below the screen's lower bound, which is why the fixture builder deliberately defaults to
     * a different value: taking the first record's score as a builder default would make every fixture
     * produce input the update surface refuses.
     */
    private static final String SEEDED_FIRST_SCORE = "274";

    /**
     * The first delivered row's display fields, in record order, exactly as the reference migration
     * inserts them.
     *
     * <p>Read out of that migration before this constant was written, and asserted rather than
     * normalized. Two things about the forms matter and are the reason this constant exists. The postal
     * code is five characters here while its column is ten and its legacy field is ten, and the
     * telephone numbers are thirteen characters while theirs are fifteen - so the seed stores display
     * text at its own length rather than padded to the field width, and a bounded variable-length column
     * is what makes that lossless. Nothing in the module trims on the way in or pads on the way out;
     * placing a stored value back through the record mapper reproduces the identical bytes.
     *
     * <p>Neither regulated identifier appears here. Both are sealed envelopes on all fifty rows, so
     * both are asserted elsewhere by shape, by envelope width and by opening under the column's own
     * binding, rather than by value.
     */
    private static final List<String> SEEDED_FIRST_DISPLAY_FIELDS = List.of(
            "Immanuel",
            "Madeline",
            "Kessler",
            "618 Deshaun Route",
            "Apt. 802",
            "Altenwerthshire",
            "NC",
            "USA",
            "12546",
            "(908)119-8310",
            "(373)693-8684",
            "1961-06-08",
            "0053581756",
            "Y");

    // =============================================================================================
    // THE KEY RANGE THIS CLASS RESERVES
    //
    // Nine digits each, because the write callback and a check constraint both refuse anything else.
    // Disjoint from the delivered seed, which occupies 000000001 through 000000050, and from every
    // other class's range. Removed after each test by explicit key, never by a prefix match: a prefix
    // wide enough to cover the leading-zero probe would also cover the delivered rows, and deleting
    // those would be refused by the cross-reference foreign key - correctly, and far too late.
    // =============================================================================================

    /** Carries the eighteen-property round trip. */
    private static final String ROUND_TRIP_ID = "910000001";

    /** Carries the absent-national-identifier round trip. */
    private static final String ABSENT_IDENTIFIER_ID = "910000002";

    /** Carries the ciphertext wider than the legacy field. */
    private static final String WIDE_CIPHERTEXT_ID = "910000003";

    /** Carries a credit score above the range the update screen enforces. */
    private static final String SCORE_ABOVE_RANGE_ID = "910000004";

    /** Carries a credit score below the range the update screen enforces. */
    private static final String SCORE_BELOW_RANGE_ID = "910000005";

    /**
     * Carries the leading-zero probe.
     *
     * <p>Deliberately inside the numeric neighbourhood of the delivered seed but outside its range, so
     * that the probe is about the padding rather than about being far away from everything.
     */
    private static final String LEADING_ZERO_ID = "000000091";

    /**
     * The same value with its padding removed, which must resolve to nothing.
     *
     * <p>The two are different rows claiming the same nine bytes of one record, which is exactly why the
     * schema pins the key to nine digits and why the repository must not treat them as interchangeable.
     */
    private static final String UNPADDED_ID = "91";

    /** Carries the compare-and-set copy of a delivered row. */
    private static final String COMPARE_AND_SET_ID = "910000006";

    /** A second valid key, used only to prove that compare-and-set cannot move a row. */
    private static final String DIFFERENT_KEY_ID = "910000007";

    /** Every key this class may create, removed after each test whether it was created or not. */
    private static final List<String> RESERVED_IDS = List.of(
            ROUND_TRIP_ID,
            ABSENT_IDENTIFIER_ID,
            WIDE_CIPHERTEXT_ID,
            SCORE_ABOVE_RANGE_ID,
            SCORE_BELOW_RANGE_ID,
            LEADING_ZERO_ID,
            COMPARE_AND_SET_ID,
            DIFFERENT_KEY_ID);

    // =============================================================================================
    // FIXTURE VALUES
    // =============================================================================================

    /**
     * The envelope marker every protected value carries, taken from the shared fixture rather than
     * restated.
     *
     * <p>The authoritative declaration belongs to the utility-layer codec, which this tier does not
     * depend on, so the marker is derived from a value that codec's own fixtures produce. Deriving it
     * rather than writing the literal a third time is what stops this class from drifting away from the
     * shape the entity actually enforces.
     */
    private static final String ENVELOPE_MARKER = TestDataFactory.SYNTHETIC_PROTECTED_VALUE
            .substring(0, TestDataFactory.SYNTHETIC_PROTECTED_VALUE.indexOf(':') + 1);

    /**
     * Decoded length of the widest envelope this class stores, chosen so the encoded form lands just
     * inside the column.
     *
     * <p>A multiple of three, so the encoded form needs no padding and measures exactly four thirds of
     * it. With the marker that totals 253 characters against a 255-character column, which is the widest
     * well-formed envelope the column can hold and therefore the value that proves the declared width is
     * real rather than nominal.
     */
    private static final int WIDE_CIPHERTEXT_BODY_BYTES = 186;

    /** The widest well-formed protected value this column accepts. */
    private static final String WIDE_CIPHERTEXT =
            ENVELOPE_MARKER + Base64.getEncoder().encodeToString(syntheticBody());

    /** A score above the upper bound the update screen enforces, still three characters wide. */
    private static final String SCORE_ABOVE_RANGE = "999";

    /** A score below the lower bound the update screen enforces, still three characters wide. */
    private static final String SCORE_BELOW_RANGE = "000";

    /** The first name an operator intends to write in the compare-and-set tests. */
    private static final String OPERATOR_FIRST_NAME = "OPERATOR IMAGE";

    /** The first name committed independently before the operator reaches the write point. */
    private static final String CONCURRENT_FIRST_NAME = "CONCURRENT IMAGE";

    /** The message the default method refuses a key change with. */
    private static final String KEY_CHANGE_REFUSAL = "customer compare-and-set cannot change the key";

    // =============================================================================================
    // CATALOGUE AND FIXTURE STATEMENTS
    //
    // Every statement below is a complete literal. No value, table name or column name is ever
    // concatenated into one: where a statement varies it takes a bound parameter. The two exclusions
    // that recur - the framework's own metadata tables and the migration history table - are written
    // the same way the shared base writes them, so a reading taken here and a reading taken there
    // cover the same set of tables.
    // =============================================================================================

    /** Reads this table's columns, in declaration order, with everything needed to check each one. */
    private static final String CUSTOMER_COLUMNS = """
            SELECT column_name, data_type, character_maximum_length, is_nullable, ordinal_position
              FROM information_schema.columns
             WHERE table_schema = 'public'
               AND table_name = ?
             ORDER BY ordinal_position
            """;

    /** Reads every nullable column of every application table, qualified by its table. */
    private static final String NULLABLE_APPLICATION_COLUMNS = """
            SELECT table_name || '.' || column_name
              FROM information_schema.columns
             WHERE table_schema = 'public'
               AND is_nullable = 'YES'
               AND table_name NOT LIKE 'batch\\_%'
               AND table_name <> 'flyway_schema_history'
             ORDER BY 1
            """;

    /** Reads whether a column is generated by the server rather than supplied by the writer. */
    private static final String KEY_COLUMN_GENERATION = """
            SELECT is_identity || '/' || coalesce(column_default, 'no-default')
              FROM information_schema.columns
             WHERE table_schema = 'public'
               AND table_name = ?
               AND column_name = ?
            """;

    /** Reads the columns of a table's primary key, in key order. */
    private static final String PRIMARY_KEY_COLUMNS = """
            SELECT key_column.column_name
              FROM information_schema.table_constraints AS constraint_row
              JOIN information_schema.key_column_usage AS key_column
                ON key_column.constraint_name = constraint_row.constraint_name
               AND key_column.table_schema = constraint_row.table_schema
             WHERE constraint_row.table_schema = 'public'
               AND constraint_row.table_name = ?
               AND constraint_row.constraint_type = 'PRIMARY KEY'
             ORDER BY key_column.ordinal_position
            """;

    /** Reads every index on one table, by name. */
    private static final String INDEXES_ON_TABLE = """
            SELECT indexname
              FROM pg_indexes
             WHERE schemaname = 'public'
               AND tablename = ?
             ORDER BY indexname
            """;

    /** Reads every index of every application table that does not back a primary key. */
    private static final String SECONDARY_INDEXES_IN_SCHEMA = """
            SELECT tablename || ' -> ' || indexname
              FROM pg_indexes
             WHERE schemaname = 'public'
               AND tablename NOT LIKE 'batch\\_%'
               AND tablename <> 'flyway_schema_history'
               AND indexname NOT LIKE 'pk\\_%'
             ORDER BY 1
            """;

    /** Reads a table's check constraints with their definitions, so a hidden rule cannot go unseen. */
    private static final String CHECK_CONSTRAINTS_ON_TABLE = """
            SELECT constraint_row.conname || ' | ' || pg_get_constraintdef(constraint_row.oid)
              FROM pg_constraint AS constraint_row
              JOIN pg_class AS table_row ON table_row.oid = constraint_row.conrelid
              JOIN pg_namespace AS schema_row ON schema_row.oid = table_row.relnamespace
             WHERE schema_row.nspname = 'public'
               AND table_row.relname = ?
               AND constraint_row.contype = 'c'
             ORDER BY 1
            """;

    /** Reads the foreign keys that point at one table, with the table each points from. */
    private static final String FOREIGN_KEYS_REFERENCING_TABLE = """
            SELECT constraint_row.conname || ' | ' || referencing.relname
              FROM pg_constraint AS constraint_row
              JOIN pg_class AS referenced ON referenced.oid = constraint_row.confrelid
              JOIN pg_class AS referencing ON referencing.oid = constraint_row.conrelid
              JOIN pg_namespace AS schema_row ON schema_row.oid = referenced.relnamespace
             WHERE schema_row.nspname = 'public'
               AND referenced.relname = ?
               AND constraint_row.contype = 'f'
             ORDER BY 1
            """;

    /** Reads the foreign keys declared by one table. */
    private static final String FOREIGN_KEYS_DECLARED_BY_TABLE = """
            SELECT constraint_row.conname
              FROM pg_constraint AS constraint_row
              JOIN pg_class AS declaring ON declaring.oid = constraint_row.conrelid
              JOIN pg_namespace AS schema_row ON schema_row.oid = declaring.relnamespace
             WHERE schema_row.nspname = 'public'
               AND declaring.relname = ?
               AND constraint_row.contype = 'f'
             ORDER BY 1
            """;

    /** Counts the cross-reference rows whose customer identifier names no existing customer. */
    private static final String CROSS_REFERENCE_ROWS_WITHOUT_A_CUSTOMER = """
            SELECT count(*)
              FROM card_cross_reference AS cross_reference
             WHERE NOT EXISTS (SELECT 1
                                 FROM customer AS existing
                                WHERE existing.cust_id = cross_reference.xref_cust_id)
            """;

    /** Counts the cross-reference rows, so a borrowed row can be proved to have come back. */
    private static final String CROSS_REFERENCE_ROW_COUNT =
            "SELECT count(*) FROM card_cross_reference";

    /** Picks one delivered cross-reference row to borrow, deterministically. */
    private static final String LOWEST_CROSS_REFERENCE_CARD_NUMBER =
            "SELECT min(xref_card_num) FROM card_cross_reference";

    /** Reads the customer a cross-reference row names. */
    private static final String CROSS_REFERENCE_CUSTOMER =
            "SELECT xref_cust_id FROM card_cross_reference WHERE xref_card_num = ?";

    /** Reads the account a cross-reference row names. */
    private static final String CROSS_REFERENCE_ACCOUNT =
            "SELECT xref_acct_id FROM card_cross_reference WHERE xref_card_num = ?";

    /** Removes one cross-reference row by its business key. */
    private static final String DELETE_CROSS_REFERENCE =
            "DELETE FROM card_cross_reference WHERE xref_card_num = ?";

    /** Writes one cross-reference row, all three fields supplied by the caller. */
    private static final String INSERT_CROSS_REFERENCE = """
            INSERT INTO card_cross_reference (xref_card_num, xref_cust_id, xref_acct_id)
            VALUES (?, ?, ?)
            """;

    /**
     * Counts the delivered customers whose national identifier is present.
     *
     * <p>Bounded to the delivered key range rather than taken over the whole table, so the reading is
     * about the seed itself and cannot be moved by a row any other class has in flight. The bounds are
     * parameters.
     */
    private static final String DELIVERED_CUSTOMERS_WITH_A_NATIONAL_IDENTIFIER = """
            SELECT count(cust_ssn)
              FROM customer
             WHERE cust_id BETWEEN ? AND ?
            """;

    /** Counts the delivered customer rows, independently of the repository's own count. */
    private static final String DELIVERED_CUSTOMER_ROW_COUNT = """
            SELECT count(*)
              FROM customer
             WHERE cust_id BETWEEN ? AND ?
            """;

    /**
     * Counts the delivered customers scoring below the update screen's lower bound.
     *
     * <p>The key bounds and the score bound are all parameters, and the column is cast for the
     * comparison alone. The stored form stays three characters of text, exactly as the legacy field
     * carries it, and nothing here converts it to a number on the way in or on the way out.
     */
    private static final String DELIVERED_CUSTOMERS_BELOW_SCREEN_MINIMUM = """
            SELECT count(*)
              FROM customer
             WHERE cust_id BETWEEN ? AND ?
               AND CAST(fico_credit_score AS integer) < ?
            """;

    /** Removes one customer row by its business key. */
    private static final String DELETE_CUSTOMER = "DELETE FROM customer WHERE cust_id = ?";

    /** Reads one column of one customer row as text, for a check the entity cannot answer. */
    private static final String CUSTOMER_NATIONAL_IDENTIFIER =
            "SELECT cust_ssn FROM customer WHERE cust_id = ?";

    /** Rewrites one customer's given name over a connection of its own. */
    private static final String UPDATE_CUSTOMER_FIRST_NAME =
            "UPDATE customer SET first_name = ? WHERE cust_id = ?";

    /** Maps one catalogue row onto the shape the column assertions compare against. */
    private static final RowMapper<ColumnDeclaration> COLUMN_DECLARATION_MAPPER =
            (row, rowNumber) -> new ColumnDeclaration(
                    row.getString("column_name"),
                    row.getString("data_type"),
                    row.getInt("character_maximum_length"),
                    "YES".equals(row.getString("is_nullable")),
                    row.getInt("ordinal_position"));

    /**
     * One column as the live catalogue declares it.
     *
     * @param name     the column name
     * @param dataType the declared type, as the catalogue spells it
     * @param width    the declared maximum length in characters
     * @param nullable whether the column admits a null
     * @param position the column's one-based position in the table
     */
    private record ColumnDeclaration(String name, String dataType, int width, boolean nullable,
            int position) {
    }

    // =============================================================================================
    // CONTEXT, COLLABORATORS AND HYGIENE
    // =============================================================================================

    /**
     * The narrowest context in which the customer repository is the real thing.
     *
     * <p>Four auto-configurations, one entity package and one repository package - and nothing above the
     * domain layer. The production repository package depends on the domain package alone, so a test of
     * that package depends on the same and no more: no service, controller, batch or messaging bean is
     * loaded, and none can be reached from here. Auto-configuration is imported by name rather than
     * enabled wholesale for exactly that reason.
     *
     * <p>The database this context talks to is the shared migrated server the base class started. Its
     * address is published into this environment by the base rather than restated here, and no address
     * appears in any profile document, so a context that failed to inherit it would fail to refresh and
     * name the missing property instead of quietly reaching somewhere else. The schema is validated, not
     * generated: every entity in the domain package is checked against the migrated tables before the
     * first assertion runs, which is what proves the eighteen mappings from the entity side without any
     * reflection.
     */
    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration({DataSourceAutoConfiguration.class, JdbcTemplateAutoConfiguration.class,
            HibernateJpaAutoConfiguration.class, TransactionAutoConfiguration.class})
    @EnableJpaRepositories(basePackageClasses = CustomerRepository.class)
    @EntityScan(basePackageClasses = Customer.class)
    static class RepositoryUnderTest {

        /** Creates the configuration. */
        RepositoryUnderTest() {
            // Intentionally empty: this configuration contributes annotations, not beans.
        }
    }

    /** The repository under test, as the application would obtain it. */
    @Autowired
    private CustomerRepository repository;

    /**
     * Reads the live catalogue and the two tables directly, for the facts an entity cannot report.
     *
     * <p>Column widths, nullability, indexes, check constraints and foreign keys are properties of the
     * schema rather than of the mapping, and the only honest way to assert them is to ask the server.
     * Every statement it runs here is a complete literal taking bound parameters.
     */
    @Autowired
    private JdbcTemplate database;

    /** Creates the test class. */
    CustomerRepositoryIT() {
        // Intentionally empty: every collaborator is injected and every fixture is built per test.
    }

    /**
     * Removes every key this class reserves, whether the test that ran created it or not.
     *
     * <p>Unconditional and explicit. Unconditional because a test that fails part-way through has still
     * written its row, and the next class must not inherit it; explicit because the reserved range
     * includes a leading-zero key whose only prefix in common with the delivered seed is the padding
     * itself, so a prefix match wide enough to catch it would catch the fifty delivered rows too.
     *
     * <p>No delivered row is ever removed. The fifty cross-reference rows point at the fifty delivered
     * customers, so removing one would be refused by the foreign key - correctly, and after the damage
     * to the suite had already been done.
     *
     * @throws SQLException if a reserved row cannot be removed
     */
    @AfterEach
    void removeReservedRows() throws SQLException {
        try (Connection connection = connect();
                PreparedStatement delete = connection.prepareStatement(DELETE_CUSTOMER)) {
            for (final String reserved : RESERVED_IDS) {
                delete.setString(1, reserved);
                delete.executeUpdate();
            }
        }
    }

    // =============================================================================================
    // THE RECORD GEOMETRY AND THE COLUMNS THAT CARRY IT
    // =============================================================================================

    /** Groups the assertions that tie the copybook geometry to the migrated columns. */
    @Nested
    @DisplayName("the eighteen mapped fields")
    final class TheEighteenMappedFields {

        /** Creates the nested group. */
        TheEighteenMappedFields() {
            // Intentionally empty: the group holds no state of its own.
        }

        @Test
        @DisplayName("account for all five hundred bytes: 332 of data, then 168 of unpersisted filler")
        void accountForEveryByteOfTheRecord() {
            final TestDataFactory.RecordLayout layout = TestDataFactory.CUSTOMER;

            assertThat(MAPPINGS)
                    .as("the record carries eighteen persisted fields; the shared layout and this "
                            + "table must agree on how many, because a nineteenth would need a column")
                    .hasSameSizeAs(layout.fields());
            assertThat(layout.recordLength())
                    .as("the provisioning job declares a five-hundred byte record")
                    .isEqualTo(500);
            assertThat(layout.dataLength())
                    .as("the eighteen fields occupy 332 contiguous bytes from offset zero")
                    .isEqualTo(332);
            assertThat(layout.hasFiller())
                    .as("the record is longer than its fields, so it carries trailing filler")
                    .isTrue();
            assertThat(layout.fillerLength())
                    .as("168 bytes of filler follow the data and are deliberately NOT persisted: "
                            + "332 plus 168 is the whole record, and no column exists for the filler")
                    .isEqualTo(168);
            assertThat(layout.dataLength() + layout.fillerLength())
                    .as("nothing in the record is unaccounted for")
                    .isEqualTo(layout.recordLength());
            assertThat(declaredColumns())
                    .as("the filler contributes no column, so the table has exactly as many columns "
                            + "as the record has persisted fields")
                    .hasSameSizeAs(MAPPINGS);
        }

        @Test
        @DisplayName("each occupy the offset and width the copybook declares, contiguously from zero")
        void occupyTheDeclaredByteRanges() {
            int cursor = 0;
            for (final Mapping mapping : MAPPINGS) {
                final TestDataFactory.FieldSpec field = TestDataFactory.CUSTOMER
                        .field(mapping.legacyName());

                assertThat(field.offset())
                        .as("field %s begins at zero-based offset %d", mapping.legacyName(),
                                mapping.legacyOffset())
                        .isEqualTo(mapping.legacyOffset())
                        .as("field %s follows the preceding field with no gap and no overlap",
                                mapping.legacyName())
                        .isEqualTo(cursor);
                assertThat(field.width())
                        .as("field %s is %d bytes wide in the record image", mapping.legacyName(),
                                mapping.legacyWidth())
                        .isEqualTo(mapping.legacyWidth());
                cursor = field.endOffset();
            }
            assertThat(cursor)
                    .as("walking the eighteen fields in record order lands exactly on the end of the "
                            + "data, which is where the unpersisted filler begins")
                    .isEqualTo(332);
        }

        @Test
        @DisplayName("appear as columns in record order, under the names the migration gives them")
        void appearAsColumnsInRecordOrder() {
            final List<ColumnDeclaration> columns = declaredColumns();

            assertThat(columns).extracting(ColumnDeclaration::name)
                    .as("the migrated table declares its columns in record order, so the position of "
                            + "a column and the offset of its field tell the same story")
                    .containsExactlyElementsOf(MAPPINGS.stream().map(Mapping::columnName).toList());
            for (int index = 0; index < MAPPINGS.size(); index++) {
                assertThat(columns.get(index).position())
                        .as("column %s sits at position %d", MAPPINGS.get(index).columnName(),
                                index + 1)
                        .isEqualTo(index + 1);
            }
        }

        @Test
        @DisplayName("are each bounded text at the legacy width, the two protected ones excepted")
        void areBoundedTextAtTheLegacyWidth() {
            for (final Mapping mapping : MAPPINGS) {
                final ColumnDeclaration column = declaredColumn(mapping.columnName());

                assertThat(column.dataType())
                        .as("column %s is bounded variable-length text, never a fixed-length type: a "
                                + "fixed-length column pads on write and ignores trailing pad on "
                                + "comparison, and either would break byte-level parity",
                                mapping.columnName())
                        .isEqualTo(BOUNDED_TEXT_TYPE);
                assertThat(column.width())
                        .as("column %s is declared %d characters wide", mapping.columnName(),
                                mapping.columnWidth())
                        .isEqualTo(mapping.columnWidth());
            }
        }

        @Test
        @DisplayName("carry the legacy width in their column except where ciphertext widens it")
        void widenOnlyTheTwoProtectedColumns() {
            final List<String> widened = MAPPINGS.stream()
                    .filter(mapping -> mapping.columnWidth() != mapping.legacyWidth())
                    .map(Mapping::columnName)
                    .toList();

            assertThat(widened)
                    .as("exactly two columns are wider than the field they carry, and both are wider "
                            + "for the same reason: they hold application-produced ciphertext rather "
                            + "than the cleartext the legacy record stored. Sixteen columns are the "
                            + "width of their field, and a widened third would mean an unprotected "
                            + "value had been given room to grow")
                    .containsExactly(NATIONAL_IDENTIFIER_COLUMN, GOVERNMENT_IDENTIFIER_COLUMN);
            assertThat(widened).allSatisfy(columnName ->
                    assertThat(declaredColumn(columnName).width())
                            .as("column %s is widened to the module's protected-value width",
                                    columnName)
                            .isEqualTo(PROTECTED_COLUMN_WIDTH));
        }

        @Test
        @DisplayName("keep the legacy prefix on three columns only, and that asymmetry is deliberate")
        void keepTheLegacyPrefixOnThreeColumnsOnly() {
            final List<String> columnNames = declaredColumns().stream()
                    .map(ColumnDeclaration::name)
                    .toList();

            assertThat(columnNames).filteredOn(name -> name.startsWith(KEPT_PREFIX))
                    .as("every legacy field name in this record carries the entity prefix, yet only "
                            + "three columns keep it. The names are transcribed from the migration "
                            + "that owns the table and the provider validates against that migration, "
                            + "so renaming a column to look consistent aborts start-up")
                    .containsExactlyElementsOf(PREFIXED_COLUMNS);
            assertThat(columnNames).filteredOn(name -> !name.startsWith(KEPT_PREFIX))
                    .as("the remaining fifteen drop the prefix")
                    .hasSize(MAPPINGS.size() - PREFIXED_COLUMNS.size());
        }

        @Test
        @DisplayName("separate a trailing digit from the rest of the name with an underscore")
        void separateATrailingDigitWithAnUnderscore() {
            final List<String> columnNames = declaredColumns().stream()
                    .map(ColumnDeclaration::name)
                    .toList();

            assertThat(columnNames)
                    .as("the five multi-part names are the three address lines and the two telephone "
                            + "numbers, each with its trailing digit separated")
                    .containsAll(DIGIT_SUFFIXED_COLUMNS);
            assertThat(columnNames).allSatisfy(name ->
                    assertThat(name)
                            .as("column %s must not attach a digit directly to a letter: the "
                                    + "separator is part of the name the migration declares, and the "
                                    + "provider resolves the column by that exact spelling", name)
                            .doesNotMatch(".*[A-Za-z][0-9].*"));
        }

        @Test
        @DisplayName("are keyed on the business key itself, with nothing generated by the server")
        void areKeyedOnTheBusinessKeyItself() {
            assertThat(database.queryForList(PRIMARY_KEY_COLUMNS, String.class, TABLE))
                    .as("the key is the nine-character identifier the record image carries at offset "
                            + "zero, exactly as the provisioning job declares it, and it is the whole "
                            + "of the key")
                    .containsExactly(KEY_COLUMN);
            assertThat(MAPPINGS.get(0).legacyOffset())
                    .as("that field begins the record")
                    .isZero();
            assertThat(database.queryForObject(KEY_COLUMN_GENERATION, String.class, TABLE, KEY_COLUMN))
                    .as("no sequence, identity or default stands behind the key. A surrogate key "
                            + "would break the correspondence between the record image and the table "
                            + "row that byte-level parity depends on")
                    .isEqualTo("NO/no-default");
        }
    }

    // =============================================================================================
    // THE ONE NULLABLE COLUMN IN THE SCHEMA
    // =============================================================================================

    /** Groups the assertions about the national identifier, the schema's only nullable column. */
    @Nested
    @DisplayName("the national identifier")
    final class TheNationalIdentifier {

        /** Creates the nested group. */
        TheNationalIdentifier() {
            // Intentionally empty: the group holds no state of its own.
        }

        @Test
        @DisplayName("is the only nullable column in the whole eleven-table schema")
        void isTheOnlyNullableColumnInTheSchema() throws SQLException {
            assertThat(applicationTableNames())
                    .as("the schema migration creates eleven application tables, so the reading below "
                            + "covers the whole of it")
                    .hasSize(APPLICATION_TABLE_COUNT);
            assertThat(database.queryForList(NULLABLE_APPLICATION_COLUMNS, String.class))
                    .as("exactly one column in the whole schema admits a null, and this is it. It "
                            + "admits one because the legacy record permits a customer with no national "
                            + "identifier on file, and a column that refused absence would refuse a "
                            + "customer the legacy system stored. The delivered seed nonetheless "
                            + "fills all fifty rows: nullable is what the schema allows, not what the "
                            + "seed does. A second nullable column once existed - the refusal deadline "
                            + "of the withdrawn sign-on attempt ledger - and the assertion is stronger "
                            + "without it: a SECOND nullable column now fails this. DL-352")
                    .containsExactly(TABLE + "." + NATIONAL_IDENTIFIER_COLUMN);
            assertThat(declaredColumn(NATIONAL_IDENTIFIER_COLUMN).nullable())
                    .as("read from the other side, the same column reports itself nullable")
                    .isTrue();
        }

        @Test
        @DisplayName("is present, and sealed, on every one of the fifty delivered rows")
        void isSealedOnEveryDeliveredRow() {
            // THIS ASSERTED ABSENCE, on the ground that a static artefact cannot produce ciphertext
            // without committing key material. The seed-bearing profiles already commit the one
            // non-production fixture key as a bare literal, and the cleartext of these nine bytes is
            // already committed twice - at app/data/ASCII/custdata.txt and at
            // src/test/resources/fixtures/input/custdata.txt - so absence protected nothing while
            // costing the only proof that mattered: no delivered row exercised a stored value here.
            assertThat(database.queryForObject(DELIVERED_CUSTOMER_ROW_COUNT, Integer.class,
                    SEEDED_FIRST_ID, SEEDED_LAST_ID))
                    .as("the delivered key range holds the fifty reference customers")
                    .isEqualTo(TestDataFactory.SEEDED_FIFTY_ROW_COUNT);
            assertThat(database.queryForObject(DELIVERED_CUSTOMERS_WITH_A_NATIONAL_IDENTIFIER,
                    Integer.class, SEEDED_FIRST_ID, SEEDED_LAST_ID))
                    .as("every delivered row carries a value here, taken from the authoritative "
                            + "fixture record and sealed by the same service the application uses")
                    .isEqualTo(TestDataFactory.SEEDED_FIFTY_ROW_COUNT);
            assertThat(repository.findAllById(List.of(SEEDED_FIRST_ID, SEEDED_LAST_ID,
                    SEEDED_LOWEST_SCORE_ID)))
                    .as("and the repository reports the stored envelope through the entity, never a "
                            + "run of digits: the entity is the layer every reader goes through")
                    .isNotEmpty()
                    .allSatisfy(customer -> assertThat(customer.getCustSsn())
                            .startsWith(SensitiveFieldCodec.ENVELOPE_PREFIX)
                            .doesNotMatch("^[0-9]{1,9}$"));
        }

        @Test
        @DisplayName("round-trips absence as a genuine null, never an empty string or the text null")
        void roundTripsAbsenceAsAGenuineNull() {
            final Customer stored = repository.saveAndFlush(
                    reservedCustomer(ABSENT_IDENTIFIER_ID).nationalIdentifierProtected(null).build());

            assertThat(stored.getCustSsn())
                    .as("the write path accepts absence")
                    .isNull();
            assertThat(repository.findById(ABSENT_IDENTIFIER_ID))
                    .get()
                    .extracting(Customer::getCustSsn)
                    .as("a keyed read returns absence unchanged. An empty string would be a value "
                            + "that decrypts to nothing, and the four characters of the word would be "
                            + "a value that decrypts to a lie")
                    .isNull();
            assertThat(database.queryForObject(CUSTOMER_NATIONAL_IDENTIFIER, String.class,
                    ABSENT_IDENTIFIER_ID))
                    .as("and the column itself holds SQL null rather than any stand-in for it")
                    .isNull();
        }

        @Test
        @DisplayName("stores a ciphertext far wider than its nine-byte field and returns it unchanged")
        void storesCiphertextWiderThanTheLegacyField() {
            assertThat(WIDE_CIPHERTEXT)
                    .as("the fixture is a well-formed envelope, wider than the legacy field by a "
                            + "wide margin and just inside the column, which is what makes the "
                            + "declared width demonstrably real rather than nominal")
                    .startsWith(ENVELOPE_MARKER)
                    .hasSizeGreaterThan(MAPPINGS.get(12).legacyWidth())
                    .hasSizeLessThanOrEqualTo(PROTECTED_COLUMN_WIDTH)
                    .hasSize(253);

            repository.saveAndFlush(reservedCustomer(WIDE_CIPHERTEXT_ID)
                    .nationalIdentifierProtected(WIDE_CIPHERTEXT)
                    .build());

            assertThat(repository.findById(WIDE_CIPHERTEXT_ID))
                    .get()
                    .extracting(Customer::getCustSsn)
                    .as("a protected value comes back character for character. An authenticated "
                            + "ciphertext that lost or gained a single character would no longer open")
                    .isEqualTo(WIDE_CIPHERTEXT);
            assertThat(database.queryForObject(CUSTOMER_NATIONAL_IDENTIFIER, String.class,
                    WIDE_CIPHERTEXT_ID))
                    .as("and the column holds exactly what was handed to it, at its full length")
                    .isEqualTo(WIDE_CIPHERTEXT)
                    .hasSize(WIDE_CIPHERTEXT.length());
        }
    }

    // =============================================================================================
    // THE CREDIT SCORE, WHICH THE PERSISTENCE LAYER DOES NOT POLICE
    // =============================================================================================

    /** Groups the assertions that the screen's credit-score range is not enforced here. */
    @Nested
    @DisplayName("the credit score")
    final class TheCreditScore {

        /** Creates the nested group. */
        TheCreditScore() {
            // Intentionally empty: the group holds no state of its own.
        }

        @Test
        @DisplayName("is guarded by no range constraint in the migrated schema")
        void isGuardedByNoRangeConstraint() {
            final List<String> checks =
                    database.queryForList(CHECK_CONSTRAINTS_ON_TABLE, String.class, TABLE);

            // FOUR check constraints, and not one of them is a content edit on the score. One is the
            // business-key digit class. One is the single-byte-text rule, which is a statement about the
            // ENCODING of every text column - octet_length equal to char_length - and says nothing whatever
            // about what a value may contain. The other two are the protected-value invariants V2_2 adds
            // over the two regulated identifiers, which are content rules but only over columns declared to
            // hold ciphertext. The distinction is the one this test exists to hold: the 300-to-850 range is
            // screen-level edit validation belonging to the account-update path, and enforcing THAT here
            // would refuse rows the legacy system stored.
            assertThat(checks)
                    .as("exactly four check constraints: the business-key digit class, the encoding rule "
                            + "and the two protected-value invariants")
                    .hasSize(4);
            assertThat(checks)
                    .as("the business-key digit class is one of them")
                    .anySatisfy(definition -> assertThat(definition).startsWith(KEY_DIGIT_CONSTRAINT));
            assertThat(checks)
                    .as("the encoding rule is another")
                    .anySatisfy(definition ->
                            assertThat(definition).startsWith(BYTE_REPERTOIRE_CONSTRAINT));
            assertThat(checks)
                    .as("and the two protected-value invariants are the rest, one per regulated "
                            + "identifier. DL-349")
                    .anySatisfy(definition -> assertThat(definition).startsWith(SSN_PROTECTED_CONSTRAINT))
                    .anySatisfy(definition -> assertThat(definition).startsWith(GOVT_PROTECTED_CONSTRAINT));
            assertThat(checks).allSatisfy(definition -> {
                assertThat(definition)
                        .as("no constraint on this table constrains the VALUE of the credit score: it may "
                                + "appear only inside the encoding rule, which every text column appears "
                                + "inside")
                        .satisfiesAnyOf(
                                text -> assertThat(text).doesNotContain(CREDIT_SCORE_COLUMN),
                                text -> assertThat(text).startsWith(BYTE_REPERTOIRE_CONSTRAINT));
                assertThat(definition)
                        .as("and no constraint that so much as MENTIONS the credit score expresses a "
                                + "range comparison. Scoped to the column rather than to the whole table, "
                                + "because a protected-value invariant does carry a length floor - it "
                                + "bounds the width of a ciphertext envelope on a different column and "
                                + "says nothing about a score. An unscoped ban would have had to be "
                                + "deleted here, which would have lost the claim entirely")
                        .satisfiesAnyOf(
                                text -> assertThat(text).doesNotContain(CREDIT_SCORE_COLUMN),
                                text -> assertThat(text).doesNotContain(">=", "<=", "BETWEEN"));
            });
            assertThat(declaredColumn(CREDIT_SCORE_COLUMN).dataType())
                    .as("the score is three characters of text, not a number. It is stored exactly "
                            + "as the legacy field carries it, leading zeros and all, so no arithmetic "
                            + "and no numeric type is introduced by this table")
                    .isEqualTo(BOUNDED_TEXT_TYPE);
        }

        @Test
        @DisplayName("loads without complaint on the twenty-one delivered rows below the screen bound")
        void loadsOnTheDeliveredRowsBelowTheScreenBound() {
            assertThat(database.queryForObject(DELIVERED_CUSTOMERS_BELOW_SCREEN_MINIMUM, Integer.class,
                    SEEDED_FIRST_ID, SEEDED_LAST_ID, TestDataFactory.CREDIT_SCORE_MINIMUM))
                    .as("twenty-one of the fifty delivered customers score below the bound the update "
                            + "screen enforces. That measured fact is why no constraint may be added: "
                            + "one would fail the seed load itself")
                    .isEqualTo(SEEDED_SCORES_BELOW_SCREEN_MINIMUM);

            assertThat(repository.findById(SEEDED_LOWEST_SCORE_ID))
                    .as("the lowest-scoring delivered row loads through the repository")
                    .get()
                    .extracting(Customer::getFicoCreditScore)
                    .as("and returns its three characters exactly, leading zeros preserved")
                    .isEqualTo(SEEDED_LOWEST_SCORE);
            assertThat(repository.findById(SEEDED_FIRST_ID))
                    .get()
                    .extracting(Customer::getFicoCreditScore)
                    .as("so does the first delivered row, which also scores below the bound and is "
                            + "why the shared fixture deliberately defaults to a different value")
                    .isEqualTo(SEEDED_FIRST_SCORE);
            assertThat(Integer.parseInt(SEEDED_LOWEST_SCORE))
                    .as("the lowest delivered score really is below the screen's lower bound")
                    .isLessThan(TestDataFactory.CREDIT_SCORE_MINIMUM);
        }

        @Test
        @DisplayName("persists and reloads at values outside the screen's range, at either end")
        void persistsOutsideTheScreenRange() {
            repository.saveAndFlush(
                    reservedCustomer(SCORE_ABOVE_RANGE_ID).creditScore(SCORE_ABOVE_RANGE).build());
            repository.saveAndFlush(
                    reservedCustomer(SCORE_BELOW_RANGE_ID).creditScore(SCORE_BELOW_RANGE).build());

            assertThat(Integer.parseInt(SCORE_ABOVE_RANGE))
                    .as("the first fixture really is above the screen's upper bound")
                    .isGreaterThan(TestDataFactory.CREDIT_SCORE_MAXIMUM);
            assertThat(Integer.parseInt(SCORE_BELOW_RANGE))
                    .as("the second really is below its lower bound")
                    .isLessThan(TestDataFactory.CREDIT_SCORE_MINIMUM);
            assertThat(repository.findById(SCORE_ABOVE_RANGE_ID))
                    .get()
                    .extracting(Customer::getFicoCreditScore)
                    .as("a value above the range is stored and returned unchanged")
                    .isEqualTo(SCORE_ABOVE_RANGE);
            assertThat(repository.findById(SCORE_BELOW_RANGE_ID))
                    .get()
                    .extracting(Customer::getFicoCreditScore)
                    .as("so is a value below it, three characters wide with its leading zeros intact")
                    .isEqualTo(SCORE_BELOW_RANGE);
        }
    }

    // =============================================================================================
    // THE DELIVERED POPULATION, THE KEY, AND THE ONE FOREIGN KEY THAT TOUCHES THIS TABLE
    // =============================================================================================

    /** Groups the assertions about the delivered seed, keyed reads and referential integrity. */
    @Nested
    @DisplayName("the delivered population")
    final class TheDeliveredPopulation {

        /** Creates the nested group. */
        TheDeliveredPopulation() {
            // Intentionally empty: the group holds no state of its own.
        }

        @Test
        @DisplayName("is exactly fifty rows, one per record of the reference fixture")
        void isExactlyFiftyRows() {
            assertThat(repository.count())
                    .as("the reference fixture is fifty five-hundred byte records, and the seed "
                            + "migration loads one row for each. The seed belongs to the "
                            + "non-production profiles alone; a production migration stops below it")
                    .isEqualTo(TestDataFactory.SEEDED_FIFTY_ROW_COUNT);
            assertThat(database.queryForObject(DELIVERED_CUSTOMER_ROW_COUNT, Integer.class,
                    SEEDED_FIRST_ID, SEEDED_LAST_ID))
                    .as("and all fifty of them lie inside the delivered key range, so no row outside "
                            + "it is being counted in")
                    .isEqualTo(TestDataFactory.SEEDED_FIFTY_ROW_COUNT);
        }

        @Test
        @DisplayName("resolves a delivered nine-character key and answers an absent one with nothing")
        void resolvesADeliveredKeyAndAnswersAnAbsentOneWithNothing() {
            assertThat(repository.findById(SEEDED_FIRST_ID))
                    .as("the first delivered key resolves")
                    .isPresent()
                    .get()
                    .extracting(Customer::getCustId)
                    .as("and the identifier comes back with the padding the record image published")
                    .isEqualTo(SEEDED_FIRST_ID);
            assertThat(repository.findById(SEEDED_LAST_ID))
                    .as("so does the last")
                    .isPresent();
            assertThat(repository.findById(TestDataFactory.UNKNOWN_CUSTOMER_ID))
                    .as("a key that names no row is answered with an empty result rather than an "
                            + "exception: absence is an outcome the caller decides about, not a "
                            + "failure the repository decides for it")
                    .isEmpty();
        }

        @Test
        @DisplayName("keeps a leading-zero key intact and does not answer to its unpadded form")
        void keepsALeadingZeroKeyIntact() {
            repository.saveAndFlush(reservedCustomer(LEADING_ZERO_ID).build());

            assertThat(repository.findById(LEADING_ZERO_ID))
                    .as("the padded key resolves")
                    .get()
                    .extracting(Customer::getCustId)
                    .as("and returns every one of its nine characters, leading zeros included")
                    .isEqualTo(LEADING_ZERO_ID);
            assertThat(LEADING_ZERO_ID)
                    .as("the fixture really is the padded form of the probe below")
                    .hasSize(MAPPINGS.get(0).legacyWidth())
                    .endsWith(UNPADDED_ID);
            assertThat(repository.findById(UNPADDED_ID))
                    .as("the same digits without their padding are a different key and resolve to "
                            + "nothing. Two values claiming the same nine bytes of one record are two "
                            + "rows, which is why the schema pins the key to nine digits")
                    .isEmpty();
        }

        @Test
        @DisplayName("carries no secondary index: the only index on this table backs its primary key")
        void carriesNoSecondaryIndex() {
            assertThat(database.queryForList(INDEXES_ON_TABLE, String.class, TABLE))
                    .as("one index, and it is the one the primary key brings with it. Nothing reads "
                            + "this table by anything but its key - a card resolves to a customer "
                            + "through the cross-reference, and the keyed read needs no other index")
                    .containsExactly(PRIMARY_KEY_INDEX);
            assertThat(database.queryForList(SECONDARY_INDEXES_IN_SCHEMA, String.class))
                    .as("three secondary indexes exist in the whole schema and not one of them is on "
                            + "this table. Each stands in for one of the three legacy alternate indexes, "
                            + "and nothing else in the delivered schema carries a secondary index: a "
                            + "fourth once served the withdrawn sign-on attempt ledger's sweep. DL-352")
                    .containsExactlyElementsOf(SECONDARY_INDEXES)
                    .noneMatch(entry -> entry.startsWith(TABLE + " ->"));
        }

        @Test
        @DisplayName("is pointed at by one foreign key and declares none of its own")
        void isPointedAtByOneForeignKeyAndDeclaresNone() {
            assertThat(database.queryForList(FOREIGN_KEYS_REFERENCING_TABLE, String.class, TABLE))
                    .as("exactly one foreign key in the schema points at this table, and it comes "
                            + "from the cross-reference")
                    .containsExactly(CUSTOMER_FOREIGN_KEY + " | " + REFERENCING_TABLE);
            assertThat(database.queryForList(FOREIGN_KEYS_DECLARED_BY_TABLE, String.class, TABLE))
                    .as("and none originates here. That direction is why the reference migration "
                            + "seeds this table first, before the account, the card and the "
                            + "cross-reference: nothing has to exist before a customer row can")
                    .isEmpty();
            assertThat(database.queryForObject(CROSS_REFERENCE_ROWS_WITHOUT_A_CUSTOMER, Integer.class))
                    .as("every delivered cross-reference row names a customer that exists, so the "
                            + "constraint is satisfied by the seed rather than merely declared over it")
                    .isZero();
        }

        @Test
        @DisplayName("refuses a cross-reference row naming a customer that does not exist")
        void refusesACrossReferenceRowNamingAnAbsentCustomer() throws SQLException {
            final String cardNumber = database.queryForObject(LOWEST_CROSS_REFERENCE_CARD_NUMBER,
                    String.class);
            final String accountId = database.queryForObject(CROSS_REFERENCE_ACCOUNT, String.class,
                    cardNumber);
            final String customerId = database.queryForObject(CROSS_REFERENCE_CUSTOMER, String.class,
                    cardNumber);
            final Integer rowsBefore =
                    database.queryForObject(CROSS_REFERENCE_ROW_COUNT, Integer.class);

            // Every delivered card already has a cross-reference row, so a plain insert would be
            // refused by the primary key before the foreign key was ever consulted. Borrowing one
            // row inside a transaction - removing it, then writing it back with a customer that does
            // not exist - leaves the customer constraint as the only one that can fail, which is what
            // makes the assertion below about that constraint and not about some other. The
            // transaction is rolled back, so the server itself puts the borrowed row back.
            try (Connection connection = connect()) {
                connection.setAutoCommit(false);
                try {
                    try (PreparedStatement delete =
                            connection.prepareStatement(DELETE_CROSS_REFERENCE)) {
                        delete.setString(1, cardNumber);
                        assertThat(delete.executeUpdate())
                                .as("the row being borrowed is there to borrow")
                                .isOne();
                    }
                    try (PreparedStatement insert =
                            connection.prepareStatement(INSERT_CROSS_REFERENCE)) {
                        insert.setString(1, cardNumber);
                        insert.setString(2, TestDataFactory.UNKNOWN_CUSTOMER_ID);
                        insert.setString(3, accountId);
                        assertThatExceptionOfType(SQLException.class)
                                .as("the write is refused, and the refusal names the constraint that "
                                        + "refused it rather than failing for some other reason")
                                .isThrownBy(insert::executeUpdate)
                                .withMessageContaining(CUSTOMER_FOREIGN_KEY);
                    }
                } finally {
                    connection.rollback();
                }
            }

            assertThat(database.queryForObject(CROSS_REFERENCE_CUSTOMER, String.class, cardNumber))
                    .as("the borrowed row is back, naming the customer it always named")
                    .isEqualTo(customerId);
            assertThat(database.queryForObject(CROSS_REFERENCE_ROW_COUNT, Integer.class))
                    .as("and the cross-reference is the size it was before the probe")
                    .isEqualTo(rowsBefore);
        }
    }

    // =============================================================================================
    // STORED TEXT IS RETURNED BYTE FOR BYTE
    // =============================================================================================

    /** Groups the assertions that nothing between the entity and the column reshapes a value. */
    @Nested
    @DisplayName("stored text")
    final class StoredText {

        /** Creates the nested group. */
        StoredText() {
            // Intentionally empty: the group holds no state of its own.
        }

        @Test
        @DisplayName("survives a round trip in all eighteen properties, trailing pad included")
        void survivesARoundTripInAllEighteenProperties() {
            // The given name is handed over at the full width of its field, space-padded exactly as
            // the record image carries it, while the postal code is handed over at its own length
            // exactly as the reference seed inserts it. Both forms must come back untouched: the
            // callers on either side of this boundary decide padding, and this boundary decides
            // nothing. A fixed-length column would have destroyed the distinction by padding the
            // second and ignoring the pad on the first.
            final String paddedGivenName = TestDataFactory.alphanumeric("Aniya",
                    MAPPINGS.get(1).legacyWidth(), MAPPINGS.get(1).legacyName());
            final Customer written = reservedCustomer(ROUND_TRIP_ID)
                    .firstName(paddedGivenName)
                    .nationalIdentifierProtected(TestDataFactory.SYNTHETIC_PROTECTED_VALUE)
                    .build();
            final List<String> handedOver = persistedProperties(written);

            repository.saveAndFlush(written);
            final Optional<Customer> reloaded = repository.findById(ROUND_TRIP_ID);

            assertThat(handedOver)
                    .as("all eighteen persisted properties take part, so none can drift unnoticed")
                    .hasSameSizeAs(MAPPINGS);
            assertThat(reloaded).isPresent();
            assertThat(persistedProperties(reloaded.orElseThrow()))
                    .as("every one of the eighteen comes back exactly as it was handed over. Nothing "
                            + "is trimmed on the way in, nothing is padded on the way out, and "
                            + "nothing is folded, normalized or reformatted in either direction")
                    .containsExactlyElementsOf(handedOver);
            assertThat(reloaded.orElseThrow().getFirstName())
                    .as("the padded given name in particular keeps its full field width and its "
                            + "trailing pad, which is only possible in a bounded variable-length "
                            + "column that stores what it is given")
                    .isEqualTo(paddedGivenName)
                    .hasSize(MAPPINGS.get(1).legacyWidth());
        }

        @Test
        @DisplayName("keeps the two-character state code and three-character country code exact")
        void keepsTheNarrowCodesExact() {
            final Customer reloaded = repository.findById(SEEDED_FIRST_ID).orElseThrow();

            assertThat(reloaded.getAddrStateCd())
                    .as("the state code is two characters in the record image, two in the column and "
                            + "two on the way back")
                    .hasSize(MAPPINGS.get(7).legacyWidth())
                    .isEqualTo(SEEDED_FIRST_DISPLAY_FIELDS.get(6));
            assertThat(reloaded.getAddrCountryCd())
                    .as("the country code is three, unchanged in the same way")
                    .hasSize(MAPPINGS.get(8).legacyWidth())
                    .isEqualTo(SEEDED_FIRST_DISPLAY_FIELDS.get(7));
            assertThat(declaredColumn(MAPPINGS.get(7).columnName()).width())
                    .as("neither column has room to hold anything wider")
                    .isEqualTo(MAPPINGS.get(7).legacyWidth());
            assertThat(declaredColumn(MAPPINGS.get(8).columnName()).width())
                    .isEqualTo(MAPPINGS.get(8).legacyWidth());
        }

        @Test
        @DisplayName("is exactly what the reference migration inserted, at the length it inserted it")
        void isExactlyWhatTheReferenceMigrationInserted() {
            final Customer delivered = repository.findById(SEEDED_FIRST_ID).orElseThrow();

            assertThat(displayProperties(delivered))
                    .as("the fourteen display fields of the first delivered row come back exactly as "
                            + "the reference migration inserted them - at their own lengths, not "
                            + "padded to their field widths, and with nothing added or removed")
                    .containsExactlyElementsOf(SEEDED_FIRST_DISPLAY_FIELDS);
            assertThat(delivered.getAddrZip())
                    .as("the postal code is stored at five characters although its field and its "
                            + "column are both ten, which is the whole reason the column is bounded "
                            + "variable-length text: placing this value back through the record "
                            + "mapper reproduces the identical ten bytes, so nothing is lost")
                    .hasSizeLessThan(MAPPINGS.get(9).legacyWidth());
            assertThat(delivered.getGovtIssuedId())
                    .as("the government-issued identifier is a sealed envelope rather than the twenty "
                            + "cleartext characters the legacy record carried, and it is wider than "
                            + "that field for exactly that reason")
                    .startsWith(ENVELOPE_MARKER)
                    .hasSizeGreaterThan(MAPPINGS.get(13).legacyWidth())
                    .hasSizeLessThanOrEqualTo(PROTECTED_COLUMN_WIDTH);
        }
    }

    // =============================================================================================
    // THE UNVERSIONED COMPARE-AND-SET
    //
    // The account-update transaction holds a complete before-image, rewrites the versioned account and
    // then rewrites the unversioned customer only while that row still matches. A repository save
    // cannot preserve the last condition, because it merges by identifier and would silently overwrite
    // a customer-only change committed after the token was verified. These tests go through the real
    // repository proxy, so they prove the query resolves against the migrated schema, that the
    // null-aware predicate over the one nullable column matches, that a row which moved is left alone,
    // and that the default method refuses a key change before any statement is issued.
    // =============================================================================================

    /** Groups the assertions about the complete before-image compare-and-set. */
    @Nested
    @DisplayName("the compare-and-set over a complete before-image")
    final class TheCompareAndSet {

        /** Creates the nested group. */
        TheCompareAndSet() {
            // Intentionally empty: the group holds no state of its own.
        }

        @Test
        @DisplayName("rewrites a matching row once, with the absent identifier part of the comparison")
        void rewritesAMatchingRowOnce() {
            final Customer before = repository.saveAndFlush(
                    reservedCustomer(COMPARE_AND_SET_ID).build());

            assertThat(before.getCustSsn())
                    .as("the fixture leaves the national identifier absent - which the delivered "
                            + "seed no longer does - so this run exercises the null-aware half of "
                            + "the comparison that the seeded rows no longer reach")
                    .isNull();
            assertThat(repository.compareAndSet(before,
                    copyOnto(before, COMPARE_AND_SET_ID, OPERATOR_FIRST_NAME)))
                    .as("the row still matches the held image, so exactly one row is rewritten")
                    .isOne();
            assertThat(repository.findById(COMPARE_AND_SET_ID))
                    .get()
                    .extracting(Customer::getFirstName)
                    .as("and the operator's value is what the row now holds")
                    .isEqualTo(OPERATOR_FIRST_NAME);
        }

        @Test
        @DisplayName("declines a row that moved after the read and leaves the competing image standing")
        void declinesARowThatMoved() throws SQLException {
            final Customer before = repository.saveAndFlush(
                    reservedCustomer(COMPARE_AND_SET_ID).build());
            commitCompetingFirstName(COMPARE_AND_SET_ID, CONCURRENT_FIRST_NAME);

            assertThat(repository.compareAndSet(before,
                    copyOnto(before, COMPARE_AND_SET_ID, OPERATOR_FIRST_NAME)))
                    .as("the row no longer matches the held image, so nothing is rewritten. This is "
                            + "the check a keyed merge cannot make, and the reason this operation "
                            + "exists on a table that carries no version column")
                    .isZero();
            assertThat(repository.findById(COMPARE_AND_SET_ID))
                    .get()
                    .extracting(Customer::getFirstName)
                    .as("the competing writer's committed value is untouched")
                    .isEqualTo(CONCURRENT_FIRST_NAME);
        }

        @Test
        @DisplayName("refuses a replacement carrying a different key, before any statement is issued")
        void refusesAReplacementCarryingADifferentKey() {
            final Customer delivered = repository.findById(SEEDED_LAST_ID).orElseThrow();
            final Customer before = copyOnto(delivered, COMPARE_AND_SET_ID,
                    delivered.getFirstName());
            final Customer after = copyOnto(delivered, DIFFERENT_KEY_ID, OPERATOR_FIRST_NAME);

            assertThatExceptionOfType(InvalidDataAccessApiUsageException.class)
                    .as("the legacy path rewrites the record addressed by the key it read and never "
                            + "moves a new key into it, so a replacement carrying a different key is "
                            + "refused rather than quietly applied to one row or the other")
                    .isThrownBy(() -> repository.compareAndSet(before, after))
                    .withRootCauseInstanceOf(IllegalArgumentException.class)
                    .withMessage(KEY_CHANGE_REFUSAL);
            assertThat(repository.findById(DIFFERENT_KEY_ID))
                    .as("and no row was created under the key the replacement named")
                    .isEmpty();
        }
    }

    // =============================================================================================
    // HELPERS
    // =============================================================================================

    /**
     * Builds the deterministic body of the widest envelope this class stores.
     *
     * <p>Deterministic rather than random, so the stored value is the same on every run and on every
     * host, and a failure names one value rather than a different one each time. It protects nothing,
     * because there is nothing here to protect.
     *
     * @return exactly {@link #WIDE_CIPHERTEXT_BODY_BYTES} bytes
     */
    private static byte[] syntheticBody() {
        final byte[] body = new byte[WIDE_CIPHERTEXT_BODY_BYTES];
        for (int index = 0; index < body.length; index++) {
            body[index] = (byte) (index + 1);
        }
        return body;
    }

    /**
     * Reads the rendered definition of this table's single-byte-text constraint from the catalogue.
     *
     * @return the rendered CHECK definition, never {@code null}
     */
    private String singleByteTextConstraintDefinition() {
        final List<String> definitions = database.queryForList("""
                SELECT pg_get_constraintdef(con.oid)
                  FROM pg_constraint con
                  JOIN pg_class rel ON rel.oid = con.conrelid
                  JOIN pg_namespace nsp ON nsp.oid = rel.relnamespace
                 WHERE nsp.nspname = 'public'
                   AND rel.relname = 'customer'
                   AND con.contype = 'c'
                   AND con.conname = 'ck_customer_single_byte_text'
                """, String.class);

        assertThat(definitions)
                .as("the customer table must declare its single-byte-text constraint; without it the "
                        + "declared character widths are not byte widths at all")
                .hasSize(1);
        return definitions.getFirst();
    }

    /**
     * Reads this table's columns as the live catalogue declares them, in declaration order.
     *
     * @return one entry per column
     */
    private List<ColumnDeclaration> declaredColumns() {
        return database.query(CUSTOMER_COLUMNS, COLUMN_DECLARATION_MAPPER, TABLE);
    }

    /**
     * Reads one column's declaration, failing the calling assertion if the column is absent.
     *
     * @param columnName the column to look up
     * @return that column's declaration
     */
    private ColumnDeclaration declaredColumn(final String columnName) {
        final Optional<ColumnDeclaration> found = declaredColumns().stream()
                .filter(column -> column.name().equals(columnName))
                .findFirst();
        assertThat(found)
                .as("the migrated table must carry a column named %s; the migration is what the "
                        + "provider validates the mapping against, so an absent column here is a "
                        + "mapping that cannot start up", columnName)
                .isPresent();
        return found.orElseThrow();
    }

    /**
     * Builds a customer fixture on a reserved key, with every field defaulted to the first delivered
     * record and the national identifier absent.
     *
     * <p>Absence here is deliberate and is no longer what the delivered seed stores: the seed seals a
     * value into all fifty rows, so the reserved-key fixtures are what keep the null-aware write, read
     * and compare-and-set paths exercised at all.
     *
     * @param custId the reserved key to build on
     * @return a fixture builder, ready to be adjusted and built
     */
    private static TestDataFactory.CustomerBuilder reservedCustomer(final String custId) {
        return TestDataFactory.customer().customerId(custId);
    }

    /**
     * Copies one image field for field onto another key, changing only the given name.
     *
     * <p>Used by the compare-and-set tests, which need two independent images of the same row: the one
     * the caller held before its changes and the one it intends to write.
     *
     * @param source    the image to copy
     * @param custId    the business key for the copy
     * @param firstName the given name for the copy
     * @return an independent customer image
     */
    private static Customer copyOnto(final Customer source, final String custId,
            final String firstName) {
        return new Customer(
                custId,
                firstName,
                source.getMiddleName(),
                source.getLastName(),
                source.getAddrLine1(),
                source.getAddrLine2(),
                source.getAddrLine3(),
                source.getAddrStateCd(),
                source.getAddrCountryCd(),
                source.getAddrZip(),
                source.getPhoneNum1(),
                source.getPhoneNum2(),
                source.getCustSsn(),
                source.getGovtIssuedId(),
                source.getCustDob(),
                source.getEftAccountId(),
                source.getPriCardHolderInd(),
                source.getFicoCreditScore());
    }

    /**
     * Returns every persisted property of one customer, in record order, as a comparable list.
     *
     * <p>Eighteen entries, read through the entity's own accessors, so a round trip can be compared in
     * one assertion rather than eighteen and a single drifted field names itself in the failure.
     *
     * @param customer the image to read
     * @return the eighteen values, in record order
     */
    private static List<String> persistedProperties(final Customer customer) {
        final List<String> values = new ArrayList<>();
        values.add(customer.getCustId());
        values.add(customer.getFirstName());
        values.add(customer.getMiddleName());
        values.add(customer.getLastName());
        values.add(customer.getAddrLine1());
        values.add(customer.getAddrLine2());
        values.add(customer.getAddrLine3());
        values.add(customer.getAddrStateCd());
        values.add(customer.getAddrCountryCd());
        values.add(customer.getAddrZip());
        values.add(customer.getPhoneNum1());
        values.add(customer.getPhoneNum2());
        values.add(customer.getCustSsn());
        values.add(customer.getGovtIssuedId());
        values.add(customer.getCustDob());
        values.add(customer.getEftAccountId());
        values.add(customer.getPriCardHolderInd());
        values.add(customer.getFicoCreditScore());
        return values;
    }

    /**
     * Returns the fourteen display properties of one customer, in record order.
     *
     * <p>The key, the two regulated identifiers and the credit score are left out: each is asserted on
     * its own terms elsewhere, and neither regulated value may appear in a comparison against a literal.
     *
     * @param customer the image to read
     * @return the fourteen display values, in record order
     */
    private static List<String> displayProperties(final Customer customer) {
        final List<String> values = new ArrayList<>();
        values.add(customer.getFirstName());
        values.add(customer.getMiddleName());
        values.add(customer.getLastName());
        values.add(customer.getAddrLine1());
        values.add(customer.getAddrLine2());
        values.add(customer.getAddrLine3());
        values.add(customer.getAddrStateCd());
        values.add(customer.getAddrCountryCd());
        values.add(customer.getAddrZip());
        values.add(customer.getPhoneNum1());
        values.add(customer.getPhoneNum2());
        values.add(customer.getCustDob());
        values.add(customer.getEftAccountId());
        values.add(customer.getPriCardHolderInd());
        return values;
    }

    /**
     * Rewrites one customer's given name over a connection of this class's own.
     *
     * <p>A separate connection is the point: the change has to be committed and visible to the
     * repository before the compare-and-set runs, which is what a competing writer looks like from
     * inside one turn of the account-update path.
     *
     * @param custId    the row to rewrite
     * @param firstName the competing value
     * @throws SQLException if the rewrite cannot be applied
     */
    private static void commitCompetingFirstName(final String custId, final String firstName)
            throws SQLException {
        try (Connection connection = connect();
                PreparedStatement update = connection.prepareStatement(UPDATE_CUSTOMER_FIRST_NAME)) {
            update.setString(1, firstName);
            update.setString(2, custId);
            assertThat(update.executeUpdate())
                    .as("the competing writer must find the row it is racing for")
                    .isOne();
        }
    }
}
