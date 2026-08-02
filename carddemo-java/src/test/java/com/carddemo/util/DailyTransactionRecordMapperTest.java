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
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.carddemo.domain.DailyTransaction;
import com.carddemo.support.SeededRecordFixture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Direct unit test for {@link DailyTransactionRecordMapper}, the mapper between the 350-byte legacy
 * daily-transaction record of {@code app/cpy/CVTRA06Y.cpy} and {@link DailyTransaction}.
 *
 * <p><strong>This fixture is the estate's only complete overpunch oracle.</strong> Across the three
 * hundred shipped records every one of the twenty zoned-decimal sign characters appears in the amount
 * field's final byte - the ten positive forms and the ten negative forms - so a full-file round trip
 * exercises the entire sign table with no synthetic fixture. The test asserts that all twenty are
 * present and that every one re-encodes to the identical byte, which is a far stronger check than any
 * hand-written amount could give.
 *
 * <p><strong>The processing timestamp is twenty-six spaces on every record, and that must survive.</strong>
 * Nothing has posted these transactions yet, so the field is blank in all three hundred records. It is
 * one of only three fields in the whole seed whose trailing blanks are behaviourally significant: no
 * temporal type can hold twenty-six spaces, so the value must persist and reload as exactly
 * twenty-six spaces - not null, not empty, not trimmed. The test pins that on every record.
 *
 * <p><strong>The origination timestamp is uniform, which bounds what this fixture can prove.</strong>
 * All three hundred records carry the same origination instant, so date-window filtering cannot be
 * exercised from this input - a window either admits every record or none. That is recorded here so
 * no later test mistakes a passing window assertion against this fixture for evidence of filtering.
 *
 * <p><strong>Filler is spaces here, so the round trip is whole-record.</strong> A census over all
 * three hundred records finds 6,000 filler bytes, every one a space - the same character
 * {@code toRecord} emits - so the round-trip assertion is an unbounded 350-byte comparison.
 *
 * <p>Provenance: checkout SHA {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release
 * stamp {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19.
 */
@DisplayName("daily-transaction record mapper")
class DailyTransactionRecordMapperTest {

    /** The shipped fixture measures 105,300 bytes: 300 records at a 351-byte stride. */
    private static final int SEEDED_RECORDS = 300;

    /** Fixture file name, resolved from the test classpath by the shared loader. */
    private static final String FIXTURE = "dailytran.txt";

    /** The ten positive and ten negative zoned-decimal overpunch characters. */
    private static final String OVERPUNCH_CHARACTERS = "{ABCDEFGHI}JKLMNOPQR";

    /** The point-of-sale origin, space-padded to the record's ten-byte field width. */
    private static final String POS_TERMINAL_SOURCE = "POS TERM  ";

    /** The operator origin, space-padded to the record's ten-byte field width. */
    private static final String OPERATOR_SOURCE = "OPERATOR  ";

    /** Measured count of point-of-sale records in the shipped fixture. */
    private static final int SEEDED_POS_TERMINAL_RECORDS = 250;

    /** Measured count of operator-originated records in the shipped fixture. */
    private static final int SEEDED_OPERATOR_RECORDS = 50;

    private static SeededRecordFixture fixture() {
        return SeededRecordFixture.load(FIXTURE, DailyTransactionRecordMapper.RECORD_LENGTH);
    }

    @Nested
    @DisplayName("declared geometry")
    class DeclaredGeometry {

        @Test
        @DisplayName("the thirteen mapped fields and the filler exactly tile the 350-byte record")
        void theMappedFieldsAndFillerTileTheRecord() {
            assertThat(DailyTransactionRecordMapper.DALYTRAN_ID_OFFSET
                    + DailyTransactionRecordMapper.DALYTRAN_ID_LENGTH)
                    .isEqualTo(DailyTransactionRecordMapper.DALYTRAN_TYPE_CD_OFFSET);
            assertThat(DailyTransactionRecordMapper.DALYTRAN_TYPE_CD_OFFSET
                    + DailyTransactionRecordMapper.DALYTRAN_TYPE_CD_LENGTH)
                    .isEqualTo(DailyTransactionRecordMapper.DALYTRAN_CAT_CD_OFFSET);
            assertThat(DailyTransactionRecordMapper.DALYTRAN_CAT_CD_OFFSET
                    + DailyTransactionRecordMapper.DALYTRAN_CAT_CD_LENGTH)
                    .isEqualTo(DailyTransactionRecordMapper.DALYTRAN_SOURCE_OFFSET);
            assertThat(DailyTransactionRecordMapper.DALYTRAN_SOURCE_OFFSET
                    + DailyTransactionRecordMapper.DALYTRAN_SOURCE_LENGTH)
                    .isEqualTo(DailyTransactionRecordMapper.DALYTRAN_DESC_OFFSET);
            assertThat(DailyTransactionRecordMapper.DALYTRAN_DESC_OFFSET
                    + DailyTransactionRecordMapper.DALYTRAN_DESC_LENGTH)
                    .isEqualTo(DailyTransactionRecordMapper.DALYTRAN_AMT_OFFSET);
            assertThat(DailyTransactionRecordMapper.DALYTRAN_AMT_OFFSET
                    + DailyTransactionRecordMapper.DALYTRAN_AMT_LENGTH)
                    .isEqualTo(DailyTransactionRecordMapper.DALYTRAN_MERCHANT_ID_OFFSET);
            assertThat(DailyTransactionRecordMapper.DALYTRAN_MERCHANT_ID_OFFSET
                    + DailyTransactionRecordMapper.DALYTRAN_MERCHANT_ID_LENGTH)
                    .isEqualTo(DailyTransactionRecordMapper.DALYTRAN_MERCHANT_NAME_OFFSET);
            assertThat(DailyTransactionRecordMapper.DALYTRAN_MERCHANT_NAME_OFFSET
                    + DailyTransactionRecordMapper.DALYTRAN_MERCHANT_NAME_LENGTH)
                    .isEqualTo(DailyTransactionRecordMapper.DALYTRAN_MERCHANT_CITY_OFFSET);
            assertThat(DailyTransactionRecordMapper.DALYTRAN_MERCHANT_CITY_OFFSET
                    + DailyTransactionRecordMapper.DALYTRAN_MERCHANT_CITY_LENGTH)
                    .isEqualTo(DailyTransactionRecordMapper.DALYTRAN_MERCHANT_ZIP_OFFSET);
            assertThat(DailyTransactionRecordMapper.DALYTRAN_MERCHANT_ZIP_OFFSET
                    + DailyTransactionRecordMapper.DALYTRAN_MERCHANT_ZIP_LENGTH)
                    .isEqualTo(DailyTransactionRecordMapper.DALYTRAN_CARD_NUM_OFFSET);
            assertThat(DailyTransactionRecordMapper.DALYTRAN_CARD_NUM_OFFSET
                    + DailyTransactionRecordMapper.DALYTRAN_CARD_NUM_LENGTH)
                    .isEqualTo(DailyTransactionRecordMapper.DALYTRAN_ORIG_TS_OFFSET);
            assertThat(DailyTransactionRecordMapper.DALYTRAN_ORIG_TS_OFFSET
                    + DailyTransactionRecordMapper.DALYTRAN_ORIG_TS_LENGTH)
                    .isEqualTo(DailyTransactionRecordMapper.DALYTRAN_PROC_TS_OFFSET);
            assertThat(DailyTransactionRecordMapper.DALYTRAN_PROC_TS_OFFSET
                    + DailyTransactionRecordMapper.DALYTRAN_PROC_TS_LENGTH)
                    .isEqualTo(DailyTransactionRecordMapper.MAPPED_DATA_LENGTH)
                    .isEqualTo(DailyTransactionRecordMapper.FILLER_OFFSET);
            assertThat(DailyTransactionRecordMapper.FILLER_OFFSET
                    + DailyTransactionRecordMapper.FILLER_LENGTH)
                    .isEqualTo(DailyTransactionRecordMapper.RECORD_LENGTH);
        }

        @Test
        @DisplayName("the amount width is the codec's own PIC S9(09)V99 width of eleven, shared with "
                + "the posted-transaction and category-balance layouts")
        void theAmountWidthIsTheCodecWidth() {
            assertThat(DailyTransactionRecordMapper.DALYTRAN_AMT_LENGTH)
                    .isEqualTo(ZonedDecimalCodec.DAILY_TRANSACTION_AMOUNT_WIDTH)
                    .isEqualTo(ZonedDecimalCodec.WIDTH_PIC_S9_09_V99)
                    .isEqualTo(11);
        }

        @Test
        @DisplayName("the two timestamp fields are twenty-six bytes each, at the offsets the external "
                + "sort specifications address")
        void theTimestampFieldsMatchTheSortOffsets() {
            assertThat(DailyTransactionRecordMapper.DALYTRAN_ORIG_TS_OFFSET).isEqualTo(278);
            assertThat(DailyTransactionRecordMapper.DALYTRAN_PROC_TS_OFFSET).isEqualTo(304);
            assertThat(DailyTransactionRecordMapper.DALYTRAN_ORIG_TS_LENGTH).isEqualTo(26);
            assertThat(DailyTransactionRecordMapper.DALYTRAN_PROC_TS_LENGTH).isEqualTo(26);
            assertThat(DailyTransactionRecordMapper.DALYTRAN_CARD_NUM_OFFSET).isEqualTo(262);
        }

        @Test
        @DisplayName("the emitted filler character is a space, matching this fixture's own filler")
        void theEmittedFillerCharacterIsASpace() {
            assertThat(DailyTransactionRecordMapper.FILLER_CHARACTER).isEqualTo(' ');
            assertThat(DailyTransactionRecordMapper.FILLER_LENGTH).isEqualTo(20);
        }
    }

    @Nested
    @DisplayName("parsing the shipped fixture")
    class ParsingTheShippedFixture {

        @Test
        @DisplayName("the fixture holds exactly three hundred records, each measuring the declared 350 bytes")
        void theFixtureHoldsThreeHundredRecordsAtTheDeclaredWidth() {
            final SeededRecordFixture loaded = fixture();

            assertThat(loaded.recordCount()).isEqualTo(SEEDED_RECORDS);
            assertThat(loaded.recordWidth()).isEqualTo(DailyTransactionRecordMapper.RECORD_LENGTH);
            assertThat(loaded.impliedByteCount()).isEqualTo(105_300);
        }

        @Test
        @DisplayName("every character field equals the fixture slice taken at the mapper's own declared "
                + "offset, on every one of the three hundred records")
        void everyCharacterFieldEqualsTheFixtureSliceAtTheDeclaredOffset() {
            final SeededRecordFixture loaded = fixture();

            for (int ordinal = 1; ordinal <= SEEDED_RECORDS; ordinal++) {
                final DailyTransaction parsed =
                        DailyTransactionRecordMapper.fromRecord(loaded.record(ordinal));

                assertThat(parsed.getDalytranId())
                        .as("id of record %d", ordinal)
                        .isEqualTo(loaded.field(ordinal,
                                DailyTransactionRecordMapper.DALYTRAN_ID_OFFSET,
                                DailyTransactionRecordMapper.DALYTRAN_ID_LENGTH));
                assertThat(parsed.getDalytranSource())
                        .as("source of record %d", ordinal)
                        .isEqualTo(loaded.field(ordinal,
                                DailyTransactionRecordMapper.DALYTRAN_SOURCE_OFFSET,
                                DailyTransactionRecordMapper.DALYTRAN_SOURCE_LENGTH));
                assertThat(parsed.getDalytranMerchantName())
                        .as("merchant name of record %d", ordinal)
                        .isEqualTo(loaded.field(ordinal,
                                DailyTransactionRecordMapper.DALYTRAN_MERCHANT_NAME_OFFSET,
                                DailyTransactionRecordMapper.DALYTRAN_MERCHANT_NAME_LENGTH));
                assertThat(parsed.getDalytranCardNum())
                        .as("card number of record %d", ordinal)
                        .isEqualTo(loaded.field(ordinal,
                                DailyTransactionRecordMapper.DALYTRAN_CARD_NUM_OFFSET,
                                DailyTransactionRecordMapper.DALYTRAN_CARD_NUM_LENGTH));
                assertThat(parsed.getDalytranOrigTs())
                        .as("origination timestamp of record %d", ordinal)
                        .isEqualTo(loaded.field(ordinal,
                                DailyTransactionRecordMapper.DALYTRAN_ORIG_TS_OFFSET,
                                DailyTransactionRecordMapper.DALYTRAN_ORIG_TS_LENGTH));
            }
        }

        @Test
        @DisplayName("the processing timestamp reloads as exactly twenty-six spaces on every record - "
                + "not null, not empty, not trimmed")
        void theProcessingTimestampReloadsAsTwentySixSpaces() {
            final SeededRecordFixture loaded = fixture();
            final String blank =
                    " ".repeat(DailyTransactionRecordMapper.DALYTRAN_PROC_TS_LENGTH);

            for (int ordinal = 1; ordinal <= SEEDED_RECORDS; ordinal++) {
                final DailyTransaction parsed =
                        DailyTransactionRecordMapper.fromRecord(loaded.record(ordinal));

                assertThat(parsed.getDalytranProcTs())
                        .as("processing timestamp of record %d", ordinal)
                        .isNotNull()
                        .isNotEmpty()
                        .hasSize(DailyTransactionRecordMapper.DALYTRAN_PROC_TS_LENGTH)
                        .isEqualTo(blank);
            }
        }

        @Test
        @DisplayName("the origination timestamp is identical on all three hundred records, so this "
                + "fixture cannot demonstrate date-window filtering")
        void theOriginationTimestampIsUniform() {
            final SeededRecordFixture loaded = fixture();
            final Set<String> instants = new HashSet<>();

            for (int ordinal = 1; ordinal <= SEEDED_RECORDS; ordinal++) {
                instants.add(DailyTransactionRecordMapper.fromRecord(loaded.record(ordinal))
                        .getDalytranOrigTs());
            }

            assertThat(instants).hasSize(1);
        }

        @Test
        @DisplayName("the source field carries the padded ten-byte image, so a trimmed literal would "
                + "not match a value read from the record")
        void theSourceFieldCarriesThePaddedImage() {
            final SeededRecordFixture loaded = fixture();
            int posTerminal = 0;
            int operator = 0;

            for (int ordinal = 1; ordinal <= SEEDED_RECORDS; ordinal++) {
                final String source = DailyTransactionRecordMapper
                        .fromRecord(loaded.record(ordinal)).getDalytranSource();

                assertThat(source)
                        .as("source width of record %d", ordinal)
                        .hasSize(DailyTransactionRecordMapper.DALYTRAN_SOURCE_LENGTH);
                if (POS_TERMINAL_SOURCE.equals(source)) {
                    posTerminal++;
                } else if (OPERATOR_SOURCE.equals(source)) {
                    operator++;
                }
            }

            assertThat(posTerminal).isEqualTo(SEEDED_POS_TERMINAL_RECORDS);
            assertThat(operator).isEqualTo(SEEDED_OPERATOR_RECORDS);
            assertThat(posTerminal + operator).isEqualTo(SEEDED_RECORDS);
        }

        @Test
        @DisplayName("the byte-array and byte-range entry points agree with the string entry point")
        void theByteEntryPointsAgreeWithTheStringEntryPoint() {
            final String image = fixture().record(1);
            final byte[] encoded = image.getBytes(StandardCharsets.US_ASCII);
            final byte[] framed = new byte[encoded.length + 13];
            System.arraycopy(encoded, 0, framed, 13, encoded.length);

            assertThat(DailyTransactionRecordMapper.toRecord(
                    DailyTransactionRecordMapper.fromRecord(encoded))).isEqualTo(image);
            assertThat(DailyTransactionRecordMapper.toRecord(
                    DailyTransactionRecordMapper.fromRecord(framed, 13))).isEqualTo(image);
        }
    }

    @Nested
    @DisplayName("the zoned-decimal amount and its complete sign table")
    class TheZonedDecimalAmount {

        @Test
        @DisplayName("all twenty overpunch characters occur in the shipped amounts, so the fixture is a "
                + "complete sign oracle needing no synthetic record")
        void allTwentyOverpunchCharactersOccur() {
            final SeededRecordFixture loaded = fixture();
            final Set<Character> signs = new HashSet<>();
            final int finalDigit = DailyTransactionRecordMapper.DALYTRAN_AMT_OFFSET
                    + DailyTransactionRecordMapper.DALYTRAN_AMT_LENGTH - 1;

            for (int ordinal = 1; ordinal <= SEEDED_RECORDS; ordinal++) {
                signs.add(loaded.field(ordinal, finalDigit, 1).charAt(0));
            }

            assertThat(signs).hasSize(OVERPUNCH_CHARACTERS.length());
            for (final char expected : OVERPUNCH_CHARACTERS.toCharArray()) {
                assertThat(signs).as("overpunch character %s", expected).contains(expected);
            }
        }

        @Test
        @DisplayName("every decoded amount carries scale exactly two, and re-encoding reproduces the "
                + "original eleven bytes including the overpunched sign")
        void everyDecodedAmountKeepsScaleAndReEncodesExactly() {
            final SeededRecordFixture loaded = fixture();

            for (int ordinal = 1; ordinal <= SEEDED_RECORDS; ordinal++) {
                final String original = loaded.record(ordinal);
                final DailyTransaction parsed = DailyTransactionRecordMapper.fromRecord(original);

                assertThat(parsed.getDalytranAmt())
                        .as("amount of record %d", ordinal).isNotNull();
                assertThat(parsed.getDalytranAmt().scale())
                        .as("amount scale of record %d", ordinal).isEqualTo(2);
                assertThat(DailyTransactionRecordMapper.toRecord(parsed).substring(
                        DailyTransactionRecordMapper.DALYTRAN_AMT_OFFSET,
                        DailyTransactionRecordMapper.DALYTRAN_MERCHANT_ID_OFFSET))
                        .as("amount bytes of record %d", ordinal)
                        .isEqualTo(original.substring(
                                DailyTransactionRecordMapper.DALYTRAN_AMT_OFFSET,
                                DailyTransactionRecordMapper.DALYTRAN_MERCHANT_ID_OFFSET));
            }
        }

        @Test
        @DisplayName("the first record's image 0000005047G decodes to exactly 504.77")
        void theFirstRecordsAmountDecodesExactly() {
            final SeededRecordFixture loaded = fixture();

            assertThat(loaded.field(1, DailyTransactionRecordMapper.DALYTRAN_AMT_OFFSET,
                    DailyTransactionRecordMapper.DALYTRAN_AMT_LENGTH)).isEqualTo("0000005047G");
            assertThat(DailyTransactionRecordMapper.fromRecord(loaded.record(1)).getDalytranAmt())
                    .isEqualByComparingTo(new BigDecimal("504.77"));
        }

        @Test
        @DisplayName("exactly fifty amounts are negative, and every one of them is an "
                + "operator-originated record, so both signed posting directions are present")
        void theNegativeAmountsAreExactlyTheOperatorRecords() {
            final SeededRecordFixture loaded = fixture();
            int negative = 0;
            int negativeFromOperator = 0;

            for (int ordinal = 1; ordinal <= SEEDED_RECORDS; ordinal++) {
                final DailyTransaction parsed =
                        DailyTransactionRecordMapper.fromRecord(loaded.record(ordinal));
                if (parsed.getDalytranAmt().signum() < 0) {
                    negative++;
                    if (OPERATOR_SOURCE.equals(parsed.getDalytranSource())) {
                        negativeFromOperator++;
                    }
                }
            }

            assertThat(negative).isEqualTo(SEEDED_OPERATOR_RECORDS);
            assertThat(negativeFromOperator).isEqualTo(negative);
        }
    }

    @Nested
    @DisplayName("emitting a record")
    class EmittingARecord {

        @Test
        @DisplayName("every one of the three hundred fixture records round-trips byte-identically "
                + "across the whole 350-byte image, filler included")
        void everyFixtureRecordRoundTripsAcrossTheWholeImage() {
            final SeededRecordFixture loaded = fixture();

            for (int ordinal = 1; ordinal <= SEEDED_RECORDS; ordinal++) {
                final String original = loaded.record(ordinal);

                final String emitted = DailyTransactionRecordMapper.toRecord(
                        DailyTransactionRecordMapper.fromRecord(original));

                assertThat(emitted)
                        .as("whole-record round trip of record %d", ordinal)
                        .isEqualTo(original);
            }
        }

        @Test
        @DisplayName("a blank processing timestamp survives the round trip as twenty-six spaces rather "
                + "than collapsing to an empty field")
        void aBlankProcessingTimestampSurvivesTheRoundTrip() {
            final SeededRecordFixture loaded = fixture();
            final String blank = " ".repeat(DailyTransactionRecordMapper.DALYTRAN_PROC_TS_LENGTH);

            final String emitted = DailyTransactionRecordMapper.toRecord(
                    DailyTransactionRecordMapper.fromRecord(loaded.record(1)));

            assertThat(emitted.substring(DailyTransactionRecordMapper.DALYTRAN_PROC_TS_OFFSET,
                    DailyTransactionRecordMapper.MAPPED_DATA_LENGTH)).isEqualTo(blank);
        }

        @Test
        @DisplayName("the emitted filler is twenty spaces, matching this fixture's own filler")
        void theEmittedFillerIsSpaces() {
            final String original = fixture().record(1);
            final String emitted = DailyTransactionRecordMapper.toRecord(
                    DailyTransactionRecordMapper.fromRecord(original));

            assertThat(emitted.substring(DailyTransactionRecordMapper.FILLER_OFFSET))
                    .isEqualTo(" ".repeat(DailyTransactionRecordMapper.FILLER_LENGTH))
                    .isEqualTo(original.substring(DailyTransactionRecordMapper.FILLER_OFFSET));
        }

        @Test
        @DisplayName("the byte-emitting entry point produces the same 350 bytes as the string one")
        void theByteEmitterAgreesWithTheStringEmitter() {
            final DailyTransaction subject =
                    DailyTransactionRecordMapper.fromRecord(fixture().record(1));

            assertThat(DailyTransactionRecordMapper.toRecordBytes(subject))
                    .isEqualTo(DailyTransactionRecordMapper.toRecord(subject)
                            .getBytes(StandardCharsets.US_ASCII));
        }
    }

    @Nested
    @DisplayName("rejecting malformed input")
    class RejectingMalformedInput {

        @Test
        @DisplayName("a short image is refused rather than silently padded")
        void aShortImageIsRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> DailyTransactionRecordMapper.fromRecord("0000000000683580"))
                    .withMessageContaining(
                            String.valueOf(DailyTransactionRecordMapper.RECORD_LENGTH));
        }

        @Test
        @DisplayName("a long image is refused rather than silently truncated")
        void aLongImageIsRefused() {
            final String overlong = "0".repeat(DailyTransactionRecordMapper.RECORD_LENGTH + 1);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> DailyTransactionRecordMapper.fromRecord(overlong));
        }

        @Test
        @DisplayName("a null image raises deterministically rather than yielding a partial entity")
        void aNullImageRaisesDeterministically() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> DailyTransactionRecordMapper.fromRecord((String) null));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> DailyTransactionRecordMapper.fromRecord((byte[]) null));
        }
    }
}
