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

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Stream;

import com.carddemo.domain.Account;
import com.carddemo.support.SeededRecordFixture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * Byte-parity acceptance for the 300-byte account record layout.
 *
 * <h2>What is under test</h2>
 * {@link AccountRecordMapper} carries five of the estate's ten zoned-decimal money fields, more than
 * any other layout, and it is therefore the layout where a decimal defect would do the most damage.
 * Four properties are asserted with particular care. Every amount decodes at scale exactly 2 and never
 * through a binary floating-point type. The overpunched sign is read and written in both directions,
 * including the two zero forms that are distinct as bytes and identical as values. Truncation toward
 * zero is asserted directly, because the estate contains no {@code ROUNDED} clause and a
 * half-up or half-even policy would differ by one cent on roughly half of all stores. And the twelve
 * fields tile {@code [0, 122)} with the filler carrying {@code [122, 300)}, so no byte of the record is
 * unaccounted for.
 *
 * <h2>Where the expectations come from</h2>
 * The geometry is stated as literal integers rather than read from the class under test, so a change to
 * a published offset fails here rather than being silently ratified. The field values are read from a
 * byte-level reading of the shipped fixture {@code app/data/ASCII/acctdata.txt}, whose fifty records
 * are 300 bytes each on a 301-byte stride.
 *
 * <h2>Why a whole-record comparison is asserted here and not everywhere</h2>
 * This layout's filler is 178 spaces in the fixture, which is exactly what the encoder emits, so a
 * decode followed by an encode reproduces all three hundred bytes. That makes the strongest available
 * assertion possible: all fifty production records survive a round trip byte for byte, with no window
 * excluded and nothing normalised away. Three other layouts in this module hold ASCII zeros in their
 * filler and can only be compared over their mapped prefix; this one has no such exemption and is held
 * to the whole record.
 *
 * <p>A pure in-process unit test: no application context, no database, no container, no mocking
 * framework and no reflection.</p>
 *
 * <p>Provenance: part of the migration of the AWS CardDemo z/OS application at checkout SHA
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. No legacy source text is reproduced.</p>
 */
@DisplayName("AccountRecordMapper - the 300-byte account record")
class AccountRecordMapperCoverageTest {

    /** Declared record width, stated here rather than read from the class under test. */
    private static final int EXPECTED_RECORD_WIDTH = 300;

    /** Declared width of the leading key the {@code FD} structure splits off. */
    private static final int EXPECTED_KEY_WIDTH = 11;

    /** Declared width of the remainder the {@code FD} structure calls data. */
    private static final int EXPECTED_DATA_WIDTH = 289;

    /** Declared width of the prefix the twelve mapped fields occupy. */
    private static final int EXPECTED_MAPPED_PREFIX_WIDTH = 122;

    /** Declared placement of the eleven-digit account identifier. */
    private static final int EXPECTED_ID_OFFSET = 0;

    /** Declared placement of the one-character active-status flag. */
    private static final int EXPECTED_STATUS_OFFSET = 11;

    /** Declared placement of the current balance. */
    private static final int EXPECTED_CURR_BAL_OFFSET = 12;

    /** Declared placement of the credit limit. */
    private static final int EXPECTED_CREDIT_LIMIT_OFFSET = 24;

    /** Declared placement of the cash credit limit. */
    private static final int EXPECTED_CASH_CREDIT_LIMIT_OFFSET = 36;

    /** Declared placement of the ten-character open date. */
    private static final int EXPECTED_OPEN_DATE_OFFSET = 48;

    /** Declared placement of the ten-character expiry date. */
    private static final int EXPECTED_EXPIRY_DATE_OFFSET = 58;

    /** Declared placement of the ten-character reissue date. */
    private static final int EXPECTED_REISSUE_DATE_OFFSET = 68;

    /** Declared placement of the current-cycle credit total. */
    private static final int EXPECTED_CYC_CREDIT_OFFSET = 78;

    /** Declared placement of the current-cycle debit total. */
    private static final int EXPECTED_CYC_DEBIT_OFFSET = 90;

    /** Declared placement of the ten-character postal code. */
    private static final int EXPECTED_ZIP_OFFSET = 102;

    /** Declared placement of the ten-character group identifier. */
    private static final int EXPECTED_GROUP_ID_OFFSET = 112;

    /** Declared placement of the trailing filler. */
    private static final int EXPECTED_FILLER_OFFSET = 122;

    /** Declared width of the trailing filler. */
    private static final int EXPECTED_FILLER_WIDTH = 178;

    /** Declared width of every one of the five zoned-decimal amounts. */
    private static final int EXPECTED_AMOUNT_WIDTH = 12;

    /** Declared width of every one of the three date fields and the two ten-character codes. */
    private static final int EXPECTED_TEN_CHARACTER_WIDTH = 10;

    /** The canonical scale of every monetary amount in this module. */
    private static final int EXPECTED_MONETARY_SCALE = 2;

    /** Name of the shipped fixture that supplies the production-representative records. */
    private static final String FIXTURE_FILE = "acctdata.txt";

    /** Number of records the shipped fixture holds. */
    private static final int EXPECTED_FIXTURE_RECORDS = 50;

    /** Byte count of the shipped fixture, its fifty records on a 301-byte stride. */
    private static final int EXPECTED_FIXTURE_BYTES = 15_050;

    /** The postal code every fixture record carries, transcribed from a byte-level reading. */
    private static final String FIXTURE_ZIP = "A000000000";

    /** The group identifier every fixture record carries: ten spaces, all of them significant. */
    private static final String FIXTURE_GROUP_ID = "          ";

    /**
     * Loads the shipped fixture, declaring the width the reader must find in every record.
     *
     * @return the loaded fixture
     */
    private static SeededRecordFixture fixture() {
        return SeededRecordFixture.load(FIXTURE_FILE, EXPECTED_RECORD_WIDTH);
    }

    /**
     * Repeats a character into a run of a given width.
     *
     * @param character the character to repeat
     * @param width     the number of repetitions
     * @return a string of exactly {@code width} copies of {@code character}
     */
    private static String run(final char character, final int width) {
        return String.valueOf(character).repeat(width);
    }

    /**
     * Assembles a complete 300-byte record image from independently supplied field images.
     *
     * <p>Every argument is supplied at its exact declared width, so this helper concatenates and never
     * pads. That is deliberate: a helper that padded would be able to hide a width defect in the very
     * expectation meant to detect one.</p>
     *
     * @param id          the eleven-digit identifier image
     * @param status      the one-character status image
     * @param currBal     the twelve-byte current-balance image
     * @param creditLimit the twelve-byte credit-limit image
     * @param cashLimit   the twelve-byte cash-credit-limit image
     * @param openDate    the ten-character open-date image
     * @param expiryDate  the ten-character expiry-date image
     * @param reissueDate the ten-character reissue-date image
     * @param cycCredit   the twelve-byte cycle-credit image
     * @param cycDebit    the twelve-byte cycle-debit image
     * @param zip         the ten-character postal-code image
     * @param groupId     the ten-character group-identifier image
     * @return a record image of exactly {@link #EXPECTED_RECORD_WIDTH} characters
     */
    private static String image(final String id, final String status, final String currBal,
            final String creditLimit, final String cashLimit, final String openDate,
            final String expiryDate, final String reissueDate, final String cycCredit,
            final String cycDebit, final String zip, final String groupId) {
        return id + status + currBal + creditLimit + cashLimit + openDate + expiryDate + reissueDate
                + cycCredit + cycDebit + zip + groupId + run(' ', EXPECTED_FILLER_WIDTH);
    }

    /**
     * Assembles a 300-byte record image with every field at a known, plain value.
     *
     * @return a canonical record image
     */
    private static String canonicalImage() {
        return image("00000000001", "Y", "00000001940{", "00000020200{", "00000010200{",
                "2014-11-20", "2025-05-20", "2025-05-20", "00000000000{", "00000000000{",
                FIXTURE_ZIP, FIXTURE_GROUP_ID);
    }

    /**
     * Builds an entity whose twelve mapped properties match {@link #canonicalImage()}.
     *
     * @return a canonical entity
     */
    private static Account canonicalEntity() {
        return new Account("00000000001", "Y", new BigDecimal("194.00"), new BigDecimal("2020.00"),
                new BigDecimal("1020.00"), "2014-11-20", "2025-05-20", "2025-05-20",
                new BigDecimal("0.00"), new BigDecimal("0.00"), FIXTURE_ZIP, FIXTURE_GROUP_ID);
    }

    /**
     * Supplies every one-based fixture record ordinal.
     *
     * @return the fifty ordinals
     */
    static Stream<Arguments> fixtureOrdinals() {
        return Stream.iterate(1, ordinal -> ordinal + 1)
                .limit(EXPECTED_FIXTURE_RECORDS)
                .map(Arguments::of);
    }

    @Nested
    @DisplayName("the declared geometry reproduces the copybook exactly")
    class DeclaredGeometry {

        @Test
        @DisplayName("the record is 300 bytes and the key/data split is 11 plus 289")
        void theRecordAndItsFdSplitAreTheDeclaredWidths() {
            assertThat(AccountRecordMapper.RECORD_LENGTH).isEqualTo(EXPECTED_RECORD_WIDTH);
            assertThat(AccountRecordMapper.KEY_LENGTH).isEqualTo(EXPECTED_KEY_WIDTH);
            assertThat(AccountRecordMapper.DATA_LENGTH).isEqualTo(EXPECTED_DATA_WIDTH);
            assertThat(AccountRecordMapper.KEY_LENGTH + AccountRecordMapper.DATA_LENGTH)
                    .as("the legacy FD structure splits the image into key and remainder with no gap")
                    .isEqualTo(EXPECTED_RECORD_WIDTH);
        }

        @Test
        @DisplayName("the key is exactly the account identifier, so no surrogate key is needed")
        void theKeyIsExactlyTheIdentifier() {
            assertThat(AccountRecordMapper.ACCT_ID_OFFSET)
                    .as("the business key must lead the record for the FD split to be a prefix")
                    .isEqualTo(EXPECTED_ID_OFFSET);
            assertThat(AccountRecordMapper.ACCT_ID_LENGTH)
                    .isEqualTo(EXPECTED_KEY_WIDTH)
                    .isEqualTo(AccountRecordMapper.KEY_LENGTH);
        }

        @Test
        @DisplayName("all twelve fields sit at their declared offsets")
        void allTwelveFieldsSitWhereTheCopybookPutsThem() {
            assertThat(AccountRecordMapper.ACCT_ACTIVE_STATUS_OFFSET)
                    .isEqualTo(EXPECTED_STATUS_OFFSET);
            assertThat(AccountRecordMapper.ACCT_CURR_BAL_OFFSET).isEqualTo(EXPECTED_CURR_BAL_OFFSET);
            assertThat(AccountRecordMapper.ACCT_CREDIT_LIMIT_OFFSET)
                    .isEqualTo(EXPECTED_CREDIT_LIMIT_OFFSET);
            assertThat(AccountRecordMapper.ACCT_CASH_CREDIT_LIMIT_OFFSET)
                    .isEqualTo(EXPECTED_CASH_CREDIT_LIMIT_OFFSET);
            assertThat(AccountRecordMapper.ACCT_OPEN_DATE_OFFSET)
                    .isEqualTo(EXPECTED_OPEN_DATE_OFFSET);
            assertThat(AccountRecordMapper.ACCT_EXPIRAION_DATE_OFFSET)
                    .isEqualTo(EXPECTED_EXPIRY_DATE_OFFSET);
            assertThat(AccountRecordMapper.ACCT_REISSUE_DATE_OFFSET)
                    .isEqualTo(EXPECTED_REISSUE_DATE_OFFSET);
            assertThat(AccountRecordMapper.ACCT_CURR_CYC_CREDIT_OFFSET)
                    .isEqualTo(EXPECTED_CYC_CREDIT_OFFSET);
            assertThat(AccountRecordMapper.ACCT_CURR_CYC_DEBIT_OFFSET)
                    .isEqualTo(EXPECTED_CYC_DEBIT_OFFSET);
            assertThat(AccountRecordMapper.ACCT_ADDR_ZIP_OFFSET).isEqualTo(EXPECTED_ZIP_OFFSET);
            assertThat(AccountRecordMapper.ACCT_GROUP_ID_OFFSET)
                    .isEqualTo(EXPECTED_GROUP_ID_OFFSET);
            assertThat(AccountRecordMapper.FILLER_OFFSET).isEqualTo(EXPECTED_FILLER_OFFSET);
        }

        @Test
        @DisplayName("all five amounts are twelve bytes wide, which is PIC S9(10)V99")
        void allFiveAmountsAreTwelveBytesWide() {
            assertThat(List.of(AccountRecordMapper.ACCT_CURR_BAL_LENGTH,
                            AccountRecordMapper.ACCT_CREDIT_LIMIT_LENGTH,
                            AccountRecordMapper.ACCT_CASH_CREDIT_LIMIT_LENGTH,
                            AccountRecordMapper.ACCT_CURR_CYC_CREDIT_LENGTH,
                            AccountRecordMapper.ACCT_CURR_CYC_DEBIT_LENGTH))
                    .as("ten integral digits plus two decimals is twelve encoded bytes")
                    .containsOnly(EXPECTED_AMOUNT_WIDTH);
            assertThat(ZonedDecimalCodec.ACCOUNT_AMOUNT_WIDTH)
                    .as("and must be the same width the codec publishes for this layout")
                    .isEqualTo(EXPECTED_AMOUNT_WIDTH);
        }

        @Test
        @DisplayName("all three dates and both ten-character codes are ten bytes wide")
        void allTenCharacterFieldsAreTenBytesWide() {
            assertThat(List.of(AccountRecordMapper.ACCT_OPEN_DATE_LENGTH,
                            AccountRecordMapper.ACCT_EXPIRAION_DATE_LENGTH,
                            AccountRecordMapper.ACCT_REISSUE_DATE_LENGTH,
                            AccountRecordMapper.ACCT_ADDR_ZIP_LENGTH,
                            AccountRecordMapper.ACCT_GROUP_ID_LENGTH))
                    .containsOnly(EXPECTED_TEN_CHARACTER_WIDTH);
            assertThat(AccountRecordMapper.ACCT_ACTIVE_STATUS_LENGTH)
                    .as("the active-status flag is a single character")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("the twelve fields and the filler tile the record exactly")
        void theTwelveFieldsAndTheFillerTileTheRecord() {
            int mapped = AccountRecordMapper.ACCT_ID_LENGTH
                    + AccountRecordMapper.ACCT_ACTIVE_STATUS_LENGTH
                    + AccountRecordMapper.ACCT_CURR_BAL_LENGTH
                    + AccountRecordMapper.ACCT_CREDIT_LIMIT_LENGTH
                    + AccountRecordMapper.ACCT_CASH_CREDIT_LIMIT_LENGTH
                    + AccountRecordMapper.ACCT_OPEN_DATE_LENGTH
                    + AccountRecordMapper.ACCT_EXPIRAION_DATE_LENGTH
                    + AccountRecordMapper.ACCT_REISSUE_DATE_LENGTH
                    + AccountRecordMapper.ACCT_CURR_CYC_CREDIT_LENGTH
                    + AccountRecordMapper.ACCT_CURR_CYC_DEBIT_LENGTH
                    + AccountRecordMapper.ACCT_ADDR_ZIP_LENGTH
                    + AccountRecordMapper.ACCT_GROUP_ID_LENGTH;

            assertThat(mapped)
                    .as("the twelve declared widths must sum to the published mapped prefix")
                    .isEqualTo(EXPECTED_MAPPED_PREFIX_WIDTH)
                    .isEqualTo(AccountRecordMapper.MAPPED_PREFIX_LENGTH)
                    .isEqualTo(AccountRecordMapper.FILLER_OFFSET);
            assertThat(AccountRecordMapper.FILLER_LENGTH).isEqualTo(EXPECTED_FILLER_WIDTH);
            assertThat(AccountRecordMapper.FILLER_OFFSET + AccountRecordMapper.FILLER_LENGTH)
                    .as("and the filler must carry the remainder to the declared record width")
                    .isEqualTo(EXPECTED_RECORD_WIDTH);
        }

        @Test
        @DisplayName("the misspelled copybook field name is preserved in the published offset constant")
        void theMisspelledCopybookNameIsPreserved() {
            assertThat(AccountRecordMapper.ACCT_EXPIRAION_DATE_OFFSET)
                    .as("the copybook spells this field without its first 'T', and correcting the"
                            + " constant name would break the correspondence a reviewer traces")
                    .isEqualTo(EXPECTED_EXPIRY_DATE_OFFSET);
            assertThat(canonicalEntity().getAcctExpirationDate())
                    .as("while the entity property is spelled correctly, which is the documented"
                            + " asymmetry rather than a drift")
                    .isEqualTo("2025-05-20");
        }
    }

    @Nested
    @DisplayName("decoding a record image")
    class Decoding {

        @Test
        @DisplayName("all twelve fields arrive at their declared positions")
        void allTwelveFieldsArriveAtTheirDeclaredPositions() {
            Account decoded = AccountRecordMapper.fromRecord(canonicalImage());

            assertThat(decoded.getAcctId()).isEqualTo("00000000001");
            assertThat(decoded.getAcctActiveStatus()).isEqualTo("Y");
            assertThat(decoded.getAcctCurrBal()).isEqualByComparingTo("194.00");
            assertThat(decoded.getAcctCreditLimit()).isEqualByComparingTo("2020.00");
            assertThat(decoded.getAcctCashCreditLimit()).isEqualByComparingTo("1020.00");
            assertThat(decoded.getAcctOpenDate()).isEqualTo("2014-11-20");
            assertThat(decoded.getAcctExpirationDate()).isEqualTo("2025-05-20");
            assertThat(decoded.getAcctReissueDate()).isEqualTo("2025-05-20");
            assertThat(decoded.getAcctCurrCycCredit()).isEqualByComparingTo("0.00");
            assertThat(decoded.getAcctCurrCycDebit()).isEqualByComparingTo("0.00");
            assertThat(decoded.getAcctAddrZip()).isEqualTo(FIXTURE_ZIP);
            assertThat(decoded.getAcctGroupId()).isEqualTo(FIXTURE_GROUP_ID);
        }

        @Test
        @DisplayName("a field is read from its own offset, not from a neighbour's")
        void everyFieldIsReadFromItsOwnOffset() {
            Account decoded = AccountRecordMapper.fromRecord(
                    image("00000000042", "N", "00000000000A", "00000000000B", "00000000000C",
                            "1111-11-11", "2222-02-22", "3333-03-03", "00000000000D",
                            "00000000000E", "ZIPZIPZIP1", "GROUPGRP01"));

            assertThat(decoded.getAcctId()).isEqualTo("00000000042");
            assertThat(decoded.getAcctActiveStatus()).isEqualTo("N");
            assertThat(decoded.getAcctCurrBal()).isEqualByComparingTo("0.01");
            assertThat(decoded.getAcctCreditLimit()).isEqualByComparingTo("0.02");
            assertThat(decoded.getAcctCashCreditLimit()).isEqualByComparingTo("0.03");
            assertThat(decoded.getAcctOpenDate())
                    .as("five distinct values across the three dates and two codes prove that no two"
                            + " offsets are transposed")
                    .isEqualTo("1111-11-11");
            assertThat(decoded.getAcctExpirationDate()).isEqualTo("2222-02-22");
            assertThat(decoded.getAcctReissueDate()).isEqualTo("3333-03-03");
            assertThat(decoded.getAcctCurrCycCredit()).isEqualByComparingTo("0.04");
            assertThat(decoded.getAcctCurrCycDebit()).isEqualByComparingTo("0.05");
            assertThat(decoded.getAcctAddrZip()).isEqualTo("ZIPZIPZIP1");
            assertThat(decoded.getAcctGroupId()).isEqualTo("GROUPGRP01");
        }

        @Test
        @DisplayName("character fields keep every significant space, trimmed nowhere")
        void characterFieldsKeepEverySignificantSpace() {
            Account decoded = AccountRecordMapper.fromRecord(
                    image("00000000001", " ", "00000000000{", "00000000000{", "00000000000{",
                            "          ", "          ", "          ", "00000000000{",
                            "00000000000{", "  A       ", "          "));

            assertThat(decoded.getAcctActiveStatus())
                    .as("a blank flag is a value, not an absence")
                    .isEqualTo(" ");
            assertThat(decoded.getAcctOpenDate()).isEqualTo(run(' ', EXPECTED_TEN_CHARACTER_WIDTH));
            assertThat(decoded.getAcctAddrZip())
                    .as("leading and trailing spaces are both part of the fixed-width contract")
                    .isEqualTo("  A       ");
            assertThat(decoded.getAcctGroupId())
                    .isEqualTo(run(' ', EXPECTED_TEN_CHARACTER_WIDTH));
        }

        @Test
        @DisplayName("every decoded amount carries scale exactly 2, never a wider or narrower one")
        void everyDecodedAmountCarriesScaleTwo() {
            Account decoded = AccountRecordMapper.fromRecord(canonicalImage());

            assertThat(List.of(decoded.getAcctCurrBal().scale(),
                            decoded.getAcctCreditLimit().scale(),
                            decoded.getAcctCashCreditLimit().scale(),
                            decoded.getAcctCurrCycCredit().scale(),
                            decoded.getAcctCurrCycDebit().scale()))
                    .as("a V99 field has two decimal places whatever its value, so 0.00 is scale 2"
                            + " and not scale 0")
                    .containsOnly(EXPECTED_MONETARY_SCALE);
        }

        @Test
        @DisplayName("an amount is a BigDecimal, so no binary floating-point type is in the path")
        void anAmountIsABigDecimal() {
            Account decoded = AccountRecordMapper.fromRecord(canonicalImage());

            assertThat(decoded.getAcctCurrBal())
                    .as("a double could not represent 194.00 exactly, and parity requires exactness")
                    .isInstanceOf(BigDecimal.class)
                    .isEqualTo(new BigDecimal("194.00"));
        }

        @Test
        @DisplayName("the byte overload agrees with the text overload field for field")
        void theByteOverloadAgreesWithTheTextOverload() {
            String text = canonicalImage();

            Account fromText = AccountRecordMapper.fromRecord(text);
            Account fromBytes = AccountRecordMapper.fromRecord(
                    text.getBytes(StandardCharsets.US_ASCII));

            assertThat(fromBytes.getAcctId()).isEqualTo(fromText.getAcctId());
            assertThat(fromBytes.getAcctActiveStatus()).isEqualTo(fromText.getAcctActiveStatus());
            assertThat(fromBytes.getAcctCurrBal()).isEqualTo(fromText.getAcctCurrBal());
            assertThat(fromBytes.getAcctCreditLimit()).isEqualTo(fromText.getAcctCreditLimit());
            assertThat(fromBytes.getAcctCashCreditLimit())
                    .isEqualTo(fromText.getAcctCashCreditLimit());
            assertThat(fromBytes.getAcctOpenDate()).isEqualTo(fromText.getAcctOpenDate());
            assertThat(fromBytes.getAcctExpirationDate())
                    .isEqualTo(fromText.getAcctExpirationDate());
            assertThat(fromBytes.getAcctReissueDate()).isEqualTo(fromText.getAcctReissueDate());
            assertThat(fromBytes.getAcctCurrCycCredit()).isEqualTo(fromText.getAcctCurrCycCredit());
            assertThat(fromBytes.getAcctCurrCycDebit()).isEqualTo(fromText.getAcctCurrCycDebit());
            assertThat(fromBytes.getAcctAddrZip()).isEqualTo(fromText.getAcctAddrZip());
            assertThat(fromBytes.getAcctGroupId()).isEqualTo(fromText.getAcctGroupId());
        }

        @Test
        @DisplayName("the optimistic-locking version is left to the provider, not read from the image")
        void theVersionIsNotReadFromTheImage() {
            Account decoded = AccountRecordMapper.fromRecord(canonicalImage());

            assertThat(decoded.getVersion())
                    .as("the version counter has no representation in a 300-byte record, so a mapped"
                            + " entity must carry the never-updated value the entity itself defines"
                            + " rather than anything derived from the image")
                    .isZero();
        }
    }

    @Nested
    @DisplayName("the overpunched sign is read in both directions")
    class OverpunchedSigns {

        @ParameterizedTest(name = "\"{0}\" decodes to {1}")
        @CsvSource({
            "00000001940{, 194.00",
            "00000001940A, 194.01",
            "00000001940B, 194.02",
            "00000001940I, 194.09",
            "00000001940}, -194.00",
            "00000001940J, -194.01",
            "00000001940R, -194.09",
            "00000000000{, 0.00",
            "99999999999I, 9999999999.99",
            "99999999999R, -9999999999.99"})
        @DisplayName("the trailing byte carries both the low-order digit and the sign")
        void theTrailingByteCarriesDigitAndSign(final String amountImage, final String expected) {
            Account decoded = AccountRecordMapper.fromRecord(
                    image("00000000001", "Y", amountImage, "00000000000{", "00000000000{",
                            "2014-11-20", "2025-05-20", "2025-05-20", "00000000000{",
                            "00000000000{", FIXTURE_ZIP, FIXTURE_GROUP_ID));

            assertThat(decoded.getAcctCurrBal())
                    .as("'{' through 'I' encode positive zero to nine and '}' through 'R' negative")
                    .isEqualByComparingTo(expected);
        }

        @Test
        @DisplayName("a negative zero image decodes to zero, because the two are the same value")
        void aNegativeZeroImageDecodesToZero() {
            Account decoded = AccountRecordMapper.fromRecord(
                    image("00000000001", "Y", "00000000000}", "00000000000{", "00000000000{",
                            "2014-11-20", "2025-05-20", "2025-05-20", "00000000000{",
                            "00000000000{", FIXTURE_ZIP, FIXTURE_GROUP_ID));

            assertThat(decoded.getAcctCurrBal())
                    .as("negative zero is a distinct byte image and an identical numeric value")
                    .isEqualByComparingTo("0.00")
                    .isEqualByComparingTo(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("a negative zero re-encodes as a negative zero, the sign travelling on the "
                + "entity's transient marker rather than inside the BigDecimal")
        void aNegativeZeroRoundTripsAsANegativeZero() {
            String original = image("00000000001", "Y", "00000000000}", "00000000000{",
                    "00000000000{", "2014-11-20", "2025-05-20", "2025-05-20", "00000000000{",
                    "00000000000{", FIXTURE_ZIP, FIXTURE_GROUP_ID);

            Account decoded = AccountRecordMapper.fromRecord(original);
            String reEncoded = AccountRecordMapper.toRecord(decoded);

            assertThat(decoded.getAcctCurrBal()).isEqualByComparingTo("0.00");
            assertThat(decoded.isAcctCurrBalNegativeZero())
                    .as("the one bit a BigDecimal cannot hold is carried beside it")
                    .isTrue();
            assertThat(decoded.isAcctCreditLimitNegativeZero())
                    .as("and only for the field whose image actually carried it")
                    .isFalse();
            assertThat(reEncoded.charAt(EXPECTED_CURR_BAL_OFFSET + EXPECTED_AMOUNT_WIDTH - 1))
                    .isEqualTo('}');
            assertThat(reEncoded).isEqualTo(original);
        }

        @ParameterizedTest(name = "{0} encodes to \"{1}\"")
        @CsvSource({
            "194.00, 00000001940{",
            "194.01, 00000001940A",
            "194.09, 00000001940I",
            "-194.00, 00000001940}",
            "-194.01, 00000001940J",
            "-194.09, 00000001940R",
            "0.00, 00000000000{",
            "9999999999.99, 99999999999I",
            "-9999999999.99, 99999999999R"})
        @DisplayName("encoding writes the same overpunched byte the decoder reads")
        void encodingWritesTheSameOverpunchedByte(final String value, final String expectedImage) {
            String encoded = AccountRecordMapper.toRecord(new Account("00000000001", "Y",
                    new BigDecimal(value), new BigDecimal("0.00"), new BigDecimal("0.00"),
                    "2014-11-20", "2025-05-20", "2025-05-20", new BigDecimal("0.00"),
                    new BigDecimal("0.00"), FIXTURE_ZIP, FIXTURE_GROUP_ID));

            assertThat(encoded.substring(EXPECTED_CURR_BAL_OFFSET,
                    EXPECTED_CURR_BAL_OFFSET + EXPECTED_AMOUNT_WIDTH))
                    .isEqualTo(expectedImage);
        }

        @Test
        @DisplayName("plain trailing digits are read as positive, as an unsigned image would be")
        void plainTrailingDigitsAreReadAsPositive() {
            Account decoded = AccountRecordMapper.fromRecord(
                    image("00000000001", "Y", "000000019400", "00000000000{", "00000000000{",
                            "2014-11-20", "2025-05-20", "2025-05-20", "00000000000{",
                            "00000000000{", FIXTURE_ZIP, FIXTURE_GROUP_ID));

            assertThat(decoded.getAcctCurrBal())
                    .as("an image written without an overpunch is a positive value, not an error")
                    .isEqualByComparingTo("194.00");
        }
    }

    @Nested
    @DisplayName("arithmetic truncates toward zero, because the estate has no ROUNDED clause")
    class TruncationPolicy {

        @ParameterizedTest(name = "{0} truncates to \"{1}\"")
        @CsvSource({
            "194.999, 00000001949I",
            "194.991, 00000001949I",
            "194.005, 00000001940{",
            "194.995, 00000001949I",
            "-194.999, 00000001949R",
            "-194.005, 00000001940}",
            "0.009, 00000000000{",
            "-0.009, 00000000000{"})
        @DisplayName("a value with more than two decimals loses the excess rather than rounding it")
        void aWiderValueIsTruncatedRatherThanRounded(final String value,
                final String expectedImage) {
            String encoded = AccountRecordMapper.toRecord(new Account("00000000001", "Y",
                    new BigDecimal(value), new BigDecimal("0.00"), new BigDecimal("0.00"),
                    "2014-11-20", "2025-05-20", "2025-05-20", new BigDecimal("0.00"),
                    new BigDecimal("0.00"), FIXTURE_ZIP, FIXTURE_GROUP_ID));

            assertThat(encoded.substring(EXPECTED_CURR_BAL_OFFSET,
                    EXPECTED_CURR_BAL_OFFSET + EXPECTED_AMOUNT_WIDTH))
                    .as("half-up would render 194.995 as 195.00 and differ by a cent, which is a"
                            + " byte-parity failure")
                    .isEqualTo(expectedImage);
        }

        @Test
        @DisplayName("truncation is toward zero on both sides of zero, not toward negative infinity")
        void truncationIsTowardZeroOnBothSides() {
            String positive = AccountRecordMapper.toRecord(new Account("00000000001", "Y",
                    new BigDecimal("1.999"), new BigDecimal("0.00"), new BigDecimal("0.00"),
                    "2014-11-20", "2025-05-20", "2025-05-20", new BigDecimal("0.00"),
                    new BigDecimal("0.00"), FIXTURE_ZIP, FIXTURE_GROUP_ID));
            String negative = AccountRecordMapper.toRecord(new Account("00000000001", "Y",
                    new BigDecimal("-1.999"), new BigDecimal("0.00"), new BigDecimal("0.00"),
                    "2014-11-20", "2025-05-20", "2025-05-20", new BigDecimal("0.00"),
                    new BigDecimal("0.00"), FIXTURE_ZIP, FIXTURE_GROUP_ID));

            assertThat(positive.substring(EXPECTED_CURR_BAL_OFFSET,
                    EXPECTED_CURR_BAL_OFFSET + EXPECTED_AMOUNT_WIDTH)).isEqualTo("00000000019I");
            assertThat(negative.substring(EXPECTED_CURR_BAL_OFFSET,
                    EXPECTED_CURR_BAL_OFFSET + EXPECTED_AMOUNT_WIDTH))
                    .as("floor rounding would give -2.00 here, which COBOL never does")
                    .isEqualTo("00000000019R");
        }

        @Test
        @DisplayName("a narrower value is widened to two decimals rather than left short")
        void aNarrowerValueIsWidenedToTwoDecimals() {
            String encoded = AccountRecordMapper.toRecord(new Account("00000000001", "Y",
                    new BigDecimal("7"), new BigDecimal("0.0"), new BigDecimal("0.00"),
                    "2014-11-20", "2025-05-20", "2025-05-20", new BigDecimal("0.00"),
                    new BigDecimal("0.00"), FIXTURE_ZIP, FIXTURE_GROUP_ID));

            assertThat(encoded.substring(EXPECTED_CURR_BAL_OFFSET,
                    EXPECTED_CURR_BAL_OFFSET + EXPECTED_AMOUNT_WIDTH))
                    .as("a V99 field always occupies its two decimal positions")
                    .isEqualTo("00000000070{");
        }
    }

    @Nested
    @DisplayName("decoding one record out of a larger buffer")
    class BufferDecoding {

        /** Stride of the shipped fixture: the record width plus its one-byte terminator. */
        private static final int STRIDE = EXPECTED_RECORD_WIDTH + 1;

        @ParameterizedTest(name = "record {0}")
        @ValueSource(ints = {1, 2, 25, 49, 50})
        @DisplayName("stride arithmetic selects the same record the fixture reader does")
        void strideArithmeticSelectsTheSameRecord(final int ordinal) {
            SeededRecordFixture loaded = fixture();
            byte[] whole = String.join("\n", loaded.records())
                    .getBytes(StandardCharsets.US_ASCII);

            Account fromBuffer = AccountRecordMapper.fromRecord(whole, (ordinal - 1) * STRIDE);

            assertThat(fromBuffer.getAcctId())
                    .as("stride %d must land on record %d and leave its terminator behind",
                            STRIDE, ordinal)
                    .isEqualTo(loaded.field(ordinal, EXPECTED_ID_OFFSET, EXPECTED_KEY_WIDTH));
        }

        @Test
        @DisplayName("a negative start index is refused rather than wrapped")
        void aNegativeStartIndexIsRefused() {
            byte[] buffer = new byte[EXPECTED_RECORD_WIDTH];

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> AccountRecordMapper.fromRecord(buffer, -1))
                    .withMessageContaining("must not be negative");
        }

        @Test
        @DisplayName("a record that would run past the end of the buffer is refused, not short-read")
        void anOverrunningRecordIsRefused() {
            byte[] buffer = canonicalImage().getBytes(StandardCharsets.US_ASCII);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("a short read would produce a plausible record from bytes that are not one")
                    .isThrownBy(() -> AccountRecordMapper.fromRecord(buffer, 1))
                    .withMessageContaining("does not fit inside the supplied buffer");
        }

        @Test
        @DisplayName("a null buffer is refused")
        void aNullBufferIsRefused() {
            assertThatNullPointerException()
                    .isThrownBy(() -> AccountRecordMapper.fromRecord(null, 0));
        }
    }

    @Nested
    @DisplayName("a malformed image is refused rather than repaired")
    class MalformedInput {

        @ParameterizedTest(name = "width {0}")
        @ValueSource(ints = {0, 1, 122, 299, 350, 600})
        @DisplayName("any width other than 300 is refused")
        void anyOtherWidthIsRefused(final int width) {
            String wrongWidth = run('0', width);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> AccountRecordMapper.fromRecord(wrongWidth))
                    .withMessageContaining("must be exactly " + EXPECTED_RECORD_WIDTH)
                    .withMessageContaining(String.valueOf(width))
                    .withMessageContaining("never padded or truncated to fit");
        }

        @Test
        @DisplayName("an unstripped line terminator is diagnosed by name, since that is the usual cause")
        void anUnstrippedTerminatorIsDiagnosedByName() {
            String withTerminator = canonicalImage() + "\n";

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> AccountRecordMapper.fromRecord(withTerminator))
                    .withMessageContaining("301")
                    .withMessageContaining("terminator");
        }

        @Test
        @DisplayName("a byte-array width refusal reports the same shape as the text one")
        void aByteArrayWidthRefusalReportsTheSameShape() {
            byte[] tooShort = canonicalImage().substring(0, EXPECTED_RECORD_WIDTH - 1)
                    .getBytes(StandardCharsets.US_ASCII);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> AccountRecordMapper.fromRecord(tooShort))
                    .withMessageContaining("must be exactly " + EXPECTED_RECORD_WIDTH)
                    .withMessageContaining(String.valueOf(EXPECTED_RECORD_WIDTH - 1));
        }

        @Test
        @DisplayName("a non-ASCII character is refused rather than transcoded")
        void aNonAsciiCharacterIsRefused() {
            String withAccent = image("00000000001", "Y", "00000001940{", "00000020200{",
                    "00000010200{", "2014-11-20", "2025-05-20", "2025-05-20", "00000000000{",
                    "00000000000{", "Z\u00dcRICH9999", FIXTURE_GROUP_ID);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("measuring by encoding is what makes a substitution impossible")
                    .isThrownBy(() -> AccountRecordMapper.fromRecord(withAccent))
                    .withMessageContaining("US-ASCII cannot represent");
        }

        @Test
        @DisplayName("a byte above 0x7F is refused, because it means another encoding")
        void aHighByteIsRefused() {
            byte[] record = canonicalImage().getBytes(StandardCharsets.US_ASCII);
            record[EXPECTED_ZIP_OFFSET] = (byte) 0xC1;

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("0xC1 is EBCDIC 'A' and reading it as ASCII would silently corrupt the field")
                    .isThrownBy(() -> AccountRecordMapper.fromRecord(record))
                    .withMessageContaining("non-ASCII byte");
        }

        @ParameterizedTest(name = "amount image \"{0}\"")
        @ValueSource(strings = {"            ", "0000000194 {", "0000000194X{", "-0000019400"})
        @DisplayName("a monetary field that is not a zoned-decimal image is refused")
        void aMalformedAmountIsRefused(final String amountImage) {
            String padded = amountImage.length() == EXPECTED_AMOUNT_WIDTH
                    ? amountImage
                    : amountImage + " ".repeat(EXPECTED_AMOUNT_WIDTH - amountImage.length());
            String record = image("00000000001", "Y", padded, "00000000000{", "00000000000{",
                    "2014-11-20", "2025-05-20", "2025-05-20", "00000000000{", "00000000000{",
                    FIXTURE_ZIP, FIXTURE_GROUP_ID);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("a partially mapped entity is worse than a refusal, so the whole record fails")
                    .isThrownBy(() -> AccountRecordMapper.fromRecord(record));
        }

        @Test
        @DisplayName("a null image is refused on both decode entry points")
        void aNullImageIsRefused() {
            assertThatNullPointerException()
                    .isThrownBy(() -> AccountRecordMapper.fromRecord((String) null));
            assertThatNullPointerException()
                    .isThrownBy(() -> AccountRecordMapper.fromRecord((byte[]) null));
        }
    }

    @Nested
    @DisplayName("encoding an entity")
    class Encoding {

        @Test
        @DisplayName("the image is exactly 300 bytes and carries no terminator")
        void theImageIsExactlyTheDeclaredWidth() {
            String encoded = AccountRecordMapper.toRecord(canonicalEntity());

            assertThat(encoded).hasSize(EXPECTED_RECORD_WIDTH);
            assertThat(encoded)
                    .as("record separation belongs to the writer, never to the mapper")
                    .doesNotContain("\n")
                    .doesNotContain("\r");
        }

        @Test
        @DisplayName("the canonical entity produces the canonical image, byte for byte")
        void theCanonicalEntityProducesTheCanonicalImage() {
            assertThat(AccountRecordMapper.toRecord(canonicalEntity()))
                    .as("an independently hand-assembled image is the oracle, never the mapper's own"
                            + " output")
                    .isEqualTo(canonicalImage());
        }

        @Test
        @DisplayName("the identifier is right-justified and zero-padded, as a numeric field must be")
        void theIdentifierIsRightJustifiedAndZeroPadded() {
            String encoded = AccountRecordMapper.toRecord(new Account("7", "Y",
                    new BigDecimal("0.00"), new BigDecimal("0.00"), new BigDecimal("0.00"),
                    "2014-11-20", "2025-05-20", "2025-05-20", new BigDecimal("0.00"),
                    new BigDecimal("0.00"), FIXTURE_ZIP, FIXTURE_GROUP_ID));

            assertThat(encoded.substring(EXPECTED_ID_OFFSET, EXPECTED_KEY_WIDTH))
                    .as("a numeric key left-justified and space-padded would still be eleven bytes"
                            + " wide and would still be the wrong key")
                    .isEqualTo("00000000007");
        }

        @Test
        @DisplayName("character fields are left-justified and space-padded")
        void characterFieldsAreLeftJustifiedAndSpacePadded() {
            String encoded = AccountRecordMapper.toRecord(new Account("00000000001", "Y",
                    new BigDecimal("0.00"), new BigDecimal("0.00"), new BigDecimal("0.00"),
                    "2014-11-20", "2025-05-20", "2025-05-20", new BigDecimal("0.00"),
                    new BigDecimal("0.00"), "AB", "C"));

            assertThat(encoded.substring(EXPECTED_ZIP_OFFSET,
                    EXPECTED_ZIP_OFFSET + EXPECTED_TEN_CHARACTER_WIDTH)).isEqualTo("AB        ");
            assertThat(encoded.substring(EXPECTED_GROUP_ID_OFFSET,
                    EXPECTED_GROUP_ID_OFFSET + EXPECTED_TEN_CHARACTER_WIDTH))
                    .isEqualTo("C         ");
        }

        @Test
        @DisplayName("the trailing filler is 178 spaces, which is the module-wide default")
        void theTrailingFillerIsSpaces() {
            String encoded = AccountRecordMapper.toRecord(canonicalEntity());

            assertThat(encoded.substring(EXPECTED_FILLER_OFFSET))
                    .hasSize(EXPECTED_FILLER_WIDTH)
                    .isEqualTo(run(' ', EXPECTED_FILLER_WIDTH));
        }

        @Test
        @DisplayName("the byte encoder returns the same image, in a fresh array the caller owns")
        void theByteEncoderReturnsTheSameImage() {
            Account account = canonicalEntity();

            byte[] first = AccountRecordMapper.toRecordBytes(account);
            byte[] second = AccountRecordMapper.toRecordBytes(account);

            assertThat(new String(first, StandardCharsets.US_ASCII)).isEqualTo(canonicalImage());
            assertThat(first).hasSize(EXPECTED_RECORD_WIDTH).isNotSameAs(second).isEqualTo(second);
        }

        @Test
        @DisplayName("an over-wide value is refused rather than truncated to fit")
        void anOverWideValueIsRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> AccountRecordMapper.toRecord(new Account("000000000001", "Y",
                            new BigDecimal("0.00"), new BigDecimal("0.00"), new BigDecimal("0.00"),
                            "2014-11-20", "2025-05-20", "2025-05-20", new BigDecimal("0.00"),
                            new BigDecimal("0.00"), FIXTURE_ZIP, FIXTURE_GROUP_ID)));
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> AccountRecordMapper.toRecord(new Account("00000000001", "YY",
                            new BigDecimal("0.00"), new BigDecimal("0.00"), new BigDecimal("0.00"),
                            "2014-11-20", "2025-05-20", "2025-05-20", new BigDecimal("0.00"),
                            new BigDecimal("0.00"), FIXTURE_ZIP, FIXTURE_GROUP_ID)));
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> AccountRecordMapper.toRecord(new Account("00000000001", "Y",
                            new BigDecimal("0.00"), new BigDecimal("0.00"), new BigDecimal("0.00"),
                            "2014-11-201", "2025-05-20", "2025-05-20", new BigDecimal("0.00"),
                            new BigDecimal("0.00"), FIXTURE_ZIP, FIXTURE_GROUP_ID)));
        }

        @Test
        @DisplayName("an amount needing more than ten integral digits is refused, not wrapped")
        void anOverWideAmountIsRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("PIC S9(10)V99 holds ten integral digits, and an eleventh has nowhere to go")
                    .isThrownBy(() -> AccountRecordMapper.toRecord(new Account("00000000001", "Y",
                            new BigDecimal("10000000000.00"), new BigDecimal("0.00"),
                            new BigDecimal("0.00"), "2014-11-20", "2025-05-20", "2025-05-20",
                            new BigDecimal("0.00"), new BigDecimal("0.00"), FIXTURE_ZIP,
                            FIXTURE_GROUP_ID)));
        }

        @Test
        @DisplayName("a null character property is refused, and the refusal names field and property")
        void aNullCharacterPropertyIsRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> AccountRecordMapper.toRecord(new Account(null, "Y",
                            new BigDecimal("0.00"), new BigDecimal("0.00"), new BigDecimal("0.00"),
                            "2014-11-20", "2025-05-20", "2025-05-20", new BigDecimal("0.00"),
                            new BigDecimal("0.00"), FIXTURE_ZIP, FIXTURE_GROUP_ID)))
                    .withMessageContaining("ACCT-ID")
                    .withMessageContaining("acctId");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> AccountRecordMapper.toRecord(new Account("00000000001", "Y",
                            new BigDecimal("0.00"), new BigDecimal("0.00"), new BigDecimal("0.00"),
                            null, "2025-05-20", "2025-05-20", new BigDecimal("0.00"),
                            new BigDecimal("0.00"), FIXTURE_ZIP, FIXTURE_GROUP_ID)))
                    .withMessageContaining("ACCT-OPEN-DATE")
                    .withMessageContaining("presented as spaces");
        }

        @Test
        @DisplayName("a null amount is refused, and the refusal says an unset amount is zero not null")
        void aNullAmountIsRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> AccountRecordMapper.toRecord(new Account("00000000001", "Y",
                            null, new BigDecimal("0.00"), new BigDecimal("0.00"), "2014-11-20",
                            "2025-05-20", "2025-05-20", new BigDecimal("0.00"),
                            new BigDecimal("0.00"), FIXTURE_ZIP, FIXTURE_GROUP_ID)))
                    .withMessageContaining("ACCT-CURR-BAL")
                    .withMessageContaining("presented as zero");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> AccountRecordMapper.toRecordBytes(new Account("00000000001",
                            "Y", new BigDecimal("0.00"), new BigDecimal("0.00"),
                            new BigDecimal("0.00"), "2014-11-20", "2025-05-20", "2025-05-20",
                            new BigDecimal("0.00"), null, FIXTURE_ZIP, FIXTURE_GROUP_ID)))
                    .withMessageContaining("ACCT-CURR-CYC-DEBIT");
        }

        @Test
        @DisplayName("a null entity is refused on both encode entry points")
        void aNullEntityIsRefused() {
            assertThatNullPointerException()
                    .isThrownBy(() -> AccountRecordMapper.toRecord(null))
                    .withMessageContaining("account");
            assertThatNullPointerException()
                    .isThrownBy(() -> AccountRecordMapper.toRecordBytes(null))
                    .withMessageContaining("account");
        }
    }

    @Nested
    @DisplayName("the shipped fixture, read as a byte-level authority")
    class ReferenceFixture {

        @Test
        @DisplayName("the fixture holds fifty 300-byte records on a 301-byte stride")
        void theFixtureHoldsFiftyRecords() {
            SeededRecordFixture loaded = fixture();

            assertThat(loaded.recordCount()).isEqualTo(EXPECTED_FIXTURE_RECORDS);
            assertThat(loaded.impliedByteCount())
                    .as("fifty records of 300 bytes plus one terminator each is 15,050 bytes")
                    .isEqualTo(EXPECTED_FIXTURE_BYTES);
            assertThat(loaded.records())
                    .allSatisfy(record -> assertThat(record).hasSize(EXPECTED_RECORD_WIDTH));
        }

        @ParameterizedTest(name = "record {0}")
        @MethodSource("com.carddemo.util.AccountRecordMapperCoverageTest#fixtureOrdinals")
        @DisplayName("every production record survives a round trip over all three hundred bytes")
        void everyProductionRecordSurvivesAWholeRecordRoundTrip(final int ordinal) {
            String original = fixture().record(ordinal);

            String reEncoded = AccountRecordMapper.toRecord(
                    AccountRecordMapper.fromRecord(original));

            assertThat(reEncoded)
                    .as("this layout's filler is spaces in the fixture and spaces in the encoder, so"
                            + " no window is excused from the comparison")
                    .isEqualTo(original);
        }

        @Test
        @DisplayName("the first record decodes to the values transcribed from the file")
        void theFirstRecordDecodesToItsTranscribedValues() {
            Account first = AccountRecordMapper.fromRecord(fixture().record(1));

            assertThat(first.getAcctId()).isEqualTo("00000000001");
            assertThat(first.getAcctActiveStatus()).isEqualTo("Y");
            assertThat(first.getAcctCurrBal()).isEqualByComparingTo("194.00");
            assertThat(first.getAcctCreditLimit()).isEqualByComparingTo("2020.00");
            assertThat(first.getAcctCashCreditLimit()).isEqualByComparingTo("1020.00");
            assertThat(first.getAcctOpenDate()).isEqualTo("2014-11-20");
            assertThat(first.getAcctExpirationDate()).isEqualTo("2025-05-20");
            assertThat(first.getAcctReissueDate()).isEqualTo("2025-05-20");
            assertThat(first.getAcctCurrCycCredit()).isEqualByComparingTo("0.00");
            assertThat(first.getAcctCurrCycDebit()).isEqualByComparingTo("0.00");
            assertThat(first.getAcctAddrZip()).isEqualTo(FIXTURE_ZIP);
            assertThat(first.getAcctGroupId()).isEqualTo(FIXTURE_GROUP_ID);
        }

        @Test
        @DisplayName("the last record decodes to the values transcribed from the file")
        void theLastRecordDecodesToItsTranscribedValues() {
            Account last = AccountRecordMapper.fromRecord(fixture().record(EXPECTED_FIXTURE_RECORDS));

            assertThat(last.getAcctId()).isEqualTo("00000000050");
            assertThat(last.getAcctCurrBal()).isEqualByComparingTo("492.00");
            assertThat(last.getAcctCreditLimit()).isEqualByComparingTo("6169.00");
            assertThat(last.getAcctCashCreditLimit()).isEqualByComparingTo("4587.00");
            assertThat(last.getAcctOpenDate()).isEqualTo("2011-04-22");
            assertThat(last.getAcctExpirationDate()).isEqualTo("2023-03-09");
            assertThat(last.getAcctReissueDate()).isEqualTo("2023-03-09");
        }

        @Test
        @DisplayName("the fifty identifiers are the contiguous run 1 to 50, so none is duplicated")
        void theFiftyIdentifiersAreContiguous() {
            List<String> identifiers = fixture().records().stream()
                    .map(record -> record.substring(EXPECTED_ID_OFFSET, EXPECTED_KEY_WIDTH))
                    .toList();

            assertThat(identifiers).hasSize(EXPECTED_FIXTURE_RECORDS).doesNotHaveDuplicates();
            assertThat(identifiers.getFirst()).isEqualTo("00000000001");
            assertThat(identifiers.getLast()).isEqualTo("00000000050");
        }

        @Test
        @DisplayName("every fixture record is active, so no inactive path is reachable from seed data")
        void everyFixtureRecordIsActive() {
            assertThat(fixture().records().stream()
                    .map(record -> record.substring(EXPECTED_STATUS_OFFSET,
                            EXPECTED_STATUS_OFFSET + 1))
                    .distinct()
                    .toList())
                    .as("recorded so a later test does not assume an inactive account can be seeded")
                    .containsExactly("Y");
        }

        @Test
        @DisplayName("every fixture amount is positively overpunched, so no negative balance is seeded")
        void everyFixtureAmountIsPositivelyOverpunched() {
            List<Integer> amountOffsets = List.of(EXPECTED_CURR_BAL_OFFSET,
                    EXPECTED_CREDIT_LIMIT_OFFSET, EXPECTED_CASH_CREDIT_LIMIT_OFFSET,
                    EXPECTED_CYC_CREDIT_OFFSET, EXPECTED_CYC_DEBIT_OFFSET);

            assertThat(fixture().records()).allSatisfy(record ->
                    assertThat(amountOffsets)
                            .allSatisfy(offset -> assertThat(
                                    record.charAt(offset + EXPECTED_AMOUNT_WIDTH - 1))
                                    .as("every seeded amount ends in a positive overpunch")
                                    .isEqualTo('{')));
        }

        @Test
        @DisplayName("every fixture record shares one postal code and a blank group identifier")
        void everyFixtureRecordSharesOnePostalCodeAndABlankGroup() {
            assertThat(fixture().records()).allSatisfy(record -> {
                assertThat(record.substring(EXPECTED_ZIP_OFFSET,
                        EXPECTED_ZIP_OFFSET + EXPECTED_TEN_CHARACTER_WIDTH))
                        .isEqualTo(FIXTURE_ZIP);
                assertThat(record.substring(EXPECTED_GROUP_ID_OFFSET,
                        EXPECTED_GROUP_ID_OFFSET + EXPECTED_TEN_CHARACTER_WIDTH))
                        .as("the group identifier is ten significant spaces, not an absent value")
                        .isEqualTo(FIXTURE_GROUP_ID);
            });
        }

        @Test
        @DisplayName("every fixture filler is 178 spaces, which is what licenses the whole-record test")
        void everyFixtureFillerIsSpaces() {
            assertThat(fixture().records()).allSatisfy(record ->
                    assertThat(record.substring(EXPECTED_FILLER_OFFSET))
                            .as("were a single filler byte an ASCII zero, the whole-record round-trip"
                                    + " assertion above would have to be narrowed to a prefix")
                            .isEqualTo(run(' ', EXPECTED_FILLER_WIDTH)));
        }
    }

    @Nested
    @DisplayName("the round trip is a fixed point in both directions")
    class RoundTrip {

        @Test
        @DisplayName("a decode of an encode returns an equal entity, field for field")
        void aDecodeOfAnEncodeReturnsAnEqualEntity() {
            Account original = canonicalEntity();

            Account round = AccountRecordMapper.fromRecord(AccountRecordMapper.toRecord(original));

            assertThat(round.getAcctId()).isEqualTo(original.getAcctId());
            assertThat(round.getAcctActiveStatus()).isEqualTo(original.getAcctActiveStatus());
            assertThat(round.getAcctCurrBal()).isEqualTo(original.getAcctCurrBal());
            assertThat(round.getAcctCreditLimit()).isEqualTo(original.getAcctCreditLimit());
            assertThat(round.getAcctCashCreditLimit()).isEqualTo(original.getAcctCashCreditLimit());
            assertThat(round.getAcctOpenDate()).isEqualTo(original.getAcctOpenDate());
            assertThat(round.getAcctExpirationDate()).isEqualTo(original.getAcctExpirationDate());
            assertThat(round.getAcctReissueDate()).isEqualTo(original.getAcctReissueDate());
            assertThat(round.getAcctCurrCycCredit()).isEqualTo(original.getAcctCurrCycCredit());
            assertThat(round.getAcctCurrCycDebit()).isEqualTo(original.getAcctCurrCycDebit());
            assertThat(round.getAcctAddrZip()).isEqualTo(original.getAcctAddrZip());
            assertThat(round.getAcctGroupId()).isEqualTo(original.getAcctGroupId());
        }

        @Test
        @DisplayName("a second round trip changes nothing, so encoding is idempotent")
        void aSecondRoundTripIsAFixedPoint() {
            String once = AccountRecordMapper.toRecord(
                    AccountRecordMapper.fromRecord(fixture().record(1)));

            String twice = AccountRecordMapper.toRecord(AccountRecordMapper.fromRecord(once));

            assertThat(twice).isEqualTo(once);
        }

        @Test
        @DisplayName("the byte and text round trips agree with each other")
        void theByteAndTextRoundTripsAgree() {
            Account account = canonicalEntity();

            byte[] viaBytes = AccountRecordMapper.toRecordBytes(account);
            String viaText = AccountRecordMapper.toRecord(account);

            assertThat(AccountRecordMapper.fromRecord(viaBytes).getAcctCurrBal())
                    .isEqualTo(AccountRecordMapper.fromRecord(viaText).getAcctCurrBal());
            assertThat(new String(viaBytes, StandardCharsets.US_ASCII)).isEqualTo(viaText);
        }
    }
}
