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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.carddemo.domain.TransactionType;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit test for {@link TranTypeRecordMapper}, the two-way mapping between the 60-byte legacy
 * transaction-type reference record and {@link TransactionType}.
 *
 * <p><strong>What is under test.</strong> One record layout, 60 bytes wide, split three ways: a
 * two-byte type code at offset 0 that is also the whole of the cluster key, a fifty-byte description
 * at offset 2, and an eight-byte trailing filler run at offset 52 that no column represents. The key
 * is a <em>bare</em> field rather than a key group, so this is the only reference entity in its family
 * with a single-column identifier and no composite-key class of any kind.
 *
 * <p><strong>Every expectation in this class is hand written.</strong> No assertion asks the mapper,
 * the slicing primitive or any other production class to compute the value it is then compared
 * against, and no expectation is a captured snapshot of an earlier run. The offsets, widths, codes,
 * description texts, filler bytes and row count below were read from the copybook, the base cluster
 * definition, the shipped reference fixture, the schema migration and the seed migration, and are
 * restated here as independent literals. A wrong constant in the mapper therefore disagrees with this
 * class rather than being echoed back by it.
 *
 * <p><strong>Two 60-byte layouts coexist, and a width check cannot separate them.</strong> The
 * transaction-category reference layout is also exactly 60 bytes, but it splits as 2 + 4 + 50 + 4 -
 * its description sits at offset 6 and its filler run is 4 bytes - against this layout's 2 + 50 + 8.
 * The independent attestation is the pair of base cluster definitions, which declare a 2-byte key here
 * and a 6-byte key there. The consequence is that a category record handed to this mapper passes the
 * width check and decodes into nonsense instead of failing, which is why the mapper is chosen by the
 * caller from the data set it read and why nothing here inspects bytes to guess which layout an image
 * belongs to. No offset, width, helper or fixture in this class is shared with that layout's test.
 *
 * <p><strong>The filler bytes deliberately disagree with the fixture.</strong> The legacy filler
 * declaration carries no initialising clause, so no byte value is canonical, and the shipped fixtures
 * genuinely differ from one another: the master fixtures pad with spaces while the reference-table
 * fixtures - this one among them - pad with ASCII zero. The mapper emits spaces uniformly. A
 * whole-record 60-byte comparison against the fixture would therefore fail on the filler alone, so
 * every fixture comparison here is bounded to the mapped data prefix from 0 up to but excluding 52,
 * and the divergence itself is asserted as a deliberate contract rather than worked around. The
 * fixture is never "corrected" and a whole-record equality against it is never asserted. The
 * resolution is recorded in {@code docs/decision-log.md}; this class only exercises it.
 *
 * <p><strong>Deliberately absent.</strong> No enumeration of the type codes, so an out-of-set value is
 * carried through unchanged; no numeric conversion of the code, so a leading zero survives; no
 * narrowing of the description, whose fifty bytes must reach the report line formatter intact because
 * the narrowing to fifteen characters belongs to that formatter and to its own test; no arithmetic of
 * any kind beyond the record's own byte geometry, the consuming report program having no computation
 * statement at all; no trimming, since trailing spaces are contractual; no reflective access, which
 * would undermine the very reason the eleven record mappers are written by hand; and no container,
 * application context, database, network or file access, this being a pure in-process unit test.
 *
 * <p>Provenance, recorded as a plain string and never asserted on a member: checkout SHA
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19.
 */
@DisplayName("transaction-type record mapper: the 60-byte reference layout with a bare two-byte key")
class TranTypeRecordMapperTest {

    // -----------------------------------------------------------------------------------------------
    // The independent oracle. Read from the copybook, the cluster definition, the shipped fixture and
    // the schema and seed migrations, and restated here as literals so that no expectation in this
    // class can be produced by the code it is testing.
    //
    //   #  property        column           PIC     offset  length  1-based span  key
    //   1  tranType        tran_type        X(02)        0       2           1-2  identifier
    //   2  tranTypeDesc    tran_type_desc   X(50)        2      50          3-52  -
    //   -  FILLER          (none)           X(08)       52       8         53-60  -
    //
    //   2 + 50 = 52 mapped bytes;  52 + 8 = 60 record bytes.
    // -----------------------------------------------------------------------------------------------

    /** Full record width in encoded bytes, from the copybook's stated record length. */
    private static final int RECORD_LENGTH = 60;

    /**
     * End of the mapped data prefix, and the only window in which the emitted image and the shipped
     * fixture can agree, the filler run beyond it being written differently by design.
     */
    private static final int MAPPED_PREFIX_LENGTH = 52;

    /** Zero-based offset of the two-byte type code, which is also the cluster key. */
    private static final int CODE_OFFSET = 0;

    /** Encoded width of the type code. */
    private static final int CODE_LENGTH = 2;

    /**
     * Zero-based offset of the description. It is 2 here. The other 60-byte layout places its
     * description at 6, and confusing the two reads the wrong window without any width check noticing.
     */
    private static final int DESCRIPTION_OFFSET = 2;

    /**
     * Encoded width of the description as this layout stores it. The transaction report presents a
     * narrower form; that narrowing belongs to the report line formatter and to its own test, never
     * here, which is precisely why all fifty bytes must survive this mapper.
     */
    private static final int DESCRIPTION_LENGTH = 50;

    /** Zero-based offset of the trailing filler run, which no column represents. */
    private static final int FILLER_OFFSET = 52;

    /** Encoded width of the trailing filler run. It is 8 here and 4 in the other 60-byte layout. */
    private static final int FILLER_LENGTH = 8;

    /** Key width declared by this layout's base cluster definition, {@code KEYS(2 0)}. */
    private static final int CLUSTER_KEY_LENGTH = 2;

    /** Key offset declared by this layout's base cluster definition, {@code KEYS(2 0)}. */
    private static final int CLUSTER_KEY_OFFSET = 0;

    /** Byte the mapper writes across the filler run: ASCII space. */
    private static final byte EMITTED_FILLER_BYTE = 0x20;

    /** Character the shipped reference fixture carries across the filler run: ASCII zero. */
    private static final char FIXTURE_FILLER_CHARACTER = '0';

    /** The same fixture filler value expressed as a byte, measured over all seven records. */
    private static final byte FIXTURE_FILLER_BYTE = 0x30;

    /**
     * Rows the reference seed migration inserts into this table, which is also the record count of the
     * shipped fixture. Factual layout evidence about a reference data set, not a service level and not
     * a capacity or throughput figure of any kind.
     */
    private static final int SEEDED_ROW_COUNT = 7;

    /**
     * Stride of the newline-terminated reference fixture: the record width plus the single terminator
     * byte, which is a record separator and never record content.
     */
    private static final int FIXTURE_STRIDE = 61;

    /** Measured size of the shipped reference fixture in bytes: seven records at the stride above. */
    private static final int FIXTURE_TOTAL_BYTES = 427;

    // -----------------------------------------------------------------------------------------------
    // Negative witnesses for the 60-byte width collision.
    //
    // These three numbers are the OTHER 60-byte layout's geometry. They exist here for one purpose
    // only: to be the right-hand side of an isNotEqualTo assertion, so that the collision is proven
    // rather than described. They are never used as an offset, a length or a bound against this
    // layout, and no constant is shared in either direction with that layout's mapper or test.
    // -----------------------------------------------------------------------------------------------

    /** Where the other 60-byte layout places its description. This layout must never agree with it. */
    private static final int COLLIDING_LAYOUT_DESCRIPTION_OFFSET = 6;

    /** How wide the other 60-byte layout's filler run is. This layout must never agree with it. */
    private static final int COLLIDING_LAYOUT_FILLER_LENGTH = 4;

    /** Key width the other 60-byte layout's cluster declares, {@code KEYS(6 0)}. */
    private static final int COLLIDING_LAYOUT_CLUSTER_KEY_LENGTH = 6;

    /** Width of the category code that the other 60-byte layout carries and this one does not. */
    private static final int COLLIDING_LAYOUT_CATEGORY_CODE_LENGTH = 4;

    // -----------------------------------------------------------------------------------------------
    // Fixture content, transcribed from the shipped reference data as plain metadata: seven
    // two-character codes and the seven description texts they carry, in row order.
    // -----------------------------------------------------------------------------------------------

    /** The seven type codes in row order. Two characters each, zero padded, leading zeros required. */
    private static final List<String> SEED_CODES =
            List.of("01", "02", "03", "04", "05", "06", "07");

    /**
     * The seven description texts in row order, as the seed migration stores them, that is with the
     * record image's trailing pad removed. The record image itself carries each of them padded out to
     * the full declared width, which is what {@link #paddedDescription(int)} reconstructs.
     */
    private static final List<String> SEED_DESCRIPTIONS = List.of(
            "Purchase", "Payment", "Credit", "Authorization", "Refund", "Reversal", "Adjustment");

    /**
     * Row 0's description exactly as the record image carries it: the eight characters of the seed
     * text followed by 42 spaces, and 8 + 42 = 50 encoded bytes.
     *
     * <p>Written out in full rather than computed, so that at least one expectation in this class
     * cannot agree with a wrong pad width by construction. Its width and its agreement with
     * {@link #paddedDescription(int)} are both asserted, which is what licenses the computed form for
     * the remaining six rows.
     */
    private static final String ROW_0_DESCRIPTION_IMAGE =
            "Purchase                                          ";

    /**
     * A description that occupies the declared width exactly, leaving no room for a pad byte. Its
     * width is asserted rather than assumed.
     */
    private static final String EXACT_WIDTH_DESCRIPTION = "FIFTY BYTE DESCRIPTION WITH NO ROOM LEFT FOR A PAD";

    /** A type code outside the seeded set, used to show that no enumeration translates the code. */
    private static final String OUT_OF_SET_CODE = "ZZ";

    // -----------------------------------------------------------------------------------------------
    // Local helpers. Every one of them is built from java.lang.String and java.util alone: none calls
    // the mapper, the slicing primitive or any other production class, so an expectation produced here
    // is independent of the code under test.
    // -----------------------------------------------------------------------------------------------

    /**
     * Returns a value's width in encoded bytes, which is the only width authority this class uses. A
     * character count is never treated as a width.
     */
    private static int encodedByteLength(final String value) {
        return value.getBytes(StandardCharsets.US_ASCII).length;
    }

    /** Encodes a value under the explicitly named charset; no platform default is ever relied on. */
    private static byte[] encoded(final String value) {
        return value.getBytes(StandardCharsets.US_ASCII);
    }

    /** Pads a value on the right with spaces to the given encoded width, matching an alphanumeric field. */
    private static String padToWidth(final String value, final int width) {
        return value + " ".repeat(width - encodedByteLength(value));
    }

    /** The description of the given fixture row as the record image carries it, padded to full width. */
    private static String paddedDescription(final int rowIndex) {
        return padToWidth(SEED_DESCRIPTIONS.get(rowIndex), DESCRIPTION_LENGTH);
    }

    /** The mapped data prefix of the given fixture row: the code followed by the padded description. */
    private static String mappedPrefix(final int rowIndex) {
        return SEED_CODES.get(rowIndex) + paddedDescription(rowIndex);
    }

    /** The filler run as the shipped fixture carries it: the fixture's own fill character, repeated. */
    private static String fixtureFillerRun() {
        return String.valueOf(FIXTURE_FILLER_CHARACTER).repeat(FILLER_LENGTH);
    }

    /**
     * The complete 60-byte record image of the given fixture row, assembled by hand from the code, the
     * padded description and the fixture's ASCII-zero filler run, and carrying no line terminator.
     */
    private static String fixtureImage(final int rowIndex) {
        return mappedPrefix(rowIndex) + fixtureFillerRun();
    }

    /**
     * The complete 60-byte record image the mapper is expected to emit for the given fixture row: the
     * same mapped prefix followed by a space filler run rather than the fixture's ASCII-zero one. Built
     * here by hand so that the full-image round trip is compared against an independent expectation and
     * not merely against the mapper's other entry point.
     */
    private static String emittedImage(final int rowIndex) {
        return mappedPrefix(rowIndex) + " ".repeat(FILLER_LENGTH);
    }

    /**
     * The whole shipped fixture as one buffer: seven records at the 61-byte stride, each followed by
     * the single terminator byte, which is exactly how a batch reader holding a whole file sees it.
     */
    private static byte[] fixtureBuffer() {
        final StringBuilder file = new StringBuilder(FIXTURE_TOTAL_BYTES);
        for (int rowIndex = 0; rowIndex < SEEDED_ROW_COUNT; rowIndex++) {
            file.append(fixtureImage(rowIndex)).append('\n');
        }
        return encoded(file.toString());
    }

    /** A locale-independent label for an assertion description; never a formatted number. */
    private static String rowLabel(final int rowIndex) {
        return "fixture row " + rowIndex;
    }

    /** The bytes of an image in the half-open range, so a comparison can be bounded to one window. */
    private static byte[] range(final byte[] image, final int fromInclusive, final int toExclusive) {
        return Arrays.copyOfRange(image, fromInclusive, toExclusive);
    }

    @Nested
    @DisplayName("declared geometry")
    class DeclaredGeometry {

        @Test
        @DisplayName("the two declared offsets are the copybook's own: the code at 0 and the "
                + "description at 2, with the filler run starting at 52")
        void theDeclaredOffsetsAreTheCopybooksOwn() {
            assertThat(TranTypeRecordMapper.TRAN_TYPE_CODE_OFFSET).isEqualTo(CODE_OFFSET);
            assertThat(TranTypeRecordMapper.TRAN_TYPE_DESCRIPTION_OFFSET).isEqualTo(DESCRIPTION_OFFSET);
            assertThat(TranTypeRecordMapper.TRAN_TYPE_FILLER_OFFSET).isEqualTo(FILLER_OFFSET);
        }

        @Test
        @DisplayName("the two declared lengths are the copybook's own: 2 for the code and 50 for the "
                + "description, with an 8-byte filler run")
        void theDeclaredLengthsAreTheCopybooksOwn() {
            assertThat(TranTypeRecordMapper.TRAN_TYPE_CODE_LENGTH).isEqualTo(CODE_LENGTH);
            assertThat(TranTypeRecordMapper.TRAN_TYPE_DESCRIPTION_LENGTH).isEqualTo(DESCRIPTION_LENGTH);
            assertThat(TranTypeRecordMapper.TRAN_TYPE_FILLER_LENGTH).isEqualTo(FILLER_LENGTH);
        }

        @Test
        @DisplayName("the record is 60 bytes wide, the mapped prefix ends at 52, and 2 + 50 = 52 while "
                + "52 + 8 = 60, so the fields tile the record with no gap and no overlap")
        void theFieldsTileTheSixtyByteRecordExactly() {
            assertThat(TranTypeRecordMapper.TRAN_TYPE_RECORD_LENGTH).isEqualTo(RECORD_LENGTH);
            assertThat(TranTypeRecordMapper.TRAN_TYPE_MAPPED_LENGTH).isEqualTo(MAPPED_PREFIX_LENGTH);

            assertThat(CODE_OFFSET + CODE_LENGTH).isEqualTo(DESCRIPTION_OFFSET);
            assertThat(DESCRIPTION_OFFSET + DESCRIPTION_LENGTH)
                    .isEqualTo(MAPPED_PREFIX_LENGTH)
                    .isEqualTo(FILLER_OFFSET);
            assertThat(FILLER_OFFSET + FILLER_LENGTH).isEqualTo(RECORD_LENGTH);
        }

        @Test
        @DisplayName("the byte the mapper writes across the filler run is a space, 0x20, which is what "
                + "makes the fixture comparison bound necessary rather than merely cautious")
        void theEmittedFillerCharacterIsASpace() {
            assertThat(TranTypeRecordMapper.TRAN_TYPE_FILLER_CHARACTER).isEqualTo(' ');
            assertThat(encoded(String.valueOf(TranTypeRecordMapper.TRAN_TYPE_FILLER_CHARACTER)))
                    .containsExactly(EMITTED_FILLER_BYTE);
            assertThat(EMITTED_FILLER_BYTE).isNotEqualTo(FIXTURE_FILLER_BYTE);
        }

        @Test
        @DisplayName("the hand-written row 0 description literal is exactly 50 encoded bytes and agrees "
                + "with the padding helper, which is what licenses the computed form for the other rows")
        void theHandWrittenRowZeroLiteralIsFiftyEncodedBytes() {
            assertThat(encodedByteLength(ROW_0_DESCRIPTION_IMAGE)).isEqualTo(DESCRIPTION_LENGTH);
            assertThat(ROW_0_DESCRIPTION_IMAGE).isEqualTo(paddedDescription(0));
            assertThat(encodedByteLength(EXACT_WIDTH_DESCRIPTION)).isEqualTo(DESCRIPTION_LENGTH);
        }
    }

    @Nested
    @DisplayName("cluster and key contract")
    class ClusterAndKeyContract {

        @Test
        @DisplayName("the base cluster declares KEYS(2 0), so the key is 2 bytes wide and starts at "
                + "offset 0, exactly where the mapper reads the code")
        void theClusterDeclaresATwoByteKeyAtOffsetZero() {
            assertThat(CLUSTER_KEY_LENGTH).isEqualTo(2);
            assertThat(CLUSTER_KEY_OFFSET).isEqualTo(0);
            assertThat(TranTypeRecordMapper.TRAN_TYPE_CODE_LENGTH).isEqualTo(CLUSTER_KEY_LENGTH);
            assertThat(TranTypeRecordMapper.TRAN_TYPE_CODE_OFFSET).isEqualTo(CLUSTER_KEY_OFFSET);
        }

        @Test
        @DisplayName("the identifier is the two-byte business key itself, read from the head of the "
                + "record image, and never a machine-assigned surrogate")
        void theIdentifierIsTheBusinessKeyAndNeverASurrogate() {
            for (int rowIndex = 0; rowIndex < SEEDED_ROW_COUNT; rowIndex++) {
                final TransactionType decoded =
                        TranTypeRecordMapper.fromRecord(fixtureImage(rowIndex));

                assertThat(decoded.getTranType())
                        .as(rowLabel(rowIndex) + " identifier")
                        .isEqualTo(SEED_CODES.get(rowIndex));
                assertThat(encodedByteLength(decoded.getTranType()))
                        .as(rowLabel(rowIndex) + " identifier width")
                        .isEqualTo(CLUSTER_KEY_LENGTH);
                assertThat(range(encoded(fixtureImage(rowIndex)), CLUSTER_KEY_OFFSET,
                        CLUSTER_KEY_OFFSET + CLUSTER_KEY_LENGTH))
                        .as(rowLabel(rowIndex) + " leading key bytes")
                        .isEqualTo(encoded(SEED_CODES.get(rowIndex)));
            }
        }
    }

    @Nested
    @DisplayName("the 60-byte width collision with a structurally different layout")
    class TheSixtyByteWidthCollision {

        @Test
        @DisplayName("60 bytes is shared with the transaction-category layout and a length check cannot "
                + "distinguish them: the description sits at 2 here and not 6, and the filler run is 8 "
                + "bytes here and not 4")
        void theSixtyByteWidthIsSharedWithAStructurallyDifferentLayout() {
            assertThat(TranTypeRecordMapper.TRAN_TYPE_RECORD_LENGTH).isEqualTo(RECORD_LENGTH);

            // The arithmetic that makes the collision real rather than merely alarming: this layout
            // sums 2 + 50 + 8 to 60, and the other sums 6 + 50 + 4 to the very same 60. Because the
            // totals coincide, a width check accepts either image and can never separate them - which
            // is why the mapper is chosen by the caller and why the two geometries share nothing.
            assertThat(CODE_LENGTH + DESCRIPTION_LENGTH + FILLER_LENGTH).isEqualTo(RECORD_LENGTH);
            assertThat(COLLIDING_LAYOUT_DESCRIPTION_OFFSET + DESCRIPTION_LENGTH
                    + COLLIDING_LAYOUT_FILLER_LENGTH).isEqualTo(RECORD_LENGTH);

            assertThat(TranTypeRecordMapper.TRAN_TYPE_DESCRIPTION_OFFSET)
                    .isEqualTo(DESCRIPTION_OFFSET)
                    .isNotEqualTo(COLLIDING_LAYOUT_DESCRIPTION_OFFSET);
            assertThat(TranTypeRecordMapper.TRAN_TYPE_FILLER_LENGTH)
                    .isEqualTo(FILLER_LENGTH)
                    .isNotEqualTo(COLLIDING_LAYOUT_FILLER_LENGTH);
            assertThat(TranTypeRecordMapper.TRAN_TYPE_MAPPED_LENGTH)
                    .isEqualTo(MAPPED_PREFIX_LENGTH)
                    .isNotEqualTo(RECORD_LENGTH - COLLIDING_LAYOUT_FILLER_LENGTH);
            assertThat(CLUSTER_KEY_LENGTH).isNotEqualTo(COLLIDING_LAYOUT_CLUSTER_KEY_LENGTH);
        }

        @Test
        @DisplayName("the mapper is chosen by the caller from the data set it read and never by "
                + "inspecting bytes: an image shaped like the other 60-byte layout decodes without "
                + "complaint, straight into the wrong window, which is the whole reason no sniffing "
                + "may be added")
        void theMapperIsChosenByTheCallerAndNeverBySniffingBytes() {
            // Shaped exactly like the other 60-byte layout: a 2-byte type code, a 4-byte category
            // code, a 50-byte description and a 4-byte filler run. Assembled here only to be handed
            // to the wrong mapper on purpose; none of these widths is ever used as an offset against
            // this layout.
            final String otherLayoutImage = SEED_CODES.get(1)
                    + "0001"
                    + paddedDescription(0)
                    + String.valueOf(FIXTURE_FILLER_CHARACTER).repeat(COLLIDING_LAYOUT_FILLER_LENGTH);
            assertThat(encodedByteLength(otherLayoutImage)).isEqualTo(RECORD_LENGTH);

            final TransactionType misread = TranTypeRecordMapper.fromRecord(otherLayoutImage);

            // Reading at this layout's offsets, the description window swallows the category code and
            // loses the last four bytes of the text. Hand-derived: the 4-byte category code followed
            // by the seed text padded out to the remaining 46 bytes.
            final String windowReadAtThisLayoutsOffsets =
                    "0001" + padToWidth(SEED_DESCRIPTIONS.get(0),
                            DESCRIPTION_LENGTH - COLLIDING_LAYOUT_CATEGORY_CODE_LENGTH);
            assertThat(encodedByteLength(windowReadAtThisLayoutsOffsets))
                    .isEqualTo(DESCRIPTION_LENGTH);

            assertThat(misread.getTranType()).isEqualTo(SEED_CODES.get(1));
            assertThat(misread.getTranTypeDesc()).isEqualTo(windowReadAtThisLayoutsOffsets);
            assertThat(misread.getTranTypeDesc()).isNotEqualTo(paddedDescription(0));
        }
    }

    @Nested
    @DisplayName("decoding a record image")
    class DecodingARecordImage {

        @Test
        @DisplayName("fixture row 0 decodes to the code 01 and to the description Purchase padded with "
                + "spaces to exactly 50 encoded bytes")
        void theFirstFixtureRowDecodesToItsHandWrittenExpectation() {
            final TransactionType decoded = TranTypeRecordMapper.fromRecord(fixtureImage(0));

            assertThat(decoded.getTranType()).isEqualTo("01");
            assertThat(encodedByteLength(decoded.getTranType())).isEqualTo(CODE_LENGTH);
            assertThat(decoded.getTranTypeDesc()).isEqualTo(ROW_0_DESCRIPTION_IMAGE);
            assertThat(encodedByteLength(decoded.getTranTypeDesc())).isEqualTo(DESCRIPTION_LENGTH);
            assertThat(decoded.getTranTypeDesc()).startsWith("Purchase");
        }

        @Test
        @DisplayName("all seven fixture rows decode to the codes 01 through 07 with their own "
                + "descriptions, each description exactly 50 encoded bytes wide")
        void allSevenFixtureRowsDecodeToTheirHandWrittenExpectations() {
            assertThat(SEED_CODES).hasSize(SEEDED_ROW_COUNT);
            assertThat(SEED_DESCRIPTIONS).hasSize(SEEDED_ROW_COUNT);

            for (int rowIndex = 0; rowIndex < SEEDED_ROW_COUNT; rowIndex++) {
                final TransactionType decoded =
                        TranTypeRecordMapper.fromRecord(fixtureImage(rowIndex));

                assertThat(decoded.getTranType())
                        .as(rowLabel(rowIndex) + " code")
                        .isEqualTo(SEED_CODES.get(rowIndex));
                assertThat(encodedByteLength(decoded.getTranType()))
                        .as(rowLabel(rowIndex) + " code width")
                        .isEqualTo(CODE_LENGTH);
                assertThat(decoded.getTranTypeDesc())
                        .as(rowLabel(rowIndex) + " description")
                        .isEqualTo(paddedDescription(rowIndex));
                assertThat(encodedByteLength(decoded.getTranTypeDesc()))
                        .as(rowLabel(rowIndex) + " description width")
                        .isEqualTo(DESCRIPTION_LENGTH);
            }
        }

        @Test
        @DisplayName("the two-character code keeps its leading zero: 01 decodes as 01 and never as 1, "
                + "because the code is carried as text and is never narrowed to a number")
        void theTwoCharacterCodeKeepsItsLeadingZero() {
            final TransactionType decoded = TranTypeRecordMapper.fromRecord(fixtureImage(0));

            assertThat(decoded.getTranType()).isEqualTo("01").isNotEqualTo("1");
            assertThat(decoded.getTranType().charAt(0)).isEqualTo('0');
            assertThat(encodedByteLength(decoded.getTranType())).isEqualTo(CODE_LENGTH);

            for (int rowIndex = 0; rowIndex < SEEDED_ROW_COUNT; rowIndex++) {
                assertThat(TranTypeRecordMapper.fromRecord(fixtureImage(rowIndex)).getTranType())
                        .as(rowLabel(rowIndex) + " retains its leading zero")
                        .startsWith("0");
            }
        }

        @Test
        @DisplayName("the description survives at its full 50 bytes and is not the right-trimmed form "
                + "the seeded column stores, because trailing spaces are contractual here")
        void theDescriptionSurvivesUntrimmed() {
            for (int rowIndex = 0; rowIndex < SEEDED_ROW_COUNT; rowIndex++) {
                final TransactionType decoded =
                        TranTypeRecordMapper.fromRecord(fixtureImage(rowIndex));

                assertThat(encodedByteLength(decoded.getTranTypeDesc()))
                        .as(rowLabel(rowIndex) + " description width")
                        .isEqualTo(DESCRIPTION_LENGTH);
                // The seed form IS the right-trimmed form, so refusing to equal it proves the padded
                // value survived - and proves it without this class ever narrowing a value itself.
                assertThat(decoded.getTranTypeDesc())
                        .as(rowLabel(rowIndex) + " is not the right-trimmed seed form")
                        .isNotEqualTo(SEED_DESCRIPTIONS.get(rowIndex));
                assertThat(decoded.getTranTypeDesc().charAt(DESCRIPTION_LENGTH - 1))
                        .as(rowLabel(rowIndex) + " final description byte")
                        .isEqualTo(' ');
                assertThat(range(encoded(decoded.getTranTypeDesc()), DESCRIPTION_LENGTH - 1,
                        DESCRIPTION_LENGTH))
                        .as(rowLabel(rowIndex) + " final description byte, encoded")
                        .containsExactly(EMITTED_FILLER_BYTE);
            }
        }

        @Test
        @DisplayName("no enumeration translates the code: an out-of-set two-character value such as ZZ "
                + "is accepted and carried through unchanged")
        void anOutOfSetCodeIsCarriedThroughUnchanged() {
            final String image =
                    OUT_OF_SET_CODE + paddedDescription(0) + fixtureFillerRun();
            assertThat(encodedByteLength(image)).isEqualTo(RECORD_LENGTH);

            final TransactionType decoded = TranTypeRecordMapper.fromRecord(image);

            assertThat(decoded.getTranType()).isEqualTo(OUT_OF_SET_CODE);
            assertThat(decoded.getTranTypeDesc()).isEqualTo(paddedDescription(0));
            assertThat(SEED_CODES).doesNotContain(OUT_OF_SET_CODE);
            assertThat(range(encoded(TranTypeRecordMapper.toRecord(decoded)), CODE_OFFSET,
                    MAPPED_PREFIX_LENGTH))
                    .isEqualTo(encoded(OUT_OF_SET_CODE + paddedDescription(0)));
        }

        @Test
        @DisplayName("the string, byte-array and byte-range entry points produce equal entities with "
                + "identical values in both properties")
        void theThreeDecodeEntryPointsAgree() {
            final String image = fixtureImage(0);
            final byte[] framed = fixtureBuffer();

            final TransactionType fromText = TranTypeRecordMapper.fromRecord(image);
            final TransactionType fromBytes = TranTypeRecordMapper.fromRecord(encoded(image));
            final TransactionType fromRange =
                    TranTypeRecordMapper.fromRecord(framed, CODE_OFFSET);

            assertThat(fromBytes).isEqualTo(fromText);
            assertThat(fromRange).isEqualTo(fromText);
            assertThat(fromBytes).hasSameHashCodeAs(fromText);
            assertThat(fromRange).hasSameHashCodeAs(fromText);
            assertThat(fromBytes.getTranType()).isEqualTo(fromText.getTranType());
            assertThat(fromRange.getTranType()).isEqualTo(fromText.getTranType());
            assertThat(fromBytes.getTranTypeDesc()).isEqualTo(fromText.getTranTypeDesc());
            assertThat(fromRange.getTranTypeDesc()).isEqualTo(fromText.getTranTypeDesc());
        }

        @Test
        @DisplayName("a whole-file buffer decodes row by row at the 61-byte stride, and 7 x 61 = 427 "
                + "bytes accounts for the shipped fixture exactly, the extra byte per row being the "
                + "terminator and never record content")
        void theWholeFixtureBufferDecodesRowByRowAtTheDeclaredStride() {
            assertThat(FIXTURE_STRIDE).isEqualTo(RECORD_LENGTH + 1);
            assertThat(SEEDED_ROW_COUNT * FIXTURE_STRIDE).isEqualTo(FIXTURE_TOTAL_BYTES);

            final byte[] buffer = fixtureBuffer();
            assertThat(buffer).hasSize(FIXTURE_TOTAL_BYTES);

            for (int rowIndex = 0; rowIndex < SEEDED_ROW_COUNT; rowIndex++) {
                final TransactionType decoded =
                        TranTypeRecordMapper.fromRecord(buffer, rowIndex * FIXTURE_STRIDE);

                assertThat(decoded.getTranType())
                        .as(rowLabel(rowIndex) + " code read from the buffer")
                        .isEqualTo(SEED_CODES.get(rowIndex));
                assertThat(decoded.getTranTypeDesc())
                        .as(rowLabel(rowIndex) + " description read from the buffer")
                        .isEqualTo(paddedDescription(rowIndex));
            }
        }

        @Test
        @DisplayName("the reference table carries exactly seven rows with seven distinct codes, which "
                + "is factual layout evidence about a reference data set and not a service level")
        void theSeededRowCountIsSeven() {
            assertThat(SEEDED_ROW_COUNT).isEqualTo(7);
            assertThat(SEED_CODES).hasSize(SEEDED_ROW_COUNT).doesNotHaveDuplicates();
            assertThat(SEED_DESCRIPTIONS).hasSize(SEEDED_ROW_COUNT).doesNotHaveDuplicates();
            assertThat(SEED_CODES)
                    .containsExactly("01", "02", "03", "04", "05", "06", "07");
            assertThat(SEED_DESCRIPTIONS).containsExactly(
                    "Purchase", "Payment", "Credit", "Authorization", "Refund", "Reversal",
                    "Adjustment");
        }
    }

    @Nested
    @DisplayName("encoding an entity")
    class EncodingAnEntity {

        @Test
        @DisplayName("the public two-argument constructor takes the code first and the description "
                + "second, matching copybook order: each value lands in its own field")
        void theAllArgumentConstructorPlacesTheTwoFieldsInCopybookOrder() {
            // Built through the public all-argument constructor on purpose. Were the two arguments
            // transposed, the 50-byte description could not be placed into the 2-byte code field and
            // the encode would be refused outright rather than producing a wrong record.
            final TransactionType subject = new TransactionType("01", ROW_0_DESCRIPTION_IMAGE);

            final byte[] emitted = TranTypeRecordMapper.toRecordBytes(subject);

            assertThat(range(emitted, CODE_OFFSET, CODE_OFFSET + CODE_LENGTH))
                    .isEqualTo(encoded("01"));
            assertThat(range(emitted, DESCRIPTION_OFFSET, DESCRIPTION_OFFSET + DESCRIPTION_LENGTH))
                    .isEqualTo(encoded(ROW_0_DESCRIPTION_IMAGE));
        }

        @Test
        @DisplayName("the mapped prefix from 0 up to but excluding 52 round-trips byte for byte under "
                + "US-ASCII for every one of the seven fixture rows")
        void theMappedPrefixRoundTripsByteForByte() {
            for (int rowIndex = 0; rowIndex < SEEDED_ROW_COUNT; rowIndex++) {
                final byte[] expectedPrefix = encoded(mappedPrefix(rowIndex));
                assertThat(expectedPrefix)
                        .as(rowLabel(rowIndex) + " expected prefix width")
                        .hasSize(MAPPED_PREFIX_LENGTH);

                final TransactionType decoded =
                        TranTypeRecordMapper.fromRecord(fixtureImage(rowIndex));
                final byte[] emitted = encoded(TranTypeRecordMapper.toRecord(decoded));

                assertThat(range(emitted, CODE_OFFSET, MAPPED_PREFIX_LENGTH))
                        .as(rowLabel(rowIndex) + " mapped prefix")
                        .isEqualTo(expectedPrefix);
            }
        }

        @Test
        @DisplayName("the emitted image is always exactly 60 encoded bytes and its bytes 52 to 59 are "
                + "every one of them 0x20")
        void theFullImageIsSixtyBytesWithSpaceFiller() {
            for (int rowIndex = 0; rowIndex < SEEDED_ROW_COUNT; rowIndex++) {
                final TransactionType decoded =
                        TranTypeRecordMapper.fromRecord(fixtureImage(rowIndex));
                final String emitted = TranTypeRecordMapper.toRecord(decoded);

                assertThat(encodedByteLength(emitted))
                        .as(rowLabel(rowIndex) + " emitted width")
                        .isEqualTo(RECORD_LENGTH);
                assertThat(encoded(emitted))
                        .as(rowLabel(rowIndex) + " emitted byte count")
                        .hasSize(RECORD_LENGTH)
                        .isEqualTo(encoded(emittedImage(rowIndex)));
                assertThat(range(encoded(emitted), FILLER_OFFSET, RECORD_LENGTH))
                        .as(rowLabel(rowIndex) + " emitted filler run")
                        .hasSize(FILLER_LENGTH)
                        .containsOnly(EMITTED_FILLER_BYTE);

                for (int index = FILLER_OFFSET; index < RECORD_LENGTH; index++) {
                    assertThat(encoded(emitted)[index])
                            .as(rowLabel(rowIndex) + " filler byte at index " + index)
                            .isEqualTo(EMITTED_FILLER_BYTE);
                }
            }
        }

        @Test
        @DisplayName("the byte-emitting entry point returns the same 60 bytes as the string one, and "
                + "returns a fresh array the caller may mutate without affecting a later call")
        void theByteEmitterAgreesWithTheStringEmitterAndReturnsAFreshArray() {
            final TransactionType subject = TranTypeRecordMapper.fromRecord(fixtureImage(0));

            final byte[] first = TranTypeRecordMapper.toRecordBytes(subject);
            assertThat(first).hasSize(RECORD_LENGTH)
                    .isEqualTo(encoded(emittedImage(0)))
                    .isEqualTo(encoded(TranTypeRecordMapper.toRecord(subject)));

            Arrays.fill(first, (byte) 0x21);
            final byte[] second = TranTypeRecordMapper.toRecordBytes(subject);

            assertThat(second).isNotEqualTo(first)
                    .isEqualTo(encoded(emittedImage(0)))
                    .isEqualTo(encoded(TranTypeRecordMapper.toRecord(subject)));
        }

        @Test
        @DisplayName("a description shorter than the field is padded on the right to the full 50 bytes "
                + "rather than stored short, and reads back with all of that padding")
        void aShortDescriptionIsPaddedToTheFullFieldWidth() {
            final String shortDescription = SEED_DESCRIPTIONS.get(2);
            final TransactionType subject = new TransactionType(SEED_CODES.get(2), shortDescription);

            final String emitted = TranTypeRecordMapper.toRecord(subject);

            assertThat(encodedByteLength(emitted)).isEqualTo(RECORD_LENGTH);
            assertThat(range(encoded(emitted), DESCRIPTION_OFFSET, MAPPED_PREFIX_LENGTH))
                    .isEqualTo(encoded(padToWidth(shortDescription, DESCRIPTION_LENGTH)));
            assertThat(TranTypeRecordMapper.fromRecord(emitted).getTranTypeDesc())
                    .isEqualTo(padToWidth(shortDescription, DESCRIPTION_LENGTH))
                    .isNotEqualTo(shortDescription);
        }

        @Test
        @DisplayName("a description that exactly fills the 50-byte field round-trips unchanged, with no "
                + "truncation and no pad byte added")
        void aDescriptionThatExactlyFillsTheFieldRoundTripsUnchanged() {
            assertThat(encodedByteLength(EXACT_WIDTH_DESCRIPTION)).isEqualTo(DESCRIPTION_LENGTH);
            final TransactionType subject =
                    new TransactionType(SEED_CODES.get(0), EXACT_WIDTH_DESCRIPTION);

            final String emitted = TranTypeRecordMapper.toRecord(subject);

            assertThat(encodedByteLength(emitted)).isEqualTo(RECORD_LENGTH);
            assertThat(range(encoded(emitted), DESCRIPTION_OFFSET, MAPPED_PREFIX_LENGTH))
                    .isEqualTo(encoded(EXACT_WIDTH_DESCRIPTION));
            assertThat(TranTypeRecordMapper.fromRecord(emitted).getTranTypeDesc())
                    .isEqualTo(EXACT_WIDTH_DESCRIPTION);
            assertThat(range(encoded(emitted), FILLER_OFFSET, RECORD_LENGTH))
                    .containsOnly(EMITTED_FILLER_BYTE);
        }
    }

    @Nested
    @DisplayName("the filler run, where the fixture and the mapper disagree by design")
    class TheFillerRun {

        @Test
        @DisplayName("the shipped fixture carries ASCII zero in bytes 52 to 59 while the mapper writes "
                + "0x20 there: a deliberate divergence, so the two images differ on the whole record "
                + "and agree only on the mapped prefix")
        void theFixtureFillerIsAsciiZeroWhileTheMapperWritesSpaces() {
            assertThat(FIXTURE_FILLER_BYTE).isNotEqualTo(EMITTED_FILLER_BYTE);
            assertThat(encoded(String.valueOf(FIXTURE_FILLER_CHARACTER)))
                    .containsExactly(FIXTURE_FILLER_BYTE);

            for (int rowIndex = 0; rowIndex < SEEDED_ROW_COUNT; rowIndex++) {
                final String image = fixtureImage(rowIndex);
                final String emitted =
                        TranTypeRecordMapper.toRecord(TranTypeRecordMapper.fromRecord(image));

                assertThat(range(encoded(image), FILLER_OFFSET, RECORD_LENGTH))
                        .as(rowLabel(rowIndex) + " fixture filler run")
                        .hasSize(FILLER_LENGTH)
                        .containsOnly(FIXTURE_FILLER_BYTE);
                assertThat(range(encoded(emitted), FILLER_OFFSET, RECORD_LENGTH))
                        .as(rowLabel(rowIndex) + " emitted filler run")
                        .hasSize(FILLER_LENGTH)
                        .containsOnly(EMITTED_FILLER_BYTE);

                // The divergence is the contract, not a defect: the whole record differs, the mapped
                // prefix does not. This is why a 60-byte equality against the fixture is never
                // asserted anywhere in this class, and why the fixture is never "corrected" either.
                assertThat(emitted)
                        .as(rowLabel(rowIndex) + " whole record differs from the fixture")
                        .isNotEqualTo(image);
                assertThat(range(encoded(emitted), CODE_OFFSET, MAPPED_PREFIX_LENGTH))
                        .as(rowLabel(rowIndex) + " mapped prefix agrees with the fixture")
                        .isEqualTo(range(encoded(image), CODE_OFFSET, MAPPED_PREFIX_LENGTH));
            }
        }
    }

    @Nested
    @DisplayName("rejecting malformed input")
    class RejectingMalformedInput {

        @Test
        @DisplayName("a 59-byte image is refused rather than silently padded, and the message names the "
                + "artefact, the expected width 60 and the actual length 59")
        void aFiftyNineByteImageIsRejectedNamingTheArtefactAndBothWidths() {
            // One filler byte short of the declared width: 2 + 50 + 7 = 59.
            final String tooShort = mappedPrefix(0)
                    + String.valueOf(FIXTURE_FILLER_CHARACTER).repeat(FILLER_LENGTH - 1);
            assertThat(encodedByteLength(tooShort)).isEqualTo(RECORD_LENGTH - 1);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> TranTypeRecordMapper.fromRecord(tooShort))
                    .withMessageContaining("TRAN-TYPE-RECORD")
                    .withMessageContaining("CVTRA03Y")
                    .withMessageContaining(String.valueOf(RECORD_LENGTH))
                    .withMessageContaining(String.valueOf(RECORD_LENGTH - 1));
        }

        @Test
        @DisplayName("a 61-byte image is refused rather than silently truncated, whether the extra byte "
                + "is an unstripped line terminator or ordinary content")
        void aSixtyOneByteImageIsRejected() {
            final String withTerminator = fixtureImage(0) + "\n";
            final String withExtraContent =
                    mappedPrefix(0) + String.valueOf(FIXTURE_FILLER_CHARACTER).repeat(FILLER_LENGTH + 1);
            assertThat(encodedByteLength(withTerminator)).isEqualTo(RECORD_LENGTH + 1);
            assertThat(encodedByteLength(withExtraContent)).isEqualTo(RECORD_LENGTH + 1);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> TranTypeRecordMapper.fromRecord(withTerminator))
                    .withMessageContaining("TRAN-TYPE-RECORD")
                    .withMessageContaining(String.valueOf(RECORD_LENGTH))
                    .withMessageContaining(String.valueOf(RECORD_LENGTH + 1));
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> TranTypeRecordMapper.fromRecord(withExtraContent))
                    .withMessageContaining(String.valueOf(RECORD_LENGTH + 1));
        }

        @Test
        @DisplayName("an empty image is refused, because a fixed-width record is never padded up to fit")
        void anEmptyImageIsRejected() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> TranTypeRecordMapper.fromRecord(""))
                    .withMessageContaining(String.valueOf(RECORD_LENGTH));
        }

        @Test
        @DisplayName("the byte-array entry point applies the same width rule, refusing both a 59-byte "
                + "and a 61-byte array")
        void theByteArrayEntryPointRejectsAWrongLength() {
            final byte[] tooShort = new byte[RECORD_LENGTH - 1];
            final byte[] tooLong = new byte[RECORD_LENGTH + 1];
            Arrays.fill(tooShort, EMITTED_FILLER_BYTE);
            Arrays.fill(tooLong, EMITTED_FILLER_BYTE);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> TranTypeRecordMapper.fromRecord(tooShort))
                    .withMessageContaining(String.valueOf(RECORD_LENGTH - 1));
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> TranTypeRecordMapper.fromRecord(tooLong))
                    .withMessageContaining(String.valueOf(RECORD_LENGTH + 1));
        }

        @Test
        @DisplayName("the byte-range entry point refuses a negative start index and a buffer that "
                + "cannot supply 60 bytes from the given position, rather than short-reading")
        void theByteRangeEntryPointRejectsANegativeStartAndAnUndersizedBuffer() {
            final byte[] buffer = fixtureBuffer();

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> TranTypeRecordMapper.fromRecord(buffer, -1))
                    .withMessageContaining("TRAN-TYPE-RECORD");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> TranTypeRecordMapper.fromRecord(buffer,
                            FIXTURE_TOTAL_BYTES - RECORD_LENGTH + 1))
                    .withMessageContaining(String.valueOf(RECORD_LENGTH));
        }

        @Test
        @DisplayName("null is refused on every entry point, decode and encode alike, rather than "
                + "yielding a partial entity or a partial record")
        void nullIsRejectedOnEveryEntryPoint() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> TranTypeRecordMapper.fromRecord((String) null));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> TranTypeRecordMapper.fromRecord((byte[]) null));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> TranTypeRecordMapper.fromRecord((byte[]) null, CODE_OFFSET));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> TranTypeRecordMapper.toRecord(null));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> TranTypeRecordMapper.toRecordBytes(null));
        }

        @Test
        @DisplayName("a value wider than its field is refused rather than truncated, because a "
                + "truncated field leaves the record the right width and the wrong content")
        void aValueWiderThanItsFieldIsRejectedRatherThanTruncated() {
            final String oversizedDescription = padToWidth(SEED_DESCRIPTIONS.get(0),
                    DESCRIPTION_LENGTH) + " ";
            assertThat(encodedByteLength(oversizedDescription)).isEqualTo(DESCRIPTION_LENGTH + 1);
            final TransactionType wideDescription =
                    new TransactionType(SEED_CODES.get(0), oversizedDescription);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> TranTypeRecordMapper.toRecord(wideDescription))
                    .withMessageContaining("TRAN-TYPE-DESC")
                    .withMessageContaining(String.valueOf(DESCRIPTION_LENGTH))
                    .withMessageContaining(String.valueOf(DESCRIPTION_LENGTH + 1));

            final TransactionType wideCode =
                    new TransactionType("001", ROW_0_DESCRIPTION_IMAGE);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> TranTypeRecordMapper.toRecordBytes(wideCode))
                    .withMessageContaining("field 'TRAN-TYPE'")
                    .withMessageContaining(String.valueOf(CODE_LENGTH))
                    .withMessageContaining(String.valueOf(CODE_LENGTH + 1));
        }

        @Test
        @DisplayName("a null attribute is refused on encode and the message names the attribute at "
                + "fault, so neither field can be silently rendered as spaces")
        void aNullAttributeIsRejectedNamingTheAttribute() {
            final TransactionType withoutCode =
                    new TransactionType(null, ROW_0_DESCRIPTION_IMAGE);
            final TransactionType withoutDescription =
                    new TransactionType(SEED_CODES.get(0), null);

            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> TranTypeRecordMapper.toRecord(withoutCode))
                    .withMessageContaining("tranType");
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> TranTypeRecordMapper.toRecordBytes(withoutDescription))
                    .withMessageContaining("tranTypeDesc");
        }
    }

    @Nested
    @DisplayName("the entity contract behind the mapping")
    class TheEntityContract {

        @Test
        @DisplayName("this is the only reference entity in its family with a single-column identifier "
                + "and no composite-key class: the key is a bare two-byte field, so the entity exposes "
                + "exactly two business properties and nothing from the identifier-class package")
        void theEntityCarriesExactlyTwoBusinessPropertiesUnderASingleColumnKey() {
            // The two mapped properties are enumerated by exercising both accessors and nothing else.
            // No reflective probing is used to count them, deliberately: the eleven record mappers are
            // written by hand precisely so that the module needs no reflective access at all, and a
            // test that introspected the entity would undermine the very property it is guarding.
            //
            // The contrast that makes this worth asserting: the other 60-byte reference layout keys on
            // a two-part group whose cluster declares 6 bytes, so it needs a composite-key class. This
            // layout keys on a bare 2-byte field, declares KEYS(2 0), and needs none. Nothing in this
            // file names, imports or constructs an identifier object of any kind.
            final TransactionType subject =
                    new TransactionType(SEED_CODES.get(0), ROW_0_DESCRIPTION_IMAGE);

            assertThat(subject.getTranType()).isEqualTo(SEED_CODES.get(0));
            assertThat(subject.getTranTypeDesc()).isEqualTo(ROW_0_DESCRIPTION_IMAGE);

            // Both properties, and only those two, are what the record image carries: their widths sum
            // to the mapped prefix, and the eight remaining bytes are filler that no property
            // represents.
            assertThat(encodedByteLength(subject.getTranType())
                    + encodedByteLength(subject.getTranTypeDesc()))
                    .isEqualTo(MAPPED_PREFIX_LENGTH);
            assertThat(MAPPED_PREFIX_LENGTH + FILLER_LENGTH).isEqualTo(RECORD_LENGTH);
        }

        @Test
        @DisplayName("both setters assign verbatim, adding no padding and removing none, so a value "
                + "placed on the entity survives a round trip through the mapper unchanged")
        void bothSettersAssignVerbatim() {
            final TransactionType subject =
                    new TransactionType(SEED_CODES.get(0), ROW_0_DESCRIPTION_IMAGE);

            subject.setTranType(SEED_CODES.get(6));
            subject.setTranTypeDesc(paddedDescription(6));

            assertThat(subject.getTranType()).isEqualTo(SEED_CODES.get(6));
            assertThat(subject.getTranTypeDesc()).isEqualTo(paddedDescription(6));
            assertThat(encodedByteLength(subject.getTranTypeDesc())).isEqualTo(DESCRIPTION_LENGTH);
            assertThat(range(encoded(TranTypeRecordMapper.toRecord(subject)), CODE_OFFSET,
                    MAPPED_PREFIX_LENGTH))
                    .isEqualTo(encoded(mappedPrefix(6)));
        }

        @Test
        @DisplayName("identity rests on the two-byte key alone, so two rows with the same code are the "
                + "same row whatever their descriptions say, and no leading zero is normalised away")
        void identityRestsOnTheKeyAlone() {
            final TransactionType first =
                    new TransactionType(SEED_CODES.get(0), ROW_0_DESCRIPTION_IMAGE);
            final TransactionType sameKeyOtherDescription =
                    new TransactionType(SEED_CODES.get(0), paddedDescription(4));
            final TransactionType otherKey =
                    new TransactionType(SEED_CODES.get(1), ROW_0_DESCRIPTION_IMAGE);
            final TransactionType narrowedKey =
                    new TransactionType("1", ROW_0_DESCRIPTION_IMAGE);

            assertThat(first).isEqualTo(sameKeyOtherDescription)
                    .hasSameHashCodeAs(sameKeyOtherDescription);
            assertThat(first).isNotEqualTo(otherKey);
            assertThat(first).isNotEqualTo(narrowedKey);
            assertThat(first).isEqualTo(first);
        }
    }
}
