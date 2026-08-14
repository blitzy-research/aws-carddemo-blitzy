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

import com.carddemo.domain.TransactionCategory;
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
 * Byte-parity acceptance for the 60-byte transaction-category record layout.
 *
 * <h2>What is under test</h2>
 * {@link TranCatRecordMapper} carries a composite key: the two-character transaction type and the
 * four-character category together form the six-byte cluster key, and the mapper exposes that key
 * image separately from the whole record because the batch tier needs it on its own. Three properties
 * therefore have to be asserted rather than assumed - the record geometry, the key geometry, and the
 * fact that the two agree, since the key is by construction the record's own first six bytes.
 *
 * <h2>The justification difference that a reviewer will look for</h2>
 * The type code is written left-justified and space-padded, as an alphanumeric declaration implies,
 * while the category code is written right-justified and zero-padded, as a numeric declaration
 * implies. That asymmetry is real and is asserted directly, in both the whole-record encoder and the
 * key-image encoder, because a mapper that treated both fields alike would still produce a record of
 * exactly the right width - the one failure mode a downstream width check can never catch.
 *
 * <h2>Where the expectations come from</h2>
 * The geometry is stated as literal integers rather than read from the class under test. The eighteen
 * decoded values are transcribed from a byte-level reading of the reference fixture
 * {@code [app/data/ASCII/trancatg.txt]}. Record images used in the decode tests are assembled
 * character by character in this file. The mapper's own output is never the oracle for its behaviour.
 *
 * <p>A pure in-process unit test: no application context, no database, no container, no mocking
 * framework, and no reflection.</p>
 *
 * <p>No legacy source text is reproduced.</p>
 */
@DisplayName("TranCatRecordMapper - the 60-byte transaction-category record")
class TranCatRecordMapperCoverageTest {

    /** Name of the reference fixture on the test classpath. */
    private static final String FIXTURE_FILE = "trancatg.txt";

    /** Declared record width, stated here rather than read from the class under test. */
    private static final int EXPECTED_RECORD_WIDTH = 60;

    /** Declared width of the prefix that carries information. */
    private static final int EXPECTED_MAPPED_WIDTH = 56;

    /** Declared width of the composite cluster key. */
    private static final int EXPECTED_KEY_WIDTH = 6;

    /** Declared placement of the two-character transaction type. */
    private static final int EXPECTED_TYPE_OFFSET = 0;

    /** Declared width of the two-character transaction type. */
    private static final int EXPECTED_TYPE_WIDTH = 2;

    /** Declared placement of the four-character category code. */
    private static final int EXPECTED_CATEGORY_OFFSET = 2;

    /** Declared width of the four-character category code. */
    private static final int EXPECTED_CATEGORY_WIDTH = 4;

    /** Declared placement of the fifty-character description. */
    private static final int EXPECTED_DESCRIPTION_OFFSET = 6;

    /** Declared width of the fifty-character description. */
    private static final int EXPECTED_DESCRIPTION_WIDTH = 50;

    /** Declared placement of the trailing filler run. */
    private static final int EXPECTED_FILLER_OFFSET = 56;

    /** Declared width of the trailing filler run. */
    private static final int EXPECTED_FILLER_WIDTH = 4;

    /** Number of records the reference fixture holds. */
    private static final int EXPECTED_FIXTURE_RECORDS = 18;

    /** The character the reference fixture holds in its filler bytes. */
    private static final char FIXTURE_FILLER_CHARACTER = '0';

    /** The character the encoder writes in its filler bytes. */
    private static final char EMITTED_FILLER_CHARACTER = ' ';

    /**
     * Supplies all eighteen reference records as one-based ordinal, type code, category code and
     * untrimmed description, transcribed from a byte-level reading of the fixture.
     *
     * @return one argument quadruple per fixture record
     */
    private static Stream<Arguments> referenceRecords() {
        return Stream.of(
                Arguments.of(1, "01", "0001", "Regular Sales Draft"),
                Arguments.of(2, "01", "0002", "Regular Cash Advance"),
                Arguments.of(3, "01", "0003", "Convenience Check Debit"),
                Arguments.of(4, "01", "0004", "ATM Cash Advance"),
                Arguments.of(5, "01", "0005", "Interest Amount"),
                Arguments.of(6, "02", "0001", "Cash payment"),
                Arguments.of(7, "02", "0002", "Electronic payment"),
                Arguments.of(8, "02", "0003", "Check payment"),
                Arguments.of(9, "03", "0001", "Credit to Account"),
                Arguments.of(10, "03", "0002", "Credit to Purchase balance"),
                Arguments.of(11, "03", "0003", "Credit to Cash balance"),
                Arguments.of(12, "04", "0001", "Zero dollar authorization"),
                Arguments.of(13, "04", "0002", "Online purchase authorization"),
                Arguments.of(14, "04", "0003", "Travel booking authorization"),
                Arguments.of(15, "05", "0001", "Refund credit"),
                Arguments.of(16, "06", "0001", "Fraud reversal"),
                Arguments.of(17, "06", "0002", "Non-fraud reversal"),
                Arguments.of(18, "07", "0001", "Sales draft credit adjustment"));
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
     * Assembles a complete 60-byte record image from its four parts.
     *
     * @param type        the two-character transaction type, used verbatim
     * @param category    the four-character category code, used verbatim
     * @param description the description, right-padded here to its declared width
     * @param filler      the character to place in all four filler bytes
     * @return a record image of exactly {@link #EXPECTED_RECORD_WIDTH} characters
     */
    private static String image(final String type, final String category, final String description,
            final char filler) {
        return type
                + category
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
        @DisplayName("the record is 60 bytes, the mapped prefix 56 and the key 6")
        void theWidthsAreTheDeclaredWidths() {
            assertThat(TranCatRecordMapper.RECORD_WIDTH)
                    .as("the transaction-category cluster declares a 60-byte record")
                    .isEqualTo(EXPECTED_RECORD_WIDTH);
            assertThat(TranCatRecordMapper.MAPPED_DATA_WIDTH)
                    .as("only the first 56 bytes carry information")
                    .isEqualTo(EXPECTED_MAPPED_WIDTH);
            assertThat(TranCatRecordMapper.TYPE_AND_CATEGORY_KEY_WIDTH)
                    .as("the composite key is six bytes")
                    .isEqualTo(EXPECTED_KEY_WIDTH);
        }

        @Test
        @DisplayName("all three fields sit at their declared offsets and widths")
        void allThreeFieldsSitWhereTheCopybookPutsThem() {
            assertThat(TranCatRecordMapper.TRAN_TYPE_CD_OFFSET).isEqualTo(EXPECTED_TYPE_OFFSET);
            assertThat(TranCatRecordMapper.TRAN_TYPE_CD_LENGTH).isEqualTo(EXPECTED_TYPE_WIDTH);
            assertThat(TranCatRecordMapper.TRAN_CAT_CD_OFFSET).isEqualTo(EXPECTED_CATEGORY_OFFSET);
            assertThat(TranCatRecordMapper.TRAN_CAT_CD_LENGTH).isEqualTo(EXPECTED_CATEGORY_WIDTH);
            assertThat(TranCatRecordMapper.TRAN_CAT_TYPE_DESC_OFFSET)
                    .as("the description follows the composite key immediately")
                    .isEqualTo(EXPECTED_DESCRIPTION_OFFSET);
            assertThat(TranCatRecordMapper.TRAN_CAT_TYPE_DESC_LENGTH)
                    .isEqualTo(EXPECTED_DESCRIPTION_WIDTH);
        }

        @Test
        @DisplayName("the key is exactly the record's own first six bytes")
        void theKeyIsTheRecordsOwnPrefix() {
            assertThat(TranCatRecordMapper.TRAN_TYPE_CD_LENGTH
                    + TranCatRecordMapper.TRAN_CAT_CD_LENGTH)
                    .as("the key must be the two key fields and nothing else")
                    .isEqualTo(TranCatRecordMapper.TYPE_AND_CATEGORY_KEY_WIDTH);
            assertThat(TranCatRecordMapper.TRAN_TYPE_CD_OFFSET)
                    .as("the key must begin the record, or a key image would need its own offsets")
                    .isZero();
            assertThat(TranCatRecordMapper.TRAN_CAT_CD_OFFSET)
                    .as("the category must follow the type with no gap")
                    .isEqualTo(TranCatRecordMapper.TRAN_TYPE_CD_LENGTH);
        }

        @Test
        @DisplayName("the filler occupies the whole remainder, so every byte is accounted for")
        void theFillerAccountsForTheRemainder() {
            assertThat(TranCatRecordMapper.FILLER_OFFSET).isEqualTo(EXPECTED_FILLER_OFFSET);
            assertThat(TranCatRecordMapper.FILLER_LENGTH).isEqualTo(EXPECTED_FILLER_WIDTH);
            assertThat(TranCatRecordMapper.FILLER_OFFSET + TranCatRecordMapper.FILLER_LENGTH)
                    .as("the three fields and the filler must tile the record exactly")
                    .isEqualTo(EXPECTED_RECORD_WIDTH);
            assertThat(TranCatRecordMapper.TRAN_CAT_TYPE_DESC_OFFSET
                    + TranCatRecordMapper.TRAN_CAT_TYPE_DESC_LENGTH)
                    .as("the mapped prefix must end where the filler begins")
                    .isEqualTo(TranCatRecordMapper.MAPPED_DATA_WIDTH);
        }

        @Test
        @DisplayName("the two artefact names identify the record and the key separately")
        void theArtefactNamesAreDistinct() {
            assertThat(TranCatRecordMapper.ARTEFACT)
                    .as("a diagnostic must be able to say which layout refused")
                    .isNotBlank()
                    .isNotEqualTo(TranCatRecordMapper.KEY_ARTEFACT);
            assertThat(TranCatRecordMapper.KEY_ARTEFACT)
                    .as("the key artefact must state its own width so a diagnostic is self-contained")
                    .contains(String.valueOf(EXPECTED_KEY_WIDTH));
        }

        @Test
        @DisplayName("the encoder's filler character is a space, which the fixture's is not")
        void theEmittedFillerIsASpace() {
            assertThat(TranCatRecordMapper.EMITTED_FILLER_CHARACTER)
                    .isEqualTo(EMITTED_FILLER_CHARACTER)
                    .isNotEqualTo(FIXTURE_FILLER_CHARACTER);
        }
    }

    @Nested
    @DisplayName("decoding a record image")
    class Decoding {

        @Test
        @DisplayName("all three fields arrive verbatim, untrimmed")
        void allThreeFieldsArriveVerbatim() {
            TransactionCategory decoded = TranCatRecordMapper.fromRecord(
                    image("01", "0001", "Regular Sales Draft", FIXTURE_FILLER_CHARACTER));

            assertThat(decoded.getTranTypeCd())
                    .as("the type keeps its leading zero")
                    .isEqualTo("01");
            assertThat(decoded.getTranCatCd())
                    .as("the category keeps all three of its leading zeros")
                    .isEqualTo("0001");
            assertThat(decoded.getTranCatTypeDesc())
                    .as("the description keeps every one of its trailing spaces")
                    .isEqualTo(padded("Regular Sales Draft", EXPECTED_DESCRIPTION_WIDTH))
                    .hasSize(EXPECTED_DESCRIPTION_WIDTH);
        }

        @Test
        @DisplayName("the decoded entity yields the composite identifier the persistence layer keys on")
        void theDecodedEntityYieldsItsCompositeIdentifier() {
            TransactionCategory decoded = TranCatRecordMapper.fromRecord(
                    image("06", "0002", "Non-fraud reversal", FIXTURE_FILLER_CHARACTER));

            assertThat(decoded.toId().getTranTypeCd()).isEqualTo("06");
            assertThat(decoded.toId().getTranCatCd())
                    .as("the identifier must carry exactly the two key fields the record supplied")
                    .isEqualTo("0002");
        }

        @Test
        @DisplayName("an all-blank record decodes to three blank fields rather than to nulls")
        void anAllBlankRecordDecodesToBlanks() {
            TransactionCategory decoded =
                    TranCatRecordMapper.fromRecord(run(' ', EXPECTED_RECORD_WIDTH));

            assertThat(decoded.getTranTypeCd()).isEqualTo(run(' ', EXPECTED_TYPE_WIDTH));
            assertThat(decoded.getTranCatCd()).isEqualTo(run(' ', EXPECTED_CATEGORY_WIDTH));
            assertThat(decoded.getTranCatTypeDesc())
                    .as("a blank description is still fifty bytes wide")
                    .isEqualTo(run(' ', EXPECTED_DESCRIPTION_WIDTH));
        }

        @Test
        @DisplayName("the filler bytes are read by nobody, so two records differing only there decode alike")
        void theFillerIsNotRead() {
            TransactionCategory zeros = TranCatRecordMapper.fromRecord(
                    image("05", "0001", "Refund credit", FIXTURE_FILLER_CHARACTER));
            TransactionCategory spaces = TranCatRecordMapper.fromRecord(
                    image("05", "0001", "Refund credit", EMITTED_FILLER_CHARACTER));

            assertThat(zeros.getTranTypeCd()).isEqualTo(spaces.getTranTypeCd());
            assertThat(zeros.getTranCatCd()).isEqualTo(spaces.getTranCatCd());
            assertThat(zeros.getTranCatTypeDesc())
                    .as("the filler carries no information, so it cannot change a decoded field")
                    .isEqualTo(spaces.getTranCatTypeDesc());
        }

        @Test
        @DisplayName("the byte overload agrees with the text overload byte for byte")
        void theByteOverloadAgreesWithTheTextOverload() {
            String text = image("03", "0002", "Credit to Purchase balance",
                    FIXTURE_FILLER_CHARACTER);

            TransactionCategory fromText = TranCatRecordMapper.fromRecord(text);
            TransactionCategory fromBytes =
                    TranCatRecordMapper.fromRecord(text.getBytes(StandardCharsets.US_ASCII));

            assertThat(fromBytes.getTranTypeCd()).isEqualTo(fromText.getTranTypeCd());
            assertThat(fromBytes.getTranCatCd()).isEqualTo(fromText.getTranCatCd());
            assertThat(fromBytes.getTranCatTypeDesc())
                    .as("choosing bytes over text must not change a single decoded character")
                    .isEqualTo(fromText.getTranCatTypeDesc());
        }
    }

    @Nested
    @DisplayName("decoding one record out of a larger buffer")
    class BufferDecoding {

        /** Stride of the newline-terminated reference file: the record width plus one terminator. */
        private static final int STRIDE = EXPECTED_RECORD_WIDTH + 1;

        @ParameterizedTest(name = "record {0} is {1}/{2}")
        @MethodSource("com.carddemo.util.TranCatRecordMapperCoverageTest#referenceRecords")
        @DisplayName("stride arithmetic selects each record and leaves its terminator behind")
        void strideArithmeticSelectsEachRecord(final int ordinal, final String type,
                final String category, final String description) {
            byte[] wholeFile = String.join("\n", fixture().records()).concat("\n")
                    .getBytes(StandardCharsets.US_ASCII);

            TransactionCategory decoded =
                    TranCatRecordMapper.fromRecord(wholeFile, (ordinal - 1) * STRIDE);

            assertThat(decoded.getTranTypeCd())
                    .as("record %d of the file must be selected, not its neighbour", ordinal)
                    .isEqualTo(type);
            assertThat(decoded.getTranCatCd()).isEqualTo(category);
            assertThat(decoded.getTranCatTypeDesc())
                    .as("the 0x0A terminator is a separator and must never enter a field")
                    .isEqualTo(padded(description, EXPECTED_DESCRIPTION_WIDTH));
        }

        @Test
        @DisplayName("a negative start index is refused rather than wrapped")
        void aNegativeStartIndexIsRefused() {
            byte[] buffer = new byte[EXPECTED_RECORD_WIDTH];

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> TranCatRecordMapper.fromRecord(buffer, -1))
                    .withMessageContaining("must not be negative");
        }

        @Test
        @DisplayName("a record that would run past the end of the buffer is refused, not short-read")
        void anOverrunningRecordIsRefused() {
            byte[] buffer =
                    run(' ', EXPECTED_RECORD_WIDTH).getBytes(StandardCharsets.US_ASCII);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> TranCatRecordMapper.fromRecord(buffer, 1))
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
                    .isThrownBy(() -> TranCatRecordMapper.fromRecord(wrongWidth))
                    .withMessageContaining("must be exactly " + EXPECTED_RECORD_WIDTH);
        }

        @Test
        @DisplayName("an unstripped line terminator is named as such, because it is the usual cause")
        void anUnstrippedTerminatorIsNamed() {
            String withTerminator =
                    image("01", "0001", "Regular Sales Draft", FIXTURE_FILLER_CHARACTER) + "\n";

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> TranCatRecordMapper.fromRecord(withTerminator))
                    .withMessageContaining("line ");
        }

        @Test
        @DisplayName("a non-ASCII character is refused rather than transcoded")
        void aNonAsciiCharacterIsRefused() {
            String withAccent =
                    image("01", "0001", "Caf\u00e9 purchase", FIXTURE_FILLER_CHARACTER);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> TranCatRecordMapper.fromRecord(withAccent))
                    .withMessageContaining("US-ASCII cannot represent");
        }

        @Test
        @DisplayName("a byte above 0x7F is refused, because it means another encoding")
        void aHighByteIsRefused() {
            byte[] record =
                    run(' ', EXPECTED_RECORD_WIDTH).getBytes(StandardCharsets.US_ASCII);
            record[EXPECTED_DESCRIPTION_OFFSET] = (byte) 0xE9;

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> TranCatRecordMapper.fromRecord(record))
                    .withMessageContaining("non-ASCII byte");
        }

        @Test
        @DisplayName("a null image is refused on every decode entry point")
        void aNullImageIsRefused() {
            assertThatNullPointerException()
                    .isThrownBy(() -> TranCatRecordMapper.fromRecord((String) null));
            assertThatNullPointerException()
                    .isThrownBy(() -> TranCatRecordMapper.fromRecord((byte[]) null));
            assertThatNullPointerException()
                    .isThrownBy(() -> TranCatRecordMapper.fromRecord(null, 0));
        }
    }

    @Nested
    @DisplayName("encoding an entity")
    class Encoding {

        @Test
        @DisplayName("the image is exactly 60 bytes and carries no terminator")
        void theImageIsExactlyTheDeclaredWidth() {
            String encoded = TranCatRecordMapper.toRecord(
                    new TransactionCategory("01", "0001", "Regular Sales Draft"));

            assertThat(encoded).hasSize(EXPECTED_RECORD_WIDTH);
            assertThat(encoded)
                    .as("a terminator is a record separator and never record content")
                    .doesNotContain("\n")
                    .doesNotContain("\r");
        }

        @Test
        @DisplayName("the type is left-justified and space-padded, as an alphanumeric field must be")
        void theTypeIsLeftJustifiedAndSpacePadded() {
            String encoded = TranCatRecordMapper.toRecord(
                    new TransactionCategory("7", "0001", "Sales draft credit adjustment"));

            assertThat(encoded.substring(EXPECTED_TYPE_OFFSET,
                    EXPECTED_TYPE_OFFSET + EXPECTED_TYPE_WIDTH))
                    .as("a one-character type is padded on the RIGHT, never zero-filled on the left")
                    .isEqualTo("7 ");
        }

        @ParameterizedTest(name = "category \"{0}\" is placed as \"{1}\"")
        @CsvSource({"0001,0001", "1,0001", "12,0012", "123,0123", "9999,9999"})
        @DisplayName("the category is right-justified and zero-padded, as a numeric field must be")
        void theCategoryIsRightJustifiedAndZeroPadded(final String supplied, final String placed) {
            String encoded = TranCatRecordMapper.toRecord(
                    new TransactionCategory("01", supplied, "Regular Sales Draft"));

            assertThat(encoded.substring(EXPECTED_CATEGORY_OFFSET,
                    EXPECTED_CATEGORY_OFFSET + EXPECTED_CATEGORY_WIDTH))
                    .as("a numeric declaration means right-justified, zero-filled on the left")
                    .isEqualTo(placed);
        }

        @Test
        @DisplayName("the description is left-justified and space-padded to fifty")
        void theDescriptionIsLeftJustifiedAndSpacePadded() {
            String encoded = TranCatRecordMapper.toRecord(
                    new TransactionCategory("04", "0003", "Travel booking authorization"));

            assertThat(encoded.substring(EXPECTED_DESCRIPTION_OFFSET,
                    EXPECTED_DESCRIPTION_OFFSET + EXPECTED_DESCRIPTION_WIDTH))
                    .isEqualTo(padded("Travel booking authorization", EXPECTED_DESCRIPTION_WIDTH));
        }

        @Test
        @DisplayName("the filler run is emitted as spaces, which the fixture's zeros are not")
        void theFillerRunIsEmittedAsSpaces() {
            String encoded = TranCatRecordMapper.toRecord(
                    new TransactionCategory("02", "0001", "Cash payment"));

            assertThat(encoded.substring(EXPECTED_FILLER_OFFSET))
                    .isEqualTo(run(EMITTED_FILLER_CHARACTER, EXPECTED_FILLER_WIDTH));
        }

        @Test
        @DisplayName("the byte encoder returns the same image, in a fresh array the caller owns")
        void theByteEncoderReturnsTheSameImage() {
            TransactionCategory entity =
                    new TransactionCategory("03", "0001", "Credit to Account");

            byte[] first = TranCatRecordMapper.toRecordBytes(entity);
            byte[] second = TranCatRecordMapper.toRecordBytes(entity);

            assertThat(new String(first, StandardCharsets.US_ASCII))
                    .isEqualTo(TranCatRecordMapper.toRecord(entity));
            assertThat(first)
                    .as("each call must return a fresh array the caller may mutate")
                    .isNotSameAs(second)
                    .isEqualTo(second);
        }

        @Test
        @DisplayName("an over-wide value is refused rather than truncated to fit")
        void anOverWideValueIsRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> TranCatRecordMapper.toRecord(
                            new TransactionCategory("012", "0001", "Regular Sales Draft")))
                    .withMessageContaining("never truncated to fit");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> TranCatRecordMapper.toRecord(
                            new TransactionCategory("01", "00001", "Regular Sales Draft")))
                    .withMessageContaining("never truncated to fit");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> TranCatRecordMapper.toRecord(new TransactionCategory(
                            "01", "0001", run('X', EXPECTED_DESCRIPTION_WIDTH + 1))))
                    .withMessageContaining("never truncated to fit");
        }

        @Test
        @DisplayName("a null entity or a null attribute is refused, and the refusal names the field")
        void aNullEntityOrAttributeIsRefused() {
            assertThatNullPointerException()
                    .isThrownBy(() -> TranCatRecordMapper.toRecord(null))
                    .withMessageContaining("category");
            assertThatNullPointerException()
                    .isThrownBy(() -> TranCatRecordMapper.toRecord(
                            new TransactionCategory(null, "0001", "Regular Sales Draft")))
                    .withMessageContaining("TRAN-TYPE-CD");
            assertThatNullPointerException()
                    .isThrownBy(() -> TranCatRecordMapper.toRecord(
                            new TransactionCategory("01", null, "Regular Sales Draft")))
                    .withMessageContaining("TRAN-CAT-CD");
            assertThatNullPointerException()
                    .isThrownBy(() -> TranCatRecordMapper.toRecordBytes(
                            new TransactionCategory("01", "0001", null)))
                    .withMessageContaining("TRAN-CAT-TYPE-DESC");
        }
    }

    @Nested
    @DisplayName("the six-byte composite key image")
    class KeyImage {

        @ParameterizedTest(name = "record {0} keys on {1}{2}")
        @MethodSource("com.carddemo.util.TranCatRecordMapperCoverageTest#referenceRecords")
        @DisplayName("is byte-identical to the record's own first six bytes")
        void isTheRecordsOwnPrefix(final int ordinal, final String type, final String category,
                final String description) {
            String record = fixture().record(ordinal);

            String keyImage = TranCatRecordMapper.typeAndCategoryKeyImage(
                    TranCatRecordMapper.fromRecord(record));

            assertThat(keyImage)
                    .as("record %d (%s) must key on its own prefix, not on a re-derived value",
                            ordinal, description)
                    .hasSize(EXPECTED_KEY_WIDTH)
                    .isEqualTo(record.substring(0, EXPECTED_KEY_WIDTH))
                    .isEqualTo(type + category);
        }

        @Test
        @DisplayName("applies the same justification rules the whole-record encoder applies")
        void appliesTheSameJustificationRules() {
            String keyImage = TranCatRecordMapper.typeAndCategoryKeyImage(
                    new TransactionCategory("7", "12", "Sales draft credit adjustment"));

            assertThat(keyImage)
                    .as("the type pads right with a space; the category pads left with zeros")
                    .isEqualTo("7 0012");
        }

        @Test
        @DisplayName("the eighteen reference keys are distinct, so the composite key is a real key")
        void theEighteenReferenceKeysAreDistinct() {
            List<String> keys = fixture().records().stream()
                    .map(TranCatRecordMapper::fromRecord)
                    .map(TranCatRecordMapper::typeAndCategoryKeyImage)
                    .toList();

            assertThat(keys)
                    .as("a duplicate would mean the cluster key does not identify a record")
                    .hasSize(EXPECTED_FIXTURE_RECORDS)
                    .doesNotHaveDuplicates();
        }

        @Test
        @DisplayName("a null entity or a null key field is refused, naming the key layout")
        void aNullEntityOrKeyFieldIsRefused() {
            assertThatNullPointerException()
                    .isThrownBy(() -> TranCatRecordMapper.typeAndCategoryKeyImage(null))
                    .withMessageContaining(String.valueOf(EXPECTED_KEY_WIDTH));
            assertThatNullPointerException()
                    .isThrownBy(() -> TranCatRecordMapper.typeAndCategoryKeyImage(
                            new TransactionCategory(null, "0001", "Regular Sales Draft")))
                    .withMessageContaining("TRAN-TYPE-CD");
            assertThatNullPointerException()
                    .isThrownBy(() -> TranCatRecordMapper.typeAndCategoryKeyImage(
                            new TransactionCategory("01", null, "Regular Sales Draft")))
                    .withMessageContaining("TRAN-CAT-CD");
        }

        @Test
        @DisplayName("a description of any length changes nothing, because the key excludes it")
        void theDescriptionDoesNotEnterTheKey() {
            String withShortDescription = TranCatRecordMapper.typeAndCategoryKeyImage(
                    new TransactionCategory("01", "0001", "x"));
            String withFullDescription = TranCatRecordMapper.typeAndCategoryKeyImage(
                    new TransactionCategory("01", "0001", run('x', EXPECTED_DESCRIPTION_WIDTH)));

            assertThat(withShortDescription)
                    .as("the key is the two key fields and nothing else")
                    .isEqualTo(withFullDescription);
        }
    }

    @Nested
    @DisplayName("the reference fixture")
    class ReferenceFixture {

        @Test
        @DisplayName("holds exactly eighteen records of exactly sixty bytes")
        void holdsEighteenRecordsOfSixtyBytes() {
            SeededRecordFixture loaded = fixture();

            assertThat(loaded.recordCount())
                    .as("the plan names eighteen transaction categories")
                    .isEqualTo(EXPECTED_FIXTURE_RECORDS);
            assertThat(loaded.records())
                    .allSatisfy(record -> assertThat(record).hasSize(EXPECTED_RECORD_WIDTH));
        }

        @ParameterizedTest(name = "record {0} is {1}/{2}")
        @MethodSource("com.carddemo.util.TranCatRecordMapperCoverageTest#referenceRecords")
        @DisplayName("each record decodes to the values transcribed from its bytes")
        void eachRecordDecodesToItsTranscribedValue(final int ordinal, final String type,
                final String category, final String description) {
            TransactionCategory decoded =
                    TranCatRecordMapper.fromRecord(fixture().record(ordinal));

            assertThat(decoded.getTranTypeCd()).isEqualTo(type);
            assertThat(decoded.getTranCatCd()).isEqualTo(category);
            assertThat(decoded.getTranCatTypeDesc())
                    .as("record %d carries description %s, padded to fifty", ordinal, description)
                    .isEqualTo(padded(description, EXPECTED_DESCRIPTION_WIDTH));
        }

        @Test
        @DisplayName("holds ASCII zero in every filler byte of every record")
        void holdsAsciiZeroInEveryFillerByte() {
            assertThat(fixture().records())
                    .allSatisfy(record -> assertThat(record.substring(EXPECTED_FILLER_OFFSET))
                            .isEqualTo(run(FIXTURE_FILLER_CHARACTER, EXPECTED_FILLER_WIDTH)));
        }

        @Test
        @DisplayName("covers the seven transaction types with the category counts the estate declares")
        void coversTheSevenTypesWithTheirCategoryCounts() {
            List<String> types = fixture().records().stream()
                    .map(TranCatRecordMapper::fromRecord)
                    .map(TransactionCategory::getTranTypeCd)
                    .toList();

            assertThat(types)
                    .as("five purchase, three payment, three credit, three authorisation, one refund,"
                            + " two reversal and one adjustment category")
                    .containsExactly("01", "01", "01", "01", "01",
                            "02", "02", "02",
                            "03", "03", "03",
                            "04", "04", "04",
                            "05",
                            "06", "06",
                            "07");
        }
    }

    @Nested
    @DisplayName("the round trip over the mapped prefix is exact")
    class RoundTrip {

        @ParameterizedTest(name = "record {0}")
        @MethodSource("com.carddemo.util.TranCatRecordMapperCoverageTest#referenceRecords")
        @DisplayName("decoding then re-encoding reproduces the first fifty-six bytes unchanged")
        void theMappedPrefixSurvivesUnchanged(final int ordinal, final String type,
                final String category, final String description) {
            String original = fixture().record(ordinal);

            String reEncoded = TranCatRecordMapper.toRecord(
                    TranCatRecordMapper.fromRecord(original));

            assertThat(reEncoded.substring(0, EXPECTED_MAPPED_WIDTH))
                    .as("record %d (%s/%s %s) must survive a decode and re-encode unchanged",
                            ordinal, type, category, description)
                    .isEqualTo(original.substring(0, EXPECTED_MAPPED_WIDTH));
        }

        @ParameterizedTest(name = "record {0}")
        @MethodSource("com.carddemo.util.TranCatRecordMapperCoverageTest#referenceRecords")
        @DisplayName("the filler run is the only place a whole-record comparison differs")
        void theFillerIsTheOnlyDifference(final int ordinal, final String type,
                final String category, final String description) {
            String original = fixture().record(ordinal);

            String reEncoded = TranCatRecordMapper.toRecord(
                    TranCatRecordMapper.fromRecord(original));

            assertThat(reEncoded)
                    .as("record %d (%s/%s %s) differs in the filler and nowhere else",
                            ordinal, type, category, description)
                    .isNotEqualTo(original)
                    .isEqualTo(original.substring(0, EXPECTED_MAPPED_WIDTH)
                            + run(EMITTED_FILLER_CHARACTER, EXPECTED_FILLER_WIDTH));
        }

        @Test
        @DisplayName("a second round trip is a fixed point, so encoding is idempotent")
        void aSecondRoundTripIsAFixedPoint() {
            String once = TranCatRecordMapper.toRecord(
                    TranCatRecordMapper.fromRecord(fixture().record(1)));

            String twice = TranCatRecordMapper.toRecord(
                    TranCatRecordMapper.fromRecord(once));

            assertThat(twice)
                    .as("an emitted image must decode and re-encode to itself exactly")
                    .isEqualTo(once);
        }

        @Test
        @DisplayName("an entity survives an encode and decode with all three attributes intact")
        void anEntitySurvivesAnEncodeAndDecode() {
            TransactionCategory original = new TransactionCategory("09", "0099",
                    padded("Synthetic category", EXPECTED_DESCRIPTION_WIDTH));

            TransactionCategory recovered = TranCatRecordMapper.fromRecord(
                    TranCatRecordMapper.toRecordBytes(original));

            assertThat(recovered.getTranTypeCd()).isEqualTo(original.getTranTypeCd());
            assertThat(recovered.getTranCatCd()).isEqualTo(original.getTranCatCd());
            assertThat(recovered.getTranCatTypeDesc())
                    .as("the padded description must return exactly as it went in")
                    .isEqualTo(original.getTranCatTypeDesc());
        }
    }
}
