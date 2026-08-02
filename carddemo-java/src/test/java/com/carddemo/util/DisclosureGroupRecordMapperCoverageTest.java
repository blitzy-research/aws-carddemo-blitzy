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
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.carddemo.domain.DisclosureGroup;
import com.carddemo.domain.id.DisclosureGroupId;
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
 * Byte-parity acceptance for the 50-byte disclosure-group record layout.
 *
 * <h2>What is under test</h2>
 * {@link DisclosureGroupRecordMapper} is the only one of the eleven fixed-width mappers whose numeric
 * field is a rate rather than an amount, and its rate is the estate's single {@code PIC S9(04)V99}
 * declaration. Two properties therefore matter here that matter nowhere else. First, the decoded value
 * must arrive at scale two with the overpunched sign folded out of its final byte, because the
 * interest computation multiplies by it and a scale drift of one digit changes every interest figure
 * the batch tier writes. Second, the record carries a three-part composite key, which the mapper
 * exposes on its own so the batch tier can look a group up without materialising a whole record.
 *
 * <h2>Where the expectations come from</h2>
 * The geometry is stated as literal integers rather than read from the class under test. Every rate
 * expectation is an independently written decimal literal paired with an independently written six-byte
 * image, transcribed from a byte-level reading of the reference fixture
 * {@code [app/data/ASCII/discgrp.txt]}. The fixture's composition - three complete seventeen-row
 * groups, one of which is the default group and one of which is uniformly zero-rated - is asserted
 * directly, because it is that composition which makes both branches of the legacy rate lookup
 * reachable from seed data alone.
 *
 * <h2>The one place the emitted image and the fixture legitimately differ</h2>
 * The fixture holds ASCII zero in all twenty-eight filler bytes; the encoder writes spaces there. That
 * divergence is asserted in both directions rather than normalised away, so that a real defect in the
 * twenty-two bytes that carry information cannot hide behind a lenient comparison.
 *
 * <p>A pure in-process unit test: no application context, no database, no container, no mocking
 * framework, and no reflection.</p>
 *
 * <p>Provenance: part of the migration of the AWS CardDemo z/OS application at checkout SHA
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. No legacy source text is reproduced.</p>
 */
@DisplayName("DisclosureGroupRecordMapper - the 50-byte disclosure-group record")
class DisclosureGroupRecordMapperCoverageTest {

    /** Name of the reference fixture on the test classpath. */
    private static final String FIXTURE_FILE = "discgrp.txt";

    /** Declared record width, stated here rather than read from the class under test. */
    private static final int EXPECTED_RECORD_WIDTH = 50;

    /** Declared placement of the ten-character group identifier. */
    private static final int EXPECTED_GROUP_OFFSET = 0;

    /** Declared width of the ten-character group identifier. */
    private static final int EXPECTED_GROUP_WIDTH = 10;

    /** Declared placement of the two-character transaction type. */
    private static final int EXPECTED_TYPE_OFFSET = 10;

    /** Declared width of the two-character transaction type. */
    private static final int EXPECTED_TYPE_WIDTH = 2;

    /** Declared placement of the four-character category code. */
    private static final int EXPECTED_CATEGORY_OFFSET = 12;

    /** Declared width of the four-character category code. */
    private static final int EXPECTED_CATEGORY_WIDTH = 4;

    /** Declared width of the three-part composite cluster key. */
    private static final int EXPECTED_KEY_WIDTH = 16;

    /** Declared placement of the interest rate. */
    private static final int EXPECTED_RATE_OFFSET = 16;

    /** Declared width of the interest rate, which is {@code PIC S9(04)V99}. */
    private static final int EXPECTED_RATE_WIDTH = 6;

    /** Declared width of the prefix that carries information. */
    private static final int EXPECTED_MAPPED_WIDTH = 22;

    /** Declared placement of the trailing filler run. */
    private static final int EXPECTED_FILLER_OFFSET = 22;

    /** Declared width of the trailing filler run. */
    private static final int EXPECTED_FILLER_WIDTH = 28;

    /** Scale every decoded rate must carry, because the declaration ends in {@code V99}. */
    private static final int EXPECTED_RATE_SCALE = 2;

    /** Number of records the reference fixture holds. */
    private static final int EXPECTED_FIXTURE_RECORDS = 51;

    /** Number of records in each of the fixture's three groups. */
    private static final int EXPECTED_ROWS_PER_GROUP = 17;

    /** The character the reference fixture holds in its filler bytes. */
    private static final char FIXTURE_FILLER_CHARACTER = '0';

    /** The character the encoder writes in its filler bytes. */
    private static final char EMITTED_FILLER_CHARACTER = ' ';

    /** The fixture's first group identifier, carried with no trailing space. */
    private static final String GROUP_A = "A000000000";

    /** The fixture's default group identifier, carried with three contractual trailing spaces. */
    private static final String GROUP_DEFAULT = "DEFAULT   ";

    /** The fixture's uniformly zero-rated group identifier, likewise trailing-space padded. */
    private static final String GROUP_ZERO_APR = "ZEROAPR   ";

    /** The three rate images the fixture uses, transcribed from its bytes. */
    private static final String RATE_IMAGE_FIFTEEN = "00150{";

    /** The twenty-five percent rate image. */
    private static final String RATE_IMAGE_TWENTY_FIVE = "00250{";

    /** The zero rate image. */
    private static final String RATE_IMAGE_ZERO = "00000{";

    /**
     * Supplies one representative record from each of the fixture's three groups, as the one-based
     * ordinal, the group identifier, the type, the category, the six-byte rate image and the decimal
     * the rate must decode to.
     *
     * @return one argument sextuple per representative record
     */
    private static Stream<Arguments> representativeRecords() {
        return Stream.of(
                Arguments.of(1, GROUP_A, "01", "0001", RATE_IMAGE_FIFTEEN, "15.00"),
                Arguments.of(2, GROUP_A, "01", "0002", RATE_IMAGE_TWENTY_FIVE, "25.00"),
                Arguments.of(5, GROUP_A, "02", "0001", RATE_IMAGE_ZERO, "0.00"),
                Arguments.of(17, GROUP_A, "07", "0001", RATE_IMAGE_FIFTEEN, "15.00"),
                Arguments.of(18, GROUP_DEFAULT, "01", "0001", RATE_IMAGE_FIFTEEN, "15.00"),
                Arguments.of(34, GROUP_DEFAULT, "07", "0001", RATE_IMAGE_ZERO, "0.00"),
                Arguments.of(35, GROUP_ZERO_APR, "01", "0001", RATE_IMAGE_ZERO, "0.00"),
                Arguments.of(51, GROUP_ZERO_APR, "07", "0001", RATE_IMAGE_ZERO, "0.00"));
    }

    /**
     * Supplies the seventeen type-and-category tuples every one of the three groups repeats, in the
     * order the fixture carries them.
     *
     * @return one argument pair per tuple
     */
    private static Stream<Arguments> groupTuples() {
        return Stream.of(
                Arguments.of("01", "0001"), Arguments.of("01", "0002"), Arguments.of("01", "0003"),
                Arguments.of("01", "0004"),
                Arguments.of("02", "0001"), Arguments.of("02", "0002"), Arguments.of("02", "0003"),
                Arguments.of("03", "0001"), Arguments.of("03", "0002"), Arguments.of("03", "0003"),
                Arguments.of("04", "0001"), Arguments.of("04", "0002"), Arguments.of("04", "0003"),
                Arguments.of("05", "0001"),
                Arguments.of("06", "0001"), Arguments.of("06", "0002"),
                Arguments.of("07", "0001"));
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
     * Assembles a complete 50-byte record image from its five parts.
     *
     * @param group     the ten-character group identifier, used verbatim
     * @param type      the two-character transaction type, used verbatim
     * @param category  the four-character category code, used verbatim
     * @param rateImage the six-byte zoned rate image, used verbatim
     * @param filler    the character to place in all twenty-eight filler bytes
     * @return a record image of exactly {@link #EXPECTED_RECORD_WIDTH} characters
     */
    private static String image(final String group, final String type, final String category,
            final String rateImage, final char filler) {
        return group + type + category + rateImage + run(filler, EXPECTED_FILLER_WIDTH);
    }

    /**
     * Loads the reference fixture at this layout's declared width.
     *
     * @return the loaded fixture
     */
    private static SeededRecordFixture fixture() {
        return SeededRecordFixture.load(FIXTURE_FILE, EXPECTED_RECORD_WIDTH);
    }

    /**
     * Decodes every fixture record.
     *
     * @return the fifty-one decoded groups, in file order
     */
    private static List<DisclosureGroup> decodedFixture() {
        return fixture().records().stream()
                .map(DisclosureGroupRecordMapper::fromRecord)
                .toList();
    }

    @Nested
    @DisplayName("the declared geometry reproduces the copybook exactly")
    class DeclaredGeometry {

        @Test
        @DisplayName("the record is 50 bytes and the mapped prefix is 22")
        void theWidthsAreTheDeclaredWidths() {
            assertThat(DisclosureGroupRecordMapper.RECORD_LENGTH)
                    .as("the disclosure-group cluster declares a 50-byte record")
                    .isEqualTo(EXPECTED_RECORD_WIDTH);
            assertThat(DisclosureGroupRecordMapper.MAPPED_PREFIX_LENGTH)
                    .as("only the first 22 bytes carry information")
                    .isEqualTo(EXPECTED_MAPPED_WIDTH);
        }

        @Test
        @DisplayName("all four fields sit at their declared offsets and widths")
        void allFourFieldsSitWhereTheCopybookPutsThem() {
            assertThat(DisclosureGroupRecordMapper.DIS_ACCT_GROUP_ID_OFFSET)
                    .isEqualTo(EXPECTED_GROUP_OFFSET);
            assertThat(DisclosureGroupRecordMapper.DIS_ACCT_GROUP_ID_LENGTH)
                    .isEqualTo(EXPECTED_GROUP_WIDTH);
            assertThat(DisclosureGroupRecordMapper.DIS_TRAN_TYPE_CD_OFFSET)
                    .isEqualTo(EXPECTED_TYPE_OFFSET);
            assertThat(DisclosureGroupRecordMapper.DIS_TRAN_TYPE_CD_LENGTH)
                    .isEqualTo(EXPECTED_TYPE_WIDTH);
            assertThat(DisclosureGroupRecordMapper.DIS_TRAN_CAT_CD_OFFSET)
                    .isEqualTo(EXPECTED_CATEGORY_OFFSET);
            assertThat(DisclosureGroupRecordMapper.DIS_TRAN_CAT_CD_LENGTH)
                    .isEqualTo(EXPECTED_CATEGORY_WIDTH);
            assertThat(DisclosureGroupRecordMapper.DIS_INT_RATE_OFFSET)
                    .as("the rate follows the composite key immediately")
                    .isEqualTo(EXPECTED_RATE_OFFSET);
            assertThat(DisclosureGroupRecordMapper.DIS_INT_RATE_LENGTH)
                    .as("PIC S9(04)V99 occupies six bytes, sign included by overpunch")
                    .isEqualTo(EXPECTED_RATE_WIDTH);
        }

        @Test
        @DisplayName("the key is exactly the record's own first sixteen bytes")
        void theKeyIsTheRecordsOwnPrefix() {
            assertThat(DisclosureGroupRecordMapper.KEY_LENGTH)
                    .as("the key is the group identifier, the type and the category, and nothing else")
                    .isEqualTo(EXPECTED_KEY_WIDTH)
                    .isEqualTo(DisclosureGroupRecordMapper.DIS_ACCT_GROUP_ID_LENGTH
                            + DisclosureGroupRecordMapper.DIS_TRAN_TYPE_CD_LENGTH
                            + DisclosureGroupRecordMapper.DIS_TRAN_CAT_CD_LENGTH);
            assertThat(DisclosureGroupRecordMapper.DIS_INT_RATE_OFFSET)
                    .as("the rate must begin where the key ends, or the key image would need offsets")
                    .isEqualTo(DisclosureGroupRecordMapper.KEY_LENGTH);
        }

        @Test
        @DisplayName("the filler occupies the whole remainder, so every byte is accounted for")
        void theFillerAccountsForTheRemainder() {
            assertThat(DisclosureGroupRecordMapper.FILLER_OFFSET)
                    .isEqualTo(EXPECTED_FILLER_OFFSET)
                    .isEqualTo(DisclosureGroupRecordMapper.MAPPED_PREFIX_LENGTH);
            assertThat(DisclosureGroupRecordMapper.FILLER_LENGTH)
                    .isEqualTo(EXPECTED_FILLER_WIDTH);
            assertThat(DisclosureGroupRecordMapper.FILLER_OFFSET
                    + DisclosureGroupRecordMapper.FILLER_LENGTH)
                    .as("the four fields and the filler must tile the record exactly")
                    .isEqualTo(EXPECTED_RECORD_WIDTH);
        }

        @Test
        @DisplayName("the rate width is the codec's own interest-rate width, not a second opinion")
        void theRateWidthAgreesWithTheCodec() {
            assertThat(DisclosureGroupRecordMapper.DIS_INT_RATE_LENGTH)
                    .as("the layout and the codec must not hold two different widths for one field")
                    .isEqualTo(ZonedDecimalCodec.INTEREST_RATE_WIDTH)
                    .isEqualTo(ZonedDecimalCodec.WIDTH_PIC_S9_04_V99);
        }

        @Test
        @DisplayName("the encoder's filler character is a space, which the fixture's is not")
        void theEmittedFillerIsASpace() {
            assertThat(DisclosureGroupRecordMapper.FILLER_CHARACTER)
                    .isEqualTo(EMITTED_FILLER_CHARACTER)
                    .isNotEqualTo(FIXTURE_FILLER_CHARACTER);
        }
    }

    @Nested
    @DisplayName("decoding a record image")
    class Decoding {

        @ParameterizedTest(name = "record {0}: {1} {2}/{3} at {5}")
        @MethodSource("com.carddemo.util.DisclosureGroupRecordMapperCoverageTest#representativeRecords")
        @DisplayName("all four fields arrive verbatim, and the rate arrives at scale two")
        void allFourFieldsArriveVerbatim(final int ordinal, final String group, final String type,
                final String category, final String rateImage, final String rate) {
            DisclosureGroup decoded = DisclosureGroupRecordMapper.fromRecord(
                    image(group, type, category, rateImage, FIXTURE_FILLER_CHARACTER));

            assertThat(decoded.getDisAcctGroupId())
                    .as("the group identifier keeps every one of its contractual trailing spaces")
                    .isEqualTo(group)
                    .hasSize(EXPECTED_GROUP_WIDTH);
            assertThat(decoded.getDisTranTypeCd()).isEqualTo(type);
            assertThat(decoded.getDisTranCatCd())
                    .as("the category keeps its leading zeros")
                    .isEqualTo(category);
            assertThat(decoded.getDisIntRate())
                    .as("record %d must decode to %s exactly", ordinal, rate)
                    .isEqualByComparingTo(rate);
            assertThat(decoded.getDisIntRate().scale())
                    .as("V99 means scale two, and a scale drift changes every interest figure")
                    .isEqualTo(EXPECTED_RATE_SCALE);
        }

        @ParameterizedTest(name = "\"{0}\" decodes to {1}")
        @CsvSource({
            "00150{,15.00",
            "00250{,25.00",
            "00000{,0.00",
            "00150},-15.00",
            "00000},0.00",
            "00150A,15.01",
            "00150J,-15.01",
            "001500,15.00",
            "99999I,9999.99",
            "99999R,-9999.99"})
        @DisplayName("the overpunched sign is folded out of the final byte, in both directions")
        void theOverpunchedSignIsFoldedOut(final String rateImage, final String rate) {
            DisclosureGroup decoded = DisclosureGroupRecordMapper.fromRecord(
                    image(GROUP_A, "01", "0001", rateImage, FIXTURE_FILLER_CHARACTER));

            assertThat(decoded.getDisIntRate())
                    .as("the zoned image %s carries the value %s, sign included", rateImage, rate)
                    .isEqualByComparingTo(rate);
        }

        @Test
        @DisplayName("negative zero decodes to zero, because a rate has no signed zero")
        void negativeZeroDecodesToZero() {
            DisclosureGroup decoded = DisclosureGroupRecordMapper.fromRecord(
                    image(GROUP_ZERO_APR, "01", "0001", "00000}", FIXTURE_FILLER_CHARACTER));

            assertThat(decoded.getDisIntRate())
                    .as("the mapper reads the magnitude, so a negative zero is simply zero")
                    .isEqualByComparingTo("0.00")
                    .isEqualTo(new BigDecimal("0.00"));
        }

        @Test
        @DisplayName("the decoded entity yields the three-part identifier the persistence layer keys on")
        void theDecodedEntityYieldsItsCompositeIdentifier() {
            DisclosureGroup decoded = DisclosureGroupRecordMapper.fromRecord(
                    image(GROUP_DEFAULT, "04", "0003", RATE_IMAGE_FIFTEEN,
                            FIXTURE_FILLER_CHARACTER));

            DisclosureGroupId identifier = decoded.toId();

            assertThat(identifier.getDisAcctGroupId()).isEqualTo(GROUP_DEFAULT);
            assertThat(identifier.getDisTranTypeCd()).isEqualTo("04");
            assertThat(identifier.getDisTranCatCd()).isEqualTo("0003");
        }

        @Test
        @DisplayName("the filler bytes are read by nobody, so two records differing only there decode alike")
        void theFillerIsNotRead() {
            DisclosureGroup zeros = DisclosureGroupRecordMapper.fromRecord(
                    image(GROUP_A, "05", "0001", RATE_IMAGE_FIFTEEN, FIXTURE_FILLER_CHARACTER));
            DisclosureGroup spaces = DisclosureGroupRecordMapper.fromRecord(
                    image(GROUP_A, "05", "0001", RATE_IMAGE_FIFTEEN, EMITTED_FILLER_CHARACTER));

            assertThat(zeros.getDisAcctGroupId()).isEqualTo(spaces.getDisAcctGroupId());
            assertThat(zeros.getDisIntRate())
                    .as("the filler carries no information, so it cannot change a decoded field")
                    .isEqualByComparingTo(spaces.getDisIntRate());
        }

        @Test
        @DisplayName("the byte overload agrees with the text overload byte for byte")
        void theByteOverloadAgreesWithTheTextOverload() {
            String text = image(GROUP_DEFAULT, "01", "0002", RATE_IMAGE_TWENTY_FIVE,
                    FIXTURE_FILLER_CHARACTER);

            DisclosureGroup fromText = DisclosureGroupRecordMapper.fromRecord(text);
            DisclosureGroup fromBytes = DisclosureGroupRecordMapper.fromRecord(
                    text.getBytes(StandardCharsets.US_ASCII));

            assertThat(fromBytes.getDisAcctGroupId()).isEqualTo(fromText.getDisAcctGroupId());
            assertThat(fromBytes.getDisTranTypeCd()).isEqualTo(fromText.getDisTranTypeCd());
            assertThat(fromBytes.getDisTranCatCd()).isEqualTo(fromText.getDisTranCatCd());
            assertThat(fromBytes.getDisIntRate())
                    .as("choosing bytes over text must not change the decoded rate")
                    .isEqualTo(fromText.getDisIntRate());
        }

        @Test
        @DisplayName("a blank rate image is refused rather than read as zero")
        void aBlankRateImageIsRefused() {
            String blankRate = image(GROUP_A, "01", "0001", run(' ', EXPECTED_RATE_WIDTH),
                    FIXTURE_FILLER_CHARACTER);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("silently reading a blank rate as zero would waive interest without saying so")
                    .isThrownBy(() -> DisclosureGroupRecordMapper.fromRecord(blankRate));
        }
    }

    @Nested
    @DisplayName("the sixteen-byte composite key, read without a whole record")
    class KeyDecoding {

        @ParameterizedTest(name = "record {0} keys on {1} {2}/{3}")
        @MethodSource("com.carddemo.util.DisclosureGroupRecordMapperCoverageTest#representativeRecords")
        @DisplayName("the key read directly agrees with the key of the fully decoded record")
        void theDirectKeyAgreesWithTheDecodedKey(final int ordinal, final String group,
                final String type, final String category, final String rateImage,
                final String rate) {
            String record = fixture().record(ordinal);

            DisclosureGroupId direct = DisclosureGroupRecordMapper.keyFromRecord(record);

            assertThat(direct)
                    .as("record %d must key identically whether or not its rate was decoded", ordinal)
                    .isEqualTo(DisclosureGroupRecordMapper.fromRecord(record).toId());
            assertThat(direct.getDisAcctGroupId()).isEqualTo(group);
            assertThat(direct.getDisTranTypeCd()).isEqualTo(type);
            assertThat(direct.getDisTranCatCd()).isEqualTo(category);
        }

        @Test
        @DisplayName("a rate the codec would refuse does not prevent the key from being read")
        void anUnreadableRateDoesNotPreventTheKeyBeingRead() {
            String withBlankRate = image(GROUP_A, "01", "0001", run(' ', EXPECTED_RATE_WIDTH),
                    FIXTURE_FILLER_CHARACTER);

            DisclosureGroupId key = DisclosureGroupRecordMapper.keyFromRecord(withBlankRate);

            assertThat(key.getDisAcctGroupId())
                    .as("reading a key must not depend on a field the key does not contain")
                    .isEqualTo(GROUP_A);
            assertThat(key.getDisTranCatCd()).isEqualTo("0001");
        }

        @Test
        @DisplayName("the fifty-one fixture keys are distinct, so the composite key is a real key")
        void theFixtureKeysAreDistinct() {
            List<DisclosureGroupId> keys = fixture().records().stream()
                    .map(DisclosureGroupRecordMapper::keyFromRecord)
                    .toList();

            assertThat(keys)
                    .as("a duplicate would mean the cluster key does not identify a record")
                    .hasSize(EXPECTED_FIXTURE_RECORDS)
                    .doesNotHaveDuplicates();
        }

        @Test
        @DisplayName("a malformed or null image is refused on the key path too")
        void aMalformedOrNullImageIsRefusedOnTheKeyPath() {
            assertThatNullPointerException()
                    .isThrownBy(() -> DisclosureGroupRecordMapper.keyFromRecord(null));
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> DisclosureGroupRecordMapper.keyFromRecord(run(' ', 49)))
                    .withMessageContaining("must be exactly " + EXPECTED_RECORD_WIDTH);
        }
    }

    @Nested
    @DisplayName("decoding one record out of a larger buffer")
    class BufferDecoding {

        /** Stride of the newline-terminated reference file: the record width plus one terminator. */
        private static final int STRIDE = EXPECTED_RECORD_WIDTH + 1;

        @ParameterizedTest(name = "record {0}")
        @MethodSource("com.carddemo.util.DisclosureGroupRecordMapperCoverageTest#representativeRecords")
        @DisplayName("stride arithmetic selects each record and leaves its terminator behind")
        void strideArithmeticSelectsEachRecord(final int ordinal, final String group,
                final String type, final String category, final String rateImage,
                final String rate) {
            byte[] wholeFile = String.join("\n", fixture().records()).concat("\n")
                    .getBytes(StandardCharsets.US_ASCII);

            DisclosureGroup decoded = DisclosureGroupRecordMapper.fromRecord(
                    wholeFile, (ordinal - 1) * STRIDE);

            assertThat(decoded.getDisAcctGroupId())
                    .as("record %d of the file must be selected, not its neighbour", ordinal)
                    .isEqualTo(group);
            assertThat(decoded.getDisTranTypeCd()).isEqualTo(type);
            assertThat(decoded.getDisTranCatCd()).isEqualTo(category);
            assertThat(decoded.getDisIntRate())
                    .as("the 0x0A terminator is a separator and must never enter a field")
                    .isEqualByComparingTo(rate);
        }

        @Test
        @DisplayName("a negative start index is refused rather than wrapped")
        void aNegativeStartIndexIsRefused() {
            byte[] buffer = new byte[EXPECTED_RECORD_WIDTH];

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> DisclosureGroupRecordMapper.fromRecord(buffer, -1))
                    .withMessageContaining("must not be negative");
        }

        @Test
        @DisplayName("a record that would run past the end of the buffer is refused, not short-read")
        void anOverrunningRecordIsRefused() {
            byte[] buffer = image(GROUP_A, "01", "0001", RATE_IMAGE_FIFTEEN,
                    FIXTURE_FILLER_CHARACTER).getBytes(StandardCharsets.US_ASCII);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> DisclosureGroupRecordMapper.fromRecord(buffer, 1))
                    .withMessageContaining("does not fit inside the supplied buffer");
        }
    }

    @Nested
    @DisplayName("a malformed image is refused rather than repaired")
    class MalformedInput {

        @ParameterizedTest(name = "width {0}")
        @ValueSource(ints = {0, 1, 49, 51, 100})
        @DisplayName("any width other than 50 is refused, in both directions")
        void anyOtherWidthIsRefused(final int width) {
            String wrongWidth = run('0', width);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> DisclosureGroupRecordMapper.fromRecord(wrongWidth))
                    .withMessageContaining("must be exactly " + EXPECTED_RECORD_WIDTH);
        }

        @Test
        @DisplayName("an unstripped line terminator is named as such, because it is the usual cause")
        void anUnstrippedTerminatorIsNamed() {
            String withTerminator = image(GROUP_A, "01", "0001", RATE_IMAGE_FIFTEEN,
                    FIXTURE_FILLER_CHARACTER) + "\n";

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> DisclosureGroupRecordMapper.fromRecord(withTerminator))
                    .withMessageContaining("line ");
        }

        @Test
        @DisplayName("a non-ASCII character is refused rather than transcoded")
        void aNonAsciiCharacterIsRefused() {
            String withAccent = image("GROUPCAF\u00e9", "01", "0001", RATE_IMAGE_FIFTEEN,
                    FIXTURE_FILLER_CHARACTER);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> DisclosureGroupRecordMapper.fromRecord(withAccent))
                    .withMessageContaining("US-ASCII cannot represent");
        }

        @Test
        @DisplayName("a byte above 0x7F is refused, because it means another encoding")
        void aHighByteIsRefused() {
            byte[] record = image(GROUP_A, "01", "0001", RATE_IMAGE_FIFTEEN,
                    FIXTURE_FILLER_CHARACTER).getBytes(StandardCharsets.US_ASCII);
            record[EXPECTED_GROUP_OFFSET] = (byte) 0x80;

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> DisclosureGroupRecordMapper.fromRecord(record))
                    .withMessageContaining("non-ASCII byte");
        }

        @Test
        @DisplayName("a null image is refused on every decode entry point")
        void aNullImageIsRefused() {
            assertThatNullPointerException()
                    .isThrownBy(() -> DisclosureGroupRecordMapper.fromRecord((String) null));
            assertThatNullPointerException()
                    .isThrownBy(() -> DisclosureGroupRecordMapper.fromRecord((byte[]) null));
            assertThatNullPointerException()
                    .isThrownBy(() -> DisclosureGroupRecordMapper.fromRecord(null, 0));
        }
    }

    @Nested
    @DisplayName("encoding an entity")
    class Encoding {

        @Test
        @DisplayName("the image is exactly 50 bytes and carries no terminator")
        void theImageIsExactlyTheDeclaredWidth() {
            String encoded = DisclosureGroupRecordMapper.toRecord(
                    new DisclosureGroup(GROUP_A, "01", "0001", new BigDecimal("15.00")));

            assertThat(encoded).hasSize(EXPECTED_RECORD_WIDTH);
            assertThat(encoded)
                    .as("a terminator is a record separator and never record content")
                    .doesNotContain("\n")
                    .doesNotContain("\r");
        }

        @Test
        @DisplayName("the alphanumeric fields pad right with spaces and the numeric field pads left with zeros")
        void theJustificationRulesDifferByDeclaration() {
            String encoded = DisclosureGroupRecordMapper.toRecord(
                    new DisclosureGroup("DEFAULT", "4", "3", new BigDecimal("15.00")));

            assertThat(encoded.substring(EXPECTED_GROUP_OFFSET,
                    EXPECTED_GROUP_OFFSET + EXPECTED_GROUP_WIDTH))
                    .as("PIC X(10) pads on the right, which is what makes DEFAULT ten bytes wide")
                    .isEqualTo(GROUP_DEFAULT);
            assertThat(encoded.substring(EXPECTED_TYPE_OFFSET,
                    EXPECTED_TYPE_OFFSET + EXPECTED_TYPE_WIDTH))
                    .as("PIC X(02) pads on the right too")
                    .isEqualTo("4 ");
            assertThat(encoded.substring(EXPECTED_CATEGORY_OFFSET,
                    EXPECTED_CATEGORY_OFFSET + EXPECTED_CATEGORY_WIDTH))
                    .as("PIC 9(04) pads on the left, which is what preserves a leading zero")
                    .isEqualTo("0003");
        }

        @ParameterizedTest(name = "{0} encodes to \"{1}\"")
        @CsvSource({
            "15.00,00150{",
            "25.00,00250{",
            "0.00,00000{",
            "-15.00,00150}",
            "15.01,00150A",
            "-15.01,00150J",
            "9999.99,99999I",
            "-9999.99,99999R"})
        @DisplayName("the rate is written with its sign overpunched into the final byte")
        void theRateIsWrittenWithAnOverpunchedSign(final String rate, final String rateImage) {
            String encoded = DisclosureGroupRecordMapper.toRecord(
                    new DisclosureGroup(GROUP_A, "01", "0001", new BigDecimal(rate)));

            assertThat(encoded.substring(EXPECTED_RATE_OFFSET,
                    EXPECTED_RATE_OFFSET + EXPECTED_RATE_WIDTH))
                    .as("the rate %s occupies six bytes as %s", rate, rateImage)
                    .isEqualTo(rateImage);
        }

        @Test
        @DisplayName("a rate carrying more than two decimal places is truncated, never rounded up")
        void aRateIsTruncatedRatherThanRounded() {
            String encoded = DisclosureGroupRecordMapper.toRecord(
                    new DisclosureGroup(GROUP_A, "01", "0001", new BigDecimal("15.999")));

            assertThat(encoded.substring(EXPECTED_RATE_OFFSET,
                    EXPECTED_RATE_OFFSET + EXPECTED_RATE_WIDTH))
                    .as("no ROUNDED clause exists anywhere in the estate, so a store truncates")
                    .isEqualTo("00159I");
        }

        @Test
        @DisplayName("the filler run is emitted as spaces, which the fixture's zeros are not")
        void theFillerRunIsEmittedAsSpaces() {
            String encoded = DisclosureGroupRecordMapper.toRecord(
                    new DisclosureGroup(GROUP_ZERO_APR, "07", "0001", new BigDecimal("0.00")));

            assertThat(encoded.substring(EXPECTED_FILLER_OFFSET))
                    .isEqualTo(run(EMITTED_FILLER_CHARACTER, EXPECTED_FILLER_WIDTH));
        }

        @Test
        @DisplayName("the byte encoder returns the same image, in a fresh array the caller owns")
        void theByteEncoderReturnsTheSameImage() {
            DisclosureGroup entity =
                    new DisclosureGroup(GROUP_A, "06", "0002", new BigDecimal("15.00"));

            byte[] first = DisclosureGroupRecordMapper.toRecordBytes(entity);
            byte[] second = DisclosureGroupRecordMapper.toRecordBytes(entity);

            assertThat(new String(first, StandardCharsets.US_ASCII))
                    .isEqualTo(DisclosureGroupRecordMapper.toRecord(entity));
            assertThat(first)
                    .as("each call must return a fresh array the caller may mutate")
                    .isNotSameAs(second)
                    .isEqualTo(second);
        }

        @Test
        @DisplayName("an over-wide value is refused rather than truncated to fit")
        void anOverWideValueIsRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> DisclosureGroupRecordMapper.toRecord(new DisclosureGroup(
                            "GROUP123456", "01", "0001", new BigDecimal("15.00"))))
                    .withMessageContaining("never truncated to fit");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> DisclosureGroupRecordMapper.toRecord(new DisclosureGroup(
                            GROUP_A, "01", "00001", new BigDecimal("15.00"))))
                    .withMessageContaining("never truncated to fit");
        }

        @Test
        @DisplayName("a rate outside the four-digit integral range is refused rather than wrapped")
        void anOutOfRangeRateIsRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("PIC S9(04)V99 cannot hold five integral digits")
                    .isThrownBy(() -> DisclosureGroupRecordMapper.toRecord(new DisclosureGroup(
                            GROUP_A, "01", "0001", new BigDecimal("10000.00"))));
        }

        @Test
        @DisplayName("a null entity or a null attribute is refused, and the refusal names the field")
        void aNullEntityOrAttributeIsRefused() {
            assertThatNullPointerException()
                    .isThrownBy(() -> DisclosureGroupRecordMapper.toRecord(null))
                    .withMessageContaining("group");
            assertThatNullPointerException()
                    .isThrownBy(() -> DisclosureGroupRecordMapper.toRecord(new DisclosureGroup(
                            null, "01", "0001", new BigDecimal("15.00"))))
                    .withMessageContaining("DIS-ACCT-GROUP-ID");
            assertThatNullPointerException()
                    .isThrownBy(() -> DisclosureGroupRecordMapper.toRecord(new DisclosureGroup(
                            GROUP_A, null, "0001", new BigDecimal("15.00"))))
                    .withMessageContaining("DIS-TRAN-TYPE-CD");
            assertThatNullPointerException()
                    .isThrownBy(() -> DisclosureGroupRecordMapper.toRecord(new DisclosureGroup(
                            GROUP_A, "01", null, new BigDecimal("15.00"))))
                    .withMessageContaining("DIS-TRAN-CAT-CD");
            assertThatNullPointerException()
                    .isThrownBy(() -> DisclosureGroupRecordMapper.toRecordBytes(
                            new DisclosureGroup(GROUP_A, "01", "0001", null)))
                    .withMessageContaining("DIS-INT-RATE");
        }
    }

    @Nested
    @DisplayName("the reference fixture, whose composition makes both rate-lookup branches reachable")
    class ReferenceFixture {

        @Test
        @DisplayName("holds exactly fifty-one records of exactly fifty bytes")
        void holdsFiftyOneRecordsOfFiftyBytes() {
            SeededRecordFixture loaded = fixture();

            assertThat(loaded.recordCount())
                    .as("the plan names fifty-one disclosure-group rows")
                    .isEqualTo(EXPECTED_FIXTURE_RECORDS);
            assertThat(loaded.records())
                    .allSatisfy(record -> assertThat(record).hasSize(EXPECTED_RECORD_WIDTH));
        }

        @Test
        @DisplayName("holds exactly three groups of exactly seventeen rows each")
        void holdsThreeGroupsOfSeventeen() {
            Map<String, Long> rowsByGroup = decodedFixture().stream()
                    .collect(Collectors.groupingBy(DisclosureGroup::getDisAcctGroupId,
                            Collectors.counting()));

            assertThat(rowsByGroup)
                    .as("three complete groups, named exactly as their raw ten-byte images read")
                    .containsOnlyKeys(GROUP_A, GROUP_DEFAULT, GROUP_ZERO_APR)
                    .containsValues((long) EXPECTED_ROWS_PER_GROUP,
                            (long) EXPECTED_ROWS_PER_GROUP, (long) EXPECTED_ROWS_PER_GROUP);
            assertThat(EXPECTED_ROWS_PER_GROUP * rowsByGroup.size())
                    .as("three groups of seventeen accounts for every record")
                    .isEqualTo(EXPECTED_FIXTURE_RECORDS);
        }

        @Test
        @DisplayName("the default group is present, which is what makes the fallback branch reachable")
        void theDefaultGroupIsPresent() {
            assertThat(decodedFixture())
                    .as("the legacy rate lookup falls back to the default group on a not-found status")
                    .anySatisfy(group -> assertThat(group.getDisAcctGroupId())
                            .isEqualTo(GROUP_DEFAULT));
        }

        @Test
        @DisplayName("one group is uniformly zero-rated, which is what makes the skip branch reachable")
        void oneGroupIsUniformlyZeroRated() {
            List<DisclosureGroup> zeroApr = decodedFixture().stream()
                    .filter(group -> GROUP_ZERO_APR.equals(group.getDisAcctGroupId()))
                    .toList();

            assertThat(zeroApr)
                    .as("interest is computed only when the rate is non-zero, so a zero group matters")
                    .hasSize(EXPECTED_ROWS_PER_GROUP)
                    .allSatisfy(group -> assertThat(group.getDisIntRate())
                            .isEqualByComparingTo("0.00"));
        }

        @Test
        @DisplayName("each group carries the rate multiset transcribed from the fixture's bytes")
        void eachGroupCarriesItsTranscribedRateMultiset() {
            Map<String, Map<BigDecimal, Long>> ratesByGroup = decodedFixture().stream()
                    .collect(Collectors.groupingBy(DisclosureGroup::getDisAcctGroupId,
                            Collectors.groupingBy(DisclosureGroup::getDisIntRate,
                                    Collectors.counting())));

            assertThat(ratesByGroup.get(GROUP_A))
                    .as("group A: eight rows at 15.00, three at 25.00 and six at zero")
                    .containsExactlyInAnyOrderEntriesOf(Map.of(
                            new BigDecimal("15.00"), 8L,
                            new BigDecimal("25.00"), 3L,
                            new BigDecimal("0.00"), 6L));
            assertThat(ratesByGroup.get(GROUP_DEFAULT))
                    .as("the default group differs from group A by one row, and only by one")
                    .containsExactlyInAnyOrderEntriesOf(Map.of(
                            new BigDecimal("15.00"), 7L,
                            new BigDecimal("25.00"), 3L,
                            new BigDecimal("0.00"), 7L));
            assertThat(ratesByGroup.get(GROUP_ZERO_APR))
                    .as("the zero-rate group is uniform")
                    .containsExactlyInAnyOrderEntriesOf(Map.of(
                            new BigDecimal("0.00"), (long) EXPECTED_ROWS_PER_GROUP));
        }

        @Test
        @DisplayName("the default group differs from group A at exactly one type-and-category tuple")
        void theDefaultGroupDiffersAtExactlyOneTuple() {
            Function<String, Map<String, BigDecimal>> ratesOf = groupId -> decodedFixture().stream()
                    .filter(group -> groupId.equals(group.getDisAcctGroupId()))
                    .collect(Collectors.toMap(
                            group -> group.getDisTranTypeCd() + "/" + group.getDisTranCatCd(),
                            DisclosureGroup::getDisIntRate));

            Map<String, BigDecimal> a = ratesOf.apply(GROUP_A);
            Map<String, BigDecimal> fallback = ratesOf.apply(GROUP_DEFAULT);

            List<String> differing = a.keySet().stream()
                    .filter(tuple -> a.get(tuple).compareTo(fallback.get(tuple)) != 0)
                    .toList();

            assertThat(differing)
                    .as("the fallback group is group A with one adjustment rate waived")
                    .containsExactly("07/0001");
            assertThat(a.get("07/0001")).isEqualByComparingTo("15.00");
            assertThat(fallback.get("07/0001")).isEqualByComparingTo("0.00");
        }

        @ParameterizedTest(name = "{0}/{1}")
        @MethodSource("com.carddemo.util.DisclosureGroupRecordMapperCoverageTest#groupTuples")
        @DisplayName("every group repeats the same seventeen type-and-category tuples")
        void everyGroupRepeatsTheSameTuples(final String type, final String category) {
            List<DisclosureGroup> decoded = decodedFixture();

            for (String groupId : List.of(GROUP_A, GROUP_DEFAULT, GROUP_ZERO_APR)) {
                assertThat(decoded)
                        .as("group %s must carry the tuple %s/%s", groupId, type, category)
                        .anySatisfy(group -> {
                            assertThat(group.getDisAcctGroupId()).isEqualTo(groupId);
                            assertThat(group.getDisTranTypeCd()).isEqualTo(type);
                            assertThat(group.getDisTranCatCd()).isEqualTo(category);
                        });
            }
        }

        @Test
        @DisplayName("uses exactly three distinct rate images, all positively overpunched")
        void usesExactlyThreeRateImages() {
            List<String> rateImages = fixture().records().stream()
                    .map(record -> record.substring(EXPECTED_RATE_OFFSET,
                            EXPECTED_RATE_OFFSET + EXPECTED_RATE_WIDTH))
                    .distinct()
                    .sorted()
                    .toList();

            assertThat(rateImages)
                    .as("a rate is never negative in this estate's seed data")
                    .containsExactly(RATE_IMAGE_ZERO, RATE_IMAGE_FIFTEEN, RATE_IMAGE_TWENTY_FIVE);
        }

        @Test
        @DisplayName("holds ASCII zero in every filler byte of every record")
        void holdsAsciiZeroInEveryFillerByte() {
            assertThat(fixture().records())
                    .allSatisfy(record -> assertThat(record.substring(EXPECTED_FILLER_OFFSET))
                            .isEqualTo(run(FIXTURE_FILLER_CHARACTER, EXPECTED_FILLER_WIDTH)));
        }
    }

    @Nested
    @DisplayName("the round trip over the mapped prefix is exact")
    class RoundTrip {

        @Test
        @DisplayName("every one of the fifty-one records survives a decode and re-encode unchanged")
        void everyRecordSurvivesUnchanged() {
            for (String original : fixture().records()) {
                String reEncoded = DisclosureGroupRecordMapper.toRecord(
                        DisclosureGroupRecordMapper.fromRecord(original));

                assertThat(reEncoded.substring(0, EXPECTED_MAPPED_WIDTH))
                        .as("the mapped prefix of %s must be reproduced byte for byte",
                                original.substring(0, EXPECTED_KEY_WIDTH))
                        .isEqualTo(original.substring(0, EXPECTED_MAPPED_WIDTH));
            }
        }

        @ParameterizedTest(name = "record {0}")
        @MethodSource("com.carddemo.util.DisclosureGroupRecordMapperCoverageTest#representativeRecords")
        @DisplayName("the filler run is the only place a whole-record comparison differs")
        void theFillerIsTheOnlyDifference(final int ordinal, final String group, final String type,
                final String category, final String rateImage, final String rate) {
            String original = fixture().record(ordinal);

            String reEncoded = DisclosureGroupRecordMapper.toRecord(
                    DisclosureGroupRecordMapper.fromRecord(original));

            assertThat(reEncoded)
                    .as("record %d (%s %s/%s) differs in the filler and nowhere else",
                            ordinal, group, type, category)
                    .isNotEqualTo(original)
                    .isEqualTo(original.substring(0, EXPECTED_MAPPED_WIDTH)
                            + run(EMITTED_FILLER_CHARACTER, EXPECTED_FILLER_WIDTH));
        }

        @Test
        @DisplayName("a second round trip is a fixed point, so encoding is idempotent")
        void aSecondRoundTripIsAFixedPoint() {
            String once = DisclosureGroupRecordMapper.toRecord(
                    DisclosureGroupRecordMapper.fromRecord(fixture().record(1)));

            String twice = DisclosureGroupRecordMapper.toRecord(
                    DisclosureGroupRecordMapper.fromRecord(once));

            assertThat(twice)
                    .as("an emitted image must decode and re-encode to itself exactly")
                    .isEqualTo(once);
        }

        @ParameterizedTest(name = "rate {0}")
        @ValueSource(strings = {"0.00", "0.01", "15.00", "25.00", "-15.00", "9999.99", "-9999.99"})
        @DisplayName("a rate survives an encode and decode with its value and its scale intact")
        void aRateSurvivesAnEncodeAndDecode(final String rate) {
            DisclosureGroup original =
                    new DisclosureGroup(GROUP_A, "01", "0001", new BigDecimal(rate));

            DisclosureGroup recovered = DisclosureGroupRecordMapper.fromRecord(
                    DisclosureGroupRecordMapper.toRecordBytes(original));

            assertThat(recovered.getDisIntRate())
                    .as("the rate %s must return exactly as it went in", rate)
                    .isEqualByComparingTo(original.getDisIntRate());
            assertThat(recovered.getDisIntRate().scale())
                    .as("and at the scale the declaration fixes, not the scale the caller chose")
                    .isEqualTo(EXPECTED_RATE_SCALE);
        }
    }
}
