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

import com.carddemo.domain.DailyTransaction;
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
 * Byte-parity acceptance for the 350-byte daily-transaction record layout.
 *
 * <h2>What is under test</h2>
 * {@link DailyTransactionRecordMapper} is the reader for the batch tier's primary input, so its
 * offsets are load-bearing twice over: once for the mapping itself and once because two external sort
 * specifications address this layout by absolute byte position. The card number sits at offset 262 for
 * sixteen bytes and the processing timestamp at 304 for twenty-six, and those are the exact addresses
 * the legacy sort control cards name. An offset defect here would not merely mis-map a field, it would
 * silently invalidate the report ordering and the statement projection built on top of it.
 *
 * <h2>Three properties asserted with particular care</h2>
 * The amount is eleven bytes of overpunched zoned decimal, which is a narrower field than the account
 * layout's twelve, so the maximum it can represent differs and is asserted separately rather than
 * assumed to be shared. The two twenty-six-byte timestamps are carried verbatim as text and never
 * parsed, because the processing timestamp is blank in every seeded record and a parse would fail on
 * data the legacy system accepts. And the thirteen fields plus the filler tile {@code [0, 350)}
 * exactly, so no byte is unaccounted for.
 *
 * <h2>Where the expectations come from</h2>
 * The geometry is stated as literal integers rather than read from the class under test. The field
 * values are read from a byte-level reading of the shipped fixture
 * {@code app/data/ASCII/dailytran.txt}, whose three hundred records are 350 bytes each on a 351-byte
 * stride. That fixture's filler is twenty spaces, which is what the encoder emits, so this layout - like
 * the account layout and unlike three others in this module - is held to a whole-record comparison with
 * no window excused.
 *
 * <p>A pure in-process unit test: no application context, no database, no container, no mocking
 * framework and no reflection.</p>
 *
 * <p>Provenance: part of the migration of the AWS CardDemo z/OS application at checkout SHA
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. No legacy source text is reproduced.</p>
 */
@DisplayName("DailyTransactionRecordMapper - the 350-byte daily-transaction record")
class DailyTransactionRecordMapperCoverageTest {

    /** Declared record width, stated here rather than read from the class under test. */
    private static final int EXPECTED_RECORD_WIDTH = 350;

    /** Declared width of the prefix the thirteen mapped fields occupy. */
    private static final int EXPECTED_MAPPED_WIDTH = 330;

    /** Declared placement of the sixteen-character transaction identifier. */
    private static final int EXPECTED_ID_OFFSET = 0;

    /** Declared width of the transaction identifier. */
    private static final int EXPECTED_ID_WIDTH = 16;

    /** Declared placement of the two-character type code. */
    private static final int EXPECTED_TYPE_OFFSET = 16;

    /** Declared width of the type code. */
    private static final int EXPECTED_TYPE_WIDTH = 2;

    /** Declared placement of the four-digit category code. */
    private static final int EXPECTED_CATEGORY_OFFSET = 18;

    /** Declared width of the category code. */
    private static final int EXPECTED_CATEGORY_WIDTH = 4;

    /** Declared placement of the ten-character source. */
    private static final int EXPECTED_SOURCE_OFFSET = 22;

    /** Declared width of the source. */
    private static final int EXPECTED_SOURCE_WIDTH = 10;

    /** Declared placement of the hundred-character description. */
    private static final int EXPECTED_DESCRIPTION_OFFSET = 32;

    /** Declared width of the description. */
    private static final int EXPECTED_DESCRIPTION_WIDTH = 100;

    /** Declared placement of the eleven-byte zoned-decimal amount. */
    private static final int EXPECTED_AMOUNT_OFFSET = 132;

    /** Declared width of the amount: nine integral digits plus two decimals. */
    private static final int EXPECTED_AMOUNT_WIDTH = 11;

    /** Declared placement of the nine-digit merchant identifier. */
    private static final int EXPECTED_MERCHANT_ID_OFFSET = 143;

    /** Declared width of the merchant identifier. */
    private static final int EXPECTED_MERCHANT_ID_WIDTH = 9;

    /** Declared placement of the fifty-character merchant name. */
    private static final int EXPECTED_MERCHANT_NAME_OFFSET = 152;

    /** Declared width of the merchant name. */
    private static final int EXPECTED_MERCHANT_NAME_WIDTH = 50;

    /** Declared placement of the fifty-character merchant city. */
    private static final int EXPECTED_MERCHANT_CITY_OFFSET = 202;

    /** Declared width of the merchant city. */
    private static final int EXPECTED_MERCHANT_CITY_WIDTH = 50;

    /** Declared placement of the ten-character merchant postal code. */
    private static final int EXPECTED_MERCHANT_ZIP_OFFSET = 252;

    /** Declared width of the merchant postal code. */
    private static final int EXPECTED_MERCHANT_ZIP_WIDTH = 10;

    /**
     * Declared placement of the sixteen-character card number.
     *
     * <p>This is the offset the two external sort specifications address, so it is a contract with the
     * job tier and not only with this mapper.</p>
     */
    private static final int EXPECTED_CARD_NUMBER_OFFSET = 262;

    /** Declared width of the card number. */
    private static final int EXPECTED_CARD_NUMBER_WIDTH = 16;

    /** Declared placement of the twenty-six-character origination timestamp. */
    private static final int EXPECTED_ORIG_TS_OFFSET = 278;

    /** Declared width of either timestamp. */
    private static final int EXPECTED_TIMESTAMP_WIDTH = 26;

    /**
     * Declared placement of the twenty-six-character processing timestamp.
     *
     * <p>Also addressed by an external sort specification, and by the alternate index the legacy
     * catalogue defines over the posted-transaction cluster at the same relative position.</p>
     */
    private static final int EXPECTED_PROC_TS_OFFSET = 304;

    /** Declared placement of the trailing filler. */
    private static final int EXPECTED_FILLER_OFFSET = 330;

    /** Declared width of the trailing filler. */
    private static final int EXPECTED_FILLER_WIDTH = 20;

    /** The canonical scale of every monetary amount in this module. */
    private static final int EXPECTED_MONETARY_SCALE = 2;

    /** Name of the shipped fixture that supplies the production-representative records. */
    private static final String FIXTURE_FILE = "dailytran.txt";

    /** Number of records the shipped fixture holds. */
    private static final int EXPECTED_FIXTURE_RECORDS = 300;

    /** Byte count of the shipped fixture, its three hundred records on a 351-byte stride. */
    private static final int EXPECTED_FIXTURE_BYTES = 105_300;

    /** Number of point-of-sale purchases among the fixture records. */
    private static final int EXPECTED_PURCHASES = 250;

    /** Number of operator-originated returns among the fixture records. */
    private static final int EXPECTED_RETURNS = 50;

    /** The single origination timestamp every fixture record shares. */
    private static final String FIXTURE_ORIG_TS = "2022-06-10 19:27:53.000000";

    /** The merchant identifier every fixture record carries. */
    private static final String FIXTURE_MERCHANT_ID = "800000000";

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
     * Right-pads a value with spaces to a declared field width.
     *
     * @param value the value to pad
     * @param width the declared field width
     * @return the value padded on the right to exactly {@code width} characters
     */
    private static String padded(final String value, final int width) {
        return value + " ".repeat(width - value.length());
    }

    /**
     * Assembles a complete 350-byte record image from independently supplied field images.
     *
     * <p>The five wide text fields are padded here to their declared widths so that the argument list
     * stays readable; every other argument must already be exactly its declared width, so a width
     * defect cannot hide behind this helper.</p>
     *
     * @param id           the sixteen-character identifier image
     * @param typeCode     the two-character type-code image
     * @param categoryCode the four-digit category-code image
     * @param source       the source, padded here to ten
     * @param description  the description, padded here to a hundred
     * @param amount       the eleven-byte zoned-decimal amount image
     * @param merchantId   the nine-digit merchant-identifier image
     * @param merchantName the merchant name, padded here to fifty
     * @param merchantCity the merchant city, padded here to fifty
     * @param merchantZip  the merchant postal code, padded here to ten
     * @param cardNumber   the sixteen-character card-number image
     * @param origTs       the twenty-six-character origination-timestamp image
     * @param procTs       the twenty-six-character processing-timestamp image
     * @return a record image of exactly {@link #EXPECTED_RECORD_WIDTH} characters
     */
    private static String image(final String id, final String typeCode, final String categoryCode,
            final String source, final String description, final String amount,
            final String merchantId, final String merchantName, final String merchantCity,
            final String merchantZip, final String cardNumber, final String origTs,
            final String procTs) {
        return id + typeCode + categoryCode
                + padded(source, EXPECTED_SOURCE_WIDTH)
                + padded(description, EXPECTED_DESCRIPTION_WIDTH)
                + amount + merchantId
                + padded(merchantName, EXPECTED_MERCHANT_NAME_WIDTH)
                + padded(merchantCity, EXPECTED_MERCHANT_CITY_WIDTH)
                + padded(merchantZip, EXPECTED_MERCHANT_ZIP_WIDTH)
                + cardNumber + origTs + procTs
                + run(' ', EXPECTED_FILLER_WIDTH);
    }

    /**
     * Assembles a record image reproducing the fixture's first record.
     *
     * @return the canonical record image
     */
    private static String canonicalImage() {
        return image("0000000000683580", "01", "0001", "POS TERM",
                "Purchase at Abshire-Lowe", "0000005047G", FIXTURE_MERCHANT_ID, "Abshire-Lowe",
                "North Enoshaven", "72112", "4859452612877065", FIXTURE_ORIG_TS,
                run(' ', EXPECTED_TIMESTAMP_WIDTH));
    }

    /**
     * Builds an entity whose thirteen mapped properties match {@link #canonicalImage()}.
     *
     * @return the canonical entity
     */
    private static DailyTransaction canonicalEntity() {
        return new DailyTransaction("0000000000683580", "01", "0001",
                padded("POS TERM", EXPECTED_SOURCE_WIDTH),
                padded("Purchase at Abshire-Lowe", EXPECTED_DESCRIPTION_WIDTH),
                new BigDecimal("504.77"), FIXTURE_MERCHANT_ID,
                padded("Abshire-Lowe", EXPECTED_MERCHANT_NAME_WIDTH),
                padded("North Enoshaven", EXPECTED_MERCHANT_CITY_WIDTH),
                padded("72112", EXPECTED_MERCHANT_ZIP_WIDTH), "4859452612877065", FIXTURE_ORIG_TS,
                run(' ', EXPECTED_TIMESTAMP_WIDTH));
    }

    /**
     * Builds an entity that differs from the canonical one only in its amount.
     *
     * @param amount the amount to carry
     * @return the entity
     */
    private static DailyTransaction entityWithAmount(final String amount) {
        return new DailyTransaction("0000000000683580", "01", "0001",
                padded("POS TERM", EXPECTED_SOURCE_WIDTH),
                padded("Purchase at Abshire-Lowe", EXPECTED_DESCRIPTION_WIDTH),
                new BigDecimal(amount), FIXTURE_MERCHANT_ID,
                padded("Abshire-Lowe", EXPECTED_MERCHANT_NAME_WIDTH),
                padded("North Enoshaven", EXPECTED_MERCHANT_CITY_WIDTH),
                padded("72112", EXPECTED_MERCHANT_ZIP_WIDTH), "4859452612877065", FIXTURE_ORIG_TS,
                run(' ', EXPECTED_TIMESTAMP_WIDTH));
    }

    /**
     * Assembles a record image that differs from the canonical one only in its amount image.
     *
     * @param amountImage the eleven-byte amount image to carry
     * @return the record image
     */
    private static String imageWithAmount(final String amountImage) {
        return image("0000000000683580", "01", "0001", "POS TERM", "Purchase at Abshire-Lowe",
                amountImage, FIXTURE_MERCHANT_ID, "Abshire-Lowe", "North Enoshaven", "72112",
                "4859452612877065", FIXTURE_ORIG_TS, run(' ', EXPECTED_TIMESTAMP_WIDTH));
    }

    /**
     * Supplies a spread of one-based fixture record ordinals covering both source types.
     *
     * @return the sampled ordinals
     */
    static Stream<Arguments> sampledOrdinals() {
        return Stream.of(1, 2, 3, 50, 100, 150, 200, 250, 299, 300).map(Arguments::of);
    }

    @Nested
    @DisplayName("the declared geometry reproduces the copybook exactly")
    class DeclaredGeometry {

        @Test
        @DisplayName("the record is 350 bytes and the mapped prefix is 330")
        void theWidthsAreTheDeclaredWidths() {
            assertThat(DailyTransactionRecordMapper.RECORD_LENGTH).isEqualTo(EXPECTED_RECORD_WIDTH);
            assertThat(DailyTransactionRecordMapper.MAPPED_DATA_LENGTH)
                    .isEqualTo(EXPECTED_MAPPED_WIDTH);
        }

        @Test
        @DisplayName("all thirteen fields sit at their declared offsets")
        void allThirteenFieldsSitWhereTheCopybookPutsThem() {
            assertThat(DailyTransactionRecordMapper.DALYTRAN_ID_OFFSET).isEqualTo(EXPECTED_ID_OFFSET);
            assertThat(DailyTransactionRecordMapper.DALYTRAN_TYPE_CD_OFFSET)
                    .isEqualTo(EXPECTED_TYPE_OFFSET);
            assertThat(DailyTransactionRecordMapper.DALYTRAN_CAT_CD_OFFSET)
                    .isEqualTo(EXPECTED_CATEGORY_OFFSET);
            assertThat(DailyTransactionRecordMapper.DALYTRAN_SOURCE_OFFSET)
                    .isEqualTo(EXPECTED_SOURCE_OFFSET);
            assertThat(DailyTransactionRecordMapper.DALYTRAN_DESC_OFFSET)
                    .isEqualTo(EXPECTED_DESCRIPTION_OFFSET);
            assertThat(DailyTransactionRecordMapper.DALYTRAN_AMT_OFFSET)
                    .isEqualTo(EXPECTED_AMOUNT_OFFSET);
            assertThat(DailyTransactionRecordMapper.DALYTRAN_MERCHANT_ID_OFFSET)
                    .isEqualTo(EXPECTED_MERCHANT_ID_OFFSET);
            assertThat(DailyTransactionRecordMapper.DALYTRAN_MERCHANT_NAME_OFFSET)
                    .isEqualTo(EXPECTED_MERCHANT_NAME_OFFSET);
            assertThat(DailyTransactionRecordMapper.DALYTRAN_MERCHANT_CITY_OFFSET)
                    .isEqualTo(EXPECTED_MERCHANT_CITY_OFFSET);
            assertThat(DailyTransactionRecordMapper.DALYTRAN_MERCHANT_ZIP_OFFSET)
                    .isEqualTo(EXPECTED_MERCHANT_ZIP_OFFSET);
            assertThat(DailyTransactionRecordMapper.DALYTRAN_CARD_NUM_OFFSET)
                    .isEqualTo(EXPECTED_CARD_NUMBER_OFFSET);
            assertThat(DailyTransactionRecordMapper.DALYTRAN_ORIG_TS_OFFSET)
                    .isEqualTo(EXPECTED_ORIG_TS_OFFSET);
            assertThat(DailyTransactionRecordMapper.DALYTRAN_PROC_TS_OFFSET)
                    .isEqualTo(EXPECTED_PROC_TS_OFFSET);
            assertThat(DailyTransactionRecordMapper.FILLER_OFFSET).isEqualTo(EXPECTED_FILLER_OFFSET);
        }

        @Test
        @DisplayName("all thirteen fields have their declared widths")
        void allThirteenFieldsHaveTheirDeclaredWidths() {
            assertThat(DailyTransactionRecordMapper.DALYTRAN_ID_LENGTH).isEqualTo(EXPECTED_ID_WIDTH);
            assertThat(DailyTransactionRecordMapper.DALYTRAN_TYPE_CD_LENGTH)
                    .isEqualTo(EXPECTED_TYPE_WIDTH);
            assertThat(DailyTransactionRecordMapper.DALYTRAN_CAT_CD_LENGTH)
                    .isEqualTo(EXPECTED_CATEGORY_WIDTH);
            assertThat(DailyTransactionRecordMapper.DALYTRAN_SOURCE_LENGTH)
                    .isEqualTo(EXPECTED_SOURCE_WIDTH);
            assertThat(DailyTransactionRecordMapper.DALYTRAN_DESC_LENGTH)
                    .isEqualTo(EXPECTED_DESCRIPTION_WIDTH);
            assertThat(DailyTransactionRecordMapper.DALYTRAN_AMT_LENGTH)
                    .isEqualTo(EXPECTED_AMOUNT_WIDTH);
            assertThat(DailyTransactionRecordMapper.DALYTRAN_MERCHANT_ID_LENGTH)
                    .isEqualTo(EXPECTED_MERCHANT_ID_WIDTH);
            assertThat(DailyTransactionRecordMapper.DALYTRAN_MERCHANT_NAME_LENGTH)
                    .isEqualTo(EXPECTED_MERCHANT_NAME_WIDTH);
            assertThat(DailyTransactionRecordMapper.DALYTRAN_MERCHANT_CITY_LENGTH)
                    .isEqualTo(EXPECTED_MERCHANT_CITY_WIDTH);
            assertThat(DailyTransactionRecordMapper.DALYTRAN_MERCHANT_ZIP_LENGTH)
                    .isEqualTo(EXPECTED_MERCHANT_ZIP_WIDTH);
            assertThat(DailyTransactionRecordMapper.DALYTRAN_CARD_NUM_LENGTH)
                    .isEqualTo(EXPECTED_CARD_NUMBER_WIDTH);
            assertThat(List.of(DailyTransactionRecordMapper.DALYTRAN_ORIG_TS_LENGTH,
                            DailyTransactionRecordMapper.DALYTRAN_PROC_TS_LENGTH))
                    .as("both timestamps are twenty-six characters, which is what the sort key width"
                            + " in the legacy control cards assumes")
                    .containsOnly(EXPECTED_TIMESTAMP_WIDTH);
        }

        @Test
        @DisplayName("the amount width is the codec's own daily-transaction width, not a local copy")
        void theAmountWidthIsTheCodecsOwnWidth() {
            assertThat(DailyTransactionRecordMapper.DALYTRAN_AMT_LENGTH)
                    .as("a locally duplicated width could drift from the codec's and produce an"
                            + " image of the right length and the wrong value")
                    .isEqualTo(ZonedDecimalCodec.DAILY_TRANSACTION_AMOUNT_WIDTH)
                    .isEqualTo(EXPECTED_AMOUNT_WIDTH);
            assertThat(EXPECTED_AMOUNT_WIDTH)
                    .as("eleven bytes is one narrower than the account layout's twelve, so the two"
                            + " layouts do not share a maximum")
                    .isNotEqualTo(ZonedDecimalCodec.ACCOUNT_AMOUNT_WIDTH);
        }

        @Test
        @DisplayName("the card number and processing timestamp sit at the offsets the sort cards name")
        void theSortAddressedFieldsSitWhereTheJobTierExpects() {
            assertThat(DailyTransactionRecordMapper.DALYTRAN_CARD_NUM_OFFSET)
                    .as("one-based column 263 in a sort control card is zero-based offset 262")
                    .isEqualTo(262);
            assertThat(DailyTransactionRecordMapper.DALYTRAN_CARD_NUM_LENGTH).isEqualTo(16);
            assertThat(DailyTransactionRecordMapper.DALYTRAN_PROC_TS_OFFSET)
                    .as("one-based column 305 in a sort control card is zero-based offset 304")
                    .isEqualTo(304);
        }

        @Test
        @DisplayName("the thirteen fields and the filler tile the record exactly")
        void theThirteenFieldsAndTheFillerTileTheRecord() {
            int mapped = DailyTransactionRecordMapper.DALYTRAN_ID_LENGTH
                    + DailyTransactionRecordMapper.DALYTRAN_TYPE_CD_LENGTH
                    + DailyTransactionRecordMapper.DALYTRAN_CAT_CD_LENGTH
                    + DailyTransactionRecordMapper.DALYTRAN_SOURCE_LENGTH
                    + DailyTransactionRecordMapper.DALYTRAN_DESC_LENGTH
                    + DailyTransactionRecordMapper.DALYTRAN_AMT_LENGTH
                    + DailyTransactionRecordMapper.DALYTRAN_MERCHANT_ID_LENGTH
                    + DailyTransactionRecordMapper.DALYTRAN_MERCHANT_NAME_LENGTH
                    + DailyTransactionRecordMapper.DALYTRAN_MERCHANT_CITY_LENGTH
                    + DailyTransactionRecordMapper.DALYTRAN_MERCHANT_ZIP_LENGTH
                    + DailyTransactionRecordMapper.DALYTRAN_CARD_NUM_LENGTH
                    + DailyTransactionRecordMapper.DALYTRAN_ORIG_TS_LENGTH
                    + DailyTransactionRecordMapper.DALYTRAN_PROC_TS_LENGTH;

            assertThat(mapped)
                    .isEqualTo(EXPECTED_MAPPED_WIDTH)
                    .isEqualTo(DailyTransactionRecordMapper.MAPPED_DATA_LENGTH)
                    .isEqualTo(DailyTransactionRecordMapper.FILLER_OFFSET);
            assertThat(DailyTransactionRecordMapper.FILLER_LENGTH).isEqualTo(EXPECTED_FILLER_WIDTH);
            assertThat(DailyTransactionRecordMapper.FILLER_OFFSET
                    + DailyTransactionRecordMapper.FILLER_LENGTH)
                    .isEqualTo(EXPECTED_RECORD_WIDTH);
        }

        @Test
        @DisplayName("the filler character is a space, and the layout names itself")
        void theFillerCharacterIsASpace() {
            assertThat(DailyTransactionRecordMapper.FILLER_CHARACTER).isEqualTo(' ');
            assertThat(DailyTransactionRecordMapper.ARTEFACT).isNotBlank();
        }

        @Test
        @DisplayName("this layout is byte-for-byte the same shape as the posted-transaction layout")
        void thisLayoutMirrorsThePostedTransactionLayout() {
            assertThat(EXPECTED_RECORD_WIDTH)
                    .as("the daily and posted transaction records are identical in shape and differ"
                            + " only in field-name prefix, which is why they remain two entities with"
                            + " two lifecycles rather than one")
                    .isEqualTo(350);
            assertThat(EXPECTED_ORIG_TS_OFFSET).isEqualTo(278);
            assertThat(EXPECTED_PROC_TS_OFFSET).isEqualTo(304);
        }
    }

    @Nested
    @DisplayName("decoding a record image")
    class Decoding {

        @Test
        @DisplayName("all thirteen fields arrive at their declared positions")
        void allThirteenFieldsArriveAtTheirDeclaredPositions() {
            DailyTransaction decoded = DailyTransactionRecordMapper.fromRecord(canonicalImage());

            assertThat(decoded.getDalytranId()).isEqualTo("0000000000683580");
            assertThat(decoded.getDalytranTypeCd()).isEqualTo("01");
            assertThat(decoded.getDalytranCatCd()).isEqualTo("0001");
            assertThat(decoded.getDalytranSource())
                    .isEqualTo(padded("POS TERM", EXPECTED_SOURCE_WIDTH));
            assertThat(decoded.getDalytranDesc())
                    .isEqualTo(padded("Purchase at Abshire-Lowe", EXPECTED_DESCRIPTION_WIDTH));
            assertThat(decoded.getDalytranAmt()).isEqualByComparingTo("504.77");
            assertThat(decoded.getDalytranMerchantId()).isEqualTo(FIXTURE_MERCHANT_ID);
            assertThat(decoded.getDalytranMerchantName())
                    .isEqualTo(padded("Abshire-Lowe", EXPECTED_MERCHANT_NAME_WIDTH));
            assertThat(decoded.getDalytranMerchantCity())
                    .isEqualTo(padded("North Enoshaven", EXPECTED_MERCHANT_CITY_WIDTH));
            assertThat(decoded.getDalytranMerchantZip())
                    .isEqualTo(padded("72112", EXPECTED_MERCHANT_ZIP_WIDTH));
            assertThat(decoded.getDalytranCardNum()).isEqualTo("4859452612877065");
            assertThat(decoded.getDalytranOrigTs()).isEqualTo(FIXTURE_ORIG_TS);
            assertThat(decoded.getDalytranProcTs())
                    .isEqualTo(run(' ', EXPECTED_TIMESTAMP_WIDTH));
        }

        @Test
        @DisplayName("every field is read from its own offset, not from a neighbour's")
        void everyFieldIsReadFromItsOwnOffset() {
            DailyTransaction decoded = DailyTransactionRecordMapper.fromRecord(
                    image("AAAAAAAAAAAAAAAA", "BB", "0002", "CCCC", "DDDD", "0000000000A",
                            "000000003", "EEEE", "FFFF", "GGGG", "HHHHHHHHHHHHHHHH",
                            "IIIIIIIIIIIIIIIIIIIIIIIIII", "JJJJJJJJJJJJJJJJJJJJJJJJJJ"));

            assertThat(decoded.getDalytranId()).isEqualTo(run('A', EXPECTED_ID_WIDTH));
            assertThat(decoded.getDalytranTypeCd()).isEqualTo("BB");
            assertThat(decoded.getDalytranCatCd()).isEqualTo("0002");
            assertThat(decoded.getDalytranSource())
                    .isEqualTo(padded("CCCC", EXPECTED_SOURCE_WIDTH));
            assertThat(decoded.getDalytranDesc())
                    .isEqualTo(padded("DDDD", EXPECTED_DESCRIPTION_WIDTH));
            assertThat(decoded.getDalytranAmt()).isEqualByComparingTo("0.01");
            assertThat(decoded.getDalytranMerchantId()).isEqualTo("000000003");
            assertThat(decoded.getDalytranMerchantName())
                    .isEqualTo(padded("EEEE", EXPECTED_MERCHANT_NAME_WIDTH));
            assertThat(decoded.getDalytranMerchantCity())
                    .isEqualTo(padded("FFFF", EXPECTED_MERCHANT_CITY_WIDTH));
            assertThat(decoded.getDalytranMerchantZip())
                    .isEqualTo(padded("GGGG", EXPECTED_MERCHANT_ZIP_WIDTH));
            assertThat(decoded.getDalytranCardNum())
                    .as("thirteen distinct values prove that no two offsets are transposed")
                    .isEqualTo(run('H', EXPECTED_CARD_NUMBER_WIDTH));
            assertThat(decoded.getDalytranOrigTs()).isEqualTo(run('I', EXPECTED_TIMESTAMP_WIDTH));
            assertThat(decoded.getDalytranProcTs()).isEqualTo(run('J', EXPECTED_TIMESTAMP_WIDTH));
        }

        @Test
        @DisplayName("the amount decodes at scale exactly 2, as a BigDecimal")
        void theAmountDecodesAtScaleTwo() {
            DailyTransaction decoded = DailyTransactionRecordMapper.fromRecord(
                    imageWithAmount("0000000000{"));

            assertThat(decoded.getDalytranAmt())
                    .isInstanceOf(BigDecimal.class)
                    .isEqualByComparingTo("0.00");
            assertThat(decoded.getDalytranAmt().scale())
                    .as("a V99 field has two decimal places whatever its value")
                    .isEqualTo(EXPECTED_MONETARY_SCALE);
        }

        @Test
        @DisplayName("both timestamps are carried as text and never parsed")
        void bothTimestampsAreCarriedAsText() {
            DailyTransaction decoded = DailyTransactionRecordMapper.fromRecord(
                    imageWithAmount("0000005047G"));

            assertThat(decoded.getDalytranOrigTs())
                    .isInstanceOf(String.class)
                    .hasSize(EXPECTED_TIMESTAMP_WIDTH);
            assertThat(decoded.getDalytranProcTs())
                    .as("the processing timestamp is blank in every seeded record, so a parse here"
                            + " would reject data the legacy system accepts")
                    .isBlank()
                    .hasSize(EXPECTED_TIMESTAMP_WIDTH);
        }

        @Test
        @DisplayName("a blank processing timestamp survives as twenty-six spaces, not as null or empty")
        void aBlankProcessingTimestampSurvivesAsSpaces() {
            DailyTransaction decoded = DailyTransactionRecordMapper.fromRecord(canonicalImage());

            assertThat(decoded.getDalytranProcTs())
                    .isNotNull()
                    .isNotEmpty()
                    .isEqualTo(run(' ', EXPECTED_TIMESTAMP_WIDTH));
        }

        @Test
        @DisplayName("a populated processing timestamp is carried verbatim too")
        void aPopulatedProcessingTimestampIsCarriedVerbatim() {
            String populated = "2022-06-11 04:00:00.123456";

            DailyTransaction decoded = DailyTransactionRecordMapper.fromRecord(
                    image("0000000000683580", "01", "0001", "POS TERM", "Purchase",
                            "0000005047G", FIXTURE_MERCHANT_ID, "Abshire-Lowe", "North Enoshaven",
                            "72112", "4859452612877065", FIXTURE_ORIG_TS, populated));

            assertThat(decoded.getDalytranProcTs()).isEqualTo(populated);
        }

        @Test
        @DisplayName("text fields keep every significant space, trimmed nowhere")
        void textFieldsKeepEverySignificantSpace() {
            DailyTransaction decoded = DailyTransactionRecordMapper.fromRecord(
                    image("0000000000683580", "  ", "0001", "   ", "  leading and trailing  ",
                            "0000000000{", FIXTURE_MERCHANT_ID, "   ", "   ", "   ",
                            "                ", run(' ', EXPECTED_TIMESTAMP_WIDTH),
                            run(' ', EXPECTED_TIMESTAMP_WIDTH)));

            assertThat(decoded.getDalytranTypeCd()).isEqualTo("  ");
            assertThat(decoded.getDalytranDesc())
                    .as("a leading space is as much part of the field as a trailing one")
                    .startsWith("  leading and trailing  ")
                    .hasSize(EXPECTED_DESCRIPTION_WIDTH);
            assertThat(decoded.getDalytranCardNum())
                    .isEqualTo(run(' ', EXPECTED_CARD_NUMBER_WIDTH));
        }

        @Test
        @DisplayName("the byte overload agrees with the text overload field for field")
        void theByteOverloadAgreesWithTheTextOverload() {
            String text = canonicalImage();

            DailyTransaction fromText = DailyTransactionRecordMapper.fromRecord(text);
            DailyTransaction fromBytes = DailyTransactionRecordMapper.fromRecord(
                    text.getBytes(StandardCharsets.US_ASCII));

            assertThat(fromBytes.getDalytranId()).isEqualTo(fromText.getDalytranId());
            assertThat(fromBytes.getDalytranTypeCd()).isEqualTo(fromText.getDalytranTypeCd());
            assertThat(fromBytes.getDalytranCatCd()).isEqualTo(fromText.getDalytranCatCd());
            assertThat(fromBytes.getDalytranSource()).isEqualTo(fromText.getDalytranSource());
            assertThat(fromBytes.getDalytranDesc()).isEqualTo(fromText.getDalytranDesc());
            assertThat(fromBytes.getDalytranAmt()).isEqualTo(fromText.getDalytranAmt());
            assertThat(fromBytes.getDalytranMerchantId())
                    .isEqualTo(fromText.getDalytranMerchantId());
            assertThat(fromBytes.getDalytranMerchantName())
                    .isEqualTo(fromText.getDalytranMerchantName());
            assertThat(fromBytes.getDalytranMerchantCity())
                    .isEqualTo(fromText.getDalytranMerchantCity());
            assertThat(fromBytes.getDalytranMerchantZip())
                    .isEqualTo(fromText.getDalytranMerchantZip());
            assertThat(fromBytes.getDalytranCardNum()).isEqualTo(fromText.getDalytranCardNum());
            assertThat(fromBytes.getDalytranOrigTs()).isEqualTo(fromText.getDalytranOrigTs());
            assertThat(fromBytes.getDalytranProcTs()).isEqualTo(fromText.getDalytranProcTs());
        }
    }

    @Nested
    @DisplayName("the overpunched sign and the eleven-byte amount bounds")
    class AmountEncoding {

        @ParameterizedTest(name = "\"{0}\" decodes to {1}")
        @CsvSource({
            "0000005047G, 504.77",
            "0000000000{, 0.00",
            "0000000000A, 0.01",
            "0000000000I, 0.09",
            "0000000000}, 0.00",
            "0000000000J, -0.01",
            "0000000000R, -0.09",
            "0000009190}, -919.00",
            "0000006032B, 603.22",
            "9999999999I, 999999999.99",
            "9999999999R, -999999999.99",
            "00000050477, 504.77"})
        @DisplayName("the trailing byte carries both the low-order digit and the sign")
        void theTrailingByteCarriesDigitAndSign(final String amountImage, final String expected) {
            DailyTransaction decoded = DailyTransactionRecordMapper.fromRecord(
                    imageWithAmount(amountImage));

            assertThat(decoded.getDalytranAmt()).isEqualByComparingTo(expected);
        }

        @ParameterizedTest(name = "{0} encodes to \"{1}\"")
        @CsvSource({
            "504.77, 0000005047G",
            "0.00, 0000000000{",
            "0.01, 0000000000A",
            "-0.01, 0000000000J",
            "-919.00, 0000009190}",
            "603.22, 0000006032B",
            "999999999.99, 9999999999I",
            "-999999999.99, 9999999999R"})
        @DisplayName("encoding writes the same overpunched byte the decoder reads")
        void encodingWritesTheSameOverpunchedByte(final String value, final String expectedImage) {
            String encoded = DailyTransactionRecordMapper.toRecord(entityWithAmount(value));

            assertThat(encoded.substring(EXPECTED_AMOUNT_OFFSET,
                    EXPECTED_AMOUNT_OFFSET + EXPECTED_AMOUNT_WIDTH)).isEqualTo(expectedImage);
        }

        @ParameterizedTest(name = "{0} truncates to \"{1}\"")
        @CsvSource({
            "504.779, 0000005047G",
            "504.775, 0000005047G",
            "-504.779, 0000005047P",
            "1.999, 0000000019I",
            "-1.999, 0000000019R",
            "0.009, 0000000000{",
            "-0.009, 0000000000{"})
        @DisplayName("a value with more than two decimals is truncated toward zero, never rounded")
        void aWiderValueIsTruncatedTowardZero(final String value, final String expectedImage) {
            String encoded = DailyTransactionRecordMapper.toRecord(entityWithAmount(value));

            assertThat(encoded.substring(EXPECTED_AMOUNT_OFFSET,
                    EXPECTED_AMOUNT_OFFSET + EXPECTED_AMOUNT_WIDTH))
                    .as("the estate contains no ROUNDED clause, so half-up would be a parity defect")
                    .isEqualTo(expectedImage);
        }

        @Test
        @DisplayName("an amount needing a tenth integral digit is refused, because the field has nine")
        void anAmountNeedingATenthIntegralDigitIsRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("PIC S9(09)V99 stops at 999999999.99, one decimal digit short of the account"
                            + " layout's ceiling, so the two layouts must not share a bound")
                    .isThrownBy(() -> DailyTransactionRecordMapper.toRecord(
                            entityWithAmount("1000000000.00")));
        }

        @Test
        @DisplayName("the largest and smallest representable amounts round trip exactly")
        void theBoundsRoundTripExactly() {
            String largest = DailyTransactionRecordMapper.toRecord(
                    entityWithAmount("999999999.99"));
            String smallest = DailyTransactionRecordMapper.toRecord(
                    entityWithAmount("-999999999.99"));

            assertThat(DailyTransactionRecordMapper.fromRecord(largest).getDalytranAmt())
                    .isEqualByComparingTo("999999999.99");
            assertThat(DailyTransactionRecordMapper.fromRecord(smallest).getDalytranAmt())
                    .isEqualByComparingTo("-999999999.99");
        }

        @Test
        @DisplayName("a negative zero re-encodes as positive zero, the one byte a round trip loses")
        void aNegativeZeroReEncodesAsPositiveZero() {
            String original = imageWithAmount("0000000000}");

            String reEncoded = DailyTransactionRecordMapper.toRecord(
                    DailyTransactionRecordMapper.fromRecord(original));

            assertThat(reEncoded.charAt(EXPECTED_AMOUNT_OFFSET + EXPECTED_AMOUNT_WIDTH - 1))
                    .as("no fixture record carries a negative zero, so nothing in the estate depends"
                            + " on the distinction surviving")
                    .isEqualTo('{');
            assertThat(reEncoded).isNotEqualTo(original);
        }
    }

    @Nested
    @DisplayName("decoding one record out of a larger buffer")
    class BufferDecoding {

        /** Stride of the shipped fixture: the record width plus its one-byte terminator. */
        private static final int STRIDE = EXPECTED_RECORD_WIDTH + 1;

        @ParameterizedTest(name = "record {0}")
        @MethodSource("com.carddemo.util.DailyTransactionRecordMapperCoverageTest#sampledOrdinals")
        @DisplayName("stride arithmetic selects the same record the fixture reader does")
        void strideArithmeticSelectsTheSameRecord(final int ordinal) {
            SeededRecordFixture loaded = fixture();
            byte[] whole = String.join("\n", loaded.records())
                    .getBytes(StandardCharsets.US_ASCII);

            DailyTransaction fromBuffer = DailyTransactionRecordMapper.fromRecord(
                    whole, (ordinal - 1) * STRIDE);

            assertThat(fromBuffer.getDalytranId())
                    .as("stride %d must land on record %d and leave its terminator behind",
                            STRIDE, ordinal)
                    .isEqualTo(loaded.field(ordinal, EXPECTED_ID_OFFSET, EXPECTED_ID_WIDTH));
            assertThat(fromBuffer.getDalytranCardNum())
                    .isEqualTo(loaded.field(ordinal, EXPECTED_CARD_NUMBER_OFFSET,
                            EXPECTED_CARD_NUMBER_WIDTH));
        }

        @Test
        @DisplayName("a negative start index is refused rather than wrapped")
        void aNegativeStartIndexIsRefused() {
            byte[] buffer = new byte[EXPECTED_RECORD_WIDTH];

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> DailyTransactionRecordMapper.fromRecord(buffer, -1))
                    .withMessageContaining("must not be negative");
        }

        @Test
        @DisplayName("a record that would run past the end of the buffer is refused, not short-read")
        void anOverrunningRecordIsRefused() {
            byte[] buffer = canonicalImage().getBytes(StandardCharsets.US_ASCII);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> DailyTransactionRecordMapper.fromRecord(buffer, 1))
                    .withMessageContaining("does not fit inside the supplied buffer");
        }

        @Test
        @DisplayName("a null buffer is refused, and the refusal names this layout")
        void aNullBufferIsRefused() {
            assertThatNullPointerException()
                    .isThrownBy(() -> DailyTransactionRecordMapper.fromRecord(null, 0))
                    .withMessageContaining("DALYTRAN-RECORD");
        }
    }

    @Nested
    @DisplayName("a malformed image is refused rather than repaired")
    class MalformedInput {

        @ParameterizedTest(name = "width {0}")
        @ValueSource(ints = {0, 1, 330, 349, 400, 700})
        @DisplayName("any width other than 350 is refused")
        void anyOtherWidthIsRefused(final int width) {
            String wrongWidth = run('0', width);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> DailyTransactionRecordMapper.fromRecord(wrongWidth))
                    .withMessageContaining("must be exactly " + EXPECTED_RECORD_WIDTH)
                    .withMessageContaining(String.valueOf(width))
                    .withMessageContaining("never padded or truncated to fit");
        }

        @Test
        @DisplayName("an unstripped line terminator is diagnosed by name and by stride")
        void anUnstrippedTerminatorIsDiagnosedByName() {
            String withTerminator = canonicalImage() + "\n";

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> DailyTransactionRecordMapper.fromRecord(withTerminator))
                    .withMessageContaining("351")
                    .withMessageContaining("terminator");
        }

        @Test
        @DisplayName("a byte-array width refusal reports the same shape as the text one")
        void aByteArrayWidthRefusalReportsTheSameShape() {
            byte[] tooShort = canonicalImage().substring(0, EXPECTED_RECORD_WIDTH - 1)
                    .getBytes(StandardCharsets.US_ASCII);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> DailyTransactionRecordMapper.fromRecord(tooShort))
                    .withMessageContaining("must be exactly " + EXPECTED_RECORD_WIDTH)
                    .withMessageContaining(String.valueOf(EXPECTED_RECORD_WIDTH - 1));
        }

        @Test
        @DisplayName("a non-ASCII character is refused rather than transcoded")
        void aNonAsciiCharacterIsRefused() {
            String withAccent = image("0000000000683580", "01", "0001", "POS TERM",
                    "Purchase at Abshire-L\u00f6we", "0000005047G", FIXTURE_MERCHANT_ID,
                    "Abshire-Lowe", "North Enoshaven", "72112", "4859452612877065",
                    FIXTURE_ORIG_TS, run(' ', EXPECTED_TIMESTAMP_WIDTH));

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> DailyTransactionRecordMapper.fromRecord(withAccent))
                    .withMessageContaining("US-ASCII cannot represent");
        }

        @Test
        @DisplayName("a byte above 0x7F is refused, because it means another encoding")
        void aHighByteIsRefused() {
            byte[] record = canonicalImage().getBytes(StandardCharsets.US_ASCII);
            record[EXPECTED_MERCHANT_NAME_OFFSET] = (byte) 0xC1;

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> DailyTransactionRecordMapper.fromRecord(record))
                    .withMessageContaining("non-ASCII byte");
        }

        @ParameterizedTest(name = "amount image \"{0}\"")
        @ValueSource(strings = {"           ", "0000005 47G", "0000005X47G", "-0000005047"})
        @DisplayName("a malformed amount is refused, and no partially mapped entity is returned")
        void aMalformedAmountIsRefused(final String amountImage) {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("mapping is all-or-nothing, so one bad field fails the whole record")
                    .isThrownBy(() -> DailyTransactionRecordMapper.fromRecord(
                            imageWithAmount(amountImage)));
        }

        @Test
        @DisplayName("a null image is refused on both decode entry points, naming this layout")
        void aNullImageIsRefused() {
            assertThatNullPointerException()
                    .isThrownBy(() -> DailyTransactionRecordMapper.fromRecord((String) null))
                    .withMessageContaining("DALYTRAN-RECORD");
            assertThatNullPointerException()
                    .isThrownBy(() -> DailyTransactionRecordMapper.fromRecord((byte[]) null))
                    .withMessageContaining("DALYTRAN-RECORD");
        }
    }

    @Nested
    @DisplayName("encoding an entity")
    class Encoding {

        @Test
        @DisplayName("the image is exactly 350 bytes and carries no terminator")
        void theImageIsExactlyTheDeclaredWidth() {
            String encoded = DailyTransactionRecordMapper.toRecord(canonicalEntity());

            assertThat(encoded).hasSize(EXPECTED_RECORD_WIDTH);
            assertThat(encoded).doesNotContain("\n").doesNotContain("\r");
        }

        @Test
        @DisplayName("the canonical entity produces the canonical image, byte for byte")
        void theCanonicalEntityProducesTheCanonicalImage() {
            assertThat(DailyTransactionRecordMapper.toRecord(canonicalEntity()))
                    .as("an independently hand-assembled image is the oracle, never the mapper's own"
                            + " output")
                    .isEqualTo(canonicalImage());
        }

        @Test
        @DisplayName("the category code and merchant identifier are right-justified and zero-padded")
        void theNumericFieldsAreRightJustifiedAndZeroPadded() {
            String encoded = DailyTransactionRecordMapper.toRecord(new DailyTransaction(
                    "0000000000683580", "01", "2", padded("POS TERM", EXPECTED_SOURCE_WIDTH),
                    padded("Purchase", EXPECTED_DESCRIPTION_WIDTH), new BigDecimal("0.00"), "8",
                    padded("Abshire-Lowe", EXPECTED_MERCHANT_NAME_WIDTH),
                    padded("North Enoshaven", EXPECTED_MERCHANT_CITY_WIDTH),
                    padded("72112", EXPECTED_MERCHANT_ZIP_WIDTH), "4859452612877065",
                    FIXTURE_ORIG_TS, run(' ', EXPECTED_TIMESTAMP_WIDTH)));

            assertThat(encoded.substring(EXPECTED_CATEGORY_OFFSET,
                    EXPECTED_CATEGORY_OFFSET + EXPECTED_CATEGORY_WIDTH))
                    .as("a numeric field left-justified and space-padded would be the right width"
                            + " and the wrong code")
                    .isEqualTo("0002");
            assertThat(encoded.substring(EXPECTED_MERCHANT_ID_OFFSET,
                    EXPECTED_MERCHANT_ID_OFFSET + EXPECTED_MERCHANT_ID_WIDTH))
                    .isEqualTo("000000008");
        }

        @Test
        @DisplayName("text fields are left-justified and space-padded")
        void textFieldsAreLeftJustifiedAndSpacePadded() {
            String encoded = DailyTransactionRecordMapper.toRecord(new DailyTransaction(
                    "0000000000683580", "01", "0001", "P", "D", new BigDecimal("0.00"),
                    FIXTURE_MERCHANT_ID, "M", "C", "Z", "4859452612877065", FIXTURE_ORIG_TS,
                    run(' ', EXPECTED_TIMESTAMP_WIDTH)));

            assertThat(encoded.substring(EXPECTED_SOURCE_OFFSET,
                    EXPECTED_SOURCE_OFFSET + EXPECTED_SOURCE_WIDTH)).isEqualTo("P         ");
            assertThat(encoded.substring(EXPECTED_MERCHANT_ZIP_OFFSET,
                    EXPECTED_MERCHANT_ZIP_OFFSET + EXPECTED_MERCHANT_ZIP_WIDTH))
                    .isEqualTo("Z         ");
        }

        @Test
        @DisplayName("the trailing filler is twenty spaces")
        void theTrailingFillerIsSpaces() {
            String encoded = DailyTransactionRecordMapper.toRecord(canonicalEntity());

            assertThat(encoded.substring(EXPECTED_FILLER_OFFSET))
                    .hasSize(EXPECTED_FILLER_WIDTH)
                    .isEqualTo(run(' ', EXPECTED_FILLER_WIDTH));
        }

        @Test
        @DisplayName("the byte encoder returns the same image, in a fresh array the caller owns")
        void theByteEncoderReturnsTheSameImage() {
            DailyTransaction record = canonicalEntity();

            byte[] first = DailyTransactionRecordMapper.toRecordBytes(record);
            byte[] second = DailyTransactionRecordMapper.toRecordBytes(record);

            assertThat(new String(first, StandardCharsets.US_ASCII)).isEqualTo(canonicalImage());
            assertThat(first).hasSize(EXPECTED_RECORD_WIDTH).isNotSameAs(second).isEqualTo(second);
        }

        @Test
        @DisplayName("an over-wide value is refused rather than truncated to fit")
        void anOverWideValueIsRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> DailyTransactionRecordMapper.toRecord(new DailyTransaction(
                            "00000000006835801", "01", "0001",
                            padded("POS TERM", EXPECTED_SOURCE_WIDTH),
                            padded("Purchase", EXPECTED_DESCRIPTION_WIDTH), new BigDecimal("0.00"),
                            FIXTURE_MERCHANT_ID, "Abshire-Lowe", "North Enoshaven", "72112",
                            "4859452612877065", FIXTURE_ORIG_TS,
                            run(' ', EXPECTED_TIMESTAMP_WIDTH))))
                    .withMessageContaining("never truncated to fit");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> DailyTransactionRecordMapper.toRecord(new DailyTransaction(
                            "0000000000683580", "01", "0001",
                            padded("POS TERM", EXPECTED_SOURCE_WIDTH),
                            padded("Purchase", EXPECTED_DESCRIPTION_WIDTH), new BigDecimal("0.00"),
                            FIXTURE_MERCHANT_ID, "Abshire-Lowe", "North Enoshaven", "72112",
                            "4859452612877065", FIXTURE_ORIG_TS + "X",
                            run(' ', EXPECTED_TIMESTAMP_WIDTH))))
                    .withMessageContaining("never truncated to fit");
        }

        @Test
        @DisplayName("a null property is refused, and the refusal names the legacy field")
        void aNullPropertyIsRefused() {
            assertThatNullPointerException()
                    .isThrownBy(() -> DailyTransactionRecordMapper.toRecord(new DailyTransaction(
                            null, "01", "0001", padded("POS TERM", EXPECTED_SOURCE_WIDTH),
                            padded("Purchase", EXPECTED_DESCRIPTION_WIDTH), new BigDecimal("0.00"),
                            FIXTURE_MERCHANT_ID, "Abshire-Lowe", "North Enoshaven", "72112",
                            "4859452612877065", FIXTURE_ORIG_TS,
                            run(' ', EXPECTED_TIMESTAMP_WIDTH))))
                    .withMessageContaining("DALYTRAN-ID")
                    .withMessageContaining("no concept of an absent field");
            assertThatNullPointerException()
                    .as("the amount shares the character fields' null contract rather than raising a"
                            + " differently typed failure")
                    .isThrownBy(() -> DailyTransactionRecordMapper.toRecordBytes(
                            new DailyTransaction("0000000000683580", "01", "0001",
                                    padded("POS TERM", EXPECTED_SOURCE_WIDTH),
                                    padded("Purchase", EXPECTED_DESCRIPTION_WIDTH), null,
                                    FIXTURE_MERCHANT_ID, "Abshire-Lowe", "North Enoshaven", "72112",
                                    "4859452612877065", FIXTURE_ORIG_TS,
                                    run(' ', EXPECTED_TIMESTAMP_WIDTH))))
                    .withMessageContaining("DALYTRAN-AMT");
            assertThatNullPointerException()
                    .isThrownBy(() -> DailyTransactionRecordMapper.toRecord(new DailyTransaction(
                            "0000000000683580", "01", "0001",
                            padded("POS TERM", EXPECTED_SOURCE_WIDTH),
                            padded("Purchase", EXPECTED_DESCRIPTION_WIDTH), new BigDecimal("0.00"),
                            FIXTURE_MERCHANT_ID, "Abshire-Lowe", "North Enoshaven", "72112",
                            "4859452612877065", FIXTURE_ORIG_TS, null)))
                    .withMessageContaining("DALYTRAN-PROC-TS");
        }

        @Test
        @DisplayName("a null entity is refused on both encode entry points")
        void aNullEntityIsRefused() {
            assertThatNullPointerException()
                    .isThrownBy(() -> DailyTransactionRecordMapper.toRecord(null))
                    .withMessageContaining("source entity must not be null");
            assertThatNullPointerException()
                    .isThrownBy(() -> DailyTransactionRecordMapper.toRecordBytes(null))
                    .withMessageContaining("source entity must not be null");
        }
    }

    @Nested
    @DisplayName("the shipped fixture, read as a byte-level authority")
    class ReferenceFixture {

        @Test
        @DisplayName("the fixture holds three hundred 350-byte records on a 351-byte stride")
        void theFixtureHoldsThreeHundredRecords() {
            SeededRecordFixture loaded = fixture();

            assertThat(loaded.recordCount()).isEqualTo(EXPECTED_FIXTURE_RECORDS);
            assertThat(loaded.impliedByteCount()).isEqualTo(EXPECTED_FIXTURE_BYTES);
            assertThat(loaded.records())
                    .allSatisfy(record -> assertThat(record).hasSize(EXPECTED_RECORD_WIDTH));
        }

        @Test
        @DisplayName("every production record survives a round trip over all three hundred fifty bytes")
        void everyProductionRecordSurvivesAWholeRecordRoundTrip() {
            assertThat(fixture().records()).allSatisfy(original ->
                    assertThat(DailyTransactionRecordMapper.toRecord(
                            DailyTransactionRecordMapper.fromRecord(original)))
                            .as("this layout's filler is spaces in the fixture and spaces in the"
                                    + " encoder, so no window is excused from the comparison")
                            .isEqualTo(original));
        }

        @Test
        @DisplayName("the first record decodes to the values transcribed from the file")
        void theFirstRecordDecodesToItsTranscribedValues() {
            DailyTransaction first = DailyTransactionRecordMapper.fromRecord(fixture().record(1));

            assertThat(first.getDalytranId()).isEqualTo("0000000000683580");
            assertThat(first.getDalytranTypeCd()).isEqualTo("01");
            assertThat(first.getDalytranCatCd()).isEqualTo("0001");
            assertThat(first.getDalytranSource())
                    .isEqualTo(padded("POS TERM", EXPECTED_SOURCE_WIDTH));
            assertThat(first.getDalytranAmt()).isEqualByComparingTo("504.77");
            assertThat(first.getDalytranMerchantId()).isEqualTo(FIXTURE_MERCHANT_ID);
            assertThat(first.getDalytranCardNum()).isEqualTo("4859452612877065");
            assertThat(first.getDalytranOrigTs()).isEqualTo(FIXTURE_ORIG_TS);
            assertThat(first.getDalytranProcTs()).isEqualTo(run(' ', EXPECTED_TIMESTAMP_WIDTH));
            assertThat(first.getDalytranDesc()).startsWith("Purchase at Abshire-Lowe");
        }

        @Test
        @DisplayName("the second record is the operator-originated return transcribed from the file")
        void theSecondRecordIsTheTranscribedReturn() {
            DailyTransaction second = DailyTransactionRecordMapper.fromRecord(fixture().record(2));

            assertThat(second.getDalytranTypeCd()).isEqualTo("03");
            assertThat(second.getDalytranSource())
                    .isEqualTo(padded("OPERATOR", EXPECTED_SOURCE_WIDTH));
            assertThat(second.getDalytranAmt())
                    .as("a return carries a negatively overpunched amount, which is what makes the"
                            + " credit posting path reachable from seed data")
                    .isEqualByComparingTo("-919.00")
                    .isNegative();
        }

        @Test
        @DisplayName("the last record decodes to the values transcribed from the file")
        void theLastRecordDecodesToItsTranscribedValues() {
            DailyTransaction last = DailyTransactionRecordMapper.fromRecord(
                    fixture().record(EXPECTED_FIXTURE_RECORDS));

            assertThat(last.getDalytranId()).isEqualTo("0000000996722787");
            assertThat(last.getDalytranAmt()).isEqualByComparingTo("603.22");
            assertThat(last.getDalytranCardNum()).isEqualTo("3260763612337560");
            assertThat(last.getDalytranDesc()).startsWith("Purchase at Kilback LLC");
        }

        @Test
        @DisplayName("the three hundred records split 250 purchases to 50 returns")
        void theRecordsSplitTwoHundredFiftyToFifty() {
            List<String> sources = fixture().records().stream()
                    .map(record -> record.substring(EXPECTED_SOURCE_OFFSET,
                            EXPECTED_SOURCE_OFFSET + EXPECTED_SOURCE_WIDTH))
                    .toList();

            assertThat(sources.stream()
                    .filter(source -> source.equals(padded("POS TERM", EXPECTED_SOURCE_WIDTH)))
                    .count())
                    .as("both signed directions must be exercised by seed data alone")
                    .isEqualTo(EXPECTED_PURCHASES);
            assertThat(sources.stream()
                    .filter(source -> source.equals(padded("OPERATOR", EXPECTED_SOURCE_WIDTH)))
                    .count())
                    .isEqualTo(EXPECTED_RETURNS);
            assertThat(sources).doesNotContainNull().hasSize(EXPECTED_FIXTURE_RECORDS);
        }

        @Test
        @DisplayName("only two type-and-category pairs appear, matching the two source types")
        void onlyTwoTypeAndCategoryPairsAppear() {
            List<String> pairs = fixture().records().stream()
                    .map(record -> record.substring(EXPECTED_TYPE_OFFSET,
                            EXPECTED_CATEGORY_OFFSET + EXPECTED_CATEGORY_WIDTH))
                    .distinct()
                    .sorted()
                    .toList();

            assertThat(pairs)
                    .as("recorded so that a later test does not assume a third pair can be seeded")
                    .containsExactly("010001", "030001");
        }

        @Test
        @DisplayName("all three hundred records share one origination timestamp")
        void allRecordsShareOneOriginationTimestamp() {
            assertThat(fixture().records().stream()
                    .map(record -> record.substring(EXPECTED_ORIG_TS_OFFSET,
                            EXPECTED_ORIG_TS_OFFSET + EXPECTED_TIMESTAMP_WIDTH))
                    .distinct()
                    .toList())
                    .as("a single timestamp means a date-window filter cannot be exercised from this"
                            + " input, which is why the reporting test needs a constructed fixture")
                    .containsExactly(FIXTURE_ORIG_TS);
        }

        @Test
        @DisplayName("all three hundred processing timestamps are blank, since nothing has posted yet")
        void allProcessingTimestampsAreBlank() {
            assertThat(fixture().records()).allSatisfy(record ->
                    assertThat(record.substring(EXPECTED_PROC_TS_OFFSET,
                            EXPECTED_PROC_TS_OFFSET + EXPECTED_TIMESTAMP_WIDTH))
                            .as("the posting job stamps this field, so an unposted input must leave"
                                    + " it blank rather than absent")
                            .isEqualTo(run(' ', EXPECTED_TIMESTAMP_WIDTH)));
        }

        @Test
        @DisplayName("the three hundred identifiers are distinct, spread over fifty card numbers")
        void theIdentifiersAreDistinctOverFiftyCards() {
            List<String> identifiers = fixture().records().stream()
                    .map(record -> record.substring(EXPECTED_ID_OFFSET, EXPECTED_ID_WIDTH))
                    .toList();
            List<String> cardNumbers = fixture().records().stream()
                    .map(record -> record.substring(EXPECTED_CARD_NUMBER_OFFSET,
                            EXPECTED_CARD_NUMBER_OFFSET + EXPECTED_CARD_NUMBER_WIDTH))
                    .distinct()
                    .toList();

            assertThat(identifiers).hasSize(EXPECTED_FIXTURE_RECORDS).doesNotHaveDuplicates();
            assertThat(cardNumbers)
                    .as("three hundred transactions across fifty cards is six per card")
                    .hasSize(50);
        }

        @Test
        @DisplayName("every fixture amount decodes without error, and the sign census is mixed")
        void everyFixtureAmountDecodesAndTheSignCensusIsMixed() {
            List<BigDecimal> amounts = fixture().records().stream()
                    .map(record -> DailyTransactionRecordMapper.fromRecord(record)
                            .getDalytranAmt())
                    .toList();

            assertThat(amounts).hasSize(EXPECTED_FIXTURE_RECORDS)
                    .allSatisfy(amount -> assertThat(amount.scale())
                            .isEqualTo(EXPECTED_MONETARY_SCALE));
            assertThat(amounts.stream().filter(amount -> amount.signum() < 0).count())
                    .as("fifty negatives, one per operator-originated return")
                    .isEqualTo(EXPECTED_RETURNS);
            assertThat(amounts.stream().filter(amount -> amount.signum() > 0).count())
                    .isEqualTo(EXPECTED_PURCHASES);
        }

        @Test
        @DisplayName("every fixture filler is twenty spaces, which licenses the whole-record test")
        void everyFixtureFillerIsSpaces() {
            assertThat(fixture().records()).allSatisfy(record ->
                    assertThat(record.substring(EXPECTED_FILLER_OFFSET))
                            .isEqualTo(run(' ', EXPECTED_FILLER_WIDTH)));
        }
    }

    @Nested
    @DisplayName("the round trip is a fixed point in both directions")
    class RoundTrip {

        @Test
        @DisplayName("a decode of an encode returns an equal entity, field for field")
        void aDecodeOfAnEncodeReturnsAnEqualEntity() {
            DailyTransaction original = canonicalEntity();

            DailyTransaction round = DailyTransactionRecordMapper.fromRecord(
                    DailyTransactionRecordMapper.toRecord(original));

            assertThat(round.getDalytranId()).isEqualTo(original.getDalytranId());
            assertThat(round.getDalytranTypeCd()).isEqualTo(original.getDalytranTypeCd());
            assertThat(round.getDalytranCatCd()).isEqualTo(original.getDalytranCatCd());
            assertThat(round.getDalytranSource()).isEqualTo(original.getDalytranSource());
            assertThat(round.getDalytranDesc()).isEqualTo(original.getDalytranDesc());
            assertThat(round.getDalytranAmt()).isEqualTo(original.getDalytranAmt());
            assertThat(round.getDalytranMerchantId()).isEqualTo(original.getDalytranMerchantId());
            assertThat(round.getDalytranMerchantName())
                    .isEqualTo(original.getDalytranMerchantName());
            assertThat(round.getDalytranMerchantCity())
                    .isEqualTo(original.getDalytranMerchantCity());
            assertThat(round.getDalytranMerchantZip()).isEqualTo(original.getDalytranMerchantZip());
            assertThat(round.getDalytranCardNum()).isEqualTo(original.getDalytranCardNum());
            assertThat(round.getDalytranOrigTs()).isEqualTo(original.getDalytranOrigTs());
            assertThat(round.getDalytranProcTs()).isEqualTo(original.getDalytranProcTs());
        }

        @Test
        @DisplayName("a second round trip changes nothing, so encoding is idempotent")
        void aSecondRoundTripIsAFixedPoint() {
            String once = DailyTransactionRecordMapper.toRecord(
                    DailyTransactionRecordMapper.fromRecord(fixture().record(1)));

            String twice = DailyTransactionRecordMapper.toRecord(
                    DailyTransactionRecordMapper.fromRecord(once));

            assertThat(twice).isEqualTo(once);
        }

        @Test
        @DisplayName("the byte and text round trips agree with each other")
        void theByteAndTextRoundTripsAgree() {
            DailyTransaction record = canonicalEntity();

            byte[] viaBytes = DailyTransactionRecordMapper.toRecordBytes(record);
            String viaText = DailyTransactionRecordMapper.toRecord(record);

            assertThat(new String(viaBytes, StandardCharsets.US_ASCII)).isEqualTo(viaText);
            assertThat(DailyTransactionRecordMapper.fromRecord(viaBytes).getDalytranAmt())
                    .isEqualTo(DailyTransactionRecordMapper.fromRecord(viaText).getDalytranAmt());
        }
    }
}
