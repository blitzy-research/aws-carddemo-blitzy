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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.carddemo.domain.id.TransactionCategoryBalanceId;
import com.carddemo.domain.id.TransactionCategoryId;
import com.carddemo.support.SchemaColumnCatalog;
import com.carddemo.support.SeededRecordFixture;
import com.carddemo.util.ZonedDecimalCodec;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies {@link TransactionCategoryBalance}, the fifty-byte per-account, per-category balance record.
 *
 * <p><strong>The layout being preserved.</strong> {@code app/cpy/CVTRA01Y.cpy} declares a fifty-byte
 * record in five parts: an eleven-byte account identifier, a two-byte transaction type, a four-byte
 * transaction category, a signed balance of nine integer digits and two decimals, and a twenty-two-byte
 * filler. The first three fields form a named key group, and the cluster definition at
 * {@code app/jcl/TCATBALF.jcl} confirms the arithmetic independently with {@code KEYS(17 0)} and
 * {@code RECORDSIZE(50 50)}: eleven plus two plus four is the seventeen-byte key, and the key starts at
 * the front of the record.
 *
 * <p><strong>Why the key width is asserted rather than assumed.</strong> The copybook that declares this
 * key and the copybook behind {@link TransactionCategory} give their key groups the <em>same</em> legacy
 * name, yet one key is seventeen bytes over three components and the other is six bytes over two, and
 * this one leads with an account identifier the other does not contain at all. A translation that reached
 * for the wrong identifier class would still compile, so the distinction is proved here from three
 * independent directions: the copybook widths, the two cluster key lengths, and the primary-key column
 * lists the migration declares for the two tables. Decision log entry D-37 records the collision.
 *
 * <p><strong>Why the balance scale is load-bearing.</strong> The interest run reads this balance for every
 * row it processes and computes {@code (balance * rate) / 1200} into a field of this same nine-integer,
 * two-decimal shape. No rounding clause exists anywhere in the estate, so that store truncates toward
 * zero. A balance carried at the wrong scale, or rescaled by the entity on the way in or out, would move
 * the truncation point and change the resulting cent, so this suite pins the scale against the copybook,
 * against the migration and against a decode of the real seeded image - and pins that the entity itself
 * neither scales nor computes.
 *
 * <p><strong>Why the seeded composition is asserted.</strong> All fifty seeded rows carry a zero balance.
 * That is not an accident of the fixture, it is what makes the accrual paths reachable from seed data
 * alone alongside the disclosure-group rows, and it is why the entity must round-trip a zero at scale two
 * without normalising it away.
 *
 * <p><strong>Deliberately not asserted.</strong> Nothing here computes interest, decodes a record image
 * into an entity or exercises the foreign key. Those belong to the interest-calculation service, the
 * fixed-width record mapper and the repository integration tier respectively. This suite establishes only
 * that the record those behaviours read is shaped, keyed, scaled and seeded the way they require.
 */
@DisplayName("TransactionCategoryBalance — the fifty-byte category-balance record")
class TransactionCategoryBalanceTest {

    /** Relational table the entity maps to. */
    private static final String TABLE = "transaction_category_balance";

    /** The table behind the six-byte key that shares this record's legacy key-group name. */
    private static final String COLLIDING_TABLE = "transaction_category";

    /** {@code RECORDSIZE(50 50)} in the cluster definition. */
    private static final int RECORD_WIDTH = 50;

    /** {@code KEYS(17 0)} in {@code app/jcl/TCATBALF.jcl} — key length. */
    private static final int KEY_WIDTH = 17;

    /** {@code KEYS(6 0)} in {@code app/jcl/TRANCATG.jcl} — the colliding key's length. */
    private static final int COLLIDING_KEY_WIDTH = 6;

    /** The five copybook widths, in declaration order. */
    private static final List<Integer> COPYBOOK_WIDTHS = List.of(11, 2, 4, 11, 22);

    /** Zero-based offset of the account identifier. */
    private static final int OFFSET_ACCOUNT_ID = 0;

    /** Zero-based offset of the transaction type. */
    private static final int OFFSET_TYPE = 11;

    /** Zero-based offset of the transaction category. */
    private static final int OFFSET_CATEGORY = 13;

    /** Zero-based offset of the balance. */
    private static final int OFFSET_BALANCE = 17;

    /** Zero-based offset of the filler. */
    private static final int OFFSET_FILLER = 28;

    /** Width of the unmapped trailing filler. */
    private static final int FILLER_WIDTH = 22;

    /** Integer digits the balance declares. */
    private static final int BALANCE_INTEGER_DIGITS = 9;

    /** Decimal digits the balance declares. */
    private static final int BALANCE_DECIMAL_DIGITS = 2;

    /** The divisor the interest run applies to the product of this balance and a rate. */
    private static final int PERCENT_TO_MONTHLY_DIVISOR = 1200;

    /** Seeded records in {@code tcatbal.txt}. */
    private static final int SEEDED_RECORDS = 50;

    /** Measured size of the seeded fixture: fifty records of fifty bytes plus one terminator each. */
    private static final int SEEDED_BYTES = 2550;

    /** The mapped columns, in copybook order. */
    private static final List<String> MAPPED_COLUMNS =
            List.of("trancat_acct_id", "trancat_type_cd", "trancat_cd", "tran_cat_bal");

    /** The migration's tables, parsed once. */
    private static final SchemaColumnCatalog SCHEMA = SchemaColumnCatalog.load();

    /** The seeded category-balance file, loaded once at its declared width. */
    private static final SeededRecordFixture SEED =
            SeededRecordFixture.load("tcatbal.txt", RECORD_WIDTH);

    /**
     * Reads one seeded record's account identifier.
     *
     * @param ordinal the one-based record ordinal
     * @return the eleven-byte account identifier
     */
    private static String seededAccountId(final int ordinal) {
        return SEED.field(ordinal, OFFSET_ACCOUNT_ID, COPYBOOK_WIDTHS.get(0));
    }

    /**
     * Reads one seeded record's balance image.
     *
     * @param ordinal the one-based record ordinal
     * @return the eleven-character zoned balance image
     */
    private static String seededBalanceImage(final int ordinal) {
        return SEED.field(ordinal, OFFSET_BALANCE, COPYBOOK_WIDTHS.get(3));
    }

    /**
     * Decodes one seeded record's balance.
     *
     * @param ordinal the one-based record ordinal
     * @return the balance the record carries
     */
    private static BigDecimal seededBalance(final int ordinal) {
        return ZonedDecimalCodec.decode(
                seededBalanceImage(ordinal),
                ZonedDecimalCodec.CATEGORY_BALANCE_WIDTH,
                BALANCE_DECIMAL_DIGITS,
                "TRAN-CAT-BAL");
    }

    /**
     * Builds the entity one seeded record describes.
     *
     * @param ordinal the one-based record ordinal
     * @return the category-balance row the record describes
     */
    private static TransactionCategoryBalance balanceFromSeed(final int ordinal) {
        return new TransactionCategoryBalance(
                seededAccountId(ordinal),
                SEED.field(ordinal, OFFSET_TYPE, COPYBOOK_WIDTHS.get(1)),
                SEED.field(ordinal, OFFSET_CATEGORY, COPYBOOK_WIDTHS.get(2)),
                seededBalance(ordinal));
    }

    /**
     * Builds a reference row with a category code whose leading zeros matter.
     *
     * @return a fully populated row
     */
    private static TransactionCategoryBalance referenceRow() {
        return new TransactionCategoryBalance(
                "00000000001", "01", "0005", new BigDecimal("1234.56"));
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
        @DisplayName("the five copybook widths sum to the fifty bytes the cluster declares")
        void theWidthsSumToTheRecordSize() {
            assertThat(COPYBOOK_WIDTHS).hasSize(5);
            assertThat(COPYBOOK_WIDTHS.stream().mapToInt(Integer::intValue).sum())
                    .isEqualTo(RECORD_WIDTH);
        }

        @Test
        @DisplayName("each field begins where the preceding widths leave off")
        void eachFieldBeginsWhereThePrecedingWidthsLeaveOff() {
            final List<Integer> offsets = List.of(
                    OFFSET_ACCOUNT_ID, OFFSET_TYPE, OFFSET_CATEGORY, OFFSET_BALANCE, OFFSET_FILLER);

            int running = 0;
            for (int index = 0; index < COPYBOOK_WIDTHS.size(); index++) {
                assertThat(offsets.get(index))
                        .as("offset of field %d", index)
                        .isEqualTo(running);
                running += COPYBOOK_WIDTHS.get(index);
            }

            assertThat(running).isEqualTo(RECORD_WIDTH);
        }

        @Test
        @DisplayName("the first three fields form the seventeen-byte key the cluster declares")
        void theFirstThreeFieldsFormTheKey() {
            assertThat(COPYBOOK_WIDTHS.get(0) + COPYBOOK_WIDTHS.get(1) + COPYBOOK_WIDTHS.get(2))
                    .isEqualTo(KEY_WIDTH);
            assertThat(OFFSET_BALANCE)
                    .as("the key runs from the front of the record to where the balance begins")
                    .isEqualTo(KEY_WIDTH);
        }

        @Test
        @DisplayName("the balance occupies eleven bytes, being nine integer digits and two decimals")
        void theBalanceOccupiesElevenBytes() {
            assertThat(COPYBOOK_WIDTHS.get(3))
                    .isEqualTo(BALANCE_INTEGER_DIGITS + BALANCE_DECIMAL_DIGITS);
            assertThat(ZonedDecimalCodec.WIDTH_PIC_S9_09_V99).isEqualTo(COPYBOOK_WIDTHS.get(3));
            assertThat(ZonedDecimalCodec.CATEGORY_BALANCE_WIDTH)
                    .as("the codec names this width for this field specifically")
                    .isEqualTo(ZonedDecimalCodec.WIDTH_PIC_S9_09_V99);
        }

        @Test
        @DisplayName("the trailing twenty-two bytes are filler and are mapped to no column")
        void theTrailingBytesAreFillerAndUnmapped() {
            assertThat(COPYBOOK_WIDTHS.get(4)).isEqualTo(FILLER_WIDTH);
            assertThat(OFFSET_FILLER + FILLER_WIDTH).isEqualTo(RECORD_WIDTH);
            assertThat(SCHEMA.columnNames(TABLE)).hasSize(COPYBOOK_WIDTHS.size() - 1);
        }
    }

    // =================================================================================================
    // THE OVERLOADED KEY-GROUP NAME
    // =================================================================================================

    /**
     * Verifies that the seventeen-byte key modelled here stays distinct from the six-byte key that shares
     * its legacy key-group name.
     */
    @Nested
    @DisplayName("the overloaded key-group name")
    class OverloadedKeyGroupName {

        @Test
        @DisplayName("this key is seventeen bytes over three components, not six over two")
        void thisKeyIsSeventeenBytesOverThreeComponents() {
            assertThat(KEY_WIDTH).isEqualTo(11 + 2 + 4).isNotEqualTo(COLLIDING_KEY_WIDTH);
            assertThat(SCHEMA.primaryKeyColumns(TABLE)).hasSize(3);
            assertThat(SCHEMA.primaryKeyColumns(COLLIDING_TABLE)).hasSize(2);
        }

        @Test
        @DisplayName("the two cluster key widths are corroborated by the two primary keys' column widths")
        void theTwoKeyWidthsAreCorroboratedByTheSchema() {
            assertThat(declaredKeyWidth(TABLE)).isEqualTo(KEY_WIDTH);
            assertThat(declaredKeyWidth(COLLIDING_TABLE)).isEqualTo(COLLIDING_KEY_WIDTH);
        }

        @Test
        @DisplayName("the six-byte key is not a prefix of this one, because this one leads with an "
                + "account identifier the other does not carry at all")
        void theSixByteKeyIsNotAPrefixOfThisOne() {
            final List<String> thisKey = SCHEMA.primaryKeyColumns(TABLE);
            final List<String> collidingKey = SCHEMA.primaryKeyColumns(COLLIDING_TABLE);

            assertThat(thisKey.get(0)).isEqualTo("trancat_acct_id");
            assertThat(collidingKey).doesNotContain("trancat_acct_id");
            assertThat(thisKey).doesNotContainAnyElementsOf(collidingKey);
            assertThat(COPYBOOK_WIDTHS.get(0))
                    .as("the leading component alone is wider than the whole colliding key")
                    .isGreaterThan(COLLIDING_KEY_WIDTH);
        }

        @Test
        @DisplayName("the extracted key is the three-part identifier class and never the two-part one")
        void theExtractedKeyIsTheThreePartIdentifierClass() {
            final Object key = referenceRow().toId();

            assertThat(key).isInstanceOf(TransactionCategoryBalanceId.class);
            assertThat(key).isNotInstanceOf(TransactionCategoryId.class);
            assertThat(key).isNotEqualTo(new TransactionCategoryId("01", "0005"));
        }

        @Test
        @DisplayName("the two copybooks' prefix spellings are transcribed as found, not regularised")
        void thePrefixSpellingsAreTranscribedAsFound() {
            assertThat(SCHEMA.primaryKeyColumns(TABLE))
                    .as("this record runs the first two words together in its key components")
                    .allSatisfy(column -> assertThat(column).startsWith("trancat_"));
            assertThat(SCHEMA.columnNames(TABLE))
                    .as("yet its balance separates them, and both spellings are kept")
                    .contains("tran_cat_bal");
            assertThat(SCHEMA.primaryKeyColumns(COLLIDING_TABLE))
                    .as("the colliding record separates them throughout")
                    .allSatisfy(column -> assertThat(column).startsWith("tran_"));
        }

        /**
         * Sums the declared widths of a table's primary-key columns.
         *
         * @param table the table to measure
         * @return the total declared width of the primary key
         */
        private int declaredKeyWidth(final String table) {
            int total = 0;
            for (final String column : SCHEMA.primaryKeyColumns(table)) {
                total += SCHEMA.declaredWidth(table, column);
            }
            return total;
        }
    }

    // =================================================================================================
    // SCHEMA AGREEMENT
    // =================================================================================================

    /**
     * Verifies that the deployed migration describes the layout the copybook does.
     */
    @Nested
    @DisplayName("schema agreement")
    class SchemaAgreement {

        @Test
        @DisplayName("the table declares the four mapped columns in copybook order")
        void theTableDeclaresTheMappedColumnsInCopybookOrder() {
            assertThat(SCHEMA.columnNames(TABLE)).containsExactlyElementsOf(MAPPED_COLUMNS);
        }

        @Test
        @DisplayName("every mapped column matches its copybook width")
        void everyMappedColumnMatchesItsCopybookWidth() {
            for (int index = 0; index < MAPPED_COLUMNS.size(); index++) {
                assertThat(SCHEMA.declaredWidth(TABLE, MAPPED_COLUMNS.get(index)))
                        .as("declared width of %s", MAPPED_COLUMNS.get(index))
                        .isEqualTo(COPYBOOK_WIDTHS.get(index));
            }
        }

        @Test
        @DisplayName("the three key columns are bounded character columns of eleven, two and four")
        void theKeyColumnsAreBoundedCharacterColumns() {
            assertThat(SCHEMA.declaredType(TABLE, "trancat_acct_id")).isEqualTo("VARCHAR(11)");
            assertThat(SCHEMA.declaredType(TABLE, "trancat_type_cd")).isEqualTo("VARCHAR(2)");
            assertThat(SCHEMA.declaredType(TABLE, "trancat_cd")).isEqualTo("VARCHAR(4)");
        }

        @Test
        @DisplayName("the balance column carries eleven digits of precision and two of scale, matching "
                + "the copybook's nine integer digits and two decimals")
        void theBalanceColumnCarriesTheCopybookPrecisionAndScale() {
            assertThat(SCHEMA.declaredType(TABLE, "tran_cat_bal")).isEqualTo("NUMERIC(11,2)");
            assertThat(SCHEMA.declaredWidth(TABLE, "tran_cat_bal"))
                    .isEqualTo(BALANCE_INTEGER_DIGITS + BALANCE_DECIMAL_DIGITS);
            assertThat(SCHEMA.declaredScale(TABLE, "tran_cat_bal")).isEqualTo(BALANCE_DECIMAL_DIGITS);
        }

        @Test
        @DisplayName("the account and category columns are character rather than integer, so a seeded "
                + "identifier keeps its leading zeros and the stored key still matches the record image")
        void theDigitOnlyColumnsAreCharacterSoLeadingZerosSurvive() {
            final String seededAccount = seededAccountId(1);
            final String seededCategory = SEED.field(1, OFFSET_CATEGORY, COPYBOOK_WIDTHS.get(2));

            assertThat(seededAccount).startsWith("0").hasSize(COPYBOOK_WIDTHS.get(0));
            assertThat(seededCategory).startsWith("0").hasSize(COPYBOOK_WIDTHS.get(2));
            assertThat(Integer.toString(Integer.parseInt(seededCategory)))
                    .as("an integer column would have stored this code without its leading zeros")
                    .isNotEqualTo(seededCategory);
            assertThat(Long.toString(Long.parseLong(seededAccount)))
                    .as("and would have done the same to the account identifier")
                    .isNotEqualTo(seededAccount);
        }

        @Test
        @DisplayName("the primary key is all three key components in copybook order, and no surrogate or "
                + "version column exists")
        void thePrimaryKeyIsAllThreeComponentsInOrder() {
            assertThat(SCHEMA.primaryKeyColumns(TABLE)).containsExactly(
                    "trancat_acct_id", "trancat_type_cd", "trancat_cd");
            assertThat(SCHEMA.columnNames(TABLE))
                    .doesNotContain("id", "transaction_category_balance_id", "version");
        }

        @Test
        @DisplayName("every column is declared not null, so no row can carry an absent balance")
        void everyColumnIsDeclaredNotNull() {
            for (final String column : SCHEMA.columnNames(TABLE)) {
                assertThat(SCHEMA.isNullable(TABLE, column))
                        .as("nullability of %s", column)
                        .isFalse();
            }
        }

        @Test
        @DisplayName("no filler column exists, so the trailing twenty-two bytes are reconstructed rather "
                + "than stored")
        void noFillerColumnExists() {
            assertThat(SCHEMA.columnNames(TABLE))
                    .noneSatisfy(column -> assertThat(column).contains("filler"));
        }
    }

    // =================================================================================================
    // CONSTRUCTION AND ACCESS
    // =================================================================================================

    /**
     * Verifies that every field the constructor takes is the field the accessor returns, and that no
     * mutator transforms what it is given.
     */
    @Nested
    @DisplayName("construction and access")
    class ConstructionAndAccess {

        @Test
        @DisplayName("every constructor argument reaches its own accessor")
        void everyConstructorArgumentReachesItsAccessor() {
            final TransactionCategoryBalance row = referenceRow();

            assertThat(row.getTrancatAcctId()).isEqualTo("00000000001");
            assertThat(row.getTrancatTypeCd()).isEqualTo("01");
            assertThat(row.getTrancatCd()).isEqualTo("0005");
            assertThat(row.getTranCatBal()).isEqualByComparingTo("1234.56");
        }

        @Test
        @DisplayName("a category code of 0005 survives construction and read-back unchanged")
        void aZeroFilledCategoryCodeSurvivesUnchanged() {
            assertThat(referenceRow().getTrancatCd()).isEqualTo("0005").hasSize(4);
        }

        @Test
        @DisplayName("every mutator stores exactly what it is given, without trimming or padding")
        void everyMutatorStoresExactlyWhatItIsGiven() {
            final TransactionCategoryBalance row = referenceRow();

            row.setTrancatAcctId("  00000009  ");
            row.setTrancatTypeCd(" 7");
            row.setTrancatCd("0 6 ");
            row.setTranCatBal(new BigDecimal("-0.01"));

            assertThat(row.getTrancatAcctId()).isEqualTo("  00000009  ");
            assertThat(row.getTrancatTypeCd()).isEqualTo(" 7");
            assertThat(row.getTrancatCd()).isEqualTo("0 6 ");
            assertThat(row.getTranCatBal()).isEqualTo(new BigDecimal("-0.01"));
        }

        @Test
        @DisplayName("an absent value is stored as absent rather than defaulted")
        void anAbsentValueIsStoredAsAbsent() {
            final TransactionCategoryBalance row = referenceRow();

            row.setTrancatAcctId(null);
            row.setTrancatTypeCd(null);
            row.setTrancatCd(null);
            row.setTranCatBal(null);

            assertThat(row.getTrancatAcctId()).isNull();
            assertThat(row.getTrancatTypeCd()).isNull();
            assertThat(row.getTrancatCd()).isNull();
            assertThat(row.getTranCatBal()).isNull();
        }

        @Test
        @DisplayName("the balance is left unset rather than seeded with a zero, so an unpopulated row "
                + "stays distinguishable from a genuine zero balance")
        void theBalanceIsLeftUnsetRatherThanSeededWithZero() {
            final TransactionCategoryBalance unpopulated = new TransactionCategoryBalance(
                    null, null, null, null);

            assertThat(unpopulated.getTranCatBal()).isNull();
            assertThat(balanceFromSeed(1).getTranCatBal())
                    .as("a seeded row carries a genuine zero, which is a different thing")
                    .isNotNull()
                    .isEqualByComparingTo(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("the persistence constructor leaves every field absent, because the provider "
                + "assigns state only after the instance exists")
        void thePersistenceConstructorLeavesEveryFieldAbsent() {
            final TransactionCategoryBalance row = new TransactionCategoryBalance();

            assertThat(row.getTrancatAcctId()).isNull();
            assertThat(row.getTrancatTypeCd()).isNull();
            assertThat(row.getTrancatCd()).isNull();
            assertThat(row.getTranCatBal()).isNull();
        }

        @Test
        @DisplayName("a row raised the way the provider raises one reaches the reference state through "
                + "its mutators alone")
        void aProviderRaisedRowReachesTheReferenceStateThroughItsMutators() {
            final TransactionCategoryBalance row = new TransactionCategoryBalance();
            row.setTrancatAcctId("00000000001");
            row.setTrancatTypeCd("01");
            row.setTrancatCd("0005");
            row.setTranCatBal(new BigDecimal("0.00"));

            assertThat(row)
                    .as("hydration by mutator is indistinguishable from construction by argument")
                    .isEqualTo(referenceRow());
            assertThat(row.getTranCatBal()).isEqualByComparingTo("0.00");
            assertThat(row.getTranCatBal().scale()).isEqualTo(2);
        }
    }

    // =================================================================================================
    // BALANCE FIDELITY
    // =================================================================================================

    /**
     * Verifies that the balance is carried exactly and that the entity contributes no arithmetic.
     */
    @Nested
    @DisplayName("balance fidelity")
    class BalanceFidelity {

        @Test
        @DisplayName("the balance is an exact decimal carried at the scale it was given")
        void theBalanceIsAnExactDecimalAtTheGivenScale() {
            final TransactionCategoryBalance row = new TransactionCategoryBalance(
                    "00000000001", "01", "0001", new BigDecimal("0.00"));

            assertThat(row.getTranCatBal().scale()).isEqualTo(BALANCE_DECIMAL_DIGITS);
            assertThat(row.getTranCatBal()).hasToString("0.00");
        }

        @Test
        @DisplayName("a value handed in at another scale is neither rescaled nor rejected, because "
                + "scaling belongs to the codec and not to the carrier")
        void aValueAtAnotherScaleIsNeitherRescaledNorRejected() {
            final TransactionCategoryBalance row = referenceRow();

            row.setTranCatBal(new BigDecimal("1.005"));

            assertThat(row.getTranCatBal().scale())
                    .as("the entity stored the scale it was handed")
                    .isEqualTo(3);
            assertThat(row.getTranCatBal()).hasToString("1.005");
        }

        @Test
        @DisplayName("the one truncating scale policy lives in the codec, and it truncates toward zero "
                + "rather than rounding")
        void theTruncatingPolicyLivesInTheCodec() {
            assertThat(ZonedDecimalCodec.COBOL_TRUNCATION_MODE).isEqualTo(RoundingMode.DOWN);
            assertThat(ZonedDecimalCodec.MONETARY_SCALE).isEqualTo(BALANCE_DECIMAL_DIGITS);
            assertThat(ZonedDecimalCodec.toScale(new BigDecimal("1.009"), BALANCE_DECIMAL_DIGITS))
                    .isEqualTo(new BigDecimal("1.00"));
            assertThat(ZonedDecimalCodec.toScale(new BigDecimal("-1.009"), BALANCE_DECIMAL_DIGITS))
                    .isEqualTo(new BigDecimal("-1.00"));
        }

        @Test
        @DisplayName("the field is signed, so a negative balance is carried as readily as a positive one")
        void theFieldIsSignedSoNegativeBalancesAreCarried() {
            final TransactionCategoryBalance row = referenceRow();

            row.setTranCatBal(new BigDecimal("-999999999.99"));

            assertThat(row.getTranCatBal()).isEqualByComparingTo("-999999999.99");
            assertThat(row.getTranCatBal().precision())
                    .as("the widest value the copybook admits still fits the declared precision")
                    .isLessThanOrEqualTo(BALANCE_INTEGER_DIGITS + BALANCE_DECIMAL_DIGITS);
        }

        @Test
        @DisplayName("the accrual divisor is a hundred times twelve, which is what makes the stored rate "
                + "a percentage per annum and this balance an unscaled amount")
        void theAccrualDivisorIsAHundredTimesTwelve() {
            assertThat(PERCENT_TO_MONTHLY_DIVISOR).isEqualTo(100 * 12);
        }

        @Test
        @DisplayName("multiplying before dividing is not the same as dividing before multiplying once "
                + "the store truncates, which is why the entity performs no arithmetic at all")
        void multiplyingBeforeDividingIsNotInterchangeable() {
            final BigDecimal balance = new BigDecimal("100.00");
            final BigDecimal rate = new BigDecimal("15.00");
            final BigDecimal divisor = BigDecimal.valueOf(PERCENT_TO_MONTHLY_DIVISOR);

            final BigDecimal faithful = balance.multiply(rate)
                    .divide(divisor, BALANCE_DECIMAL_DIGITS, RoundingMode.DOWN);
            final BigDecimal rearranged = balance
                    .multiply(rate.divide(divisor, BALANCE_DECIMAL_DIGITS, RoundingMode.DOWN))
                    .setScale(BALANCE_DECIMAL_DIGITS, RoundingMode.DOWN);

            assertThat(faithful).isEqualByComparingTo("1.25");
            assertThat(rearranged)
                    .as("pre-scaling the rate moves the truncation point and loses the whole amount")
                    .isEqualByComparingTo("1.00");
            assertThat(faithful).isNotEqualByComparingTo(rearranged);
        }
    }

    // =================================================================================================
    // SEEDED COMPOSITION
    // =================================================================================================

    /**
     * Verifies the composition of the named fixture the reference seed is built from.
     */
    @Nested
    @DisplayName("seeded composition")
    class SeededComposition {

        @Test
        @DisplayName("the named fixture holds fifty records of fifty bytes, measuring 2,550 bytes")
        void theNamedFixtureHoldsFiftyRecords() {
            assertThat(SEED.fileName()).isEqualTo("tcatbal.txt");
            assertThat(SEED.recordWidth()).isEqualTo(RECORD_WIDTH);
            assertThat(SEED.recordCount()).isEqualTo(SEEDED_RECORDS);
            assertThat(SEED.impliedByteCount()).isEqualTo(SEEDED_BYTES);
        }

        @Test
        @DisplayName("every seeded record carries a zero balance, which is what makes the accrual "
                + "branches reachable from seed data alone")
        void everySeededRecordCarriesAZeroBalance() {
            for (int ordinal = 1; ordinal <= SEEDED_RECORDS; ordinal++) {
                assertThat(seededBalance(ordinal))
                        .as("balance of seeded record %d", ordinal)
                        .isEqualByComparingTo(BigDecimal.ZERO);
            }
        }

        @Test
        @DisplayName("the seeded balance image ends in the positive-zero overpunch, so the sign is folded "
                + "into the trailing byte rather than packed")
        void theSeededBalanceImageEndsInThePositiveZeroOverpunch() {
            final String image = seededBalanceImage(1);

            assertThat(image).hasSize(COPYBOOK_WIDTHS.get(3)).endsWith("{");
            assertThat(image.substring(0, COPYBOOK_WIDTHS.get(3) - 1))
                    .containsOnlyDigits()
                    .isEqualTo("0".repeat(COPYBOOK_WIDTHS.get(3) - 1));
        }

        @Test
        @DisplayName("every seeded record produces a distinct key, so all fifty rows can coexist under "
                + "the composite primary key")
        void everySeededRecordProducesADistinctKey() {
            final Set<TransactionCategoryBalanceId> keys = new HashSet<>();
            final List<TransactionCategoryBalance> rows = new ArrayList<>();

            for (int ordinal = 1; ordinal <= SEEDED_RECORDS; ordinal++) {
                final TransactionCategoryBalance row = balanceFromSeed(ordinal);
                rows.add(row);
                keys.add(row.toId());
            }

            assertThat(rows).hasSize(SEEDED_RECORDS);
            assertThat(keys).hasSize(SEEDED_RECORDS);
            assertThat(new HashSet<>(rows)).hasSize(SEEDED_RECORDS);
        }

        @Test
        @DisplayName("every seeded key component fills its field exactly, so the seventeen-byte key "
                + "image reconstructs from the three stored components")
        void everySeededKeyComponentFillsItsField() {
            for (int ordinal = 1; ordinal <= SEEDED_RECORDS; ordinal++) {
                final TransactionCategoryBalance row = balanceFromSeed(ordinal);
                final String rebuilt =
                        row.getTrancatAcctId() + row.getTrancatTypeCd() + row.getTrancatCd();

                assertThat(rebuilt)
                        .as("rebuilt key of seeded record %d", ordinal)
                        .hasSize(KEY_WIDTH)
                        .isEqualTo(SEED.field(ordinal, OFFSET_ACCOUNT_ID, KEY_WIDTH));
            }
        }
    }

    // =================================================================================================
    // COMPOSITE-KEY IDENTITY
    // =================================================================================================

    /**
     * Verifies that equality and hashing follow the three key components and nothing else.
     */
    @Nested
    @DisplayName("composite-key identity")
    class CompositeKeyIdentity {

        @Test
        @DisplayName("two rows with the same three key components are equal and hash alike, whatever "
                + "their balances")
        void twoRowsWithTheSameKeyAreEqualWhateverTheBalance() {
            final TransactionCategoryBalance first = referenceRow();
            final TransactionCategoryBalance second = new TransactionCategoryBalance(
                    "00000000001", "01", "0005", new BigDecimal("-7654.32"));

            assertThat(first).isEqualTo(second);
            assertThat(first).hasSameHashCodeAs(second);
        }

        @Test
        @DisplayName("a difference in any one key component makes the rows unequal")
        void aDifferenceInAnyKeyComponentMakesRowsUnequal() {
            final TransactionCategoryBalance reference = referenceRow();

            assertThat(reference).isNotEqualTo(new TransactionCategoryBalance(
                    "00000000002", "01", "0005", new BigDecimal("1234.56")));
            assertThat(reference).isNotEqualTo(new TransactionCategoryBalance(
                    "00000000001", "02", "0005", new BigDecimal("1234.56")));
            assertThat(reference).isNotEqualTo(new TransactionCategoryBalance(
                    "00000000001", "01", "0006", new BigDecimal("1234.56")));
        }

        @Test
        @DisplayName("a zero-filled component is a different key from its shortened form")
        void aZeroFilledComponentDiffersFromItsShortenedForm() {
            assertThat(referenceRow()).isNotEqualTo(new TransactionCategoryBalance(
                    "1", "01", "5", new BigDecimal("1234.56")));
        }

        @Test
        @DisplayName("equality is reflexive and rejects an unrelated type and an absent reference")
        void equalityIsReflexiveAndTypeSafe() {
            final TransactionCategoryBalance row = referenceRow();

            assertThat(row).isEqualTo(row);
            assertThat(row).isNotEqualTo(null);
            assertThat(row).isNotEqualTo("00000000001");
            assertThat(row).isNotEqualTo(row.toId());
        }

        @Test
        @DisplayName("a row with absent components is comparable rather than fatal")
        void aRowWithAbsentComponentsIsComparable() {
            final TransactionCategoryBalance first =
                    new TransactionCategoryBalance(null, "01", null, null);
            final TransactionCategoryBalance second =
                    new TransactionCategoryBalance(null, "01", null, null);

            assertThat(first).isEqualTo(second);
            assertThat(first).hasSameHashCodeAs(second);
            assertThat(first).isNotEqualTo(referenceRow());
        }

        @Test
        @DisplayName("mutating the balance leaves equality and hash untouched, so a row already stored "
                + "in a set stays findable after a flush rewrites it")
        void mutatingTheBalanceLeavesIdentityUntouched() {
            final TransactionCategoryBalance row = referenceRow();
            final Set<TransactionCategoryBalance> stored = new HashSet<>();
            stored.add(row);
            final int hashBefore = row.hashCode();

            row.setTranCatBal(new BigDecimal("999999999.99"));

            assertThat(row.hashCode()).isEqualTo(hashBefore);
            assertThat(stored).contains(row);
            assertThat(stored.contains(referenceRow()))
                    .as("and an equal row built afresh still finds it")
                    .isTrue();
        }

        @Test
        @DisplayName("mutating a key component does change identity, which is why a key component is "
                + "never rewritten on a managed row")
        void mutatingAKeyComponentDoesChangeIdentity() {
            final TransactionCategoryBalance row = referenceRow();
            final int hashBefore = row.hashCode();

            row.setTrancatCd("0006");

            assertThat(row.hashCode()).isNotEqualTo(hashBefore);
            assertThat(row).isNotEqualTo(referenceRow());
        }

        @Test
        @DisplayName("the row works as a map key, which is what an identity map relies on")
        void theRowWorksAsAMapKey() {
            final Map<TransactionCategoryBalance, String> index = new HashMap<>();
            index.put(referenceRow(), "first");

            assertThat(index.get(referenceRow())).isEqualTo("first");
            assertThat(index.get(new TransactionCategoryBalance(
                    "00000000001", "01", "0006", new BigDecimal("1234.56")))).isNull();
        }
    }

    // =================================================================================================
    // THE EXTRACTED KEY
    // =================================================================================================

    /**
     * Verifies the convenience projection onto the identifier class the entity binds.
     */
    @Nested
    @DisplayName("the extracted key")
    class TheExtractedKey {

        @Test
        @DisplayName("the extracted key equals one constructed from the same three components")
        void theExtractedKeyEqualsOneConstructedDirectly() {
            assertThat(referenceRow().toId())
                    .isEqualTo(new TransactionCategoryBalanceId("00000000001", "01", "0005"));
        }

        @Test
        @DisplayName("the extracted key reports the three components verbatim, in key order")
        void theExtractedKeyReportsTheComponentsVerbatim() {
            final TransactionCategoryBalanceId key = referenceRow().toId();

            assertThat(key.getTrancatAcctId()).isEqualTo("00000000001");
            assertThat(key.getTrancatTypeCd()).isEqualTo("01");
            assertThat(key.getTrancatCd()).isEqualTo("0005");
        }

        @Test
        @DisplayName("a fresh key is produced on each call and none is retained")
        void aFreshKeyIsProducedOnEachCall() {
            final TransactionCategoryBalance row = referenceRow();

            assertThat(row.toId()).isNotSameAs(row.toId()).isEqualTo(row.toId());
        }

        @Test
        @DisplayName("the extracted key tracks a mutated key component")
        void theExtractedKeyTracksAMutatedComponent() {
            final TransactionCategoryBalance row = referenceRow();
            row.setTrancatCd("0006");

            assertThat(row.toId())
                    .isEqualTo(new TransactionCategoryBalanceId("00000000001", "01", "0006"));
        }

        @Test
        @DisplayName("an unpopulated row extracts a key of absent components rather than failing")
        void anUnpopulatedRowExtractsAKeyOfAbsentComponents() {
            final TransactionCategoryBalanceId key =
                    new TransactionCategoryBalance(null, null, null, null).toId();

            assertThat(key.getTrancatAcctId()).isNull();
            assertThat(key.getTrancatTypeCd()).isNull();
            assertThat(key.getTrancatCd()).isNull();
        }

        @Test
        @DisplayName("a row raised by the persistence constructor extracts a key of absent components, "
                + "so the projection is safe before the provider assigns state")
        void aProviderRaisedRowExtractsAKeyOfAbsentComponents() {
            final TransactionCategoryBalanceId key = new TransactionCategoryBalance().toId();

            assertThat(key.getTrancatAcctId()).isNull();
            assertThat(key.getTrancatTypeCd()).isNull();
            assertThat(key.getTrancatCd()).isNull();
        }
    }

    // =================================================================================================
    // DIAGNOSTIC REPRESENTATION
    // =================================================================================================

    /**
     * Verifies that the diagnostic rendering names the key and withholds the balance.
     */
    @Nested
    @DisplayName("diagnostic representation")
    class DiagnosticRepresentation {

        @Test
        @DisplayName("the rendering names the type and quotes all three key components as stored")
        void theRenderingNamesTheTypeAndQuotesTheKeyComponents() {
            assertThat(referenceRow()).hasToString(
                    "TransactionCategoryBalance[trancatAcctId='00000000001', "
                            + "trancatTypeCd='01', trancatCd='0005']");
        }

        @Test
        @DisplayName("the balance never appears, because it is financial data")
        void theBalanceNeverAppears() {
            final TransactionCategoryBalance row = new TransactionCategoryBalance(
                    "00000000001", "01", "0005", new BigDecimal("87654321.99"));

            assertThat(row.toString()).doesNotContain("87654321").doesNotContain("tranCatBal");
        }

        @Test
        @DisplayName("an unpopulated row renders without failing")
        void anUnpopulatedRowRendersWithoutFailing() {
            assertThat(new TransactionCategoryBalance(null, null, null, null)).hasToString(
                    "TransactionCategoryBalance[trancatAcctId='null', "
                            + "trancatTypeCd='null', trancatCd='null']");
        }
    }
}
