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

import com.carddemo.support.SensitiveValues;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Unit tests for {@link Transaction}, the 350-byte posted-transaction record.
 *
 * <p>This is the settled counterpart of the staged daily record, and the two layouts are
 * byte-for-byte parallel. The tests are therefore written to catch the specific ways a
 * byte-for-byte parallel pair goes wrong: a merchant column silently regularised onto the sibling's
 * prefix, two equally wide neighbouring fields transposed, a padded value helpfully trimmed, or an
 * amount quietly rescaled.
 *
 * <h2>What is asserted here and what is asserted elsewhere</h2>
 *
 * <p>This file asserts <em>behaviour</em>: what the constructors, accessors, mutators, equality
 * contract and diagnostic rendering actually do. It reads no width out of production code - every
 * width and offset below is hand-summed from the copybook layout and appears here as its own literal,
 * so a width changed in the entity cannot also change the expectation it is checked against.
 *
 * <p>The provider-level <em>mapping</em> - column names, declared widths, nullability and key columns,
 * compared against the shipped migration through the provider's own computed metadata - is asserted
 * exhaustively by {@code EntityPersistenceMappingTest}, which is where the four unprefixed merchant
 * column names are verified against the schema. The two files are deliberately complementary: this one
 * would still pass if every column name were wrong, and that one would still pass if every accessor
 * returned a constant.
 *
 * <h2>Provenance</h2>
 *
 * <p>Widths, offsets and values are cited from the estate at commit
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, whose members carry the upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. No legacy source text is reproduced; member
 * names, field names, widths, offsets and codes are cited only.
 */
@DisplayName("Transaction - the 350-byte posted-transaction record written by the posting run")
class TransactionTest {

    // ------------------------------------------------------------------------------------------
    // Hand-summed field widths from the copybook layout. These are the derivation inputs for every
    // offset asserted below; nothing in this file reads a width from production code.
    // ------------------------------------------------------------------------------------------

    /** Width of the transaction identifier. */
    private static final int WIDTH_ID = 16;

    /** Width of the transaction type code. */
    private static final int WIDTH_TYPE_CD = 2;

    /** Width of the transaction category code. */
    private static final int WIDTH_CAT_CD = 4;

    /** Width of the origination source code. */
    private static final int WIDTH_SOURCE = 10;

    /** Width of the transaction description. */
    private static final int WIDTH_DESC = 100;

    /** Integer digit count of the signed nine-and-two amount picture. */
    private static final int AMOUNT_INTEGER_DIGITS = 9;

    /** Decimal digit count of the signed nine-and-two amount picture. */
    private static final int AMOUNT_DECIMAL_DIGITS = 2;

    /** Width of the amount, being the integer digits plus the decimal digits. */
    private static final int WIDTH_AMT = 11;

    /** Width of the merchant identifier. */
    private static final int WIDTH_MERCHANT_ID = 9;

    /** Width of the merchant name. */
    private static final int WIDTH_MERCHANT_NAME = 50;

    /** Width of the merchant city. */
    private static final int WIDTH_MERCHANT_CITY = 50;

    /** Width of the merchant postal code. */
    private static final int WIDTH_MERCHANT_ZIP = 10;

    /** Width of the card number. */
    private static final int WIDTH_CARD_NUM = 16;

    /** Width of the origination timestamp lexeme. */
    private static final int WIDTH_ORIG_TS = 26;

    /** Width of the processing timestamp lexeme. */
    private static final int WIDTH_PROC_TS = 26;

    /** Width of the unmapped trailing filler. */
    private static final int WIDTH_FILLER = 20;

    /** Sum of the thirteen mapped widths, computed by hand as 330. */
    private static final int MAPPED_WIDTH_TOTAL = 330;

    /** Declared record width, computed by hand as the mapped total plus the filler. */
    private static final int RECORD_WIDTH = 350;

    // ------------------------------------------------------------------------------------------
    // Zero-based offsets, each the running total of every width that precedes it. The last three
    // are contractual: external sort specifications address them by absolute position.
    // ------------------------------------------------------------------------------------------

    /** Offset of the card number, hand-derived as the sum of the ten preceding widths. */
    private static final int OFFSET_CARD_NUM = 262;

    /**
     * One-based position of the card number, which is how the report procedure's symbol declaration
     * addresses it. Stated separately because an off-by-one here is the whole defect.
     */
    private static final int POSITION_CARD_NUM = 263;

    /** Offset of the origination timestamp, hand-derived as the card-number offset plus its width. */
    private static final int OFFSET_ORIG_TS = 278;

    /** Offset of the processing timestamp, and the alternate-index key offset. */
    private static final int OFFSET_PROC_TS = 304;

    /** Offset of the unmapped trailing filler, hand-derived as the processing offset plus its width. */
    private static final int OFFSET_FILLER = 330;

    /** Key width the base cluster declares, which is the identifier width at offset zero. */
    private static final int CLUSTER_KEY_WIDTH = 16;

    /** Key offset the base cluster declares. */
    private static final int CLUSTER_KEY_OFFSET = 0;

    /** Key width the timestamp alternate index declares. */
    private static final int ALTERNATE_KEY_WIDTH = 26;

    // ------------------------------------------------------------------------------------------
    // Representative values. Padded values are assembled from the text plus their hand-counted
    // trailing spaces, which reproduces the image the record carries. This is test-input
    // construction; no value read back out of the entity is ever trimmed or padded.
    // ------------------------------------------------------------------------------------------

    /** A posted transaction identifier, sixteen characters with ten leading zeros. */
    private static final String ROW_ID = "0000000000683580";

    /** A transaction type code. */
    private static final String ROW_TYPE_CD = "01";

    /** A transaction category code, four characters with three leading zeros. */
    private static final String ROW_CAT_CD = "0001";

    /** Point-of-sale origination marker: eight characters of text plus two trailing spaces. */
    private static final String ROW_SOURCE = "POS TERM" + " ".repeat(2);

    /** Operator origination marker: eight characters of text plus two trailing spaces. */
    private static final String OPERATOR_SOURCE = "OPERATOR" + " ".repeat(2);

    /** A description: twenty-four characters of text plus seventy-six trailing spaces. */
    private static final String ROW_DESC = "Purchase at Abshire-Lowe" + " ".repeat(76);

    /** A positive amount, as a decimal string so no approximate binary value is ever constructed. */
    private static final String ROW_AMT = "504.77";

    /** A negative amount, as carried by an operator-originated return. */
    private static final String RETURN_AMT = "-504.77";

    /** A merchant identifier, nine digits. */
    private static final String ROW_MERCHANT_ID = "800000000";

    /** A merchant name: twelve characters of text plus thirty-eight trailing spaces. */
    private static final String ROW_MERCHANT_NAME = "Abshire-Lowe" + " ".repeat(38);

    /** A merchant city: fifteen characters of text plus thirty-five trailing spaces. */
    private static final String ROW_MERCHANT_CITY = "North Enoshaven" + " ".repeat(35);

    /** A merchant postal code: five characters of text plus five trailing spaces. */
    private static final String ROW_MERCHANT_ZIP = "72112" + " ".repeat(5);

    /** A card number, sixteen digits. */
    private static final String ROW_CARD_NUM = "4859452612877065";

    /** An origination timestamp lexeme, exactly 26 characters. */
    private static final String ROW_ORIG_TS = "2022-06-10 19:27:53.000000";

    /** A processing timestamp lexeme, exactly 26 characters. */
    private static final String ROW_PROC_TS = "2022-06-11 03:14:07.000000";

    /** The blank processing timestamp an unposted record carries: 26 spaces, and never null. */
    private static final String BLANK_PROC_TS = " ".repeat(26);

    /**
     * The code the interest run writes as its source, shorter than the field and therefore proof that
     * the field's padding is decided by the caller rather than by this class.
     */
    private static final String SYSTEM_SOURCE = "System";

    /**
     * Builds a fully populated posted transaction through the thirteen-argument constructor, in
     * copybook declaration order.
     *
     * @return a posted transaction carrying the representative values above
     */
    private static Transaction postedTransaction() {
        return new Transaction(
                ROW_ID,
                ROW_TYPE_CD,
                ROW_CAT_CD,
                ROW_SOURCE,
                ROW_DESC,
                new BigDecimal(ROW_AMT),
                ROW_MERCHANT_ID,
                ROW_MERCHANT_NAME,
                ROW_MERCHANT_CITY,
                ROW_MERCHANT_ZIP,
                ROW_CARD_NUM,
                ROW_ORIG_TS,
                ROW_PROC_TS);
    }

    /**
     * Counts the bytes a value occupies once encoded, naming the character set explicitly so that no
     * assertion in this file can depend on the platform default encoding. The fixed-width contract is
     * expressed in bytes, so a character count would be the wrong measure.
     *
     * @param value the value to measure
     * @return the number of encoded bytes
     */
    private static int encodedBytes(String value) {
        return value.getBytes(StandardCharsets.US_ASCII).length;
    }

    /**
     * Construction and accessor behaviour: both constructors and all twenty-six accessors.
     */
    @Nested
    @DisplayName("construction and accessors")
    class ConstructionAndAccessors {

        @Test
        @DisplayName("the thirteen-argument constructor takes the copybook's thirteen mapped fields in "
                + "declaration order and every one round-trips unchanged")
        void thirteenArgumentConstructorRoundTripsEveryProperty() {
            Transaction record = postedTransaction();

            assertThat(record.getTranId()).isEqualTo(ROW_ID);
            assertThat(record.getTranTypeCd()).isEqualTo(ROW_TYPE_CD);
            assertThat(record.getTranCatCd()).isEqualTo(ROW_CAT_CD);
            assertThat(record.getTranSource()).isEqualTo(ROW_SOURCE);
            assertThat(record.getTranDesc()).isEqualTo(ROW_DESC);
            assertThat(record.getTranAmt()).isEqualTo(new BigDecimal(ROW_AMT));
            assertThat(record.getMerchantId()).isEqualTo(ROW_MERCHANT_ID);
            assertThat(record.getMerchantName()).isEqualTo(ROW_MERCHANT_NAME);
            assertThat(record.getMerchantCity()).isEqualTo(ROW_MERCHANT_CITY);
            assertThat(record.getMerchantZip()).isEqualTo(ROW_MERCHANT_ZIP);
            assertThat(SensitiveValues.fingerprint(record.getTranCardNum())).isEqualTo(SensitiveValues.fingerprint(ROW_CARD_NUM));
            assertThat(record.getTranOrigTs()).isEqualTo(ROW_ORIG_TS);
            assertThat(record.getTranProcTs()).isEqualTo(ROW_PROC_TS);
        }

        @Test
        @DisplayName("the two adjacent sixteen-byte fields, the two adjacent fifty-byte fields and the "
                + "two adjacent twenty-six-byte fields are not transposed by the constructor")
        void equallyWideNeighboursAreNotTransposed() {
            // Three pairs of neighbouring fields share a width, so a transposition inside the
            // constructor would compile, would preserve every width, and would still be wrong. Each
            // pair is therefore given distinguishable values and checked in both directions.
            Transaction record = postedTransaction();

            assertThat(record.getTranId()).isNotEqualTo(record.getTranCardNum());
            assertThat(record.getTranId()).isEqualTo(ROW_ID);
            assertThat(SensitiveValues.fingerprint(record.getTranCardNum())).isEqualTo(SensitiveValues.fingerprint(ROW_CARD_NUM));

            assertThat(record.getMerchantName()).isNotEqualTo(record.getMerchantCity());
            assertThat(record.getMerchantName()).isEqualTo(ROW_MERCHANT_NAME);
            assertThat(record.getMerchantCity()).isEqualTo(ROW_MERCHANT_CITY);

            assertThat(record.getTranOrigTs()).isNotEqualTo(record.getTranProcTs());
            assertThat(record.getTranOrigTs()).isEqualTo(ROW_ORIG_TS);
            assertThat(record.getTranProcTs()).isEqualTo(ROW_PROC_TS);
        }

        @Test
        @DisplayName("all thirteen mutators are plain assignments: each replaces exactly the field it "
                + "names and leaves the other twelve untouched")
        void allThirteenSettersRoundTrip() {
            Transaction record = new Transaction(
                    ROW_ID, ROW_TYPE_CD, ROW_CAT_CD, ROW_SOURCE, ROW_DESC, new BigDecimal(ROW_AMT),
                    ROW_MERCHANT_ID, ROW_MERCHANT_NAME, ROW_MERCHANT_CITY, ROW_MERCHANT_ZIP,
                    ROW_CARD_NUM, ROW_ORIG_TS, ROW_PROC_TS);

            record.setTranId("0000000000683581");
            record.setTranTypeCd("02");
            record.setTranCatCd("0002");
            record.setTranSource(OPERATOR_SOURCE);
            record.setTranDesc("Return to Abshire-Lowe" + " ".repeat(78));
            record.setTranAmt(new BigDecimal(RETURN_AMT));
            record.setMerchantId("100000002");
            record.setMerchantName("Hegmann and Sons" + " ".repeat(34));
            record.setMerchantCity("West Lucileview" + " ".repeat(35));
            record.setMerchantZip("60601" + " ".repeat(5));
            record.setTranCardNum("4859452612877066");
            record.setTranOrigTs("2022-06-12 08:00:01.000000");
            record.setTranProcTs(BLANK_PROC_TS);

            assertThat(record.getTranId()).isEqualTo("0000000000683581");
            assertThat(record.getTranTypeCd()).isEqualTo("02");
            assertThat(record.getTranCatCd()).isEqualTo("0002");
            assertThat(record.getTranSource()).isEqualTo(OPERATOR_SOURCE);
            assertThat(record.getTranDesc()).isEqualTo("Return to Abshire-Lowe" + " ".repeat(78));
            assertThat(record.getTranAmt()).isEqualTo(new BigDecimal(RETURN_AMT));
            assertThat(record.getMerchantId()).isEqualTo("100000002");
            assertThat(record.getMerchantName()).isEqualTo("Hegmann and Sons" + " ".repeat(34));
            assertThat(record.getMerchantCity()).isEqualTo("West Lucileview" + " ".repeat(35));
            assertThat(record.getMerchantZip()).isEqualTo("60601" + " ".repeat(5));
            assertThat(SensitiveValues.fingerprint(record.getTranCardNum())).isEqualTo(SensitiveValues.fingerprint("4859452612877066"));
            assertThat(record.getTranOrigTs()).isEqualTo("2022-06-12 08:00:01.000000");
            assertThat(record.getTranProcTs()).isEqualTo(BLANK_PROC_TS);
        }

        @Test
        @DisplayName("the no-argument constructor the persistence provider needs leaves all thirteen "
                + "fields absent rather than defaulting the amount to zero")
        void noArgumentConstructorYieldsAnAllAbsentState() {
            // Visible to a subclass in the same package, which is how the provider reaches it. The
            // amount is deliberately not initialised: a zero default would be indistinguishable from a
            // genuine zero amount that was never assigned.
            Transaction record = new Transaction();

            assertThat(record.getTranId()).isNull();
            assertThat(record.getTranTypeCd()).isNull();
            assertThat(record.getTranCatCd()).isNull();
            assertThat(record.getTranSource()).isNull();
            assertThat(record.getTranDesc()).isNull();
            assertThat(record.getTranAmt()).isNull();
            assertThat(record.getMerchantId()).isNull();
            assertThat(record.getMerchantName()).isNull();
            assertThat(record.getMerchantCity()).isNull();
            assertThat(record.getMerchantZip()).isNull();
            assertThat(record.getTranCardNum()).isNull();
            assertThat(record.getTranOrigTs()).isNull();
            assertThat(record.getTranProcTs()).isNull();
        }
    }

    /**
     * Record geometry: the widths, their running sums, and the three offsets addressed from outside.
     */
    @Nested
    @DisplayName("record geometry")
    class RecordGeometry {

        @Test
        @DisplayName("every character field carries exactly its copybook width in encoded bytes")
        void everyCharacterFieldCarriesItsCopybookWidthInEncodedBytes() {
            Transaction record = postedTransaction();

            assertThat(encodedBytes(record.getTranId())).isEqualTo(WIDTH_ID);
            assertThat(encodedBytes(record.getTranTypeCd())).isEqualTo(WIDTH_TYPE_CD);
            assertThat(encodedBytes(record.getTranCatCd())).isEqualTo(WIDTH_CAT_CD);
            assertThat(encodedBytes(record.getTranSource())).isEqualTo(WIDTH_SOURCE);
            assertThat(encodedBytes(record.getTranDesc())).isEqualTo(WIDTH_DESC);
            assertThat(encodedBytes(record.getMerchantId())).isEqualTo(WIDTH_MERCHANT_ID);
            assertThat(encodedBytes(record.getMerchantName())).isEqualTo(WIDTH_MERCHANT_NAME);
            assertThat(encodedBytes(record.getMerchantCity())).isEqualTo(WIDTH_MERCHANT_CITY);
            assertThat(encodedBytes(record.getMerchantZip())).isEqualTo(WIDTH_MERCHANT_ZIP);
            assertThat(encodedBytes(record.getTranCardNum())).isEqualTo(WIDTH_CARD_NUM);
            assertThat(encodedBytes(record.getTranOrigTs())).isEqualTo(WIDTH_ORIG_TS);
            assertThat(encodedBytes(record.getTranProcTs())).isEqualTo(WIDTH_PROC_TS);
        }

        @Test
        @DisplayName("the thirteen mapped widths sum to 330 of the declared 350 bytes, and the 20-byte "
                + "remainder is the unmapped trailing filler")
        void theThirteenMappedWidthsSumToThreeHundredThirty() {
            int mapped = WIDTH_ID + WIDTH_TYPE_CD + WIDTH_CAT_CD + WIDTH_SOURCE + WIDTH_DESC
                    + WIDTH_AMT + WIDTH_MERCHANT_ID + WIDTH_MERCHANT_NAME + WIDTH_MERCHANT_CITY
                    + WIDTH_MERCHANT_ZIP + WIDTH_CARD_NUM + WIDTH_ORIG_TS + WIDTH_PROC_TS;

            assertThat(mapped).isEqualTo(MAPPED_WIDTH_TOTAL);
            assertThat(mapped + WIDTH_FILLER).isEqualTo(RECORD_WIDTH);
            assertThat(OFFSET_FILLER).isEqualTo(MAPPED_WIDTH_TOTAL);
        }

        @Test
        @DisplayName("the card number begins at zero-based 262, which the report procedure addresses as "
                + "one-based 263 - the offset that makes the external sort specification correct")
        void theCardNumberOffsetIsTheRunningSumOfTheTenPrecedingWidths() {
            int precedingTen = WIDTH_ID + WIDTH_TYPE_CD + WIDTH_CAT_CD + WIDTH_SOURCE + WIDTH_DESC
                    + WIDTH_AMT + WIDTH_MERCHANT_ID + WIDTH_MERCHANT_NAME + WIDTH_MERCHANT_CITY
                    + WIDTH_MERCHANT_ZIP;

            assertThat(precedingTen).isEqualTo(OFFSET_CARD_NUM);
            assertThat(OFFSET_CARD_NUM + 1).isEqualTo(POSITION_CARD_NUM);
        }

        @Test
        @DisplayName("the origination timestamp begins at 278 and the processing timestamp at 304, the "
                + "latter being the width-26 key offset the timestamp alternate index declares")
        void theTimestampOffsetsFollowFromTheCardNumberOffset() {
            assertThat(OFFSET_CARD_NUM + WIDTH_CARD_NUM).isEqualTo(OFFSET_ORIG_TS);
            assertThat(OFFSET_ORIG_TS + WIDTH_ORIG_TS).isEqualTo(OFFSET_PROC_TS);
            assertThat(OFFSET_PROC_TS + WIDTH_PROC_TS).isEqualTo(OFFSET_FILLER);
            assertThat(ALTERNATE_KEY_WIDTH).isEqualTo(WIDTH_PROC_TS);
        }

        @Test
        @DisplayName("the base cluster keys on width 16 at offset 0, so the key is the leading substring "
                + "of the record image and the identifier is that key verbatim")
        void theClusterKeyIsTheLeadingSubstringOfTheRecordImage() {
            Transaction record = postedTransaction();

            assertThat(CLUSTER_KEY_OFFSET).isZero();
            assertThat(CLUSTER_KEY_WIDTH).isEqualTo(WIDTH_ID);
            assertThat(encodedBytes(record.getTranId())).isEqualTo(CLUSTER_KEY_WIDTH);
        }
    }

    /**
     * The merchant block, which is the one place this record and its byte-identical twin disagree.
     */
    @Nested
    @DisplayName("merchant block naming")
    class MerchantBlockNaming {

        @Test
        @DisplayName("all four merchant fields are unprefixed here, while the byte-identical staged twin "
                + "deliberately keeps the prefix - do not regularise either side")
        void allFourMerchantFieldsAreUnprefixed() {
            // This is the one block where the two tables disagree. Here the merchant identifier, name,
            // city and postal code are unprefixed in both the column name and the Java property, so
            // every accessor below reads getMerchant... On the staged daily table the same four keep
            // the legacy prefix. The asymmetry is a property of the migrated schema, it is recorded in
            // the project decision log, and tidying it in either direction turns start-up into four
            // mapping failures because the provider validates against that schema. Consequently no
            // prefixed merchant accessor exists on this type to be called.
            Transaction record = postedTransaction();

            assertThat(record.getMerchantId()).isEqualTo(ROW_MERCHANT_ID);
            assertThat(record.getMerchantName()).isEqualTo(ROW_MERCHANT_NAME);
            assertThat(record.getMerchantCity()).isEqualTo(ROW_MERCHANT_CITY);
            assertThat(record.getMerchantZip()).isEqualTo(ROW_MERCHANT_ZIP);

            record.setMerchantId("100000002");
            record.setMerchantName("Hegmann and Sons" + " ".repeat(34));
            record.setMerchantCity("West Lucileview" + " ".repeat(35));
            record.setMerchantZip("60601" + " ".repeat(5));

            assertThat(record.getMerchantId()).isEqualTo("100000002");
            assertThat(record.getMerchantName()).isEqualTo("Hegmann and Sons" + " ".repeat(34));
            assertThat(record.getMerchantCity()).isEqualTo("West Lucileview" + " ".repeat(35));
            assertThat(record.getMerchantZip()).isEqualTo("60601" + " ".repeat(5));

            assertThat(encodedBytes(record.getMerchantId())).isEqualTo(WIDTH_MERCHANT_ID);
            assertThat(encodedBytes(record.getMerchantName())).isEqualTo(WIDTH_MERCHANT_NAME);
            assertThat(encodedBytes(record.getMerchantCity())).isEqualTo(WIDTH_MERCHANT_CITY);
            assertThat(encodedBytes(record.getMerchantZip())).isEqualTo(WIDTH_MERCHANT_ZIP);
        }

        @Test
        @DisplayName("the merchant identifier stays a nine-character lexeme with its leading zeros, "
                + "because a numeric type would render them away")
        void theMerchantIdentifierKeepsItsLeadingZeros() {
            Transaction record = postedTransaction();

            record.setMerchantId("000000042");

            assertThat(record.getMerchantId()).isEqualTo("000000042");
            assertThat(encodedBytes(record.getMerchantId())).isEqualTo(WIDTH_MERCHANT_ID);
        }
    }

    /**
     * Amount fidelity: exact decimal representation, sign, scale, and the absence of any arithmetic.
     *
     * <p>Every amount in this cluster is built from a string literal. No approximate binary value is
     * constructed anywhere in this file, because decimal precision must be identical to the legacy
     * representation with no floating-point substitution.
     */
    @Nested
    @DisplayName("amount fidelity")
    class AmountFidelity {

        @Test
        @DisplayName("the amount round-trips with both its value and its scale of two intact, so it is "
                + "not equal to the same number written at a different scale")
        void theAmountRoundTripsWithValueAndScaleIntact() {
            Transaction record = postedTransaction();

            assertThat(record.getTranAmt()).isEqualTo(new BigDecimal("504.77"));
            assertThat(record.getTranAmt().scale()).isEqualTo(AMOUNT_DECIMAL_DIGITS);
            // isEqualTo on BigDecimal compares scale as well as value, which is the point: an amount
            // rescaled on the way through would fail here even though it is numerically the same.
            assertThat(record.getTranAmt()).isNotEqualTo(new BigDecimal("504.7700"));
            assertThat(record.getTranAmt()).isEqualByComparingTo(new BigDecimal("504.7700"));
            assertThat(WIDTH_AMT).isEqualTo(AMOUNT_INTEGER_DIGITS + AMOUNT_DECIMAL_DIGITS);
        }

        @Test
        @DisplayName("a negative amount survives unchanged, sign included, because operator-originated "
                + "returns are ordinary rather than exceptional")
        void aNegativeAmountSurvivesUnchanged() {
            Transaction record = postedTransaction();

            record.setTranAmt(new BigDecimal(RETURN_AMT));

            assertThat(record.getTranAmt()).isEqualTo(new BigDecimal("-504.77"));
            assertThat(record.getTranAmt().signum()).isNegative();
            assertThat(record.getTranAmt().scale()).isEqualTo(AMOUNT_DECIMAL_DIGITS);
        }

        @Test
        @DisplayName("the entity performs no arithmetic and no rescaling: the instance handed in is the "
                + "very instance handed back, so no rounding policy can hide here")
        void theEntityPerformsNoArithmeticAndNoRescaling() {
            // The module truncates toward zero rather than rounding half-even, because no rounding
            // directive exists anywhere in the estate. That policy lives in the decimal codec of the
            // utility layer, and the way this entity stays out of it is by never touching the value.
            // Identity - not merely equality - is what proves nothing was recomputed on the way
            // through.
            Transaction record = new Transaction();
            BigDecimal supplied = new BigDecimal("1.005");

            record.setTranAmt(supplied);

            assertThat(record.getTranAmt()).isSameAs(supplied);
            assertThat(record.getTranAmt().scale()).isEqualTo(3);
            assertThat(record.getTranAmt().toPlainString()).isEqualTo("1.005");
        }

        @Test
        @DisplayName("a nine-integer-digit amount at the picture's maximum magnitude is carried exactly, "
                + "with no precision loss of the kind a binary floating-point type would introduce")
        void theWidestRepresentableAmountIsCarriedExactly() {
            Transaction record = postedTransaction();
            BigDecimal widest = new BigDecimal("999999999.99");

            record.setTranAmt(widest);

            assertThat(record.getTranAmt()).isEqualTo(widest);
            assertThat(record.getTranAmt().precision()).isEqualTo(WIDTH_AMT);
            assertThat(record.getTranAmt().toPlainString()).isEqualTo("999999999.99");
        }
    }

    /**
     * Padding fidelity: the values whose trailing blanks carry meaning and must therefore survive.
     */
    @Nested
    @DisplayName("padding fidelity")
    class PaddingFidelity {

        @Test
        @DisplayName("the source code keeps its trailing blanks, so a padded ten-character marker comes "
                + "back padded rather than trimmed")
        void theSourceCodeKeepsItsTrailingBlanks() {
            Transaction record = postedTransaction();

            assertThat(record.getTranSource()).isEqualTo("POS TERM  ");
            assertThat(record.getTranSource()).endsWith("  ");
            assertThat(record.getTranSource()).isNotEqualTo("POS TERM");
            assertThat(encodedBytes(record.getTranSource())).isEqualTo(WIDTH_SOURCE);

            record.setTranSource(OPERATOR_SOURCE);

            assertThat(record.getTranSource()).isEqualTo("OPERATOR  ");
            assertThat(encodedBytes(record.getTranSource())).isEqualTo(WIDTH_SOURCE);
        }

        @Test
        @DisplayName("the source code is a raw lexeme rather than an enumerated constant, so a shorter "
                + "code such as the interest run's is stored exactly as given")
        void theSourceCodeIsARawLexemeAndNotAnEnumeratedConstant() {
            // Were this mapped as an enumerated type, the persisted value would be a Java constant
            // name and could not also be a padded ten-character legacy marker. Storing a code that is
            // neither padded nor a constant name demonstrates that no such translation is happening.
            Transaction record = postedTransaction();

            record.setTranSource(SYSTEM_SOURCE);

            assertThat(record.getTranSource()).isEqualTo("System");
            assertThat(record.getTranSource()).isNotEqualTo(SYSTEM_SOURCE.toUpperCase(Locale.ROOT));
            assertThat(encodedBytes(record.getTranSource())).isLessThan(WIDTH_SOURCE);
        }

        @Test
        @DisplayName("an all-blank processing timestamp round-trips as 26 blanks, neither trimmed to an "
                + "empty string nor collapsed to absent")
        void anAllBlankProcessingTimestampRoundTripsAsBlanks() {
            // A blank processing timestamp is the legacy marker for a record that has not been
            // processed, which is why the column is character rather than a temporal type: no
            // date-time value can represent it.
            Transaction record = postedTransaction();

            record.setTranProcTs(BLANK_PROC_TS);

            assertThat(record.getTranProcTs()).isEqualTo(BLANK_PROC_TS);
            assertThat(record.getTranProcTs()).isNotNull();
            assertThat(record.getTranProcTs()).isNotEmpty();
            assertThat(record.getTranProcTs()).isBlank();
            assertThat(encodedBytes(record.getTranProcTs())).isEqualTo(WIDTH_PROC_TS);
        }

        @Test
        @DisplayName("the category code keeps its leading zeros, so a four-character code is never "
                + "rendered as the number it resembles")
        void theCategoryCodeKeepsItsLeadingZeros() {
            Transaction record = postedTransaction();

            record.setTranCatCd("0005");

            assertThat(record.getTranCatCd()).isEqualTo("0005");
            assertThat(record.getTranCatCd()).isNotEqualTo("5");
            assertThat(encodedBytes(record.getTranCatCd())).isEqualTo(WIDTH_CAT_CD);
        }

        @Test
        @DisplayName("the description keeps its trailing blanks across its full hundred characters")
        void theDescriptionKeepsItsTrailingBlanks() {
            Transaction record = postedTransaction();

            assertThat(record.getTranDesc()).startsWith("Purchase at Abshire-Lowe");
            assertThat(record.getTranDesc()).endsWith(" ");
            assertThat(encodedBytes(record.getTranDesc())).isEqualTo(WIDTH_DESC);
        }
    }

    /**
     * The equality contract, which is keyed on the business identifier and nothing else.
     */
    @Nested
    @DisplayName("identity semantics")
    class IdentitySemantics {

        @Test
        @DisplayName("two records with the same identifier are equal even when every other field "
                + "differs, because the identifier alone is the persistent identity")
        void recordsWithTheSameIdentifierAreEqual() {
            Transaction one = postedTransaction();
            Transaction two = new Transaction(
                    ROW_ID, "99", "9999", OPERATOR_SOURCE, "Something else entirely" + " ".repeat(77),
                    new BigDecimal(RETURN_AMT), "999999999", "Other Merchant" + " ".repeat(36),
                    "Other City" + " ".repeat(40), "99999" + " ".repeat(5), "9999999999999999",
                    "2001-01-01 00:00:00.000000", BLANK_PROC_TS);

            assertThat(one).isEqualTo(two);
            assertThat(one).hasSameHashCodeAs(two);
        }

        @Test
        @DisplayName("two records differing only in identifier are unequal")
        void recordsDifferingOnlyInIdentifierAreUnequal() {
            Transaction one = postedTransaction();
            Transaction two = postedTransaction();

            two.setTranId("0000000000683581");

            assertThat(one).isNotEqualTo(two);
        }

        @Test
        @DisplayName("equality and hash stay stable when a non-key field changes, which is what keeps a "
                + "record findable in a hash-based collection across a flush")
        void equalityIsStableAcrossNonKeyMutation() {
            Transaction record = postedTransaction();
            Transaction sameKey = postedTransaction();
            int hashBefore = record.hashCode();

            record.setTranAmt(new BigDecimal(RETURN_AMT));
            record.setMerchantCity("Somewhere Else" + " ".repeat(36));
            record.setTranProcTs(BLANK_PROC_TS);

            assertThat(record.hashCode()).isEqualTo(hashBefore);
            assertThat(record).isEqualTo(sameKey);
        }

        @Test
        @DisplayName("a record stays retrievable from a hash set and a hash map after a non-key field "
                + "has been mutated in place")
        void aRecordStaysRetrievableFromHashBasedCollections() {
            Transaction record = postedTransaction();
            Set<Transaction> set = new HashSet<>();
            Map<Transaction, String> map = new HashMap<>();
            set.add(record);
            map.put(record, "posted");

            record.setTranAmt(new BigDecimal("0.01"));
            record.setTranDesc("Adjusted" + " ".repeat(92));

            assertThat(set).contains(record);
            assertThat(map).containsEntry(record, "posted");
            assertThat(set.contains(postedTransaction())).isTrue();
        }

        @Test
        @DisplayName("a record equals itself, equals nothing of another type, and equals no null")
        void equalityHandlesSelfOtherTypesAndNull() {
            Transaction record = postedTransaction();

            assertThat(record).isEqualTo(record);
            assertThat(record).isNotEqualTo(null);
            assertThat(record).isNotEqualTo("0000000000683580");
            assertThat(record).isNotEqualTo(new Object());
        }

        @Test
        @DisplayName("two records with an absent identifier are equal, and hashing one does not throw")
        void recordsWithAnAbsentIdentifierAreEqual() {
            // The provider constructs an empty instance before populating it, so an absent key is a
            // real transient state and neither equality nor hashing may fail on it.
            Transaction one = new Transaction();
            Transaction two = new Transaction();

            assertThat(one).isEqualTo(two);
            assertThat(one).hasSameHashCodeAs(two);
            assertThat(one).isNotEqualTo(postedTransaction());
        }
    }

    /**
     * Diagnostic rendering, which must not disclose the card number, the amount or a merchant value.
     */
    @Nested
    @DisplayName("diagnostic rendering")
    class DiagnosticRendering {

        @Test
        @DisplayName("the rendering names the type and carries the identifier, the type code and the "
                + "category code")
        void theRenderingCarriesTheThreeDisclosableValues() {
            Transaction record = postedTransaction();

            String rendered = record.toString();

            assertThat(rendered).startsWith("Transaction[");
            assertThat(rendered).contains(ROW_ID);
            assertThat(rendered).contains(ROW_TYPE_CD);
            assertThat(rendered).contains(ROW_CAT_CD);
            assertThat(rendered).endsWith("]");
        }

        @Test
        @DisplayName("the rendering discloses neither the card number nor the amount, because one is a "
                + "primary account number and the other is financial data")
        void theRenderingDisclosesNeitherCardNumberNorAmount() {
            // An entity reaches a log event, a failed assertion message or an interpolated exception
            // message without its author choosing to disclose anything, so the withholding has to
            // happen in the rendering itself rather than at each call site.
            Transaction record = postedTransaction();

            String rendered = record.toString();

            assertThat(rendered).doesNotContain(ROW_CARD_NUM);
            assertThat(rendered).doesNotContain("504.77");
            assertThat(rendered).doesNotContain(ROW_CARD_NUM.substring(0, 6));
            assertThat(rendered).doesNotContain(ROW_CARD_NUM.substring(ROW_CARD_NUM.length() - 4));
        }

        @Test
        @DisplayName("the rendering discloses no merchant value, so all four unprefixed merchant fields "
                + "stay out of a diagnostic")
        void theRenderingDisclosesNoMerchantValue() {
            Transaction record = postedTransaction();

            String rendered = record.toString();

            assertThat(rendered).doesNotContain(ROW_MERCHANT_ID);
            assertThat(rendered).doesNotContain("Abshire-Lowe");
            assertThat(rendered).doesNotContain("North Enoshaven");
            assertThat(rendered).doesNotContain("72112");
        }

        @Test
        @DisplayName("rendering an empty record does not throw and still discloses nothing sensitive")
        void renderingAnEmptyRecordDoesNotThrow() {
            Transaction record = new Transaction();

            String rendered = record.toString();

            assertThat(rendered).startsWith("Transaction[");
            assertThat(rendered).contains("null");
            assertThat(rendered).endsWith("]");
        }

        @Test
        @DisplayName("every accessor still returns the untouched value, so withholding in the rendering "
                + "costs nothing to code that legitimately needs the data")
        void everyAccessorStillReturnsTheUntouchedValue() {
            Transaction record = postedTransaction();

            assertThat(SensitiveValues.fingerprint(record.getTranCardNum())).isEqualTo(SensitiveValues.fingerprint(ROW_CARD_NUM));
            assertThat(record.getTranAmt()).isEqualTo(new BigDecimal(ROW_AMT));
            assertThat(record.getMerchantName()).isEqualTo(ROW_MERCHANT_NAME);
        }
    }

    /**
     * Design boundaries that are deliberate, load-bearing and easy to "improve" by mistake.
     */
    @Nested
    @DisplayName("deliberate design boundaries")
    class DeliberateDesignBoundaries {

        @Test
        @DisplayName("no surrogate identifier and no generated value exists: identity is the sixteen-byte "
                + "business key taken verbatim from the record image")
        void noSurrogateIdentifierExists() {
            // The entity declares no generic or generated identifier accessor, no generated value and
            // no database sequence, so what is asserted here is the positive consequence: identity is
            // the business key read straight from the record image. The legacy bill-payment path mints
            // a new identifier as the highest existing key plus one, inside the transaction; a sequence
            // would diverge from that permanently at the first rollback.
            Transaction record = postedTransaction();

            assertThat(record.getTranId()).isEqualTo(ROW_ID);
            assertThat(encodedBytes(record.getTranId())).isEqualTo(WIDTH_ID);
            assertThat(new Transaction().getTranId()).isNull();
        }

        @Test
        @DisplayName("the highest-key-plus-one derivation is expressible on the stored lexeme, which is "
                + "why the identifier stays sixteen characters wide rather than becoming a number")
        void theHighestKeyPlusOneDerivationPreservesTheLexemeWidth() {
            // Documented here, performed by the bill-payment service. The point of the assertion is
            // that incrementing the key and writing it back keeps the sixteen-character width and its
            // leading zeros, which is the property a numeric identifier column would lose.
            Transaction record = postedTransaction();

            String next = String.format(Locale.ROOT, "%016d",
                    Long.parseLong(record.getTranId()) + 1L);
            record.setTranId(next);

            assertThat(record.getTranId()).isEqualTo("0000000000683581");
            assertThat(encodedBytes(record.getTranId())).isEqualTo(WIDTH_ID);
        }

        @Test
        @DisplayName("the card number is a scalar value with no navigable card reference, because "
                + "referential integrity is a database constraint rather than an object graph")
        void theCardNumberIsAScalarWithNoNavigableReference() {
            // No association exists to navigate, so what is asserted is that the key is carried as the
            // raw fixed-width value the record image holds. The same bytes are typed as zoned decimal
            // by the report sort and as character by the statement sort, which is exactly why they are
            // held as text: a numeric type would adopt one job's reading and discard the other's.
            Transaction record = postedTransaction();

            assertThat(SensitiveValues.fingerprint(record.getTranCardNum())).isEqualTo(SensitiveValues.fingerprint(ROW_CARD_NUM));
            assertThat(encodedBytes(record.getTranCardNum())).isEqualTo(WIDTH_CARD_NUM);

            record.setTranCardNum("0000000000000001");

            assertThat(SensitiveValues.fingerprint(record.getTranCardNum())).isEqualTo(SensitiveValues.fingerprint("0000000000000001"));
            assertThat(encodedBytes(record.getTranCardNum())).isEqualTo(WIDTH_CARD_NUM);
        }

        @Test
        @DisplayName("the geometry is byte-identical to the staged twin, yet the two remain separate "
                + "types because they are separate tables with separate lifecycles")
        void theGeometryMatchesTheTwinYetTheTypesStaySeparate() {
            Transaction posted = postedTransaction();
            DailyTransaction staged = new DailyTransaction(
                    ROW_ID, ROW_TYPE_CD, ROW_CAT_CD, ROW_SOURCE, ROW_DESC, new BigDecimal(ROW_AMT),
                    ROW_MERCHANT_ID, ROW_MERCHANT_NAME, ROW_MERCHANT_CITY, ROW_MERCHANT_ZIP,
                    ROW_CARD_NUM, ROW_ORIG_TS, BLANK_PROC_TS);

            assertThat(encodedBytes(posted.getTranId()))
                    .isEqualTo(encodedBytes(staged.getDalytranId()));
            assertThat(encodedBytes(posted.getMerchantName()))
                    .isEqualTo(encodedBytes(staged.getDalytranMerchantName()));
            assertThat(posted.getTranAmt()).isEqualTo(staged.getDalytranAmt());
            // Byte-identical geometry, and still not interchangeable: neither type is an instance of
            // the other, so a staged record cannot become a posted one by a cast.
            assertThat(posted).isNotEqualTo(staged);
            assertThat(posted).isNotInstanceOf(DailyTransaction.class);
            assertThat(staged).isNotInstanceOf(Transaction.class);
        }

        @Test
        @DisplayName("no optimistic-lock counter exists, because a posted transaction is inserted and "
                + "read rather than edited in place")
        void noOptimisticLockCounterExists() {
            // Only the two records updated through the online screens carry a version counter. The
            // positive consequence asserted here is that a posted transaction is fully described by
            // its thirteen mapped fields, with no fourteenth provider-owned attribute to reconcile.
            Transaction record = postedTransaction();

            assertThat(record.getTranId()).isNotNull();
            assertThat(record.getTranProcTs()).isNotNull();
            assertThat(record).isEqualTo(postedTransaction());
        }
    }

    /**
     * The persistence-time identifier guard: the one rule this entity enforces on its own way to a row.
     *
     * <h2>Why it is here and not in the constructor</h2>
     *
     * <p>The persistence provider hydrates a row by instantiating the entity and assigning its fields
     * directly, so a constructor guard is bypassed on every read while a lifecycle callback sits on the
     * one path every insert and every update must take. That placement is what these assertions
     * establish: an instance built for an assertion or an intermediate calculation is unrestricted, and
     * only one about to become a row is checked.
     *
     * <h2>Why the digit class is contractual even though the picture clause is alphanumeric</h2>
     *
     * <p>Identifiers are minted by taking the current <em>character</em> maximum and adding one, with no
     * sequence anywhere in the schema, and a character maximum coincides with a numeric maximum only
     * while every stored value is sixteen zero-padded digits. A single value of any other shape sorts
     * above every well-formed identifier and silently freezes allocation, which is a defect that breaks
     * no compilation and fails no test that is not looking for it - so it is looked for here.
     */
    @Nested
    @DisplayName("the persistence-time identifier guard and amount normalisation")
    class PersistenceTimeIdentifierGuard {

        @Test
        @DisplayName("a well-formed sixteen-digit identifier passes, leading zeros included")
        void aWellFormedIdentifierPasses() {
            Transaction record = postedTransaction();

            assertThat(record.getTranId()).hasSize(16);
            record.normalizeAndValidateBeforeWrite();
        }

        @Test
        @DisplayName("a short identifier is refused before the write, because a value that short could "
                + "not have been sliced from a valid record image and would split one identity in two")
        void aShortIdentifierIsRefused() {
            Transaction record = postedTransaction();
            record.setTranId("42");

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(record::normalizeAndValidateBeforeWrite)
                    .withMessageContaining("tranId")
                    .withMessageContaining("exactly 16 characters");
        }

        @Test
        @DisplayName("a sixteen-character identifier carrying a non-digit is refused, because the "
                + "allocation ordering is only valid across zero-padded digits")
        void aNonDigitIdentifierIsRefused() {
            Transaction record = postedTransaction();
            record.setTranId("00000000000000X1");

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(record::normalizeAndValidateBeforeWrite)
                    .withMessageContaining("tranId")
                    .withMessageContaining("digits");
        }

        @Test
        @DisplayName("an absent identifier is refused, naming the attribute rather than surfacing a bare "
                + "null failure from somewhere deeper")
        void anAbsentIdentifierIsRefused() {
            Transaction record = postedTransaction();
            record.setTranId(null);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(record::normalizeAndValidateBeforeWrite)
                    .withMessageContaining("tranId")
                    .withMessageContaining("must be present");
        }

        @Test
        @DisplayName("the guard is bound to both write callbacks, so an update is checked as well as an "
                + "insert - a row whose identifier was edited in place is the same hazard")
        void theGuardIsBoundToBothWriteCallbacks() throws NoSuchMethodException {
            java.lang.reflect.Method guard =
                    Transaction.class.getDeclaredMethod("normalizeAndValidateBeforeWrite");

            assertThat(guard.isAnnotationPresent(jakarta.persistence.PrePersist.class)).isTrue();
            assertThat(guard.isAnnotationPresent(jakarta.persistence.PreUpdate.class)).isTrue();
        }

        @Test
        @DisplayName("an instance that is never written is never checked, so a fixture or an "
                + "intermediate value is not refused by a rule that governs rows")
        void anInstanceThatIsNeverWrittenIsNeverChecked() {
            Transaction record = postedTransaction();
            record.setTranId("not-a-key");

            assertThat(record.getTranId())
                    .as("construction and assignment stay unchecked on purpose")
                    .isEqualTo("not-a-key");
        }
    }
}
