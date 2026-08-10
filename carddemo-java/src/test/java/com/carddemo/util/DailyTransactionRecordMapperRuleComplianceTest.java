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

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import com.carddemo.domain.DailyTransaction;
import com.carddemo.support.SensitiveValues;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * Verifies {@link DailyTransactionRecordMapper} against the 350-byte {@code DALYTRAN-RECORD}
 * layout published by copybook {@code CVTRA06Y}.
 *
 * <p>The daily-transaction record is the primary input to the posting pipeline, so this mapper sits
 * directly on the Gate 1 byte-parity path: {@code app/jcl/POSTTRAN.jcl} feeds
 * {@code app/data/ASCII/dailytran.txt} to {@code app/cbl/CBTRN02C.cbl}, and a record that decodes
 * and re-encodes to anything other than its original bytes would silently corrupt every downstream
 * balance. The strongest assertion in this class is therefore the whole-fixture round trip: all 300
 * records of the real sample file are decoded and re-encoded and each must come back byte for byte
 * identical. Unlike the reference-table mappers, whose fixture filler is ASCII zeros while the
 * mapper emits spaces, this layout's trailing filler is already spaces in the sample data, so the
 * round trip is exact rather than normalising.</p>
 *
 * <p>The amount field is {@code DALYTRAN-AMT PIC S9(09)V99} at offset 132, eleven zoned-decimal
 * bytes whose final byte carries both the low-order digit and the sign. The sample file is unusually
 * good coverage for that encoding: its 300 records exercise <em>all twenty</em> overpunch characters
 * — {@code {} and {@code A} through {@code I} for the 250 non-negative amounts, {@code }} and
 * {@code J} through {@code R} for the 50 negative ones — which is asserted here explicitly so that a
 * future change to the fixture cannot quietly narrow the encoding coverage.</p>
 *
 * <p>Because no {@code ROUNDED} clause appears anywhere in the COBOL estate, every store into a
 * {@code V99} field truncates toward zero. This class pins {@link RoundingMode#DOWN} with witnesses
 * whose third decimal is six or greater, since a third decimal of exactly five yields the same image
 * under both {@code DOWN} and {@code HALF_EVEN} and would prove nothing. It also records the
 * consequence that matters most at the field boundary: {@code 999999999.999} encodes cleanly under
 * truncation and would overflow the eleven-byte field under half-even rounding.</p>
 *
 * <p>Provenance: checkout SHA {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release
 * stamp {@code CardDemo_v1.0-15-g27d6c6f-68} (2022-07-19). Layout authority
 * {@code app/cpy/CVTRA06Y.cpy}; amount width authority {@code app/cpy/CVTRA06Y.cpy:L14}.</p>
 */
@DisplayName("DailyTransactionRecordMapper — the 350-byte CVTRA06Y daily-transaction record")
class DailyTransactionRecordMapperRuleComplianceTest {

    /** The real sample file shipped with the legacy estate and used as the Gate 4 named artefact. */
    private static final Path FIXTURE =
            Path.of("src", "test", "resources", "fixtures", "input", "dailytran.txt");

    /** Measured record count of {@link #FIXTURE}. */
    private static final int FIXTURE_RECORD_COUNT = 300;

    /** Measured stride of {@link #FIXTURE}: the 350-byte record plus one 0x0A terminator. */
    private static final int FIXTURE_STRIDE = DailyTransactionRecordMapper.RECORD_LENGTH + 1;

    /** The positive overpunch alphabet, indexed by low-order digit. */
    private static final String POSITIVE_OVERPUNCH = "{ABCDEFGHI";

    /** The negative overpunch alphabet, indexed by low-order digit. */
    private static final String NEGATIVE_OVERPUNCH = "}JKLMNOPQR";

    /** The two transaction sources present in the sample file, at their padded field width. */
    private static final String POINT_OF_SALE_SOURCE = "POS TERM  ";

    /** The operator-originated source, which carries the sample file's negative amounts. */
    private static final String OPERATOR_SOURCE = "OPERATOR  ";

    /** The single origination timestamp shared by every record in the sample file. */
    private static final String FIXTURE_ORIGINATION_TIMESTAMP = "2022-06-10 19:27:53.000000";

    /** Every record of {@link #FIXTURE}, decoded once and shared across the whole class. */
    private static List<String> fixtureRecords;

    /** The raw bytes of {@link #FIXTURE}, retained for the buffer-slicing overload. */
    private static byte[] fixtureBytes;

    @BeforeAll
    static void readTheRealFixtureOnce() throws IOException {
        fixtureBytes = Files.readAllBytes(FIXTURE);
        final List<String> records = new ArrayList<>(FIXTURE_RECORD_COUNT);
        for (int index = 0; index < FIXTURE_RECORD_COUNT; index++) {
            records.add(new String(fixtureBytes, index * FIXTURE_STRIDE,
                    DailyTransactionRecordMapper.RECORD_LENGTH, StandardCharsets.US_ASCII));
        }
        fixtureRecords = List.copyOf(records);
    }

    /**
     * Returns the record image at the supplied zero-based position in the sample file, stripped of
     * its line terminator.
     *
     * @param index the zero-based record position
     * @return the 350-byte record image
     */
    private static String fixtureRecord(final int index) {
        return fixtureRecords.get(index);
    }

    /**
     * Left-justifies the supplied value in a field of the supplied width, padding with spaces
     * exactly as {@link FixedWidthFieldReader#putAlphanumeric} does.
     *
     * @param value the value to place
     * @param width the field width in bytes
     * @return the padded field image
     */
    private static String spacePadded(final String value, final int width) {
        return value + " ".repeat(width - value.length());
    }

    /**
     * Right-justifies the supplied value in a field of the supplied width, padding with ASCII zeros
     * exactly as {@link FixedWidthFieldReader#putNumeric} does.
     *
     * @param value the value to place
     * @param width the field width in bytes
     * @return the padded field image
     */
    private static String zeroPadded(final String value, final int width) {
        return "0".repeat(width - value.length()) + value;
    }

    /**
     * Computes the zoned-decimal image the module must produce for the supplied amount, deriving it
     * from the documented algorithm rather than from the mapper under test. The overpunch character
     * <em>replaces</em> the final digit of the zero-padded unscaled value; it is never appended.
     *
     * @param amount the amount to encode
     * @return the eleven-byte zoned-decimal image
     */
    private static String zonedImage(final BigDecimal amount) {
        final BigDecimal truncated =
                amount.setScale(ZonedDecimalCodec.MONETARY_SCALE, RoundingMode.DOWN);
        final String digits = zeroPadded(truncated.unscaledValue().abs().toString(),
                DailyTransactionRecordMapper.DALYTRAN_AMT_LENGTH);
        final String alphabet =
                truncated.signum() < 0 ? NEGATIVE_OVERPUNCH : POSITIVE_OVERPUNCH;
        final int lowOrderDigit = digits.charAt(digits.length() - 1) - '0';
        return digits.substring(0, digits.length() - 1) + alphabet.charAt(lowOrderDigit);
    }

    /**
     * Assembles a well-formed 350-byte record image around the supplied variable parts, holding
     * every other field at a nominal value. Every field is written at its published offset and
     * width, so the result is a genuine record rather than an approximation of one.
     *
     * @param transactionId the sixteen-character transaction identifier
     * @param source        the ten-character source, already padded
     * @param amountImage   the eleven-byte zoned-decimal amount image
     * @return the assembled record image
     */
    private static String recordImage(final String transactionId, final String source,
            final String amountImage) {
        return transactionId
                + "01"
                + "0001"
                + source
                + spacePadded("Purchase at Abshire-Lowe",
                        DailyTransactionRecordMapper.DALYTRAN_DESC_LENGTH)
                + amountImage
                + "800000000"
                + spacePadded("Abshire-Lowe",
                        DailyTransactionRecordMapper.DALYTRAN_MERCHANT_NAME_LENGTH)
                + spacePadded("North Enoshaven",
                        DailyTransactionRecordMapper.DALYTRAN_MERCHANT_CITY_LENGTH)
                + spacePadded("72112", DailyTransactionRecordMapper.DALYTRAN_MERCHANT_ZIP_LENGTH)
                + "4859452612877065"
                + FIXTURE_ORIGINATION_TIMESTAMP
                + " ".repeat(DailyTransactionRecordMapper.DALYTRAN_PROC_TS_LENGTH)
                + " ".repeat(DailyTransactionRecordMapper.FILLER_LENGTH);
    }

    /**
     * Builds a nominal entity whose thirteen attributes are all present and all within their field
     * widths. The arguments of the sole all-arguments constructor are laid out one per line in
     * layout order so a reader can check them against the copybook without counting commas.
     *
     * @return a fully populated daily-transaction entity
     */
    private static DailyTransaction aNominalTransaction() {
        return new DailyTransaction(
                "0000000000683580",
                "01",
                "0001",
                POINT_OF_SALE_SOURCE,
                spacePadded("Purchase at Abshire-Lowe",
                        DailyTransactionRecordMapper.DALYTRAN_DESC_LENGTH),
                new BigDecimal("504.77"),
                "800000000",
                spacePadded("Abshire-Lowe",
                        DailyTransactionRecordMapper.DALYTRAN_MERCHANT_NAME_LENGTH),
                spacePadded("North Enoshaven",
                        DailyTransactionRecordMapper.DALYTRAN_MERCHANT_CITY_LENGTH),
                spacePadded("72112", DailyTransactionRecordMapper.DALYTRAN_MERCHANT_ZIP_LENGTH),
                "4859452612877065",
                FIXTURE_ORIGINATION_TIMESTAMP,
                " ".repeat(DailyTransactionRecordMapper.DALYTRAN_PROC_TS_LENGTH));
    }

    // ================================================================================
    // The published layout
    // ================================================================================

    @Nested
    @DisplayName("the published layout")
    class ThePublishedLayout {

        @Test
        @DisplayName("publishes the 350-byte record width the copybook declares")
        void publishesTheRecordWidthTheCopybookDeclares() {
            assertThat(DailyTransactionRecordMapper.RECORD_LENGTH).isEqualTo(350);
        }

        @Test
        @DisplayName("splits the record into 330 mapped bytes and 20 bytes of trailing filler")
        void splitsTheRecordIntoMappedBytesAndTrailingFiller() {
            assertThat(DailyTransactionRecordMapper.MAPPED_DATA_LENGTH).isEqualTo(330);
            assertThat(DailyTransactionRecordMapper.FILLER_OFFSET).isEqualTo(330);
            assertThat(DailyTransactionRecordMapper.FILLER_LENGTH).isEqualTo(20);
            assertThat(DailyTransactionRecordMapper.MAPPED_DATA_LENGTH
                    + DailyTransactionRecordMapper.FILLER_LENGTH)
                    .isEqualTo(DailyTransactionRecordMapper.RECORD_LENGTH);
        }

        @Test
        @DisplayName("emits its trailing filler as spaces, not as zeros")
        void emitsItsTrailingFillerAsSpaces() {
            assertThat(DailyTransactionRecordMapper.FILLER_CHARACTER).isEqualTo(' ');
        }

        @Test
        @DisplayName("names itself with both the record name and its copybook")
        void namesItselfWithBothTheRecordNameAndItsCopybook() {
            assertThat(DailyTransactionRecordMapper.ARTEFACT)
                    .isEqualTo("DALYTRAN-RECORD (CVTRA06Y)");
        }

        @Test
        @DisplayName("declares every field offset the copybook fixes")
        void declaresEveryFieldOffsetTheCopybookFixes() {
            assertThat(DailyTransactionRecordMapper.DALYTRAN_ID_OFFSET).isZero();
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
        @DisplayName("declares every field length the copybook fixes")
        void declaresEveryFieldLengthTheCopybookFixes() {
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
        @DisplayName("takes its amount width from the shared PIC S9(09)V99 constant")
        void takesItsAmountWidthFromTheSharedPictureConstant() {
            assertThat(DailyTransactionRecordMapper.DALYTRAN_AMT_LENGTH)
                    .isEqualTo(ZonedDecimalCodec.DAILY_TRANSACTION_AMOUNT_WIDTH)
                    .isEqualTo(ZonedDecimalCodec.WIDTH_PIC_S9_09_V99);
        }

        @Test
        @DisplayName("lays its fields end to end with no gap and no overlap")
        void laysItsFieldsEndToEndWithNoGapAndNoOverlap() {
            final int[][] fields = {
                {DailyTransactionRecordMapper.DALYTRAN_ID_OFFSET,
                    DailyTransactionRecordMapper.DALYTRAN_ID_LENGTH},
                {DailyTransactionRecordMapper.DALYTRAN_TYPE_CD_OFFSET,
                    DailyTransactionRecordMapper.DALYTRAN_TYPE_CD_LENGTH},
                {DailyTransactionRecordMapper.DALYTRAN_CAT_CD_OFFSET,
                    DailyTransactionRecordMapper.DALYTRAN_CAT_CD_LENGTH},
                {DailyTransactionRecordMapper.DALYTRAN_SOURCE_OFFSET,
                    DailyTransactionRecordMapper.DALYTRAN_SOURCE_LENGTH},
                {DailyTransactionRecordMapper.DALYTRAN_DESC_OFFSET,
                    DailyTransactionRecordMapper.DALYTRAN_DESC_LENGTH},
                {DailyTransactionRecordMapper.DALYTRAN_AMT_OFFSET,
                    DailyTransactionRecordMapper.DALYTRAN_AMT_LENGTH},
                {DailyTransactionRecordMapper.DALYTRAN_MERCHANT_ID_OFFSET,
                    DailyTransactionRecordMapper.DALYTRAN_MERCHANT_ID_LENGTH},
                {DailyTransactionRecordMapper.DALYTRAN_MERCHANT_NAME_OFFSET,
                    DailyTransactionRecordMapper.DALYTRAN_MERCHANT_NAME_LENGTH},
                {DailyTransactionRecordMapper.DALYTRAN_MERCHANT_CITY_OFFSET,
                    DailyTransactionRecordMapper.DALYTRAN_MERCHANT_CITY_LENGTH},
                {DailyTransactionRecordMapper.DALYTRAN_MERCHANT_ZIP_OFFSET,
                    DailyTransactionRecordMapper.DALYTRAN_MERCHANT_ZIP_LENGTH},
                {DailyTransactionRecordMapper.DALYTRAN_CARD_NUM_OFFSET,
                    DailyTransactionRecordMapper.DALYTRAN_CARD_NUM_LENGTH},
                {DailyTransactionRecordMapper.DALYTRAN_ORIG_TS_OFFSET,
                    DailyTransactionRecordMapper.DALYTRAN_ORIG_TS_LENGTH},
                {DailyTransactionRecordMapper.DALYTRAN_PROC_TS_OFFSET,
                    DailyTransactionRecordMapper.DALYTRAN_PROC_TS_LENGTH},
            };
            int expectedOffset = 0;
            for (final int[] field : fields) {
                assertThat(field[0]).isEqualTo(expectedOffset);
                expectedOffset += field[1];
            }
            assertThat(expectedOffset).isEqualTo(DailyTransactionRecordMapper.MAPPED_DATA_LENGTH);
        }

        @Test
        @DisplayName("is a static contract with a single hidden constructor")
        void isAStaticContractWithASingleHiddenConstructor() throws ReflectiveOperationException {
            final Constructor<?>[] constructors =
                    DailyTransactionRecordMapper.class.getDeclaredConstructors();
            assertThat(constructors).hasSize(1);
            final Constructor<DailyTransactionRecordMapper> constructor =
                    DailyTransactionRecordMapper.class.getDeclaredConstructor();
            constructor.setAccessible(true);
            assertThat(constructor.newInstance()).isNotNull();
        }
    }

    // ================================================================================
    // Decoding the real fixture
    // ================================================================================

    @Nested
    @DisplayName("decoding the real sample file")
    class DecodingTheRealFixture {

        @Test
        @DisplayName("the sample file holds 300 records of exactly 350 bytes")
        void theSampleFileHoldsThreeHundredRecordsOfExactlyThreeHundredAndFiftyBytes() {
            assertThat(fixtureBytes).hasSize(FIXTURE_RECORD_COUNT * FIXTURE_STRIDE);
            assertThat(fixtureRecords).hasSize(FIXTURE_RECORD_COUNT);
            assertThat(fixtureRecords)
                    .allSatisfy(record -> assertThat(record)
                            .hasSize(DailyTransactionRecordMapper.RECORD_LENGTH));
        }

        @Test
        @DisplayName("decodes every mapped field of the first record")
        void decodesEveryMappedFieldOfTheFirstRecord() {
            final DailyTransaction decoded =
                    DailyTransactionRecordMapper.fromRecord(fixtureRecord(0));

            assertThat(decoded.getDalytranId()).isEqualTo("0000000000683580");
            assertThat(decoded.getDalytranTypeCd()).isEqualTo("01");
            assertThat(decoded.getDalytranCatCd()).isEqualTo("0001");
            assertThat(decoded.getDalytranSource()).isEqualTo(POINT_OF_SALE_SOURCE);
            assertThat(decoded.getDalytranDesc())
                    .startsWith("Purchase at Abshire-Lowe")
                    .hasSize(DailyTransactionRecordMapper.DALYTRAN_DESC_LENGTH);
            assertThat(decoded.getDalytranAmt()).isEqualByComparingTo("504.77");
            assertThat(decoded.getDalytranMerchantId()).isEqualTo("800000000");
            assertThat(decoded.getDalytranMerchantName())
                    .startsWith("Abshire-Lowe")
                    .hasSize(DailyTransactionRecordMapper.DALYTRAN_MERCHANT_NAME_LENGTH);
            assertThat(decoded.getDalytranMerchantCity())
                    .startsWith("North Enoshaven")
                    .hasSize(DailyTransactionRecordMapper.DALYTRAN_MERCHANT_CITY_LENGTH);
            assertThat(decoded.getDalytranMerchantZip()).isEqualTo("72112     ");
            assertThat(SensitiveValues.fingerprint(decoded.getDalytranCardNum())).isEqualTo(SensitiveValues.fingerprint("4859452612877065"));
            assertThat(decoded.getDalytranOrigTs()).isEqualTo(FIXTURE_ORIGINATION_TIMESTAMP);
            assertThat(decoded.getDalytranProcTs())
                    .isBlank()
                    .hasSize(DailyTransactionRecordMapper.DALYTRAN_PROC_TS_LENGTH);
        }

        @Test
        @DisplayName("decodes the amount at scale two, never at the scale of the literal")
        void decodesTheAmountAtScaleTwo() {
            assertThat(fixtureRecords)
                    .allSatisfy(record -> assertThat(
                            DailyTransactionRecordMapper.fromRecord(record).getDalytranAmt().scale())
                            .isEqualTo(ZonedDecimalCodec.MONETARY_SCALE));
        }

        @Test
        @DisplayName("decodes all 300 records without refusing any of them")
        void decodesAllThreeHundredRecordsWithoutRefusingAnyOfThem() {
            final List<DailyTransaction> decoded = new ArrayList<>(FIXTURE_RECORD_COUNT);
            for (final String record : fixtureRecords) {
                decoded.add(DailyTransactionRecordMapper.fromRecord(record));
            }
            assertThat(decoded).hasSize(FIXTURE_RECORD_COUNT).doesNotContainNull();
        }

        @Test
        @DisplayName("yields a unique transaction identifier for every record")
        void yieldsAUniqueTransactionIdentifierForEveryRecord() {
            final Set<String> identifiers = new LinkedHashSet<>();
            for (final String record : fixtureRecords) {
                identifiers.add(DailyTransactionRecordMapper.fromRecord(record).getDalytranId());
            }
            assertThat(identifiers).hasSize(FIXTURE_RECORD_COUNT);
        }

        @Test
        @DisplayName("reads the two transaction sources the sample file carries and no others")
        void readsTheTwoTransactionSourcesTheSampleFileCarries() {
            final Set<String> sources = new TreeSet<>();
            for (final String record : fixtureRecords) {
                sources.add(DailyTransactionRecordMapper.fromRecord(record).getDalytranSource());
            }
            assertThat(sources).containsExactly(OPERATOR_SOURCE, POINT_OF_SALE_SOURCE);
        }

        @Test
        @DisplayName("reads the two type-and-category pairings the sample file carries")
        void readsTheTwoTypeAndCategoryPairingsTheSampleFileCarries() {
            final Set<String> pairs = new TreeSet<>();
            for (final String record : fixtureRecords) {
                final DailyTransaction decoded = DailyTransactionRecordMapper.fromRecord(record);
                pairs.add(decoded.getDalytranTypeCd() + decoded.getDalytranCatCd());
            }
            assertThat(pairs).containsExactly("010001", "030001");
        }

        @Test
        @DisplayName("leaves the processing timestamp blank on every record, as the posting job "
                + "is what stamps it")
        void leavesTheProcessingTimestampBlankOnEveryRecord() {
            assertThat(fixtureRecords)
                    .allSatisfy(record -> assertThat(
                            DailyTransactionRecordMapper.fromRecord(record).getDalytranProcTs())
                            .isBlank());
        }

        @Test
        @DisplayName("reads one shared origination timestamp across every record")
        void readsOneSharedOriginationTimestampAcrossEveryRecord() {
            final Set<String> timestamps = new TreeSet<>();
            for (final String record : fixtureRecords) {
                timestamps.add(DailyTransactionRecordMapper.fromRecord(record).getDalytranOrigTs());
            }
            assertThat(timestamps).containsExactly(FIXTURE_ORIGINATION_TIMESTAMP);
        }

        @Test
        @DisplayName("exercises all twenty overpunch characters across the sample file")
        void exercisesAllTwentyOverpunchCharactersAcrossTheSampleFile() {
            final Set<Character> finalBytes = new TreeSet<>();
            for (final String record : fixtureRecords) {
                finalBytes.add(record.charAt(DailyTransactionRecordMapper.DALYTRAN_AMT_OFFSET
                        + DailyTransactionRecordMapper.DALYTRAN_AMT_LENGTH - 1));
            }
            final Set<Character> expected = new TreeSet<>();
            for (final char character : (POSITIVE_OVERPUNCH + NEGATIVE_OVERPUNCH).toCharArray()) {
                expected.add(character);
            }
            assertThat(finalBytes).isEqualTo(expected).hasSize(20);
        }

        @Test
        @DisplayName("decodes fifty negative amounts and two hundred and fifty non-negative ones")
        void decodesFiftyNegativeAmountsAndTwoHundredAndFiftyNonNegativeOnes() {
            int negative = 0;
            int nonNegative = 0;
            for (final String record : fixtureRecords) {
                if (DailyTransactionRecordMapper.fromRecord(record).getDalytranAmt().signum() < 0) {
                    negative++;
                } else {
                    nonNegative++;
                }
            }
            assertThat(negative).isEqualTo(50);
            assertThat(nonNegative).isEqualTo(250);
        }

        @Test
        @DisplayName("decodes the sample file's extreme amounts exactly")
        void decodesTheSampleFilesExtremeAmountsExactly() {
            final TreeSet<BigDecimal> amounts = new TreeSet<>();
            for (final String record : fixtureRecords) {
                amounts.add(DailyTransactionRecordMapper.fromRecord(record).getDalytranAmt());
            }
            assertThat(amounts.first()).isEqualByComparingTo("-998.33");
            assertThat(amounts.last()).isEqualByComparingTo("999.77");
        }

        @Test
        @DisplayName("reads a record out of the middle of an unsplit buffer at its stride offset")
        void readsARecordOutOfTheMiddleOfAnUnsplitBuffer() {
            final DailyTransaction sliced =
                    DailyTransactionRecordMapper.fromRecord(fixtureBytes, FIXTURE_STRIDE);
            final DailyTransaction whole =
                    DailyTransactionRecordMapper.fromRecord(fixtureRecord(1));
            assertThat(sliced.getDalytranId()).isEqualTo(whole.getDalytranId());
            assertThat(sliced.getDalytranAmt()).isEqualByComparingTo(whole.getDalytranAmt());
            assertThat(SensitiveValues.fingerprint(sliced.getDalytranCardNum())).isEqualTo(SensitiveValues.fingerprint(whole.getDalytranCardNum()));
        }

        @Test
        @DisplayName("decodes a byte image and a string image to the same entity")
        void decodesAByteImageAndAStringImageToTheSameEntity() {
            final DailyTransaction fromString =
                    DailyTransactionRecordMapper.fromRecord(fixtureRecord(0));
            final DailyTransaction fromBytes = DailyTransactionRecordMapper.fromRecord(
                    fixtureRecord(0).getBytes(StandardCharsets.US_ASCII));
            assertThat(fromBytes).isEqualTo(fromString);
        }

        @Test
        @DisplayName("preserves trailing spaces inside a field rather than trimming them")
        void preservesTrailingSpacesInsideAFieldRatherThanTrimming() {
            final DailyTransaction decoded =
                    DailyTransactionRecordMapper.fromRecord(fixtureRecord(0));
            assertThat(decoded.getDalytranMerchantZip()).endsWith("     ");
            assertThat(decoded.getDalytranSource()).endsWith("  ");
        }
    }

    // ================================================================================
    // Encoding and the byte-identical round trip
    // ================================================================================

    @Nested
    @DisplayName("encoding and the byte-identical round trip")
    class EncodingAndTheByteIdenticalRoundTrip {

        @Test
        @DisplayName("round trips all 300 sample records byte for byte")
        void roundTripsAllThreeHundredSampleRecordsByteForByte() {
            for (int index = 0; index < FIXTURE_RECORD_COUNT; index++) {
                final String original = fixtureRecord(index);
                final String reEmitted = DailyTransactionRecordMapper.toRecord(
                        DailyTransactionRecordMapper.fromRecord(original));
                assertThat(reEmitted)
                        .as("record %d must survive the round trip unchanged", index)
                        .isEqualTo(original);
            }
        }

        @Test
        @DisplayName("round trips all 300 sample records through the byte form as well")
        void roundTripsAllThreeHundredSampleRecordsThroughTheByteForm() {
            for (int index = 0; index < FIXTURE_RECORD_COUNT; index++) {
                final String original = fixtureRecord(index);
                final byte[] reEmitted = DailyTransactionRecordMapper.toRecordBytes(
                        DailyTransactionRecordMapper.fromRecord(original));
                assertThat(reEmitted)
                        .as("record %d must survive the byte round trip unchanged", index)
                        .isEqualTo(original.getBytes(StandardCharsets.US_ASCII));
            }
        }

        @Test
        @DisplayName("emits exactly 350 bytes for every sample record")
        void emitsExactlyThreeHundredAndFiftyBytesForEverySampleRecord() {
            for (final String record : fixtureRecords) {
                assertThat(DailyTransactionRecordMapper.toRecord(
                        DailyTransactionRecordMapper.fromRecord(record)))
                        .hasSize(DailyTransactionRecordMapper.RECORD_LENGTH);
            }
        }

        @Test
        @DisplayName("emits the string form and the byte form as the same 350 bytes")
        void emitsTheStringFormAndTheByteFormAsTheSameBytes() {
            final DailyTransaction transaction = aNominalTransaction();
            assertThat(DailyTransactionRecordMapper.toRecordBytes(transaction))
                    .isEqualTo(DailyTransactionRecordMapper.toRecord(transaction)
                            .getBytes(StandardCharsets.US_ASCII));
        }

        @Test
        @DisplayName("writes twenty spaces of trailing filler")
        void writesTwentySpacesOfTrailingFiller() {
            final String emitted = DailyTransactionRecordMapper.toRecord(aNominalTransaction());
            assertThat(emitted.substring(DailyTransactionRecordMapper.FILLER_OFFSET))
                    .hasSize(DailyTransactionRecordMapper.FILLER_LENGTH)
                    .isBlank()
                    .isEqualTo(" ".repeat(DailyTransactionRecordMapper.FILLER_LENGTH));
        }

        @Test
        @DisplayName("places every field at its published offset when emitting")
        void placesEveryFieldAtItsPublishedOffsetWhenEmitting() {
            final String emitted = DailyTransactionRecordMapper.toRecord(aNominalTransaction());

            assertThat(emitted.substring(DailyTransactionRecordMapper.DALYTRAN_ID_OFFSET,
                    DailyTransactionRecordMapper.DALYTRAN_ID_OFFSET
                            + DailyTransactionRecordMapper.DALYTRAN_ID_LENGTH))
                    .isEqualTo("0000000000683580");
            assertThat(emitted.substring(DailyTransactionRecordMapper.DALYTRAN_TYPE_CD_OFFSET,
                    DailyTransactionRecordMapper.DALYTRAN_TYPE_CD_OFFSET
                            + DailyTransactionRecordMapper.DALYTRAN_TYPE_CD_LENGTH))
                    .isEqualTo("01");
            assertThat(emitted.substring(DailyTransactionRecordMapper.DALYTRAN_CAT_CD_OFFSET,
                    DailyTransactionRecordMapper.DALYTRAN_CAT_CD_OFFSET
                            + DailyTransactionRecordMapper.DALYTRAN_CAT_CD_LENGTH))
                    .isEqualTo("0001");
            assertThat(emitted.substring(DailyTransactionRecordMapper.DALYTRAN_AMT_OFFSET,
                    DailyTransactionRecordMapper.DALYTRAN_AMT_OFFSET
                            + DailyTransactionRecordMapper.DALYTRAN_AMT_LENGTH))
                    .isEqualTo("0000005047G");
            assertThat(emitted.substring(DailyTransactionRecordMapper.DALYTRAN_MERCHANT_ID_OFFSET,
                    DailyTransactionRecordMapper.DALYTRAN_MERCHANT_ID_OFFSET
                            + DailyTransactionRecordMapper.DALYTRAN_MERCHANT_ID_LENGTH))
                    .isEqualTo("800000000");
            assertThat(emitted.substring(DailyTransactionRecordMapper.DALYTRAN_CARD_NUM_OFFSET,
                    DailyTransactionRecordMapper.DALYTRAN_CARD_NUM_OFFSET
                            + DailyTransactionRecordMapper.DALYTRAN_CARD_NUM_LENGTH))
                    .isEqualTo("4859452612877065");
            assertThat(emitted.substring(DailyTransactionRecordMapper.DALYTRAN_ORIG_TS_OFFSET,
                    DailyTransactionRecordMapper.DALYTRAN_ORIG_TS_OFFSET
                            + DailyTransactionRecordMapper.DALYTRAN_ORIG_TS_LENGTH))
                    .isEqualTo(FIXTURE_ORIGINATION_TIMESTAMP);
        }

        @Test
        @DisplayName("right-justifies the numeric category code with leading zeros")
        void rightJustifiesTheNumericCategoryCodeWithLeadingZeros() {
            final DailyTransaction transaction = aNominalTransaction();
            transaction.setDalytranCatCd("7");
            final String emitted = DailyTransactionRecordMapper.toRecord(transaction);
            assertThat(emitted.substring(DailyTransactionRecordMapper.DALYTRAN_CAT_CD_OFFSET,
                    DailyTransactionRecordMapper.DALYTRAN_CAT_CD_OFFSET
                            + DailyTransactionRecordMapper.DALYTRAN_CAT_CD_LENGTH))
                    .isEqualTo("0007");
        }

        @Test
        @DisplayName("right-justifies the numeric merchant identifier with leading zeros")
        void rightJustifiesTheNumericMerchantIdentifierWithLeadingZeros() {
            final DailyTransaction transaction = aNominalTransaction();
            transaction.setDalytranMerchantId("42");
            final String emitted = DailyTransactionRecordMapper.toRecord(transaction);
            assertThat(emitted.substring(DailyTransactionRecordMapper.DALYTRAN_MERCHANT_ID_OFFSET,
                    DailyTransactionRecordMapper.DALYTRAN_MERCHANT_ID_OFFSET
                            + DailyTransactionRecordMapper.DALYTRAN_MERCHANT_ID_LENGTH))
                    .isEqualTo("000000042");
        }

        @Test
        @DisplayName("left-justifies an alphanumeric field and pads it to width with spaces")
        void leftJustifiesAnAlphanumericFieldAndPadsItToWidthWithSpaces() {
            final DailyTransaction transaction = aNominalTransaction();
            transaction.setDalytranMerchantName("ACME");
            final String emitted = DailyTransactionRecordMapper.toRecord(transaction);
            assertThat(emitted.substring(DailyTransactionRecordMapper.DALYTRAN_MERCHANT_NAME_OFFSET,
                    DailyTransactionRecordMapper.DALYTRAN_MERCHANT_NAME_OFFSET
                            + DailyTransactionRecordMapper.DALYTRAN_MERCHANT_NAME_LENGTH))
                    .isEqualTo(spacePadded("ACME",
                            DailyTransactionRecordMapper.DALYTRAN_MERCHANT_NAME_LENGTH));
        }

        @ParameterizedTest(name = "{0} encodes to [{1}]")
        @CsvSource({
            "504.77, 0000005047G",
            "0.00, 0000000000{",
            "0.01, 0000000000A",
            "0.09, 0000000000I",
            "-0.01, 0000000000J",
            "-0.09, 0000000000R",
            "999.77, 0000009997G",
            "-113.11, 0000001131J",
            "-504.77, 0000005047P",
            "999999999.99, 9999999999I",
            "-999999999.99, 9999999999R",
        })
        @DisplayName("overpunches the sign into the final digit of the amount")
        void overpunchesTheSignIntoTheFinalDigitOfTheAmount(final String amount,
                final String expectedImage) {
            final DailyTransaction transaction = aNominalTransaction();
            transaction.setDalytranAmt(new BigDecimal(amount));
            final String emitted = DailyTransactionRecordMapper.toRecord(transaction);

            assertThat(emitted.substring(DailyTransactionRecordMapper.DALYTRAN_AMT_OFFSET,
                    DailyTransactionRecordMapper.DALYTRAN_AMT_OFFSET
                            + DailyTransactionRecordMapper.DALYTRAN_AMT_LENGTH))
                    .isEqualTo(expectedImage)
                    .isEqualTo(zonedImage(new BigDecimal(amount)));
        }

        @ParameterizedTest(name = "{0} truncates to [{1}]")
        @CsvSource({
            "504.776, 0000005047G",
            "504.779, 0000005047G",
            "504.006, 0000005040{",
            "-504.006, 0000005040}",
            "0.009, 0000000000{",
            "-0.009, 0000000000{",
        })
        @DisplayName("truncates toward zero rather than rounding, because the estate declares no "
                + "ROUNDED clause")
        void truncatesTowardZeroRatherThanRounding(final String amount,
                final String expectedImage) {
            final BigDecimal value = new BigDecimal(amount);
            final DailyTransaction transaction = aNominalTransaction();
            transaction.setDalytranAmt(value);

            final String emitted = DailyTransactionRecordMapper.toRecord(transaction);
            final String amountImage =
                    emitted.substring(DailyTransactionRecordMapper.DALYTRAN_AMT_OFFSET,
                            DailyTransactionRecordMapper.DALYTRAN_AMT_OFFSET
                                    + DailyTransactionRecordMapper.DALYTRAN_AMT_LENGTH);

            assertThat(amountImage).isEqualTo(expectedImage);

            final BigDecimal halfEven =
                    value.setScale(ZonedDecimalCodec.MONETARY_SCALE, RoundingMode.HALF_EVEN);
            final BigDecimal truncated =
                    value.setScale(ZonedDecimalCodec.MONETARY_SCALE, RoundingMode.DOWN);
            assertThat(halfEven)
                    .as("this witness must actually distinguish DOWN from HALF_EVEN")
                    .isNotEqualByComparingTo(truncated);
        }

        @Test
        @DisplayName("truncation keeps the widest amount inside the field where half-even rounding "
                + "would overflow it")
        void truncationKeepsTheWidestAmountInsideTheFieldWhereHalfEvenWouldOverflow() {
            final BigDecimal value = new BigDecimal("999999999.999");
            final DailyTransaction transaction = aNominalTransaction();
            transaction.setDalytranAmt(value);

            final String emitted = DailyTransactionRecordMapper.toRecord(transaction);
            assertThat(emitted.substring(DailyTransactionRecordMapper.DALYTRAN_AMT_OFFSET,
                    DailyTransactionRecordMapper.DALYTRAN_AMT_OFFSET
                            + DailyTransactionRecordMapper.DALYTRAN_AMT_LENGTH))
                    .isEqualTo("9999999999I");

            assertThat(value.setScale(ZonedDecimalCodec.MONETARY_SCALE, RoundingMode.HALF_EVEN)
                    .unscaledValue().abs().toString())
                    .as("half-even rounding would need a twelfth digit this field does not have")
                    .hasSize(DailyTransactionRecordMapper.DALYTRAN_AMT_LENGTH + 1);
        }

        @Test
        @DisplayName("normalises a negative zero amount to an unsigned zero on the way in")
        void normalisesANegativeZeroAmountToAnUnsignedZeroOnTheWayIn() {
            final String negativeZeroImage =
                    "0".repeat(DailyTransactionRecordMapper.DALYTRAN_AMT_LENGTH - 1) + "}";
            final String image =
                    recordImage("0000000000000001", POINT_OF_SALE_SOURCE, negativeZeroImage);

            final BigDecimal decoded =
                    DailyTransactionRecordMapper.fromRecord(image).getDalytranAmt();

            assertThat(decoded.signum()).isZero();
            assertThat(decoded).isEqualByComparingTo(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("re-emits a negative zero amount as a negative zero, the sign travelling beside "
                + "the amount rather than inside it")
        void reEmitsANegativeZeroAmountAsANegativeZero() {
            final String negativeZeroImage =
                    "0".repeat(DailyTransactionRecordMapper.DALYTRAN_AMT_LENGTH - 1) + "}";
            final String image =
                    recordImage("0000000000000001", POINT_OF_SALE_SOURCE, negativeZeroImage);

            final DailyTransaction decoded = DailyTransactionRecordMapper.fromRecord(image);
            final String reEmitted = DailyTransactionRecordMapper.toRecord(decoded);

            assertThat(decoded.isDalytranAmtNegativeZero()).isTrue();
            assertThat(reEmitted.substring(DailyTransactionRecordMapper.DALYTRAN_AMT_OFFSET,
                    DailyTransactionRecordMapper.DALYTRAN_AMT_OFFSET
                            + DailyTransactionRecordMapper.DALYTRAN_AMT_LENGTH))
                    .isEqualTo(negativeZeroImage);
            assertThat(reEmitted).isEqualTo(image);
        }

        @ParameterizedTest(name = "low-order digit {0}")
        @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9})
        @DisplayName("uses the documented overpunch alphabet for both signs at every low-order "
                + "digit")
        void usesTheDocumentedOverpunchAlphabetForBothSigns(final int lowOrderDigit) {
            final BigDecimal positive = new BigDecimal("1.0" + lowOrderDigit);
            final BigDecimal negative = positive.negate();

            final DailyTransaction transaction = aNominalTransaction();

            transaction.setDalytranAmt(positive);
            final String positiveImage = DailyTransactionRecordMapper.toRecord(transaction)
                    .substring(DailyTransactionRecordMapper.DALYTRAN_AMT_OFFSET,
                            DailyTransactionRecordMapper.DALYTRAN_AMT_OFFSET
                                    + DailyTransactionRecordMapper.DALYTRAN_AMT_LENGTH);
            assertThat(positiveImage)
                    .endsWith(String.valueOf(POSITIVE_OVERPUNCH.charAt(lowOrderDigit)))
                    .isEqualTo(zonedImage(positive));

            transaction.setDalytranAmt(negative);
            final String negativeImage = DailyTransactionRecordMapper.toRecord(transaction)
                    .substring(DailyTransactionRecordMapper.DALYTRAN_AMT_OFFSET,
                            DailyTransactionRecordMapper.DALYTRAN_AMT_OFFSET
                                    + DailyTransactionRecordMapper.DALYTRAN_AMT_LENGTH);
            assertThat(negativeImage)
                    .as("a non-zero magnitude keeps its sign at every low-order digit")
                    .endsWith(String.valueOf(NEGATIVE_OVERPUNCH.charAt(lowOrderDigit)))
                    .isEqualTo(zonedImage(negative));
        }

        @Test
        @DisplayName("round trips an amount through every overpunch character the fixture uses")
        void roundTripsAnAmountThroughEveryOverpunchCharacterTheFixtureUses() {
            for (final char overpunch
                    : (POSITIVE_OVERPUNCH + NEGATIVE_OVERPUNCH).toCharArray()) {
                final String amountImage =
                        "0".repeat(DailyTransactionRecordMapper.DALYTRAN_AMT_LENGTH - 3)
                                + "12" + overpunch;
                final String image =
                        recordImage("0000000000000002", OPERATOR_SOURCE, amountImage);

                final BigDecimal decoded =
                        DailyTransactionRecordMapper.fromRecord(image).getDalytranAmt();
                final int lowOrderDigit = POSITIVE_OVERPUNCH.indexOf(overpunch) >= 0
                        ? POSITIVE_OVERPUNCH.indexOf(overpunch)
                        : NEGATIVE_OVERPUNCH.indexOf(overpunch);
                final boolean negative = NEGATIVE_OVERPUNCH.indexOf(overpunch) >= 0;

                assertThat(decoded.abs())
                        .as("overpunch %s decodes digit %d", overpunch, lowOrderDigit)
                        .isEqualByComparingTo(new BigDecimal("1.2" + lowOrderDigit));
                if (negative && lowOrderDigit == 0) {
                    assertThat(decoded).isEqualByComparingTo(new BigDecimal("-1.20"));
                } else if (negative) {
                    assertThat(decoded.signum()).isNegative();
                } else {
                    assertThat(decoded.signum()).isPositive();
                }

                assertThat(DailyTransactionRecordMapper.toRecord(
                        DailyTransactionRecordMapper.fromRecord(image)))
                        .as("overpunch %s survives the round trip", overpunch)
                        .isEqualTo(image);
            }
        }

        @Test
        @DisplayName("round trips a purpose-built record assembled field by field")
        void roundTripsAPurposeBuiltRecordAssembledFieldByField() {
            final String image =
                    recordImage("0000000000683580", POINT_OF_SALE_SOURCE, "0000005047G");
            assertThat(image).hasSize(DailyTransactionRecordMapper.RECORD_LENGTH);
            assertThat(DailyTransactionRecordMapper.toRecord(
                    DailyTransactionRecordMapper.fromRecord(image)))
                    .isEqualTo(image);
        }
    }

    // ================================================================================
    // The static layout self-checks
    // ================================================================================

    @Nested
    @DisplayName("the static layout self-checks")
    class TheStaticLayoutSelfChecks {

        /**
         * Invokes the mapper's private contiguity assertion.
         *
         * @param fieldName      the field being checked
         * @param declaredOffset the offset the mapper publishes
         * @param computedOffset the offset the preceding widths imply
         * @throws ReflectiveOperationException if the private method cannot be reached
         */
        private void invokeRequireContiguous(final String fieldName, final int declaredOffset,
                final int computedOffset) throws ReflectiveOperationException {
            final Method method = DailyTransactionRecordMapper.class.getDeclaredMethod(
                    "requireContiguous", String.class, int.class, int.class);
            method.setAccessible(true);
            method.invoke(null, fieldName, declaredOffset, computedOffset);
        }

        /**
         * Invokes the mapper's private width-sum assertion.
         *
         * @param subject  the subject being checked
         * @param declared the published total
         * @param computed the total the parts imply
         * @throws ReflectiveOperationException if the private method cannot be reached
         */
        private void invokeRequireSum(final String subject, final int declared, final int computed)
                throws ReflectiveOperationException {
            final Method method = DailyTransactionRecordMapper.class.getDeclaredMethod(
                    "requireSum", String.class, int.class, int.class);
            method.setAccessible(true);
            method.invoke(null, subject, declared, computed);
        }

        @Test
        @DisplayName("the class initialises, which means every published offset already agrees "
                + "with the widths before it")
        void theClassInitialisesWhichMeansEveryPublishedOffsetAlreadyAgrees() {
            assertThat(DailyTransactionRecordMapper.RECORD_LENGTH).isPositive();
            assertThat(DailyTransactionRecordMapper.MAPPED_DATA_LENGTH)
                    .isEqualTo(DailyTransactionRecordMapper.DALYTRAN_PROC_TS_OFFSET
                            + DailyTransactionRecordMapper.DALYTRAN_PROC_TS_LENGTH);
        }

        @Test
        @DisplayName("accepts a contiguous offset without complaint")
        void acceptsAContiguousOffsetWithoutComplaint() throws ReflectiveOperationException {
            invokeRequireContiguous("DALYTRAN-AMT",
                    DailyTransactionRecordMapper.DALYTRAN_AMT_OFFSET,
                    DailyTransactionRecordMapper.DALYTRAN_AMT_OFFSET);
        }

        @Test
        @DisplayName("refuses a field whose declared offset leaves a hole in the record")
        void refusesAFieldWhoseDeclaredOffsetLeavesAHoleInTheRecord() {
            assertThatExceptionOfType(InvocationTargetException.class)
                    .isThrownBy(() -> invokeRequireContiguous("DALYTRAN-AMT", 132, 999))
                    .withCauseInstanceOf(IllegalStateException.class)
                    .satisfies(thrown -> assertThat(thrown.getCause())
                            .hasMessageContaining(DailyTransactionRecordMapper.ARTEFACT)
                            .hasMessageContaining("layout is inconsistent")
                            .hasMessageContaining("field 'DALYTRAN-AMT'")
                            .hasMessageContaining("declares offset 132")
                            .hasMessageContaining("sum to 999"));
        }

        @Test
        @DisplayName("accepts a width total that agrees with its parts")
        void acceptsAWidthTotalThatAgreesWithItsParts() throws ReflectiveOperationException {
            invokeRequireSum("record image", DailyTransactionRecordMapper.RECORD_LENGTH,
                    DailyTransactionRecordMapper.RECORD_LENGTH);
        }

        @Test
        @DisplayName("refuses a published total that disagrees with the sum of its parts")
        void refusesAPublishedTotalThatDisagreesWithTheSumOfItsParts() {
            assertThatExceptionOfType(InvocationTargetException.class)
                    .isThrownBy(() -> invokeRequireSum("record image", 350, 351))
                    .withCauseInstanceOf(IllegalStateException.class)
                    .satisfies(thrown -> assertThat(thrown.getCause())
                            .hasMessageContaining(DailyTransactionRecordMapper.ARTEFACT)
                            .hasMessageContaining("layout is inconsistent")
                            .hasMessageContaining("record image")
                            .hasMessageContaining("published as 350")
                            .hasMessageContaining("parts sum to 351"));
        }

        @Test
        @DisplayName("names the mapped prefix as well as the whole record in its width checks")
        void namesTheMappedPrefixAsWellAsTheWholeRecord() {
            assertThatExceptionOfType(InvocationTargetException.class)
                    .isThrownBy(() -> invokeRequireSum("mapped data prefix", 330, 329))
                    .withCauseInstanceOf(IllegalStateException.class)
                    .satisfies(thrown -> assertThat(thrown.getCause())
                            .hasMessageContaining("mapped data prefix")
                            .hasMessageContaining("published as 330")
                            .hasMessageContaining("parts sum to 329"));
        }
    }

    // ================================================================================
    // Refusals and their diagnostics
    // ================================================================================

    @Nested
    @DisplayName("refusals and their diagnostics")
    class RefusalsAndTheirDiagnostics {

        @Test
        @DisplayName("refuses a record image one byte short")
        void refusesARecordImageOneByteShort() {
            final String truncated = fixtureRecord(0)
                    .substring(0, DailyTransactionRecordMapper.RECORD_LENGTH - 1);
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> DailyTransactionRecordMapper.fromRecord(truncated))
                    .withMessageContaining(DailyTransactionRecordMapper.ARTEFACT)
                    .withMessageContaining("must be exactly 350 encoded bytes")
                    .withMessageContaining("is 349 encoded bytes")
                    .withMessageContaining("never padded or truncated to fit");
        }

        @Test
        @DisplayName("explains a one-byte overshoot as an unstripped line terminator")
        void explainsAOneByteOvershootAsAnUnstrippedLineTerminator() {
            final String overshoot = fixtureRecord(0) + "X";
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> DailyTransactionRecordMapper.fromRecord(overshoot))
                    .withMessageContaining("is 351 encoded bytes")
                    .withMessageContaining("unstripped 0x0A line terminator")
                    .withMessageContaining("stride is 351");
        }

        @Test
        @DisplayName("offers the terminator hint only for an overshoot of exactly one byte")
        void offersTheTerminatorHintOnlyForAnOvershootOfExactlyOneByte() {
            final String farTooLong = fixtureRecord(0) + "XX";
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> DailyTransactionRecordMapper.fromRecord(farTooLong))
                    .withMessageContaining("is 352 encoded bytes")
                    .withMessageNotContaining("line terminator");
        }

        @Test
        @DisplayName("refuses a byte image of the wrong width")
        void refusesAByteImageOfTheWrongWidth() {
            final byte[] truncated = fixtureRecord(0)
                    .substring(0, DailyTransactionRecordMapper.RECORD_LENGTH - 1)
                    .getBytes(StandardCharsets.US_ASCII);
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> DailyTransactionRecordMapper.fromRecord(truncated))
                    .withMessageContaining("is 349 encoded bytes");
        }

        @Test
        @DisplayName("reports only lengths when refusing a mis-sized record, never the record")
        void reportsOnlyLengthsWhenRefusingAMisSizedRecord() {
            final String hostile = "SECRET\r\nINJECTED\u0000PAYLOAD"
                    .repeat(DailyTransactionRecordMapper.RECORD_LENGTH / 24 + 1);
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> DailyTransactionRecordMapper.fromRecord(hostile))
                    .satisfies(thrown -> assertThat(thrown.getMessage())
                            .doesNotContain("SECRET")
                            .doesNotContain("INJECTED")
                            .doesNotContain("PAYLOAD")
                            .doesNotContain("\r")
                            .doesNotContain("\n")
                            .doesNotContain("\u0000"));
        }

        @Test
        @DisplayName("refuses a null string image by naming the artefact")
        void refusesANullStringImageByNamingTheArtefact() {
            assertThatNullPointerException()
                    .isThrownBy(() -> DailyTransactionRecordMapper.fromRecord((String) null))
                    .withMessage(DailyTransactionRecordMapper.ARTEFACT
                            + " record image must not be null");
        }

        @Test
        @DisplayName("refuses a null byte image by naming the artefact")
        void refusesANullByteImageByNamingTheArtefact() {
            assertThatNullPointerException()
                    .isThrownBy(() -> DailyTransactionRecordMapper.fromRecord((byte[]) null))
                    .withMessage(DailyTransactionRecordMapper.ARTEFACT
                            + " record image must not be null");
        }

        @Test
        @DisplayName("refuses a null buffer by naming it as a buffer rather than an image")
        void refusesANullBufferByNamingItAsABuffer() {
            assertThatNullPointerException()
                    .isThrownBy(() -> DailyTransactionRecordMapper.fromRecord(null, 0))
                    .withMessage(DailyTransactionRecordMapper.ARTEFACT
                            + " record buffer must not be null");
        }

        @Test
        @DisplayName("refuses a null entity when emitting a string image")
        void refusesANullEntityWhenEmittingAStringImage() {
            assertThatNullPointerException()
                    .isThrownBy(() -> DailyTransactionRecordMapper.toRecord(null))
                    .withMessage(DailyTransactionRecordMapper.ARTEFACT
                            + " source entity must not be null");
        }

        @Test
        @DisplayName("refuses a null entity when emitting a byte image")
        void refusesANullEntityWhenEmittingAByteImage() {
            assertThatNullPointerException()
                    .isThrownBy(() -> DailyTransactionRecordMapper.toRecordBytes(null))
                    .withMessage(DailyTransactionRecordMapper.ARTEFACT
                            + " source entity must not be null");
        }

        @Test
        @DisplayName("refuses a slice that would run past the end of the buffer")
        void refusesASliceThatWouldRunPastTheEndOfTheBuffer() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> DailyTransactionRecordMapper.fromRecord(
                            fixtureBytes, fixtureBytes.length - 10))
                    .withMessageContaining("does not fit inside the supplied buffer")
                    .withMessageContaining("recordWidth=350");
        }

        @Test
        @DisplayName("refuses a negative slice start")
        void refusesANegativeSliceStart() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> DailyTransactionRecordMapper.fromRecord(fixtureBytes, -1))
                    .withMessageContaining("start index must not be negative")
                    .withMessageContaining("from=-1");
        }

        @ParameterizedTest(name = "field {1} is refused when absent")
        @CsvSource({
            "0, DALYTRAN-ID",
            "1, DALYTRAN-TYPE-CD",
            "2, DALYTRAN-CAT-CD",
            "3, DALYTRAN-SOURCE",
            "4, DALYTRAN-DESC",
            "5, DALYTRAN-AMT",
            "6, DALYTRAN-MERCHANT-ID",
            "7, DALYTRAN-MERCHANT-NAME",
            "8, DALYTRAN-MERCHANT-CITY",
            "9, DALYTRAN-MERCHANT-ZIP",
            "10, DALYTRAN-CARD-NUM",
            "11, DALYTRAN-ORIG-TS",
            "12, DALYTRAN-PROC-TS",
        })
        @DisplayName("refuses to emit a record with any mapped field absent")
        void refusesToEmitARecordWithAnyMappedFieldAbsent(final int position,
                final String fieldName) {
            final DailyTransaction transaction = aNominalTransaction();
            switch (position) {
                case 0 -> transaction.setDalytranId(null);
                case 1 -> transaction.setDalytranTypeCd(null);
                case 2 -> transaction.setDalytranCatCd(null);
                case 3 -> transaction.setDalytranSource(null);
                case 4 -> transaction.setDalytranDesc(null);
                case 5 -> transaction.setDalytranAmt(null);
                case 6 -> transaction.setDalytranMerchantId(null);
                case 7 -> transaction.setDalytranMerchantName(null);
                case 8 -> transaction.setDalytranMerchantCity(null);
                case 9 -> transaction.setDalytranMerchantZip(null);
                case 10 -> transaction.setDalytranCardNum(null);
                case 11 -> transaction.setDalytranOrigTs(null);
                default -> transaction.setDalytranProcTs(null);
            }

            assertThatNullPointerException()
                    .isThrownBy(() -> DailyTransactionRecordMapper.toRecord(transaction))
                    .withMessage(DailyTransactionRecordMapper.ARTEFACT + " field '" + fieldName
                            + "' must be present: a fixed-width record has no concept of an absent"
                            + " field, and emitting spaces for one would produce a record of the"
                            + " right width and the wrong content");
        }

        @Test
        @DisplayName("refuses an absent field when emitting the byte form as well")
        void refusesAnAbsentFieldWhenEmittingTheByteFormAsWell() {
            final DailyTransaction transaction = aNominalTransaction();
            transaction.setDalytranCardNum(null);
            assertThatNullPointerException()
                    .isThrownBy(() -> DailyTransactionRecordMapper.toRecordBytes(transaction))
                    .withMessageContaining("field 'DALYTRAN-CARD-NUM' must be present");
        }

        @Test
        @DisplayName("refuses a value wider than the field rather than truncating it")
        void refusesAValueWiderThanTheFieldRatherThanTruncatingIt() {
            final DailyTransaction transaction = aNominalTransaction();
            transaction.setDalytranId("1".repeat(
                    DailyTransactionRecordMapper.DALYTRAN_ID_LENGTH + 1));
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> DailyTransactionRecordMapper.toRecord(transaction))
                    .withMessageContaining("value does not fit")
                    .withMessageContaining("field 'DALYTRAN-ID'")
                    .withMessageContaining("field width is 16 encoded bytes")
                    .withMessageContaining("the value is 17 encoded bytes")
                    .withMessageContaining("never truncated to fit");
        }

        @Test
        @DisplayName("refuses an amount that needs more digits than the field holds")
        void refusesAnAmountThatNeedsMoreDigitsThanTheFieldHolds() {
            final DailyTransaction transaction = aNominalTransaction();
            transaction.setDalytranAmt(new BigDecimal("1000000000.00"));
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> DailyTransactionRecordMapper.toRecord(transaction))
                    .withMessageContaining("zoned decimal field 'DALYTRAN-AMT'")
                    .withMessageContaining("needs 12 digit(s) at scale 2")
                    .withMessageContaining("field holds only 11");
        }

        @Test
        @DisplayName("refuses a character US-ASCII cannot represent, naming the position only")
        void refusesACharacterUsAsciiCannotRepresent() {
            final DailyTransaction transaction = aNominalTransaction();
            transaction.setDalytranMerchantName("caf\u00e9");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> DailyTransactionRecordMapper.toRecord(transaction))
                    .withMessageContaining("field 'DALYTRAN-MERCHANT-NAME'")
                    .withMessageContaining("US-ASCII cannot represent")
                    .withMessageContaining("at index 3")
                    .withMessageContaining("0xE9")
                    .withMessageContaining("must never be transcoded silently");
        }

        @Test
        @DisplayName("refuses a non-digit inside the amount field, naming the offset and the byte")
        void refusesANonDigitInsideTheAmountField() {
            final String image =
                    recordImage("0000000000000003", POINT_OF_SALE_SOURCE, "00 0005047G");
            assertThat(image).hasSize(DailyTransactionRecordMapper.RECORD_LENGTH);
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> DailyTransactionRecordMapper.fromRecord(image))
                    .withMessageContaining("zoned decimal field 'DALYTRAN-AMT'")
                    .withMessageContaining("expected an ASCII digit '0' through '9'")
                    .withMessageContaining("zero-based offset 2")
                    .withMessageContaining("11-byte image");
        }

        @Test
        @DisplayName("refuses an unrecognised final byte in the amount field")
        void refusesAnUnrecognisedFinalByteInTheAmountField() {
            final String image = recordImage("0000000000000004", POINT_OF_SALE_SOURCE,
                    "0".repeat(DailyTransactionRecordMapper.DALYTRAN_AMT_LENGTH - 1) + "*");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> DailyTransactionRecordMapper.fromRecord(image))
                    .withMessageContaining("zoned decimal field 'DALYTRAN-AMT'")
                    .withMessageContaining("overpunched sign character")
                    .withMessageContaining("zero-based offset 10");
        }

        @Test
        @DisplayName("never echoes the offending field content when refusing an over-wide value")
        void neverEchoesTheOffendingFieldContentWhenRefusingAnOverWideValue() {
            final DailyTransaction transaction = aNominalTransaction();
            transaction.setDalytranDesc("SECRET\r\nINJECTED\u0000PAYLOAD".repeat(9));

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> DailyTransactionRecordMapper.toRecord(transaction))
                    .satisfies(thrown -> assertThat(thrown.getMessage())
                            .contains("field 'DALYTRAN-DESC'")
                            .contains("field width is 100 encoded bytes")
                            .doesNotContain("SECRET")
                            .doesNotContain("INJECTED")
                            .doesNotContain("PAYLOAD")
                            .doesNotContain("\r")
                            .doesNotContain("\n")
                            .doesNotContain("\u0000"));
        }

        @Test
        @DisplayName("never echoes the offending record content when refusing a bad amount")
        void neverEchoesTheOffendingRecordContentWhenRefusingABadAmount() {
            final String amountImage =
                    "0".repeat(DailyTransactionRecordMapper.DALYTRAN_AMT_LENGTH - 1) + "*";
            final String image = recordImage("0000000000000005", OPERATOR_SOURCE, amountImage);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> DailyTransactionRecordMapper.fromRecord(image))
                    .satisfies(thrown -> assertThat(thrown.getMessage())
                            .doesNotContain("Abshire-Lowe")
                            .doesNotContain("4859452612877065")
                            .doesNotContain("0000000000000005"));
        }
    }
}
