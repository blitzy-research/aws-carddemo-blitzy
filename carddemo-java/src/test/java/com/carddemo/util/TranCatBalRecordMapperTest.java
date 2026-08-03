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

import com.carddemo.domain.TransactionCategoryBalance;
import com.carddemo.domain.id.TransactionCategoryBalanceId;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Unit test for {@link TranCatBalRecordMapper}, the two-way mapping between the 50-byte transaction
 * category balance record image and {@link TransactionCategoryBalance}.
 *
 * <p><strong>What this layout is and why it matters.</strong> It is the per-account, per-type,
 * per-category running balance the interest run reads: the interest calculation multiplies this balance
 * by the disclosure group's rate before dividing, so a balance read one byte off, at the wrong scale or
 * with the wrong sign produces a wrong interest transaction and a wrong account balance downstream.
 * There are fifty seeded rows and the byte-equivalence gate depends on all fifty decoding exactly.
 *
 * <p><strong>The composite key is seventeen bytes, and the width is a trap.</strong> The key is the
 * leading substring of the record - an eleven-byte account identifier, a two-byte type code and a
 * four-byte category code - and the cluster definition makes exactly those seventeen bytes the stored
 * key. A group of the <em>same name</em> in the transaction-category copybook is only six bytes wide,
 * holds only the last two of these three components and places them at different offsets, and the
 * seventeen-byte key is <strong>not</strong> a prefix of it nor it of this. Every key assertion below
 * therefore states the width as the literal seventeen and names all three components, so the two can
 * never be conflated.
 *
 * <p><strong>Every expectation here is hand-derived and independent of the code under test.</strong>
 * Record images are declared as literal field values and reassembled by this class's own two padding
 * helpers, which reimplement the justification a {@code PIC X(n)} and a {@code PIC 9(n)} field receive.
 * The geometry block asserts the mapper's published constants against the integer widths the copybook
 * declares, never against other constants of the mapper, so a uniformly shifted layout - the one error
 * a self-consistent constant set cannot detect - fails here. No expected value is produced by calling
 * the code under test.
 *
 * <p><strong>A second, wholly independent oracle: the shipped reference fixture.</strong> The seeded
 * category-balance dataset is read directly from the classpath and its first rows are decoded and
 * compared field by field. That fixture also settles a filler question that a round trip alone would
 * get wrong: COBOL filler carries no initialising clause, so its bytes are undefined, and the shipped
 * file uses ASCII <em>zero</em> where this mapper emits a <em>space</em>. A whole-record comparison
 * against the fixture would therefore fail on the filler alone, which is exactly why the mapper
 * publishes a 28-byte mapped-prefix bound and why every fixture comparison below observes it.
 *
 * <p><strong>The balance is zoned decimal with its sign overpunched into its final byte.</strong>
 * Eleven bytes hold nine integer digits and two decimals; the trailing byte carries both the low-order
 * digit and the sign, drawn from the ten positive and ten negative forms. Decoding truncates toward
 * zero at scale two and never rounds, because no arithmetic statement in the estate specifies rounding
 * and a COBOL store without a rounding clause truncates. This class names no rounding mode, rescales
 * nothing, and builds every expected balance from a decimal string literal rather than a binary
 * floating-point value. Negative zero is exercised because the shipped fixture is full of it: an
 * account with no activity in a category carries a positively signed zero, and the negative form is a
 * representable image whose value is still zero.
 *
 * <p>Provenance: checkout SHA {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. Layout authority is copybook
 * {@code app/cpy/CVTRA01Y.cpy}; the record width and the seventeen-byte key at offset zero are
 * independently attested by the cluster definition in {@code app/jcl/TCATBALF.jcl}. No legacy source
 * line is transcribed anywhere in this file - only widths, offsets, counts, field names and contract
 * literals, which are metadata rather than source.
 *
 * @see TranCatBalRecordMapper
 * @see TransactionCategoryBalance
 * @see TransactionCategoryBalanceId
 */
@DisplayName("transaction category balance mapper: the 50-byte CVTRA01Y layout and its 17-byte key")
class TranCatBalRecordMapperTest {

    // ------------------------------------------------------------------------------------------
    // The copybook's declared widths, transcribed from app/cpy/CVTRA01Y.cpy as integer literals.
    // These are the independent side of every geometry assertion.
    // ------------------------------------------------------------------------------------------

    /** {@code TRANCAT-ACCT-ID PIC 9(11)}: significant leading zeros. */
    private static final int WIDTH_ACCT_ID = 11;

    /** {@code TRANCAT-TYPE-CD PIC X(02)}. */
    private static final int WIDTH_TYPE_CD = 2;

    /** {@code TRANCAT-CD PIC 9(04)}: significant leading zeros. */
    private static final int WIDTH_CAT_CD = 4;

    /** {@code TRAN-CAT-BAL PIC S9(09)V99}: nine integer digits, two decimals, sign overpunched. */
    private static final int WIDTH_BALANCE = 11;

    /** {@code FILLER PIC X(22)}: the unmapped trailing run. */
    private static final int WIDTH_FILLER = 22;

    /** The declared record length, and the sum of the five widths above. */
    private static final int RECORD_WIDTH = 50;

    /** The three-component composite key: {@code 11 + 2 + 4}, and what the cluster stores as its key. */
    private static final int KEY_WIDTH = 17;

    /** The mapped prefix, {@code 17 + 11}: the only bound at which a fixture comparison is valid. */
    private static final int MAPPED_PREFIX_WIDTH = 28;

    /** Zero-based offset of the account identifier, and of the key group: both begin the record. */
    private static final int OFFSET_ACCT_ID = 0;

    /** Zero-based offset of the type code. */
    private static final int OFFSET_TYPE_CD = 11;

    /** Zero-based offset of the category code. */
    private static final int OFFSET_CAT_CD = 13;

    /** Zero-based offset of the balance, immediately after the seventeen-byte key. */
    private static final int OFFSET_BALANCE = 17;

    /** Zero-based offset at which the unmapped filler run begins. */
    private static final int OFFSET_FILLER = 28;

    /**
     * Width of the identically named but structurally unrelated key of the transaction-category
     * layout, declared here so the assertion that the two are different is made against a literal.
     */
    private static final int UNRELATED_SIX_BYTE_KEY_WIDTH = 6;

    // ------------------------------------------------------------------------------------------
    // Hand-authored reference rows. Each is declared as its significant field values plus an
    // already-encoded balance image, so no assertion depends on the codec to produce the bytes it
    // then checks.
    // ------------------------------------------------------------------------------------------

    /** Eleven-byte account identifier of the first authored row. */
    private static final String FIRST_ACCT_ID = "00000000042";

    /** Two-byte type code of the first authored row: a purchase. */
    private static final String FIRST_TYPE_CD = "01";

    /** Four-byte category code of the first authored row. */
    private static final String FIRST_CAT_CD = "0005";

    /**
     * Eleven-byte zoned image of the first row's balance: ten digits then an overpunched final byte.
     * {@code F} is the seventh positive form, contributing the digit six as well as the sign, so the
     * eleven digits are {@code 00000123456} and, at two implied decimals, the value below.
     */
    private static final String FIRST_BALANCE_IMAGE = "0000012345F";

    /** The first row's balance, decoded by hand from {@link #FIRST_BALANCE_IMAGE}. */
    private static final BigDecimal FIRST_BALANCE = new BigDecimal("1234.56");

    /** Eleven-byte account identifier of the second authored row. */
    private static final String SECOND_ACCT_ID = "00000000099";

    /** Two-byte type code of the second authored row: a return. */
    private static final String SECOND_TYPE_CD = "02";

    /** Four-byte category code of the second authored row. */
    private static final String SECOND_CAT_CD = "0017";

    /**
     * Eleven-byte zoned image of the second row's balance. A closing brace is the negative form of the
     * digit zero, so the eleven digits are {@code 00000009190} and the value is negative.
     */
    private static final String SECOND_BALANCE_IMAGE = "0000000919}";

    /** The second row's balance, decoded by hand from {@link #SECOND_BALANCE_IMAGE}. */
    private static final BigDecimal SECOND_BALANCE = new BigDecimal("-91.90");

    /** The ten characters that overpunch a positive final digit, zero through nine in order. */
    private static final String POSITIVE_OVERPUNCH = "{ABCDEFGHI";

    /** The ten characters that overpunch a negative final digit, zero through nine in order. */
    private static final String NEGATIVE_OVERPUNCH = "}JKLMNOPQR";

    // ------------------------------------------------------------------------------------------
    // The shipped reference fixture, used as a second oracle independent of everything above.
    // ------------------------------------------------------------------------------------------

    /** Classpath location of the seeded category-balance dataset. */
    private static final String FIXTURE = "/fixtures/input/tcatbal.txt";

    /** Records the seeded dataset carries, from its measured 2,550 bytes at a 51-byte stride. */
    private static final int FIXTURE_RECORD_COUNT = 50;

    /** Stride of the seeded dataset: the record width plus its single line terminator. */
    private static final int FIXTURE_STRIDE = RECORD_WIDTH + 1;

    /** The byte the shipped fixture uses across its filler run, which is not the byte this mapper emits. */
    private static final char FIXTURE_FILLER_CHARACTER = '0';

    /**
     * Places a value the way a {@code PIC X(n)} field holds it: left-justified, space-padded.
     *
     * @param  value the significant content
     * @param  width the declared field width
     * @return the value at exactly {@code width} characters
     */
    private static String alphanumeric(final String value, final int width) {
        assertThat(value.length())
                .as("the hand-transcribed value '%s' cannot exceed its %d-byte field", value, width)
                .isLessThanOrEqualTo(width);
        return value + " ".repeat(width - value.length());
    }

    /**
     * Places a value the way a {@code PIC 9(n)} field holds it: right-justified, zero-padded.
     *
     * @param  value the significant content
     * @param  width the declared field width
     * @return the value at exactly {@code width} characters
     */
    private static String numeric(final String value, final int width) {
        assertThat(value.length())
                .as("the hand-transcribed value '%s' cannot exceed its %d-byte field", value, width)
                .isLessThanOrEqualTo(width);
        return "0".repeat(width - value.length()) + value;
    }

    /**
     * Reassembles a complete record image from its significant field values.
     *
     * @param  acctId       account identifier
     * @param  typeCd       transaction type code
     * @param  catCd        transaction category code
     * @param  balanceImage the eleven-byte zoned image of the balance
     * @param  filler       the byte to write across the twenty-two-byte filler run
     * @return the complete image, exactly {@value #RECORD_WIDTH} characters
     */
    private static String recordImage(final String acctId, final String typeCd, final String catCd,
            final String balanceImage, final char filler) {
        return numeric(acctId, WIDTH_ACCT_ID)
                + alphanumeric(typeCd, WIDTH_TYPE_CD)
                + numeric(catCd, WIDTH_CAT_CD)
                + balanceImage
                + String.valueOf(filler).repeat(WIDTH_FILLER);
    }

    /** @return the first authored row, with the space filler this mapper emits */
    private static String firstImage() {
        return recordImage(FIRST_ACCT_ID, FIRST_TYPE_CD, FIRST_CAT_CD, FIRST_BALANCE_IMAGE, ' ');
    }

    /** @return the second authored row, with the space filler this mapper emits */
    private static String secondImage() {
        return recordImage(SECOND_ACCT_ID, SECOND_TYPE_CD, SECOND_CAT_CD, SECOND_BALANCE_IMAGE, ' ');
    }

    /**
     * Reassembles the first authored row with a different balance image, for the sign cases.
     *
     * @param  balanceImage the eleven-byte zoned image to place
     * @return the complete image at exactly {@value #RECORD_WIDTH} characters
     */
    private static String imageWithBalance(final String balanceImage) {
        return recordImage(FIRST_ACCT_ID, FIRST_TYPE_CD, FIRST_CAT_CD, balanceImage, ' ');
    }

    /** @return an entity carrying exactly the first authored row's field values */
    private static TransactionCategoryBalance firstEntity() {
        return new TransactionCategoryBalance(numeric(FIRST_ACCT_ID, WIDTH_ACCT_ID),
                alphanumeric(FIRST_TYPE_CD, WIDTH_TYPE_CD),
                numeric(FIRST_CAT_CD, WIDTH_CAT_CD),
                FIRST_BALANCE);
    }

    /**
     * Measures a value the way the record measures it, in encoded bytes rather than characters.
     *
     * @param  value the value to measure
     * @return its US-ASCII encoded length
     */
    private static int encodedWidth(final String value) {
        return value.getBytes(StandardCharsets.US_ASCII).length;
    }

    /**
     * Reads the shipped reference fixture and splits it into records on its own stride.
     *
     * @return the fixture's records, each exactly {@value #RECORD_WIDTH} characters and terminator-free
     * @throws IOException if the fixture cannot be read
     */
    private static List<String> fixtureRecords() throws IOException {
        final byte[] content;
        try (InputStream stream = TranCatBalRecordMapperTest.class.getResourceAsStream(FIXTURE)) {
            assertThat(stream).as("%s must be on the test classpath", FIXTURE).isNotNull();
            content = stream.readAllBytes();
        }

        assertThat(content.length % FIXTURE_STRIDE)
                .as("the stride must divide the fixture exactly, or no record boundary is recoverable")
                .isZero();

        final List<String> records = new ArrayList<>();
        final String whole = new String(content, StandardCharsets.US_ASCII);
        for (int start = 0; start < whole.length(); start += FIXTURE_STRIDE) {
            assertThat(whole.charAt(start + RECORD_WIDTH))
                    .as("every fixture record must be closed by one line feed")
                    .isEqualTo('\n');
            records.add(whole.substring(start, start + RECORD_WIDTH));
        }
        return records;
    }

    @Nested
    @DisplayName("the declared geometry")
    class TheDeclaredGeometry {

        @Test
        @DisplayName("all four field offsets equal the zero-based positions the copybook declares, and "
                + "the filler begins where the mapped prefix ends")
        void allFourOffsetsEqualTheDeclaredPositions() {
            assertThat(TranCatBalRecordMapper.TRANCAT_ACCT_ID_OFFSET).isEqualTo(OFFSET_ACCT_ID);
            assertThat(TranCatBalRecordMapper.TRANCAT_TYPE_CD_OFFSET).isEqualTo(OFFSET_TYPE_CD);
            assertThat(TranCatBalRecordMapper.TRANCAT_CD_OFFSET).isEqualTo(OFFSET_CAT_CD);
            assertThat(TranCatBalRecordMapper.TRAN_CAT_BAL_OFFSET).isEqualTo(OFFSET_BALANCE);
            assertThat(TranCatBalRecordMapper.FILLER_OFFSET).isEqualTo(OFFSET_FILLER);
        }

        @Test
        @DisplayName("all four field lengths equal the byte widths the copybook declares, and the "
                + "filler run is twenty-two bytes")
        void allFourLengthsEqualTheDeclaredWidths() {
            assertThat(TranCatBalRecordMapper.TRANCAT_ACCT_ID_LENGTH).isEqualTo(WIDTH_ACCT_ID);
            assertThat(TranCatBalRecordMapper.TRANCAT_TYPE_CD_LENGTH).isEqualTo(WIDTH_TYPE_CD);
            assertThat(TranCatBalRecordMapper.TRANCAT_CD_LENGTH).isEqualTo(WIDTH_CAT_CD);
            assertThat(TranCatBalRecordMapper.TRAN_CAT_BAL_LENGTH).isEqualTo(WIDTH_BALANCE);
            assertThat(TranCatBalRecordMapper.FILLER_LENGTH).isEqualTo(WIDTH_FILLER);
        }

        @Test
        @DisplayName("the record is 50 bytes: a 28-byte mapped prefix plus a 22-byte filler run, and "
                + "the five declared widths sum to exactly that")
        void theRecordIsFiftyBytesWide() {
            final int sumOfWidths =
                    WIDTH_ACCT_ID + WIDTH_TYPE_CD + WIDTH_CAT_CD + WIDTH_BALANCE + WIDTH_FILLER;

            assertThat(sumOfWidths)
                    .as("the copybook's own widths must account for the whole declared record")
                    .isEqualTo(RECORD_WIDTH);
            assertThat(TranCatBalRecordMapper.RECORD_LENGTH).isEqualTo(RECORD_WIDTH);
            assertThat(TranCatBalRecordMapper.MAPPED_PREFIX_LENGTH).isEqualTo(MAPPED_PREFIX_WIDTH);
        }

        @Test
        @DisplayName("the composite key is seventeen bytes at offset zero, being all three components, "
                + "and is deliberately not the six-byte key of the transaction-category layout")
        void theCompositeKeyIsSeventeenBytesAtOffsetZero() {
            assertThat(TranCatBalRecordMapper.ACCOUNT_TYPE_AND_CATEGORY_KEY_WIDTH)
                    .as("eleven plus two plus four is seventeen, which is what the cluster stores")
                    .isEqualTo(KEY_WIDTH)
                    .isEqualTo(WIDTH_ACCT_ID + WIDTH_TYPE_CD + WIDTH_CAT_CD)
                    .isNotEqualTo(UNRELATED_SIX_BYTE_KEY_WIDTH);
            assertThat(TranCatBalRecordMapper.TRANCAT_ACCT_ID_OFFSET)
                    .as("the key group begins the record, which is why the cluster keys at offset 0")
                    .isZero();
            assertThat(TranCatBalRecordMapper.TRAN_CAT_BAL_OFFSET)
                    .as("the balance begins exactly where the key ends, with no gap")
                    .isEqualTo(KEY_WIDTH);
        }

        @Test
        @DisplayName("the balance occupies eleven bytes, being nine integer digits plus two implied "
                + "decimals with the sign overpunched into the last of them")
        void theBalanceOccupiesElevenBytes() {
            assertThat(TranCatBalRecordMapper.TRAN_CAT_BAL_LENGTH)
                    .as("neither the twelve bytes of an account monetary field nor the six of a rate")
                    .isEqualTo(WIDTH_BALANCE)
                    .isEqualTo(ZonedDecimalCodec.CATEGORY_BALANCE_WIDTH)
                    .isNotEqualTo(ZonedDecimalCodec.ACCOUNT_AMOUNT_WIDTH)
                    .isNotEqualTo(ZonedDecimalCodec.INTEREST_RATE_WIDTH);
        }

        @Test
        @DisplayName("the filler run is emitted as spaces, which is NOT the byte the shipped fixture "
                + "carries, so a fixture comparison must observe the mapped-prefix bound")
        void theFillerRunIsEmittedAsSpaces() {
            assertThat(TranCatBalRecordMapper.FILLER_CHARACTER).isEqualTo(' ');
            assertThat(TranCatBalRecordMapper.FILLER_CHARACTER)
                    .as("filler carries no initialising clause, so the two bytes legitimately differ")
                    .isNotEqualTo(FIXTURE_FILLER_CHARACTER);
        }

        @Test
        @DisplayName("both artefact names identify the layout, and the key's name states its width so "
                + "a diagnostic can never be mistaken for the unrelated six-byte key")
        void bothArtefactNamesIdentifyTheLayout() {
            assertThat(TranCatBalRecordMapper.ARTEFACT)
                    .contains("TRAN-CAT-BAL-RECORD")
                    .contains("CVTRA01Y");
            assertThat(TranCatBalRecordMapper.KEY_ARTEFACT)
                    .contains("TRAN-CAT-KEY")
                    .contains("CVTRA01Y")
                    .contains(String.valueOf(KEY_WIDTH));
        }

        @Test
        @DisplayName("the hand-assembled oracle images are themselves exactly 50 bytes, so a fault in "
                + "this class's own helpers cannot be mistaken for a fault in the mapper")
        void theHandAssembledOracleImagesAreFiftyBytes() {
            assertThat(encodedWidth(firstImage())).isEqualTo(RECORD_WIDTH);
            assertThat(encodedWidth(secondImage())).isEqualTo(RECORD_WIDTH);
            assertThat(encodedWidth(FIRST_BALANCE_IMAGE)).isEqualTo(WIDTH_BALANCE);
            assertThat(encodedWidth(SECOND_BALANCE_IMAGE)).isEqualTo(WIDTH_BALANCE);
        }
    }

    @Nested
    @DisplayName("reading a record image")
    class ReadingARecordImage {

        @Test
        @DisplayName("all four properties of the first authored row map to their hand-derived values, "
                + "at their full declared widths")
        void allFourPropertiesMapToTheirHandDerivedValues() {
            final TransactionCategoryBalance mapped =
                    TranCatBalRecordMapper.fromRecord(firstImage());

            assertThat(mapped.getTrancatAcctId()).isEqualTo(numeric(FIRST_ACCT_ID, WIDTH_ACCT_ID));
            assertThat(mapped.getTrancatTypeCd())
                    .isEqualTo(alphanumeric(FIRST_TYPE_CD, WIDTH_TYPE_CD));
            assertThat(mapped.getTrancatCd()).isEqualTo(numeric(FIRST_CAT_CD, WIDTH_CAT_CD));
            assertThat(mapped.getTranCatBal()).isEqualByComparingTo(FIRST_BALANCE);

            assertThat(encodedWidth(mapped.getTrancatAcctId())).isEqualTo(WIDTH_ACCT_ID);
            assertThat(encodedWidth(mapped.getTrancatTypeCd())).isEqualTo(WIDTH_TYPE_CD);
            assertThat(encodedWidth(mapped.getTrancatCd())).isEqualTo(WIDTH_CAT_CD);
        }

        @Test
        @DisplayName("significant leading zeros survive on both numeric components, because each is "
                + "carried as text rather than parsed into a number")
        void significantLeadingZerosSurvive() {
            final TransactionCategoryBalance mapped =
                    TranCatBalRecordMapper.fromRecord(firstImage());

            assertThat(mapped.getTrancatAcctId()).isEqualTo("00000000042").startsWith("000000000");
            assertThat(mapped.getTrancatCd()).isEqualTo("0005").startsWith("000");
        }

        @Test
        @DisplayName("the twenty-two filler bytes are not read at all, so a record whose filler differs "
                + "still maps to an equal entity")
        void theFillerIsNotRead() {
            final TransactionCategoryBalance withSpaces =
                    TranCatBalRecordMapper.fromRecord(firstImage());
            final TransactionCategoryBalance withZeros = TranCatBalRecordMapper.fromRecord(
                    recordImage(FIRST_ACCT_ID, FIRST_TYPE_CD, FIRST_CAT_CD, FIRST_BALANCE_IMAGE,
                            FIXTURE_FILLER_CHARACTER));

            assertThat(withZeros.getTrancatAcctId()).isEqualTo(withSpaces.getTrancatAcctId());
            assertThat(withZeros.getTrancatTypeCd()).isEqualTo(withSpaces.getTrancatTypeCd());
            assertThat(withZeros.getTrancatCd()).isEqualTo(withSpaces.getTrancatCd());
            assertThat(withZeros.getTranCatBal()).isEqualByComparingTo(withSpaces.getTranCatBal());
        }

        @Test
        @DisplayName("the three reading entry points produce equal entities from the same bytes")
        void theThreeEntryPointsProduceEqualEntities() {
            final String image = firstImage();
            final byte[] bytes = image.getBytes(StandardCharsets.US_ASCII);

            final TransactionCategoryBalance fromString = TranCatBalRecordMapper.fromRecord(image);
            final TransactionCategoryBalance fromBytes = TranCatBalRecordMapper.fromRecord(bytes);
            final TransactionCategoryBalance fromRange =
                    TranCatBalRecordMapper.fromRecord(bytes, 0);

            assertThat(TranCatBalRecordMapper.toRecord(fromBytes))
                    .isEqualTo(TranCatBalRecordMapper.toRecord(fromString));
            assertThat(TranCatBalRecordMapper.toRecord(fromRange))
                    .isEqualTo(TranCatBalRecordMapper.toRecord(fromString));
        }

        @Test
        @DisplayName("the byte-range entry point selects one record out of a buffer holding many at a "
                + "51-byte stride, leaving each record separator behind")
        void theByteRangeEntryPointSelectsOneRecordFromABuffer() {
            final List<String> images = List.of(firstImage(), secondImage(), firstImage());
            final StringBuilder buffer = new StringBuilder();
            for (final String image : images) {
                buffer.append(image).append('\n');
            }
            final byte[] bytes = buffer.toString().getBytes(StandardCharsets.US_ASCII);

            final List<String> recovered = new ArrayList<>();
            for (int index = 0; index < images.size(); index++) {
                recovered.add(TranCatBalRecordMapper.toRecord(
                        TranCatBalRecordMapper.fromRecord(bytes, index * FIXTURE_STRIDE)));
            }

            assertThat(recovered).containsExactlyElementsOf(images);
        }
    }

    @Nested
    @DisplayName("the seventeen-byte composite key")
    class TheCompositeKey {

        @Test
        @DisplayName("the key is extracted from a record image as its three components, each at its "
                + "full declared width")
        void theKeyIsExtractedAsItsThreeComponents() {
            final TransactionCategoryBalanceId key =
                    TranCatBalRecordMapper.keyFromRecord(firstImage());

            assertThat(key.getTrancatAcctId()).isEqualTo(numeric(FIRST_ACCT_ID, WIDTH_ACCT_ID));
            assertThat(key.getTrancatTypeCd())
                    .isEqualTo(alphanumeric(FIRST_TYPE_CD, WIDTH_TYPE_CD));
            assertThat(key.getTrancatCd()).isEqualTo(numeric(FIRST_CAT_CD, WIDTH_CAT_CD));
        }

        @Test
        @DisplayName("the key image is exactly the leading seventeen bytes of the record image, which "
                + "is what the cluster definition makes the stored key")
        void theKeyImageIsTheLeadingSeventeenBytesOfTheRecord() {
            final String keyImage =
                    TranCatBalRecordMapper.accountTypeAndCategoryKeyImage(firstEntity());

            assertThat(encodedWidth(keyImage)).isEqualTo(KEY_WIDTH);
            assertThat(keyImage).isEqualTo(firstImage().substring(0, KEY_WIDTH));
        }

        @Test
        @DisplayName("the key image restores the significant leading zeros of both numeric components "
                + "and leaves the type code left-justified")
        void theKeyImageRestoresLeadingZeros() {
            final String keyImage =
                    TranCatBalRecordMapper.accountTypeAndCategoryKeyImage(firstEntity());

            assertThat(keyImage.substring(OFFSET_ACCT_ID, OFFSET_ACCT_ID + WIDTH_ACCT_ID))
                    .isEqualTo("00000000042");
            assertThat(keyImage.substring(OFFSET_TYPE_CD, OFFSET_TYPE_CD + WIDTH_TYPE_CD))
                    .isEqualTo("01");
            assertThat(keyImage.substring(OFFSET_CAT_CD, OFFSET_CAT_CD + WIDTH_CAT_CD))
                    .isEqualTo("0005");
        }

        @Test
        @DisplayName("a key extracted from a record and a key image rendered from the equivalent "
                + "entity describe the same seventeen bytes, so the two directions agree")
        void extractionAndRenderingAgree() {
            final TransactionCategoryBalanceId extracted =
                    TranCatBalRecordMapper.keyFromRecord(secondImage());
            final String rendered = TranCatBalRecordMapper.accountTypeAndCategoryKeyImage(
                    new TransactionCategoryBalance(extracted.getTrancatAcctId(),
                            extracted.getTrancatTypeCd(), extracted.getTrancatCd(),
                            SECOND_BALANCE));

            assertThat(rendered).isEqualTo(secondImage().substring(0, KEY_WIDTH));
        }

        @Test
        @DisplayName("an absent record image and an absent entity are both refused rather than "
                + "yielding a partly populated key")
        void anAbsentImageOrEntityIsRefused() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> TranCatBalRecordMapper.keyFromRecord(null));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> TranCatBalRecordMapper.accountTypeAndCategoryKeyImage(null));
        }

        @Test
        @DisplayName("an entity missing a key component is refused on the key-rendering path, the "
                + "diagnostic naming the legacy field rather than the value")
        void anEntityMissingAKeyComponentIsRefused() {
            final TransactionCategoryBalance missingCategory = firstEntity();
            missingCategory.setTrancatCd(null);

            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> TranCatBalRecordMapper
                            .accountTypeAndCategoryKeyImage(missingCategory))
                    .withMessageContaining("TRANCAT-CD");
        }

        @Test
        @DisplayName("a record image of the wrong width is refused on the key path too, so a key can "
                + "never be recovered from bytes that were not a record")
        void aWrongWidthImageIsRefusedOnTheKeyPath() {
            final String keyOnly = firstImage().substring(0, KEY_WIDTH);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> TranCatBalRecordMapper.keyFromRecord(keyOnly))
                    .withMessageContaining("CVTRA01Y")
                    .withMessageContaining(String.valueOf(RECORD_WIDTH));
        }
    }

    @Nested
    @DisplayName("the zoned-decimal balance and its overpunched sign")
    class TheZonedDecimalBalance {

        @Test
        @DisplayName("a positive overpunch contributes a digit as well as a sign")
        void aPositiveOverpunchContributesADigitAsWellAsASign() {
            assertThat(FIRST_BALANCE_IMAGE).endsWith("F");
            assertThat(TranCatBalRecordMapper.fromRecord(firstImage()).getTranCatBal())
                    .isEqualByComparingTo(FIRST_BALANCE)
                    .isPositive();
        }

        @Test
        @DisplayName("a negative overpunch carries the sign in the final byte, so a credit balance is "
                + "read as negative rather than as a large positive value")
        void aNegativeOverpunchCarriesTheSignInTheFinalByte() {
            assertThat(SECOND_BALANCE_IMAGE).endsWith("}");
            assertThat(TranCatBalRecordMapper.fromRecord(secondImage()).getTranCatBal())
                    .isEqualByComparingTo(SECOND_BALANCE)
                    .isNegative();
        }

        @Test
        @DisplayName("a positively signed all-zero image is zero at scale two, which is the state every "
                + "seeded row carries for a category with no activity")
        void aPositivelySignedAllZeroImageIsZero() {
            final BigDecimal balance =
                    TranCatBalRecordMapper.fromRecord(imageWithBalance("0000000000{"))
                            .getTranCatBal();

            assertThat(balance).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(balance.scale()).isEqualTo(ZonedDecimalCodec.MONETARY_SCALE);
        }

        @Test
        @DisplayName("a negatively signed all-zero image also decodes to zero, because negative zero is "
                + "a representable image whose value is still zero")
        void aNegativelySignedAllZeroImageIsAlsoZero() {
            final BigDecimal balance =
                    TranCatBalRecordMapper.fromRecord(imageWithBalance("0000000000}"))
                            .getTranCatBal();

            assertThat(balance).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(balance.scale()).isEqualTo(ZonedDecimalCodec.MONETARY_SCALE);
        }

        @Test
        @DisplayName("all twenty sign characters decode to the digit and sign the convention gives "
                + "them, so no form reachable in the estate is unreadable here")
        void allTwentySignCharactersDecodeCorrectly() {
            final String leadingDigits = "0000012345";
            for (int digit = 0; digit <= 9; digit++) {
                assertThat(TranCatBalRecordMapper
                                .fromRecord(imageWithBalance(
                                        leadingDigits + POSITIVE_OVERPUNCH.charAt(digit)))
                                .getTranCatBal())
                        .as("the positive form of digit %d", digit)
                        .isEqualByComparingTo(new BigDecimal("1234.5" + digit));
                assertThat(TranCatBalRecordMapper
                                .fromRecord(imageWithBalance(
                                        leadingDigits + NEGATIVE_OVERPUNCH.charAt(digit)))
                                .getTranCatBal())
                        .as("the negative form of digit %d", digit)
                        .isEqualByComparingTo(new BigDecimal("-1234.5" + digit));
            }
        }

        @Test
        @DisplayName("an unsigned trailing digit is read as a positive digit, so a field written by a "
                + "producer that emitted no overpunch is still read correctly")
        void anUnsignedTrailingDigitIsReadAsPositive() {
            assertThat(TranCatBalRecordMapper.fromRecord(imageWithBalance("00000123456"))
                            .getTranCatBal())
                    .isEqualByComparingTo(FIRST_BALANCE);
        }

        @Test
        @DisplayName("every decoded balance carries scale exactly two and the module truncates toward "
                + "zero, because the estate specifies no rounding clause anywhere")
        void everyDecodedBalanceCarriesScaleExactlyTwo() {
            assertThat(TranCatBalRecordMapper.fromRecord(firstImage()).getTranCatBal().scale())
                    .isEqualTo(ZonedDecimalCodec.MONETARY_SCALE);
            assertThat(TranCatBalRecordMapper.fromRecord(secondImage()).getTranCatBal().scale())
                    .isEqualTo(ZonedDecimalCodec.MONETARY_SCALE);
            assertThat(ZonedDecimalCodec.COBOL_TRUNCATION_MODE.name()).isEqualTo("DOWN");
        }

        @Test
        @DisplayName("a balance field carrying a character the zoned convention does not define is "
                + "refused, the diagnostic naming the COBOL field")
        void aMalformedBalanceImageIsRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> TranCatBalRecordMapper
                            .fromRecord(imageWithBalance("0000012345*")))
                    .withMessageContaining("TRAN-CAT-BAL");
        }
    }

    @Nested
    @DisplayName("emitting a record image")
    class EmittingARecordImage {

        @Test
        @DisplayName("the whole 50-byte image is reproduced, filler included, because this mapper "
                + "emits the filler run as spaces")
        void theWholeImageIsReproduced() {
            final TransactionCategoryBalance mapped =
                    TranCatBalRecordMapper.fromRecord(firstImage());
            final String emitted = TranCatBalRecordMapper.toRecord(mapped);

            assertThat(emitted).isEqualTo(firstImage());
            assertThat(emitted.substring(OFFSET_FILLER)).isEqualTo(" ".repeat(WIDTH_FILLER));
            assertThat(encodedWidth(emitted)).isEqualTo(RECORD_WIDTH);
        }

        @Test
        @DisplayName("the byte-emitting entry point produces the same 50 bytes and returns a fresh "
                + "array a caller can hold")
        void theByteEmitterProducesTheHandAssembledBytes() {
            final TransactionCategoryBalance mapped =
                    TranCatBalRecordMapper.fromRecord(firstImage());
            final byte[] emitted = TranCatBalRecordMapper.toRecordBytes(mapped);

            assertThat(emitted).hasSize(RECORD_WIDTH);
            assertThat(emitted).isEqualTo(firstImage().getBytes(StandardCharsets.US_ASCII));
            assertThat(TranCatBalRecordMapper.toRecordBytes(mapped)).isNotSameAs(emitted);
        }

        @Test
        @DisplayName("an entity built through the public four-argument constructor emits the authored "
                + "image, so the encoding path does not depend on having decoded first")
        void anEntityBuiltThroughThePublicConstructorEmitsTheAuthoredImage() {
            assertThat(TranCatBalRecordMapper.toRecord(firstEntity())).isEqualTo(firstImage());
        }

        @Test
        @DisplayName("a negative balance round-trips with its closing-brace sign byte restored in the "
                + "last byte of the balance field")
        void aNegativeBalanceRoundTrips() {
            final String emitted = TranCatBalRecordMapper
                    .toRecord(TranCatBalRecordMapper.fromRecord(secondImage()));

            assertThat(emitted.substring(OFFSET_BALANCE, OFFSET_BALANCE + WIDTH_BALANCE))
                    .isEqualTo(SECOND_BALANCE_IMAGE);
            assertThat(emitted.charAt(OFFSET_BALANCE + WIDTH_BALANCE - 1)).isEqualTo('}');
            assertThat(emitted).isEqualTo(secondImage());
        }

        @Test
        @DisplayName("an absent entity, and an entity with an absent mapped property, are both refused "
                + "on the emitting path")
        void anAbsentEntityOrPropertyIsRefused() {
            final TransactionCategoryBalance missingBalance = firstEntity();
            missingBalance.setTranCatBal(null);
            final TransactionCategoryBalance missingTypeCode = firstEntity();
            missingTypeCode.setTrancatTypeCd(null);

            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> TranCatBalRecordMapper.toRecord(null));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> TranCatBalRecordMapper.toRecord(missingBalance))
                    .withMessageContaining("TRAN-CAT-BAL");
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> TranCatBalRecordMapper.toRecordBytes(missingTypeCode))
                    .withMessageContaining("TRANCAT-TYPE-CD");
        }

        @Test
        @DisplayName("a component wider than its field is refused rather than silently truncated")
        void aComponentWiderThanItsFieldIsRefused() {
            final TransactionCategoryBalance overWide = firstEntity();
            overWide.setTrancatTypeCd("012");

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> TranCatBalRecordMapper.toRecord(overWide));
        }

        @Test
        @DisplayName("a balance needing more integer digits than the field provides is refused rather "
                + "than narrowed, which diverges deliberately from the legacy silent truncation")
        void aBalanceTooWideForItsFieldIsRefused() {
            final TransactionCategoryBalance tooLarge = firstEntity();
            tooLarge.setTranCatBal(new BigDecimal("1234567890.12"));

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> TranCatBalRecordMapper.toRecord(tooLarge));
        }
    }

    @Nested
    @DisplayName("refusing a malformed image")
    class RefusingAMalformedImage {

        @Test
        @DisplayName("a 49-byte image is refused rather than silently padded, and the diagnostic names "
                + "the layout, the expected width and the actual length")
        void aFortyNineByteImageIsRefused() {
            final String tooShort = firstImage().substring(0, RECORD_WIDTH - 1);
            assertThat(encodedWidth(tooShort)).isEqualTo(49);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> TranCatBalRecordMapper.fromRecord(tooShort))
                    .withMessageContaining("CVTRA01Y")
                    .withMessageContaining(String.valueOf(RECORD_WIDTH))
                    .withMessageContaining("49");
        }

        @Test
        @DisplayName("a 51-byte image is refused rather than silently truncated, which is exactly the "
                + "case of a caller that forgot to strip the record separator")
        void aFiftyOneByteImageIsRefused() {
            final String withTerminator = firstImage() + "\n";
            assertThat(encodedWidth(withTerminator)).isEqualTo(FIXTURE_STRIDE);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> TranCatBalRecordMapper.fromRecord(withTerminator))
                    .withMessageContaining("CVTRA01Y")
                    .withMessageContaining(String.valueOf(RECORD_WIDTH));
        }

        @Test
        @DisplayName("the byte-array and byte-range entry points apply the same width and range rules")
        void theByteEntryPointsApplyTheSameRules() {
            final byte[] tooShort = firstImage().substring(0, RECORD_WIDTH - 1)
                    .getBytes(StandardCharsets.US_ASCII);
            final byte[] oneRecord = firstImage().getBytes(StandardCharsets.US_ASCII);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> TranCatBalRecordMapper.fromRecord(tooShort))
                    .withMessageContaining(String.valueOf(RECORD_WIDTH));
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> TranCatBalRecordMapper.fromRecord(oneRecord, 1));
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> TranCatBalRecordMapper.fromRecord(oneRecord, -1));
        }

        @Test
        @DisplayName("an absent image is refused on every reading entry point")
        void anAbsentImageIsRefusedOnEveryEntryPoint() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> TranCatBalRecordMapper.fromRecord((String) null));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> TranCatBalRecordMapper.fromRecord((byte[]) null));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> TranCatBalRecordMapper.fromRecord(null, 0));
        }
    }

    @Nested
    @DisplayName("the shipped reference fixture, as a second and wholly independent oracle")
    class TheShippedReferenceFixture {

        @Test
        @DisplayName("the fixture carries fifty 50-byte records at a 51-byte stride, each closed by "
                + "one line feed")
        void theFixtureCarriesFiftyRecords() throws IOException {
            assertThat(fixtureRecords())
                    .hasSize(FIXTURE_RECORD_COUNT)
                    .allSatisfy(record -> assertThat(encodedWidth(record)).isEqualTo(RECORD_WIDTH));
        }

        @Test
        @DisplayName("every one of the fifty seeded records decodes, and each decoded field is exactly "
                + "the slice of the record at the copybook's own offset")
        void everySeededRecordDecodesToItsOwnSlices() throws IOException {
            for (final String record : fixtureRecords()) {
                final TransactionCategoryBalance mapped =
                        TranCatBalRecordMapper.fromRecord(record);

                assertThat(mapped.getTrancatAcctId())
                        .isEqualTo(record.substring(OFFSET_ACCT_ID,
                                OFFSET_ACCT_ID + WIDTH_ACCT_ID));
                assertThat(mapped.getTrancatTypeCd())
                        .isEqualTo(record.substring(OFFSET_TYPE_CD,
                                OFFSET_TYPE_CD + WIDTH_TYPE_CD));
                assertThat(mapped.getTrancatCd())
                        .isEqualTo(record.substring(OFFSET_CAT_CD, OFFSET_CAT_CD + WIDTH_CAT_CD));
                assertThat(mapped.getTranCatBal().scale())
                        .isEqualTo(ZonedDecimalCodec.MONETARY_SCALE);
            }
        }

        @Test
        @DisplayName("the first seeded record decodes to the account, type, category and zero balance "
                + "transcribed by hand from the shipped bytes")
        void theFirstSeededRecordDecodesToItsTranscribedValues() throws IOException {
            final TransactionCategoryBalance mapped =
                    TranCatBalRecordMapper.fromRecord(fixtureRecords().get(0));

            assertThat(mapped.getTrancatAcctId()).isEqualTo("00000000001");
            assertThat(mapped.getTrancatTypeCd()).isEqualTo("01");
            assertThat(mapped.getTrancatCd()).isEqualTo("0001");
            assertThat(mapped.getTranCatBal()).isEqualByComparingTo(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("every seeded record round-trips over the 28-byte mapped prefix, and the filler "
                + "beyond it legitimately differs because the fixture writes zeros there")
        void everySeededRecordRoundTripsOverTheMappedPrefix() throws IOException {
            final List<String> records = fixtureRecords();

            for (final String record : records) {
                final String emitted = TranCatBalRecordMapper
                        .toRecord(TranCatBalRecordMapper.fromRecord(record));

                assertThat(emitted.substring(0, MAPPED_PREFIX_WIDTH))
                        .as("the mapped prefix must be reproduced byte for byte")
                        .isEqualTo(record.substring(0, MAPPED_PREFIX_WIDTH));
                assertThat(emitted.substring(OFFSET_FILLER))
                        .isEqualTo(" ".repeat(WIDTH_FILLER));
            }

            // Stated as behaviour so the comparison bound is not mistaken for a defect: the shipped
            // filler is ASCII zero, this mapper writes spaces, and COBOL filler has no initialising
            // clause, so neither byte is canonical.
            assertThat(records.get(0).substring(OFFSET_FILLER))
                    .isEqualTo(String.valueOf(FIXTURE_FILLER_CHARACTER).repeat(WIDTH_FILLER));
        }

        @Test
        @DisplayName("every seeded record's key is the leading seventeen bytes of that record, so the "
                + "key extracted and the key rendered agree across all fifty rows")
        void everySeededRecordKeyIsItsLeadingSeventeenBytes() throws IOException {
            for (final String record : fixtureRecords()) {
                final TransactionCategoryBalanceId key =
                        TranCatBalRecordMapper.keyFromRecord(record);
                final String rendered = TranCatBalRecordMapper.accountTypeAndCategoryKeyImage(
                        TranCatBalRecordMapper.fromRecord(record));

                assertThat(rendered).isEqualTo(record.substring(0, KEY_WIDTH));
                assertThat(key.getTrancatAcctId() + key.getTrancatTypeCd() + key.getTrancatCd())
                        .isEqualTo(record.substring(0, KEY_WIDTH));
            }
        }

        @Test
        @DisplayName("the fifty seeded keys are all distinct, which is what a keyed cluster requires "
                + "and what the composite primary key of the migrated table enforces")
        void theFiftySeededKeysAreAllDistinct() throws IOException {
            final List<String> keys = new ArrayList<>();
            for (final String record : fixtureRecords()) {
                keys.add(record.substring(0, KEY_WIDTH));
            }

            assertThat(keys).hasSize(FIXTURE_RECORD_COUNT).doesNotHaveDuplicates();
        }
    }
}
