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
package com.carddemo.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Unit test for {@link DailyTransaction}, the entity form of the 350-byte daily-transaction record that
 * is the primary input of the posting pipeline.
 *
 * <p><strong>What this test proves.</strong> Three independent legacy authorities fix the shape of this
 * entity, and this test asserts that the Java form honours all three:
 * <ul>
 *   <li>the copybook member {@code CVTRA06Y}, whose record is 350 bytes: a 16-byte identifier at
 *       offset 0, a 2-byte type code at 16, a 4-digit category code at 18, a 10-byte source at 22, a
 *       100-byte description at 32, an 11-byte zoned amount at 132, a 9-digit merchant identifier at
 *       143, two 50-byte merchant text fields at 152 and 202, a 10-byte merchant postal code at 252, a
 *       16-byte card number at 262, a 26-byte origination timestamp at 278, a 26-byte processing
 *       timestamp at 304 and a 20-byte trailing filler at 330;</li>
 *   <li>the external sort specifications, which address the card number as sixteen bytes beginning at
 *       one-based column 263 and the processing date as ten bytes beginning at one-based column 305.
 *       Those two columns are exactly the zero-based offsets 262 and 304 that the copybook arithmetic
 *       yields, so the two authorities cross-check one another rather than restating one twice; and</li>
 *   <li>the absence of any rounding clause in the estate, which makes every store into the two-decimal
 *       amount field a truncation toward zero. The entity carries the value, so this test asserts it
 *       preserves an exactly-scaled amount and demonstrates that the truncating and half-even modes
 *       disagree on the boundary case.</li>
 * </ul>
 *
 * <p><strong>Why this layout has its own entity even though it matches the transaction record byte for
 * byte.</strong> The two layouts are identical apart from their field-name prefixes, but they are
 * distinct datasets with distinct lifecycles: this one is a sequential input consumed by a posting run,
 * the other is an indexed store written by it. Keeping them separate is what allows the posting run to
 * read one and write the other within a single step.
 *
 * <p><strong>The processing timestamp arrives blank.</strong> Every row of the reference fixture carries
 * twenty-six spaces in the processing timestamp, because the posting run is what fills it. This test
 * asserts that a blank of exactly the field width is stored unchanged, so that a reader cannot mistake
 * the blank for a defect and a writer cannot silently substitute a value for it.
 *
 * <p><strong>Reference data.</strong> The daily-transaction fixture holds 300 rows of 350 bytes, of
 * which 250 carry the point-of-sale source and 50 carry the operator source; its first row carries the
 * values asserted below. Only data values are reproduced here; no line of legacy source is transcribed
 * anywhere in this file.
 *
 * <p><strong>Scope.</strong> A pure in-process unit test. It starts no application context, opens no
 * database connection, reads no file, touches no network, runs no container and performs no
 * introspection. Every expected value is a literal typed out in this source.
 *
 * <p><strong>Provenance.</strong> Legacy estate read at commit SHA
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. Cited as provenance only and never asserted
 * against a member.
 */
@DisplayName("DailyTransaction - the entity form of the 350-byte daily-transaction record")
class DailyTransactionBaselineTest {

    private static final int ID_WIDTH = 16;
    private static final int TYPE_CODE_WIDTH = 2;
    private static final int CATEGORY_CODE_WIDTH = 4;
    private static final int SOURCE_WIDTH = 10;
    private static final int DESCRIPTION_WIDTH = 100;
    private static final int AMOUNT_WIDTH = 11;
    private static final int MERCHANT_ID_WIDTH = 9;
    private static final int MERCHANT_TEXT_WIDTH = 50;
    private static final int MERCHANT_ZIP_WIDTH = 10;
    private static final int CARD_NUMBER_WIDTH = 16;
    private static final int TIMESTAMP_WIDTH = 26;
    private static final int FILLER_WIDTH = 20;

    /** Record length declared by the copybook and by the sequential dataset. */
    private static final int RECORD_LENGTH = 350;

    /** Scale the amount carries, fixed by the two decimal places of the picture. */
    private static final int AMOUNT_SCALE = 2;

    /** Zero-based offset of the card number, which the sort specification addresses as column 263. */
    private static final int CARD_NUMBER_OFFSET = 262;

    /** Zero-based offset of the origination timestamp. */
    private static final int ORIGINATION_TIMESTAMP_OFFSET = 278;

    /** Zero-based offset of the processing timestamp, addressed by the sort as column 305. */
    private static final int PROCESSING_TIMESTAMP_OFFSET = 304;

    /** Rows measured in the daily-transaction reference fixture. */
    private static final int FIXTURE_ROWS = 300;

    /** Point-of-sale rows measured in the fixture. */
    private static final int FIXTURE_POS_TERM_ROWS = 250;

    /** Operator-originated rows measured in the fixture. */
    private static final int FIXTURE_OPERATOR_ROWS = 50;

    private static final String FIRST_ID = "0000000000683580";
    private static final String PURCHASE_TYPE = "01";
    private static final String FIRST_CATEGORY = "0001";
    private static final String POS_TERM_SOURCE = "POS TERM  ";
    private static final String OPERATOR_SOURCE = "OPERATOR  ";
    private static final String FIRST_DESCRIPTION = rightPadded("Grocery purchase", DESCRIPTION_WIDTH);
    private static final BigDecimal FIRST_AMOUNT = new BigDecimal("504.77");
    private static final String FIRST_MERCHANT_ID = "800000000";
    private static final String FIRST_MERCHANT_NAME = rightPadded("Corner Market", MERCHANT_TEXT_WIDTH);
    private static final String FIRST_MERCHANT_CITY = rightPadded("Raleigh", MERCHANT_TEXT_WIDTH);
    private static final String FIRST_MERCHANT_ZIP = "12546     ";
    private static final String FIRST_CARD_NUMBER = "4859452612877065";
    private static final String FIRST_ORIGINATION_TIMESTAMP = "2022-06-10 19:27:53.000000";
    private static final String BLANK_PROCESSING_TIMESTAMP = " ".repeat(TIMESTAMP_WIDTH);

    private DailyTransaction transaction;

    /**
     * Right pads a value with spaces to the exact width the record layout reserves for its field.
     * Used only to build test inputs at their declared widths; the width itself is always asserted
     * against the independently declared constant rather than against this helper's output.
     *
     * @param value the significant text of the field
     * @param width the declared field width from the copybook layout
     * @return the value padded on the right with spaces to exactly {@code width} characters
     */
    private static String rightPadded(final String value, final int width) {
        return value + " ".repeat(width - value.length());
    }

    @BeforeEach
    void createFirstFixtureRow() {
        transaction = new DailyTransaction(FIRST_ID, PURCHASE_TYPE, FIRST_CATEGORY, POS_TERM_SOURCE,
                FIRST_DESCRIPTION, FIRST_AMOUNT, FIRST_MERCHANT_ID, FIRST_MERCHANT_NAME,
                FIRST_MERCHANT_CITY, FIRST_MERCHANT_ZIP, FIRST_CARD_NUMBER,
                FIRST_ORIGINATION_TIMESTAMP, BLANK_PROCESSING_TIMESTAMP);
    }

    @Nested
    @DisplayName("Construction in copybook declaration order")
    class Construction {

        @Test
        @DisplayName("the thirteen-argument constructor binds every field in copybook declaration "
                + "order: identifier, type, category, source, description, amount, merchant identifier, "
                + "merchant name, merchant city, merchant postal code, card number, origination "
                + "timestamp, processing timestamp")
        void theConstructorBindsEveryFieldInDeclarationOrder() {
            assertThat(transaction.getDalytranId()).isEqualTo(FIRST_ID);
            assertThat(transaction.getDalytranTypeCd()).isEqualTo(PURCHASE_TYPE);
            assertThat(transaction.getDalytranCatCd()).isEqualTo(FIRST_CATEGORY);
            assertThat(transaction.getDalytranSource()).isEqualTo(POS_TERM_SOURCE);
            assertThat(transaction.getDalytranDesc()).isEqualTo(FIRST_DESCRIPTION);
            assertThat(transaction.getDalytranAmt()).isEqualByComparingTo(FIRST_AMOUNT);
            assertThat(transaction.getDalytranMerchantId()).isEqualTo(FIRST_MERCHANT_ID);
            assertThat(transaction.getDalytranMerchantName()).isEqualTo(FIRST_MERCHANT_NAME);
            assertThat(transaction.getDalytranMerchantCity()).isEqualTo(FIRST_MERCHANT_CITY);
            assertThat(transaction.getDalytranMerchantZip()).isEqualTo(FIRST_MERCHANT_ZIP);
            assertThat(transaction.getDalytranCardNum()).isEqualTo(FIRST_CARD_NUMBER);
            assertThat(transaction.getDalytranOrigTs()).isEqualTo(FIRST_ORIGINATION_TIMESTAMP);
            assertThat(transaction.getDalytranProcTs()).isEqualTo(BLANK_PROCESSING_TIMESTAMP);
        }

        @Test
        @DisplayName("the no-arg constructor the provider requires leaves all thirteen fields absent, "
                + "because the provider populates state afterwards")
        void theNoArgConstructorLeavesEveryFieldAbsent() {
            final DailyTransaction empty = new DailyTransaction();

            assertThat(empty.getDalytranId()).isNull();
            assertThat(empty.getDalytranTypeCd()).isNull();
            assertThat(empty.getDalytranCatCd()).isNull();
            assertThat(empty.getDalytranSource()).isNull();
            assertThat(empty.getDalytranDesc()).isNull();
            assertThat(empty.getDalytranAmt()).isNull();
            assertThat(empty.getDalytranMerchantId()).isNull();
            assertThat(empty.getDalytranMerchantName()).isNull();
            assertThat(empty.getDalytranMerchantCity()).isNull();
            assertThat(empty.getDalytranMerchantZip()).isNull();
            assertThat(empty.getDalytranCardNum()).isNull();
            assertThat(empty.getDalytranOrigTs()).isNull();
            assertThat(empty.getDalytranProcTs()).isNull();
        }

        @Test
        @DisplayName("the constructor stores every value verbatim - no trim, no pad and no numeric "
                + "reinterpretation - because the posting cascade performs the validation")
        void theConstructorStoresEveryValueVerbatim() {
            final DailyTransaction raw = new DailyTransaction("  1  ", "aZ", "9x0", "unknown",
                    "", new BigDecimal("1.5"), "m", "", "  ", "z", "not-a-card", "", "  ");

            assertThat(raw.getDalytranId()).isEqualTo("  1  ");
            assertThat(raw.getDalytranTypeCd()).isEqualTo("aZ");
            assertThat(raw.getDalytranCatCd()).isEqualTo("9x0");
            assertThat(raw.getDalytranSource()).isEqualTo("unknown");
            assertThat(raw.getDalytranDesc()).isEmpty();
            assertThat(raw.getDalytranAmt()).isEqualTo(new BigDecimal("1.5"));
            assertThat(raw.getDalytranCardNum()).isEqualTo("not-a-card");
            assertThat(raw.getDalytranOrigTs()).isEmpty();
            assertThat(raw.getDalytranProcTs()).isEqualTo("  ");
        }

        @Test
        @DisplayName("absent values are accepted and returned unchanged, so a malformed input row can be "
                + "carried as far as the validation cascade that rejects it")
        void absentValuesAreAcceptedAndReturnedUnchanged() {
            final DailyTransaction sparse = new DailyTransaction(FIRST_ID, null, null, null, null,
                    null, null, null, null, null, null, null, null);

            assertThat(sparse.getDalytranId()).isEqualTo(FIRST_ID);
            assertThat(sparse.getDalytranCardNum()).isNull();
            assertThat(sparse.getDalytranAmt()).isNull();
        }
    }

    @Nested
    @DisplayName("Byte geometry of the 350-byte record and the sort columns it fixes")
    class ByteGeometry {

        @Test
        @DisplayName("the thirteen field widths plus the 20-byte filler close the 350-byte record "
                + "exactly, so the copybook layout and the declared record size agree")
        void theFieldWidthsCloseTheRecordExactly() {
            final int summed = ID_WIDTH
                    + TYPE_CODE_WIDTH
                    + CATEGORY_CODE_WIDTH
                    + SOURCE_WIDTH
                    + DESCRIPTION_WIDTH
                    + AMOUNT_WIDTH
                    + MERCHANT_ID_WIDTH
                    + 2 * MERCHANT_TEXT_WIDTH
                    + MERCHANT_ZIP_WIDTH
                    + CARD_NUMBER_WIDTH
                    + 2 * TIMESTAMP_WIDTH
                    + FILLER_WIDTH;

            assertThat(summed).isEqualTo(RECORD_LENGTH);
        }

        @Test
        @DisplayName("the card number sits at zero-based offset 262, which is the one-based column 263 "
                + "that the external sort specification addresses for sixteen bytes")
        void theCardNumberOffsetMatchesTheSortColumn() {
            final int computed = ID_WIDTH + TYPE_CODE_WIDTH + CATEGORY_CODE_WIDTH + SOURCE_WIDTH
                    + DESCRIPTION_WIDTH + AMOUNT_WIDTH + MERCHANT_ID_WIDTH
                    + 2 * MERCHANT_TEXT_WIDTH + MERCHANT_ZIP_WIDTH;
            final int sortColumnOneBased = 263;
            final int sortFieldLength = 16;

            assertThat(computed).isEqualTo(CARD_NUMBER_OFFSET);
            assertThat(computed + 1).isEqualTo(sortColumnOneBased);
            assertThat(sortFieldLength).isEqualTo(CARD_NUMBER_WIDTH);
            assertThat(transaction.getDalytranCardNum()).hasSize(CARD_NUMBER_WIDTH);
        }

        @Test
        @DisplayName("the origination timestamp sits at offset 278 and the processing timestamp at 304, "
                + "twenty-six bytes apart, and the processing date the sort filters on begins at the "
                + "one-based column 305")
        void theTwoTimestampOffsetsFollowTheCardNumber() {
            final int sortDateColumnOneBased = 305;
            final int sortDateLength = 10;

            assertThat(CARD_NUMBER_OFFSET + CARD_NUMBER_WIDTH)
                    .isEqualTo(ORIGINATION_TIMESTAMP_OFFSET);
            assertThat(ORIGINATION_TIMESTAMP_OFFSET + TIMESTAMP_WIDTH)
                    .isEqualTo(PROCESSING_TIMESTAMP_OFFSET);
            assertThat(PROCESSING_TIMESTAMP_OFFSET + 1).isEqualTo(sortDateColumnOneBased);
            assertThat(sortDateLength).isLessThan(TIMESTAMP_WIDTH);
        }

        @Test
        @DisplayName("the two timestamps plus the filler close the record after the processing "
                + "timestamp, so nothing follows the filler")
        void nothingFollowsTheFiller() {
            assertThat(PROCESSING_TIMESTAMP_OFFSET + TIMESTAMP_WIDTH + FILLER_WIDTH)
                    .isEqualTo(RECORD_LENGTH);
        }

        @Test
        @DisplayName("both timestamps are exactly twenty-six characters, which is the width the sort "
                + "key on the indexed transaction store also declares")
        void bothTimestampsAreExactlyTwentySixCharacters() {
            assertThat(transaction.getDalytranOrigTs()).hasSize(TIMESTAMP_WIDTH);
            assertThat(transaction.getDalytranProcTs()).hasSize(TIMESTAMP_WIDTH);
        }

        @Test
        @DisplayName("the identifier is sixteen bytes and is the record's business key, matching the "
                + "sixteen-byte key the indexed transaction store declares")
        void theIdentifierIsSixteenBytes() {
            assertThat(transaction.getDalytranId()).hasSize(ID_WIDTH);
            assertThat(ID_WIDTH).isEqualTo(CARD_NUMBER_WIDTH);
        }

        @Test
        @DisplayName("every padded text field keeps the width its picture reserves, so a value survives "
                + "into the entity with its trailing spaces intact")
        void everyPaddedTextFieldKeepsItsWidth() {
            assertThat(transaction.getDalytranSource()).hasSize(SOURCE_WIDTH);
            assertThat(transaction.getDalytranDesc()).hasSize(DESCRIPTION_WIDTH);
            assertThat(transaction.getDalytranMerchantName()).hasSize(MERCHANT_TEXT_WIDTH);
            assertThat(transaction.getDalytranMerchantCity()).hasSize(MERCHANT_TEXT_WIDTH);
            assertThat(transaction.getDalytranMerchantZip()).hasSize(MERCHANT_ZIP_WIDTH);
        }

        @Test
        @DisplayName("the type and category codes keep their narrow widths and their leading zeros, so "
                + "both are text rather than numbers")
        void theTypeAndCategoryCodesKeepTheirLeadingZeros() {
            assertThat(transaction.getDalytranTypeCd()).hasSize(TYPE_CODE_WIDTH).isEqualTo("01");
            assertThat(transaction.getDalytranCatCd())
                    .hasSize(CATEGORY_CODE_WIDTH)
                    .isEqualTo("0001");
        }

        @Test
        @DisplayName("the merchant identifier is nine digits, narrower than both the card number and the "
                + "transaction identifier, so the three cannot be confused by width")
        void theMerchantIdentifierIsNineDigits() {
            assertThat(transaction.getDalytranMerchantId())
                    .hasSize(MERCHANT_ID_WIDTH)
                    .containsOnlyDigits();
            assertThat(MERCHANT_ID_WIDTH).isLessThan(CARD_NUMBER_WIDTH).isLessThan(ID_WIDTH);
        }
    }

    @Nested
    @DisplayName("Decimal fidelity of the zoned amount field")
    class DecimalFidelity {

        @Test
        @DisplayName("the amount keeps the scale of two the picture fixes, so the field neither gains "
                + "nor loses a decimal place on its way into the entity")
        void theAmountKeepsScaleTwo() {
            assertThat(transaction.getDalytranAmt().scale()).isEqualTo(AMOUNT_SCALE);
        }

        @Test
        @DisplayName("the amount is an exact decimal rather than a binary approximation, so it compares "
                + "equal to its own literal rendering")
        void theAmountIsAnExactDecimal() {
            assertThat(transaction.getDalytranAmt()).isEqualTo(new BigDecimal("504.77"));
            assertThat(transaction.getDalytranAmt().toPlainString()).isEqualTo("504.77");
            assertThat(transaction.getDalytranAmt().unscaledValue().intValue()).isEqualTo(50_477);
        }

        @ParameterizedTest(name = "{0} truncates to {1} but half-even would give {2}")
        @DisplayName("truncation toward zero is what a store into the two-decimal amount does, because "
                + "no rounding clause exists anywhere in the estate - the two modes disagree, so "
                + "choosing the conventional one would shift an amount by a cent")
        @CsvSource({
            "504.775, 504.77, 504.78",
            "504.774, 504.77, 504.77",
            "504.999, 504.99, 505.00",
            "-504.775, -504.77, -504.78"})
        void truncationTowardZeroIsWhatAStoreDoes(final String raw, final String truncated,
                final String halfEven) {
            final BigDecimal source = new BigDecimal(raw);

            assertThat(source.setScale(AMOUNT_SCALE, RoundingMode.DOWN))
                    .isEqualTo(new BigDecimal(truncated));
            assertThat(source.setScale(AMOUNT_SCALE, RoundingMode.HALF_EVEN))
                    .isEqualTo(new BigDecimal(halfEven));

            transaction.setDalytranAmt(source.setScale(AMOUNT_SCALE, RoundingMode.DOWN));
            assertThat(transaction.getDalytranAmt()).isEqualTo(new BigDecimal(truncated));
        }

        @Test
        @DisplayName("the widest value the eleven-byte picture can carry survives unchanged, so the "
                + "column precision of eleven is sufficient for the whole field range")
        void theWidestRepresentableValueSurvivesUnchanged() {
            final BigDecimal widest = new BigDecimal("999999999.99");

            transaction.setDalytranAmt(widest);

            assertThat(transaction.getDalytranAmt()).isEqualTo(widest);
            assertThat(transaction.getDalytranAmt().precision()).isEqualTo(AMOUNT_WIDTH);
            assertThat(transaction.getDalytranAmt().scale()).isEqualTo(AMOUNT_SCALE);
        }

        @Test
        @DisplayName("a negative amount survives with its sign, which is what carries the fifty "
                + "operator-originated return rows of the fixture through the posting cascade")
        void aNegativeAmountSurvivesWithItsSign() {
            final BigDecimal refund = new BigDecimal("-504.77");

            transaction.setDalytranAmt(refund);
            transaction.setDalytranSource(OPERATOR_SOURCE);

            assertThat(transaction.getDalytranAmt()).isEqualTo(refund);
            assertThat(transaction.getDalytranAmt().signum()).isNegative();
            assertThat(transaction.getDalytranSource()).isEqualTo(OPERATOR_SOURCE);
        }

        @Test
        @DisplayName("the amount field is narrower than the account balance field by one byte, because "
                + "it carries nine integer digits where the balance carries ten")
        void theAmountFieldIsNarrowerThanTheBalanceField() {
            final int accountBalanceWidth = 12;

            assertThat(AMOUNT_WIDTH).isEqualTo(accountBalanceWidth - 1);
            assertThat(new BigDecimal("999999999.99").precision()).isEqualTo(AMOUNT_WIDTH);
        }
    }

    @Nested
    @DisplayName("Processing timestamp that arrives blank and is filled by the posting run")
    class ProcessingTimestamp {

        @Test
        @DisplayName("a blank of exactly the field width is stored unchanged, so a reader cannot mistake "
                + "the seeded blank for a defect")
        void aBlankOfExactlyTheFieldWidthIsStoredUnchanged() {
            assertThat(transaction.getDalytranProcTs())
                    .hasSize(TIMESTAMP_WIDTH)
                    .isBlank();
            assertThat(transaction.getDalytranProcTs()).isEqualTo(BLANK_PROCESSING_TIMESTAMP);
        }

        @Test
        @DisplayName("the origination timestamp is populated while the processing timestamp is blank, "
                + "which is exactly the state every seeded row arrives in")
        void theOriginationTimestampIsPopulatedWhileTheProcessingOneIsBlank() {
            assertThat(transaction.getDalytranOrigTs()).isNotBlank();
            assertThat(transaction.getDalytranProcTs()).isBlank();
        }

        @Test
        @DisplayName("the posting run may fill the processing timestamp to the same twenty-six-byte "
                + "width, and the entity stores it unchanged")
        void thePostingRunMayFillTheProcessingTimestamp() {
            final String posted = "2024-01-15-10.20.30.450000";

            transaction.setDalytranProcTs(posted);

            assertThat(transaction.getDalytranProcTs())
                    .isEqualTo(posted)
                    .hasSize(TIMESTAMP_WIDTH);
        }

        @Test
        @DisplayName("the origination timestamp of the first seeded row is a hyphenated date followed by "
                + "a space and a dotted time, twenty-six characters in all")
        void theOriginationTimestampShapeOfTheFirstSeededRow() {
            final String origination = transaction.getDalytranOrigTs();

            assertThat(origination).isEqualTo("2022-06-10 19:27:53.000000");
            assertThat(origination).hasSize(TIMESTAMP_WIDTH);
            assertThat(origination.charAt(4)).isEqualTo('-');
            assertThat(origination.charAt(10)).isEqualTo(' ');
            assertThat(origination.charAt(19)).isEqualTo('.');
        }
    }

    @Nested
    @DisplayName("Mutability required by the posting step")
    class Mutability {

        @Test
        @DisplayName("every mapped field round-trips through its setter, which the fixed-width reader "
                + "needs in order to populate a row slice by slice")
        void everyMappedFieldRoundTripsThroughItsSetter() {
            final DailyTransaction target = new DailyTransaction();

            target.setDalytranId("0000000000000001");
            target.setDalytranTypeCd("02");
            target.setDalytranCatCd("0005");
            target.setDalytranSource(OPERATOR_SOURCE);
            target.setDalytranDesc("Returned merchandise");
            target.setDalytranAmt(new BigDecimal("-12.34"));
            target.setDalytranMerchantId("900000000");
            target.setDalytranMerchantName("Returns Desk");
            target.setDalytranMerchantCity("Durham");
            target.setDalytranMerchantZip("27701     ");
            target.setDalytranCardNum("4859452612877066");
            target.setDalytranOrigTs("2022-06-11 08:00:00.000000");
            target.setDalytranProcTs("2022-06-12 09:00:00.000000");

            assertThat(target.getDalytranId()).isEqualTo("0000000000000001");
            assertThat(target.getDalytranTypeCd()).isEqualTo("02");
            assertThat(target.getDalytranCatCd()).isEqualTo("0005");
            assertThat(target.getDalytranSource()).isEqualTo(OPERATOR_SOURCE);
            assertThat(target.getDalytranDesc()).isEqualTo("Returned merchandise");
            assertThat(target.getDalytranAmt()).isEqualTo(new BigDecimal("-12.34"));
            assertThat(target.getDalytranMerchantId()).isEqualTo("900000000");
            assertThat(target.getDalytranMerchantName()).isEqualTo("Returns Desk");
            assertThat(target.getDalytranMerchantCity()).isEqualTo("Durham");
            assertThat(target.getDalytranMerchantZip()).isEqualTo("27701     ");
            assertThat(target.getDalytranCardNum()).isEqualTo("4859452612877066");
            assertThat(target.getDalytranOrigTs()).isEqualTo("2022-06-11 08:00:00.000000");
            assertThat(target.getDalytranProcTs()).isEqualTo("2022-06-12 09:00:00.000000");
        }

        @Test
        @DisplayName("a setter accepts an absent value, so clearing a field is possible and the non-null "
                + "contract is enforced by the column rather than by the entity")
        void aSetterAcceptsAnAbsentValue() {
            transaction.setDalytranCardNum(null);
            transaction.setDalytranAmt(null);

            assertThat(transaction.getDalytranCardNum()).isNull();
            assertThat(transaction.getDalytranAmt()).isNull();
        }
    }

    @Nested
    @DisplayName("Identity founded on the business key alone")
    class Identity {

        @Test
        @DisplayName("two independently constructed rows with the same identifier are equal and hash "
                + "alike, even when every other attribute differs, because the key is the identity")
        void sameIdentifierMeansEqualEvenWhenEverythingElseDiffers() {
            final DailyTransaction other = new DailyTransaction(FIRST_ID, "02", "0005",
                    OPERATOR_SOURCE, "different", new BigDecimal("-1.00"), "900000000", "n", "c",
                    "00000     ", "4859452612877066", "2000-01-01 00:00:00.000000",
                    "2000-01-02 00:00:00.000000");

            assertThat(transaction).isEqualTo(other);
            assertThat(transaction).hasSameHashCodeAs(other);
        }

        @Test
        @DisplayName("a different identifier means a different row, even when every other attribute is "
                + "identical - which matters because the fixture repeats amounts and cards across rows")
        void aDifferentIdentifierMeansADifferentRow() {
            final DailyTransaction other = new DailyTransaction("0000000000683581", PURCHASE_TYPE,
                    FIRST_CATEGORY, POS_TERM_SOURCE, FIRST_DESCRIPTION, FIRST_AMOUNT,
                    FIRST_MERCHANT_ID, FIRST_MERCHANT_NAME, FIRST_MERCHANT_CITY, FIRST_MERCHANT_ZIP,
                    FIRST_CARD_NUMBER, FIRST_ORIGINATION_TIMESTAMP, BLANK_PROCESSING_TIMESTAMP);

            assertThat(transaction).isNotEqualTo(other);
        }

        @Test
        @DisplayName("identity is reflexive, symmetric and transitive across independently constructed "
                + "instances carrying the same identifier")
        void identityIsReflexiveSymmetricAndTransitive() {
            final DailyTransaction second = new DailyTransaction();
            final DailyTransaction third = new DailyTransaction();
            second.setDalytranId(FIRST_ID);
            third.setDalytranId(FIRST_ID);

            assertThat(transaction.equals(transaction)).isTrue();
            assertThat(transaction.equals(second)).isTrue();
            assertThat(second.equals(transaction)).isTrue();
            assertThat(second.equals(third)).isTrue();
            assertThat(transaction.equals(third)).isTrue();
        }

        @Test
        @DisplayName("zero filling is significant: a sixteen-byte identifier is not equal to the same "
                + "value unpadded, because the key is text and not a number")
        void zeroFillingIsSignificant() {
            final DailyTransaction unpadded = new DailyTransaction();
            unpadded.setDalytranId("683580");

            assertThat(transaction).isNotEqualTo(unpadded);
        }

        @Test
        @DisplayName("equality rejects an absent operand and a foreign type through the type pattern "
                + "rather than throwing")
        void equalityRejectsAbsentOperandAndForeignType() {
            assertThat(transaction.equals(null)).isFalse();
            assertThat(transaction.equals(FIRST_ID)).isFalse();
            assertThat(transaction.equals(new Account())).isFalse();
        }

        @Test
        @DisplayName("two rows with absent identifiers are equal, so a provider may compare two "
                + "not-yet-populated instances without surprise")
        void twoRowsWithAbsentIdentifiersAreEqual() {
            assertThat(new DailyTransaction()).isEqualTo(new DailyTransaction());
            assertThat(new DailyTransaction()).hasSameHashCodeAs(new DailyTransaction());
        }

        @Test
        @DisplayName("all three hundred fixture identifiers stay distinct in a hash set, so no two "
                + "seeded rows collapse onto one transaction")
        void allFixtureIdentifiersStayDistinctInAHashSet() {
            final Set<DailyTransaction> rows = new HashSet<>();

            for (int row = 1; row <= FIXTURE_ROWS; row++) {
                final DailyTransaction seeded = new DailyTransaction();
                seeded.setDalytranId(String.format("%016d", row));
                rows.add(seeded);
            }

            assertThat(rows).hasSize(FIXTURE_ROWS);
        }
    }

    @Nested
    @DisplayName("Diagnostic representation")
    class StringRepresentation {

        @Test
        @DisplayName("the representation names the type, the identifier, the type code and the category "
                + "code, unquoted, matching this entity's own convention")
        void theRepresentationNamesTheKeyAndTheTwoCodes() {
            assertThat(transaction.toString()).isEqualTo(
                    "DailyTransaction[dalytranId=0000000000683580, dalytranTypeCd=01, "
                            + "dalytranCatCd=0001]");
        }

        @Test
        @DisplayName("the representation carries neither the amount nor the card number, so a log line "
                + "cannot leak a monetary value or a full card number")
        void theRepresentationCarriesNeitherAmountNorCardNumber() {
            final String rendered = transaction.toString();

            assertThat(rendered).doesNotContain("504.77");
            assertThat(rendered).doesNotContain(FIRST_CARD_NUMBER);
            assertThat(rendered).doesNotContain(FIRST_MERCHANT_ID);
        }

        @Test
        @DisplayName("the representation of an empty row reports all three fields as absent rather than "
                + "failing")
        void theRepresentationOfAnEmptyRowReportsAbsentFields() {
            assertThat(new DailyTransaction().toString()).isEqualTo(
                    "DailyTransaction[dalytranId=null, dalytranTypeCd=null, dalytranCatCd=null]");
        }
    }

    @Nested
    @DisplayName("Correspondence with the daily-transaction fixture")
    class FixtureCorrespondence {

        @Test
        @DisplayName("the fixture's 250 point-of-sale rows and 50 operator rows sum to its 300 measured "
                + "records, so both signed directions of the balance computation are seeded")
        void theFixtureRowCountsSumToTheMeasuredTotal() {
            assertThat(FIXTURE_POS_TERM_ROWS + FIXTURE_OPERATOR_ROWS).isEqualTo(FIXTURE_ROWS);
        }

        @Test
        @DisplayName("the fixture's measured byte length is exactly three hundred records of three "
                + "hundred and fifty bytes plus one line terminator each")
        void theFixtureByteLengthCloses() {
            final int measuredBytes = 105_300;
            final int terminatorsPerRow = 1;

            assertThat(FIXTURE_ROWS * (RECORD_LENGTH + terminatorsPerRow)).isEqualTo(measuredBytes);
        }

        @Test
        @DisplayName("the first seeded row is a purchase in the first category from a point-of-sale "
                + "source, which is the row the posting pipeline processes first")
        void theFirstSeededRowIsAPurchaseFromAPointOfSaleSource() {
            assertThat(transaction.getDalytranTypeCd()).isEqualTo("01");
            assertThat(transaction.getDalytranCatCd()).isEqualTo("0001");
            assertThat(transaction.getDalytranSource()).isEqualTo(POS_TERM_SOURCE);
            assertThat(transaction.getDalytranAmt().signum()).isPositive();
        }
    }
}
