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

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

import com.carddemo.domain.enums.TransactionSourceType;
import com.carddemo.support.SchemaColumnCatalog;
import com.carddemo.support.SeededRecordFixture;
import com.carddemo.util.ZonedDecimalCodec;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies {@link DailyTransaction}, the three-hundred-and-fifty-byte daily-transaction record that
 * drives the posting run.
 *
 * <p><strong>The layout being preserved.</strong> {@code app/cpy/CVTRA06Y.cpy} declares fourteen fields
 * over three hundred and fifty bytes, and is byte-for-byte identical to the posted-transaction layout of
 * {@code app/cpy/CVTRA05Y.cpy} apart from the field-name prefix. The two are kept as separate types
 * because they are separate datasets with separate lifecycles: the daily file is the posting run's input
 * and its processing timestamp arrives blank, while the posted file is its output and carries one.
 *
 * <p><strong>Why the amount is eleven wide with a scale of two.</strong> A signed picture of nine integer
 * digits and two decimal digits needs eleven significant digits, so the relational column is declared
 * with a precision of eleven and a scale of two. This is one digit narrower than the account amounts, and
 * the difference is asserted rather than assumed because a shared amount width would be an easy and
 * invisible mistake.
 *
 * <p><strong>The sign lives in the last byte.</strong> The amount is a zoned decimal, so the final byte
 * of the eleven-byte image carries both the low-order digit and the sign: an open brace or a letter from
 * A to I for a positive value, a close brace or a letter from J to R for a negative one. In the seeded
 * file the two hundred and fifty point-of-sale purchases all end in a positive overpunch and the fifty
 * operator returns all end in a negative one, so the seed exercises both signed directions. That
 * correlation is exact and this suite asserts it, because a decoder that ignored the overpunch would turn
 * every return into a purchase and the posting run's balance arithmetic would silently invert.
 *
 * <p><strong>Two blank slots that are not defects.</strong> Every seeded record leaves the
 * twenty-six-byte processing timestamp blank and the twenty-byte trailing filler blank. The processing
 * timestamp is blank because the posting run is what fills it; the filler is never mapped at all. Both
 * are asserted so that a future fixture change is noticed.
 *
 * <p><strong>Why the seed cannot exercise a date window.</strong> All three hundred records carry the
 * same origination timestamp. A report test that filtered on a date range and expected a subset would
 * therefore pass or fail for reasons unrelated to the filter, so the date-window behaviour has to be
 * driven from a constructed fixture. This suite records the fact rather than papering over it.
 *
 * <p><strong>Deliberately not asserted.</strong> Nothing here validates a card number against the cross
 * reference, checks an account for an overlimit condition or assigns a reject reason; all three belong to
 * the posting service and its validation processor. Nothing writes a processing timestamp, because the
 * record arrives without one.
 */
@DisplayName("DailyTransaction — the three-hundred-and-fifty-byte posting input record")
class DailyTransactionTest {

    /** Relational table the entity maps to. */
    private static final String TABLE = "daily_transaction";

    /** Fixture holding the three hundred seeded daily transactions. */
    private static final String FIXTURE_FILE = "dailytran.txt";

    /** Record width the copybook declares. */
    private static final int RECORD_WIDTH = 350;

    /** Width of the record's leading identifier, which is also its key. */
    private static final int KEY_WIDTH = 16;

    /** Integer digits of {@code PIC S9(09)V99}. */
    private static final int AMOUNT_INTEGER_DIGITS = 9;

    /** Decimal digits of {@code PIC S9(09)V99}. */
    private static final int AMOUNT_DECIMAL_DIGITS = 2;

    /** Width of the unmapped trailing filler. */
    private static final int FILLER_WIDTH = 20;

    /** Records the seeded daily fixture holds. */
    private static final int SEEDED_RECORDS = 300;

    /** Seeded point-of-sale purchases. */
    private static final int SEEDED_PURCHASES = 250;

    /** Seeded operator returns. */
    private static final int SEEDED_RETURNS = 50;

    /** Distinct card numbers the seeded transactions reference. */
    private static final int SEEDED_DISTINCT_CARDS = 50;

    /** Transaction type the seeded purchases carry. */
    private static final String PURCHASE_TYPE_CODE = "01";

    /** Transaction type the seeded returns carry. */
    private static final String RETURN_TYPE_CODE = "03";

    /** The single origination timestamp every seeded record carries. */
    private static final String SEEDED_ORIGINATION_TIMESTAMP = "2022-06-10 19:27:53.000000";

    /** The fourteen copybook widths, in declaration order. */
    private static final List<Integer> COPYBOOK_WIDTHS =
            List.of(16, 2, 4, 10, 100, 11, 9, 50, 50, 10, 16, 26, 26, 20);

    /** Zero-based offset of each mapped field, derived from the copybook widths. */
    private static final int OFFSET_ID = 0;
    private static final int OFFSET_TYPE_CD = 16;
    private static final int OFFSET_CAT_CD = 18;
    private static final int OFFSET_SOURCE = 22;
    private static final int OFFSET_DESC = 32;
    private static final int OFFSET_AMT = 132;
    private static final int OFFSET_MERCHANT_ID = 143;
    private static final int OFFSET_MERCHANT_NAME = 152;
    private static final int OFFSET_MERCHANT_CITY = 202;
    private static final int OFFSET_MERCHANT_ZIP = 252;
    private static final int OFFSET_CARD_NUM = 262;
    private static final int OFFSET_ORIG_TS = 278;
    private static final int OFFSET_PROC_TS = 304;
    private static final int OFFSET_FILLER = 330;

    /** The migration's daily-transaction table, parsed once. */
    private static final SchemaColumnCatalog SCHEMA = SchemaColumnCatalog.load();

    /** The seeded daily fixture, loaded once. */
    private static final SeededRecordFixture FIXTURE =
            SeededRecordFixture.load(FIXTURE_FILE, RECORD_WIDTH);

    /**
     * Builds a daily transaction from one seeded record by slicing the copybook offsets.
     *
     * @param ordinal the one-based record position
     * @return the transaction the record describes
     */
    private static DailyTransaction transactionFromSeededRecord(final int ordinal) {
        return new DailyTransaction(
                FIXTURE.field(ordinal, OFFSET_ID, KEY_WIDTH),
                FIXTURE.field(ordinal, OFFSET_TYPE_CD, 2),
                FIXTURE.field(ordinal, OFFSET_CAT_CD, 4),
                FIXTURE.field(ordinal, OFFSET_SOURCE, 10),
                FIXTURE.field(ordinal, OFFSET_DESC, 100),
                ZonedDecimalCodec.decodeMonetary(
                        FIXTURE.field(ordinal, OFFSET_AMT, 11), 11, "DALYTRAN-AMT"),
                FIXTURE.field(ordinal, OFFSET_MERCHANT_ID, 9),
                FIXTURE.field(ordinal, OFFSET_MERCHANT_NAME, 50),
                FIXTURE.field(ordinal, OFFSET_MERCHANT_CITY, 50),
                FIXTURE.field(ordinal, OFFSET_MERCHANT_ZIP, 10),
                FIXTURE.field(ordinal, OFFSET_CARD_NUM, KEY_WIDTH),
                FIXTURE.field(ordinal, OFFSET_ORIG_TS, 26),
                FIXTURE.field(ordinal, OFFSET_PROC_TS, 26));
    }

    /**
     * Builds a transaction whose every field is distinguishable.
     *
     * @param dalytranId the identifier to give the transaction
     * @return a fully populated transaction
     */
    private static DailyTransaction sampleTransaction(final String dalytranId) {
        return new DailyTransaction(
                dalytranId, PURCHASE_TYPE_CODE, "0001", "POS TERM  ",
                "Purchase at Abshire-Lowe", new BigDecimal("504.77"),
                "800000000", "Abshire-Lowe", "North Enoshaven", "72112     ",
                "4859452612877065", SEEDED_ORIGINATION_TIMESTAMP, " ".repeat(26));
    }

    // =================================================================================================
    // RECORD LAYOUT
    // =================================================================================================

    /**
     * Verifies the copybook geometry the entity has to honour.
     */
    @Nested
    @DisplayName("record layout")
    class RecordLayout {

        @Test
        @DisplayName("the fourteen copybook widths sum to three hundred and fifty bytes")
        void theWidthsSumToTheRecordSize() {
            assertThat(COPYBOOK_WIDTHS).hasSize(14);
            assertThat(COPYBOOK_WIDTHS.stream().mapToInt(Integer::intValue).sum())
                    .isEqualTo(RECORD_WIDTH);
        }

        @Test
        @DisplayName("each mapped field starts where the preceding widths leave off")
        void eachFieldStartsWhereThePrecedingWidthsLeaveOff() {
            final List<Integer> expectedOffsets = List.of(
                    OFFSET_ID, OFFSET_TYPE_CD, OFFSET_CAT_CD, OFFSET_SOURCE, OFFSET_DESC, OFFSET_AMT,
                    OFFSET_MERCHANT_ID, OFFSET_MERCHANT_NAME, OFFSET_MERCHANT_CITY,
                    OFFSET_MERCHANT_ZIP, OFFSET_CARD_NUM, OFFSET_ORIG_TS, OFFSET_PROC_TS,
                    OFFSET_FILLER);

            int running = 0;
            for (int index = 0; index < COPYBOOK_WIDTHS.size(); index++) {
                assertThat(running)
                        .as("offset of field %d", index + 1)
                        .isEqualTo(expectedOffsets.get(index));
                running += COPYBOOK_WIDTHS.get(index);
            }
            assertThat(running).isEqualTo(RECORD_WIDTH);
        }

        @Test
        @DisplayName("the two timestamps sit at the offsets the external sort specifications address")
        void theTimestampsSitAtTheSortAddressedOffsets() {
            assertThat(OFFSET_CARD_NUM).isEqualTo(262);
            assertThat(OFFSET_ORIG_TS).isEqualTo(278);
            assertThat(OFFSET_PROC_TS).isEqualTo(304);
            assertThat(OFFSET_ORIG_TS + 26).isEqualTo(OFFSET_PROC_TS);
        }

        @Test
        @DisplayName("a signed nine-and-two picture needs eleven significant digits, one fewer than an "
                + "account amount")
        void aSignedNineAndTwoPictureNeedsElevenDigits() {
            assertThat(AMOUNT_INTEGER_DIGITS + AMOUNT_DECIMAL_DIGITS).isEqualTo(11);
            assertThat(COPYBOOK_WIDTHS.get(5)).isEqualTo(11);
            assertThat(ZonedDecimalCodec.WIDTH_PIC_S9_09_V99).isEqualTo(11);
            assertThat(ZonedDecimalCodec.DAILY_TRANSACTION_AMOUNT_WIDTH).isEqualTo(11);
            assertThat(ZonedDecimalCodec.DAILY_TRANSACTION_AMOUNT_WIDTH)
                    .as("a daily amount is one digit narrower than an account amount")
                    .isLessThan(ZonedDecimalCodec.ACCOUNT_AMOUNT_WIDTH);
        }

        @Test
        @DisplayName("every seeded record measures the declared width and the file measures its declared "
                + "byte count")
        void everySeededRecordMeasuresTheDeclaredWidth() {
            assertThat(FIXTURE.recordCount()).isEqualTo(SEEDED_RECORDS);
            assertThat(FIXTURE.recordWidth()).isEqualTo(RECORD_WIDTH);
            assertThat(FIXTURE.impliedByteCount()).isEqualTo(105_300);
            for (final String image : FIXTURE.records()) {
                assertThat(image).hasSize(RECORD_WIDTH);
            }
        }

        @Test
        @DisplayName("the trailing filler is blank in every seeded record")
        void theTrailingFillerIsBlankInEverySeededRecord() {
            for (int ordinal = 1; ordinal <= FIXTURE.recordCount(); ordinal++) {
                assertThat(FIXTURE.field(ordinal, OFFSET_FILLER, FILLER_WIDTH))
                        .as("filler of record %d", ordinal)
                        .isBlank()
                        .hasSize(FILLER_WIDTH);
            }
        }
    }

    // =================================================================================================
    // SCHEMA AGREEMENT
    // =================================================================================================

    /**
     * Verifies that the deployed migration describes the same layout the copybook does.
     */
    @Nested
    @DisplayName("schema agreement")
    class SchemaAgreement {

        @Test
        @DisplayName("the table declares the thirteen mapped columns in copybook order")
        void theTableDeclaresTheMappedColumnsInCopybookOrder() {
            assertThat(SCHEMA.columnNames(TABLE)).containsExactly(
                    "dalytran_id", "dalytran_type_cd", "dalytran_cat_cd", "dalytran_source",
                    "dalytran_desc", "dalytran_amt", "dalytran_merchant_id",
                    "dalytran_merchant_name", "dalytran_merchant_city", "dalytran_merchant_zip",
                    "dalytran_card_num", "dalytran_orig_ts", "dalytran_proc_ts");
            assertThat(SCHEMA.columnNames(TABLE)).hasSize(COPYBOOK_WIDTHS.size() - 1);
        }

        @Test
        @DisplayName("every mapped column is declared at its copybook width")
        void everyMappedColumnMatchesItsCopybookWidth() {
            final List<String> columns = SCHEMA.columnNames(TABLE);

            for (int index = 0; index < columns.size(); index++) {
                assertThat(SCHEMA.declaredWidth(TABLE, columns.get(index)))
                        .as("declared width of %s", columns.get(index))
                        .isEqualTo(COPYBOOK_WIDTHS.get(index));
            }
        }

        @Test
        @DisplayName("the amount is the only numeric column, at precision eleven and scale two")
        void theAmountIsTheOnlyNumericColumn() {
            assertThat(SCHEMA.declaredType(TABLE, "dalytran_amt")).isEqualTo("NUMERIC(11,2)");
            assertThat(SCHEMA.declaredWidth(TABLE, "dalytran_amt"))
                    .isEqualTo(AMOUNT_INTEGER_DIGITS + AMOUNT_DECIMAL_DIGITS);
            assertThat(SCHEMA.declaredScale(TABLE, "dalytran_amt")).isEqualTo(AMOUNT_DECIMAL_DIGITS);

            for (final String column : SCHEMA.columnNames(TABLE)) {
                if ("dalytran_amt".equals(column)) {
                    continue;
                }
                assertThat(SCHEMA.declaredType(TABLE, column))
                        .as("declared type of %s", column)
                        .startsWith("VARCHAR(");
            }
        }

        @Test
        @DisplayName("the primary key is the sixteen-byte identifier alone, so no surrogate is created")
        void thePrimaryKeyIsTheIdentifierAlone() {
            assertThat(SCHEMA.primaryKeyColumns(TABLE)).containsExactly("dalytran_id");
            assertThat(SCHEMA.columnNames(TABLE)).doesNotContain("id", "version");
        }

        @Test
        @DisplayName("the daily table and the posted table declare the same widths, because the two "
                + "layouts are byte-for-byte identical")
        void theDailyAndPostedTablesDeclareTheSameWidths() {
            final List<String> daily = SCHEMA.columnNames(TABLE);
            final List<String> posted = SCHEMA.columnNames("transaction");

            assertThat(daily).hasSameSizeAs(posted);
            for (int index = 0; index < daily.size(); index++) {
                assertThat(SCHEMA.declaredType(TABLE, daily.get(index)))
                        .as("declared type of %s against %s", daily.get(index), posted.get(index))
                        .isEqualTo(SCHEMA.declaredType("transaction", posted.get(index)));
            }
        }
    }

    // =================================================================================================
    // CONSTRUCTION AND ACCESS
    // =================================================================================================

    /**
     * Verifies that every field the constructor takes is the field the accessor returns.
     */
    @Nested
    @DisplayName("construction and access")
    class ConstructionAndAccess {

        @Test
        @DisplayName("every constructor argument reaches its own accessor")
        void everyConstructorArgumentReachesItsAccessor() {
            final DailyTransaction transaction = sampleTransaction("0000000000683580");

            assertThat(transaction.getDalytranId()).isEqualTo("0000000000683580");
            assertThat(transaction.getDalytranTypeCd()).isEqualTo(PURCHASE_TYPE_CODE);
            assertThat(transaction.getDalytranCatCd()).isEqualTo("0001");
            assertThat(transaction.getDalytranSource()).isEqualTo("POS TERM  ");
            assertThat(transaction.getDalytranDesc()).isEqualTo("Purchase at Abshire-Lowe");
            assertThat(transaction.getDalytranAmt()).isEqualTo(new BigDecimal("504.77"));
            assertThat(transaction.getDalytranMerchantId()).isEqualTo("800000000");
            assertThat(transaction.getDalytranMerchantName()).isEqualTo("Abshire-Lowe");
            assertThat(transaction.getDalytranMerchantCity()).isEqualTo("North Enoshaven");
            assertThat(transaction.getDalytranMerchantZip()).isEqualTo("72112     ");
            assertThat(transaction.getDalytranCardNum()).isEqualTo("4859452612877065");
            assertThat(transaction.getDalytranOrigTs()).isEqualTo(SEEDED_ORIGINATION_TIMESTAMP);
            assertThat(transaction.getDalytranProcTs()).isBlank().hasSize(26);
        }

        @Test
        @DisplayName("the two sixteen-byte identifiers do not swap, which same-width adjacency across "
                + "the record would hide")
        void theTwoSixteenByteIdentifiersDoNotSwap() {
            final DailyTransaction transaction = transactionFromSeededRecord(1);

            assertThat(transaction.getDalytranId()).isEqualTo("0000000000683580");
            assertThat(transaction.getDalytranCardNum()).isEqualTo("4859452612877065");
            assertThat(transaction.getDalytranId()).isNotEqualTo(transaction.getDalytranCardNum());
        }

        @Test
        @DisplayName("the two fifty-byte merchant fields do not swap either")
        void theTwoMerchantFieldsDoNotSwap() {
            final DailyTransaction transaction = transactionFromSeededRecord(1);

            assertThat(transaction.getDalytranMerchantName()).startsWith("Abshire-Lowe");
            assertThat(transaction.getDalytranMerchantCity()).startsWith("North Enoshaven");
        }

        @Test
        @DisplayName("every mutator replaces exactly the field it names")
        void everyMutatorReplacesTheFieldItNames() {
            final DailyTransaction transaction = sampleTransaction("0000000000683580");

            transaction.setDalytranId("9999999999999999");
            transaction.setDalytranTypeCd(RETURN_TYPE_CODE);
            transaction.setDalytranCatCd("0009");
            transaction.setDalytranSource("OPERATOR  ");
            transaction.setDalytranDesc("Return item");
            transaction.setDalytranAmt(new BigDecimal("-1.23"));
            transaction.setDalytranMerchantId("100000000");
            transaction.setDalytranMerchantName("Other Merchant");
            transaction.setDalytranMerchantCity("Other City");
            transaction.setDalytranMerchantZip("00001     ");
            transaction.setDalytranCardNum("1111111111111111");
            transaction.setDalytranOrigTs("2023-01-01 00:00:00.000000");
            transaction.setDalytranProcTs("2023-01-02 00:00:00.000000");

            assertThat(transaction.getDalytranId()).isEqualTo("9999999999999999");
            assertThat(transaction.getDalytranTypeCd()).isEqualTo(RETURN_TYPE_CODE);
            assertThat(transaction.getDalytranCatCd()).isEqualTo("0009");
            assertThat(transaction.getDalytranSource()).isEqualTo("OPERATOR  ");
            assertThat(transaction.getDalytranDesc()).isEqualTo("Return item");
            assertThat(transaction.getDalytranAmt()).isEqualTo(new BigDecimal("-1.23"));
            assertThat(transaction.getDalytranMerchantId()).isEqualTo("100000000");
            assertThat(transaction.getDalytranMerchantName()).isEqualTo("Other Merchant");
            assertThat(transaction.getDalytranMerchantCity()).isEqualTo("Other City");
            assertThat(transaction.getDalytranMerchantZip()).isEqualTo("00001     ");
            assertThat(transaction.getDalytranCardNum()).isEqualTo("1111111111111111");
            assertThat(transaction.getDalytranOrigTs()).isEqualTo("2023-01-01 00:00:00.000000");
            assertThat(transaction.getDalytranProcTs()).isEqualTo("2023-01-02 00:00:00.000000");
        }

        @Test
        @DisplayName("the persistence constructor leaves every field absent")
        void thePersistenceConstructorLeavesEveryFieldAbsent() {
            final DailyTransaction transaction = new DailyTransaction();

            assertThat(transaction.getDalytranId()).isNull();
            assertThat(transaction.getDalytranTypeCd()).isNull();
            assertThat(transaction.getDalytranCatCd()).isNull();
            assertThat(transaction.getDalytranSource()).isNull();
            assertThat(transaction.getDalytranDesc()).isNull();
            assertThat(transaction.getDalytranAmt()).isNull();
            assertThat(transaction.getDalytranMerchantId()).isNull();
            assertThat(transaction.getDalytranMerchantName()).isNull();
            assertThat(transaction.getDalytranMerchantCity()).isNull();
            assertThat(transaction.getDalytranMerchantZip()).isNull();
            assertThat(transaction.getDalytranCardNum()).isNull();
            assertThat(transaction.getDalytranOrigTs()).isNull();
            assertThat(transaction.getDalytranProcTs()).isNull();
        }

        @Test
        @DisplayName("the entity carries a value at every declared width, including the hundred-byte "
                + "description and the twenty-six-byte timestamps")
        void theEntityCarriesValuesAtTheDeclaredWidths() {
            final DailyTransaction transaction = new DailyTransaction(
                    "9".repeat(16), "99", "9999", "X".repeat(10), "D".repeat(100),
                    new BigDecimal("999999999.99"), "8".repeat(9), "M".repeat(50), "C".repeat(50),
                    "Z".repeat(10), "7".repeat(16), "O".repeat(26), "P".repeat(26));

            assertThat(transaction.getDalytranId()).hasSize(16);
            assertThat(transaction.getDalytranSource()).hasSize(10);
            assertThat(transaction.getDalytranDesc()).hasSize(100);
            assertThat(transaction.getDalytranMerchantId()).hasSize(9);
            assertThat(transaction.getDalytranMerchantName()).hasSize(50);
            assertThat(transaction.getDalytranMerchantCity()).hasSize(50);
            assertThat(transaction.getDalytranMerchantZip()).hasSize(10);
            assertThat(transaction.getDalytranCardNum()).hasSize(16);
            assertThat(transaction.getDalytranOrigTs()).hasSize(26);
            assertThat(transaction.getDalytranProcTs()).hasSize(26);
            assertThat(transaction.getDalytranAmt().precision())
                    .isEqualTo(AMOUNT_INTEGER_DIGITS + AMOUNT_DECIMAL_DIGITS);
        }
    }

    // =================================================================================================
    // AMOUNT FIDELITY
    // =================================================================================================

    /**
     * Verifies the amount's scale, sign and the truncating rescaling point.
     */
    @Nested
    @DisplayName("amount fidelity")
    class AmountFidelity {

        @Test
        @DisplayName("a decoded amount keeps its scale of two")
        void aDecodedAmountKeepsItsScaleOfTwo() {
            final DailyTransaction transaction = transactionFromSeededRecord(1);

            assertThat(transaction.getDalytranAmt().scale()).isEqualTo(AMOUNT_DECIMAL_DIGITS);
            assertThat(transaction.getDalytranAmt().toPlainString()).isEqualTo("504.77");
        }

        @Test
        @DisplayName("the entity is transparent: a value handed in at another scale comes back unchanged")
        void theEntityDoesNotRescale() {
            final DailyTransaction transaction = sampleTransaction("0000000000683580");

            transaction.setDalytranAmt(new BigDecimal("7"));
            assertThat(transaction.getDalytranAmt().scale()).isZero();

            transaction.setDalytranAmt(new BigDecimal("7.1234"));
            assertThat(transaction.getDalytranAmt().scale()).isEqualTo(4);
            assertThat(transaction.getDalytranAmt().toPlainString()).isEqualTo("7.1234");
        }

        @Test
        @DisplayName("the one rescaling point truncates toward zero in both directions")
        void theOneRescalingPointTruncates() {
            assertThat(ZonedDecimalCodec.COBOL_TRUNCATION_MODE).isEqualTo(RoundingMode.DOWN);
            assertThat(ZonedDecimalCodec.toMonetaryScale(new BigDecimal("504.779")).toPlainString())
                    .isEqualTo("504.77");
            assertThat(ZonedDecimalCodec.toMonetaryScale(new BigDecimal("-504.779")).toPlainString())
                    .isEqualTo("-504.77");
        }

        @Test
        @DisplayName("the widest amount the picture allows fits the declared precision exactly")
        void theWidestAmountFitsTheDeclaredPrecision() {
            final BigDecimal widest = new BigDecimal("999999999.99");

            assertThat(widest.precision()).isEqualTo(SCHEMA.declaredWidth(TABLE, "dalytran_amt"));
            assertThat(widest.scale()).isEqualTo(SCHEMA.declaredScale(TABLE, "dalytran_amt"));
        }

        @Test
        @DisplayName("a positive overpunch decodes positive and a negative overpunch decodes negative, "
                + "so a return cannot be read as a purchase")
        void theOverpunchDecidesTheSign() {
            assertThat(ZonedDecimalCodec.decodeMonetary("0000005047G", 11, "probe").toPlainString())
                    .isEqualTo("504.77");
            assertThat(ZonedDecimalCodec.decodeMonetary("0000005047P", 11, "probe").toPlainString())
                    .isEqualTo("-504.77");
            assertThat(ZonedDecimalCodec.decodeMonetary("0000000000{", 11, "probe").signum()).isZero();
            assertThat(ZonedDecimalCodec.decodeMonetary("0000000000}", 11, "probe").signum()).isZero();
        }
    }

    // =================================================================================================
    // SEEDED FILE COMPOSITION
    // =================================================================================================

    /**
     * Verifies what the seeded posting input actually exercises, and what it cannot.
     */
    @Nested
    @DisplayName("seeded file composition")
    class SeededFileComposition {

        /**
         * Maps every seeded record onto an entity.
         *
         * @return the three hundred seeded transactions, in file order
         */
        private List<DailyTransaction> allSeededTransactions() {
            final List<DailyTransaction> transactions = new ArrayList<>();
            for (int ordinal = 1; ordinal <= FIXTURE.recordCount(); ordinal++) {
                transactions.add(transactionFromSeededRecord(ordinal));
            }
            return transactions;
        }

        @Test
        @DisplayName("the file splits into two hundred and fifty purchases and fifty returns")
        void theFileSplitsIntoPurchasesAndReturns() {
            final List<DailyTransaction> transactions = allSeededTransactions();

            assertThat(transactions).hasSize(SEEDED_RECORDS);
            assertThat(transactions.stream()
                    .filter(transaction -> PURCHASE_TYPE_CODE.equals(transaction.getDalytranTypeCd()))
                    .count())
                    .isEqualTo(SEEDED_PURCHASES);
            assertThat(transactions.stream()
                    .filter(transaction -> RETURN_TYPE_CODE.equals(transaction.getDalytranTypeCd()))
                    .count())
                    .isEqualTo(SEEDED_RETURNS);
            assertThat(SEEDED_PURCHASES + SEEDED_RETURNS).isEqualTo(SEEDED_RECORDS);
        }

        @Test
        @DisplayName("the origin marker, the transaction type and the amount's sign agree on every "
                + "record, so a decoder that dropped the overpunch would invert every return")
        void theOriginTypeAndSignAgreeOnEveryRecord() {
            for (final DailyTransaction transaction : allSeededTransactions()) {
                final String source = transaction.getDalytranSource();
                final int signum = transaction.getDalytranAmt().signum();

                if (TransactionSourceType.POS_TERM.getValue().equals(source)) {
                    assertThat(transaction.getDalytranTypeCd())
                            .as("type of purchase %s", transaction.getDalytranId())
                            .isEqualTo(PURCHASE_TYPE_CODE);
                    assertThat(signum)
                            .as("sign of purchase %s", transaction.getDalytranId())
                            .isPositive();
                } else {
                    assertThat(source)
                            .as("origin of %s", transaction.getDalytranId())
                            .isEqualTo(TransactionSourceType.OPERATOR.getValue());
                    assertThat(transaction.getDalytranTypeCd())
                            .as("type of return %s", transaction.getDalytranId())
                            .isEqualTo(RETURN_TYPE_CODE);
                    assertThat(signum)
                            .as("sign of return %s", transaction.getDalytranId())
                            .isNegative();
                }
            }
        }

        @Test
        @DisplayName("both seeded origin markers resolve, so the whole file is readable through the "
                + "origin vocabulary")
        void bothSeededOriginMarkersResolve() {
            for (final DailyTransaction transaction : allSeededTransactions()) {
                assertThat(TransactionSourceType.fromValue(transaction.getDalytranSource()))
                        .as("origin of %s", transaction.getDalytranId())
                        .isPresent();
            }
        }

        @Test
        @DisplayName("every identifier is distinct and the three hundred transactions spread across "
                + "fifty cards")
        void everyIdentifierIsDistinctAcrossFiftyCards() {
            final List<DailyTransaction> transactions = allSeededTransactions();

            assertThat(transactions.stream().map(DailyTransaction::getDalytranId).distinct().toList())
                    .hasSize(SEEDED_RECORDS);
            assertThat(transactions.stream().map(DailyTransaction::getDalytranCardNum).distinct()
                    .toList())
                    .hasSize(SEEDED_DISTINCT_CARDS);
            assertThat(SEEDED_RECORDS / SEEDED_DISTINCT_CARDS).isEqualTo(6);
        }

        @Test
        @DisplayName("the processing timestamp is blank on every seeded record, because the posting run "
                + "is what fills it")
        void theProcessingTimestampIsBlankOnEveryRecord() {
            for (final DailyTransaction transaction : allSeededTransactions()) {
                assertThat(transaction.getDalytranProcTs())
                        .as("processing timestamp of %s", transaction.getDalytranId())
                        .isBlank()
                        .hasSize(26);
            }
        }

        @Test
        @DisplayName("every record carries the same origination timestamp, so a date-window filter "
                + "cannot be exercised from this fixture and needs a constructed one")
        void everyRecordCarriesTheSameOriginationTimestamp() {
            for (final DailyTransaction transaction : allSeededTransactions()) {
                assertThat(transaction.getDalytranOrigTs())
                        .as("origination timestamp of %s", transaction.getDalytranId())
                        .isEqualTo(SEEDED_ORIGINATION_TIMESTAMP)
                        .hasSize(26);
            }
        }

        @Test
        @DisplayName("every category code is the same, so the seed exercises one category only")
        void everyCategoryCodeIsTheSame() {
            for (final DailyTransaction transaction : allSeededTransactions()) {
                assertThat(transaction.getDalytranCatCd())
                        .as("category of %s", transaction.getDalytranId())
                        .isEqualTo("0001");
            }
        }
    }

    // =================================================================================================
    // BUSINESS-KEY IDENTITY
    // =================================================================================================

    /**
     * Verifies that identity is the record's own key and nothing else.
     */
    @Nested
    @DisplayName("business-key identity")
    class BusinessKeyIdentity {

        @Test
        @DisplayName("a transaction equals itself")
        void aTransactionEqualsItself() {
            final DailyTransaction transaction = sampleTransaction("0000000000683580");

            assertThat(transaction).isEqualTo(transaction);
            assertThat(transaction.hashCode()).isEqualTo(transaction.hashCode());
        }

        @Test
        @DisplayName("two transactions with the same identifier are equal even when every other field "
                + "differs")
        void sameIdentifierMeansEqualRegardlessOfTheRest() {
            final DailyTransaction left = sampleTransaction("0000000000683580");
            final DailyTransaction right = new DailyTransaction(
                    "0000000000683580", RETURN_TYPE_CODE, "0009", "OPERATOR  ",
                    "Return item", new BigDecimal("-1.00"),
                    "100000000", "Other", "Other", "00001     ",
                    "1111111111111111", "2023-01-01 00:00:00.000000",
                    "2023-01-02 00:00:00.000000");

            assertThat(left).isEqualTo(right);
            assertThat(right).isEqualTo(left);
            assertThat(left).hasSameHashCodeAs(right);
        }

        @Test
        @DisplayName("two transactions on the same card with different identifiers are unequal, which is "
                + "what keeps six transactions per card distinct")
        void differentIdentifierOnTheSameCardMeansUnequal() {
            final DailyTransaction left = sampleTransaction("0000000000683580");
            final DailyTransaction right = sampleTransaction("0000000000683581");

            assertThat(left.getDalytranCardNum()).isEqualTo(right.getDalytranCardNum());
            assertThat(left).isNotEqualTo(right);
            assertThat(right).isNotEqualTo(left);
        }

        @Test
        @DisplayName("equality is transitive across three transactions sharing an identifier")
        void equalityIsTransitive() {
            final DailyTransaction first = sampleTransaction("0000000000683580");
            final DailyTransaction second = sampleTransaction("0000000000683580");
            final DailyTransaction third = sampleTransaction("0000000000683580");

            assertThat(first).isEqualTo(second);
            assertThat(second).isEqualTo(third);
            assertThat(first).isEqualTo(third);
            assertThat(first).hasSameHashCodeAs(third);
        }

        @Test
        @DisplayName("a transaction is unequal to null and to an unrelated type")
        void aTransactionIsUnequalToNullAndToAnotherType() {
            final DailyTransaction transaction = sampleTransaction("0000000000683580");

            assertThat(transaction).isNotEqualTo(null);
            assertThat(transaction.equals("0000000000683580")).isFalse();
            assertThat(transaction).isNotEqualTo(new Object());
        }

        @Test
        @DisplayName("two transactions with an absent identifier are equal, because both keys are absent "
                + "rather than generated")
        void twoUnkeyedTransactionsAreEqual() {
            assertThat(new DailyTransaction()).isEqualTo(new DailyTransaction());
            assertThat(new DailyTransaction()).hasSameHashCodeAs(new DailyTransaction());
            assertThat(new DailyTransaction().getDalytranId()).isNull();
        }
    }

    // =================================================================================================
    // DIAGNOSTIC REPRESENTATION
    // =================================================================================================

    /**
     * Verifies the diagnostic string, including what it deliberately withholds.
     */
    @Nested
    @DisplayName("diagnostic representation")
    class DiagnosticRepresentation {

        @Test
        @DisplayName("the description names the type, the identifier, the transaction type and the "
                + "category")
        void theDescriptionNamesTheKeyAndClassification() {
            assertThat(sampleTransaction("0000000000683580").toString()).isEqualTo(
                    "DailyTransaction[dalytranId=0000000000683580, dalytranTypeCd=01, "
                            + "dalytranCatCd=0001]");
        }

        @Test
        @DisplayName("the description carries neither the card number nor the amount, so neither can "
                + "reach a log line through it")
        void theDescriptionCarriesNoCardNumberOrAmount() {
            final String description = sampleTransaction("0000000000683580").toString();

            assertThat(description)
                    .doesNotContain("4859452612877065")
                    .doesNotContain("504.77")
                    .doesNotContain("Abshire-Lowe");
        }

        @Test
        @DisplayName("an unkeyed transaction still describes itself rather than failing")
        void anUnkeyedTransactionStillDescribesItself() {
            assertThat(new DailyTransaction().toString()).isEqualTo(
                    "DailyTransaction[dalytranId=null, dalytranTypeCd=null, dalytranCatCd=null]");
        }
    }
}
