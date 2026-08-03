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

import com.carddemo.domain.Transaction;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Unit test for {@link TransactionRecordMapper}, the two-way mapping between the 350-byte posted
 * transaction record image and {@link Transaction}.
 *
 * <p><strong>What this layout is and why its bytes matter beyond this class.</strong> It is the posted
 * transaction: the keyed cluster the online view, add and list screens read, the record the posting run
 * writes, the record the interest run synthesises, and the record two external sorts address <em>by
 * column</em>. Three of its field positions are therefore not internal detail but a published contract:
 * the card number is addressed at one-based column 263 for 16 bytes and the processing date at one-based
 * column 305 for 10 bytes by the report job's sort symbols, and the statement job reprojects from
 * one-based column 263. A field displaced by a single byte would compile, round-trip through this mapper
 * and still hand the external sort the wrong bytes, so every offset below is asserted against the
 * integer the copybook declares rather than against another constant of the code under test.
 *
 * <p><strong>Every expectation here is hand-derived and independent of the code under test.</strong>
 * The reference records are declared as literal field values and reassembled by this class's own two
 * padding helpers, which reimplement the justification a {@code PIC X(n)} and a {@code PIC 9(n)} field
 * receive - left-justified space-padded, and right-justified zero-padded, respectively. No assertion
 * calls a production method to compute the value it then checks, no expected image is snapshotted from
 * an earlier run, and no offset or width constant of the mapper appears on both sides of a comparison.
 * The geometry block asserts the mapper's published constants against copybook literals, so a layout
 * shifted uniformly - the one error a self-consistent constant set cannot catch - fails here.
 *
 * <p><strong>Two entities share one geometry and are deliberately kept apart.</strong> The daily
 * transaction layout is field-for-field parallel to this one: same order, same widths, same offsets.
 * They remain separate entities over separate tables because they are separate datasets with separate
 * lifecycles - this one was a keyed cluster, that one arrives as a sequential dataset. Accordingly this
 * file imports neither the sibling mapper nor the sibling entity, extracts no shared base class, no
 * shared offset holder and no shared helper, and the visible naming asymmetry is preserved rather than
 * tidied: <em>this</em> entity leaves all four merchant properties unprefixed, where the sibling
 * prefixes all thirteen.
 *
 * <p><strong>The amount is zoned decimal with its sign overpunched into its final byte.</strong>
 * Eleven bytes hold nine integer digits and two decimals; the trailing byte carries both the low-order
 * digit and the sign, from the ten positive forms and the ten negative forms. Decoding truncates toward
 * zero at scale two and never rounds, because no arithmetic statement anywhere in the estate specifies
 * rounding and a COBOL store without a rounding clause truncates. This class names no rounding mode,
 * rescales nothing, and builds every expected amount from a decimal string literal rather than from a
 * binary floating-point value.
 *
 * <p><strong>Timestamps stay raw twenty-six-byte text.</strong> Nothing here parses, formats or
 * normalises one, and a processing timestamp that has not been stamped is twenty-six spaces, which no
 * temporal type can hold: it must survive as spaces rather than becoming null, empty or trimmed. Both
 * states are exercised, because the posting run writes a record whose processing stamp is blank and
 * later rewrites the same record with it populated.
 *
 * <p>Provenance: checkout SHA {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. Layout authority is copybook
 * {@code app/cpy/CVTRA05Y.cpy}; the column positions the external sorts address are declared by
 * {@code app/proc/TRANREPT.prc} and {@code app/jcl/CREASTMT.JCL}. No legacy source line is transcribed
 * anywhere in this file - only widths, offsets, counts, field names and contract literals, which are
 * metadata rather than source.
 *
 * @see TransactionRecordMapper
 * @see Transaction
 */
@DisplayName("posted transaction record mapper: the 350-byte CVTRA05Y keyed layout")
class TransactionRecordMapperTest {

    // ------------------------------------------------------------------------------------------
    // The copybook's declared widths, transcribed from app/cpy/CVTRA05Y.cpy as integer literals.
    //
    // These are the independent side of every geometry assertion. They are declared here, once, as
    // literals rather than read from the mapper, so that the mapper's published constants are
    // compared against the copybook rather than against themselves.
    // ------------------------------------------------------------------------------------------

    /** {@code TRAN-ID PIC X(16)}. */
    private static final int WIDTH_ID = 16;

    /** {@code TRAN-TYPE-CD PIC X(02)}. */
    private static final int WIDTH_TYPE_CD = 2;

    /** {@code TRAN-CAT-CD PIC 9(04)}. */
    private static final int WIDTH_CAT_CD = 4;

    /** {@code TRAN-SOURCE PIC X(10)}. */
    private static final int WIDTH_SOURCE = 10;

    /** {@code TRAN-DESC PIC X(100)}. */
    private static final int WIDTH_DESC = 100;

    /** {@code TRAN-AMT PIC S9(09)V99}: nine integer digits, two decimals, sign overpunched. */
    private static final int WIDTH_AMT = 11;

    /** {@code TRAN-MERCHANT-ID PIC 9(09)}. */
    private static final int WIDTH_MERCHANT_ID = 9;

    /** {@code TRAN-MERCHANT-NAME PIC X(50)}. */
    private static final int WIDTH_MERCHANT_NAME = 50;

    /** {@code TRAN-MERCHANT-CITY PIC X(50)}. */
    private static final int WIDTH_MERCHANT_CITY = 50;

    /** {@code TRAN-MERCHANT-ZIP PIC X(10)}. */
    private static final int WIDTH_MERCHANT_ZIP = 10;

    /** {@code TRAN-CARD-NUM PIC X(16)}. */
    private static final int WIDTH_CARD_NUM = 16;

    /** {@code TRAN-ORIG-TS PIC X(26)}. */
    private static final int WIDTH_ORIG_TS = 26;

    /** {@code TRAN-PROC-TS PIC X(26)}. */
    private static final int WIDTH_PROC_TS = 26;

    /** {@code FILLER PIC X(20)}: the unmapped trailing run. */
    private static final int WIDTH_FILLER = 20;

    /** The declared record length, and the sum of the fourteen widths above. */
    private static final int RECORD_WIDTH = 350;

    /** The mapped prefix: everything before the unmapped filler run. */
    private static final int MAPPED_WIDTH = RECORD_WIDTH - WIDTH_FILLER;

    // ------------------------------------------------------------------------------------------
    // Zero-based offsets, obtained by summing the widths above in declaration order. Written out
    // as literals rather than as sums so that an arithmetic slip in one offset cannot propagate
    // silently into the next.
    // ------------------------------------------------------------------------------------------

    /** Offset of the transaction identifier: the record begins with it. */
    private static final int OFFSET_ID = 0;

    /** Offset of the transaction type code. */
    private static final int OFFSET_TYPE_CD = 16;

    /** Offset of the transaction category code. */
    private static final int OFFSET_CAT_CD = 18;

    /** Offset of the origination source. */
    private static final int OFFSET_SOURCE = 22;

    /** Offset of the description. */
    private static final int OFFSET_DESC = 32;

    /** Offset of the zoned-decimal amount. */
    private static final int OFFSET_AMT = 132;

    /** Offset of the merchant identifier. */
    private static final int OFFSET_MERCHANT_ID = 143;

    /** Offset of the merchant name. */
    private static final int OFFSET_MERCHANT_NAME = 152;

    /** Offset of the merchant city. */
    private static final int OFFSET_MERCHANT_CITY = 202;

    /** Offset of the merchant postal code. */
    private static final int OFFSET_MERCHANT_ZIP = 252;

    /** Offset of the card number, which one external sort addresses at one-based column 263. */
    private static final int OFFSET_CARD_NUM = 262;

    /** Offset of the origination timestamp. */
    private static final int OFFSET_ORIG_TS = 278;

    /** Offset of the processing timestamp, whose leading date one sort addresses at column 305. */
    private static final int OFFSET_PROC_TS = 304;

    /** Offset at which the unmapped filler run begins. */
    private static final int OFFSET_FILLER = 330;

    /**
     * One-based column at which the report job's sort symbol addresses the card number.
     *
     * <p>Declared as the literal the job stream carries, not as {@code OFFSET_CARD_NUM + 1}, because
     * deriving it would make the assertion that the two conventions agree vacuous.
     */
    private static final int SORT_COLUMN_CARD_NUM = 263;

    /** One-based column of the origination timestamp, as an external sort addresses it. */
    private static final int SORT_COLUMN_ORIG_TS = 279;

    /** One-based column at which the report job's sort symbol addresses the processing date. */
    private static final int SORT_COLUMN_PROC_DT = 305;

    /** Width the report job's sort symbol gives the processing date: the date only, not the stamp. */
    private static final int WIDTH_PROC_DT = 10;

    // ------------------------------------------------------------------------------------------
    // The first reference record, declared as its significant field values. Padding to the declared
    // widths is applied by the helpers below. Every literal is 7-bit ASCII, so a character count and
    // an encoded byte count coincide for these values.
    // ------------------------------------------------------------------------------------------

    /** Sixteen-byte transaction identifier, carrying the leading zeros a keyed record holds. */
    private static final String FIRST_ID = "0000000000683580";

    /** Transaction type code: a purchase. */
    private static final String FIRST_TYPE_CD = "01";

    /** Four-byte category code, significant leading zeros included. */
    private static final String FIRST_CAT_CD = "0001";

    /** Origination source of a point-of-sale purchase, shorter than its ten-byte field. */
    private static final String FIRST_SOURCE = "POS TERM";

    /** Description, far shorter than its hundred-byte field, so the padding is exercised. */
    private static final String FIRST_DESC = "Purchase at Brekke, Bradtke and Weimann";

    /**
     * Eleven-byte zoned image of the first record's amount, transcribed as the field's bytes.
     *
     * <p>Ten digits followed by an overpunched final byte: {@code F} is the seventh positive form, so
     * it contributes the digit six as well as the sign, giving the eleven digits
     * {@code 00000123456} and, at two implied decimals, the value below.
     */
    private static final String FIRST_AMOUNT_IMAGE = "0000012345F";

    /** The first record's amount, decoded by hand from {@link #FIRST_AMOUNT_IMAGE}. */
    private static final BigDecimal FIRST_AMOUNT = new BigDecimal("1234.56");

    /** Nine-byte merchant identifier with significant leading zeros. */
    private static final String FIRST_MERCHANT_ID = "000123456";

    /** Merchant name, shorter than its fifty-byte field. */
    private static final String FIRST_MERCHANT_NAME = "Brekke, Bradtke and Weimann";

    /** Merchant city, shorter than its fifty-byte field. */
    private static final String FIRST_MERCHANT_CITY = "Port Kaseyfurt";

    /** Merchant postal code, filling its ten-byte field exactly. */
    private static final String FIRST_MERCHANT_ZIP = "9407884128";

    /** Sixteen-byte card number, filling its field exactly. */
    private static final String FIRST_CARD_NUM = "4111111111111111";

    /** Twenty-six-byte origination timestamp, filling its field exactly. */
    private static final String FIRST_ORIG_TS = "2022-07-19 23:12:33.000000";

    /** Twenty-six-byte processing timestamp, filling its field exactly. */
    private static final String FIRST_PROC_TS = "2022-07-20 01:02:03.000000";

    // ------------------------------------------------------------------------------------------
    // The second reference record: a negative amount, an operator origination, and a processing
    // timestamp that has not been stamped. Both divergences from the first record are contractual
    // states of this layout rather than invented edge cases.
    // ------------------------------------------------------------------------------------------

    /** Sixteen-byte identifier of the second record. */
    private static final String SECOND_ID = "0000000000683581";

    /** Transaction type code: a return. */
    private static final String SECOND_TYPE_CD = "02";

    /** Category code of the second record. */
    private static final String SECOND_CAT_CD = "0003";

    /** Origination source of an operator-entered return. */
    private static final String SECOND_SOURCE = "OPERATOR";

    /** Description of the second record. */
    private static final String SECOND_DESC = "Refund posted by operator";

    /**
     * Eleven-byte zoned image of the second record's amount: a closing brace is the negative form of
     * the digit zero, so the eleven digits are {@code 00000009190} and the value is negative.
     */
    private static final String SECOND_AMOUNT_IMAGE = "0000000919}";

    /** The second record's amount, decoded by hand from {@link #SECOND_AMOUNT_IMAGE}. */
    private static final BigDecimal SECOND_AMOUNT = new BigDecimal("-91.90");

    /** Merchant identifier of the second record. */
    private static final String SECOND_MERCHANT_ID = "000654321";

    /** Merchant name of the second record. */
    private static final String SECOND_MERCHANT_NAME = "Kuhic and Sons";

    /** Merchant city of the second record. */
    private static final String SECOND_MERCHANT_CITY = "South Devinshire";

    /** Merchant postal code of the second record. */
    private static final String SECOND_MERCHANT_ZIP = "1130584892";

    /** Card number of the second record. */
    private static final String SECOND_CARD_NUM = "4111111111111112";

    /** Origination timestamp of the second record. */
    private static final String SECOND_ORIG_TS = "2022-07-19 23:12:34.000000";

    /** An unstamped processing timestamp: twenty-six spaces, which must survive as spaces. */
    private static final String UNSTAMPED_PROC_TS = "                          ";

    /** The ten characters that overpunch a positive final digit, zero through nine in order. */
    private static final String POSITIVE_OVERPUNCH = "{ABCDEFGHI";

    /** The ten characters that overpunch a negative final digit, zero through nine in order. */
    private static final String NEGATIVE_OVERPUNCH = "}JKLMNOPQR";

    /**
     * Reassembles a complete record image from its significant field values.
     *
     * <p>The amount is supplied as its already-encoded eleven-byte image rather than as a number, so
     * that no assertion in this class depends on the codec to produce the bytes it then checks.
     *
     * @param  id            transaction identifier
     * @param  typeCd        transaction type code
     * @param  catCd         transaction category code
     * @param  source        origination source
     * @param  desc          description
     * @param  amountImage   the eleven-byte zoned-decimal image of the amount
     * @param  merchantId    merchant identifier
     * @param  merchantName  merchant name
     * @param  merchantCity  merchant city
     * @param  merchantZip   merchant postal code
     * @param  cardNum       card number
     * @param  origTs        origination timestamp
     * @param  procTs        processing timestamp, possibly all spaces
     * @return the complete image, exactly {@value #RECORD_WIDTH} characters, filler included
     */
    private static String recordImage(final String id, final String typeCd, final String catCd,
            final String source, final String desc, final String amountImage,
            final String merchantId, final String merchantName, final String merchantCity,
            final String merchantZip, final String cardNum, final String origTs,
            final String procTs) {
        return alphanumeric(id, WIDTH_ID)
                + alphanumeric(typeCd, WIDTH_TYPE_CD)
                + numeric(catCd, WIDTH_CAT_CD)
                + alphanumeric(source, WIDTH_SOURCE)
                + alphanumeric(desc, WIDTH_DESC)
                + amountImage
                + numeric(merchantId, WIDTH_MERCHANT_ID)
                + alphanumeric(merchantName, WIDTH_MERCHANT_NAME)
                + alphanumeric(merchantCity, WIDTH_MERCHANT_CITY)
                + alphanumeric(merchantZip, WIDTH_MERCHANT_ZIP)
                + alphanumeric(cardNum, WIDTH_CARD_NUM)
                + alphanumeric(origTs, WIDTH_ORIG_TS)
                + alphanumeric(procTs, WIDTH_PROC_TS)
                + " ".repeat(WIDTH_FILLER);
    }

    /**
     * Places a value the way a {@code PIC X(n)} field holds it: left-justified, space-padded.
     *
     * @param  value the significant content
     * @param  width the declared field width
     * @return the value at exactly {@code width} characters
     */
    private static String alphanumeric(final String value, final int width) {
        assertThat(value.length())
                .as("the hand-transcribed value '%s' cannot exceed its %d-byte field", value, width)
                .isLessThanOrEqualTo(width);
        return value + " ".repeat(width - value.length());
    }

    /**
     * Places a value the way a {@code PIC 9(n)} field holds it: right-justified, zero-padded.
     *
     * @param  value the significant content
     * @param  width the declared field width
     * @return the value at exactly {@code width} characters
     */
    private static String numeric(final String value, final int width) {
        assertThat(value.length())
                .as("the hand-transcribed value '%s' cannot exceed its %d-byte field", value, width)
                .isLessThanOrEqualTo(width);
        return "0".repeat(width - value.length()) + value;
    }

    /** @return the hand-assembled image of the first reference record */
    private static String firstImage() {
        return recordImage(FIRST_ID, FIRST_TYPE_CD, FIRST_CAT_CD, FIRST_SOURCE, FIRST_DESC,
                FIRST_AMOUNT_IMAGE, FIRST_MERCHANT_ID, FIRST_MERCHANT_NAME, FIRST_MERCHANT_CITY,
                FIRST_MERCHANT_ZIP, FIRST_CARD_NUM, FIRST_ORIG_TS, FIRST_PROC_TS);
    }

    /** @return the hand-assembled image of the second reference record */
    private static String secondImage() {
        return recordImage(SECOND_ID, SECOND_TYPE_CD, SECOND_CAT_CD, SECOND_SOURCE, SECOND_DESC,
                SECOND_AMOUNT_IMAGE, SECOND_MERCHANT_ID, SECOND_MERCHANT_NAME,
                SECOND_MERCHANT_CITY, SECOND_MERCHANT_ZIP, SECOND_CARD_NUM, SECOND_ORIG_TS,
                UNSTAMPED_PROC_TS);
    }

    /**
     * Reassembles the first reference record with a different amount image, for the sign cases.
     *
     * @param  amountImage the eleven-byte zoned-decimal image to place
     * @return the complete image at exactly {@value #RECORD_WIDTH} characters
     */
    private static String imageWithAmount(final String amountImage) {
        return recordImage(FIRST_ID, FIRST_TYPE_CD, FIRST_CAT_CD, FIRST_SOURCE, FIRST_DESC,
                amountImage, FIRST_MERCHANT_ID, FIRST_MERCHANT_NAME, FIRST_MERCHANT_CITY,
                FIRST_MERCHANT_ZIP, FIRST_CARD_NUM, FIRST_ORIG_TS, FIRST_PROC_TS);
    }

    /** @return an entity carrying exactly the first reference record's field values */
    private static Transaction firstEntity() {
        return new Transaction(alphanumeric(FIRST_ID, WIDTH_ID),
                alphanumeric(FIRST_TYPE_CD, WIDTH_TYPE_CD),
                numeric(FIRST_CAT_CD, WIDTH_CAT_CD),
                alphanumeric(FIRST_SOURCE, WIDTH_SOURCE),
                alphanumeric(FIRST_DESC, WIDTH_DESC),
                FIRST_AMOUNT,
                numeric(FIRST_MERCHANT_ID, WIDTH_MERCHANT_ID),
                alphanumeric(FIRST_MERCHANT_NAME, WIDTH_MERCHANT_NAME),
                alphanumeric(FIRST_MERCHANT_CITY, WIDTH_MERCHANT_CITY),
                alphanumeric(FIRST_MERCHANT_ZIP, WIDTH_MERCHANT_ZIP),
                alphanumeric(FIRST_CARD_NUM, WIDTH_CARD_NUM),
                alphanumeric(FIRST_ORIG_TS, WIDTH_ORIG_TS),
                alphanumeric(FIRST_PROC_TS, WIDTH_PROC_TS));
    }

    /**
     * Measures a value the way the record measures it, in encoded bytes rather than characters.
     *
     * @param  value the value to measure
     * @return its US-ASCII encoded length
     */
    private static int encodedWidth(final String value) {
        return value.getBytes(StandardCharsets.US_ASCII).length;
    }

    @Nested
    @DisplayName("the declared geometry")
    class TheDeclaredGeometry {

        @Test
        @DisplayName("all thirteen mapped field offsets equal the zero-based positions the copybook "
                + "declares, and the filler begins where the mapped prefix ends")
        void allThirteenMappedOffsetsEqualTheDeclaredPositions() {
            assertThat(TransactionRecordMapper.TRAN_ID_OFFSET).isEqualTo(OFFSET_ID);
            assertThat(TransactionRecordMapper.TRAN_TYPE_CD_OFFSET).isEqualTo(OFFSET_TYPE_CD);
            assertThat(TransactionRecordMapper.TRAN_CAT_CD_OFFSET).isEqualTo(OFFSET_CAT_CD);
            assertThat(TransactionRecordMapper.TRAN_SOURCE_OFFSET).isEqualTo(OFFSET_SOURCE);
            assertThat(TransactionRecordMapper.TRAN_DESC_OFFSET).isEqualTo(OFFSET_DESC);
            assertThat(TransactionRecordMapper.TRAN_AMT_OFFSET).isEqualTo(OFFSET_AMT);
            assertThat(TransactionRecordMapper.TRAN_MERCHANT_ID_OFFSET)
                    .isEqualTo(OFFSET_MERCHANT_ID);
            assertThat(TransactionRecordMapper.TRAN_MERCHANT_NAME_OFFSET)
                    .isEqualTo(OFFSET_MERCHANT_NAME);
            assertThat(TransactionRecordMapper.TRAN_MERCHANT_CITY_OFFSET)
                    .isEqualTo(OFFSET_MERCHANT_CITY);
            assertThat(TransactionRecordMapper.TRAN_MERCHANT_ZIP_OFFSET)
                    .isEqualTo(OFFSET_MERCHANT_ZIP);
            assertThat(TransactionRecordMapper.TRAN_CARD_NUM_OFFSET).isEqualTo(OFFSET_CARD_NUM);
            assertThat(TransactionRecordMapper.TRAN_ORIG_TS_OFFSET).isEqualTo(OFFSET_ORIG_TS);
            assertThat(TransactionRecordMapper.TRAN_PROC_TS_OFFSET).isEqualTo(OFFSET_PROC_TS);
            assertThat(TransactionRecordMapper.FILLER_OFFSET).isEqualTo(OFFSET_FILLER);
        }

        @Test
        @DisplayName("all thirteen mapped field lengths equal the byte widths the copybook declares, "
                + "and the filler run is twenty bytes")
        void allThirteenMappedLengthsEqualTheDeclaredWidths() {
            assertThat(TransactionRecordMapper.TRAN_ID_LENGTH).isEqualTo(WIDTH_ID);
            assertThat(TransactionRecordMapper.TRAN_TYPE_CD_LENGTH).isEqualTo(WIDTH_TYPE_CD);
            assertThat(TransactionRecordMapper.TRAN_CAT_CD_LENGTH).isEqualTo(WIDTH_CAT_CD);
            assertThat(TransactionRecordMapper.TRAN_SOURCE_LENGTH).isEqualTo(WIDTH_SOURCE);
            assertThat(TransactionRecordMapper.TRAN_DESC_LENGTH).isEqualTo(WIDTH_DESC);
            assertThat(TransactionRecordMapper.TRAN_AMT_LENGTH).isEqualTo(WIDTH_AMT);
            assertThat(TransactionRecordMapper.TRAN_MERCHANT_ID_LENGTH)
                    .isEqualTo(WIDTH_MERCHANT_ID);
            assertThat(TransactionRecordMapper.TRAN_MERCHANT_NAME_LENGTH)
                    .isEqualTo(WIDTH_MERCHANT_NAME);
            assertThat(TransactionRecordMapper.TRAN_MERCHANT_CITY_LENGTH)
                    .isEqualTo(WIDTH_MERCHANT_CITY);
            assertThat(TransactionRecordMapper.TRAN_MERCHANT_ZIP_LENGTH)
                    .isEqualTo(WIDTH_MERCHANT_ZIP);
            assertThat(TransactionRecordMapper.TRAN_CARD_NUM_LENGTH).isEqualTo(WIDTH_CARD_NUM);
            assertThat(TransactionRecordMapper.TRAN_ORIG_TS_LENGTH).isEqualTo(WIDTH_ORIG_TS);
            assertThat(TransactionRecordMapper.TRAN_PROC_TS_LENGTH).isEqualTo(WIDTH_PROC_TS);
            assertThat(TransactionRecordMapper.FILLER_LENGTH).isEqualTo(WIDTH_FILLER);
        }

        @Test
        @DisplayName("the record is 350 bytes: a 330-byte mapped prefix plus a 20-byte filler run, "
                + "and the fourteen declared widths sum to exactly that")
        void theRecordIsThreeHundredAndFiftyBytesWide() {
            final int sumOfWidths = WIDTH_ID + WIDTH_TYPE_CD + WIDTH_CAT_CD + WIDTH_SOURCE
                    + WIDTH_DESC + WIDTH_AMT + WIDTH_MERCHANT_ID + WIDTH_MERCHANT_NAME
                    + WIDTH_MERCHANT_CITY + WIDTH_MERCHANT_ZIP + WIDTH_CARD_NUM + WIDTH_ORIG_TS
                    + WIDTH_PROC_TS + WIDTH_FILLER;

            assertThat(sumOfWidths)
                    .as("the copybook's own widths must account for the whole declared record")
                    .isEqualTo(RECORD_WIDTH);
            assertThat(TransactionRecordMapper.RECORD_LENGTH).isEqualTo(RECORD_WIDTH);
            assertThat(TransactionRecordMapper.MAPPED_DATA_LENGTH).isEqualTo(MAPPED_WIDTH);
        }

        @Test
        @DisplayName("the amount occupies eleven bytes, being nine integer digits plus two implied "
                + "decimals with the sign overpunched into the last of them")
        void theAmountOccupiesElevenBytes() {
            assertThat(TransactionRecordMapper.TRAN_AMT_LENGTH)
                    .as("nine integer digits and two decimals occupy eleven bytes; the sign is "
                            + "overpunched rather than carried separately")
                    .isEqualTo(WIDTH_AMT)
                    .isEqualTo(ZonedDecimalCodec.TRANSACTION_AMOUNT_WIDTH);
        }

        @Test
        @DisplayName("the three one-based sort columns the job streams address are exactly one more "
                + "than the zero-based offsets of the same bytes")
        void theOneBasedSortColumnsAgreeWithTheZeroBasedOffsets() {
            // The two addressing conventions coexist in this estate and confusing them produces code
            // that compiles and reads one byte off, so the relationship is asserted rather than
            // trusted: each literal below is the column the job stream carries.
            assertThat(TransactionRecordMapper.TRAN_CARD_NUM_ONE_BASED_SORT_POSITION)
                    .isEqualTo(SORT_COLUMN_CARD_NUM)
                    .isEqualTo(OFFSET_CARD_NUM + 1);
            assertThat(TransactionRecordMapper.TRAN_ORIG_TS_ONE_BASED_SORT_POSITION)
                    .isEqualTo(SORT_COLUMN_ORIG_TS)
                    .isEqualTo(OFFSET_ORIG_TS + 1);
            assertThat(TransactionRecordMapper.TRAN_PROC_TS_ONE_BASED_SORT_POSITION)
                    .isEqualTo(SORT_COLUMN_PROC_DT)
                    .isEqualTo(OFFSET_PROC_TS + 1);
        }

        @Test
        @DisplayName("the processing date the report sort addresses is the leading ten bytes of the "
                + "twenty-six-byte processing timestamp, not a field of its own")
        void theProcessingDateIsTheLeadingPartOfTheProcessingTimestamp() {
            assertThat(TransactionRecordMapper.TRAN_PROC_DT_OFFSET)
                    .as("the date shares the timestamp's first byte")
                    .isEqualTo(OFFSET_PROC_TS);
            assertThat(TransactionRecordMapper.TRAN_PROC_DT_LENGTH)
                    .as("the sort symbol gives the date ten bytes")
                    .isEqualTo(WIDTH_PROC_DT)
                    .isLessThan(WIDTH_PROC_TS);
            assertThat(TransactionRecordMapper.TRAN_PROC_DT_ONE_BASED_SORT_POSITION)
                    .isEqualTo(SORT_COLUMN_PROC_DT);
        }

        @Test
        @DisplayName("the filler run is emitted as spaces")
        void theFillerRunIsEmittedAsSpaces() {
            assertThat(TransactionRecordMapper.FILLER_CHARACTER).isEqualTo(' ');
        }

        @Test
        @DisplayName("the artefact name identifies the record group and its copybook, so a diagnostic "
                + "names the layout rather than leaving it to be inferred from a width")
        void theArtefactNameIdentifiesTheRecordGroupAndItsCopybook() {
            assertThat(TransactionRecordMapper.ARTEFACT)
                    .contains("TRAN-RECORD")
                    .contains("CVTRA05Y");
        }

        @Test
        @DisplayName("the hand-assembled oracle images are themselves exactly 350 bytes, so a fault "
                + "in this class's own helpers cannot be mistaken for a fault in the mapper")
        void theHandAssembledOracleImagesAreThreeHundredAndFiftyBytes() {
            assertThat(encodedWidth(firstImage())).isEqualTo(RECORD_WIDTH);
            assertThat(encodedWidth(secondImage())).isEqualTo(RECORD_WIDTH);
            assertThat(encodedWidth(FIRST_AMOUNT_IMAGE)).isEqualTo(WIDTH_AMT);
            assertThat(encodedWidth(SECOND_AMOUNT_IMAGE)).isEqualTo(WIDTH_AMT);
            assertThat(encodedWidth(UNSTAMPED_PROC_TS)).isEqualTo(WIDTH_PROC_TS);
        }
    }

    @Nested
    @DisplayName("reading a record image")
    class ReadingARecordImage {

        @Test
        @DisplayName("all thirteen properties of the first reference record map to their hand-derived "
                + "values, at their full declared widths")
        void allThirteenPropertiesMapToTheirHandDerivedValues() {
            final Transaction mapped = TransactionRecordMapper.fromRecord(firstImage());

            assertThat(mapped.getTranId()).isEqualTo(alphanumeric(FIRST_ID, WIDTH_ID));
            assertThat(mapped.getTranTypeCd()).isEqualTo(alphanumeric(FIRST_TYPE_CD, WIDTH_TYPE_CD));
            assertThat(mapped.getTranCatCd()).isEqualTo(numeric(FIRST_CAT_CD, WIDTH_CAT_CD));
            assertThat(mapped.getTranSource()).isEqualTo(alphanumeric(FIRST_SOURCE, WIDTH_SOURCE));
            assertThat(mapped.getTranDesc()).isEqualTo(alphanumeric(FIRST_DESC, WIDTH_DESC));
            assertThat(mapped.getTranAmt()).isEqualByComparingTo(FIRST_AMOUNT);
            assertThat(mapped.getMerchantId())
                    .isEqualTo(numeric(FIRST_MERCHANT_ID, WIDTH_MERCHANT_ID));
            assertThat(mapped.getMerchantName())
                    .isEqualTo(alphanumeric(FIRST_MERCHANT_NAME, WIDTH_MERCHANT_NAME));
            assertThat(mapped.getMerchantCity())
                    .isEqualTo(alphanumeric(FIRST_MERCHANT_CITY, WIDTH_MERCHANT_CITY));
            assertThat(mapped.getMerchantZip())
                    .isEqualTo(alphanumeric(FIRST_MERCHANT_ZIP, WIDTH_MERCHANT_ZIP));
            assertThat(mapped.getTranCardNum())
                    .isEqualTo(alphanumeric(FIRST_CARD_NUM, WIDTH_CARD_NUM));
            assertThat(mapped.getTranOrigTs()).isEqualTo(alphanumeric(FIRST_ORIG_TS, WIDTH_ORIG_TS));
            assertThat(mapped.getTranProcTs()).isEqualTo(alphanumeric(FIRST_PROC_TS, WIDTH_PROC_TS));
        }

        @Test
        @DisplayName("every character field arrives at its full declared byte width, padding included, "
                + "because a fixed-width field is never trimmed on the way in")
        void everyCharacterFieldArrivesAtItsFullDeclaredWidth() {
            final Transaction mapped = TransactionRecordMapper.fromRecord(firstImage());

            assertThat(encodedWidth(mapped.getTranId())).isEqualTo(WIDTH_ID);
            assertThat(encodedWidth(mapped.getTranTypeCd())).isEqualTo(WIDTH_TYPE_CD);
            assertThat(encodedWidth(mapped.getTranCatCd())).isEqualTo(WIDTH_CAT_CD);
            assertThat(encodedWidth(mapped.getTranSource())).isEqualTo(WIDTH_SOURCE);
            assertThat(encodedWidth(mapped.getTranDesc())).isEqualTo(WIDTH_DESC);
            assertThat(encodedWidth(mapped.getMerchantId())).isEqualTo(WIDTH_MERCHANT_ID);
            assertThat(encodedWidth(mapped.getMerchantName())).isEqualTo(WIDTH_MERCHANT_NAME);
            assertThat(encodedWidth(mapped.getMerchantCity())).isEqualTo(WIDTH_MERCHANT_CITY);
            assertThat(encodedWidth(mapped.getMerchantZip())).isEqualTo(WIDTH_MERCHANT_ZIP);
            assertThat(encodedWidth(mapped.getTranCardNum())).isEqualTo(WIDTH_CARD_NUM);
            assertThat(encodedWidth(mapped.getTranOrigTs())).isEqualTo(WIDTH_ORIG_TS);
            assertThat(encodedWidth(mapped.getTranProcTs())).isEqualTo(WIDTH_PROC_TS);
        }

        @Test
        @DisplayName("the four merchant properties are unprefixed on this entity, which is the naming "
                + "asymmetry that keeps it distinct from the parallel daily-transaction layout")
        void theFourMerchantPropertiesAreUnprefixed() {
            // Recorded as behaviour rather than as a comment: the two layouts share a geometry and are
            // deliberately not merged, and tidying this asymmetry away would be the first step towards
            // merging them.
            final Transaction mapped = TransactionRecordMapper.fromRecord(firstImage());

            assertThat(mapped.getMerchantId()).isNotBlank();
            assertThat(mapped.getMerchantName()).isNotBlank();
            assertThat(mapped.getMerchantCity()).isNotBlank();
            assertThat(mapped.getMerchantZip()).isNotBlank();
        }

        @Test
        @DisplayName("leading zeros survive on the category code and the merchant identifier, because "
                + "both are carried as text rather than parsed into a number")
        void leadingZerosSurviveOnTheNumericIdentifiers() {
            final Transaction mapped = TransactionRecordMapper.fromRecord(firstImage());

            assertThat(mapped.getTranCatCd()).startsWith("000").isEqualTo("0001");
            assertThat(mapped.getMerchantId()).startsWith("000").isEqualTo("000123456");
            assertThat(mapped.getTranId()).startsWith("0000000000");
        }

        @Test
        @DisplayName("both timestamps are carried as raw twenty-six-byte text, neither parsed nor "
                + "normalised")
        void bothTimestampsAreCarriedAsRawText() {
            final Transaction mapped = TransactionRecordMapper.fromRecord(firstImage());

            assertThat(mapped.getTranOrigTs()).isEqualTo(FIRST_ORIG_TS);
            assertThat(mapped.getTranProcTs()).isEqualTo(FIRST_PROC_TS);
        }

        @Test
        @DisplayName("an unstamped processing timestamp arrives as exactly twenty-six spaces - not "
                + "null, not empty, not trimmed - because no temporal type can hold that state")
        void anUnstampedProcessingTimestampArrivesAsTwentySixSpaces() {
            final Transaction mapped = TransactionRecordMapper.fromRecord(secondImage());

            assertThat(mapped.getTranProcTs())
                    .isNotNull()
                    .isNotEmpty()
                    .isEqualTo(UNSTAMPED_PROC_TS)
                    .isBlank();
            assertThat(encodedWidth(mapped.getTranProcTs())).isEqualTo(WIDTH_PROC_TS);
        }

        @Test
        @DisplayName("the card number lands at the byte the external sort addresses, so a displaced "
                + "field would fail here rather than silently mis-sorting a report")
        void theCardNumberLandsAtTheByteTheExternalSortAddresses() {
            final String image = firstImage();

            assertThat(image.substring(SORT_COLUMN_CARD_NUM - 1,
                            SORT_COLUMN_CARD_NUM - 1 + WIDTH_CARD_NUM))
                    .isEqualTo(alphanumeric(FIRST_CARD_NUM, WIDTH_CARD_NUM));
            assertThat(TransactionRecordMapper.fromRecord(image).getTranCardNum())
                    .isEqualTo(image.substring(SORT_COLUMN_CARD_NUM - 1,
                            SORT_COLUMN_CARD_NUM - 1 + WIDTH_CARD_NUM));
        }

        @Test
        @DisplayName("the processing date the report sort filters on is the first ten bytes of the "
                + "processing timestamp the mapper reads")
        void theProcessingDateIsTheFirstTenBytesOfTheProcessingTimestamp() {
            final Transaction mapped = TransactionRecordMapper.fromRecord(firstImage());

            assertThat(mapped.getTranProcTs().substring(0, WIDTH_PROC_DT))
                    .isEqualTo("2022-07-20");
            assertThat(firstImage().substring(SORT_COLUMN_PROC_DT - 1,
                            SORT_COLUMN_PROC_DT - 1 + WIDTH_PROC_DT))
                    .isEqualTo("2022-07-20");
        }
    }

    @Nested
    @DisplayName("the zoned-decimal amount and its overpunched sign")
    class TheZonedDecimalAmount {

        @Test
        @DisplayName("a positive overpunch contributes a digit as well as a sign: an image ending F "
                + "is a positive value whose final digit is six")
        void aPositiveOverpunchContributesADigitAsWellAsASign() {
            final Transaction mapped = TransactionRecordMapper.fromRecord(firstImage());

            assertThat(FIRST_AMOUNT_IMAGE).endsWith("F");
            assertThat(mapped.getTranAmt())
                    .as("F is the seventh positive form, so it carries the digit six as well as the "
                            + "sign")
                    .isEqualByComparingTo(FIRST_AMOUNT)
                    .isPositive();
        }

        @Test
        @DisplayName("a negative overpunch carries the sign in the final byte: an image ending in a "
                + "closing brace is a negative value whose final digit is zero")
        void aNegativeOverpunchCarriesTheSignInTheFinalByte() {
            final Transaction mapped = TransactionRecordMapper.fromRecord(secondImage());

            assertThat(SECOND_AMOUNT_IMAGE).endsWith("}");
            assertThat(mapped.getTranAmt())
                    .isEqualByComparingTo(SECOND_AMOUNT)
                    .isNegative();
        }

        @Test
        @DisplayName("a negatively signed all-zero image decodes to zero at scale two, because "
                + "negative zero is a representable image and zero is its value")
        void aNegativelySignedAllZeroImageDecodesToZero() {
            final Transaction mapped =
                    TransactionRecordMapper.fromRecord(imageWithAmount("0000000000}"));

            assertThat(mapped.getTranAmt()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(mapped.getTranAmt().scale()).isEqualTo(ZonedDecimalCodec.MONETARY_SCALE);
        }

        @Test
        @DisplayName("all twenty sign characters decode to the digit and sign the convention gives "
                + "them, so no form is reachable in the estate that this mapper cannot read")
        void allTwentySignCharactersDecodeCorrectly() {
            // The final byte carries the low-order digit AND the sign, and the estate's fixtures reach
            // every one of the twenty forms. Each expectation is composed from a decimal string
            // literal, never from a production constant.
            final String leadingDigits = "0000012345";
            for (int digit = 0; digit <= 9; digit++) {
                final char positiveSign = POSITIVE_OVERPUNCH.charAt(digit);
                final char negativeSign = NEGATIVE_OVERPUNCH.charAt(digit);

                assertThat(TransactionRecordMapper
                                .fromRecord(imageWithAmount(leadingDigits + positiveSign))
                                .getTranAmt())
                        .as("the positive form of digit %d", digit)
                        .isEqualByComparingTo(new BigDecimal("1234.5" + digit));
                assertThat(TransactionRecordMapper
                                .fromRecord(imageWithAmount(leadingDigits + negativeSign))
                                .getTranAmt())
                        .as("the negative form of digit %d", digit)
                        .isEqualByComparingTo(new BigDecimal("-1234.5" + digit));
            }
        }

        @Test
        @DisplayName("an unsigned trailing digit reads as a positive digit, so a field written by a "
                + "producer that emitted no overpunch is still read correctly")
        void anUnsignedTrailingDigitIsReadAsPositive() {
            final Transaction mapped =
                    TransactionRecordMapper.fromRecord(imageWithAmount("00000123456"));

            assertThat(mapped.getTranAmt()).isEqualByComparingTo(FIRST_AMOUNT);
        }

        @Test
        @DisplayName("every decoded amount carries scale exactly two, because a store into a two-decimal"
                + " field truncates toward zero and never rounds")
        void everyDecodedAmountCarriesScaleExactlyTwo() {
            assertThat(TransactionRecordMapper.fromRecord(firstImage()).getTranAmt().scale())
                    .isEqualTo(ZonedDecimalCodec.MONETARY_SCALE);
            assertThat(TransactionRecordMapper.fromRecord(secondImage()).getTranAmt().scale())
                    .isEqualTo(ZonedDecimalCodec.MONETARY_SCALE);
            assertThat(ZonedDecimalCodec.COBOL_TRUNCATION_MODE.name())
                    .as("the estate specifies no rounding clause anywhere, so a store truncates")
                    .isEqualTo("DOWN");
        }

        @Test
        @DisplayName("the sign of the amount tracks the transaction type in the reference records: a "
                + "purchase is positive and an operator-entered return is negative")
        void theSignOfTheAmountTracksTheTransactionType() {
            assertThat(TransactionRecordMapper.fromRecord(firstImage()).getTranAmt()).isPositive();
            assertThat(TransactionRecordMapper.fromRecord(firstImage()).getTranSource())
                    .startsWith(FIRST_SOURCE);
            assertThat(TransactionRecordMapper.fromRecord(secondImage()).getTranAmt()).isNegative();
            assertThat(TransactionRecordMapper.fromRecord(secondImage()).getTranSource())
                    .startsWith(SECOND_SOURCE);
        }
    }

    @Nested
    @DisplayName("emitting a record image")
    class EmittingARecordImage {

        @Test
        @DisplayName("the 330-byte mapped prefix is reproduced byte for byte")
        void theMappedPrefixIsReproducedByteForByte() {
            final Transaction mapped = TransactionRecordMapper.fromRecord(firstImage());

            assertThat(TransactionRecordMapper.toRecord(mapped).substring(0, MAPPED_WIDTH))
                    .isEqualTo(firstImage().substring(0, MAPPED_WIDTH));
        }

        @Test
        @DisplayName("the whole 350-byte image is reproduced, the twenty filler bytes beyond the "
                + "mapped prefix included, because this mapper emits them as spaces")
        void theWholeImageIsReproducedWithSpaceFiller() {
            final Transaction mapped = TransactionRecordMapper.fromRecord(firstImage());
            final String emitted = TransactionRecordMapper.toRecord(mapped);

            assertThat(emitted).isEqualTo(firstImage());
            assertThat(emitted.substring(OFFSET_FILLER)).isEqualTo(" ".repeat(WIDTH_FILLER));
            assertThat(encodedWidth(emitted)).isEqualTo(RECORD_WIDTH);
        }

        @Test
        @DisplayName("the byte-emitting entry point produces the same 350 bytes as the hand-assembled "
                + "image, and returns an array a caller can hold")
        void theByteEmitterProducesTheHandAssembledBytes() {
            final Transaction mapped = TransactionRecordMapper.fromRecord(firstImage());
            final byte[] emitted = TransactionRecordMapper.toRecordBytes(mapped);

            assertThat(emitted).hasSize(RECORD_WIDTH);
            assertThat(emitted).isEqualTo(firstImage().getBytes(StandardCharsets.US_ASCII));
            assertThat(TransactionRecordMapper.toRecordBytes(mapped))
                    .as("each call must return a fresh array rather than a shared one")
                    .isNotSameAs(emitted);
        }

        @Test
        @DisplayName("an entity built through the public thirteen-argument constructor emits the "
                + "reference image, so the encoding path does not depend on having decoded first")
        void anEntityBuiltThroughThePublicConstructorEmitsTheReferenceImage() {
            assertThat(TransactionRecordMapper.toRecord(firstEntity())).isEqualTo(firstImage());
        }

        @Test
        @DisplayName("an unstamped processing timestamp is re-emitted as twenty-six spaces rather "
                + "than being collapsed, so a record written before processing round-trips")
        void anUnstampedProcessingTimestampIsReEmittedAsSpaces() {
            final Transaction mapped = TransactionRecordMapper.fromRecord(secondImage());
            final String emitted = TransactionRecordMapper.toRecord(mapped);

            assertThat(emitted.substring(OFFSET_PROC_TS, OFFSET_PROC_TS + WIDTH_PROC_TS))
                    .isEqualTo(UNSTAMPED_PROC_TS);
            assertThat(emitted).isEqualTo(secondImage());
        }

        @Test
        @DisplayName("the negatively signed reference record round-trips too, its closing-brace sign "
                + "byte restored in the last byte of the amount field")
        void theNegativelySignedReferenceRecordRoundTrips() {
            final Transaction mapped = TransactionRecordMapper.fromRecord(secondImage());
            final String emitted = TransactionRecordMapper.toRecord(mapped);

            assertThat(emitted.substring(OFFSET_AMT, OFFSET_AMT + WIDTH_AMT))
                    .isEqualTo(SECOND_AMOUNT_IMAGE);
            assertThat(emitted.charAt(OFFSET_AMT + WIDTH_AMT - 1)).isEqualTo('}');
        }

        @Test
        @DisplayName("a numeric field's significant leading zeros are restored on the way out, so a "
                + "category code and a merchant identifier are never narrowed")
        void significantLeadingZerosAreRestoredOnTheWayOut() {
            final Transaction mapped = TransactionRecordMapper.fromRecord(firstImage());
            final String emitted = TransactionRecordMapper.toRecord(mapped);

            assertThat(emitted.substring(OFFSET_CAT_CD, OFFSET_CAT_CD + WIDTH_CAT_CD))
                    .isEqualTo("0001");
            assertThat(emitted.substring(OFFSET_MERCHANT_ID,
                            OFFSET_MERCHANT_ID + WIDTH_MERCHANT_ID))
                    .isEqualTo("000123456");
        }
    }

    @Nested
    @DisplayName("the three reading entry points")
    class TheThreeReadingEntryPoints {

        @Test
        @DisplayName("the string, byte-array and byte-range entry points produce equal entities from "
                + "the same bytes")
        void theThreeEntryPointsProduceEqualEntities() {
            final String image = firstImage();
            final byte[] bytes = image.getBytes(StandardCharsets.US_ASCII);

            final Transaction fromString = TransactionRecordMapper.fromRecord(image);
            final Transaction fromBytes = TransactionRecordMapper.fromRecord(bytes);
            final Transaction fromRange = TransactionRecordMapper.fromRecord(bytes, 0);

            assertThat(TransactionRecordMapper.toRecord(fromBytes))
                    .isEqualTo(TransactionRecordMapper.toRecord(fromString));
            assertThat(TransactionRecordMapper.toRecord(fromRange))
                    .isEqualTo(TransactionRecordMapper.toRecord(fromString));
            assertThat(fromRange.getTranId()).isEqualTo(fromString.getTranId());
            assertThat(fromRange.getTranAmt()).isEqualByComparingTo(fromString.getTranAmt());
        }

        @Test
        @DisplayName("the byte-range entry point selects one record out of a buffer holding many at a "
                + "351-byte stride, leaving each record separator behind")
        void theByteRangeEntryPointSelectsOneRecordFromABuffer() {
            // A newline-terminated file has a stride one greater than the record, so record i begins
            // at i * (350 + 1). Stride arithmetic belongs to the caller, and this proves the mapper
            // honours the index it is given rather than assuming a record boundary.
            final int stride = RECORD_WIDTH + 1;
            final List<String> images = List.of(firstImage(), secondImage(), firstImage());
            final StringBuilder buffer = new StringBuilder();
            for (final String image : images) {
                buffer.append(image).append('\n');
            }
            final byte[] bytes = buffer.toString().getBytes(StandardCharsets.US_ASCII);

            final List<String> recovered = new ArrayList<>();
            for (int index = 0; index < images.size(); index++) {
                recovered.add(TransactionRecordMapper
                        .toRecord(TransactionRecordMapper.fromRecord(bytes, index * stride)));
            }

            assertThat(recovered).containsExactlyElementsOf(images);
        }
    }

    @Nested
    @DisplayName("refusing a malformed image")
    class RefusingAMalformedImage {

        @Test
        @DisplayName("an image one byte short is refused rather than padded, and the diagnostic names "
                + "the layout and both widths")
        void anImageOneByteShortIsRefused() {
            final String tooShort = firstImage().substring(0, RECORD_WIDTH - 1);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> TransactionRecordMapper.fromRecord(tooShort))
                    .withMessageContaining("CVTRA05Y")
                    .withMessageContaining(String.valueOf(RECORD_WIDTH))
                    .withMessageContaining(String.valueOf(RECORD_WIDTH - 1));
        }

        @Test
        @DisplayName("an image one byte long is refused rather than truncated, and the diagnostic "
                + "names an unstripped record separator as the likely cause")
        void anImageOneByteLongIsRefused() {
            final String tooLong = firstImage() + "\n";

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> TransactionRecordMapper.fromRecord(tooLong))
                    .withMessageContaining(String.valueOf(RECORD_WIDTH + 1))
                    .withMessageContaining("terminator");
        }

        @Test
        @DisplayName("a byte array of the wrong length is refused on the same terms as a string")
        void aByteArrayOfTheWrongLengthIsRefused() {
            final byte[] tooShort = firstImage().substring(0, RECORD_WIDTH - 1)
                    .getBytes(StandardCharsets.US_ASCII);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> TransactionRecordMapper.fromRecord(tooShort))
                    .withMessageContaining(String.valueOf(RECORD_WIDTH));
        }

        @Test
        @DisplayName("a byte range that does not lie wholly inside its buffer is refused, and a "
                + "negative index is refused too")
        void aByteRangeOutsideItsBufferIsRefused() {
            final byte[] oneRecord = firstImage().getBytes(StandardCharsets.US_ASCII);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> TransactionRecordMapper.fromRecord(oneRecord, 1));
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> TransactionRecordMapper.fromRecord(oneRecord, -1));
        }

        @Test
        @DisplayName("an absent image is refused on every entry point rather than yielding a partly "
                + "populated entity")
        void anAbsentImageIsRefusedOnEveryEntryPoint() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> TransactionRecordMapper.fromRecord((String) null));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> TransactionRecordMapper.fromRecord((byte[]) null));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> TransactionRecordMapper.fromRecord(null, 0));
        }

        @Test
        @DisplayName("an absent entity, and an entity with an absent mapped property, are both refused "
                + "on the emitting path, the diagnostic naming the field rather than the value")
        void anAbsentEntityOrPropertyIsRefusedOnTheEmittingPath() {
            final Transaction missingAmount = firstEntity();
            missingAmount.setTranAmt(null);
            final Transaction missingDescription = firstEntity();
            missingDescription.setTranDesc(null);

            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> TransactionRecordMapper.toRecord(null));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> TransactionRecordMapper.toRecord(missingAmount))
                    .withMessageContaining("TRAN-AMT");
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> TransactionRecordMapper.toRecordBytes(missingDescription))
                    .withMessageContaining("TRAN-DESC");
        }

        @Test
        @DisplayName("a value wider than its field is refused rather than silently truncated, because "
                + "a truncated value is a wrong value that looks right")
        void aValueWiderThanItsFieldIsRefused() {
            final Transaction overWide = firstEntity();
            overWide.setTranTypeCd("012");

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> TransactionRecordMapper.toRecord(overWide));
        }

        @Test
        @DisplayName("an amount needing more integer digits than the field provides is refused rather "
                + "than narrowed, which diverges deliberately from the legacy silent truncation")
        void anAmountTooWideForItsFieldIsRefused() {
            final Transaction tooLarge = firstEntity();
            tooLarge.setTranAmt(new BigDecimal("1234567890.12"));

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> TransactionRecordMapper.toRecord(tooLarge));
        }

        @Test
        @DisplayName("an amount field carrying a character the zoned convention does not define is "
                + "refused, the diagnostic naming the COBOL field")
        void aMalformedAmountImageIsRefused() {
            final String malformed = imageWithAmount("0000012345*");

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> TransactionRecordMapper.fromRecord(malformed))
                    .withMessageContaining("TRAN-AMT");
        }
    }

    @Nested
    @DisplayName("the layout is self-consistent at class initialisation")
    class TheLayoutIsSelfConsistent {

        @Test
        @DisplayName("the published constants form one contiguous run with no gap and no overlap, so "
                + "a field cannot be inserted without the widths ceasing to sum")
        void thePublishedConstantsFormOneContiguousRun() {
            final int[][] fields = {
                {TransactionRecordMapper.TRAN_ID_OFFSET, TransactionRecordMapper.TRAN_ID_LENGTH},
                {TransactionRecordMapper.TRAN_TYPE_CD_OFFSET,
                        TransactionRecordMapper.TRAN_TYPE_CD_LENGTH},
                {TransactionRecordMapper.TRAN_CAT_CD_OFFSET,
                        TransactionRecordMapper.TRAN_CAT_CD_LENGTH},
                {TransactionRecordMapper.TRAN_SOURCE_OFFSET,
                        TransactionRecordMapper.TRAN_SOURCE_LENGTH},
                {TransactionRecordMapper.TRAN_DESC_OFFSET,
                        TransactionRecordMapper.TRAN_DESC_LENGTH},
                {TransactionRecordMapper.TRAN_AMT_OFFSET, TransactionRecordMapper.TRAN_AMT_LENGTH},
                {TransactionRecordMapper.TRAN_MERCHANT_ID_OFFSET,
                        TransactionRecordMapper.TRAN_MERCHANT_ID_LENGTH},
                {TransactionRecordMapper.TRAN_MERCHANT_NAME_OFFSET,
                        TransactionRecordMapper.TRAN_MERCHANT_NAME_LENGTH},
                {TransactionRecordMapper.TRAN_MERCHANT_CITY_OFFSET,
                        TransactionRecordMapper.TRAN_MERCHANT_CITY_LENGTH},
                {TransactionRecordMapper.TRAN_MERCHANT_ZIP_OFFSET,
                        TransactionRecordMapper.TRAN_MERCHANT_ZIP_LENGTH},
                {TransactionRecordMapper.TRAN_CARD_NUM_OFFSET,
                        TransactionRecordMapper.TRAN_CARD_NUM_LENGTH},
                {TransactionRecordMapper.TRAN_ORIG_TS_OFFSET,
                        TransactionRecordMapper.TRAN_ORIG_TS_LENGTH},
                {TransactionRecordMapper.TRAN_PROC_TS_OFFSET,
                        TransactionRecordMapper.TRAN_PROC_TS_LENGTH},
                {TransactionRecordMapper.FILLER_OFFSET, TransactionRecordMapper.FILLER_LENGTH},
            };

            int expectedOffset = 0;
            for (final int[] field : fields) {
                assertThat(field[0])
                        .as("field beginning at %d must follow the previous field with no gap",
                                field[0])
                        .isEqualTo(expectedOffset);
                expectedOffset += field[1];
            }

            assertThat(expectedOffset)
                    .as("the fourteen published widths must account for the whole record")
                    .isEqualTo(RECORD_WIDTH);
            assertThat(Arrays.stream(fields).mapToInt(field -> field[1]).sum())
                    .isEqualTo(RECORD_WIDTH);
        }
    }
}
