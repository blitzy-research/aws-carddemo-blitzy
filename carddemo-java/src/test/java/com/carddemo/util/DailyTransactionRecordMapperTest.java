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
 * <p><strong>This is the primary end-to-end input layout</strong> and byte fidelity here is
 * load-bearing twice over. The dataset drives the posting run - 105,300 bytes, three hundred records
 * at a 351-byte stride, being a 350-byte record plus one separator that is never record content - and
 * a record failing validation downstream is echoed into the reject output <em>unchanged</em>, the
 * 350-byte source image copied verbatim with a trailer appended, so any distortion introduced while
 * reading reappears in a second contractual output. The reject output, its reason codes and the
 * posting cascade belong to the batch tier and none of it is emulated here.
 *
 * <p><strong>Two entities share one geometry and are deliberately kept apart.</strong> This layout is
 * field-for-field parallel with the posted-transaction layout, yet the two are separate entities over
 * separate tables because they are separate datasets with separate lifecycles - the posted record was
 * a keyed cluster, this one arrives sequentially. No shared base class, offset holder or test base is
 * extracted, and this test imports neither sibling. The visible consequence must not be tidied:
 * <em>all thirteen</em> properties here carry the {@code dalytran} prefix, including all four
 * merchant properties, which the sibling leaves unprefixed.
 *
 * <p><strong>The amount is the estate's only complete overpunch oracle</strong> - its sign is
 * overpunched into the final byte of an eleven-byte zoned-decimal image, and across the three hundred
 * shipped records all twenty sign characters occur. Decoding truncates toward zero at scale two,
 * never rounding, because no arithmetic statement in the estate specifies rounding. This test names
 * no rounding mode, never rescales, and builds every expected amount from a decimal string literal.
 *
 * <p><strong>Timestamps stay raw twenty-six-byte text</strong> and nothing here parses or normalises
 * one. All three hundred processing timestamps are twenty-six spaces, which no temporal type can hold
 * and which must survive as spaces rather than becoming null, empty or trimmed. All three hundred
 * origination timestamps carry one identical value, so a date window over this input admits every
 * record or none: date-window filtering cannot be exercised from this fixture and needs a separately
 * constructed input, which belongs to the batch tier.
 *
 * <p>A second complete batch program reads this same layout and no job member, procedure or resource
 * definition invokes it. That documented orphan is migrated behind a job excluded from the default
 * pipeline; it is named here only as corroboration that this layout is read from two places and so
 * must be mapped once, correctly, for both.
 *
 * <p>Every expectation is hand-derived: the two fixture records are transcribed as literal field
 * values and reassembled by this class's own padding helpers, no production method computes a value
 * that is then checked, nothing is snapshotted, and every offset and length is asserted against the
 * integer literal the copybook declares so a uniformly shifted layout cannot pass.
 */
@DisplayName("daily-transaction record mapper: the 350-byte primary batch input layout")
class DailyTransactionRecordMapperTest {
    private static final String FIRST_ID = "0000000000683580";

    private static final String FIRST_TYPE_CD = "01";

    private static final String FIRST_CAT_CD = "0001";

    private static final String FIRST_DESC = "Purchase at Abshire-Lowe";

    private static final String FIRST_AMOUNT_IMAGE = "0000005047G";

    private static final BigDecimal FIRST_AMOUNT = new BigDecimal("504.77");

    private static final String MERCHANT_ID = "800000000";

    private static final String FIRST_MERCHANT_NAME = "Abshire-Lowe";

    private static final String FIRST_MERCHANT_CITY = "North Enoshaven";

    private static final String FIRST_MERCHANT_ZIP = "72112";

    private static final String FIRST_CARD_NUM = "4859452612877065";

    private static final String SECOND_ID = "0000000001774260";

    private static final String SECOND_TYPE_CD = "03";

    private static final String SECOND_DESC = "Return item at Nitzsche, Nicolas and Lowe";

    private static final String SECOND_AMOUNT_IMAGE = "0000009190}";

    private static final BigDecimal SECOND_AMOUNT = new BigDecimal("-919.00");

    private static final String SECOND_MERCHANT_NAME = "Nitzsche, Nicolas and Lowe";

    private static final String SECOND_MERCHANT_CITY = "Fidelshire";

    private static final String SECOND_MERCHANT_ZIP = "53378";

    private static final String SECOND_CARD_NUM = "0927987108636232";

    private static final String ORIGINATION_TIMESTAMP = "2022-06-10 19:27:53.000000";

    private static final String BLANK_PROCESSING_TIMESTAMP = "                          ";

    private static final String POS_TERMINAL_SOURCE = "POS TERM  ";

    private static final String OPERATOR_SOURCE = "OPERATOR  ";

    private static final String FIRST_RECORD_IMAGE = recordImage(FIRST_ID, FIRST_TYPE_CD,
            FIRST_CAT_CD, POS_TERMINAL_SOURCE, FIRST_DESC, FIRST_AMOUNT_IMAGE, MERCHANT_ID,
            FIRST_MERCHANT_NAME, FIRST_MERCHANT_CITY, FIRST_MERCHANT_ZIP, FIRST_CARD_NUM,
            ORIGINATION_TIMESTAMP, BLANK_PROCESSING_TIMESTAMP);

    private static final String SECOND_RECORD_IMAGE = recordImage(SECOND_ID, SECOND_TYPE_CD,
            FIRST_CAT_CD, OPERATOR_SOURCE, SECOND_DESC, SECOND_AMOUNT_IMAGE, MERCHANT_ID,
            SECOND_MERCHANT_NAME, SECOND_MERCHANT_CITY, SECOND_MERCHANT_ZIP, SECOND_CARD_NUM,
            ORIGINATION_TIMESTAMP, BLANK_PROCESSING_TIMESTAMP);

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

    private static String alphanumeric(String value, int width) {
        return value + " ".repeat(width - value.length());
    }

    private static String numeric(String value, int width) {
        return "0".repeat(width - value.length()) + value;
    }

    private static String imageWithAmount(String amountImage, String source) {
        return recordImage(FIRST_ID, FIRST_TYPE_CD, FIRST_CAT_CD, source, FIRST_DESC, amountImage,
                MERCHANT_ID, FIRST_MERCHANT_NAME, FIRST_MERCHANT_CITY, FIRST_MERCHANT_ZIP,
                FIRST_CARD_NUM, ORIGINATION_TIMESTAMP, BLANK_PROCESSING_TIMESTAMP);
    }

    private static BigDecimal amountOf(String amountImage, String source) {
        return DailyTransactionRecordMapper.fromRecord(imageWithAmount(amountImage, source))
                .getDalytranAmt();
    }

    private static int encodedWidth(String value) {
        return value.getBytes(StandardCharsets.US_ASCII).length;
    }

    private static byte[] encoded(String value) {
        return value.getBytes(StandardCharsets.US_ASCII);
    }

    @Nested
    @DisplayName("the declared geometry")
    class TheDeclaredGeometry {
        @Test
        @DisplayName("all thirteen field offsets equal the zero-based positions the copybook declares")
        void allThirteenFieldOffsetsEqualTheDeclaredPositions() {
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

            assertThat(DailyTransactionRecordMapper.MAPPED_DATA_LENGTH
                    + DailyTransactionRecordMapper.FILLER_LENGTH).isEqualTo(350);
            assertThat(DailyTransactionRecordMapper.DALYTRAN_PROC_TS_OFFSET
                    + DailyTransactionRecordMapper.DALYTRAN_PROC_TS_LENGTH).isEqualTo(330);
        }

        @Test
        @DisplayName("the amount occupies eleven bytes, being nine integer digits plus two implied "
                + "decimals, with the sign overpunched rather than given a byte of its own")
        void theAmountOccupiesElevenBytes() {
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
            assertThat(DailyTransactionRecordMapper.DALYTRAN_ID_OFFSET).isZero();
            assertThat(DailyTransactionRecordMapper.DALYTRAN_ID_LENGTH).isEqualTo(16);

            DailyTransaction mapped =
                    DailyTransactionRecordMapper.fromRecord(FIRST_RECORD_IMAGE);

            assertThat(mapped.getDalytranId()).isEqualTo(FIRST_ID);
            assertThat(encodedWidth(mapped.getDalytranId())).isEqualTo(16);

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
            DailyTransaction mapped = DailyTransactionRecordMapper.fromRecord(FIRST_RECORD_IMAGE);

            assertThat(mapped.getDalytranMerchantId()).isEqualTo("800000000");
            assertThat(mapped.getDalytranMerchantName())
                    .isEqualTo(alphanumeric(FIRST_MERCHANT_NAME, 50));
            assertThat(mapped.getDalytranMerchantCity())
                    .isEqualTo(alphanumeric(FIRST_MERCHANT_CITY, 50));
            assertThat(mapped.getDalytranMerchantZip()).isEqualTo("72112     ");

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

            String zeroFilledMerchantId = recordImage(FIRST_ID, FIRST_TYPE_CD, FIRST_CAT_CD,
                    POS_TERMINAL_SOURCE, FIRST_DESC, FIRST_AMOUNT_IMAGE, "42",
                    FIRST_MERCHANT_NAME, FIRST_MERCHANT_CITY, FIRST_MERCHANT_ZIP, FIRST_CARD_NUM,
                    ORIGINATION_TIMESTAMP, BLANK_PROCESSING_TIMESTAMP);

            DailyTransaction padded = DailyTransactionRecordMapper.fromRecord(zeroFilledMerchantId);

            assertThat(padded.getDalytranMerchantId()).isEqualTo("000000042").isNotEqualTo("42");
            assertThat(encodedWidth(padded.getDalytranMerchantId())).isEqualTo(9);

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

            assertThat(amountOf("0000000000{", POS_TERMINAL_SOURCE))
                    .isEqualByComparingTo(negativeZero);
        }

        @Test
        @DisplayName("all twenty sign characters decode correctly - the reference input is the "
                + "estate's only complete overpunch oracle, its three hundred records splitting 250 "
                + "positive to 50 negative with every one of the twenty codes occurring")
        void allTwentySignCharactersDecodeCorrectly() {
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
            BigDecimal amount = amountOf("00000050477", POS_TERMINAL_SOURCE);

            assertThat(amount).isEqualByComparingTo(new BigDecimal("504.77"));
            assertThat(amount.scale()).isEqualTo(2);
        }

        @Test
        @DisplayName("every decoded amount carries scale exactly two, because a store into a "
                + "two-decimal field truncates toward zero and never rounds")
        void everyDecodedAmountCarriesScaleExactlyTwo() {
            assertThat(amountOf(FIRST_AMOUNT_IMAGE, POS_TERMINAL_SOURCE).scale()).isEqualTo(2);
            assertThat(amountOf(SECOND_AMOUNT_IMAGE, OPERATOR_SOURCE).scale()).isEqualTo(2);
            assertThat(amountOf("0000003250{", POS_TERMINAL_SOURCE).scale()).isEqualTo(2);
            assertThat(amountOf("0000000000K", OPERATOR_SOURCE).scale()).isEqualTo(2);

            assertThat(amountOf("0000003250{", POS_TERMINAL_SOURCE))
                    .isEqualTo(new BigDecimal("325.00"))
                    .isNotEqualTo(new BigDecimal("325"));
        }

        @Test
        @DisplayName("the sign of the amount tracks the origination source in the reference input: "
                + "every one of the fifty negative records is operator-entered and every one of the "
                + "250 positive records comes from a point-of-sale terminal")
        void theSignOfTheAmountTracksTheOriginationSource() {
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
            assertThat(emitted[349]).isEqualTo((byte) 0x20);
        }

        @Test
        @DisplayName("an entity built through the public thirteen-argument constructor emits the "
                + "reference image, which proves the constructor's parameter order is copybook order")
        void anEntityBuiltThroughThePublicConstructorEmitsTheReferenceImage() {
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
