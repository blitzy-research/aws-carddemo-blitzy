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
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.carddemo.domain.CardCrossReference;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit test for {@link CardXrefRecordMapper}, the two-way mapping between the legacy card
 * cross-reference record and {@link CardCrossReference}.
 *
 * <p><strong>The one fact this file exists to pin down: this record has two live physical widths,
 * and both are correct.</strong> The copybook {@code app/cpy/CVACT03Y.cpy} declares a 50-byte group
 * of which only 36 bytes carry information - a 16-byte card number at offset 0, a 9-byte customer
 * identifier at offset 16 and an 11-byte account identifier at offset 25 - followed by a 14-byte
 * unnamed filler run at offset 36 that no column represents. The same fifty logical rows ship twice:
 * the text fixture {@code app/data/ASCII/cardxref.txt} omits the filler entirely and is therefore
 * written at 36 bytes per row, while the sequential dataset
 * {@code app/data/EBCDIC/AWS.M2.CARDDEMO.CARDXREF.PS} carries it and is written at 50. Neither file
 * is wrong and neither is repaired.
 *
 * <p><strong>Why that matters enough to test deliberately.</strong> A reader that advances through
 * the 36-byte text fixture at a 50-byte stride is 14 bytes out of step from the second row onward
 * and misparses every row after the first. The failure is silent: it yields a card number assembled
 * from the tail of one row and the head of the next, which is plausible enough to survive review and
 * wrong in every row it touches. Nothing below ever pads a 36-byte image out to 50 in a read
 * expectation, and the two strides are asserted separately and by name.
 *
 * <p><strong>Every expectation in this class is hand written.</strong> No assertion asks the mapper,
 * the slicing primitive or the entity to compute the value it is then compared against. The offsets
 * and widths were read from the copybook clauses; the record and key geometry were corroborated
 * independently by the base cluster definition in {@code app/jcl/XREFFILE.jcl}, which declares
 * {@code KEYS(16 0)} with {@code RECORDSIZE(50 50)} on an {@code INDEXED} cluster; and the third
 * field's position was corroborated a third time by the {@code CXACAIX} alternate index in that same
 * job stream, declared {@code KEYS(11 25)} {@code NONUNIQUEKEY} {@code UPGRADE}. Each of those
 * numbers is restated here as an independent literal, so a wrong constant in the mapper disagrees
 * with this file rather than being confirmed by it.
 *
 * <p><strong>The two fixture rows below were transcribed by hand from the committed text fixture at
 * its own 36-byte stride</strong>, and their field values were sliced by hand at the offsets in the
 * copybook rather than by calling anything under test. Row 0 also establishes cross-file agreement:
 * its account identifier is byte-identical to the one {@code app/data/ASCII/carddata.txt} carries at
 * offset 16 for 11 bytes on its own row 0, and its card number is byte-identical to that file's
 * leading 16 bytes. The two files describe the same card, which is what makes the 36-byte reading
 * demonstrably the correct one rather than merely the one that happens to divide evenly.
 *
 * <p><strong>Every identifier is text and none is ever a number.</strong> All three properties are
 * {@link String}. Their leading zeros are contractual and are asserted intact. This layout contains
 * no zoned-decimal field - every item is {@code PIC X(n)} or unsigned {@code PIC 9(n)} - so no
 * decimal codec participates, no arbitrary-precision decimal type appears, and no scale or rounding
 * decision arises anywhere in this file.
 *
 * <p><strong>Scope, stated so a reader is not left looking for what is absent.</strong> This is a
 * pure unit test: no container, no Spring context, no persistence provider, no repository, no
 * network and no filesystem. It asserts nothing about referential integrity either. A note is worth
 * recording so a reviewer does not propagate an error found in the surrounding documentation: the
 * claim that the schema declares no foreign keys is wrong - {@code V2__create_indexes.sql} creates
 * six, three of them originating from this table - but that is a database fact with no bearing on a
 * mapper that only decodes and encodes bytes, so it is recorded here as a correction and asserted
 * nowhere.
 *
 * <p>The translation decisions this file demonstrates, each recorded in
 * {@code docs/decision-log.md}: two accepted read widths against one canonical write width; the text
 * fixture's absent filler and the 36-byte stride that follows from it; space filler emitted
 * uniformly on write; the deliberately abbreviated mapper name, which is
 * {@code CardXrefRecordMapper} while the entity it maps is the fully spelled
 * {@link CardCrossReference}; and the corrected six-foreign-key figure noted above.
 *
 * <p><strong>Provenance.</strong> Legacy checkout SHA 7756d895ffeb65f7ea72aaa609e356d9899afcec;
 * upstream release stamp CardDemo_v1.0-15-g27d6c6f-68 (2022-07-19). No copybook, program or
 * job-stream text is reproduced here: traceability is carried by citation of member names, field
 * names, pictures, widths, offsets and counts only.
 */
@DisplayName("CardXrefRecordMapper - one card cross-reference record at two live widths (CVACT03Y)")
class CardXrefRecordMapperTest {

    // ----------------------------------------------------------------------------------------
    // Geometry, transcribed by hand from the copybook clauses and corroborated by the cluster
    // definition. Every one of these is an independent literal, never a reference to the constant
    // it checks.
    // ----------------------------------------------------------------------------------------

    /** Offset of the card number, which is also the base cluster's key offset, {@code KEYS(16 0)}. */
    private static final int CARD_NUM_OFFSET = 0;

    /** Width of the card number, {@code PIC X(16)}, which is also the cluster's key length. */
    private static final int CARD_NUM_LENGTH = 16;

    /** Offset of the customer identifier: the card number's width, so the fields are contiguous. */
    private static final int CUST_ID_OFFSET = 16;

    /** Width of the customer identifier, {@code PIC 9(09)}. */
    private static final int CUST_ID_LENGTH = 9;

    /**
     * Offset of the account identifier. The {@code CXACAIX} alternate index keys this same field at
     * {@code KEYS(11 25)}, which is the third independent attestation of the number 25.
     */
    private static final int ACCT_ID_OFFSET = 25;

    /** Width of the account identifier, {@code PIC 9(11)}, and the alternate index's key length. */
    private static final int ACCT_ID_LENGTH = 11;

    /**
     * The data-only width: {@code 16 + 9 + 11}. This is the width the committed text fixture is
     * written at, with the filler absent entirely, and it is a first-class accepted width rather
     * than a degraded form of the declared one.
     */
    private static final int DATA_WIDTH = 36;

    /** Offset at which the unmapped filler run begins, which is the end of the data prefix. */
    private static final int FILLER_OFFSET = 36;

    /** Width of the unmapped filler run, {@code PIC X(14)}. */
    private static final int FILLER_WIDTH = 14;

    /**
     * The canonical declared width, {@code 36 + 14}, which is also the {@code RECORDSIZE(50 50)} the
     * base cluster is defined with. Identical low and high record sizes there are a further
     * confirmation that the layout is fixed length.
     */
    private static final int RECORD_WIDTH = 50;

    /** Byte written across the filler run on the encode path: an ASCII space, never a zero. */
    private static final byte ASCII_SPACE = (byte) 0x20;

    /** ASCII zero, used only to prove that a foreign filler byte is ignored on the read path. */
    private static final char ASCII_ZERO_CHARACTER = '0';

    // ----------------------------------------------------------------------------------------
    // Artefact arithmetic. The two shipped files differ by exactly the filler, less the text
    // fixture's terminator, and both totals are stated as literals so neither is inferred.
    // ----------------------------------------------------------------------------------------

    /** Rows carried by both artefacts. */
    private static final int FIXTURE_ROW_COUNT = 50;

    /** Stride of the text fixture: the data width plus one {@code 0x0A} terminator per row. */
    private static final int TEXT_FIXTURE_STRIDE = 37;

    /** Total size of {@code app/data/ASCII/cardxref.txt}, which is 50 rows at a 37-byte stride. */
    private static final int TEXT_FIXTURE_BYTE_COUNT = 1850;

    /**
     * Total size of {@code app/data/EBCDIC/AWS.M2.CARDDEMO.CARDXREF.PS}, which is the same 50 rows
     * at the declared 50-byte width with no terminator at all.
     */
    private static final int SEQUENTIAL_DATASET_BYTE_COUNT = 2500;

    // ----------------------------------------------------------------------------------------
    // Fixture row 0, transcribed by hand from app/data/ASCII/cardxref.txt at its 36-byte stride,
    // then sliced by hand at the copybook offsets. The three field literals concatenate back to
    // the row image, which is stated as an assertion below rather than assumed here.
    // ----------------------------------------------------------------------------------------

    /** Row 0 of the committed text fixture, exactly 36 bytes, terminator excluded. */
    private static final String ROW_0_IMAGE = "050002445376574000000005000000000050";

    /** Row 0 bytes 0 through 15: the card number, whose leading zero is significant. */
    private static final String ROW_0_CARD_NUM = "0500024453765740";

    /** Row 0 bytes 16 through 24: the customer identifier, seven leading zeros included. */
    private static final String ROW_0_CUST_ID = "000000050";

    /** Row 0 bytes 25 through 35: the account identifier, nine leading zeros included. */
    private static final String ROW_0_ACCT_ID = "00000000050";

    // ----------------------------------------------------------------------------------------
    // Fixture row 1, transcribed the same way. A second real row is what turns "the offsets work"
    // into "the offsets work on data they were not chosen against".
    // ----------------------------------------------------------------------------------------

    /** Row 1 of the committed text fixture, exactly 36 bytes, terminator excluded. */
    private static final String ROW_1_IMAGE = "068358619817151600000002700000000027";

    /** Row 1 bytes 0 through 15: the card number. */
    private static final String ROW_1_CARD_NUM = "0683586198171516";

    /** Row 1 bytes 16 through 24: the customer identifier. */
    private static final String ROW_1_CUST_ID = "000000027";

    /** Row 1 bytes 25 through 35: the account identifier. */
    private static final String ROW_1_ACCT_ID = "00000000027";

    /**
     * The 14 spaces the encode path writes across the filler run, written as its own literal so the
     * expected filler is a value in this file rather than something the mapper decides.
     */
    private static final String SPACE_FILLER = "              ";

    /**
     * Row 0 widened to the declared width the way the sequential dataset holds it: the same 36 data
     * bytes followed by 14 spaces. Never used as a read expectation for the text fixture.
     */
    private static final String ROW_0_CANONICAL_IMAGE = ROW_0_IMAGE + SPACE_FILLER;

    /** Row 1 widened to the declared width, for the same purpose. */
    private static final String ROW_1_CANONICAL_IMAGE = ROW_1_IMAGE + SPACE_FILLER;

    /**
     * Encodes a value the way the mapper measures one, in US-ASCII bytes.
     *
     * <p>Every width check in this file goes through this method or through an array's own length,
     * and never through {@link String#length()}: the two measures coincide for well-formed input and
     * diverge for exactly the input a guard exists to catch.
     *
     * @param  value the value to encode
     * @return its US-ASCII bytes
     */
    private static byte[] ascii(final String value) {
        return value.getBytes(StandardCharsets.US_ASCII);
    }

    /**
     * Measures a value in encoded US-ASCII bytes.
     *
     * @param  value the value to measure
     * @return its encoded byte count
     */
    private static int encodedWidth(final String value) {
        return value.getBytes(StandardCharsets.US_ASCII).length;
    }

    /**
     * Copies a byte range out of an image, so a slice can be compared without decoding the whole.
     *
     * <p>Nothing here trims, strips or normalises: the bytes are copied exactly as they stand,
     * because the filler is contractual on the encode path and a trimmed comparison would assert
     * nothing about it.
     *
     * @param  source the image to slice
     * @param  from   zero-based start of the slice
     * @param  length number of bytes to copy
     * @return a fresh array holding exactly that range
     */
    private static byte[] slice(final byte[] source, final int from, final int length) {
        final byte[] target = new byte[length];
        System.arraycopy(source, from, target, 0, length);
        return target;
    }

    /**
     * Builds the entity row 0 corresponds to, through the public three-argument constructor.
     *
     * <p>The argument order is the copybook declaration order, and passing the three values
     * positionally is what makes a transposition in the constructor visible here rather than only at
     * a constraint violation.
     *
     * @return a fully populated cross-reference carrying row 0's three values
     */
    private static CardCrossReference row0Entity() {
        return new CardCrossReference(ROW_0_CARD_NUM, ROW_0_CUST_ID, ROW_0_ACCT_ID);
    }

    /**
     * Builds the entity row 1 corresponds to, through the same constructor.
     *
     * @return a fully populated cross-reference carrying row 1's three values
     */
    private static CardCrossReference row1Entity() {
        return new CardCrossReference(ROW_1_CARD_NUM, ROW_1_CUST_ID, ROW_1_ACCT_ID);
    }

    /**
     * Asserts that a decoded entity carries exactly the three values expected of it.
     *
     * <p>All three are checked on every read, in every overload, at both widths. A mapper that
     * transposed two same-shaped string fields would satisfy a check of any one of them.
     *
     * @param actual   the entity the mapper produced
     * @param cardNum  the card number expected, hand-sliced from the row image
     * @param custId   the customer identifier expected, hand-sliced from the row image
     * @param acctId   the account identifier expected, hand-sliced from the row image
     */
    private static void assertCarries(final CardCrossReference actual, final String cardNum,
            final String custId, final String acctId) {
        assertThat(actual).as("the mapper must never return null").isNotNull();
        assertThat(actual.getXrefCardNum()).as("XREF-CARD-NUM at offset 0 for 16 bytes")
                .isEqualTo(cardNum);
        assertThat(actual.getXrefCustId()).as("XREF-CUST-ID at offset 16 for 9 bytes")
                .isEqualTo(custId);
        assertThat(actual.getXrefAcctId()).as("XREF-ACCT-ID at offset 25 for 11 bytes")
                .isEqualTo(acctId);
    }

    @Nested
    @DisplayName("Declared geometry: the offsets, the widths, and the arithmetic joining them")
    class DeclaredGeometry {

        @Test
        @DisplayName("each mapped field sits at the offset its copybook position implies, and the "
                + "three are contiguous from zero")
        void eachMappedFieldSitsAtItsDeclaredOffset() {
            assertThat(CardXrefRecordMapper.XREF_CARD_NUM_OFFSET)
                    .as("XREF-CARD-NUM is the leading item, so it begins the record")
                    .isEqualTo(CARD_NUM_OFFSET);
            assertThat(CardXrefRecordMapper.XREF_CUST_ID_OFFSET)
                    .as("XREF-CUST-ID begins where the 16-byte card number ends")
                    .isEqualTo(CUST_ID_OFFSET);
            assertThat(CardXrefRecordMapper.XREF_ACCT_ID_OFFSET)
                    .as("XREF-ACCT-ID begins where the 9-byte customer identifier ends")
                    .isEqualTo(ACCT_ID_OFFSET);

            // Contiguity stated as arithmetic rather than as three separate numbers: a gap or an
            // overlap anywhere in the prefix would break one of these two identities even if each
            // offset above happened to be right on its own.
            assertThat(CARD_NUM_OFFSET + CARD_NUM_LENGTH)
                    .as("the card number runs up to, and not past, the customer identifier")
                    .isEqualTo(CUST_ID_OFFSET);
            assertThat(CUST_ID_OFFSET + CUST_ID_LENGTH)
                    .as("the customer identifier runs up to, and not past, the account identifier")
                    .isEqualTo(ACCT_ID_OFFSET);
        }

        @Test
        @DisplayName("each mapped field carries the width its picture clause declares")
        void eachMappedFieldCarriesItsDeclaredWidth() {
            assertThat(CardXrefRecordMapper.XREF_CARD_NUM_LENGTH)
                    .as("XREF-CARD-NUM is PIC X(16)")
                    .isEqualTo(CARD_NUM_LENGTH);
            assertThat(CardXrefRecordMapper.XREF_CUST_ID_LENGTH)
                    .as("XREF-CUST-ID is PIC 9(09)")
                    .isEqualTo(CUST_ID_LENGTH);
            assertThat(CardXrefRecordMapper.XREF_ACCT_ID_LENGTH)
                    .as("XREF-ACCT-ID is PIC 9(11)")
                    .isEqualTo(ACCT_ID_LENGTH);
        }

        @Test
        @DisplayName("the two live widths are published under their own names, differ from each "
                + "other, and satisfy 16 + 9 + 11 = 36 and 36 + 14 = 50")
        void theTwoLiveWidthsArePublishedAndReconcile() {
            // Both widths are published constants, so a caller states which one it holds rather than
            // encoding a magic number. DATA_RECORD_LENGTH names the filler-absent form the text
            // fixture uses; RECORD_LENGTH names the filler-present form the copybook declares and
            // the cluster is defined at. The two names are what make a call site readable; the
            // assertion that they differ is what makes a call site checkable.
            assertThat(CardXrefRecordMapper.DATA_RECORD_LENGTH)
                    .as("the data-only width, filler absent")
                    .isEqualTo(DATA_WIDTH);
            assertThat(CardXrefRecordMapper.RECORD_LENGTH)
                    .as("the canonical declared width, filler present")
                    .isEqualTo(RECORD_WIDTH);
            assertThat(DATA_WIDTH)
                    .as("the two widths are genuinely different numbers, which is the whole problem")
                    .isNotEqualTo(RECORD_WIDTH);

            assertThat(CARD_NUM_LENGTH + CUST_ID_LENGTH + ACCT_ID_LENGTH)
                    .as("the three mapped fields account for the whole data prefix")
                    .isEqualTo(DATA_WIDTH);
            assertThat(DATA_WIDTH + FILLER_WIDTH)
                    .as("the data prefix plus the filler run accounts for the declared record")
                    .isEqualTo(RECORD_WIDTH);
        }

        @Test
        @DisplayName("the filler run begins where the mapped prefix ends, is 14 bytes wide, and is "
                + "written as a space rather than a zero or a null")
        void theFillerRunBeginsWhereTheMappedPrefixEnds() {
            assertThat(CardXrefRecordMapper.FILLER_OFFSET)
                    .as("nothing mapped is read or written past this offset")
                    .isEqualTo(FILLER_OFFSET);
            assertThat(CardXrefRecordMapper.FILLER_LENGTH)
                    .as("FILLER is PIC X(14)")
                    .isEqualTo(FILLER_WIDTH);
            assertThat(CardXrefRecordMapper.FILLER_CHARACTER)
                    .as("the filler byte is a space; a zero or a null would not match the dataset")
                    .isEqualTo(' ');

            assertThat(ACCT_ID_OFFSET + ACCT_ID_LENGTH)
                    .as("the last mapped byte is the one immediately before the filler starts")
                    .isEqualTo(FILLER_OFFSET);
            assertThat(FILLER_OFFSET + FILLER_WIDTH)
                    .as("the filler run ends exactly at the declared width")
                    .isEqualTo(RECORD_WIDTH);
        }

        @Test
        @DisplayName("the cluster key is the whole of the leading card number and nothing else, "
                + "corroborating KEYS(16 0) - there is no surrogate key")
        void theClusterKeyIsTheLeadingCardNumber() {
            // KEYS(16 0) says the key is 16 bytes starting at offset 0, which is exactly the card
            // number. The entity's identity is therefore that business key itself: a generated
            // surrogate would have no position in the record image at all and would break the
            // correspondence between a stored row and the bytes it came from.
            assertThat(CardXrefRecordMapper.KEY_LENGTH)
                    .as("the declared key length")
                    .isEqualTo(CARD_NUM_LENGTH);
            assertThat(CardXrefRecordMapper.KEY_LENGTH)
                    .as("the key is the card number field, so the two widths cannot differ")
                    .isEqualTo(CardXrefRecordMapper.XREF_CARD_NUM_LENGTH);
            assertThat(CardXrefRecordMapper.XREF_CARD_NUM_OFFSET)
                    .as("a key not starting at offset zero would not be a leading substring")
                    .isEqualTo(CARD_NUM_OFFSET);
        }

        @Test
        @DisplayName("the account identifier's offset and width match the CXACAIX alternate index, "
                + "KEYS(11 25), which is an independent attestation of both numbers")
        void theAccountIdentifierMatchesTheAlternateIndexKey() {
            // The alternate index is documentation here, never behaviour: this file holds no
            // repository and issues no query. What it corroborates is geometry - a non-unique,
            // upgraded index keyed 11 bytes at offset 25 can only be keyed on a field that is 11
            // bytes wide and starts at 25.
            assertThat(CardXrefRecordMapper.XREF_ACCT_ID_OFFSET)
                    .as("KEYS(11 25) keys at offset 25")
                    .isEqualTo(ACCT_ID_OFFSET);
            assertThat(CardXrefRecordMapper.XREF_ACCT_ID_LENGTH)
                    .as("KEYS(11 25) keys for 11 bytes")
                    .isEqualTo(ACCT_ID_LENGTH);
            assertThat(ACCT_ID_OFFSET + ACCT_ID_LENGTH)
                    .as("the alternate index key ends exactly where the data prefix ends")
                    .isEqualTo(DATA_WIDTH);
        }

        @Test
        @DisplayName("both artefact names identify the copybook and differ from one another, so a "
                + "diagnostic says which of the two widths was in force")
        void bothArtefactNamesIdentifyTheCopybookAndDiffer() {
            assertThat(CardXrefRecordMapper.ARTEFACT)
                    .as("the canonical-width layout name")
                    .contains("CVACT03Y");
            assertThat(CardXrefRecordMapper.DATA_ARTEFACT)
                    .as("the data-projection layout name")
                    .contains("CVACT03Y")
                    .isNotEqualTo(CardXrefRecordMapper.ARTEFACT);
        }

        @Test
        @DisplayName("the two shipped artefacts reconcile: 1,850 bytes is 50 rows at a 37-byte "
                + "stride, and 2,500 bytes is the same 50 rows at the declared 50")
        void theTwoShippedArtefactsReconcile() {
            // The text fixture's stride is the data width plus one terminator. This is the piece of
            // arithmetic a reader most needs, because 1,850 divided by 50 is 37 and 37 is not a
            // record width - it is a record width plus a separator.
            assertThat(TEXT_FIXTURE_STRIDE)
                    .as("the text stride is the data width plus one 0x0A terminator")
                    .isEqualTo(DATA_WIDTH + 1);
            assertThat(FIXTURE_ROW_COUNT * TEXT_FIXTURE_STRIDE)
                    .as("50 rows at a 37-byte stride is the committed text fixture's size")
                    .isEqualTo(TEXT_FIXTURE_BYTE_COUNT);

            // The sequential dataset carries the filler and no terminator at all, so its stride is
            // the record width itself: 50 rows of 50 bytes is 2,500.
            assertThat(FIXTURE_ROW_COUNT * RECORD_WIDTH)
                    .as("the same 50 rows at the declared width is the sequential dataset's size")
                    .isEqualTo(SEQUENTIAL_DATASET_BYTE_COUNT);

            // And the whole difference between the two files is accounted for: each row trades one
            // terminator for fourteen filler bytes, a gain of thirteen bytes fifty times over.
            assertThat(SEQUENTIAL_DATASET_BYTE_COUNT - TEXT_FIXTURE_BYTE_COUNT)
                    .as("fifty fillers less fifty terminators is the entire size difference")
                    .isEqualTo(FIXTURE_ROW_COUNT * (FILLER_WIDTH - 1));
        }

        @Test
        @DisplayName("both hand-transcribed fixture rows measure exactly 36 encoded bytes and "
                + "reassemble from their own three hand-sliced fields")
        void bothHandTranscribedRowsMeasureTheDataWidth() {
            // This is the guard on the transcription itself. If a digit were dropped while copying a
            // row image or one of its field slices into this file, every expectation built on it
            // would be wrong together, and no assertion against the mapper would notice. Checking
            // the row against its own parts closes that.
            assertThat(encodedWidth(ROW_0_IMAGE))
                    .as("row 0 is stored at the data width, terminator excluded")
                    .isEqualTo(DATA_WIDTH);
            assertThat(encodedWidth(ROW_1_IMAGE))
                    .as("row 1 is stored at the data width, terminator excluded")
                    .isEqualTo(DATA_WIDTH);

            assertThat(ROW_0_CARD_NUM + ROW_0_CUST_ID + ROW_0_ACCT_ID)
                    .as("row 0's three hand-sliced fields concatenate back to row 0's image")
                    .isEqualTo(ROW_0_IMAGE);
            assertThat(ROW_1_CARD_NUM + ROW_1_CUST_ID + ROW_1_ACCT_ID)
                    .as("row 1's three hand-sliced fields concatenate back to row 1's image")
                    .isEqualTo(ROW_1_IMAGE);

            assertThat(encodedWidth(ROW_0_CARD_NUM)).isEqualTo(CARD_NUM_LENGTH);
            assertThat(encodedWidth(ROW_0_CUST_ID)).isEqualTo(CUST_ID_LENGTH);
            assertThat(encodedWidth(ROW_0_ACCT_ID)).isEqualTo(ACCT_ID_LENGTH);
            assertThat(encodedWidth(ROW_1_CARD_NUM)).isEqualTo(CARD_NUM_LENGTH);
            assertThat(encodedWidth(ROW_1_CUST_ID)).isEqualTo(CUST_ID_LENGTH);
            assertThat(encodedWidth(ROW_1_ACCT_ID)).isEqualTo(ACCT_ID_LENGTH);

            // The widened forms are 50 bytes because the filler literal is 14, not because anything
            // padded them. Asserting the filler literal's own width keeps that honest.
            assertThat(encodedWidth(SPACE_FILLER)).isEqualTo(FILLER_WIDTH);
            assertThat(encodedWidth(ROW_0_CANONICAL_IMAGE)).isEqualTo(RECORD_WIDTH);
            assertThat(encodedWidth(ROW_1_CANONICAL_IMAGE)).isEqualTo(RECORD_WIDTH);
        }
    }

    @Nested
    @DisplayName("Reading at both live widths, on the committed fixture's own rows")
    class ReadingAtBothWidths {

        @Test
        @DisplayName("fixture row 0 at 36 bytes decodes to its three hand-sliced values, which is "
                + "the text fixture read at its own stride")
        void fixtureRowZeroAtTheDataWidthDecodesCorrectly() {
            // The 36-byte form is read as it is stored. Nothing is padded out to 50 here, because a
            // read expectation that padded would be asserting a transformation this file exists to
            // prove unnecessary.
            assertThat(encodedWidth(ROW_0_IMAGE)).isEqualTo(DATA_WIDTH);

            final CardCrossReference decoded = CardXrefRecordMapper.fromRecord(ROW_0_IMAGE);

            assertCarries(decoded, ROW_0_CARD_NUM, ROW_0_CUST_ID, ROW_0_ACCT_ID);
        }

        @Test
        @DisplayName("fixture row 1 at 36 bytes decodes to its own three values, proving the offsets "
                + "hold on a row they were not chosen against")
        void fixtureRowOneAtTheDataWidthDecodesCorrectly() {
            assertThat(encodedWidth(ROW_1_IMAGE)).isEqualTo(DATA_WIDTH);

            final CardCrossReference decoded = CardXrefRecordMapper.fromRecord(ROW_1_IMAGE);

            assertCarries(decoded, ROW_1_CARD_NUM, ROW_1_CUST_ID, ROW_1_ACCT_ID);
        }

        @Test
        @DisplayName("the same row at 50 bytes, filler present, decodes to the same three values - "
                + "the width decides only whether a filler run follows, never where a field begins")
        void theSameRowAtTheCanonicalWidthDecodesIdentically() {
            assertThat(encodedWidth(ROW_0_CANONICAL_IMAGE)).isEqualTo(RECORD_WIDTH);

            final CardCrossReference fromData = CardXrefRecordMapper.fromRecord(ROW_0_IMAGE);
            final CardCrossReference fromCanonical =
                    CardXrefRecordMapper.fromRecord(ROW_0_CANONICAL_IMAGE);

            assertCarries(fromCanonical, ROW_0_CARD_NUM, ROW_0_CUST_ID, ROW_0_ACCT_ID);
            assertCarries(fromData, ROW_0_CARD_NUM, ROW_0_CUST_ID, ROW_0_ACCT_ID);
            assertThat(fromCanonical)
                    .as("both widths yield the same identity, which is the card number")
                    .isEqualTo(fromData);
        }

        @Test
        @DisplayName("row 1 at 50 bytes decodes to its own three values as well, so neither width is "
                + "a special case of one row")
        void rowOneAtTheCanonicalWidthDecodesIdentically() {
            final CardCrossReference decoded =
                    CardXrefRecordMapper.fromRecord(ROW_1_CANONICAL_IMAGE);

            assertCarries(decoded, ROW_1_CARD_NUM, ROW_1_CUST_ID, ROW_1_ACCT_ID);
        }

        @Test
        @DisplayName("a 50-byte image whose filler is ASCII zero rather than space decodes to the "
                + "same three values, proving the filler is genuinely ignored on read")
        void aCanonicalImageWithZeroFillerDecodesIdentically() {
            // Filler with no initialising clause is uninitialised, so nothing makes any particular
            // byte canonical on the read path. Feeding a foreign filler byte is what proves the
            // decode really stops at offset 36 rather than merely tolerating the bytes it expects.
            // The encode path is a different question and is settled separately: it writes spaces.
            final String zeroFilled =
                    ROW_0_IMAGE + String.valueOf(ASCII_ZERO_CHARACTER).repeat(FILLER_WIDTH);
            assertThat(encodedWidth(zeroFilled)).isEqualTo(RECORD_WIDTH);
            assertThat(zeroFilled)
                    .as("this image differs from the space-filled one, so the test is not vacuous")
                    .isNotEqualTo(ROW_0_CANONICAL_IMAGE);

            final CardCrossReference decoded = CardXrefRecordMapper.fromRecord(zeroFilled);

            assertCarries(decoded, ROW_0_CARD_NUM, ROW_0_CUST_ID, ROW_0_ACCT_ID);
        }

        @Test
        @DisplayName("leading zeros survive on all three properties, because none of them is ever "
                + "parsed to a numeric type")
        void leadingZerosSurviveOnAllThreeProperties() {
            // Row 0 is the useful row here: its card number leads with a zero, its customer
            // identifier carries seven, and its account identifier carries nine. A numeric round
            // trip would return 500024453765740, 50 and 50, each narrower than the field it
            // occupies, shifting every byte that follows it in the image.
            final CardCrossReference decoded = CardXrefRecordMapper.fromRecord(ROW_0_IMAGE);

            assertThat(decoded.getXrefCardNum())
                    .as("the card number's leading zero is significant")
                    .startsWith("0")
                    .isEqualTo(ROW_0_CARD_NUM);
            assertThat(encodedWidth(decoded.getXrefCardNum())).isEqualTo(CARD_NUM_LENGTH);

            assertThat(decoded.getXrefCustId())
                    .as("the customer identifier keeps every leading zero of PIC 9(09)")
                    .isEqualTo("000000050");
            assertThat(encodedWidth(decoded.getXrefCustId())).isEqualTo(CUST_ID_LENGTH);

            assertThat(decoded.getXrefAcctId())
                    .as("the account identifier keeps every leading zero of PIC 9(11)")
                    .isEqualTo("00000000050");
            assertThat(encodedWidth(decoded.getXrefAcctId())).isEqualTo(ACCT_ID_LENGTH);
        }

        @Test
        @DisplayName("the string, byte-array and buffer-range overloads all decode row 0 to equal "
                + "entities at the 36-byte width")
        void allThreeOverloadsAgreeAtTheDataWidth() {
            final byte[] rowBytes = ascii(ROW_0_IMAGE);
            assertThat(rowBytes).hasSize(DATA_WIDTH);

            final CardCrossReference fromString = CardXrefRecordMapper.fromRecord(ROW_0_IMAGE);
            final CardCrossReference fromBytes = CardXrefRecordMapper.fromRecord(rowBytes);
            final CardCrossReference fromRange =
                    CardXrefRecordMapper.fromRecord(rowBytes, 0, DATA_WIDTH);

            assertCarries(fromString, ROW_0_CARD_NUM, ROW_0_CUST_ID, ROW_0_ACCT_ID);
            assertCarries(fromBytes, ROW_0_CARD_NUM, ROW_0_CUST_ID, ROW_0_ACCT_ID);
            assertCarries(fromRange, ROW_0_CARD_NUM, ROW_0_CUST_ID, ROW_0_ACCT_ID);
            assertThat(fromBytes).isEqualTo(fromString);
            assertThat(fromRange).isEqualTo(fromString);
            assertThat(fromBytes).hasSameHashCodeAs(fromString);
        }

        @Test
        @DisplayName("the same three overloads all decode row 1 to equal entities at the 50-byte "
                + "width too, so overload agreement is not a property of one width")
        void allThreeOverloadsAgreeAtTheCanonicalWidth() {
            final byte[] canonicalBytes = ascii(ROW_1_CANONICAL_IMAGE);
            assertThat(canonicalBytes).hasSize(RECORD_WIDTH);

            final CardCrossReference fromString =
                    CardXrefRecordMapper.fromRecord(ROW_1_CANONICAL_IMAGE);
            final CardCrossReference fromBytes = CardXrefRecordMapper.fromRecord(canonicalBytes);
            final CardCrossReference fromRange =
                    CardXrefRecordMapper.fromRecord(canonicalBytes, 0, RECORD_WIDTH);

            assertCarries(fromString, ROW_1_CARD_NUM, ROW_1_CUST_ID, ROW_1_ACCT_ID);
            assertCarries(fromBytes, ROW_1_CARD_NUM, ROW_1_CUST_ID, ROW_1_ACCT_ID);
            assertCarries(fromRange, ROW_1_CARD_NUM, ROW_1_CUST_ID, ROW_1_ACCT_ID);
            assertThat(fromBytes).isEqualTo(fromString);
            assertThat(fromRange).isEqualTo(fromString);
            assertThat(fromRange).hasSameHashCodeAs(fromString);
        }

        @Test
        @DisplayName("the terminated text stride reads row 1 from offset 37 of a two-row buffer, "
                + "which is how the committed fixture must actually be walked")
        void theTerminatedTextStrideReachesTheSecondRow() {
            // Two real rows, each followed by its terminator, exactly as the committed fixture holds
            // them. Row i starts at i * 37 and the terminator is stepped over rather than stripped.
            final byte[] buffer =
                    ascii(ROW_0_IMAGE + "\n" + ROW_1_IMAGE + "\n");
            assertThat(buffer).hasSize(2 * TEXT_FIXTURE_STRIDE);

            final CardCrossReference first =
                    CardXrefRecordMapper.fromRecord(buffer, 0, DATA_WIDTH);
            final CardCrossReference second =
                    CardXrefRecordMapper.fromRecord(buffer, TEXT_FIXTURE_STRIDE, DATA_WIDTH);

            assertCarries(first, ROW_0_CARD_NUM, ROW_0_CUST_ID, ROW_0_ACCT_ID);
            assertCarries(second, ROW_1_CARD_NUM, ROW_1_CUST_ID, ROW_1_ACCT_ID);
            assertThat(second)
                    .as("two distinct rows must not decode to the same identity")
                    .isNotEqualTo(first);
        }

        @Test
        @DisplayName("the 36-byte image is a byte-exact prefix of the 50-byte one, so no data byte "
                + "moves between the widths and no field begins anywhere else")
        void theDataImageIsAByteExactPrefixOfTheCanonicalImage() {
            // This is the structural reason the two widths can share one set of offsets, stated at
            // the byte level rather than inferred from the decoded values. Everything the two forms
            // disagree about lies at or past offset 36.
            final byte[] dataBytes = ascii(ROW_0_IMAGE);
            final byte[] canonicalBytes = ascii(ROW_0_CANONICAL_IMAGE);

            assertThat(dataBytes).hasSize(DATA_WIDTH);
            assertThat(canonicalBytes).hasSize(RECORD_WIDTH);
            assertThat(slice(canonicalBytes, 0, DATA_WIDTH))
                    .as("the canonical image's leading 36 bytes are the data image exactly")
                    .isEqualTo(dataBytes);

            // And each field occupies the identical range in both, checked field by field so a
            // whole-prefix match cannot hide a compensating pair of shifts.
            assertThat(slice(canonicalBytes, CARD_NUM_OFFSET, CARD_NUM_LENGTH))
                    .isEqualTo(ascii(ROW_0_CARD_NUM));
            assertThat(slice(canonicalBytes, CUST_ID_OFFSET, CUST_ID_LENGTH))
                    .isEqualTo(ascii(ROW_0_CUST_ID));
            assertThat(slice(canonicalBytes, ACCT_ID_OFFSET, ACCT_ID_LENGTH))
                    .isEqualTo(ascii(ROW_0_ACCT_ID));
            assertThat(slice(dataBytes, CARD_NUM_OFFSET, CARD_NUM_LENGTH))
                    .isEqualTo(ascii(ROW_0_CARD_NUM));
            assertThat(slice(dataBytes, CUST_ID_OFFSET, CUST_ID_LENGTH))
                    .isEqualTo(ascii(ROW_0_CUST_ID));
            assertThat(slice(dataBytes, ACCT_ID_OFFSET, ACCT_ID_LENGTH))
                    .isEqualTo(ascii(ROW_0_ACCT_ID));
        }

        @Test
        @DisplayName("the unterminated fixed-length stride reads row 1 from offset 50 of a two-row "
                + "buffer, which is how the sequential dataset must be walked")
        void theUnterminatedFixedLengthStrideReachesTheSecondRow() {
            // The sequential dataset carries no terminator, so its stride is the record width
            // itself: row i starts at i * 50. This is the other half of the pair, and the reason the
            // buffer overload requires the width rather than defaulting to one of them.
            final byte[] buffer = ascii(ROW_0_CANONICAL_IMAGE + ROW_1_CANONICAL_IMAGE);
            assertThat(buffer).hasSize(2 * RECORD_WIDTH);

            final CardCrossReference first =
                    CardXrefRecordMapper.fromRecord(buffer, 0, RECORD_WIDTH);
            final CardCrossReference second =
                    CardXrefRecordMapper.fromRecord(buffer, RECORD_WIDTH, RECORD_WIDTH);

            assertCarries(first, ROW_0_CARD_NUM, ROW_0_CUST_ID, ROW_0_ACCT_ID);
            assertCarries(second, ROW_1_CARD_NUM, ROW_1_CUST_ID, ROW_1_ACCT_ID);
        }
    }

    @Nested
    @DisplayName("Writing both projections: one canonical width, one data width, one placement rule")
    class WritingBothProjections {

        @Test
        @DisplayName("the canonical write emits exactly 50 encoded bytes whose leading 36 are the "
                + "fixture row and whose trailing 14 are every one 0x20")
        void theCanonicalWriteEmitsFiftyBytesWithSpaceFiller() {
            // The entity is built through the public three-argument constructor, positionally, so
            // this assertion also proves the constructor's parameter order is the record's order: a
            // transposition there would place the customer identifier where the account identifier
            // belongs and the emitted image would stop matching the row.
            final String encoded = CardXrefRecordMapper.toRecord(row0Entity());

            assertThat(encodedWidth(encoded))
                    .as("the canonical projection is exactly the declared record width")
                    .isEqualTo(RECORD_WIDTH);

            final byte[] bytes = ascii(encoded);
            assertThat(slice(bytes, 0, DATA_WIDTH))
                    .as("the leading 36 bytes reproduce the committed fixture row exactly")
                    .isEqualTo(ascii(ROW_0_IMAGE));

            // Bytes 36 through 49 inclusive, checked one at a time against 0x20. Not a zero, not a
            // null, and not left to the buffer's initial state.
            for (int offset = FILLER_OFFSET; offset < RECORD_WIDTH; offset++) {
                assertThat(bytes[offset])
                        .as("filler byte at offset %d must be an ASCII space", offset)
                        .isEqualTo(ASCII_SPACE);
            }
            assertThat(slice(bytes, FILLER_OFFSET, FILLER_WIDTH))
                    .as("and the filler run as a whole is the fourteen-space literal")
                    .isEqualTo(ascii(SPACE_FILLER));

            // Stated once as a whole-image equality too, which is the assertion a reader checks the
            // sequential dataset against.
            assertThat(encoded).isEqualTo(ROW_0_CANONICAL_IMAGE);
        }

        @Test
        @DisplayName("the canonical byte-array write emits the same 50 bytes, guaranteed by the "
                + "array's own length rather than asserted after a decode")
        void theCanonicalByteArrayWriteEmitsTheSameFiftyBytes() {
            final byte[] bytes = CardXrefRecordMapper.toRecordBytes(row1Entity());

            assertThat(bytes)
                    .as("the array's length is the emitted width, with no charset in the caller")
                    .hasSize(RECORD_WIDTH);
            assertThat(slice(bytes, 0, DATA_WIDTH)).isEqualTo(ascii(ROW_1_IMAGE));
            assertThat(slice(bytes, FILLER_OFFSET, FILLER_WIDTH)).isEqualTo(ascii(SPACE_FILLER));
            assertThat(bytes)
                    .as("the whole image matches the fixture row widened by the filler")
                    .isEqualTo(ascii(ROW_1_CANONICAL_IMAGE));

            // The array is the caller's own: mutating it must not disturb a second call.
            bytes[0] = ASCII_SPACE;
            assertThat(CardXrefRecordMapper.toRecordBytes(row1Entity()))
                    .as("each call returns a fresh array, never a shared one")
                    .isEqualTo(ascii(ROW_1_CANONICAL_IMAGE));
        }

        @Test
        @DisplayName("the 36-byte data projection emits the mapped prefix alone, byte for byte equal "
                + "to the fixture row and not equal to the 50-byte form")
        void theDataProjectionEmitsThirtySixBytesAndDiffersFromTheCanonical() {
            final String dataImage = CardXrefRecordMapper.toDataRecord(row0Entity());
            final String canonicalImage = CardXrefRecordMapper.toRecord(row0Entity());

            assertThat(encodedWidth(dataImage))
                    .as("the data projection is exactly the data-only width, filler absent")
                    .isEqualTo(DATA_WIDTH);
            assertThat(ascii(dataImage))
                    .as("and it reproduces the committed fixture row byte for byte, unbounded")
                    .isEqualTo(ascii(ROW_0_IMAGE));

            // The two projections are genuinely different emissions of the same three values, which
            // is what makes a fixture round trip unambiguous: a caller states which artefact it is
            // reproducing by choosing the method, not by slicing the result.
            assertThat(dataImage)
                    .as("the data projection is not the canonical image")
                    .isNotEqualTo(canonicalImage);
            assertThat(encodedWidth(canonicalImage) - encodedWidth(dataImage))
                    .as("and the whole of the difference is the filler run")
                    .isEqualTo(FILLER_WIDTH);
        }

        @Test
        @DisplayName("the 36-byte byte-array projection emits the same 36 bytes and differs from the "
                + "canonical byte array by exactly the filler")
        void theDataProjectionByteArrayEmitsThirtySixBytes() {
            final byte[] dataBytes = CardXrefRecordMapper.toDataRecordBytes(row1Entity());
            final byte[] canonicalBytes = CardXrefRecordMapper.toRecordBytes(row1Entity());

            assertThat(dataBytes).hasSize(DATA_WIDTH);
            assertThat(dataBytes).isEqualTo(ascii(ROW_1_IMAGE));
            assertThat(canonicalBytes).hasSize(RECORD_WIDTH);
            assertThat(dataBytes)
                    .as("the two projections are not the same array of bytes")
                    .isNotEqualTo(canonicalBytes);
            assertThat(slice(canonicalBytes, 0, DATA_WIDTH))
                    .as("but the canonical image's prefix is the data projection exactly")
                    .isEqualTo(dataBytes);

            dataBytes[0] = ASCII_SPACE;
            assertThat(CardXrefRecordMapper.toDataRecordBytes(row1Entity()))
                    .as("each call returns a fresh array, never a shared one")
                    .isEqualTo(ascii(ROW_1_IMAGE));
        }

        @Test
        @DisplayName("a full read followed by a write restores the source image at whichever width "
                + "was read, for both fixture rows")
        void aReadFollowedByAWriteRestoresTheSourceImage() {
            // The data width round trip, which is what a reader of app/data/ASCII/cardxref.txt needs:
            // read at 36, write at 36, and the result is the row that was read, with no bound.
            assertThat(CardXrefRecordMapper.toDataRecord(
                    CardXrefRecordMapper.fromRecord(ROW_0_IMAGE)))
                    .isEqualTo(ROW_0_IMAGE);
            assertThat(CardXrefRecordMapper.toDataRecord(
                    CardXrefRecordMapper.fromRecord(ROW_1_IMAGE)))
                    .isEqualTo(ROW_1_IMAGE);

            // The canonical round trip, which is what a reader of the sequential dataset needs.
            assertThat(CardXrefRecordMapper.toRecord(
                    CardXrefRecordMapper.fromRecord(ROW_0_CANONICAL_IMAGE)))
                    .isEqualTo(ROW_0_CANONICAL_IMAGE);
            assertThat(CardXrefRecordMapper.toRecord(
                    CardXrefRecordMapper.fromRecord(ROW_1_CANONICAL_IMAGE)))
                    .isEqualTo(ROW_1_CANONICAL_IMAGE);

            // And crossing the two widths is a widening or a narrowing of the filler alone, never a
            // change to any data byte.
            assertThat(CardXrefRecordMapper.toRecord(
                    CardXrefRecordMapper.fromRecord(ROW_0_IMAGE)))
                    .as("a 36-byte row written canonically gains the filler and nothing else")
                    .isEqualTo(ROW_0_CANONICAL_IMAGE);
            assertThat(CardXrefRecordMapper.toDataRecord(
                    CardXrefRecordMapper.fromRecord(ROW_0_CANONICAL_IMAGE)))
                    .as("a 50-byte row written as data loses the filler and nothing else")
                    .isEqualTo(ROW_0_IMAGE);
        }

        @Test
        @DisplayName("a card number handed over short is space padded to the right, matching "
                + "PIC X(16), and the filler stays a separate run")
        void aShortCardNumberIsSpacePaddedToTheRight() {
            // PIC X(n) is alphanumeric and left justified, so a short value keeps its position and
            // the field's tail is spaced. The value is not trimmed, not rejected and not widened by
            // zeros the way a numeric field would be.
            final CardCrossReference shortCard =
                    new CardCrossReference("0500", ROW_0_CUST_ID, ROW_0_ACCT_ID);

            final byte[] bytes = CardXrefRecordMapper.toRecordBytes(shortCard);

            assertThat(bytes).hasSize(RECORD_WIDTH);
            assertThat(slice(bytes, CARD_NUM_OFFSET, CARD_NUM_LENGTH))
                    .as("four supplied bytes then twelve spaces, left justified")
                    .isEqualTo(ascii("0500            "));
            assertThat(slice(bytes, CUST_ID_OFFSET, CUST_ID_LENGTH))
                    .as("the following field is undisturbed by the padding before it")
                    .isEqualTo(ascii(ROW_0_CUST_ID));
            assertThat(slice(bytes, ACCT_ID_OFFSET, ACCT_ID_LENGTH))
                    .isEqualTo(ascii(ROW_0_ACCT_ID));
            assertThat(slice(bytes, FILLER_OFFSET, FILLER_WIDTH))
                    .as("and the filler run is still its own fourteen bytes")
                    .isEqualTo(ascii(SPACE_FILLER));
        }

        @Test
        @DisplayName("an identifier handed over short is widened by leading zeros, matching PIC 9(n), "
                + "exactly as a legacy move would widen it")
        void aShortIdentifierIsWidenedByLeadingZeros() {
            // PIC 9(n) is right justified and zero filled, which is what makes the leading zeros of
            // both identifiers reproducible rather than incidental. Neither value is parsed to a
            // numeric type at any point in order to achieve this.
            final CardCrossReference shortIds =
                    new CardCrossReference(ROW_0_CARD_NUM, "50", "50");

            final byte[] bytes = CardXrefRecordMapper.toDataRecordBytes(shortIds);

            assertThat(bytes).hasSize(DATA_WIDTH);
            assertThat(slice(bytes, CARD_NUM_OFFSET, CARD_NUM_LENGTH))
                    .isEqualTo(ascii(ROW_0_CARD_NUM));
            assertThat(slice(bytes, CUST_ID_OFFSET, CUST_ID_LENGTH))
                    .as("two supplied digits widened to nine by seven leading zeros")
                    .isEqualTo(ascii("000000050"));
            assertThat(slice(bytes, ACCT_ID_OFFSET, ACCT_ID_LENGTH))
                    .as("two supplied digits widened to eleven by nine leading zeros")
                    .isEqualTo(ascii("00000000050"));

            // Which is to say the widened form is row 0's own image: the zero fill reconstructs
            // precisely the leading zeros the committed fixture carries.
            assertThat(bytes).isEqualTo(ascii(ROW_0_IMAGE));
        }
    }

    @Nested
    @DisplayName("Rejected input: every width that is neither 36 nor 50, and every absent value")
    class RejectedInput {

        @Test
        @DisplayName("an image one byte short of the data width is rejected, and the message names "
                + "both accepted widths and the actual encoded length")
        void anImageOneByteShortOfTheDataWidthIsRejected() {
            // This is the case the mandated message assertion is made on. A caller that arrives here
            // holds a number it believes to be a record width, so being told only that its own value
            // was wrong is not enough - it needs both numbers that would have been right.
            final String tooShort = "05000244537657400000000500000000005";
            assertThat(encodedWidth(tooShort)).isEqualTo(35);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("35 bytes is neither accepted width")
                    .isThrownBy(() -> CardXrefRecordMapper.fromRecord(tooShort))
                    .withMessageContaining("CVACT03Y")
                    .withMessageContaining("36")
                    .withMessageContaining("50")
                    .withMessageContaining("35")
                    .withMessageContaining("exactly 36 encoded bytes")
                    .withMessageContaining("exactly 50 encoded bytes");
        }

        @Test
        @DisplayName("an image one byte past the data width is rejected, and the message names both "
                + "widths, the actual length and an unstripped terminator as the likely cause")
        void anImageOneBytePastTheDataWidthIsRejected() {
            // 37 bytes is the single most likely way this guard fires, because the text fixture's
            // stride is 37: a reader that keeps the 0x0A terminator arrives here holding one byte too
            // many. The terminator is a record separator and is never record content.
            final String withTerminatorWidth = ROW_0_IMAGE + " ";
            assertThat(encodedWidth(withTerminatorWidth)).isEqualTo(TEXT_FIXTURE_STRIDE);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> CardXrefRecordMapper.fromRecord(withTerminatorWidth))
                    .withMessageContaining("36")
                    .withMessageContaining("50")
                    .withMessageContaining("37")
                    .withMessageContaining("0x0A");
        }

        @Test
        @DisplayName("an image one byte short of the canonical width is rejected rather than padded "
                + "to fit, and the message still names both widths and the actual length")
        void anImageOneByteShortOfTheCanonicalWidthIsRejected() {
            final String tooShort = ROW_0_IMAGE + "             ";
            assertThat(encodedWidth(tooShort)).isEqualTo(49);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("49 bytes falls between the two widths and is not silently completed")
                    .isThrownBy(() -> CardXrefRecordMapper.fromRecord(tooShort))
                    .withMessageContaining("36")
                    .withMessageContaining("50")
                    .withMessageContaining("49");
        }

        @Test
        @DisplayName("an image one byte past the canonical width is rejected rather than truncated, "
                + "and the terminator hint appears there too")
        void anImageOneBytePastTheCanonicalWidthIsRejected() {
            final String tooLong = ROW_0_CANONICAL_IMAGE + " ";
            assertThat(encodedWidth(tooLong)).isEqualTo(51);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> CardXrefRecordMapper.fromRecord(tooLong))
                    .withMessageContaining("36")
                    .withMessageContaining("50")
                    .withMessageContaining("51")
                    .withMessageContaining("0x0A");
        }

        @Test
        @DisplayName("a byte array at an unaccepted width is rejected on its own length, with no "
                + "charset decision taken anywhere")
        void aByteArrayAtAnUnacceptedWidthIsRejected() {
            final byte[] fortyBytes = ascii(ROW_0_IMAGE + "    ");
            assertThat(fortyBytes).hasSize(40);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> CardXrefRecordMapper.fromRecord(fortyBytes))
                    .withMessageContaining("36")
                    .withMessageContaining("50")
                    .withMessageContaining("40");
        }

        @Test
        @DisplayName("a buffer read at a width that is neither accepted is refused before any slice "
                + "is taken, and the message names the requested width")
        void aBufferReadAtAnUnacceptedWidthIsRefused() {
            // The buffer overload makes the caller state the width, so a wrong stride is caught here
            // as a wrong width rather than surfacing later as a misplaced field.
            final byte[] buffer = ascii(ROW_0_CANONICAL_IMAGE);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> CardXrefRecordMapper.fromRecord(buffer, 0, 40))
                    .withMessageContaining("36")
                    .withMessageContaining("50")
                    .withMessageContaining("40");
        }

        @Test
        @DisplayName("a buffer read that runs off the end is reported as a range failure rather than "
                + "silently truncated")
        void aBufferReadThatRunsOffTheEndIsReported() {
            final byte[] buffer = ascii(ROW_0_CANONICAL_IMAGE);
            assertThat(buffer).hasSize(RECORD_WIDTH);

            // Starting one byte in leaves only 49 bytes, which is one short of the width requested.
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> CardXrefRecordMapper.fromRecord(buffer, 1, RECORD_WIDTH))
                    .withMessageContaining("CVACT03Y");
        }

        @Test
        @DisplayName("a negative buffer start index is refused rather than wrapped or clamped")
        void aNegativeBufferStartIndexIsRefused() {
            final byte[] buffer = ascii(ROW_0_IMAGE);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> CardXrefRecordMapper.fromRecord(buffer, -1, DATA_WIDTH))
                    .withMessageContaining("CVACT03Y");
        }

        @Test
        @DisplayName("a value wider than its field is rejected rather than truncated to fit, at both "
                + "emission widths")
        void aValueWiderThanItsFieldIsRejected() {
            // A truncated value would leave the record exactly the right width and the wrong
            // content, which is the failure mode a fixed-width layout must never produce silently.
            final CardCrossReference overlongCard =
                    new CardCrossReference(ROW_0_CARD_NUM + "0", ROW_0_CUST_ID, ROW_0_ACCT_ID);
            final CardCrossReference overlongAccount =
                    new CardCrossReference(ROW_0_CARD_NUM, ROW_0_CUST_ID, ROW_0_ACCT_ID + "0");

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("17 bytes does not fit a 16-byte field")
                    .isThrownBy(() -> CardXrefRecordMapper.toRecord(overlongCard))
                    .withMessageContaining("XREF-CARD-NUM");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("and the data projection applies the same field widths")
                    .isThrownBy(() -> CardXrefRecordMapper.toDataRecord(overlongAccount))
                    .withMessageContaining("XREF-ACCT-ID");
        }

        @Test
        @DisplayName("every public entrypoint refuses a null argument deterministically")
        void everyPublicEntrypointRefusesNull() {
            // All seven public members are covered, and the two decode overloads are disambiguated by
            // an explicit cast rather than left to overload resolution.
            assertThatNullPointerException()
                    .as("the string decode overload")
                    .isThrownBy(() -> CardXrefRecordMapper.fromRecord((String) null));
            assertThatNullPointerException()
                    .as("the byte-array decode overload")
                    .isThrownBy(() -> CardXrefRecordMapper.fromRecord((byte[]) null));
            assertThatNullPointerException()
                    .as("the buffer-range decode overload")
                    .isThrownBy(() -> CardXrefRecordMapper.fromRecord(null, 0, RECORD_WIDTH));
            assertThatNullPointerException()
                    .as("the canonical string encode")
                    .isThrownBy(() -> CardXrefRecordMapper.toRecord(null));
            assertThatNullPointerException()
                    .as("the canonical byte-array encode")
                    .isThrownBy(() -> CardXrefRecordMapper.toRecordBytes(null));
            assertThatNullPointerException()
                    .as("the data-projection string encode")
                    .isThrownBy(() -> CardXrefRecordMapper.toDataRecord(null));
            assertThatNullPointerException()
                    .as("the data-projection byte-array encode")
                    .isThrownBy(() -> CardXrefRecordMapper.toDataRecordBytes(null));
        }

        @Test
        @DisplayName("a partially populated cross-reference names the legacy field it is missing, "
                + "rather than failing obscurely inside the encoder")
        void aPartiallyPopulatedCrossReferenceNamesTheMissingField() {
            // Each of the three mapped fields is checked before placement, so the diagnostic names
            // the copybook field a maintainer would go looking for.
            assertThatNullPointerException()
                    .isThrownBy(() -> CardXrefRecordMapper.toRecord(
                            new CardCrossReference(null, ROW_0_CUST_ID, ROW_0_ACCT_ID)))
                    .withMessageContaining("XREF-CARD-NUM");
            assertThatNullPointerException()
                    .isThrownBy(() -> CardXrefRecordMapper.toRecord(
                            new CardCrossReference(ROW_0_CARD_NUM, null, ROW_0_ACCT_ID)))
                    .withMessageContaining("XREF-CUST-ID");
            assertThatNullPointerException()
                    .isThrownBy(() -> CardXrefRecordMapper.toDataRecord(
                            new CardCrossReference(ROW_0_CARD_NUM, ROW_0_CUST_ID, null)))
                    .withMessageContaining("XREF-ACCT-ID");
        }
    }

    @Nested
    @DisplayName("Entity state contract: three attributes, identity on the card number, no fourth")
    class EntityStateContract {

        @Test
        @DisplayName("the three attributes are the whole of the entity's mapped state, so a decode "
                + "followed by an encode is lossless at both widths")
        void theThreeAttributesAreTheWholeOfTheMappedState() {
            // The entity declares no optimistic-locking counter, and none is missing. That absence is
            // established by the compiler rather than by introspection: this file names every
            // accessor the entity exposes, and there is no version accessor to name. No reflection is
            // used anywhere in this class, which is what keeps the module's zero budget for
            // low-level reflective access intact.
            final CardCrossReference decoded = CardXrefRecordMapper.fromRecord(ROW_0_IMAGE);

            assertCarries(decoded, ROW_0_CARD_NUM, ROW_0_CUST_ID, ROW_0_ACCT_ID);

            // If any further piece of mapped state existed, one of these two emissions would have
            // somewhere to put it and the round trip would not be byte exact.
            assertThat(CardXrefRecordMapper.toDataRecord(decoded)).isEqualTo(ROW_0_IMAGE);
            assertThat(CardXrefRecordMapper.toRecord(decoded)).isEqualTo(ROW_0_CANONICAL_IMAGE);
        }

        @Test
        @DisplayName("each setter assigns verbatim, so a mutated entity encodes to the mutated row "
                + "with nothing trimmed, padded or folded")
        void eachSetterAssignsVerbatim() {
            // Built from row 0 and mutated field by field into row 1, which proves each setter
            // reaches its own attribute and disturbs neither of the other two.
            final CardCrossReference mutating = row0Entity();

            mutating.setXrefCardNum(ROW_1_CARD_NUM);
            assertThat(mutating.getXrefCardNum()).isEqualTo(ROW_1_CARD_NUM);
            assertThat(mutating.getXrefCustId()).isEqualTo(ROW_0_CUST_ID);
            assertThat(mutating.getXrefAcctId()).isEqualTo(ROW_0_ACCT_ID);

            mutating.setXrefCustId(ROW_1_CUST_ID);
            assertThat(mutating.getXrefCustId()).isEqualTo(ROW_1_CUST_ID);
            assertThat(mutating.getXrefAcctId()).isEqualTo(ROW_0_ACCT_ID);

            mutating.setXrefAcctId(ROW_1_ACCT_ID);
            assertCarries(mutating, ROW_1_CARD_NUM, ROW_1_CUST_ID, ROW_1_ACCT_ID);

            // And the fully mutated entity encodes to row 1 at both widths.
            assertThat(CardXrefRecordMapper.toDataRecord(mutating)).isEqualTo(ROW_1_IMAGE);
            assertThat(CardXrefRecordMapper.toRecord(mutating)).isEqualTo(ROW_1_CANONICAL_IMAGE);
        }

        @Test
        @DisplayName("identity is the card number alone, so two rows resolving to different accounts "
                + "are still one identity when their card numbers agree")
        void identityIsTheCardNumberAlone() {
            // The documented contract: equals and hashCode consider the identifier only, because the
            // identifier is the cluster key and a row's identity in the target is that same business
            // key. Nothing here asserts on the rendering, which withholds all three values.
            final CardCrossReference decoded = CardXrefRecordMapper.fromRecord(ROW_0_IMAGE);
            final CardCrossReference sameKeyOtherValues =
                    new CardCrossReference(ROW_0_CARD_NUM, ROW_1_CUST_ID, ROW_1_ACCT_ID);
            final CardCrossReference otherKey = CardXrefRecordMapper.fromRecord(ROW_1_IMAGE);

            assertThat(sameKeyOtherValues)
                    .as("the same card number is the same row, whatever else differs")
                    .isEqualTo(decoded)
                    .hasSameHashCodeAs(decoded);
            assertThat(otherKey)
                    .as("a different card number is a different row")
                    .isNotEqualTo(decoded);
            assertThat(decoded)
                    .as("reflexive, and not equal to an unrelated type or to null")
                    .isEqualTo(decoded)
                    .isNotEqualTo(ROW_0_CARD_NUM)
                    .isNotEqualTo(null);
        }
    }
}
