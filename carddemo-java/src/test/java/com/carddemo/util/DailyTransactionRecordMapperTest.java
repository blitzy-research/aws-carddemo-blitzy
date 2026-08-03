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

import com.carddemo.domain.DailyTransaction;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Unit test for {@link DailyTransactionRecordMapper}, the two-way mapping between the 350-byte
 * daily-transaction record image and {@link DailyTransaction}.
 *
 * <p><strong>This is the primary end-to-end input layout.</strong> The daily-transaction dataset is
 * the production-representative file that drives the posting run, and it is the largest reference
 * input in the estate: 105,300 bytes, three hundred records at a 351-byte stride, being a 350-byte
 * record plus one record separator that is never record content. Byte fidelity here is load-bearing
 * for the byte-equivalence gate, and it is load-bearing twice over, because a record that fails
 * validation downstream is echoed into the reject output <em>unchanged</em> - the 350-byte source
 * image is copied verbatim and a trailer appended - so any distortion introduced while reading this
 * layout reappears in a second contractual output. Producing that reject output, its reason codes and
 * the posting validation cascade all belong to the batch step tier and none of it is exercised or
 * emulated here.
 *
 * <p><strong>Every expectation below is hand-derived and independent of the code under test.</strong>
 * The two fixture records are transcribed here as literal field values and reassembled by this
 * class's own padding helpers, which reimplement the justification rules a {@code PIC X(n)} and a
 * {@code PIC 9(n)} field receive. No assertion calls a production method to compute the value it then
 * checks, no output is snapshotted, and no offset constant appears on both sides of a comparison -
 * every offset and length is asserted against the integer literal the copybook declares, so a
 * uniformly shifted layout cannot pass. The reassembled images were themselves verified byte for byte
 * against the shipped file, and this class re-checks their width on every run.
 *
 * <p><strong>Two entities share one geometry and are deliberately kept apart.</strong> This layout is
 * field-for-field parallel with the posted-transaction layout - same order, same offsets, same widths
 * - yet the two are separate entities over separate tables, because they are separate datasets with
 * separate lifecycles: the posted record was a keyed cluster, while this one arrives as a sequential
 * dataset. The two are not merged, neither subclasses the other, and no shared base class, shared
 * offset holder, shared helper or shared test base is extracted; this test imports neither the
 * sibling mapper nor the sibling entity. The visible consequence is a naming asymmetry that must not
 * be tidied: <em>all thirteen</em> properties here carry the {@code dalytran} prefix, including all
 * four merchant properties, which the sibling leaves unprefixed.
 *
 * <p><strong>The amount is the estate's only complete overpunch oracle.</strong> Its sign is
 * overpunched into the final byte of an eleven-byte zoned-decimal image, and across the three hundred
 * shipped records all twenty sign characters occur - the ten positive forms and the ten negative
 * forms. Decoding truncates toward zero at scale two, never rounding: no arithmetic statement in the
 * estate specifies rounding, and a store without a rounding clause truncates. This test names no
 * rounding mode, never rescales a value and builds every expected amount from a decimal string
 * literal, never from a binary floating-point value.
 *
 * <p><strong>Timestamps stay raw twenty-six-byte text.</strong> Nothing here parses, formats or
 * normalises one. All three hundred processing timestamps are twenty-six spaces, which no temporal
 * type can hold and which must therefore survive as spaces rather than becoming null, empty or
 * trimmed. All three hundred origination timestamps carry one identical value, which bounds what this
 * input can demonstrate: a date window over it admits every record or none, so date-window filtering
 * cannot be exercised from this fixture and needs a separately constructed input. That construction
 * belongs to the batch tier and no date-range behaviour is asserted here.
 *
 * <p><strong>This geometry has two legacy readers, not one.</strong> Besides the posting program that
 * the reference input drives, a second complete batch program - 491 lines across eighteen paragraphs -
 * reads this same record layout, and no job member, cataloged procedure or online resource definition
 * anywhere in the estate invokes it. That orphan is a documented source anomaly rather than dead code
 * to drop: it is migrated behind a job that is defined but excluded from the default pipeline and
 * exercised only by tests. It is recorded here purely as corroboration that this layout is read from
 * two places and so must be mapped once, correctly, for both; nothing is implemented for it in this
 * file.
 *
 * <p>Provenance: checkout SHA {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release
 * stamp {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. Layout authority is copybook
 * {@code app/cpy/CVTRA06Y.cpy}; the reference input is {@code app/data/ASCII/dailytran.txt}. No
 * legacy source line is transcribed anywhere in this file - only widths, offsets, counts, field names
 * and contract literals, which are metadata rather than source.
 *
 * @see DailyTransactionRecordMapper
 * @see DailyTransaction
 */
@DisplayName("daily-transaction record mapper: the 350-byte primary batch input layout")
class DailyTransactionRecordMapperTest {

    // ------------------------------------------------------------------------------------------
    // Hand-transcribed field values of the first two records of app/data/ASCII/dailytran.txt.
    //
    // Each value is the field's significant content; the padding that brings it to its declared
    // width is applied by this class's own helpers below, using the width literals the copybook
    // declares. Every literal is 7-bit ASCII, so a character count and an encoded byte count
    // coincide for these values - which is why the helpers may measure characters while every
    // assertion about a mapped value measures encoded bytes.
    // ------------------------------------------------------------------------------------------

    /** Record 1 transaction identifier, sixteen bytes with the leading zeros the file carries. */
    private static final String FIRST_ID = "0000000000683580";

    /** Record 1 transaction type code: a purchase. */
    private static final String FIRST_TYPE_CD = "01";

    /** Record 1 transaction category code, four bytes, zero-filled on the left in the file. */
    private static final String FIRST_CAT_CD = "0001";

    /** Record 1 description, twenty-four significant bytes inside a hundred-byte field. */
    private static final String FIRST_DESC = "Purchase at Abshire-Lowe";

    /**
     * Record 1 amount image: ten digit bytes followed by an overpunched sign byte.
     *
     * <p>Derived by hand: the digits are {@code 0000005047} and the eleventh byte {@code G} is a
     * positive overpunch contributing the digit {@code 7}, so the digit string is {@code 00000050477}.
     * The field is declared with two implied decimal places, so the trailing two digits are the
     * fraction and the value is {@code +504.77}. Reading {@code G} as a sign-only byte would give
     * {@code 500.47}, which is wrong: the overpunch carries a digit as well as a sign.
     */
    private static final String FIRST_AMOUNT_IMAGE = "0000005047G";

    /** Record 1 amount, decoded by hand from {@link #FIRST_AMOUNT_IMAGE}. */
    private static final BigDecimal FIRST_AMOUNT = new BigDecimal("504.77");

    /** Merchant identifier carried by every record of the reference input, nine bytes. */
    private static final String MERCHANT_ID = "800000000";

    /** Record 1 merchant name, twelve significant bytes inside a fifty-byte field. */
    private static final String FIRST_MERCHANT_NAME = "Abshire-Lowe";

    /** Record 1 merchant city, fifteen significant bytes inside a fifty-byte field. */
    private static final String FIRST_MERCHANT_CITY = "North Enoshaven";

    /** Record 1 merchant postal code: free-form text, never parsed as a number. */
    private static final String FIRST_MERCHANT_ZIP = "72112";

    /** Record 1 card number, sixteen bytes. */
    private static final String FIRST_CARD_NUM = "4859452612877065";

    /** Record 2 transaction identifier, sixteen bytes. */
    private static final String SECOND_ID = "0000000001774260";

    /** Record 2 transaction type code: a return, which is what carries the negative amount. */
    private static final String SECOND_TYPE_CD = "03";

    /** Record 2 description, forty-one significant bytes inside a hundred-byte field. */
    private static final String SECOND_DESC = "Return item at Nitzsche, Nicolas and Lowe";

    /**
     * Record 2 amount image, negatively signed.
     *
     * <p>Derived by hand: the digits are {@code 0000009190} and the eleventh byte, a closing brace,
     * is the negative overpunch contributing the digit {@code 0}, so the digit string is
     * {@code 00000091900} and, with two implied decimals, the value is {@code -919.00}.
     */
    private static final String SECOND_AMOUNT_IMAGE = "0000009190}";

    /** Record 2 amount, decoded by hand from {@link #SECOND_AMOUNT_IMAGE}. */
    private static final BigDecimal SECOND_AMOUNT = new BigDecimal("-919.00");

    /** Record 2 merchant name, twenty-six significant bytes inside a fifty-byte field. */
    private static final String SECOND_MERCHANT_NAME = "Nitzsche, Nicolas and Lowe";

    /** Record 2 merchant city, ten significant bytes inside a fifty-byte field. */
    private static final String SECOND_MERCHANT_CITY = "Fidelshire";

    /** Record 2 merchant postal code. */
    private static final String SECOND_MERCHANT_ZIP = "53378";

    /** Record 2 card number, whose own leading zero must survive as text. */
    private static final String SECOND_CARD_NUM = "0927987108636232";

    /**
     * The origination timestamp, twenty-six bytes, identical on all three hundred records.
     *
     * <p>Held as text and never parsed. Its uniformity is what makes date-window filtering
     * undemonstrable from this input.
     */
    private static final String ORIGINATION_TIMESTAMP = "2022-06-10 19:27:53.000000";

    /**
     * The processing timestamp as it arrives: twenty-six spaces on all three hundred records,
     * because nothing has posted these transactions yet.
     */
    private static final String BLANK_PROCESSING_TIMESTAMP = "                          ";

    /** Origination source of a point-of-sale purchase, padded to its ten-byte field width. */
    private static final String POS_TERMINAL_SOURCE = "POS TERM  ";

    /** Origination source of an operator-entered return, padded to its ten-byte field width. */
    private static final String OPERATOR_SOURCE = "OPERATOR  ";

    /** Complete record image of the first fixture record, reassembled from the literals above. */
    private static final String FIRST_RECORD_IMAGE = recordImage(FIRST_ID, FIRST_TYPE_CD,
            FIRST_CAT_CD, POS_TERMINAL_SOURCE, FIRST_DESC, FIRST_AMOUNT_IMAGE, MERCHANT_ID,
            FIRST_MERCHANT_NAME, FIRST_MERCHANT_CITY, FIRST_MERCHANT_ZIP, FIRST_CARD_NUM,
            ORIGINATION_TIMESTAMP, BLANK_PROCESSING_TIMESTAMP);

    /** Complete record image of the second fixture record, the negatively signed one. */
    private static final String SECOND_RECORD_IMAGE = recordImage(SECOND_ID, SECOND_TYPE_CD,
            FIRST_CAT_CD, OPERATOR_SOURCE, SECOND_DESC, SECOND_AMOUNT_IMAGE, MERCHANT_ID,
            SECOND_MERCHANT_NAME, SECOND_MERCHANT_CITY, SECOND_MERCHANT_ZIP, SECOND_CARD_NUM,
            ORIGINATION_TIMESTAMP, BLANK_PROCESSING_TIMESTAMP);

    /**
     * Assembles a complete record image from thirteen field values plus the trailing filler run.
     *
     * <p>The width of each field is written here as the integer literal the copybook declares, so
     * this assembly is an oracle independent of the mapper's own constants: if the mapper moved a
     * field, the images built here would no longer agree with what it reads. The two numeric fields
     * are placed right-justified and zero-filled and every character field left-justified and
     * space-filled, which is how the legacy layout justifies each kind, and the twenty filler bytes
     * are spaces because that is what the reference input carries.
     *
     * @param  id            transaction identifier
     * @param  typeCd        transaction type code
     * @param  catCd         transaction category code
     * @param  source        origination source
     * @param  desc          description
     * @param  amountImage   the amount already in its eleven-byte zoned-decimal form
     * @param  merchantId    merchant identifier
     * @param  merchantName  merchant name
     * @param  merchantCity  merchant city
     * @param  merchantZip   merchant postal code
     * @param  cardNum       card number
     * @param  origTs        origination timestamp text
     * @param  procTs        processing timestamp text
     * @return the assembled 350-byte record image, carrying no record separator
     */
    private static String recordImage(String id, String typeCd, String catCd, String source,
            String desc, String amountImage, String merchantId, String merchantName,
            String merchantCity, String merchantZip, String cardNum, String origTs, String procTs) {
        return alphanumeric(id, 16)
                + alphanumeric(typeCd, 2)
                + numeric(catCd, 4)
                + alphanumeric(source, 10)
                + alphanumeric(desc, 100)
                + amountImage
                + numeric(merchantId, 9)
                + alphanumeric(merchantName, 50)
                + alphanumeric(merchantCity, 50)
                + alphanumeric(merchantZip, 10)
                + alphanumeric(cardNum, 16)
                + alphanumeric(origTs, 26)
                + alphanumeric(procTs, 26)
                + " ".repeat(20);
    }

    /**
     * Places a character value left-justified and space-filled to its declared width, mirroring how
     * an alphanumeric field is justified.
     *
     * @param  value the significant content
     * @param  width the field's declared byte width
     * @return the value padded to exactly {@code width} bytes
     */
    private static String alphanumeric(String value, int width) {
        return value + " ".repeat(width - value.length());
    }

    /**
     * Places an unsigned numeric value right-justified and zero-filled to its declared width,
     * mirroring how a numeric field is justified. The value stays text throughout, because leading
     * zeros are significant and an identifier is not a number.
     *
     * @param  value the significant digits
     * @param  width the field's declared byte width
     * @return the value padded to exactly {@code width} bytes
     */
    private static String numeric(String value, int width) {
        return "0".repeat(width - value.length()) + value;
    }

    /**
     * Assembles a record image differing from the first fixture record only in its amount image and
     * its origination source, for exercising individual overpunch characters.
     *
     * @param  amountImage the eleven-byte zoned-decimal image to substitute
     * @param  source      the ten-byte origination source to substitute
     * @return the assembled 350-byte record image
     */
    private static String imageWithAmount(String amountImage, String source) {
        return recordImage(FIRST_ID, FIRST_TYPE_CD, FIRST_CAT_CD, source, FIRST_DESC, amountImage,
                MERCHANT_ID, FIRST_MERCHANT_NAME, FIRST_MERCHANT_CITY, FIRST_MERCHANT_ZIP,
                FIRST_CARD_NUM, ORIGINATION_TIMESTAMP, BLANK_PROCESSING_TIMESTAMP);
    }

    /**
     * Reads the amount out of a record whose amount image and origination source are substituted,
     * leaving every other field at its first-reference-record value.
     *
     * <p>Used to walk the sign table one character at a time. This obtains the value under test; the
     * value it is compared against is always a decimal string literal derived by hand.
     *
     * @param  amountImage the eleven-byte zoned-decimal image to read
     * @param  source      the ten-byte origination source to accompany it
     * @return the decoded amount
     */
    private static BigDecimal amountOf(String amountImage, String source) {
        return DailyTransactionRecordMapper.fromRecord(imageWithAmount(amountImage, source))
                .getDalytranAmt();
    }

    /** Returns the encoded byte width of a value, which is the only width that means anything here. */
    private static int encodedWidth(String value) {
        return value.getBytes(StandardCharsets.US_ASCII).length;
    }

    /** Returns the encoded bytes of a value under the one charset these records are defined in. */
    private static byte[] encoded(String value) {
        return value.getBytes(StandardCharsets.US_ASCII);
    }

    @Nested
    @DisplayName("the declared geometry")
    class TheDeclaredGeometry {

        @Test
        @DisplayName("all thirteen field offsets equal the zero-based positions the copybook declares")
        void allThirteenFieldOffsetsEqualTheDeclaredPositions() {
            // Asserted against integer literals rather than against one another, because a
            // contiguity check alone would accept a layout shifted uniformly by any amount.
            assertThat(DailyTransactionRecordMapper.DALYTRAN_ID_OFFSET).isEqualTo(0);
            assertThat(DailyTransactionRecordMapper.DALYTRAN_TYPE_CD_OFFSET).isEqualTo(16);
            assertThat(DailyTransactionRecordMapper.DALYTRAN_CAT_CD_OFFSET).isEqualTo(18);
            assertThat(DailyTransactionRecordMapper.DALYTRAN_SOURCE_OFFSET).isEqualTo(22);
            assertThat(DailyTransactionRecordMapper.DALYTRAN_DESC_OFFSET).isEqualTo(32);
            assertThat(DailyTransactionRecordMapper.DALYTRAN_AMT_OFFSET).isEqualTo(132);
            assertThat(DailyTransactionRecordMapper.DALYTRAN_MERCHANT_ID_OFFSET).isEqualTo(143);
            assertThat(DailyTransactionRecordMapper.DALYTRAN_MERCHANT_NAME_OFFSET).isEqualTo(152);
            assertThat(DailyTransactionRecordMapper.DALYTRAN_MERCHANT_CITY_OFFSET).isEqualTo(202);
            assertThat(DailyTransactionRecordMapper.DALYTRAN_MERCHANT_ZIP_OFFSET).isEqualTo(252);
            assertThat(DailyTransactionRecordMapper.DALYTRAN_CARD_NUM_OFFSET).isEqualTo(262);
            assertThat(DailyTransactionRecordMapper.DALYTRAN_ORIG_TS_OFFSET).isEqualTo(278);
            assertThat(DailyTransactionRecordMapper.DALYTRAN_PROC_TS_OFFSET).isEqualTo(304);
        }

        @Test
        @DisplayName("all thirteen field lengths equal the byte widths the copybook declares")
        void allThirteenFieldLengthsEqualTheDeclaredWidths() {
            assertThat(DailyTransactionRecordMapper.DALYTRAN_ID_LENGTH).isEqualTo(16);
            assertThat(DailyTransactionRecordMapper.DALYTRAN_TYPE_CD_LENGTH).isEqualTo(2);
            assertThat(DailyTransactionRecordMapper.DALYTRAN_CAT_CD_LENGTH).isEqualTo(4);
            assertThat(DailyTransactionRecordMapper.DALYTRAN_SOURCE_LENGTH).isEqualTo(10);
            assertThat(DailyTransactionRecordMapper.DALYTRAN_DESC_LENGTH).isEqualTo(100);
            assertThat(DailyTransactionRecordMapper.DALYTRAN_AMT_LENGTH).isEqualTo(11);
            assertThat(DailyTransactionRecordMapper.DALYTRAN_MERCHANT_ID_LENGTH).isEqualTo(9);
            assertThat(DailyTransactionRecordMapper.DALYTRAN_MERCHANT_NAME_LENGTH).isEqualTo(50);
            assertThat(DailyTransactionRecordMapper.DALYTRAN_MERCHANT_CITY_LENGTH).isEqualTo(50);
            assertThat(DailyTransactionRecordMapper.DALYTRAN_MERCHANT_ZIP_LENGTH).isEqualTo(10);
            assertThat(DailyTransactionRecordMapper.DALYTRAN_CARD_NUM_LENGTH).isEqualTo(16);
            assertThat(DailyTransactionRecordMapper.DALYTRAN_ORIG_TS_LENGTH).isEqualTo(26);
            assertThat(DailyTransactionRecordMapper.DALYTRAN_PROC_TS_LENGTH).isEqualTo(26);
        }

        @Test
        @DisplayName("the record is 350 bytes wide: a 330-byte mapped prefix plus a 20-byte filler run")
        void theRecordIsThreeHundredAndFiftyBytesWide() {
            assertThat(DailyTransactionRecordMapper.RECORD_LENGTH).isEqualTo(350);
            assertThat(DailyTransactionRecordMapper.MAPPED_DATA_LENGTH).isEqualTo(330);
            assertThat(DailyTransactionRecordMapper.FILLER_OFFSET).isEqualTo(330);
            assertThat(DailyTransactionRecordMapper.FILLER_LENGTH).isEqualTo(20);

            // The width arithmetic the copybook implies, written out so it is checked and not merely
            // stated: the mapped prefix ends where the filler begins, and 330 + 20 = 350.
            assertThat(DailyTransactionRecordMapper.MAPPED_DATA_LENGTH
                    + DailyTransactionRecordMapper.FILLER_LENGTH).isEqualTo(350);
            assertThat(DailyTransactionRecordMapper.DALYTRAN_PROC_TS_OFFSET
                    + DailyTransactionRecordMapper.DALYTRAN_PROC_TS_LENGTH).isEqualTo(330);
        }

        @Test
        @DisplayName("the amount occupies eleven bytes, being nine integer digits plus two implied "
                + "decimals, with the sign overpunched rather than given a byte of its own")
        void theAmountOccupiesElevenBytes() {
            // A signed zoned field of scale two is two bytes wider than its integer digit count, and
            // no byte is spent on either the sign or the decimal point: 9 + 2 = 11.
            assertThat(DailyTransactionRecordMapper.DALYTRAN_AMT_LENGTH).isEqualTo(9 + 2);
            assertThat(DailyTransactionRecordMapper.DALYTRAN_MERCHANT_ID_OFFSET
                    - DailyTransactionRecordMapper.DALYTRAN_AMT_OFFSET).isEqualTo(11);
            assertThat(encodedWidth(FIRST_AMOUNT_IMAGE)).isEqualTo(11);
            assertThat(encodedWidth(SECOND_AMOUNT_IMAGE)).isEqualTo(11);
        }

        @Test
        @DisplayName("the filler run is emitted as spaces, matching what the reference input carries")
        void theFillerRunIsEmittedAsSpaces() {
            assertThat(DailyTransactionRecordMapper.FILLER_CHARACTER).isEqualTo(' ');
        }

        @Test
        @DisplayName("the artefact name identifies the record group and its copybook, so a diagnostic "
                + "says which layout was refused")
        void theArtefactNameIdentifiesTheRecordGroupAndItsCopybook() {
            assertThat(DailyTransactionRecordMapper.ARTEFACT)
                    .isNotBlank()
                    .contains("DALYTRAN-RECORD")
                    .contains("CVTRA06Y");
        }

        @Test
        @DisplayName("this layout is a sequential dataset, so it has no cluster definition and no "
                + "declared key to corroborate: the identifier is the leading sixteen-byte business "
                + "key at offset zero and no surrogate key exists")
        void thisLayoutIsASequentialDatasetWithNoClusterDeclaration() {
            // Alone among the estate's record layouts this one is provisioned as a sequential
            // dataset rather than as a keyed cluster. There is therefore no cluster declaration, and
            // consequently no declared key length and offset, and no declared record size, to cite
            // as independent corroboration of the geometry - the copybook is the only authority. The
            // sibling posted-transaction layout does have such a declaration; this one must never be
            // given a borrowed one, so no such corroboration is asserted here or anywhere else.
            //
            // What still holds without it: the entity's identity is the leading field of the record
            // image, taken verbatim, and nothing generated or sequence-backed is introduced.
            assertThat(DailyTransactionRecordMapper.DALYTRAN_ID_OFFSET).isZero();
            assertThat(DailyTransactionRecordMapper.DALYTRAN_ID_LENGTH).isEqualTo(16);

            DailyTransaction mapped =
                    DailyTransactionRecordMapper.fromRecord(FIRST_RECORD_IMAGE);

            assertThat(mapped.getDalytranId()).isEqualTo(FIRST_ID);
            assertThat(encodedWidth(mapped.getDalytranId())).isEqualTo(16);

            // Identity is that business key alone: two records agreeing on it are the same record,
            // which is only true because no surrogate identifier was introduced alongside it.
            DailyTransaction sameKey =
                    DailyTransactionRecordMapper.fromRecord(FIRST_RECORD_IMAGE);
            DailyTransaction otherKey =
                    DailyTransactionRecordMapper.fromRecord(SECOND_RECORD_IMAGE);

            assertThat(mapped).isEqualTo(sameKey).hasSameHashCodeAs(sameKey);
            assertThat(mapped).isNotEqualTo(otherKey);
        }

        @Test
        @DisplayName("the hand-assembled oracle images are themselves exactly 350 bytes, so the "
                + "expectations built from them are dimensionally sound")
        void theHandAssembledOracleImagesAreThreeHundredAndFiftyBytes() {
            assertThat(encodedWidth(FIRST_RECORD_IMAGE)).isEqualTo(350);
            assertThat(encodedWidth(SECOND_RECORD_IMAGE)).isEqualTo(350);
            assertThat(encodedWidth(ORIGINATION_TIMESTAMP)).isEqualTo(26);
            assertThat(encodedWidth(BLANK_PROCESSING_TIMESTAMP)).isEqualTo(26);
            assertThat(encodedWidth(POS_TERMINAL_SOURCE)).isEqualTo(10);
            assertThat(encodedWidth(OPERATOR_SOURCE)).isEqualTo(10);
        }
    }

    @Nested
    @DisplayName("reading a record image")
    class ReadingARecordImage {

        @Test
        @DisplayName("all thirteen properties of the first reference record map to their hand-derived "
                + "values")
        void allThirteenPropertiesMapToTheirHandDerivedValues() {
            DailyTransaction mapped = DailyTransactionRecordMapper.fromRecord(FIRST_RECORD_IMAGE);

            assertThat(mapped.getDalytranId()).isEqualTo("0000000000683580");
            assertThat(mapped.getDalytranTypeCd()).isEqualTo("01");
            assertThat(mapped.getDalytranCatCd()).isEqualTo("0001");
            assertThat(mapped.getDalytranSource()).isEqualTo("POS TERM  ");
            assertThat(mapped.getDalytranDesc()).isEqualTo(alphanumeric(FIRST_DESC, 100));
            assertThat(mapped.getDalytranAmt()).isEqualByComparingTo(FIRST_AMOUNT);
            assertThat(mapped.getDalytranMerchantId()).isEqualTo("800000000");
            assertThat(mapped.getDalytranMerchantName())
                    .isEqualTo(alphanumeric(FIRST_MERCHANT_NAME, 50));
            assertThat(mapped.getDalytranMerchantCity())
                    .isEqualTo(alphanumeric(FIRST_MERCHANT_CITY, 50));
            assertThat(mapped.getDalytranMerchantZip()).isEqualTo("72112     ");
            assertThat(mapped.getDalytranCardNum()).isEqualTo("4859452612877065");
            assertThat(mapped.getDalytranOrigTs()).isEqualTo("2022-06-10 19:27:53.000000");
            assertThat(mapped.getDalytranProcTs()).isEqualTo(BLANK_PROCESSING_TIMESTAMP);
        }

        @Test
        @DisplayName("this mapper and the posted-transaction mapper share one geometry yet stay "
                + "separate entities, so all four merchant properties keep the dalytran prefix that "
                + "the sibling leaves off, and neither is merged into or derived from the other")
        void theMergeProhibitionKeepsAllFourMerchantPropertiesPrefixed() {
            // The two layouts are field-for-field parallel, which is exactly why this must be pinned.
            // They are nonetheless separate datasets with separate lifecycles: the posted record was
            // a keyed cluster, this one is a sequential daily input the posting job consumes. So the
            // two mappers are not merged, neither subclasses the other, and no shared base class,
            // offset holder, helper or test base is extracted between them - this test file imports
            // neither the sibling mapper nor the sibling entity, and reads none of its constants.
            //
            // The naming asymmetry is the observable consequence and must not be regularised in
            // either direction: prefixing the sibling, or unprefixing these four, would break the
            // mapping each entity has against its own table.
            DailyTransaction mapped = DailyTransactionRecordMapper.fromRecord(FIRST_RECORD_IMAGE);

            assertThat(mapped.getDalytranMerchantId()).isEqualTo("800000000");
            assertThat(mapped.getDalytranMerchantName())
                    .isEqualTo(alphanumeric(FIRST_MERCHANT_NAME, 50));
            assertThat(mapped.getDalytranMerchantCity())
                    .isEqualTo(alphanumeric(FIRST_MERCHANT_CITY, 50));
            assertThat(mapped.getDalytranMerchantZip()).isEqualTo("72112     ");

            // The merchant block occupies one contiguous run of 119 bytes, from the amount's end to
            // the card number's start, and each of the four fields is read from inside it.
            assertThat(DailyTransactionRecordMapper.DALYTRAN_CARD_NUM_OFFSET
                    - DailyTransactionRecordMapper.DALYTRAN_MERCHANT_ID_OFFSET).isEqualTo(119);
            assertThat(DailyTransactionRecordMapper.DALYTRAN_MERCHANT_ID_LENGTH
                    + DailyTransactionRecordMapper.DALYTRAN_MERCHANT_NAME_LENGTH
                    + DailyTransactionRecordMapper.DALYTRAN_MERCHANT_CITY_LENGTH
                    + DailyTransactionRecordMapper.DALYTRAN_MERCHANT_ZIP_LENGTH).isEqualTo(119);
        }

        @Test
        @DisplayName("every character field arrives at its full declared byte width, padding included, "
                + "so a value read from a record never equals its significant content alone")
        void everyCharacterFieldArrivesAtItsFullDeclaredWidth() {
            DailyTransaction mapped = DailyTransactionRecordMapper.fromRecord(FIRST_RECORD_IMAGE);

            // Widths are measured in encoded bytes, never in characters: a character count is not a
            // width authority for a fixed-width record.
            assertThat(encodedWidth(mapped.getDalytranId())).isEqualTo(16);
            assertThat(encodedWidth(mapped.getDalytranTypeCd())).isEqualTo(2);
            assertThat(encodedWidth(mapped.getDalytranCatCd())).isEqualTo(4);
            assertThat(encodedWidth(mapped.getDalytranSource())).isEqualTo(10);
            assertThat(encodedWidth(mapped.getDalytranDesc())).isEqualTo(100);
            assertThat(encodedWidth(mapped.getDalytranMerchantId())).isEqualTo(9);
            assertThat(encodedWidth(mapped.getDalytranMerchantName())).isEqualTo(50);
            assertThat(encodedWidth(mapped.getDalytranMerchantCity())).isEqualTo(50);
            assertThat(encodedWidth(mapped.getDalytranMerchantZip())).isEqualTo(10);
            assertThat(encodedWidth(mapped.getDalytranCardNum())).isEqualTo(16);
            assertThat(encodedWidth(mapped.getDalytranOrigTs())).isEqualTo(26);
            assertThat(encodedWidth(mapped.getDalytranProcTs())).isEqualTo(26);

            // The trailing blanks are contractual data, not noise. Each constant below holds the
            // field's significant content only, so a padded value must differ from it - which is
            // what a stripped or trimmed read would silently destroy.
            assertThat(mapped.getDalytranSource()).isNotEqualTo("POS TERM");
            assertThat(mapped.getDalytranDesc()).isNotEqualTo(FIRST_DESC);
            assertThat(mapped.getDalytranMerchantName()).isNotEqualTo(FIRST_MERCHANT_NAME);
            assertThat(mapped.getDalytranMerchantCity()).isNotEqualTo(FIRST_MERCHANT_CITY);
            assertThat(mapped.getDalytranMerchantZip()).isNotEqualTo(FIRST_MERCHANT_ZIP);
        }

        @Test
        @DisplayName("leading zeros survive on the category code and the merchant identifier, because "
                + "each is an identifier carried as text and not a number")
        void leadingZerosSurviveOnTheNumericIdentifiers() {
            DailyTransaction reference =
                    DailyTransactionRecordMapper.fromRecord(FIRST_RECORD_IMAGE);

            assertThat(reference.getDalytranCatCd()).isEqualTo("0001").isNotEqualTo("1");
            assertThat(encodedWidth(reference.getDalytranCatCd())).isEqualTo(4);

            // The reference input happens to carry one merchant identifier that fills its field, so
            // a zero-filled one is assembled here to pin the same guarantee on that field.
            String zeroFilledMerchantId = recordImage(FIRST_ID, FIRST_TYPE_CD, FIRST_CAT_CD,
                    POS_TERMINAL_SOURCE, FIRST_DESC, FIRST_AMOUNT_IMAGE, "42",
                    FIRST_MERCHANT_NAME, FIRST_MERCHANT_CITY, FIRST_MERCHANT_ZIP, FIRST_CARD_NUM,
                    ORIGINATION_TIMESTAMP, BLANK_PROCESSING_TIMESTAMP);

            DailyTransaction padded = DailyTransactionRecordMapper.fromRecord(zeroFilledMerchantId);

            assertThat(padded.getDalytranMerchantId()).isEqualTo("000000042").isNotEqualTo("42");
            assertThat(encodedWidth(padded.getDalytranMerchantId())).isEqualTo(9);

            // The identifier and the card number are text for the same reason, and the second
            // reference record proves it: its card number begins with a zero that must not collapse.
            assertThat(DailyTransactionRecordMapper.fromRecord(SECOND_RECORD_IMAGE)
                    .getDalytranCardNum()).isEqualTo("0927987108636232");
        }

        @Test
        @DisplayName("the origination timestamp is carried as raw twenty-six-byte text, neither parsed "
                + "nor normalised, and it is identical on every record so no date window can be "
                + "demonstrated from this input")
        void theOriginationTimestampIsCarriedAsRawText() {
            DailyTransaction first = DailyTransactionRecordMapper.fromRecord(FIRST_RECORD_IMAGE);
            DailyTransaction second = DailyTransactionRecordMapper.fromRecord(SECOND_RECORD_IMAGE);

            assertThat(first.getDalytranOrigTs()).isEqualTo("2022-06-10 19:27:53.000000");
            assertThat(encodedWidth(first.getDalytranOrigTs())).isEqualTo(26);

            // Every one of the three hundred records carries this same value, so a date-window filter
            // over this input admits all of them or none. Date-window filtering therefore cannot be
            // exercised here and needs a separately constructed input; building that input, and
            // asserting any date-range behaviour, belongs to the batch tier and not to this test.
            assertThat(second.getDalytranOrigTs()).isEqualTo(first.getDalytranOrigTs());
        }

        @Test
        @DisplayName("the processing timestamp arrives as exactly twenty-six spaces - not null, not "
                + "empty and not trimmed - because nothing has posted these records yet")
        void theProcessingTimestampArrivesAsTwentySixSpaces() {
            DailyTransaction mapped = DailyTransactionRecordMapper.fromRecord(FIRST_RECORD_IMAGE);

            assertThat(mapped.getDalytranProcTs())
                    .isNotNull()
                    .isNotEmpty()
                    .isEqualTo("                          ")
                    .isNotEqualTo("");
            assertThat(encodedWidth(mapped.getDalytranProcTs())).isEqualTo(26);
            assertThat(mapped.getDalytranProcTs()).isEqualTo(BLANK_PROCESSING_TIMESTAMP);
        }
    }

    @Nested
    @DisplayName("the zoned-decimal amount and its overpunched sign")
    class TheZonedDecimalAmount {

        @Test
        @DisplayName("a positive overpunch contributes a digit as well as a sign: 0000005047G is "
                + "504.77 at scale two, not 500.47")
        void aPositiveOverpunchContributesADigitAsWellAsASign() {
            // Hand derivation, byte by byte:
            //   bytes 1 to 10        0000005047
            //   byte 11              G, the positive overpunch for the digit 7
            //   digit string         0000005047 followed by 7, i.e. 00000050477
            //   two implied decimals 000000504 and 77
            //   value                +504.77
            // Treating the trailing G as a sign-only byte would yield 500.47, which is the mistake
            // this assertion exists to catch.
            BigDecimal amount = amountOf(FIRST_AMOUNT_IMAGE, POS_TERMINAL_SOURCE);

            assertThat(amount).isEqualByComparingTo(new BigDecimal("504.77"));
            assertThat(amount).isNotEqualByComparingTo(new BigDecimal("500.47"));
            assertThat(amount.scale()).isEqualTo(2);
            assertThat(amount.signum()).isOne();
        }

        @Test
        @DisplayName("a negative overpunch carries the sign in the final byte: 0000009190 terminated "
                + "by a closing brace is -919.00 at scale two")
        void aNegativeOverpunchCarriesTheSignInTheFinalByte() {
            // Hand derivation: digits 0000009190, final byte the negative overpunch for the digit 0,
            // so the digit string is 00000091900 and with two implied decimals the value is -919.00.
            BigDecimal amount = amountOf(SECOND_AMOUNT_IMAGE, OPERATOR_SOURCE);

            assertThat(amount).isEqualByComparingTo(new BigDecimal("-919.00"));
            assertThat(amount.scale()).isEqualTo(2);
            assertThat(amount.signum()).isEqualTo(-1);
        }

        @Test
        @DisplayName("a negatively signed all-zero image decodes to zero at scale two, because the "
                + "decoded type has no negative zero to carry the distinction into")
        void aNegativelySignedAllZeroImageDecodesToZero() {
            BigDecimal negativeZero = amountOf("0000000000}", OPERATOR_SOURCE);

            assertThat(negativeZero).isEqualByComparingTo(new BigDecimal("0.00"));
            assertThat(negativeZero.scale()).isEqualTo(2);
            assertThat(negativeZero.signum()).isZero();

            // The positively signed all-zero image decodes to the same value, which is the whole of
            // the asymmetry: the sign bit of an all-zero image is the one piece of information the
            // decoded amount cannot hold, so a caller needing it byte-exact reads the field through
            // the codec's signed entry points instead. Recorded as a documented divergence.
            assertThat(amountOf("0000000000{", POS_TERMINAL_SOURCE))
                    .isEqualByComparingTo(negativeZero);
        }

        @Test
        @DisplayName("all twenty sign characters decode correctly - the reference input is the "
                + "estate's only complete overpunch oracle, its three hundred records splitting 250 "
                + "positive to 50 negative with every one of the twenty codes occurring")
        void allTwentySignCharactersDecodeCorrectly() {
            // Measured census of the final byte of the amount field across all three hundred records:
            //   positive non-zero  A 28  B 29  C 30  D 29  E 23  F 21  G 24  H 17  I 24   = 225
            //   negative non-zero  J  3  K  5  L  5  M  6  N  2  O  4  P  7  Q  4  R  8   =  44
            //   signed zero        the positive form 25, the negative form 6               =  31
            //   total 300, which counts as 250 positive and 50 negative.
            // Ten of the twenty characters below are transcribed from records that carry them; the
            // remaining ten are assembled here, and every expected value is derived by hand from the
            // sign table, in which the positive forms run brace then A to I for the digits 0 to 9 and
            // the negative forms run brace then J to R.

            // The ten positive forms, digit 0 through digit 9.
            assertThat(amountOf("0000003250{", POS_TERMINAL_SOURCE))
                    .isEqualByComparingTo(new BigDecimal("325.00"));
            assertThat(amountOf("0000004161A", POS_TERMINAL_SOURCE))
                    .isEqualByComparingTo(new BigDecimal("416.11"));
            assertThat(amountOf("0000000012B", POS_TERMINAL_SOURCE))
                    .isEqualByComparingTo(new BigDecimal("1.22"));
            assertThat(amountOf("0000000000C", POS_TERMINAL_SOURCE))
                    .isEqualByComparingTo(new BigDecimal("0.03"));
            assertThat(amountOf("0000000000D", POS_TERMINAL_SOURCE))
                    .isEqualByComparingTo(new BigDecimal("0.04"));
            assertThat(amountOf("0000000000E", POS_TERMINAL_SOURCE))
                    .isEqualByComparingTo(new BigDecimal("0.05"));
            assertThat(amountOf("0000000000F", POS_TERMINAL_SOURCE))
                    .isEqualByComparingTo(new BigDecimal("0.06"));
            assertThat(amountOf("0000005047G", POS_TERMINAL_SOURCE))
                    .isEqualByComparingTo(new BigDecimal("504.77"));
            assertThat(amountOf("0000000678H", POS_TERMINAL_SOURCE))
                    .isEqualByComparingTo(new BigDecimal("67.88"));
            assertThat(amountOf("0000008499I", POS_TERMINAL_SOURCE))
                    .isEqualByComparingTo(new BigDecimal("849.99"));

            // The ten negative forms, digit 0 through digit 9.
            assertThat(amountOf("0000009190}", OPERATOR_SOURCE))
                    .isEqualByComparingTo(new BigDecimal("-919.00"));
            assertThat(amountOf("0000008351J", OPERATOR_SOURCE))
                    .isEqualByComparingTo(new BigDecimal("-835.11"));
            assertThat(amountOf("0000000000K", OPERATOR_SOURCE))
                    .isEqualByComparingTo(new BigDecimal("-0.02"));
            assertThat(amountOf("0000000000L", OPERATOR_SOURCE))
                    .isEqualByComparingTo(new BigDecimal("-0.03"));
            assertThat(amountOf("0000000000M", OPERATOR_SOURCE))
                    .isEqualByComparingTo(new BigDecimal("-0.04"));
            assertThat(amountOf("0000000000N", OPERATOR_SOURCE))
                    .isEqualByComparingTo(new BigDecimal("-0.05"));
            assertThat(amountOf("0000009456O", OPERATOR_SOURCE))
                    .isEqualByComparingTo(new BigDecimal("-945.66"));
            assertThat(amountOf("0000000567P", OPERATOR_SOURCE))
                    .isEqualByComparingTo(new BigDecimal("-56.77"));
            assertThat(amountOf("0000005358Q", OPERATOR_SOURCE))
                    .isEqualByComparingTo(new BigDecimal("-535.88"));
            assertThat(amountOf("0000000709R", OPERATOR_SOURCE))
                    .isEqualByComparingTo(new BigDecimal("-70.99"));
        }

        @Test
        @DisplayName("an unsigned trailing digit is read as a positive digit, so a field written "
                + "without an overpunch is still decoded rather than refused")
        void anUnsignedTrailingDigitIsReadAsPositive() {
            // Hand derivation: 00000050477 has no overpunch at all, so every byte is a digit and the
            // value is positive, giving the same 504.77 the overpunched form gives.
            BigDecimal amount = amountOf("00000050477", POS_TERMINAL_SOURCE);

            assertThat(amount).isEqualByComparingTo(new BigDecimal("504.77"));
            assertThat(amount.scale()).isEqualTo(2);
        }

        @Test
        @DisplayName("every decoded amount carries scale exactly two, because a store into a "
                + "two-decimal field truncates toward zero and never rounds")
        void everyDecodedAmountCarriesScaleExactlyTwo() {
            // No arithmetic statement in the estate specifies rounding, so truncation is the only
            // faithful policy: half-even and half-up are both wrong here and neither is named
            // anywhere in this file. The scale is a property of the field declaration, which is why
            // it holds for a whole value as much as for a fractional one.
            assertThat(amountOf(FIRST_AMOUNT_IMAGE, POS_TERMINAL_SOURCE).scale()).isEqualTo(2);
            assertThat(amountOf(SECOND_AMOUNT_IMAGE, OPERATOR_SOURCE).scale()).isEqualTo(2);
            assertThat(amountOf("0000003250{", POS_TERMINAL_SOURCE).scale()).isEqualTo(2);
            assertThat(amountOf("0000000000K", OPERATOR_SOURCE).scale()).isEqualTo(2);

            // A whole-unit amount keeps both fraction digits rather than collapsing to a bare
            // integer, which is what makes the re-encoded image eleven bytes wide again. Compared
            // with exact equality rather than by value, because exact equality of a decimal requires
            // the scales to agree as well as the numbers: a decoded 325 would fail this even though
            // it compares equal by value to 325.00.
            assertThat(amountOf("0000003250{", POS_TERMINAL_SOURCE))
                    .isEqualTo(new BigDecimal("325.00"))
                    .isNotEqualTo(new BigDecimal("325"));
        }

        @Test
        @DisplayName("the sign of the amount tracks the origination source in the reference input: "
                + "every one of the fifty negative records is operator-entered and every one of the "
                + "250 positive records comes from a point-of-sale terminal")
        void theSignOfTheAmountTracksTheOriginationSource() {
            // Both signed directions of the posting computation are therefore reachable from seeded
            // data alone, which is why this input needs no synthetic companion for that purpose.
            DailyTransaction purchase = DailyTransactionRecordMapper.fromRecord(FIRST_RECORD_IMAGE);
            DailyTransaction refund = DailyTransactionRecordMapper.fromRecord(SECOND_RECORD_IMAGE);

            assertThat(purchase.getDalytranAmt()).isEqualByComparingTo(FIRST_AMOUNT);
            assertThat(purchase.getDalytranAmt().signum()).isOne();
            assertThat(purchase.getDalytranSource()).isEqualTo("POS TERM  ");
            assertThat(encodedWidth(purchase.getDalytranSource())).isEqualTo(10);
            assertThat(purchase.getDalytranTypeCd()).isEqualTo("01");

            assertThat(refund.getDalytranAmt()).isEqualByComparingTo(SECOND_AMOUNT);
            assertThat(refund.getDalytranAmt().signum()).isEqualTo(-1);
            assertThat(refund.getDalytranSource()).isEqualTo("OPERATOR  ");
            assertThat(encodedWidth(refund.getDalytranSource())).isEqualTo(10);
            assertThat(refund.getDalytranTypeCd()).isEqualTo("03");

            // The source is an unconstrained ten-byte value, not a closed set: an unrecognised code
            // is carried through rather than refused, because this layout lands unvalidated and
            // validation belongs to the batch step that reads it.
            assertThat(DailyTransactionRecordMapper
                    .fromRecord(imageWithAmount(FIRST_AMOUNT_IMAGE, "MAIL ORDER"))
                    .getDalytranSource()).isEqualTo("MAIL ORDER");
        }
    }

    @Nested
    @DisplayName("emitting a record image")
    class EmittingARecordImage {

        @Test
        @DisplayName("the 330-byte mapped prefix is reproduced byte for byte")
        void theMappedPrefixIsReproducedByteForByte() {
            DailyTransaction mapped = DailyTransactionRecordMapper.fromRecord(FIRST_RECORD_IMAGE);

            byte[] emitted = encoded(DailyTransactionRecordMapper.toRecord(mapped));

            // Compared as bytes over the half-open range zero to 330, the bound being written as the
            // literal the copybook implies rather than read back from the class under test.
            assertThat(Arrays.copyOfRange(emitted, 0, 330))
                    .isEqualTo(Arrays.copyOfRange(encoded(FIRST_RECORD_IMAGE), 0, 330));
        }

        @Test
        @DisplayName("the whole 350-byte image is reproduced, the twenty filler bytes beyond offset "
                + "330 all being spaces")
        void theWholeImageIsReproducedWithSpaceFiller() {
            DailyTransaction mapped = DailyTransactionRecordMapper.fromRecord(FIRST_RECORD_IMAGE);

            String emitted = DailyTransactionRecordMapper.toRecord(mapped);

            assertThat(emitted).isEqualTo(FIRST_RECORD_IMAGE);
            assertThat(encodedWidth(emitted)).isEqualTo(350);

            // Bytes 330 through 349 inclusive are the filler run, and every one of them is 0x20. The
            // reference input carries the same twenty spaces, which is why a whole-record comparison
            // is sound for this layout rather than only a prefix comparison.
            assertThat(Arrays.copyOfRange(encoded(emitted), 330, 350))
                    .hasSize(20)
                    .containsOnly((byte) 0x20);
        }

        @Test
        @DisplayName("the byte-emitting entry point produces the same 350 bytes as the hand-assembled "
                + "image, and carries no record separator")
        void theByteEmitterProducesTheHandAssembledBytes() {
            DailyTransaction mapped = DailyTransactionRecordMapper.fromRecord(FIRST_RECORD_IMAGE);

            byte[] emitted = DailyTransactionRecordMapper.toRecordBytes(mapped);

            assertThat(emitted).hasSize(350).isEqualTo(encoded(FIRST_RECORD_IMAGE));
            // Record separation belongs to whoever writes the file, so no terminator is appended.
            assertThat(emitted[349]).isEqualTo((byte) 0x20);
        }

        @Test
        @DisplayName("an entity built through the public thirteen-argument constructor emits the "
                + "reference image, which proves the constructor's parameter order is copybook order")
        void anEntityBuiltThroughThePublicConstructorEmitsTheReferenceImage() {
            // Arguments are supplied in the order a reader encounters the fields moving left to right
            // across the record image. Had any pair been transposed - the two timestamps, or the
            // merchant city and name, which are both fifty bytes - the emitted image would differ
            // from the hand-assembled one, so this single comparison pins the whole ordering. The
            // no-argument constructor is reserved for the persistence provider and is not called.
            DailyTransaction built = new DailyTransaction(
                    "0000000000683580",
                    "01",
                    "0001",
                    "POS TERM  ",
                    alphanumeric(FIRST_DESC, 100),
                    new BigDecimal("504.77"),
                    "800000000",
                    alphanumeric(FIRST_MERCHANT_NAME, 50),
                    alphanumeric(FIRST_MERCHANT_CITY, 50),
                    "72112     ",
                    "4859452612877065",
                    "2022-06-10 19:27:53.000000",
                    "                          ");

            assertThat(DailyTransactionRecordMapper.toRecord(built)).isEqualTo(FIRST_RECORD_IMAGE);
            assertThat(DailyTransactionRecordMapper.toRecordBytes(built))
                    .isEqualTo(encoded(FIRST_RECORD_IMAGE));
        }

        @Test
        @DisplayName("a blank processing timestamp is re-emitted as twenty-six spaces rather than "
                + "collapsing to an empty or defaulted field")
        void aBlankProcessingTimestampIsReEmittedAsSpaces() {
            DailyTransaction mapped = DailyTransactionRecordMapper.fromRecord(FIRST_RECORD_IMAGE);

            byte[] emitted = DailyTransactionRecordMapper.toRecordBytes(mapped);

            // The processing timestamp occupies bytes 304 through 329 inclusive.
            assertThat(Arrays.copyOfRange(emitted, 304, 330))
                    .hasSize(26)
                    .containsOnly((byte) 0x20);
        }

        @Test
        @DisplayName("the negatively signed reference record round-trips too, its closing-brace sign "
                + "byte reappearing in the final byte of the amount field")
        void theNegativelySignedReferenceRecordRoundTrips() {
            DailyTransaction mapped = DailyTransactionRecordMapper.fromRecord(SECOND_RECORD_IMAGE);

            String emitted = DailyTransactionRecordMapper.toRecord(mapped);

            assertThat(emitted).isEqualTo(SECOND_RECORD_IMAGE);
            assertThat(encodedWidth(emitted)).isEqualTo(350);

            // The amount field occupies bytes 132 through 142 inclusive; its final byte must still be
            // the negative overpunch, because a positive re-emission would silently flip the sign of
            // a refund.
            assertThat(Arrays.copyOfRange(encoded(emitted), 132, 143))
                    .isEqualTo(encoded("0000009190}"));
        }
    }

    @Nested
    @DisplayName("the three reading entry points")
    class TheThreeReadingEntryPoints {

        @Test
        @DisplayName("the string, byte-array and byte-range entry points produce equal entities "
                + "carrying identical field values")
        void theThreeEntryPointsProduceEqualEntities() {
            byte[] wholeRecord = encoded(FIRST_RECORD_IMAGE);
            byte[] framed = new byte[wholeRecord.length + 7];
            System.arraycopy(wholeRecord, 0, framed, 7, wholeRecord.length);

            DailyTransaction fromText = DailyTransactionRecordMapper.fromRecord(FIRST_RECORD_IMAGE);
            DailyTransaction fromBytes = DailyTransactionRecordMapper.fromRecord(wholeRecord);
            DailyTransaction fromRange = DailyTransactionRecordMapper.fromRecord(framed, 7);

            // Entity equality is over the identifier alone, so it would hold even if the other twelve
            // properties disagreed. Each entity is therefore re-emitted and compared against the
            // hand-assembled image, which is what actually proves all thirteen fields agree.
            assertThat(DailyTransactionRecordMapper.toRecord(fromText))
                    .isEqualTo(FIRST_RECORD_IMAGE);
            assertThat(DailyTransactionRecordMapper.toRecord(fromBytes))
                    .isEqualTo(FIRST_RECORD_IMAGE);
            assertThat(DailyTransactionRecordMapper.toRecord(fromRange))
                    .isEqualTo(FIRST_RECORD_IMAGE);

            assertThat(fromBytes).isEqualTo(fromText).hasSameHashCodeAs(fromText);
            assertThat(fromRange).isEqualTo(fromText).hasSameHashCodeAs(fromText);
        }

        @Test
        @DisplayName("the byte-range entry point selects one record out of a buffer holding many at "
                + "the file's 351-byte stride, leaving each separator behind")
        void theByteRangeEntryPointSelectsOneRecordFromABuffer() {
            // The reference file's stride is 350 plus one separator byte, so record index i begins at
            // i times 351. Reading the second record at offset 351 both selects it and excludes the
            // separator, which is never record content.
            byte[] buffer = encoded(FIRST_RECORD_IMAGE + "\n" + SECOND_RECORD_IMAGE + "\n");

            assertThat(buffer).hasSize(2 * 351);
            assertThat(DailyTransactionRecordMapper.toRecord(
                    DailyTransactionRecordMapper.fromRecord(buffer, 0)))
                    .isEqualTo(FIRST_RECORD_IMAGE);
            assertThat(DailyTransactionRecordMapper.toRecord(
                    DailyTransactionRecordMapper.fromRecord(buffer, 351)))
                    .isEqualTo(SECOND_RECORD_IMAGE);
        }
    }

    @Nested
    @DisplayName("refusing a malformed image")
    class RefusingAMalformedImage {

        @Test
        @DisplayName("an image one byte short is refused rather than padded, the diagnostic naming the "
                + "layout, the expected 350 bytes and the 349 actually supplied")
        void anImageOneByteShortIsRefused() {
            String tooShort = "0".repeat(349);

            assertThat(encodedWidth(tooShort)).isEqualTo(349);
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> DailyTransactionRecordMapper.fromRecord(tooShort))
                    .withMessageContaining("DALYTRAN-RECORD")
                    .withMessageContaining("350")
                    .withMessageContaining("349");
        }

        @Test
        @DisplayName("an image one byte long is refused rather than truncated, and the diagnostic "
                + "names an unstripped record separator as the likely cause")
        void anImageOneByteLongIsRefused() {
            String withSeparator = FIRST_RECORD_IMAGE + "\n";

            assertThat(encodedWidth(withSeparator)).isEqualTo(351);
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> DailyTransactionRecordMapper.fromRecord(withSeparator))
                    .withMessageContaining("350")
                    .withMessageContaining("351")
                    .withMessageContaining("terminator");
        }

        @Test
        @DisplayName("a byte array of the wrong length is refused on the same terms as a string")
        void aByteArrayOfTheWrongLengthIsRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> DailyTransactionRecordMapper.fromRecord(new byte[349]))
                    .withMessageContaining("349");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> DailyTransactionRecordMapper.fromRecord(new byte[351]))
                    .withMessageContaining("351");
        }

        @Test
        @DisplayName("a byte range that does not lie wholly inside its buffer is refused")
        void aByteRangeOutsideItsBufferIsRefused() {
            byte[] exactlyOneRecord = encoded(FIRST_RECORD_IMAGE);

            // Offset one leaves only 349 bytes ahead of it, so the range overruns the buffer.
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> DailyTransactionRecordMapper.fromRecord(exactlyOneRecord, 1));
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> DailyTransactionRecordMapper.fromRecord(exactlyOneRecord, -1));
        }

        @Test
        @DisplayName("an absent image is refused on every entry point rather than yielding a partly "
                + "populated entity")
        void anAbsentImageIsRefusedOnEveryEntryPoint() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> DailyTransactionRecordMapper.fromRecord((String) null));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> DailyTransactionRecordMapper.fromRecord((byte[]) null));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> DailyTransactionRecordMapper.fromRecord((byte[]) null, 0));
        }

        @Test
        @DisplayName("an absent entity, and an entity with an absent mapped property, are both refused "
                + "on the emitting path rather than emitting a record of the right width and the "
                + "wrong content")
        void anAbsentEntityOrPropertyIsRefusedOnTheEmittingPath() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> DailyTransactionRecordMapper.toRecord(null));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> DailyTransactionRecordMapper.toRecordBytes(null));

            DailyTransaction missingDescription =
                    DailyTransactionRecordMapper.fromRecord(FIRST_RECORD_IMAGE);
            missingDescription.setDalytranDesc(null);

            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> DailyTransactionRecordMapper.toRecord(missingDescription))
                    .withMessageContaining("DALYTRAN-DESC");
        }

        @Test
        @DisplayName("a value wider than its field is refused rather than silently truncated")
        void aValueWiderThanItsFieldIsRefused() {
            DailyTransaction overlong =
                    DailyTransactionRecordMapper.fromRecord(FIRST_RECORD_IMAGE);
            overlong.setDalytranTypeCd("013");

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> DailyTransactionRecordMapper.toRecord(overlong));
        }
    }
}
