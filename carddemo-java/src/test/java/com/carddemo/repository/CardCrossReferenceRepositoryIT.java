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
import static org.assertj.core.api.Assertions.assertThatNoException;

import com.carddemo.domain.CardCrossReference;
import com.carddemo.support.AbstractPostgresIT;
import com.carddemo.support.TestDataFactory;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * Verifies, against a real PostgreSQL 16 server carrying the shipped migrations and the delivered
 * reference seed, the whole persistence contract of the card-to-customer-to-account cross reference:
 * its 36-of-50 byte layout, its business key, the non-unique account access path that replaces the
 * legacy alternate index, and the three foreign keys that originate from it.
 *
 * <h2>Why this table is worth a class of its own</h2>
 *
 * <p>Its record layout has the third-highest inclusion count in the estate - ten programs take it,
 * which is more than any layout except the conversation area and the shared screen furniture - because
 * it is the universal resolution point: every path that holds a card number and needs an account, or
 * holds an account and needs a card, goes through this one table. The Java consumers are the
 * bill-payment service, the transaction-add service and the file-maintenance service, so a single
 * declared finder here is not a narrow surface and is certainly not dead code; it is the surface three
 * features share.
 *
 * <p>It is also the most heavily constrained table in the schema. Of the six foreign keys the whole
 * migrated schema declares, <strong>three originate here</strong> - one to the card master, one to the
 * account master and one to the customer master - which is more than any other table declares. That is
 * why the reference seed loads it <em>last</em> of the four related tables: the customer, the account
 * and the card rows must all exist before a single row of this one can be admitted. A test that
 * inserts here therefore has to think about parents, and the fixtures below do.
 *
 * <h2>The layout arithmetic, and the byte count it explains</h2>
 *
 * <p>The record is 50 bytes wide and carries information in 36 of them: a 16-byte card number at
 * offset 0, which is the business key, a 9-byte customer identifier at offset 16 and an 11-byte
 * account identifier at offset 25. Those three sum to exactly 36, and the remaining 14 bytes are a
 * trailing filler that is deliberately neither an attribute of the entity nor a column of the table.
 *
 * <p>That split is the one fact a reader needs in order not to mistake a correct fixture for data
 * loss. The delivered text fixture {@code app/data/ASCII/cardxref.txt} measures 1,850 bytes for the
 * same 50 records that the sequential data set
 * {@code app/data/EBCDIC/AWS.M2.CARDDEMO.CARDXREF.PS} holds in 2,500. Both are correct and neither is
 * truncated: 50 rows of the 36 mapped bytes plus one line terminator each is 1,850, while 50 rows at
 * the full record length is 2,500. The two file sizes differ by 650 bytes; the record payloads differ
 * by the 700 filler bytes, and the 50 line terminators of the text form account for the remaining 50.
 * Every one of those figures is asserted below rather than merely stated here, because an arithmetic
 * claim in a comment is exactly the kind that decays.
 *
 * <h2>Why the finder answers with a list, and why the seed alone cannot prove it</h2>
 *
 * <p>The legacy access path over the account identifier is <em>non-unique</em> and upgraded, and a
 * keyed read of it returns the first base record in ascending base-key order rather than the only one.
 * The Java translation therefore declares a list-returning finder: a single-valued derived query would
 * raise an incorrect-result-size failure the instant a second row shared an account identifier, which
 * is a failure the legacy read of a duplicate-bearing path cannot produce. The single-row selection
 * rule belongs to the caller of that list, not to a second repository method invented here - the
 * reasoning is recorded as {@code DL-121} and {@code DL-164} in {@code docs/decision-log.md}, and this
 * class deliberately adds nothing to the production interface.
 *
 * <p>The delivered seed is strictly one-to-one - 50 accounts, 50 cards, 50 cross-reference rows - so
 * <strong>no seeded account carries more than one row and the seed alone cannot demonstrate the list
 * contract at all</strong>. That is an honest limitation of the fixture rather than of the design, and
 * it is closed the only way it can be closed: one test inserts two further rows against a single
 * account, with differing card numbers, and proves that the finder returns both. Without that fixture
 * the most important behavioural property of this repository would go unproven, which is why the test
 * that supplies it is never skipped or made conditional.
 *
 * <h2>Why the plan check asks the planner to ignore a whole-table read</h2>
 *
 * <p>The seeded table is 50 rows in a single page, so reading all of it genuinely costs less than
 * descending an index, and once the server has statistics for the table the planner correctly prefers
 * the whole-table read. An unconditioned plan assertion would therefore pass on a freshly migrated
 * server and fail after the first analysis of the table - a flake, not a finding. Removing the
 * sequential alternative for the single statement under inspection asks the planner the question the
 * assertion is actually about: <em>can this predicate be answered through that index at all</em>. The
 * answer is a plan shape and nothing else - no cost figure, no timing, no duration threshold, and no
 * execution of the statement. That restraint is deliberate: no numeric performance figure is asserted
 * anywhere in this module, because the estate documents no baseline to compare one against.
 *
 * <h2>What this class deliberately does not declare</h2>
 *
 * <p>The server is owned by {@link AbstractPostgresIT}, which starts one PostgreSQL 16 instance for
 * the whole run, migrates it, and publishes its address so a context-booting subclass inherits it.
 * Honouring that contract, this class declares no container, no container extension, no data-source
 * property source of its own and no context-dirtying annotation. It declares only its own application
 * context and its own fixtures. No in-memory engine is used or reachable: the widths, the check
 * constraints and the catalogue this class reads back exist only on the real server.
 *
 * <h2>Why the context is assembled from named configurations rather than scanned</h2>
 *
 * <p>A real Spring context is what makes the derived finder's resolution a test at all - the name is
 * parsed against the entity while the context refreshes, and a misspelling in it is a refusal to start
 * rather than a compile error. But the context is assembled from the four auto-configurations this
 * class needs plus one configuration of its own, and it deliberately performs <strong>no component
 * scan</strong>.
 *
 * <p>The reason is specific rather than stylistic. A scan of the base package reaches the compiled test
 * tree as well as the production tree, and two neighbouring repository tests each publish a nested
 * configuration that enables repository scanning of the whole production repository package. Two
 * configurations publishing the same repository bean is a refusal to refresh - an override error naming
 * a repository this table has nothing to do with - so a scanned context here would fail for a reason no
 * reader would connect to the cross reference. Naming what is needed removes the scan, and with it a
 * whole class of failure that has nothing to say about this table. The neighbouring tests' fixtures
 * stay theirs; this class publishes its own.
 *
 * <p>Every test that writes rolls its own writes back, so the delivered seed the other classes assert
 * against is left exactly as it was found. The reserved card numbers this class uses lie far above
 * every seeded key and are shared with nothing else.
 *
 * <h2>On the card number itself</h2>
 *
 * <p>A card number is a primary account number. Nothing here renders one into a name, a message, a
 * comment or a diagnostic: values are held in named constants and asserted by equality and by width
 * against those constants, never by being printed. The legacy design applies no encryption, no
 * tokenisation and no masking to this field and this migration introduces none, because inventing one
 * would be feature expansion; that residual gap is decision {@code D-14} in
 * {@code docs/decision-log.md} and is recorded there as deliberately unclosed rather than fixed here.
 *
 * <p>Provenance: this test has no legacy antecedent - the estate carries no test harness of any kind.
 * The contract it asserts is that of the record layout {@code app/cpy/CVACT03Y.cpy}, the cluster and
 * alternate-index definitions of {@code app/jcl/XREFFILE.jcl}, the two online file definitions of
 * {@code app/csd/CARDDEMO.CSD}, and the keyed reads of the account path in
 * {@code app/cbl/COBIL00C.cbl} and {@code app/cbl/COTRN02C.cbl} together with the sequential read of
 * the base cluster in {@code app/cbl/CBACT03C.cbl}. All were read as read-only reference at checkout
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. That stamp is a provenance string for the
 * traceability matrix header only; nothing here asserts it against a member. No source line of any
 * legacy artefact is transcribed and none is copied into this module: the estate is cited by member
 * name, record width, field offset, key length, table name, column name, index name and constraint
 * name alone.
 */
@SpringBootTest(classes = CardCrossReferenceRepositoryIT.PersistenceUnderTest.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "spring.main.banner-mode=off")
@DisplayName("Card cross reference: the 36-of-50 byte layout, the non-unique account path, and the "
        + "three foreign keys that originate here")
final class CardCrossReferenceRepositoryIT extends AbstractPostgresIT {

    /** The table the schema migration creates for this record layout. */
    private static final String TABLE = "card_cross_reference";

    /**
     * The non-unique index that replaces the legacy alternate index over the account identifier.
     *
     * <p>Named as a constant because three assertions read it - the catalogue roster, the uniqueness
     * flag and the plan shape - and a misspelling in any one of them would weaken that assertion
     * silently rather than fail it.
     */
    private static final String ACCOUNT_INDEX = "idx_card_cross_reference_xref_acct_id";

    /** The column that index leads on, which is the column the declared finder matches. */
    private static final String ACCOUNT_COLUMN = "xref_acct_id";

    /** The foreign key from this table to the card master. */
    private static final String FOREIGN_KEY_TO_CARD = "fk_card_xref_card";

    /** The foreign key from this table to the account master. */
    private static final String FOREIGN_KEY_TO_ACCOUNT = "fk_card_xref_account";

    /** The foreign key from this table to the customer master. */
    private static final String FOREIGN_KEY_TO_CUSTOMER = "fk_card_xref_customer";

    /** The card master, parent of the business key. */
    private static final String CARD_TABLE = "card";

    /** The account master, parent of the account identifier. */
    private static final String ACCOUNT_TABLE = "account";

    /** The customer master, parent of the customer identifier. */
    private static final String CUSTOMER_TABLE = "customer";

    /** The primary key of this table, so the index roster assertion can be exhaustive. */
    private static final String PRIMARY_KEY_INDEX = "pk_card_cross_reference";

    /** Rows the reference seed loads, which the seed script itself refuses to deviate from. */
    private static final int SEEDED_ROW_COUNT = 50;

    /** Width of the card number in characters: the business key, at offset 0. */
    private static final int CARD_NUMBER_WIDTH = 16;

    /** Width of the customer identifier in characters, at offset 16. */
    private static final int CUSTOMER_ID_WIDTH = 9;

    /** Width of the account identifier in characters, at offset 25 - the alternate-key position. */
    private static final int ACCOUNT_ID_WIDTH = 11;

    /** Width of the trailing filler in bytes, mapped to no attribute and to no column. */
    private static final int FILLER_WIDTH = 14;

    /** Bytes of the record that carry information: the three mapped fields, and nothing else. */
    private static final int DATA_WIDTH = 36;

    /** Full record width of the cluster, mapped bytes plus filler. */
    private static final int RECORD_WIDTH = 50;

    /** The one byte a line of the text fixture ends with, which the fixed-length data set has not. */
    private static final int LINE_TERMINATOR_WIDTH = 1;

    /** Measured size of the delivered text fixture: rows of mapped bytes, each newline-terminated. */
    private static final int FIXTURE_FILE_BYTES = 1850;

    /** Measured size of the mainframe sequential data set holding the same rows at full width. */
    private static final int DATASET_FILE_BYTES = 2500;

    /**
     * The business key of one seeded row, chosen because it is the lowest of the fifty and carries a
     * leading zero - so one constant serves both the keyed-read assertions and the padding assertions.
     */
    private static final String SEEDED_CARD_NUMBER = "0500024453765740";

    /** The customer identifier that row resolves to. */
    private static final String SEEDED_CUSTOMER_ID = "000000050";

    /** The account identifier that row resolves to, and the alternate key every lookup below uses. */
    private static final String SEEDED_ACCOUNT_ID = "00000000050";

    /** The same business key with its leading zero removed, which must match nothing. */
    private static final String UNPADDED_CARD_NUMBER = "500024453765740";

    /** The same customer identifier with its leading zeros removed, which must match nothing. */
    private static final String UNPADDED_CUSTOMER_ID = "50";

    /** The same account identifier with its leading zeros removed, which must match nothing. */
    private static final String UNPADDED_ACCOUNT_ID = "50";

    /** The character every padded identifier of the seeded row begins with. */
    private static final String LEADING_ZERO = "0";

    /** A card number present in no row of any table, taken from the shared fixture vocabulary. */
    private static final String ABSENT_CARD_NUMBER = TestDataFactory.UNKNOWN_CARD_NUMBER;

    /** An account identifier present in no row of any table. */
    private static final String ABSENT_ACCOUNT_ID = TestDataFactory.UNKNOWN_ACCOUNT_ID;

    /** A customer identifier present in no row of any table. */
    private static final String ABSENT_CUSTOMER_ID = TestDataFactory.UNKNOWN_CUSTOMER_ID;

    /** Rows the seeded account carries before any fixture of this class is applied. */
    private static final int SEEDED_ROWS_OF_ONE_ACCOUNT = 1;

    /** Rows that same account carries once the multi-row fixture has added two more. */
    private static final int ROWS_AFTER_THE_MULTI_ROW_FIXTURE = 3;

    /**
     * The first card number reserved for this class.
     *
     * <p>Chosen well above every seeded key so that it collides with no delivered row, and distinct
     * from the reserved keys of the neighbouring classes so that two classes can never contend for one
     * row. It is not the absent card number above: this one is deliberately made to <em>exist</em> in
     * the card master for the duration of a fixture, which is the only way an insert can isolate a
     * foreign key other than the one on the business key.
     */
    private static final String RESERVED_CARD_ONE = "9910000000000001";

    /** The second card number reserved for this class, so two rows can differ in their key alone. */
    private static final String RESERVED_CARD_TWO = "9910000000000002";

    /** Verification code of a reserved card row: three synthetic digits protecting nothing. */
    private static final String RESERVED_VERIFICATION_CODE = "000";

    /** Embossed name of a reserved card row, chosen to be recognisably a fixture. */
    private static final String RESERVED_EMBOSSED_NAME = "CROSS REFERENCE FIXTURE";

    /** Expiry of a reserved card row, far enough out that no window check can be surprised by it. */
    private static final String RESERVED_EXPIRATION_DATE = "2099-12-31";

    /** Active indicator of a reserved card row. */
    private static final String RESERVED_ACTIVE_STATUS = "Y";

    /** The fragment every PostgreSQL plan node that descends an index carries in its description. */
    private static final String INDEX_SCAN_NODE = "Index Scan";

    /** The fragment the whole-table alternative carries, which the plan under inspection must not. */
    private static final String SEQUENTIAL_SCAN_NODE = "Seq Scan";

    /**
     * Inserts one reserved row into the card master so that a cross-reference row may name it.
     *
     * <p>A complete literal statement with every value bound. The owning account is a seeded one, so
     * the card master's own foreign key resolves and the only constraint a following cross-reference
     * insert can offend is the one the test is about.
     */
    private static final String INSERT_CARD = """
            INSERT INTO card (card_num, card_acct_id, card_cvv_cd, card_embossed_name,
                              card_expiration_date, card_active_status)
                 VALUES (?, ?, ?, ?, ?, ?)
            """;

    /** Inserts one cross-reference row directly, bypassing the entity's own write-time rules. */
    private static final String INSERT_CROSS_REFERENCE = """
            INSERT INTO card_cross_reference (xref_card_num, xref_cust_id, xref_acct_id)
                 VALUES (?, ?, ?)
            """;

    /** Counts seeded rows whose business key names no row of the card master. */
    private static final String ROWS_WITH_NO_CARD_PARENT = """
            SELECT count(*) FROM card_cross_reference x
             WHERE NOT EXISTS (SELECT 1 FROM card p WHERE p.card_num = x.xref_card_num)
            """;

    /** Counts seeded rows whose account identifier names no row of the account master. */
    private static final String ROWS_WITH_NO_ACCOUNT_PARENT = """
            SELECT count(*) FROM card_cross_reference x
             WHERE NOT EXISTS (SELECT 1 FROM account p WHERE p.acct_id = x.xref_acct_id)
            """;

    /** Counts seeded rows whose customer identifier names no row of the customer master. */
    private static final String ROWS_WITH_NO_CUSTOMER_PARENT = """
            SELECT count(*) FROM card_cross_reference x
             WHERE NOT EXISTS (SELECT 1 FROM customer p WHERE p.cust_id = x.xref_cust_id)
            """;

    /** Reads back, by name, every foreign key declared on one table. */
    private static final String FOREIGN_KEY_NAMES = """
            SELECT tc.constraint_name
              FROM information_schema.table_constraints tc
             WHERE tc.table_schema = 'public'
               AND tc.table_name = ?
               AND tc.constraint_type = 'FOREIGN KEY'
             ORDER BY tc.constraint_name
            """;

    /** Reads back the table one named foreign key points at. */
    private static final String FOREIGN_KEY_PARENT_TABLE = """
            SELECT DISTINCT ccu.table_name
              FROM information_schema.constraint_column_usage ccu
             WHERE ccu.constraint_schema = 'public'
               AND ccu.constraint_name = ?
            """;

    /** Reads back, by name, every index present on one table. */
    private static final String INDEX_NAMES = """
            SELECT indexname FROM pg_indexes
             WHERE schemaname = 'public' AND tablename = ?
             ORDER BY indexname
            """;

    /** Reads back whether one named index enforces uniqueness. */
    private static final String INDEX_ENFORCES_UNIQUENESS = """
            SELECT i.indisunique
              FROM pg_index i
              JOIN pg_class c ON c.oid = i.indexrelid
              JOIN pg_namespace n ON n.oid = c.relnamespace
             WHERE n.nspname = 'public' AND c.relname = ?
            """;

    /** Reads back the name of the column one named index is ordered on first. */
    private static final String INDEX_LEADING_COLUMN = """
            SELECT a.attname
              FROM pg_index i
              JOIN pg_class c ON c.oid = i.indexrelid
              JOIN pg_namespace n ON n.oid = c.relnamespace
              JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = i.indkey[0]
             WHERE n.nspname = 'public' AND c.relname = ?
            """;

    /** Reads back the stored width of each mapped field of one row, measured by the server. */
    private static final String STORED_FIELD_WIDTHS = """
            SELECT char_length(xref_card_num) AS card_number_width,
                   char_length(xref_cust_id)  AS customer_id_width,
                   char_length(xref_acct_id)  AS account_id_width
              FROM card_cross_reference
             WHERE xref_card_num = ?
            """;

    /**
     * The account lookup, presented to the planner for description rather than for execution.
     *
     * <p>The projection names all three mapped columns, which is what the finder reads, so the plan
     * described is the plan the finder gets rather than a narrower index-only variant of it.
     */
    private static final String EXPLAIN_ACCOUNT_LOOKUP = """
            EXPLAIN SELECT xref_card_num, xref_cust_id, xref_acct_id
                      FROM card_cross_reference
                     WHERE xref_acct_id = ?
            """;

    /** Counts the rows of this table, for a reading taken outside a fixture's own transaction. */
    private static final String COUNT_CROSS_REFERENCE_ROWS =
            "SELECT count(*) FROM card_cross_reference";

    /** Counts the rows of the card master, for the same purpose. */
    private static final String COUNT_CARD_ROWS = "SELECT count(*) FROM card";

    /** Removes the whole-table alternative for the session, so the plan answers a question of kind. */
    private static final String WITHOUT_THE_WHOLE_TABLE_ALTERNATIVE = "SET enable_seqscan = off";

    /** Restores it, so the pooled connection is handed back exactly as it was taken. */
    private static final String WITH_THE_WHOLE_TABLE_ALTERNATIVE = "RESET enable_seqscan";

    /** The repository under test. Injected, so a derived-query name defect fails the context refresh. */
    @Autowired
    private CardCrossReferenceRepository repository;

    /** The template every catalogue read, plan description and fixture write below goes through. */
    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** Creates the test class. */
    CardCrossReferenceRepositoryIT() {
    }

    /**
     * The context this class runs against: a data source, a template, a persistence unit and the
     * production repositories, and nothing else.
     *
     * <p>Four auto-configurations are named rather than discovered - the data source, the template, the
     * persistence provider and transaction management - so the context holds no web tier, no messaging
     * client, no security filter chain and no scheduler. Each of those would have to start, and none of
     * them has anything to do with a record layout.
     *
     * <p>The repositories are enabled from the production repository interface and the entities are
     * scanned from the production entity, so both rosters are stated by pointing at the real thing
     * instead of by writing a package name that a later move would silently invalidate. The persistence
     * provider is left on the validate-only posture the shipped test profile sets, which means a
     * refresh that completes is itself a statement that every mapped attribute matches the migrated
     * column it names.
     *
     * <p>The address of the server is not written here: the shared base class publishes the started
     * container's own address into this context, at a precedence above every property file.
     */
    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration({DataSourceAutoConfiguration.class, JdbcTemplateAutoConfiguration.class,
            HibernateJpaAutoConfiguration.class, TransactionAutoConfiguration.class})
    @EnableJpaRepositories(basePackageClasses = CardCrossReferenceRepository.class)
    @EntityScan(basePackageClasses = CardCrossReference.class)
    static class PersistenceUnderTest {

        /** Creates the configuration. */
        PersistenceUnderTest() {
        }
    }

    /**
     * The shape of the delivered seed, the business key, and the width arithmetic of the layout.
     *
     * <p>The volume assertion comes first in this nest on purpose: every later absence assertion in the
     * class - no unresolved parent, no row for an absent account - would pass vacuously against an
     * empty table, so proving the table is populated is what makes the rest of the class mean anything.
     */
    @Nested
    @DisplayName("the seeded shape, the business key and the width arithmetic")
    final class SeededShapeAndLayout {

        /** Creates the nest. */
        SeededShapeAndLayout() {
        }

        @Test
        @DisplayName("the reference seed loads exactly fifty rows, which is what makes every absence "
                + "assertion in this class non-vacuous")
        void theSeedLoadsExactlyFiftyRows() {
            assertThat(repository.count())
                    .as("the seed script refuses to complete at any other count")
                    .isEqualTo(SEEDED_ROW_COUNT);
        }

        @Test
        @DisplayName("the business key is the card number itself, and an absent key answers empty "
                + "rather than raising")
        void theBusinessKeyResolvesAndAnAbsentKeyAnswersEmpty() {
            final Optional<CardCrossReference> found = repository.findById(SEEDED_CARD_NUMBER);

            assertThat(found)
                    .as("the identifier is the record's own leading field, not a generated surrogate")
                    .isPresent();
            assertThat(found).get()
                    .extracting(CardCrossReference::getXrefCardNum)
                    .isEqualTo(SEEDED_CARD_NUMBER);
            assertThatNoException()
                    .as("a keyed read that finds nothing is the legacy not-found response, not a fault")
                    .isThrownBy(() -> repository.findById(ABSENT_CARD_NUMBER));
            assertThat(repository.findById(ABSENT_CARD_NUMBER)).isEmpty();
        }

        @Test
        @DisplayName("the three mapped fields measure sixteen, nine and eleven characters and account "
                + "for every one of the thirty-six data bytes")
        void theThreeMappedFieldsAccountForEveryDataByte() {
            final CardCrossReference row = repository.findById(SEEDED_CARD_NUMBER).orElseThrow();

            assertThat(row.getXrefCardNum())
                    .as("the key occupies its whole field")
                    .hasSize(CARD_NUMBER_WIDTH);
            assertThat(row.getXrefCustId()).hasSize(CUSTOMER_ID_WIDTH);
            assertThat(row.getXrefAcctId()).hasSize(ACCOUNT_ID_WIDTH);
            assertThat(row.getXrefCardNum().length() + row.getXrefCustId().length()
                    + row.getXrefAcctId().length())
                    .as("the three stored widths sum to the mapped portion of the record and no more")
                    .isEqualTo(DATA_WIDTH);
            assertThat(CARD_NUMBER_WIDTH + CUSTOMER_ID_WIDTH + ACCOUNT_ID_WIDTH)
                    .as("and the declared widths agree with the stored ones")
                    .isEqualTo(DATA_WIDTH);
        }

        @Test
        @DisplayName("the server measures the same three widths on a row it holds, so the widths are a "
                + "property of the schema and not of the mapping")
        void theServerMeasuresTheSameThreeWidths() {
            final StoredFieldWidths widths = storedFieldWidthsOf(SEEDED_CARD_NUMBER);

            assertThat(widths.cardNumber()).isEqualTo(CARD_NUMBER_WIDTH);
            assertThat(widths.customerId()).isEqualTo(CUSTOMER_ID_WIDTH);
            assertThat(widths.accountId()).isEqualTo(ACCOUNT_ID_WIDTH);
            assertThat(widths.total())
                    .as("bounded variable-length columns hold the full field, never a shortened form")
                    .isEqualTo(DATA_WIDTH);
        }

        @Test
        @DisplayName("the fourteen-byte filler is what reconciles a fixture of 1,850 bytes with a data "
                + "set of 2,500 holding the very same fifty records")
        void theFillerReconcilesTheTwoFileSizes() {
            assertThat(DATA_WIDTH + FILLER_WIDTH)
                    .as("mapped bytes plus filler is the full cluster record length")
                    .isEqualTo(RECORD_WIDTH);
            assertThat(SEEDED_ROW_COUNT * (DATA_WIDTH + LINE_TERMINATOR_WIDTH))
                    .as("the text fixture holds the mapped bytes and a line terminator per row")
                    .isEqualTo(FIXTURE_FILE_BYTES);
            assertThat(SEEDED_ROW_COUNT * RECORD_WIDTH)
                    .as("the sequential data set holds the same rows at the full record length")
                    .isEqualTo(DATASET_FILE_BYTES);
            assertThat(SEEDED_ROW_COUNT * FILLER_WIDTH
                    - SEEDED_ROW_COUNT * LINE_TERMINATOR_WIDTH)
                    .as("so the 650-byte difference between the two files is the 700 filler bytes less "
                            + "the 50 line terminators the text form adds, and nothing is missing")
                    .isEqualTo(DATASET_FILE_BYTES - FIXTURE_FILE_BYTES);
        }

        @Test
        @DisplayName("both delivered record images carry the same three fields at the two widths the "
                + "layout admits, which is why one loader cannot read the other's form")
        void bothRecordImagesCarryTheSameFieldsAtTheirOwnWidths() {
            final String fixtureImage = TestDataFactory.cardCrossReference()
                    .cardNumber(SEEDED_CARD_NUMBER)
                    .customerId(SEEDED_CUSTOMER_ID)
                    .accountId(SEEDED_ACCOUNT_ID)
                    .fixtureImage();
            final String datasetImage = TestDataFactory.cardCrossReference()
                    .cardNumber(SEEDED_CARD_NUMBER)
                    .customerId(SEEDED_CUSTOMER_ID)
                    .accountId(SEEDED_ACCOUNT_ID)
                    .datasetImage();

            assertThat(fixtureImage)
                    .as("the text form ends on the last mapped byte and carries no filler")
                    .hasSize(DATA_WIDTH);
            assertThat(datasetImage)
                    .as("the fixed-length form carries the filler as well")
                    .hasSize(RECORD_WIDTH)
                    .startsWith(fixtureImage);
            assertThat(datasetImage.length() - fixtureImage.length()).isEqualTo(FILLER_WIDTH);
        }
    }

    /**
     * The padding of all three identifiers, which is contractual rather than cosmetic.
     *
     * <p>Each of the three fields is a slice of a fixed-width record image, so a value that has lost
     * its leading zeros is not a tidier spelling of the same identity - it is a different one. Nothing
     * in this nest removes surrounding blanks from a value before comparing it, and no assertion here
     * would pass if anything in the write or read path did so on its behalf.
     */
    @Nested
    @DisplayName("leading zeros are part of the identity, on all three identifiers")
    final class PaddingFidelity {

        /** Creates the nest. */
        PaddingFidelity() {
        }

        @Test
        @DisplayName("all three identifiers come back exactly as the seed stored them, padding included")
        void allThreeIdentifiersComeBackExactlyAsStored() {
            final CardCrossReference row = repository.findById(SEEDED_CARD_NUMBER).orElseThrow();

            assertThat(row.getXrefCardNum())
                    .as("the key is returned untrimmed and unaltered")
                    .isEqualTo(SEEDED_CARD_NUMBER)
                    .startsWith(LEADING_ZERO);
            assertThat(row.getXrefCustId())
                    .isEqualTo(SEEDED_CUSTOMER_ID)
                    .startsWith(LEADING_ZERO);
            assertThat(row.getXrefAcctId())
                    .isEqualTo(SEEDED_ACCOUNT_ID)
                    .startsWith(LEADING_ZERO);
        }

        @Test
        @DisplayName("the unpadded spelling of the key matches no row, because it is a different key")
        void theUnpaddedKeyMatchesNoRow() {
            assertThat(UNPADDED_CARD_NUMBER)
                    .as("the unpadded spelling is one character short of the field width")
                    .hasSize(CARD_NUMBER_WIDTH - 1);
            assertThat(repository.findById(UNPADDED_CARD_NUMBER))
                    .as("a shortened key is not a near miss; it identifies nothing")
                    .isEmpty();
        }

        @Test
        @DisplayName("the unpadded spellings of the customer and account identifiers match neither the "
                + "stored values nor, in the account's case, any row of the finder")
        void theUnpaddedIdentifiersMatchNothing() {
            final CardCrossReference row = repository.findById(SEEDED_CARD_NUMBER).orElseThrow();

            assertThat(row.getXrefCustId()).isNotEqualTo(UNPADDED_CUSTOMER_ID);
            assertThat(row.getXrefAcctId()).isNotEqualTo(UNPADDED_ACCOUNT_ID);
            assertThat(repository.findByXrefAcctId(UNPADDED_ACCOUNT_ID))
                    .as("the alternate-key finder matches the stored value exactly, so an unpadded "
                            + "account identifier resolves to nothing rather than to every account "
                            + "whose digits happen to end that way")
                    .isEmpty();
        }
    }

    /**
     * The account access path that replaces the legacy alternate index, and its non-unique semantics.
     *
     * <p>Three properties are asserted here and they are not interchangeable. First, the finder answers
     * with a collection - proved at compile time by the assignment its result is read into, since a
     * single-valued declaration would not compile there. Second, an account carrying no row answers
     * empty rather than null and raises nothing, which is the analogue of the legacy not-found response.
     * Third, and only demonstrable with a fixture the seed cannot supply, an account carrying two rows
     * yields both.
     */
    @Nested
    @DisplayName("the non-unique account access path")
    final class AccountAccessPath {

        /** Creates the nest. */
        AccountAccessPath() {
        }

        @Test
        @DisplayName("the derived finder resolved while the context was refreshing, which is the only "
                + "place a misspelled attribute in its name could have been caught")
        void theDerivedFinderResolvedDuringBootstrap() {
            assertThat(repository)
                    .as("the repository proxy exists, so the finder's name parsed against the entity")
                    .isNotNull();
            assertThatNoException()
                    .as("and invoking it issues real SQL against the migrated schema")
                    .isThrownBy(() -> repository.findByXrefAcctId(SEEDED_ACCOUNT_ID));
        }

        @Test
        @DisplayName("the finder answers with a collection, and the seeded account carries exactly one "
                + "row whose account identifier is eleven characters untrimmed")
        void theFinderAnswersWithACollectionOfTheAccountsRows() {
            final List<CardCrossReference> rows = repository.findByXrefAcctId(SEEDED_ACCOUNT_ID);

            assertThat(rows)
                    .as("the declared return type is what the line above proves: a single-valued "
                            + "declaration would not compile into this variable, and would fail at run "
                            + "time the moment a second row shared an account identifier")
                    .isInstanceOf(Collection.class);
            assertThat(rows)
                    .as("the delivered seed is one-to-one across accounts, cards and rows")
                    .hasSize(SEEDED_ROWS_OF_ONE_ACCOUNT);

            final CardCrossReference only = rows.get(0);

            assertThat(only.getXrefAcctId())
                    .as("matched exactly as supplied, and returned at the width the alternate key "
                            + "occupies with nothing removed")
                    .isEqualTo(SEEDED_ACCOUNT_ID)
                    .hasSize(ACCOUNT_ID_WIDTH);
            assertThat(only.getXrefCardNum()).isEqualTo(SEEDED_CARD_NUMBER);
        }

        @Test
        @DisplayName("an account carrying no row answers with an empty collection rather than null, and "
                + "raises nothing")
        void anAccountCarryingNoRowAnswersEmpty() {
            assertThatNoException()
                    .as("absence is an answer here, not a failure")
                    .isThrownBy(() -> repository.findByXrefAcctId(ABSENT_ACCOUNT_ID));

            final List<CardCrossReference> rows = repository.findByXrefAcctId(ABSENT_ACCOUNT_ID);

            assertThat(rows)
                    .as("empty, and never null: the service turns absence into a screen message")
                    .isNotNull()
                    .isEmpty();
        }

        /**
         * Supplies the one fixture the delivered seed cannot, and proves the property that matters most
         * about this repository.
         *
         * <p>The seed is strictly one-to-one, so nothing in it exercises the non-unique alternate key.
         * Two further rows are inserted against a single seeded account, differing only in their card
         * numbers, and the finder is required to return both. The two card numbers must first exist in
         * the card master, because one of this table's three foreign keys says so - which is itself
         * worth seeing in a test, since it is the reason the seed loads this table last.
         *
         * <p>The method is transactional, so every row it writes is rolled back and the one-to-one seed
         * the neighbouring classes assert against is left untouched. Nothing about it is conditional:
         * were it skipped, the list contract would be unproven.
         */
        @Test
        @Transactional
        @DisplayName("two rows sharing one account and differing in their key alone are BOTH returned, "
                + "which is the non-unique contract the one-to-one seed cannot show")
        void twoRowsSharingOneAccountAreBothReturned() throws SQLException {
            assertThat(repository.findByXrefAcctId(SEEDED_ACCOUNT_ID))
                    .as("the starting state is the one-to-one seed")
                    .hasSize(SEEDED_ROWS_OF_ONE_ACCOUNT);
            insertReservedCard(RESERVED_CARD_ONE);
            insertReservedCard(RESERVED_CARD_TWO);
            repository.saveAllAndFlush(List.of(
                    reservedCrossReference(RESERVED_CARD_ONE),
                    reservedCrossReference(RESERVED_CARD_TWO)));

            final List<CardCrossReference> rows = repository.findByXrefAcctId(SEEDED_ACCOUNT_ID);

            assertThat(rows)
                    .as("every row of the account is exposed, not merely the first")
                    .hasSize(ROWS_AFTER_THE_MULTI_ROW_FIXTURE);
            assertThat(rows)
                    .extracting(CardCrossReference::getXrefCardNum)
                    .as("both inserted rows are present alongside the seeded one, and they differ in "
                            + "exactly the field the base cluster is keyed on")
                    .containsExactlyInAnyOrder(SEEDED_CARD_NUMBER, RESERVED_CARD_ONE, RESERVED_CARD_TWO)
                    .doesNotHaveDuplicates();
            assertThat(rows)
                    .allSatisfy(row -> assertThat(row.getXrefAcctId()).isEqualTo(SEEDED_ACCOUNT_ID));
            assertThat(committedRowCount(COUNT_CROSS_REFERENCE_ROWS))
                    .as("read from outside this transaction the delivered volume is unchanged, which is "
                            + "what proves the fixture is uncommitted and the seed the other classes "
                            + "assert against survives this one")
                    .isEqualTo(SEEDED_ROW_COUNT);
            assertThat(committedRowCount(COUNT_CARD_ROWS))
                    .as("and the two parent rows this fixture needed are uncommitted with it, so the "
                            + "card master is left as it was found too")
                    .isEqualTo(SEEDED_ROW_COUNT);
        }
    }

    /**
     * The index that backs the account path: that it exists under the name the migration gives it, that
     * it admits duplicates as the legacy path did, and that the account predicate can be answered
     * through it.
     *
     * <p>Nothing here observes a cost, a row estimate, an elapsed time or a rate, and the statement
     * under inspection is described rather than run. A plan is read for its shape alone.
     */
    @Nested
    @DisplayName("the index that backs the account path")
    final class IndexBackingTheAccountPath {

        /** Creates the nest. */
        IndexBackingTheAccountPath() {
        }

        @Test
        @DisplayName("the table carries exactly its primary key and the account index, and the account "
                + "index admits duplicates and leads on the account column")
        void theAccountIndexExistsAdmitsDuplicatesAndLeadsOnTheAccountColumn() {
            assertThat(jdbcTemplate.queryForList(INDEX_NAMES, String.class, TABLE))
                    .as("one index for identity and one for the alternate path; no third")
                    .containsExactly(ACCOUNT_INDEX, PRIMARY_KEY_INDEX);
            assertThat(jdbcTemplate.queryForObject(
                    INDEX_ENFORCES_UNIQUENESS, Boolean.class, ACCOUNT_INDEX))
                    .as("the legacy path was declared non-unique, so a unique index here would reject "
                            + "the very second row the finder exists to return")
                    .isFalse();
            assertThat(jdbcTemplate.queryForObject(
                    INDEX_ENFORCES_UNIQUENESS, Boolean.class, PRIMARY_KEY_INDEX))
                    .as("while the business key remains unique, which is what makes it the identifier")
                    .isTrue();
            assertThat(jdbcTemplate.queryForObject(
                    INDEX_LEADING_COLUMN, String.class, ACCOUNT_INDEX))
                    .as("ordered on the account identifier first, so an account predicate alone can "
                            + "use it")
                    .isEqualTo(ACCOUNT_COLUMN);
        }

        @Test
        @DisplayName("the account lookup is answered by descending that index rather than by reading "
                + "the whole table")
        void theAccountLookupIsAnsweredThroughThatIndex() {
            final String plan = String.join("\n", describedAccountLookup(SEEDED_ACCOUNT_ID));

            assertThat(plan)
                    .as("the planner reaches the rows through the index the migration created for this "
                            + "predicate, by that name")
                    .contains(ACCOUNT_INDEX)
                    .contains(INDEX_SCAN_NODE)
                    .doesNotContain(SEQUENTIAL_SCAN_NODE);
        }
    }

    /**
     * The three foreign keys that originate from this table - more than from any other table in the
     * schema - and the referential integrity they hold.
     *
     * <p>Three things are proved and each answers a different question. The catalogue roster answers
     * "are they declared, under the names the migration gives them, against the parents it names".
     * The resolution counts answer "do they hold for the delivered data", which a declared-but-unloaded
     * constraint would not. And three refused inserts answer "are they enforced", which is the only one
     * of the three that a disabled or deferred constraint could fail.
     *
     * <p>Each refusal is isolated to exactly one constraint: the row offered names an absent parent in
     * one column and a present parent in the other two, so the failure cannot be attributed to the
     * wrong constraint. Isolating the account and the customer keys means the offered card number has
     * to <em>exist</em> in the card master first, which is why those two tests create a reserved card
     * row - and is a demonstration in its own right of why the seed loads this table last.
     *
     * <p>Each refusal is asserted by constraint name. Nothing here asserts on the server's own
     * explanatory detail, which restates the offending value.
     *
     * <p>The three keys are constraints of the database and of nothing else. The entity declares no
     * mapped association - no collection attribute, no owning or inverse side, no join-column
     * declaration and no fetch plan - because this table exists so that a lookup can go from one key to
     * another without loading anything, and an association would defeat exactly that. Nothing in this
     * class navigates from a row to a parent, and nothing should.
     */
    @Nested
    @DisplayName("the three foreign keys that originate from this table")
    final class ForeignKeysOriginatingHere {

        /** Creates the nest. */
        ForeignKeysOriginatingHere() {
        }

        @Test
        @DisplayName("exactly three foreign keys are declared here, by name, and they point at the card, "
                + "the account and the customer masters respectively")
        void exactlyThreeForeignKeysAreDeclaredAndNameTheirParents() {
            assertThat(jdbcTemplate.queryForList(FOREIGN_KEY_NAMES, String.class, TABLE))
                    .as("three, and no fourth: this table is the most heavily constrained in the schema")
                    .containsExactly(FOREIGN_KEY_TO_ACCOUNT, FOREIGN_KEY_TO_CARD,
                            FOREIGN_KEY_TO_CUSTOMER);
            assertThat(jdbcTemplate.queryForObject(
                    FOREIGN_KEY_PARENT_TABLE, String.class, FOREIGN_KEY_TO_CARD))
                    .isEqualTo(CARD_TABLE);
            assertThat(jdbcTemplate.queryForObject(
                    FOREIGN_KEY_PARENT_TABLE, String.class, FOREIGN_KEY_TO_ACCOUNT))
                    .isEqualTo(ACCOUNT_TABLE);
            assertThat(jdbcTemplate.queryForObject(
                    FOREIGN_KEY_PARENT_TABLE, String.class, FOREIGN_KEY_TO_CUSTOMER))
                    .isEqualTo(CUSTOMER_TABLE);
        }

        @Test
        @DisplayName("every one of the fifty seeded rows resolves against all three parents, which is "
                + "why the seed loads this table after the other three")
        void everySeededRowResolvesAgainstAllThreeParents() {
            assertThat(repository.count())
                    .as("stated first so that the three counts below cannot pass over an empty table")
                    .isEqualTo(SEEDED_ROW_COUNT);
            assertThat(jdbcTemplate.queryForObject(ROWS_WITH_NO_CARD_PARENT, Long.class))
                    .as("no seeded row names a card the card master does not hold")
                    .isZero();
            assertThat(jdbcTemplate.queryForObject(ROWS_WITH_NO_ACCOUNT_PARENT, Long.class))
                    .as("no seeded row names an account the account master does not hold")
                    .isZero();
            assertThat(jdbcTemplate.queryForObject(ROWS_WITH_NO_CUSTOMER_PARENT, Long.class))
                    .as("no seeded row names a customer the customer master does not hold")
                    .isZero();
        }

        @Test
        @DisplayName("a row naming a card that no card row holds is refused by the card constraint, "
                + "leaving the table as it was")
        void aRowNamingAnAbsentCardIsRefusedByTheCardConstraint() {
            assertThatExceptionOfType(DataIntegrityViolationException.class)
                    .as("the other two identifiers are seeded ones, so only the key on the business "
                            + "key can be the constraint that refuses this row")
                    .isThrownBy(() -> jdbcTemplate.update(INSERT_CROSS_REFERENCE,
                            ABSENT_CARD_NUMBER, SEEDED_CUSTOMER_ID, SEEDED_ACCOUNT_ID))
                    .withMessageContaining(FOREIGN_KEY_TO_CARD);
            assertThat(repository.count())
                    .as("a refused insert leaves nothing behind")
                    .isEqualTo(SEEDED_ROW_COUNT);
        }

        @Test
        @Transactional
        @DisplayName("a row naming an account that no account row holds is refused by the account "
                + "constraint, even though its card and its customer both exist")
        void aRowNamingAnAbsentAccountIsRefusedByTheAccountConstraint() {
            insertReservedCard(RESERVED_CARD_ONE);

            assertThatExceptionOfType(DataIntegrityViolationException.class)
                    .as("the card now exists and the customer is a seeded one, so the account key is "
                            + "the only constraint left that this row can offend")
                    .isThrownBy(() -> jdbcTemplate.update(INSERT_CROSS_REFERENCE,
                            RESERVED_CARD_ONE, SEEDED_CUSTOMER_ID, ABSENT_ACCOUNT_ID))
                    .withMessageContaining(FOREIGN_KEY_TO_ACCOUNT);
        }

        @Test
        @Transactional
        @DisplayName("a row naming a customer that no customer row holds is refused by the customer "
                + "constraint, even though its card and its account both exist")
        void aRowNamingAnAbsentCustomerIsRefusedByTheCustomerConstraint() {
            insertReservedCard(RESERVED_CARD_ONE);

            assertThatExceptionOfType(DataIntegrityViolationException.class)
                    .as("the card now exists and the account is a seeded one, so the customer key is "
                            + "the only constraint left that this row can offend")
                    .isThrownBy(() -> jdbcTemplate.update(INSERT_CROSS_REFERENCE,
                            RESERVED_CARD_ONE, ABSENT_CUSTOMER_ID, SEEDED_ACCOUNT_ID))
                    .withMessageContaining(FOREIGN_KEY_TO_CUSTOMER);
        }
    }

    /**
     * A write and a read of a purpose-built row, through the inherited methods alone.
     *
     * <p>Keyed access to this cluster is the inherited keyed read and a rewrite is the inherited save;
     * no finder is declared for either, and none is needed. What this nest establishes is that a row
     * this module writes comes back byte-for-byte as it was written - which is the property every
     * fixed-width comparison downstream depends on - and that the server, asked independently,
     * measures the three fields at the widths the layout declares.
     */
    @Nested
    @DisplayName("a purpose-built row through the inherited save and keyed read")
    final class RoundTripThroughInheritedMethods {

        /** Creates the nest. */
        RoundTripThroughInheritedMethods() {
        }

        @Test
        @Transactional
        @DisplayName("all three identifiers survive a save and a keyed read unchanged, at the widths "
                + "the layout declares")
        void allThreeIdentifiersSurviveASaveAndKeyedReadUnchanged() {
            insertReservedCard(RESERVED_CARD_ONE);
            repository.saveAndFlush(reservedCrossReference(RESERVED_CARD_ONE));

            final CardCrossReference reread = repository.findById(RESERVED_CARD_ONE).orElseThrow();

            assertThat(reread.getXrefCardNum())
                    .isEqualTo(RESERVED_CARD_ONE)
                    .hasSize(CARD_NUMBER_WIDTH);
            assertThat(reread.getXrefCustId())
                    .isEqualTo(SEEDED_CUSTOMER_ID)
                    .hasSize(CUSTOMER_ID_WIDTH);
            assertThat(reread.getXrefAcctId())
                    .isEqualTo(SEEDED_ACCOUNT_ID)
                    .hasSize(ACCOUNT_ID_WIDTH);

            final StoredFieldWidths widths = storedFieldWidthsOf(RESERVED_CARD_ONE);

            assertThat(widths.total())
                    .as("measured by the server on the row it now holds, not by the mapping that wrote "
                            + "it")
                    .isEqualTo(DATA_WIDTH);
            assertThat(repository.findByXrefAcctId(SEEDED_ACCOUNT_ID))
                    .as("and the new row is reachable through the alternate-key path as well as by key")
                    .extracting(CardCrossReference::getXrefCardNum)
                    .contains(RESERVED_CARD_ONE);
        }
    }

    /**
     * Adds one reserved row to the card master so that a cross-reference row may name it.
     *
     * <p>Required rather than incidental: the business key of this table is constrained to the card
     * master, so every fixture row of this class needs a card of its own to exist first. The owning
     * account is a seeded one, so the card master's own key resolves and the fixture introduces no
     * second reason for a write to be refused.
     *
     * <p>Written through the template rather than through the card repository, because this test belongs
     * to the cross-reference and has no business declaring a dependency on a neighbouring aggregate to
     * stand up its own fixture. The statement is a complete literal with every value bound.
     *
     * @param cardNumber the reserved sixteen-character key to add
     */
    private void insertReservedCard(final String cardNumber) {
        jdbcTemplate.update(INSERT_CARD, cardNumber, SEEDED_ACCOUNT_ID, RESERVED_VERIFICATION_CODE,
                RESERVED_EMBOSSED_NAME, RESERVED_EXPIRATION_DATE, RESERVED_ACTIVE_STATUS);
    }

    /**
     * Builds a cross-reference row on a reserved key, pointing at the seeded customer and account.
     *
     * <p>Built through the shared fixture builder rather than by calling the entity's constructor here,
     * so that the argument order of the three identifiers is stated once, in the one place that already
     * owns the layout, instead of once per call site where a transposition of three same-shaped strings
     * would compile silently.
     *
     * @param cardNumber the reserved key the row is to carry
     * @return a row ready to be stored
     */
    private static CardCrossReference reservedCrossReference(final String cardNumber) {
        return TestDataFactory.cardCrossReference()
                .cardNumber(cardNumber)
                .customerId(SEEDED_CUSTOMER_ID)
                .accountId(SEEDED_ACCOUNT_ID)
                .build();
    }

    /**
     * Asks the server to measure the three mapped fields of one stored row.
     *
     * <p>The measurement is taken by the server on the row as it holds it, so it is a statement about
     * the schema rather than about the mapping that wrote it. One literal statement, one bound value,
     * and a typed row mapper - so nothing is cast and no result is left untyped.
     *
     * @param cardNumber the key of the row to measure
     * @return the three widths the server reports
     */
    private StoredFieldWidths storedFieldWidthsOf(final String cardNumber) {
        return jdbcTemplate.queryForObject(STORED_FIELD_WIDTHS,
                (row, rowNumber) -> new StoredFieldWidths(
                        row.getInt(1), row.getInt(2), row.getInt(3)),
                cardNumber);
    }

    /**
     * Returns the plan the server describes for the account lookup, one line per plan row.
     *
     * <p>The statement is described and never executed, and the description is read for its shape alone:
     * which node reaches the rows, and through which named index. No cost, no row estimate, no elapsed
     * time and no rate is read out of it, here or by the caller.
     *
     * <p>The whole-table alternative is removed for the duration of the description and restored
     * immediately afterwards, on the one connection the description is taken over. The reason is stated
     * on this class: fifty rows occupy a single page, so once the server has statistics for the table it
     * correctly prefers reading all of it, and a plan assertion that did not remove that alternative
     * would pass on a freshly migrated server and fail on an analysed one. Removing it turns the
     * question from "which access path is cheaper today" - which is a timing question, and not one this
     * module asks - into "can this predicate be answered through that index at all", which is what the
     * migration promised and what the assertion is for. The restoration is what lets the connection go
     * back to the pool exactly as it was taken.
     *
     * @param accountId the account identifier the described lookup is to match
     * @return the plan lines, in the order the server reported them
     */
    private List<String> describedAccountLookup(final String accountId) {
        final ConnectionCallback<List<String>> describe = connection -> {
            applyPlannerSetting(connection, WITHOUT_THE_WHOLE_TABLE_ALTERNATIVE);
            try {
                return planOf(connection, accountId);
            } finally {
                applyPlannerSetting(connection, WITH_THE_WHOLE_TABLE_ALTERNATIVE);
            }
        };
        return jdbcTemplate.execute(describe);
    }

    /**
     * Applies one literal planner setting to one connection.
     *
     * @param connection the connection to apply it to
     * @param setting    the literal setting statement; never assembled from a value
     * @throws SQLException if the setting cannot be applied
     */
    private static void applyPlannerSetting(final Connection connection, final String setting)
            throws SQLException {
        try (Statement session = connection.createStatement()) {
            session.execute(setting);
        }
    }

    /**
     * Reads the described plan of the account lookup over one connection.
     *
     * @param connection the connection to describe the statement on
     * @param accountId  the bound account identifier
     * @return the plan lines in report order
     * @throws SQLException if the statement cannot be described
     */
    private static List<String> planOf(final Connection connection, final String accountId)
            throws SQLException {
        try (PreparedStatement described = connection.prepareStatement(EXPLAIN_ACCOUNT_LOOKUP)) {
            described.setString(1, accountId);
            try (ResultSet lines = described.executeQuery()) {
                final List<String> plan = new ArrayList<>();
                while (lines.next()) {
                    plan.add(lines.getString(1));
                }
                return List.copyOf(plan);
            }
        }
    }

    /**
     * Counts rows over a connection of its own, outside whatever transaction the caller is in.
     *
     * <p>This is how a transactional fixture proves that it is one: a row written inside an uncommitted
     * transaction is invisible here, so a count taken through this method that still reports the
     * delivered volume is a demonstration that the delivered seed will be intact once the test's
     * transaction is rolled back. Reading it over a separate connection is the whole point, and a plain
     * count neither takes nor waits on a lock, so it cannot contend with the fixture that is running.
     *
     * @param countStatement a complete literal count statement
     * @return the number of rows visible outside the caller's transaction
     * @throws SQLException if the count cannot be taken
     */
    private static long committedRowCount(final String countStatement) throws SQLException {
        try (Connection connection = connect();
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(countStatement)) {
            if (!rows.next()) {
                throw new IllegalStateException(
                        "a count statement returned no row, which cannot happen for an aggregate");
            }
            return rows.getLong(1);
        }
    }

    /**
     * The three widths the server reports for one stored row.
     *
     * @param cardNumber width of the stored card number
     * @param customerId width of the stored customer identifier
     * @param accountId  width of the stored account identifier
     */
    private record StoredFieldWidths(int cardNumber, int customerId, int accountId) {

        /**
         * Returns the mapped portion of the record these three fields account for.
         *
         * @return the sum of the three widths
         */
        int total() {
            return cardNumber + customerId + accountId;
        }
    }
}
