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

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import com.carddemo.domain.DailyTransaction;
import com.carddemo.support.AbstractPostgresIT;
import com.carddemo.support.TestDataFactory;
import com.carddemo.support.TestDataFactory.FieldSpec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Verifies, against a real PostgreSQL 16 server carrying the shipped migrations, the landing surface
 * of the sequential daily input: its thirteen fully prefixed columns, its deliberate absence of any
 * referential constraint, the decimal fidelity of its amount, the measured composition of its
 * three-hundred-row seed, and the ordered and paged sequential reads the batch tier performs over it.
 *
 * <h2>Why this table is unlike the other ten</h2>
 *
 * <p>Two facts set {@code daily_transaction} apart from every other table in the migrated schema, and
 * between them they account for most of what this specification asserts.</p>
 *
 * <p>First, it is the only one of the eleven whose legacy antecedent is a <strong>sequential</strong>
 * data set rather than a keyed cluster. There is consequently no browse to reproduce - no positioned
 * start, no forward or backward step, no browse end - and the resource-definition inventory the online
 * region loads registers eight files, none of which is this one, so the table has <em>no online access
 * path at all</em>. Its Java consumers are the posting service, the validation processor, the reject
 * writer, the posting job configuration, the consolidation processor and the daily read service.
 * Ordering nonetheless remains behavioural: the consolidation job merges two concatenated inputs
 * ordered by transaction identifier <strong>ascending</strong>, which is why an ascending sort is
 * asserted here as an ordered list and never as a set.</p>
 *
 * <p>Second, it is the only table in the schema with <strong>no foreign key in any migration
 * version</strong>, and it carries no secondary index either. That is a deliberate design decision
 * rather than an oversight, and it is load bearing: the posting program emits five distinct reject
 * reason codes - an invalid card, an account not found on the read, an over-limit condition, arrival
 * after account expiry, and an account not found on the rewrite - and posting proceeds only when the
 * reason code is zero. Each rejection produces a four-hundred-and-thirty byte reject record: the
 * three-hundred-and-fifty byte source image copied verbatim, then a four-digit reason code and a
 * seventy-six character description. A foreign key to the card master or the account master would
 * refuse the offending row <em>at insert time</em>, making the invalid-card and account-not-found
 * reasons unreachable and the reject record unproducible - a direct byte-parity failure. The
 * zero-foreign-key test below is therefore the proof that those reason codes remain reachable, and it
 * exists so that a future maintainer does not "correct" the missing constraint. <strong>No foreign
 * key, no check constraint and no bean-validation constraint may be added to this table or its
 * entity.</strong> The validation cascade itself is not exercised here: it lives in the batch tier,
 * runs in source order, and short-circuits at the first rejection.</p>
 *
 * <h2>The merchant-column asymmetry, which is the likeliest mapping error in this package</h2>
 *
 * <p>The landing layout and the posted layout are byte-for-byte identical - the same thirteen fields
 * at the same offsets and widths, three hundred and thirty mapped bytes followed by twenty bytes of
 * trailing filler that is not a column. They differ in exactly one respect, and it is a naming
 * respect: on <em>this</em> table all thirteen columns carry the landing prefix, <strong>including the
 * four merchant columns</strong>, whereas on the posted table those same four are unprefixed. The two
 * are mirror images in that one detail. Every column assertion here is therefore derived
 * mechanically from the record layout's own field names rather than restated by hand, and each
 * column's bare form is additionally asserted <em>absent</em>, so a mapping that borrowed the posted
 * table's names would fail on both counts. The context refresh is itself the coarsest form of the same
 * check: schema validation is active on the test profile, so a column name the entity does not agree
 * with prevents the context from starting.</p>
 *
 * <p>Despite the identical layouts there is <strong>no shared supertype</strong> anywhere - no mapped
 * superclass, no shared interface, no shared base repository - and none may be introduced. They are
 * separate data sets with separate lifecycles: this one is the raw landing image that validation reads
 * and that the reject writer copies verbatim, and it is seeded, while the posted table is seeded
 * empty.</p>
 *
 * <h2>Decimal fidelity, and the one rounding mode this estate permits</h2>
 *
 * <p>The amount derives from a zoned-decimal field of nine integer digits and two decimal places plus
 * a sign, mapped to a numeric column of precision eleven and scale two and to a
 * {@link java.math.BigDecimal} whose scale matches exactly. A census of the legacy estate found
 * <strong>zero</strong> occurrences of the rounding keyword across every program and copybook, so
 * every store into a two-decimal field truncates toward zero. Truncation is therefore the contract,
 * not an implementation detail, and it is asserted below on a value whose third decimal digit would
 * round the other way under any half-up or half-even policy. No floating-point type appears anywhere
 * in this file.</p>
 *
 * <p>Decoding the legacy representation is not attempted here: the overpunched sign convention, in
 * which the final byte carries both the low-order digit and the sign, is the sole responsibility of
 * the fixed-width codec. Nor is arithmetic performed here: the over-limit basis is cycle credit less
 * cycle debit plus the daily amount, evaluated <strong>strictly left to right</strong> into a
 * two-decimal field by the posting service, and that operand order may never be algebraically
 * rearranged, because truncation makes the arithmetic non-associative.</p>
 *
 * <h2>What the seed can prove, and the one thing it provably cannot</h2>
 *
 * <p>The reference fixture is three hundred records of three hundred and fifty bytes, and the seed
 * reproduces all three hundred under the local and test profiles only. Three properties of it are
 * measured facts rather than conveniences, and all three are asserted: two hundred and fifty rows
 * carry the point-of-sale source with a positive amount and fifty carry the operator source with a
 * negative amount, so both signed directions of the balance computation are exercised by real fixture
 * data; every origination timestamp is one and the same twenty-six character value, so there is
 * exactly one distinct value across the whole fixture; and every processing timestamp is twenty-six
 * blanks.</p>
 *
 * <p><strong>The consequence is a limitation that must be recorded rather than tested around.</strong>
 * Because every origination timestamp is identical and every processing timestamp is blank,
 * processing-date-window filtering <em>cannot be exercised from the seeded data at all</em>. A window
 * applied to it would select nothing while appearing to pass. No date-window test is attempted here;
 * date-range behaviour belongs to the posted table's specification, over purpose-built rows carrying a
 * spread of processing dates. The seed is never edited to compensate: the legacy tree is read-only and
 * the seeded content is what the byte-equivalence gate compares against.</p>
 *
 * <p>One further measured detail governs how the source column is asserted. The legacy field is ten
 * characters, space padded, and the enumerated contract carries that padding - but the seed stores the
 * <em>right-trimmed</em> form, because the column is a bounded variable-width type rather than a
 * blank-padded one. This specification asserts exactly what the seed stores and never a form derived
 * from the layout width, and it never trims or strips inside an assertion, so a change to either the
 * seed or the column type surfaces here instead of being absorbed.</p>
 *
 * <h2>Container, context and isolation</h2>
 *
 * <p>The database container is owned entirely by {@link AbstractPostgresIT}: this class declares no
 * container annotation, no container field, no server of its own and no data-source property source,
 * and it does not discard the context. It supplies only its own bootstrap - the narrowest slice that
 * a repository needs, being the data source, the template and the persistence provider - and reads the
 * shared server the base class has already migrated. Every mutating test is transactional and rolls
 * back, and every identifier it writes lies in a reserved band no fixture and no sibling
 * specification uses, so the table is observed at exactly three hundred rows by whatever runs next.</p>
 *
 * <p>The card primary account number receives no masking, tokenization or encryption anywhere in the
 * legacy design, and none is added here; that gap is recorded in the decision log rather than closed
 * by unrequested work. Accordingly no card number is written into a display name, an assertion
 * message, a log line or a literal in this file - every such value is held in a variable and compared
 * by equality.</p>
 *
 * <p>Provenance: the behaviour asserted here is that of the daily-transaction record layout
 * {@code app/cpy/CVTRA06Y.cpy}, the posting program {@code app/cbl/CBTRN02C.cbl}, the extract program
 * {@code app/cbl/CBTRN01C.cbl}, the job members {@code app/jcl/POSTTRAN.jcl} and
 * {@code app/jcl/COMBTRAN.jcl}, the resource definition {@code app/csd/CARDDEMO.CSD} and the reference
 * data set {@code app/data/ASCII/dailytran.txt}, read as read-only reference at commit SHA
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. That stamp is a provenance string for the
 * traceability matrix header alone and is asserted against no member. No legacy source statement is
 * transcribed.</p>
 *
 * <p><strong>Orphan-program context.</strong> The extract program named above reads this layout and is
 * invoked by no job member, no procedure and no online resource definition. Its Java counterpart is a
 * batch job that is defined but excluded from the default pipeline and exercised only by tests, so the
 * apparent orphan is a documented source anomaly rather than dead code. It is deliberately neither
 * launched nor referenced from this repository specification.</p>
 *
 * @see DailyTransactionRepository
 * @see DailyTransaction
 */
@SpringBootTest(classes = DailyTransactionRepositoryIT.RepositoryUnderTest.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DisplayName("Daily transaction repository: prefixed columns, zero constraints, truncating amounts, "
        + "measured seed, sequential order")
final class DailyTransactionRepositoryIT extends AbstractPostgresIT {

    /** The migrated table this specification is about. */
    private static final String LANDING_TABLE = "daily_transaction";

    /**
     * The prefix every one of the thirteen columns carries, the four merchant columns included.
     *
     * <p>Held once, so the expected column name of each field can be derived from the layout rather
     * than restated, and so the bare form of each name can be produced by removing it.
     */
    private static final String COLUMN_PREFIX = "dalytran_";

    /** The entity property the sequential reads order by, and the business key. */
    private static final String KEY_PROPERTY = "dalytranId";

    /** The amount column, the one column of the thirteen that is not character data. */
    private static final String AMOUNT_COLUMN = COLUMN_PREFIX + "amt";

    /** The layout field the amount column was derived from, named as the record layout names it. */
    private static final String AMOUNT_FIELD = "DALYTRAN-AMT";

    /** How many of the three hundred and fifty bytes are mapped to columns. */
    private static final int MAPPED_BYTES = 330;

    /** How many trailing filler bytes follow the mapped ones, and are not a column. */
    private static final int FILLER_BYTES = 20;

    /** The whole record width, mapped bytes plus filler. */
    private static final int RECORD_BYTES = 350;

    /** Digits the amount holds before the decimal point, from the layout's own width less its scale. */
    private static final int AMOUNT_INTEGER_DIGITS = 9;

    /** The lowest seeded identifier, which carries ten leading zeros within its sixteen characters. */
    private static final String LOWEST_SEEDED_KEY = "0000000000683580";

    /** The highest seeded identifier, and therefore the first row a descending read returns. */
    private static final String HIGHEST_SEEDED_KEY = "0000000996722787";

    /**
     * {@link #LOWEST_SEEDED_KEY} with its leading zeros removed.
     *
     * <p>Not a key of anything: the point of holding it is that it must resolve to nothing, which is
     * what proves the leading zeros are significant rather than decorative.
     */
    private static final String UNPADDED_KEY = "683580";

    /** The source value the seed stores on its two hundred and fifty purchases, exactly as stored. */
    private static final String PURCHASE_SOURCE = "POS TERM";

    /** The source value the seed stores on its fifty returns, exactly as stored. */
    private static final String RETURN_SOURCE = "OPERATOR";

    /**
     * The reserved identifier band, chosen clear of every seeded value and of every band a sibling
     * specification reserves, so a row that somehow escaped a rollback could still not be mistaken for
     * fixture data.
     */
    private static final String DANGLING_KEY = "9700000000000010";

    /** Reserved identifier of the row that exercises the full thirteen-property round trip. */
    private static final String ROUND_TRIP_KEY = "9700000000000020";

    /** Reserved identifier of the row that exercises the widest amount the column holds. */
    private static final String WIDEST_AMOUNT_KEY = "9700000000000030";

    /** Reserved identifier of the row that exercises truncation of a positive amount. */
    private static final String POSITIVE_TRUNCATION_KEY = "9700000000000040";

    /** Reserved identifier of the row that exercises truncation of a negative amount. */
    private static final String NEGATIVE_TRUNCATION_KEY = "9700000000000050";

    /** Reserved identifier carrying leading zeros, for the significant-zero round trip. */
    private static final String LEADING_ZERO_KEY = "0000000000009700";

    /** An identifier in the reserved band that is deliberately never written. */
    private static final String ABSENT_KEY = "9700000000000099";

    /** The widest amount the column holds: nine integer digits and two decimal places. */
    private static final BigDecimal WIDEST_AMOUNT = new BigDecimal("999999999.99");

    /**
     * A positive amount carrying a third decimal digit that truncation and rounding disagree about.
     *
     * <p>Truncating toward zero yields the hundredths below; every half-up or half-even policy yields
     * one hundredth more. That disagreement is the whole point of the value.
     */
    private static final BigDecimal POSITIVE_THREE_DECIMAL_AMOUNT = new BigDecimal("12.349");

    /** What {@link #POSITIVE_THREE_DECIMAL_AMOUNT} must become when stored. */
    private static final BigDecimal POSITIVE_TRUNCATED_AMOUNT = new BigDecimal("12.34");

    /** The negative counterpart, where truncating toward zero moves the value up rather than down. */
    private static final BigDecimal NEGATIVE_THREE_DECIMAL_AMOUNT = new BigDecimal("-12.349");

    /** What {@link #NEGATIVE_THREE_DECIMAL_AMOUNT} must become when stored. */
    private static final BigDecimal NEGATIVE_TRUNCATED_AMOUNT = new BigDecimal("-12.34");

    /** A four-character category code whose leading zeros must survive. */
    private static final String PADDED_CATEGORY_CODE = "0005";

    /** The same category code with its zeros removed, which must not be equal to the padded form. */
    private static final String UNPADDED_CATEGORY_CODE = "5";

    /** A nine-character merchant identifier outside the single value the seed carries. */
    private static final String MERCHANT_IDENTIFIER = "800000123";

    /**
     * The page size the paged read uses.
     *
     * <p>Chosen by this specification rather than taken from anywhere: the table has no screen and
     * therefore no legacy row count, and no page size is hard coded into production code. It divides
     * the fixture unevenly on purpose, so the final page is a short page and the page count has to be
     * rounded up rather than divided out.
     */
    private static final int PAGE_SIZE = 40;

    /** Pages the fixture occupies at {@link #PAGE_SIZE}: seven full pages and one short one. */
    private static final int EXPECTED_PAGE_COUNT = 8;

    /** Rows on the final, short page. */
    private static final int EXPECTED_LAST_PAGE_SIZE = 20;

    /**
     * The bound the bounded-cursor read is given.
     *
     * <p>Owned by the caller rather than by the repository, which is the point: the one declared finder
     * takes its limit as an argument and hard codes none.
     */
    private static final int CURSOR_LIMIT = 3;

    /** Counts the columns of one table that carry one name. */
    private static final String COLUMN_PRESENCE_SQL = """
            SELECT count(*) FROM information_schema.columns
             WHERE table_schema = 'public' AND table_name = ? AND column_name = ?
            """;

    /** Projects the columns of one table in declaration order. */
    private static final String COLUMN_ORDER_SQL = """
            SELECT column_name FROM information_schema.columns
             WHERE table_schema = 'public' AND table_name = ?
             ORDER BY ordinal_position
            """;

    /** Reads the declared width of one character column. */
    private static final String CHARACTER_WIDTH_SQL = """
            SELECT character_maximum_length FROM information_schema.columns
             WHERE table_schema = 'public' AND table_name = ? AND column_name = ?
            """;

    /** Reads the declared total digit count of one numeric column. */
    private static final String NUMERIC_PRECISION_SQL = """
            SELECT numeric_precision FROM information_schema.columns
             WHERE table_schema = 'public' AND table_name = ? AND column_name = ?
            """;

    /** Reads the declared fraction digit count of one numeric column. */
    private static final String NUMERIC_SCALE_SQL = """
            SELECT numeric_scale FROM information_schema.columns
             WHERE table_schema = 'public' AND table_name = ? AND column_name = ?
            """;

    /**
     * Counts the constraints of one kind that one table carries.
     *
     * <p>Read from the constraint catalogue rather than from the information schema deliberately: the
     * information schema projects each not-null column as a check constraint of its own, so a count
     * taken there would report thirteen checks on a table that declares none, and the assertion would
     * be about the wrong thing.
     */
    private static final String CONSTRAINT_KIND_COUNT_SQL = """
            SELECT count(*) FROM pg_constraint c
              JOIN pg_class t ON t.oid = c.conrelid
              JOIN pg_namespace n ON n.oid = t.relnamespace
             WHERE n.nspname = 'public' AND t.relname = ? AND c.contype = ?
            """;

    /** Projects the constraint kinds one table carries, so the whole set can be asserted at once. */
    private static final String CONSTRAINT_KINDS_SQL = """
            SELECT DISTINCT c.contype::text FROM pg_constraint c
              JOIN pg_class t ON t.oid = c.conrelid
              JOIN pg_namespace n ON n.oid = t.relnamespace
             WHERE n.nspname = 'public' AND t.relname = ?
            """;

    /** Counts the constraints held elsewhere that point at one table. */
    private static final String INBOUND_REFERENCE_COUNT_SQL = """
            SELECT count(*) FROM pg_constraint c
              JOIN pg_class t ON t.oid = c.confrelid
              JOIN pg_namespace n ON n.oid = t.relnamespace
             WHERE n.nspname = 'public' AND t.relname = ?
            """;

    /** Projects every index one table carries, the primary key's own index included. */
    private static final String INDEX_NAMES_SQL = """
            SELECT indexname FROM pg_indexes
             WHERE schemaname = 'public' AND tablename = ?
             ORDER BY indexname
            """;

    /** Counts the card master rows carrying one card number. */
    private static final String CARD_PRESENCE_SQL = """
            SELECT count(*) FROM card WHERE card_num = ?
            """;

    /** Counts the cross-reference rows carrying one card number. */
    private static final String CROSS_REFERENCE_PRESENCE_SQL = """
            SELECT count(*) FROM card_cross_reference WHERE xref_card_num = ?
            """;

    /** Counts the account master rows carrying one account identifier. */
    private static final String ACCOUNT_PRESENCE_SQL = """
            SELECT count(*) FROM account WHERE acct_id = ?
            """;

    /** Reads one stored amount straight out of the column, bypassing the persistence context. */
    private static final String STORED_AMOUNT_SQL = """
            SELECT dalytran_amt FROM daily_transaction WHERE dalytran_id = ?
            """;

    /** The surface under test: every assertion exercises an inherited operation or the one finder. */
    @Autowired
    private DailyTransactionRepository repository;

    /** Reads the catalogue, and reads one stored amount without the persistence context in the way. */
    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** Creates the test class. */
    DailyTransactionRepositoryIT() {
    }

    /**
     * The narrowest bootstrap a repository needs: the data source, the template and the persistence
     * provider, with the landing repository and its entity registered by class rather than by package
     * scan so a failure names them.
     *
     * <p>No web environment, no batch orchestration, no messaging and no object store: none of them is
     * involved in reading this table, and importing them would make this specification depend on
     * services it does not use. Schema validation and the connection address arrive from the shared
     * test profile and from the base class respectively, which is what makes a column-name mismatch a
     * refusal to start rather than a silent divergence.
     */
    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration({DataSourceAutoConfiguration.class, JdbcTemplateAutoConfiguration.class,
            HibernateJpaAutoConfiguration.class, TransactionAutoConfiguration.class})
    @EnableJpaRepositories(basePackageClasses = DailyTransactionRepository.class)
    @EntityScan(basePackageClasses = DailyTransaction.class)
    static class RepositoryUnderTest {

        /** Creates the configuration. */
        RepositoryUnderTest() {
        }
    }

    @Nested
    @DisplayName("the mapped schema, every column under the landing prefix")
    final class TheMappedSchema {

        /** Creates the nest. */
        TheMappedSchema() {
        }

        @Test
        @DisplayName("the context refreshed under schema validation, so the entity and the migrated "
                + "table agree on all thirteen column names and types")
        void theContextRefreshedUnderSchemaValidation() {
            assertThat(repository)
                    .as("schema validation is active on the test profile, so a context that started at "
                            + "all is a context whose mapping the server accepted")
                    .isNotNull();
            assertThat(jdbcTemplate).isNotNull();
            assertThatCode(() -> repository.findById(LOWEST_SEEDED_KEY))
                    .as("a keyed read over the mapped entity issues no failing statement")
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("all thirteen record-layout fields map, in layout order, to columns that each "
                + "carry the landing prefix - the four merchant columns included")
        void allThirteenFieldsMapToPrefixedColumnsInLayoutOrder() {
            final List<FieldSpec> fields = TestDataFactory.DAILY_TRANSACTION.fields();
            final List<String> expected = fields.stream()
                    .map(DailyTransactionRepositoryIT::columnNameOf)
                    .toList();

            assertThat(expected)
                    .as("the layout declares thirteen persisted fields")
                    .hasSize(13);
            assertThat(expected)
                    .as("every column of this table carries the landing prefix, which is the one "
                            + "respect in which it differs from the byte-identical posted table")
                    .allSatisfy(column -> assertThat(column).startsWith(COLUMN_PREFIX));
            assertThat(liveColumnsInOrder())
                    .as("the migrated table carries exactly those columns, in the record-image order "
                            + "the layout declares")
                    .containsExactlyElementsOf(expected);
        }

        @Test
        @DisplayName("no bare, unprefixed form of any of the thirteen column names exists on this "
                + "table, which is what keeps the two mirror-image layouts apart")
        void noUnprefixedFormOfAnyColumnExistsHere() {
            for (final FieldSpec field : TestDataFactory.DAILY_TRANSACTION.fields()) {
                final String bareName = columnNameOf(field).substring(COLUMN_PREFIX.length());

                assertThat(columnCount(bareName))
                        .as("the unprefixed name belongs to the posted table alone, so finding it here "
                                + "would mean the landing entity had borrowed that table's mapping")
                        .isZero();
            }
        }

        @Test
        @DisplayName("each character column is declared at exactly the width its layout field occupies")
        void eachCharacterColumnMatchesItsLayoutWidth() {
            for (final FieldSpec field : TestDataFactory.DAILY_TRANSACTION.fields()) {
                final String column = columnNameOf(field);
                if (AMOUNT_COLUMN.equals(column)) {
                    continue;
                }

                assertThat(characterWidthOf(column))
                        .as("column %s holds the %d bytes its field occupies in the record image",
                                column, field.width())
                        .isEqualTo(field.width());
            }
        }

        @Test
        @DisplayName("the amount column is declared with nine digits before the point and two after, "
                + "which is the layout width split at the implied decimal")
        void theAmountColumnCarriesTheLayoutPrecisionAndScale() {
            final FieldSpec amountField = layoutField(AMOUNT_FIELD);

            assertThat(numericPrecisionOf(AMOUNT_COLUMN))
                    .as("total digits equal the field width, so no digit of the legacy field is lost")
                    .isEqualTo(amountField.width());
            assertThat(numericScaleOf(AMOUNT_COLUMN))
                    .as("two fraction digits, matching the implied decimal position of the layout")
                    .isEqualTo(TestDataFactory.MONETARY_SCALE);
            assertThat(amountField.width() - TestDataFactory.MONETARY_SCALE)
                    .as("which leaves nine digits before the point")
                    .isEqualTo(AMOUNT_INTEGER_DIGITS);
        }

        @Test
        @DisplayName("the mapped bytes stop before the trailing filler, so the filler is not a column")
        void theMappedBytesStopBeforeTheTrailingFiller() {
            assertThat(TestDataFactory.DAILY_TRANSACTION.recordLength())
                    .as("the record image is three hundred and fifty bytes")
                    .isEqualTo(RECORD_BYTES);
            assertThat(TestDataFactory.DAILY_TRANSACTION.dataLength())
                    .as("thirteen persisted fields occupy the first three hundred and thirty bytes")
                    .isEqualTo(MAPPED_BYTES);
            assertThat(TestDataFactory.DAILY_TRANSACTION.fillerLength())
                    .as("the remaining twenty bytes are trailing filler and are mapped to nothing")
                    .isEqualTo(FILLER_BYTES);
            assertThat(liveColumnsInOrder())
                    .as("so the table carries thirteen columns and not fourteen")
                    .hasSize(TestDataFactory.DAILY_TRANSACTION.fields().size());
        }

        @Test
        @DisplayName("the layout fields are contiguous from offset zero, so the offsets the mappers "
                + "slice by are the offsets the columns were derived from")
        void theLayoutFieldsAreContiguousFromZero() {
            int cursor = 0;
            for (final FieldSpec field : TestDataFactory.DAILY_TRANSACTION.fields()) {
                assertThat(field.offset())
                        .as("field %s begins where the preceding field ended", field.cobolName())
                        .isEqualTo(cursor);
                cursor = field.endOffset();
            }

            assertThat(cursor)
                    .as("and the last field ends at the mapped-byte boundary")
                    .isEqualTo(MAPPED_BYTES);
        }
    }

    /**
     * The deliberate absence of every referential and structural constraint beyond the primary key.
     *
     * <p>Nothing in this nest may be relaxed, and nothing it asserts absent may be added. The landing
     * table is the raw, unvalidated surface the sequential daily input arrives on: a row naming a card
     * or an account that exists nowhere has to be <em>storable</em>, because storing it is the only
     * route to the validation cascade that rejects it, and rejection is the only route to the
     * four-hundred-and-thirty byte reject record and to the five reject reason codes. A foreign key
     * here would refuse that row at insert time and delete the very case the posting job exists to
     * report.
     */
    @Nested
    @DisplayName("the deliberate absence of referential constraints")
    final class TheDeliberateAbsenceOfConstraints {

        /** Creates the nest. */
        TheDeliberateAbsenceOfConstraints() {
        }

        @Test
        @DisplayName("the table carries its primary key and nothing else - no foreign key, no check "
                + "constraint and no unique constraint")
        void theTableCarriesItsPrimaryKeyAndNothingElse() {
            assertThat(constraintKindCount('f'))
                    .as("a foreign key would refuse a row whose card or account is absent, and that "
                            + "row is the only route to the invalid-card and account-not-found reasons")
                    .isZero();
            assertThat(constraintKindCount('c'))
                    .as("a check constraint would refuse a malformed value that the validation cascade "
                            + "is supposed to report with a reason code")
                    .isZero();
            assertThat(constraintKindCount('u'))
                    .as("no uniqueness beyond the business key is declared")
                    .isZero();
            assertThat(constraintKindCount('p'))
                    .as("the business key is the one constraint the table does carry")
                    .isEqualTo(1);
            assertThat(constraintKinds())
                    .as("so the whole constraint vocabulary of this table is the primary key")
                    .containsExactly("p");
        }

        @Test
        @DisplayName("nothing anywhere in the schema points at the landing table either")
        void nothingPointsAtTheLandingTable() {
            assertThat(inboundReferenceCount())
                    .as("the landing table is a terminus: no other table's constraint depends on a row "
                            + "of it existing, so a landing row can be replaced or removed freely")
                    .isZero();
        }

        @Test
        @DisplayName("the table carries no secondary index: the primary key's own index is the only one")
        void theTableCarriesNoSecondaryIndex() {
            final List<String> indexes = indexNames();

            assertThat(indexes)
                    .as("the batch tier reads this table sequentially and by business key alone, so no "
                            + "alternate access path was migrated and none is indexed")
                    .hasSize(1);
            assertThat(indexes.get(0))
                    .as("and the single index is the one the primary key implies")
                    .contains(LANDING_TABLE);
        }

        @Test
        @Transactional
        @DisplayName("a row naming a card and an account that exist in no row is ACCEPTED, which is "
                + "what keeps all five reject reason codes reachable")
        void aRowNamingNoExistingCardOrAccountIsAccepted() {
            final String danglingCard = TestDataFactory.UNKNOWN_CARD_NUMBER;
            final String danglingAccount = TestDataFactory.UNKNOWN_ACCOUNT_ID;

            assertThat(cardMasterCount(danglingCard))
                    .as("the card master holds no such card, so the reference genuinely dangles")
                    .isZero();
            assertThat(crossReferenceCount(danglingCard))
                    .as("and no cross-reference resolves it to an account either")
                    .isZero();
            assertThat(accountMasterCount(danglingAccount))
                    .as("and the account master holds no such account")
                    .isZero();

            final DailyTransaction dangling = TestDataFactory.orphanDailyTransaction()
                    .id(DANGLING_KEY)
                    .build();

            assertThatCode(() -> repository.saveAndFlush(dangling))
                    .as("the insert reaches the server and is accepted: this is the assertion that "
                            + "proves no foreign key stands between the input and the reject writer")
                    .doesNotThrowAnyException();

            assertThat(repository.count())
                    .as("the row landed alongside the three hundred seeded ones")
                    .isEqualTo(TestDataFactory.SEEDED_DAILY_TRANSACTION_COUNT + 1L);

            final Optional<DailyTransaction> reloaded = repository.findById(DANGLING_KEY);
            assertThat(reloaded).isPresent();
            assertThat(reloaded.orElseThrow().getDalytranCardNum())
                    .as("and it kept the reference that resolves to nothing, unchanged")
                    .isEqualTo(danglingCard);
        }

        @Test
        @DisplayName("every reject reason code the contract recognises is a distinct four-digit value, "
                + "so the eighty-byte trailer can carry any of them")
        void everyRejectReasonCodeFitsTheTrailer() {
            assertThat(TestDataFactory.REJECT_REASON_CODES)
                    .as("the posting program emits five distinct reasons")
                    .hasSize(5)
                    .doesNotContain(TestDataFactory.NO_REJECT_REASON_CODE)
                    .allSatisfy(code -> assertThat(code)
                            .as("a reason code occupies four digits")
                            .isBetween(0, 9999));
            assertThat(TestDataFactory.REJECT_RECORD_WIDTH)
                    .as("the reject record is the landing image followed by the validation trailer")
                    .isEqualTo(RECORD_BYTES + TestDataFactory.REJECT_REASON_CODE_WIDTH
                            + TestDataFactory.REJECT_DESCRIPTION_WIDTH);
        }
    }

    /**
     * The measured composition of the seeded fixture, and the one thing that composition cannot prove.
     *
     * <p>Every figure asserted here was measured from the reference data set rather than assumed: three
     * hundred records of three hundred and fifty bytes, split two hundred and fifty to fifty between
     * the two sources with matching signs, one single origination instant throughout, and a processing
     * timestamp that is blank at its full width on every row.
     *
     * <p>The last two of those facts are why <strong>no processing-date-window test appears anywhere in
     * this class</strong>. A window over an all-identical origination value selects everything and a
     * window over a blank processing value selects nothing; either way the filter would be untested
     * while the test passed. That behaviour belongs to the posted table's specification, over rows
     * built for it.
     */
    @Nested
    @DisplayName("the measured three-hundred-row seed")
    final class TheSeededComposition {

        /** Creates the nest. */
        TheSeededComposition() {
        }

        @Test
        @DisplayName("the seed holds exactly three hundred rows, one per record of the reference data "
                + "set")
        void theSeedHoldsExactlyThreeHundredRows() {
            assertThat(repository.count())
                    .as("three hundred records of three hundred and fifty bytes, seeded one for one")
                    .isEqualTo(TestDataFactory.SEEDED_DAILY_TRANSACTION_COUNT);
            assertThat(seededRows())
                    .as("and every one of them loads through the mapped entity")
                    .hasSize(TestDataFactory.SEEDED_DAILY_TRANSACTION_COUNT);
        }

        @Test
        @DisplayName("the composition is two hundred and fifty point-of-sale rows with positive amounts "
                + "and fifty operator rows with negative amounts, so both signed directions are real")
        void theCompositionCarriesBothSignedDirections() {
            final List<DailyTransaction> rows = seededRows();

            assertThat(rows.stream().filter(row -> PURCHASE_SOURCE.equals(row.getDalytranSource()))
                    .filter(row -> row.getDalytranAmt().signum() > 0)
                    .count())
                    .as("the purchases, which drive the debit direction of the balance computation")
                    .isEqualTo(TestDataFactory.SEEDED_PURCHASE_COUNT);
            assertThat(rows.stream().filter(row -> RETURN_SOURCE.equals(row.getDalytranSource()))
                    .filter(row -> row.getDalytranAmt().signum() < 0)
                    .count())
                    .as("the returns, which drive the credit direction")
                    .isEqualTo(TestDataFactory.SEEDED_RETURN_COUNT);
            assertThat(TestDataFactory.SEEDED_PURCHASE_COUNT + TestDataFactory.SEEDED_RETURN_COUNT)
                    .as("and between them they account for the whole fixture, so no row is unsourced")
                    .isEqualTo(TestDataFactory.SEEDED_DAILY_TRANSACTION_COUNT);
        }

        @Test
        @DisplayName("the source is stored in exactly the form the seed writes, which is the "
                + "right-trimmed form and not the padded layout width")
        void theSourceIsStoredExactlyAsTheSeedWritesIt() {
            final List<String> sources = seededRows().stream()
                    .map(DailyTransaction::getDalytranSource)
                    .distinct()
                    .sorted()
                    .toList();

            assertThat(sources)
                    .as("two source values across the whole fixture, asserted as the seed stores them "
                            + "rather than as the layout pads them")
                    .containsExactly(RETURN_SOURCE, PURCHASE_SOURCE);
            assertThat(sources)
                    .as("the column is a bounded variable-width type, so a stored value is shorter than "
                            + "the layout field it came from and nothing is trimmed on the way out")
                    .allSatisfy(source -> assertThat(source.length())
                            .isLessThan(layoutField("DALYTRAN-SOURCE").width()));
        }

        @Test
        @DisplayName("every one of the three hundred origination timestamps is one and the same "
                + "twenty-six character value, so exactly one distinct value exists")
        void everyOriginationTimestampIsTheSameValue() {
            final List<String> distinct = seededRows().stream()
                    .map(DailyTransaction::getDalytranOrigTs)
                    .distinct()
                    .toList();

            assertThat(distinct)
                    .as("one distinct origination instant across the whole fixture - the measured fact "
                            + "that makes an origination-date window meaningless over seeded data")
                    .hasSize(1);
            assertThat(distinct.get(0))
                    .as("and it is the fixture instant the pinned clock is anchored to")
                    .isEqualTo(TestDataFactory.SEEDED_ORIGINAL_TIMESTAMP)
                    .hasSize(TestDataFactory.TIMESTAMP_TEXT_WIDTH);
        }

        @Test
        @DisplayName("every one of the three hundred processing timestamps is blank at its full "
                + "twenty-six character width, because the posting job writes it and the input does not")
        void everyProcessingTimestampIsBlankAtFullWidth() {
            assertThat(seededRows())
                    .allSatisfy(row -> {
                        assertThat(row.getDalytranProcTs())
                                .as("the field occupies its full width even while carrying no value")
                                .hasSize(TestDataFactory.TIMESTAMP_TEXT_WIDTH)
                                .isEqualTo(TestDataFactory.BLANK_PROCESSING_TIMESTAMP);
                        assertThat(row.getDalytranProcTs().isBlank())
                                .as("and it is blank throughout rather than merely short")
                                .isTrue();
                    });
            assertThat(blankProcessingTimestampCount())
                    .as("the server agrees, counted straight from the column")
                    .isEqualTo(TestDataFactory.SEEDED_DAILY_TRANSACTION_COUNT);
        }

        @Test
        @DisplayName("the seeded type codes and category codes keep their zero-filled widths, so a "
                + "narrower spelling of the same value is a different value")
        void theSeededCodesKeepTheirZeroFilledWidths() {
            assertThat(seededRows())
                    .allSatisfy(row -> {
                        assertThat(row.getDalytranTypeCd())
                                .hasSize(layoutField("DALYTRAN-TYPE-CD").width());
                        assertThat(row.getDalytranCatCd())
                                .hasSize(layoutField("DALYTRAN-CAT-CD").width());
                        assertThat(row.getDalytranMerchantId())
                                .hasSize(layoutField("DALYTRAN-MERCHANT-ID").width());
                        assertThat(row.getDalytranId())
                                .hasSize(layoutField("DALYTRAN-ID").width());
                    });
        }
    }

    /**
     * Decimal fidelity of the stored amount: exact scale, preserved sign, plain rendering, the full
     * nine integer digits, and truncation toward zero rather than rounding.
     *
     * <p>Scale is asserted with an equality that is scale sensitive, deliberately: a comparison that
     * ignored scale would accept the very drift this nest exists to catch. The truncation assertions
     * use a third decimal digit that a half-up or half-even policy would carry into the hundredths, so
     * they fail under any rounding policy other than the truncating one the estate's total absence of a
     * rounding directive requires.
     */
    @Nested
    @DisplayName("the stored amount: exact scale, preserved sign, truncation toward zero")
    final class TheStoredAmount {

        /** Creates the nest. */
        TheStoredAmount() {
        }

        @Test
        @DisplayName("every one of the three hundred seeded amounts loads at scale two exactly, with no "
                + "scale drift in either direction")
        void everySeededAmountLoadsAtScaleTwo() {
            assertThat(seededRows())
                    .allSatisfy(row -> assertThat(row.getDalytranAmt().scale())
                            .as("the amount is a fixed-point decimal of two fraction digits, never a "
                                    + "binary approximation and never a rescaled one")
                            .isEqualTo(TestDataFactory.MONETARY_SCALE));
        }

        @Test
        @DisplayName("the fifty operator-sourced amounts keep their negative sign, which the legacy "
                + "representation folded into the final byte of the field")
        void theFiftyReturnsKeepTheirNegativeSign() {
            final List<DailyTransaction> negatives = seededRows().stream()
                    .filter(row -> row.getDalytranAmt().signum() < 0)
                    .toList();

            assertThat(negatives)
                    .as("fifty negative amounts survived the decode of the overpunched sign")
                    .hasSize(TestDataFactory.SEEDED_RETURN_COUNT);
            assertThat(negatives)
                    .allSatisfy(row -> {
                        assertThat(row.getDalytranSource())
                                .as("and every one of them is operator sourced")
                                .isEqualTo(RETURN_SOURCE);
                        assertThat(row.getDalytranAmt().scale())
                                .as("a negative amount is no less exact than a positive one")
                                .isEqualTo(TestDataFactory.MONETARY_SCALE);
                    });
        }

        @Test
        @DisplayName("no amount renders in scientific notation, so a plain rendering of any of them is "
                + "safe to place in a fixed-width record")
        void noAmountRendersInScientificNotation() {
            assertThat(seededRows())
                    .allSatisfy(row -> {
                        final String plain = row.getDalytranAmt().toPlainString();
                        assertThat(plain)
                                .as("an exponent marker in a fixed-width amount field would be a "
                                        + "byte-parity failure the moment the record was written")
                                .doesNotContain("E")
                                .doesNotContain("e")
                                .contains(".");
                        assertThat(plain.length() - plain.indexOf('.') - 1)
                                .as("and the plain rendering carries exactly two fraction digits")
                                .isEqualTo(TestDataFactory.MONETARY_SCALE);
                    });
        }

        @Test
        @Transactional
        @DisplayName("an amount using all nine integer digits persists and reloads without loss, "
                + "overflow or rescaling")
        void theWidestAmountSurvivesTheRoundTrip() {
            assertThat(WIDEST_AMOUNT.precision() - WIDEST_AMOUNT.scale())
                    .as("the probe genuinely uses every digit the column holds before the point")
                    .isEqualTo(AMOUNT_INTEGER_DIGITS);

            repository.saveAndFlush(TestDataFactory.dailyTransaction()
                    .id(WIDEST_AMOUNT_KEY)
                    .amount(WIDEST_AMOUNT)
                    .build());
            final BigDecimal stored = storedAmountOf(WIDEST_AMOUNT_KEY);

            assertThat(stored)
                    .as("the server stored the widest value the column admits, digit for digit")
                    .isEqualTo(WIDEST_AMOUNT);
            assertThat(stored.scale())
                    .as("at the declared scale, so nothing was rescaled on the way in")
                    .isEqualTo(TestDataFactory.MONETARY_SCALE);
            assertThat(stored.toPlainString())
                    .as("rendered plainly, never as an exponent")
                    .isEqualTo(WIDEST_AMOUNT.toPlainString());
            assertThat(repository.findById(WIDEST_AMOUNT_KEY).orElseThrow().getDalytranAmt())
                    .as("and the entity reads it back unchanged")
                    .isEqualTo(WIDEST_AMOUNT);
        }

        @Test
        @Transactional
        @DisplayName("a positive amount carrying a third decimal digit is TRUNCATED toward zero on the "
                + "way in and is never rounded up")
        void aPositiveThirdDecimalDigitIsTruncatedAndNotRounded() {
            final DailyTransaction row = TestDataFactory.dailyTransaction()
                    .id(POSITIVE_TRUNCATION_KEY)
                    .build();
            row.setDalytranAmt(POSITIVE_THREE_DECIMAL_AMOUNT);

            repository.saveAndFlush(row);
            final BigDecimal stored = storedAmountOf(POSITIVE_TRUNCATION_KEY);

            assertThat(stored)
                    .as("the estate declares no rounding anywhere, so a store into a two-decimal field "
                            + "discards the third digit rather than carrying it")
                    .isEqualTo(POSITIVE_TRUNCATED_AMOUNT);
            assertThat(stored.scale())
                    .isEqualTo(TestDataFactory.MONETARY_SCALE);
            assertThat(stored)
                    .as("a policy that rounded to nearest would have produced one more hundredth, which "
                            + "is the discrepancy this probe exists to catch")
                    .isLessThan(POSITIVE_THREE_DECIMAL_AMOUNT);
        }

        @Test
        @Transactional
        @DisplayName("a negative amount carrying a third decimal digit is truncated TOWARD ZERO, so it "
                + "moves up rather than away from zero")
        void aNegativeThirdDecimalDigitIsTruncatedTowardZero() {
            final DailyTransaction row = TestDataFactory.dailyTransaction()
                    .id(NEGATIVE_TRUNCATION_KEY)
                    .build();
            row.setDalytranAmt(NEGATIVE_THREE_DECIMAL_AMOUNT);

            repository.saveAndFlush(row);
            final BigDecimal stored = storedAmountOf(NEGATIVE_TRUNCATION_KEY);

            assertThat(stored)
                    .as("truncation is toward zero on both sides of it, which is what makes the sign "
                            + "irrelevant to the policy and the policy uniform across the estate")
                    .isEqualTo(NEGATIVE_TRUNCATED_AMOUNT);
            assertThat(stored)
                    .as("and the stored value is nearer zero than the supplied one, not further from it")
                    .isGreaterThan(NEGATIVE_THREE_DECIMAL_AMOUNT);
            assertThat(stored.signum())
                    .as("while remaining negative")
                    .isEqualTo(-1);
        }
    }

    /**
     * Sequential-access semantics: ordering and paging over inherited operations only.
     *
     * <p>The legacy data set is sequential, so there is no positioned browse to reproduce - but order
     * is still behavioural rather than incidental, and every assertion here is made against an
     * <strong>ordered list</strong> and never against a set. The ascending case is the one the
     * consolidation job depends on: it merges two concatenated inputs ordered by transaction
     * identifier ascending, so an unordered read would silently change the merged output. The
     * descending case is asserted because a repository that honoured only one direction would satisfy
     * the ascending assertion while being wrong.
     *
     * <p>The page size is chosen by this specification. The table drives no screen, so it has no
     * legacy row count, and no page size is hard coded into production code - the caller supplies one.
     */
    @Nested
    @DisplayName("sequential access: ordering and caller-paged reads")
    final class SequentialAccessOrderingAndPaging {

        /** Creates the nest. */
        SequentialAccessOrderingAndPaging() {
        }

        @Test
        @DisplayName("an ascending sort returns the whole fixture in ascending business-key order, "
                + "which is the order the consolidation merge depends on")
        void anAscendingSortReturnsAscendingKeyOrder() {
            final List<String> ascending = keysSortedBy(Sort.Direction.ASC);

            assertThat(ascending)
                    .as("every seeded row is returned, once")
                    .hasSize(TestDataFactory.SEEDED_DAILY_TRANSACTION_COUNT)
                    .doesNotHaveDuplicates();
            assertThat(ascending)
                    .as("in ascending order, asserted as a sequence and never as a set")
                    .isSorted();
            assertThat(ascending.get(0))
                    .as("so the first row is the lowest seeded identifier")
                    .isEqualTo(LOWEST_SEEDED_KEY);
            assertThat(ascending.get(ascending.size() - 1))
                    .as("and the last is the highest")
                    .isEqualTo(HIGHEST_SEEDED_KEY);
        }

        @Test
        @DisplayName("a descending sort returns the same rows in exactly the reverse order, so both "
                + "fill directions are honoured rather than only one")
        void aDescendingSortReturnsTheReverseOrder() {
            final List<String> ascending = keysSortedBy(Sort.Direction.ASC);
            final List<String> descending = keysSortedBy(Sort.Direction.DESC);

            assertThat(descending)
                    .as("the reverse of the ascending read, element for element")
                    .containsExactlyElementsOf(ascending.reversed());
            assertThat(descending.get(0))
                    .as("so a descending read starts at the highest seeded identifier")
                    .isEqualTo(HIGHEST_SEEDED_KEY);
        }

        @Test
        @DisplayName("a caller-supplied page size pages the whole fixture, reporting the right total "
                + "row count and the right page count including a short final page")
        void aCallerSuppliedPageSizePagesTheWholeFixture() {
            final List<String> ascending = keysSortedBy(Sort.Direction.ASC);
            final PageRequest firstRequest =
                    PageRequest.of(0, PAGE_SIZE, Sort.by(Sort.Direction.ASC, KEY_PROPERTY));
            final Page<DailyTransaction> first = repository.findAll(firstRequest);

            assertThat(first.getTotalElements())
                    .as("the total is the whole fixture, not the page")
                    .isEqualTo(TestDataFactory.SEEDED_DAILY_TRANSACTION_COUNT);
            assertThat(first.getTotalPages())
                    .as("the page count is rounded up, because the size divides the fixture unevenly")
                    .isEqualTo(EXPECTED_PAGE_COUNT);
            assertThat(first.getNumberOfElements())
                    .as("a full first page")
                    .isEqualTo(PAGE_SIZE);
            assertThat(first.isFirst()).isTrue();
            assertThat(first.isLast()).isFalse();
            assertThat(keysOf(first))
                    .as("holding the first rows of the ascending sequence, in that sequence")
                    .containsExactlyElementsOf(ascending.subList(0, PAGE_SIZE));

            final Page<DailyTransaction> last =
                    repository.findAll(firstRequest.withPage(EXPECTED_PAGE_COUNT - 1));

            assertThat(last.getNumberOfElements())
                    .as("and the final page is the short one the uneven division implies")
                    .isEqualTo(EXPECTED_LAST_PAGE_SIZE);
            assertThat(last.isLast()).isTrue();
            assertThat(keysOf(last))
                    .as("carrying the tail of the same ascending sequence")
                    .containsExactlyElementsOf(ascending.subList(
                            TestDataFactory.SEEDED_DAILY_TRANSACTION_COUNT - EXPECTED_LAST_PAGE_SIZE,
                            TestDataFactory.SEEDED_DAILY_TRANSACTION_COUNT));
        }

        @Test
        @DisplayName("paging the whole fixture visits every row exactly once, with no row repeated "
                + "across a page boundary and none skipped at one")
        void pagingVisitsEveryRowExactlyOnce() {
            final PageRequest request =
                    PageRequest.of(0, PAGE_SIZE, Sort.by(Sort.Direction.ASC, KEY_PROPERTY));
            final List<String> visited = new ArrayList<>();
            for (int pageNumber = 0; pageNumber < EXPECTED_PAGE_COUNT; pageNumber++) {
                visited.addAll(keysOf(repository.findAll(request.withPage(pageNumber))));
            }

            assertThat(visited)
                    .as("a boundary that repeated or dropped a row would change what the batch tier "
                            + "posts, so the paged walk is asserted to be a partition of the fixture")
                    .hasSize(TestDataFactory.SEEDED_DAILY_TRANSACTION_COUNT)
                    .doesNotHaveDuplicates()
                    .containsExactlyElementsOf(keysSortedBy(Sort.Direction.ASC));
        }

        @Test
        @DisplayName("the one declared bounded cursor excludes its own key and stays inside the limit "
                + "the caller owns, so a sequential pass makes strict progress")
        void theBoundedCursorExcludesItsKeyAndHonoursTheCallerLimit() {
            final List<String> ascending = keysSortedBy(Sort.Direction.ASC);
            final String cursor = ascending.get(0);
            final List<DailyTransaction> afterCursor =
                    repository.findByDalytranIdGreaterThanOrderByDalytranIdAsc(
                            cursor, Limit.of(CURSOR_LIMIT));

            assertThat(keysOf(afterCursor))
                    .as("strictly after the cursor and bounded by the caller's limit")
                    .containsExactlyElementsOf(ascending.subList(1, 1 + CURSOR_LIMIT));
            assertThat(keysOf(afterCursor))
                    .allSatisfy(key -> assertThat(key).isGreaterThan(cursor));
        }
    }

    /**
     * The business key, the significance of its leading zeros, and the survival of all thirteen
     * properties across a write and a read.
     *
     * <p>The identifier is the business key taken verbatim from the leading bytes of the record image.
     * The schema creates no sequence, no serial and no generated column, and none may be added: a
     * surrogate would break the correspondence between the record image and the table row, and on this
     * table specifically it would make it impossible to echo the original three-hundred-and-fifty byte
     * image into a reject record. Because the key is text rather than a number, its leading zeros are
     * data - which is exactly why the narrower spelling of the same value has to resolve to nothing.
     */
    @Nested
    @DisplayName("the business key and the thirteen-property round trip")
    final class TheBusinessKey {

        /** Creates the nest. */
        TheBusinessKey() {
        }

        @Test
        @DisplayName("a seeded identifier resolves, and an identifier no row carries yields an empty "
                + "result rather than a failure")
        void aSeededIdentifierResolvesAndAnAbsentOneYieldsAnEmptyResult() {
            assertThat(repository.findById(LOWEST_SEEDED_KEY))
                    .as("the lowest seeded identifier resolves to its row")
                    .isPresent()
                    .get()
                    .extracting(DailyTransaction::getDalytranId)
                    .isEqualTo(LOWEST_SEEDED_KEY);
            assertThatCode(() -> repository.findById(ABSENT_KEY))
                    .as("an absent key is a normal outcome of a keyed read, not an error")
                    .doesNotThrowAnyException();
            assertThat(repository.findById(ABSENT_KEY))
                    .as("and it is reported as an empty result")
                    .isEmpty();
            assertThat(repository.existsById(ABSENT_KEY)).isFalse();
        }

        @Test
        @DisplayName("leading zeros are data, so the same value spelled without them resolves to "
                + "nothing at all")
        void leadingZerosAreSignificant() {
            assertThat(LOWEST_SEEDED_KEY)
                    .as("the seeded identifier is stored at its full width, zeros included")
                    .hasSize(layoutField("DALYTRAN-ID").width())
                    .startsWith("0")
                    .endsWith(UNPADDED_KEY);
            assertThat(repository.findById(UNPADDED_KEY))
                    .as("the unpadded spelling is a different key and belongs to no row, which is what "
                            + "a numeric identifier column would have silently collapsed")
                    .isEmpty();
        }

        @Test
        @Transactional
        @DisplayName("an identifier written with leading zeros reloads with them intact and is not "
                + "equal to its unpadded form")
        void anIdentifierWithLeadingZerosSurvivesAWrite() {
            repository.saveAndFlush(TestDataFactory.dailyTransaction()
                    .id(LEADING_ZERO_KEY)
                    .build());

            final Optional<DailyTransaction> reloaded = repository.findById(LEADING_ZERO_KEY);

            assertThat(reloaded).isPresent();
            assertThat(reloaded.orElseThrow().getDalytranId())
                    .as("stored and returned character for character")
                    .isEqualTo(LEADING_ZERO_KEY)
                    .hasSize(layoutField("DALYTRAN-ID").width());
            assertThat(repository.findById(LEADING_ZERO_KEY.replace("0", "")))
                    .as("and the value with its zeros removed is not the same key")
                    .isEmpty();
        }

        @Test
        @Transactional
        @DisplayName("a zero-filled four-character category code keeps its width, so it is not the same "
                + "value as the single digit it contains")
        void aZeroFilledCategoryCodeIsNotItsBareDigit() {
            assertThat(PADDED_CATEGORY_CODE)
                    .as("the codes differ, which is the whole reason the column is text")
                    .isNotEqualTo(UNPADDED_CATEGORY_CODE)
                    .hasSize(layoutField("DALYTRAN-CAT-CD").width());

            repository.saveAndFlush(TestDataFactory.dailyTransaction()
                    .id(ROUND_TRIP_KEY)
                    .categoryCode(PADDED_CATEGORY_CODE)
                    .merchantId(MERCHANT_IDENTIFIER)
                    .build());

            final DailyTransaction reloaded = repository.findById(ROUND_TRIP_KEY).orElseThrow();

            assertThat(reloaded.getDalytranCatCd())
                    .as("the stored code kept its zero fill and was not normalised to a number")
                    .isEqualTo(PADDED_CATEGORY_CODE)
                    .isNotEqualTo(UNPADDED_CATEGORY_CODE);
            assertThat(reloaded.getDalytranMerchantId())
                    .as("and the nine-character merchant identifier kept its own width")
                    .isEqualTo(MERCHANT_IDENTIFIER)
                    .hasSize(layoutField("DALYTRAN-MERCHANT-ID").width());
        }

        @Test
        @Transactional
        @DisplayName("all thirteen persisted properties survive a save and a reload unchanged, so no "
                + "column is dropped, swapped or reshaped by the mapping")
        void allThirteenPropertiesSurviveASaveAndReload() {
            final DailyTransaction written = TestDataFactory.dailyTransaction()
                    .id(ROUND_TRIP_KEY)
                    .categoryCode(PADDED_CATEGORY_CODE)
                    .merchantId(MERCHANT_IDENTIFIER)
                    .build();

            repository.saveAndFlush(written);
            final DailyTransaction reloaded = repository.findById(ROUND_TRIP_KEY).orElseThrow();

            assertThat(reloaded.getDalytranId()).isEqualTo(written.getDalytranId());
            assertThat(reloaded.getDalytranTypeCd()).isEqualTo(written.getDalytranTypeCd());
            assertThat(reloaded.getDalytranCatCd()).isEqualTo(written.getDalytranCatCd());
            assertThat(reloaded.getDalytranSource()).isEqualTo(written.getDalytranSource());
            assertThat(reloaded.getDalytranDesc()).isEqualTo(written.getDalytranDesc());
            assertThat(reloaded.getDalytranAmt())
                    .as("the amount is compared scale sensitively, because scale is the contract")
                    .isEqualTo(written.getDalytranAmt());
            assertThat(reloaded.getDalytranMerchantId()).isEqualTo(written.getDalytranMerchantId());
            assertThat(reloaded.getDalytranMerchantName())
                    .isEqualTo(written.getDalytranMerchantName());
            assertThat(reloaded.getDalytranMerchantCity())
                    .isEqualTo(written.getDalytranMerchantCity());
            assertThat(reloaded.getDalytranMerchantZip()).isEqualTo(written.getDalytranMerchantZip());
            assertThat(reloaded.getDalytranCardNum())
                    .as("compared against the value held by the fixture, never against a literal")
                    .isEqualTo(written.getDalytranCardNum());
            assertThat(reloaded.getDalytranOrigTs()).isEqualTo(written.getDalytranOrigTs());
            assertThat(reloaded.getDalytranProcTs()).isEqualTo(written.getDalytranProcTs());
            assertThat(reloaded)
                    .as("and identity is the business key alone, so the reload is the same record")
                    .isEqualTo(written);
        }

        @Test
        @DisplayName("the seeded rows all carry the business key at its full width, so no row was "
                + "loaded through a numeric identifier that lost a leading zero")
        void everySeededKeyIsStoredAtFullWidth() {
            final int keyWidth = layoutField("DALYTRAN-ID").width();

            assertThat(seededRows())
                    .allSatisfy(row -> assertThat(row.getDalytranId()).hasSize(keyWidth));
        }
    }

    // =============================================================================================
    // Helpers. Every catalogue read below is a fixed literal statement with bound parameters: no
    // value and no identifier is assembled into a statement, and no native query is issued through
    // the persistence provider.
    // =============================================================================================

    /**
     * Derives the column name a layout field maps to by lower casing its legacy field name and
     * replacing each separator.
     *
     * <p>Derived rather than restated so that the expectation cannot drift from the layout, and so that
     * the prefixed spelling of all thirteen names - the four merchant names included - is produced by
     * one rule rather than by thirteen literals a maintainer could get individually wrong.
     *
     * <p>The fold is root locale, not default locale: a default-locale fold is wrong under a locale
     * whose case mapping differs, and the column names are protocol rather than prose.
     *
     * @param field the layout field to map; must not be null
     * @return the column name the migrated table declares for that field
     */
    private static String columnNameOf(final FieldSpec field) {
        return field.cobolName().toLowerCase(Locale.ROOT).replace('-', '_');
    }

    /**
     * Finds one field of the record layout by its legacy field name, so a width can be asserted against
     * the layout rather than against a number restated at the assertion.
     *
     * <p>Looked up by <em>layout</em> field name rather than by column name deliberately. The layout
     * name is the authority the column was derived from, so an assertion that reaches it cannot be
     * satisfied by a column that drifted; and naming the field rather than the column keeps the bare,
     * unprefixed spelling of a column name out of this file entirely, which is the spelling that
     * belongs to the byte-identical posted table and must never appear here.
     *
     * @param cobolName the legacy field name the layout declares
     * @return the layout field carrying that name
     */
    private static FieldSpec layoutField(final String cobolName) {
        return TestDataFactory.DAILY_TRANSACTION.fields().stream()
                .filter(field -> field.cobolName().equals(cobolName))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "the daily-transaction layout declares no field named " + cobolName));
    }

    /** @return every seeded and written row, in ascending business-key order */
    private List<DailyTransaction> seededRows() {
        return repository.findAll(Sort.by(Sort.Direction.ASC, KEY_PROPERTY));
    }

    /**
     * @param direction the direction to read in
     * @return the business keys of every row, in the requested order
     */
    private List<String> keysSortedBy(final Sort.Direction direction) {
        return repository.findAll(Sort.by(direction, KEY_PROPERTY)).stream()
                .map(DailyTransaction::getDalytranId)
                .toList();
    }

    /**
     * @param page one page of rows
     * @return the business keys of that page, in page order
     */
    private static List<String> keysOf(final Page<DailyTransaction> page) {
        return keysOf(page.getContent());
    }

    /**
     * @param rows rows in some order
     * @return their business keys in the same order
     */
    private static List<String> keysOf(final List<DailyTransaction> rows) {
        return rows.stream().map(DailyTransaction::getDalytranId).toList();
    }

    /** @return the landing table's column names, in declaration order */
    private List<String> liveColumnsInOrder() {
        return jdbcTemplate.queryForList(COLUMN_ORDER_SQL, String.class, LANDING_TABLE);
    }

    /**
     * @param column a column name to look for on the landing table
     * @return how many columns of that name the landing table declares, which is zero or one
     */
    private int columnCount(final String column) {
        return requiredCount(COLUMN_PRESENCE_SQL, LANDING_TABLE, column);
    }

    /**
     * @param column a character column of the landing table
     * @return its declared maximum width in characters
     */
    private int characterWidthOf(final String column) {
        return requiredCount(CHARACTER_WIDTH_SQL, LANDING_TABLE, column);
    }

    /**
     * @param column a numeric column of the landing table
     * @return its declared total digit count
     */
    private int numericPrecisionOf(final String column) {
        return requiredCount(NUMERIC_PRECISION_SQL, LANDING_TABLE, column);
    }

    /**
     * @param column a numeric column of the landing table
     * @return its declared fraction digit count
     */
    private int numericScaleOf(final String column) {
        return requiredCount(NUMERIC_SCALE_SQL, LANDING_TABLE, column);
    }

    /**
     * @param kind the single-character constraint kind the catalogue records
     * @return how many constraints of that kind the landing table carries
     */
    private int constraintKindCount(final char kind) {
        return requiredCount(CONSTRAINT_KIND_COUNT_SQL, LANDING_TABLE, String.valueOf(kind));
    }

    /** @return every constraint kind the landing table carries, without repetition */
    private List<String> constraintKinds() {
        return jdbcTemplate.queryForList(CONSTRAINT_KINDS_SQL, String.class, LANDING_TABLE);
    }

    /** @return how many constraints held elsewhere in the schema point at the landing table */
    private int inboundReferenceCount() {
        return requiredCount(INBOUND_REFERENCE_COUNT_SQL, LANDING_TABLE);
    }

    /** @return every index the landing table carries, the primary key's own index included */
    private List<String> indexNames() {
        return jdbcTemplate.queryForList(INDEX_NAMES_SQL, String.class, LANDING_TABLE);
    }

    /**
     * @param cardNumber the card number to look for, held by the caller and never logged
     * @return how many card-master rows carry it
     */
    private int cardMasterCount(final String cardNumber) {
        return requiredCount(CARD_PRESENCE_SQL, cardNumber);
    }

    /**
     * @param cardNumber the card number to look for, held by the caller and never logged
     * @return how many cross-reference rows carry it
     */
    private int crossReferenceCount(final String cardNumber) {
        return requiredCount(CROSS_REFERENCE_PRESENCE_SQL, cardNumber);
    }

    /**
     * @param accountId the account identifier to look for
     * @return how many account-master rows carry it
     */
    private int accountMasterCount(final String accountId) {
        return requiredCount(ACCOUNT_PRESENCE_SQL, accountId);
    }

    /** @return how many landing rows carry a processing timestamp that is blank at its full width */
    private int blankProcessingTimestampCount() {
        return (int) seededRows().stream()
                .filter(row -> row.getDalytranProcTs().length()
                        == TestDataFactory.TIMESTAMP_TEXT_WIDTH)
                .filter(row -> row.getDalytranProcTs().isBlank())
                .count();
    }

    /**
     * Reads one stored amount straight out of the column, so the assertion sees what the server holds
     * rather than what the persistence context remembers.
     *
     * @param key the business key of the row to read
     * @return the amount as the column holds it, at the column's own scale
     */
    private BigDecimal storedAmountOf(final String key) {
        final BigDecimal amount = jdbcTemplate.queryForObject(STORED_AMOUNT_SQL, BigDecimal.class, key);
        if (amount == null) {
            throw new AssertionError("no landing row carries the identifier under test: " + key);
        }
        return amount;
    }

    /**
     * Runs one fixed catalogue statement that projects a single count or width, and fails rather than
     * returning a placeholder when the catalogue has nothing to say.
     *
     * <p>A typed projection is used deliberately, so that no cast of any kind is needed to read the
     * result and the compiler can check the whole path under its strictest settings.
     *
     * @param sql       the literal statement, which carries only bound placeholders
     * @param arguments the values bound to those placeholders
     * @return the projected value
     */
    private int requiredCount(final String sql, final Object... arguments) {
        final Integer value = jdbcTemplate.queryForObject(sql, Integer.class, arguments);
        if (value == null) {
            throw new AssertionError(
                    "the catalogue returned no value for a statement that must always project one");
        }
        return value;
    }
}
