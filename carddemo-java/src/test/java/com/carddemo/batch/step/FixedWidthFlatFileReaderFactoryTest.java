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
package com.carddemo.batch.step;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

import com.carddemo.domain.Account;
import com.carddemo.domain.Card;
import com.carddemo.domain.CardCrossReference;
import com.carddemo.domain.Customer;
import com.carddemo.domain.DailyTransaction;
import com.carddemo.domain.DisclosureGroup;
import com.carddemo.domain.Transaction;
import com.carddemo.domain.TransactionCategory;
import com.carddemo.domain.TransactionCategoryBalance;
import com.carddemo.domain.TransactionType;
import com.carddemo.domain.UserSecurity;
import com.carddemo.domain.enums.TransactionSourceType;
import com.carddemo.support.TestDataFactory;
import com.carddemo.util.AccountRecordMapper;
import com.carddemo.util.CardRecordMapper;
import com.carddemo.util.CardXrefRecordMapper;
import com.carddemo.util.CustomerRecordMapper;
import com.carddemo.util.DailyTransactionRecordMapper;
import com.carddemo.util.DisclosureGroupRecordMapper;
import com.carddemo.util.SensitiveFieldCodec;
import com.carddemo.util.TranCatBalRecordMapper;
import com.carddemo.util.TranCatRecordMapper;
import com.carddemo.util.TranTypeRecordMapper;
import com.carddemo.util.TransactionRecordMapper;
import com.carddemo.util.UserSecurityRecordMapper;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.ItemStreamException;
import org.springframework.batch.item.file.FlatFileItemReader;
import org.springframework.batch.item.file.FlatFileParseException;
import org.springframework.batch.item.file.NonTransientFlatFileException;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;

/**
 * Verifies {@link FixedWidthFlatFileReaderFactory}: the eleven typed readers it builds, the widths and
 * byte offsets each of those readers' layouts is defined by, and the properties of the reader instances
 * themselves.
 *
 * <h2>Legacy authority</h2>
 *
 * <p>The eleven record layouts under test are defined by eleven copybooks in {@code app/cpy} - the
 * account, card, card cross-reference, customer, transaction, daily transaction, transaction category
 * balance, disclosure group, transaction type, transaction category and user security descriptions -
 * and the nine production-representative sequential datasets in {@code app/data/ASCII} are the measured
 * evidence for how those layouts appear in real input. Both trees are <strong>read-only reference</strong>.
 * No copybook, job-control, screen-definition or resource-definition source line is reproduced here; what
 * is carried across is metadata only, being field widths, zero-based byte offsets, record lengths, key
 * lengths, record counts and member names. Nothing under {@code app/} is opened at run time either: every
 * fixture this suite reads is the copy on the test classpath under {@code fixtures/input}, so the build
 * never depends on a tree the module does not ship.
 *
 * <h2>Provenance</h2>
 *
 * <p>Matrix header: commit SHA {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19.
 *
 * <p><strong>That stamp is not universal, and this suite therefore never asserts it per member.</strong>
 * Across the estate 78 members carry it, 3 carry later stamps, all 17 screen definitions carry something
 * different again, and 25 members carry no stamp at all. A per-member assertion would fail on more than a
 * third of the estate while proving nothing about behaviour, so the pair above is a matrix-header string
 * and nothing more.
 *
 * <h2>Independent oracle</h2>
 *
 * <p>Every expected value here is built by hand - plain string concatenation, and a zoned-decimal
 * encoder written in this file - or taken from {@link TestDataFactory}, whose image builders are
 * independently implemented and reference no production mapper, codec, formatter or template. No record
 * mapper and no decimal codec is ever called to produce an expectation, because the eleven mappers are
 * precisely the collaborators this factory wires: using one as an oracle would let a mapper defect and a
 * test agree with each other and both be wrong. Mappers are named here only to read their published
 * offset constants, and in that direction the hand-derived copybook literal is the expectation and the
 * production constant is the value under test, which makes those cases drift detectors.
 *
 * <h2>Findings recorded as decision-log candidates</h2>
 *
 * <ul>
 *   <li><strong>The factory takes no collaborator, and that is deliberate.</strong> The eleven record
 *       mappers are stateless utilities with private constructors and only static members, so there is
 *       no mapper bean to inject. The two layouts needing a caller-owned policy function - the
 *       customer layout's field sealer and the user security layout's credential digest - receive it as
 *       a method argument, because each is a key-bearing policy owned by the layer that holds the key.
 *   <li><strong>Eleven layouts, and no twelfth.</strong> A second customer copybook describes the same
 *       500 bytes with the same fields at the same offsets, differing only in how one date field's name
 *       is punctuated, and it is live because the statement generator includes it. That is one entity
 *       viewed twice, so it gets no reader of its own.
 *   <li><strong>Twelve reader names over those eleven layouts.</strong> The transaction layout is
 *       reachable two ways: line-terminated, and fixed-unblocked at an exact 350-byte stride. Those are
 *       two physical boundaries over one logical layout, not two layouts, and they carry different names
 *       so that restart metadata from one is never applied to the other.
 *   <li><strong>Two expiration-date field names are misspelled in the source</strong>, verified at
 *       {@code app/cpy/CVACT01Y.cpy} line 11 for the account layout and {@code app/cpy/CVACT02Y.cpy}
 *       line 9 for the card layout. An earlier planning document cites line 10 for the account layout;
 *       line 11 is correct. The misspelling is preserved in the layout and in the mapper's published
 *       offset constant, so the byte offsets are unchanged at 58 and 80; the Java properties are spelled
 *       correctly. Correcting the layout would move no byte and would break the traceability link.
 *   <li><strong>The cross-reference record is live at two widths.</strong> Its copybook declares 36 data
 *       bytes followed by a 14-byte filler for a canonical 50, and the shipped sequential dataset omits
 *       that filler entirely, so its lines measure 36 with the record's tail ending on digits rather
 *       than spaces. Both are legitimate; the shipped form is never padded up to 50 for tidiness.
 *   <li><strong>The filler convention is measured, non-uniform, and deliberately preserved.</strong>
 *       Four datasets pad with spaces, four pad with the ASCII digit zero, and one has no filler run at
 *       all. Unifying them would be a change to input data that no requirement asks for.
 *   <li><strong>One legacy group name denotes two different composite keys.</strong> The same name
 *       identifies a 17-byte key on the category balance layout and a 6-byte key on the transaction
 *       category layout. The two are unrelated and are asserted separately; conflating them by name
 *       would silently mis-key one of the two.
 *   <li><strong>Trailing spaces are contractual.</strong> A ten-character group key holding a
 *       seven-character value carries three trailing spaces; a ten-character source field holding a
 *       six-character value carries four; a timestamp the legacy program never populated is 26 spaces
 *       rather than null, empty or a parsed instant. None of these is trimmed anywhere on the read path.
 * </ul>
 *
 * <h2>Inspection notes, recorded rather than asserted</h2>
 *
 * <p>Two properties of the class under test were established by reading it and are noted here because
 * asserting them would require the very mechanism the module forbids. First, it holds no reflective
 * registry and no dispatch keyed by class: each reader's line mapping is a direct call to a named static
 * method whose signature the compiler checks, which is exactly why the eleven mappers are hand-written
 * with explicit offsets rather than driven by an annotation-based mapping library. Second, it performs no
 * slicing of its own - no {@code substring}, no {@code trim}, no {@code strip} - because the offset
 * primitive in the utility layer is the single place a position-derived width may be interpreted. The
 * module's reflection budget is zero, so this suite uses no reflection either; the substitute for a
 * reflective check is behavioural, and it is the assertion that a value carrying significant trailing
 * spaces survives a read unmodified, which a stray trim anywhere on the path would break.
 *
 * <p>This is a surefire-tier unit test. It starts no application context, provisions no container,
 * opens no database, queue or object store, and extends no support base class; the only I/O it performs
 * is reading classpath fixtures and writing small temporary datasets that the test framework deletes.
 */
@DisplayName("FixedWidthFlatFileReaderFactory - eleven typed readers over the eleven verified layouts")
class FixedWidthFlatFileReaderFactoryTest {

    /** Classpath directory holding the named sequential fixtures. */
    private static final String FIXTURES = "fixtures/input/";

    /** Number of distinct record layouts the factory serves, one typed reader each. */
    private static final int LAYOUT_COUNT = 11;

    /** Records in the account, card, cross-reference, customer and category balance fixtures. */
    private static final int FIFTY_RECORDS = 50;

    /** Records in the daily transaction fixture. */
    private static final int DAILY_TRANSACTION_RECORDS = 300;

    /** Records in the disclosure group fixture: three complete groups of seventeen. */
    private static final int DISCLOSURE_GROUP_RECORDS = 51;

    /** Distinct group keys in the disclosure group fixture. */
    private static final int DISCLOSURE_GROUP_KEY_COUNT = 3;

    /** Records in the transaction category fixture. */
    private static final int TRANSACTION_CATEGORY_RECORDS = 18;

    /** Records in the transaction type fixture. */
    private static final int TRANSACTION_TYPE_RECORDS = 7;

    /** Records in the user security fixture. */
    private static final int USER_SECURITY_RECORDS = 10;

    /** Canonical encoded width of one account record. */
    private static final int ACCOUNT_WIDTH = 300;

    /** Canonical encoded width of one card record. */
    private static final int CARD_WIDTH = 150;

    /** Canonical encoded width of one cross-reference record, filler present. */
    private static final int CROSS_REFERENCE_WIDTH = 50;

    /** Encoded width of one cross-reference record as the shipped dataset carries it, filler absent. */
    private static final int CROSS_REFERENCE_DATA_WIDTH = 36;

    /** Width of the cross-reference filler run that the shipped dataset omits. */
    private static final int CROSS_REFERENCE_FILLER_WIDTH = 14;

    /** Canonical encoded width of one customer record. */
    private static final int CUSTOMER_WIDTH = 500;

    /** Canonical encoded width of one transaction record, and of one daily transaction record. */
    private static final int TRANSACTION_WIDTH = 350;

    /** Canonical encoded width of one category balance record. */
    private static final int CATEGORY_BALANCE_WIDTH = 50;

    /** Canonical encoded width of one disclosure group record. */
    private static final int DISCLOSURE_GROUP_WIDTH = 50;

    /** Canonical encoded width of one transaction type record. */
    private static final int TRANSACTION_TYPE_WIDTH = 60;

    /** Canonical encoded width of one transaction category record. */
    private static final int TRANSACTION_CATEGORY_WIDTH = 60;

    /** Canonical encoded width of one user security record. */
    private static final int USER_SECURITY_WIDTH = 80;

    /**
     * Extent of the account layout's data fields, filler excluded. The filler run therefore occupies
     * every byte from here to the canonical width.
     */
    private static final int ACCOUNT_MAPPED_WIDTH = 122;

    /** Extent of the card layout's data fields, filler excluded. */
    private static final int CARD_MAPPED_WIDTH = 91;

    /** Extent of the customer layout's data fields, filler excluded. */
    private static final int CUSTOMER_MAPPED_WIDTH = 332;

    /** Extent of the transaction layout's data fields, filler excluded. */
    private static final int TRANSACTION_MAPPED_WIDTH = 330;

    /** Extent of the category balance layout's data fields, filler excluded. */
    private static final int CATEGORY_BALANCE_MAPPED_WIDTH = 28;

    /** Extent of the disclosure group layout's data fields, filler excluded. */
    private static final int DISCLOSURE_GROUP_MAPPED_WIDTH = 22;

    /** Extent of the transaction type layout's data fields, filler excluded. */
    private static final int TRANSACTION_TYPE_MAPPED_WIDTH = 52;

    /** Extent of the transaction category layout's data fields, filler excluded. */
    private static final int TRANSACTION_CATEGORY_MAPPED_WIDTH = 56;

    /** Zero-based offset of the cross-reference account identifier, the alternate-index key. */
    private static final int CROSS_REFERENCE_ACCOUNT_ID_OFFSET = 25;

    /** Declared width of the cross-reference account identifier. */
    private static final int CROSS_REFERENCE_ACCOUNT_ID_WIDTH = 11;

    /** Declared width of the disclosure group's account group identifier field. */
    private static final int GROUP_ID_WIDTH = 10;

    /**
     * Width of the disclosure group's three-part composite key: a ten-character group identifier, a
     * two-character type code and a four-digit category code.
     */
    private static final int DISCLOSURE_GROUP_KEY_WIDTH = 16;

    /** Declared width of a transaction source field. */
    private static final int SOURCE_FIELD_WIDTH = 10;

    /** Declared width of a record timestamp field. */
    private static final int TIMESTAMP_WIDTH = 26;

    /**
     * The default group key exactly as the record carries it: a seven-character value padded with
     * spaces to the field's ten-character width. The padding is the point of every assertion using it.
     */
    private static final String DEFAULT_GROUP_KEY_AS_STORED = "DEFAULT   ";

    /** The zero-rate group key exactly as the record carries it, padded to the field width. */
    private static final String ZERO_RATE_GROUP_KEY_AS_STORED = "ZEROAPR   ";

    /** The direct-hit group key, whose ten characters are fully occupied by its value. */
    private static final String DIRECT_HIT_GROUP_KEY_AS_STORED = "A000000000";

    /** Overpunch table for a positive final digit, index zero through nine. */
    private static final String POSITIVE_OVERPUNCH = "{ABCDEFGHI";

    /** Overpunch table for a negative final digit, index zero through nine. */
    private static final String NEGATIVE_OVERPUNCH = "}JKLMNOPQR";

    /** Encoded width of a ten-integer-digit two-decimal zoned field. */
    private static final int ACCOUNT_AMOUNT_WIDTH = 12;

    /** Encoded width of a nine-integer-digit two-decimal zoned field. */
    private static final int TRANSACTION_AMOUNT_WIDTH = 11;

    /** Encoded width of a four-integer-digit two-decimal zoned field. */
    private static final int INTEREST_RATE_WIDTH = 6;

    /** Character length of a credential digest, which the user security entity requires exactly. */
    private static final int DIGEST_LENGTH = 60;

    /** Structural prefix of a credential digest: version marker plus cost, seven characters. */
    private static final String DIGEST_PREFIX = "$2b$12$";

    /**
     * Synthetic digest tail. Not a hash, not derived from any value, and not itself a credential: it
     * exists only to give the entity a value of the shape it insists on.
     */
    private static final String DIGEST_TAIL = "SyntheticDigestTailUsedOnlyByThisReaderFactorySuite00";

    /** The digest the stand-in digest function returns for every input. */
    private static final String SYNTHETIC_DIGEST = DIGEST_PREFIX + DIGEST_TAIL;

    /** Line terminator written into the temporary datasets this suite builds. */
    private static final String NEWLINE = "\n";

    /** Sixteen-character transaction identifier this suite places in the images it builds by hand. */
    private static final String SENTINEL_TRANSACTION_ID = "TRN0000000000042";

    /** The class under test. It is stateless and holds no collaborator, so one instance serves all. */
    private final FixedWidthFlatFileReaderFactory factory = new FixedWidthFlatFileReaderFactory();

    /** Scratch directory for the small datasets some cases build; the framework deletes it. */
    @TempDir
    private Path temporaryDirectory;

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    @DisplayName("eleven layouts, eleven canonical widths, eleven distinct entity types")
    class ElevenLayouts {

        /**
         * Describes every layout the factory serves: its name, the canonical encoded width its copybook
         * declares, the encoded width the shipped dataset actually carries, the entity its reader must
         * produce, the record count of that dataset, the dataset's file name, and the factory call under
         * test.
         *
         * <p>The canonical width and the dataset width differ for exactly one layout, which is the point
         * of carrying both rather than assuming they agree.
         *
         * @return one case per layout, eleven in total
         */
        Stream<Arguments> elevenLayouts() {
            return Stream.of(
                    layoutCase("account", ACCOUNT_WIDTH, ACCOUNT_WIDTH, Account.class,
                            FIFTY_RECORDS, "acctdata.txt",
                            (subject, resource) -> subject.accountReader(resource)),
                    layoutCase("card", CARD_WIDTH, CARD_WIDTH, Card.class,
                            FIFTY_RECORDS, "carddata.txt",
                            (subject, resource) -> subject.cardReader(resource)),
                    layoutCase("card cross-reference", CROSS_REFERENCE_WIDTH,
                            CROSS_REFERENCE_DATA_WIDTH, CardCrossReference.class,
                            FIFTY_RECORDS, "cardxref.txt",
                            (subject, resource) -> subject.cardCrossReferenceReader(resource)),
                    layoutCase("customer", CUSTOMER_WIDTH, CUSTOMER_WIDTH, Customer.class,
                            FIFTY_RECORDS, "custdata.txt",
                            (subject, resource) -> subject.customerReader(resource, sealer())),
                    layoutCase("transaction", TRANSACTION_WIDTH, TRANSACTION_WIDTH, Transaction.class,
                            DAILY_TRANSACTION_RECORDS, "dailytran.txt",
                            (subject, resource) -> subject.transactionReader(resource)),
                    layoutCase("daily transaction", TRANSACTION_WIDTH, TRANSACTION_WIDTH,
                            DailyTransaction.class, DAILY_TRANSACTION_RECORDS, "dailytran.txt",
                            (subject, resource) -> subject.dailyTransactionReader(resource)),
                    layoutCase("transaction category balance", CATEGORY_BALANCE_WIDTH,
                            CATEGORY_BALANCE_WIDTH, TransactionCategoryBalance.class,
                            FIFTY_RECORDS, "tcatbal.txt",
                            (subject, resource) -> subject.transactionCategoryBalanceReader(resource)),
                    layoutCase("disclosure group", DISCLOSURE_GROUP_WIDTH, DISCLOSURE_GROUP_WIDTH,
                            DisclosureGroup.class, DISCLOSURE_GROUP_RECORDS, "discgrp.txt",
                            (subject, resource) -> subject.disclosureGroupReader(resource)),
                    layoutCase("transaction type", TRANSACTION_TYPE_WIDTH, TRANSACTION_TYPE_WIDTH,
                            TransactionType.class, TRANSACTION_TYPE_RECORDS, "trantype.txt",
                            (subject, resource) -> subject.transactionTypeReader(resource)),
                    layoutCase("transaction category", TRANSACTION_CATEGORY_WIDTH,
                            TRANSACTION_CATEGORY_WIDTH, TransactionCategory.class,
                            TRANSACTION_CATEGORY_RECORDS, "trancatg.txt",
                            (subject, resource) -> subject.transactionCategoryReader(resource)),
                    layoutCase("user security", USER_SECURITY_WIDTH, USER_SECURITY_WIDTH,
                            UserSecurity.class, USER_SECURITY_RECORDS, "usrsec.txt",
                            (subject, resource) ->
                                    subject.userSecurityReader(resource, digestFunction())));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("elevenLayouts")
        @DisplayName("each layout's reader consumes its dataset at the dataset's width and yields "
                + "its own entity type")
        void eachLayoutYieldsItsOwnEntityType(final String layoutName, final int canonicalWidth,
                final int datasetWidth, final Class<?> entityType, final int expectedRecords,
                final String fixtureName,
                final BiFunction<FixedWidthFlatFileReaderFactory, Resource,
                        FlatFileItemReader<?>> readerCall) throws Exception {
            final List<String> images = recordImagesOf(fixtureName, datasetWidth);
            final Resource resource = lineTerminatedResource(fixtureName, images);

            final List<?> items = readAll(readerCall.apply(factory, resource));

            assertThat(images)
                    .as("%s: the shipped dataset holds the record count this suite claims", layoutName)
                    .hasSize(expectedRecords);
            assertThat(images).allSatisfy(image -> assertThat(encodedLengthOf(image))
                    .as("%s: every record in the dataset measures its declared encoded width",
                            layoutName)
                    .isEqualTo(datasetWidth));
            assertThat(items)
                    .as("%s: every record mapped, none dropped and none null", layoutName)
                    .hasSize(expectedRecords)
                    .doesNotContainNull();
            assertThat(items)
                    .as("%s: the reader is bound to the mapper for this layout and no other, so the "
                            + "entity type is what proves the binding", layoutName)
                    .allSatisfy(item -> assertThat(item).isInstanceOf(entityType));
            assertThat(canonicalWidth)
                    .as("%s: the canonical width the copybook declares", layoutName)
                    .isPositive();
        }

        @Test
        @DisplayName("the eleven cases cover eleven distinct entity types, and there is no twelfth "
                + "layout")
        void elevenDistinctEntityTypes() {
            final List<Class<?>> entityTypes = elevenLayouts()
                    .<Class<?>>map(arguments -> asEntityType(arguments.get()[3]))
                    .toList();

            assertThat(entityTypes)
                    .as("one typed reader per layout, each producing a different entity")
                    .hasSize(LAYOUT_COUNT)
                    .doesNotHaveDuplicates();
            assertThat(entityTypes).containsExactlyInAnyOrder(Account.class, Card.class,
                    CardCrossReference.class, Customer.class, Transaction.class,
                    DailyTransaction.class, TransactionCategoryBalance.class, DisclosureGroup.class,
                    TransactionType.class, TransactionCategory.class, UserSecurity.class);
        }

        @Test
        @DisplayName("the published record-length constants agree with the widths measured from the "
                + "copybooks")
        void publishedWidthsMatchTheCopybooks() {
            assertThat(AccountRecordMapper.RECORD_LENGTH).isEqualTo(ACCOUNT_WIDTH);
            assertThat(CardRecordMapper.RECORD_LENGTH).isEqualTo(CARD_WIDTH);
            assertThat(CardXrefRecordMapper.RECORD_LENGTH).isEqualTo(CROSS_REFERENCE_WIDTH);
            assertThat(CustomerRecordMapper.RECORD_WIDTH).isEqualTo(CUSTOMER_WIDTH);
            assertThat(TransactionRecordMapper.RECORD_LENGTH).isEqualTo(TRANSACTION_WIDTH);
            assertThat(DailyTransactionRecordMapper.RECORD_LENGTH)
                    .as("the daily layout is byte-for-byte the transaction layout's width")
                    .isEqualTo(TRANSACTION_WIDTH);
            assertThat(TranCatBalRecordMapper.RECORD_LENGTH).isEqualTo(CATEGORY_BALANCE_WIDTH);
            assertThat(DisclosureGroupRecordMapper.RECORD_LENGTH).isEqualTo(DISCLOSURE_GROUP_WIDTH);
            assertThat(TranTypeRecordMapper.TRAN_TYPE_RECORD_LENGTH)
                    .isEqualTo(TRANSACTION_TYPE_WIDTH);
            assertThat(TranCatRecordMapper.RECORD_WIDTH).isEqualTo(TRANSACTION_CATEGORY_WIDTH);
            assertThat(UserSecurityRecordMapper.RECORD_LENGTH).isEqualTo(USER_SECURITY_WIDTH);
        }
    }

    @Nested
    @DisplayName("layouts that share a width stay distinct entities")
    class SharedWidthsStayDistinct {

        @Test
        @DisplayName("one 350-byte image yields a master transaction through one reader and a daily "
                + "transaction through the other")
        void oneImageTwoEntityTypes() throws Exception {
            final String image = dailyTransactionImage();
            final Resource dataset = writeLines(temporary("shared-350.txt"), List.of(image));

            final Object master = readAll(factory.transactionReader(dataset)).getFirst();
            final Object daily = readAll(factory.dailyTransactionReader(dataset)).getFirst();

            assertThat(encodedLengthOf(image))
                    .as("one image, one width, fed to both readers")
                    .isEqualTo(TRANSACTION_WIDTH);
            assertThat(master).isInstanceOf(Transaction.class);
            assertThat(daily).isInstanceOf(DailyTransaction.class);
            assertThat(master.getClass())
                    .as("nothing in the 350 bytes distinguishes the two layouts, so only the reader "
                            + "does; substituting one for the other would parse cleanly and write to "
                            + "the wrong dataset")
                    .isNotEqualTo(daily.getClass());
        }

        @Test
        @DisplayName("the same 350 bytes populate corresponding fields in both entities")
        void bothEntitiesCarryTheSameFieldValues() throws Exception {
            final Resource dataset =
                    writeLines(temporary("shared-350-fields.txt"), List.of(dailyTransactionImage()));

            final Transaction master = readAll(factory.transactionReader(dataset)).getFirst();
            final DailyTransaction daily =
                    readAll(factory.dailyTransactionReader(dataset)).getFirst();

            assertThat(master.getTranId()).isEqualTo(SENTINEL_TRANSACTION_ID);
            assertThat(daily.getDalytranId())
                    .as("the identifier occupies the same leading sixteen bytes in both layouts")
                    .isEqualTo(master.getTranId());
            assertThat(daily.getDalytranSource()).isEqualTo(master.getTranSource());
            assertThat(daily.getDalytranProcTs()).isEqualTo(master.getTranProcTs());
        }

        @Test
        @DisplayName("the three unrelated fifty-byte layouts each map to their own entity")
        void fiftyByteTrioDoesNotCrossSubstitute() throws Exception {
            final CardCrossReference reference =
                    readAll(factory.cardCrossReferenceReader(fixture("cardxref.txt"))).getFirst();
            final TransactionCategoryBalance balance =
                    readAll(factory.transactionCategoryBalanceReader(fixture("tcatbal.txt")))
                            .getFirst();
            final DisclosureGroup group =
                    readAll(factory.disclosureGroupReader(fixture("discgrp.txt"))).getFirst();

            assertThat(CROSS_REFERENCE_WIDTH)
                    .as("all three layouts are canonically fifty bytes and hold nothing else in common")
                    .isEqualTo(CATEGORY_BALANCE_WIDTH)
                    .isEqualTo(DISCLOSURE_GROUP_WIDTH);
            assertThat(List.of(reference.getClass(), balance.getClass(), group.getClass()))
                    .as("a shared width already caused one published mapping table to attribute the "
                            + "cross-reference program to the category balance file; the entity is "
                            + "what distinguishes them, never the width")
                    .doesNotHaveDuplicates()
                    .containsExactly(CardCrossReference.class, TransactionCategoryBalance.class,
                            DisclosureGroup.class);
        }

        @Test
        @DisplayName("the two unrelated sixty-byte layouts each map to their own entity")
        void sixtyBytePairDoesNotCrossSubstitute() throws Exception {
            final TransactionType type =
                    readAll(factory.transactionTypeReader(fixture("trantype.txt"))).getFirst();
            final TransactionCategory category =
                    readAll(factory.transactionCategoryReader(fixture("trancatg.txt"))).getFirst();

            assertThat(TRANSACTION_TYPE_WIDTH)
                    .as("both layouts are sixty bytes; one is keyed by type alone and the other by "
                            + "type plus category")
                    .isEqualTo(TRANSACTION_CATEGORY_WIDTH);
            assertThat(type).isInstanceOf(TransactionType.class);
            assertThat(category).isInstanceOf(TransactionCategory.class);
            assertThat(type.getClass()).isNotEqualTo(category.getClass());
        }
    }

    @Nested
    @DisplayName("the cross-reference reader accepts both live widths and imposes neither")
    class CrossReferenceWidths {

        @Test
        @DisplayName("the shipped dataset is the 36-byte form with no filler at all, and it reads")
        void shippedFormIsThirtySixBytesAndReads() throws Exception {
            final List<String> images = lines("cardxref.txt");

            final List<CardCrossReference> references =
                    readAll(factory.cardCrossReferenceReader(fixture("cardxref.txt")));

            assertThat(images).hasSize(FIFTY_RECORDS);
            assertThat(images).allSatisfy(image -> assertThat(encodedLengthOf(image))
                    .as("the shipped dataset carries the data bytes only; the filler run is absent")
                    .isEqualTo(CROSS_REFERENCE_DATA_WIDTH));
            assertThat(images.getFirst())
                    .as("the record's tail ends on digits rather than spaces, which is how the "
                            + "absence of a filler run is visible in the data itself")
                    .endsWith(images.getFirst()
                            .substring(CROSS_REFERENCE_DATA_WIDTH - 1))
                    .doesNotEndWith(" ");
            assertThat(references).hasSize(FIFTY_RECORDS).doesNotContainNull();
            assertThat(references).allSatisfy(reference ->
                    assertThat(reference).isInstanceOf(CardCrossReference.class));
        }

        @Test
        @DisplayName("the canonical width is fifty, being thirty-six data bytes plus a fourteen-byte "
                + "filler")
        void canonicalWidthIsFifty() {
            assertThat(CROSS_REFERENCE_DATA_WIDTH + CROSS_REFERENCE_FILLER_WIDTH)
                    .as("the copybook's data fields and filler run sum to the canonical width")
                    .isEqualTo(CROSS_REFERENCE_WIDTH);
            assertThat(CardXrefRecordMapper.RECORD_LENGTH).isEqualTo(CROSS_REFERENCE_WIDTH);
            assertThat(CardXrefRecordMapper.DATA_RECORD_LENGTH)
                    .isEqualTo(CROSS_REFERENCE_DATA_WIDTH);
            assertThat(CardXrefRecordMapper.FILLER_LENGTH).isEqualTo(CROSS_REFERENCE_FILLER_WIDTH);
        }

        @Test
        @DisplayName("the account identifier sits at offset twenty-five for eleven bytes, which is the "
                + "legacy alternate-index key definition for this cluster")
        void accountIdentifierOffsetAndWidth() throws Exception {
            final String image = lines("cardxref.txt").getFirst();
            final String accountIdentifierSlice = image.substring(CROSS_REFERENCE_ACCOUNT_ID_OFFSET,
                    CROSS_REFERENCE_ACCOUNT_ID_OFFSET + CROSS_REFERENCE_ACCOUNT_ID_WIDTH);

            final CardCrossReference reference =
                    readAll(factory.cardCrossReferenceReader(fixture("cardxref.txt"))).getFirst();

            assertThat(CardXrefRecordMapper.XREF_ACCT_ID_OFFSET)
                    .as("zero-based offset of the alternate-index key")
                    .isEqualTo(CROSS_REFERENCE_ACCOUNT_ID_OFFSET);
            assertThat(CardXrefRecordMapper.XREF_ACCT_ID_LENGTH)
                    .isEqualTo(CROSS_REFERENCE_ACCOUNT_ID_WIDTH);
            assertThat(encodedLengthOf(accountIdentifierSlice))
                    .isEqualTo(CROSS_REFERENCE_ACCOUNT_ID_WIDTH);
            assertThat(reference.getXrefAcctId())
                    .as("the entity carries exactly the bytes that sit at that offset")
                    .isEqualTo(accountIdentifierSlice);
        }

        @Test
        @DisplayName("the 36-byte shipped form and the 50-byte mainframe form decode identically, and "
                + "the shipped form is never padded to reach fifty")
        void bothWidthsDecodeIdentically() throws Exception {
            final List<String> shipped = lines("cardxref.txt");
            final List<String> mainframeForm = shipped.stream()
                    .map(image -> image + " ".repeat(CROSS_REFERENCE_FILLER_WIDTH))
                    .collect(Collectors.toList());
            final Resource padded = writeLines(temporary("cardxref-50.txt"), mainframeForm);

            final List<CardCrossReference> fromShipped =
                    readAll(factory.cardCrossReferenceReader(fixture("cardxref.txt")));
            final List<CardCrossReference> fromMainframeForm =
                    readAll(factory.cardCrossReferenceReader(padded));

            assertThat(encodedLengthOf(mainframeForm.getFirst()))
                    .as("the padded form really is the canonical width")
                    .isEqualTo(CROSS_REFERENCE_WIDTH);
            assertThat(encodedLengthOf(shipped.getFirst()))
                    .as("and the shipped form is still the data-only width, unpadded on disk")
                    .isEqualTo(CROSS_REFERENCE_DATA_WIDTH);
            assertThat(fromMainframeForm)
                    .as("the filler run carries no data, so the two widths decode to equal entities")
                    .isEqualTo(fromShipped);
        }

        @Test
        @DisplayName("a width that is neither thirty-six nor fifty is refused, naming both accepted "
                + "widths")
        void anyOtherWidthIsRefused() throws Exception {
            final Resource oversized = writeLines(temporary("cardxref-37.txt"),
                    List.of(lines("cardxref.txt").getFirst() + " "));

            assertThatThrownBy(() -> readAll(factory.cardCrossReferenceReader(oversized)))
                    .isInstanceOf(FlatFileParseException.class)
                    .cause()
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(String.valueOf(CROSS_REFERENCE_DATA_WIDTH))
                    .hasMessageContaining(String.valueOf(CROSS_REFERENCE_WIDTH))
                    .as("the diagnosis names both accepted widths and the width supplied, and says "
                            + "the record is neither padded nor truncated to fit")
                    .hasMessageContaining("never padded or truncated");
        }
    }

    @Nested
    @DisplayName("the measured filler conventions are tolerated exactly as the datasets carry them")
    class FillerConventions {

        @Test
        @DisplayName("the four space-filled datasets read, and their filler runs really are spaces")
        void spaceFilledDatasetsRead() throws Exception {
            assertFillerRun("acctdata.txt", ACCOUNT_MAPPED_WIDTH, ACCOUNT_WIDTH, ' ');
            assertFillerRun("carddata.txt", CARD_MAPPED_WIDTH, CARD_WIDTH, ' ');
            assertFillerRun("custdata.txt", CUSTOMER_MAPPED_WIDTH, CUSTOMER_WIDTH, ' ');
            assertFillerRun("dailytran.txt", TRANSACTION_MAPPED_WIDTH, TRANSACTION_WIDTH, ' ');

            assertThat(readAll(factory.accountReader(fixture("acctdata.txt"))))
                    .hasSize(FIFTY_RECORDS);
            assertThat(readAll(factory.cardReader(fixture("carddata.txt")))).hasSize(FIFTY_RECORDS);
            assertThat(readAll(factory.customerReader(fixture("custdata.txt"), sealer())))
                    .hasSize(FIFTY_RECORDS);
            assertThat(readAll(factory.dailyTransactionReader(fixture("dailytran.txt"))))
                    .hasSize(DAILY_TRANSACTION_RECORDS);
        }

        @Test
        @DisplayName("the four zero-filled datasets read, and their filler runs really are the ASCII "
                + "digit zero")
        void zeroFilledDatasetsRead() throws Exception {
            assertFillerRun("discgrp.txt", DISCLOSURE_GROUP_MAPPED_WIDTH, DISCLOSURE_GROUP_WIDTH, '0');
            assertFillerRun("tcatbal.txt", CATEGORY_BALANCE_MAPPED_WIDTH, CATEGORY_BALANCE_WIDTH, '0');
            assertFillerRun("trancatg.txt", TRANSACTION_CATEGORY_MAPPED_WIDTH,
                    TRANSACTION_CATEGORY_WIDTH, '0');
            assertFillerRun("trantype.txt", TRANSACTION_TYPE_MAPPED_WIDTH, TRANSACTION_TYPE_WIDTH,
                    '0');

            assertThat(readAll(factory.disclosureGroupReader(fixture("discgrp.txt"))))
                    .hasSize(DISCLOSURE_GROUP_RECORDS);
            assertThat(readAll(factory.transactionCategoryBalanceReader(fixture("tcatbal.txt"))))
                    .hasSize(FIFTY_RECORDS);
            assertThat(readAll(factory.transactionCategoryReader(fixture("trancatg.txt"))))
                    .hasSize(TRANSACTION_CATEGORY_RECORDS);
            assertThat(readAll(factory.transactionTypeReader(fixture("trantype.txt"))))
                    .hasSize(TRANSACTION_TYPE_RECORDS);
        }

        @Test
        @DisplayName("the one dataset with no filler run at all reads too, and is not padded to reach "
                + "its canonical width")
        void unfilledDatasetReads() throws Exception {
            final String image = lines("cardxref.txt").getFirst();

            final List<CardCrossReference> references =
                    readAll(factory.cardCrossReferenceReader(fixture("cardxref.txt")));

            assertThat(encodedLengthOf(image))
                    .as("the record stops where its data stops; there is no filler run to inspect")
                    .isEqualTo(CROSS_REFERENCE_DATA_WIDTH)
                    .isLessThan(CROSS_REFERENCE_WIDTH);
            assertThat(references).hasSize(FIFTY_RECORDS).doesNotContainNull();
        }

        @Test
        @DisplayName("the convention is genuinely non-uniform across the nine datasets, and is left "
                + "that way deliberately")
        void conventionIsNonUniform() throws Exception {
            final Set<Character> conventions = new LinkedHashSet<>();
            conventions.add(fillerCharacterOf("acctdata.txt", ACCOUNT_MAPPED_WIDTH, ACCOUNT_WIDTH));
            conventions.add(fillerCharacterOf("discgrp.txt", DISCLOSURE_GROUP_MAPPED_WIDTH,
                    DISCLOSURE_GROUP_WIDTH));

            assertThat(conventions)
                    .as("two different filler characters are in live use; unifying them would be a "
                            + "change to input data that no requirement asks for")
                    .containsExactly(' ', '0');
            assertThat(encodedLengthOf(lines("cardxref.txt").getFirst()))
                    .as("and a third dataset has no filler run whatsoever")
                    .isEqualTo(CROSS_REFERENCE_DATA_WIDTH);
        }
    }

    @Nested
    @DisplayName("byte offsets within the layouts, asserted against the copybooks")
    class LayoutOffsets {

        @Test
        @DisplayName("the account layout's five money fields are split around its three date fields, "
                + "so a sentinel placed at each offset arrives in the field that offset belongs to")
        void accountMoneyFieldsAreSplitAroundTheDateFields() throws Exception {
            final String image = accountImageWithDistinctSentinels();
            final Resource dataset = writeLines(temporary("account-field-order.txt"), List.of(image));

            final Account account = readAll(factory.accountReader(dataset)).getFirst();

            assertThat(encodedLengthOf(image)).isEqualTo(ACCOUNT_WIDTH);
            assertThat(account.getAcctId())
                    .as("11 bytes at offset 0").isEqualTo("00000000042");
            assertThat(account.getAcctActiveStatus())
                    .as("1 byte at offset 11").isEqualTo("Y");
            assertThat(account.getAcctCurrBal())
                    .as("12 bytes at offset 12 - the first money field")
                    .isEqualTo(new BigDecimal("1111111111.11"));
            assertThat(account.getAcctCreditLimit())
                    .as("12 bytes at offset 24 - the second money field")
                    .isEqualTo(new BigDecimal("2222222222.22"));
            assertThat(account.getAcctCashCreditLimit())
                    .as("12 bytes at offset 36 - the third money field, and the last one before the "
                            + "dates")
                    .isEqualTo(new BigDecimal("3333333333.33"));
            assertThat(account.getAcctOpenDate())
                    .as("10 bytes at offset 48 - the first date field").isEqualTo("2020-01-02");
            assertThat(account.getAcctExpirationDate())
                    .as("10 bytes at offset 58 - the field whose copybook name is misspelled")
                    .isEqualTo("2030-03-04");
            assertThat(account.getAcctReissueDate())
                    .as("10 bytes at offset 68 - the last date field").isEqualTo("2025-05-06");
            assertThat(account.getAcctCurrCycCredit())
                    .as("12 bytes at offset 78 - a money field that comes AFTER the dates, which is "
                            + "the whole trap: grouping all five money fields together would corrupt "
                            + "every offset from 36 onward")
                    .isEqualTo(new BigDecimal("4444444444.44"));
            assertThat(account.getAcctCurrCycDebit())
                    .as("12 bytes at offset 90, carrying a negative overpunch")
                    .isEqualTo(new BigDecimal("-5555555555.55"));
            assertThat(account.getAcctAddrZip())
                    .as("10 bytes at offset 102").isEqualTo("ZIP0000001");
            assertThat(account.getAcctGroupId())
                    .as("10 bytes at offset 112").isEqualTo("GROUP00001");
        }

        @Test
        @DisplayName("the account layout's published offsets are the ten this suite derived from the "
                + "copybook")
        void accountPublishedOffsets() {
            assertThat(AccountRecordMapper.ACCT_CURR_BAL_OFFSET).isEqualTo(12);
            assertThat(AccountRecordMapper.ACCT_CREDIT_LIMIT_OFFSET).isEqualTo(24);
            assertThat(AccountRecordMapper.ACCT_CASH_CREDIT_LIMIT_OFFSET).isEqualTo(36);
            assertThat(AccountRecordMapper.ACCT_OPEN_DATE_OFFSET).isEqualTo(48);
            assertThat(AccountRecordMapper.ACCT_EXPIRAION_DATE_OFFSET).isEqualTo(58);
            assertThat(AccountRecordMapper.ACCT_REISSUE_DATE_OFFSET).isEqualTo(68);
            assertThat(AccountRecordMapper.ACCT_CURR_CYC_CREDIT_OFFSET).isEqualTo(78);
            assertThat(AccountRecordMapper.ACCT_CURR_CYC_DEBIT_OFFSET).isEqualTo(90);
            assertThat(AccountRecordMapper.ACCT_ADDR_ZIP_OFFSET).isEqualTo(102);
            assertThat(AccountRecordMapper.ACCT_GROUP_ID_OFFSET).isEqualTo(112);
            assertThat(AccountRecordMapper.MAPPED_PREFIX_LENGTH)
                    .as("the data fields end where the filler run begins")
                    .isEqualTo(ACCOUNT_MAPPED_WIDTH);
        }

        @Test
        @DisplayName("both misspelled expiration-date field names leave their byte offsets unchanged")
        void misspelledExpirationDatesKeepTheirOffsets() {
            assertThat(AccountRecordMapper.ACCT_EXPIRAION_DATE_OFFSET)
                    .as("the account expiration date still begins at offset 58 despite the "
                            + "copybook's spelling")
                    .isEqualTo(58);
            assertThat(AccountRecordMapper.ACCT_EXPIRAION_DATE_LENGTH).isEqualTo(10);
            assertThat(CardRecordMapper.CARD_EXPIRAION_DATE_OFFSET)
                    .as("and the card expiration date still begins at offset 80")
                    .isEqualTo(80);
            assertThat(CardRecordMapper.CARD_EXPIRAION_DATE_LENGTH).isEqualTo(10);
        }

        @Test
        @DisplayName("the card layout's reader places the expiration date read from offset eighty into "
                + "the correctly spelled property")
        void cardExpirationDateIsReadFromOffsetEighty() throws Exception {
            final String image = cardImageWithDistinctSentinels();
            final Resource dataset = writeLines(temporary("card-offsets.txt"), List.of(image));

            final Card card = readAll(factory.cardReader(dataset)).getFirst();

            assertThat(encodedLengthOf(image)).isEqualTo(CARD_WIDTH);
            assertThat(card.getCardNum()).as("16 bytes at offset 0").isEqualTo("4111111111111111");
            assertThat(card.getCardAcctId()).as("11 bytes at offset 16").isEqualTo("00000000042");
            assertThat(card.getCardCvvCd()).as("3 bytes at offset 27").isEqualTo("123");
            assertThat(card.getCardEmbossedName())
                    .as("50 bytes at offset 30, trailing spaces intact")
                    .isEqualTo(padRight("MARY ANN CARDHOLDER", 50));
            assertThat(card.getCardExpirationDate())
                    .as("10 bytes at offset 80 - read from the offset the misspelled field declares, "
                            + "exposed through a correctly spelled property")
                    .isEqualTo("2031-12-31");
            assertThat(card.getCardActiveStatus()).as("1 byte at offset 90").isEqualTo("Y");
        }

        @Test
        @DisplayName("the two composite keys sharing one legacy group name are six bytes and seventeen "
                + "bytes, and are never conflated")
        void compositeKeyWidthsAreAssertedSeparately() {
            assertThat(TranCatRecordMapper.TYPE_AND_CATEGORY_KEY_WIDTH)
                    .as("the transaction category key is a two-byte type plus a four-digit category")
                    .isEqualTo(6);
            assertThat(TranCatRecordMapper.TRAN_TYPE_CD_LENGTH
                            + TranCatRecordMapper.TRAN_CAT_CD_LENGTH)
                    .as("and its two parts sum to that width")
                    .isEqualTo(6);
            assertThat(TranCatBalRecordMapper.ACCOUNT_TYPE_AND_CATEGORY_KEY_WIDTH)
                    .as("the category balance key is an eleven-byte account identifier, a two-byte "
                            + "type and a four-digit category")
                    .isEqualTo(17);
            assertThat(TranCatBalRecordMapper.TRANCAT_ACCT_ID_LENGTH
                            + TranCatBalRecordMapper.TRANCAT_TYPE_CD_LENGTH
                            + TranCatBalRecordMapper.TRANCAT_CD_LENGTH)
                    .as("and its three parts sum to that width")
                    .isEqualTo(17);
            assertThat(TranCatRecordMapper.TYPE_AND_CATEGORY_KEY_WIDTH)
                    .as("one legacy group name denotes both keys, so the widths are what tell them "
                            + "apart; treating them as one would mis-key one of the two layouts")
                    .isNotEqualTo(TranCatBalRecordMapper.ACCOUNT_TYPE_AND_CATEGORY_KEY_WIDTH);
            assertThat(DisclosureGroupRecordMapper.KEY_LENGTH)
                    .as("the disclosure group's own three-part key is sixteen bytes, a third distinct "
                            + "width again")
                    .isEqualTo(16);
        }

        @Test
        @DisplayName("the composite keys the entities expose match the key bytes of their records")
        void compositeKeysAreBuiltFromTheKeyBytes() throws Exception {
            final TransactionCategory category =
                    readAll(factory.transactionCategoryReader(fixture("trancatg.txt"))).getFirst();
            final TransactionCategoryBalance balance =
                    readAll(factory.transactionCategoryBalanceReader(fixture("tcatbal.txt")))
                            .getFirst();
            final String categoryImage = lines("trancatg.txt").getFirst();
            final String balanceImage = lines("tcatbal.txt").getFirst();

            assertThat(category.getTranTypeCd() + category.getTranCatCd())
                    .as("the six key bytes, taken from the head of the record")
                    .isEqualTo(categoryImage.substring(0, 6));
            assertThat(balance.getTrancatAcctId() + balance.getTrancatTypeCd()
                            + balance.getTrancatCd())
                    .as("the seventeen key bytes, taken from the head of the record")
                    .isEqualTo(balanceImage.substring(0, 17));
            assertThat(category.toId()).isNotNull();
            assertThat(balance.toId()).isNotNull();
        }

        @Test
        @DisplayName("the disclosure group's sixteen-byte key is followed by a six-byte rate at "
                + "offset sixteen")
        void disclosureGroupRateOffsetAndWidth() throws Exception {
            final String shipped = lines("discgrp.txt").getFirst();
            final String rateSlice = shipped.substring(DISCLOSURE_GROUP_KEY_WIDTH,
                    DISCLOSURE_GROUP_KEY_WIDTH + INTEREST_RATE_WIDTH);

            final DisclosureGroup group =
                    readAll(factory.disclosureGroupReader(fixture("discgrp.txt"))).getFirst();

            assertThat(DisclosureGroupRecordMapper.DIS_INT_RATE_OFFSET)
                    .as("the rate begins where the three-part key ends")
                    .isEqualTo(DISCLOSURE_GROUP_KEY_WIDTH);
            assertThat(DisclosureGroupRecordMapper.DIS_INT_RATE_LENGTH)
                    .as("a four-integer-digit two-decimal zoned field occupies six bytes")
                    .isEqualTo(INTEREST_RATE_WIDTH);
            assertThat(encodedLengthOf(rateSlice)).isEqualTo(INTEREST_RATE_WIDTH);
            assertThat(DisclosureGroupRecordMapper.DIS_INT_RATE_OFFSET
                            + DisclosureGroupRecordMapper.DIS_INT_RATE_LENGTH)
                    .as("and the filler run begins where the rate ends")
                    .isEqualTo(DISCLOSURE_GROUP_MAPPED_WIDTH);
            assertThat(group.getDisAcctGroupId()).isEqualTo(DIRECT_HIT_GROUP_KEY_AS_STORED);
            assertThat(group.getDisIntRate())
                    .as("the sign of a zoned rate is overpunched into its final byte, so the six "
                            + "bytes at offset sixteen decode to a two-decimal value")
                    .isEqualTo(new BigDecimal("15.00"));
        }

        @Test
        @DisplayName("a hand-encoded six-byte rate round-trips through the disclosure group reader")
        void handEncodedRateRoundTrips() throws Exception {
            final String image = DEFAULT_GROUP_KEY_AS_STORED
                    + "01"
                    + "0005"
                    + zonedDecimal("001250", INTEREST_RATE_WIDTH, false)
                    + "0".repeat(DISCLOSURE_GROUP_WIDTH - DISCLOSURE_GROUP_MAPPED_WIDTH);
            final Resource dataset = writeLines(temporary("discgrp-hand-built.txt"), List.of(image));

            final DisclosureGroup group = readAll(factory.disclosureGroupReader(dataset)).getFirst();

            assertThat(encodedLengthOf(image)).isEqualTo(DISCLOSURE_GROUP_WIDTH);
            assertThat(group.getDisAcctGroupId())
                    .as("the padded key survives, three trailing spaces included")
                    .isEqualTo(DEFAULT_GROUP_KEY_AS_STORED);
            assertThat(group.getDisTranTypeCd()).isEqualTo("01");
            assertThat(group.getDisTranCatCd()).isEqualTo("0005");
            assertThat(group.getDisIntRate())
                    .as("encoded by hand rather than by the production codec, so a codec defect "
                            + "cannot agree with this expectation")
                    .isEqualTo(new BigDecimal("12.50"));
        }

        @Test
        @DisplayName("the user security layout's credential field is asserted by position and width "
                + "alone")
        void credentialFieldPositionOnly() {
            assertThat(UserSecurityRecordMapper.SEC_USR_PWD_OFFSET)
                    .as("zero-based offset of the credential window")
                    .isEqualTo(48);
            assertThat(UserSecurityRecordMapper.SEC_USR_PWD_LENGTH)
                    .as("declared width of the credential window")
                    .isEqualTo(8);
            assertThat(UserSecurityRecordMapper.SEC_USR_ID_OFFSET
                            + UserSecurityRecordMapper.SEC_USR_ID_LENGTH
                            + UserSecurityRecordMapper.SEC_USR_FNAME_LENGTH
                            + UserSecurityRecordMapper.SEC_USR_LNAME_LENGTH)
                    .as("the identifier and the two names end exactly where the credential begins, "
                            + "which locates the window without naming any value in it")
                    .isEqualTo(UserSecurityRecordMapper.SEC_USR_PWD_OFFSET);
            assertThat(UserSecurityRecordMapper.SEC_USR_TYPE_OFFSET)
                    .as("and the type byte begins where the credential window ends")
                    .isEqualTo(UserSecurityRecordMapper.SEC_USR_PWD_OFFSET
                            + UserSecurityRecordMapper.SEC_USR_PWD_LENGTH);
        }

        @Test
        @DisplayName("the two sort offsets the external specifications address are the card number at "
                + "one-based column 263 and the processing timestamp at zero-based 304")
        void transactionSortOffsets() {
            assertThat(TransactionRecordMapper.TRAN_CARD_NUM_OFFSET)
                    .as("zero-based 262 is one-based column 263")
                    .isEqualTo(262);
            assertThat(TransactionRecordMapper.TRAN_CARD_NUM_OFFSET + 1)
                    .isEqualTo(TestDataFactory.SORT_CARD_NUMBER_POSITION);
            assertThat(TransactionRecordMapper.TRAN_ORIG_TS_OFFSET).isEqualTo(278);
            assertThat(TransactionRecordMapper.TRAN_PROC_TS_OFFSET).isEqualTo(304);
            assertThat(TransactionRecordMapper.MAPPED_DATA_LENGTH)
                    .as("the data fields end where the twenty-byte filler run begins")
                    .isEqualTo(TRANSACTION_MAPPED_WIDTH);
        }
    }

    @Nested
    @DisplayName("record content reaches the mapper untouched, trailing spaces included")
    class ContentSurvivesUntouched {

        @Test
        @DisplayName("a seven-character group key padded to ten keeps its three trailing spaces")
        void paddedGroupKeysKeepTheirTrailingSpaces() throws Exception {
            final List<DisclosureGroup> groups =
                    readAll(factory.disclosureGroupReader(fixture("discgrp.txt")));

            final Set<String> keys = groups.stream()
                    .map(DisclosureGroup::getDisAcctGroupId)
                    .collect(Collectors.toCollection(LinkedHashSet::new));

            assertThat(groups).hasSize(DISCLOSURE_GROUP_RECORDS);
            assertThat(keys)
                    .as("three complete groups of seventeen rows, so the direct hit, the default "
                            + "fallback and the zero-rate skip are all reachable from seeded data")
                    .hasSize(DISCLOSURE_GROUP_KEY_COUNT)
                    .containsExactly(DIRECT_HIT_GROUP_KEY_AS_STORED, DEFAULT_GROUP_KEY_AS_STORED,
                            ZERO_RATE_GROUP_KEY_AS_STORED);
            assertThat(keys).allSatisfy(key -> assertThat(encodedLengthOf(key))
                    .as("every key occupies the field's full ten bytes, padded rather than trimmed")
                    .isEqualTo(GROUP_ID_WIDTH));
            assertThat(DEFAULT_GROUP_KEY_AS_STORED)
                    .as("a bare seven-character key would never match the stored value")
                    .isNotEqualTo(DEFAULT_GROUP_KEY_AS_STORED.trim())
                    .endsWith("   ");
            assertThat(ZERO_RATE_GROUP_KEY_AS_STORED)
                    .isNotEqualTo(ZERO_RATE_GROUP_KEY_AS_STORED.trim())
                    .endsWith("   ");
        }

        @Test
        @DisplayName("this suite's padded keys agree with the shared support constants, so one "
                + "definition of the padding cannot drift from the other")
        void paddedKeysAgreeWithTheSharedConstants() {
            assertThat(DEFAULT_GROUP_KEY_AS_STORED)
                    .isEqualTo(TestDataFactory.FALLBACK_DISCLOSURE_GROUP_ID);
            assertThat(ZERO_RATE_GROUP_KEY_AS_STORED)
                    .isEqualTo(TestDataFactory.ZERO_RATE_DISCLOSURE_GROUP_ID);
            assertThat(DIRECT_HIT_GROUP_KEY_AS_STORED)
                    .isEqualTo(TestDataFactory.DIRECT_HIT_DISCLOSURE_GROUP_ID);
            assertThat(TestDataFactory.DISCLOSURE_GROUP_ROWS_PER_GROUP
                            * DISCLOSURE_GROUP_KEY_COUNT)
                    .as("three groups of seventeen rows account for every record in the dataset")
                    .isEqualTo(DISCLOSURE_GROUP_RECORDS);
        }

        @Test
        @DisplayName("a ten-character source field holding a six-character value keeps its four "
                + "trailing spaces")
        void sixCharacterSourceKeepsFourTrailingSpaces() throws Exception {
            final String systemSource = TransactionSourceType.SYSTEM.getValue();
            final Resource dataset = writeLines(temporary("transaction-system-source.txt"),
                    List.of(transactionImageWithSource(systemSource)));

            final Transaction transaction = readAll(factory.transactionReader(dataset)).getFirst();

            assertThat(encodedLengthOf(systemSource))
                    .as("the source field is ten bytes wide whatever it holds")
                    .isEqualTo(SOURCE_FIELD_WIDTH);
            assertThat(systemSource.strip())
                    .as("the value itself is six characters, so four bytes of padding follow it")
                    .hasSize(6);
            assertThat(systemSource).endsWith("    ");
            assertThat(transaction.getTranSource())
                    .as("the reader returns the padded field exactly, not the stripped value")
                    .isEqualTo(systemSource)
                    .isNotEqualTo(systemSource.strip());
        }

        @Test
        @DisplayName("the shipped daily dataset's own source values keep their two trailing spaces")
        void shippedSourceValuesKeepTheirTrailingSpaces() throws Exception {
            final List<DailyTransaction> transactions =
                    readAll(factory.dailyTransactionReader(fixture("dailytran.txt")));

            final Set<String> sources = transactions.stream()
                    .map(DailyTransaction::getDalytranSource)
                    .collect(Collectors.toCollection(LinkedHashSet::new));

            assertThat(sources)
                    .as("the dataset carries two eight-character sources, each padded to ten")
                    .containsExactlyInAnyOrder(TransactionSourceType.POS_TERM.getValue(),
                            TransactionSourceType.OPERATOR.getValue());
            assertThat(sources).allSatisfy(source -> assertThat(encodedLengthOf(source))
                    .isEqualTo(SOURCE_FIELD_WIDTH));
            assertThat(sources).allSatisfy(source -> assertThat(source).endsWith("  "));
        }

        @Test
        @DisplayName("an unpopulated twenty-six-byte timestamp comes back as twenty-six spaces, not "
                + "null, not empty and not a parsed instant")
        void blankTimestampComesBackAsSpaces() throws Exception {
            final List<DailyTransaction> transactions =
                    readAll(factory.dailyTransactionReader(fixture("dailytran.txt")));

            assertThat(transactions).hasSize(DAILY_TRANSACTION_RECORDS);
            assertThat(transactions).allSatisfy(transaction -> {
                final String processingTimestamp = transaction.getDalytranProcTs();
                assertThat(processingTimestamp)
                        .as("the legacy program never populated this field, and a blank field is "
                                + "still a field")
                        .isNotNull()
                        .isNotEmpty()
                        .isEqualTo(" ".repeat(TIMESTAMP_WIDTH));
                assertThat(encodedLengthOf(processingTimestamp)).isEqualTo(TIMESTAMP_WIDTH);
            });
            assertThat(transactions.getFirst().getDalytranProcTs())
                    .isEqualTo(TestDataFactory.BLANK_PROCESSING_TIMESTAMP);
        }

        @Test
        @DisplayName("a record whose first character is a hash is read, not silently discarded as a "
                + "comment")
        void hashLeadingRecordIsNotTreatedAsAComment() throws Exception {
            final String valid = lines("discgrp.txt").getFirst();
            final String hashLeading = "#" + valid.substring(1);
            final Resource dataset =
                    writeLines(temporary("discgrp-hash.txt"), List.of(hashLeading, valid));

            final List<DisclosureGroup> groups = readAll(factory.disclosureGroupReader(dataset));

            assertThat(encodedLengthOf(hashLeading))
                    .as("only the first byte differs, so the record is still a valid image")
                    .isEqualTo(DISCLOSURE_GROUP_WIDTH);
            assertThat(groups)
                    .as("comment recognition is disabled; the framework default would have dropped "
                            + "the first record with no exception and no count discrepancy")
                    .hasSize(2);
            assertThat(groups.getFirst().getDisAcctGroupId()).startsWith("#");
        }

        @Test
        @DisplayName("a carriage-return terminator is consumed as a terminator, never as record data")
        void carriageReturnIsNotRecordData() throws Exception {
            final String image = lines("trantype.txt").getFirst();
            final Path file = temporary("trantype-crlf.txt");
            Files.writeString(file, image + "\r\n" + image + "\r\n", StandardCharsets.US_ASCII);

            final List<TransactionType> types =
                    readAll(factory.transactionTypeReader(new FileSystemResource(file)));

            assertThat(types).hasSize(2);
            assertThat(types.getFirst().getTranTypeDesc())
                    .as("a terminator left in the image would have counted towards the width and the "
                            + "mapper would have refused the record")
                    .isEqualTo(types.getLast().getTranTypeDesc());
        }

        @Test
        @DisplayName("an embossed name holding an embedded space keeps it, and keeps its padding")
        void embeddedSpacesSurvive() throws Exception {
            final Resource dataset =
                    writeLines(temporary("card-embedded-space.txt"),
                            List.of(cardImageWithDistinctSentinels()));

            final Card card = readAll(factory.cardReader(dataset)).getFirst();

            assertThat(card.getCardEmbossedName())
                    .as("embedded spaces are data, and the legacy alphabetic check accepts them")
                    .contains(" ")
                    .startsWith("MARY ANN CARDHOLDER");
            assertThat(encodedLengthOf(card.getCardEmbossedName()))
                    .as("and the field is returned at its declared width, not stripped")
                    .isEqualTo(50);
        }
    }

    @Nested
    @DisplayName("failures are reported with an actionable diagnosis, never reinterpreted")
    class Failures {

        @Test
        @DisplayName("a line one byte short of the declared width fails, naming both widths")
        void shortLineFails() throws Exception {
            final String image = lines("trantype.txt").getFirst();
            final Resource truncated = writeLines(temporary("trantype-short.txt"),
                    List.of(image.substring(0, image.length() - 1)));

            assertThatThrownBy(() -> readAll(factory.transactionTypeReader(truncated)))
                    .as("the width check belongs to the mapper and is not duplicated by the reader")
                    .isInstanceOf(FlatFileParseException.class)
                    .cause()
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(String.valueOf(TRANSACTION_TYPE_WIDTH))
                    .hasMessageContaining(String.valueOf(TRANSACTION_TYPE_WIDTH - 1))
                    .hasMessageContaining("never padded or truncated");
        }

        @Test
        @DisplayName("a line one byte longer than the declared width fails rather than being silently "
                + "truncated")
        void longLineFails() throws Exception {
            final String image = lines("trantype.txt").getFirst();
            final Resource oversized =
                    writeLines(temporary("trantype-long.txt"), List.of(image + " "));

            assertThatThrownBy(() -> readAll(factory.transactionTypeReader(oversized)))
                    .isInstanceOf(FlatFileParseException.class)
                    .cause()
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(String.valueOf(TRANSACTION_TYPE_WIDTH))
                    .hasMessageContaining(String.valueOf(TRANSACTION_TYPE_WIDTH + 1))
                    .as("neither padded up nor truncated down: an overshoot is reported, and the "
                            + "message names the usual cause")
                    .hasMessageContaining("never padded or truncated");
        }

        @Test
        @DisplayName("the failure names the resource and the line number that produced it")
        void failureNamesTheResourceAndLine() throws Exception {
            final String image = lines("trantype.txt").getFirst();
            final Resource dataset = writeLines(temporary("trantype-second-bad.txt"),
                    List.of(image, image.substring(0, image.length() - 2)));

            assertThatThrownBy(() -> readAll(factory.transactionTypeReader(dataset)))
                    .isInstanceOf(FlatFileParseException.class)
                    .hasMessageContaining("trantype-second-bad.txt");
        }

        @Test
        @DisplayName("the terminator-free user security dataset is refused by a line-oriented reader "
                + "rather than mis-split")
        void terminatorFreeDatasetIsRefused() {
            final FlatFileItemReader<UserSecurity> reader =
                    factory.userSecurityReader(fixture("usrsec.txt"), digestFunction());

            assertThatThrownBy(() -> readAll(reader))
                    .isInstanceOf(FlatFileParseException.class)
                    .cause()
                    .as("the whole file arrives as one line and the mapper measures it, so the "
                            + "boundary is reported instead of being guessed at")
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("a missing resource fails when the reader opens, never as an empty successful run")
        void missingResourceFailsOnOpen() {
            final FlatFileItemReader<Account> reader = factory.accountReader(
                    new FileSystemResource(temporary("absent.txt")));

            assertThatThrownBy(() -> reader.open(new ExecutionContext()))
                    .as("strict resource presence mirrors the legacy programs, every one of which "
                            + "treats a failed open as terminal")
                    .isInstanceOf(ItemStreamException.class);
        }

        @Test
        @DisplayName("every reader rejects a null resource")
        void nullResourceIsRejected() {
            assertThatThrownBy(() -> factory.accountReader(null))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> factory.cardReader(null))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> factory.cardCrossReferenceReader(null))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> factory.customerReader(null, sealer()))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> factory.transactionReader(null))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> factory.fixedTransactionReader(null))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> factory.dailyTransactionReader(null))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> factory.transactionCategoryBalanceReader(null))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> factory.disclosureGroupReader(null))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> factory.transactionTypeReader(null))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> factory.transactionCategoryReader(null))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> factory.userSecurityReader(null, digestFunction()))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("the two layouts needing a caller-owned function reject a null function by name")
        void nullPolicyFunctionIsRejected() {
            final Resource anyResource = fixture("custdata.txt");

            assertThatThrownBy(() -> factory.customerReader(anyResource, null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("regulatedFieldSealer");
            assertThatThrownBy(() -> factory.userSecurityReader(anyResource, null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("credentialDigestFunction");
        }

        @Test
        @DisplayName("a fixed-unblocked resource ending mid-stride reports how far it got")
        void incompleteStrideIsReported() throws Exception {
            final String image = lines("dailytran.txt").getFirst();
            final Path generation = temporary("interest-generation-truncated");
            Files.writeString(generation, image + image.substring(0, 10),
                    StandardCharsets.US_ASCII);

            assertThatThrownBy(() -> readAll(
                    factory.fixedTransactionReader(new FileSystemResource(generation))))
                    .as("a partial trailing stride is an input failure, not a short final record; the "
                            + "framework reports the unreadable resource and carries the stride "
                            + "diagnosis as its cause")
                    .isInstanceOf(NonTransientFlatFileException.class)
                    .cause()
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("fixed-unblocked")
                    .hasMessageContaining("ended after")
                    .hasMessageContaining(String.valueOf(TRANSACTION_WIDTH));
        }
    }

    @Nested
    @DisplayName("reader instances, names and configuration")
    class ReaderIdentity {

        @Test
        @DisplayName("every call returns a new reader, so no cursor is ever shared")
        void everyCallReturnsANewInstance() {
            assertThat(factory.accountReader(fixture("acctdata.txt")))
                    .isNotSameAs(factory.accountReader(fixture("acctdata.txt")));
            assertThat(factory.dailyTransactionReader(fixture("dailytran.txt")))
                    .isNotSameAs(factory.dailyTransactionReader(fixture("dailytran.txt")));
            assertThat(factory.disclosureGroupReader(fixture("discgrp.txt")))
                    .isNotSameAs(factory.disclosureGroupReader(fixture("discgrp.txt")));
            assertThat(factory.fixedTransactionReader(fixture("dailytran.txt")))
                    .isNotSameAs(factory.fixedTransactionReader(fixture("dailytran.txt")));
        }

        @Test
        @DisplayName("two readers over one dataset advance independently")
        void readersDoNotShareACursor() throws Exception {
            final List<TransactionType> inFileOrder =
                    readAll(factory.transactionTypeReader(fixture("trantype.txt")));
            final FlatFileItemReader<TransactionType> first =
                    factory.transactionTypeReader(fixture("trantype.txt"));
            final FlatFileItemReader<TransactionType> second =
                    factory.transactionTypeReader(fixture("trantype.txt"));
            first.open(new ExecutionContext());
            second.open(new ExecutionContext());
            try {
                first.read();
                first.read();

                assertThat(second.read().getTranType())
                        .as("the second reader starts at the first record whatever the first reader "
                                + "has already consumed")
                        .isEqualTo(inFileOrder.get(0).getTranType());
                assertThat(first.read().getTranType())
                        .as("and the first reader resumes at its own third record")
                        .isEqualTo(inFileOrder.get(2).getTranType());
            } finally {
                first.close();
                second.close();
            }
        }

        @Test
        @DisplayName("the reader names are stable across calls for one layout")
        void readerNamesAreStableAcrossCalls() throws Exception {
            final ExecutionContext firstContext = new ExecutionContext();
            final ExecutionContext secondContext = new ExecutionContext();

            saveProgress(factory.accountReader(fixture("acctdata.txt")), firstContext);
            saveProgress(factory.accountReader(fixture("acctdata.txt")), secondContext);

            assertThat(keysOf(firstContext)).isNotEmpty();
            assertThat(keysOf(secondContext))
                    .as("a name composed at run time would change the restart key between calls and "
                            + "silently strand a restarted execution")
                    .isEqualTo(keysOf(firstContext));
            assertThat(keysOf(secondContext)).allSatisfy(key -> assertThat(key)
                    .startsWith(FixedWidthFlatFileReaderFactory.ACCOUNT_READER_NAME + "."));
        }

        @Test
        @DisplayName("the reader names are distinct across layouts, so no restart key collides")
        void readerNamesAreDistinctAcrossLayouts() {
            final List<String> names = List.of(
                    FixedWidthFlatFileReaderFactory.ACCOUNT_READER_NAME,
                    FixedWidthFlatFileReaderFactory.CARD_READER_NAME,
                    FixedWidthFlatFileReaderFactory.CARD_CROSS_REFERENCE_READER_NAME,
                    FixedWidthFlatFileReaderFactory.CUSTOMER_READER_NAME,
                    FixedWidthFlatFileReaderFactory.TRANSACTION_READER_NAME,
                    FixedWidthFlatFileReaderFactory.FIXED_TRANSACTION_READER_NAME,
                    FixedWidthFlatFileReaderFactory.DAILY_TRANSACTION_READER_NAME,
                    FixedWidthFlatFileReaderFactory.TRANSACTION_CATEGORY_BALANCE_READER_NAME,
                    FixedWidthFlatFileReaderFactory.DISCLOSURE_GROUP_READER_NAME,
                    FixedWidthFlatFileReaderFactory.TRANSACTION_TYPE_READER_NAME,
                    FixedWidthFlatFileReaderFactory.TRANSACTION_CATEGORY_READER_NAME,
                    FixedWidthFlatFileReaderFactory.USER_SECURITY_READER_NAME);

            assertThat(names)
                    .as("eleven layouts plus the transaction layout's second physical boundary")
                    .hasSize(LAYOUT_COUNT + 1)
                    .doesNotHaveDuplicates()
                    .doesNotContainNull();
            assertThat(names).allSatisfy(name -> assertThat(name).isNotBlank());
            assertThat(FixedWidthFlatFileReaderFactory.TRANSACTION_READER_NAME)
                    .as("the two identical 350-byte layouts must not share a restart key")
                    .isNotEqualTo(FixedWidthFlatFileReaderFactory.DAILY_TRANSACTION_READER_NAME);
            assertThat(FixedWidthFlatFileReaderFactory.FIXED_TRANSACTION_READER_NAME)
                    .as("nor may the two physical boundary modes over one layout")
                    .isNotEqualTo(FixedWidthFlatFileReaderFactory.TRANSACTION_READER_NAME);
            assertThat(FixedWidthFlatFileReaderFactory.CARD_CROSS_REFERENCE_READER_NAME)
                    .as("nor may any two of the three fifty-byte layouts")
                    .isNotEqualTo(
                            FixedWidthFlatFileReaderFactory.TRANSACTION_CATEGORY_BALANCE_READER_NAME)
                    .isNotEqualTo(FixedWidthFlatFileReaderFactory.DISCLOSURE_GROUP_READER_NAME);
        }

        @Test
        @DisplayName("a reader persists its progress under its own declared name")
        void progressIsSavedUnderTheDeclaredName() throws Exception {
            final ExecutionContext context = new ExecutionContext();

            saveProgress(factory.accountReader(fixture("acctdata.txt")), context);

            assertThat(context.entrySet()).isNotEmpty();
            assertThat(keysOf(context)).allSatisfy(key -> assertThat(key)
                    .as("restart metadata is keyed on the reader's stable name, which is how a "
                            + "restarted execution resumes this reader and no other")
                    .startsWith(FixedWidthFlatFileReaderFactory.ACCOUNT_READER_NAME + "."));
        }

        @Test
        @DisplayName("two layouts' readers write restart metadata under disjoint keys")
        void restartKeysDoNotOverlapAcrossLayouts() throws Exception {
            final ExecutionContext master = new ExecutionContext();
            final ExecutionContext daily = new ExecutionContext();

            saveProgress(factory.transactionReader(fixture("dailytran.txt")), master);
            saveProgress(factory.dailyTransactionReader(fixture("dailytran.txt")), daily);

            assertThat(keysOf(master)).isNotEmpty();
            assertThat(keysOf(daily)).isNotEmpty();
            assertThat(keysOf(master))
                    .as("identical images, identical widths, and still no shared restart key")
                    .doesNotContainAnyElementsOf(keysOf(daily));
        }

        @Test
        @DisplayName("the charset is named US-ASCII rather than left to a framework default")
        void charsetIsNamedExplicitly() {
            assertThat(FixedWidthFlatFileReaderFactory.RECORD_CHARSET_NAME)
                    .as("the reader's own default is UTF-8, and a default is the wrong thing to rely "
                            + "on for a byte-exact contract")
                    .isEqualTo(StandardCharsets.US_ASCII.name());
        }
    }

    @Nested
    @DisplayName("the caller's policy functions are applied by the mapper, once per field")
    class PolicyFunctions {

        @Test
        @DisplayName("the customer reader applies the caller's sealer to the two regulated fields only")
        void sealerIsApplied() throws Exception {
            final AtomicInteger invocations = new AtomicInteger();
            final UnaryOperator<String> counting = value -> {
                invocations.incrementAndGet();
                return seal(value);
            };

            final List<Customer> customers =
                    readAll(factory.customerReader(fixture("custdata.txt"), counting));

            assertThat(customers).hasSize(FIFTY_RECORDS);
            assertThat(invocations.get())
                    .as("two regulated identifiers per record and no other field")
                    .isEqualTo(FIFTY_RECORDS * 2);
            assertThat(customers.getFirst().getCustSsn())
                    .startsWith(SensitiveFieldCodec.ENVELOPE_PREFIX);
        }

        @Test
        @DisplayName("the user security reader applies the caller's digest once per record")
        void digestIsApplied() throws Exception {
            final AtomicInteger invocations = new AtomicInteger();
            final UnaryOperator<String> counting = value -> {
                invocations.incrementAndGet();
                return SYNTHETIC_DIGEST;
            };
            final Resource terminated = writeLines(temporary("usrsec-digest.txt"),
                    stridedRecords("usrsec.txt", USER_SECURITY_WIDTH));

            final List<UserSecurity> users =
                    readAll(factory.userSecurityReader(terminated, counting));

            assertThat(users).hasSize(USER_SECURITY_RECORDS).doesNotContainNull();
            assertThat(invocations.get())
                    .as("the credential window is handed to the caller's function exactly once per "
                            + "record, and nothing else is")
                    .isEqualTo(USER_SECURITY_RECORDS);
            assertThat(users).allSatisfy(user -> assertThat(user.credentialDigest())
                    .as("the entity carries what the caller's function returned, so no cleartext "
                            + "credential is ever bound to a name or persisted")
                    .isEqualTo(SYNTHETIC_DIGEST));
            assertThat(SYNTHETIC_DIGEST)
                    .as("the stand-in has the shape the entity insists on, which makes the assertion "
                            + "above about routing rather than about hashing")
                    .hasSize(DIGEST_LENGTH)
                    .startsWith(DIGEST_PREFIX);
        }
    }

    @Nested
    @DisplayName("fixtures are discovered on the test classpath and nowhere else")
    class FixtureDiscovery {

        @Test
        @DisplayName("all ten named datasets resolve from the classpath")
        void allNamedFixturesResolve() {
            final List<String> named = List.of("acctdata.txt", "carddata.txt", "cardxref.txt",
                    "custdata.txt", "dailytran.txt", "discgrp.txt", "tcatbal.txt", "trancatg.txt",
                    "trantype.txt", "usrsec.txt");

            assertThat(named).hasSize(10).doesNotHaveDuplicates();
            named.forEach(FixedWidthFlatFileReaderFactoryTest::requireFixture);
        }

        @Test
        @DisplayName("an absent fixture fails with a diagnosis naming the fixture and where it was "
                + "expected")
        void absentFixtureFailsWithAnActionableDiagnosis() {
            assertThatThrownBy(() -> requireFixture("not-a-shipped-dataset.txt"))
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("not-a-shipped-dataset.txt")
                    .as("the diagnosis names the classpath location, so a missing fixture is a "
                            + "one-line fix rather than an investigation")
                    .hasMessageContaining(FIXTURES);
        }

        @Test
        @DisplayName("the datasets are reached as classpath resources, never by a repository path")
        void fixturesAreClasspathResources() {
            final ClassPathResource resource = fixture("acctdata.txt");

            assertThat(resource)
                    .as("a hardcoded path into the legacy tree would make the module's build depend "
                            + "on a tree the module does not ship")
                    .isInstanceOf(ClassPathResource.class);
            assertThat(resource.exists()).isTrue();
            assertThat(resource.getPath()).isEqualTo(FIXTURES + "acctdata.txt");
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Parameterized-case assembly
    // ---------------------------------------------------------------------------------------------

    /**
     * Assembles one layout case for the parameterized sweep.
     *
     * @param  layoutName    the layout's name, used as the case's display name
     * @param  canonicalWidth the encoded width the copybook declares
     * @param  datasetWidth  the encoded width the shipped dataset actually carries
     * @param  entityType    the entity the layout's reader must produce
     * @param  expectedRecords the record count of the shipped dataset
     * @param  fixtureName   the dataset's file name on the test classpath
     * @param  readerCall    the factory call under test
     * @return the assembled case
     */
    private static Arguments layoutCase(final String layoutName, final int canonicalWidth,
            final int datasetWidth, final Class<?> entityType, final int expectedRecords,
            final String fixtureName,
            final BiFunction<FixedWidthFlatFileReaderFactory, Resource,
                    FlatFileItemReader<?>> readerCall) {
        return Arguments.of(layoutName, canonicalWidth, datasetWidth, entityType, expectedRecords,
                fixtureName, readerCall);
    }

    /**
     * Narrows a parameterized case's entity-type element.
     *
     * <p>A wildcard-parameterized {@code Class} is a reifiable type, so this is an ordinary checked
     * cast and not a reflective operation: nothing is looked up by name and no member is accessed.
     *
     * @param  element the element as the case carries it
     * @return the entity type
     */
    private static Class<?> asEntityType(final Object element) {
        return (Class<?>) element;
    }

    // ---------------------------------------------------------------------------------------------
    // Reader driving
    // ---------------------------------------------------------------------------------------------

    /**
     * Drains a reader completely, opening and closing it exactly as a step would.
     *
     * @param  <T>    the domain type the reader produces
     * @param  reader the reader to drain
     * @return every item the reader produced, in file order
     * @throws Exception if the reader fails, which several cases here assert on
     */
    private static <T> List<T> readAll(final FlatFileItemReader<T> reader) throws Exception {
        final List<T> items = new ArrayList<>();
        reader.open(new ExecutionContext());
        try {
            T item = reader.read();
            while (item != null) {
                items.add(item);
                item = reader.read();
            }
        } finally {
            reader.close();
        }
        return items;
    }

    /**
     * Reads one record and asks the reader to persist its progress into the supplied context.
     *
     * @param  reader  the reader to advance
     * @param  context the context to write restart metadata into
     * @throws Exception if the reader fails
     */
    private static void saveProgress(final FlatFileItemReader<?> reader,
            final ExecutionContext context) throws Exception {
        reader.open(context);
        try {
            reader.read();
            reader.update(context);
        } finally {
            reader.close();
        }
    }

    /**
     * Returns the keys an execution context holds.
     *
     * @param  context the context to inspect
     * @return the keys, order preserved as the context reports them
     */
    private static Set<String> keysOf(final ExecutionContext context) {
        return context.entrySet().stream()
                .map(java.util.Map.Entry::getKey)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    // ---------------------------------------------------------------------------------------------
    // Fixture access, on the test classpath only
    // ---------------------------------------------------------------------------------------------

    /**
     * Returns a named dataset as a classpath resource.
     *
     * @param  fileName the dataset's file name
     * @return the resource, never {@code null}
     */
    private static ClassPathResource fixture(final String fileName) {
        return new ClassPathResource(FIXTURES + fileName);
    }

    /**
     * Returns a named dataset, failing with an actionable diagnosis if it is not on the classpath.
     *
     * @param  fileName the dataset's file name
     * @return the resource, which is guaranteed to exist
     */
    private static Resource requireFixture(final String fileName) {
        final ClassPathResource resource = fixture(fileName);
        if (!resource.exists()) {
            fail(String.format(Locale.ROOT,
                    "required dataset %s is not on the test classpath; it was expected at %s%s, "
                            + "which is the copy under src/test/resources/%s%s - the legacy tree "
                            + "under app/data/ASCII is read-only reference and is never opened at "
                            + "run time, so restore the classpath copy rather than pointing the "
                            + "suite at the legacy path",
                    fileName, FIXTURES, fileName, FIXTURES, fileName));
        }
        return resource;
    }

    /**
     * Reads a newline-terminated dataset as its record images, terminators removed.
     *
     * @param  fileName the dataset's file name
     * @return the record images, in file order
     * @throws IOException if the dataset cannot be read
     */
    private static List<String> lines(final String fileName) throws IOException {
        return List.of(contentOf(fileName).split(NEWLINE));
    }

    /**
     * Returns a dataset's record images however that dataset happens to be shaped.
     *
     * <p>Nine of the ten shipped datasets are newline-terminated and split on the terminator. The user
     * security dataset is the exception: it is its records concatenated with no separator at all,
     * mirroring the fixed-unblocked record format the legacy definition declares, so it is sliced at
     * its declared width instead. The slicing is this suite's own arithmetic over its own declared
     * width and says nothing about the class under test, which slices nothing.
     *
     * @param  fileName    the dataset's file name
     * @param  recordWidth the encoded width of one record in that dataset
     * @return the record images, in file order
     * @throws IOException if the dataset cannot be read
     */
    private static List<String> recordImagesOf(final String fileName, final int recordWidth)
            throws IOException {
        requireFixture(fileName);
        return isTerminatorFree(fileName) ? stridedRecords(fileName, recordWidth) : lines(fileName);
    }

    /**
     * Returns a resource holding one record per line, copying the dataset only when it needs one.
     *
     * @param  fileName the dataset's file name
     * @param  images   that dataset's record images
     * @return the shipped classpath resource, or a newline-terminated temporary copy of it
     * @throws IOException if the copy cannot be written
     */
    private Resource lineTerminatedResource(final String fileName, final List<String> images)
            throws IOException {
        if (!isTerminatorFree(fileName)) {
            return fixture(fileName);
        }
        return writeLines(temporary("terminated-" + fileName), images);
    }

    /**
     * Reports whether a shipped dataset carries no record separator.
     *
     * @param  fileName the dataset's file name
     * @return {@code true} only for the strided user security dataset
     */
    private static boolean isTerminatorFree(final String fileName) {
        return "usrsec.txt".equals(fileName);
    }

    /**
     * Splits a terminator-free strided dataset into its fixed-width record images.
     *
     * @param  fileName    the dataset's file name
     * @param  recordWidth the declared width of one record
     * @return the record images, in file order
     * @throws IOException if the dataset cannot be read
     */
    private static List<String> stridedRecords(final String fileName, final int recordWidth)
            throws IOException {
        final String content = contentOf(fileName);
        final List<String> images = new ArrayList<>();
        for (int offset = 0; offset + recordWidth <= content.length(); offset += recordWidth) {
            images.add(content.substring(offset, offset + recordWidth));
        }
        return images;
    }

    /**
     * Reads a dataset in full, one character per byte.
     *
     * @param  fileName the dataset's file name
     * @return the dataset's content
     * @throws IOException if the dataset cannot be read
     */
    private static String contentOf(final String fileName) throws IOException {
        try (InputStream stream = requireFixture(fileName).getInputStream()) {
            return new String(stream.readAllBytes(), StandardCharsets.US_ASCII);
        }
    }

    /**
     * Writes record images to a file, one per line, and returns it as a resource.
     *
     * @param  file   the file to write
     * @param  images the record images
     * @return the file as a resource
     * @throws IOException if the file cannot be written
     */
    private static Resource writeLines(final Path file, final List<String> images)
            throws IOException {
        Files.writeString(file, String.join(NEWLINE, images) + NEWLINE, StandardCharsets.US_ASCII);
        return new FileSystemResource(file);
    }

    /**
     * Resolves a scratch file name inside this test's temporary directory.
     *
     * @param  fileName the scratch file's name
     * @return the resolved path
     */
    private Path temporary(final String fileName) {
        return temporaryDirectory.resolve(fileName);
    }

    // ---------------------------------------------------------------------------------------------
    // Measurement
    // ---------------------------------------------------------------------------------------------

    /**
     * Measures a value in encoded bytes, which is the only width that means anything for a
     * fixed-width record.
     *
     * @param  value the value to measure
     * @return its encoded length
     */
    private static int encodedLengthOf(final String value) {
        return value.getBytes(StandardCharsets.US_ASCII).length;
    }

    /**
     * Asserts that a dataset's filler run is present at the expected width and consists solely of the
     * expected character.
     *
     * @param  fileName        the dataset's file name
     * @param  dataEndOffset   the zero-based offset at which the data fields end
     * @param  recordWidth     the canonical encoded width of one record
     * @param  fillerCharacter the character this dataset pads with
     * @throws IOException if the dataset cannot be read
     */
    private static void assertFillerRun(final String fileName, final int dataEndOffset,
            final int recordWidth, final char fillerCharacter) throws IOException {
        final String image = lines(fileName).getFirst();
        final String fillerRun = image.substring(dataEndOffset, recordWidth);

        assertThat(encodedLengthOf(image))
                .as("%s: the record measures its canonical width", fileName)
                .isEqualTo(recordWidth);
        assertThat(encodedLengthOf(fillerRun))
                .as("%s: the filler run occupies every byte from the data's end to the record's end",
                        fileName)
                .isEqualTo(recordWidth - dataEndOffset);
        assertThat(fillerRun)
                .as("%s: the filler run is this dataset's own measured convention, left as it is",
                        fileName)
                .isEqualTo(String.valueOf(fillerCharacter).repeat(recordWidth - dataEndOffset));
    }

    /**
     * Returns the character a dataset pads its filler run with.
     *
     * @param  fileName      the dataset's file name
     * @param  dataEndOffset the zero-based offset at which the data fields end
     * @param  recordWidth   the canonical encoded width of one record
     * @return the filler character
     * @throws IOException if the dataset cannot be read
     */
    private static char fillerCharacterOf(final String fileName, final int dataEndOffset,
            final int recordWidth) throws IOException {
        final String image = lines(fileName).getFirst();
        assertThat(encodedLengthOf(image)).isEqualTo(recordWidth);
        return image.charAt(dataEndOffset);
    }

    // ---------------------------------------------------------------------------------------------
    // Hand-built record images and their hand-built expectations
    // ---------------------------------------------------------------------------------------------

    /**
     * Encodes an unsigned digit string as a zoned decimal field, folding the sign into the final byte.
     *
     * <p>Written out here rather than delegated, because the codec that does this in production is one
     * of the collaborators these readers are wired to and using it would let a codec defect and this
     * suite agree with each other. The implied decimal point is positional and is never stored: a
     * ten-integer-digit two-decimal field is twelve bytes and a nine-integer-digit two-decimal field is
     * eleven, so the caller supplies the digits already scaled.
     *
     * @param  digits   the unsigned digits, decimals included and the point omitted
     * @param  width    the field's encoded width
     * @param  negative whether the value is negative
     * @return the encoded field, exactly {@code width} bytes wide
     */
    private static String zonedDecimal(final String digits, final int width, final boolean negative) {
        final String scaled = "0".repeat(width - digits.length()) + digits;
        final int finalDigit = scaled.charAt(width - 1) - '0';
        final String overpunch = negative ? NEGATIVE_OVERPUNCH : POSITIVE_OVERPUNCH;
        return scaled.substring(0, width - 1) + overpunch.charAt(finalDigit);
    }

    /**
     * Pads a value with spaces to a field's declared width.
     *
     * @param  value the value to pad
     * @param  width the field's declared width
     * @return the padded value
     */
    private static String padRight(final String value, final int width) {
        return value + " ".repeat(width - value.length());
    }

    /**
     * Builds a 300-byte account image whose every field carries a distinct sentinel.
     *
     * <p>The sentinels are deliberately unlike one another so that the field-order assertion cannot
     * pass by coincidence: if the layout's five money fields were grouped together rather than split
     * around its three date fields, a date would land in a money field and the sentinels would not
     * arrive where they are expected.
     *
     * @return the image, exactly 300 bytes
     */
    private static String accountImageWithDistinctSentinels() {
        return "00000000042"
                + "Y"
                + zonedDecimal("111111111111", ACCOUNT_AMOUNT_WIDTH, false)
                + zonedDecimal("222222222222", ACCOUNT_AMOUNT_WIDTH, false)
                + zonedDecimal("333333333333", ACCOUNT_AMOUNT_WIDTH, false)
                + "2020-01-02"
                + "2030-03-04"
                + "2025-05-06"
                + zonedDecimal("444444444444", ACCOUNT_AMOUNT_WIDTH, false)
                + zonedDecimal("555555555555", ACCOUNT_AMOUNT_WIDTH, true)
                + "ZIP0000001"
                + "GROUP00001"
                + " ".repeat(ACCOUNT_WIDTH - ACCOUNT_MAPPED_WIDTH);
    }

    /**
     * Builds a 150-byte card image whose every field carries a distinct sentinel.
     *
     * @return the image, exactly 150 bytes
     */
    private static String cardImageWithDistinctSentinels() {
        return "4111111111111111"
                + "00000000042"
                + "123"
                + padRight("MARY ANN CARDHOLDER", 50)
                + "2031-12-31"
                + "Y"
                + " ".repeat(CARD_WIDTH - CARD_MAPPED_WIDTH);
    }

    /**
     * Builds a 350-byte transaction image carrying the supplied source field.
     *
     * @param  source the ten-byte source field, padded by the caller
     * @return the image, exactly 350 bytes
     */
    private static String transactionImageWithSource(final String source) {
        return SENTINEL_TRANSACTION_ID
                + "01"
                + "0005"
                + source
                + padRight("SENTINEL TRANSACTION DESCRIPTION", 100)
                + zonedDecimal("123456", TRANSACTION_AMOUNT_WIDTH, false)
                + "000000042"
                + padRight("SENTINEL MERCHANT", 50)
                + padRight("SENTINEL CITY", 50)
                + "Z000000001"
                + "4111111111111111"
                + TestDataFactory.SEEDED_ORIGINAL_TIMESTAMP
                + " ".repeat(TIMESTAMP_WIDTH)
                + " ".repeat(TRANSACTION_WIDTH - TRANSACTION_MAPPED_WIDTH);
    }

    /**
     * Builds a 350-byte image carrying a source the shipped daily dataset also uses.
     *
     * @return the image, exactly 350 bytes
     */
    private static String dailyTransactionImage() {
        return transactionImageWithSource(TransactionSourceType.POS_TERM.getValue());
    }

    // ---------------------------------------------------------------------------------------------
    // Caller-owned policy functions
    // ---------------------------------------------------------------------------------------------

    /**
     * The sealing function this suite hands to the customer reader.
     *
     * @return a sealing function, never {@code null}
     */
    private static UnaryOperator<String> sealer() {
        return FixedWidthFlatFileReaderFactoryTest::seal;
    }

    /**
     * Produces a value of the protected-value envelope shape the customer entity requires.
     *
     * <p>No cryptography and no key: the body is the value's bytes zero-extended to the envelope's
     * minimum length and Base64-encoded behind the module's marker. The entity checks the shape, which
     * is all this suite needs, and the real sealing operation belongs to the layer that holds a key.
     *
     * @param  cleartext the value exactly as the record image carries it
     * @return an envelope-shaped value
     */
    private static String seal(final String cleartext) {
        final byte[] raw = cleartext.getBytes(StandardCharsets.US_ASCII);
        final byte[] body =
                new byte[Math.max(SensitiveFieldCodec.MINIMUM_ENVELOPE_BODY_BYTES, raw.length)];
        System.arraycopy(raw, 0, body, 0, raw.length);
        return SensitiveFieldCodec.ENVELOPE_PREFIX + Base64.getEncoder().encodeToString(body);
    }

    /**
     * The digest function this suite hands to the user security reader.
     *
     * <p>Returns one fixed synthetic value of the required shape for every input, and is deliberately
     * not a hash: what these cases prove is that the reader routes the credential window to the
     * caller's function and stores what the function returned, and a real digest would prove no more
     * than that while making the expectation depend on a hashing implementation.
     *
     * @return a digest function, never {@code null}
     */
    private static UnaryOperator<String> digestFunction() {
        return window -> SYNTHETIC_DIGEST;
    }
}
