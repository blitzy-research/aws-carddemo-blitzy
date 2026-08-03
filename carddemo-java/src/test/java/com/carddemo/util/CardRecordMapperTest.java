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

import com.carddemo.domain.Card;
import com.carddemo.support.SeededRecordFixture;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit test for {@link CardRecordMapper}, the two-way mapping between the 150-byte legacy card
 * record and {@link Card}.
 *
 * <p><strong>What is under test.</strong> One record layout, 150 bytes wide, split seven ways: the
 * sixteen-byte card number at offset 0 that is also the whole of the cluster key, an eleven-byte
 * owning account identifier, a three-byte verification code, a fifty-byte embossed name, a
 * ten-byte expiry held as text, a one-byte active-status code, and a fifty-nine byte trailing filler
 * run at offset 91 that no column represents. Only ninety-one of the hundred and fifty bytes carry a
 * mapped field, which is why the filler is asserted here in its own right rather than assumed.
 *
 * <p><strong>Every expectation in this class is hand written.</strong> No assertion asks the mapper,
 * the slicing primitive or the entity to compute the value it is then compared against. The offsets
 * and widths below were read from the copybook {@code app/cpy/CVACT02Y.cpy} and the base cluster
 * definition in {@code app/jcl/CARDFILE.jcl} and are restated here as independent literals, so a
 * wrong constant in the mapper disagrees with this file rather than being confirmed by it.
 *
 * <p><strong>The seeded fixture is the second, independent oracle.</strong> The fifty committed card
 * records are read and reassembled byte for byte through the mapper in both directions. That is the
 * assertion which proves the offsets are right against real data rather than against a single
 * hand-built image, and it is stated here - in the mapper's own suite - because the mapper is the
 * production code that owns fixed-width slicing for this record. The entity's own suite deliberately
 * references no mapper, so that neither can be wrong together with the other.
 *
 * <p><strong>Provenance.</strong> Legacy checkout SHA 7756d895ffeb65f7ea72aaa609e356d9899afcec;
 * upstream release stamp CardDemo_v1.0-15-g27d6c6f-68 (2022-07-19).
 */
@DisplayName("CardRecordMapper - the 150-byte card record (CVACT02Y)")
class CardRecordMapperTest {

    /** The committed reference fixture, fifty records of the declared width. */
    private static final String FIXTURE_NAME = "carddata.txt";

    /** The record width the copybook declares. */
    private static final int RECORD_WIDTH = 150;

    /** The number of records the committed fixture carries. */
    private static final int FIXTURE_RECORD_COUNT = 50;

    /** A hand-built card number: sixteen digits, deliberately leading with a zero. */
    private static final String CARD_NUM = "0123456789012345";

    /** A hand-built owning account identifier at its declared eleven digits. */
    private static final String ACCT_ID = "00000000011";

    /** A hand-built verification code, leading with a zero so no numeric coercion can pass. */
    private static final String CVV_CD = "047";

    /** The unpadded embossed name, mixed case and carrying an embedded space. */
    private static final String EMBOSSED_NAME_TEXT = "Aniya Von";

    /** The expiry in the ten-character external form the legacy field holds. */
    private static final String EXPIRATION_DATE = "2023-03-09";

    /** The one-byte active-status code, carried raw. */
    private static final String ACTIVE_STATUS = "Y";

    /**
     * Builds the hand-written 150-byte image the assertions below are stated against.
     *
     * <p>Assembled by concatenation from the field literals and explicit padding runs rather than by
     * asking the mapper to write it, so it is an independent statement of the layout.
     *
     * @return the record image, exactly {@value #RECORD_WIDTH} characters wide
     */
    private static String handWrittenImage() {
        return CARD_NUM
                + ACCT_ID
                + CVV_CD
                + EMBOSSED_NAME_TEXT + " ".repeat(50 - EMBOSSED_NAME_TEXT.length())
                + EXPIRATION_DATE
                + ACTIVE_STATUS
                + " ".repeat(59);
    }

    /** The embossed name at its full declared width, space filled on the right. */
    private static String paddedEmbossedName() {
        return EMBOSSED_NAME_TEXT + " ".repeat(50 - EMBOSSED_NAME_TEXT.length());
    }

    @Nested
    @DisplayName("Declared geometry")
    class DeclaredGeometry {

        @Test
        @DisplayName("the record is 150 bytes, of which the key is the leading 16 and the remainder "
                + "accounts for the other 134")
        void theRecordIsOneHundredAndFiftyBytes() {
            assertThat(CardRecordMapper.RECORD_LENGTH).isEqualTo(150);
            assertThat(CardRecordMapper.KEY_LENGTH).isEqualTo(16);
            assertThat(CardRecordMapper.DATA_LENGTH).isEqualTo(134);
            assertThat(CardRecordMapper.KEY_LENGTH + CardRecordMapper.DATA_LENGTH)
                    .isEqualTo(CardRecordMapper.RECORD_LENGTH);
        }

        @Test
        @DisplayName("the six mapped fields are contiguous from offset zero and end at 91, where the "
                + "unmapped filler begins")
        void theMappedFieldsAreContiguous() {
            assertThat(CardRecordMapper.CARD_NUM_OFFSET).isZero();
            assertThat(CardRecordMapper.CARD_NUM_OFFSET + CardRecordMapper.CARD_NUM_LENGTH)
                    .isEqualTo(CardRecordMapper.CARD_ACCT_ID_OFFSET);
            assertThat(CardRecordMapper.CARD_ACCT_ID_OFFSET + CardRecordMapper.CARD_ACCT_ID_LENGTH)
                    .isEqualTo(CardRecordMapper.CARD_CVV_CD_OFFSET);
            assertThat(CardRecordMapper.CARD_CVV_CD_OFFSET + CardRecordMapper.CARD_CVV_CD_LENGTH)
                    .isEqualTo(CardRecordMapper.CARD_EMBOSSED_NAME_OFFSET);
            assertThat(CardRecordMapper.CARD_EMBOSSED_NAME_OFFSET
                    + CardRecordMapper.CARD_EMBOSSED_NAME_LENGTH)
                    .isEqualTo(CardRecordMapper.CARD_EXPIRAION_DATE_OFFSET);
            assertThat(CardRecordMapper.CARD_EXPIRAION_DATE_OFFSET
                    + CardRecordMapper.CARD_EXPIRAION_DATE_LENGTH)
                    .isEqualTo(CardRecordMapper.CARD_ACTIVE_STATUS_OFFSET);
            assertThat(CardRecordMapper.CARD_ACTIVE_STATUS_OFFSET
                    + CardRecordMapper.CARD_ACTIVE_STATUS_LENGTH)
                    .isEqualTo(CardRecordMapper.FILLER_OFFSET);
        }

        @Test
        @DisplayName("each field carries the width its copybook clause declares, stated here as "
                + "literals rather than derived from the mapper")
        void eachFieldCarriesItsDeclaredWidth() {
            assertThat(CardRecordMapper.CARD_NUM_LENGTH).isEqualTo(16);
            assertThat(CardRecordMapper.CARD_ACCT_ID_LENGTH).isEqualTo(11);
            assertThat(CardRecordMapper.CARD_CVV_CD_LENGTH).isEqualTo(3);
            assertThat(CardRecordMapper.CARD_EMBOSSED_NAME_LENGTH).isEqualTo(50);
            assertThat(CardRecordMapper.CARD_EXPIRAION_DATE_LENGTH).isEqualTo(10);
            assertThat(CardRecordMapper.CARD_ACTIVE_STATUS_LENGTH).isEqualTo(1);
        }

        @Test
        @DisplayName("the mapped prefix is 91 bytes and the filler is the 59 that follow, so the two "
                + "together account for every byte of the record")
        void theMappedPrefixAndFillerAccountForTheWholeRecord() {
            assertThat(CardRecordMapper.MAPPED_PREFIX_LENGTH).isEqualTo(91);
            assertThat(CardRecordMapper.FILLER_OFFSET).isEqualTo(91);
            assertThat(CardRecordMapper.FILLER_LENGTH).isEqualTo(59);
            assertThat(CardRecordMapper.MAPPED_PREFIX_LENGTH + CardRecordMapper.FILLER_LENGTH)
                    .isEqualTo(CardRecordMapper.RECORD_LENGTH);
            assertThat(CardRecordMapper.FILLER_CHARACTER).isEqualTo(' ');
        }
    }

    @Nested
    @DisplayName("Reading a record")
    class ReadingARecord {

        @Test
        @DisplayName("every field of a hand-written image reaches its own property, so a transposition "
                + "of any two same-typed positions is caught")
        void everyFieldReachesItsOwnProperty() {
            final Card card = CardRecordMapper.fromRecord(handWrittenImage());

            assertThat(card.getCardNum()).isEqualTo(CARD_NUM);
            assertThat(card.getCardAcctId()).isEqualTo(ACCT_ID);
            assertThat(card.getCardCvvCd()).isEqualTo(CVV_CD);
            assertThat(card.getCardEmbossedName()).isEqualTo(paddedEmbossedName());
            assertThat(card.getCardExpirationDate()).isEqualTo(EXPIRATION_DATE);
            assertThat(card.getCardActiveStatus()).isEqualTo(ACTIVE_STATUS);
        }

        @Test
        @DisplayName("a leading zero survives on the card number and on the verification code, so "
                + "neither is coerced through a numeric type on the way in")
        void leadingZeroesSurvive() {
            final Card card = CardRecordMapper.fromRecord(handWrittenImage());

            assertThat(card.getCardNum()).startsWith("0").hasSize(16);
            assertThat(card.getCardCvvCd()).isEqualTo("047").startsWith("0").hasSize(3);
        }

        @Test
        @DisplayName("the embossed name keeps its trailing padding and its embedded space, because the "
                + "field is fixed width and is never trimmed or tokenised")
        void theEmbossedNameKeepsItsPaddingAndEmbeddedSpace() {
            final Card card = CardRecordMapper.fromRecord(handWrittenImage());

            assertThat(card.getCardEmbossedName())
                    .hasSize(50)
                    .startsWith("Aniya Von")
                    .endsWith(" ")
                    .isNotEqualTo(EMBOSSED_NAME_TEXT);
            assertThat(card.getCardEmbossedName().charAt(5)).isEqualTo(' ');
        }

        @Test
        @DisplayName("the byte-array form reads identically to the string form")
        void theByteArrayFormReadsIdentically() {
            final Card fromText = CardRecordMapper.fromRecord(handWrittenImage());
            final Card fromBytes = CardRecordMapper.fromRecord(
                    handWrittenImage().getBytes(StandardCharsets.US_ASCII));

            assertThat(fromBytes.getCardNum()).isEqualTo(fromText.getCardNum());
            assertThat(fromBytes.getCardAcctId()).isEqualTo(fromText.getCardAcctId());
            assertThat(fromBytes.getCardCvvCd()).isEqualTo(fromText.getCardCvvCd());
            assertThat(fromBytes.getCardEmbossedName()).isEqualTo(fromText.getCardEmbossedName());
            assertThat(fromBytes.getCardExpirationDate())
                    .isEqualTo(fromText.getCardExpirationDate());
            assertThat(fromBytes.getCardActiveStatus()).isEqualTo(fromText.getCardActiveStatus());
        }

        @Test
        @DisplayName("a record is read from its own offset inside a larger blocked buffer, which is "
                + "how a fixed-block dataset presents consecutive records")
        void aRecordIsReadFromItsOffsetInsideABlockedBuffer() {
            final String leading = "9".repeat(RECORD_WIDTH);
            final byte[] block = (leading + handWrittenImage())
                    .getBytes(StandardCharsets.US_ASCII);

            final Card second = CardRecordMapper.fromRecord(block, RECORD_WIDTH);

            assertThat(block).hasSize(2 * RECORD_WIDTH);
            assertThat(second.getCardNum()).isEqualTo(CARD_NUM);
            assertThat(second.getCardActiveStatus()).isEqualTo(ACTIVE_STATUS);
        }
    }

    @Nested
    @DisplayName("Writing a record")
    class WritingARecord {

        @Test
        @DisplayName("a hand-written image survives a read followed by a write unchanged, character "
                + "for character, filler included")
        void anImageSurvivesAReadFollowedByAWrite() {
            final String image = handWrittenImage();

            final String rewritten = CardRecordMapper.toRecord(CardRecordMapper.fromRecord(image));

            assertThat(rewritten).isEqualTo(image).hasSize(RECORD_WIDTH);
        }

        @Test
        @DisplayName("the written image blank fills the fifty-nine trailing bytes no column represents")
        void theWrittenImageBlankFillsTheTrailingFiller() {
            final String written = CardRecordMapper.toRecord(
                    CardRecordMapper.fromRecord(handWrittenImage()));

            assertThat(written.substring(CardRecordMapper.FILLER_OFFSET))
                    .hasSize(CardRecordMapper.FILLER_LENGTH)
                    .isBlank()
                    .isEqualTo(" ".repeat(CardRecordMapper.FILLER_LENGTH));
        }

        @Test
        @DisplayName("the byte-array form writes the same 150 bytes the string form writes")
        void theByteArrayFormWritesTheSameBytes() {
            final Card card = CardRecordMapper.fromRecord(handWrittenImage());

            final byte[] written = CardRecordMapper.toRecordBytes(card);

            assertThat(written).hasSize(RECORD_WIDTH);
            assertThat(new String(written, StandardCharsets.US_ASCII))
                    .isEqualTo(CardRecordMapper.toRecord(card));
        }
    }

    @Nested
    @DisplayName("The seeded fixture, read as an independent oracle")
    class TheSeededFixture {

        @Test
        @DisplayName("all fifty committed records reassemble byte for byte through the mapper, which "
                + "proves the offsets against real data and not only against one built image")
        void allFiftyRecordsReassembleByteForByte() {
            final SeededRecordFixture fixture =
                    SeededRecordFixture.load(FIXTURE_NAME, RECORD_WIDTH);

            assertThat(fixture.recordCount()).isEqualTo(FIXTURE_RECORD_COUNT);
            for (final String image : fixture.records()) {
                assertThat(image).hasSize(RECORD_WIDTH);
                assertThat(CardRecordMapper.toRecord(CardRecordMapper.fromRecord(image)))
                        .as("record must survive a round trip unchanged")
                        .isEqualTo(image);
            }
        }

        @Test
        @DisplayName("every committed record carries a sixteen-digit key, an eleven-digit account "
                + "identifier and a blank trailing filler run")
        void everyCommittedRecordCarriesTheDeclaredShape() {
            final SeededRecordFixture fixture =
                    SeededRecordFixture.load(FIXTURE_NAME, RECORD_WIDTH);

            for (final String image : fixture.records()) {
                final Card card = CardRecordMapper.fromRecord(image);

                assertThat(card.getCardNum()).hasSize(16).containsOnlyDigits();
                assertThat(card.getCardAcctId()).hasSize(11).containsOnlyDigits();
                assertThat(card.getCardCvvCd()).hasSize(3).containsOnlyDigits();
                assertThat(card.getCardEmbossedName()).hasSize(50);
                assertThat(card.getCardExpirationDate()).hasSize(10);
                assertThat(card.getCardActiveStatus()).hasSize(1);
                assertThat(image.substring(CardRecordMapper.FILLER_OFFSET)).isBlank();
            }
        }
    }

    @Nested
    @DisplayName("Rejected input")
    class RejectedInput {

        @Test
        @DisplayName("an image one byte too long is rejected, and the message names the unstripped "
                + "line terminator that is almost always the cause")
        void anImageOneByteTooLongIsRejected() {
            final String withTerminator = handWrittenImage() + "\n";

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> CardRecordMapper.fromRecord(withTerminator))
                    .withMessageContaining("151")
                    .withMessageContaining("terminator");
        }

        @Test
        @DisplayName("an image one byte too short is rejected rather than padded to fit")
        void anImageOneByteTooShortIsRejected() {
            final String truncated = handWrittenImage().substring(0, RECORD_WIDTH - 1);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> CardRecordMapper.fromRecord(truncated))
                    .withMessageContaining("149")
                    .withMessageContaining("never padded or truncated");
        }

        @Test
        @DisplayName("a null image and a null card are both refused")
        void nullInputIsRefused() {
            assertThatNullPointerException()
                    .isThrownBy(() -> CardRecordMapper.fromRecord((String) null));
            assertThatNullPointerException()
                    .isThrownBy(() -> CardRecordMapper.fromRecord((byte[]) null));
            assertThatNullPointerException()
                    .isThrownBy(() -> CardRecordMapper.toRecord(null));
        }
    }
}
