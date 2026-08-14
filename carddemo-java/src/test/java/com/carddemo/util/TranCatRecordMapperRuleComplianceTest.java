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

import com.carddemo.domain.TransactionCategory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * Unit tests for {@link TranCatRecordMapper}, which maps the sixty-byte {@code TRAN-CAT-RECORD}
 * declared by {@code app/cpy/CVTRA04Y.cpy} onto {@link TransactionCategory} and back.
 *
 * <p><strong>This layout has a composite key, and the key has its own image.</strong> The first six
 * bytes are the transaction type and category codes together, which is why the mapper publishes a
 * separate key-image emitter alongside the whole-record one. The key emitter shares the two field
 * offsets with the record emitter, so a drift between the two would break every keyed lookup while
 * leaving whole-record encoding intact; the tests therefore assert that the key image is a prefix of
 * the record image rather than merely that it is six bytes long.
 *
 * <p><strong>The category code is numeric and the type code is not.</strong> The record emitter writes
 * the category code right-justified and zero-padded but the type code left-justified and space-padded,
 * a distinction that only shows up when a value is narrower than its field. Both are asserted.
 *
 * <p><strong>The emitted filler deliberately differs from the fixture's.</strong> The sample file
 * carries four ASCII zeros in the trailing filler; the mapper emits four spaces. A decode-then-encode
 * cycle is therefore byte-identical across the fifty-six mapped bytes and normalising across the final
 * four, and that is asserted explicitly rather than smoothed over.
 */
@DisplayName("TranCatRecordMapper - the sixty-byte CVTRA04Y record and its six-byte key")
class TranCatRecordMapperRuleComplianceTest {

    /** The fixture whose bytes are the decode authority for this layout. */
    private static final Path FIXTURE = Path.of("src/test/resources/fixtures/input/trancatg.txt");

    /** The record count the fixture carries, as measured from its byte length. */
    private static final int FIXTURE_RECORD_COUNT = 18;

    /** The stride between fixture records: the record width plus one line terminator. */
    private static final int FIXTURE_STRIDE = TranCatRecordMapper.RECORD_WIDTH + 1;

    /**
     * Reads one record image from the fixture by ordinal, excluding the line terminator.
     *
     * @param ordinal the zero-based record position
     * @return the sixty-byte record image
     * @throws IOException when the fixture cannot be read
     */
    private static String fixtureRecord(final int ordinal) throws IOException {
        final byte[] file = Files.readAllBytes(FIXTURE);
        return new String(file, ordinal * FIXTURE_STRIDE, TranCatRecordMapper.RECORD_WIDTH,
                StandardCharsets.US_ASCII);
    }

    /**
     * Reads every record image the fixture carries.
     *
     * @return the eighteen record images in file order
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
     * Pads a value on the right with spaces to a declared width.
     *
     * @param value the value to pad
     * @param width the declared field width
     * @return the value padded to the width
     */
    private static String spacePadded(final String value, final int width) {
        return value + " ".repeat(width - value.length());
    }

    /**
     * Assembles a well-formed record image with space filler.
     *
     * @param typeCode the two-character transaction type code
     * @param categoryCode the four-character category code
     * @param description the description, padded to its declared width
     * @return a sixty-byte record image
     */
    private static String recordImage(final String typeCode, final String categoryCode,
            final String description) {
        return spacePadded(typeCode, TranCatRecordMapper.TRAN_TYPE_CD_LENGTH)
                + spacePadded(categoryCode, TranCatRecordMapper.TRAN_CAT_CD_LENGTH)
                + spacePadded(description, TranCatRecordMapper.TRAN_CAT_TYPE_DESC_LENGTH)
                + " ".repeat(TranCatRecordMapper.FILLER_LENGTH);
    }

    // =============================================================================================

    @Nested
    @DisplayName("the published layout")
    class ThePublishedLayout {

        @Test
        @DisplayName("the record is sixty bytes, of which fifty-six are mapped and four are trailing "
                + "filler, matching the CVTRA04Y declaration")
        void theRecordIsSixtyBytesOfWhichFiftySixAreMapped() {
            assertThat(TranCatRecordMapper.RECORD_WIDTH).isEqualTo(60);
            assertThat(TranCatRecordMapper.MAPPED_DATA_WIDTH).isEqualTo(56);
            assertThat(TranCatRecordMapper.FILLER_LENGTH).isEqualTo(4);
            assertThat(TranCatRecordMapper.MAPPED_DATA_WIDTH + TranCatRecordMapper.FILLER_LENGTH)
                    .isEqualTo(TranCatRecordMapper.RECORD_WIDTH);
        }

        @Test
        @DisplayName("the three fields are contiguous from offset zero and the filler begins exactly "
                + "where the mapped prefix ends")
        void theFieldsAreContiguousFromOffsetZero() {
            assertThat(TranCatRecordMapper.TRAN_TYPE_CD_OFFSET).isZero();
            assertThat(TranCatRecordMapper.TRAN_CAT_CD_OFFSET)
                    .isEqualTo(TranCatRecordMapper.TRAN_TYPE_CD_OFFSET
                            + TranCatRecordMapper.TRAN_TYPE_CD_LENGTH);
            assertThat(TranCatRecordMapper.TRAN_CAT_TYPE_DESC_OFFSET)
                    .isEqualTo(TranCatRecordMapper.TRAN_CAT_CD_OFFSET
                            + TranCatRecordMapper.TRAN_CAT_CD_LENGTH);
            assertThat(TranCatRecordMapper.FILLER_OFFSET)
                    .isEqualTo(TranCatRecordMapper.TRAN_CAT_TYPE_DESC_OFFSET
                            + TranCatRecordMapper.TRAN_CAT_TYPE_DESC_LENGTH)
                    .isEqualTo(TranCatRecordMapper.MAPPED_DATA_WIDTH);
        }

        @Test
        @DisplayName("the field widths match the copybook: type two, category four, description fifty")
        void theFieldWidthsMatchTheCopybook() {
            assertThat(TranCatRecordMapper.TRAN_TYPE_CD_LENGTH).isEqualTo(2);
            assertThat(TranCatRecordMapper.TRAN_CAT_CD_LENGTH).isEqualTo(4);
            assertThat(TranCatRecordMapper.TRAN_CAT_TYPE_DESC_LENGTH).isEqualTo(50);
        }

        @Test
        @DisplayName("the composite key is the leading six bytes, the type and category codes together")
        void theCompositeKeyIsTheLeadingSixBytes() {
            assertThat(TranCatRecordMapper.TYPE_AND_CATEGORY_KEY_WIDTH)
                    .isEqualTo(6)
                    .isEqualTo(TranCatRecordMapper.TRAN_TYPE_CD_LENGTH
                            + TranCatRecordMapper.TRAN_CAT_CD_LENGTH);
        }

        @Test
        @DisplayName("both artefact labels name the copybook, so a diagnostic identifies the layout that "
                + "refused rather than only the operation that failed")
        void bothArtefactLabelsNameTheCopybook() {
            assertThat(TranCatRecordMapper.ARTEFACT).isEqualTo("TRAN-CAT-RECORD (CVTRA04Y)");
            assertThat(TranCatRecordMapper.KEY_ARTEFACT)
                    .isEqualTo("TRAN-CAT-KEY (CVTRA04Y, 6 bytes)");
            assertThat(TranCatRecordMapper.KEY_ARTEFACT)
                    .contains(String.valueOf(TranCatRecordMapper.TYPE_AND_CATEGORY_KEY_WIDTH));
        }

        @Test
        @DisplayName("the emitted filler character is a space, which is why an encode cycle normalises "
                + "the sample file's numeric filler")
        void theEmittedFillerCharacterIsASpace() {
            assertThat(TranCatRecordMapper.EMITTED_FILLER_CHARACTER).isEqualTo(' ');
        }

        @Test
        @DisplayName("the mapper is a final class that cannot be instantiated")
        void theMapperCannotBeInstantiated() throws NoSuchMethodException {
            assertThat(Modifier.isFinal(TranCatRecordMapper.class.getModifiers())).isTrue();

            final Constructor<TranCatRecordMapper> constructor =
                    TranCatRecordMapper.class.getDeclaredConstructor();
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
        @DisplayName("the fixture carries eighteen records at a stride of sixty-one")
        void theFixtureCarriesEighteenRecords() throws IOException {
            assertThat(Files.readAllBytes(FIXTURE))
                    .hasSize(FIXTURE_RECORD_COUNT * FIXTURE_STRIDE);
            assertThat(FIXTURE_STRIDE).isEqualTo(61);
        }

        @Test
        @DisplayName("the first fixture record decodes to the regular sales draft category with its "
                + "description space-padded to fifty")
        void theFirstFixtureRecordDecodesToRegularSalesDraft() throws IOException {
            final TransactionCategory decoded = TranCatRecordMapper.fromRecord(fixtureRecord(0));

            assertThat(decoded.getTranTypeCd()).isEqualTo("01");
            assertThat(decoded.getTranCatCd()).isEqualTo("0001");
            assertThat(decoded.getTranCatTypeDesc())
                    .isEqualTo(spacePadded("Regular Sales Draft",
                            TranCatRecordMapper.TRAN_CAT_TYPE_DESC_LENGTH))
                    .hasSize(TranCatRecordMapper.TRAN_CAT_TYPE_DESC_LENGTH);
        }

        @Test
        @DisplayName("every one of the eighteen fixture records decodes to the declared widths")
        void everyFixtureRecordDecodesToTheDeclaredWidths() throws IOException {
            for (final String image : allFixtureRecords()) {
                final TransactionCategory decoded = TranCatRecordMapper.fromRecord(image);

                assertThat(decoded.getTranTypeCd())
                        .hasSize(TranCatRecordMapper.TRAN_TYPE_CD_LENGTH);
                assertThat(decoded.getTranCatCd())
                        .hasSize(TranCatRecordMapper.TRAN_CAT_CD_LENGTH);
                assertThat(decoded.getTranCatTypeDesc())
                        .hasSize(TranCatRecordMapper.TRAN_CAT_TYPE_DESC_LENGTH);
                assertThat(decoded.getTranCatTypeDesc().strip()).isNotEmpty();
            }
        }

        @Test
        @DisplayName("the eighteen composite keys are distinct, so the fixture is a valid reference "
                + "table under its own primary key")
        void theEighteenCompositeKeysAreDistinct() throws IOException {
            final List<String> keys = new ArrayList<>();
            for (final String image : allFixtureRecords()) {
                keys.add(TranCatRecordMapper.typeAndCategoryKeyImage(
                        TranCatRecordMapper.fromRecord(image)));
            }

            assertThat(keys).hasSize(FIXTURE_RECORD_COUNT).doesNotHaveDuplicates();
        }

        @Test
        @DisplayName("every category belongs to a transaction type the type fixture also declares, so "
                + "the two reference tables agree")
        void everyCategoryBelongsToADeclaredTransactionType() throws IOException {
            final byte[] typeFile = Files.readAllBytes(
                    Path.of("src/test/resources/fixtures/input/trantype.txt"));
            final List<String> declaredTypes = new ArrayList<>();
            for (int ordinal = 0; ordinal < 7; ordinal++) {
                declaredTypes.add(new String(typeFile,
                        ordinal * (TranTypeRecordMapper.TRAN_TYPE_RECORD_LENGTH + 1),
                        TranTypeRecordMapper.TRAN_TYPE_CODE_LENGTH, StandardCharsets.US_ASCII));
            }

            for (final String image : allFixtureRecords()) {
                assertThat(declaredTypes)
                        .contains(TranCatRecordMapper.fromRecord(image).getTranTypeCd());
            }
        }

        @Test
        @DisplayName("a byte-array image decodes identically to the equivalent string image")
        void aByteArrayImageDecodesIdenticallyToAStringImage() throws IOException {
            final String image = fixtureRecord(0);

            final TransactionCategory fromString = TranCatRecordMapper.fromRecord(image);
            final TransactionCategory fromBytes = TranCatRecordMapper.fromRecord(
                    image.getBytes(StandardCharsets.US_ASCII));

            assertThat(fromBytes.getTranTypeCd()).isEqualTo(fromString.getTranTypeCd());
            assertThat(fromBytes.getTranCatCd()).isEqualTo(fromString.getTranCatCd());
            assertThat(fromBytes.getTranCatTypeDesc()).isEqualTo(fromString.getTranCatTypeDesc());
        }

        @Test
        @DisplayName("an offset decode reads every record out of the whole-file buffer, which is how a "
                + "blocked sequential read presents its records")
        void anOffsetDecodeReadsEveryRecordOutOfTheBuffer() throws IOException {
            final byte[] file = Files.readAllBytes(FIXTURE);

            for (int ordinal = 0; ordinal < FIXTURE_RECORD_COUNT; ordinal++) {
                final TransactionCategory fromBuffer =
                        TranCatRecordMapper.fromRecord(file, ordinal * FIXTURE_STRIDE);

                assertThat(TranCatRecordMapper.typeAndCategoryKeyImage(fromBuffer))
                        .isEqualTo(TranCatRecordMapper.typeAndCategoryKeyImage(
                                TranCatRecordMapper.fromRecord(fixtureRecord(ordinal))));
            }
        }
    }

    // =============================================================================================

    @Nested
    @DisplayName("the six-byte key image")
    class TheSixByteKeyImage {

        @Test
        @DisplayName("the key image of the first fixture record is the type and category codes "
                + "concatenated")
        void theKeyImageIsTheTwoCodesConcatenated() throws IOException {
            assertThat(TranCatRecordMapper.typeAndCategoryKeyImage(
                    TranCatRecordMapper.fromRecord(fixtureRecord(0)))).isEqualTo("010001");
        }

        @Test
        @DisplayName("the key image is exactly the leading six bytes of the record image, so a keyed "
                + "lookup and a whole-record write cannot disagree about where the key lives")
        void theKeyImageIsThePrefixOfTheRecordImage() throws IOException {
            for (final String image : allFixtureRecords()) {
                final TransactionCategory decoded = TranCatRecordMapper.fromRecord(image);

                assertThat(TranCatRecordMapper.typeAndCategoryKeyImage(decoded))
                        .isEqualTo(TranCatRecordMapper.toRecord(decoded)
                                .substring(0, TranCatRecordMapper.TYPE_AND_CATEGORY_KEY_WIDTH));
            }
        }

        @Test
        @DisplayName("the key image is always six bytes wide")
        void theKeyImageIsAlwaysSixBytesWide() throws IOException {
            for (final String image : allFixtureRecords()) {
                assertThat(TranCatRecordMapper.typeAndCategoryKeyImage(
                        TranCatRecordMapper.fromRecord(image)))
                        .hasSize(TranCatRecordMapper.TYPE_AND_CATEGORY_KEY_WIDTH);
            }
        }

        @Test
        @DisplayName("the key emitter zero-pads a narrow category code, matching the record emitter, "
                + "because the category code is numeric in both")
        void theKeyEmitterZeroPadsANarrowCategoryCode() {
            final TransactionCategory narrow = new TransactionCategory("01", "1", "Description");

            assertThat(TranCatRecordMapper.typeAndCategoryKeyImage(narrow)).isEqualTo("010001");
        }

        @Test
        @DisplayName("a null category and a null key field are each refused, so no partial key can be "
                + "used for a lookup")
        void aNullKeyFieldIsRefused() {
            assertThatNullPointerException()
                    .isThrownBy(() -> TranCatRecordMapper.typeAndCategoryKeyImage(null));
            assertThatNullPointerException()
                    .isThrownBy(() -> TranCatRecordMapper.typeAndCategoryKeyImage(
                            new TransactionCategory(null, "0001", "Description")))
                    .withMessageContaining("TRAN-TYPE-CD");
            assertThatNullPointerException()
                    .isThrownBy(() -> TranCatRecordMapper.typeAndCategoryKeyImage(
                            new TransactionCategory("01", null, "Description")))
                    .withMessageContaining("TRAN-CAT-CD");
        }

        @Test
        @DisplayName("the key emitter does not require the description, because a key is composed from "
                + "the key fields alone")
        void theKeyEmitterDoesNotRequireTheDescription() {
            assertThat(TranCatRecordMapper.typeAndCategoryKeyImage(
                    new TransactionCategory("01", "0001", null))).isEqualTo("010001");
        }
    }

    // =============================================================================================

    @Nested
    @DisplayName("encoding and the round trip")
    class EncodingAndTheRoundTrip {

        @Test
        @DisplayName("the fifty-six mapped bytes of every fixture record survive a decode and encode "
                + "cycle unchanged, which is the byte-parity contract Gate 1 measures")
        void theMappedBytesSurviveTheRoundTripUnchanged() throws IOException {
            for (final String image : allFixtureRecords()) {
                final String encoded =
                        TranCatRecordMapper.toRecord(TranCatRecordMapper.fromRecord(image));

                assertThat(encoded.substring(0, TranCatRecordMapper.MAPPED_DATA_WIDTH))
                        .isEqualTo(image.substring(0, TranCatRecordMapper.MAPPED_DATA_WIDTH));
            }
        }

        @Test
        @DisplayName("the four filler bytes are normalised to spaces, because the sample file carries "
                + "zeros there and COBOL FILLER has no defined content")
        void theFillerBytesAreNormalisedToSpaces() throws IOException {
            final String image = fixtureRecord(0);
            final String encoded =
                    TranCatRecordMapper.toRecord(TranCatRecordMapper.fromRecord(image));

            assertThat(image.substring(TranCatRecordMapper.FILLER_OFFSET)).isEqualTo("0000");
            assertThat(encoded.substring(TranCatRecordMapper.FILLER_OFFSET))
                    .isEqualTo(" ".repeat(TranCatRecordMapper.FILLER_LENGTH));
            assertThat(encoded).isNotEqualTo(image);
        }

        @Test
        @DisplayName("a record whose filler is already spaces round-trips byte for byte, proving the "
                + "only difference is the filler convention")
        void aSpaceFilledRecordRoundTripsByteForByte() {
            final String image = recordImage("01", "0001", "Regular Sales Draft");

            assertThat(TranCatRecordMapper.toRecord(TranCatRecordMapper.fromRecord(image)))
                    .isEqualTo(image);
        }

        @Test
        @DisplayName("the type code is left-justified and space-padded while the category code is "
                + "right-justified and zero-padded, because only the latter is numeric")
        void theTwoCodesArePaddedDifferently() {
            final String encoded =
                    TranCatRecordMapper.toRecord(new TransactionCategory("1", "1", "Sales"));

            assertThat(encoded.substring(TranCatRecordMapper.TRAN_TYPE_CD_OFFSET,
                    TranCatRecordMapper.TRAN_TYPE_CD_OFFSET
                            + TranCatRecordMapper.TRAN_TYPE_CD_LENGTH)).isEqualTo("1 ");
            assertThat(encoded.substring(TranCatRecordMapper.TRAN_CAT_CD_OFFSET,
                    TranCatRecordMapper.TRAN_CAT_CD_OFFSET
                            + TranCatRecordMapper.TRAN_CAT_CD_LENGTH)).isEqualTo("0001");
        }

        @Test
        @DisplayName("the encoded image is always exactly sixty bytes")
        void theEncodedImageIsAlwaysSixtyBytes() {
            assertThat(TranCatRecordMapper.toRecord(new TransactionCategory("01", "0001", "")))
                    .hasSize(TranCatRecordMapper.RECORD_WIDTH);
            assertThat(TranCatRecordMapper.toRecord(new TransactionCategory("01", "0001",
                    "X".repeat(TranCatRecordMapper.TRAN_CAT_TYPE_DESC_LENGTH))))
                    .hasSize(TranCatRecordMapper.RECORD_WIDTH);
        }

        @Test
        @DisplayName("the byte-array encoding is the US-ASCII encoding of the string encoding")
        void theByteEncodingMatchesTheStringEncoding() throws IOException {
            final TransactionCategory decoded = TranCatRecordMapper.fromRecord(fixtureRecord(0));

            assertThat(TranCatRecordMapper.toRecordBytes(decoded))
                    .isEqualTo(TranCatRecordMapper.toRecord(decoded)
                            .getBytes(StandardCharsets.US_ASCII))
                    .hasSize(TranCatRecordMapper.RECORD_WIDTH);
        }
    }

    // =============================================================================================

    @Nested
    @DisplayName("refusals, and what the refusal is allowed to say")
    class RefusalsAndTheirDiagnostics {

        @ParameterizedTest
        @ValueSource(ints = {0, 1, 59, 61, 120})
        @DisplayName("an image of any width other than sixty is refused")
        void anImageOfTheWrongWidthIsRefused(final int width) {
            final String wrongWidth = "X".repeat(width);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> TranCatRecordMapper.fromRecord(wrongWidth));
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> TranCatRecordMapper.fromRecord(
                            wrongWidth.getBytes(StandardCharsets.US_ASCII)));
        }

        @Test
        @DisplayName("a sixty-one byte image is refused and the diagnostic names the line terminator, "
                + "which is the overwhelmingly common cause of a one-byte overshoot")
        void anUnstrippedTerminatorIsRefusedWithAHint() throws IOException {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> TranCatRecordMapper.fromRecord(fixtureRecord(0) + "\n"))
                    .withMessageContaining("0x0A line");
        }

        @Test
        @DisplayName("a null image, a null buffer and a null entity are all refused")
        void nullInputsAreRefused() {
            assertThatNullPointerException()
                    .isThrownBy(() -> TranCatRecordMapper.fromRecord((String) null));
            assertThatNullPointerException()
                    .isThrownBy(() -> TranCatRecordMapper.fromRecord((byte[]) null));
            assertThatNullPointerException()
                    .isThrownBy(() -> TranCatRecordMapper.fromRecord(null, 0));
            assertThatNullPointerException()
                    .isThrownBy(() -> TranCatRecordMapper.toRecord(null));
            assertThatNullPointerException()
                    .isThrownBy(() -> TranCatRecordMapper.toRecordBytes(null));
        }

        @Test
        @DisplayName("an offset that would read past the end of the buffer is refused rather than "
                + "silently returning a short record")
        void anOffsetPastTheEndOfTheBufferIsRefused() {
            final byte[] oneRecord = recordImage("01", "0001", "Sales")
                    .getBytes(StandardCharsets.US_ASCII);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> TranCatRecordMapper.fromRecord(oneRecord, 1));
        }

        @Test
        @DisplayName("a non-ASCII character is refused, because a multi-byte character would silently "
                + "shift every field after it")
        void aNonAsciiCharacterIsRefused() {
            final String withNonAscii = "010001" + spacePadded("R\u00e9gular",
                    TranCatRecordMapper.TRAN_CAT_TYPE_DESC_LENGTH)
                    + " ".repeat(TranCatRecordMapper.FILLER_LENGTH);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> TranCatRecordMapper.fromRecord(withNonAscii));
        }

        @Test
        @DisplayName("each absent mapped field is refused by name, because a fixed-width field has no "
                + "representation for an absent value")
        void eachAbsentMappedFieldIsRefusedByName() {
            assertThatNullPointerException()
                    .isThrownBy(() -> TranCatRecordMapper.toRecord(
                            new TransactionCategory(null, "0001", "Sales")))
                    .withMessageContaining("TRAN-TYPE-CD");
            assertThatNullPointerException()
                    .isThrownBy(() -> TranCatRecordMapper.toRecord(
                            new TransactionCategory("01", null, "Sales")))
                    .withMessageContaining("TRAN-CAT-CD");
            assertThatNullPointerException()
                    .isThrownBy(() -> TranCatRecordMapper.toRecord(
                            new TransactionCategory("01", "0001", null)))
                    .withMessageContaining("TRAN-CAT-TYPE-DESC");
        }

        @Test
        @DisplayName("a value wider than its field is refused rather than truncated")
        void anOverWideValueIsRefusedRatherThanTruncated() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> TranCatRecordMapper.toRecord(new TransactionCategory("01",
                            "0001",
                            "X".repeat(TranCatRecordMapper.TRAN_CAT_TYPE_DESC_LENGTH + 1))))
                    .withMessageContaining("never truncated to fit");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> TranCatRecordMapper.toRecord(
                            new TransactionCategory("01", "00001", "Sales")));
        }

        @Test
        @DisplayName("no refusal diagnostic echoes the record image, so a malformed reference record "
                + "cannot inject its own content into a log line")
        void noRefusalDiagnosticEchoesTheRecordImage() {
            final String hostile =
                    spacePadded("019999\r\nFAKE LOG LINE", TranCatRecordMapper.RECORD_WIDTH);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> TranCatRecordMapper.fromRecord(hostile + " "))
                    .satisfies(refusal -> {
                        assertThat(refusal.getMessage()).doesNotContain("FAKE LOG LINE");
                        assertThat(refusal.getMessage()).doesNotContain("\r");
                        assertThat(refusal.getMessage()).doesNotContain("\n");
                    });
        }
    }
}
