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
import com.carddemo.support.SeededRecordFixture;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit test for {@link CardXrefRecordMapper}, the two-way mapping between the legacy card
 * cross-reference record and {@link CardCrossReference}.
 *
 * <p><strong>What is under test.</strong> One record layout declared 50 bytes wide of which only 36
 * carry information, split three ways: a sixteen-byte card number at offset 0 that is also the whole
 * of the cluster key, a nine-byte customer identifier, an eleven-byte owning account identifier, and
 * then a fourteen-byte trailing filler run at offset 36 that no column represents. That filler is the
 * whole reason this mapper accepts two widths rather than one, and it is the arithmetic behind the two
 * artefact sizes the migration has to reconcile: the committed text fixture measures 1,850 bytes
 * because 50 rows of 36 data bytes plus one terminator each is 1,850, while the sequential dataset
 * measures 2,500 because the same 50 rows carry the filler. Both are asserted below.
 *
 * <p><strong>Every expectation in this class is hand written.</strong> No assertion asks the mapper,
 * the slicing primitive or the entity to compute the value it is then compared against. The offsets
 * and widths below were read from the copybook {@code app/cpy/CVACT03Y.cpy} and the base cluster
 * definition in {@code app/jcl/XREFFILE.jcl}, whose alternate index keys the owning account
 * identifier at {@code KEYS(11 25)} - the same offset 25 and length 11 restated here as independent
 * literals, so a wrong constant in the mapper disagrees with this file rather than being confirmed
 * by it.
 *
 * <p><strong>The seeded fixture is the second, independent oracle.</strong> The fifty committed
 * cross-reference records are read and reassembled byte for byte through the mapper in both
 * directions, at the data width they are stored at. That is the assertion which proves the offsets
 * are right against real data rather than against a single hand-built image, and it is stated here -
 * in the mapper's own suite - because the mapper is the production code that owns fixed-width slicing
 * for this record. The entity's own suite deliberately references no mapper, so that neither can be
 * wrong together with the other.
 *
 * <p><strong>Provenance.</strong> Legacy checkout SHA 7756d895ffeb65f7ea72aaa609e356d9899afcec;
 * upstream release stamp CardDemo_v1.0-15-g27d6c6f-68 (2022-07-19).
 */
@DisplayName("CardXrefRecordMapper - the 50-byte card cross-reference record (CVACT03Y)")
class CardXrefRecordMapperTest {

    /** The committed reference fixture, fifty records of the data width. */
    private static final String FIXTURE_NAME = "cardxref.txt";

    /** The declared record width, filler included. */
    private static final int RECORD_WIDTH = 50;

    /** The width of the mapped prefix, which is the width the committed text fixture is stored at. */
    private static final int DATA_WIDTH = 36;

    /** The width of the trailing filler run no column represents. */
    private static final int FILLER_WIDTH = 14;

    /** The number of records the committed fixture carries. */
    private static final int FIXTURE_RECORD_COUNT = 50;

    /** The byte count of the committed text fixture: fifty data rows, each newline terminated. */
    private static final int FIXTURE_BYTE_COUNT = 1850;

    /** The byte count of the unterminated sequential dataset: fifty rows at the declared width. */
    private static final int SEQUENTIAL_DATASET_BYTE_COUNT = 2500;

    /** A hand-built card number: sixteen digits, deliberately leading with a zero. */
    private static final String CARD_NUM = "0123456789012345";

    /** A hand-built customer identifier at its declared nine digits, leading with zeros. */
    private static final String CUST_ID = "000000042";

    /** A hand-built owning account identifier at its declared eleven digits. */
    private static final String ACCT_ID = "00000000017";

    /** The 36-byte data projection of the three values above, assembled by hand. */
    private static final String DATA_IMAGE = CARD_NUM + CUST_ID + ACCT_ID;

    /** The full 50-byte image: the same data projection followed by fourteen spaces. */
    private static final String FULL_IMAGE = DATA_IMAGE + " ".repeat(FILLER_WIDTH);

    /**
     * Measures a value as the mapper measures it, in encoded US-ASCII bytes.
     *
     * @param  value the value to measure
     * @return its encoded byte count
     */
    private static int encodedWidth(final String value) {
        return value.getBytes(StandardCharsets.US_ASCII).length;
    }

    /**
     * Builds the hand-written entity the images above correspond to.
     *
     * @return a fully populated cross-reference
     */
    private static CardCrossReference handBuiltEntity() {
        return new CardCrossReference(CARD_NUM, CUST_ID, ACCT_ID);
    }

    @Nested
    @DisplayName("Declared geometry")
    class DeclaredGeometry {

        @Test
        @DisplayName("the record is 50 bytes, of which the leading 36 are mapped and the trailing 14 "
                + "are filler")
        void theRecordIsFiftyBytesOfWhichThirtySixAreMapped() {
            assertThat(CardXrefRecordMapper.RECORD_LENGTH).isEqualTo(RECORD_WIDTH);
            assertThat(CardXrefRecordMapper.DATA_RECORD_LENGTH).isEqualTo(DATA_WIDTH);
            assertThat(CardXrefRecordMapper.FILLER_LENGTH).isEqualTo(FILLER_WIDTH);
            assertThat(CardXrefRecordMapper.FILLER_OFFSET).isEqualTo(DATA_WIDTH);
            assertThat(CardXrefRecordMapper.DATA_RECORD_LENGTH
                    + CardXrefRecordMapper.FILLER_LENGTH)
                    .as("the mapped prefix and the filler together account for the whole record")
                    .isEqualTo(CardXrefRecordMapper.RECORD_LENGTH);
            assertThat(CardXrefRecordMapper.FILLER_CHARACTER).isEqualTo(' ');
        }

        @Test
        @DisplayName("the three mapped fields are contiguous from offset zero and end at 36, where "
                + "the filler begins")
        void theThreeMappedFieldsAreContiguousFromOffsetZero() {
            assertThat(CardXrefRecordMapper.XREF_CARD_NUM_OFFSET).isZero();
            assertThat(CardXrefRecordMapper.XREF_CUST_ID_OFFSET).isEqualTo(16);
            assertThat(CardXrefRecordMapper.XREF_ACCT_ID_OFFSET)
                    .as("the alternate index of app/jcl/XREFFILE.jcl keys this field at KEYS(11 25)")
                    .isEqualTo(25);
            assertThat(CardXrefRecordMapper.XREF_ACCT_ID_OFFSET
                    + CardXrefRecordMapper.XREF_ACCT_ID_LENGTH)
                    .as("the last mapped byte is the one before the filler starts")
                    .isEqualTo(CardXrefRecordMapper.FILLER_OFFSET);
        }

        @Test
        @DisplayName("each field carries the width its copybook clause declares, stated here as "
                + "independent literals")
        void eachFieldCarriesItsDeclaredWidth() {
            assertThat(CardXrefRecordMapper.XREF_CARD_NUM_LENGTH).isEqualTo(16);
            assertThat(CardXrefRecordMapper.XREF_CUST_ID_LENGTH).isEqualTo(9);
            assertThat(CardXrefRecordMapper.XREF_ACCT_ID_LENGTH).isEqualTo(11);
            assertThat(CardXrefRecordMapper.XREF_CARD_NUM_LENGTH
                    + CardXrefRecordMapper.XREF_CUST_ID_LENGTH
                    + CardXrefRecordMapper.XREF_ACCT_ID_LENGTH)
                    .isEqualTo(DATA_WIDTH);
        }

        @Test
        @DisplayName("the cluster key is the whole of the leading card number and nothing more")
        void theClusterKeyIsTheWholeOfTheLeadingCardNumber() {
            assertThat(CardXrefRecordMapper.KEY_LENGTH)
                    .isEqualTo(CardXrefRecordMapper.XREF_CARD_NUM_LENGTH)
                    .isEqualTo(16);
            assertThat(CardXrefRecordMapper.XREF_CARD_NUM_OFFSET)
                    .as("a key that did not start at offset zero would not be a leading substring")
                    .isZero();
        }

        @Test
        @DisplayName("both artefact names identify the copybook, so a diagnostic says which width was "
                + "in force")
        void bothArtefactNamesIdentifyTheCopybook() {
            assertThat(CardXrefRecordMapper.ARTEFACT).contains("CVACT03Y");
            assertThat(CardXrefRecordMapper.DATA_ARTEFACT)
                    .contains("CVACT03Y")
                    .isNotEqualTo(CardXrefRecordMapper.ARTEFACT);
        }
    }

    @Nested
    @DisplayName("Reading a record")
    class ReadingARecord {

        @Test
        @DisplayName("every field of a hand-written image reaches its own property, so a transposition "
                + "cannot pass")
        void everyFieldReachesItsOwnProperty() {
            final CardCrossReference xref = CardXrefRecordMapper.fromRecord(FULL_IMAGE);

            assertThat(xref.getXrefCardNum()).isEqualTo(CARD_NUM);
            assertThat(xref.getXrefCustId()).isEqualTo(CUST_ID);
            assertThat(xref.getXrefAcctId()).isEqualTo(ACCT_ID);
        }

        @Test
        @DisplayName("the 36-byte data projection reads to the same entity as the 50-byte image, "
                + "because the width decides only whether a filler run follows")
        void theDataProjectionReadsToTheSameEntity() {
            final CardCrossReference fromData = CardXrefRecordMapper.fromRecord(DATA_IMAGE);
            final CardCrossReference fromFull = CardXrefRecordMapper.fromRecord(FULL_IMAGE);

            assertThat(fromData.getXrefCardNum()).isEqualTo(fromFull.getXrefCardNum());
            assertThat(fromData.getXrefCustId()).isEqualTo(fromFull.getXrefCustId());
            assertThat(fromData.getXrefAcctId()).isEqualTo(fromFull.getXrefAcctId());
        }

        @Test
        @DisplayName("leading zeros survive on both identifiers, because neither is parsed to a "
                + "numeric type at any point")
        void leadingZerosSurviveOnBothIdentifiers() {
            final CardCrossReference xref = CardXrefRecordMapper.fromRecord(FULL_IMAGE);

            assertThat(xref.getXrefCardNum()).startsWith("0").hasSize(16);
            assertThat(xref.getXrefCustId()).startsWith("000").hasSize(9);
            assertThat(xref.getXrefAcctId()).startsWith("000").hasSize(11);
        }

        @Test
        @DisplayName("the byte-array form reads identically to the string form")
        void theByteArrayFormReadsIdentically() {
            final CardCrossReference xref = CardXrefRecordMapper.fromRecord(
                    FULL_IMAGE.getBytes(StandardCharsets.US_ASCII));

            assertThat(xref.getXrefCardNum()).isEqualTo(CARD_NUM);
            assertThat(xref.getXrefCustId()).isEqualTo(CUST_ID);
            assertThat(xref.getXrefAcctId()).isEqualTo(ACCT_ID);
        }

        @Test
        @DisplayName("a record is read from its own offset inside a larger blocked buffer, at the "
                + "width the caller states")
        void aRecordIsReadFromItsOwnOffsetInsideABuffer() {
            final String blocked = FULL_IMAGE + FULL_IMAGE;
            final byte[] buffer = blocked.getBytes(StandardCharsets.US_ASCII);

            final CardCrossReference second =
                    CardXrefRecordMapper.fromRecord(buffer, RECORD_WIDTH, RECORD_WIDTH);

            assertThat(second.getXrefCardNum()).isEqualTo(CARD_NUM);
            assertThat(second.getXrefAcctId()).isEqualTo(ACCT_ID);
        }

        @Test
        @DisplayName("the terminated text stride reads the second data row from offset 37, which is "
                + "the stride the committed fixture is written at")
        void theTerminatedTextStrideReadsTheSecondDataRow() {
            final String terminated = DATA_IMAGE + "\n" + DATA_IMAGE + "\n";
            final byte[] buffer = terminated.getBytes(StandardCharsets.US_ASCII);

            final CardCrossReference second =
                    CardXrefRecordMapper.fromRecord(buffer, DATA_WIDTH + 1, DATA_WIDTH);

            assertThat(second.getXrefCardNum()).isEqualTo(CARD_NUM);
            assertThat(second.getXrefCustId()).isEqualTo(CUST_ID);
            assertThat(second.getXrefAcctId()).isEqualTo(ACCT_ID);
        }
    }

    @Nested
    @DisplayName("Writing a record")
    class WritingARecord {

        @Test
        @DisplayName("a hand-written image survives a read followed by a write unchanged, character "
                + "for character")
        void aHandWrittenImageSurvivesARoundTrip() {
            assertThat(CardXrefRecordMapper.toRecord(CardXrefRecordMapper.fromRecord(FULL_IMAGE)))
                    .isEqualTo(FULL_IMAGE);
        }

        @Test
        @DisplayName("the written image blank fills the fourteen trailing bytes no column represents")
        void theWrittenImageBlankFillsTheTrailingFiller() {
            final String image = CardXrefRecordMapper.toRecord(handBuiltEntity());

            assertThat(encodedWidth(image)).isEqualTo(RECORD_WIDTH);
            assertThat(image.substring(CardXrefRecordMapper.FILLER_OFFSET))
                    .hasSize(FILLER_WIDTH)
                    .isBlank();
        }

        @Test
        @DisplayName("the data projection emits the mapped prefix alone, at the width the committed "
                + "text fixture is stored at")
        void theDataProjectionEmitsTheMappedPrefixAlone() {
            final String projection = CardXrefRecordMapper.toDataRecord(handBuiltEntity());

            assertThat(encodedWidth(projection)).isEqualTo(DATA_WIDTH);
            assertThat(projection).isEqualTo(DATA_IMAGE);
            assertThat(CardXrefRecordMapper.toRecord(handBuiltEntity()))
                    .as("the canonical image is the projection plus the filler and nothing else")
                    .startsWith(projection);
        }

        @Test
        @DisplayName("both byte-array forms write the same bytes their string counterparts write, and "
                + "hand back a fresh array each time")
        void bothByteArrayFormsWriteTheSameBytes() {
            final byte[] full = CardXrefRecordMapper.toRecordBytes(handBuiltEntity());
            final byte[] data = CardXrefRecordMapper.toDataRecordBytes(handBuiltEntity());

            assertThat(full).hasSize(RECORD_WIDTH)
                    .isEqualTo(FULL_IMAGE.getBytes(StandardCharsets.US_ASCII));
            assertThat(data).hasSize(DATA_WIDTH)
                    .isEqualTo(DATA_IMAGE.getBytes(StandardCharsets.US_ASCII));
            assertThat(CardXrefRecordMapper.toRecordBytes(handBuiltEntity()))
                    .as("a fresh array is handed back rather than a shared one")
                    .isNotSameAs(full);
        }

        @Test
        @DisplayName("an identifier handed over short is widened by leading zeros, exactly as a "
                + "legacy move into PIC 9(n) would widen it")
        void anIdentifierHandedOverShortIsWidenedByLeadingZeros() {
            final String image = CardXrefRecordMapper.toRecord(
                    new CardCrossReference(CARD_NUM, "42", "17"));

            assertThat(image.substring(CardXrefRecordMapper.XREF_CUST_ID_OFFSET,
                    CardXrefRecordMapper.XREF_CUST_ID_OFFSET
                            + CardXrefRecordMapper.XREF_CUST_ID_LENGTH))
                    .isEqualTo("000000042");
            assertThat(image.substring(CardXrefRecordMapper.XREF_ACCT_ID_OFFSET,
                    CardXrefRecordMapper.XREF_ACCT_ID_OFFSET
                            + CardXrefRecordMapper.XREF_ACCT_ID_LENGTH))
                    .isEqualTo("00000000017");
        }

        @Test
        @DisplayName("a card number handed over short is space padded to the right, matching PIC X(16)")
        void aCardNumberHandedOverShortIsSpacePaddedToTheRight() {
            final String image = CardXrefRecordMapper.toRecord(
                    new CardCrossReference("4111", CUST_ID, ACCT_ID));

            assertThat(image.substring(CardXrefRecordMapper.XREF_CARD_NUM_OFFSET,
                    CardXrefRecordMapper.XREF_CARD_NUM_LENGTH))
                    .isEqualTo("4111            ")
                    .startsWith("4111");
        }
    }

    @Nested
    @DisplayName("The seeded fixture, read as an independent oracle")
    class TheSeededFixture {

        @Test
        @DisplayName("all fifty committed records reassemble byte for byte through the mapper, which "
                + "is the offsets proved against real data")
        void allFiftyCommittedRecordsReassembleByteForByte() {
            final SeededRecordFixture fixture =
                    SeededRecordFixture.load(FIXTURE_NAME, DATA_WIDTH);

            assertThat(fixture.recordCount()).isEqualTo(FIXTURE_RECORD_COUNT);
            for (final String image : fixture.records()) {
                assertThat(image).hasSize(DATA_WIDTH);
                assertThat(CardXrefRecordMapper.toDataRecord(
                        CardXrefRecordMapper.fromRecord(image)))
                        .as("record must survive a round trip unchanged")
                        .isEqualTo(image);
            }
        }

        @Test
        @DisplayName("every committed record carries a sixteen-digit key, a nine-digit customer "
                + "identifier and an eleven-digit account identifier")
        void everyCommittedRecordCarriesTheThreeDeclaredIdentifiers() {
            final SeededRecordFixture fixture =
                    SeededRecordFixture.load(FIXTURE_NAME, DATA_WIDTH);

            for (final String image : fixture.records()) {
                final CardCrossReference xref = CardXrefRecordMapper.fromRecord(image);

                assertThat(xref.getXrefCardNum()).hasSize(16).containsOnlyDigits();
                assertThat(xref.getXrefCustId()).hasSize(9).containsOnlyDigits();
                assertThat(xref.getXrefAcctId()).hasSize(11).containsOnlyDigits();
            }
        }

        @Test
        @DisplayName("the two artefact sizes are reconciled by the filler: 1,850 bytes terminated at "
                + "the data width against 2,500 unterminated at the declared width")
        void theTwoArtefactSizesAreReconciledByTheFiller() {
            final SeededRecordFixture fixture =
                    SeededRecordFixture.load(FIXTURE_NAME, DATA_WIDTH);

            assertThat(fixture.recordCount() * (DATA_WIDTH + 1))
                    .as("fifty data rows, each newline terminated, is the committed fixture's size")
                    .isEqualTo(FIXTURE_BYTE_COUNT);
            assertThat(fixture.recordCount() * RECORD_WIDTH)
                    .as("the same fifty rows carrying the filler is the sequential dataset's size")
                    .isEqualTo(SEQUENTIAL_DATASET_BYTE_COUNT);
            assertThat(SEQUENTIAL_DATASET_BYTE_COUNT - FIXTURE_BYTE_COUNT)
                    .as("the whole difference is fifty fillers less fifty terminators")
                    .isEqualTo(fixture.recordCount() * (FILLER_WIDTH - 1));
        }

        @Test
        @DisplayName("a committed record widened to the declared width reads to the same entity, so a "
                + "reader may take either artefact")
        void aCommittedRecordWidenedToTheDeclaredWidthReadsTheSame() {
            final SeededRecordFixture fixture =
                    SeededRecordFixture.load(FIXTURE_NAME, DATA_WIDTH);
            final String first = fixture.record(1);

            final CardCrossReference fromData = CardXrefRecordMapper.fromRecord(first);
            final CardCrossReference fromWidened =
                    CardXrefRecordMapper.fromRecord(first + " ".repeat(FILLER_WIDTH));

            assertThat(fromWidened.getXrefCardNum()).isEqualTo(fromData.getXrefCardNum());
            assertThat(fromWidened.getXrefCustId()).isEqualTo(fromData.getXrefCustId());
            assertThat(fromWidened.getXrefAcctId()).isEqualTo(fromData.getXrefAcctId());
            assertThat(CardXrefRecordMapper.toRecord(fromData))
                    .as("and writing it back at the declared width restores the filler")
                    .isEqualTo(first + " ".repeat(FILLER_WIDTH));
        }
    }

    @Nested
    @DisplayName("Rejected input")
    class RejectedInput {

        @Test
        @DisplayName("an image one byte too long is rejected, and the message names the layout so an "
                + "unstripped terminator is diagnosable")
        void anImageOneByteTooLongIsRejected() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> CardXrefRecordMapper.fromRecord(FULL_IMAGE + " "))
                    .withMessageContaining("CVACT03Y");
        }

        @Test
        @DisplayName("an image between the two accepted widths is rejected rather than padded to fit")
        void anImageBetweenTheTwoAcceptedWidthsIsRejected() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> CardXrefRecordMapper.fromRecord(DATA_IMAGE + "   "))
                    .withMessageContaining("CVACT03Y");
        }

        @Test
        @DisplayName("a byte array at an unaccepted width is rejected on its own length")
        void aByteArrayAtAnUnacceptedWidthIsRejected() {
            final byte[] tooShort = new byte[DATA_WIDTH - 1];

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> CardXrefRecordMapper.fromRecord(tooShort))
                    .withMessageContaining("CVACT03Y");
        }

        @Test
        @DisplayName("a buffer read at a width that is neither accepted is refused before any slice "
                + "is taken")
        void aBufferReadAtAnUnacceptedWidthIsRefused() {
            final byte[] buffer = FULL_IMAGE.getBytes(StandardCharsets.US_ASCII);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> CardXrefRecordMapper.fromRecord(buffer, 0, 40));
        }

        @Test
        @DisplayName("a buffer read that runs off the end is reported rather than truncated")
        void aBufferReadThatRunsOffTheEndIsReported() {
            final byte[] buffer = FULL_IMAGE.getBytes(StandardCharsets.US_ASCII);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> CardXrefRecordMapper.fromRecord(buffer, 1, RECORD_WIDTH));
        }

        @Test
        @DisplayName("a value wider than its field is rejected rather than silently trimmed")
        void aValueWiderThanItsFieldIsRejected() {
            final CardCrossReference overlong =
                    new CardCrossReference(CARD_NUM + "0", CUST_ID, ACCT_ID);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> CardXrefRecordMapper.toRecord(overlong));
        }

        @Test
        @DisplayName("a null image, a null buffer and a null cross-reference are all refused")
        void everyNullIsRefused() {
            assertThatNullPointerException()
                    .isThrownBy(() -> CardXrefRecordMapper.fromRecord((String) null));
            assertThatNullPointerException()
                    .isThrownBy(() -> CardXrefRecordMapper.fromRecord((byte[]) null));
            assertThatNullPointerException()
                    .isThrownBy(() -> CardXrefRecordMapper.fromRecord(null, 0, RECORD_WIDTH));
            assertThatNullPointerException()
                    .isThrownBy(() -> CardXrefRecordMapper.toRecord(null));
            assertThatNullPointerException()
                    .isThrownBy(() -> CardXrefRecordMapper.toDataRecord(null));
        }

        @Test
        @DisplayName("a partially populated cross-reference names the legacy field it is missing")
        void aPartiallyPopulatedCrossReferenceNamesTheMissingField() {
            final CardCrossReference missingAccount =
                    new CardCrossReference(CARD_NUM, CUST_ID, null);

            assertThatNullPointerException()
                    .isThrownBy(() -> CardXrefRecordMapper.toRecord(missingAccount))
                    .withMessageContaining("XREF-ACCT-ID");
        }
    }
}
