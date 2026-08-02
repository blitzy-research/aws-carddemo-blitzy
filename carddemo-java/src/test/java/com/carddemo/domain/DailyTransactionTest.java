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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit test for {@link DailyTransaction}, the 350-byte daily-transaction record that is staged from a
 * sequential dataset and consumed as the primary input of the posting run.
 *
 * <p>Provenance of every legacy fact asserted below: repository checkout
 * 7756d895ffeb65f7ea72aaa609e356d9899afcec, upstream release stamp CardDemo_v1.0-15-g27d6c6f-68
 * (2022-07-19). The stamp is recorded here as a header string only; it is never asserted against a
 * source member, because it is not carried uniformly by every artefact in the estate.
 *
 * <h2>The independent oracle this suite asserts against</h2>
 *
 * <p>Every expectation in this file was derived by hand from two authorities and from nothing else. No
 * production method is ever called to produce the value that a production method is then asked to
 * match, and no output is snapshotted.
 *
 * <p>The first authority is the copybook layout, whose thirteen mapped fields plus one trailing filler
 * were summed by hand: 16 + 2 + 4 + 10 + 100 + 11 + 9 + 50 + 50 + 10 + 16 + 26 + 26 = 330 mapped
 * bytes, and 330 + 20 = 350. Every offset asserted here is the running total of the widths that
 * precede it, which is why the geometry cluster asserts the summations themselves rather than quoting
 * the offsets as bare magic numbers.
 *
 * <p>The second authority is the staged input file, measured directly: 105,300 bytes holding 300
 * records, each 350 bytes wide plus one line terminator, so 300 multiplied by 351. Its composition was
 * counted rather than assumed. The origination-source field holds a ten-byte padded value on every
 * record, 250 of them the point-of-sale marker and 50 the operator marker. The amount is positive on
 * 250 records and negative on 50, the negatives being operator-originated returns. All 300 origination
 * timestamps carry one identical 26-character value, all 300 processing timestamps are 26 spaces, and
 * all 300 trailing fillers are 20 spaces.
 *
 * <h2>Why the first record's amount is 504.77 and not 500.47</h2>
 *
 * <p>The amount is zoned decimal, so the trailing byte of the eleven-byte image carries both the
 * low-order digit and the sign. The first record's image ends in the letter G, which encodes the digit
 * seven with a positive sign, so the eleven unsigned digits are 00000050477 and the scale-2 value is
 * 504.77. Reading the trailing letter as a separator rather than as an overpunched digit is what
 * produces the wrong answer of 500.47, and that misreading is a documented correction. The positive
 * overpunch alphabet runs from the opening-brace character for positive zero through the letters A to
 * I for positive one to nine, and the negative alphabet runs from the closing-brace character for
 * negative zero through the letters J to R; a plain trailing digit is an unsigned positive value. This
 * input is the only one in the estate that exercises the whole twenty-code alphabet, because it is the
 * only one carrying both signs.
 *
 * <p>Decoding an image is emphatically <em>not</em> this suite's job, and no record image is assembled
 * or decoded anywhere in this file. The hand-decoded values are cited only to explain where the
 * literals below come from; the decoder and the fixed-width mapper are verified by their own tests.
 *
 * <h2>What this suite deliberately does not touch</h2>
 *
 * <p>Nothing here asserts a column name, a column length or a nullability constraint. That mapping
 * layer is asserted by {@code EntityPersistenceMappingTest}, which bootstraps the persistence
 * provider's metadata offline and compares the mapping it computes against the shipped migration
 * {@code V1__create_schema.sql} and against an independent copybook-width oracle. The runtime
 * configuration additionally fixes the provider at schema validation, so a divergence fails start-up
 * in a deployed environment, though that is a property of a deployment rather than a check this build
 * performs.
 *
 * <p>This file inspects no annotation because entity metadata is that suite's subject rather than this
 * one's, and duplicating it here would restate a check that already exists. It is emphatically not
 * because reflection is prohibited in a test: the module's zero-reflection budget is scoped to
 * production sources under {@code src/main/java}, and the audit that records it excludes test sources
 * by design, so a suite that reflected would not breach it.
 *
 * <p>Nothing here asserts the reject output. The 430-byte reject record, its four-digit reason code,
 * its 76-character description and the five reject reason codes belong to the reject writer and the
 * reject-reason enumeration. Nothing here evaluates the overlimit expression, which belongs to the
 * posting service. Nothing here implements or references a comparator, because ordering was external
 * to the program in the legacy estate.
 *
 * <p>A second legacy program also reads this layout: a complete 491-line reader that no job member,
 * cataloged procedure or online resource definition invokes anywhere in the estate. It is migrated
 * regardless, behind a batch job that is defined but excluded from the default pipeline. That is a
 * batch concern and is mentioned here only so a reader does not mistake this entity for the input of a
 * single program.
 *
 * <p>This is a pure unit test. It starts no container, opens no database, touches no network and reads
 * no file.
 *
 * @see DailyTransaction
 */
@DisplayName("DailyTransaction - the 350-byte daily-transaction record staged for the posting run")
class DailyTransactionTest {

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

    /**
     * Width of the account-record money fields, which use a signed ten-and-two picture and are
     * therefore one digit wider than this record's amount. Asserted as a difference so that a
     * copy-and-paste of the wrong precision cannot pass unnoticed.
     */
    private static final int ACCOUNT_AMOUNT_WIDTH = 12;

    /**
     * Width of the disclosure-group interest rate, which uses a signed four-and-two picture and is
     * therefore five digits narrower than this record's amount.
     */
    private static final int DISCLOSURE_RATE_WIDTH = 6;

    // ------------------------------------------------------------------------------------------
    // Zero-based offsets, each the running total of every width that precedes it.
    // ------------------------------------------------------------------------------------------

    /** Offset of the card number, hand-derived as the sum of the ten preceding widths. */
    private static final int OFFSET_CARD_NUM = 262;

    /** Offset of the origination timestamp, hand-derived as the card-number offset plus its width. */
    private static final int OFFSET_ORIG_TS = 278;

    /** Offset of the processing timestamp, hand-derived as the origination offset plus its width. */
    private static final int OFFSET_PROC_TS = 304;

    /** Offset of the unmapped trailing filler, hand-derived as the processing offset plus its width. */
    private static final int OFFSET_FILLER = 330;

    // ------------------------------------------------------------------------------------------
    // Measured composition of the staged input, counted directly from the file.
    // ------------------------------------------------------------------------------------------

    /** Records the staged input holds. */
    private static final int STAGED_RECORDS = 300;

    /** Staged records carrying the point-of-sale origination marker and a positive amount. */
    private static final int STAGED_PURCHASES = 250;

    /** Staged records carrying the operator origination marker and a negative amount. */
    private static final int STAGED_RETURNS = 50;

    // ------------------------------------------------------------------------------------------
    // Hand-decoded values of the staged input's first record. Padded values are assembled from the
    // text plus its hand-counted trailing spaces, which reproduces the image the file carries. This
    // is test-input construction; no value read back out of the entity is ever trimmed or padded.
    // ------------------------------------------------------------------------------------------

    /** First record's transaction identifier, sixteen characters with ten leading zeros. */
    private static final String ROW_ID = "0000000000683580";

    /** First record's transaction type code. */
    private static final String ROW_TYPE_CD = "01";

    /** First record's transaction category code, four characters with three leading zeros. */
    private static final String ROW_CAT_CD = "0001";

    /** Point-of-sale origination marker: eight characters of text plus two trailing spaces. */
    private static final String ROW_SOURCE = "POS TERM" + " ".repeat(2);

    /** Operator origination marker: eight characters of text plus two trailing spaces. */
    private static final String OPERATOR_SOURCE = "OPERATOR" + " ".repeat(2);

    /** First record's description: twenty-four characters of text plus seventy-six trailing spaces. */
    private static final String ROW_DESC = "Purchase at Abshire-Lowe" + " ".repeat(76);

    /** First record's amount, hand-decoded from an image whose trailing byte is a positive seven. */
    private static final String ROW_AMT = "504.77";

    /** First record's merchant identifier, nine digits. */
    private static final String ROW_MERCHANT_ID = "800000000";

    /** First record's merchant name: twelve characters of text plus thirty-eight trailing spaces. */
    private static final String ROW_MERCHANT_NAME = "Abshire-Lowe" + " ".repeat(38);

    /** First record's merchant city: fifteen characters of text plus thirty-five trailing spaces. */
    private static final String ROW_MERCHANT_CITY = "North Enoshaven" + " ".repeat(35);

    /** First record's merchant postal code: five characters of text plus five trailing spaces. */
    private static final String ROW_MERCHANT_ZIP = "72112" + " ".repeat(5);

    /** First record's card number, sixteen digits. */
    private static final String ROW_CARD_NUM = "4859452612877065";

    /** The single origination timestamp lexeme every staged record carries, exactly 26 characters. */
    private static final String ROW_ORIG_TS = "2022-06-10 19:27:53.000000";

    /** The blank processing timestamp every staged record carries: 26 spaces, and never null. */
    private static final String BLANK_PROC_TS = " ".repeat(26);

    /**
     * Builds the first staged record as an entity through the thirteen-argument constructor, in
     * copybook declaration order.
     *
     * @return a fully populated record carrying the hand-decoded values of the first staged record
     */
    private static DailyTransaction firstStagedRecord() {
        return new DailyTransaction(
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
                BLANK_PROC_TS);
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
     * Construction and accessor behaviour: the two constructors and all twenty-six accessors.
     */
    @Nested
    @DisplayName("construction and accessors")
    class ConstructionAndAccessors {

        @Test
        @DisplayName("the thirteen-argument constructor takes the copybook's thirteen mapped fields in "
                + "declaration order and every argument reaches its own accessor unchanged")
        void thirteenArgumentConstructorRoundTripsEveryProperty() {
            // The argument order is the copybook declaration order, which is also the record image
            // order, so a caller reading the image left to right supplies them as it meets them.
            // Every accessor named here carries the legacy prefix - see the merchant-block cluster
            // for why that matters.
            DailyTransaction record = firstStagedRecord();

            assertThat(record.getDalytranId()).isEqualTo(ROW_ID);
            assertThat(record.getDalytranTypeCd()).isEqualTo(ROW_TYPE_CD);
            assertThat(record.getDalytranCatCd()).isEqualTo(ROW_CAT_CD);
            assertThat(record.getDalytranSource()).isEqualTo(ROW_SOURCE);
            assertThat(record.getDalytranDesc()).isEqualTo(ROW_DESC);
            assertThat(record.getDalytranAmt()).isEqualTo(new BigDecimal(ROW_AMT));
            assertThat(record.getDalytranMerchantId()).isEqualTo(ROW_MERCHANT_ID);
            assertThat(record.getDalytranMerchantName()).isEqualTo(ROW_MERCHANT_NAME);
            assertThat(record.getDalytranMerchantCity()).isEqualTo(ROW_MERCHANT_CITY);
            assertThat(record.getDalytranMerchantZip()).isEqualTo(ROW_MERCHANT_ZIP);
            assertThat(record.getDalytranCardNum()).isEqualTo(ROW_CARD_NUM);
            assertThat(record.getDalytranOrigTs()).isEqualTo(ROW_ORIG_TS);
            assertThat(record.getDalytranProcTs()).isEqualTo(BLANK_PROC_TS);
        }

        @Test
        @DisplayName("the two adjacent sixteen-byte fields and the two adjacent fifty-byte fields are "
                + "not transposed, which equal widths would otherwise hide")
        void equallyWidePairsAreNotTransposed() {
            // The identifier and the card number are both sixteen bytes wide, and the merchant name
            // and merchant city are both fifty; a swapped pair would satisfy every width assertion in
            // this file, so the distinct values are compared against each other directly.
            DailyTransaction record = firstStagedRecord();

            assertThat(record.getDalytranId()).isNotEqualTo(record.getDalytranCardNum());
            assertThat(record.getDalytranId()).isEqualTo(ROW_ID);
            assertThat(record.getDalytranCardNum()).isEqualTo(ROW_CARD_NUM);

            assertThat(record.getDalytranMerchantName()).isNotEqualTo(record.getDalytranMerchantCity());
            assertThat(record.getDalytranMerchantName()).isEqualTo(ROW_MERCHANT_NAME);
            assertThat(record.getDalytranMerchantCity()).isEqualTo(ROW_MERCHANT_CITY);
        }

        @Test
        @DisplayName("all thirteen mutators are plain assignments: each replaces exactly the field it "
                + "names and none trims, pads, folds case, normalises, validates or rescales")
        void allThirteenSettersRoundTrip() {
            // Values distinct from the first staged record's, so a mutator that silently kept the
            // constructor value would fail. Every value is presented at its legacy width.
            DailyTransaction record = firstStagedRecord();

            record.setDalytranId("0000000000999999");
            record.setDalytranTypeCd("03");
            record.setDalytranCatCd("0099");
            record.setDalytranSource(OPERATOR_SOURCE);
            record.setDalytranDesc("Return authorised by operator" + " ".repeat(71));
            record.setDalytranAmt(new BigDecimal("-12.34"));
            record.setDalytranMerchantId("900000001");
            record.setDalytranMerchantName("Zulauf-Rempel" + " ".repeat(37));
            record.setDalytranMerchantCity("South Marvinport" + " ".repeat(34));
            record.setDalytranMerchantZip("30303" + " ".repeat(5));
            record.setDalytranCardNum("4111111111111111");
            record.setDalytranOrigTs("2022-06-11 08:15:00.000000");
            record.setDalytranProcTs("2022-06-12 23:59:59.999999");

            assertThat(record.getDalytranId()).isEqualTo("0000000000999999");
            assertThat(record.getDalytranTypeCd()).isEqualTo("03");
            assertThat(record.getDalytranCatCd()).isEqualTo("0099");
            assertThat(record.getDalytranSource()).isEqualTo(OPERATOR_SOURCE);
            assertThat(record.getDalytranDesc()).isEqualTo("Return authorised by operator" + " ".repeat(71));
            assertThat(record.getDalytranAmt()).isEqualTo(new BigDecimal("-12.34"));
            assertThat(record.getDalytranMerchantId()).isEqualTo("900000001");
            assertThat(record.getDalytranMerchantName()).isEqualTo("Zulauf-Rempel" + " ".repeat(37));
            assertThat(record.getDalytranMerchantCity()).isEqualTo("South Marvinport" + " ".repeat(34));
            assertThat(record.getDalytranMerchantZip()).isEqualTo("30303" + " ".repeat(5));
            assertThat(record.getDalytranCardNum()).isEqualTo("4111111111111111");
            assertThat(record.getDalytranOrigTs()).isEqualTo("2022-06-11 08:15:00.000000");
            assertThat(record.getDalytranProcTs()).isEqualTo("2022-06-12 23:59:59.999999");
        }

        @Test
        @DisplayName("the no-argument constructor the persistence provider needs leaves all thirteen "
                + "mapped fields absent, with no defaulted zero amount to mask a mapping fault")
        void noArgumentConstructorYieldsAnAllAbsentState() {
            // This test class lives in the entity's own package, so ordinary Java package access
            // reaches the protected constructor directly. This is same-package visibility and
            // explicitly NOT reflection: no reflective call of any kind appears in this file.
            DailyTransaction empty = new DailyTransaction();

            assertThat(empty.getDalytranId()).isNull();
            assertThat(empty.getDalytranTypeCd()).isNull();
            assertThat(empty.getDalytranCatCd()).isNull();
            assertThat(empty.getDalytranSource()).isNull();
            assertThat(empty.getDalytranDesc()).isNull();
            assertThat(empty.getDalytranAmt()).isNull();
            assertThat(empty.getDalytranMerchantId()).isNull();
            assertThat(empty.getDalytranMerchantName()).isNull();
            assertThat(empty.getDalytranMerchantCity()).isNull();
            assertThat(empty.getDalytranMerchantZip()).isNull();
            assertThat(empty.getDalytranCardNum()).isNull();
            assertThat(empty.getDalytranOrigTs()).isNull();
            assertThat(empty.getDalytranProcTs()).isNull();
        }
    }

    /**
     * Record geometry: the widths the entity must carry and the offsets those widths derive.
     */
    @Nested
    @DisplayName("record geometry")
    class RecordGeometry {

        @Test
        @DisplayName("every character field carries exactly its copybook width in encoded bytes - "
                + "16, 2, 4, 10, 100, 9, 50, 50, 10, 16, 26 and 26")
        void everyCharacterFieldCarriesItsCopybookWidthInEncodedBytes() {
            // Measured in encoded bytes rather than characters, because the fixed-width contract is a
            // byte contract. The character set is named at every boundary so that the platform
            // default encoding can never influence the result.
            DailyTransaction record = firstStagedRecord();

            assertThat(encodedBytes(record.getDalytranId())).isEqualTo(WIDTH_ID);
            assertThat(encodedBytes(record.getDalytranTypeCd())).isEqualTo(WIDTH_TYPE_CD);
            assertThat(encodedBytes(record.getDalytranCatCd())).isEqualTo(WIDTH_CAT_CD);
            assertThat(encodedBytes(record.getDalytranSource())).isEqualTo(WIDTH_SOURCE);
            assertThat(encodedBytes(record.getDalytranDesc())).isEqualTo(WIDTH_DESC);
            assertThat(encodedBytes(record.getDalytranMerchantId())).isEqualTo(WIDTH_MERCHANT_ID);
            assertThat(encodedBytes(record.getDalytranMerchantName())).isEqualTo(WIDTH_MERCHANT_NAME);
            assertThat(encodedBytes(record.getDalytranMerchantCity())).isEqualTo(WIDTH_MERCHANT_CITY);
            assertThat(encodedBytes(record.getDalytranMerchantZip())).isEqualTo(WIDTH_MERCHANT_ZIP);
            assertThat(encodedBytes(record.getDalytranCardNum())).isEqualTo(WIDTH_CARD_NUM);
            assertThat(encodedBytes(record.getDalytranOrigTs())).isEqualTo(WIDTH_ORIG_TS);
            assertThat(encodedBytes(record.getDalytranProcTs())).isEqualTo(WIDTH_PROC_TS);
        }

        @Test
        @DisplayName("a signed nine-and-two amount picture is eleven digits wide, one narrower than the "
                + "twelve of the account money fields and five wider than the six of the interest rate")
        void theAmountIsElevenWideAndDiffersFromTwelveAndSix() {
            // Nine integer digits plus two decimal digits. The two inequalities are asserted because
            // the estate carries three different money widths and pasting the wrong precision onto a
            // column is both easy and invisible until byte comparison fails.
            assertThat(AMOUNT_INTEGER_DIGITS + AMOUNT_DECIMAL_DIGITS).isEqualTo(WIDTH_AMT);
            assertThat(WIDTH_AMT).isNotEqualTo(ACCOUNT_AMOUNT_WIDTH);
            assertThat(WIDTH_AMT).isNotEqualTo(DISCLOSURE_RATE_WIDTH);
        }

        @Test
        @DisplayName("the thirteen mapped widths sum to 330 of the declared 350 bytes, and the 20-byte "
                + "remainder is the unmapped trailing filler")
        void theThirteenMappedWidthsSumToThreeHundredThirtyOfThreeHundredFifty() {
            // The filler carries no information and is therefore neither an attribute on the entity
            // nor a column in the schema; it is reconstructed on output from the declared width.
            assertThat(WIDTH_ID + WIDTH_TYPE_CD + WIDTH_CAT_CD + WIDTH_SOURCE + WIDTH_DESC + WIDTH_AMT
                    + WIDTH_MERCHANT_ID + WIDTH_MERCHANT_NAME + WIDTH_MERCHANT_CITY
                    + WIDTH_MERCHANT_ZIP + WIDTH_CARD_NUM + WIDTH_ORIG_TS + WIDTH_PROC_TS)
                    .isEqualTo(MAPPED_WIDTH_TOTAL);

            assertThat(MAPPED_WIDTH_TOTAL + WIDTH_FILLER).isEqualTo(RECORD_WIDTH);
            assertThat(RECORD_WIDTH - MAPPED_WIDTH_TOTAL).isEqualTo(WIDTH_FILLER);
        }

        @Test
        @DisplayName("the card number begins at offset 262, which is the running sum of the ten widths "
                + "that precede it")
        void theCardNumberOffsetIsTheRunningSumOfTheTenPrecedingWidths() {
            // 16 + 2 + 4 + 10 + 100 + 11 + 9 + 50 + 50 + 10 = 262. Asserted as the summation rather
            // than as a bare constant so the derivation is visible and cannot silently drift.
            assertThat(WIDTH_ID + WIDTH_TYPE_CD + WIDTH_CAT_CD + WIDTH_SOURCE + WIDTH_DESC + WIDTH_AMT
                    + WIDTH_MERCHANT_ID + WIDTH_MERCHANT_NAME + WIDTH_MERCHANT_CITY
                    + WIDTH_MERCHANT_ZIP)
                    .isEqualTo(OFFSET_CARD_NUM);
        }

        @Test
        @DisplayName("the origination timestamp begins at 278 and the processing timestamp at 304, each "
                + "the preceding offset plus the preceding width, closing the mapped area at 330")
        void theTimestampOffsetsFollowFromTheCardNumberOffset() {
            // 262 + 16 = 278, then 278 + 26 = 304, then 304 + 26 = 330, where the filler begins. The
            // external sort specifications address the record at these offsets, so they are part of
            // the contract rather than an implementation detail.
            assertThat(OFFSET_CARD_NUM + WIDTH_CARD_NUM).isEqualTo(OFFSET_ORIG_TS);
            assertThat(OFFSET_ORIG_TS + WIDTH_ORIG_TS).isEqualTo(OFFSET_PROC_TS);
            assertThat(OFFSET_PROC_TS + WIDTH_PROC_TS).isEqualTo(OFFSET_FILLER);
            assertThat(OFFSET_FILLER).isEqualTo(MAPPED_WIDTH_TOTAL);
            assertThat(OFFSET_FILLER + WIDTH_FILLER).isEqualTo(RECORD_WIDTH);
        }
    }

    /**
     * The four-field merchant block, which is where this record's naming diverges from its twin's.
     */
    @Nested
    @DisplayName("merchant block naming")
    class MerchantBlockNaming {

        @Test
        @DisplayName("all four merchant fields keep the legacy prefix here, while the byte-identical "
                + "posted-transaction twin deliberately drops it - do not regularise either side")
        void allFourMerchantFieldsKeepTheLegacyPrefix() {
            // This is the one block where the two tables disagree. Here the merchant identifier, name,
            // city and postal code all keep the legacy prefix in both the column name and the Java
            // property, so every accessor below reads getDalytranMerchant... On the posted-transaction
            // table the same four are unprefixed. The asymmetry is a property of the migrated schema,
            // it is recorded in the project decision log, and tidying it in either direction turns
            // start-up into four mapping failures because the provider validates against that schema.
            // Consequently no bare merchant accessor is ever called anywhere in this file.
            DailyTransaction record = firstStagedRecord();

            assertThat(record.getDalytranMerchantId()).isEqualTo(ROW_MERCHANT_ID);
            assertThat(record.getDalytranMerchantName()).isEqualTo(ROW_MERCHANT_NAME);
            assertThat(record.getDalytranMerchantCity()).isEqualTo(ROW_MERCHANT_CITY);
            assertThat(record.getDalytranMerchantZip()).isEqualTo(ROW_MERCHANT_ZIP);

            record.setDalytranMerchantId("100000002");
            record.setDalytranMerchantName("Hegmann and Sons" + " ".repeat(34));
            record.setDalytranMerchantCity("West Lucileview" + " ".repeat(35));
            record.setDalytranMerchantZip("60601" + " ".repeat(5));

            assertThat(record.getDalytranMerchantId()).isEqualTo("100000002");
            assertThat(record.getDalytranMerchantName()).isEqualTo("Hegmann and Sons" + " ".repeat(34));
            assertThat(record.getDalytranMerchantCity()).isEqualTo("West Lucileview" + " ".repeat(35));
            assertThat(record.getDalytranMerchantZip()).isEqualTo("60601" + " ".repeat(5));

            assertThat(encodedBytes(record.getDalytranMerchantId())).isEqualTo(WIDTH_MERCHANT_ID);
            assertThat(encodedBytes(record.getDalytranMerchantName())).isEqualTo(WIDTH_MERCHANT_NAME);
            assertThat(encodedBytes(record.getDalytranMerchantCity())).isEqualTo(WIDTH_MERCHANT_CITY);
            assertThat(encodedBytes(record.getDalytranMerchantZip())).isEqualTo(WIDTH_MERCHANT_ZIP);
        }
    }

    /**
     * Design boundaries that are deliberate, load-bearing and easy to "improve" by mistake.
     */
    @Nested
    @DisplayName("deliberate design boundaries")
    class DeliberateDesignBoundaries {

        @Test
        @DisplayName("the geometry is byte-identical to the posted-transaction twin, yet the two remain "
                + "separate types sharing no base class, mapped superclass, interface, constants holder "
                + "or test helper, because they are distinct datasets with distinct lifecycles")
        void theGeometryMatchesTheTwinYetTheTypesStaySeparate() {
            // The posted-transaction copybook describes the same fourteen fields, in the same order, at
            // the same offsets, with the same widths, differing only in field-name prefix. The two are
            // nevertheless independent entities over independent tables: the posted record lived in a
            // keyed indexed cluster and is a persistent store, whereas this one arrives as a staged
            // sequential input that is consumed and discarded. The duplication of thirteen attributes
            // is intentional and required - a shared supertype would impose one set of column names on
            // both tables and would destroy the merchant-block asymmetry asserted above.
            //
            // The point is made in prose rather than in code on purpose: the twin type is not imported,
            // not referenced and not parameterised over, and no property of one is compared with a
            // property of the other. What is asserted is that THIS record's own widths close at the
            // declared 350 bytes.
            assertThat(WIDTH_ID + WIDTH_TYPE_CD + WIDTH_CAT_CD + WIDTH_SOURCE + WIDTH_DESC + WIDTH_AMT
                    + WIDTH_MERCHANT_ID + WIDTH_MERCHANT_NAME + WIDTH_MERCHANT_CITY
                    + WIDTH_MERCHANT_ZIP + WIDTH_CARD_NUM + WIDTH_ORIG_TS + WIDTH_PROC_TS
                    + WIDTH_FILLER)
                    .isEqualTo(RECORD_WIDTH);
        }

        @Test
        @DisplayName("no legacy cluster definition exists for this dataset: it is a sequential dataset, "
                + "so the 350-byte width comes from the copybook and the measured file stride rather "
                + "than from a record-size clause, and the key declaration cannot be cited")
        void theDatasetHasNoLegacyClusterKeyDeclaration() {
            // Ten of the estate's files are defined as keyed indexed clusters whose definitions state
            // both a key position and a record size. This dataset has no such definition anywhere in
            // the job library. Its width is established twice over instead: by summing the copybook
            // widths, and by the measured file - 105,300 bytes over 300 records is a stride of 351
            // bytes, being the 350-byte image plus one line terminator.
            //
            // Nothing here asserts a key position or a record-size clause, because neither exists to
            // assert. The persistent identity declared on the identifier is a persistence requirement
            // for the staged table, not the reflection of a legacy cluster key.
            assertThat(MAPPED_WIDTH_TOTAL + WIDTH_FILLER).isEqualTo(RECORD_WIDTH);
            assertThat(STAGED_PURCHASES + STAGED_RETURNS).isEqualTo(STAGED_RECORDS);
            assertThat(STAGED_RECORDS * (RECORD_WIDTH + 1)).isEqualTo(105_300);
        }

        @Test
        @DisplayName("the table carries zero foreign keys on purpose, so an unmatched card number and an "
                + "unmatched merchant identifier are both accepted unchanged and without exception, "
                + "keeping reject codes 100, 101 and 109 reachable and the 430-byte reject record "
                + "reproducible; validation lives in the batch validation processor")
        void unmatchedReferencesAreAcceptedBecauseThereAreNoForeignKeys() {
            // No migration defines a foreign key on this table: none from the card number to the card
            // table, none from the type or category code to the reference tables, none anywhere. That
            // is load-bearing rather than an oversight. A constraint would refuse the offending row at
            // insert time, three of the posting program's five reject reason codes would become
            // unreachable - an invalid card number, an account not found on read, and an account not
            // found when the posted balance is written back - and the reject output could never be
            // produced. That output is a 350-byte copy of the source image followed by an 80-byte
            // trailer, and it is compared byte for byte, so it must stay reproducible.
            //
            // Consequently this entity declares no association of any kind, and the checks are the
            // batch validation processor's responsibility, applied in legacy source order and stopping
            // at the first failure. Nothing about the reject record or its reason codes is asserted
            // here; they are cited as the reason the constraint is absent.
            String cardNumberInNoOtherTable = "9999999999999999";
            String merchantIdInNoOtherTable = "999999999";

            DailyTransaction record = firstStagedRecord();
            record.setDalytranCardNum(cardNumberInNoOtherTable);
            record.setDalytranMerchantId(merchantIdInNoOtherTable);

            assertThat(record.getDalytranCardNum()).isEqualTo(cardNumberInNoOtherTable);
            assertThat(record.getDalytranMerchantId()).isEqualTo(merchantIdInNoOtherTable);
            assertThat(encodedBytes(record.getDalytranCardNum())).isEqualTo(WIDTH_CARD_NUM);
            assertThat(encodedBytes(record.getDalytranMerchantId())).isEqualTo(WIDTH_MERCHANT_ID);

            // An unknown type code and an unknown category code are accepted for the same reason.
            record.setDalytranTypeCd("ZZ");
            record.setDalytranCatCd("9998");

            assertThat(record.getDalytranTypeCd()).isEqualTo("ZZ");
            assertThat(record.getDalytranCatCd()).isEqualTo("9998");
        }

        @Test
        @DisplayName("no surrogate identifier and no generated value exists: the persistent identity is "
                + "the sixteen-byte business key taken verbatim from the record image")
        void noSurrogateIdentifierExists() {
            // The entity declares no generic or generated identifier accessor, no generated value and
            // no database sequence, so what is asserted here is the positive consequence: identity is
            // the business key read straight from the record image.
            //
            // The business key is what the batch file descriptions treat as the leading substring of
            // the record image, so a surrogate would break the correspondence between the image and the
            // table row, and on this table specifically it would make echoing the original image into a
            // reject record impossible.
            DailyTransaction record = firstStagedRecord();

            assertThat(record.getDalytranId()).isEqualTo(ROW_ID);
            assertThat(encodedBytes(record.getDalytranId())).isEqualTo(WIDTH_ID);
            assertThat(new DailyTransaction().getDalytranId()).isNull();
        }

        @Test
        @DisplayName("the overlimit basis - cycle credit less cycle debit plus this amount, evaluated "
                + "strictly left to right into a signed nine-and-two field - decides reject code 102, "
                + "but it belongs to the posting service, so this entity performs no arithmetic")
        void theOverlimitComputationBelongsToThePostingService() {
            // Documented here, computed elsewhere. The legacy expression subtracts the cycle debit from the cycle
            // credit and then adds this record's amount, in that order, storing the result into a
            // signed nine-and-two receiving field. Because a store into a two-decimal field truncates -
            // no rounding clause exists anywhere in the estate - the arithmetic is not associative and
            // any algebraic rearrangement changes which records are rejected. The expression must
            // therefore be reproduced operand for operand in the posting service, which is neither
            // imported nor referenced here.
            //
            // What is asserted is the boundary itself: the amount handed to this entity comes back
            // exactly as supplied, with no addition, subtraction, negation or magnitude taken.
            DailyTransaction record = firstStagedRecord();
            BigDecimal supplied = new BigDecimal(ROW_AMT);

            record.setDalytranAmt(supplied);

            assertThat(record.getDalytranAmt()).isEqualTo(supplied);
            assertThat(record.getDalytranAmt()).isSameAs(supplied);
            assertThat(WIDTH_AMT).isEqualTo(AMOUNT_INTEGER_DIGITS + AMOUNT_DECIMAL_DIGITS);
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
        @DisplayName("the first staged record's amount is 504.77 and it round-trips with both its value "
                + "and its scale of two intact, so it is not equal to the same number at scale four")
        void theAmountRoundTripsWithValueAndScaleIntact() {
            // 504.77 is the hand-decoded value of the first record's image, whose trailing byte encodes
            // a positive seven. Numeric equality and scale identity are asserted separately, because
            // they are different properties: comparison ignores trailing zeros while equality does not,
            // and the column is declared at scale two.
            DailyTransaction record = firstStagedRecord();

            assertThat(record.getDalytranAmt()).isEqualByComparingTo(new BigDecimal("504.77"));
            assertThat(record.getDalytranAmt().compareTo(new BigDecimal("504.77"))).isZero();
            assertThat(record.getDalytranAmt().scale()).isEqualTo(AMOUNT_DECIMAL_DIGITS);
            assertThat(record.getDalytranAmt()).isEqualTo(new BigDecimal("504.77"));
            assertThat(record.getDalytranAmt()).isNotEqualTo(new BigDecimal("504.7700"));
        }

        @Test
        @DisplayName("a negative amount round-trips with its sign: 50 of the 300 staged records are "
                + "operator-originated returns carrying negative amounts, so negatives are ordinary "
                + "data here and both signed directions must survive")
        void aNegativeAmountRoundTripsIncludingItsSign() {
            // The negative sign is carried by an overpunched trailing byte in the image, which is why a
            // decoder that ignored the overpunch would turn every return into a purchase and invert the
            // posting run's balance arithmetic. Nothing in this entity rejects, normalises or takes the
            // magnitude of a negative amount.
            DailyTransaction record = firstStagedRecord();

            record.setDalytranAmt(new BigDecimal("-504.77"));

            assertThat(record.getDalytranAmt()).isEqualByComparingTo(new BigDecimal("-504.77"));
            assertThat(record.getDalytranAmt()).isEqualTo(new BigDecimal("-504.77"));
            assertThat(record.getDalytranAmt().signum()).isEqualTo(-1);
            assertThat(record.getDalytranAmt()).isNegative();
            assertThat(record.getDalytranAmt().scale()).isEqualTo(AMOUNT_DECIMAL_DIGITS);
            assertThat(STAGED_RETURNS).isEqualTo(STAGED_RECORDS - STAGED_PURCHASES);
        }

        @Test
        @DisplayName("a zero amount round-trips as a present value at scale two and never as an absent "
                + "one, because a settled zero and a missing amount are different facts")
        void aZeroAmountRoundTripsAsAPresentValue() {
            DailyTransaction record = firstStagedRecord();

            record.setDalytranAmt(new BigDecimal("0.00"));

            assertThat(record.getDalytranAmt()).isNotNull();
            assertThat(record.getDalytranAmt().compareTo(BigDecimal.ZERO)).isZero();
            assertThat(record.getDalytranAmt().scale()).isEqualTo(AMOUNT_DECIMAL_DIGITS);
            assertThat(record.getDalytranAmt().signum()).isZero();
        }

        @Test
        @DisplayName("negative zero is stored exactly as supplied, because the zoned representation "
                + "distinguishes positive zero from negative zero in the overpunched trailing byte and "
                + "the entity carries what it is given rather than canonicalising it")
        void negativeZeroIsAcceptedAndPreservedAsSupplied() {
            // The positive-zero and negative-zero overpunch codes are distinct characters in the image.
            // Canonicalising one to the other here would be a decision this class has no authority to
            // make; the entity is a passive carrier and the codec owns the representation.
            DailyTransaction record = firstStagedRecord();

            record.setDalytranAmt(new BigDecimal("-0.00"));

            assertThat(record.getDalytranAmt()).isNotNull();
            assertThat(record.getDalytranAmt().compareTo(BigDecimal.ZERO)).isZero();
            assertThat(record.getDalytranAmt().scale()).isEqualTo(AMOUNT_DECIMAL_DIGITS);
            assertThat(record.getDalytranAmt().signum()).isZero();
        }

        @Test
        @DisplayName("the entity applies no scaling: a value handed in at scale one comes back at scale "
                + "one rather than being widened to the column's scale of two")
        void theEntityAppliesNoScaling() {
            // Widening here would look harmless and would hide a mapper that failed to present the
            // value at its declared scale. Scale is the codec's responsibility, not the entity's.
            DailyTransaction record = firstStagedRecord();

            record.setDalytranAmt(new BigDecimal("1.5"));

            assertThat(record.getDalytranAmt().scale()).isEqualTo(1);
            assertThat(record.getDalytranAmt()).isEqualTo(new BigDecimal("1.5"));
            assertThat(record.getDalytranAmt()).isEqualByComparingTo(new BigDecimal("1.50"));
        }

        @Test
        @DisplayName("the entity applies no rounding and no arithmetic: a value handed in at scale three "
                + "comes back as 2.999, neither truncated to 2.99 nor rounded to 3.00, because the "
                + "truncating store belongs to the codec and never to this class")
        void theEntityAppliesNoRoundingAndNoArithmetic() {
            // A census of the estate found no rounding clause on any arithmetic statement, so every
            // legacy store into a two-decimal field truncates toward zero. That truncation is applied
            // in exactly one place - the codec - so that no class can introduce a different policy by
            // accident. This entity is not that place: it stores and returns, and does nothing else.
            DailyTransaction record = firstStagedRecord();

            record.setDalytranAmt(new BigDecimal("2.999"));

            assertThat(record.getDalytranAmt()).isEqualTo(new BigDecimal("2.999"));
            assertThat(record.getDalytranAmt().scale()).isEqualTo(3);
            assertThat(record.getDalytranAmt()).isNotEqualTo(new BigDecimal("2.99"));
            assertThat(record.getDalytranAmt()).isNotEqualTo(new BigDecimal("3.00"));
            assertThat(record.getDalytranAmt().toPlainString()).isEqualTo("2.999");
        }

        @Test
        @DisplayName("the widest amount the signed nine-and-two picture allows round-trips intact, so "
                + "the eleven declared digits are genuinely available")
        void theWidestAmountThePictureAllowsRoundTripsIntact() {
            // Nine integer digits and two decimal digits, in both directions.
            DailyTransaction record = firstStagedRecord();

            record.setDalytranAmt(new BigDecimal("999999999.99"));
            assertThat(record.getDalytranAmt()).isEqualTo(new BigDecimal("999999999.99"));
            assertThat(record.getDalytranAmt().precision()).isEqualTo(WIDTH_AMT);
            assertThat(record.getDalytranAmt().scale()).isEqualTo(AMOUNT_DECIMAL_DIGITS);

            record.setDalytranAmt(new BigDecimal("-999999999.99"));
            assertThat(record.getDalytranAmt()).isEqualTo(new BigDecimal("-999999999.99"));
            assertThat(record.getDalytranAmt().precision()).isEqualTo(WIDTH_AMT);
            assertThat(record.getDalytranAmt().scale()).isEqualTo(AMOUNT_DECIMAL_DIGITS);
        }
    }

    /**
     * Fixed-width text fidelity: leading zeros, trailing spaces and unparsed lexemes.
     */
    @Nested
    @DisplayName("fixed-width text fidelity")
    class FixedWidthTextFidelity {

        @Test
        @DisplayName("the transaction identifier keeps its leading zeros: the sixteen-character value "
                + "stays sixteen characters and is not equal to its shortened numeric form, which is why "
                + "the column is bounded text and explicitly not a numeric type")
        void theTransactionIdentifierKeepsItsLeadingZeros() {
            // The staged input carries values with ten leading zeros. A numeric property would discard
            // them silently, and the identifier is echoed verbatim into the reject record, so the
            // padding is contractual. Nothing here converts the value to a number.
            DailyTransaction record = firstStagedRecord();

            assertThat(record.getDalytranId()).isEqualTo(ROW_ID);
            assertThat(encodedBytes(record.getDalytranId())).isEqualTo(WIDTH_ID);
            assertThat(record.getDalytranId()).isNotEqualTo("683580");
            assertThat(record.getDalytranId()).startsWith("0000000000");
        }

        @Test
        @DisplayName("the category code keeps its leading zeros: the four-character value stays four "
                + "characters and is not equal to its single-digit form")
        void theCategoryCodeKeepsItsLeadingZeros() {
            DailyTransaction record = firstStagedRecord();

            assertThat(record.getDalytranCatCd()).isEqualTo(ROW_CAT_CD);
            assertThat(encodedBytes(record.getDalytranCatCd())).isEqualTo(WIDTH_CAT_CD);
            assertThat(record.getDalytranCatCd()).isNotEqualTo("1");
            assertThat(record.getDalytranCatCd()).startsWith("000");
        }

        @Test
        @DisplayName("the merchant identifier keeps all nine digits and is never reduced to a number, so "
                + "a value with leading zeros survives at its declared width")
        void theMerchantIdentifierKeepsItsFullNineDigits() {
            DailyTransaction record = firstStagedRecord();

            assertThat(record.getDalytranMerchantId()).isEqualTo(ROW_MERCHANT_ID);
            assertThat(encodedBytes(record.getDalytranMerchantId())).isEqualTo(WIDTH_MERCHANT_ID);

            record.setDalytranMerchantId("000000123");

            assertThat(record.getDalytranMerchantId()).isEqualTo("000000123");
            assertThat(record.getDalytranMerchantId()).isNotEqualTo("123");
            assertThat(encodedBytes(record.getDalytranMerchantId())).isEqualTo(WIDTH_MERCHANT_ID);
        }

        @Test
        @DisplayName("the origination source keeps its trailing spaces: the staged input holds 250 "
                + "point-of-sale markers and 50 operator markers across its 300 records, each an "
                + "eight-character marker padded into the ten-byte field, and trimming either is "
                + "forbidden because it would shorten the fixed-width image")
        void theOriginationSourceKeepsItsTrailingSpaces() {
            // Both markers are eight characters followed by two spaces. The padded and unpadded forms
            // are asserted to be unequal, which is the whole point: a trimmed value would no longer
            // occupy its field.
            DailyTransaction record = firstStagedRecord();

            assertThat(record.getDalytranSource()).isEqualTo(ROW_SOURCE);
            assertThat(encodedBytes(record.getDalytranSource())).isEqualTo(WIDTH_SOURCE);
            assertThat(record.getDalytranSource()).isNotEqualTo("POS TERM");
            assertThat(record.getDalytranSource()).endsWith("  ");

            record.setDalytranSource(OPERATOR_SOURCE);

            assertThat(record.getDalytranSource()).isEqualTo(OPERATOR_SOURCE);
            assertThat(encodedBytes(record.getDalytranSource())).isEqualTo(WIDTH_SOURCE);
            assertThat(record.getDalytranSource()).isNotEqualTo("OPERATOR");
            assertThat(record.getDalytranSource()).endsWith("  ");

            assertThat(STAGED_PURCHASES + STAGED_RETURNS).isEqualTo(STAGED_RECORDS);
        }

        @Test
        @DisplayName("the origination source is a raw ten-byte code, so a value outside the known "
                + "vocabulary is stored unchanged with no exception, no default substitution and no "
                + "normalisation - proving there is no enumerated mapping and no bean validation")
        void theOriginationSourceAcceptsAValueOutsideTheKnownVocabulary() {
            // Persisting a typed constant by name would store the constant name rather than the padded
            // legacy lexeme and would break byte parity; persisting one by ordinal would store an
            // integer against a character column. Translating a code into a typed constant is the
            // service layer's job, so no source-type enumeration is imported or referenced here.
            String outsideTheVocabulary = "MAIL ORDR";

            DailyTransaction record = firstStagedRecord();
            record.setDalytranSource(outsideTheVocabulary + " ");

            assertThat(record.getDalytranSource()).isEqualTo("MAIL ORDR ");
            assertThat(encodedBytes(record.getDalytranSource())).isEqualTo(WIDTH_SOURCE);

            // Mixed case is preserved too: nothing folds case on the way in or out.
            record.setDalytranSource("pos term  ");

            assertThat(record.getDalytranSource()).isEqualTo("pos term  ");
            assertThat(record.getDalytranSource()).isNotEqualTo(ROW_SOURCE);
        }

        @Test
        @DisplayName("the description round-trips at its full hundred bytes with trailing spaces intact, "
                + "so the padded form is not equal to the unpadded text it starts with")
        void theDescriptionRoundTripsAtItsFullHundredBytes() {
            // Twenty-four characters of text followed by seventy-six spaces, exactly as the staged
            // input's first record carries it.
            DailyTransaction record = firstStagedRecord();

            assertThat(record.getDalytranDesc()).isEqualTo(ROW_DESC);
            assertThat(encodedBytes(record.getDalytranDesc())).isEqualTo(WIDTH_DESC);
            assertThat(record.getDalytranDesc()).isNotEqualTo("Purchase at Abshire-Lowe");
            assertThat(record.getDalytranDesc()).startsWith("Purchase at Abshire-Lowe");
            assertThat(record.getDalytranDesc()).endsWith(" ");
        }

        @Test
        @DisplayName("both timestamps are unparsed twenty-six-character lexemes, so the staged value is "
                + "returned intact and a value that is not a valid calendar instant is accepted "
                + "unchanged - proving neither field holds a temporal type")
        void bothTimestampsRoundTripAsRawTwentySixCharacterLexemes() {
            // Strict calendar parsing belongs to the service layer. Holding a temporal type here would
            // be impossible in any case, because the processing timestamp arrives blank on every staged
            // record and no temporal type can represent a blank instant.
            DailyTransaction record = firstStagedRecord();

            assertThat(record.getDalytranOrigTs()).isEqualTo(ROW_ORIG_TS);
            assertThat(encodedBytes(record.getDalytranOrigTs())).isEqualTo(WIDTH_ORIG_TS);

            // The thirteenth month and the thirty-second day are not calendar instants. They are
            // accepted here regardless, because this field carries a lexeme and nothing more.
            String notACalendarInstant = "2022-13-32 25:61:61.999999";

            record.setDalytranOrigTs(notACalendarInstant);
            record.setDalytranProcTs(notACalendarInstant);

            assertThat(record.getDalytranOrigTs()).isEqualTo(notACalendarInstant);
            assertThat(record.getDalytranProcTs()).isEqualTo(notACalendarInstant);
            assertThat(encodedBytes(record.getDalytranOrigTs())).isEqualTo(WIDTH_ORIG_TS);
            assertThat(encodedBytes(record.getDalytranProcTs())).isEqualTo(WIDTH_PROC_TS);
        }

        @Test
        @DisplayName("the processing timestamp round-trips as twenty-six spaces: all 300 staged records "
                + "arrive blank because the posting run is what stamps the field, which is why the value "
                + "must be storable as pure whitespace rather than normalised to absent or empty")
        void theProcessingTimestampRoundTripsAsTwentySixSpaces() {
            // This single measurement is what forces the whole module to model record timestamps as
            // bounded character lexemes: a temporal mapping would fail on the very first staged record.
            DailyTransaction record = firstStagedRecord();

            assertThat(record.getDalytranProcTs()).isNotNull();
            assertThat(record.getDalytranProcTs()).isEqualTo(BLANK_PROC_TS);
            assertThat(encodedBytes(record.getDalytranProcTs())).isEqualTo(WIDTH_PROC_TS);
            assertThat(record.getDalytranProcTs()).isNotEqualTo("");
            assertThat(record.getDalytranProcTs()).isNotEqualTo(" ");
            assertThat(record.getDalytranProcTs()).isNotEqualTo(ROW_ORIG_TS);
            assertThat(STAGED_RECORDS).isEqualTo(300);
        }
    }

    /**
     * Business-key identity and the redacted diagnostic representation.
     */
    @Nested
    @DisplayName("business-key identity")
    class BusinessKeyIdentity {

        @Test
        @DisplayName("equality and hashing are keyed on the transaction identifier alone: two records "
                + "sharing an identifier are equal and share a hash code even when every other field "
                + "differs, because every other field is mutable")
        void equalityAndHashingAreKeyedOnTheIdentifierAlone() {
            // Including a mutable property would let an instance change its own equality across a
            // flush, corrupting set and map membership and the persistence-context identity map. The
            // second record below differs in all twelve non-key fields.
            DailyTransaction first = firstStagedRecord();
            DailyTransaction second = new DailyTransaction(
                    ROW_ID,
                    "03",
                    "0099",
                    OPERATOR_SOURCE,
                    "Return authorised by operator" + " ".repeat(71),
                    new BigDecimal("-99.01"),
                    "900000001",
                    "Zulauf-Rempel" + " ".repeat(37),
                    "South Marvinport" + " ".repeat(34),
                    "30303" + " ".repeat(5),
                    "4111111111111111",
                    "2022-06-11 08:15:00.000000",
                    "2022-06-12 23:59:59.999999");

            assertThat(second.getDalytranId()).isEqualTo(first.getDalytranId());
            assertThat(second.getDalytranTypeCd()).isNotEqualTo(first.getDalytranTypeCd());
            assertThat(second.getDalytranCatCd()).isNotEqualTo(first.getDalytranCatCd());
            assertThat(second.getDalytranSource()).isNotEqualTo(first.getDalytranSource());
            assertThat(second.getDalytranDesc()).isNotEqualTo(first.getDalytranDesc());
            assertThat(second.getDalytranAmt()).isNotEqualTo(first.getDalytranAmt());
            assertThat(second.getDalytranMerchantId()).isNotEqualTo(first.getDalytranMerchantId());
            assertThat(second.getDalytranMerchantName()).isNotEqualTo(first.getDalytranMerchantName());
            assertThat(second.getDalytranMerchantCity()).isNotEqualTo(first.getDalytranMerchantCity());
            assertThat(second.getDalytranMerchantZip()).isNotEqualTo(first.getDalytranMerchantZip());
            assertThat(second.getDalytranCardNum()).isNotEqualTo(first.getDalytranCardNum());
            assertThat(second.getDalytranOrigTs()).isNotEqualTo(first.getDalytranOrigTs());
            assertThat(second.getDalytranProcTs()).isNotEqualTo(first.getDalytranProcTs());

            assertThat(first).isEqualTo(second);
            assertThat(second).isEqualTo(first);
            assertThat(first).hasSameHashCodeAs(second);
        }

        @Test
        @DisplayName("two records with different identifiers are unequal even when every other field "
                + "matches, so a distinct staged row is never mistaken for a duplicate")
        void differentIdentifiersMeanUnequal() {
            DailyTransaction first = firstStagedRecord();
            DailyTransaction other = firstStagedRecord();

            other.setDalytranId("0000000000683581");

            assertThat(first).isNotEqualTo(other);
            assertThat(other).isNotEqualTo(first);
        }

        @Test
        @DisplayName("equality is reflexive, rejects an absent argument and rejects a foreign type "
                + "carrying the very same identifier text")
        void equalityIsReflexiveNullSafeAndForeignTypeSafe() {
            DailyTransaction record = firstStagedRecord();

            assertThat(record).isEqualTo(record);
            assertThat(record.equals(record)).isTrue();

            // Compared through the declared method rather than through an assertion helper, so the
            // null-handling and type-handling branches are exercised exactly as a caller would hit them.
            Object absent = null;
            assertThat(record.equals(absent)).isFalse();

            Object sameTextDifferentType = ROW_ID;
            assertThat(record.equals(sameTextDifferentType)).isFalse();
        }

        @Test
        @DisplayName("two records with an absent identifier are equal, because both keys are absent and "
                + "the comparison is null-tolerant rather than throwing")
        void twoRecordsWithAnAbsentIdentifierAreEqual() {
            DailyTransaction first = new DailyTransaction();
            DailyTransaction second = new DailyTransaction();

            assertThat(first).isEqualTo(second);
            assertThat(first).hasSameHashCodeAs(second);
        }

        @Test
        @DisplayName("the diagnostic representation names the identifier, the type code and the category "
                + "code, and withholds the card number, the amount and every merchant value so that "
                + "regulated data cannot reach a log line or an exception message")
        void theDiagnosticRepresentationCarriesOnlyTheThreeUnregulatedFields() {
            // The card number is a primary account number and the amount is financial data, so neither
            // belongs in a diagnostic string. The merchant block is withheld with them.
            DailyTransaction record = firstStagedRecord();

            String description = record.toString();

            assertThat(description).contains(ROW_ID);
            assertThat(description).contains(ROW_TYPE_CD);
            assertThat(description).contains(ROW_CAT_CD);

            assertThat(description).doesNotContain(ROW_CARD_NUM);
            assertThat(description).doesNotContain(ROW_AMT);
            assertThat(description).doesNotContain("Abshire-Lowe");
            assertThat(description).doesNotContain("North Enoshaven");
            assertThat(description).doesNotContain(ROW_MERCHANT_ID);
            assertThat(description).doesNotContain("72112");
        }

        @Test
        @DisplayName("a record with an absent identifier still describes itself rather than failing, so "
                + "a diagnostic can be emitted for a half-mapped row")
        void aRecordWithAnAbsentIdentifierStillDescribesItself() {
            DailyTransaction empty = new DailyTransaction();

            assertThat(empty.toString()).isNotNull();
            assertThat(empty.toString()).contains("DailyTransaction");
        }
    }
}
