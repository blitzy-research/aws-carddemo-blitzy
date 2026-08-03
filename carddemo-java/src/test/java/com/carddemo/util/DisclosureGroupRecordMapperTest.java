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
 * Unit test for {@link DisclosureGroupRecordMapper}, the two-way mapping between the <strong>50-byte
 * disclosure-group record image</strong> and {@link DisclosureGroup}.
 *
 * <p>The layout under test is a <strong>16-byte three-part composite key</strong> - a ten-character
 * account group identifier, a two-character transaction type and a four-character transaction
 * category - followed by the <strong>six-byte</strong> interest rate and a 28-byte filler run. The
 * cluster that provisioned the legacy dataset attests the key geometry independently by declaring a
 * key length of 16 at offset 0, so the identity of a row is its business key and never a surrogate.
 *
 * <h2>Every expectation in this class is hand-derived</h2>
 *
 * <p>No assertion here calls a production method to compute the value it then checks, and nothing is
 * snapshotted from an earlier run. Each offset, width, field image and decoded amount is written out
 * as a literal taken from the verified copybook geometry and the shipped sample data, and the record
 * images are assembled from those literals field by field so that a reviewer can count the bytes.
 * That is the whole point: a test whose expectation is produced by the code under test agrees with
 * that code by construction and can never contradict it. In particular the rate width is written as
 * the digit 6 rather than borrowed from any width constant published elsewhere in the module, because
 * a borrowed constant would make this test pass for a mapper that slices the wrong field.
 *
 * <p>The class is also entirely self-contained: it opens no file, reads no classpath resource, touches
 * no database, queue or network, starts no application context and needs no container. The sample-data
 * rows it exercises are reproduced here as literals, byte for byte, from the census recorded below.
 *
 * <h2>Trap one - the rate is six encoded bytes, and getting it wrong fails silently</h2>
 *
 * <p>The interest rate is four integer digits and two decimal digits, so its image occupies
 * {@code 4 + 2 = 6} bytes. It is the only field of that shape anywhere in the estate and the only
 * exact-numeric column of precision six in the schema; every other zoned-decimal amount in the module
 * is eleven or twelve bytes wide. <strong>An eleven-byte slice taken at the rate's offset does not
 * fail.</strong> It reaches five bytes past the end of the mapped data and into the filler run, and
 * because those filler bytes are ASCII zero in the shipped data the resulting image is all digits and
 * decodes without complaint - to a value one hundred thousand times too large. No exception is raised
 * and no output looks obviously wrong, so only an explicit width assertion catches it. This class
 * therefore pins the width to 6 and pins it apart from both 11 and 12.
 *
 * <h2>Trap two - the ten-character group identifier must never be trimmed</h2>
 *
 * <p>Two of the three seeded group identifiers carry trailing spaces, and that padding is part of the
 * key rather than incidental whitespace. When the interest program fails to find a disclosure group it
 * re-probes with a seven-character default literal moved into the ten-byte identifier field, which
 * left-justifies and space-fills it, so the probe key is the padded ten-character form. Trimming the
 * stored identifier anywhere would make that probe miss and would <strong>silently disable the entire
 * default-group fallback</strong>: no rate would be found for accounts that should have fallen back,
 * and nothing would fail loudly. Every group identifier is therefore asserted at exactly ten encoded
 * bytes and asserted unequal to its trimmed form.
 *
 * <h2>The filler bytes diverge from the sample data by design</h2>
 *
 * <p>Filler declared without an initialising clause is uninitialised, so no byte value is canonical.
 * The reference-table sample files use ASCII zero, while this module emits space uniformly across every
 * layout. <strong>A whole-record 50-byte comparison against a sample row therefore fails on the filler
 * alone</strong>, which is neither a mapping defect nor a defect in the sample data. Round-trip
 * assertions here are bounded to the mapped data prefix, byte 0 up to but excluding byte 22, and one
 * test exists purely to record the divergence so a later reader does not "fix" either side.
 *
 * <h2>Decimal semantics: truncation, not rounding</h2>
 *
 * <p>No arithmetic statement anywhere in the legacy estate specifies rounding, and a legacy store
 * without it truncates toward zero rather than rounding half-up or half-even. The module honours that
 * by applying one truncating policy in the zoned-decimal codec, which is the only component allowed to
 * choose a scale. This class consequently never rescales anything: it names no rounding mode, calls no
 * rescaling method, and builds every expected amount from a decimal string literal rather than from a
 * binary floating-point value, so that no expectation here can be off by a cent for a reason of its
 * own. The sign convention it relies on is the overpunched final digit byte, which is why the negative
 * case is written as a six-byte image with a negative-range terminator rather than as a minus sign.
 *
 * <h2>What this class deliberately does not test</h2>
 *
 * <p>The rate feeds an interest computation that multiplies a category balance by it and only then
 * divides, and the padded default identifier drives the fallback lookup described above. Neither the
 * arithmetic, its operand order, nor the lookup is implemented, replicated or asserted here: this is a
 * mapper test, and both belong to the accrual service. They are named only to explain why the six-byte
 * width and the untrimmed key are load-bearing rather than cosmetic.
 *
 * <h2>Faithful over idiomatic, and the divergences this class pins</h2>
 *
 * <p>Where legacy semantics and idiomatic Java disagree, the legacy semantics win and the divergence is
 * recorded in {@code docs/decision-log.md} rather than settled by taste. This class does not edit that
 * log; it holds the five divergences of this layout to their recorded outcomes so that none of them can
 * regress unnoticed:
 *
 * <ol>
 * <li>the estate's only six-byte zoned-decimal field, and the schema's only exact-numeric column of
 *     precision six, whose mis-slicing at a sibling's width parses silently instead of failing;</li>
 * <li>a ten-character group identifier whose trailing spaces are load-bearing, because the
 *     default-group fallback probes with the padded form;</li>
 * <li>a rate of {@code 0.00} that is a present, legitimate value and never an absence;</li>
 * <li>ASCII-zero filler in the shipped sample data against uniform space filler on write, which bounds
 *     every comparison to the mapped prefix; and</li>
 * <li>truncating rather than rounding decimal arithmetic, owned by the codec and never overridden
 *     here.</li>
 * </ol>
 *
 * <p>No user-specified rules exist for this engagement - the project's rules document states that none
 * were provided - so this class is held to enterprise-standard best practice instead: a zero-warning
 * compile treated as a build failure, no reflection, no code generation, no logging, a package that
 * mirrors the production package exactly, and the licence header that every artefact in the estate
 * carries.
 *
 * <p>Provenance: checkout SHA {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. Sources are cited, never transcribed.
 *
 * @see DisclosureGroupRecordMapper
 * @see DisclosureGroup
 * @see DisclosureGroupId
 */
@DisplayName("disclosure-group record mapper: the 50-byte layout and its six-byte rate")
class DisclosureGroupRecordMapperTest {

    // ------------------------------------------------------------------------------------------
    // The independent layout oracle. Zero-based offsets and encoded byte lengths, hand-written from
    // the copybook geometry. These are the expectations; the mapper's own constants are the subjects.
    // ------------------------------------------------------------------------------------------

    /** Full record width in encoded bytes: the mapped prefix plus the filler run. */
    private static final int EXPECTED_RECORD_LENGTH = 50;

    /** Group identifier: zero-based offset 0, one-based span 1-10. */
    private static final int EXPECTED_GROUP_ID_OFFSET = 0;

    /** Group identifier width. Ten bytes, and the trailing spaces inside them are significant. */
    private static final int EXPECTED_GROUP_ID_LENGTH = 10;

    /** Transaction type code: zero-based offset 10, one-based span 11-12. */
    private static final int EXPECTED_TYPE_CD_OFFSET = 10;

    /** Transaction type code width. */
    private static final int EXPECTED_TYPE_CD_LENGTH = 2;

    /** Transaction category code: zero-based offset 12, one-based span 13-16. */
    private static final int EXPECTED_CAT_CD_OFFSET = 12;

    /** Transaction category code width. Leading zeros are significant, so it stays textual. */
    private static final int EXPECTED_CAT_CD_LENGTH = 4;

    /** Composite key width: {@code 10 + 2 + 4}, the leading substring of the record image. */
    private static final int EXPECTED_KEY_LENGTH = 16;

    /** Interest rate: zero-based offset 16, one-based span 17-22. */
    private static final int EXPECTED_RATE_OFFSET = 16;

    /**
     * Interest rate width: <strong>six</strong> encoded bytes, four integer digits plus two decimal
     * digits. Written as a literal digit here on purpose - see the class comment on trap one.
     */
    private static final int EXPECTED_RATE_LENGTH = 6;

    /** End of the mapped data, and the exact upper bound for a sample-data comparison: {@code 16 + 6}. */
    private static final int EXPECTED_MAPPED_PREFIX_LENGTH = 22;

    /** Filler run: zero-based offset 22, one-based span 23-50. */
    private static final int EXPECTED_FILLER_OFFSET = 22;

    /** Filler run width: {@code 50 - 22}. */
    private static final int EXPECTED_FILLER_LENGTH = 28;

    // ------------------------------------------------------------------------------------------
    // Cluster corroboration. The provisioning job declares the key as length 16 at offset 0, which
    // is an authority independent of the copybook for the same two numbers.
    // ------------------------------------------------------------------------------------------

    /** Key length the indexed cluster declares. */
    private static final int CLUSTER_DECLARED_KEY_LENGTH = 16;

    /** Key offset the indexed cluster declares: the key is the leading substring of the image. */
    private static final int CLUSTER_DECLARED_KEY_OFFSET = 0;

    /** Record size the indexed cluster declares, minimum and maximum alike. */
    private static final int CLUSTER_DECLARED_RECORD_SIZE = 50;

    // ------------------------------------------------------------------------------------------
    // The two sibling zoned-decimal widths this field is NOT. Spelled out as literals rather than
    // imported from anywhere, so that this test cannot drift into agreement with a wrong slice.
    // ------------------------------------------------------------------------------------------

    /** Width of the transaction, daily-transaction and category-balance amounts. Never the rate's. */
    private static final int SIBLING_ELEVEN_BYTE_AMOUNT_WIDTH = 11;

    /** Width of the five account monetary fields. Never the rate's either. */
    private static final int SIBLING_TWELVE_BYTE_AMOUNT_WIDTH = 12;

    /** Bytes an eleven-byte window would absorb past the end of the rate: {@code 11 - 6}. */
    private static final int ELEVEN_BYTE_WINDOW_SURPLUS = 5;

    // ------------------------------------------------------------------------------------------
    // Sample-data accounting, measured over app/data/ASCII/discgrp.txt. Layout evidence only: these
    // are record and byte counts, never a service level or a performance target.
    // ------------------------------------------------------------------------------------------

    /** Rows in the shipped reference data: three complete groups of seventeen. */
    private static final int SEEDED_ROW_COUNT = 51;

    /** Distinct account group identifiers in the shipped reference data. */
    private static final int SEEDED_GROUP_COUNT = 3;

    /** Rows per group. The product with the group count is the row count exactly. */
    private static final int SEEDED_ROWS_PER_GROUP = 17;

    /** Total size of the shipped reference data in bytes. */
    private static final int SEEDED_FILE_BYTES = 2601;

    /** Row stride in the shipped reference data: the record width plus one line-feed terminator. */
    private static final int SEEDED_ROW_STRIDE = 51;

    /** Filler bytes across all 51 rows, every one of them ASCII zero: {@code 51 * 28}. */
    private static final int SEEDED_FILLER_BYTE_COUNT = 1428;

    // ------------------------------------------------------------------------------------------
    // Field literals. Group-key and field images are permitted layout metadata, not source text.
    // ------------------------------------------------------------------------------------------

    /** The one group identifier that fills its ten bytes with no padding at all. */
    private static final String DIRECT_HIT_GROUP_KEY = "A000000000";

    /** The fallback group identifier: seven characters and three trailing spaces. */
    private static final String FALLBACK_GROUP_KEY = "DEFAULT   ";

    /** The fallback group identifier as it must never be stored or probed. */
    private static final String FALLBACK_GROUP_KEY_TRIMMED = "DEFAULT";

    /** The zero-rate group identifier: seven characters and three trailing spaces. */
    private static final String ZERO_RATE_GROUP_KEY = "ZEROAPR   ";

    /** The zero-rate group identifier as it must never be stored or probed. */
    private static final String ZERO_RATE_GROUP_KEY_TRIMMED = "ZEROAPR";

    /** Trailing spaces carried by each of the two padded group identifiers. */
    private static final int PADDED_GROUP_KEY_TRAILING_SPACES = 3;

    /** The transaction type code every seeded category balance carries. */
    private static final String SEEDED_TYPE_CD = "01";

    /** The highest transaction type code present in the reference data. */
    private static final String HIGHEST_SEEDED_TYPE_CD = "07";

    /** A type code outside the seeded set: the mapper carries it verbatim rather than rejecting it. */
    private static final String OUT_OF_SET_TYPE_CD = "ZZ";

    /** The transaction category code every seeded category balance carries, leading zeros included. */
    private static final String SEEDED_CAT_CD = "0001";

    /** The category code with its leading zeros removed. Never a value this layout stores. */
    private static final String SEEDED_CAT_CD_WITHOUT_LEADING_ZEROS = "1";

    // ------------------------------------------------------------------------------------------
    // Rate images and the values they decode to. The final byte overpunches the sign: '{' carries
    // digit 0 positive, 'A' through 'I' carry 1 through 9 positive, '}' carries 0 negative and 'J'
    // through 'R' carry 1 through 9 negative. A plain trailing digit is positive.
    // ------------------------------------------------------------------------------------------

    /** Fifteen per cent, as the reference data writes it: 15 rows carry this image. */
    private static final String RATE_IMAGE_FIFTEEN = "00150{";

    /** A genuine zero rate, as the reference data writes it: 30 rows carry this image. */
    private static final String RATE_IMAGE_ZERO = "00000{";

    /** Twenty-five per cent, the third and last image in the reference data: 6 rows carry it. */
    private static final String RATE_IMAGE_TWENTY_FIVE = "00250{";

    /**
     * Fifteen per cent written with a plain trailing digit instead of an overpunched sign. Legal,
     * positive, and absent from the reference data; it exists here because it is the shape in which an
     * over-wide slice parses silently rather than being rejected on a non-digit byte.
     */
    private static final String RATE_IMAGE_FIFTEEN_PLAIN_SIGN = "001500";

    /**
     * A negative rate. The reference data contains none - all 51 sign bytes are positive - so the
     * sign path has to be constructed. The trailing {@code M} carries digit 4 with a negative sign.
     */
    private static final String RATE_IMAGE_NEGATIVE = "00123M";

    /**
     * The widest magnitude six bytes at this geometry can carry. The trailing {@code I} carries digit
     * 9 positive, so the six digits are all nines: four integer digits and two decimal digits.
     */
    private static final String RATE_IMAGE_WIDEST = "99999I";

    /** Decoded fifteen per cent, at the scale the layout's two decimal digits fix. */
    private static final BigDecimal RATE_FIFTEEN = new BigDecimal("15.00");

    /** Decoded zero. A present, legitimate value - never an absence, never null. */
    private static final BigDecimal RATE_ZERO = new BigDecimal("0.00");

    /** Decoded twenty-five per cent, the third image the reference data carries. */
    private static final BigDecimal RATE_TWENTY_FIVE = new BigDecimal("25.00");

    /** Decoded negative rate: four integer digits, two decimal digits, negative sign. */
    private static final BigDecimal RATE_NEGATIVE = new BigDecimal("-12.34");

    /** Decoded widest magnitude: precision six, scale two. */
    private static final BigDecimal RATE_WIDEST = new BigDecimal("9999.99");

    /** Scale every decoded rate carries, because the layout declares two decimal digits. */
    private static final int RATE_SCALE = 2;

    /** Significant digits the widest value carries: four integer digits plus two decimal digits. */
    private static final int RATE_FULL_PRECISION = 6;

    /** The factor by which an eleven-byte window over a five-zero filler run inflates the value. */
    private static final BigDecimal ELEVEN_BYTE_WINDOW_INFLATION = new BigDecimal("100000");

    /** What fifteen per cent becomes when five filler zeros are read into it. */
    private static final BigDecimal RATE_FIFTEEN_INFLATED = new BigDecimal("1500000.00");

    /** The eleven digits an eleven-byte window would lift out of a plain-signed row. */
    private static final String ELEVEN_BYTE_WINDOW_IMAGE = "00150000000";

    // ------------------------------------------------------------------------------------------
    // Filler bytes. The reference data and this module deliberately disagree here.
    // ------------------------------------------------------------------------------------------

    /** The byte the reference-table sample files use for filler. */
    private static final char SAMPLE_DATA_FILLER_CHARACTER = '0';

    /** The byte this module emits for filler, uniformly, across every layout. */
    private static final char EMITTED_FILLER_CHARACTER = ' ';

    /** Filler run as the reference data writes it: 28 ASCII zeros. */
    private static final String SAMPLE_DATA_FILLER_RUN =
            String.valueOf(SAMPLE_DATA_FILLER_CHARACTER).repeat(EXPECTED_FILLER_LENGTH);

    /** Filler run as this module emits it: 28 spaces. */
    private static final String EMITTED_FILLER_RUN =
            String.valueOf(EMITTED_FILLER_CHARACTER).repeat(EXPECTED_FILLER_LENGTH);

    // ------------------------------------------------------------------------------------------
    // Whole record images, assembled field by field from the literals above so that every byte of
    // every image is auditable against the layout table without running anything.
    // ------------------------------------------------------------------------------------------

    /** Row 0 of the reference data: the direct-hit group at fifteen per cent. */
    private static final String SAMPLE_ROW_0 =
            sampleRow(DIRECT_HIT_GROUP_KEY, SEEDED_TYPE_CD, SEEDED_CAT_CD, RATE_IMAGE_FIFTEEN);

    /** Row 17: the first row of the fallback group, also at fifteen per cent. */
    private static final String SAMPLE_ROW_17 =
            sampleRow(FALLBACK_GROUP_KEY, SEEDED_TYPE_CD, SEEDED_CAT_CD, RATE_IMAGE_FIFTEEN);

    /** Row 34: the first row of the zero-rate group. */
    private static final String SAMPLE_ROW_34 =
            sampleRow(ZERO_RATE_GROUP_KEY, SEEDED_TYPE_CD, SEEDED_CAT_CD, RATE_IMAGE_ZERO);

    /** Row 50: the last row of the reference data, on the highest seeded type code. */
    private static final String SAMPLE_ROW_50 =
            sampleRow(ZERO_RATE_GROUP_KEY, HIGHEST_SEEDED_TYPE_CD, SEEDED_CAT_CD, RATE_IMAGE_ZERO);

    /** A row whose rate carries a plain trailing digit rather than an overpunched sign. */
    private static final String PLAIN_SIGN_ROW = sampleRow(
            DIRECT_HIT_GROUP_KEY, SEEDED_TYPE_CD, SEEDED_CAT_CD, RATE_IMAGE_FIFTEEN_PLAIN_SIGN);

    /** A constructed row carrying the negative rate the reference data never contains. */
    private static final String NEGATIVE_RATE_ROW =
            sampleRow(DIRECT_HIT_GROUP_KEY, SEEDED_TYPE_CD, SEEDED_CAT_CD, RATE_IMAGE_NEGATIVE);

    /** A constructed row carrying the widest magnitude the six-byte field can hold. */
    private static final String WIDEST_RATE_ROW =
            sampleRow(DIRECT_HIT_GROUP_KEY, SEEDED_TYPE_CD, SEEDED_CAT_CD, RATE_IMAGE_WIDEST);

    /** A constructed row whose type code lies outside the seeded set. */
    private static final String OUT_OF_SET_TYPE_ROW =
            sampleRow(DIRECT_HIT_GROUP_KEY, OUT_OF_SET_TYPE_CD, SEEDED_CAT_CD, RATE_IMAGE_TWENTY_FIVE);

    // ------------------------------------------------------------------------------------------
    // Helpers. Each one is a plain assembly or slicing utility over hand-written literals; none of
    // them consults the code under test, and each names US-ASCII explicitly at its byte boundary.
    // ------------------------------------------------------------------------------------------

    /**
     * Assembles a record image the way the reference data writes it: four mapped fields followed by
     * an ASCII-zero filler run.
     *
     * @param groupKey  ten-byte account group identifier, trailing spaces included
     * @param typeCd    two-byte transaction type code
     * @param catCd     four-byte transaction category code, leading zeros included
     * @param rateImage six-byte zoned-decimal rate image
     * @return the 50-byte record image as the sample data holds it
     */
    private static String sampleRow(String groupKey, String typeCd, String catCd, String rateImage) {
        return groupKey + typeCd + catCd + rateImage + SAMPLE_DATA_FILLER_RUN;
    }

    /**
     * Assembles a record image the way this module emits one: the same four mapped fields followed by
     * a space filler run.
     *
     * @param groupKey  ten-byte account group identifier, trailing spaces included
     * @param typeCd    two-byte transaction type code
     * @param catCd     four-byte transaction category code, leading zeros included
     * @param rateImage six-byte zoned-decimal rate image
     * @return the 50-byte record image as this module writes it
     */
    private static String emittedRow(String groupKey, String typeCd, String catCd, String rateImage) {
        return groupKey + typeCd + catCd + rateImage + EMITTED_FILLER_RUN;
    }

    /** Returns the mapped data prefix of an image: byte 0 up to but excluding byte 22. */
    private static String mappedPrefix(String groupKey, String typeCd, String catCd, String rateImage) {
        return groupKey + typeCd + catCd + rateImage;
    }

    /** Encodes a value to US-ASCII, which is the only width authority this test recognises. */
    private static byte[] asciiBytes(String value) {
        return value.getBytes(StandardCharsets.US_ASCII);
    }

    /** Returns a value's width in encoded bytes. Character counts are never a width authority. */
    private static int encodedLength(String value) {
        return value.getBytes(StandardCharsets.US_ASCII).length;
    }

    /** Slices an image by zero-based encoded byte offset and encoded byte length. */
    private static String slice(String image, int offset, int length) {
        return new String(asciiBytes(image), offset, length, StandardCharsets.US_ASCII);
    }

    /** Slices an image's encoded bytes by zero-based offset and length. */
    private static byte[] sliceBytes(String image, int offset, int length) {
        return Arrays.copyOfRange(asciiBytes(image), offset, offset + length);
    }

    /**
     * Assembles a newline-terminated buffer of whole rows, the shape a batch reader holds a file in.
     * Row <em>i</em> then starts at {@code i * 51}, the record width plus its one terminator.
     *
     * @param rows rows to lay down in order, each already at its full record width
     * @return the buffer, one stride per row, every row line-feed terminated
     */
    private static byte[] rowBuffer(String... rows) {
        return asciiBytes(String.join("\n", rows) + "\n");
    }

    // ==========================================================================================
    // DECLARED GEOMETRY
    // ==========================================================================================

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
            // Each sum is written out on the expectation side so the arithmetic is visible rather
            // than inferred from a chain of constants.
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
            // The provisioning job's KEYS(16 0) and RECORDSIZE(50 50) are an authority separate from
            // the copybook for the same three numbers.
            assertThat(DisclosureGroupRecordMapper.KEY_LENGTH)
                    .isEqualTo(CLUSTER_DECLARED_KEY_LENGTH);
            assertThat(DisclosureGroupRecordMapper.DIS_ACCT_GROUP_ID_OFFSET)
                    .isEqualTo(CLUSTER_DECLARED_KEY_OFFSET);
            assertThat(DisclosureGroupRecordMapper.RECORD_LENGTH)
                    .isEqualTo(CLUSTER_DECLARED_RECORD_SIZE);

            // A key length of 16 at offset 0 means the key is the leading substring of the image, so
            // the three components read back out of that substring and nothing is generated.
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

    // ==========================================================================================
    // TRAP ONE :: the rate is six encoded bytes, and an over-wide slice fails silently
    // ==========================================================================================

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

            // Four integer digits plus two decimal digits is where the six comes from, and it is why
            // this is the only precision-six field in the module.
            assertThat(EXPECTED_RATE_LENGTH).isEqualTo(RATE_FULL_PRECISION);

            // The two widths it must never borrow, restated so the contrast is on the page.
            assertThat(SIBLING_ELEVEN_BYTE_AMOUNT_WIDTH).isEqualTo(11);
            assertThat(SIBLING_TWELVE_BYTE_AMOUNT_WIDTH).isEqualTo(12);

            // Every sample rate image is exactly six encoded bytes wide.
            assertThat(encodedLength(RATE_IMAGE_FIFTEEN)).isEqualTo(EXPECTED_RATE_LENGTH);
            assertThat(encodedLength(RATE_IMAGE_ZERO)).isEqualTo(EXPECTED_RATE_LENGTH);
            assertThat(encodedLength(RATE_IMAGE_TWENTY_FIVE)).isEqualTo(EXPECTED_RATE_LENGTH);
        }

        @Test
        @DisplayName("an eleven-byte window at the rate offset reaches five bytes past the mapped "
                + "prefix into filler, inflating the value a hundred thousandfold without failing")
        void anElevenByteWindowWouldReachIntoTheFiller() {
            // The correct window, hand-written and then compared with the slice at that offset.
            assertThat(slice(SAMPLE_ROW_0, EXPECTED_RATE_OFFSET, EXPECTED_RATE_LENGTH))
                    .isEqualTo(RATE_IMAGE_FIFTEEN);
            assertThat(DisclosureGroupRecordMapper.fromRecord(SAMPLE_ROW_0).getDisIntRate())
                    .isEqualTo(RATE_FIFTEEN);

            // Where an eleven-byte window would end, and how far past the mapped data that is. No
            // production method is called with the wrong width: the contrast is derived here.
            assertThat(SIBLING_ELEVEN_BYTE_AMOUNT_WIDTH - EXPECTED_RATE_LENGTH)
                    .isEqualTo(ELEVEN_BYTE_WINDOW_SURPLUS);
            assertThat(EXPECTED_RATE_OFFSET + SIBLING_ELEVEN_BYTE_AMOUNT_WIDTH)
                    .isEqualTo(27)
                    .isGreaterThan(EXPECTED_MAPPED_PREFIX_LENGTH);

            // The five surplus bytes are filler, every one of them, so nothing in them is value.
            assertThat(sliceBytes(SAMPLE_ROW_0, EXPECTED_MAPPED_PREFIX_LENGTH,
                    ELEVEN_BYTE_WINDOW_SURPLUS))
                    .hasSize(ELEVEN_BYTE_WINDOW_SURPLUS)
                    .containsOnly((byte) SAMPLE_DATA_FILLER_CHARACTER);

            // On a row whose sign byte is a plain digit, those eleven bytes are all digits, which is
            // exactly why the mistake is silent rather than rejected on a non-digit byte.
            assertThat(slice(PLAIN_SIGN_ROW, EXPECTED_RATE_OFFSET, SIBLING_ELEVEN_BYTE_AMOUNT_WIDTH))
                    .isEqualTo(ELEVEN_BYTE_WINDOW_IMAGE)
                    .containsOnlyDigits();

            // Read at two decimal places those eleven digits are 1500000.00, which is the true rate
            // multiplied by one hundred thousand: a wrong answer no exception would announce.
            assertThat(RATE_FIFTEEN_INFLATED)
                    .isEqualByComparingTo(RATE_FIFTEEN.multiply(ELEVEN_BYTE_WINDOW_INFLATION))
                    .isNotEqualByComparingTo(RATE_FIFTEEN);

            // The six-byte window over that same row still yields the true rate.
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

            // No decoded rate can carry more significant digits than the field has bytes.
            assertThat(widest.precision()).isLessThanOrEqualTo(EXPECTED_RATE_LENGTH);
        }
    }

    // ==========================================================================================
    // TRAP TWO :: the ten-character group identifier is never trimmed
    // ==========================================================================================

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

            // The padding is what makes up the width, and there is exactly three bytes of it.
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

            // The direct-hit identifier needs no padding at all: it fills its ten bytes with data.
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

    // ==========================================================================================
    // DECODING
    // ==========================================================================================

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

            // Hand-written expectations for the same three slices, so the offsets are attested twice.
            assertThat(group.getDisAcctGroupId()).isEqualTo(ZERO_RATE_GROUP_KEY);
            assertThat(group.getDisTranTypeCd()).isEqualTo(HIGHEST_SEEDED_TYPE_CD);
            assertThat(group.getDisTranCatCd()).isEqualTo(SEEDED_CAT_CD);
            assertThat(group.getDisIntRate()).isEqualTo(RATE_ZERO);
        }

        @Test
        @DisplayName("a rate of 0.00 is a present value at scale two, never null, never an absence")
        void aZeroRateIsAPresentValue() {
            // The declared return type is the value itself, not a container, so a non-null assertion
            // here is the whole of the contract: there is no empty case to represent.
            BigDecimal zeroRate = DisclosureGroupRecordMapper.fromRecord(SAMPLE_ROW_34)
                    .getDisIntRate();

            assertThat(zeroRate)
                    .isNotNull()
                    .isEqualTo(RATE_ZERO)
                    .isEqualByComparingTo(RATE_ZERO);
            assertThat(zeroRate.scale()).isEqualTo(RATE_SCALE);
            assertThat(zeroRate.signum()).isZero();

            // A genuine zero is what makes the accrual program's non-zero-rate test meaningful; the
            // skip itself is service logic and is deliberately not exercised here.
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

            // The images themselves, so the mapping from bytes to value is visible on the page.
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

    // ==========================================================================================
    // ENCODING AND THE BOUNDED ROUND TRIP
    // ==========================================================================================

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

            // The same bound applied to the sample row: the mapped data agrees, and only the filler
            // beyond byte 22 is allowed to differ.
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
            // Four deliberately distinguishable values, supplied positionally. If any two arguments
            // were transposed the emitted image would place them at the wrong offsets.
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

            // The identifier is derived from the three components the constructor received; it is
            // never handed to the entity as an object, which is what the composite-key binding
            // requires. A key built by hand from the same three values is the same identity.
            assertThat(group.toId()).isEqualTo(new DisclosureGroupId(
                    FALLBACK_GROUP_KEY, HIGHEST_SEEDED_TYPE_CD, SEEDED_CAT_CD));

            // A decoded entity is populated the same way, component by component.
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

    // ==========================================================================================
    // FILLER DIVERGENCE :: recorded on purpose, never "corrected" on either side
    // ==========================================================================================

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

            // The divergence, stated as an assertion so that neither side can be quietly changed to
            // match the other. Filler declared without an initialising clause has no canonical byte,
            // so this is a source anomaly rather than a defect in either the data or the mapper.
            assertThat(emittedFiller).isNotEqualTo(sampleFiller);
            assertThat(SAMPLE_DATA_FILLER_CHARACTER).isNotEqualTo(EMITTED_FILLER_CHARACTER);
            assertThat(emitted).isNotEqualTo(SAMPLE_ROW_0);

            // And the mapped prefix, which is the only comparison this layout supports, does agree.
            assertThat(sliceBytes(emitted, 0, EXPECTED_MAPPED_PREFIX_LENGTH))
                    .isEqualTo(sliceBytes(SAMPLE_ROW_0, 0, EXPECTED_MAPPED_PREFIX_LENGTH));
        }
    }

    // ==========================================================================================
    // REFERENCE-DATA ACCOUNTING :: layout evidence, never a performance figure
    // ==========================================================================================

    @Nested
    @DisplayName("reference-data accounting")
    class ReferenceDataAccounting {

        @Test
        @DisplayName("the reference data is 51 rows on a 51-byte stride, forming three complete "
                + "groups of seventeen, from which the fallback and zero-rate paths both draw")
        void theReferenceDataIsThreeGroupsOfSeventeen() {
            // 51 rows is odd and reads like an off-by-one; it is not.
            assertThat(SEEDED_GROUP_COUNT * SEEDED_ROWS_PER_GROUP).isEqualTo(SEEDED_ROW_COUNT);

            // Stride is the record width plus one line-feed terminator, and the file size follows.
            assertThat(SEEDED_ROW_STRIDE).isEqualTo(EXPECTED_RECORD_LENGTH + 1);
            assertThat(SEEDED_ROW_COUNT * SEEDED_ROW_STRIDE).isEqualTo(SEEDED_FILE_BYTES);
            assertThat(SEEDED_ROW_COUNT * EXPECTED_FILLER_LENGTH)
                    .isEqualTo(SEEDED_FILLER_BYTE_COUNT);

            // Three distinct group identifiers, each exactly ten encoded bytes.
            assertThat(DIRECT_HIT_GROUP_KEY)
                    .isNotEqualTo(FALLBACK_GROUP_KEY)
                    .isNotEqualTo(ZERO_RATE_GROUP_KEY);
            assertThat(FALLBACK_GROUP_KEY).isNotEqualTo(ZERO_RATE_GROUP_KEY);
            assertThat(encodedLength(DIRECT_HIT_GROUP_KEY)
                    + encodedLength(FALLBACK_GROUP_KEY)
                    + encodedLength(ZERO_RATE_GROUP_KEY))
                    .isEqualTo(SEEDED_GROUP_COUNT * EXPECTED_GROUP_ID_LENGTH);

            // The composition is what makes the accrual program's default-group fallback and its
            // zero-rate skip both reachable from seeded data alone: the padded default identifier is
            // present to be re-probed, and the zero-rate group supplies genuine 0.00 rates. Both
            // branches belong to the accrual service and are not exercised here.
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

    // ==========================================================================================
    // ENTRY-POINT AGREEMENT
    // ==========================================================================================

    @Nested
    @DisplayName("entry-point agreement")
    class EntryPointAgreement {

        @Test
        @DisplayName("the string, byte-array and byte-range entry points decode the same row to the "
                + "same four property values")
        void theThreeEntryPointsDecodeTheSameRowIdentically() {
            // Row 17 laid down as the second row of a newline-terminated buffer, so the range entry
            // point has to skip a whole stride and leave the terminator behind.
            byte[] buffer = rowBuffer(SAMPLE_ROW_0, SAMPLE_ROW_17, SAMPLE_ROW_34);
            assertThat(buffer).hasSize(3 * SEEDED_ROW_STRIDE);

            DisclosureGroup fromString = DisclosureGroupRecordMapper.fromRecord(SAMPLE_ROW_17);
            DisclosureGroup fromBytes =
                    DisclosureGroupRecordMapper.fromRecord(asciiBytes(SAMPLE_ROW_17));
            DisclosureGroup fromRange =
                    DisclosureGroupRecordMapper.fromRecord(buffer, SEEDED_ROW_STRIDE);

            // Entity equality is key-only by design, so the rate is compared explicitly as well.
            assertThat(fromBytes).isEqualTo(fromString);
            assertThat(fromRange).isEqualTo(fromString);

            for (DisclosureGroup decoded : Arrays.asList(fromString, fromBytes, fromRange)) {
                assertThat(decoded.getDisAcctGroupId()).isEqualTo(FALLBACK_GROUP_KEY);
                assertThat(decoded.getDisTranTypeCd()).isEqualTo(SEEDED_TYPE_CD);
                assertThat(decoded.getDisTranCatCd()).isEqualTo(SEEDED_CAT_CD);
                assertThat(decoded.getDisIntRate()).isEqualTo(RATE_FIFTEEN);
                assertThat(decoded.getDisIntRate().scale()).isEqualTo(RATE_SCALE);
            }

            // The last row of the buffer, addressed the same way, is a different row entirely.
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

    // ==========================================================================================
    // REJECTING MALFORMED INPUT
    // ==========================================================================================

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

            // A range that starts one byte in no longer fits inside a single-row buffer.
            byte[] singleRow = asciiBytes(SAMPLE_ROW_0);
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> DisclosureGroupRecordMapper.fromRecord(singleRow, 1))
                    .withMessageContaining("CVTRA02Y");

            // A negative start index is refused before any slicing is attempted.
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
