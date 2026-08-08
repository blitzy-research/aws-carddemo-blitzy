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
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.carddemo.domain.Card;
import com.carddemo.support.SensitiveValues;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * Unit test for {@link CardRecordMapper}, the sole holder of the 150-byte card record layout and of
 * the two-way mapping between that record image and the {@link Card} entity.
 *
 * <h2>What is under test</h2>
 * One record layout, 150 encoded bytes wide, split seven ways: a sixteen-byte card number at offset
 * 0 which is also the whole of the cluster key, an eleven-byte owning account identifier, a
 * three-byte verification code, a fifty-byte embossed name, a ten-byte expiry held as text, a
 * one-byte active-status code, and a fifty-nine byte trailing filler run at offset 91 that no entity
 * property and no column represents. Only 91 of the 150 bytes carry a mapped field, which is why the
 * filler is asserted here in its own right rather than assumed.
 *
 * <h2>Every expectation in this class is hand written</h2>
 * No assertion asks the mapper, the shared slicing primitive {@link FixedWidthFieldReader} or the
 * entity to compute the value it is then compared against, and no assertion is a snapshot of
 * production output. The offsets and widths restated below were read from the copybook
 * {@code app/cpy/CVACT02Y.cpy} and from the base cluster definition in {@code app/jcl/CARDFILE.jcl}
 * and appear here as independent integer literals, so a wrong constant in the mapper disagrees with
 * this file rather than being confirmed by it. Likewise, no value is proved by a round trip alone:
 * every field is asserted directly on the way in and again on the way out.
 *
 * <h2>The three independent corroborations of the layout</h2>
 * <ul>
 *   <li>the copybook's six named fields plus its trailing filler run sum to exactly 150, which this
 *       suite asserts as {@code 16 + 11 + 3 + 50 + 10 + 1 + 59 = 150};</li>
 *   <li>the base cluster is defined with a sixteen-byte key at offset zero and a record fixed at 150
 *       for both its minimum and its maximum, {@code KEYS(16 0) RECORDSIZE(150 150)};</li>
 *   <li>the non-unique alternate index over the account identifier is declared as
 *       {@code KEYS(11 16) NONUNIQUEKEY UPGRADE}, whose offset of 16 and length of 11 independently
 *       fix the second field's position and width.</li>
 * </ul>
 *
 * <h2>The seeded row this suite is stated against</h2>
 * The shipped reference fixture {@code app/data/ASCII/carddata.txt} is 7,550 bytes, which factors
 * exactly as 50 records of 150 bytes each followed by a one-byte terminator: 50 x 151 = 7,550. Its
 * first record is transcribed into this file as literals and is never read from disk, so this suite
 * touches no file, no classpath resource, no database, no network and no clock, and stays a pure
 * unit test that a reviewer can check by eye against the fixture.
 *
 * <h2>The four divergences this file proves</h2>
 * Each is recorded in {@code docs/decision-log.md}; this suite is where each is held to.
 * <ul>
 *   <li>the copybook's misspelled expiry field name is preserved as a <em>layout position</em> while
 *       the Java property is spelled correctly - the offset is asserted to be exactly 80 and is
 *       never "fixed";</li>
 *   <li>the verification code stays a zero-padded {@code String} rather than becoming a number;</li>
 *   <li>no decimal conversion happens on this record, because it has no money field at all - the
 *       only mapper in the family of which that is true;</li>
 *   <li>a mis-sized record image raises {@link IllegalArgumentException} rather than any exception
 *       type belonging to this module, because a caller supplying the wrong number of bytes has no
 *       legacy antecedent: indexed and sequential records are fixed length by construction.</li>
 * </ul>
 *
 * <h2>Provenance</h2>
 * Legacy checkout SHA 7756d895ffeb65f7ea72aaa609e356d9899afcec; upstream release stamp
 * CardDemo_v1.0-15-g27d6c6f-68 (2022-07-19). These are provenance strings for the reader only: no
 * assertion in this file expects either of them on any member.
 *
 * @see CardRecordMapper
 * @see Card
 * @see FixedWidthFieldReader
 */
@DisplayName("CardRecordMapper - the 150-byte CARD-RECORD layout of CVACT02Y")
class CardRecordMapperTest {

    // ------------------------------------------------------------------------------------------
    // Geometry, restated as independent literals. Offsets are zero-based byte positions; widths are
    // encoded byte counts. Nothing here is derived from the class under test.
    // ------------------------------------------------------------------------------------------

    /** The record width the copybook states and the cluster fixes as both minimum and maximum. */
    private static final int RECORD_WIDTH = 150;

    /** Byte width of the cluster key, which the cluster declares at offset zero. */
    private static final int KEY_WIDTH = 16;

    /** Byte width of the record remainder after the key: 16 + 134 = 150. */
    private static final int DATA_WIDTH = 134;

    /** Bytes before the filler run, that is the span the mapper is the authority for. */
    private static final int MAPPED_PREFIX_WIDTH = 91;

    /** Zero-based offset of CARD-NUM, the leading field and the whole of the key. */
    private static final int CARD_NUM_OFFSET = 0;

    /** Byte width of CARD-NUM, PIC X(16). */
    private static final int CARD_NUM_WIDTH = 16;

    /** Zero-based offset of CARD-ACCT-ID, independently fixed by the alternate index. */
    private static final int ACCT_ID_OFFSET = 16;

    /** Byte width of CARD-ACCT-ID, PIC 9(11). */
    private static final int ACCT_ID_WIDTH = 11;

    /** Zero-based offset of CARD-CVV-CD. */
    private static final int CVV_CD_OFFSET = 27;

    /** Byte width of CARD-CVV-CD, PIC 9(03). Three characters always, leading zeros significant. */
    private static final int CVV_CD_WIDTH = 3;

    /** Zero-based offset of CARD-EMBOSSED-NAME. */
    private static final int EMBOSSED_NAME_OFFSET = 30;

    /** Byte width of CARD-EMBOSSED-NAME, PIC X(50). */
    private static final int EMBOSSED_NAME_WIDTH = 50;

    /**
     * Zero-based offset of the expiry date. The copybook genuinely misspells this field's name at
     * its physical line 9 - CARD-EXPIRAION-DATE, without the T of EXPIRATION - and the offset is
     * unchanged at 80 because of it. The defect is preserved where it is load bearing, which is the
     * byte position, and corrected where it is not, which is the Java property name. Recorded as
     * anomaly 1 of the source anomaly register. Corroborated independently by the card-update
     * program, which addresses this same field by substring at positions 1 for 4, 6 for 2 and 9
     * for 2 - a hyphenated ten-byte external form.
     */
    private static final int EXPIRAION_DATE_OFFSET = 80;

    /** Byte width of the expiry date, PIC X(10). */
    private static final int EXPIRAION_DATE_WIDTH = 10;

    /** Zero-based offset of CARD-ACTIVE-STATUS. */
    private static final int ACTIVE_STATUS_OFFSET = 90;

    /** Byte width of CARD-ACTIVE-STATUS, PIC X(01). */
    private static final int ACTIVE_STATUS_WIDTH = 1;

    /** Zero-based offset of the trailing filler run, which no property represents. */
    private static final int FILLER_OFFSET = 91;

    /** Byte width of the trailing filler run, FILLER PIC X(59). */
    private static final int FILLER_WIDTH = 59;

    /** The space byte all 50 rows of the shipped fixture carry across the filler run. */
    private static final byte ASCII_SPACE = 0x20;

    /** Trailing spaces the nine-character embossed name of the seeded row acquires: 50 - 9 = 41. */
    private static final int SEEDED_NAME_PAD_WIDTH = 41;

    // ------------------------------------------------------------------------------------------
    // Row 0 of the shipped reference fixture, transcribed field by field as literals.
    // ------------------------------------------------------------------------------------------

    /** CARD-NUM of the seeded row: sixteen digits whose leading digit is significant. */
    private static final String SEEDED_CARD_NUM = "0500024453765740";

    /**
     * CARD-ACCT-ID of the seeded row. Eleven characters with nine significant leading zeros. The
     * same value appears as the account identifier of row 0 of the card cross-reference fixture
     * {@code app/data/ASCII/cardxref.txt}, which is an independent corroboration that this slice is
     * being taken at the right offset rather than merely at a self-consistent one.
     */
    private static final String SEEDED_ACCT_ID = "00000000050";

    /** CARD-CVV-CD of the seeded row: three digits, carried as text. */
    private static final String SEEDED_CVV_CD = "747";

    /**
     * CARD-EMBOSSED-NAME of the seeded row before padding. Mixed case, and it carries an embedded
     * space: under the legacy blank-and-trim alphabetic idiom an embedded space is a valid character
     * in this field, so no assertion here may imply otherwise. Field-level validation of this value
     * belongs to the card-update service and is deliberately absent from this suite.
     */
    private static final String SEEDED_NAME_TEXT = "Aniya Von";

    /** The expiry of the seeded row, in the ten-character hyphenated external form. */
    private static final String SEEDED_EXPIRAION_DATE = "2023-03-09";

    /** CARD-ACTIVE-STATUS of the seeded row: a raw single-character code, never an enum here. */
    private static final String SEEDED_ACTIVE_STATUS = "Y";

    // ------------------------------------------------------------------------------------------
    // Hand-assembled expectations. Each is built by concatenating the literals above with explicit
    // padding runs of hand-derived length, never by asking the mapper to render anything.
    // ------------------------------------------------------------------------------------------

    /**
     * The embossed name of the seeded row at its full declared width.
     *
     * <p>Assembled as the nine-character value plus a hand-counted run of
     * {@value #SEEDED_NAME_PAD_WIDTH} spaces, because 50 - 9 = 41. The padding is contractual data
     * rather than incidental whitespace, so it is written out rather than trimmed away, and the value
     * is never case folded: the platform's locale-sensitive, Unicode-aware upper-casing conversion is
     * forbidden throughout this module, with or without an explicit locale, and the legacy fold is a
     * strict 26-character ASCII table applied by the card-update service, not during record mapping.
     *
     * @return the name padded to exactly 50 encoded bytes
     */
    private static String seededEmbossedName() {
        return SEEDED_NAME_TEXT + " ".repeat(SEEDED_NAME_PAD_WIDTH);
    }

    /**
     * The trailing filler run of the seeded row: {@value #FILLER_WIDTH} spaces.
     *
     * @return the filler run at exactly its declared width
     */
    private static String seededFillerRun() {
        return " ".repeat(FILLER_WIDTH);
    }

    /**
     * The complete 150-byte image of row 0 of the shipped fixture.
     *
     * <p>Assembled by concatenating the seven transcribed runs in copybook order. This is the
     * independent oracle the rest of the suite is stated against, and it is asserted to be exactly
     * {@value #RECORD_WIDTH} encoded bytes before it is relied upon.
     *
     * @return the record image, carrying no line terminator
     */
    private static String seededImage() {
        return SEEDED_CARD_NUM
                + SEEDED_ACCT_ID
                + SEEDED_CVV_CD
                + seededEmbossedName()
                + SEEDED_EXPIRAION_DATE
                + SEEDED_ACTIVE_STATUS
                + seededFillerRun();
    }

    /**
     * The mapped prefix of the seeded row: the {@value #MAPPED_PREFIX_WIDTH} bytes before the filler.
     *
     * <p>Assembled from the same literals rather than sliced out of {@link #seededImage()}, so the
     * prefix expectation and the whole-image expectation are two independent statements that must
     * agree with one another as well as with the mapper.
     *
     * @return the mapped prefix, carrying no filler and no terminator
     */
    private static String seededMappedPrefix() {
        return SEEDED_CARD_NUM
                + SEEDED_ACCT_ID
                + SEEDED_CVV_CD
                + seededEmbossedName()
                + SEEDED_EXPIRAION_DATE
                + SEEDED_ACTIVE_STATUS;
    }

    /**
     * Rebuilds the seeded image with one field replaced, so a single field can be varied in
     * isolation without any other byte of the record moving.
     *
     * <p>Substitution is by explicit re-concatenation of the seven runs rather than by overwriting a
     * range in place, which keeps the caller's replacement honest: a replacement of the wrong width
     * changes the total width and is caught by the mapper's own geometric check rather than silently
     * shifting every byte that follows.
     *
     * @param  verificationCode the value to place in the three-byte verification-code field
     * @return a complete record image differing from the seeded row only in that field
     */
    private static String seededImageWithVerificationCode(String verificationCode) {
        return SEEDED_CARD_NUM
                + SEEDED_ACCT_ID
                + verificationCode
                + seededEmbossedName()
                + SEEDED_EXPIRAION_DATE
                + SEEDED_ACTIVE_STATUS
                + seededFillerRun();
    }

    /**
     * Rebuilds the seeded image with the one-byte active-status field replaced.
     *
     * <p>Assembled by explicit re-concatenation of the seven runs, for the same reason as
     * {@link #seededImageWithVerificationCode(String)}: nothing is overwritten in place, so a
     * replacement of the wrong width shows up as a width failure rather than as a silent shift.
     *
     * @param  activeStatus the value to place in the one-byte active-status field
     * @return a complete record image differing from the seeded row only in that field
     */
    private static String seededImageWithActiveStatus(String activeStatus) {
        return SEEDED_CARD_NUM
                + SEEDED_ACCT_ID
                + SEEDED_CVV_CD
                + seededEmbossedName()
                + SEEDED_EXPIRAION_DATE
                + activeStatus
                + seededFillerRun();
    }

    /**
     * Builds the seeded card through the entity's public all-argument constructor.
     *
     * <p>The argument order is the copybook's own field order, and that is the point of routing a
     * write test through this constructor rather than through {@link CardRecordMapper#fromRecord}: if
     * the six-parameter signature were ordered differently from the record image, the rendered image
     * would differ from the seeded literal and the write assertions would fail. The entity's
     * no-argument constructor is {@code protected} for the persistence provider and is deliberately
     * not called from here, and the optimistic-locking counter is absent from this signature because
     * the record image has no representation for it.
     *
     * @return a card carrying exactly the seeded row's six mapped values
     */
    private static Card seededCard() {
        return new Card(
                SEEDED_CARD_NUM,
                SEEDED_ACCT_ID,
                SEEDED_CVV_CD,
                seededEmbossedName(),
                SEEDED_EXPIRAION_DATE,
                SEEDED_ACTIVE_STATUS);
    }

    @Nested
    @DisplayName("Declared geometry")
    class DeclaredGeometry {

        @Test
        @DisplayName("the record is exactly 150 bytes, and the seven declared widths sum to it: "
                + "16 + 11 + 3 + 50 + 10 + 1 + 59 = 150")
        void theSevenDeclaredWidthsSumToTheRecordWidth() {
            assertThat(CardRecordMapper.RECORD_LENGTH).isEqualTo(150);

            // The copybook's own arithmetic, written out term by term from the PIC clauses rather
            // than folded into a single total, so a wrong width identifies itself by term.
            assertThat(16 + 11 + 3 + 50 + 10 + 1 + 59).isEqualTo(150);

            assertThat(CARD_NUM_WIDTH
                    + ACCT_ID_WIDTH
                    + CVV_CD_WIDTH
                    + EMBOSSED_NAME_WIDTH
                    + EXPIRAION_DATE_WIDTH
                    + ACTIVE_STATUS_WIDTH
                    + FILLER_WIDTH)
                    .isEqualTo(RECORD_WIDTH);

            assertThat(CardRecordMapper.CARD_NUM_LENGTH
                    + CardRecordMapper.CARD_ACCT_ID_LENGTH
                    + CardRecordMapper.CARD_CVV_CD_LENGTH
                    + CardRecordMapper.CARD_EMBOSSED_NAME_LENGTH
                    + CardRecordMapper.CARD_EXPIRAION_DATE_LENGTH
                    + CardRecordMapper.CARD_ACTIVE_STATUS_LENGTH
                    + CardRecordMapper.FILLER_LENGTH)
                    .isEqualTo(CardRecordMapper.RECORD_LENGTH);
        }

        @Test
        @DisplayName("all six mapped fields start at the offsets the copybook declares: "
                + "0, 16, 27, 30, 80 and 90")
        void allSixOffsetsAreTheDeclaredLiterals() {
            assertThat(CardRecordMapper.CARD_NUM_OFFSET).isEqualTo(0);
            assertThat(CardRecordMapper.CARD_ACCT_ID_OFFSET).isEqualTo(16);
            assertThat(CardRecordMapper.CARD_CVV_CD_OFFSET).isEqualTo(27);
            assertThat(CardRecordMapper.CARD_EMBOSSED_NAME_OFFSET).isEqualTo(30);
            assertThat(CardRecordMapper.CARD_EXPIRAION_DATE_OFFSET).isEqualTo(80);
            assertThat(CardRecordMapper.CARD_ACTIVE_STATUS_OFFSET).isEqualTo(90);

            assertThat(CardRecordMapper.CARD_NUM_OFFSET).isEqualTo(CARD_NUM_OFFSET);
            assertThat(CardRecordMapper.CARD_ACCT_ID_OFFSET).isEqualTo(ACCT_ID_OFFSET);
            assertThat(CardRecordMapper.CARD_CVV_CD_OFFSET).isEqualTo(CVV_CD_OFFSET);
            assertThat(CardRecordMapper.CARD_EMBOSSED_NAME_OFFSET).isEqualTo(EMBOSSED_NAME_OFFSET);
            assertThat(CardRecordMapper.CARD_EXPIRAION_DATE_OFFSET).isEqualTo(EXPIRAION_DATE_OFFSET);
            assertThat(CardRecordMapper.CARD_ACTIVE_STATUS_OFFSET).isEqualTo(ACTIVE_STATUS_OFFSET);
        }

        @Test
        @DisplayName("all six mapped fields carry the widths their PIC clauses declare: "
                + "16, 11, 3, 50, 10 and 1")
        void allSixWidthsAreTheDeclaredLiterals() {
            assertThat(CardRecordMapper.CARD_NUM_LENGTH).isEqualTo(16);
            assertThat(CardRecordMapper.CARD_ACCT_ID_LENGTH).isEqualTo(11);
            assertThat(CardRecordMapper.CARD_CVV_CD_LENGTH).isEqualTo(3);
            assertThat(CardRecordMapper.CARD_EMBOSSED_NAME_LENGTH).isEqualTo(50);
            assertThat(CardRecordMapper.CARD_EXPIRAION_DATE_LENGTH).isEqualTo(10);
            assertThat(CardRecordMapper.CARD_ACTIVE_STATUS_LENGTH).isEqualTo(1);

            assertThat(CardRecordMapper.CARD_NUM_LENGTH).isEqualTo(CARD_NUM_WIDTH);
            assertThat(CardRecordMapper.CARD_ACCT_ID_LENGTH).isEqualTo(ACCT_ID_WIDTH);
            assertThat(CardRecordMapper.CARD_CVV_CD_LENGTH).isEqualTo(CVV_CD_WIDTH);
            assertThat(CardRecordMapper.CARD_EMBOSSED_NAME_LENGTH).isEqualTo(EMBOSSED_NAME_WIDTH);
            assertThat(CardRecordMapper.CARD_EXPIRAION_DATE_LENGTH).isEqualTo(EXPIRAION_DATE_WIDTH);
            assertThat(CardRecordMapper.CARD_ACTIVE_STATUS_LENGTH).isEqualTo(ACTIVE_STATUS_WIDTH);
        }

        @Test
        @DisplayName("the expiry date begins at offset 80 and is 10 bytes wide, and the copybook's "
                + "misspelling of its name is preserved as a layout position and never corrected")
        void theExpiryDateBeginsAtOffsetEighty() {
            // ANOMALY 1 OF THE SOURCE ANOMALY REGISTER. The copybook declares this field at its
            // physical line 9 as CARD-EXPIRAION-DATE - the T of EXPIRATION is genuinely absent. The
            // misspelling is load bearing in exactly one respect, the byte position, and that
            // position is 80. It is therefore preserved here and in the mapper's own constant name,
            // so the mapping stays findable by searching for the copybook's own spelling, while the
            // entity property is spelled correctly as cardExpirationDate. The layout is never
            // "fixed": moving this offset would shift the active-status byte and the filler run and
            // break byte compatibility with every record ever written.
            assertThat(CardRecordMapper.CARD_EXPIRAION_DATE_OFFSET).isEqualTo(80);
            assertThat(CardRecordMapper.CARD_EXPIRAION_DATE_LENGTH).isEqualTo(10);

            // Corroborated independently by the card-update program, which addresses this same field
            // by substring at positions 1 for 4, 6 for 2 and 9 for 2 within its ten bytes - so the
            // field is a hyphenated year-month-day external form and cannot be shorter than ten.
            assertThat(4 + 1 + 2 + 1 + 2).isEqualTo(10);
            assertThat(SEEDED_EXPIRAION_DATE.getBytes(StandardCharsets.US_ASCII))
                    .hasSize(EXPIRAION_DATE_WIDTH);

            // And the property really is spelled correctly on the entity: this call compiles only
            // because the accessor is getCardExpirationDate, with the T restored.
            assertThat(seededCard().getCardExpirationDate()).isEqualTo(SEEDED_EXPIRAION_DATE);
        }

        @Test
        @DisplayName("the key is the leading 16 bytes at offset 0, matching the cluster's "
                + "KEYS(16 0), and identity is that business key rather than a surrogate")
        void theKeyIsTheLeadingSixteenBytesAtOffsetZero() {
            // The base cluster is defined KEYS(16 0) RECORDSIZE(150 150): a sixteen-byte key at
            // offset zero, so the key is the leading substring of the record image itself.
            assertThat(CardRecordMapper.KEY_LENGTH).isEqualTo(16);
            assertThat(CardRecordMapper.KEY_LENGTH).isEqualTo(KEY_WIDTH);
            assertThat(CardRecordMapper.CARD_NUM_OFFSET).isZero();
            assertThat(CardRecordMapper.CARD_NUM_LENGTH).isEqualTo(CardRecordMapper.KEY_LENGTH);

            // The file section splits the same record into key and remainder: 16 + 134 = 150.
            assertThat(CardRecordMapper.DATA_LENGTH).isEqualTo(134);
            assertThat(CardRecordMapper.DATA_LENGTH).isEqualTo(DATA_WIDTH);
            assertThat(CardRecordMapper.KEY_LENGTH + CardRecordMapper.DATA_LENGTH)
                    .isEqualTo(CardRecordMapper.RECORD_LENGTH);

            // NO SURROGATE PRIMARY KEY. Identity is the card number the record itself carries, so a
            // card read from the image and a card carrying only that same number are the same
            // entity. A generated identifier would sever the record-image-to-row correspondence on
            // which byte-level output parity depends.
            Card fromImage = CardRecordMapper.fromRecord(seededImage());
            Card sameKeyOnly = new Card(
                    SEEDED_CARD_NUM,
                    "00000000099",
                    "001",
                    " ".repeat(EMBOSSED_NAME_WIDTH),
                    "1999-12-31",
                    "N");

            assertThat(fromImage).isEqualTo(sameKeyOnly);
            assertThat(fromImage).hasSameHashCodeAs(sameKeyOnly);
            assertThat(fromImage.getCardNum().getBytes(StandardCharsets.US_ASCII))
                    .hasSize(CardRecordMapper.KEY_LENGTH);
        }

        @Test
        @DisplayName("the account identifier occupies 11 bytes at offset 16, which the non-unique "
                + "alternate index KEYS(11 16) independently fixes")
        void theAlternateIndexCorroboratesTheAccountIdentifierPosition() {
            // The same job stream that defines the base cluster defines an alternate index over the
            // account identifier as KEYS(11 16) NONUNIQUEKEY UPGRADE. Its declared length and offset
            // are the second field's width and position, read from a wholly separate artefact. The
            // index itself becomes a derived repository finder plus a B-tree index; nothing about it
            // is behaviour here, which is why only its geometry is asserted.
            assertThat(CardRecordMapper.CARD_ACCT_ID_OFFSET).isEqualTo(16);
            assertThat(CardRecordMapper.CARD_ACCT_ID_LENGTH).isEqualTo(11);
            assertThat(CardRecordMapper.CARD_NUM_OFFSET + CardRecordMapper.CARD_NUM_LENGTH)
                    .isEqualTo(CardRecordMapper.CARD_ACCT_ID_OFFSET);
        }

        @Test
        @DisplayName("the six fields tile the leading 91 bytes without gap or overlap, and the "
                + "59-byte space filler accounts for the remaining bytes: 91 + 59 = 150")
        void theFieldsTileTheRecordWithoutGapOrOverlap() {
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

            assertThat(CardRecordMapper.MAPPED_PREFIX_LENGTH).isEqualTo(91);
            assertThat(CardRecordMapper.MAPPED_PREFIX_LENGTH).isEqualTo(MAPPED_PREFIX_WIDTH);
            assertThat(CardRecordMapper.FILLER_OFFSET).isEqualTo(91);
            assertThat(CardRecordMapper.FILLER_OFFSET).isEqualTo(FILLER_OFFSET);
            assertThat(CardRecordMapper.FILLER_LENGTH).isEqualTo(59);
            assertThat(CardRecordMapper.FILLER_LENGTH).isEqualTo(FILLER_WIDTH);
            assertThat(CardRecordMapper.MAPPED_PREFIX_LENGTH + CardRecordMapper.FILLER_LENGTH)
                    .isEqualTo(CardRecordMapper.RECORD_LENGTH);

            // The filler byte is a space for this master layout, and a space is 0x20. The copybook
            // supplies no initialising clause, so no byte is canonical by the copybook; a space is
            // what all 50 rows of the shipped fixture carry.
            assertThat(CardRecordMapper.FILLER_CHARACTER).isEqualTo(' ');
            assertThat((byte) CardRecordMapper.FILLER_CHARACTER).isEqualTo(ASCII_SPACE);
        }

        @Test
        @DisplayName("the hand-transcribed seeded image is itself exactly 150 encoded bytes, so the "
                + "oracle this suite is stated against is sound before it is relied upon")
        void theSeededOracleIsExactlyOneHundredAndFiftyEncodedBytes() {
            // Width is asserted on encoded bytes rather than on a character count, because a
            // character count is not a width authority for a fixed-width record.
            assertThat(seededImage().getBytes(StandardCharsets.US_ASCII)).hasSize(RECORD_WIDTH);
            assertThat(seededMappedPrefix().getBytes(StandardCharsets.US_ASCII))
                    .hasSize(MAPPED_PREFIX_WIDTH);
            assertThat(seededFillerRun().getBytes(StandardCharsets.US_ASCII)).hasSize(FILLER_WIDTH);
            assertThat(seededEmbossedName().getBytes(StandardCharsets.US_ASCII))
                    .hasSize(EMBOSSED_NAME_WIDTH);

            // The two independently assembled expectations must agree with one another as well as
            // with the mapper: the whole image is the mapped prefix followed by the filler run.
            assertThat(seededImage()).isEqualTo(seededMappedPrefix() + seededFillerRun());

            // And the fixture's own file arithmetic: 50 records of 150 bytes each plus a one-byte
            // terminator per record is a stride of 151 and a total of 7,550 bytes.
            assertThat(50 * (RECORD_WIDTH + 1)).isEqualTo(7550);
        }
    }

    @Nested
    @DisplayName("Reading the seeded record")
    class ReadingTheSeededRecord {

        @Test
        @DisplayName("all six fields of the seeded row reach their own property, so a transposition "
                + "of any two same-typed positions is caught")
        void allSixFieldsReachTheirOwnProperty() {
            Card card = CardRecordMapper.fromRecord(seededImage());

            assertThat(SensitiveValues.fingerprint(card.getCardNum())).isEqualTo(SensitiveValues.fingerprint("0500024453765740"));
            assertThat(card.getCardAcctId()).isEqualTo("00000000050");
            assertThat(SensitiveValues.fingerprint(card.getCardCvvCd())).isEqualTo(SensitiveValues.fingerprint("747"));
            assertThat(card.getCardEmbossedName()).isEqualTo(seededEmbossedName());
            assertThat(card.getCardExpirationDate()).isEqualTo("2023-03-09");
            assertThat(card.getCardActiveStatus()).isEqualTo("Y");
        }

        @Test
        @DisplayName("the card number keeps all sixteen digits including its significant leading "
                + "zero, because it is text and never passes through a numeric type")
        void theCardNumberKeepsItsLeadingZero() {
            Card card = CardRecordMapper.fromRecord(seededImage());

            assertThat(SensitiveValues.fingerprint(card.getCardNum())).isEqualTo(SensitiveValues.fingerprint(SEEDED_CARD_NUM));
            assertThat(card.getCardNum().startsWith("0")).isTrue();
            assertThat(card.getCardNum().getBytes(StandardCharsets.US_ASCII))
                    .hasSize(CARD_NUM_WIDTH);
        }

        @Test
        @DisplayName("the account identifier keeps its nine leading zeros as 00000000050, which is "
                + "the same value row 0 of the cross-reference fixture carries")
        void theAccountIdentifierKeepsItsLeadingZeros() {
            Card card = CardRecordMapper.fromRecord(seededImage());

            assertThat(card.getCardAcctId()).isEqualTo("00000000050").startsWith("000000000");
            assertThat(card.getCardAcctId().getBytes(StandardCharsets.US_ASCII))
                    .hasSize(ACCT_ID_WIDTH);

            // Not numerically normalised: 00000000050 and 50 are different values of this field, and
            // only the eleven-character form fits the column the record declares.
            assertThat(card.getCardAcctId()).isNotEqualTo("50");
        }

        @Test
        @DisplayName("the embossed name arrives at its full 50 bytes with its 41 trailing spaces and "
                + "its embedded space intact, and is not equal to its trimmed form")
        void theEmbossedNameKeepsAllFiftyBytes() {
            Card card = CardRecordMapper.fromRecord(seededImage());

            assertThat(card.getCardEmbossedName().getBytes(StandardCharsets.US_ASCII))
                    .hasSize(EMBOSSED_NAME_WIDTH);
            assertThat(card.getCardEmbossedName()).isEqualTo(seededEmbossedName());

            // The padding is contractual data, so the mapped value is NOT the trimmed value. The
            // trimmed form is written out as a hand-held literal rather than computed by trimming
            // the actual, because nothing in this module may trim or strip a fixed-width slice.
            assertThat(card.getCardEmbossedName()).isNotEqualTo(SEEDED_NAME_TEXT);
            assertThat(card.getCardEmbossedName()).isNotEqualTo("Aniya Von");
            assertThat(card.getCardEmbossedName()).startsWith("Aniya Von").endsWith(" ");

            // The embedded space at index 5 is a VALID character of this field under the legacy
            // blank-and-trim alphabetic idiom, which blanks every letter and then measures what is
            // left: a name with an embedded space passes. Nothing here implies otherwise, and no
            // validation of this value happens in a mapper - that belongs to the card-update
            // service. The case is also untouched: the legacy upper-case fold is a strict
            // 26-character ASCII table applied elsewhere, never a library conversion, and the
            // platform's locale-sensitive upper-casing conversion is forbidden throughout this
            // module, with or without an explicit locale.
            assertThat(card.getCardEmbossedName().charAt(5)).isEqualTo(' ');
            assertThat(card.getCardEmbossedName()).contains("Aniya Von");
            assertThat(card.getCardEmbossedName()).isNotEqualTo("ANIYA VON".concat(" ".repeat(41)));
        }

        @Test
        @DisplayName("the expiry arrives as ten characters of text in the hyphenated external form, "
                + "not as a parsed temporal value")
        void theExpiryArrivesAsTenCharactersOfText() {
            Card card = CardRecordMapper.fromRecord(seededImage());

            assertThat(card.getCardExpirationDate()).isEqualTo("2023-03-09");
            assertThat(card.getCardExpirationDate().getBytes(StandardCharsets.US_ASCII))
                    .hasSize(EXPIRAION_DATE_WIDTH);
            assertThat(card.getCardExpirationDate().charAt(4)).isEqualTo('-');
            assertThat(card.getCardExpirationDate().charAt(7)).isEqualTo('-');
        }

        @Test
        @DisplayName("the active status arrives as a single raw character, translated by nothing and "
                + "rejected by nothing")
        void theActiveStatusArrivesAsASingleRawCharacter() {
            Card card = CardRecordMapper.fromRecord(seededImage());

            assertThat(card.getCardActiveStatus()).isEqualTo("Y");
            assertThat(card.getCardActiveStatus().getBytes(StandardCharsets.US_ASCII))
                    .hasSize(ACTIVE_STATUS_WIDTH);

            // A code the mapper has never seen is data, not an error: the status vocabulary belongs
            // to the screen and service layers, and a record the legacy system accepted maps here
            // without complaint. No enumeration is consulted and none is referenced by this suite.
            Card unfamiliar = CardRecordMapper.fromRecord(seededImageWithActiveStatus("Q"));

            assertThat(unfamiliar.getCardActiveStatus()).isEqualTo("Q");
            assertThat(SensitiveValues.fingerprint(unfamiliar.getCardNum())).isEqualTo(SensitiveValues.fingerprint(SEEDED_CARD_NUM));
        }

        @Test
        @DisplayName("the optimistic-locking counter is left at its default of zero, because the "
                + "record image has no representation for it")
        void theOptimisticLockingCounterIsUntouched() {
            Card card = CardRecordMapper.fromRecord(seededImage());

            // The counter is the persistence provider's to own. The mapper never reads it and never
            // sets it, and it is deliberately absent from the entity's all-argument constructor, so
            // a freshly mapped card carries the field's default value.
            assertThat(card.getVersion()).isZero();
            assertThat(seededCard().getVersion()).isZero();
            assertThat(CardRecordMapper.fromRecord(
                    seededImage().getBytes(StandardCharsets.US_ASCII)).getVersion()).isZero();
        }
    }

    @Nested
    @DisplayName("Leading zeros on the three-byte verification code")
    class VerificationCodeLeadingZeros {

        @Test
        @DisplayName("a verification code of 7 is read as the three characters 007 and is never the "
                + "one-character value 7")
        void aCodeOfSevenIsReadAsThreeCharacters() {
            Card card = CardRecordMapper.fromRecord(seededImageWithVerificationCode("007"));

            assertThat(SensitiveValues.fingerprint(card.getCardCvvCd())).isEqualTo(SensitiveValues.fingerprint("007"));
            assertThat(card.getCardCvvCd().getBytes(StandardCharsets.US_ASCII))
                    .hasSize(CVV_CD_WIDTH);

            // NOT NUMERICALLY NORMALISED. The value is never parsed to an int and re-rendered:
            // doing so would narrow a three-byte field to one byte and shift every byte after it.
            assertThat(SensitiveValues.fingerprint(card.getCardCvvCd())).isNotEqualTo(SensitiveValues.fingerprint("7"));
            assertThat(SensitiveValues.fingerprint(card.getCardCvvCd())).isNotEqualTo(SensitiveValues.fingerprint("07"));
            assertThat(card.getCardCvvCd().startsWith("00")).isTrue();
            assertThat(card.getCardCvvCd().endsWith("7")).isTrue();
        }

        @Test
        @DisplayName("a verification code of 47 is read as the three characters 047 and is never the "
                + "two-character value 47")
        void aCodeOfFortySevenIsReadAsThreeCharacters() {
            Card card = CardRecordMapper.fromRecord(seededImageWithVerificationCode("047"));

            assertThat(SensitiveValues.fingerprint(card.getCardCvvCd())).isEqualTo(SensitiveValues.fingerprint("047"));
            assertThat(card.getCardCvvCd().getBytes(StandardCharsets.US_ASCII))
                    .hasSize(CVV_CD_WIDTH);
            assertThat(SensitiveValues.fingerprint(card.getCardCvvCd())).isNotEqualTo(SensitiveValues.fingerprint("47"));
            assertThat(card.getCardCvvCd().startsWith("0")).isTrue();
        }

        @Test
        @DisplayName("writing a bare 7 places 007, and writing a bare 47 places 047: the field is "
                + "right-justified and zero-filled on the way out, matching PIC 9(03)")
        void writingABareCodeRightJustifiesAndZeroFills() {
            String fromSeven = CardRecordMapper.toRecord(new Card(
                    SEEDED_CARD_NUM,
                    SEEDED_ACCT_ID,
                    "7",
                    seededEmbossedName(),
                    SEEDED_EXPIRAION_DATE,
                    SEEDED_ACTIVE_STATUS));
            String fromFortySeven = CardRecordMapper.toRecord(new Card(
                    SEEDED_CARD_NUM,
                    SEEDED_ACCT_ID,
                    "47",
                    seededEmbossedName(),
                    SEEDED_EXPIRAION_DATE,
                    SEEDED_ACTIVE_STATUS));

            byte[] sevenSlice = Arrays.copyOfRange(
                    fromSeven.getBytes(StandardCharsets.US_ASCII),
                    CVV_CD_OFFSET,
                    CVV_CD_OFFSET + CVV_CD_WIDTH);
            byte[] fortySevenSlice = Arrays.copyOfRange(
                    fromFortySeven.getBytes(StandardCharsets.US_ASCII),
                    CVV_CD_OFFSET,
                    CVV_CD_OFFSET + CVV_CD_WIDTH);

            assertThat(sevenSlice).isEqualTo("007".getBytes(StandardCharsets.US_ASCII));
            assertThat(fortySevenSlice).isEqualTo("047".getBytes(StandardCharsets.US_ASCII));

            // The whole image stays exactly 150 bytes, so the zero-fill was placed inside the field
            // rather than by shifting the bytes that follow it.
            assertThat(fromSeven.getBytes(StandardCharsets.US_ASCII)).hasSize(RECORD_WIDTH);
            assertThat(fromFortySeven.getBytes(StandardCharsets.US_ASCII)).hasSize(RECORD_WIDTH);
        }

        @Test
        @DisplayName("007 and 047 both survive a read followed by a write with their leading zeros "
                + "still in place")
        void zeroPaddedCodesSurviveARoundTrip() {
            String withSeven = seededImageWithVerificationCode("007");
            String withFortySeven = seededImageWithVerificationCode("047");

            assertThat(CardRecordMapper.toRecord(CardRecordMapper.fromRecord(withSeven)))
                    .isEqualTo(withSeven);
            assertThat(CardRecordMapper.toRecord(CardRecordMapper.fromRecord(withFortySeven)))
                    .isEqualTo(withFortySeven);

            // Stated again directly on the field rather than only through the image, so no value in
            // this suite is proved by a round trip alone.
            assertThat(SensitiveValues.fingerprint(CardRecordMapper.fromRecord(withSeven).getCardCvvCd())).isEqualTo(SensitiveValues.fingerprint("007"));
            assertThat(SensitiveValues.fingerprint(CardRecordMapper.fromRecord(withFortySeven).getCardCvvCd())).isEqualTo(SensitiveValues.fingerprint("047"));
        }
    }

    @Nested
    @DisplayName("No decimal field exists on this record")
    class NoDecimalFieldExists {

        @Test
        @DisplayName("all six mapped properties are character values, so there is no BigDecimal, no "
                + "numeric wrapper and no rounding decision anywhere on this record")
        void allSixMappedPropertiesAreCharacterValues() {
            Card card = CardRecordMapper.fromRecord(seededImage());

            // COMPILE-TIME PROOF, which is stronger than any runtime check and needs no reflection:
            // each of these six assignments compiles only because the accessor's declared return
            // type is String. String is final, so the static type is exact. This is the only mapper
            // in the family of eleven with no money field at all: every field here is either
            // PIC X(n) character data or an unsigned PIC 9(n) numeric field carried as text, and no
            // overpunched sign, amount or rate appears in the layout.
            String cardNum = card.getCardNum();
            String acctId = card.getCardAcctId();
            String verificationCode = card.getCardCvvCd();
            String embossedName = card.getCardEmbossedName();
            String expiry = card.getCardExpirationDate();
            String activeStatus = card.getCardActiveStatus();

            List<Object> mapped = List.of(
                    cardNum, acctId, verificationCode, embossedName, expiry, activeStatus);

            assertThat(mapped).hasSize(6).allMatch(value -> value instanceof String);
            assertThat(mapped).doesNotContainNull();
        }

        @Test
        @DisplayName("width alone cannot tell a money field from an identifier, which is exactly why "
                + "this record routes through no decimal codec at all")
        void widthAloneCannotIdentifyAMoneyField() {
            // A genuine and deliberate coincidence: the eleven-byte account identifier is exactly as
            // wide as a zoned PIC S9(09)V99 money image, because 9 + 2 digits is also eleven bytes.
            // The two are nevertheless unrelated - PIC 9(11) is unsigned display text while the
            // zoned form carries an overpunched sign in its trailing byte - so the discriminator is
            // the picture clause, never the width. Naming the codec's widths here documents that
            // trap; no conversion is performed, no scale is set and no rounding mode is chosen
            // anywhere in this suite.
            List<Integer> zonedMoneyWidths = List.of(
                    ZonedDecimalCodec.WIDTH_PIC_S9_10_V99,
                    ZonedDecimalCodec.WIDTH_PIC_S9_09_V99,
                    ZonedDecimalCodec.WIDTH_PIC_S9_04_V99);

            // Hand-derived from the picture clauses of the three money and rate fields that exist
            // elsewhere in the estate: 10 + 2 = 12, 9 + 2 = 11 and 4 + 2 = 6 digits, and a zoned
            // image is one byte per digit with the sign folded into the trailing byte.
            assertThat(zonedMoneyWidths).containsExactly(12, 11, 6);

            // The coincidence, stated rather than hidden.
            assertThat(zonedMoneyWidths).contains(ACCT_ID_WIDTH);

            // And confined to that one field: no other width on this record collides.
            assertThat(zonedMoneyWidths).doesNotContain(
                    CARD_NUM_WIDTH,
                    CVV_CD_WIDTH,
                    EMBOSSED_NAME_WIDTH,
                    EXPIRAION_DATE_WIDTH,
                    ACTIVE_STATUS_WIDTH);
        }
    }

    @Nested
    @DisplayName("Writing a record")
    class WritingARecord {

        @Test
        @DisplayName("the seeded row survives a read followed by a write, all 150 bytes byte for "
                + "byte, filler run included")
        void theSeededRowSurvivesAReadFollowedByAWrite() {
            String rendered = CardRecordMapper.toRecord(CardRecordMapper.fromRecord(seededImage()));

            // Compared as encoded bytes, not as characters, because the record is a byte contract.
            assertThat(rendered.getBytes(StandardCharsets.US_ASCII))
                    .isEqualTo(seededImage().getBytes(StandardCharsets.US_ASCII));
            assertThat(rendered.getBytes(StandardCharsets.US_ASCII)).hasSize(RECORD_WIDTH);
            assertThat(rendered).isEqualTo(seededImage());
        }

        @Test
        @DisplayName("the mapped prefix, byte range 0 up to but excluding 91, is reproduced byte for "
                + "byte under US-ASCII")
        void theMappedPrefixIsReproducedByteForByte() {
            String rendered = CardRecordMapper.toRecord(seededCard());

            byte[] renderedPrefix = Arrays.copyOfRange(
                    rendered.getBytes(StandardCharsets.US_ASCII),
                    0,
                    MAPPED_PREFIX_WIDTH);

            // The expectation is assembled from the transcribed field literals, never sliced out of
            // the rendered image, so the two are genuinely independent statements.
            assertThat(renderedPrefix)
                    .isEqualTo(seededMappedPrefix().getBytes(StandardCharsets.US_ASCII));
            assertThat(renderedPrefix).hasSize(91);
            assertThat(renderedPrefix).hasSize(CardRecordMapper.MAPPED_PREFIX_LENGTH);
        }

        @Test
        @DisplayName("the written image is exactly 150 encoded bytes and bytes 91 through 149 are "
                + "all 0x20, so the 59-byte filler run is space filled")
        void theWrittenFillerRunIsFiftyNineSpaceBytes() {
            byte[] rendered = CardRecordMapper.toRecord(seededCard())
                    .getBytes(StandardCharsets.US_ASCII);

            assertThat(rendered).hasSize(150);
            assertThat(rendered).hasSize(RECORD_WIDTH);

            byte[] filler = Arrays.copyOfRange(rendered, FILLER_OFFSET, RECORD_WIDTH);

            assertThat(filler).hasSize(FILLER_WIDTH);
            assertThat(filler).hasSize(59);
            assertThat(filler).containsOnly(ASCII_SPACE);
            assertThat(filler).isEqualTo(seededFillerRun().getBytes(StandardCharsets.US_ASCII));

            // Stated byte by byte across the whole run as well, so a single stray byte anywhere
            // inside it cannot hide behind an aggregate assertion.
            for (int index = FILLER_OFFSET; index < RECORD_WIDTH; index++) {
                assertThat(rendered[index])
                        .as("byte at offset %d must be an ASCII space", index)
                        .isEqualTo(ASCII_SPACE);
            }
        }

        @Test
        @DisplayName("an entity built through the public six-argument constructor renders the seeded "
                + "image, which proves the constructor's parameter order is the copybook's order")
        void anEntityBuiltThroughTheAllArgsConstructorRendersTheSeededImage() {
            // Built with the six values in copybook order and nothing else: no setter is called, the
            // protected no-argument constructor is never reached, and the version counter is absent
            // from the signature. If the six parameters were ordered differently from the record
            // image, the rendered bytes would differ from the transcribed literal below.
            Card handBuilt = new Card(
                    SEEDED_CARD_NUM,
                    SEEDED_ACCT_ID,
                    SEEDED_CVV_CD,
                    seededEmbossedName(),
                    SEEDED_EXPIRAION_DATE,
                    SEEDED_ACTIVE_STATUS);

            assertThat(CardRecordMapper.toRecord(handBuilt).getBytes(StandardCharsets.US_ASCII))
                    .isEqualTo(seededImage().getBytes(StandardCharsets.US_ASCII));

            // Each field also lands in its own window, so a transposition of two same-width
            // arguments could not pass by accident.
            byte[] rendered = CardRecordMapper.toRecord(handBuilt)
                    .getBytes(StandardCharsets.US_ASCII);

            assertThat(Arrays.copyOfRange(rendered, CARD_NUM_OFFSET,
                    CARD_NUM_OFFSET + CARD_NUM_WIDTH))
                    .isEqualTo(SEEDED_CARD_NUM.getBytes(StandardCharsets.US_ASCII));
            assertThat(Arrays.copyOfRange(rendered, ACCT_ID_OFFSET,
                    ACCT_ID_OFFSET + ACCT_ID_WIDTH))
                    .isEqualTo(SEEDED_ACCT_ID.getBytes(StandardCharsets.US_ASCII));
            assertThat(Arrays.copyOfRange(rendered, CVV_CD_OFFSET, CVV_CD_OFFSET + CVV_CD_WIDTH))
                    .isEqualTo(SEEDED_CVV_CD.getBytes(StandardCharsets.US_ASCII));
            assertThat(Arrays.copyOfRange(rendered, EMBOSSED_NAME_OFFSET,
                    EMBOSSED_NAME_OFFSET + EMBOSSED_NAME_WIDTH))
                    .isEqualTo(seededEmbossedName().getBytes(StandardCharsets.US_ASCII));
            assertThat(Arrays.copyOfRange(rendered, EXPIRAION_DATE_OFFSET,
                    EXPIRAION_DATE_OFFSET + EXPIRAION_DATE_WIDTH))
                    .isEqualTo(SEEDED_EXPIRAION_DATE.getBytes(StandardCharsets.US_ASCII));
            assertThat(Arrays.copyOfRange(rendered, ACTIVE_STATUS_OFFSET,
                    ACTIVE_STATUS_OFFSET + ACTIVE_STATUS_WIDTH))
                    .isEqualTo(SEEDED_ACTIVE_STATUS.getBytes(StandardCharsets.US_ASCII));
        }

        @Test
        @DisplayName("the byte-array write produces the same 150 bytes as the string write, in a "
                + "fresh array that carries no line terminator")
        void theByteArrayWriteProducesTheSameBytes() {
            Card card = seededCard();

            byte[] renderedBytes = CardRecordMapper.toRecordBytes(card);

            assertThat(renderedBytes).hasSize(RECORD_WIDTH);
            assertThat(renderedBytes).isEqualTo(seededImage().getBytes(StandardCharsets.US_ASCII));
            assertThat(renderedBytes)
                    .isEqualTo(CardRecordMapper.toRecord(card).getBytes(StandardCharsets.US_ASCII));

            // No record separator: separation belongs to the writer, so the final byte is the last
            // byte of the filler run and not a terminator.
            assertThat(renderedBytes[RECORD_WIDTH - 1]).isEqualTo(ASCII_SPACE);

            // The array is fresh and unshared, so mutating one does not disturb the next.
            byte[] second = CardRecordMapper.toRecordBytes(card);

            assertThat(second).isNotSameAs(renderedBytes).isEqualTo(renderedBytes);
        }

        @Test
        @DisplayName("a short character value is left-justified and space-padded to its full field "
                + "width, matching PIC X, and nothing after it moves")
        void aShortCharacterValueIsSpacePaddedToItsFullWidth() {
            Card card = new Card(
                    SEEDED_CARD_NUM,
                    SEEDED_ACCT_ID,
                    SEEDED_CVV_CD,
                    "A",
                    SEEDED_EXPIRAION_DATE,
                    SEEDED_ACTIVE_STATUS);

            byte[] rendered = CardRecordMapper.toRecordBytes(card);

            assertThat(rendered).hasSize(RECORD_WIDTH);
            assertThat(Arrays.copyOfRange(rendered, EMBOSSED_NAME_OFFSET,
                    EMBOSSED_NAME_OFFSET + EMBOSSED_NAME_WIDTH))
                    .isEqualTo(("A" + " ".repeat(49)).getBytes(StandardCharsets.US_ASCII));

            // The expiry still begins at 80, so the pad went inside the name's own field.
            assertThat(Arrays.copyOfRange(rendered, EXPIRAION_DATE_OFFSET,
                    EXPIRAION_DATE_OFFSET + EXPIRAION_DATE_WIDTH))
                    .isEqualTo(SEEDED_EXPIRAION_DATE.getBytes(StandardCharsets.US_ASCII));
        }
    }

    @Nested
    @DisplayName("Read-overload agreement")
    class ReadOverloadAgreement {

        @Test
        @DisplayName("the string, byte-array and byte-range overloads produce equal entities, field "
                + "for field, with the version counter untouched in all three")
        void theThreeOverloadsProduceEqualEntities() {
            String image = seededImage();
            byte[] imageBytes = image.getBytes(StandardCharsets.US_ASCII);

            Card fromText = CardRecordMapper.fromRecord(image);
            Card fromBytes = CardRecordMapper.fromRecord(imageBytes);
            Card fromRange = CardRecordMapper.fromRecord(imageBytes, 0);

            // Entity equality is over the card number alone, so field-for-field comparison is the
            // assertion that actually proves the three agree.
            assertThat(fromText).isEqualTo(fromBytes).isEqualTo(fromRange);

            assertThat(SensitiveValues.fingerprint(fromBytes.getCardNum())).isEqualTo(SensitiveValues.fingerprint(SEEDED_CARD_NUM));
            assertThat(SensitiveValues.fingerprint(fromRange.getCardNum())).isEqualTo(SensitiveValues.fingerprint(SEEDED_CARD_NUM));
            assertThat(fromBytes.getCardAcctId()).isEqualTo(SEEDED_ACCT_ID);
            assertThat(fromRange.getCardAcctId()).isEqualTo(SEEDED_ACCT_ID);
            assertThat(SensitiveValues.fingerprint(fromBytes.getCardCvvCd())).isEqualTo(SensitiveValues.fingerprint(SEEDED_CVV_CD));
            assertThat(SensitiveValues.fingerprint(fromRange.getCardCvvCd())).isEqualTo(SensitiveValues.fingerprint(SEEDED_CVV_CD));
            assertThat(fromBytes.getCardEmbossedName()).isEqualTo(seededEmbossedName());
            assertThat(fromRange.getCardEmbossedName()).isEqualTo(seededEmbossedName());
            assertThat(fromBytes.getCardExpirationDate()).isEqualTo(SEEDED_EXPIRAION_DATE);
            assertThat(fromRange.getCardExpirationDate()).isEqualTo(SEEDED_EXPIRAION_DATE);
            assertThat(fromBytes.getCardActiveStatus()).isEqualTo(SEEDED_ACTIVE_STATUS);
            assertThat(fromRange.getCardActiveStatus()).isEqualTo(SEEDED_ACTIVE_STATUS);

            assertThat(fromText.getVersion()).isZero();
            assertThat(fromBytes.getVersion()).isZero();
            assertThat(fromRange.getVersion()).isZero();

            // And all three render the same bytes back out.
            assertThat(CardRecordMapper.toRecordBytes(fromText))
                    .isEqualTo(CardRecordMapper.toRecordBytes(fromBytes))
                    .isEqualTo(CardRecordMapper.toRecordBytes(fromRange))
                    .isEqualTo(imageBytes);
        }

        @Test
        @DisplayName("the byte-range overload selects the second record of a terminated buffer at a "
                + "stride of 151, leaving the 0x0A terminator behind")
        void theByteRangeOverloadSelectsARecordAtItsStride() {
            // A newline-terminated fixed-width file has a stride of 151 for this layout, so record i
            // starts at i * 151. The buffer below is two such records, the first deliberately
            // distinct from the seeded row so a wrong stride cannot pass.
            String firstRecord = seededImageWithActiveStatus("N");
            byte[] buffer = (firstRecord + "\n" + seededImage() + "\n")
                    .getBytes(StandardCharsets.US_ASCII);
            int stride = RECORD_WIDTH + 1;

            assertThat(buffer).hasSize(2 * stride);
            assertThat(stride).isEqualTo(151);

            Card first = CardRecordMapper.fromRecord(buffer, 0);
            Card second = CardRecordMapper.fromRecord(buffer, stride);

            assertThat(first.getCardActiveStatus()).isEqualTo("N");
            assertThat(second.getCardActiveStatus()).isEqualTo("Y");
            assertThat(SensitiveValues.fingerprint(second.getCardNum())).isEqualTo(SensitiveValues.fingerprint(SEEDED_CARD_NUM));
            assertThat(second.getCardAcctId()).isEqualTo(SEEDED_ACCT_ID);
            assertThat(SensitiveValues.fingerprint(second.getCardCvvCd())).isEqualTo(SensitiveValues.fingerprint(SEEDED_CVV_CD));
            assertThat(second.getCardEmbossedName()).isEqualTo(seededEmbossedName());
            assertThat(second.getCardExpirationDate()).isEqualTo(SEEDED_EXPIRAION_DATE);

            // The terminator is genuinely left behind: the selected range never includes it.
            assertThat(CardRecordMapper.toRecordBytes(second))
                    .isEqualTo(seededImage().getBytes(StandardCharsets.US_ASCII));
        }
    }

    @Nested
    @DisplayName("Rejected input")
    class RejectedInput {

        @Test
        @DisplayName("an image of 151 encoded bytes is rejected, and the message names the layout, "
                + "the expected width of 150, the actual 151 and the unstripped line terminator")
        void anImageOfOneHundredAndFiftyOneBytesIsRejected() {
            // The commonest real defect: a caller that read a line from the terminated fixture and
            // did not strip the 0x0A. The record is never truncated to fit.
            String withTerminator = seededImage() + "\n";

            assertThat(withTerminator.getBytes(StandardCharsets.US_ASCII))
                    .hasSize(RECORD_WIDTH + 1);

            // A plain IllegalArgumentException, deliberately not any exception type belonging to
            // this module: a caller supplying the wrong number of bytes has no legacy antecedent,
            // because indexed and sequential records are fixed length by construction.
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> CardRecordMapper.fromRecord(withTerminator))
                    .withMessageContaining("CARD-RECORD")
                    .withMessageContaining("CVACT02Y")
                    .withMessageContaining("exactly 150 encoded bytes")
                    .withMessageContaining("151 encoded bytes")
                    .withMessageContaining("never padded or truncated")
                    .withMessageContaining("terminator");
        }

        @Test
        @DisplayName("an image of 149 encoded bytes is rejected rather than padded to fit, and the "
                + "message names the layout, the expected 150 and the actual 149")
        void anImageOfOneHundredAndFortyNineBytesIsRejected() {
            // One byte short: the trailing filler byte is missing. Assembled by shortening the
            // filler run by one rather than by truncating the whole image, so the deficit sits where
            // a real short read would put it.
            String tooShort = seededMappedPrefix() + " ".repeat(FILLER_WIDTH - 1);

            assertThat(tooShort.getBytes(StandardCharsets.US_ASCII)).hasSize(RECORD_WIDTH - 1);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> CardRecordMapper.fromRecord(tooShort))
                    .withMessageContaining("CARD-RECORD")
                    .withMessageContaining("exactly 150 encoded bytes")
                    .withMessageContaining("149 encoded bytes")
                    .withMessageContaining("never padded or truncated");

            // And the same deficit is refused through the byte-array overload, so neither entry
            // point admits a mis-sized record.
            byte[] tooShortBytes = tooShort.getBytes(StandardCharsets.US_ASCII);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> CardRecordMapper.fromRecord(tooShortBytes))
                    .withMessageContaining("149 encoded bytes");
        }

        @Test
        @DisplayName("the byte-array overload refuses an over-long image too, so the width contract "
                + "is not a property of the string entry point alone")
        void theByteArrayOverloadRefusesAnOverLongImage() {
            byte[] tooLong = (seededImage() + "\n").getBytes(StandardCharsets.US_ASCII);

            assertThat(tooLong).hasSize(151);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> CardRecordMapper.fromRecord(tooLong))
                    .withMessageContaining("exactly 150 encoded bytes")
                    .withMessageContaining("151 encoded bytes");
        }

        @Test
        @DisplayName("the byte-range overload refuses a range that does not lie wholly inside its "
                + "buffer, naming the start index, the record width and the buffer length")
        void theByteRangeOverloadRefusesARangeOutsideItsBuffer() {
            byte[] shortBuffer = seededMappedPrefix().getBytes(StandardCharsets.US_ASCII);

            assertThat(shortBuffer).hasSize(MAPPED_PREFIX_WIDTH);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> CardRecordMapper.fromRecord(shortBuffer, 0))
                    .withMessageContaining("does not fit inside the supplied buffer")
                    .withMessageContaining("recordWidth=150")
                    .withMessageContaining("buffer length=91");

            byte[] wholeRecord = seededImage().getBytes(StandardCharsets.US_ASCII);

            // A negative start index is refused by name.
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> CardRecordMapper.fromRecord(wholeRecord, -1))
                    .withMessageContaining("must not be negative");

            // And a start index that leaves fewer than 150 bytes ahead of it, even though the buffer
            // is itself exactly one record long.
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> CardRecordMapper.fromRecord(wholeRecord, 1))
                    .withMessageContaining("does not fit inside the supplied buffer");
        }

        @Test
        @DisplayName("null is refused at every entry point, on both the reading and the writing side")
        void nullIsRefusedAtEveryEntryPoint() {
            // The casts are required rather than stylistic: a bare null literal is ambiguous between
            // the string and byte-array read overloads.
            assertThatNullPointerException()
                    .isThrownBy(() -> CardRecordMapper.fromRecord((String) null));
            assertThatNullPointerException()
                    .isThrownBy(() -> CardRecordMapper.fromRecord((byte[]) null));
            assertThatNullPointerException()
                    .isThrownBy(() -> CardRecordMapper.fromRecord(null, 0));
            assertThatNullPointerException()
                    .isThrownBy(() -> CardRecordMapper.toRecord(null));
            assertThatNullPointerException()
                    .isThrownBy(() -> CardRecordMapper.toRecordBytes(null));
        }

        @Test
        @DisplayName("a null mapped property is refused when writing, naming both the legacy field "
                + "and the entity property, because a fixed-width record cannot represent absence")
        void aNullMappedPropertyIsRefusedWhenWriting() {
            // An unset character field is presented as spaces and an unset numeric field as zeros;
            // there is no null in a record image, so a null property is a caller defect.
            Card missingName = new Card(
                    SEEDED_CARD_NUM,
                    SEEDED_ACCT_ID,
                    SEEDED_CVV_CD,
                    null,
                    SEEDED_EXPIRAION_DATE,
                    SEEDED_ACTIVE_STATUS);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> CardRecordMapper.toRecord(missingName))
                    .withMessageContaining("CARD-EMBOSSED-NAME")
                    .withMessageContaining("Card.cardEmbossedName")
                    .withMessageContaining("must not be null");

            Card missingExpiry = new Card(
                    SEEDED_CARD_NUM,
                    SEEDED_ACCT_ID,
                    SEEDED_CVV_CD,
                    seededEmbossedName(),
                    null,
                    SEEDED_ACTIVE_STATUS);

            // The diagnostic reproduces the copybook's own misspelling, so a reader can find the
            // field by the name the copybook uses, while the entity property stays spelled
            // correctly. Anomaly 1 again, seen from the diagnostic side.
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> CardRecordMapper.toRecordBytes(missingExpiry))
                    .withMessageContaining("CARD-EXPIRAION-DATE")
                    .withMessageContaining("Card.cardExpirationDate");
        }

        @Test
        @DisplayName("a value wider than its own field is refused rather than truncated, so the "
                + "record can never be reshaped to accommodate bad data")
        void aValueWiderThanItsFieldIsRefused() {
            Card overWideName = new Card(
                    SEEDED_CARD_NUM,
                    SEEDED_ACCT_ID,
                    SEEDED_CVV_CD,
                    "A".repeat(EMBOSSED_NAME_WIDTH + 1),
                    SEEDED_EXPIRAION_DATE,
                    SEEDED_ACTIVE_STATUS);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> CardRecordMapper.toRecord(overWideName))
                    .withMessageContaining("CARD-EMBOSSED-NAME");

            Card overWideCode = new Card(
                    SEEDED_CARD_NUM,
                    SEEDED_ACCT_ID,
                    "0747",
                    seededEmbossedName(),
                    SEEDED_EXPIRAION_DATE,
                    SEEDED_ACTIVE_STATUS);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> CardRecordMapper.toRecordBytes(overWideCode))
                    .withMessageContaining("CARD-CVV-CD");
        }
    }
}
