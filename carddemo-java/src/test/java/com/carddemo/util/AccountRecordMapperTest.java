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
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import com.carddemo.domain.Account;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * Unit test for {@link AccountRecordMapper}, the sole holder of the <strong>300-byte</strong> account
 * record layout and of the two-way mapping between that record image and {@link Account}.
 *
 * <p><strong>Every expectation in this class is hand written.</strong> Not one is produced by calling a
 * production method, and not one is a snapshot of previous output. That is the whole point of the class:
 * fixed-width layout knowledge lives exclusively in the production {@code util} package, so an oracle
 * derived from that same package could not detect a wrong offset - it would slice the comparison
 * value at the identical wrong position and agree with itself. The offsets, widths and field values
 * below were read independently from the account copybook and from the shipped reference data, and are
 * written here as literals. Nothing on disk is opened while this class runs: it touches no file, no
 * classpath resource, no database, no container and no network, so a failure here is always a mapping
 * defect and never an environment one.
 *
 * <p><strong>The five monetary fields are not contiguous.</strong> Three run consecutively at 12, 24 and
 * 36, then the three ten-byte date fields occupy 48, 58 and 68, then the remaining two resume at 78 and
 * 90. An implementation that walked five consecutive twelve-byte amounts would read the open date as an
 * amount and still return a plausible object, which is exactly why the split is asserted explicitly
 * rather than left implicit in a tiling check.
 *
 * <p><strong>The expiry field's offset is 58 and must never move.</strong> The copybook genuinely spells
 * that item without its {@code T} - {@code ACCT-EXPIRAION-DATE} - and the defect is handled by dividing
 * it: the layout position is load bearing and is preserved byte for byte, the mapper's geometry
 * constants mirror the misspelling so the mapping stays findable under the copybook's own name, and
 * only the Java property is spelled correctly. Row 1 of the source anomaly register. This class pins
 * offset 58 so a well-meaning rename can never shift the layout behind a corrected name.
 *
 * <p><strong>Two reference-data anomalies are preserved, not repaired.</strong> On every seeded account
 * the ten bytes at the ZIP position hold a value beginning with a letter, and the ten bytes at the
 * group-identifier position hold ten spaces. Mapping is strictly positional, so neither is swapped,
 * inferred or corrected here. The consequence is behavioural rather than cosmetic: because the group
 * identifier is blank on every seeded account, the disclosure-group lookup compares two space-padded
 * ten-byte keys and misses, which is what makes the default-group fallback the branch the reference data
 * actually exercises. Trimming the value in a test would quietly endorse trimming it in production and
 * disable that path, so no value is trimmed anywhere in this class.
 *
 * <p><strong>Truncation, not rounding, and scale exactly two.</strong> Each amount is a zoned decimal
 * occupying twelve encoded bytes - ten integer digits and two implied decimals - with its sign
 * overpunched into the final digit byte rather than carried separately. No arithmetic statement in the
 * estate specifies rounding, so a COBOL store into a two-decimal field truncates toward zero; both the
 * decoded value and its scale are therefore asserted, since a half-even substitution would differ by one
 * cent and would pass a test that asserted the value alone. Scale is never adjusted here: the codec is
 * the module's single scaling point, and every expected amount is built from a string rather than from a
 * binary floating-point literal.
 *
 * <p>The seeded amounts all carry the positive-zero overpunch, so this fixture alone exercises one sign
 * encoding. A second, synthetic image supplies the remaining write-side sign cases, and exhaustive sign
 * coverage belongs to the codec's own test rather than here.
 *
 * <p>A mis-sized image raises {@link IllegalArgumentException} rather than a domain exception, because a
 * caller supplying the wrong number of bytes has no legacy antecedent: the legacy records are fixed
 * length by construction. That divergence, the preserved misspelling, the preserved reference-data
 * anomaly and the single scaling point are all recorded in the module's decision log; this class only
 * proves them.
 *
 * <p>Provenance: checkout SHA {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. Carried as a header string for traceability
 * only; no member asserts it, and no copybook, program or job-stream text is reproduced.
 */
@DisplayName("AccountRecordMapper: the hand-verified 300-byte account layout")
class AccountRecordMapperTest {

    // ------------------------------------------------------------------------------------------
    // Layout oracle. Independently derived from the account copybook, corroborated by the cluster
    // definition that fixes an eleven-byte key at offset zero and by the sequential reader that
    // splits the same image into eleven key bytes plus a 289-byte remainder. Written as literals on
    // purpose: a constant taken from the class under test could not falsify that class.
    // ------------------------------------------------------------------------------------------

    private static final int EXPECTED_RECORD_LENGTH = 300;

    private static final int EXPECTED_KEY_LENGTH = 11;

    private static final int EXPECTED_DATA_LENGTH = 289;

    private static final int EXPECTED_MAPPED_PREFIX_LENGTH = 122;

    private static final int EXPECTED_ACCT_ID_OFFSET = 0;

    private static final int EXPECTED_ACCT_ID_LENGTH = 11;

    private static final int EXPECTED_ACTIVE_STATUS_OFFSET = 11;

    private static final int EXPECTED_ACTIVE_STATUS_LENGTH = 1;

    private static final int EXPECTED_CURR_BAL_OFFSET = 12;

    private static final int EXPECTED_CREDIT_LIMIT_OFFSET = 24;

    private static final int EXPECTED_CASH_CREDIT_LIMIT_OFFSET = 36;

    private static final int EXPECTED_OPEN_DATE_OFFSET = 48;

    /**
     * Offset of the misspelled expiry item. Pinned to 58 as its own named expectation, because this is
     * the one position in the layout a rename is most likely to disturb.
     */
    private static final int EXPECTED_EXPIRATION_DATE_OFFSET = 58;

    private static final int EXPECTED_REISSUE_DATE_OFFSET = 68;

    private static final int EXPECTED_CURR_CYC_CREDIT_OFFSET = 78;

    private static final int EXPECTED_CURR_CYC_DEBIT_OFFSET = 90;

    private static final int EXPECTED_ADDR_ZIP_OFFSET = 102;

    private static final int EXPECTED_GROUP_ID_OFFSET = 112;

    private static final int EXPECTED_FILLER_OFFSET = 122;

    private static final int EXPECTED_FILLER_LENGTH = 178;

    private static final int EXPECTED_AMOUNT_LENGTH = 12;

    private static final int EXPECTED_TEN_BYTE_LENGTH = 10;

    private static final int EXPECTED_DATE_BLOCK_LENGTH = 30;

    private static final int EXPECTED_MONETARY_SCALE = 2;

    // ------------------------------------------------------------------------------------------
    // Value oracle for the first reference record, read byte by byte at the offsets above. Each
    // slice is a literal, so a shifted offset in the mapper changes the parsed value and fails.
    // ------------------------------------------------------------------------------------------

    /** Leading zeros are significant, so the identifier is text: never {@code 1}, never {@code "1"}. */
    private static final String ROW_ACCT_ID = "00000000001";

    /** Carried as a raw one-character code; this layer neither translates nor validates it. */
    private static final String ROW_ACTIVE_STATUS = "Y";

    private static final String ROW_CURR_BAL_IMAGE = "00000001940{";

    private static final String ROW_CREDIT_LIMIT_IMAGE = "00000020200{";

    private static final String ROW_CASH_CREDIT_LIMIT_IMAGE = "00000010200{";

    private static final String ROW_OPEN_DATE = "2014-11-20";

    private static final String ROW_EXPIRATION_DATE = "2025-05-20";

    private static final String ROW_REISSUE_DATE = "2025-05-20";

    private static final String ROW_CURR_CYC_CREDIT_IMAGE = "00000000000{";

    private static final String ROW_CURR_CYC_DEBIT_IMAGE = "00000000000{";

    /**
     * Preserved anomaly: the seeded ZIP begins with a letter rather than a digit, so it is copied
     * verbatim and is never treated as a number.
     */
    private static final String ROW_ADDR_ZIP = "A000000000";

    /**
     * Preserved anomaly: ten spaces, on every seeded account. Behaviourally significant - a blank group
     * identifier is what makes the disclosure-group lookup miss and the default group the reachable
     * path - so it is asserted at full width and is never trimmed.
     */
    private static final String ROW_GROUP_ID = "          ";

    /** The trimmed form of an all-space value, named so the "never trimmed" assertion reads clearly. */
    private static final String TRIMMED_GROUP_ID = "";

    private static final BigDecimal ROW_CURR_BAL = new BigDecimal("194.00");

    private static final BigDecimal ROW_CREDIT_LIMIT = new BigDecimal("2020.00");

    private static final BigDecimal ROW_CASH_CREDIT_LIMIT = new BigDecimal("1020.00");

    private static final BigDecimal ROW_CURR_CYC_CREDIT = new BigDecimal("0.00");

    private static final BigDecimal ROW_CURR_CYC_DEBIT = new BigDecimal("0.00");

    private static final String EXPECTED_FILLER = " ".repeat(EXPECTED_FILLER_LENGTH);

    /**
     * The whole reference record, assembled from the twelve verified slices in copybook order plus the
     * filler run. Assembling it here rather than transcribing 300 characters keeps each field's value
     * visible beside its offset, and its encoded width is asserted before it is relied upon.
     */
    private static final String ROW_IMAGE = ROW_ACCT_ID
            + ROW_ACTIVE_STATUS
            + ROW_CURR_BAL_IMAGE
            + ROW_CREDIT_LIMIT_IMAGE
            + ROW_CASH_CREDIT_LIMIT_IMAGE
            + ROW_OPEN_DATE
            + ROW_EXPIRATION_DATE
            + ROW_REISSUE_DATE
            + ROW_CURR_CYC_CREDIT_IMAGE
            + ROW_CURR_CYC_DEBIT_IMAGE
            + ROW_ADDR_ZIP
            + ROW_GROUP_ID
            + EXPECTED_FILLER;

    private static final byte ASCII_SPACE = 0x20;

    /** Layout name the mapper carries into every diagnostic: the copybook group and the copybook. */
    private static final String EXPECTED_ARTEFACT = "ACCOUNT-RECORD (CVACT01Y)";

    // ------------------------------------------------------------------------------------------
    // A second, synthetic oracle. The reference data carries only the positive-zero overpunch, so
    // the remaining write-side sign encodings are pinned here: '{' and 'A' through 'I' encode
    // positive zero through nine, '}' and 'J' through 'R' encode negative zero through nine. Every
    // expected image below was derived by hand from that convention and from the field's width, and
    // the group identifier is a non-blank ten-byte value so the padding rule is visible too.
    // ------------------------------------------------------------------------------------------

    private static final String SIGNED_ACCT_ID = "00000000042";

    private static final String SIGNED_ACTIVE_STATUS = "N";

    /** Negative, cent digit nine: the final byte becomes 'R'. */
    private static final BigDecimal SIGNED_CURR_BAL = new BigDecimal("-4321.09");

    private static final String SIGNED_CURR_BAL_IMAGE = "00000043210R";

    /** The widest value the field admits, positive, cent digit nine: the final byte becomes 'I'. */
    private static final BigDecimal SIGNED_CREDIT_LIMIT = new BigDecimal("9999999999.99");

    private static final String SIGNED_CREDIT_LIMIT_IMAGE = "99999999999I";

    /** The smallest positive amount the scale admits, cent digit one: the final byte becomes 'A'. */
    private static final BigDecimal SIGNED_CASH_CREDIT_LIMIT = new BigDecimal("0.01");

    private static final String SIGNED_CASH_CREDIT_LIMIT_IMAGE = "00000000000A";

    /** Negative, cent digit five: the final byte becomes 'N'. */
    private static final BigDecimal SIGNED_CURR_CYC_CREDIT = new BigDecimal("-0.05");

    private static final String SIGNED_CURR_CYC_CREDIT_IMAGE = "00000000000N";

    /** Positive, cent digit four: the final byte becomes 'D'. */
    private static final BigDecimal SIGNED_CURR_CYC_DEBIT = new BigDecimal("12.34");

    private static final String SIGNED_CURR_CYC_DEBIT_IMAGE = "00000000123D";

    private static final String SIGNED_GROUP_ID = "ZEROAPR   ";

    /** The whole synthetic image, assembled from the hand-derived slices in copybook order. */
    private static final String SIGNED_IMAGE = SIGNED_ACCT_ID
            + SIGNED_ACTIVE_STATUS
            + SIGNED_CURR_BAL_IMAGE
            + SIGNED_CREDIT_LIMIT_IMAGE
            + SIGNED_CASH_CREDIT_LIMIT_IMAGE
            + ROW_OPEN_DATE
            + ROW_EXPIRATION_DATE
            + ROW_REISSUE_DATE
            + SIGNED_CURR_CYC_CREDIT_IMAGE
            + SIGNED_CURR_CYC_DEBIT_IMAGE
            + ROW_ADDR_ZIP
            + SIGNED_GROUP_ID
            + EXPECTED_FILLER;

    // ------------------------------------------------------------------------------------------
    // Helpers. Deliberately tiny and free of production calls: they encode, slice and compare, and
    // never compute an expected value.
    // ------------------------------------------------------------------------------------------

    /**
     * Encodes a value as US-ASCII. Named explicitly at every string-to-byte boundary, because a
     * character count is not a width and only an encoded byte length is layout evidence.
     */
    private static byte[] asciiBytes(String value) {
        return value.getBytes(StandardCharsets.US_ASCII);
    }

    /** Encoded byte length of a value, which is the only width authority this class recognises. */
    private static int encodedWidth(String value) {
        return asciiBytes(value).length;
    }

    /**
     * Builds the reference account through the entity's public twelve-argument constructor, in copybook
     * order. Used by the encoding tests so that a constructor whose parameters were reordered would
     * place values at the wrong offsets and fail rather than compile quietly. The protected no-argument
     * constructor belongs to the persistence provider and is never called from here.
     */
    private static Account referenceAccount() {
        return new Account(ROW_ACCT_ID,
                ROW_ACTIVE_STATUS,
                ROW_CURR_BAL,
                ROW_CREDIT_LIMIT,
                ROW_CASH_CREDIT_LIMIT,
                ROW_OPEN_DATE,
                ROW_EXPIRATION_DATE,
                ROW_REISSUE_DATE,
                ROW_CURR_CYC_CREDIT,
                ROW_CURR_CYC_DEBIT,
                ROW_ADDR_ZIP,
                ROW_GROUP_ID);
    }

    /**
     * Builds the synthetic signed account through the same public twelve-argument constructor, reusing
     * the reference dates and ZIP so that only the amounts, the identifier, the status and the group
     * identifier differ from the reference row.
     */
    private static Account signedAccount() {
        return new Account(SIGNED_ACCT_ID,
                SIGNED_ACTIVE_STATUS,
                SIGNED_CURR_BAL,
                SIGNED_CREDIT_LIMIT,
                SIGNED_CASH_CREDIT_LIMIT,
                ROW_OPEN_DATE,
                ROW_EXPIRATION_DATE,
                ROW_REISSUE_DATE,
                SIGNED_CURR_CYC_CREDIT,
                SIGNED_CURR_CYC_DEBIT,
                ROW_ADDR_ZIP,
                SIGNED_GROUP_ID);
    }

    /**
     * Asserts all twelve mapped properties and the untouched locking counter against the hand-written
     * oracle. Compared property by property on purpose: the entity's equality is defined over the
     * account identifier alone, so comparing two entities as wholes would pass while eleven fields
     * differed.
     */
    private static void assertMatchesReferenceRow(Account account) {
        assertThat(account.getAcctId()).isEqualTo(ROW_ACCT_ID);
        assertThat(account.getAcctActiveStatus()).isEqualTo(ROW_ACTIVE_STATUS);
        assertThat(account.getAcctCurrBal()).isEqualTo(ROW_CURR_BAL);
        assertThat(account.getAcctCreditLimit()).isEqualTo(ROW_CREDIT_LIMIT);
        assertThat(account.getAcctCashCreditLimit()).isEqualTo(ROW_CASH_CREDIT_LIMIT);
        assertThat(account.getAcctOpenDate()).isEqualTo(ROW_OPEN_DATE);
        assertThat(account.getAcctExpirationDate()).isEqualTo(ROW_EXPIRATION_DATE);
        assertThat(account.getAcctReissueDate()).isEqualTo(ROW_REISSUE_DATE);
        assertThat(account.getAcctCurrCycCredit()).isEqualTo(ROW_CURR_CYC_CREDIT);
        assertThat(account.getAcctCurrCycDebit()).isEqualTo(ROW_CURR_CYC_DEBIT);
        assertThat(account.getAcctAddrZip()).isEqualTo(ROW_ADDR_ZIP);
        assertThat(account.getAcctGroupId()).isEqualTo(ROW_GROUP_ID);
        assertThat(account.getVersion()).isZero();
    }

    @Nested
    @DisplayName("the published layout, pinned to independently derived literals")
    class ThePublishedLayout {

        @Test
        @DisplayName("all twelve field offsets equal the values read from the copybook, so no field can "
                + "drift without failing here")
        void allTwelveOffsetsEqualTheDerivedValues() {
            assertThat(AccountRecordMapper.ACCT_ID_OFFSET).isEqualTo(EXPECTED_ACCT_ID_OFFSET);
            assertThat(AccountRecordMapper.ACCT_ACTIVE_STATUS_OFFSET)
                    .isEqualTo(EXPECTED_ACTIVE_STATUS_OFFSET);
            assertThat(AccountRecordMapper.ACCT_CURR_BAL_OFFSET).isEqualTo(EXPECTED_CURR_BAL_OFFSET);
            assertThat(AccountRecordMapper.ACCT_CREDIT_LIMIT_OFFSET)
                    .isEqualTo(EXPECTED_CREDIT_LIMIT_OFFSET);
            assertThat(AccountRecordMapper.ACCT_CASH_CREDIT_LIMIT_OFFSET)
                    .isEqualTo(EXPECTED_CASH_CREDIT_LIMIT_OFFSET);
            assertThat(AccountRecordMapper.ACCT_OPEN_DATE_OFFSET).isEqualTo(EXPECTED_OPEN_DATE_OFFSET);
            // The constant mirrors the copybook's own misspelling; only the entity property is
            // spelled correctly. Preserving the name keeps the mapping findable under the copybook.
            assertThat(AccountRecordMapper.ACCT_EXPIRAION_DATE_OFFSET)
                    .isEqualTo(EXPECTED_EXPIRATION_DATE_OFFSET);
            assertThat(AccountRecordMapper.ACCT_REISSUE_DATE_OFFSET)
                    .isEqualTo(EXPECTED_REISSUE_DATE_OFFSET);
            assertThat(AccountRecordMapper.ACCT_CURR_CYC_CREDIT_OFFSET)
                    .isEqualTo(EXPECTED_CURR_CYC_CREDIT_OFFSET);
            assertThat(AccountRecordMapper.ACCT_CURR_CYC_DEBIT_OFFSET)
                    .isEqualTo(EXPECTED_CURR_CYC_DEBIT_OFFSET);
            assertThat(AccountRecordMapper.ACCT_ADDR_ZIP_OFFSET).isEqualTo(EXPECTED_ADDR_ZIP_OFFSET);
            assertThat(AccountRecordMapper.ACCT_GROUP_ID_OFFSET).isEqualTo(EXPECTED_GROUP_ID_OFFSET);
        }

        @Test
        @DisplayName("all twelve field lengths equal the widths the picture clauses declare, and the "
                + "filler run declares 178")
        void allTwelveLengthsEqualTheDerivedWidths() {
            assertThat(AccountRecordMapper.ACCT_ID_LENGTH).isEqualTo(EXPECTED_ACCT_ID_LENGTH);
            assertThat(AccountRecordMapper.ACCT_ACTIVE_STATUS_LENGTH)
                    .isEqualTo(EXPECTED_ACTIVE_STATUS_LENGTH);
            assertThat(AccountRecordMapper.ACCT_CURR_BAL_LENGTH).isEqualTo(EXPECTED_AMOUNT_LENGTH);
            assertThat(AccountRecordMapper.ACCT_CREDIT_LIMIT_LENGTH).isEqualTo(EXPECTED_AMOUNT_LENGTH);
            assertThat(AccountRecordMapper.ACCT_CASH_CREDIT_LIMIT_LENGTH)
                    .isEqualTo(EXPECTED_AMOUNT_LENGTH);
            assertThat(AccountRecordMapper.ACCT_OPEN_DATE_LENGTH).isEqualTo(EXPECTED_TEN_BYTE_LENGTH);
            assertThat(AccountRecordMapper.ACCT_EXPIRAION_DATE_LENGTH)
                    .isEqualTo(EXPECTED_TEN_BYTE_LENGTH);
            assertThat(AccountRecordMapper.ACCT_REISSUE_DATE_LENGTH)
                    .isEqualTo(EXPECTED_TEN_BYTE_LENGTH);
            assertThat(AccountRecordMapper.ACCT_CURR_CYC_CREDIT_LENGTH)
                    .isEqualTo(EXPECTED_AMOUNT_LENGTH);
            assertThat(AccountRecordMapper.ACCT_CURR_CYC_DEBIT_LENGTH)
                    .isEqualTo(EXPECTED_AMOUNT_LENGTH);
            assertThat(AccountRecordMapper.ACCT_ADDR_ZIP_LENGTH).isEqualTo(EXPECTED_TEN_BYTE_LENGTH);
            assertThat(AccountRecordMapper.ACCT_GROUP_ID_LENGTH).isEqualTo(EXPECTED_TEN_BYTE_LENGTH);
            assertThat(AccountRecordMapper.FILLER_LENGTH).isEqualTo(EXPECTED_FILLER_LENGTH);
        }

        @Test
        @DisplayName("the record width is 300 and the thirteen declared widths sum to exactly that")
        void theThirteenDeclaredWidthsSumToTheRecordWidth() {
            final int summed = AccountRecordMapper.ACCT_ID_LENGTH
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
                    + AccountRecordMapper.ACCT_GROUP_ID_LENGTH
                    + AccountRecordMapper.FILLER_LENGTH;

            assertThat(AccountRecordMapper.RECORD_LENGTH).isEqualTo(EXPECTED_RECORD_LENGTH);
            assertThat(summed).isEqualTo(EXPECTED_RECORD_LENGTH);
        }

        @Test
        @DisplayName("the key is eleven bytes at offset zero and the remainder completes the record, "
                + "matching the cluster declaration, so identity is the business key and never a "
                + "surrogate")
        void theKeyIsElevenBytesAtOffsetZeroAndTheRemainderCompletesTheRecord() {
            assertThat(AccountRecordMapper.ACCT_ID_OFFSET).isEqualTo(EXPECTED_ACCT_ID_OFFSET);
            assertThat(AccountRecordMapper.KEY_LENGTH).isEqualTo(EXPECTED_KEY_LENGTH);
            assertThat(AccountRecordMapper.DATA_LENGTH).isEqualTo(EXPECTED_DATA_LENGTH);
            assertThat(AccountRecordMapper.ACCT_ID_LENGTH).isEqualTo(EXPECTED_KEY_LENGTH);
            assertThat(AccountRecordMapper.KEY_LENGTH + AccountRecordMapper.DATA_LENGTH)
                    .isEqualTo(EXPECTED_RECORD_LENGTH);
        }

        @Test
        @DisplayName("the mapped prefix ends at 122, where the filler run begins, and the filler closes "
                + "the record")
        void theMappedPrefixEndsWhereTheFillerBegins() {
            assertThat(AccountRecordMapper.MAPPED_PREFIX_LENGTH)
                    .isEqualTo(EXPECTED_MAPPED_PREFIX_LENGTH);
            assertThat(AccountRecordMapper.FILLER_OFFSET).isEqualTo(EXPECTED_FILLER_OFFSET);
            assertThat(AccountRecordMapper.ACCT_GROUP_ID_OFFSET
                    + AccountRecordMapper.ACCT_GROUP_ID_LENGTH)
                    .isEqualTo(EXPECTED_MAPPED_PREFIX_LENGTH);
            assertThat(AccountRecordMapper.FILLER_OFFSET + AccountRecordMapper.FILLER_LENGTH)
                    .isEqualTo(EXPECTED_RECORD_LENGTH);
        }

        @Test
        @DisplayName("the five monetary fields are split three-and-two by the three date fields, so a "
                + "walk of five consecutive amounts cannot pass")
        void theFiveMonetaryFieldsAreSplitByTheThreeDateFields() {
            // First monetary run: three consecutive twelve-byte amounts at 12, 24 and 36.
            assertThat(AccountRecordMapper.ACCT_CURR_BAL_OFFSET
                    + AccountRecordMapper.ACCT_CURR_BAL_LENGTH)
                    .isEqualTo(EXPECTED_CREDIT_LIMIT_OFFSET);
            assertThat(AccountRecordMapper.ACCT_CREDIT_LIMIT_OFFSET
                    + AccountRecordMapper.ACCT_CREDIT_LIMIT_LENGTH)
                    .isEqualTo(EXPECTED_CASH_CREDIT_LIMIT_OFFSET);

            // The run is then interrupted: the byte after the third amount is the open date, not a
            // fourth amount, and the three ten-byte dates occupy 48, 58 and 68.
            assertThat(AccountRecordMapper.ACCT_CASH_CREDIT_LIMIT_OFFSET
                    + AccountRecordMapper.ACCT_CASH_CREDIT_LIMIT_LENGTH)
                    .isEqualTo(EXPECTED_OPEN_DATE_OFFSET)
                    .isNotEqualTo(EXPECTED_CURR_CYC_CREDIT_OFFSET);
            assertThat(AccountRecordMapper.ACCT_OPEN_DATE_OFFSET
                    + AccountRecordMapper.ACCT_OPEN_DATE_LENGTH)
                    .isEqualTo(EXPECTED_EXPIRATION_DATE_OFFSET);
            assertThat(AccountRecordMapper.ACCT_EXPIRAION_DATE_OFFSET
                    + AccountRecordMapper.ACCT_EXPIRAION_DATE_LENGTH)
                    .isEqualTo(EXPECTED_REISSUE_DATE_OFFSET);

            // Second monetary run: the remaining two amounts resume at 78 and 90, immediately after
            // the thirty bytes of dates.
            assertThat(AccountRecordMapper.ACCT_REISSUE_DATE_OFFSET
                    + AccountRecordMapper.ACCT_REISSUE_DATE_LENGTH)
                    .isEqualTo(EXPECTED_CURR_CYC_CREDIT_OFFSET);
            assertThat(AccountRecordMapper.ACCT_CURR_CYC_CREDIT_OFFSET
                    + AccountRecordMapper.ACCT_CURR_CYC_CREDIT_LENGTH)
                    .isEqualTo(EXPECTED_CURR_CYC_DEBIT_OFFSET);
            assertThat(EXPECTED_CURR_CYC_CREDIT_OFFSET - EXPECTED_OPEN_DATE_OFFSET)
                    .isEqualTo(EXPECTED_DATE_BLOCK_LENGTH);
        }

        @Test
        @DisplayName("the expiry field keeps offset 58 and width 10 despite the copybook misspelling, "
                + "which is preserved as a layout position and corrected only in the Java property")
        void theMisspelledExpiryFieldKeepsOffsetFiftyEight() {
            assertThat(AccountRecordMapper.ACCT_EXPIRAION_DATE_OFFSET)
                    .isEqualTo(EXPECTED_EXPIRATION_DATE_OFFSET);
            assertThat(AccountRecordMapper.ACCT_EXPIRAION_DATE_LENGTH)
                    .isEqualTo(EXPECTED_TEN_BYTE_LENGTH);
        }

        @Test
        @DisplayName("the monetary scale is two and the truncating rounding mode is the codec's, so no "
                + "second rounding policy can enter through this mapper")
        void theMonetaryScaleAndRoundingModeAreTheCodecs() {
            assertThat(ZonedDecimalCodec.MONETARY_SCALE).isEqualTo(EXPECTED_MONETARY_SCALE);
            assertThat(ZonedDecimalCodec.ACCOUNT_AMOUNT_WIDTH).isEqualTo(EXPECTED_AMOUNT_LENGTH);
            assertThat(ZonedDecimalCodec.COBOL_TRUNCATION_MODE).isEqualTo(RoundingMode.DOWN);
        }
    }

    @Nested
    @DisplayName("decoding the verified reference record")
    class DecodingTheVerifiedReferenceRecord {

        @Test
        @DisplayName("the hand-assembled reference image is exactly 300 encoded bytes, so the oracle "
                + "itself is sound before anything is decoded from it")
        void theHandAssembledImageIsExactlyThreeHundredEncodedBytes() {
            assertThat(encodedWidth(ROW_IMAGE)).isEqualTo(EXPECTED_RECORD_LENGTH);
            assertThat(encodedWidth(EXPECTED_FILLER)).isEqualTo(EXPECTED_FILLER_LENGTH);
        }

        @Test
        @DisplayName("all twelve mapped properties decode to their independently derived values, which "
                + "is what proves all twelve offsets")
        void allTwelveMappedPropertiesDecodeToTheirDerivedValues() {
            final Account decoded = AccountRecordMapper.fromRecord(ROW_IMAGE);

            assertMatchesReferenceRow(decoded);
        }

        @Test
        @DisplayName("the eleven-digit identifier keeps its significant leading zeros and stays text")
        void theIdentifierKeepsItsSignificantLeadingZeros() {
            final Account decoded = AccountRecordMapper.fromRecord(ROW_IMAGE);

            assertThat(decoded.getAcctId())
                    .isEqualTo(ROW_ACCT_ID)
                    .isNotEqualTo("1")
                    .startsWith("0000000000");
            assertThat(encodedWidth(decoded.getAcctId())).isEqualTo(EXPECTED_ACCT_ID_LENGTH);
        }

        @Test
        @DisplayName("the ZIP is copied verbatim even though it begins with a letter, so it is never "
                + "treated as a number - a preserved reference-data anomaly")
        void theZipIsCopiedVerbatimEvenThoughItBeginsWithALetter() {
            final Account decoded = AccountRecordMapper.fromRecord(ROW_IMAGE);

            assertThat(decoded.getAcctAddrZip()).isEqualTo(ROW_ADDR_ZIP).startsWith("A");
            assertThat(encodedWidth(decoded.getAcctAddrZip())).isEqualTo(EXPECTED_TEN_BYTE_LENGTH);
        }

        @Test
        @DisplayName("the group identifier arrives as ten spaces and survives untrimmed, because the "
                + "blank value is what makes the default disclosure group the reachable path")
        void theGroupIdentifierArrivesAsTenSpacesAndSurvivesUntrimmed() {
            final Account decoded = AccountRecordMapper.fromRecord(ROW_IMAGE);

            assertThat(decoded.getAcctGroupId())
                    .isEqualTo(ROW_GROUP_ID)
                    .isNotEqualTo(TRIMMED_GROUP_ID)
                    .isNotEmpty();
            assertThat(encodedWidth(decoded.getAcctGroupId())).isEqualTo(EXPECTED_TEN_BYTE_LENGTH);
            assertThat(asciiBytes(decoded.getAcctGroupId())).isEqualTo(asciiBytes(ROW_GROUP_ID));
        }

        @Test
        @DisplayName("the status is a raw one-character code, neither translated to a constant nor "
                + "rejected for being outside a vocabulary")
        void theStatusIsARawOneCharacterCode() {
            final Account decoded = AccountRecordMapper.fromRecord(ROW_IMAGE);

            assertThat(decoded.getAcctActiveStatus()).isEqualTo(ROW_ACTIVE_STATUS);
            assertThat(encodedWidth(decoded.getAcctActiveStatus()))
                    .isEqualTo(EXPECTED_ACTIVE_STATUS_LENGTH);
        }

        @Test
        @DisplayName("the three date fields decode as the ten-byte text they are, with the expiry taken "
                + "from offset 58")
        void theThreeDateFieldsDecodeAsTenByteText() {
            final Account decoded = AccountRecordMapper.fromRecord(ROW_IMAGE);

            assertThat(decoded.getAcctOpenDate()).isEqualTo(ROW_OPEN_DATE);
            assertThat(decoded.getAcctExpirationDate()).isEqualTo(ROW_EXPIRATION_DATE);
            assertThat(decoded.getAcctReissueDate()).isEqualTo(ROW_REISSUE_DATE);
            assertThat(encodedWidth(decoded.getAcctOpenDate())).isEqualTo(EXPECTED_TEN_BYTE_LENGTH);
            assertThat(encodedWidth(decoded.getAcctExpirationDate()))
                    .isEqualTo(EXPECTED_TEN_BYTE_LENGTH);
            assertThat(encodedWidth(decoded.getAcctReissueDate()))
                    .isEqualTo(EXPECTED_TEN_BYTE_LENGTH);
        }

        @Test
        @DisplayName("all five amounts decode to their derived value at scale exactly two, so a "
                + "half-even substitution differing by one cent cannot pass")
        void allFiveAmountsDecodeAtScaleExactlyTwo() {
            final Account decoded = AccountRecordMapper.fromRecord(ROW_IMAGE);

            assertThat(decoded.getAcctCurrBal()).isEqualTo(ROW_CURR_BAL);
            assertThat(decoded.getAcctCurrBal().scale()).isEqualTo(EXPECTED_MONETARY_SCALE);
            assertThat(decoded.getAcctCreditLimit()).isEqualTo(ROW_CREDIT_LIMIT);
            assertThat(decoded.getAcctCreditLimit().scale()).isEqualTo(EXPECTED_MONETARY_SCALE);
            assertThat(decoded.getAcctCashCreditLimit()).isEqualTo(ROW_CASH_CREDIT_LIMIT);
            assertThat(decoded.getAcctCashCreditLimit().scale()).isEqualTo(EXPECTED_MONETARY_SCALE);
            assertThat(decoded.getAcctCurrCycCredit()).isEqualTo(ROW_CURR_CYC_CREDIT);
            assertThat(decoded.getAcctCurrCycCredit().scale()).isEqualTo(EXPECTED_MONETARY_SCALE);
            assertThat(decoded.getAcctCurrCycDebit()).isEqualTo(ROW_CURR_CYC_DEBIT);
            assertThat(decoded.getAcctCurrCycDebit().scale()).isEqualTo(EXPECTED_MONETARY_SCALE);
        }

        @Test
        @DisplayName("the seeded amounts carry the positive-zero overpunch in their final byte, "
                + "which is the only sign this reference data exercises")
        void theSeededAmountsCarryThePositiveZeroOverpunch() {
            // The seed's five amounts all end '{', the positive-zero overpunch: the sign is folded
            // into the final digit byte rather than carried separately. Exhaustive sign coverage
            // belongs to the codec's own test; the write-side cases appear below on a synthetic image.
            assertThat(ROW_CURR_BAL_IMAGE).endsWith("{");
            assertThat(ROW_CREDIT_LIMIT_IMAGE).endsWith("{");
            assertThat(ROW_CASH_CREDIT_LIMIT_IMAGE).endsWith("{");
            assertThat(ROW_CURR_CYC_CREDIT_IMAGE).endsWith("{");
            assertThat(ROW_CURR_CYC_DEBIT_IMAGE).endsWith("{");
            assertThat(AccountRecordMapper.fromRecord(ROW_IMAGE).getAcctCurrBal())
                    .isEqualTo(ROW_CURR_BAL)
                    .isPositive();
        }

        @Test
        @DisplayName("the optimistic-locking counter is left at its default, because the record image "
                + "does not carry it and the mapper must not invent one")
        void theOptimisticLockingCounterIsLeftAtItsDefault() {
            final Account decoded = AccountRecordMapper.fromRecord(ROW_IMAGE);

            assertThat(decoded.getVersion()).isZero();
        }

        @Test
        @DisplayName("the byte-array entry point yields the same twelve derived values as the "
                + "string one")
        void theByteArrayEntryPointYieldsTheSameDerivedValues() {
            final Account decoded = AccountRecordMapper.fromRecord(asciiBytes(ROW_IMAGE));

            assertMatchesReferenceRow(decoded);
        }

        @Test
        @DisplayName("the byte-range entry point selects the record from inside a larger buffer and "
                + "yields the same twelve derived values, leaving the terminator behind")
        void theByteRangeEntryPointSelectsTheRecordFromInsideALargerBuffer() {
            // A whole newline-terminated fixed-width file has a stride one byte wider than the record,
            // so record i begins at i * (300 + 1). Two records are framed here and the second is
            // selected, which is the seam a file reader uses.
            final int stride = EXPECTED_RECORD_LENGTH + 1;
            final String twoRecordFile = ROW_IMAGE + "\n" + ROW_IMAGE + "\n";
            final byte[] buffer = asciiBytes(twoRecordFile);

            assertThat(buffer).hasSize(2 * stride);
            assertMatchesReferenceRow(AccountRecordMapper.fromRecord(buffer, 0));
            assertMatchesReferenceRow(AccountRecordMapper.fromRecord(buffer, stride));
        }

        @Test
        @DisplayName("the three decoding entry points agree field by field, compared property by "
                + "property because entity equality is defined over the identifier alone")
        void theThreeDecodingEntryPointsAgreeFieldByField() {
            final byte[] encoded = asciiBytes(ROW_IMAGE);
            final byte[] framed = asciiBytes(ROW_IMAGE + "\n");

            final Account fromText = AccountRecordMapper.fromRecord(ROW_IMAGE);
            final Account fromBytes = AccountRecordMapper.fromRecord(encoded);
            final Account fromRange = AccountRecordMapper.fromRecord(framed, 0);

            assertMatchesReferenceRow(fromText);
            assertMatchesReferenceRow(fromBytes);
            assertMatchesReferenceRow(fromRange);
            assertThat(fromBytes).isEqualTo(fromText);
            assertThat(fromRange).isEqualTo(fromText);
            assertThat(fromBytes).hasSameHashCodeAs(fromText);
            assertThat(AccountRecordMapper.toRecord(fromBytes))
                    .isEqualTo(AccountRecordMapper.toRecord(fromText))
                    .isEqualTo(AccountRecordMapper.toRecord(fromRange));
        }
    }

    @Nested
    @DisplayName("emitting a record image")
    class EmittingARecordImage {

        @Test
        @DisplayName("the mapped prefix from 0 up to but excluding 122 is reproduced byte for byte "
                + "under US-ASCII, which is the mapper's own guarantee")
        void theMappedPrefixIsReproducedByteForByte() {
            final byte[] emitted = asciiBytes(AccountRecordMapper.toRecord(referenceAccount()));
            final byte[] expected = asciiBytes(ROW_IMAGE);

            final byte[] emittedPrefix =
                    Arrays.copyOfRange(emitted, 0, EXPECTED_MAPPED_PREFIX_LENGTH);
            final byte[] expectedPrefix =
                    Arrays.copyOfRange(expected, 0, EXPECTED_MAPPED_PREFIX_LENGTH);

            assertThat(emittedPrefix)
                    .hasSize(EXPECTED_MAPPED_PREFIX_LENGTH)
                    .isEqualTo(expectedPrefix);
        }

        @Test
        @DisplayName("the whole 300-byte image is reproduced byte for byte, filler included, because "
                + "this reference data pads with the same space the mapper emits")
        void theWholeImageIsReproducedByteForByte() {
            final String emitted = AccountRecordMapper.toRecord(referenceAccount());

            assertThat(encodedWidth(emitted)).isEqualTo(EXPECTED_RECORD_LENGTH);
            assertThat(asciiBytes(emitted))
                    .hasSize(EXPECTED_RECORD_LENGTH)
                    .isEqualTo(asciiBytes(ROW_IMAGE));
        }

        @Test
        @DisplayName("the emitted filler is 178 ASCII spaces and never ASCII zero, so an unmapped byte "
                + "can never masquerade as a digit")
        void theEmittedFillerIsAsciiSpacesAndNeverAsciiZero() {
            final byte[] emitted = asciiBytes(AccountRecordMapper.toRecord(referenceAccount()));

            final byte[] filler =
                    Arrays.copyOfRange(emitted, EXPECTED_FILLER_OFFSET, EXPECTED_RECORD_LENGTH);

            assertThat(filler)
                    .hasSize(EXPECTED_FILLER_LENGTH)
                    .isEqualTo(asciiBytes(EXPECTED_FILLER))
                    .isNotEqualTo(asciiBytes("0".repeat(EXPECTED_FILLER_LENGTH)))
                    .containsOnly(ASCII_SPACE);
        }

        @Test
        @DisplayName("the byte emitter produces the same 300 bytes as the string emitter, and returns a "
                + "fresh array carrying no line terminator")
        void theByteEmitterProducesTheSameThreeHundredBytes() {
            final Account subject = referenceAccount();

            final byte[] first = AccountRecordMapper.toRecordBytes(subject);
            final byte[] second = AccountRecordMapper.toRecordBytes(subject);

            assertThat(first)
                    .hasSize(EXPECTED_RECORD_LENGTH)
                    .isEqualTo(asciiBytes(ROW_IMAGE))
                    .isEqualTo(asciiBytes(AccountRecordMapper.toRecord(subject)));
            assertThat(second).isEqualTo(first).isNotSameAs(first);
        }

        @Test
        @DisplayName("an entity built through the public twelve-argument constructor encodes to the "
                + "reference image, which proves the constructor's parameter order is copybook order")
        void anEntityFromThePublicConstructorEncodesToTheReferenceImage() {
            // Any transposition in the twelve-argument order would place a value at another field's
            // offset, so a byte-for-byte match here is the order proof.
            final Account constructed = referenceAccount();

            assertThat(asciiBytes(AccountRecordMapper.toRecord(constructed)))
                    .isEqualTo(asciiBytes(ROW_IMAGE));
            assertMatchesReferenceRow(constructed);
        }

        @Test
        @DisplayName("the sign is overpunched into each amount's final byte, positive and negative "
                + "alike, and the synthetic image round-trips to the same five amounts")
        void theSignIsOverpunchedIntoEachAmountsFinalByte() {
            final String emitted = AccountRecordMapper.toRecord(signedAccount());
            final byte[] encoded = asciiBytes(emitted);

            assertThat(encoded).hasSize(EXPECTED_RECORD_LENGTH).isEqualTo(asciiBytes(SIGNED_IMAGE));
            assertThat(emitted.substring(EXPECTED_CURR_BAL_OFFSET,
                    EXPECTED_CURR_BAL_OFFSET + EXPECTED_AMOUNT_LENGTH))
                    .isEqualTo(SIGNED_CURR_BAL_IMAGE);
            assertThat(emitted.substring(EXPECTED_CREDIT_LIMIT_OFFSET,
                    EXPECTED_CREDIT_LIMIT_OFFSET + EXPECTED_AMOUNT_LENGTH))
                    .isEqualTo(SIGNED_CREDIT_LIMIT_IMAGE);
            assertThat(emitted.substring(EXPECTED_CASH_CREDIT_LIMIT_OFFSET,
                    EXPECTED_CASH_CREDIT_LIMIT_OFFSET + EXPECTED_AMOUNT_LENGTH))
                    .isEqualTo(SIGNED_CASH_CREDIT_LIMIT_IMAGE);
            assertThat(emitted.substring(EXPECTED_CURR_CYC_CREDIT_OFFSET,
                    EXPECTED_CURR_CYC_CREDIT_OFFSET + EXPECTED_AMOUNT_LENGTH))
                    .isEqualTo(SIGNED_CURR_CYC_CREDIT_IMAGE);
            assertThat(emitted.substring(EXPECTED_CURR_CYC_DEBIT_OFFSET,
                    EXPECTED_CURR_CYC_DEBIT_OFFSET + EXPECTED_AMOUNT_LENGTH))
                    .isEqualTo(SIGNED_CURR_CYC_DEBIT_IMAGE);

            final Account decoded = AccountRecordMapper.fromRecord(SIGNED_IMAGE);

            assertThat(decoded.getAcctCurrBal()).isEqualTo(SIGNED_CURR_BAL);
            assertThat(decoded.getAcctCreditLimit()).isEqualTo(SIGNED_CREDIT_LIMIT);
            assertThat(decoded.getAcctCashCreditLimit()).isEqualTo(SIGNED_CASH_CREDIT_LIMIT);
            assertThat(decoded.getAcctCurrCycCredit()).isEqualTo(SIGNED_CURR_CYC_CREDIT);
            assertThat(decoded.getAcctCurrCycDebit()).isEqualTo(SIGNED_CURR_CYC_DEBIT);
            assertThat(decoded.getAcctCurrBal().scale()).isEqualTo(EXPECTED_MONETARY_SCALE);
            assertThat(decoded.getAcctGroupId()).isEqualTo(SIGNED_GROUP_ID);
            assertThat(decoded.getAcctActiveStatus()).isEqualTo(SIGNED_ACTIVE_STATUS);
        }

        @Test
        @DisplayName("a narrow identifier is placed right-justified and zero-filled, while a narrow "
                + "character value is placed left-justified and space-padded")
        void aNarrowIdentifierIsPlacedRightJustifiedAndZeroFilled() {
            final Account narrow = new Account("42",
                    ROW_ACTIVE_STATUS,
                    ROW_CURR_BAL,
                    ROW_CREDIT_LIMIT,
                    ROW_CASH_CREDIT_LIMIT,
                    ROW_OPEN_DATE,
                    ROW_EXPIRATION_DATE,
                    ROW_REISSUE_DATE,
                    ROW_CURR_CYC_CREDIT,
                    ROW_CURR_CYC_DEBIT,
                    ROW_ADDR_ZIP,
                    "A");

            final String emitted = AccountRecordMapper.toRecord(narrow);

            assertThat(encodedWidth(emitted)).isEqualTo(EXPECTED_RECORD_LENGTH);
            assertThat(emitted.substring(EXPECTED_ACCT_ID_OFFSET,
                    EXPECTED_ACCT_ID_OFFSET + EXPECTED_ACCT_ID_LENGTH)).isEqualTo("00000000042");
            assertThat(emitted.substring(EXPECTED_GROUP_ID_OFFSET,
                    EXPECTED_GROUP_ID_OFFSET + EXPECTED_TEN_BYTE_LENGTH)).isEqualTo("A         ");
        }
    }

    @Nested
    @DisplayName("refusing a malformed image")
    class RefusingAMalformedImage {

        @Test
        @DisplayName("an image one byte short is refused, and the diagnostic names the artefact, the "
                + "expected width and the actual encoded byte length")
        void anImageOneByteShortIsRefusedWithAFullDiagnostic() {
            final String tooShort = ROW_IMAGE.substring(0, EXPECTED_RECORD_LENGTH - 1);

            assertThat(encodedWidth(tooShort)).isEqualTo(EXPECTED_RECORD_LENGTH - 1);
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> AccountRecordMapper.fromRecord(tooShort))
                    .withMessageContaining(EXPECTED_ARTEFACT)
                    .withMessageContaining(String.valueOf(EXPECTED_RECORD_LENGTH))
                    .withMessageContaining(String.valueOf(EXPECTED_RECORD_LENGTH - 1));
        }

        @Test
        @DisplayName("an image one byte long is refused, and because that is the shape of an unstripped "
                + "line terminator the diagnostic says so")
        void anImageOneByteLongIsRefusedAndTheTerminatorIsNamed() {
            final String withTerminator = ROW_IMAGE + "\n";

            assertThat(encodedWidth(withTerminator)).isEqualTo(EXPECTED_RECORD_LENGTH + 1);
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> AccountRecordMapper.fromRecord(withTerminator))
                    .withMessageContaining(EXPECTED_ARTEFACT)
                    .withMessageContaining(String.valueOf(EXPECTED_RECORD_LENGTH))
                    .withMessageContaining(String.valueOf(EXPECTED_RECORD_LENGTH + 1))
                    .withMessageContaining("terminator");
        }

        @Test
        @DisplayName("a byte array of the wrong length is refused rather than padded or truncated")
        void aByteArrayOfTheWrongLengthIsRefused() {
            final byte[] tooLong = asciiBytes(ROW_IMAGE + " ");
            final byte[] tooShort = Arrays.copyOfRange(asciiBytes(ROW_IMAGE), 0,
                    EXPECTED_RECORD_LENGTH - 1);

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> AccountRecordMapper.fromRecord(tooLong))
                    .withMessageContaining(String.valueOf(EXPECTED_RECORD_LENGTH + 1));
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> AccountRecordMapper.fromRecord(tooShort))
                    .withMessageContaining(String.valueOf(EXPECTED_RECORD_LENGTH - 1));
        }

        @Test
        @DisplayName("a byte range that runs past the end of the buffer is refused, and a negative "
                + "start index is refused too")
        void anOutOfRangeByteWindowIsRefused() {
            final byte[] exactlyOneRecord = asciiBytes(ROW_IMAGE);

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> AccountRecordMapper.fromRecord(exactlyOneRecord, 1))
                    .withMessageContaining(EXPECTED_ARTEFACT);
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> AccountRecordMapper.fromRecord(exactlyOneRecord, -1))
                    .withMessageContaining(EXPECTED_ARTEFACT);
        }

        @Test
        @DisplayName("every entry point refuses null rather than returning a partial entity or a "
                + "partial image")
        void everyEntryPointRefusesNull() {
            assertThatNullPointerException()
                    .isThrownBy(() -> AccountRecordMapper.fromRecord((String) null));
            assertThatNullPointerException()
                    .isThrownBy(() -> AccountRecordMapper.fromRecord((byte[]) null));
            assertThatNullPointerException()
                    .isThrownBy(() -> AccountRecordMapper.fromRecord(null, 0));
            assertThatNullPointerException()
                    .isThrownBy(() -> AccountRecordMapper.toRecord(null));
            assertThatNullPointerException()
                    .isThrownBy(() -> AccountRecordMapper.toRecordBytes(null));
        }

        @Test
        @DisplayName("a null character property is refused, and the diagnostic names both the legacy "
                + "field and the entity property")
        void aNullCharacterPropertyIsRefused() {
            final Account missingGroupId = new Account(ROW_ACCT_ID,
                    ROW_ACTIVE_STATUS,
                    ROW_CURR_BAL,
                    ROW_CREDIT_LIMIT,
                    ROW_CASH_CREDIT_LIMIT,
                    ROW_OPEN_DATE,
                    ROW_EXPIRATION_DATE,
                    ROW_REISSUE_DATE,
                    ROW_CURR_CYC_CREDIT,
                    ROW_CURR_CYC_DEBIT,
                    ROW_ADDR_ZIP,
                    null);

            // A fixed-width record has no representation for an absent value: an unset character field
            // is presented as spaces, which is why the blank group identifier above is a value and not
            // an absence.
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> AccountRecordMapper.toRecord(missingGroupId))
                    .withMessageContaining(EXPECTED_ARTEFACT)
                    .withMessageContaining("ACCT-GROUP-ID")
                    .withMessageContaining("acctGroupId");
        }

        @Test
        @DisplayName("a null amount is refused, and the diagnostic names both the legacy field and the "
                + "entity property")
        void aNullAmountIsRefused() {
            final Account missingBalance = new Account(ROW_ACCT_ID,
                    ROW_ACTIVE_STATUS,
                    null,
                    ROW_CREDIT_LIMIT,
                    ROW_CASH_CREDIT_LIMIT,
                    ROW_OPEN_DATE,
                    ROW_EXPIRATION_DATE,
                    ROW_REISSUE_DATE,
                    ROW_CURR_CYC_CREDIT,
                    ROW_CURR_CYC_DEBIT,
                    ROW_ADDR_ZIP,
                    ROW_GROUP_ID);

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> AccountRecordMapper.toRecordBytes(missingBalance))
                    .withMessageContaining(EXPECTED_ARTEFACT)
                    .withMessageContaining("ACCT-CURR-BAL")
                    .withMessageContaining("acctCurrBal");
        }
    }
}
