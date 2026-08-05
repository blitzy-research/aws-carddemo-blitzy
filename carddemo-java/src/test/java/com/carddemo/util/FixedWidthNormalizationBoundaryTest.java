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
package com.carddemo.util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.function.UnaryOperator;

import com.carddemo.domain.Account;
import com.carddemo.domain.Customer;
import com.carddemo.domain.DailyTransaction;
import com.carddemo.domain.DisclosureGroup;
import com.carddemo.domain.TransactionCategory;
import com.carddemo.domain.TransactionType;
import com.carddemo.domain.UserSecurity;
import com.carddemo.domain.id.DisclosureGroupId;
import com.carddemo.support.SchemaColumnCatalog;
import com.carddemo.support.SeededRecordFixture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the one normalization boundary the fixed-width layer is allowed to have.
 *
 * <h2>The boundary, stated once</h2>
 *
 * <p>A display-text value exists in two legitimate forms in this module, and confusing them is the
 * defect this class exists to prevent:</p>
 * <ul>
 *   <li><strong>In a record image it is always the full declared width</strong>, blank-padded on the
 *       right. That is what the legacy dataset holds, it is what slicing a record produces, and it is
 *       what every writer in this package emits.</li>
 *   <li><strong>In a relational column it is right-trimmed</strong>, because
 *       {@code V3__seed_reference_data.sql} stores display text without its trailing pad. Keeping the
 *       pad as well would store the same information twice, and the writer supplies it on output
 *       anyway.</li>
 * </ul>
 *
 * <p>Both forms are correct, and the reason they can coexist without a parity risk is a single
 * property of {@link FixedWidthFieldReader.Builder#putAlphanumeric(String, int, int, String)}: it
 * left-justifies the value and then writes the remaining bytes of the field as spaces explicitly. A
 * trimmed value and a padded value therefore produce the <em>same record bytes</em>. That is not
 * something to take on trust from a comment, so every mapper that carries a display-text field
 * proves it below by rendering both forms and comparing the images byte for byte.</p>
 *
 * <h2>Three fields where a trailing blank is the value, not padding</h2>
 *
 * <p>Trimming is safe for display text precisely because nothing keys on it. Three columns are the
 * exception, and the seed deliberately keeps their blanks:</p>
 * <ul>
 *   <li>{@code account.acct_group_id} - ten spaces in all fifty seeded rows, which is what sends the
 *       interest calculation down its fallback path rather than a direct group read;</li>
 *   <li>{@code disclosure_group.dis_acct_group_id} - part of a composite key, so
 *       {@code 'DEFAULT   '} and {@code 'DEFAULT'} are different rows and only the padded form
 *       resolves;</li>
 *   <li>{@code daily_transaction.dalytran_proc_ts} - twenty-six spaces in all three hundred seeded
 *       rows, meaning "not yet posted" rather than "no value".</li>
 * </ul>
 *
 * <p>Because the writer pads, a record image cannot police any of these: trimming them would emit
 * identical bytes and fail silently at the database instead. The assertions below therefore hold
 * them at the level where the difference is real - the stored value and the composite key - and pin
 * the seed literals directly.</p>
 *
 * <h2>Driven by the named fixtures, not by invented values</h2>
 *
 * <p>Every padded form here is read out of the sequential fixtures the plan names by filename, so an
 * offset or a width that disagrees with the legacy layout fails rather than agreeing with a value
 * this test made up. A pure in-process suite: no application context, no database, no container.</p>
 */
@DisplayName("Fixed-width normalization boundary - a trimmed column value and a padded record span "
        + "render the same bytes")
class FixedWidthNormalizationBoundaryTest {

    /** Reference-data seed, read as text so its blank-bearing literals can be counted. */
    private static final String SEED_RESOURCE = "/db/migration/V3__seed_reference_data.sql";

    /** A 32-byte key. Readable ASCII so a failure message stays intelligible; it protects nothing. */
    private static final byte[] KEY =
            "carddemo-boundary-test-key-01234".getBytes(StandardCharsets.UTF_8);

    /** Seals a regulated customer field on the way in, exactly as the application path does. */
    private static final UnaryOperator<String> SEALER = value -> SensitiveFieldCodec.protect(value, KEY);

    /** Reveals a regulated customer field on the way out, exactly as the application path does. */
    private static final UnaryOperator<String> REVEALER = value -> SensitiveFieldCodec.reveal(value, KEY);

    /**
     * A syntactically valid BCrypt digest shape: the {@code $2b$} marker, a cost of 12 and a
     * fifty-three character radix-64 tail. It is a shape, not a hash of anything, and it exists only
     * because the user entity refuses a credential that could be cleartext.
     */
    private static final String BCRYPT_SHAPED_DIGEST =
            "$2b$12$abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXY01";

    /** Turns the eight cleartext credential bytes of a user record into a digest-shaped value. */
    private static final UnaryOperator<String> DIGESTER = credential -> BCRYPT_SHAPED_DIGEST;

    /**
     * Right-trims a value, which is the single transformation the seed applies to display text.
     *
     * <p>Deliberately not {@link String#trim()} or {@link String#strip()}: both remove leading
     * whitespace as well, and a leading space in a fixed-width field is part of the value.</p>
     *
     * @param value the padded value
     * @return the value with trailing spaces removed and every other byte untouched
     */
    private static String rightTrim(final String value) {
        int end = value.length();
        while (end > 0 && value.charAt(end - 1) == ' ') {
            end--;
        }
        return value.substring(0, end);
    }

    /** Reads the reference-data seed as text. */
    private static String seedText() {
        try (InputStream stream = FixedWidthNormalizationBoundaryTest.class
                .getResourceAsStream(SEED_RESOURCE)) {
            assertThat(stream)
                    .as("the reference-data seed must be on the test classpath at %s", SEED_RESOURCE)
                    .isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException readFailure) {
            throw new IllegalStateException("the seed at " + SEED_RESOURCE + " could not be read",
                    readFailure);
        }
    }

    @Nested
    @DisplayName("A trimmed value and a padded value render byte-identical record images")
    class TrimmingIsInvisibleInTheRecordImage {

        @Test
        @DisplayName("transaction-type description: 50 bytes in the record, 6 to 13 characters in the column")
        void theTransactionTypeDescription() {
            final SeededRecordFixture fixture =
                    SeededRecordFixture.load("trantype.txt", TranTypeRecordMapper.TRAN_TYPE_RECORD_LENGTH);
            final String image = fixture.record(1);
            final String paddedSpan = fixture.field(1, TranTypeRecordMapper.TRAN_TYPE_DESCRIPTION_OFFSET,
                    TranTypeRecordMapper.TRAN_TYPE_DESCRIPTION_LENGTH);

            assertThat(TranTypeRecordMapper.fromRecord(image).getTranTypeDesc())
                    .as("the reader slices the span verbatim and never trims on the way in")
                    .isEqualTo(paddedSpan)
                    .hasSize(TranTypeRecordMapper.TRAN_TYPE_DESCRIPTION_LENGTH);

            final TransactionType padded = TranTypeRecordMapper.fromRecord(image);
            padded.setTranTypeDesc(paddedSpan);
            final TransactionType trimmed = TranTypeRecordMapper.fromRecord(image);
            trimmed.setTranTypeDesc(rightTrim(paddedSpan));

            assertThat(trimmed.getTranTypeDesc())
                    .as("the two stored forms genuinely differ, so the comparison below is not vacuous")
                    .isNotEqualTo(padded.getTranTypeDesc());
            assertThat(TranTypeRecordMapper.toRecord(trimmed))
                    .isEqualTo(TranTypeRecordMapper.toRecord(padded));
            assertThat(TranTypeRecordMapper.toRecordBytes(trimmed))
                    .isEqualTo(TranTypeRecordMapper.toRecordBytes(padded));
            assertThat(TranTypeRecordMapper.toRecord(trimmed)
                    .substring(TranTypeRecordMapper.TRAN_TYPE_DESCRIPTION_OFFSET,
                            TranTypeRecordMapper.TRAN_TYPE_DESCRIPTION_OFFSET
                                    + TranTypeRecordMapper.TRAN_TYPE_DESCRIPTION_LENGTH))
                    .as("the writer restores the authority bytes, pad included")
                    .isEqualTo(paddedSpan);
        }

        @Test
        @DisplayName("transaction-category description: 50 bytes in the record, 12 to 29 characters in the column")
        void theTransactionCategoryDescription() {
            final SeededRecordFixture fixture =
                    SeededRecordFixture.load("trancatg.txt", TranCatRecordMapper.RECORD_WIDTH);
            final String image = fixture.record(1);
            final String paddedSpan = fixture.field(1, TranCatRecordMapper.TRAN_CAT_TYPE_DESC_OFFSET,
                    TranCatRecordMapper.TRAN_CAT_TYPE_DESC_LENGTH);

            assertThat(TranCatRecordMapper.fromRecord(image).getTranCatTypeDesc())
                    .isEqualTo(paddedSpan)
                    .hasSize(TranCatRecordMapper.TRAN_CAT_TYPE_DESC_LENGTH);

            final TransactionCategory padded = TranCatRecordMapper.fromRecord(image);
            padded.setTranCatTypeDesc(paddedSpan);
            final TransactionCategory trimmed = TranCatRecordMapper.fromRecord(image);
            trimmed.setTranCatTypeDesc(rightTrim(paddedSpan));

            assertThat(trimmed.getTranCatTypeDesc()).isNotEqualTo(padded.getTranCatTypeDesc());
            assertThat(TranCatRecordMapper.toRecord(trimmed))
                    .isEqualTo(TranCatRecordMapper.toRecord(padded));
            assertThat(TranCatRecordMapper.toRecordBytes(trimmed))
                    .isEqualTo(TranCatRecordMapper.toRecordBytes(padded));
        }

        @Test
        @DisplayName("all nine trimmable customer fields at once: three names, three address lines, "
                + "the postal code and both telephone numbers")
        void everyTrimmableCustomerField() {
            final SeededRecordFixture fixture =
                    SeededRecordFixture.load("custdata.txt", CustomerRecordMapper.RECORD_WIDTH);
            final String image = fixture.record(1);

            final Customer padded = CustomerRecordMapper.fromRecord(image, SEALER);
            assertThat(padded.getFirstName())
                    .as("the reader does not trim, so the given name arrives at its full 25 bytes")
                    .hasSize(CustomerRecordMapper.FIRST_NAME_LENGTH);

            final Customer trimmed = CustomerRecordMapper.fromRecord(image, SEALER);
            trimmed.setFirstName(rightTrim(padded.getFirstName()));
            trimmed.setMiddleName(rightTrim(padded.getMiddleName()));
            trimmed.setLastName(rightTrim(padded.getLastName()));
            trimmed.setAddrLine1(rightTrim(padded.getAddrLine1()));
            trimmed.setAddrLine2(rightTrim(padded.getAddrLine2()));
            trimmed.setAddrLine3(rightTrim(padded.getAddrLine3()));
            trimmed.setAddrZip(rightTrim(padded.getAddrZip()));
            trimmed.setPhoneNum1(rightTrim(padded.getPhoneNum1()));
            trimmed.setPhoneNum2(rightTrim(padded.getPhoneNum2()));

            assertThat(trimmed.getFirstName()).isNotEqualTo(padded.getFirstName());
            assertThat(CustomerRecordMapper.toRecord(trimmed, REVEALER))
                    .isEqualTo(CustomerRecordMapper.toRecord(padded, REVEALER));
            assertThat(CustomerRecordMapper.toRecordBytes(trimmed, REVEALER))
                    .isEqualTo(CustomerRecordMapper.toRecordBytes(padded, REVEALER));
        }

        @Test
        @DisplayName("the five trimmable daily-transaction fields: channel, description, merchant name, "
                + "merchant city and merchant postal code")
        void everyTrimmableDailyTransactionField() {
            final SeededRecordFixture fixture =
                    SeededRecordFixture.load("dailytran.txt", DailyTransactionRecordMapper.RECORD_LENGTH);
            final String image = fixture.record(1);

            final DailyTransaction padded = DailyTransactionRecordMapper.fromRecord(image);
            assertThat(padded.getDalytranSource())
                    .as("the channel label arrives at its full 10 bytes, which is why the enum keys on "
                            + "the padded form")
                    .hasSize(DailyTransactionRecordMapper.DALYTRAN_SOURCE_LENGTH);

            final DailyTransaction trimmed = DailyTransactionRecordMapper.fromRecord(image);
            trimmed.setDalytranSource(rightTrim(padded.getDalytranSource()));
            trimmed.setDalytranDesc(rightTrim(padded.getDalytranDesc()));
            trimmed.setDalytranMerchantName(rightTrim(padded.getDalytranMerchantName()));
            trimmed.setDalytranMerchantCity(rightTrim(padded.getDalytranMerchantCity()));
            trimmed.setDalytranMerchantZip(rightTrim(padded.getDalytranMerchantZip()));

            assertThat(trimmed.getDalytranSource()).isNotEqualTo(padded.getDalytranSource());
            assertThat(DailyTransactionRecordMapper.toRecord(trimmed))
                    .isEqualTo(DailyTransactionRecordMapper.toRecord(padded));
            assertThat(DailyTransactionRecordMapper.toRecordBytes(trimmed))
                    .isEqualTo(DailyTransactionRecordMapper.toRecordBytes(padded));
        }

        @Test
        @DisplayName("both user-security display names: 20 bytes in the record, shorter in the column")
        void bothUserSecurityDisplayNames() {
            final String image = "ADMIN001"
                    + pad("Admin", UserSecurityRecordMapper.SEC_USR_FNAME_LENGTH)
                    + pad("User", UserSecurityRecordMapper.SEC_USR_LNAME_LENGTH)
                    + pad("", UserSecurityRecordMapper.SEC_USR_PWD_LENGTH)
                    + "A"
                    + pad("", UserSecurityRecordMapper.SEC_USR_FILLER_LENGTH);
            assertThat(image).hasSize(UserSecurityRecordMapper.RECORD_LENGTH);

            final UserSecurity padded = UserSecurityRecordMapper.fromRecord(image, DIGESTER);
            assertThat(padded.getSecUsrFname())
                    .hasSize(UserSecurityRecordMapper.SEC_USR_FNAME_LENGTH);

            final UserSecurity trimmed = UserSecurityRecordMapper.fromRecord(image, DIGESTER);
            trimmed.setSecUsrFname(rightTrim(padded.getSecUsrFname()));
            trimmed.setSecUsrLname(rightTrim(padded.getSecUsrLname()));

            assertThat(trimmed.getSecUsrFname()).isNotEqualTo(padded.getSecUsrFname());
            assertThat(UserSecurityRecordMapper.toRecord(trimmed))
                    .isEqualTo(UserSecurityRecordMapper.toRecord(padded));
            assertThat(UserSecurityRecordMapper.toRecordBytes(trimmed))
                    .isEqualTo(UserSecurityRecordMapper.toRecordBytes(padded));
        }
    }

    @Nested
    @DisplayName("Three fields whose trailing blanks are the value and must never be trimmed")
    class BlanksThatCarryMeaning {

        @Test
        @DisplayName("account.acct_group_id is ten spaces, the reader keeps all ten, and trimming it "
                + "would be invisible in the record and fatal in the database")
        void theAccountGroupIdentifier() {
            final SeededRecordFixture fixture =
                    SeededRecordFixture.load("acctdata.txt", AccountRecordMapper.RECORD_LENGTH);
            final String image = fixture.record(1);

            assertThat(fixture.field(1, AccountRecordMapper.ACCT_GROUP_ID_OFFSET,
                    AccountRecordMapper.ACCT_GROUP_ID_LENGTH))
                    .as("the authority record carries ten spaces here, not a value")
                    .isEqualTo(" ".repeat(AccountRecordMapper.ACCT_GROUP_ID_LENGTH));

            final Account account = AccountRecordMapper.fromRecord(image);
            assertThat(account.getAcctGroupId())
                    .as("the reader returns all ten spaces: not null, not empty, not trimmed")
                    .isNotNull()
                    .isNotEmpty()
                    .hasSize(AccountRecordMapper.ACCT_GROUP_ID_LENGTH)
                    .isEqualTo(" ".repeat(AccountRecordMapper.ACCT_GROUP_ID_LENGTH));

            final Account emptied = AccountRecordMapper.fromRecord(image);
            emptied.setAcctGroupId("");
            assertThat(AccountRecordMapper.toRecord(emptied))
                    .as("the record image cannot police this field, because the writer pads either "
                            + "form back to ten bytes - which is exactly why the seed keeps the blanks")
                    .isEqualTo(AccountRecordMapper.toRecord(account));
            assertThat(emptied.getAcctGroupId())
                    .as("the difference is real where it matters: in the stored value")
                    .isNotEqualTo(account.getAcctGroupId());
        }

        @Test
        @DisplayName("disclosure_group.dis_acct_group_id is part of a composite key, so the padded and "
                + "trimmed forms are different rows")
        void theDisclosureGroupKey() {
            final SeededRecordFixture fixture =
                    SeededRecordFixture.load("discgrp.txt", DisclosureGroupRecordMapper.RECORD_LENGTH);

            int defaultRow = 0;
            for (int ordinal = 1; ordinal <= fixture.recordCount(); ordinal++) {
                final String group = fixture.field(ordinal,
                        DisclosureGroupRecordMapper.DIS_ACCT_GROUP_ID_OFFSET,
                        DisclosureGroupRecordMapper.DIS_ACCT_GROUP_ID_LENGTH);
                if ("DEFAULT".equals(rightTrim(group))) {
                    defaultRow = ordinal;
                    break;
                }
            }
            assertThat(defaultRow)
                    .as("the fixture must contain the fallback group the interest calculation re-probes")
                    .isPositive();

            final String image = fixture.record(defaultRow);
            final DisclosureGroupId key = DisclosureGroupRecordMapper.keyFromRecord(image);
            assertThat(key.getDisAcctGroupId())
                    .as("the fallback literal is seven characters moved into a ten-character field, so "
                            + "the key carries exactly three trailing spaces")
                    .isEqualTo("DEFAULT   ")
                    .hasSize(DisclosureGroupRecordMapper.DIS_ACCT_GROUP_ID_LENGTH);

            final DisclosureGroupId trimmedKey = new DisclosureGroupId(
                    rightTrim(key.getDisAcctGroupId()), key.getDisTranTypeCd(), key.getDisTranCatCd());
            assertThat(trimmedKey)
                    .as("trimming the key produces a different row, which would silently miss the "
                            + "fallback lookup rather than fail")
                    .isNotEqualTo(key);

            final DisclosureGroup group = DisclosureGroupRecordMapper.fromRecord(image);
            final DisclosureGroup trimmedGroup = DisclosureGroupRecordMapper.fromRecord(image);
            trimmedGroup.setDisAcctGroupId(rightTrim(group.getDisAcctGroupId()));
            assertThat(DisclosureGroupRecordMapper.toRecord(trimmedGroup))
                    .as("and the record image again cannot police it")
                    .isEqualTo(DisclosureGroupRecordMapper.toRecord(group));
        }

        @Test
        @DisplayName("daily_transaction.dalytran_proc_ts is twenty-six spaces meaning 'not yet posted', "
                + "and reloads as twenty-six spaces")
        void theProcessingTimestamp() {
            final SeededRecordFixture fixture =
                    SeededRecordFixture.load("dailytran.txt", DailyTransactionRecordMapper.RECORD_LENGTH);
            final String blank = " ".repeat(DailyTransactionRecordMapper.DALYTRAN_PROC_TS_LENGTH);

            assertThat(fixture.field(1, DailyTransactionRecordMapper.DALYTRAN_PROC_TS_OFFSET,
                    DailyTransactionRecordMapper.DALYTRAN_PROC_TS_LENGTH))
                    .isEqualTo(blank);

            final DailyTransaction record = DailyTransactionRecordMapper.fromRecord(fixture.record(1));
            assertThat(record.getDalytranProcTs())
                    .as("not null, not empty, not trimmed - the posting job writes this field, the "
                            + "fixture does not")
                    .isNotNull()
                    .isNotEmpty()
                    .isEqualTo(blank);

            final DailyTransaction emptied = DailyTransactionRecordMapper.fromRecord(fixture.record(1));
            emptied.setDalytranProcTs("");
            assertThat(DailyTransactionRecordMapper.toRecord(emptied))
                    .isEqualTo(DailyTransactionRecordMapper.toRecord(record));
            assertThat(emptied.getDalytranProcTs()).isNotEqualTo(record.getDalytranProcTs());
        }

        @Test
        @DisplayName("the seed itself keeps every one of those blanks: 50 ten-space group identifiers, "
                + "300 twenty-six-space timestamps and no trimmed group key")
        void theSeedKeepsTheBlanks() {
            final String seed = seedText();

            assertThat(countOf(seed, "'" + " ".repeat(10) + "'"))
                    .as("one ten-space literal for each of the fifty seeded accounts")
                    .isEqualTo(50);
            assertThat(countOf(seed, "'" + " ".repeat(26) + "'"))
                    .as("one twenty-six-space literal for each of the three hundred seeded daily "
                            + "transactions")
                    .isEqualTo(300);
            assertThat(countOf(seed, "'DEFAULT   '"))
                    .as("seventeen fallback rows, plus the comments that explain them")
                    .isGreaterThanOrEqualTo(17);
            assertThat(countOf(seed, "'ZEROAPR   '"))
                    .isGreaterThanOrEqualTo(17);
            assertThat(seed)
                    .as("no trimmed group key may appear anywhere in the seed, in a value or in a "
                            + "comment, because either would invite the same mistake")
                    .doesNotContain("'DEFAULT'")
                    .doesNotContain("'ZEROAPR'");
        }
    }

    @Nested
    @DisplayName("Every trimmable column is declared exactly as wide as the record span it holds")
    class ColumnWidthsMatchRecordSpans {

        @Test
        @DisplayName("the eighteen trimmable columns across six tables agree with their mappers")
        void theTrimmableColumnsAgree() {
            final SchemaColumnCatalog catalog = SchemaColumnCatalog.load();

            assertWidth(catalog, "transaction_type", "tran_type_desc",
                    TranTypeRecordMapper.TRAN_TYPE_DESCRIPTION_LENGTH);
            assertWidth(catalog, "transaction_category", "tran_cat_type_desc",
                    TranCatRecordMapper.TRAN_CAT_TYPE_DESC_LENGTH);

            assertWidth(catalog, "customer", "first_name", CustomerRecordMapper.FIRST_NAME_LENGTH);
            assertWidth(catalog, "customer", "middle_name", CustomerRecordMapper.MIDDLE_NAME_LENGTH);
            assertWidth(catalog, "customer", "last_name", CustomerRecordMapper.LAST_NAME_LENGTH);
            assertWidth(catalog, "customer", "addr_line_1", CustomerRecordMapper.ADDR_LINE_1_LENGTH);
            assertWidth(catalog, "customer", "addr_line_2", CustomerRecordMapper.ADDR_LINE_2_LENGTH);
            assertWidth(catalog, "customer", "addr_line_3", CustomerRecordMapper.ADDR_LINE_3_LENGTH);
            assertWidth(catalog, "customer", "addr_zip", CustomerRecordMapper.ADDR_ZIP_LENGTH);
            assertWidth(catalog, "customer", "phone_num_1", CustomerRecordMapper.PHONE_NUM_1_LENGTH);
            assertWidth(catalog, "customer", "phone_num_2", CustomerRecordMapper.PHONE_NUM_2_LENGTH);

            assertWidth(catalog, "daily_transaction", "dalytran_source",
                    DailyTransactionRecordMapper.DALYTRAN_SOURCE_LENGTH);
            assertWidth(catalog, "daily_transaction", "dalytran_desc",
                    DailyTransactionRecordMapper.DALYTRAN_DESC_LENGTH);
            assertWidth(catalog, "daily_transaction", "dalytran_merchant_name",
                    DailyTransactionRecordMapper.DALYTRAN_MERCHANT_NAME_LENGTH);
            assertWidth(catalog, "daily_transaction", "dalytran_merchant_city",
                    DailyTransactionRecordMapper.DALYTRAN_MERCHANT_CITY_LENGTH);
            assertWidth(catalog, "daily_transaction", "dalytran_merchant_zip",
                    DailyTransactionRecordMapper.DALYTRAN_MERCHANT_ZIP_LENGTH);

            assertWidth(catalog, "user_security", "sec_usr_fname",
                    UserSecurityRecordMapper.SEC_USR_FNAME_LENGTH);
            assertWidth(catalog, "user_security", "sec_usr_lname",
                    UserSecurityRecordMapper.SEC_USR_LNAME_LENGTH);
        }

        @Test
        @DisplayName("the three blank-bearing columns are declared exactly as wide as their spans too, "
                + "so a full-width blank always fits")
        void theBlankBearingColumnsAgree() {
            final SchemaColumnCatalog catalog = SchemaColumnCatalog.load();

            assertWidth(catalog, "account", "acct_group_id", AccountRecordMapper.ACCT_GROUP_ID_LENGTH);
            assertWidth(catalog, "disclosure_group", "dis_acct_group_id",
                    DisclosureGroupRecordMapper.DIS_ACCT_GROUP_ID_LENGTH);
            assertWidth(catalog, "daily_transaction", "dalytran_proc_ts",
                    DailyTransactionRecordMapper.DALYTRAN_PROC_TS_LENGTH);
        }

        @Test
        @DisplayName("the two columns that are deliberately wider than their record span stay wider, "
                + "because neither holds the legacy value")
        void theTwoDeliberateDivergencesRemain() {
            final SchemaColumnCatalog catalog = SchemaColumnCatalog.load();

            assertThat(catalog.declaredWidth("customer", "cust_ssn"))
                    .as("widened to hold an authenticated ciphertext envelope rather than the "
                            + "%d-byte legacy field", CustomerRecordMapper.CUST_SSN_LENGTH)
                    .isGreaterThan(CustomerRecordMapper.CUST_SSN_LENGTH);
            assertThat(catalog.declaredWidth("customer", "govt_issued_id"))
                    .isGreaterThan(CustomerRecordMapper.GOVT_ISSUED_ID_LENGTH);
            assertThat(catalog.declaredWidth("user_security", "sec_usr_pwd"))
                    .as("widened to hold a BCrypt digest rather than the %d-byte cleartext credential",
                            UserSecurityRecordMapper.SEC_USR_PWD_LENGTH)
                    .isGreaterThan(UserSecurityRecordMapper.SEC_USR_PWD_LENGTH);
        }
    }

    /**
     * Asserts that a column is declared exactly as wide as the record span that feeds it.
     *
     * @param catalog  the declared schema, parsed from the schema migration
     * @param table    the table name
     * @param column   the column name
     * @param expected the record span width the mapper publishes
     */
    private static void assertWidth(final SchemaColumnCatalog catalog, final String table,
                                    final String column, final int expected) {
        assertThat(catalog.declaredWidth(table, column))
                .as("%s.%s must be declared exactly as wide as its %d-byte record span, so a "
                        + "full-width padded value fits and a trimmed one loses nothing", table, column,
                        expected)
                .isEqualTo(expected);
    }

    /**
     * Counts non-overlapping occurrences of a literal.
     *
     * @param text    the text to search
     * @param literal the literal to count
     * @return the number of occurrences
     */
    private static int countOf(final String text, final String literal) {
        int count = 0;
        int at = text.indexOf(literal);
        while (at >= 0) {
            count++;
            at = text.indexOf(literal, at + literal.length());
        }
        return count;
    }

    /**
     * Left-justifies a value in a fixed-width field, space-padding on the right.
     *
     * @param value the value
     * @param width the field width
     * @return the value padded to the field width
     */
    private static String pad(final String value, final int width) {
        return value + " ".repeat(width - value.length());
    }
}
