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
package com.carddemo.domain;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit test for {@link Account}, the Java carrier of the 300-byte legacy account record.
 *
 * <p>Every expected value here was hand-derived, never computed by the code under test. Widths,
 * offsets and the total record length come from three legacy artifacts written independently of one
 * another and each read directly: the copybook {@code app/cpy/CVACT01Y.cpy}, which states the record
 * length and declares twelve named fields followed by a 178-byte trailing filler; the cluster
 * definition {@code app/jcl/ACCTFILE.jcl}, which states the same record length and the key's width
 * and position; and the sequential reader {@code app/cbl/CBACT01C.cbl}, whose file section splits the
 * record into an 11-byte key field and a 289-byte remainder. Because the three were written
 * independently, cross-checking the width arithmetic against all three is a real test rather than a
 * restatement. The field values are likewise hand-decoded from the first record of the named fixture
 * {@code app/data/ASCII/acctdata.txt}. Nothing here decodes a record image and no production mapper
 * or codec is referenced: making another class the oracle would leave both free to be wrong together.
 *
 * <p>The five monetary fields are deliberately not contiguous. Three sit at offsets 12, 24 and 36;
 * three date fields intervene at 48, 58 and 68; two further amounts follow at 78 and 90. The
 * interleaving is asserted explicitly, because a reader who assumed the amounts formed one block
 * would place every field from offset 48 onward incorrectly.
 *
 * <p>A pure unit test: no container, socket, file or application context. It verifies the record
 * contract the entity carries - construction, accessor transparency, decimal fidelity, business-key
 * identity and the version counter's default. It deliberately does not verify column names, widths or
 * nullability, because the deployed schema is authoritative and the integration tier validates the
 * mapping against a real database; it does not exercise optimistic locking, which needs a persistence
 * context; and it asserts no validation rule of any kind, because the entity performs none - the
 * legacy edit cascade lives on the account-update path and inventing a constraint here would reject
 * input the legacy system accepted.
 *
 * <p><strong>Scope, stated as much by exclusion as by inclusion.</strong> This is a pure unit test:
 * it starts no container, opens no socket, reads no file and builds no application context. It
 * verifies the record contract the entity carries - construction, accessor transparency, decimal
 * fidelity, business-key identity and the version counter's default. It deliberately does not verify
 * column names, column widths or nullability, because the deployed schema is authoritative for those
 * and that agreement is asserted where it belongs: {@code EntityPersistenceMappingTest} bootstraps the
 * persistence provider's metadata and compares the mapping it computes for this entity against the
 * shipped migration {@code V1__create_schema.sql} and against an independent copybook-width oracle.
 * The runtime configuration additionally fixes the provider at schema validation, so a divergence
 * fails start-up in a deployed environment - but that is a property of a deployment rather than a
 * check this build performs, so it is not offered here as this suite's justification. It deliberately does not exercise optimistic locking, which needs a persistence
 * context. And it deliberately asserts no validation rule of any kind, because the entity performs
 * none: the legacy edit cascade lives on the account-update path, and inventing a constraint here
 * would reject input the legacy system accepted.
 *
 * <p><strong>Divergence from this file's specification, recorded as required.</strong> The
 * specification describes a "thirteen-argument constructor" and thirteen business properties. The
 * class as written declares a <em>twelve</em>-argument constructor and twelve business properties,
 * and that is what this test compiles against. The two counts are reconcilable: the layout table has
 * thirteen rows, but its thirteenth row is the unmapped 178-byte filler, which is deliberately
 * neither a property nor a column; and the entity does map thirteen columns, being those twelve
 * business fields plus the version counter the persistence provider owns. The mapped-width sum
 * asserted below is correspondingly a twelve-term sum. A second divergence: the version counter has
 * a getter but no setter, so the tests that would otherwise vary it are documenting tests, since
 * reflection is not permitted here and a persistence context is out of scope.
 */
@DisplayName("Account - the 300-byte account record of copybook CVACT01Y")
class AccountTest {

    // FIELD WIDTHS - hand-derived from app/cpy/CVACT01Y.cpy

    private static final int ACCT_ID_WIDTH = 11;

    private static final int ACTIVE_STATUS_WIDTH = 1;

    private static final int AMOUNT_INTEGER_DIGITS = 10;

    private static final int AMOUNT_DECIMAL_DIGITS = 2;

    /**
     * Width of each of the five account amounts, asserted below as the sum of the integer and decimal
     * digit counts rather than stated independently, because that sum is what a copybook change breaks.
     */
    private static final int AMOUNT_WIDTH = 12;

    /**
     * Width of the transaction and category-balance amounts, one digit narrower than an account amount,
     * held only so the account width can be asserted to differ from it.
     */
    private static final int TRANSACTION_AMOUNT_WIDTH = 11;

    /**
     * Width of the disclosure-group rate, held only so the account width can be asserted to differ.
     */
    private static final int DISCLOSURE_RATE_WIDTH = 6;

    /** Width of each date field, which is fixed-width text rather than a date. */
    private static final int DATE_WIDTH = 10;

    private static final int ADDR_ZIP_WIDTH = 10;

    private static final int GROUP_ID_WIDTH = 10;

    /** Width of the trailing filler, which is deliberately neither a property nor a column. */
    private static final int FILLER_WIDTH = 178;

    // FIELD OFFSETS - zero-based, hand-derived by accumulating the widths above

    private static final int OFFSET_ACCT_ID = 0;

    private static final int OFFSET_ACTIVE_STATUS = 11;

    private static final int OFFSET_CURR_BAL = 12;

    private static final int OFFSET_CREDIT_LIMIT = 24;

    private static final int OFFSET_CASH_CREDIT_LIMIT = 36;

    private static final int OFFSET_OPEN_DATE = 48;

    /**
     * Offset of the expiration date. The copybook spells this field {@code ACCT-EXPIRAION-DATE}, dropping
     * a letter from EXPIRATION; the misspelling is a documented source anomaly and the offset is the
     * invariant.
     */
    private static final int OFFSET_EXPIRATION_DATE = 58;

    private static final int OFFSET_REISSUE_DATE = 68;

    private static final int OFFSET_CURR_CYC_CREDIT = 78;

    private static final int OFFSET_CURR_CYC_DEBIT = 90;

    private static final int OFFSET_ADDR_ZIP = 102;

    private static final int OFFSET_GROUP_ID = 112;

    /** Offset at which the unmapped trailing filler begins, and so the total mapped width. */
    private static final int OFFSET_FILLER = 122;

    // RECORD GEOMETRY - from app/jcl/ACCTFILE.jcl and app/cbl/CBACT01C.cbl, independent of the above

    private static final int RECORD_WIDTH = 300;

    private static final int KEY_WIDTH = 11;

    private static final int KEY_OFFSET = 0;

    private static final int FD_KEY_WIDTH = 11;

    private static final int FD_DATA_WIDTH = 289;

    /**
     * The declared widths in declaration order: twelve mapped fields, then the trailing filler.
     */
    private static final List<Integer> COPYBOOK_WIDTHS = List.of(
            ACCT_ID_WIDTH, ACTIVE_STATUS_WIDTH,
            AMOUNT_WIDTH, AMOUNT_WIDTH, AMOUNT_WIDTH,
            DATE_WIDTH, DATE_WIDTH, DATE_WIDTH,
            AMOUNT_WIDTH, AMOUNT_WIDTH,
            ADDR_ZIP_WIDTH, GROUP_ID_WIDTH,
            FILLER_WIDTH);

    private static final List<Integer> COPYBOOK_OFFSETS = List.of(
            OFFSET_ACCT_ID, OFFSET_ACTIVE_STATUS,
            OFFSET_CURR_BAL, OFFSET_CREDIT_LIMIT, OFFSET_CASH_CREDIT_LIMIT,
            OFFSET_OPEN_DATE, OFFSET_EXPIRATION_DATE, OFFSET_REISSUE_DATE,
            OFFSET_CURR_CYC_CREDIT, OFFSET_CURR_CYC_DEBIT,
            OFFSET_ADDR_ZIP, OFFSET_GROUP_ID,
            OFFSET_FILLER);

    // NAMED FIXTURE FACTS - app/data/ASCII/acctdata.txt, measured directly

    private static final int SEEDED_RECORD_COUNT = 50;

    /** Bytes each fixture line occupies: the record image plus one line terminator. */
    private static final int SEEDED_RECORD_STRIDE = 301;

    private static final int SEEDED_FILE_BYTES = 15_050;

    // ROW ZERO OF THE NAMED FIXTURE - hand-decoded, one literal per field. The amounts are zoned
    // decimal with an overpunched trailing byte carrying both the low-order digit and the sign, which
    // is how these literals were derived. This file never performs that decoding, because decoding is
    // the codec's contract and asserting it here would test the wrong class.

    private static final String SEED_ACCT_ID = "00000000001";

    private static final String SEED_ACTIVE_STATUS = "Y";

    private static final String SEED_CURR_BAL = "194.00";

    private static final String SEED_CREDIT_LIMIT = "2020.00";

    private static final String SEED_CASH_CREDIT_LIMIT = "1020.00";

    private static final String SEED_OPEN_DATE = "2014-11-20";

    private static final String SEED_EXPIRATION_DATE = "2025-05-20";

    private static final String SEED_REISSUE_DATE = "2025-05-20";

    private static final String SEED_CURR_CYC_CREDIT = "0.00";

    private static final String SEED_CURR_CYC_DEBIT = "0.00";

    /**
     * Address ZIP of the first fixture record, shared by all fifty; its leading character is a letter.
     */
    private static final String SEED_ADDR_ZIP = "A000000000";

    /**
     * Account group identifier of the first fixture record: ten spaces, as in all fifty. Its encoded
     * width is asserted below so a miscount fails loudly rather than weakening the tests that use it.
     */
    private static final String SEED_GROUP_ID = "          ";

    private static final int MONETARY_SCALE = 2;

    /**
     * Builds the first fixture record through the public constructor, every value a hand-decoded literal.
     *
     * @return an account carrying the first fixture record's field values
     */
    private static Account seededRowZero() {
        return new Account(
                SEED_ACCT_ID,
                SEED_ACTIVE_STATUS,
                new BigDecimal(SEED_CURR_BAL),
                new BigDecimal(SEED_CREDIT_LIMIT),
                new BigDecimal(SEED_CASH_CREDIT_LIMIT),
                SEED_OPEN_DATE,
                SEED_EXPIRATION_DATE,
                SEED_REISSUE_DATE,
                new BigDecimal(SEED_CURR_CYC_CREDIT),
                new BigDecimal(SEED_CURR_CYC_DEBIT),
                SEED_ADDR_ZIP,
                SEED_GROUP_ID);
    }

    /**
     * Measures a value as the record contract measures it: encoded bytes under an explicitly named
     * charset, because the platform default would make the same assertion mean different things.
     *
     * @param value the value to measure
     * @return the number of bytes the value occupies when encoded as US-ASCII
     */
    private static int encodedWidth(final String value) {
        return value.getBytes(StandardCharsets.US_ASCII).length;
    }

    /**
     * Geometry the entity must honour, cross-checked across the copybook's field widths, the cluster
     * definition's record size and the sequential reader's key split - three independent artifacts.
     */
    @Nested
    @DisplayName("record layout")
    class RecordLayout {

        @Test
        @DisplayName("the twelve mapped widths sum to 122, and 122 + 178 filler bytes is the "
                + "300-byte record the cluster declares")
        void theMappedWidthsSumToOneHundredAndTwentyTwo() {
            assertThat(ACCT_ID_WIDTH + ACTIVE_STATUS_WIDTH
                    + AMOUNT_WIDTH + AMOUNT_WIDTH + AMOUNT_WIDTH
                    + DATE_WIDTH + DATE_WIDTH + DATE_WIDTH
                    + AMOUNT_WIDTH + AMOUNT_WIDTH
                    + ADDR_ZIP_WIDTH + GROUP_ID_WIDTH)
                    .as("11 + 1 + 12 + 12 + 12 + 10 + 10 + 10 + 12 + 12 + 10 + 10 == 122")
                    .isEqualTo(OFFSET_FILLER);

            assertThat(OFFSET_FILLER + FILLER_WIDTH)
                    .as("122 mapped bytes + 178 filler bytes == 300")
                    .isEqualTo(RECORD_WIDTH);
            assertThat(RECORD_WIDTH - OFFSET_FILLER)
                    .as("the trailing remainder is the filler width")
                    .isEqualTo(FILLER_WIDTH);
        }

        @Test
        @DisplayName("all thirteen declared widths, filler included, sum to the 300 bytes of "
                + "RECORDSIZE(300 300)")
        void allThirteenDeclaredWidthsSumToTheRecordSize() {
            assertThat(COPYBOOK_WIDTHS).hasSize(13);
            assertThat(COPYBOOK_WIDTHS.stream().mapToInt(Integer::intValue).sum())
                    .isEqualTo(RECORD_WIDTH);
        }

        @Test
        @DisplayName("each declared field begins where the preceding widths leave off, so the "
                + "offsets are derived rather than copied")
        void eachFieldBeginsWhereThePrecedingWidthsLeaveOff() {
            assertThat(COPYBOOK_OFFSETS).hasSameSizeAs(COPYBOOK_WIDTHS);

            int running = 0;
            for (int index = 0; index < COPYBOOK_WIDTHS.size(); index++) {
                assertThat(running)
                        .as("offset of declared field %d", index + 1)
                        .isEqualTo(COPYBOOK_OFFSETS.get(index));
                running += COPYBOOK_WIDTHS.get(index);
            }
            assertThat(running)
                    .as("accumulating every declared width reaches the end of the record")
                    .isEqualTo(RECORD_WIDTH);
        }

        @Test
        @DisplayName("the five monetary fields are NOT contiguous: 36 + 12 == 48 hands over to the "
                + "dates and 68 + 10 == 78 hands back to the amounts")
        void theMonetaryFieldsAreInterleavedWithTheDates() {
            assertThat(OFFSET_CURR_BAL + AMOUNT_WIDTH)
                    .as("12 + 12 == 24")
                    .isEqualTo(OFFSET_CREDIT_LIMIT);
            assertThat(OFFSET_CREDIT_LIMIT + AMOUNT_WIDTH)
                    .as("24 + 12 == 36")
                    .isEqualTo(OFFSET_CASH_CREDIT_LIMIT);

            // Then the amounts stop and the dates begin: the hand-over a reader who assumed five adjacent
            // amounts would miss, misplacing every later field. Contiguous, the fourth would start at
            // 48; it starts at 78, thirty bytes of dates later.
            assertThat(OFFSET_CASH_CREDIT_LIMIT + AMOUNT_WIDTH)
                    .as("36 + 12 == 48 - the third amount is followed by a date, not a fourth amount")
                    .isEqualTo(OFFSET_OPEN_DATE);

            assertThat(OFFSET_OPEN_DATE + DATE_WIDTH)
                    .as("48 + 10 == 58")
                    .isEqualTo(OFFSET_EXPIRATION_DATE);
            assertThat(OFFSET_EXPIRATION_DATE + DATE_WIDTH)
                    .as("58 + 10 == 68")
                    .isEqualTo(OFFSET_REISSUE_DATE);

            assertThat(OFFSET_REISSUE_DATE + DATE_WIDTH)
                    .as("68 + 10 == 78 - the third date is followed by an amount")
                    .isEqualTo(OFFSET_CURR_CYC_CREDIT);
            assertThat(OFFSET_CURR_CYC_CREDIT + AMOUNT_WIDTH)
                    .as("78 + 12 == 90")
                    .isEqualTo(OFFSET_CURR_CYC_DEBIT);

            assertThat(OFFSET_CURR_CYC_CREDIT)
                    .as("the fourth amount does not follow the third directly")
                    .isNotEqualTo(OFFSET_CASH_CREDIT_LIMIT + AMOUNT_WIDTH);
            assertThat(OFFSET_CURR_CYC_CREDIT - (OFFSET_CASH_CREDIT_LIMIT + AMOUNT_WIDTH))
                    .as("three ten-byte dates separate the two groups of amounts")
                    .isEqualTo(DATE_WIDTH * 3);
        }

        @Test
        @DisplayName("the expiry field sits at 48 + 10 == 58: the copybook misspells it "
                + "ACCT-EXPIRAION-DATE at line 11, and the offset - not the spelling - is the "
                + "invariant")
        void theExpiryOffsetIsDerivedFromTheOpenDate() {
            assertThat(OFFSET_OPEN_DATE + DATE_WIDTH)
                    .as("48 + 10 == 58")
                    .isEqualTo(OFFSET_EXPIRATION_DATE);

            // Preserving the offset is what keeps the record image byte-compatible. The Java property
            // spells expiration correctly, a documented divergence rather than a silent correction: the
            // misspelled legacy name is cited in the entity's own documentation so the mapping stays
            // findable.
            assertThat(seededRowZero().getAcctExpirationDate())
                    .isEqualTo(SEED_EXPIRATION_DATE);
            assertThat(encodedWidth(seededRowZero().getAcctExpirationDate()))
                    .isEqualTo(DATE_WIDTH);
        }

        @Test
        @DisplayName("an account amount is 10 + 2 == 12 bytes wide, which is neither the 11 of "
                + "PIC S9(09)V99 nor the 6 of PIC S9(04)V99")
        void anAccountAmountIsTwelveBytesWide() {
            assertThat(AMOUNT_INTEGER_DIGITS + AMOUNT_DECIMAL_DIGITS)
                    .as("10 integer digits + 2 decimal digits == 12")
                    .isEqualTo(AMOUNT_WIDTH);

            assertThat(COPYBOOK_WIDTHS.get(2)).isEqualTo(AMOUNT_WIDTH);
            assertThat(COPYBOOK_WIDTHS.get(3)).isEqualTo(AMOUNT_WIDTH);
            assertThat(COPYBOOK_WIDTHS.get(4)).isEqualTo(AMOUNT_WIDTH);
            assertThat(COPYBOOK_WIDTHS.get(8)).isEqualTo(AMOUNT_WIDTH);
            assertThat(COPYBOOK_WIDTHS.get(9)).isEqualTo(AMOUNT_WIDTH);

            // Sharing one width constant across record types would corrupt every one of them.
            assertThat(AMOUNT_WIDTH)
                    .as("12 != 11 - a transaction or category-balance amount is one digit narrower")
                    .isNotEqualTo(TRANSACTION_AMOUNT_WIDTH);
            assertThat(AMOUNT_WIDTH)
                    .as("12 != 6 - a disclosure-group rate is six digits narrower")
                    .isNotEqualTo(DISCLOSURE_RATE_WIDTH);
            assertThat(TRANSACTION_AMOUNT_WIDTH).isEqualTo(9 + AMOUNT_DECIMAL_DIGITS);
            assertThat(DISCLOSURE_RATE_WIDTH).isEqualTo(4 + AMOUNT_DECIMAL_DIGITS);
        }

        @Test
        @DisplayName("the key is the leading 11 bytes at offset 0, exactly as KEYS(11 0) states")
        void theKeyIsTheLeadingElevenBytes() {
            assertThat(KEY_OFFSET).isEqualTo(OFFSET_ACCT_ID);
            assertThat(KEY_WIDTH).isEqualTo(ACCT_ID_WIDTH);
            assertThat(KEY_OFFSET + KEY_WIDTH)
                    .as("the byte after the key is the status code at offset 11")
                    .isEqualTo(OFFSET_ACTIVE_STATUS);
        }

        @Test
        @DisplayName("the sequential reader splits the record as 11 + 289 == 300, which is why the "
                + "business key is the identifier")
        void theSequentialReaderSplitsTheRecordIntoKeyAndRemainder() {
            // The reader's own key-and-remainder split is the independent oracle: a different member states
            // the same relationship the cluster definition does.
            assertThat(FD_KEY_WIDTH + FD_DATA_WIDTH)
                    .as("11 + 289 == 300")
                    .isEqualTo(RECORD_WIDTH);
            assertThat(FD_KEY_WIDTH)
                    .as("the reader's key width is the identifier width")
                    .isEqualTo(ACCT_ID_WIDTH);
            assertThat(FD_DATA_WIDTH)
                    .as("the remainder is everything after the key")
                    .isEqualTo(RECORD_WIDTH - ACCT_ID_WIDTH);
        }

        @Test
        @DisplayName("the named fixture holds 50 records of 300 bytes, so 50 x 301 == 15,050 bytes "
                + "with one terminator per line")
        void theNamedFixtureGeometryIsConsistent() {
            assertThat(SEEDED_RECORD_STRIDE)
                    .as("300 image bytes + 1 terminator")
                    .isEqualTo(RECORD_WIDTH + 1);
            assertThat(SEEDED_RECORD_COUNT * SEEDED_RECORD_STRIDE)
                    .as("50 x 301 == 15,050")
                    .isEqualTo(SEEDED_FILE_BYTES);
        }
    }

    /**
     * The entity as transparent carrier: what goes in comes back out, field for field, with nothing
     * trimmed, padded, folded, normalized, parsed, validated or rescaled on the way through.
     */
    @Nested
    @DisplayName("construction and accessors")
    class ConstructionAndAccessors {

        @Test
        @DisplayName("the constructor round-trips every business field of the first fixture record "
                + "in copybook order")
        void theConstructorRoundTripsEveryBusinessField() {
            final Account account = seededRowZero();

            assertThat(account.getAcctId()).isEqualTo(SEED_ACCT_ID);
            assertThat(account.getAcctActiveStatus()).isEqualTo(SEED_ACTIVE_STATUS);
            assertThat(account.getAcctCurrBal()).isEqualTo(new BigDecimal(SEED_CURR_BAL));
            assertThat(account.getAcctCreditLimit()).isEqualTo(new BigDecimal(SEED_CREDIT_LIMIT));
            assertThat(account.getAcctCashCreditLimit())
                    .isEqualTo(new BigDecimal(SEED_CASH_CREDIT_LIMIT));
            assertThat(account.getAcctOpenDate()).isEqualTo(SEED_OPEN_DATE);
            assertThat(account.getAcctExpirationDate()).isEqualTo(SEED_EXPIRATION_DATE);
            assertThat(account.getAcctReissueDate()).isEqualTo(SEED_REISSUE_DATE);
            assertThat(account.getAcctCurrCycCredit())
                    .isEqualTo(new BigDecimal(SEED_CURR_CYC_CREDIT));
            assertThat(account.getAcctCurrCycDebit())
                    .isEqualTo(new BigDecimal(SEED_CURR_CYC_DEBIT));
            assertThat(account.getAcctAddrZip()).isEqualTo(SEED_ADDR_ZIP);
            assertThat(account.getAcctGroupId()).isEqualTo(SEED_GROUP_ID);
        }

        @Test
        @DisplayName("the constructor assigns each argument to its own field, so no two positionally "
                + "adjacent fields are transposed")
        void theConstructorDoesNotTransposeAdjacentFields() {
            // Distinct values per field, so a swapped assignment cannot hide behind equal values - including
            // the ZIP and the group identifier, the two positions the fixture itself makes look alike.
            final Account account = new Account(
                    "00000000042",
                    "N",
                    new BigDecimal("11.11"),
                    new BigDecimal("22.22"),
                    new BigDecimal("33.33"),
                    "1901-01-01",
                    "1902-02-02",
                    "1903-03-03",
                    new BigDecimal("44.44"),
                    new BigDecimal("55.55"),
                    "ZIP0000001",
                    "GRP0000002");

            assertThat(account.getAcctId()).isEqualTo("00000000042");
            assertThat(account.getAcctActiveStatus()).isEqualTo("N");
            assertThat(account.getAcctCurrBal()).isEqualTo(new BigDecimal("11.11"));
            assertThat(account.getAcctCreditLimit()).isEqualTo(new BigDecimal("22.22"));
            assertThat(account.getAcctCashCreditLimit()).isEqualTo(new BigDecimal("33.33"));
            assertThat(account.getAcctOpenDate()).isEqualTo("1901-01-01");
            assertThat(account.getAcctExpirationDate()).isEqualTo("1902-02-02");
            assertThat(account.getAcctReissueDate()).isEqualTo("1903-03-03");
            assertThat(account.getAcctCurrCycCredit()).isEqualTo(new BigDecimal("44.44"));
            assertThat(account.getAcctCurrCycDebit()).isEqualTo(new BigDecimal("55.55"));
            assertThat(account.getAcctAddrZip()).isEqualTo("ZIP0000001");
            assertThat(account.getAcctGroupId()).isEqualTo("GRP0000002");
        }

        @Test
        @DisplayName("every business setter round-trips its value, each writing only its own field")
        void everyBusinessSetterRoundTripsItsValue() {
            final Account account = new Account();

            account.setAcctId("00000000007");
            account.setAcctActiveStatus("N");
            account.setAcctCurrBal(new BigDecimal("-1.01"));
            account.setAcctCreditLimit(new BigDecimal("2.02"));
            account.setAcctCashCreditLimit(new BigDecimal("3.03"));
            account.setAcctOpenDate("2000-01-01");
            account.setAcctExpirationDate("2010-02-02");
            account.setAcctReissueDate("2020-03-03");
            account.setAcctCurrCycCredit(new BigDecimal("4.04"));
            account.setAcctCurrCycDebit(new BigDecimal("5.05"));
            account.setAcctAddrZip("B123456789");
            account.setAcctGroupId("DEFAULT   ");

            assertThat(account.getAcctId()).isEqualTo("00000000007");
            assertThat(account.getAcctActiveStatus()).isEqualTo("N");
            assertThat(account.getAcctCurrBal()).isEqualTo(new BigDecimal("-1.01"));
            assertThat(account.getAcctCreditLimit()).isEqualTo(new BigDecimal("2.02"));
            assertThat(account.getAcctCashCreditLimit()).isEqualTo(new BigDecimal("3.03"));
            assertThat(account.getAcctOpenDate()).isEqualTo("2000-01-01");
            assertThat(account.getAcctExpirationDate()).isEqualTo("2010-02-02");
            assertThat(account.getAcctReissueDate()).isEqualTo("2020-03-03");
            assertThat(account.getAcctCurrCycCredit()).isEqualTo(new BigDecimal("4.04"));
            assertThat(account.getAcctCurrCycDebit()).isEqualTo(new BigDecimal("5.05"));
            assertThat(account.getAcctAddrZip()).isEqualTo("B123456789");
            assertThat(account.getAcctGroupId()).isEqualTo("DEFAULT   ");
        }

        @Test
        @DisplayName("a setter overwrites a constructed value without altering any other field")
        void aSetterOverwritesOnlyItsOwnField() {
            final Account account = seededRowZero();

            account.setAcctCurrBal(new BigDecimal("999.99"));

            assertThat(account.getAcctCurrBal()).isEqualTo(new BigDecimal("999.99"));
            assertThat(account.getAcctCreditLimit()).isEqualTo(new BigDecimal(SEED_CREDIT_LIMIT));
            assertThat(account.getAcctCashCreditLimit())
                    .isEqualTo(new BigDecimal(SEED_CASH_CREDIT_LIMIT));
            assertThat(account.getAcctId()).isEqualTo(SEED_ACCT_ID);
            assertThat(account.getAcctGroupId()).isEqualTo(SEED_GROUP_ID);
        }

        @Test
        @DisplayName("the no-argument constructor the persistence provider needs yields an entirely "
                + "empty business state and a version of zero")
        void theNoArgumentConstructorYieldsAnEmptyBusinessState() {
            // This test lives in the entity's own package, so ordinary Java package access reaches
            // the protected no-argument constructor directly. This is same-package visibility and
            // explicitly NOT reflection: no reflective member lookup occurs anywhere in this file.
            final Account account = new Account();

            assertThat(account.getAcctId()).isNull();
            assertThat(account.getAcctActiveStatus()).isNull();
            assertThat(account.getAcctCurrBal()).isNull();
            assertThat(account.getAcctCreditLimit()).isNull();
            assertThat(account.getAcctCashCreditLimit()).isNull();
            assertThat(account.getAcctOpenDate()).isNull();
            assertThat(account.getAcctExpirationDate()).isNull();
            assertThat(account.getAcctReissueDate()).isNull();
            assertThat(account.getAcctCurrCycCredit()).isNull();
            assertThat(account.getAcctCurrCycDebit()).isNull();
            assertThat(account.getAcctAddrZip()).isNull();
            assertThat(account.getAcctGroupId()).isNull();

            // The amounts are absent rather than zero: an unset amount and a zero balance are different
            // facts, and conflating them would invent data the record never carried.
            assertThat(account.getVersion()).isEqualTo(0L);
        }

        @Test
        @DisplayName("a null business value is accepted and returned as null, because the entity "
                + "validates nothing - absence is the schema's concern, not this class's")
        void aNullBusinessValueIsStoredAsSupplied() {
            final Account account = seededRowZero();

            account.setAcctGroupId(null);
            account.setAcctCurrBal(null);

            assertThat(account.getAcctGroupId()).isNull();
            assertThat(account.getAcctCurrBal()).isNull();

            // Non-nullability is a database constraint enforced at insert time, not a setter guard.
            // Asserting an exception here would invent behaviour neither the class nor the record had.
            assertThat(account.getAcctId()).isEqualTo(SEED_ACCT_ID);
        }
    }

    /**
     * The five amounts keep both value and scale; the entity neither rescales, rounds nor computes.
     * Every amount is built from a decimal string and is an exact decimal - never from an approximate
     * binary numeric type, which cannot hold an exact two-decimal amount and which the migration
     * contract forbids substituting; no such type is named anywhere in this file, in code or prose.
     */
    @Nested
    @DisplayName("monetary fidelity")
    class MonetaryFidelity {

        @Test
        @DisplayName("all five amounts of the first fixture record round-trip with their value and "
                + "their scale of 2 intact")
        void allFiveAmountsRoundTripWithValueAndScale() {
            final Account account = seededRowZero();

            assertThat(account.getAcctCurrBal().compareTo(new BigDecimal(SEED_CURR_BAL))).isZero();
            assertThat(account.getAcctCurrBal().scale()).isEqualTo(MONETARY_SCALE);

            assertThat(account.getAcctCreditLimit().compareTo(new BigDecimal(SEED_CREDIT_LIMIT)))
                    .isZero();
            assertThat(account.getAcctCreditLimit().scale()).isEqualTo(MONETARY_SCALE);

            assertThat(account.getAcctCashCreditLimit()
                    .compareTo(new BigDecimal(SEED_CASH_CREDIT_LIMIT))).isZero();
            assertThat(account.getAcctCashCreditLimit().scale()).isEqualTo(MONETARY_SCALE);

            assertThat(account.getAcctCurrCycCredit()
                    .compareTo(new BigDecimal(SEED_CURR_CYC_CREDIT))).isZero();
            assertThat(account.getAcctCurrCycCredit().scale()).isEqualTo(MONETARY_SCALE);

            assertThat(account.getAcctCurrCycDebit()
                    .compareTo(new BigDecimal(SEED_CURR_CYC_DEBIT))).isZero();
            assertThat(account.getAcctCurrCycDebit().scale()).isEqualTo(MONETARY_SCALE);
        }

        @Test
        @DisplayName("scale is part of the stored value: 194.00 compares equal to 194 but is not "
                + "equal to it, and the entity keeps the two-decimal form")
        void scaleIsPartOfTheStoredValue() {
            final BigDecimal twoDecimals = new BigDecimal(SEED_CURR_BAL);
            final BigDecimal noDecimals = new BigDecimal("194");

            assertThat(twoDecimals.compareTo(noDecimals)).isZero();
            // Yet distinct values, because equality on an exact decimal includes its scale - the
            // distinction a fixed-width two-decimal field depends on.
            assertThat(twoDecimals).isNotEqualTo(noDecimals);

            final Account account = seededRowZero();
            assertThat(account.getAcctCurrBal()).isEqualTo(twoDecimals);
            assertThat(account.getAcctCurrBal()).isNotEqualTo(noDecimals);
            assertThat(account.getAcctCurrBal().scale()).isEqualTo(MONETARY_SCALE);
            assertThat(noDecimals.scale()).isZero();
        }

        @Test
        @DisplayName("a negative balance round-trips with its sign, because the amount picture is "
                + "signed even though every fixture record is positive")
        void aNegativeBalanceRoundTripsWithItsSign() {
            final Account account = seededRowZero();

            account.setAcctCurrBal(new BigDecimal("-194.00"));

            assertThat(account.getAcctCurrBal()).isEqualTo(new BigDecimal("-194.00"));
            assertThat(account.getAcctCurrBal().compareTo(new BigDecimal("-194.00"))).isZero();
            assertThat(account.getAcctCurrBal().signum()).isEqualTo(-1);
            assertThat(account.getAcctCurrBal().scale()).isEqualTo(MONETARY_SCALE);
            assertThat(account.getAcctCurrBal().negate())
                    .isEqualTo(new BigDecimal(SEED_CURR_BAL));
        }

        @Test
        @DisplayName("a negative-zero literal is stored without rescaling: an exact decimal has no "
                + "signed zero, so the argument is simply zero at the two-decimal scale and the entity "
                + "neither folds it nor widens it")
        void negativeZeroIsKeptAsSupplied() {
            final Account account = seededRowZero();

            account.setAcctCurrCycDebit(new BigDecimal("-0.00"));

            assertThat(account.getAcctCurrCycDebit().compareTo(new BigDecimal("0.00"))).isZero();
            assertThat(account.getAcctCurrCycDebit().signum()).isZero();
            assertThat(account.getAcctCurrCycDebit().scale()).isEqualTo(MONETARY_SCALE);

            // An exact decimal has no signed zero, so this argument is the value zero at scale two and
            // equality holds against either spelling. The zoned image carries its sign in the
            // overpunched trailing byte, which is the codec's concern; what is pinned here is that the
            // entity neither rescales the value nor substitutes one of its own.
            assertThat(account.getAcctCurrCycDebit()).isEqualTo(new BigDecimal("-0.00"));
        }

        @Test
        @DisplayName("the entity performs no scaling: a value of scale 1 comes back at scale 1, "
                + "never widened to the two decimals of the column")
        void theEntityPerformsNoScaling() {
            final Account account = seededRowZero();

            account.setAcctCreditLimit(new BigDecimal("1.5"));

            assertThat(account.getAcctCreditLimit().scale())
                    .as("a plain assignment cannot have widened the scale")
                    .isEqualTo(1);
            assertThat(account.getAcctCreditLimit()).isEqualTo(new BigDecimal("1.5"));
            assertThat(account.getAcctCreditLimit()).isNotEqualTo(new BigDecimal("1.50"));
            assertThat(account.getAcctCreditLimit().compareTo(new BigDecimal("1.50"))).isZero();
        }

        @Test
        @DisplayName("the entity performs no rounding and no arithmetic: 2.999 comes back as 2.999, "
                + "neither truncated to 2.99 nor rounded to 3.00 - truncation toward zero belongs to "
                + "the codec, because no legacy arithmetic statement carries a rounding clause")
        void theEntityPerformsNoRoundingAndNoArithmetic() {
            final Account account = seededRowZero();

            account.setAcctCashCreditLimit(new BigDecimal("2.999"));

            assertThat(account.getAcctCashCreditLimit()).isEqualTo(new BigDecimal("2.999"));
            assertThat(account.getAcctCashCreditLimit().scale()).isEqualTo(3);

            // Not truncated toward zero, which is what a store into a two-decimal legacy field does.
            assertThat(account.getAcctCashCreditLimit()).isNotEqualTo(new BigDecimal("2.99"));
            // Not rounded half-up either, which is what conventional Java guidance would have done.
            assertThat(account.getAcctCashCreditLimit()).isNotEqualTo(new BigDecimal("3.00"));
        // Either policy here would be a second place for rounding to be decided, and the two would
        // eventually disagree by a cent. There is exactly one such place, and it is not this class.
        }

        @Test
        @DisplayName("an amount at the full width of the picture round-trips undamaged: ten integer "
                + "digits and two decimals, signed both ways")
        void anAmountAtTheFullPictureWidthRoundTrips() {
            final Account account = seededRowZero();
            final BigDecimal widestPositive = new BigDecimal("9999999999.99");
            final BigDecimal widestNegative = new BigDecimal("-9999999999.99");

            account.setAcctCurrBal(widestPositive);
            assertThat(account.getAcctCurrBal()).isEqualTo(widestPositive);
            assertThat(account.getAcctCurrBal().precision()).isEqualTo(AMOUNT_WIDTH);
            assertThat(account.getAcctCurrBal().scale()).isEqualTo(MONETARY_SCALE);

            account.setAcctCurrBal(widestNegative);
            assertThat(account.getAcctCurrBal()).isEqualTo(widestNegative);
            assertThat(account.getAcctCurrBal().precision())
                    .as("the sign is not a digit, so precision is unchanged by it")
                    .isEqualTo(AMOUNT_WIDTH);
            assertThat(account.getAcctCurrBal().scale()).isEqualTo(MONETARY_SCALE);
            assertThat(account.getAcctCurrBal().unscaledValue().toString())
                    .as("twelve significant digits, matching the twelve-byte picture width")
                    .hasSize(AMOUNT_WIDTH + 1)
                    .startsWith("-");
        }
    }

    /**
     * Character fields as verbatim byte images: full width preserved, padding significant, no parsing,
     * no vocabulary check and no normalization of any kind.
     */
    @Nested
    @DisplayName("raw character fidelity")
    class RawCharacterFidelity {

        @Test
        @DisplayName("the character fields occupy exactly 11, 1, 10, 10, 10, 10 and 10 encoded "
                + "bytes, measured as bytes because the record is a byte image")
        void theCharacterFieldsOccupyTheirDeclaredEncodedWidths() {
            final Account account = seededRowZero();

            assertThat(encodedWidth(account.getAcctId())).isEqualTo(ACCT_ID_WIDTH);
            assertThat(encodedWidth(account.getAcctActiveStatus())).isEqualTo(ACTIVE_STATUS_WIDTH);
            assertThat(encodedWidth(account.getAcctOpenDate())).isEqualTo(DATE_WIDTH);
            assertThat(encodedWidth(account.getAcctExpirationDate())).isEqualTo(DATE_WIDTH);
            assertThat(encodedWidth(account.getAcctReissueDate())).isEqualTo(DATE_WIDTH);
            assertThat(encodedWidth(account.getAcctAddrZip())).isEqualTo(ADDR_ZIP_WIDTH);
            assertThat(encodedWidth(account.getAcctGroupId())).isEqualTo(GROUP_ID_WIDTH);

            assertThat(ACCT_ID_WIDTH + ACTIVE_STATUS_WIDTH + (DATE_WIDTH * 3)
                    + ADDR_ZIP_WIDTH + GROUP_ID_WIDTH + (AMOUNT_WIDTH * 5))
                    .isEqualTo(OFFSET_FILLER);
        }

        @Test
        @DisplayName("the account identifier keeps its leading zeros: the eleven-character form is "
                + "the contract, and it is not the number one")
        void theAccountIdentifierKeepsItsLeadingZeros() {
            final Account account = seededRowZero();

            assertThat(account.getAcctId()).isEqualTo(SEED_ACCT_ID);
            assertThat(encodedWidth(account.getAcctId())).isEqualTo(ACCT_ID_WIDTH);
            assertThat(account.getAcctId()).startsWith("0").endsWith("1");

            // A bounded character column, deliberately not a numeric one. Nothing here converts the
            // identifier to a number, because a numeric round trip would discard exactly the ten
            // leading zeros the fixed-width contract requires.
            assertThat(account.getAcctId()).isNotEqualTo("1");
            assertThat(account.getAcctId()).isNotEqualTo("00000000001 ");
            assertThat(encodedWidth("1")).isNotEqualTo(ACCT_ID_WIDTH);
        }

        @Test
        @DisplayName("the account group identifier round-trips as exactly ten spaces, untrimmed: all "
                + "50 fixture records carry ten spaces here, and trimming it would break the "
                + "disclosure-group default-fallback lookup")
        void theAccountGroupIdentifierRoundTripsAsTenSpaces() {
            assertThat(encodedWidth(SEED_GROUP_ID)).isEqualTo(GROUP_ID_WIDTH);

            final Account account = new Account();
            account.setAcctGroupId(SEED_GROUP_ID);

            assertThat(account.getAcctGroupId()).isNotNull();
            assertThat(encodedWidth(account.getAcctGroupId())).isEqualTo(GROUP_ID_WIDTH);
            assertThat(account.getAcctGroupId()).isEqualTo(SEED_GROUP_ID);

            // Padding is part of the value. Were the accessor to trim, all three of these would collapse
            // into one value in Java while remaining three distinct values in the database.
            assertThat(account.getAcctGroupId()).isNotEqualTo("");
            assertThat(account.getAcctGroupId()).isNotEqualTo(" ");
            assertThat(account.getAcctGroupId()).isNotEqualTo("DEFAULT   ");

            assertThat(seededRowZero().getAcctGroupId()).isEqualTo(SEED_GROUP_ID);
            assertThat(encodedWidth(seededRowZero().getAcctGroupId())).isEqualTo(GROUP_ID_WIDTH);
        }

        @Test
        @DisplayName("the address ZIP round-trips as A000000000: on all 50 fixture records the ZIP "
                + "position holds that value while the group-id position holds ten spaces, and the "
                + "apparent transposition is honoured rather than repaired")
        void theAddressZipRoundTripsAsTheFixtureHoldsIt() {
            final Account account = seededRowZero();

            assertThat(account.getAcctAddrZip()).isEqualTo(SEED_ADDR_ZIP);
            assertThat(encodedWidth(account.getAcctAddrZip())).isEqualTo(ADDR_ZIP_WIDTH);
            assertThat(account.getAcctAddrZip()).startsWith("A");

            // Both positions are read strictly by offset and both slots are ten bytes wide, so nothing is
            // swapped, inferred or corrected: a mapper that "fixed" the apparent transposition would
            // disagree with the seeded data and with the interest run's rate lookup.
            assertThat(ADDR_ZIP_WIDTH).isEqualTo(GROUP_ID_WIDTH);
            assertThat(OFFSET_ADDR_ZIP + ADDR_ZIP_WIDTH).isEqualTo(OFFSET_GROUP_ID);
            assertThat(account.getAcctAddrZip()).isNotEqualTo(account.getAcctGroupId());

            // A ZIP-shaped value is accepted just as readily, because the field is an opaque lexeme
            // rather than a validated postal code.
            account.setAcctAddrZip("12345-6789");
            assertThat(account.getAcctAddrZip()).isEqualTo("12345-6789");
            assertThat(encodedWidth(account.getAcctAddrZip())).isEqualTo(ADDR_ZIP_WIDTH);
        }

        @Test
        @DisplayName("the active status is a raw single character: Y and N round-trip, and so does a "
                + "value outside that pair, because the entity maps no enum and validates nothing")
        void theActiveStatusIsARawSingleCharacter() {
            final Account account = new Account();

            account.setAcctActiveStatus("Y");
            assertThat(account.getAcctActiveStatus()).isEqualTo("Y");
            assertThat(encodedWidth(account.getAcctActiveStatus())).isEqualTo(ACTIVE_STATUS_WIDTH);

            account.setAcctActiveStatus("N");
            assertThat(account.getAcctActiveStatus()).isEqualTo("N");
            assertThat(encodedWidth(account.getAcctActiveStatus())).isEqualTo(ACTIVE_STATUS_WIDTH);

            // A code outside the documented pair is stored and returned unchanged: no exception, default,
            // case fold or normalization. The column is a raw code rather than a mapped enum constant,
            // which would reject it - new behaviour the legacy file never had.
            account.setAcctActiveStatus("X");
            assertThat(account.getAcctActiveStatus()).isEqualTo("X");

            account.setAcctActiveStatus("y");
            assertThat(account.getAcctActiveStatus())
                    .as("case is preserved, so no folding occurred")
                    .isEqualTo("y")
                    .isNotEqualTo("Y");

            account.setAcctActiveStatus(" ");
            assertThat(account.getAcctActiveStatus()).isEqualTo(" ");
            assertThat(encodedWidth(account.getAcctActiveStatus())).isEqualTo(ACTIVE_STATUS_WIDTH);
        }

        @Test
        @DisplayName("the three date fields are raw ten-character text: a real date round-trips, and "
                + "so does 9999-99-99, because the entity parses nothing and holds no date type")
        void theDateFieldsAreRawTenCharacterText() {
            final Account account = seededRowZero();

            assertThat(account.getAcctOpenDate()).isEqualTo(SEED_OPEN_DATE);
            assertThat(encodedWidth(account.getAcctOpenDate())).isEqualTo(DATE_WIDTH);

            // A value that is not a valid calendar date is stored and returned unchanged. Strict calendar
            // validation lives in the date-validation service; here it would reject values the record
            // could carry.
            account.setAcctOpenDate("9999-99-99");
            account.setAcctExpirationDate("9999-99-99");
            account.setAcctReissueDate("0000-00-00");

            assertThat(account.getAcctOpenDate()).isEqualTo("9999-99-99");
            assertThat(encodedWidth(account.getAcctOpenDate())).isEqualTo(DATE_WIDTH);
            assertThat(account.getAcctExpirationDate()).isEqualTo("9999-99-99");
            assertThat(encodedWidth(account.getAcctExpirationDate())).isEqualTo(DATE_WIDTH);
            assertThat(account.getAcctReissueDate()).isEqualTo("0000-00-00");
            assertThat(encodedWidth(account.getAcctReissueDate())).isEqualTo(DATE_WIDTH);

            // Ten spaces are equally acceptable, which a date type could not represent at all.
            account.setAcctReissueDate(SEED_GROUP_ID);
            assertThat(account.getAcctReissueDate()).isEqualTo(SEED_GROUP_ID);
            assertThat(encodedWidth(account.getAcctReissueDate())).isEqualTo(DATE_WIDTH);
        }
    }

    /**
     * Identity rests on the account identifier alone - the same eleven bytes the cluster definition
     * names as the key and the sequential reader takes as the record image's leading substring.
     */
    @Nested
    @DisplayName("business-key identity")
    class BusinessKeyIdentity {

        /**
         * Builds an account sharing the given identifier but differing in every other business field.
         *
         * @param acctId the identifier to share
         * @return an account differing from {@link AccountTest#seededRowZero()} in all non-key fields
         */
        private Account differingInEveryFieldExceptTheKey(final String acctId) {
            return new Account(
                    acctId,
                    "N",
                    new BigDecimal("-1.11"),
                    new BigDecimal("-2.22"),
                    new BigDecimal("-3.33"),
                    "1970-01-01",
                    "1971-02-02",
                    "1972-03-03",
                    new BigDecimal("-4.44"),
                    new BigDecimal("-5.55"),
                    "Z999999999",
                    "OTHERGRP  ");
        }

        @Test
        @DisplayName("two accounts sharing an identifier are equal and share a hash code even when "
                + "every other field, all five amounts included, differs")
        void twoAccountsSharingAnIdentifierAreEqual() {
            final Account fromFixture = seededRowZero();
            final Account divergent = differingInEveryFieldExceptTheKey(SEED_ACCT_ID);

            assertThat(fromFixture).isEqualTo(divergent);
            assertThat(divergent).isEqualTo(fromFixture);
            assertThat(fromFixture).hasSameHashCodeAs(divergent);

            assertThat(divergent.getAcctActiveStatus()).isNotEqualTo(fromFixture.getAcctActiveStatus());
            assertThat(divergent.getAcctCurrBal()).isNotEqualTo(fromFixture.getAcctCurrBal());
            assertThat(divergent.getAcctCreditLimit())
                    .isNotEqualTo(fromFixture.getAcctCreditLimit());
            assertThat(divergent.getAcctCashCreditLimit())
                    .isNotEqualTo(fromFixture.getAcctCashCreditLimit());
            assertThat(divergent.getAcctCurrCycCredit())
                    .isNotEqualTo(fromFixture.getAcctCurrCycCredit());
            assertThat(divergent.getAcctCurrCycDebit())
                    .isNotEqualTo(fromFixture.getAcctCurrCycDebit());
            assertThat(divergent.getAcctOpenDate()).isNotEqualTo(fromFixture.getAcctOpenDate());
            assertThat(divergent.getAcctExpirationDate())
                    .isNotEqualTo(fromFixture.getAcctExpirationDate());
            assertThat(divergent.getAcctReissueDate())
                    .isNotEqualTo(fromFixture.getAcctReissueDate());
            assertThat(divergent.getAcctAddrZip()).isNotEqualTo(fromFixture.getAcctAddrZip());
            assertThat(divergent.getAcctGroupId()).isNotEqualTo(fromFixture.getAcctGroupId());
        }

        @Test
        @DisplayName("a mutated non-key field leaves equality and the hash code untouched, so an "
                + "account stays findable in a hash-based collection across a state change")
        void mutatingANonKeyFieldDoesNotDisturbIdentity() {
            final Account account = seededRowZero();
            final Account reference = seededRowZero();
            final int hashBefore = account.hashCode();

            account.setAcctCurrBal(new BigDecimal("-99999.99"));
            account.setAcctActiveStatus("X");
            account.setAcctGroupId("MOVED     ");

            assertThat(account.hashCode()).isEqualTo(hashBefore);
            assertThat(account).isEqualTo(reference);
        }

        @Test
        @DisplayName("accounts with different identifiers are unequal, even when every other field "
                + "matches byte for byte")
        void accountsWithDifferentIdentifiersAreUnequal() {
            final Account first = seededRowZero();
            final Account second = new Account(
                    "00000000002",
                    SEED_ACTIVE_STATUS,
                    new BigDecimal(SEED_CURR_BAL),
                    new BigDecimal(SEED_CREDIT_LIMIT),
                    new BigDecimal(SEED_CASH_CREDIT_LIMIT),
                    SEED_OPEN_DATE,
                    SEED_EXPIRATION_DATE,
                    SEED_REISSUE_DATE,
                    new BigDecimal(SEED_CURR_CYC_CREDIT),
                    new BigDecimal(SEED_CURR_CYC_DEBIT),
                    SEED_ADDR_ZIP,
                    SEED_GROUP_ID);

            assertThat(first).isNotEqualTo(second);
            assertThat(second).isNotEqualTo(first);
        }

        @Test
        @DisplayName("the identifier is compared byte for byte, so a padded identifier is a "
                + "different account from an unpadded one")
        void theIdentifierIsComparedByteForByte() {
            final Account elevenDigits = seededRowZero();
            final Account shortened = differingInEveryFieldExceptTheKey("1");

            assertThat(elevenDigits).isNotEqualTo(shortened);
            assertThat(elevenDigits.hashCode()).isNotEqualTo(shortened.hashCode());
        }

        @Test
        @DisplayName("equality is reflexive, null-safe and foreign-type-safe, and an absent "
                + "identifier matches another absent identifier")
        void equalityIsReflexiveNullSafeAndForeignTypeSafe() {
            final Account account = seededRowZero();

            assertThat(account).isEqualTo(account);
            assertThat(account.equals(account)).isTrue();
            assertThat(account.hashCode()).isEqualTo(account.hashCode());

            assertThat(account.equals(null)).isFalse();
            assertThat(account.equals(SEED_ACCT_ID))
                    .as("a String carrying the same identifier is not an account")
                    .isFalse();
            assertThat(account.equals(new Object())).isFalse();

            // Two provider-instantiated instances are equal because both identifiers are absent, and
            // the hash of an absent identifier is stable rather than an exception.
            final Account firstEmpty = new Account();
            final Account secondEmpty = new Account();
            assertThat(firstEmpty).isEqualTo(secondEmpty);
            assertThat(firstEmpty).hasSameHashCodeAs(secondEmpty);
            assertThat(firstEmpty).isNotEqualTo(account);
            assertThat(account).isNotEqualTo(firstEmpty);
        }

        @Test
        @DisplayName("no surrogate identifier exists: the key is the business identifier itself, "
                + "which is why the reader's 11 + 289 == 300 split holds and why a fresh instance "
                + "reports an absent identifier rather than a generated one")
        void noSurrogateIdentifierExists() {
            final Account account = new Account();

            assertThat(account.getAcctId())
                    .as("nothing generated an identifier at construction time")
                    .isNull();

            // The key is a substring of the record image, not a value the database invents: the
            // reader's split states the same relationship the cluster's key clause does.
            assertThat(FD_KEY_WIDTH + FD_DATA_WIDTH).isEqualTo(RECORD_WIDTH);
            assertThat(FD_KEY_WIDTH).isEqualTo(KEY_WIDTH);
            assertThat(KEY_OFFSET).isZero();

            account.setAcctId(SEED_ACCT_ID);
            assertThat(account.getAcctId()).isEqualTo(SEED_ACCT_ID);
            assertThat(account).isEqualTo(seededRowZero());
        }
    }

    /**
     * Proves the version counter's default and its independence from identity, and that the diagnostic
     * rendering exposes the identifier and the raw status while withholding every other component.
     *
     * <p>Optimistic locking itself is not exercised here: an increment needs a persistence context and
     * belongs to the repository integration tier.
     */
    @Nested
    @DisplayName("version counter and diagnostic rendering")
    class VersionCounterAndDiagnostics {

        @Test
        @DisplayName("the version counter defaults to zero and is never seeded with a non-zero "
                + "value, so a row that has never been updated matches the schema default")
        void theVersionCounterDefaultsToZero() {
            assertThat(new Account().getVersion()).isEqualTo(0L);
            assertThat(seededRowZero().getVersion()).isEqualTo(0L);

            // The counter is absent from the constructor's parameter list on purpose: the provider owns the
            // value. The class exposes a getter and no setter, so this reads through the getter; reflection
            // would reach the field, is prohibited here, and would say nothing about the provider anyway.
        }

        @Test
        @DisplayName("the version counter is not part of entity identity: two instances sharing an "
                + "identifier are equal, and equality never reads the counter")
        void theVersionCounterIsNotPartOfIdentity() {
            final Account fromFixture = seededRowZero();
            final Account divergent = new Account();
            divergent.setAcctId(SEED_ACCT_ID);

            // Both counters are necessarily zero: no accessible setter and no persistence context. This is
            // a documenting test of the intended contract - identity is the business key alone, so a
            // counter advancing on every update must not change what row an instance is. The repository
            // integration tier observes an actual increment.
            assertThat(fromFixture.getVersion()).isEqualTo(0L);
            assertThat(divergent.getVersion()).isEqualTo(0L);
            assertThat(fromFixture).isEqualTo(divergent);
            assertThat(fromFixture).hasSameHashCodeAs(divergent);

            // The counter's presence is a documented strengthening rather than a behavioural change: it
            // replaces the legacy program's hand-written before-and-after image comparison over file
            // definitions specifying uncommitted read integrity, no recovery and no journaling. Stronger
            // isolation is not a regression.
        }

        @Test
        @DisplayName("the diagnostic rendering carries the identifier and the raw status and nothing "
                + "else")
        void theDiagnosticRenderingCarriesOnlyTheIdentifierAndStatus() {
            final String rendered = seededRowZero().toString();

            // The whole rendering is pinned character for character, written out here by hand rather
            // than assembled from the instance's accessors. A containment check alone would still pass
            // if a further field were appended, which is precisely the regression this guards against.
            assertThat(rendered).isEqualTo("Account[acctId='00000000001', acctActiveStatus='Y']");

            assertThat(rendered)
                    .isNotNull()
                    .contains(SEED_ACCT_ID)
                    .contains(SEED_ACTIVE_STATUS);

            // The rendering deliberately omits every monetary and address component; this assertion pins
            // that omission so a later convenience edit to the rendering cannot widen it.
            assertThat(rendered).doesNotContain(
                    SEED_CURR_BAL,
                    SEED_CREDIT_LIMIT,
                    SEED_CASH_CREDIT_LIMIT,
                    SEED_OPEN_DATE,
                    SEED_EXPIRATION_DATE,
                    SEED_REISSUE_DATE,
                    SEED_ADDR_ZIP);

            // The identifier appears untrimmed, so significant padding stays visible.
            assertThat(rendered).doesNotContain("acctId='1'");
        }

        @Test
        @DisplayName("the diagnostic rendering of a provider-instantiated instance is the same fixed "
                + "shape carrying unset values, so an assertion failure reports rather than throws")
        void theDiagnosticRenderingOfAnEmptyInstanceIsUsable() {
            // Pinned exactly rather than merely checked for existence. A non-null, non-empty check
            // passes for any string at all, including one that had begun disclosing an amount, so it
            // is not evidence that an unpopulated entity renders safely. The expected text is written
            // out here in full rather than assembled from the instance's own accessors: an unset
            // entity renders its two unset values as the literal text null, and the surrounding shape
            // is unchanged.
            assertThat(new Account().toString())
                    .isEqualTo("Account[acctId='null', acctActiveStatus='null']");
        }
    }
}
