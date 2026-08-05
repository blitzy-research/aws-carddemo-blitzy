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
import com.carddemo.util.SensitiveFieldCodec;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.ItemStreamException;
import org.springframework.batch.item.file.FlatFileItemReader;
import org.springframework.batch.item.file.FlatFileParseException;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;

/**
 * Verifies the eleven typed readers this factory builds, driving each one over the production-
 * representative sequential fixture for its layout.
 *
 * <p>Every assertion here is about <em>composition</em>, because that is all the class under test
 * does: that a reader is bound to the right mapper and therefore yields the right domain type, that a
 * record image reaches its mapper byte for byte, that the two layouts sharing a width stay distinct,
 * that a failure is reported rather than reinterpreted, and that each call yields an independent
 * reader. Field offsets, widths and decimal scales are the mappers' contracts and are asserted by the
 * mappers' own suites; repeating them here would create a second place to keep in step with a layout.
 *
 * <p>The fixtures are the copies under {@code src/test/resources/fixtures/input}, read from the test
 * classpath. Nothing under {@code app/} is read: the legacy estate is a read-only reference, and a
 * test that reached into it would make the module's build depend on a tree the module does not ship.
 */
@DisplayName("FixedWidthFlatFileReaderFactory - eleven typed readers over the verified layouts")
class FixedWidthFlatFileReaderFactoryTest {

    /** Classpath directory holding the named sequential fixtures. */
    private static final String FIXTURES = "fixtures/input/";

    /** Records in the account, card, cross-reference, customer and category balance fixtures. */
    private static final int FIFTY_RECORDS = 50;

    /** Records in the daily transaction fixture. */
    private static final int DAILY_TRANSACTION_RECORDS = 300;

    /** Records in the disclosure group fixture: three complete groups of seventeen. */
    private static final int DISCLOSURE_GROUP_RECORDS = 51;

    /** Records in the transaction category fixture. */
    private static final int TRANSACTION_CATEGORY_RECORDS = 18;

    /** Records in the transaction type fixture. */
    private static final int TRANSACTION_TYPE_RECORDS = 7;

    /** Records in the user security fixture. */
    private static final int USER_SECURITY_RECORDS = 10;

    /** Encoded width of one user security record, from the user security copybook. */
    private static final int USER_SECURITY_WIDTH = 80;

    /** Encoded width of one disclosure group record, from the disclosure group copybook. */
    private static final int DISCLOSURE_GROUP_WIDTH = 50;

    /** Encoded width of one transaction record. */
    private static final int TRANSACTION_WIDTH = 350;

    /** Declared width of the disclosure group's account group identifier field. */
    private static final int DISCLOSURE_GROUP_KEY_WIDTH = 10;

    /**
     * The default group key exactly as the record carries it: seven characters padded with spaces to
     * the field's full width. The padding is the point of the assertion that uses it.
     */
    private static final String DEFAULT_GROUP_KEY_AS_STORED = "DEFAULT   ";

    /** The zero-rate group key exactly as the record carries it, padded to the field width. */
    private static final String ZERO_RATE_GROUP_KEY_AS_STORED = "ZEROAPR   ";

    /** Character length of a credential digest, which the user security entity requires exactly. */
    private static final int DIGEST_LENGTH = 60;

    /** Structural prefix of a credential digest: version marker plus cost, seven characters. */
    private static final String DIGEST_PREFIX = "$2b$12$";

    /**
     * Synthetic digest tail. Not a hash, not derived from any value, and not a credential: it exists
     * only to give the entity a value of the shape it insists on.
     */
    private static final String DIGEST_TAIL = "SyntheticDigestTailUsedOnlyByThisReaderFactorySuite00";

    /** The digest the stand-in digest function returns for every input. */
    private static final String SYNTHETIC_DIGEST = DIGEST_PREFIX + DIGEST_TAIL;

    /** Line terminator written into the temporary datasets this suite builds. */
    private static final String NEWLINE = "\n";

    /** The class under test. It is stateless, so one instance serves every case. */
    private final FixedWidthFlatFileReaderFactory factory = new FixedWidthFlatFileReaderFactory();

    @Nested
    @DisplayName("each layout's reader yields its own domain type from its own fixture")
    class EachLayoutReadsItsFixture {

        @Test
        @DisplayName("the account reader maps every record of the 300-byte fixture")
        void accountFixture() throws Exception {
            List<Account> accounts = readAll(factory.accountReader(fixture("acctdata.txt")));

            assertThat(accounts).hasSize(FIFTY_RECORDS).doesNotContainNull();
            assertThat(accounts.getFirst().getAcctId())
                    .as("the leading key is copied verbatim, significant zeros intact")
                    .isEqualTo("00000000001");
        }

        @Test
        @DisplayName("the card reader maps every record of the 150-byte fixture")
        void cardFixture() throws Exception {
            List<Card> cards = readAll(factory.cardReader(fixture("carddata.txt")));

            assertThat(cards).hasSize(FIFTY_RECORDS).doesNotContainNull();
            assertThat(cards.getFirst().getCardNum()).isEqualTo("0500024453765740");
        }

        @Test
        @DisplayName("the cross-reference reader maps every record of the 36-byte fixture")
        void crossReferenceFixture() throws Exception {
            List<CardCrossReference> references =
                    readAll(factory.cardCrossReferenceReader(fixture("cardxref.txt")));

            assertThat(references).hasSize(FIFTY_RECORDS).doesNotContainNull();
            assertThat(references.getFirst().getXrefCardNum()).isEqualTo("0500024453765740");
        }

        @Test
        @DisplayName("the customer reader maps every record of the 500-byte fixture")
        void customerFixture() throws Exception {
            List<Customer> customers =
                    readAll(factory.customerReader(fixture("custdata.txt"), sealer()));

            assertThat(customers).hasSize(FIFTY_RECORDS).doesNotContainNull();
            assertThat(customers.getFirst().getCustId()).isEqualTo("000000001");
        }

        @Test
        @DisplayName("the daily transaction reader maps every record of the 350-byte fixture")
        void dailyTransactionFixture() throws Exception {
            List<DailyTransaction> transactions =
                    readAll(factory.dailyTransactionReader(fixture("dailytran.txt")));

            assertThat(transactions).hasSize(DAILY_TRANSACTION_RECORDS).doesNotContainNull();
            assertThat(transactions.getFirst().getDalytranId()).isEqualTo("0000000000683580");
        }

        @Test
        @DisplayName("the transaction reader maps the same 350-byte images to the master type")
        void transactionFixture() throws Exception {
            List<Transaction> transactions =
                    readAll(factory.transactionReader(fixture("dailytran.txt")));

            assertThat(transactions).hasSize(DAILY_TRANSACTION_RECORDS).doesNotContainNull();
            assertThat(transactions.getFirst().getTranId()).isEqualTo("0000000000683580");
        }

        @Test
        @DisplayName("the fixed-unblocked transaction reader splits adjacent 350-byte records")
        void fixedUnblockedTransactionFixture(@TempDir Path directory) throws Exception {
            final List<String> sourceRecords = lines("dailytran.txt").subList(0, 3);
            final Path generation = directory.resolve("interest-generation");
            Files.writeString(generation, String.join("", sourceRecords), StandardCharsets.US_ASCII);

            final List<Transaction> transactions = readAll(
                    factory.fixedTransactionReader(new FileSystemResource(generation)));

            assertThat(transactions).extracting(Transaction::getTranId)
                    .containsExactlyElementsOf(sourceRecords.stream()
                            .map(record -> record.substring(0, 16))
                            .toList());
            assertThat(Files.size(generation)).isEqualTo(3L * TRANSACTION_WIDTH);
        }

        @Test
        @DisplayName("the category balance reader maps every record of its 50-byte fixture")
        void categoryBalanceFixture() throws Exception {
            List<TransactionCategoryBalance> balances =
                    readAll(factory.transactionCategoryBalanceReader(fixture("tcatbal.txt")));

            assertThat(balances).hasSize(FIFTY_RECORDS).doesNotContainNull();
            assertThat(balances.getFirst().getTrancatAcctId()).isEqualTo("00000000001");
        }

        @Test
        @DisplayName("the disclosure group reader maps every record of its 50-byte fixture")
        void disclosureGroupFixture() throws Exception {
            List<DisclosureGroup> groups =
                    readAll(factory.disclosureGroupReader(fixture("discgrp.txt")));

            assertThat(groups).hasSize(DISCLOSURE_GROUP_RECORDS).doesNotContainNull();
            assertThat(groups.stream().map(DisclosureGroup::getDisAcctGroupId).distinct().toList())
                    .as("three complete groups, so both the default fallback and the zero-rate skip "
                            + "are reachable from seeded data alone")
                    .hasSize(3);
        }

        @Test
        @DisplayName("the transaction type reader maps every record of its 60-byte fixture")
        void transactionTypeFixture() throws Exception {
            List<TransactionType> types =
                    readAll(factory.transactionTypeReader(fixture("trantype.txt")));

            assertThat(types).hasSize(TRANSACTION_TYPE_RECORDS).doesNotContainNull();
            assertThat(types.getFirst().getTranType()).isEqualTo("01");
        }

        @Test
        @DisplayName("the transaction category reader maps every record of its 60-byte fixture")
        void transactionCategoryFixture() throws Exception {
            List<TransactionCategory> categories =
                    readAll(factory.transactionCategoryReader(fixture("trancatg.txt")));

            assertThat(categories).hasSize(TRANSACTION_CATEGORY_RECORDS).doesNotContainNull();
            assertThat(categories.getFirst().getTranTypeCd()).isEqualTo("01");
        }

        @Test
        @DisplayName("the user security reader maps every record once the strided fixture is "
                + "terminated one record per line")
        void userSecurityFixture(@TempDir Path directory) throws Exception {
            Resource terminated = writeLines(directory.resolve("usrsec-terminated.txt"),
                    stridedRecords("usrsec.txt", USER_SECURITY_WIDTH));

            List<UserSecurity> users =
                    readAll(factory.userSecurityReader(terminated, digestFunction()));

            assertThat(users).hasSize(USER_SECURITY_RECORDS).doesNotContainNull();
            assertThat(users.getFirst().getSecUsrId()).isEqualTo("ADMIN001");
            assertThat(users).allSatisfy(user -> assertThat(user.credentialDigest())
                    .as("the entity carries the digest the caller's function returned")
                    .isEqualTo(SYNTHETIC_DIGEST));
        }

        @Test
        @DisplayName("the shipped user security fixture is terminator-free, so a line-oriented reader "
                + "rejects it rather than silently mis-splitting it")
        void userSecurityStridedFixtureIsRejected() {
            FlatFileItemReader<UserSecurity> reader =
                    factory.userSecurityReader(fixture("usrsec.txt"), digestFunction());

            assertThatThrownBy(() -> readAll(reader))
                    .isInstanceOf(FlatFileParseException.class)
                    .cause()
                    .as("the mapper measured the whole 800-byte image and refused it")
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("the cross-reference reader accepts both live widths and imposes neither")
    class CrossReferenceWidths {

        @Test
        @DisplayName("the 36-byte data projection and the 50-byte mainframe image yield equal entities")
        void bothWidthsAgree(@TempDir Path directory) throws Exception {
            List<String> dataProjections = lines("cardxref.txt");
            List<String> paddedImages = dataProjections.stream()
                    .map(image -> image + " ".repeat(14))
                    .collect(Collectors.toList());
            Resource padded = writeLines(directory.resolve("cardxref-50.txt"), paddedImages);

            List<CardCrossReference> fromProjection =
                    readAll(factory.cardCrossReferenceReader(fixture("cardxref.txt")));
            List<CardCrossReference> fromMainframeImage =
                    readAll(factory.cardCrossReferenceReader(padded));

            assertThat(paddedImages.getFirst().getBytes(StandardCharsets.US_ASCII))
                    .as("the padded form really is the 50-byte mainframe width")
                    .hasSize(50);
            assertThat(fromMainframeImage)
                    .as("the filler run carries no data, so both widths decode identically")
                    .isEqualTo(fromProjection);
        }

        @Test
        @DisplayName("a width that is neither 36 nor 50 is refused by the mapper, not padded here")
        void anyOtherWidthIsRefused(@TempDir Path directory) throws Exception {
            Resource almost = writeLines(directory.resolve("cardxref-37.txt"),
                    List.of(lines("cardxref.txt").getFirst() + " "));

            assertThatThrownBy(() -> readAll(factory.cardCrossReferenceReader(almost)))
                    .isInstanceOf(FlatFileParseException.class)
                    .cause()
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("record content reaches the mapper untouched")
    class ContentSurvives {

        @Test
        @DisplayName("trailing spaces inside a key field survive, so a padded group key stays padded")
        void trailingSpacesSurvive() throws Exception {
            List<DisclosureGroup> groups =
                    readAll(factory.disclosureGroupReader(fixture("discgrp.txt")));

            Set<String> keys = groups.stream()
                    .map(DisclosureGroup::getDisAcctGroupId)
                    .collect(Collectors.toCollection(LinkedHashSet::new));

            assertThat(keys)
                    .as("no trim, strip or normalisation is applied anywhere on the read path")
                    .contains(DEFAULT_GROUP_KEY_AS_STORED, ZERO_RATE_GROUP_KEY_AS_STORED);
            assertThat(keys).allSatisfy(key -> assertThat(key.getBytes(StandardCharsets.US_ASCII))
                    .hasSize(DISCLOSURE_GROUP_KEY_WIDTH));
        }

        @Test
        @DisplayName("a record whose first character is a hash is read, not silently discarded")
        void hashLeadingRecordIsNotTreatedAsAComment(@TempDir Path directory) throws Exception {
            String valid = lines("discgrp.txt").getFirst();
            String hashLeading = "#" + valid.substring(1);
            Resource dataset = writeLines(directory.resolve("discgrp-hash.txt"),
                    List.of(hashLeading, valid));

            List<DisclosureGroup> groups = readAll(factory.disclosureGroupReader(dataset));

            assertThat(hashLeading.getBytes(StandardCharsets.US_ASCII))
                    .as("only the first byte differs, so the record is still a valid image")
                    .hasSize(DISCLOSURE_GROUP_WIDTH);
            assertThat(groups)
                    .as("comment recognition is disabled; leaving the framework default in place "
                            + "would have dropped the first record with no error at all")
                    .hasSize(2);
            assertThat(groups.getFirst().getDisAcctGroupId()).startsWith("#");
        }

        @Test
        @DisplayName("a carriage-return terminator is consumed as a terminator, not as record data")
        void carriageReturnIsNotRecordData(@TempDir Path directory) throws Exception {
            String image = lines("trantype.txt").getFirst();
            Path file = directory.resolve("trantype-crlf.txt");
            Files.writeString(file, image + "\r\n" + image + "\r\n", StandardCharsets.US_ASCII);

            List<TransactionType> types =
                    readAll(factory.transactionTypeReader(new FileSystemResource(file)));

            assertThat(types).hasSize(2);
            assertThat(types.getFirst().getTranTypeDesc())
                    .isEqualTo(types.getLast().getTranTypeDesc());
        }
    }

    @Nested
    @DisplayName("failures are reported, never reinterpreted")
    class Failures {

        @Test
        @DisplayName("a malformed width surfaces as a parse failure caused by the mapper's own refusal")
        void malformedWidthPropagates(@TempDir Path directory) throws Exception {
            String image = lines("trantype.txt").getFirst();
            Resource truncated = writeLines(directory.resolve("trantype-short.txt"),
                    List.of(image.substring(0, image.length() - 1)));

            assertThatThrownBy(() -> readAll(factory.transactionTypeReader(truncated)))
                    .as("the width check is the mapper's and is not duplicated by the reader")
                    .isInstanceOf(FlatFileParseException.class)
                    .cause()
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("a missing resource fails when the reader opens, never as an empty successful run")
        void missingResourceFailsOnOpen(@TempDir Path directory) {
            FlatFileItemReader<Account> reader = factory.accountReader(
                    new FileSystemResource(directory.resolve("absent.txt")));

            assertThatThrownBy(() -> reader.open(new ExecutionContext()))
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
        @DisplayName("the two layouts needing a caller-owned function reject a null function")
        void nullPolicyFunctionIsRejected() {
            Resource anyResource = fixture("custdata.txt");

            assertThatThrownBy(() -> factory.customerReader(anyResource, null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("regulatedFieldSealer");
            assertThatThrownBy(() -> factory.userSecurityReader(anyResource, null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("credentialDigestFunction");
        }
    }

    @Nested
    @DisplayName("reader identity, instances and configuration")
    class ReaderIdentity {

        @Test
        @DisplayName("every call returns a new reader, so no cursor is ever shared")
        void everyCallReturnsANewInstance() {
            assertThat(factory.accountReader(fixture("acctdata.txt")))
                    .isNotSameAs(factory.accountReader(fixture("acctdata.txt")));
            assertThat(factory.dailyTransactionReader(fixture("dailytran.txt")))
                    .isNotSameAs(factory.dailyTransactionReader(fixture("dailytran.txt")));
            assertThat(factory.fixedTransactionReader(fixture("dailytran.txt")))
                    .isNotSameAs(factory.fixedTransactionReader(fixture("dailytran.txt")));
        }

        @Test
        @DisplayName("two readers over one fixture advance independently")
        void readersDoNotShareACursor() throws Exception {
            List<TransactionType> inFileOrder =
                    readAll(factory.transactionTypeReader(fixture("trantype.txt")));
            FlatFileItemReader<TransactionType> first =
                    factory.transactionTypeReader(fixture("trantype.txt"));
            FlatFileItemReader<TransactionType> second =
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
        @DisplayName("the reader names are distinct, so no restart key collides")
        void readerNamesAreDistinct() {
            List<String> names = List.of(
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

            assertThat(names).doesNotHaveDuplicates().hasSize(12);
            assertThat(FixedWidthFlatFileReaderFactory.TRANSACTION_READER_NAME)
                    .as("the two identical 350-byte layouts must not share a restart key")
                    .isNotEqualTo(FixedWidthFlatFileReaderFactory.DAILY_TRANSACTION_READER_NAME);
            assertThat(FixedWidthFlatFileReaderFactory.FIXED_TRANSACTION_READER_NAME)
                    .as("the two physical boundary modes must not share a restart key")
                    .isNotEqualTo(FixedWidthFlatFileReaderFactory.TRANSACTION_READER_NAME);
        }

        @Test
        @DisplayName("a reader persists its progress under its declared name")
        void progressIsSavedUnderTheDeclaredName() throws Exception {
            FlatFileItemReader<Account> reader = factory.accountReader(fixture("acctdata.txt"));
            ExecutionContext context = new ExecutionContext();
            reader.open(context);
            try {
                reader.read();
                reader.update(context);
            } finally {
                reader.close();
            }

            assertThat(context.entrySet()).isNotEmpty();
            assertThat(context.entrySet()).allSatisfy(entry -> assertThat(entry.getKey())
                    .as("restart metadata is keyed on the reader's stable name")
                    .startsWith(FixedWidthFlatFileReaderFactory.ACCOUNT_READER_NAME + "."));
        }

        @Test
        @DisplayName("the charset is named US-ASCII rather than left to a framework default")
        void charsetIsNamedExplicitly() {
            assertThat(FixedWidthFlatFileReaderFactory.RECORD_CHARSET_NAME)
                    .isEqualTo(StandardCharsets.US_ASCII.name());
        }
    }

    @Nested
    @DisplayName("the caller's policy functions are applied by the mapper, once per record")
    class PolicyFunctions {

        @Test
        @DisplayName("the customer reader applies the caller's sealer to the two regulated fields only")
        void sealerIsApplied() throws Exception {
            AtomicInteger invocations = new AtomicInteger();
            UnaryOperator<String> counting = value -> {
                invocations.incrementAndGet();
                return seal(value);
            };

            List<Customer> customers =
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
        void digestIsApplied(@TempDir Path directory) throws Exception {
            AtomicInteger invocations = new AtomicInteger();
            UnaryOperator<String> counting = value -> {
                invocations.incrementAndGet();
                return SYNTHETIC_DIGEST;
            };
            Resource terminated = writeLines(directory.resolve("usrsec-digest.txt"),
                    stridedRecords("usrsec.txt", USER_SECURITY_WIDTH));

            List<UserSecurity> users = readAll(factory.userSecurityReader(terminated, counting));

            assertThat(users).hasSize(USER_SECURITY_RECORDS);
            assertThat(invocations.get()).isEqualTo(USER_SECURITY_RECORDS);
            assertThat(SYNTHETIC_DIGEST)
                    .as("the stand-in digest has the shape the entity insists on, so the assertion "
                            + "above is about routing rather than about hashing")
                    .hasSize(DIGEST_LENGTH)
                    .startsWith(DIGEST_PREFIX);
        }
    }

    @Nested
    @DisplayName("the two identical 350-byte layouts stay distinct")
    class IdenticalWidthsStayDistinct {

        @Test
        @DisplayName("one image maps to a master transaction or a daily transaction by reader, not by "
                + "anything in the record")
        void oneImageTwoTypes() throws Exception {
            Resource sameFixture = fixture("dailytran.txt");

            Object master = readAll(factory.transactionReader(sameFixture)).getFirst();
            Object daily = readAll(factory.dailyTransactionReader(sameFixture)).getFirst();

            assertThat(master).isInstanceOf(Transaction.class);
            assertThat(daily).isInstanceOf(DailyTransaction.class);
            assertThat(master.getClass())
                    .as("nothing in the 350 bytes distinguishes the two, so only the reader does")
                    .isNotEqualTo(daily.getClass());
        }
    }

    /**
     * Drains a reader completely, opening and closing it exactly as a step would.
     *
     * @param  <T>    the domain type the reader produces
     * @param  reader the reader to drain
     * @return every item the reader produced, in file order
     * @throws Exception if the reader fails, which several cases here assert on
     */
    private static <T> List<T> readAll(final FlatFileItemReader<T> reader) throws Exception {
        List<T> items = new ArrayList<>();
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
     * Returns a named fixture as a classpath resource.
     *
     * @param  fileName the fixture's file name
     * @return the resource, never {@code null}
     */
    private static Resource fixture(final String fileName) {
        return new ClassPathResource(FIXTURES + fileName);
    }

    /**
     * Reads a newline-terminated fixture as its record images, terminators removed.
     *
     * @param  fileName the fixture's file name
     * @return the record images, in file order
     * @throws IOException if the fixture cannot be read
     */
    private static List<String> lines(final String fileName) throws IOException {
        return List.of(contentOf(fileName).split("\n"));
    }

    /**
     * Splits a terminator-free strided fixture into its fixed-width record images.
     *
     * <p>Used for the user security fixture, which is ten records concatenated with no separator at
     * all. Slicing it here is the test's own arithmetic over its own declared width and says nothing
     * about the class under test, which never slices anything.
     *
     * @param  fileName    the fixture's file name
     * @param  recordWidth the declared width of one record
     * @return the record images, in file order
     * @throws IOException if the fixture cannot be read
     */
    private static List<String> stridedRecords(final String fileName, final int recordWidth)
            throws IOException {
        String content = contentOf(fileName);
        List<String> images = new ArrayList<>();
        for (int offset = 0; offset + recordWidth <= content.length(); offset += recordWidth) {
            images.add(content.substring(offset, offset + recordWidth));
        }
        return images;
    }

    /**
     * Reads a fixture in full, one character per byte.
     *
     * @param  fileName the fixture's file name
     * @return the fixture's content
     * @throws IOException if the fixture cannot be read
     */
    private static String contentOf(final String fileName) throws IOException {
        try (InputStream stream = fixture(fileName).getInputStream()) {
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
    private static Resource writeLines(final Path file, final List<String> images) throws IOException {
        Files.writeString(file, String.join(NEWLINE, images) + NEWLINE, StandardCharsets.US_ASCII);
        return new FileSystemResource(file);
    }

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
        byte[] raw = cleartext.getBytes(StandardCharsets.US_ASCII);
        byte[] body = new byte[Math.max(SensitiveFieldCodec.MINIMUM_ENVELOPE_BODY_BYTES, raw.length)];
        System.arraycopy(raw, 0, body, 0, raw.length);
        return SensitiveFieldCodec.ENVELOPE_PREFIX + Base64.getEncoder().encodeToString(body);
    }

    /**
     * The digest function this suite hands to the user security reader.
     *
     * <p>Returns one fixed synthetic value of the required shape for every input. It is deliberately
     * not a hash: this suite proves that the reader routes the credential window to the caller's
     * function and stores what the function returned, and a real digest would prove nothing more.
     *
     * @return a digest function, never {@code null}
     */
    private static UnaryOperator<String> digestFunction() {
        return window -> SYNTHETIC_DIGEST;
    }
}
