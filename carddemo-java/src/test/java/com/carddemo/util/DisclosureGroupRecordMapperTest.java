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
import java.util.Arrays;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.carddemo.domain.DisclosureGroup;
import com.carddemo.domain.id.DisclosureGroupId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * Unit test for {@link DisclosureGroupRecordMapper}, the two-way mapping between the 50-byte
 * disclosure-group record image and {@link DisclosureGroup}. The layout is a 16-byte three-part
 * composite key - a ten-character account group identifier, a two-character transaction type and a
 * four-character transaction category - followed by the six-byte interest rate and a 28-byte filler
 * run. The provisioning cluster attests the key geometry independently by declaring a key length of 16
 * at offset 0, so a row's identity is its business key and never a surrogate.
 *
 * <p><strong>Trap one - the rate is six encoded bytes, and getting it wrong fails silently.</strong>
 * Four integer digits plus two decimals is a six-byte image, the only field of that shape in the estate
 * and the only exact-numeric column of precision six in the schema; every other zoned-decimal amount is
 * eleven or twelve bytes wide. <strong>An eleven-byte slice taken at the rate's offset does not
 * fail:</strong> it reaches five bytes into the filler run, and because those bytes are ASCII zero in
 * the shipped data the image is all digits and decodes without complaint, to a value one hundred
 * thousand times too large. Only an explicit width assertion catches it, so the width is pinned to 6
 * and pinned apart from both 11 and 12.
 *
 * <p><strong>Trap two - the ten-character group identifier must never be trimmed.</strong> Two of the
 * three seeded identifiers carry trailing spaces, and that padding is part of the key. When the interest
 * program fails to find a disclosure group it re-probes with a seven-character default literal moved
 * into the ten-byte field, which left-justifies and space-fills it, so the probe key is the padded
 * ten-character form. Trimming anywhere would make that probe miss and would <strong>silently disable
 * the entire default-group fallback</strong> - no rate found for accounts that should have fallen back,
 * and nothing failing loudly. Every identifier is therefore asserted at exactly ten encoded bytes and
 * asserted unequal to its trimmed form.
 *
 * <p><strong>The filler bytes diverge from the sample data by design.</strong> Filler declared without
 * an initialising clause is uninitialised, so no byte value is canonical: the sample files use ASCII
 * zero while this module emits space uniformly. <strong>A whole-record 50-byte comparison against a
 * sample row therefore fails on the filler alone</strong>, which is neither a mapping defect nor a
 * defect in the data. Round-trip assertions are bounded to the mapped prefix, byte 0 up to but excluding
 * byte 22, and one test exists purely to record the divergence so a later reader does not "fix" either
 * side.
 *
 * <p><strong>Truncation, not rounding.</strong> No arithmetic statement in the estate specifies
 * rounding, and a store without it truncates toward zero. The zoned-decimal codec is the only component
 * allowed to choose a scale, so this class never rescales anything, names no rounding mode and builds
 * every expected amount from a decimal string literal. The sign convention is the overpunched final
 * digit byte, which is why the negative case is a six-byte image with a negative-range terminator rather
 * than a minus sign.
 *
 * <p>Deliberately untested here: the rate feeds an interest computation that multiplies a category
 * balance by it and only then divides, and the padded default identifier drives the fallback lookup.
 * Neither the arithmetic, its operand order, nor the lookup is replicated or asserted - this is a mapper
 * test and both belong to the accrual service. They are named only to explain why the six-byte width and
 * the untrimmed key are load-bearing rather than cosmetic.
 *
 * <p>Every expectation is hand-derived: each offset, width, field image and decoded amount is a literal
 * taken from the verified copybook geometry and the shipped sample data, and the record images are
 * assembled from those literals field by field so a reviewer can count the bytes. The rate width in
 * particular is written as the digit 6 rather than borrowed from a published width constant, because a
 * borrowed constant would make this test pass for a mapper that slices the wrong field. The class opens
 * no file, reads no resource and needs no container.
 *
 * @see DisclosureGroupRecordMapper
 * @see DisclosureGroup
 * @see DisclosureGroupId
 */
@DisplayName("disclosure-group record mapper: the 50-byte layout and its six-byte rate")
class DisclosureGroupRecordMapperTest {
    private static final int EXPECTED_RECORD_LENGTH = 50;

    private static final int EXPECTED_GROUP_ID_OFFSET = 0;

    private static final int EXPECTED_GROUP_ID_LENGTH = 10;

    private static final int EXPECTED_TYPE_CD_OFFSET = 10;

    private static final int EXPECTED_TYPE_CD_LENGTH = 2;

    private static final int EXPECTED_CAT_CD_OFFSET = 12;

    private static final int EXPECTED_CAT_CD_LENGTH = 4;

    private static final int EXPECTED_KEY_LENGTH = 16;

    private static final int EXPECTED_RATE_OFFSET = 16;

    /**
     * Interest rate width: <strong>six</strong> encoded bytes. Written as a literal digit rather than
     * borrowed from a published width constant, so that a mapper slicing at a sibling's width fails here
     * instead of decoding silently - see trap one on the class.
     */
    private static final int EXPECTED_RATE_LENGTH = 6;

    private static final int EXPECTED_MAPPED_PREFIX_LENGTH = 22;

    private static final int EXPECTED_FILLER_OFFSET = 22;

    private static final int EXPECTED_FILLER_LENGTH = 28;

    // Cluster corroboration: the provisioning job declares the key as length 16 at offset 0 over a
    // 50-byte record, an authority independent of the copybook for the same three numbers.
    private static final int CLUSTER_DECLARED_KEY_LENGTH = 16;

    private static final int CLUSTER_DECLARED_KEY_OFFSET = 0;

    private static final int CLUSTER_DECLARED_RECORD_SIZE = 50;

    // The two widths every other zoned-decimal amount in the module takes, held here purely so the rate
    // can be asserted unequal to both. The surplus below is how far an eleven-byte slice at the rate's
    // offset reaches past the mapped data and into the filler run.
    private static final int SIBLING_ELEVEN_BYTE_AMOUNT_WIDTH = 11;

    private static final int SIBLING_TWELVE_BYTE_AMOUNT_WIDTH = 12;

    private static final int ELEVEN_BYTE_WINDOW_SURPLUS = 5;

    private static final int SEEDED_ROW_COUNT = 51;

    private static final int SEEDED_GROUP_COUNT = 3;

    private static final int SEEDED_ROWS_PER_GROUP = 17;

    private static final int SEEDED_FILE_BYTES = 2601;

    private static final int SEEDED_ROW_STRIDE = 51;

    private static final int SEEDED_FILLER_BYTE_COUNT = 1428;

    private static final String DIRECT_HIT_GROUP_KEY = "A000000000";

    // The padded form is the probe key the interest program actually builds, which is why the trailing
    // spaces are asserted present and the trimmed form is held apart from it - see trap two.
    private static final String FALLBACK_GROUP_KEY = "DEFAULT   ";

    private static final String FALLBACK_GROUP_KEY_TRIMMED = "DEFAULT";

    private static final String ZERO_RATE_GROUP_KEY = "ZEROAPR   ";

    private static final String ZERO_RATE_GROUP_KEY_TRIMMED = "ZEROAPR";

    private static final int PADDED_GROUP_KEY_TRAILING_SPACES = 3;

    private static final String SEEDED_TYPE_CD = "01";

    private static final String HIGHEST_SEEDED_TYPE_CD = "07";

    private static final String OUT_OF_SET_TYPE_CD = "ZZ";

    private static final String SEEDED_CAT_CD = "0001";

    private static final String SEEDED_CAT_CD_WITHOUT_LEADING_ZEROS = "1";

    private static final String RATE_IMAGE_FIFTEEN = "00150{";

    private static final String RATE_IMAGE_ZERO = "00000{";

    private static final String RATE_IMAGE_TWENTY_FIVE = "00250{";

    private static final String RATE_IMAGE_FIFTEEN_PLAIN_SIGN = "001500";

    private static final String RATE_IMAGE_NEGATIVE = "00123M";

    private static final String RATE_IMAGE_WIDEST = "99999I";

    private static final BigDecimal RATE_FIFTEEN = new BigDecimal("15.00");

    private static final BigDecimal RATE_ZERO = new BigDecimal("0.00");

    private static final BigDecimal RATE_TWENTY_FIVE = new BigDecimal("25.00");

    private static final BigDecimal RATE_NEGATIVE = new BigDecimal("-12.34");

    private static final BigDecimal RATE_WIDEST = new BigDecimal("9999.99");

    private static final int RATE_SCALE = 2;

    private static final int RATE_FULL_PRECISION = 6;

    private static final BigDecimal ELEVEN_BYTE_WINDOW_INFLATION = new BigDecimal("100000");

    private static final BigDecimal RATE_FIFTEEN_INFLATED = new BigDecimal("1500000.00");

    private static final String ELEVEN_BYTE_WINDOW_IMAGE = "00150000000";

    private static final char SAMPLE_DATA_FILLER_CHARACTER = '0';

    private static final char EMITTED_FILLER_CHARACTER = ' ';

    private static final String SAMPLE_DATA_FILLER_RUN =
            String.valueOf(SAMPLE_DATA_FILLER_CHARACTER).repeat(EXPECTED_FILLER_LENGTH);

    private static final String EMITTED_FILLER_RUN =
            String.valueOf(EMITTED_FILLER_CHARACTER).repeat(EXPECTED_FILLER_LENGTH);

    private static final String SAMPLE_ROW_0 =
            sampleRow(DIRECT_HIT_GROUP_KEY, SEEDED_TYPE_CD, SEEDED_CAT_CD, RATE_IMAGE_FIFTEEN);

    private static final String SAMPLE_ROW_17 =
            sampleRow(FALLBACK_GROUP_KEY, SEEDED_TYPE_CD, SEEDED_CAT_CD, RATE_IMAGE_FIFTEEN);

    private static final String SAMPLE_ROW_34 =
            sampleRow(ZERO_RATE_GROUP_KEY, SEEDED_TYPE_CD, SEEDED_CAT_CD, RATE_IMAGE_ZERO);

    private static final String SAMPLE_ROW_50 =
            sampleRow(ZERO_RATE_GROUP_KEY, HIGHEST_SEEDED_TYPE_CD, SEEDED_CAT_CD, RATE_IMAGE_ZERO);

    private static final String PLAIN_SIGN_ROW = sampleRow(
            DIRECT_HIT_GROUP_KEY, SEEDED_TYPE_CD, SEEDED_CAT_CD, RATE_IMAGE_FIFTEEN_PLAIN_SIGN);

    private static final String NEGATIVE_RATE_ROW =
            sampleRow(DIRECT_HIT_GROUP_KEY, SEEDED_TYPE_CD, SEEDED_CAT_CD, RATE_IMAGE_NEGATIVE);

    private static final String WIDEST_RATE_ROW =
            sampleRow(DIRECT_HIT_GROUP_KEY, SEEDED_TYPE_CD, SEEDED_CAT_CD, RATE_IMAGE_WIDEST);

    private static final String OUT_OF_SET_TYPE_ROW =
            sampleRow(DIRECT_HIT_GROUP_KEY, OUT_OF_SET_TYPE_CD, SEEDED_CAT_CD, RATE_IMAGE_TWENTY_FIVE);

    private static String sampleRow(String groupKey, String typeCd, String catCd, String rateImage) {
        return groupKey + typeCd + catCd + rateImage + SAMPLE_DATA_FILLER_RUN;
    }

    private static String emittedRow(String groupKey, String typeCd, String catCd, String rateImage) {
        return groupKey + typeCd + catCd + rateImage + EMITTED_FILLER_RUN;
    }

    private static String mappedPrefix(String groupKey, String typeCd, String catCd, String rateImage) {
        return groupKey + typeCd + catCd + rateImage;
    }

    private static byte[] asciiBytes(String value) {
        return value.getBytes(StandardCharsets.US_ASCII);
    }

    private static int encodedLength(String value) {
        return value.getBytes(StandardCharsets.US_ASCII).length;
    }

    private static String slice(String image, int offset, int length) {
        return new String(asciiBytes(image), offset, length, StandardCharsets.US_ASCII);
    }

    private static byte[] sliceBytes(String image, int offset, int length) {
        return Arrays.copyOfRange(asciiBytes(image), offset, offset + length);
    }

    private static byte[] rowBuffer(String... rows) {
        return asciiBytes(String.join("\n", rows) + "\n");
    }

    @Nested
    @DisplayName("declared geometry")
    class DeclaredGeometry {
        @Test
        @DisplayName("every declared offset is the zero-based byte position the copybook fixes: "
                + "0, 10, 12, 16 and 22")
        void everyDeclaredOffsetMatchesTheCopybook() {
            assertThat(DisclosureGroupRecordMapper.DIS_ACCT_GROUP_ID_OFFSET)
                    .isEqualTo(EXPECTED_GROUP_ID_OFFSET);
            assertThat(DisclosureGroupRecordMapper.DIS_TRAN_TYPE_CD_OFFSET)
                    .isEqualTo(EXPECTED_TYPE_CD_OFFSET);
            assertThat(DisclosureGroupRecordMapper.DIS_TRAN_CAT_CD_OFFSET)
                    .isEqualTo(EXPECTED_CAT_CD_OFFSET);
            assertThat(DisclosureGroupRecordMapper.DIS_INT_RATE_OFFSET)
                    .isEqualTo(EXPECTED_RATE_OFFSET);
            assertThat(DisclosureGroupRecordMapper.FILLER_OFFSET)
                    .isEqualTo(EXPECTED_FILLER_OFFSET);
        }

        @Test
        @DisplayName("every declared length is the encoded byte width the copybook fixes: "
                + "10, 2, 4, 6 and 28")
        void everyDeclaredLengthMatchesTheCopybook() {
            assertThat(DisclosureGroupRecordMapper.DIS_ACCT_GROUP_ID_LENGTH)
                    .isEqualTo(EXPECTED_GROUP_ID_LENGTH);
            assertThat(DisclosureGroupRecordMapper.DIS_TRAN_TYPE_CD_LENGTH)
                    .isEqualTo(EXPECTED_TYPE_CD_LENGTH);
            assertThat(DisclosureGroupRecordMapper.DIS_TRAN_CAT_CD_LENGTH)
                    .isEqualTo(EXPECTED_CAT_CD_LENGTH);
            assertThat(DisclosureGroupRecordMapper.DIS_INT_RATE_LENGTH)
                    .isEqualTo(EXPECTED_RATE_LENGTH);
            assertThat(DisclosureGroupRecordMapper.FILLER_LENGTH)
                    .isEqualTo(EXPECTED_FILLER_LENGTH);
        }

        @Test
        @DisplayName("the widths tile the record exactly: 10 + 2 + 4 = 16 key, 16 + 6 = 22 mapped, "
                + "22 + 28 = 50 total")
        void theWidthsTileTheRecordExactly() {
            assertThat(EXPECTED_GROUP_ID_LENGTH + EXPECTED_TYPE_CD_LENGTH + EXPECTED_CAT_CD_LENGTH)
                    .isEqualTo(EXPECTED_KEY_LENGTH);
            assertThat(EXPECTED_KEY_LENGTH + EXPECTED_RATE_LENGTH)
                    .isEqualTo(EXPECTED_MAPPED_PREFIX_LENGTH);
            assertThat(EXPECTED_MAPPED_PREFIX_LENGTH + EXPECTED_FILLER_LENGTH)
                    .isEqualTo(EXPECTED_RECORD_LENGTH);

            assertThat(DisclosureGroupRecordMapper.KEY_LENGTH).isEqualTo(EXPECTED_KEY_LENGTH);
            assertThat(DisclosureGroupRecordMapper.MAPPED_PREFIX_LENGTH)
                    .isEqualTo(EXPECTED_MAPPED_PREFIX_LENGTH);
            assertThat(DisclosureGroupRecordMapper.RECORD_LENGTH)
                    .isEqualTo(EXPECTED_RECORD_LENGTH);
        }

        @Test
        @DisplayName("the record width is 50 encoded bytes, and every sample row measures exactly that")
        void theRecordWidthIsFiftyEncodedBytes() {
            assertThat(DisclosureGroupRecordMapper.RECORD_LENGTH).isEqualTo(EXPECTED_RECORD_LENGTH);
            assertThat(encodedLength(SAMPLE_ROW_0)).isEqualTo(EXPECTED_RECORD_LENGTH);
            assertThat(encodedLength(SAMPLE_ROW_17)).isEqualTo(EXPECTED_RECORD_LENGTH);
            assertThat(encodedLength(SAMPLE_ROW_34)).isEqualTo(EXPECTED_RECORD_LENGTH);
            assertThat(encodedLength(SAMPLE_ROW_50)).isEqualTo(EXPECTED_RECORD_LENGTH);
        }

        @Test
        @DisplayName("the indexed cluster corroborates the key independently: length 16 at offset 0, "
                + "so the key is the business key and never a surrogate")
        void theClusterCorroboratesTheKeyGeometry() {
            assertThat(DisclosureGroupRecordMapper.KEY_LENGTH)
                    .isEqualTo(CLUSTER_DECLARED_KEY_LENGTH);
            assertThat(DisclosureGroupRecordMapper.DIS_ACCT_GROUP_ID_OFFSET)
                    .isEqualTo(CLUSTER_DECLARED_KEY_OFFSET);
            assertThat(DisclosureGroupRecordMapper.RECORD_LENGTH)
                    .isEqualTo(CLUSTER_DECLARED_RECORD_SIZE);

            DisclosureGroupId key = DisclosureGroupRecordMapper.keyFromRecord(SAMPLE_ROW_0);
            assertThat(key.getDisAcctGroupId() + key.getDisTranTypeCd() + key.getDisTranCatCd())
                    .isEqualTo(slice(SAMPLE_ROW_0, CLUSTER_DECLARED_KEY_OFFSET,
                            CLUSTER_DECLARED_KEY_LENGTH));
            assertThat(encodedLength(
                    key.getDisAcctGroupId() + key.getDisTranTypeCd() + key.getDisTranCatCd()))
                    .isEqualTo(EXPECTED_KEY_LENGTH);
        }

        @Test
        @DisplayName("the artefact name identifies the layout and its copybook in every diagnostic")
        void theArtefactNameIdentifiesTheLayout() {
            assertThat(DisclosureGroupRecordMapper.ARTEFACT)
                    .isNotBlank()
                    .contains("CVTRA02Y");
        }
    }

    @Nested
    @DisplayName("trap one: the six-byte rate")
    class TheSixByteRate {
        @Test
        @DisplayName("the rate is 6 encoded bytes and is neither 11 nor 12, because an over-wide "
                + "slice reads filler digits into the value and parses silently")
        void theRateIsSixBytesAndNeitherElevenNorTwelve() {
            assertThat(DisclosureGroupRecordMapper.DIS_INT_RATE_LENGTH)
                    .isEqualTo(EXPECTED_RATE_LENGTH)
                    .isEqualTo(6)
                    .isNotEqualTo(SIBLING_ELEVEN_BYTE_AMOUNT_WIDTH)
                    .isNotEqualTo(SIBLING_TWELVE_BYTE_AMOUNT_WIDTH);

            assertThat(EXPECTED_RATE_LENGTH).isEqualTo(RATE_FULL_PRECISION);

            assertThat(SIBLING_ELEVEN_BYTE_AMOUNT_WIDTH).isEqualTo(11);
            assertThat(SIBLING_TWELVE_BYTE_AMOUNT_WIDTH).isEqualTo(12);

            assertThat(encodedLength(RATE_IMAGE_FIFTEEN)).isEqualTo(EXPECTED_RATE_LENGTH);
            assertThat(encodedLength(RATE_IMAGE_ZERO)).isEqualTo(EXPECTED_RATE_LENGTH);
            assertThat(encodedLength(RATE_IMAGE_TWENTY_FIVE)).isEqualTo(EXPECTED_RATE_LENGTH);
        }

        @Test
        @DisplayName("an eleven-byte window at the rate offset reaches five bytes past the mapped "
                + "prefix into filler, inflating the value a hundred thousandfold without failing")
        void anElevenByteWindowWouldReachIntoTheFiller() {
            assertThat(slice(SAMPLE_ROW_0, EXPECTED_RATE_OFFSET, EXPECTED_RATE_LENGTH))
                    .isEqualTo(RATE_IMAGE_FIFTEEN);
            assertThat(DisclosureGroupRecordMapper.fromRecord(SAMPLE_ROW_0).getDisIntRate())
                    .isEqualTo(RATE_FIFTEEN);

            assertThat(SIBLING_ELEVEN_BYTE_AMOUNT_WIDTH - EXPECTED_RATE_LENGTH)
                    .isEqualTo(ELEVEN_BYTE_WINDOW_SURPLUS);
            assertThat(EXPECTED_RATE_OFFSET + SIBLING_ELEVEN_BYTE_AMOUNT_WIDTH)
                    .isEqualTo(27)
                    .isGreaterThan(EXPECTED_MAPPED_PREFIX_LENGTH);

            assertThat(sliceBytes(SAMPLE_ROW_0, EXPECTED_MAPPED_PREFIX_LENGTH,
                    ELEVEN_BYTE_WINDOW_SURPLUS))
                    .hasSize(ELEVEN_BYTE_WINDOW_SURPLUS)
                    .containsOnly((byte) SAMPLE_DATA_FILLER_CHARACTER);

            assertThat(slice(PLAIN_SIGN_ROW, EXPECTED_RATE_OFFSET, SIBLING_ELEVEN_BYTE_AMOUNT_WIDTH))
                    .isEqualTo(ELEVEN_BYTE_WINDOW_IMAGE)
                    .containsOnlyDigits();

            assertThat(RATE_FIFTEEN_INFLATED)
                    .isEqualByComparingTo(RATE_FIFTEEN.multiply(ELEVEN_BYTE_WINDOW_INFLATION))
                    .isNotEqualByComparingTo(RATE_FIFTEEN);

            assertThat(DisclosureGroupRecordMapper.fromRecord(PLAIN_SIGN_ROW).getDisIntRate())
                    .isEqualTo(RATE_FIFTEEN);
        }

        @Test
        @DisplayName("the six-byte field carries four integer digits and two decimal digits, so its "
                + "widest magnitude has precision six and scale two")
        void theSixByteFieldCarriesPrecisionSixAtScaleTwo() {
            BigDecimal widest = DisclosureGroupRecordMapper.fromRecord(WIDEST_RATE_ROW)
                    .getDisIntRate();
            assertThat(widest)
                    .isEqualTo(RATE_WIDEST)
                    .isEqualByComparingTo(RATE_WIDEST);
            assertThat(widest.scale()).isEqualTo(RATE_SCALE);
            assertThat(widest.precision()).isEqualTo(RATE_FULL_PRECISION);

            assertThat(widest.precision()).isLessThanOrEqualTo(EXPECTED_RATE_LENGTH);
        }
    }

    @Nested
    @DisplayName("trap two: the untrimmed ten-character group identifier")
    class TheUntrimmedGroupIdentifier {
        @Test
        @DisplayName("all three seeded group identifiers occupy exactly ten encoded bytes, two of "
                + "them by way of three trailing spaces")
        void allThreeGroupIdentifiersOccupyTenEncodedBytes() {
            assertThat(encodedLength(DIRECT_HIT_GROUP_KEY)).isEqualTo(EXPECTED_GROUP_ID_LENGTH);
            assertThat(encodedLength(FALLBACK_GROUP_KEY)).isEqualTo(EXPECTED_GROUP_ID_LENGTH);
            assertThat(encodedLength(ZERO_RATE_GROUP_KEY)).isEqualTo(EXPECTED_GROUP_ID_LENGTH);

            assertThat(EXPECTED_GROUP_ID_LENGTH - encodedLength(FALLBACK_GROUP_KEY_TRIMMED))
                    .isEqualTo(PADDED_GROUP_KEY_TRAILING_SPACES);
            assertThat(EXPECTED_GROUP_ID_LENGTH - encodedLength(ZERO_RATE_GROUP_KEY_TRIMMED))
                    .isEqualTo(PADDED_GROUP_KEY_TRAILING_SPACES);
            assertThat(sliceBytes(FALLBACK_GROUP_KEY,
                    encodedLength(FALLBACK_GROUP_KEY_TRIMMED), PADDED_GROUP_KEY_TRAILING_SPACES))
                    .containsOnly((byte) EMITTED_FILLER_CHARACTER);
            assertThat(sliceBytes(ZERO_RATE_GROUP_KEY,
                    encodedLength(ZERO_RATE_GROUP_KEY_TRIMMED), PADDED_GROUP_KEY_TRAILING_SPACES))
                    .containsOnly((byte) EMITTED_FILLER_CHARACTER);

            assertThat(DIRECT_HIT_GROUP_KEY).doesNotContain(String.valueOf(EMITTED_FILLER_CHARACTER));
        }

        @Test
        @DisplayName("a decoded group identifier keeps its trailing spaces, because the interest "
                + "program re-probes with the padded default literal and a trimmed key would "
                + "silently disable the whole default-group fallback")
        void aDecodedGroupIdentifierKeepsItsTrailingSpaces() {
            String fallbackIdentifier =
                    DisclosureGroupRecordMapper.fromRecord(SAMPLE_ROW_17).getDisAcctGroupId();
            assertThat(fallbackIdentifier)
                    .isEqualTo(FALLBACK_GROUP_KEY)
                    .isNotEqualTo(FALLBACK_GROUP_KEY_TRIMMED);
            assertThat(encodedLength(fallbackIdentifier)).isEqualTo(EXPECTED_GROUP_ID_LENGTH);

            String zeroRateIdentifier =
                    DisclosureGroupRecordMapper.fromRecord(SAMPLE_ROW_34).getDisAcctGroupId();
            assertThat(zeroRateIdentifier)
                    .isEqualTo(ZERO_RATE_GROUP_KEY)
                    .isNotEqualTo(ZERO_RATE_GROUP_KEY_TRIMMED);
            assertThat(encodedLength(zeroRateIdentifier)).isEqualTo(EXPECTED_GROUP_ID_LENGTH);

            String directHitIdentifier =
                    DisclosureGroupRecordMapper.fromRecord(SAMPLE_ROW_0).getDisAcctGroupId();
            assertThat(directHitIdentifier).isEqualTo(DIRECT_HIT_GROUP_KEY);
            assertThat(encodedLength(directHitIdentifier)).isEqualTo(EXPECTED_GROUP_ID_LENGTH);
        }

        @Test
        @DisplayName("the padded identifier round-trips unchanged, so a re-emitted row still matches "
                + "the padded probe key")
        void thePaddedIdentifierRoundTripsUnchanged() {
            DisclosureGroup fallbackGroup = DisclosureGroupRecordMapper.fromRecord(SAMPLE_ROW_17);
            String emitted = DisclosureGroupRecordMapper.toRecord(fallbackGroup);

            assertThat(slice(emitted, EXPECTED_GROUP_ID_OFFSET, EXPECTED_GROUP_ID_LENGTH))
                    .isEqualTo(FALLBACK_GROUP_KEY);
            assertThat(sliceBytes(emitted, EXPECTED_GROUP_ID_OFFSET, EXPECTED_GROUP_ID_LENGTH))
                    .isEqualTo(asciiBytes(FALLBACK_GROUP_KEY));
            assertThat(encodedLength(
                    slice(emitted, EXPECTED_GROUP_ID_OFFSET, EXPECTED_GROUP_ID_LENGTH)))
                    .isEqualTo(EXPECTED_GROUP_ID_LENGTH);
        }

        @Test
        @DisplayName("a padded key is deliberately a different identity from its trimmed form, in "
                + "the projected key as well as in the entity")
        void aPaddedKeyIsADifferentIdentityFromItsTrimmedForm() {
            DisclosureGroupId paddedKey = DisclosureGroupRecordMapper.keyFromRecord(SAMPLE_ROW_17);
            DisclosureGroupId handBuiltPaddedKey =
                    new DisclosureGroupId(FALLBACK_GROUP_KEY, SEEDED_TYPE_CD, SEEDED_CAT_CD);
            DisclosureGroupId handBuiltTrimmedKey =
                    new DisclosureGroupId(FALLBACK_GROUP_KEY_TRIMMED, SEEDED_TYPE_CD, SEEDED_CAT_CD);

            assertThat(paddedKey)
                    .isEqualTo(handBuiltPaddedKey)
                    .isNotEqualTo(handBuiltTrimmedKey);
            assertThat(paddedKey.hashCode()).isEqualTo(handBuiltPaddedKey.hashCode());
            assertThat(paddedKey.getDisAcctGroupId()).isEqualTo(FALLBACK_GROUP_KEY);

            DisclosureGroup paddedGroup = DisclosureGroupRecordMapper.fromRecord(SAMPLE_ROW_17);
            assertThat(paddedGroup)
                    .isEqualTo(new DisclosureGroup(
                            FALLBACK_GROUP_KEY, SEEDED_TYPE_CD, SEEDED_CAT_CD, RATE_FIFTEEN))
                    .isNotEqualTo(new DisclosureGroup(
                            FALLBACK_GROUP_KEY_TRIMMED, SEEDED_TYPE_CD, SEEDED_CAT_CD,
                            RATE_FIFTEEN));
        }
    }

    @Nested
    @DisplayName("decoding a record image")
    class DecodingARecordImage {
        @Test
        @DisplayName("the first sample row decodes to its four properties: A000000000, type 01, "
                + "category 0001 and a rate of 15.00")
        void theFirstSampleRowDecodesToItsFourProperties() {
            DisclosureGroup group = DisclosureGroupRecordMapper.fromRecord(SAMPLE_ROW_0);

            assertThat(group.getDisAcctGroupId()).isEqualTo(DIRECT_HIT_GROUP_KEY);
            assertThat(group.getDisTranTypeCd()).isEqualTo(SEEDED_TYPE_CD);
            assertThat(group.getDisTranCatCd()).isEqualTo(SEEDED_CAT_CD);
            assertThat(group.getDisIntRate())
                    .isEqualTo(RATE_FIFTEEN)
                    .isEqualByComparingTo(RATE_FIFTEEN);
            assertThat(group.getDisIntRate().scale()).isEqualTo(RATE_SCALE);
        }

        @Test
        @DisplayName("each property is sliced at its own declared offset and nowhere else")
        void eachPropertyIsSlicedAtItsDeclaredOffset() {
            DisclosureGroup group = DisclosureGroupRecordMapper.fromRecord(SAMPLE_ROW_50);

            assertThat(group.getDisAcctGroupId()).isEqualTo(
                    slice(SAMPLE_ROW_50, EXPECTED_GROUP_ID_OFFSET, EXPECTED_GROUP_ID_LENGTH));
            assertThat(group.getDisTranTypeCd()).isEqualTo(
                    slice(SAMPLE_ROW_50, EXPECTED_TYPE_CD_OFFSET, EXPECTED_TYPE_CD_LENGTH));
            assertThat(group.getDisTranCatCd()).isEqualTo(
                    slice(SAMPLE_ROW_50, EXPECTED_CAT_CD_OFFSET, EXPECTED_CAT_CD_LENGTH));

            assertThat(group.getDisAcctGroupId()).isEqualTo(ZERO_RATE_GROUP_KEY);
            assertThat(group.getDisTranTypeCd()).isEqualTo(HIGHEST_SEEDED_TYPE_CD);
            assertThat(group.getDisTranCatCd()).isEqualTo(SEEDED_CAT_CD);
            assertThat(group.getDisIntRate()).isEqualTo(RATE_ZERO);
        }

        @Test
        @DisplayName("a rate of 0.00 is a present value at scale two, never null, never an absence")
        void aZeroRateIsAPresentValue() {
            BigDecimal zeroRate = DisclosureGroupRecordMapper.fromRecord(SAMPLE_ROW_34)
                    .getDisIntRate();

            assertThat(zeroRate)
                    .isNotNull()
                    .isEqualTo(RATE_ZERO)
                    .isEqualByComparingTo(RATE_ZERO);
            assertThat(zeroRate.scale()).isEqualTo(RATE_SCALE);
            assertThat(zeroRate.signum()).isZero();

            assertThat(DisclosureGroupRecordMapper.fromRecord(SAMPLE_ROW_50).getDisIntRate())
                    .isNotNull()
                    .isEqualTo(RATE_ZERO);
        }

        @Test
        @DisplayName("both sample rate images decode at scale exactly two: 00150{ to 15.00 and "
                + "00000{ to 0.00")
        void bothSampleRateImagesDecodeAtScaleTwo() {
            BigDecimal fifteen = DisclosureGroupRecordMapper.fromRecord(SAMPLE_ROW_0)
                    .getDisIntRate();
            BigDecimal zero = DisclosureGroupRecordMapper.fromRecord(SAMPLE_ROW_34).getDisIntRate();

            assertThat(fifteen).isEqualTo(RATE_FIFTEEN);
            assertThat(fifteen.scale()).isEqualTo(RATE_SCALE);
            assertThat(zero).isEqualTo(RATE_ZERO);
            assertThat(zero.scale()).isEqualTo(RATE_SCALE);

            assertThat(slice(SAMPLE_ROW_0, EXPECTED_RATE_OFFSET, EXPECTED_RATE_LENGTH))
                    .isEqualTo(RATE_IMAGE_FIFTEEN);
            assertThat(slice(SAMPLE_ROW_34, EXPECTED_RATE_OFFSET, EXPECTED_RATE_LENGTH))
                    .isEqualTo(RATE_IMAGE_ZERO);
        }

        @Test
        @DisplayName("a negative rate decodes through its overpunched final byte, even though the "
                + "reference data carries no negative sign at all")
        void aNegativeRateDecodesThroughItsOverpunchedFinalByte() {
            BigDecimal negative = DisclosureGroupRecordMapper.fromRecord(NEGATIVE_RATE_ROW)
                    .getDisIntRate();

            assertThat(negative)
                    .isEqualTo(RATE_NEGATIVE)
                    .isEqualByComparingTo(RATE_NEGATIVE)
                    .isNegative();
            assertThat(negative.scale()).isEqualTo(RATE_SCALE);
            assertThat(slice(NEGATIVE_RATE_ROW, EXPECTED_RATE_OFFSET, EXPECTED_RATE_LENGTH))
                    .isEqualTo(RATE_IMAGE_NEGATIVE);
        }

        @Test
        @DisplayName("the category code keeps its leading zeros, because an identifier is text and "
                + "not a number")
        void theCategoryCodeKeepsItsLeadingZeros() {
            String categoryCode = DisclosureGroupRecordMapper.fromRecord(SAMPLE_ROW_0)
                    .getDisTranCatCd();

            assertThat(categoryCode)
                    .isEqualTo(SEEDED_CAT_CD)
                    .startsWith("0")
                    .isNotEqualTo(SEEDED_CAT_CD_WITHOUT_LEADING_ZEROS);
            assertThat(encodedLength(categoryCode)).isEqualTo(EXPECTED_CAT_CD_LENGTH);
        }

        @Test
        @DisplayName("the type code is a raw two-byte string with no translation, so a value outside "
                + "the seeded set is carried through rather than rejected")
        void theTypeCodeIsARawTwoByteString() {
            String seededTypeCode = DisclosureGroupRecordMapper.fromRecord(SAMPLE_ROW_50)
                    .getDisTranTypeCd();
            assertThat(seededTypeCode).isEqualTo(HIGHEST_SEEDED_TYPE_CD);
            assertThat(encodedLength(seededTypeCode)).isEqualTo(EXPECTED_TYPE_CD_LENGTH);

            DisclosureGroup outOfSet = DisclosureGroupRecordMapper.fromRecord(OUT_OF_SET_TYPE_ROW);
            assertThat(outOfSet.getDisTranTypeCd()).isEqualTo(OUT_OF_SET_TYPE_CD);
            assertThat(encodedLength(outOfSet.getDisTranTypeCd()))
                    .isEqualTo(EXPECTED_TYPE_CD_LENGTH);
            assertThat(outOfSet.getDisIntRate()).isEqualTo(RATE_TWENTY_FIVE);
        }
    }

    @Nested
    @DisplayName("encoding a record image")
    class EncodingARecordImage {
        @Test
        @DisplayName("the mapped prefix, byte 0 up to but excluding byte 22, round-trips byte for "
                + "byte under US-ASCII")
        void theMappedPrefixRoundTripsByteForByte() {
            DisclosureGroup group = DisclosureGroupRecordMapper.fromRecord(SAMPLE_ROW_0);
            String emitted = DisclosureGroupRecordMapper.toRecord(group);

            byte[] expectedPrefix = asciiBytes(mappedPrefix(
                    DIRECT_HIT_GROUP_KEY, SEEDED_TYPE_CD, SEEDED_CAT_CD, RATE_IMAGE_FIFTEEN));
            assertThat(expectedPrefix).hasSize(EXPECTED_MAPPED_PREFIX_LENGTH);
            assertThat(sliceBytes(emitted, 0, EXPECTED_MAPPED_PREFIX_LENGTH))
                    .isEqualTo(expectedPrefix);

            assertThat(sliceBytes(emitted, 0, EXPECTED_MAPPED_PREFIX_LENGTH))
                    .isEqualTo(sliceBytes(SAMPLE_ROW_0, 0, EXPECTED_MAPPED_PREFIX_LENGTH));
        }

        @Test
        @DisplayName("the emitted image is exactly 50 encoded bytes and its filler run is 28 spaces")
        void theEmittedImageIsFiftyBytesWithATwentyEightSpaceFillerRun() {
            DisclosureGroup group = DisclosureGroupRecordMapper.fromRecord(SAMPLE_ROW_0);
            String emitted = DisclosureGroupRecordMapper.toRecord(group);

            assertThat(encodedLength(emitted)).isEqualTo(EXPECTED_RECORD_LENGTH);
            assertThat(emitted).isEqualTo(emittedRow(
                    DIRECT_HIT_GROUP_KEY, SEEDED_TYPE_CD, SEEDED_CAT_CD, RATE_IMAGE_FIFTEEN));

            byte[] filler = sliceBytes(emitted, EXPECTED_FILLER_OFFSET, EXPECTED_FILLER_LENGTH);
            assertThat(filler)
                    .hasSize(EXPECTED_FILLER_LENGTH)
                    .containsOnly((byte) EMITTED_FILLER_CHARACTER);
            assertThat(DisclosureGroupRecordMapper.FILLER_CHARACTER)
                    .isEqualTo(EMITTED_FILLER_CHARACTER);
        }

        @Test
        @DisplayName("the byte-emitting entry point produces the same 50 bytes, guaranteed by "
                + "construction rather than asserted after the fact")
        void theByteEmittingEntryPointProducesTheSameFiftyBytes() {
            DisclosureGroup group = DisclosureGroupRecordMapper.fromRecord(SAMPLE_ROW_34);
            byte[] emitted = DisclosureGroupRecordMapper.toRecordBytes(group);

            assertThat(emitted)
                    .hasSize(EXPECTED_RECORD_LENGTH)
                    .isEqualTo(asciiBytes(emittedRow(
                            ZERO_RATE_GROUP_KEY, SEEDED_TYPE_CD, SEEDED_CAT_CD, RATE_IMAGE_ZERO)));
            assertThat(Arrays.copyOfRange(emitted, EXPECTED_FILLER_OFFSET, EXPECTED_RECORD_LENGTH))
                    .containsOnly((byte) EMITTED_FILLER_CHARACTER);
        }

        @Test
        @DisplayName("the public four-argument constructor takes the fields in copybook order, and "
                + "no identifier object is ever assigned to the entity")
        void thePublicConstructorTakesTheFieldsInCopybookOrder() {
            DisclosureGroup group = new DisclosureGroup(
                    FALLBACK_GROUP_KEY, HIGHEST_SEEDED_TYPE_CD, SEEDED_CAT_CD, RATE_NEGATIVE);
            String emitted = DisclosureGroupRecordMapper.toRecord(group);

            assertThat(slice(emitted, EXPECTED_GROUP_ID_OFFSET, EXPECTED_GROUP_ID_LENGTH))
                    .isEqualTo(FALLBACK_GROUP_KEY);
            assertThat(slice(emitted, EXPECTED_TYPE_CD_OFFSET, EXPECTED_TYPE_CD_LENGTH))
                    .isEqualTo(HIGHEST_SEEDED_TYPE_CD);
            assertThat(slice(emitted, EXPECTED_CAT_CD_OFFSET, EXPECTED_CAT_CD_LENGTH))
                    .isEqualTo(SEEDED_CAT_CD);
            assertThat(slice(emitted, EXPECTED_RATE_OFFSET, EXPECTED_RATE_LENGTH))
                    .isEqualTo(RATE_IMAGE_NEGATIVE);
            assertThat(encodedLength(emitted)).isEqualTo(EXPECTED_RECORD_LENGTH);

            assertThat(group.toId()).isEqualTo(new DisclosureGroupId(
                    FALLBACK_GROUP_KEY, HIGHEST_SEEDED_TYPE_CD, SEEDED_CAT_CD));

            DisclosureGroup decoded = DisclosureGroupRecordMapper.fromRecord(SAMPLE_ROW_17);
            assertThat(decoded.getDisAcctGroupId()).isEqualTo(FALLBACK_GROUP_KEY);
            assertThat(decoded.getDisTranTypeCd()).isEqualTo(SEEDED_TYPE_CD);
            assertThat(decoded.getDisTranCatCd()).isEqualTo(SEEDED_CAT_CD);
            assertThat(decoded.toId()).isEqualTo(DisclosureGroupRecordMapper
                    .keyFromRecord(SAMPLE_ROW_17));
        }

        @Test
        @DisplayName("a negative rate re-emits its overpunched final byte, so the sign survives the "
                + "round trip")
        void aNegativeRateReEmitsItsOverpunchedFinalByte() {
            DisclosureGroup group = DisclosureGroupRecordMapper.fromRecord(NEGATIVE_RATE_ROW);
            assertThat(group.getDisIntRate()).isEqualTo(RATE_NEGATIVE);

            String emitted = DisclosureGroupRecordMapper.toRecord(group);
            assertThat(slice(emitted, EXPECTED_RATE_OFFSET, EXPECTED_RATE_LENGTH))
                    .isEqualTo(RATE_IMAGE_NEGATIVE);
            assertThat(sliceBytes(emitted, 0, EXPECTED_MAPPED_PREFIX_LENGTH))
                    .isEqualTo(asciiBytes(mappedPrefix(DIRECT_HIT_GROUP_KEY, SEEDED_TYPE_CD,
                            SEEDED_CAT_CD, RATE_IMAGE_NEGATIVE)));
        }

        @Test
        @DisplayName("the widest magnitude the six-byte field holds re-emits its six bytes unchanged")
        void theWidestMagnitudeReEmitsUnchanged() {
            DisclosureGroup group = DisclosureGroupRecordMapper.fromRecord(WIDEST_RATE_ROW);
            assertThat(group.getDisIntRate()).isEqualTo(RATE_WIDEST);

            String emitted = DisclosureGroupRecordMapper.toRecord(group);
            assertThat(slice(emitted, EXPECTED_RATE_OFFSET, EXPECTED_RATE_LENGTH))
                    .isEqualTo(RATE_IMAGE_WIDEST);
            assertThat(encodedLength(slice(emitted, EXPECTED_RATE_OFFSET, EXPECTED_RATE_LENGTH)))
                    .isEqualTo(EXPECTED_RATE_LENGTH);
        }
    }

    @Nested
    @DisplayName("filler bytes")
    class FillerBytes {
        @Test
        @DisplayName("the reference data's filler is ASCII zero while this module emits spaces, so a "
                + "whole-record 50-byte comparison against the sample row fails by design and only "
                + "the mapped prefix may be compared")
        void theReferenceDataFillerIsAsciiZeroWhileThisModuleEmitsSpaces() {
            byte[] sampleFiller =
                    sliceBytes(SAMPLE_ROW_0, EXPECTED_FILLER_OFFSET, EXPECTED_FILLER_LENGTH);
            assertThat(sampleFiller)
                    .hasSize(EXPECTED_FILLER_LENGTH)
                    .containsOnly((byte) SAMPLE_DATA_FILLER_CHARACTER);

            String emitted =
                    DisclosureGroupRecordMapper.toRecord(
                            DisclosureGroupRecordMapper.fromRecord(SAMPLE_ROW_0));
            byte[] emittedFiller =
                    sliceBytes(emitted, EXPECTED_FILLER_OFFSET, EXPECTED_FILLER_LENGTH);
            assertThat(emittedFiller)
                    .hasSize(EXPECTED_FILLER_LENGTH)
                    .containsOnly((byte) EMITTED_FILLER_CHARACTER);

            assertThat(emittedFiller).isNotEqualTo(sampleFiller);
            assertThat(SAMPLE_DATA_FILLER_CHARACTER).isNotEqualTo(EMITTED_FILLER_CHARACTER);
            assertThat(emitted).isNotEqualTo(SAMPLE_ROW_0);

            assertThat(sliceBytes(emitted, 0, EXPECTED_MAPPED_PREFIX_LENGTH))
                    .isEqualTo(sliceBytes(SAMPLE_ROW_0, 0, EXPECTED_MAPPED_PREFIX_LENGTH));
        }
    }

    @Nested
    @DisplayName("reference-data accounting")
    class ReferenceDataAccounting {
        @Test
        @DisplayName("the reference data is 51 rows on a 51-byte stride, forming three complete "
                + "groups of seventeen, from which the fallback and zero-rate paths both draw")
        void theReferenceDataIsThreeGroupsOfSeventeen() {
            assertThat(SEEDED_GROUP_COUNT * SEEDED_ROWS_PER_GROUP).isEqualTo(SEEDED_ROW_COUNT);

            assertThat(SEEDED_ROW_STRIDE).isEqualTo(EXPECTED_RECORD_LENGTH + 1);
            assertThat(SEEDED_ROW_COUNT * SEEDED_ROW_STRIDE).isEqualTo(SEEDED_FILE_BYTES);
            assertThat(SEEDED_ROW_COUNT * EXPECTED_FILLER_LENGTH)
                    .isEqualTo(SEEDED_FILLER_BYTE_COUNT);

            assertThat(DIRECT_HIT_GROUP_KEY)
                    .isNotEqualTo(FALLBACK_GROUP_KEY)
                    .isNotEqualTo(ZERO_RATE_GROUP_KEY);
            assertThat(FALLBACK_GROUP_KEY).isNotEqualTo(ZERO_RATE_GROUP_KEY);
            assertThat(encodedLength(DIRECT_HIT_GROUP_KEY)
                    + encodedLength(FALLBACK_GROUP_KEY)
                    + encodedLength(ZERO_RATE_GROUP_KEY))
                    .isEqualTo(SEEDED_GROUP_COUNT * EXPECTED_GROUP_ID_LENGTH);

            assertThat(DisclosureGroupRecordMapper.fromRecord(SAMPLE_ROW_17).getDisAcctGroupId())
                    .isEqualTo(FALLBACK_GROUP_KEY);
            assertThat(DisclosureGroupRecordMapper.fromRecord(SAMPLE_ROW_34).getDisIntRate())
                    .isEqualTo(RATE_ZERO);
        }

        @Test
        @DisplayName("the three rate images the reference data carries are all six bytes wide and "
                + "decode to 15.00, 25.00 and 0.00")
        void theThreeRateImagesAreAllSixBytesWide() {
            assertThat(encodedLength(RATE_IMAGE_FIFTEEN)).isEqualTo(EXPECTED_RATE_LENGTH);
            assertThat(encodedLength(RATE_IMAGE_TWENTY_FIVE)).isEqualTo(EXPECTED_RATE_LENGTH);
            assertThat(encodedLength(RATE_IMAGE_ZERO)).isEqualTo(EXPECTED_RATE_LENGTH);

            assertThat(DisclosureGroupRecordMapper.fromRecord(sampleRow(DIRECT_HIT_GROUP_KEY,
                    SEEDED_TYPE_CD, SEEDED_CAT_CD, RATE_IMAGE_FIFTEEN)).getDisIntRate())
                    .isEqualTo(RATE_FIFTEEN);
            assertThat(DisclosureGroupRecordMapper.fromRecord(sampleRow(DIRECT_HIT_GROUP_KEY,
                    SEEDED_TYPE_CD, SEEDED_CAT_CD, RATE_IMAGE_TWENTY_FIVE)).getDisIntRate())
                    .isEqualTo(RATE_TWENTY_FIVE);
            assertThat(DisclosureGroupRecordMapper.fromRecord(sampleRow(ZERO_RATE_GROUP_KEY,
                    SEEDED_TYPE_CD, SEEDED_CAT_CD, RATE_IMAGE_ZERO)).getDisIntRate())
                    .isEqualTo(RATE_ZERO);
        }
    }

    @Nested
    @DisplayName("entry-point agreement")
    class EntryPointAgreement {
        @Test
        @DisplayName("the string, byte-array and byte-range entry points decode the same row to the "
                + "same four property values")
        void theThreeEntryPointsDecodeTheSameRowIdentically() {
            byte[] buffer = rowBuffer(SAMPLE_ROW_0, SAMPLE_ROW_17, SAMPLE_ROW_34);
            assertThat(buffer).hasSize(3 * SEEDED_ROW_STRIDE);

            DisclosureGroup fromString = DisclosureGroupRecordMapper.fromRecord(SAMPLE_ROW_17);
            DisclosureGroup fromBytes =
                    DisclosureGroupRecordMapper.fromRecord(asciiBytes(SAMPLE_ROW_17));
            DisclosureGroup fromRange =
                    DisclosureGroupRecordMapper.fromRecord(buffer, SEEDED_ROW_STRIDE);

            assertThat(fromBytes).isEqualTo(fromString);
            assertThat(fromRange).isEqualTo(fromString);

            for (DisclosureGroup decoded : Arrays.asList(fromString, fromBytes, fromRange)) {
                assertThat(decoded.getDisAcctGroupId()).isEqualTo(FALLBACK_GROUP_KEY);
                assertThat(decoded.getDisTranTypeCd()).isEqualTo(SEEDED_TYPE_CD);
                assertThat(decoded.getDisTranCatCd()).isEqualTo(SEEDED_CAT_CD);
                assertThat(decoded.getDisIntRate()).isEqualTo(RATE_FIFTEEN);
                assertThat(decoded.getDisIntRate().scale()).isEqualTo(RATE_SCALE);
            }

            assertThat(DisclosureGroupRecordMapper
                    .fromRecord(buffer, 2 * SEEDED_ROW_STRIDE).getDisAcctGroupId())
                    .isEqualTo(ZERO_RATE_GROUP_KEY);
        }

        @Test
        @DisplayName("the key projection agrees with the entity's own three key components")
        void theKeyProjectionAgreesWithTheEntityComponents() {
            DisclosureGroupId projected = DisclosureGroupRecordMapper.keyFromRecord(SAMPLE_ROW_34);

            assertThat(projected.getDisAcctGroupId()).isEqualTo(ZERO_RATE_GROUP_KEY);
            assertThat(projected.getDisTranTypeCd()).isEqualTo(SEEDED_TYPE_CD);
            assertThat(projected.getDisTranCatCd()).isEqualTo(SEEDED_CAT_CD);
            assertThat(projected).isEqualTo(
                    new DisclosureGroupId(ZERO_RATE_GROUP_KEY, SEEDED_TYPE_CD, SEEDED_CAT_CD));
            assertThat(encodedLength(projected.getDisAcctGroupId()))
                    .isEqualTo(EXPECTED_GROUP_ID_LENGTH);
        }
    }

    @Nested
    @DisplayName("rejecting malformed input")
    class RejectingMalformedInput {
        @Test
        @DisplayName("a 49-byte image is refused rather than silently padded, and the diagnostic "
                + "names the artefact, the expected width of 50 and the actual length of 49")
        void aShortImageIsRefusedRatherThanPadded() {
            String shortImage = slice(SAMPLE_ROW_0, 0, EXPECTED_RECORD_LENGTH - 1);
            assertThat(encodedLength(shortImage)).isEqualTo(49);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> DisclosureGroupRecordMapper.fromRecord(shortImage))
                    .withMessageContaining("CVTRA02Y")
                    .withMessageContaining(DisclosureGroupRecordMapper.ARTEFACT)
                    .withMessageContaining(String.valueOf(EXPECTED_RECORD_LENGTH))
                    .withMessageContaining(String.valueOf(EXPECTED_RECORD_LENGTH - 1))
                    .withMessageContaining("encoded bytes");
        }

        @Test
        @DisplayName("a 51-byte image is refused rather than silently truncated, which is the exact "
                + "mistake of handing over a whole stride with its line-feed terminator attached")
        void aLongImageIsRefusedRatherThanTruncated() {
            String strideImage = SAMPLE_ROW_0 + "\n";
            assertThat(encodedLength(strideImage))
                    .isEqualTo(51)
                    .isEqualTo(SEEDED_ROW_STRIDE);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> DisclosureGroupRecordMapper.fromRecord(strideImage))
                    .withMessageContaining("CVTRA02Y")
                    .withMessageContaining(String.valueOf(EXPECTED_RECORD_LENGTH))
                    .withMessageContaining(String.valueOf(SEEDED_ROW_STRIDE));
        }

        @Test
        @DisplayName("the byte-array and byte-range entry points apply the same width rule")
        void theByteEntryPointsApplyTheSameWidthRule() {
            byte[] shortImage = sliceBytes(SAMPLE_ROW_0, 0, EXPECTED_RECORD_LENGTH - 1);
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> DisclosureGroupRecordMapper.fromRecord(shortImage))
                    .withMessageContaining(String.valueOf(EXPECTED_RECORD_LENGTH));

            byte[] singleRow = asciiBytes(SAMPLE_ROW_0);
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> DisclosureGroupRecordMapper.fromRecord(singleRow, 1))
                    .withMessageContaining("CVTRA02Y");

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> DisclosureGroupRecordMapper.fromRecord(singleRow, -1))
                    .withMessageContaining("CVTRA02Y");
        }

        @Test
        @DisplayName("the key projection applies the same width rule as a full decode")
        void theKeyProjectionAppliesTheSameWidthRule() {
            String shortImage = slice(SAMPLE_ROW_0, 0, EXPECTED_KEY_LENGTH);
            assertThat(encodedLength(shortImage)).isEqualTo(EXPECTED_KEY_LENGTH);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> DisclosureGroupRecordMapper.keyFromRecord(shortImage))
                    .withMessageContaining(String.valueOf(EXPECTED_RECORD_LENGTH))
                    .withMessageContaining(String.valueOf(EXPECTED_KEY_LENGTH));
        }

        @Test
        @DisplayName("null is refused deterministically at every entry point, in both directions")
        void nullIsRefusedAtEveryEntryPoint() {
            assertThatNullPointerException().isThrownBy(
                    () -> DisclosureGroupRecordMapper.fromRecord((String) null));
            assertThatNullPointerException().isThrownBy(
                    () -> DisclosureGroupRecordMapper.fromRecord((byte[]) null));
            assertThatNullPointerException().isThrownBy(
                    () -> DisclosureGroupRecordMapper.fromRecord((byte[]) null, 0));
            assertThatNullPointerException().isThrownBy(
                    () -> DisclosureGroupRecordMapper.keyFromRecord(null));
            assertThatNullPointerException().isThrownBy(
                    () -> DisclosureGroupRecordMapper.toRecord(null));
            assertThatNullPointerException().isThrownBy(
                    () -> DisclosureGroupRecordMapper.toRecordBytes(null));
        }

        @Test
        @DisplayName("an entity missing a mapped property is refused by name rather than emitting a "
                + "partly filled image")
        void anEntityMissingAMappedPropertyIsRefusedByName() {
            DisclosureGroup withoutRate = new DisclosureGroup(
                    DIRECT_HIT_GROUP_KEY, SEEDED_TYPE_CD, SEEDED_CAT_CD, null);
            assertThatNullPointerException()
                    .isThrownBy(() -> DisclosureGroupRecordMapper.toRecord(withoutRate))
                    .withMessageContaining("DIS-INT-RATE");

            DisclosureGroup withoutGroupId = new DisclosureGroup(
                    null, SEEDED_TYPE_CD, SEEDED_CAT_CD, RATE_FIFTEEN);
            assertThatNullPointerException()
                    .isThrownBy(() -> DisclosureGroupRecordMapper.toRecordBytes(withoutGroupId))
                    .withMessageContaining("DIS-ACCT-GROUP-ID");
        }
    }
}
