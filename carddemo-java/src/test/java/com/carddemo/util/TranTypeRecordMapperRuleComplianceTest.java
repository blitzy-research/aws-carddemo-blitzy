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
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.carddemo.domain.TransactionType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * Unit tests for {@link TranTypeRecordMapper}, which maps the sixty-byte {@code TRAN-TYPE-RECORD}
 * declared by {@code app/cpy/CVTRA03Y.cpy} onto {@link TransactionType} and back.
 *
 * <p><strong>The fixture is the authority, not a convenience.</strong> Every decode assertion below is
 * made against the real bytes of {@code fixtures/input/trantype.txt}, which carries seven records at a
 * stride of sixty-one — sixty bytes of record plus one line terminator that is a separator and never
 * record content. Gate 1 is a byte-equality gate, so a test that constructed its own input would prove
 * only that the mapper is self-consistent.
 *
 * <p><strong>The emitted filler deliberately differs from the fixture's.</strong> The sample file
 * carries eight ASCII zeros in the trailing filler; the mapper emits eight spaces, because
 * {@code TRAN_TYPE_FILLER_CHARACTER} is a space and COBOL {@code FILLER} has no defined content. A
 * decode-then-encode cycle is therefore byte-identical across the fifty-two mapped bytes and
 * deliberately normalising across the final eight. That distinction is asserted explicitly rather than
 * hidden behind a whole-record comparison that would either fail or be weakened to make it pass.
 */
@DisplayName("TranTypeRecordMapper - the sixty-byte CVTRA03Y record contract")
class TranTypeRecordMapperRuleComplianceTest {

    /** The fixture whose bytes are the decode authority for this layout. */
    private static final Path FIXTURE = Path.of("src/test/resources/fixtures/input/trantype.txt");

    /** The record count the fixture carries, as measured from its byte length. */
    private static final int FIXTURE_RECORD_COUNT = 7;

    /** The stride between fixture records: the record width plus one line terminator. */
    private static final int FIXTURE_STRIDE = TranTypeRecordMapper.TRAN_TYPE_RECORD_LENGTH + 1;

    /**
     * Reads one record image from the fixture by ordinal, excluding the line terminator.
     *
     * @param ordinal the zero-based record position
     * @return the sixty-byte record image
     * @throws IOException when the fixture cannot be read
     */
    private static String fixtureRecord(final int ordinal) throws IOException {
        final byte[] file = Files.readAllBytes(FIXTURE);
        return new String(file, ordinal * FIXTURE_STRIDE,
                TranTypeRecordMapper.TRAN_TYPE_RECORD_LENGTH, StandardCharsets.US_ASCII);
    }

    /**
     * Reads every record image the fixture carries.
     *
     * @return the seven record images in file order
     * @throws IOException when the fixture cannot be read
     */
    private static List<String> allFixtureRecords() throws IOException {
        final List<String> records = new ArrayList<>();
        for (int ordinal = 0; ordinal < FIXTURE_RECORD_COUNT; ordinal++) {
            records.add(fixtureRecord(ordinal));
        }
        return records;
    }

    /**
     * Pads a value on the right with spaces to a declared width, the way a COBOL alphanumeric field is
     * presented.
     *
     * @param value the value to pad
     * @param width the declared field width
     * @return the value padded to the width
     */
    private static String spacePadded(final String value, final int width) {
        return value + " ".repeat(width - value.length());
    }

    /**
     * Assembles a well-formed record image from a code and a description.
     *
     * @param code the two-character transaction type code
     * @param description the description, padded to its declared width
     * @return a sixty-byte record image with space filler
     */
    private static String recordImage(final String code, final String description) {
        return spacePadded(code, TranTypeRecordMapper.TRAN_TYPE_CODE_LENGTH)
                + spacePadded(description, TranTypeRecordMapper.TRAN_TYPE_DESCRIPTION_LENGTH)
                + " ".repeat(TranTypeRecordMapper.TRAN_TYPE_FILLER_LENGTH);
    }

    // =============================================================================================

    @Nested
    @DisplayName("the published layout")
    class ThePublishedLayout {

        @Test
        @DisplayName("the record is sixty bytes, of which fifty-two are mapped and eight are trailing "
                + "filler, matching the CVTRA03Y declaration")
        void theRecordIsSixtyBytesOfWhichFiftyTwoAreMapped() {
            assertThat(TranTypeRecordMapper.TRAN_TYPE_RECORD_LENGTH).isEqualTo(60);
            assertThat(TranTypeRecordMapper.TRAN_TYPE_MAPPED_LENGTH).isEqualTo(52);
            assertThat(TranTypeRecordMapper.TRAN_TYPE_FILLER_LENGTH).isEqualTo(8);
        }

        @Test
        @DisplayName("the two fields are contiguous from offset zero and the filler begins exactly "
                + "where the mapped prefix ends")
        void theFieldsAreContiguousFromOffsetZero() {
            assertThat(TranTypeRecordMapper.TRAN_TYPE_CODE_OFFSET).isZero();
            assertThat(TranTypeRecordMapper.TRAN_TYPE_DESCRIPTION_OFFSET)
                    .isEqualTo(TranTypeRecordMapper.TRAN_TYPE_CODE_OFFSET
                            + TranTypeRecordMapper.TRAN_TYPE_CODE_LENGTH);
            assertThat(TranTypeRecordMapper.TRAN_TYPE_FILLER_OFFSET)
                    .isEqualTo(TranTypeRecordMapper.TRAN_TYPE_DESCRIPTION_OFFSET
                            + TranTypeRecordMapper.TRAN_TYPE_DESCRIPTION_LENGTH)
                    .isEqualTo(TranTypeRecordMapper.TRAN_TYPE_MAPPED_LENGTH);
        }

        @Test
        @DisplayName("the parts sum to the whole, so no byte of the record is unaccounted for")
        void thePartsSumToTheWhole() {
            assertThat(TranTypeRecordMapper.TRAN_TYPE_MAPPED_LENGTH
                    + TranTypeRecordMapper.TRAN_TYPE_FILLER_LENGTH)
                    .isEqualTo(TranTypeRecordMapper.TRAN_TYPE_RECORD_LENGTH);
        }

        @Test
        @DisplayName("the code is two characters and the description fifty, matching TRAN-TYPE PIC "
                + "X(02) and TRAN-TYPE-DESC PIC X(50)")
        void theFieldWidthsMatchTheCopybook() {
            assertThat(TranTypeRecordMapper.TRAN_TYPE_CODE_LENGTH).isEqualTo(2);
            assertThat(TranTypeRecordMapper.TRAN_TYPE_DESCRIPTION_LENGTH).isEqualTo(50);
        }

        @Test
        @DisplayName("the emitted filler character is a space, which is why an encode cycle normalises "
                + "the sample file's numeric filler")
        void theEmittedFillerCharacterIsASpace() {
            assertThat(TranTypeRecordMapper.TRAN_TYPE_FILLER_CHARACTER).isEqualTo(' ');
        }

        @Test
        @DisplayName("the mapper is a final class that cannot be instantiated, so the layout has one "
                + "definition and no per-instance state")
        void theMapperCannotBeInstantiated() throws NoSuchMethodException {
            assertThat(Modifier.isFinal(TranTypeRecordMapper.class.getModifiers())).isTrue();

            final Constructor<TranTypeRecordMapper> constructor =
                    TranTypeRecordMapper.class.getDeclaredConstructor();
            assertThat(Modifier.isPrivate(constructor.getModifiers())).isTrue();
            constructor.setAccessible(true);

            assertThatExceptionOfType(InvocationTargetException.class)
                    .isThrownBy(constructor::newInstance)
                    .withCauseInstanceOf(AssertionError.class);
        }
    }

    // =============================================================================================

    @Nested
    @DisplayName("decoding the real fixture")
    class DecodingTheRealFixture {

        @Test
        @DisplayName("the fixture carries seven records at a stride of sixty-one, sixty bytes of record "
                + "plus one terminator that is a separator and never content")
        void theFixtureCarriesSevenRecordsAtAStrideOfSixtyOne() throws IOException {
            assertThat(Files.readAllBytes(FIXTURE))
                    .hasSize(FIXTURE_RECORD_COUNT * FIXTURE_STRIDE);
            assertThat(FIXTURE_STRIDE).isEqualTo(61);
        }

        @Test
        @DisplayName("the first fixture record decodes to the purchase type with its description "
                + "space-padded to fifty, because a fixed-width field is never trimmed")
        void theFirstFixtureRecordDecodesToThePurchaseType() throws IOException {
            final TransactionType decoded =
                    TranTypeRecordMapper.fromRecord(fixtureRecord(0));

            assertThat(decoded.getTranType()).isEqualTo("01");
            assertThat(decoded.getTranTypeDesc())
                    .isEqualTo(spacePadded("Purchase",
                            TranTypeRecordMapper.TRAN_TYPE_DESCRIPTION_LENGTH))
                    .hasSize(TranTypeRecordMapper.TRAN_TYPE_DESCRIPTION_LENGTH);
            assertThat(decoded.getTranTypeDesc().strip()).isEqualTo("Purchase");
        }

        @Test
        @DisplayName("every one of the seven fixture records decodes with a two-character code and a "
                + "fifty-character description")
        void everyFixtureRecordDecodesToTheDeclaredWidths() throws IOException {
            for (final String image : allFixtureRecords()) {
                final TransactionType decoded = TranTypeRecordMapper.fromRecord(image);

                assertThat(decoded.getTranType())
                        .hasSize(TranTypeRecordMapper.TRAN_TYPE_CODE_LENGTH);
                assertThat(decoded.getTranTypeDesc())
                        .hasSize(TranTypeRecordMapper.TRAN_TYPE_DESCRIPTION_LENGTH);
                assertThat(decoded.getTranTypeDesc().strip()).isNotEmpty();
            }
        }

        @Test
        @DisplayName("the seven codes are distinct, so the fixture is a valid reference table and not "
                + "seven copies of one row")
        void theSevenCodesAreDistinct() throws IOException {
            final List<String> codes = new ArrayList<>();
            for (final String image : allFixtureRecords()) {
                codes.add(TranTypeRecordMapper.fromRecord(image).getTranType());
            }

            assertThat(codes).hasSize(FIXTURE_RECORD_COUNT).doesNotHaveDuplicates();
        }

        @Test
        @DisplayName("a byte-array image decodes identically to the equivalent string image, because "
                + "both funnel through the same reader")
        void aByteArrayImageDecodesIdenticallyToAStringImage() throws IOException {
            final String image = fixtureRecord(0);

            final TransactionType fromString = TranTypeRecordMapper.fromRecord(image);
            final TransactionType fromBytes = TranTypeRecordMapper.fromRecord(
                    image.getBytes(StandardCharsets.US_ASCII));

            assertThat(fromBytes.getTranType()).isEqualTo(fromString.getTranType());
            assertThat(fromBytes.getTranTypeDesc()).isEqualTo(fromString.getTranTypeDesc());
        }

        @Test
        @DisplayName("an offset decode reads a record out of the middle of a buffer, which is how a "
                + "blocked sequential read presents its records")
        void anOffsetDecodeReadsARecordOutOfABuffer() throws IOException {
            final byte[] file = Files.readAllBytes(FIXTURE);

            for (int ordinal = 0; ordinal < FIXTURE_RECORD_COUNT; ordinal++) {
                final TransactionType fromBuffer =
                        TranTypeRecordMapper.fromRecord(file, ordinal * FIXTURE_STRIDE);

                assertThat(fromBuffer.getTranType())
                        .isEqualTo(TranTypeRecordMapper.fromRecord(fixtureRecord(ordinal))
                                .getTranType());
            }
        }
    }

    // =============================================================================================

    @Nested
    @DisplayName("encoding and the round trip")
    class EncodingAndTheRoundTrip {

        @Test
        @DisplayName("the fifty-two mapped bytes of every fixture record survive a decode and encode "
                + "cycle unchanged, which is the byte-parity contract Gate 1 measures")
        void theMappedBytesSurviveTheRoundTripUnchanged() throws IOException {
            for (final String image : allFixtureRecords()) {
                final String encoded =
                        TranTypeRecordMapper.toRecord(TranTypeRecordMapper.fromRecord(image));

                assertThat(encoded.substring(0, TranTypeRecordMapper.TRAN_TYPE_MAPPED_LENGTH))
                        .isEqualTo(image.substring(0,
                                TranTypeRecordMapper.TRAN_TYPE_MAPPED_LENGTH));
            }
        }

        @Test
        @DisplayName("the eight filler bytes are normalised to spaces, because the sample file carries "
                + "zeros there and COBOL FILLER has no defined content")
        void theFillerBytesAreNormalisedToSpaces() throws IOException {
            final String image = fixtureRecord(0);
            final String encoded =
                    TranTypeRecordMapper.toRecord(TranTypeRecordMapper.fromRecord(image));

            assertThat(image.substring(TranTypeRecordMapper.TRAN_TYPE_FILLER_OFFSET))
                    .isEqualTo("00000000");
            assertThat(encoded.substring(TranTypeRecordMapper.TRAN_TYPE_FILLER_OFFSET))
                    .isEqualTo(" ".repeat(TranTypeRecordMapper.TRAN_TYPE_FILLER_LENGTH));
            assertThat(encoded).isNotEqualTo(image);
        }

        @Test
        @DisplayName("a record whose filler is already spaces round-trips byte for byte, proving the "
                + "only difference is the filler convention and nothing else")
        void aSpaceFilledRecordRoundTripsByteForByte() {
            final String image = recordImage("01", "Purchase");

            assertThat(TranTypeRecordMapper.toRecord(TranTypeRecordMapper.fromRecord(image)))
                    .isEqualTo(image);
        }

        @Test
        @DisplayName("the encoded image is always exactly sixty bytes, whatever the length of the "
                + "supplied description")
        void theEncodedImageIsAlwaysSixtyBytes() {
            assertThat(TranTypeRecordMapper.toRecord(new TransactionType("01", "")))
                    .hasSize(TranTypeRecordMapper.TRAN_TYPE_RECORD_LENGTH);
            assertThat(TranTypeRecordMapper.toRecord(new TransactionType("01", "X")))
                    .hasSize(TranTypeRecordMapper.TRAN_TYPE_RECORD_LENGTH);
            assertThat(TranTypeRecordMapper.toRecord(new TransactionType("01",
                    "X".repeat(TranTypeRecordMapper.TRAN_TYPE_DESCRIPTION_LENGTH))))
                    .hasSize(TranTypeRecordMapper.TRAN_TYPE_RECORD_LENGTH);
        }

        @Test
        @DisplayName("both fields are left-justified and space-padded, because both are alphanumeric "
                + "rather than numeric")
        void bothFieldsAreLeftJustifiedAndSpacePadded() {
            final String encoded = TranTypeRecordMapper.toRecord(new TransactionType("1", "Buy"));

            assertThat(encoded.substring(TranTypeRecordMapper.TRAN_TYPE_CODE_OFFSET,
                    TranTypeRecordMapper.TRAN_TYPE_CODE_LENGTH)).isEqualTo("1 ");
            assertThat(encoded.substring(TranTypeRecordMapper.TRAN_TYPE_DESCRIPTION_OFFSET,
                    TranTypeRecordMapper.TRAN_TYPE_DESCRIPTION_OFFSET + 4)).isEqualTo("Buy ");
        }

        @Test
        @DisplayName("the byte-array encoding is the US-ASCII encoding of the string encoding, so the "
                + "two emitters cannot diverge")
        void theByteEncodingMatchesTheStringEncoding() throws IOException {
            final TransactionType decoded = TranTypeRecordMapper.fromRecord(fixtureRecord(0));

            assertThat(TranTypeRecordMapper.toRecordBytes(decoded))
                    .isEqualTo(TranTypeRecordMapper.toRecord(decoded)
                            .getBytes(StandardCharsets.US_ASCII))
                    .hasSize(TranTypeRecordMapper.TRAN_TYPE_RECORD_LENGTH);
        }

        @Test
        @DisplayName("a decoded entity re-encodes to the same image every time, so encoding carries no "
                + "hidden state between calls")
        void encodingIsRepeatable() throws IOException {
            final TransactionType decoded = TranTypeRecordMapper.fromRecord(fixtureRecord(3));

            assertThat(TranTypeRecordMapper.toRecord(decoded))
                    .isEqualTo(TranTypeRecordMapper.toRecord(decoded));
        }
    }

    // =============================================================================================

    @Nested
    @DisplayName("refusals, and what the refusal is allowed to say")
    class RefusalsAndTheirDiagnostics {

        @ParameterizedTest
        @ValueSource(ints = {0, 1, 59, 61, 120})
        @DisplayName("an image of any width other than sixty is refused, because a fixed-width record "
                + "is never padded or truncated to fit")
        void anImageOfTheWrongWidthIsRefused(final int width) {
            final String wrongWidth = "X".repeat(width);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> TranTypeRecordMapper.fromRecord(wrongWidth));
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> TranTypeRecordMapper.fromRecord(
                            wrongWidth.getBytes(StandardCharsets.US_ASCII)));
        }

        @Test
        @DisplayName("a sixty-one byte image is refused, which is what an unstripped line terminator "
                + "from the sample file looks like")
        void anUnstrippedTerminatorIsRefused() throws IOException {
            final String withTerminator = fixtureRecord(0) + "\n";

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> TranTypeRecordMapper.fromRecord(withTerminator));
        }

        @Test
        @DisplayName("a null image and a null buffer are both refused before any slicing is attempted")
        void aNullImageIsRefused() {
            assertThatNullPointerException()
                    .isThrownBy(() -> TranTypeRecordMapper.fromRecord((String) null));
            assertThatNullPointerException()
                    .isThrownBy(() -> TranTypeRecordMapper.fromRecord((byte[]) null));
            assertThatNullPointerException()
                    .isThrownBy(() -> TranTypeRecordMapper.fromRecord(null, 0));
        }

        @Test
        @DisplayName("an offset that would read past the end of the buffer is refused rather than "
                + "silently returning a short record")
        void anOffsetPastTheEndOfTheBufferIsRefused() {
            final byte[] oneRecord =
                    recordImage("01", "Purchase").getBytes(StandardCharsets.US_ASCII);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> TranTypeRecordMapper.fromRecord(oneRecord, 1));
        }

        @Test
        @DisplayName("a non-ASCII byte is refused, because the legacy record set is single-byte and a "
                + "multi-byte character would silently shift every field after it")
        void aNonAsciiCharacterIsRefused() {
            final String withNonAscii = "01" + spacePadded("Purchas\u00e9",
                    TranTypeRecordMapper.TRAN_TYPE_DESCRIPTION_LENGTH)
                    + " ".repeat(TranTypeRecordMapper.TRAN_TYPE_FILLER_LENGTH);

            assertThat(withNonAscii).hasSize(TranTypeRecordMapper.TRAN_TYPE_RECORD_LENGTH);
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> TranTypeRecordMapper.fromRecord(withNonAscii));
        }

        @Test
        @DisplayName("a null entity is refused when a record image is assembled")
        void aNullEntityIsRefusedOnEncode() {
            assertThatNullPointerException()
                    .isThrownBy(() -> TranTypeRecordMapper.toRecord(null));
            assertThatNullPointerException()
                    .isThrownBy(() -> TranTypeRecordMapper.toRecordBytes(null));
        }

        @Test
        @DisplayName("an absent code and an absent description are each refused by name, because a "
                + "fixed-width field has no representation for an absent value")
        void anAbsentMappedFieldIsRefusedByName() {
            assertThatNullPointerException()
                    .isThrownBy(() -> TranTypeRecordMapper.toRecord(
                            new TransactionType(null, "Purchase")))
                    .withMessageContaining("tranType");
            assertThatNullPointerException()
                    .isThrownBy(() -> TranTypeRecordMapper.toRecord(
                            new TransactionType("01", null)))
                    .withMessageContaining("tranTypeDesc");
        }

        @Test
        @DisplayName("a value wider than its field is refused rather than truncated, because a "
                + "truncated value leaves the record the right width and the wrong content")
        void anOverWideValueIsRefusedRatherThanTruncated() {
            final String tooLongDescription =
                    "X".repeat(TranTypeRecordMapper.TRAN_TYPE_DESCRIPTION_LENGTH + 1);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> TranTypeRecordMapper.toRecord(
                            new TransactionType("01", tooLongDescription)))
                    .withMessageContaining("never truncated to fit");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> TranTypeRecordMapper.toRecord(
                            new TransactionType("001", "Purchase")));
        }

        @Test
        @DisplayName("no refusal diagnostic echoes the record image, so a malformed reference record "
                + "cannot inject its own content into a log line")
        void noRefusalDiagnosticEchoesTheRecordImage() {
            final String hostile = spacePadded("9\r\nFAKE LOG LINE", 60);

            assertThat(hostile).hasSize(TranTypeRecordMapper.TRAN_TYPE_RECORD_LENGTH);

            final TransactionType decoded = TranTypeRecordMapper.fromRecord(hostile);
            assertThat(decoded.getTranType()).isEqualTo("9\r");

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> TranTypeRecordMapper.fromRecord(hostile + " "))
                    .satisfies(refusal -> {
                        assertThat(refusal.getMessage()).doesNotContain("FAKE LOG LINE");
                        assertThat(refusal.getMessage()).doesNotContain("\n");
                        assertThat(refusal.getMessage()).doesNotContain("\r");
                    });
        }
    }
}
