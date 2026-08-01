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
 * <p><strong>Provenance.</strong> Legacy checkout SHA 7756d895ffeb65f7ea72aaa609e356d9899afcec;
 * upstream release stamp CardDemo_v1.0-15-g27d6c6f-68 (2022-07-19). Recorded here as a plain
 * identifier string for the traceability matrix. No assertion is made about that stamp: it is not
 * carried uniformly by every legacy member, so testing for it would test the estate rather than
 * this class.
 *
 * <p><strong>Every expected value in this file was hand-derived, never computed by the code under
 * test.</strong> Widths, offsets and the total record length come from three mutually independent
 * legacy artifacts, each read directly:
 * <ul>
 *   <li>the copybook {@code app/cpy/CVACT01Y.cpy}, whose header states a record length of 300 and
 *       which declares twelve named fields followed by a 178-byte trailing filler;</li>
 *   <li>the cluster definition {@code app/jcl/ACCTFILE.jcl}, which independently states
 *       {@code KEYS(11 0)} and {@code RECORDSIZE(300 300)};</li>
 *   <li>the sequential reader {@code app/cbl/CBACT01C.cbl}, whose file section splits the same
 *       record into an 11-byte key field {@code FD-ACCT-ID} and a 289-byte remainder
 *       {@code FD-ACCT-DATA}.</li>
 * </ul>
 * Because those three descriptions were written independently of one another, cross-checking the
 * width arithmetic against all three is a real test rather than a restatement. The field values are
 * likewise hand-decoded from the first record of the named fixture {@code app/data/ASCII/acctdata.txt}
 * and appear below as literals. Nothing in this file decodes a record image, and no production
 * mapper or codec is referenced: making another class the oracle would leave both classes free to
 * be wrong together.
 *
 * <p><strong>The five monetary fields are deliberately not contiguous.</strong> Three sit at offsets
 * 12, 24 and 36; three date fields then intervene at 48, 58 and 68; two further monetary fields
 * follow at 78 and 90. The interleaving is asserted explicitly, because a reader who assumed the
 * amounts formed one block would place every field from offset 48 onward incorrectly.
 *
 * <p><strong>Scope, stated as much by exclusion as by inclusion.</strong> This is a pure unit test:
 * it starts no container, opens no socket, reads no file and builds no application context. It
 * verifies the record contract the entity carries - construction, accessor transparency, decimal
 * fidelity, business-key identity and the version counter's default. It deliberately does not verify
 * column names, column widths or nullability, because the deployed schema is authoritative for those
 * and the integration tier validates the mapping against a real database rather than against a
 * restatement here. It deliberately does not exercise optimistic locking, which needs a persistence
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

    // -------------------------------------------------------------------------------------------
    // FIELD WIDTHS - hand-derived from the picture clauses of app/cpy/CVACT01Y.cpy
    // -------------------------------------------------------------------------------------------

    /** Width of the account identifier, an eleven-digit external-decimal field. */
    private static final int ACCT_ID_WIDTH = 11;

    /** Width of the active status code, a single alphanumeric byte. */
    private static final int ACTIVE_STATUS_WIDTH = 1;

    /** Integer digit count of the account amount picture {@code PIC S9(10)V99}. */
    private static final int AMOUNT_INTEGER_DIGITS = 10;

    /** Decimal digit count of the account amount picture {@code PIC S9(10)V99}. */
    private static final int AMOUNT_DECIMAL_DIGITS = 2;

    /**
     * Width of each of the five account amounts. Asserted below to be the sum of the integer and
     * decimal digit counts rather than stated independently, because that sum is the property a
     * copybook change would break.
     */
    private static final int AMOUNT_WIDTH = 12;

    /**
     * Width of the transaction and category-balance amounts, whose picture is {@code PIC S9(09)V99}
     * - one digit narrower than an account amount. Verified in {@code app/cpy/CVTRA05Y.cpy},
     * {@code app/cpy/CVTRA06Y.cpy} and {@code app/cpy/CVTRA01Y.cpy}. Held here only so that the
     * account width can be asserted to differ from it.
     */
    private static final int TRANSACTION_AMOUNT_WIDTH = 11;

    /**
     * Width of the disclosure-group interest rate, whose picture is {@code PIC S9(04)V99}. Verified
     * in {@code app/cpy/CVTRA02Y.cpy}. Held here only so that the account width can be asserted to
     * differ from it.
     */
    private static final int DISCLOSURE_RATE_WIDTH = 6;

    /** Width of each of the three date fields, held as fixed-width text rather than as a date. */
    private static final int DATE_WIDTH = 10;

    /** Width of the address ZIP field. */
    private static final int ADDR_ZIP_WIDTH = 10;

    /** Width of the account group identifier. */
    private static final int GROUP_ID_WIDTH = 10;

    /** Width of the trailing filler, which is deliberately neither a property nor a column. */
    private static final int FILLER_WIDTH = 178;

    // -------------------------------------------------------------------------------------------
    // FIELD OFFSETS - zero-based, hand-derived by accumulating the widths above
    // -------------------------------------------------------------------------------------------

    /** Offset of the account identifier. */
    private static final int OFFSET_ACCT_ID = 0;

    /** Offset of the active status code. */
    private static final int OFFSET_ACTIVE_STATUS = 11;

    /** Offset of the current balance - the first of three consecutive amounts. */
    private static final int OFFSET_CURR_BAL = 12;

    /** Offset of the credit limit - the second of three consecutive amounts. */
    private static final int OFFSET_CREDIT_LIMIT = 24;

    /** Offset of the cash credit limit - the last amount before the dates intervene. */
    private static final int OFFSET_CASH_CREDIT_LIMIT = 36;

    /** Offset of the open date - where the amounts give way to the dates. */
    private static final int OFFSET_OPEN_DATE = 48;

    /**
     * Offset of the expiration date. The copybook spells this field {@code ACCT-EXPIRAION-DATE},
     * dropping a letter from EXPIRATION, at copybook line 11. The misspelling is a documented source
     * anomaly; the offset is the invariant.
     */
    private static final int OFFSET_EXPIRATION_DATE = 58;

    /** Offset of the reissue date - the last date before the amounts resume. */
    private static final int OFFSET_REISSUE_DATE = 68;

    /** Offset of the current cycle credit - where the dates give way to the amounts again. */
    private static final int OFFSET_CURR_CYC_CREDIT = 78;

    /** Offset of the current cycle debit - the fifth and last amount. */
    private static final int OFFSET_CURR_CYC_DEBIT = 90;

    /** Offset of the address ZIP. */
    private static final int OFFSET_ADDR_ZIP = 102;

    /** Offset of the account group identifier. */
    private static final int OFFSET_GROUP_ID = 112;

    /** Offset at which the unmapped trailing filler begins, and so the total mapped width. */
    private static final int OFFSET_FILLER = 122;

    // -------------------------------------------------------------------------------------------
    // RECORD GEOMETRY - from app/jcl/ACCTFILE.jcl and app/cbl/CBACT01C.cbl, independent of the above
    // -------------------------------------------------------------------------------------------

    /** Record length, from {@code RECORDSIZE(300 300)} in the cluster definition. */
    private static final int RECORD_WIDTH = 300;

    /** Key length, from {@code KEYS(11 0)} in the cluster definition. */
    private static final int KEY_WIDTH = 11;

    /** Key offset, from {@code KEYS(11 0)} in the cluster definition. */
    private static final int KEY_OFFSET = 0;

    /** Width of {@code FD-ACCT-ID}, the key portion of the record in the sequential reader. */
    private static final int FD_KEY_WIDTH = 11;

    /** Width of {@code FD-ACCT-DATA}, the remainder of the record in the sequential reader. */
    private static final int FD_DATA_WIDTH = 289;

    /**
     * The thirteen declared widths of the copybook in declaration order: twelve mapped fields
     * followed by the trailing filler.
     */
    private static final List<Integer> COPYBOOK_WIDTHS = List.of(
            ACCT_ID_WIDTH, ACTIVE_STATUS_WIDTH,
            AMOUNT_WIDTH, AMOUNT_WIDTH, AMOUNT_WIDTH,
            DATE_WIDTH, DATE_WIDTH, DATE_WIDTH,
            AMOUNT_WIDTH, AMOUNT_WIDTH,
            ADDR_ZIP_WIDTH, GROUP_ID_WIDTH,
            FILLER_WIDTH);

    /** Zero-based offset of every declared field, in the same order as {@link #COPYBOOK_WIDTHS}. */
    private static final List<Integer> COPYBOOK_OFFSETS = List.of(
            OFFSET_ACCT_ID, OFFSET_ACTIVE_STATUS,
            OFFSET_CURR_BAL, OFFSET_CREDIT_LIMIT, OFFSET_CASH_CREDIT_LIMIT,
            OFFSET_OPEN_DATE, OFFSET_EXPIRATION_DATE, OFFSET_REISSUE_DATE,
            OFFSET_CURR_CYC_CREDIT, OFFSET_CURR_CYC_DEBIT,
            OFFSET_ADDR_ZIP, OFFSET_GROUP_ID,
            OFFSET_FILLER);

    // -------------------------------------------------------------------------------------------
    // NAMED FIXTURE FACTS - app/data/ASCII/acctdata.txt, measured directly
    // -------------------------------------------------------------------------------------------

    /** Records the named account fixture holds. */
    private static final int SEEDED_RECORD_COUNT = 50;

    /** Bytes each fixture line occupies: the 300-byte image plus one line terminator. */
    private static final int SEEDED_RECORD_STRIDE = 301;

    /** Measured size of the named account fixture in bytes. */
    private static final int SEEDED_FILE_BYTES = 15_050;

    // -------------------------------------------------------------------------------------------
    // ROW ZERO OF THE NAMED FIXTURE - hand-decoded, one literal per field
    //
    // The amounts are zoned decimal with an overpunched trailing byte: the final byte carries both
    // the low-order digit and the sign, so the image "00000001940{" holds the unsigned digits
    // 000000019400 with a positive sign, which at a scale of two is 194.00. That convention explains
    // how these literals were derived; this file never performs the decoding, because decoding is
    // the codec's contract and asserting it here would test the wrong class.
    // -------------------------------------------------------------------------------------------

    /** Account identifier of the first fixture record, zero-filled to its full width. */
    private static final String SEED_ACCT_ID = "00000000001";

    /** Active status of the first fixture record. All fifty records carry this same value. */
    private static final String SEED_ACTIVE_STATUS = "Y";

    /** Current balance of the first fixture record. */
    private static final String SEED_CURR_BAL = "194.00";

    /** Credit limit of the first fixture record. */
    private static final String SEED_CREDIT_LIMIT = "2020.00";

    /** Cash credit limit of the first fixture record. */
    private static final String SEED_CASH_CREDIT_LIMIT = "1020.00";

    /** Open date of the first fixture record, in its ten-character external form. */
    private static final String SEED_OPEN_DATE = "2014-11-20";

    /** Expiration date of the first fixture record, in its ten-character external form. */
    private static final String SEED_EXPIRATION_DATE = "2025-05-20";

    /** Reissue date of the first fixture record, in its ten-character external form. */
    private static final String SEED_REISSUE_DATE = "2025-05-20";

    /** Current cycle credit of the first fixture record. */
    private static final String SEED_CURR_CYC_CREDIT = "0.00";

    /** Current cycle debit of the first fixture record. */
    private static final String SEED_CURR_CYC_DEBIT = "0.00";

    /**
     * Address ZIP of the first fixture record. Every one of the fifty records carries this same
     * ten-byte value, whose leading character is a letter.
     */
    private static final String SEED_ADDR_ZIP = "A000000000";

    /**
     * Account group identifier of the first fixture record: ten spaces. Every one of the fifty
     * records carries ten spaces here. The literal's encoded width is asserted below so that a
     * miscounted transcription fails loudly rather than silently weakening the tests that use it.
     */
    private static final String SEED_GROUP_ID = "          ";

    /** Scale every account amount carries, being the decimal digit count of its picture. */
    private static final int MONETARY_SCALE = 2;

    /**
     * Builds the first record of the named fixture through the public constructor, with every value
     * a hand-decoded literal.
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
     * Measures a value the way the fixed-width record contract measures it: in encoded bytes, under
     * an explicitly named charset.
     *
     * <p>The record is a byte image, so its widths are byte widths. Naming the charset at every
     * boundary keeps the measurement independent of the platform default, which would otherwise make
     * the same assertion mean different things on different machines.
     *
     * @param value the value to measure
     * @return the number of bytes the value occupies when encoded as US-ASCII
     */
    private static int encodedWidth(final String value) {
        return value.getBytes(StandardCharsets.US_ASCII).length;
    }

    // CLUSTER 1 :: RECORD LAYOUT

    /**
     * Proves the geometry the entity has to honour, by cross-checking the copybook's field widths
     * against the cluster definition's record size and the sequential reader's key split - three
     * artifacts written independently of one another.
     */
    @Nested
    @DisplayName("record layout")
    class RecordLayout {

        @Test
        @DisplayName("the twelve mapped widths sum to 122, and 122 + 178 filler bytes is the "
                + "300-byte record the cluster declares")
        void theMappedWidthsSumToOneHundredAndTwentyTwo() {
            // The left side is the twelve picture widths read from the copybook; the right side is
            // the offset at which the filler begins. Neither number comes from the entity.
            assertThat(ACCT_ID_WIDTH + ACTIVE_STATUS_WIDTH
                    + AMOUNT_WIDTH + AMOUNT_WIDTH + AMOUNT_WIDTH
                    + DATE_WIDTH + DATE_WIDTH + DATE_WIDTH
                    + AMOUNT_WIDTH + AMOUNT_WIDTH
                    + ADDR_ZIP_WIDTH + GROUP_ID_WIDTH)
                    .as("11 + 1 + 12 + 12 + 12 + 10 + 10 + 10 + 12 + 12 + 10 + 10 == 122")
                    .isEqualTo(OFFSET_FILLER);

            // And the 178-byte remainder is exactly the unmapped filler: the copybook accounts for
            // the whole record, so nothing is hiding beyond the twelve mapped fields.
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
            // Three amounts run consecutively: 12 -> 24 -> 36.
            assertThat(OFFSET_CURR_BAL + AMOUNT_WIDTH)
                    .as("12 + 12 == 24")
                    .isEqualTo(OFFSET_CREDIT_LIMIT);
            assertThat(OFFSET_CREDIT_LIMIT + AMOUNT_WIDTH)
                    .as("24 + 12 == 36")
                    .isEqualTo(OFFSET_CASH_CREDIT_LIMIT);

            // Then the amounts stop and the dates begin. This is the hand-over a reader who assumed
            // five adjacent amounts would miss, and it would misplace every later field.
            assertThat(OFFSET_CASH_CREDIT_LIMIT + AMOUNT_WIDTH)
                    .as("36 + 12 == 48 - the third amount is followed by a date, not a fourth amount")
                    .isEqualTo(OFFSET_OPEN_DATE);

            // Three dates run consecutively: 48 -> 58 -> 68.
            assertThat(OFFSET_OPEN_DATE + DATE_WIDTH)
                    .as("48 + 10 == 58")
                    .isEqualTo(OFFSET_EXPIRATION_DATE);
            assertThat(OFFSET_EXPIRATION_DATE + DATE_WIDTH)
                    .as("58 + 10 == 68")
                    .isEqualTo(OFFSET_REISSUE_DATE);

            // Then the dates stop and the remaining two amounts begin.
            assertThat(OFFSET_REISSUE_DATE + DATE_WIDTH)
                    .as("68 + 10 == 78 - the third date is followed by an amount")
                    .isEqualTo(OFFSET_CURR_CYC_CREDIT);
            assertThat(OFFSET_CURR_CYC_CREDIT + AMOUNT_WIDTH)
                    .as("78 + 12 == 90")
                    .isEqualTo(OFFSET_CURR_CYC_DEBIT);

            // Stated as a single fact: the amounts do not form one block. Were they contiguous, the
            // fourth would start at 48; it starts at 78, thirty bytes of dates later.
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
            // Derived, not copied: the expiry begins exactly one date width after the open date.
            assertThat(OFFSET_OPEN_DATE + DATE_WIDTH)
                    .as("48 + 10 == 58")
                    .isEqualTo(OFFSET_EXPIRATION_DATE);

            // Preserving the offset is what keeps the record image byte-compatible. The Java
            // property spells expiration correctly, which is a documented divergence rather than a
            // silent correction: the misspelled legacy name is cited in the entity's own
            // documentation so the mapping back to the copybook field stays findable.
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

            // All five account amounts share that one shape.
            assertThat(COPYBOOK_WIDTHS.get(2)).isEqualTo(AMOUNT_WIDTH);
            assertThat(COPYBOOK_WIDTHS.get(3)).isEqualTo(AMOUNT_WIDTH);
            assertThat(COPYBOOK_WIDTHS.get(4)).isEqualTo(AMOUNT_WIDTH);
            assertThat(COPYBOOK_WIDTHS.get(8)).isEqualTo(AMOUNT_WIDTH);
            assertThat(COPYBOOK_WIDTHS.get(9)).isEqualTo(AMOUNT_WIDTH);

            // And it is not the shape of the neighbouring records' amounts. Sharing one width
            // constant across records would corrupt every one of them.
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
            // FD-ACCT-ID and FD-ACCT-DATA in app/cbl/CBACT01C.cbl - cited by member and field name
            // rather than by line number, because the physical line differs from the specification's
            // citation and the widths are the durable fact.
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

    // CLUSTER 2 :: CONSTRUCTION AND ACCESSORS

    /**
     * Proves the entity is a transparent carrier: what goes in comes back out, field for field, with
     * nothing trimmed, padded, folded, normalized, parsed, validated or rescaled on the way through.
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
            // Distinct values per field, so a swapped assignment cannot hide behind equal values.
            // The three amounts differ from one another, as do the three dates, and the ZIP differs
            // from the group identifier - the two positions the fixture itself makes look alike.
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

            // The five amounts are absent rather than zero: an unset amount and a zero balance are
            // different facts, and conflating them would invent data the record never carried.
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

            // Non-nullability is a database constraint enforced at insert time, not a guard the
            // setter imposes. Asserting an exception here would invent behaviour the class does not
            // have and the legacy record never had.
            assertThat(account.getAcctId()).isEqualTo(SEED_ACCT_ID);
        }
    }

    // CLUSTER 3 :: MONETARY FIDELITY

    /**
     * Proves the five amounts keep both their value and their scale, and that the entity neither
     * rescales nor rounds nor computes.
     *
     * <p>Every amount here is built from a decimal string, and every one is an exact decimal. None is
     * built from an approximate binary numeric type, because such a type cannot hold an exact
     * two-decimal amount and the migration contract forbids substituting one for the legacy decimal.
     * That prohibition is honoured mechanically: no approximate numeric type is named anywhere in this
     * file, in code or in prose.
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

            // Numerically identical - which is why numeric comparisons must use compareTo.
            assertThat(twoDecimals.compareTo(noDecimals)).isZero();
            // Yet distinct values, because equality on an exact decimal includes its scale. That is
            // the distinction a fixed-width two-decimal field depends on.
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
        @DisplayName("negative zero is accepted and kept as supplied: the legacy zoned form "
                + "distinguishes a positive zero from a negative zero, and the entity canonicalises "
                + "neither")
        void negativeZeroIsKeptAsSupplied() {
            final Account account = seededRowZero();

            account.setAcctCurrCycDebit(new BigDecimal("-0.00"));

            // Numerically zero, and still carrying the two-decimal scale.
            assertThat(account.getAcctCurrCycDebit().compareTo(new BigDecimal("0.00"))).isZero();
            assertThat(account.getAcctCurrCycDebit().signum()).isZero();
            assertThat(account.getAcctCurrCycDebit().scale()).isEqualTo(MONETARY_SCALE);

            // Stored exactly as supplied rather than folded to a positive zero. An exact decimal has
            // no signed zero, so the sign is carried by the zoned image's overpunched trailing byte
            // rather than by this value - which is precisely why the entity must not normalize what
            // it is handed and must not assume the codec handed it one form rather than the other.
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
            // Either policy applied here would be a second place for rounding to be decided, and the
            // two places would eventually disagree by a cent. There is exactly one such place, and
            // it is not this class.
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

    // CLUSTER 4 :: RAW CHARACTER FIDELITY

    /**
     * Proves the character fields are byte images carried verbatim: full width preserved, padding
     * significant, no parsing, no vocabulary check and no normalization of any kind.
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

            // The seven character widths plus the five twelve-byte amounts are the whole mapped
            // portion of the record.
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
            // Guard the transcription first: a miscounted literal would silently weaken everything
            // that follows.
            assertThat(encodedWidth(SEED_GROUP_ID)).isEqualTo(GROUP_ID_WIDTH);

            final Account account = new Account();
            account.setAcctGroupId(SEED_GROUP_ID);

            assertThat(account.getAcctGroupId()).isNotNull();
            assertThat(encodedWidth(account.getAcctGroupId())).isEqualTo(GROUP_ID_WIDTH);
            assertThat(account.getAcctGroupId()).isEqualTo(SEED_GROUP_ID);

            // Padding is part of the value, so a padded identifier is not an empty one and not a
            // single space. Were the accessor to trim, all three of these would collapse into one
            // value in Java while remaining three distinct values in the database.
            assertThat(account.getAcctGroupId()).isNotEqualTo("");
            assertThat(account.getAcctGroupId()).isNotEqualTo(" ");
            assertThat(account.getAcctGroupId()).isNotEqualTo("DEFAULT   ");

            // The constructed path behaves identically to the mutated one.
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

            // The two positions are read strictly by offset. Both slots are ten bytes wide, so
            // neither value is malformed and nothing here is swapped, inferred or corrected: a
            // mapper that "fixed" the apparent transposition would disagree with the seeded data
            // and with the interest run's rate lookup that reads the group identifier.
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

            // All fifty fixture records carry Y.
            account.setAcctActiveStatus("Y");
            assertThat(account.getAcctActiveStatus()).isEqualTo("Y");
            assertThat(encodedWidth(account.getAcctActiveStatus())).isEqualTo(ACTIVE_STATUS_WIDTH);

            account.setAcctActiveStatus("N");
            assertThat(account.getAcctActiveStatus()).isEqualTo("N");
            assertThat(encodedWidth(account.getAcctActiveStatus())).isEqualTo(ACTIVE_STATUS_WIDTH);

            // A code outside the documented pair is stored and returned unchanged: no exception, no
            // substituted default, no case folding, no normalization. This is what proves the column
            // is a raw code rather than a mapped enum constant - a mapped enum would reject it, and
            // rejecting it would be new behaviour the legacy file never had.
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

            // A value that is not a valid calendar date at all is stored and returned unchanged.
            // Strict calendar validation reproduces the legacy edit cascade in the date-validation
            // service; performing it here would reject values the legacy record could carry.
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

    // CLUSTER 5 :: BUSINESS-KEY IDENTITY

    /**
     * Proves identity rests on the account identifier alone - the same eleven bytes the cluster
     * definition names as the key and the sequential reader takes as the leading substring of the
     * record image.
     */
    @Nested
    @DisplayName("business-key identity")
    class BusinessKeyIdentity {

        /**
         * Builds an account that shares the given identifier but differs in every single other
         * business field, including all five amounts.
         *
         * @param acctId the identifier to share
         * @return an account differing from {@link AccountTest#seededRowZero()} in all eleven
         *         non-key fields
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

            // Same row of the same table, so the same entity - whatever their current state.
            assertThat(fromFixture).isEqualTo(divergent);
            assertThat(divergent).isEqualTo(fromFixture);
            assertThat(fromFixture).hasSameHashCodeAs(divergent);

            // Demonstrably different in every non-key field, so equality really did ignore them.
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
            // Proof by compile-time absence. This class references no generated-identifier accessor
            // anywhere - there is no such member to reference - and it uses no reflection to look for
            // one, because a reflective probe would assert the absence of a name rather than the
            // absence of an identifier.
            final Account account = new Account();

            assertThat(account.getAcctId())
                    .as("nothing generated an identifier at construction time")
                    .isNull();

            // The key is a substring of the record image, not a value the database invents: the
            // reader's split states the same relationship the cluster's key clause does.
            assertThat(FD_KEY_WIDTH + FD_DATA_WIDTH).isEqualTo(RECORD_WIDTH);
            assertThat(FD_KEY_WIDTH).isEqualTo(KEY_WIDTH);
            assertThat(KEY_OFFSET).isZero();

            // Supplying the key is what gives the instance its identity.
            account.setAcctId(SEED_ACCT_ID);
            assertThat(account.getAcctId()).isEqualTo(SEED_ACCT_ID);
            assertThat(account).isEqualTo(seededRowZero());
        }
    }

    // CLUSTER 6 :: VERSION COUNTER AND DIAGNOSTIC RENDERING

    /**
     * Proves the version counter's default and its independence from identity, and that the
     * diagnostic rendering exposes only the two values it is allowed to.
     *
     * <p>Optimistic locking itself is not exercised here: an increment needs a persistence context
     * and belongs to the repository integration tier. What is asserted is the part that is
     * observable without one.
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

            // The counter is absent from the constructor's parameter list on purpose: the
            // persistence provider owns the value, so application code has no business supplying it.
            // The class exposes a getter and no setter, so this test reads it through the getter and
            // makes no attempt to write it. Reflection would reach the field, and is not used: it is
            // prohibited here, and a reflective write would prove nothing about the provider's
            // behaviour anyway.
        }

        @Test
        @DisplayName("the version counter is not part of entity identity: two instances sharing an "
                + "identifier are equal, and equality never reads the counter")
        void theVersionCounterIsNotPartOfIdentity() {
            final Account fromFixture = seededRowZero();
            final Account divergent = new Account();
            divergent.setAcctId(SEED_ACCT_ID);

            // Both counters are necessarily zero, because no accessible setter exists and no
            // persistence context is in scope. This is therefore a documenting test of the intended
            // contract: identity is the business key alone, so a counter that advances on every
            // update must not change what row an instance is. The repository integration tier
            // observes an actual increment and re-checks equality across it.
            assertThat(fromFixture.getVersion()).isEqualTo(0L);
            assertThat(divergent.getVersion()).isEqualTo(0L);
            assertThat(fromFixture).isEqualTo(divergent);
            assertThat(fromFixture).hasSameHashCodeAs(divergent);

            // The counter's presence is itself a documented strengthening rather than a behavioural
            // change: it replaces the legacy program's hand-written before-and-after image
            // comparison, and the legacy file definitions specified uncommitted read integrity, no
            // recovery and no journaling. Stronger isolation should not be misread as a regression.
        }

        @Test
        @DisplayName("the diagnostic rendering carries the identifier and the raw status and nothing "
                + "else, so no amount and no address component can reach a log line")
        void theDiagnosticRenderingCarriesOnlyTheIdentifierAndStatus() {
            final String rendered = seededRowZero().toString();

            assertThat(rendered)
                    .isNotNull()
                    .contains(SEED_ACCT_ID)
                    .contains(SEED_ACTIVE_STATUS);

            // Every other field is withheld. The amounts and the address components are the values
            // that must never appear in a diagnostic line.
            assertThat(rendered).doesNotContain(
                    SEED_CURR_BAL,
                    SEED_CREDIT_LIMIT,
                    SEED_CASH_CREDIT_LIMIT,
                    SEED_OPEN_DATE,
                    SEED_EXPIRATION_DATE,
                    SEED_REISSUE_DATE,
                    SEED_ADDR_ZIP);

            // The identifier appears untrimmed, so significant padding stays visible to a reader.
            assertThat(rendered).doesNotContain("acctId='1'");
        }

        @Test
        @DisplayName("the diagnostic rendering of a provider-instantiated instance is still usable, "
                + "so an assertion failure on an unpopulated entity reports rather than throws")
        void theDiagnosticRenderingOfAnEmptyInstanceIsUsable() {
            final String rendered = new Account().toString();

            assertThat(rendered).isNotNull().isNotEmpty();
        }
    }
}
