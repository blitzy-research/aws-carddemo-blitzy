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

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Stream;

import com.carddemo.domain.TransactionType;
import com.carddemo.support.SeededRecordFixture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * Byte-parity acceptance for the 60-byte transaction-type record layout.
 *
 * <h2>What is under test</h2>
 * {@link TranTypeRecordMapper} is one of the eleven hand-written fixed-width mappers the migration
 * plan requires in place of any annotation-driven or reflective mapping library, and the sole reason
 * that prohibition exists is the reflection budget of zero the unsafe-code audit commits to. A
 * hand-written mapper buys that budget at the cost of every offset being an assertion someone has to
 * make, so this class makes them: the record width, both mapped field placements, the filler run, the
 * left-justified space padding an alphanumeric legacy declaration implies, and the exact inverse
 * relationship between decoding and encoding over the mapped prefix.
 *
 * <h2>Where the expectations come from</h2>
 * Every expectation here is authored independently of the mapper. The geometry is stated as literal
 * integers rather than read from the class under test, so a future edit to an offset constant fails
 * this class instead of silently agreeing with it. The seven decoded values are transcribed from a
 * byte-level reading of the reference fixture {@code [app/data/ASCII/trantype.txt]}, and the
 * record images used in the decode tests are assembled character by character in this file. At no
 * point is the mapper's own output used as the oracle for the mapper's behaviour.
 *
 * <h2>The one place the emitted image and the fixture legitimately differ</h2>
 * The fixture holds ASCII zero in the eight filler bytes; the encoder writes spaces there. That is
 * the documented behaviour of {@code TRAN_TYPE_FILLER_CHARACTER}, and it is asserted in both
 * directions rather than worked around, because a comparison that silently normalised the filler
 * would also hide a real defect in the fifty-two bytes that carry information.
 *
 * <p>A pure in-process unit test: no application context, no database, no container, no mocking
 * framework, and no reflection.</p>
 *
 * <p>Provenance: part of the migration of the AWS CardDemo z/OS application at checkout SHA
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. No legacy source text is reproduced.</p>
 */
@DisplayName("TranTypeRecordMapper - the 60-byte transaction-type record")
class TranTypeRecordMapperCoverageTest {

    /** Name of the reference fixture on the test classpath. */
    private static final String FIXTURE_FILE = "trantype.txt";

    /** Declared record width, stated here rather than read from the class under test. */
    private static final int EXPECTED_RECORD_WIDTH = 60;

    /** Declared width of the prefix that carries information. */
    private static final int EXPECTED_MAPPED_WIDTH = 52;

    /** Declared placement of the two-character type code. */
    private static final int EXPECTED_CODE_OFFSET = 0;

    /** Declared width of the two-character type code. */
    private static final int EXPECTED_CODE_WIDTH = 2;

    /** Declared placement of the fifty-character description. */
    private static final int EXPECTED_DESCRIPTION_OFFSET = 2;

    /** Declared width of the fifty-character description. */
    private static final int EXPECTED_DESCRIPTION_WIDTH = 50;

    /** Declared placement of the trailing filler run. */
    private static final int EXPECTED_FILLER_OFFSET = 52;

    /** Declared width of the trailing filler run. */
    private static final int EXPECTED_FILLER_WIDTH = 8;

    /** Number of records the reference fixture holds. */
    private static final int EXPECTED_FIXTURE_RECORDS = 7;

    /** The character the reference fixture holds in its filler bytes. */
    private static final char FIXTURE_FILLER_CHARACTER = '0';

    /** The character the encoder writes in its filler bytes. */
    private static final char EMITTED_FILLER_CHARACTER = ' ';

    /**
     * Supplies the seven reference records, each as the one-based ordinal, the type code and the
     * untrimmed fifty-character description exactly as the fixture carries them.
     *
     * <p>Transcribed from a byte-level reading of the fixture. The ordinals are one-based to match
     * the fixture loader's accessor, and the descriptions are padded by an explicit call rather than
     * with a long run of literal spaces, because a literal run of fifty-minus-n spaces is unreadable
     * and impossible to review.</p>
     *
     * @return one argument triple per fixture record
     */
    private static Stream<Arguments> referenceRecords() {
        return Stream.of(
                Arguments.of(1, "01", "Purchase"),
                Arguments.of(2, "02", "Payment"),
                Arguments.of(3, "03", "Credit"),
                Arguments.of(4, "04", "Authorization"),
                Arguments.of(5, "05", "Refund"),
                Arguments.of(6, "06", "Reversal"),
                Arguments.of(7, "07", "Adjustment"));
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
     * Assembles a complete 60-byte record image from its three parts.
     *
     * @param code        the two-character type code, used verbatim
     * @param description the description, right-padded here to its declared width
     * @param filler      the character to place in all eight filler bytes
     * @return a record image of exactly {@link #EXPECTED_RECORD_WIDTH} characters
     */
    private static String image(final String code, final String description, final char filler) {
        return code
                + padded(description, EXPECTED_DESCRIPTION_WIDTH)
                + run(filler, EXPECTED_FILLER_WIDTH);
    }

    /**
     * Loads the reference fixture at this layout's declared width.
     *
     * @return the loaded fixture
     */
    private static SeededRecordFixture fixture() {
        return SeededRecordFixture.load(FIXTURE_FILE, EXPECTED_RECORD_WIDTH);
    }

    @Nested
    @DisplayName("the declared geometry reproduces the copybook exactly")
    class DeclaredGeometry {

        @Test
        @DisplayName("the record is 60 bytes and the mapped prefix is 52")
        void theWidthsAreTheDeclaredWidths() {
            assertThat(TranTypeRecordMapper.TRAN_TYPE_RECORD_LENGTH)
                    .as("the transaction-type cluster declares a 60-byte record")
                    .isEqualTo(EXPECTED_RECORD_WIDTH);
            assertThat(TranTypeRecordMapper.TRAN_TYPE_MAPPED_LENGTH)
                    .as("only the first 52 bytes carry information")
                    .isEqualTo(EXPECTED_MAPPED_WIDTH);
        }

        @Test
        @DisplayName("both fields sit at their declared offsets and widths")
        void bothFieldsSitWhereTheCopybookPutsThem() {
            assertThat(TranTypeRecordMapper.TRAN_TYPE_CODE_OFFSET)
                    .as("TRAN-TYPE begins the record")
                    .isEqualTo(EXPECTED_CODE_OFFSET);
            assertThat(TranTypeRecordMapper.TRAN_TYPE_CODE_LENGTH)
                    .as("TRAN-TYPE is two characters")
                    .isEqualTo(EXPECTED_CODE_WIDTH);
            assertThat(TranTypeRecordMapper.TRAN_TYPE_DESCRIPTION_OFFSET)
                    .as("TRAN-TYPE-DESC follows the code immediately")
                    .isEqualTo(EXPECTED_DESCRIPTION_OFFSET);
            assertThat(TranTypeRecordMapper.TRAN_TYPE_DESCRIPTION_LENGTH)
                    .as("TRAN-TYPE-DESC is fifty characters")
                    .isEqualTo(EXPECTED_DESCRIPTION_WIDTH);
        }

        @Test
        @DisplayName("the filler occupies the whole remainder, so every byte is accounted for")
        void theFillerAccountsForTheRemainder() {
            assertThat(TranTypeRecordMapper.TRAN_TYPE_FILLER_OFFSET)
                    .as("the filler begins where the mapped prefix ends")
                    .isEqualTo(EXPECTED_FILLER_OFFSET);
            assertThat(TranTypeRecordMapper.TRAN_TYPE_FILLER_LENGTH)
                    .as("the filler is eight bytes")
                    .isEqualTo(EXPECTED_FILLER_WIDTH);
            assertThat(TranTypeRecordMapper.TRAN_TYPE_FILLER_OFFSET
                    + TranTypeRecordMapper.TRAN_TYPE_FILLER_LENGTH)
                    .as("code, description and filler must tile the record with no gap and no overlap")
                    .isEqualTo(EXPECTED_RECORD_WIDTH);
        }

        @Test
        @DisplayName("the mapped prefix is exactly the sum of the two mapped fields")
        void theMappedPrefixIsTheSumOfItsFields() {
            assertThat(TranTypeRecordMapper.TRAN_TYPE_CODE_LENGTH
                    + TranTypeRecordMapper.TRAN_TYPE_DESCRIPTION_LENGTH)
                    .as("a mapped prefix that is not the sum of its fields hides a gap")
                    .isEqualTo(TranTypeRecordMapper.TRAN_TYPE_MAPPED_LENGTH);
        }

        @Test
        @DisplayName("the encoder's filler character is a space, which the fixture's is not")
        void theEmittedFillerIsASpace() {
            assertThat(TranTypeRecordMapper.TRAN_TYPE_FILLER_CHARACTER)
                    .as("the encoder writes spaces into the filler run")
                    .isEqualTo(EMITTED_FILLER_CHARACTER);
            assertThat(TranTypeRecordMapper.TRAN_TYPE_FILLER_CHARACTER)
                    .as("the fixture holds ASCII zero there, so the two legitimately differ")
                    .isNotEqualTo(FIXTURE_FILLER_CHARACTER);
        }
    }

    @Nested
    @DisplayName("decoding a record image")
    class Decoding {

        @Test
        @DisplayName("both fields arrive verbatim, untrimmed and not case-folded")
        void bothFieldsArriveVerbatim() {
            TransactionType decoded = TranTypeRecordMapper.fromRecord(
                    image("01", "Purchase", FIXTURE_FILLER_CHARACTER));

            assertThat(decoded.getTranType())
                    .as("the code keeps its leading zero")
                    .isEqualTo("01");
            assertThat(decoded.getTranTypeDesc())
                    .as("the description keeps every one of its trailing spaces")
                    .isEqualTo(padded("Purchase", EXPECTED_DESCRIPTION_WIDTH))
                    .hasSize(EXPECTED_DESCRIPTION_WIDTH);
        }

        @Test
        @DisplayName("a description that fills its field arrives with no padding at all")
        void aFullWidthDescriptionArrivesWhole() {
            String full = run('X', EXPECTED_DESCRIPTION_WIDTH);

            TransactionType decoded =
                    TranTypeRecordMapper.fromRecord(image("99", full, EMITTED_FILLER_CHARACTER));

            assertThat(decoded.getTranTypeDesc())
                    .as("a fifty-character description occupies the field exactly")
                    .isEqualTo(full);
        }

        @Test
        @DisplayName("an all-blank record decodes to two blank fields rather than to nulls")
        void anAllBlankRecordDecodesToBlanks() {
            TransactionType decoded = TranTypeRecordMapper.fromRecord(
                    run(' ', EXPECTED_RECORD_WIDTH));

            assertThat(decoded.getTranType())
                    .as("a blank key is still a value, and substituting null would lose the width")
                    .isEqualTo(run(' ', EXPECTED_CODE_WIDTH));
            assertThat(decoded.getTranTypeDesc())
                    .as("a blank description is still fifty bytes wide")
                    .isEqualTo(run(' ', EXPECTED_DESCRIPTION_WIDTH));
        }

        @Test
        @DisplayName("the filler bytes are read by nobody, so two records differing only there decode alike")
        void theFillerIsNotRead() {
            TransactionType withZeroFiller = TranTypeRecordMapper.fromRecord(
                    image("03", "Credit", FIXTURE_FILLER_CHARACTER));
            TransactionType withSpaceFiller = TranTypeRecordMapper.fromRecord(
                    image("03", "Credit", EMITTED_FILLER_CHARACTER));

            assertThat(withZeroFiller.getTranType()).isEqualTo(withSpaceFiller.getTranType());
            assertThat(withZeroFiller.getTranTypeDesc())
                    .as("the filler carries no information, so it cannot change a decoded field")
                    .isEqualTo(withSpaceFiller.getTranTypeDesc());
        }

        @Test
        @DisplayName("the byte overload agrees with the text overload byte for byte")
        void theByteOverloadAgreesWithTheTextOverload() {
            String text = image("04", "Authorization", FIXTURE_FILLER_CHARACTER);

            TransactionType fromText = TranTypeRecordMapper.fromRecord(text);
            TransactionType fromBytes =
                    TranTypeRecordMapper.fromRecord(text.getBytes(StandardCharsets.US_ASCII));

            assertThat(fromBytes.getTranType()).isEqualTo(fromText.getTranType());
            assertThat(fromBytes.getTranTypeDesc())
                    .as("choosing bytes over text must not change a single decoded character")
                    .isEqualTo(fromText.getTranTypeDesc());
        }
    }

    @Nested
    @DisplayName("decoding one record out of a larger buffer")
    class BufferDecoding {

        /** Stride of the newline-terminated reference file: the record width plus one terminator. */
        private static final int STRIDE = EXPECTED_RECORD_WIDTH + 1;

        @ParameterizedTest(name = "record {0} is {1}")
        @MethodSource("com.carddemo.util.TranTypeRecordMapperCoverageTest#referenceRecords")
        @DisplayName("stride arithmetic selects each record and leaves its terminator behind")
        void strideArithmeticSelectsEachRecord(
                final int ordinal, final String code, final String description) {
            byte[] wholeFile = String.join("\n", fixture().records()).concat("\n")
                    .getBytes(StandardCharsets.US_ASCII);

            TransactionType decoded =
                    TranTypeRecordMapper.fromRecord(wholeFile, (ordinal - 1) * STRIDE);

            assertThat(decoded.getTranType())
                    .as("record %d of the file must be selected, not its neighbour", ordinal)
                    .isEqualTo(code);
            assertThat(decoded.getTranTypeDesc())
                    .as("the 0x0A terminator is a separator and must never enter a field")
                    .isEqualTo(padded(description, EXPECTED_DESCRIPTION_WIDTH));
        }

        @Test
        @DisplayName("a negative start index is refused rather than wrapped")
        void aNegativeStartIndexIsRefused() {
            byte[] buffer = new byte[EXPECTED_RECORD_WIDTH];

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> TranTypeRecordMapper.fromRecord(buffer, -1))
                    .withMessageContaining("must not be negative");
        }

        @Test
        @DisplayName("a record that would run past the end of the buffer is refused, not short-read")
        void anOverrunningRecordIsRefused() {
            byte[] buffer = run(' ', EXPECTED_RECORD_WIDTH)
                    .getBytes(StandardCharsets.US_ASCII);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> TranTypeRecordMapper.fromRecord(buffer, 1))
                    .withMessageContaining("does not fit inside the supplied buffer");
        }
    }

    @Nested
    @DisplayName("a malformed image is refused rather than repaired")
    class MalformedInput {

        @ParameterizedTest(name = "width {0}")
        @ValueSource(ints = {0, 1, 59, 61, 120})
        @DisplayName("any width other than 60 is refused, in both directions")
        void anyOtherWidthIsRefused(final int width) {
            String wrongWidth = run(' ', width);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> TranTypeRecordMapper.fromRecord(wrongWidth))
                    .withMessageContaining("must be exactly " + EXPECTED_RECORD_WIDTH);
        }

        @Test
        @DisplayName("an unstripped line terminator is named as such, because it is the usual cause")
        void anUnstrippedTerminatorIsNamed() {
            String withTerminator =
                    image("01", "Purchase", FIXTURE_FILLER_CHARACTER) + "\n";

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> TranTypeRecordMapper.fromRecord(withTerminator))
                    .withMessageContaining("line ");
        }

        @Test
        @DisplayName("a non-ASCII character is refused rather than transcoded")
        void aNonAsciiCharacterIsRefused() {
            String withAccent = "01" + padded("Caf\u00e9", EXPECTED_DESCRIPTION_WIDTH)
                    + run(FIXTURE_FILLER_CHARACTER, EXPECTED_FILLER_WIDTH);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> TranTypeRecordMapper.fromRecord(withAccent))
                    .withMessageContaining("US-ASCII cannot represent");
        }

        @Test
        @DisplayName("a byte above 0x7F is refused, because it means another encoding")
        void aHighByteIsRefused() {
            byte[] record = run(' ', EXPECTED_RECORD_WIDTH)
                    .getBytes(StandardCharsets.US_ASCII);
            record[EXPECTED_DESCRIPTION_OFFSET] = (byte) 0xC3;

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> TranTypeRecordMapper.fromRecord(record))
                    .withMessageContaining("non-ASCII byte");
        }

        @Test
        @DisplayName("a null image is refused on every decode entry point")
        void aNullImageIsRefused() {
            assertThatNullPointerException()
                    .isThrownBy(() -> TranTypeRecordMapper.fromRecord((String) null));
            assertThatNullPointerException()
                    .isThrownBy(() -> TranTypeRecordMapper.fromRecord((byte[]) null));
            assertThatNullPointerException()
                    .isThrownBy(() -> TranTypeRecordMapper.fromRecord(null, 0));
        }
    }

    @Nested
    @DisplayName("encoding an entity")
    class Encoding {

        @Test
        @DisplayName("the image is exactly 60 bytes and carries no terminator")
        void theImageIsExactlyTheDeclaredWidth() {
            String encoded =
                    TranTypeRecordMapper.toRecord(new TransactionType("01", "Purchase"));

            assertThat(encoded)
                    .as("a fixed-width record is never padded or truncated on output")
                    .hasSize(EXPECTED_RECORD_WIDTH);
            assertThat(encoded)
                    .as("a terminator is a record separator and never record content")
                    .doesNotContain("\n")
                    .doesNotContain("\r");
        }

        @Test
        @DisplayName("both fields are left-justified and space-padded to their declared widths")
        void bothFieldsAreLeftJustifiedAndSpacePadded() {
            String encoded =
                    TranTypeRecordMapper.toRecord(new TransactionType("07", "Adjustment"));

            assertThat(encoded.substring(EXPECTED_CODE_OFFSET,
                    EXPECTED_CODE_OFFSET + EXPECTED_CODE_WIDTH))
                    .as("the code is placed verbatim and never zero-padded into a leading zero")
                    .isEqualTo("07");
            assertThat(encoded.substring(EXPECTED_DESCRIPTION_OFFSET,
                    EXPECTED_DESCRIPTION_OFFSET + EXPECTED_DESCRIPTION_WIDTH))
                    .as("an alphanumeric declaration means left-justified, space-padded")
                    .isEqualTo(padded("Adjustment", EXPECTED_DESCRIPTION_WIDTH));
        }

        @Test
        @DisplayName("the filler run is emitted as spaces, which the fixture's zeros are not")
        void theFillerRunIsEmittedAsSpaces() {
            String encoded =
                    TranTypeRecordMapper.toRecord(new TransactionType("02", "Payment"));

            assertThat(encoded.substring(EXPECTED_FILLER_OFFSET))
                    .as("the emitted filler is eight spaces")
                    .isEqualTo(run(EMITTED_FILLER_CHARACTER, EXPECTED_FILLER_WIDTH));
        }

        @Test
        @DisplayName("the byte encoder returns the same image, in a fresh array the caller owns")
        void theByteEncoderReturnsTheSameImage() {
            TransactionType entity = new TransactionType("05", "Refund");

            byte[] first = TranTypeRecordMapper.toRecordBytes(entity);
            byte[] second = TranTypeRecordMapper.toRecordBytes(entity);

            assertThat(new String(first, StandardCharsets.US_ASCII))
                    .as("bytes and text must render identically")
                    .isEqualTo(TranTypeRecordMapper.toRecord(entity));
            assertThat(first)
                    .as("each call must return a fresh array the caller may mutate")
                    .isNotSameAs(second)
                    .isEqualTo(second);

            first[0] = 'Z';
            assertThat(TranTypeRecordMapper.toRecordBytes(entity))
                    .as("mutating a returned array must not affect a later call")
                    .isEqualTo(second);
        }

        @Test
        @DisplayName("an over-wide value is refused rather than truncated to fit")
        void anOverWideValueIsRefused() {
            TransactionType tooLongCode = new TransactionType("012", "Purchase");
            TransactionType tooLongDescription = new TransactionType("01",
                    run('X', EXPECTED_DESCRIPTION_WIDTH + 1));

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> TranTypeRecordMapper.toRecord(tooLongCode))
                    .withMessageContaining("never truncated to fit");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> TranTypeRecordMapper.toRecord(tooLongDescription))
                    .withMessageContaining("never truncated to fit");
        }

        @Test
        @DisplayName("a null entity or a null attribute is refused, and the refusal names the attribute")
        void aNullEntityOrAttributeIsRefused() {
            assertThatNullPointerException()
                    .isThrownBy(() -> TranTypeRecordMapper.toRecord(null))
                    .withMessageContaining("transactionType");
            assertThatNullPointerException()
                    .isThrownBy(() -> TranTypeRecordMapper.toRecord(
                            new TransactionType(null, "Purchase")))
                    .withMessageContaining("tranType");
            assertThatNullPointerException()
                    .isThrownBy(() -> TranTypeRecordMapper.toRecordBytes(
                            new TransactionType("01", null)))
                    .withMessageContaining("tranTypeDesc");
        }
    }

    @Nested
    @DisplayName("the reference fixture")
    class ReferenceFixture {

        @Test
        @DisplayName("holds exactly seven records of exactly sixty bytes")
        void holdsSevenRecordsOfSixtyBytes() {
            SeededRecordFixture loaded = fixture();

            assertThat(loaded.recordCount())
                    .as("the plan names seven transaction types")
                    .isEqualTo(EXPECTED_FIXTURE_RECORDS);
            assertThat(loaded.records())
                    .as("every record measures the declared width")
                    .allSatisfy(record -> assertThat(record).hasSize(EXPECTED_RECORD_WIDTH));
        }

        @ParameterizedTest(name = "record {0} is code {1}")
        @MethodSource("com.carddemo.util.TranTypeRecordMapperCoverageTest#referenceRecords")
        @DisplayName("each record decodes to the code and description transcribed from its bytes")
        void eachRecordDecodesToItsTranscribedValue(
                final int ordinal, final String code, final String description) {
            TransactionType decoded =
                    TranTypeRecordMapper.fromRecord(fixture().record(ordinal));

            assertThat(decoded.getTranType())
                    .as("record %d carries code %s", ordinal, code)
                    .isEqualTo(code);
            assertThat(decoded.getTranTypeDesc())
                    .as("record %d carries description %s, padded to fifty", ordinal, description)
                    .isEqualTo(padded(description, EXPECTED_DESCRIPTION_WIDTH));
        }

        @Test
        @DisplayName("holds ASCII zero in every filler byte of every record")
        void holdsAsciiZeroInEveryFillerByte() {
            assertThat(fixture().records())
                    .as("the fixture's filler is zeros, which is why a whole-record comparison fails")
                    .allSatisfy(record -> assertThat(record.substring(EXPECTED_FILLER_OFFSET))
                            .isEqualTo(run(FIXTURE_FILLER_CHARACTER, EXPECTED_FILLER_WIDTH)));
        }

        @Test
        @DisplayName("carries seven distinct codes, one to seven, in ascending order")
        void carriesSevenDistinctAscendingCodes() {
            List<String> codes = fixture().records().stream()
                    .map(TranTypeRecordMapper::fromRecord)
                    .map(TransactionType::getTranType)
                    .toList();

            assertThat(codes)
                    .as("the type codes are the seven legacy values in file order")
                    .containsExactly("01", "02", "03", "04", "05", "06", "07")
                    .doesNotHaveDuplicates();
        }
    }

    @Nested
    @DisplayName("the round trip over the mapped prefix is exact")
    class RoundTrip {

        @ParameterizedTest(name = "record {0}")
        @MethodSource("com.carddemo.util.TranTypeRecordMapperCoverageTest#referenceRecords")
        @DisplayName("decoding then re-encoding reproduces the first fifty-two bytes unchanged")
        void theMappedPrefixSurvivesUnchanged(
                final int ordinal, final String code, final String description) {
            String original = fixture().record(ordinal);

            String reEncoded = TranTypeRecordMapper.toRecord(
                    TranTypeRecordMapper.fromRecord(original));

            assertThat(reEncoded.substring(0, EXPECTED_MAPPED_WIDTH))
                    .as("record %d (%s / %s) must survive a decode and re-encode unchanged",
                            ordinal, code, description)
                    .isEqualTo(original.substring(0, EXPECTED_MAPPED_WIDTH));
        }

        @ParameterizedTest(name = "record {0}")
        @MethodSource("com.carddemo.util.TranTypeRecordMapperCoverageTest#referenceRecords")
        @DisplayName("the filler run is the only place a whole-record comparison differs")
        void theFillerIsTheOnlyDifference(
                final int ordinal, final String code, final String description) {
            String original = fixture().record(ordinal);

            String reEncoded = TranTypeRecordMapper.toRecord(
                    TranTypeRecordMapper.fromRecord(original));

            assertThat(reEncoded)
                    .as("record %d (%s / %s) differs from the fixture in the filler and nowhere else",
                            ordinal, code, description)
                    .isNotEqualTo(original)
                    .isEqualTo(original.substring(0, EXPECTED_MAPPED_WIDTH)
                            + run(EMITTED_FILLER_CHARACTER, EXPECTED_FILLER_WIDTH));
        }

        @Test
        @DisplayName("a second round trip is a fixed point, so encoding is idempotent")
        void aSecondRoundTripIsAFixedPoint() {
            String once = TranTypeRecordMapper.toRecord(
                    TranTypeRecordMapper.fromRecord(fixture().record(1)));

            String twice = TranTypeRecordMapper.toRecord(
                    TranTypeRecordMapper.fromRecord(once));

            assertThat(twice)
                    .as("an emitted image must decode and re-encode to itself exactly")
                    .isEqualTo(once);
        }

        @Test
        @DisplayName("an entity survives an encode and decode with both attributes intact")
        void anEntitySurvivesAnEncodeAndDecode() {
            TransactionType original = new TransactionType("09",
                    padded("Synthetic type", EXPECTED_DESCRIPTION_WIDTH));

            TransactionType recovered = TranTypeRecordMapper.fromRecord(
                    TranTypeRecordMapper.toRecordBytes(original));

            assertThat(recovered.getTranType()).isEqualTo(original.getTranType());
            assertThat(recovered.getTranTypeDesc())
                    .as("the padded description must return exactly as it went in")
                    .isEqualTo(original.getTranTypeDesc());
        }
    }
}
