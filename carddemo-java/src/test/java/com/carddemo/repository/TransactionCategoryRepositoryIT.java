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
import static org.assertj.core.api.Assertions.assertThatCode;

import com.carddemo.domain.TransactionCategory;
import com.carddemo.domain.id.TransactionCategoryId;
import com.carddemo.support.AbstractPostgresIT;
import com.carddemo.support.TestDataFactory;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.List;
import java.util.Map;
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
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * Drives the transaction-category reference repository against a real PostgreSQL 16 server carrying the
 * shipped migrations, and pins the one property of it that is easiest to get wrong: the shape and the
 * component order of its two-part composite key.
 *
 * <h2>Why this needs a database rather than a mock</h2>
 * The repository interface declares no method of its own, so everything exercised here is inherited
 * behaviour whose correctness lives in the mapping rather than in this module's code. Three things can
 * only fail against a server. The identifier class has to bind to the entity's two separately annotated
 * key fields, and a name or type mismatch aborts context start-up rather than returning a wrong answer.
 * The mapping is validated against the migrated schema on refresh, so a column that drifted from the
 * migration fails there and nowhere else. And the keyed read has to emit both key predicates in the
 * order the primary key declares, which a stubbed repository would answer identically however the
 * arguments were ordered. A mock would agree with every one of those mistakes.
 *
 * <h2>THE COLLISION THIS CLASS EXISTS TO GUARD - two unrelated keys share one legacy name</h2>
 * The copybook behind this table and the copybook behind the transaction-category-balance table give
 * their key groups <strong>the same identifier</strong>, and the two keys are nonetheless structurally
 * unrelated:
 * <ul>
 *   <li>this table's key is <strong>two</strong> components and <strong>6 bytes</strong> at offset 0 -
 *       a 2-byte type code at offset 0 followed by a 4-byte category code at offset 2;</li>
 *   <li>the category-balance key is <strong>three</strong> components and <strong>17 bytes</strong> at
 *       offset 0, and it <em>leads with an 11-byte account identifier</em> that this key does not
 *       contain at all.</li>
 * </ul>
 * The 6-byte key is therefore <strong>not a prefix, sub-key or reusable fragment</strong> of the
 * 17-byte one: because an account identifier heads the longer form, the two layouts align at no offset
 * whatsoever and share no leading byte. The two copybooks also spell their components differently - one
 * separates the words, the other runs them together - and the migration transcribes both spellings
 * exactly rather than regularising them, so the two tables' key columns are named as differently as
 * their sources are. That is faithful transcription and not an inconsistency to tidy.
 *
 * <p>Conflating the two is the highest-probability mapping error in this package, so this class is
 * deliberately sealed against it: it names only this table's own identifier class and only this table's
 * own column names, it never mentions the other table's identifier class or its column prefix, and the
 * swapped-argument test below fails the moment the component order is disturbed. The decision is
 * recorded as D-37 in {@code docs/decision-log.md}, together with the reason both spellings are
 * preserved rather than unified.
 *
 * <p>A third neighbour is distinct again and is likewise out of reach from here: the transaction-type
 * reference table is also a 60-byte record with a 50-character description, but its key is a single
 * 2-byte column and it has no identifier class at all.
 *
 * <h2>Access-pattern context: this is a batch-only reference cluster</h2>
 * The legacy resource definition registers eight files to the online transaction manager and this
 * cluster is not among them, so no screen ever reached it. Its one consumer is the batch reporting
 * tier, where step {@code STEP10R} of the {@code TRANREPT} procedure supplies the cluster to report
 * program {@code CBTRN03C} through the {@code TRANCATG} data-definition name as one of that job's
 * reference inputs. There is consequently no browse, no page size, no cursor and no alternate index for
 * this table - the index migration defines none touching it - so nothing of the sort is invented here.
 * Read access is the inherited keyed read and the inherited full read, and that is the whole surface.
 *
 * <h2>What the shape assertions are worth</h2>
 * The record is 60 bytes: 6 of key at offset 0, 50 of description at offset 6, and 4 trailing filler
 * bytes at offset 56 that are deliberately <em>not</em> a column. The declared key length of 6 at offset
 * 0 corroborates the two-component key independently of the copybook, and the widths reconcile exactly -
 * 2 + 4 = 6 for the key and 6 + 50 + 4 = 60 for the record. Because the key sits at offset 0 it is the
 * leading substring of the stored image, which is why the identifier is the legacy business key itself
 * and why the schema carries no sequence, no identity column and no surrogate: a surrogate would break
 * the record-image-to-row correspondence that byte-level parity verification depends on. Those
 * properties are asserted against the server's own catalogue rather than assumed.
 *
 * <h2>Values are compared exactly, and never trimmed</h2>
 * Fixed-width padding is significant throughout this schema, so no assertion in this file normalises a
 * stored value in any way. The seeded descriptions are compared against the exact literals the reference
 * seed inserts, and the two key components are compared byte for byte - which is what makes a category
 * code of {@code "0005"} genuinely distinguishable from one that reads as a bare digit.
 *
 * <h2>How this class treats the shared server</h2>
 * The container is owned by {@link AbstractPostgresIT} and is shared by the whole integration tier, so
 * this class declares none of its own and leaves the table exactly as it found it: every read is
 * non-mutating, and the one test that writes is transactional, rolls back, and uses a reserved type code
 * that no seeded row carries. The seeded row count is therefore a stable assertion for any other class
 * in the same run.
 *
 * <h2>Why the context is scoped to {@link RepositoryUnderTest} rather than to the application</h2>
 * The class carries its own {@code @SpringBootTest}, as the shared base requires, and points it at the
 * narrow configuration below rather than letting it discover the delivered application class. That
 * scoping is a correctness requirement here, not a performance choice. The delivered application scans
 * the base package, the integration tier's own nested repository configurations live inside that same
 * package, and several of those enclosing test classes declare no directly annotated test method of their
 * own - which is exactly the shape the framework's test-type exclusion cannot recognise. An
 * application-wide scan therefore registers more than one of those configurations at once, two of them
 * ask for the same repository bean, and the refresh fails on a duplicate definition before a single
 * assertion runs. Naming the configuration keeps this class independent of every neighbour's internals,
 * and it is the same slicing the module's other container-backed specifications already use.
 *
 * <p>The slice is still a real one in every respect that matters here: a real data source pointed at the
 * shared server, the real provider, the whole domain package mapped, the real repository proxy and a real
 * transaction manager. Nothing is mocked and no in-memory engine is substituted.
 *
 * <p>Provenance: the legacy facts cited above were read at checkout
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. That stamp is a provenance string for the
 * traceability matrix header only and is asserted against nothing here. No copybook, job-stream or
 * resource-definition source text is reproduced: the estate is cited by member name, resource name,
 * width, offset and row count alone.
 *
 * @see TransactionCategoryRepository
 * @see TransactionCategory
 * @see TransactionCategoryId
 */
@SpringBootTest(classes = TransactionCategoryRepositoryIT.RepositoryUnderTest.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {"spring.jpa.hibernate.ddl-auto=validate", "spring.jpa.open-in-view=false"})
@DisplayName("Transaction category repository: the 6-byte composite key against the migrated schema")
class TransactionCategoryRepositoryIT extends AbstractPostgresIT {

    /** The table this class reads, named once so every catalogue assertion agrees on the subject. */
    private static final String TABLE_NAME = "transaction_category";

    /** The primary-key constraint the schema migration names. */
    private static final String PRIMARY_KEY_NAME = "pk_transaction_category";

    /** Width of key component 1, the type code at offset 0. */
    private static final int TYPE_CODE_WIDTH = 2;

    /** Width of key component 2, the category code at offset 2. */
    private static final int CATEGORY_CODE_WIDTH = 4;

    /** Width of the composite key at offset 0, which the two component widths must sum to. */
    private static final int KEY_WIDTH = 6;

    /** Width of the description at offset 6, and so the bound the stored column declares. */
    private static final int DESCRIPTION_WIDTH = 50;

    /** Width of the trailing filler at offset 56, which is not persisted and is not a column. */
    private static final int FILLER_WIDTH = 4;

    /** Length of the whole record image, which the mapped widths plus the filler must sum to. */
    private static final int RECORD_LENGTH = 60;

    /**
     * The number of rows the reference seed inserts.
     *
     * <p>Measured rather than guessed: the delivered reference fixture is 1,098 bytes of 60-byte records
     * carrying a one-byte line terminator each, which is 18 records exactly.
     */
    private static final int SEEDED_ROW_COUNT = 18;

    /** The narrowest seeded description, in characters. */
    private static final int NARROWEST_SEEDED_DESCRIPTION = 12;

    /** The widest seeded description, in characters, comfortably inside the declared bound of 50. */
    private static final int WIDEST_SEEDED_DESCRIPTION = 29;

    /** A type code no seeded row carries, reserved so a leaked write could never shadow the seed. */
    private static final String RESERVED_TYPE_CODE = "99";

    /** A category code under the reserved type code, used by the write round trip. */
    private static final String RESERVED_CATEGORY_CODE = "9001";

    /** A category code under the reserved type code that is never stored by anything. */
    private static final String ABSENT_CATEGORY_CODE = "9999";

    /** The description the write round trip stores, chosen to be recognisably synthetic. */
    private static final String RESERVED_DESCRIPTION = "Reserved integration probe row";

    /**
     * The eighteen seeded rows, restated as literals in seed order.
     *
     * <p>Restated rather than derived from the migration or from the fixture on disk, and that is the
     * point of the class: an expectation read back out of the thing it describes moves whenever that
     * thing moves, and would keep passing if a row were renumbered, reworded or dropped.
     */
    private static final List<SeededRow> SEEDED_ROWS = List.of(
            new SeededRow("01", "0001", "Regular Sales Draft"),
            new SeededRow("01", "0002", "Regular Cash Advance"),
            new SeededRow("01", "0003", "Convenience Check Debit"),
            new SeededRow("01", "0004", "ATM Cash Advance"),
            new SeededRow("01", "0005", "Interest Amount"),
            new SeededRow("02", "0001", "Cash payment"),
            new SeededRow("02", "0002", "Electronic payment"),
            new SeededRow("02", "0003", "Check payment"),
            new SeededRow("03", "0001", "Credit to Account"),
            new SeededRow("03", "0002", "Credit to Purchase balance"),
            new SeededRow("03", "0003", "Credit to Cash balance"),
            new SeededRow("04", "0001", "Zero dollar authorization"),
            new SeededRow("04", "0002", "Online purchase authorization"),
            new SeededRow("04", "0003", "Travel booking authorization"),
            new SeededRow("05", "0001", "Refund credit"),
            new SeededRow("06", "0001", "Fraud reversal"),
            new SeededRow("06", "0002", "Non-fraud reversal"),
            new SeededRow("07", "0001", "Sales draft credit adjustment"));

    /** The first seeded row, used wherever one concrete seeded key is needed. */
    private static final SeededRow FIRST_SEEDED_ROW = SEEDED_ROWS.get(0);

    /**
     * The seeded row whose category code carries three leading zeros.
     *
     * <p>It is the reason both key components are stored as text: this code has to survive as four
     * characters, because a value that narrowed to a bare digit would no longer reconstruct the 6-byte
     * key image from its two components.
     */
    private static final SeededRow LEADING_ZERO_ROW = SEEDED_ROWS.get(4);

    /** The repository under test, whose declared surface is empty and whose behaviour is inherited. */
    @Autowired
    private TransactionCategoryRepository repository;

    /** Reads the server's own catalogue, so the schema claims above are observed rather than assumed. */
    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * The transactional persistence context, used by the write round trip alone.
     *
     * <p>It is needed for one narrow reason: inside a single unit of work the keyed read is answered from
     * the persistence context, so a round trip that did not detach first would prove only that the
     * context remembers what it was handed. Clearing it forces the read to reach the server.
     */
    @PersistenceContext
    private EntityManager entityManager;

    /** Creates the test class. */
    TransactionCategoryRepositoryIT() {
    }

    /**
     * The narrowest context this specification needs, and no wider.
     *
     * <p>Four auto-configurations, named explicitly rather than enabled wholesale: the data source, which
     * resolves to the shared server through the properties the base class registers; the template used to
     * read the server's own catalogue; the provider, which brings the entity-manager factory and the
     * transaction manager the repository proxy and the transactional test method both need; and the
     * transaction infrastructure that makes {@code @Transactional} mean what it says. Enabling
     * auto-configuration wholesale would drag in the messaging, security, management and batch tiers, none
     * of which this specification asserts on and every one of which would be a new way for it to fail.
     *
     * <p>The mapping is deliberately scanned for the <em>whole</em> domain package rather than for this
     * one entity, and the mapping posture is validate-only. That is what makes the refresh itself an
     * assertion: every delivered entity - the three composite-key ones included - is checked against the
     * migrated schema, so a drifted column name or width anywhere in the domain fails the class rather
     * than surviving until something happens to read it.
     */
    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration({DataSourceAutoConfiguration.class, JdbcTemplateAutoConfiguration.class,
            HibernateJpaAutoConfiguration.class, TransactionAutoConfiguration.class})
    @EnableJpaRepositories(basePackageClasses = TransactionCategoryRepository.class)
    @EntityScan(basePackageClasses = TransactionCategory.class)
    static class RepositoryUnderTest {

        /** Creates the configuration. */
        RepositoryUnderTest() {
        }
    }

    /**
     * One seeded row, as the reference seed inserts it.
     *
     * @param typeCode     key component 1, the 2-character type code from offset 0
     * @param categoryCode key component 2, the 4-character category code from offset 2
     * @param description  the description from offset 6, exactly as stored
     */
    private record SeededRow(String typeCode, String categoryCode, String description) {

        /**
         * Assembles this row's identifier in contractual component order.
         *
         * @return the composite identifier, type code first
         */
        TransactionCategoryId id() {
            return new TransactionCategoryId(typeCode, categoryCode);
        }

        /**
         * Assembles the 6-byte key image, which is the two components concatenated in key order.
         *
         * @return the six-character key image
         */
        String keyImage() {
            return typeCode + categoryCode;
        }
    }

    @Test
    @DisplayName("the context refreshes, which is what proves the identifier class binds and the mapping "
            + "matches the migrated schema")
    void theContextRefreshesSoTheMappingIsValidatedNotAssumed() {
        assertThat(repository)
                .as("the mapping posture is validate-only over the whole domain package, so a drifted "
                        + "column, a mistyped key field or an identifier class the provider could not "
                        + "bind would have aborted the refresh before this method ran - reaching here at "
                        + "all is the assertion")
                .isNotNull();
        assertThat(jdbcTemplate)
                .as("the catalogue reader is present, so the schema-shape assertions observe the server "
                        + "rather than restating the migration")
                .isNotNull();
        assertThat(entityManager)
                .as("and the transactional persistence context is present, which the write round trip "
                        + "needs in order to detach before it reads back")
                .isNotNull();
        assertThatCode(() -> repository.count())
                .as("and the inherited aggregate has to reach a real table, not a substituted engine")
                .doesNotThrowAnyException();
    }

    /**
     * The seeded volume and the exact content of it.
     *
     * <p>The reference seed is scoped to the non-production profiles, and the shared server is migrated
     * to the head of the delivered set, so these rows are present for every subclass of the shared base.
     * Nothing in the integration tier writes this table, and the one write below rolls back, so the count
     * is stable however the classes are ordered.
     */
    @Nested
    @DisplayName("the reference seed")
    class TheReferenceSeed {

        /** Creates the nested test class. */
        TheReferenceSeed() {
        }

        @Test
        @DisplayName("holds exactly eighteen rows, the measured record count of the delivered fixture")
        void holdsExactlyEighteenRows() {
            assertThat(repository.count())
                    .as("1,098 fixture bytes over 60-byte records with one terminator each is 18 records")
                    .isEqualTo(SEEDED_ROW_COUNT);
            assertThat(repository.findAll())
                    .as("the inherited full read agrees with the aggregate")
                    .hasSize(SEEDED_ROW_COUNT);
        }

        @Test
        @DisplayName("carries eighteen distinct composite keys, so no six-byte key image repeats")
        void carriesEighteenDistinctCompositeKeys() {
            final List<String> keyImages = repository.findAll().stream()
                    .map(row -> row.getTranTypeCd() + row.getTranCatCd())
                    .toList();

            assertThat(keyImages).hasSize(SEEDED_ROW_COUNT);
            assertThat(keyImages)
                    .as("a repeated key image would mean two rows claiming the same six bytes of one "
                            + "record, which the primary key exists to make impossible")
                    .doesNotHaveDuplicates();
            assertThat(keyImages)
                    .as("every key image is exactly the declared key width")
                    .allSatisfy(image -> assertThat(image).hasSize(KEY_WIDTH));
        }

        @Test
        @DisplayName("is exactly the eighteen seeded pairs, in no assumed order")
        void isExactlyTheEighteenSeededPairs() {
            final List<String> stored = repository.findAll().stream()
                    .map(row -> row.getTranTypeCd() + row.getTranCatCd())
                    .toList();
            final List<String> expected = SEEDED_ROWS.stream().map(SeededRow::keyImage).toList();

            assertThat(stored)
                    .as("the full read is unordered by contract - this table has no index to order it "
                            + "and none is invented - so membership is asserted rather than sequence")
                    .containsExactlyInAnyOrderElementsOf(expected);
        }

        @Test
        @DisplayName("spans seven type codes, every one of which keeps its leading zero")
        void spansSevenTypeCodesThatKeepTheirLeadingZero() {
            final List<String> typeCodes = repository.findAll().stream()
                    .map(TransactionCategory::getTranTypeCd)
                    .distinct()
                    .sorted()
                    .toList();

            assertThat(typeCodes).containsExactly("01", "02", "03", "04", "05", "06", "07");
            assertThat(typeCodes)
                    .as("a numeric representation would have dropped every one of these leading zeros "
                            + "and the key image would no longer reconstruct")
                    .allSatisfy(code -> assertThat(code).hasSize(TYPE_CODE_WIDTH));
        }

        @Test
        @DisplayName("returns every description exactly as seeded, neither padded nor trimmed by the "
                + "mapping")
        void returnsEveryDescriptionExactlyAsSeeded() {
            for (final SeededRow seeded : SEEDED_ROWS) {
                final Optional<TransactionCategory> found = repository.findById(seeded.id());

                assertThat(found)
                        .as("seeded key image %s must resolve", seeded.keyImage())
                        .isPresent();
                assertThat(found.orElseThrow().getTranCatTypeDesc())
                        .as("the description of key image %s is compared to the exact seeded literal: "
                                + "the mapping adds no padding on the way out and removes none on the "
                                + "way in, because the external width of 50 belongs to the record writer",
                                seeded.keyImage())
                        .isEqualTo(seeded.description());
            }
        }

        @Test
        @DisplayName("keeps every description inside the declared column width, and none is blank")
        void keepsEveryDescriptionInsideTheDeclaredWidth() {
            assertThat(repository.findAll())
                    .allSatisfy(row -> assertThat(row.getTranCatTypeDesc())
                            .isNotBlank()
                            .hasSizeBetween(NARROWEST_SEEDED_DESCRIPTION, WIDEST_SEEDED_DESCRIPTION)
                            .hasSizeLessThanOrEqualTo(DESCRIPTION_WIDTH));
        }
    }

    /**
     * The mechanics of the two-component key, which is where this table is most easily got wrong.
     *
     * <p>Every assertion here goes through the inherited keyed read, because that is the entire access
     * path the repository publishes and the argument it takes is the composite identifier. The
     * swapped-argument case is the load-bearing one: it is the only assertion that fails if the component
     * order is ever reversed, in the identifier class, in the entity's key field order or in the
     * migration's primary-key column order.
     */
    @Nested
    @DisplayName("the composite key")
    class TheCompositeKey {

        /** Creates the nested test class. */
        TheCompositeKey() {
        }

        @Test
        @DisplayName("resolves a seeded row when its two components are supplied in contractual order")
        void resolvesASeededRowInContractualOrder() {
            final Optional<TransactionCategory> found = repository.findById(FIRST_SEEDED_ROW.id());

            assertThat(found).isPresent();
            final TransactionCategory row = found.orElseThrow();
            assertThat(row.getTranTypeCd()).isEqualTo(FIRST_SEEDED_ROW.typeCode());
            assertThat(row.getTranCatCd()).isEqualTo(FIRST_SEEDED_ROW.categoryCode());
            assertThat(row.getTranCatTypeDesc()).isEqualTo(FIRST_SEEDED_ROW.description());
            assertThat(row.toId())
                    .as("the identifier the row reports is the one that fetched it, which is what lets a "
                            + "caller round-trip a key without reassembling it by hand")
                    .isEqualTo(FIRST_SEEDED_ROW.id());
        }

        @Test
        @DisplayName("resolves NOTHING when the two components are swapped, which is what locks the "
                + "argument order against a future refactor")
        void resolvesNothingWhenTheComponentsAreSwapped() {
            final TransactionCategoryId swapped = new TransactionCategoryId(
                    FIRST_SEEDED_ROW.categoryCode(), FIRST_SEEDED_ROW.typeCode());

            assertThat(swapped)
                    .as("the swapped key is genuinely a different key, so the identifier's own equality "
                            + "already refuses to treat the two as interchangeable")
                    .isNotEqualTo(FIRST_SEEDED_ROW.id());
            assertThat(repository.findById(swapped))
                    .as("no seeded pair is its own reverse - the type codes are two characters wide and "
                            + "the category codes four - so a swap can never accidentally hit a row. If "
                            + "this ever resolves, the two components have been transposed somewhere")
                    .isEmpty();
            assertThat(repository.existsById(swapped)).isFalse();
        }

        @Test
        @DisplayName("carries components of exactly two and four characters, which sum to the declared "
                + "six-byte key width")
        void carriesComponentsOfTwoAndFourThatSumToTheKeyWidth() {
            final TransactionCategory row = repository.findById(FIRST_SEEDED_ROW.id()).orElseThrow();

            assertThat(row.getTranTypeCd()).hasSize(TYPE_CODE_WIDTH);
            assertThat(row.getTranCatCd()).hasSize(CATEGORY_CODE_WIDTH);
            assertThat(TYPE_CODE_WIDTH + CATEGORY_CODE_WIDTH)
                    .as("the two component widths reconcile with the key length declared at offset 0")
                    .isEqualTo(KEY_WIDTH);
            assertThat(KEY_WIDTH + DESCRIPTION_WIDTH + FILLER_WIDTH)
                    .as("and the key, the description and the unpersisted trailing filler reconcile with "
                            + "the whole record image")
                    .isEqualTo(RECORD_LENGTH);
            assertThat(row.getTranTypeCd() + row.getTranCatCd())
                    .as("so the stored components reassemble the key image byte for byte")
                    .isEqualTo(FIRST_SEEDED_ROW.keyImage())
                    .hasSize(KEY_WIDTH);
        }

        @Test
        @DisplayName("answers an empty optional for a key no row carries, and raises nothing")
        void answersEmptyForAnAbsentKeyWithoutRaising() {
            final TransactionCategoryId absent =
                    new TransactionCategoryId(RESERVED_TYPE_CODE, ABSENT_CATEGORY_CODE);

            assertThatCode(() -> repository.findById(absent))
                    .as("absence is an ordinary outcome on this path: the legacy batch tier normalises a "
                            + "raw file status into a coarser result and treats not-found as data, and "
                            + "the repository-level expression of that is an empty optional, not a throw")
                    .doesNotThrowAnyException();
            assertThat(repository.findById(absent)).isEmpty();
            assertThat(repository.existsById(absent)).isFalse();
        }

        @Test
        @DisplayName("keeps a four-character category code whole, so a padded code and a bare digit are "
                + "different keys")
        void keepsAFourCharacterCategoryCodeWhole() {
            final Optional<TransactionCategory> padded = repository.findById(LEADING_ZERO_ROW.id());

            assertThat(padded).isPresent();
            assertThat(padded.orElseThrow().getTranCatCd())
                    .as("the stored code round-trips with all three leading zeros intact")
                    .isEqualTo(LEADING_ZERO_ROW.categoryCode())
                    .hasSize(CATEGORY_CODE_WIDTH);
            assertThat(repository.findById(
                    new TransactionCategoryId(LEADING_ZERO_ROW.typeCode(), "5")))
                    .as("and the bare digit is a different key entirely - an integral column would have "
                            + "made these two the same row and silently broken the six-byte key image")
                    .isEmpty();
        }

        @Test
        @DisplayName("keeps a two-character type code whole, so its leading zero is part of the key")
        void keepsATwoCharacterTypeCodeWhole() {
            assertThat(repository.findById(
                    new TransactionCategoryId("1", FIRST_SEEDED_ROW.categoryCode())))
                    .as("the seeded type code reads as two characters with a leading zero, so the "
                            + "one-character form matches nothing")
                    .isEmpty();
            assertThat(repository.findById(FIRST_SEEDED_ROW.id()))
                    .as("while the two-character form matches, which is the same comparison the other "
                            + "way round")
                    .isPresent();
        }

        @Test
        @DisplayName("agrees with the inherited existence check on every seeded key")
        void agreesWithTheExistenceCheckOnEverySeededKey() {
            for (final SeededRow seeded : SEEDED_ROWS) {
                assertThat(repository.existsById(seeded.id()))
                        .as("key image %s exists", seeded.keyImage())
                        .isTrue();
                assertThat(repository.findById(seeded.id()))
                        .as("and the keyed read agrees for key image %s", seeded.keyImage())
                        .isPresent();
            }
        }
    }

    /**
     * The shape of the migrated table, read back from the server's own catalogue.
     *
     * <p>The mapping is validated on refresh, which proves the entity and the migration agree with each
     * other. It does not prove what they agree <em>on</em>, and three of those facts are contractual
     * rather than incidental: that the primary key is the two key components in the declared order, that
     * the table carries no index beyond the one the primary key needs, and that no foreign key runs into
     * it or out of it. Each is asserted directly, with a fixed statement and the table name bound as a
     * parameter.
     *
     * <p>The name of this table is a strict prefix of the category-balance table's name, so every
     * statement below matches on exact equality and never on a pattern. A pattern match here would
     * silently fold the two tables together and would report the other table's constraints as this one's -
     * which is exactly the confusion the class comment exists to prevent.
     */
    @Nested
    @DisplayName("the migrated table")
    class TheMigratedTable {

        /** Creates the nested test class. */
        TheMigratedTable() {
        }

        @Test
        @DisplayName("is keyed on the two key components, in that order and no other")
        void isKeyedOnTheTwoKeyComponentsInOrder() {
            assertThat(primaryKeyColumns())
                    .as("component order is fixed independently by the cluster key geometry and by the "
                            + "migration, and reordering it would change the key rather than reformat it")
                    .containsExactly("tran_type_cd", "tran_cat_cd");
        }

        @Test
        @DisplayName("declares three required text columns at the record's own widths, and nothing else")
        void declaresThreeRequiredTextColumnsAtTheRecordWidths() {
            final List<Map<String, Object>> columns = columnMetadata();

            assertThat(columns)
                    .as("the 4 trailing filler bytes at offset 56 are not a column, so 3 columns cover "
                            + "the 56 mapped bytes of the 60-byte record")
                    .hasSize(3);
            assertThat(project(columns, "column_name"))
                    .containsExactly("tran_type_cd", "tran_cat_cd", "tran_cat_type_desc");
            assertThat(project(columns, "data_type"))
                    .as("bounded variable-length text throughout - not an integral type, which would "
                            + "drop a leading zero, and not a blank-padded type, which would hide a "
                            + "difference in trailing space")
                    .containsOnly("character varying");
            assertThat(project(columns, "character_maximum_length"))
                    .as("the declared widths are the record's own: 2 at offset 0, 4 at offset 2 and 50 "
                            + "at offset 6")
                    .containsExactly(TYPE_CODE_WIDTH, CATEGORY_CODE_WIDTH, DESCRIPTION_WIDTH);
            assertThat(project(columns, "is_nullable"))
                    .as("a fixed-width record has no absent field, so no column is optional")
                    .containsOnly("NO");
        }

        @Test
        @DisplayName("carries no generated, defaulted or sequence-backed column, because the key is the "
                + "legacy business key itself")
        void carriesNoGeneratedOrDefaultedColumn() {
            final List<Map<String, Object>> columns = columnMetadata();

            assertThat(project(columns, "is_identity"))
                    .as("a surrogate identifier would break the record-image-to-row correspondence that "
                            + "byte-level parity verification depends on")
                    .containsOnly("NO");
            assertThat(project(columns, "column_default"))
                    .as("and no column is server-defaulted either, so every stored value came from a "
                            + "record image or from a caller")
                    .containsOnlyNulls();
        }

        @Test
        @DisplayName("carries NO secondary index: the only index present is the one the primary key needs")
        void carriesNoSecondaryIndex() {
            assertThat(indexNames())
                    .as("the index migration defines no index touching this table, and this table has no "
                            + "browse, no paging and no alternate index to support - so anything beyond "
                            + "the primary key's own index is an access path nothing asked for")
                    .containsExactly(PRIMARY_KEY_NAME);
        }

        @Test
        @DisplayName("has NO foreign key leaving it and NO foreign key arriving at it")
        void hasNoForeignKeyInEitherDirection() {
            assertThat(foreignKeysDeclaredOnTheTable())
                    .as("nothing constrains this reference row to another table - in particular its type "
                            + "code is not constrained to the transaction-type table")
                    .isEmpty();
            assertThat(foreignKeysTargetingTheTable())
                    .as("and nothing constrains a transaction, daily-transaction or category-balance row "
                            + "to this table. The source treats these codes as classification lexemes "
                            + "carried on the record, so constraining them would change which rows the "
                            + "system accepts and would make the posting program's reject-with-reason "
                            + "paths unreachable on the unvalidated landing table")
                    .isEmpty();
        }
    }

    /**
     * The write surface, which is inherited and is exercised once.
     *
     * <p>Nothing in normal operation writes this table - it is reference data the reporting path reads -
     * but the inherited save is part of the published surface, so it is proven rather than assumed. The
     * test is transactional and rolls back, and it uses a reserved type code that no seeded row carries,
     * so the shared server is left exactly as it was found even if the rollback were ever to fail.
     */
    @Nested
    @DisplayName("the inherited write surface")
    class TheInheritedWriteSurface {

        /** Creates the nested test class. */
        TheInheritedWriteSurface() {
        }

        @Test
        @Transactional
        @DisplayName("round-trips a purpose-built row through the server unchanged, and leaves the "
                + "eighteen seeded rows alone")
        void roundTripsAPurposeBuiltRowUnchanged() {
            final TransactionCategory built = TestDataFactory.transactionCategory()
                    .typeCode(RESERVED_TYPE_CODE)
                    .categoryCode(RESERVED_CATEGORY_CODE)
                    .description(RESERVED_DESCRIPTION)
                    .build();
            final TransactionCategoryId id =
                    new TransactionCategoryId(RESERVED_TYPE_CODE, RESERVED_CATEGORY_CODE);

            repository.saveAndFlush(built);
            // Detach before reading back, or the keyed read is answered from the persistence context and
            // proves only that the context remembers what it was handed.
            entityManager.clear();

            final Optional<TransactionCategory> reloaded = repository.findById(id);

            assertThat(reloaded).isPresent();
            final TransactionCategory row = reloaded.orElseThrow();
            assertThat(row)
                    .as("a genuinely re-read instance, not the one that was saved")
                    .isNotSameAs(built);
            assertThat(row.getTranTypeCd()).isEqualTo(RESERVED_TYPE_CODE);
            assertThat(row.getTranCatCd()).isEqualTo(RESERVED_CATEGORY_CODE);
            assertThat(row.getTranCatTypeDesc())
                    .as("stored verbatim: the entity pads nothing on the way in and trims nothing on the "
                            + "way out, because the external width of 50 belongs to the record writer")
                    .isEqualTo(RESERVED_DESCRIPTION);
            assertThat(row.toId()).isEqualTo(id);
            assertThat(repository.count())
                    .as("one reserved row above the seeded eighteen, inside this unit of work only")
                    .isEqualTo(SEEDED_ROW_COUNT + 1L);

            repository.deleteById(id);
            repository.flush();
            entityManager.clear();

            assertThat(repository.findById(id))
                    .as("removed again, so the rollback that follows has nothing left to undo")
                    .isEmpty();
            assertThat(repository.count()).isEqualTo(SEEDED_ROW_COUNT);
        }
    }

    /**
     * Reads the primary-key columns in their declared order.
     *
     * @return the key column names, ordered by their position in the constraint
     */
    private List<String> primaryKeyColumns() {
        return jdbcTemplate.queryForList("""
                SELECT column_name
                  FROM information_schema.key_column_usage
                 WHERE table_schema = 'public'
                   AND table_name = ?
                   AND constraint_name = ?
                 ORDER BY ordinal_position
                """, String.class, TABLE_NAME, PRIMARY_KEY_NAME);
    }

    /**
     * Reads the table's columns in record-layout order with the attributes this class asserts on.
     *
     * @return one row per column, ordered as the migration declares them
     */
    private List<Map<String, Object>> columnMetadata() {
        return jdbcTemplate.queryForList("""
                SELECT column_name,
                       data_type,
                       character_maximum_length,
                       is_nullable,
                       column_default,
                       is_identity
                  FROM information_schema.columns
                 WHERE table_schema = 'public'
                   AND table_name = ?
                 ORDER BY ordinal_position
                """, TABLE_NAME);
    }

    /**
     * Reads the names of every index the server holds on this table.
     *
     * @return the index names in name order
     */
    private List<String> indexNames() {
        return jdbcTemplate.queryForList("""
                SELECT indexname
                  FROM pg_indexes
                 WHERE schemaname = 'public'
                   AND tablename = ?
                 ORDER BY indexname
                """, String.class, TABLE_NAME);
    }

    /**
     * Reads the names of every foreign key declared on this table, which must be none.
     *
     * @return the outbound foreign-key constraint names in name order
     */
    private List<String> foreignKeysDeclaredOnTheTable() {
        return jdbcTemplate.queryForList("""
                SELECT constraint_name
                  FROM information_schema.table_constraints
                 WHERE table_schema = 'public'
                   AND table_name = ?
                   AND constraint_type = 'FOREIGN KEY'
                 ORDER BY constraint_name
                """, String.class, TABLE_NAME);
    }

    /**
     * Reads the names of every foreign key anywhere in the schema that references this table, which must
     * be none.
     *
     * @return the inbound foreign-key constraint names in name order
     */
    private List<String> foreignKeysTargetingTheTable() {
        return jdbcTemplate.queryForList("""
                SELECT tc.constraint_name
                  FROM information_schema.table_constraints tc
                  JOIN information_schema.constraint_column_usage ccu
                    ON ccu.constraint_schema = tc.constraint_schema
                   AND ccu.constraint_name = tc.constraint_name
                 WHERE tc.constraint_type = 'FOREIGN KEY'
                   AND ccu.table_schema = 'public'
                   AND ccu.table_name = ?
                 ORDER BY tc.constraint_name
                """, String.class, TABLE_NAME);
    }

    /**
     * Projects one catalogue attribute out of a column-metadata result, preserving row order.
     *
     * <p>Returned untyped on purpose: the attributes read here are a mixture of text and numbers, and the
     * assertions that consume this compare against the literal each attribute actually carries.
     *
     * @param columns   the column-metadata rows
     * @param attribute the catalogue column to project
     * @return the projected values in the order the rows were returned
     */
    private static List<Object> project(final List<Map<String, Object>> columns, final String attribute) {
        return columns.stream().map(column -> column.get(attribute)).toList();
    }
}
