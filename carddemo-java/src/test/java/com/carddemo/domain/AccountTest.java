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

import com.carddemo.support.SchemaColumnCatalog;
import com.carddemo.support.SeededRecordFixture;
import com.carddemo.util.ZonedDecimalCodec;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies {@link Account}, the three-hundred-byte account record.
 *
 * <p><strong>The layout being preserved.</strong> {@code app/cpy/CVACT01Y.cpy} declares thirteen fields
 * over three hundred bytes: an eleven-digit identifier, a one-byte status, three signed
 * ten-and-two decimal amounts, three ten-byte dates, two more signed ten-and-two decimal amounts, a
 * ten-byte postal code, a ten-byte disclosure-group identifier and a hundred-and-seventy-eight-byte
 * filler. The cluster definition at {@code app/jcl/ACCTFILE.jcl} states the same geometry from the other
 * direction: {@code KEYS(11 0)} places an eleven-byte key at offset zero, and
 * {@code RECORDSIZE(300 300)} fixes the record at three hundred bytes.
 *
 * <p><strong>Three descriptions must agree.</strong> This suite compares the copybook widths it holds as
 * literal constants against the columns the deployed migration creates, and then drives the entity with
 * a record taken from the named account fixture. A width that is wrong in the entity, wrong in the
 * migration, or wrong in only one of them, fails.
 *
 * <p><strong>Why the five amounts are twelve wide with a scale of two.</strong> A signed picture of ten
 * integer digits and two decimal digits needs twelve significant digits in total, so the relational
 * column is declared with a precision of twelve and a scale of two. The suite asserts that arithmetic
 * rather than restating the number, so a copybook change of the integer part would be caught.
 *
 * <p><strong>Why the entity does not round.</strong> No arithmetic statement anywhere in the estate
 * carries a rounding clause, so every store into a two-decimal field truncates toward zero. Truncation
 * belongs to the one codec that converts a zoned image into a decimal; the entity itself must carry
 * whatever scale it is handed and must not quietly rescale, because a second rescaling point is a second
 * place for a rounding policy to drift. The suite asserts the entity is transparent, and separately
 * asserts that the codec's own mode is the truncating one.
 *
 * <p><strong>Why the identifier is the primary key.</strong> The sequential reader splits the record into
 * an eleven-digit key and a two-hundred-and-eighty-nine-byte remainder, so the key is a substring of the
 * record image rather than a value the database invents. A generated surrogate would break the
 * correspondence between the record image and the table row, so this suite proves no key is generated: a
 * default-constructed instance reports an absent identifier rather than a fresh one.
 *
 * <p><strong>A preserved misspelling.</strong> The copybook spells the expiry field
 * {@code ACCT-EXPIRAION-DATE} at line 11, missing a letter. The byte position and width are contractual
 * and are preserved exactly; the spelling is not, so the Java property and the relational column both
 * read correctly. The suite asserts the corrected spelling is what the schema declares and that the
 * misspelling appears nowhere in it.
 *
 * <p><strong>An observation about the seeded data.</strong> Every one of the fifty seeded account records
 * carries a disclosure-group-shaped value in the postal-code slot at offset one hundred and two, and
 * leaves the disclosure-group slot at offset one hundred and twelve blank. Both slots are ten bytes so
 * neither is malformed, but the consequence is worth recording: the interest run moves the
 * disclosure-group identifier into its rate-lookup key at {@code app/cbl/CBACT04C.cbl} line 210 and
 * falls back to the default group at line 437 when the lookup misses, so a blank identifier drives every
 * seeded account down the fallback path. The suite records the data fact and makes no claim beyond it.
 *
 * <p><strong>Deliberately not asserted.</strong> Nothing here validates a status value, a date format or
 * a credit limit, because the entity performs no validation &mdash; those rules live in the update
 * service and its request object, and asserting them here would invent a constraint the record does not
 * carry. Nothing writes to the version field, because the persistence provider owns it.
 */
@DisplayName("Account — the three-hundred-byte account record")
class AccountTest {

    /** Relational table the entity maps to. */
    private static final String TABLE = "account";

    /** Fixture holding the fifty seeded account records. */
    private static final String FIXTURE_FILE = "acctdata.txt";

    /** {@code RECORDSIZE(300 300)} in the cluster definition. */
    private static final int RECORD_WIDTH = 300;

    /** {@code KEYS(11 0)} — key length. */
    private static final int KEY_WIDTH = 11;

    /** {@code KEYS(11 0)} — key offset. */
    private static final int KEY_OFFSET = 0;

    /** Integer digits of {@code PIC S9(10)V99}. */
    private static final int AMOUNT_INTEGER_DIGITS = 10;

    /** Decimal digits of {@code PIC S9(10)V99}. */
    private static final int AMOUNT_DECIMAL_DIGITS = 2;

    /** Records the seeded account fixture holds. */
    private static final int SEEDED_RECORDS = 50;

    /** The thirteen copybook widths, in declaration order. */
    private static final List<Integer> COPYBOOK_WIDTHS =
            List.of(11, 1, 12, 12, 12, 10, 10, 10, 12, 12, 10, 10, 178);

    /** Zero-based offset of each mapped field, derived from the copybook widths. */
    private static final int OFFSET_ACCT_ID = 0;
    private static final int OFFSET_ACTIVE_STATUS = 11;
    private static final int OFFSET_CURR_BAL = 12;
    private static final int OFFSET_CREDIT_LIMIT = 24;
    private static final int OFFSET_CASH_CREDIT_LIMIT = 36;
    private static final int OFFSET_OPEN_DATE = 48;
    private static final int OFFSET_EXPIRATION_DATE = 58;
    private static final int OFFSET_REISSUE_DATE = 68;
    private static final int OFFSET_CURR_CYC_CREDIT = 78;
    private static final int OFFSET_CURR_CYC_DEBIT = 90;
    private static final int OFFSET_ADDR_ZIP = 102;
    private static final int OFFSET_GROUP_ID = 112;
    private static final int OFFSET_FILLER = 122;

    /** Width of the unmapped trailing filler. */
    private static final int FILLER_WIDTH = 178;

    /** The migration's account table, parsed once. */
    private static final SchemaColumnCatalog SCHEMA = SchemaColumnCatalog.load();

    /** The seeded account fixture, loaded once. */
    private static final SeededRecordFixture FIXTURE =
            SeededRecordFixture.load(FIXTURE_FILE, RECORD_WIDTH);

    /** The five amount columns, in copybook order. */
    private static final List<String> AMOUNT_COLUMNS = List.of(
            "acct_curr_bal", "acct_credit_limit", "acct_cash_credit_limit",
            "acct_curr_cyc_credit", "acct_curr_cyc_debit");

    /**
     * Builds an account whose every field is distinguishable, for identity and accessor assertions.
     *
     * @param acctId the identifier to give the account
     * @return a fully populated account
     */
    private static Account sampleAccount(final String acctId) {
        return new Account(
                acctId,
                "Y",
                new BigDecimal("194.00"),
                new BigDecimal("2020.00"),
                new BigDecimal("1020.00"),
                "2014-11-20",
                "2025-05-20",
                "2025-05-20",
                new BigDecimal("0.00"),
                new BigDecimal("0.00"),
                "A000000000",
                "          ");
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
        @DisplayName("the thirteen copybook widths sum to the three hundred bytes the cluster declares")
        void theWidthsSumToTheRecordSize() {
            assertThat(COPYBOOK_WIDTHS).hasSize(13);
            assertThat(COPYBOOK_WIDTHS.stream().mapToInt(Integer::intValue).sum())
                    .isEqualTo(RECORD_WIDTH);
        }

        @Test
        @DisplayName("the twelve mapped fields plus the filler account for the whole record")
        void theMappedFieldsPlusFillerAccountForTheRecord() {
            final int mapped = COPYBOOK_WIDTHS.stream().mapToInt(Integer::intValue).sum()
                    - FILLER_WIDTH;

            assertThat(mapped).isEqualTo(OFFSET_FILLER);
            assertThat(mapped + FILLER_WIDTH).isEqualTo(RECORD_WIDTH);
        }

        @Test
        @DisplayName("each mapped field starts where the preceding widths leave off")
        void eachFieldStartsWhereThePrecedingWidthsLeaveOff() {
            final List<Integer> expectedOffsets = List.of(
                    OFFSET_ACCT_ID, OFFSET_ACTIVE_STATUS, OFFSET_CURR_BAL, OFFSET_CREDIT_LIMIT,
                    OFFSET_CASH_CREDIT_LIMIT, OFFSET_OPEN_DATE, OFFSET_EXPIRATION_DATE,
                    OFFSET_REISSUE_DATE, OFFSET_CURR_CYC_CREDIT, OFFSET_CURR_CYC_DEBIT,
                    OFFSET_ADDR_ZIP, OFFSET_GROUP_ID, OFFSET_FILLER);

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
        @DisplayName("the key is the leading eleven bytes, as the cluster's key clause states")
        void theKeyIsTheLeadingElevenBytes() {
            assertThat(KEY_OFFSET).isEqualTo(OFFSET_ACCT_ID);
            assertThat(KEY_WIDTH).isEqualTo(COPYBOOK_WIDTHS.get(0));
            assertThat(KEY_OFFSET + KEY_WIDTH).isEqualTo(OFFSET_ACTIVE_STATUS);
        }

        @Test
        @DisplayName("a signed ten-and-two picture needs twelve significant digits")
        void aSignedTenAndTwoPictureNeedsTwelveDigits() {
            assertThat(AMOUNT_INTEGER_DIGITS + AMOUNT_DECIMAL_DIGITS).isEqualTo(12);
            assertThat(COPYBOOK_WIDTHS.get(2)).isEqualTo(12);
            assertThat(ZonedDecimalCodec.WIDTH_PIC_S9_10_V99).isEqualTo(12);
            assertThat(ZonedDecimalCodec.ACCOUNT_AMOUNT_WIDTH).isEqualTo(12);
        }

        @Test
        @DisplayName("every seeded record measures the declared three hundred bytes")
        void everySeededRecordMeasuresTheDeclaredWidth() {
            assertThat(FIXTURE.recordCount()).isEqualTo(SEEDED_RECORDS);
            assertThat(FIXTURE.recordWidth()).isEqualTo(RECORD_WIDTH);
            for (final String image : FIXTURE.records()) {
                assertThat(image).hasSize(RECORD_WIDTH);
            }
            assertThat(FIXTURE.impliedByteCount()).isEqualTo(15_050);
        }

        @Test
        @DisplayName("the trailing filler is blank in every seeded record, so nothing hides behind the "
                + "mapped fields")
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
        @DisplayName("the table declares the twelve mapped columns plus the optimistic-version column, "
                + "in copybook order")
        void theTableDeclaresTheMappedColumnsInCopybookOrder() {
            assertThat(SCHEMA.columnNames(TABLE)).containsExactly(
                    "acct_id", "acct_active_status", "acct_curr_bal", "acct_credit_limit",
                    "acct_cash_credit_limit", "acct_open_date", "acct_expiration_date",
                    "acct_reissue_date", "acct_curr_cyc_credit", "acct_curr_cyc_debit",
                    "acct_addr_zip", "acct_group_id", "version");
        }

        @Test
        @DisplayName("each character column is declared at its copybook width")
        void eachCharacterColumnIsDeclaredAtItsCopybookWidth() {
            assertThat(SCHEMA.declaredType(TABLE, "acct_id")).isEqualTo("VARCHAR(11)");
            assertThat(SCHEMA.declaredWidth(TABLE, "acct_id")).isEqualTo(COPYBOOK_WIDTHS.get(0));
            assertThat(SCHEMA.declaredWidth(TABLE, "acct_active_status"))
                    .isEqualTo(COPYBOOK_WIDTHS.get(1));
            assertThat(SCHEMA.declaredWidth(TABLE, "acct_open_date")).isEqualTo(COPYBOOK_WIDTHS.get(5));
            assertThat(SCHEMA.declaredWidth(TABLE, "acct_expiration_date"))
                    .isEqualTo(COPYBOOK_WIDTHS.get(6));
            assertThat(SCHEMA.declaredWidth(TABLE, "acct_reissue_date"))
                    .isEqualTo(COPYBOOK_WIDTHS.get(7));
            assertThat(SCHEMA.declaredWidth(TABLE, "acct_addr_zip")).isEqualTo(COPYBOOK_WIDTHS.get(10));
            assertThat(SCHEMA.declaredWidth(TABLE, "acct_group_id")).isEqualTo(COPYBOOK_WIDTHS.get(11));
        }

        @Test
        @DisplayName("all five amount columns are numeric at precision twelve and scale two")
        void allFiveAmountColumnsAreNumericAtTwelveAndTwo() {
            for (final String column : AMOUNT_COLUMNS) {
                assertThat(SCHEMA.declaredType(TABLE, column))
                        .as("declared type of %s", column)
                        .isEqualTo("NUMERIC(12,2)");
                assertThat(SCHEMA.declaredWidth(TABLE, column))
                        .as("precision of %s", column)
                        .isEqualTo(AMOUNT_INTEGER_DIGITS + AMOUNT_DECIMAL_DIGITS);
                assertThat(SCHEMA.declaredScale(TABLE, column))
                        .as("scale of %s", column)
                        .isEqualTo(AMOUNT_DECIMAL_DIGITS);
            }
            assertThat(AMOUNT_COLUMNS).hasSize(5);
        }

        @Test
        @DisplayName("the primary key is the eleven-byte identifier alone, so no surrogate is created")
        void thePrimaryKeyIsTheIdentifierAlone() {
            assertThat(SCHEMA.primaryKeyColumns(TABLE)).containsExactly("acct_id");
            assertThat(SCHEMA.columnNames(TABLE))
                    .doesNotContain("id")
                    .doesNotContain("account_id")
                    .doesNotContain("pk");
        }

        @Test
        @DisplayName("every mapped column is declared not null, matching a fixed-width record where no "
                + "field can be absent")
        void everyMappedColumnIsDeclaredNotNull() {
            for (final String column : SCHEMA.columnNames(TABLE)) {
                assertThat(SCHEMA.isNullable(TABLE, column))
                        .as("nullability of %s", column)
                        .isFalse();
            }
        }

        @Test
        @DisplayName("the optimistic-version column is a whole number rather than part of the record "
                + "layout")
        void theVersionColumnIsAWholeNumber() {
            assertThat(SCHEMA.declaredType(TABLE, "version")).isEqualTo("BIGINT");
            assertThat(SCHEMA.columnNames(TABLE))
                    .as("twelve mapped record fields plus the version column, and the filler is not "
                            + "mapped at all")
                    .hasSize(COPYBOOK_WIDTHS.size() - 1 + 1);
        }
    }

    // =================================================================================================
    // THE PRESERVED MISSPELLING
    // =================================================================================================

    /**
     * Verifies how the copybook's misspelled expiry field is carried forward.
     */
    @Nested
    @DisplayName("the preserved misspelling")
    class PreservedMisspelling {

        @Test
        @DisplayName("the expiry column is spelled correctly, because a column name is not part of the "
                + "record contract")
        void theExpiryColumnIsSpelledCorrectly() {
            assertThat(SCHEMA.columnNames(TABLE)).contains("acct_expiration_date");
            assertThat(SCHEMA.declaredWidth(TABLE, "acct_expiration_date")).isEqualTo(10);
        }

        @Test
        @DisplayName("the copybook's misspelling appears in no column name")
        void theMisspellingAppearsInNoColumnName() {
            for (final String column : SCHEMA.columnNames(TABLE)) {
                assertThat(column)
                        .as("column %s", column)
                        .doesNotContain("expiraion");
            }
        }

        @Test
        @DisplayName("the expiry field keeps its byte position and width, which is what the record "
                + "contract actually fixes")
        void theExpiryFieldKeepsItsBytePosition() {
            assertThat(OFFSET_EXPIRATION_DATE).isEqualTo(58);
            assertThat(COPYBOOK_WIDTHS.get(6)).isEqualTo(10);
            assertThat(OFFSET_EXPIRATION_DATE + COPYBOOK_WIDTHS.get(6))
                    .isEqualTo(OFFSET_REISSUE_DATE);
        }

        @Test
        @DisplayName("a seeded record's expiry slot reads as a ten-character date at that position")
        void aSeededRecordsExpirySlotReadsAsADate() {
            final String expiry = FIXTURE.field(1, OFFSET_EXPIRATION_DATE, 10);

            assertThat(expiry).hasSize(10).isEqualTo("2025-05-20");
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
            final Account account = sampleAccount("00000000001");

            assertThat(account.getAcctId()).isEqualTo("00000000001");
            assertThat(account.getAcctActiveStatus()).isEqualTo("Y");
            assertThat(account.getAcctCurrBal()).isEqualTo(new BigDecimal("194.00"));
            assertThat(account.getAcctCreditLimit()).isEqualTo(new BigDecimal("2020.00"));
            assertThat(account.getAcctCashCreditLimit()).isEqualTo(new BigDecimal("1020.00"));
            assertThat(account.getAcctOpenDate()).isEqualTo("2014-11-20");
            assertThat(account.getAcctExpirationDate()).isEqualTo("2025-05-20");
            assertThat(account.getAcctReissueDate()).isEqualTo("2025-05-20");
            assertThat(account.getAcctCurrCycCredit()).isEqualTo(new BigDecimal("0.00"));
            assertThat(account.getAcctCurrCycDebit()).isEqualTo(new BigDecimal("0.00"));
            assertThat(account.getAcctAddrZip()).isEqualTo("A000000000");
            assertThat(account.getAcctGroupId()).isEqualTo("          ");
        }

        @Test
        @DisplayName("no constructor argument leaks into a neighbouring field, which a same-typed "
                + "adjacent pair would otherwise hide")
        void noArgumentLeaksIntoANeighbouringField() {
            final Account account = new Account(
                    "00000000001", "Y",
                    new BigDecimal("1.00"), new BigDecimal("2.00"), new BigDecimal("3.00"),
                    "2001-01-01", "2002-02-02", "2003-03-03",
                    new BigDecimal("4.00"), new BigDecimal("5.00"),
                    "ZIP0000001", "GROUP00001");

            assertThat(List.of(
                    account.getAcctCurrBal(), account.getAcctCreditLimit(),
                    account.getAcctCashCreditLimit(), account.getAcctCurrCycCredit(),
                    account.getAcctCurrCycDebit()))
                    .containsExactly(
                            new BigDecimal("1.00"), new BigDecimal("2.00"), new BigDecimal("3.00"),
                            new BigDecimal("4.00"), new BigDecimal("5.00"));
            assertThat(List.of(
                    account.getAcctOpenDate(), account.getAcctExpirationDate(),
                    account.getAcctReissueDate()))
                    .containsExactly("2001-01-01", "2002-02-02", "2003-03-03");
            assertThat(account.getAcctAddrZip()).isEqualTo("ZIP0000001");
            assertThat(account.getAcctGroupId()).isEqualTo("GROUP00001");
        }

        @Test
        @DisplayName("every mutator replaces exactly the field it names")
        void everyMutatorReplacesTheFieldItNames() {
            final Account account = sampleAccount("00000000001");

            account.setAcctId("00000000099");
            account.setAcctActiveStatus("N");
            account.setAcctCurrBal(new BigDecimal("-12.34"));
            account.setAcctCreditLimit(new BigDecimal("9999999999.99"));
            account.setAcctCashCreditLimit(new BigDecimal("11.11"));
            account.setAcctOpenDate("1999-12-31");
            account.setAcctExpirationDate("2030-01-01");
            account.setAcctReissueDate("2030-06-30");
            account.setAcctCurrCycCredit(new BigDecimal("22.22"));
            account.setAcctCurrCycDebit(new BigDecimal("33.33"));
            account.setAcctAddrZip("90210     ");
            account.setAcctGroupId("ZEROAPR   ");

            assertThat(account.getAcctId()).isEqualTo("00000000099");
            assertThat(account.getAcctActiveStatus()).isEqualTo("N");
            assertThat(account.getAcctCurrBal()).isEqualTo(new BigDecimal("-12.34"));
            assertThat(account.getAcctCreditLimit()).isEqualTo(new BigDecimal("9999999999.99"));
            assertThat(account.getAcctCashCreditLimit()).isEqualTo(new BigDecimal("11.11"));
            assertThat(account.getAcctOpenDate()).isEqualTo("1999-12-31");
            assertThat(account.getAcctExpirationDate()).isEqualTo("2030-01-01");
            assertThat(account.getAcctReissueDate()).isEqualTo("2030-06-30");
            assertThat(account.getAcctCurrCycCredit()).isEqualTo(new BigDecimal("22.22"));
            assertThat(account.getAcctCurrCycDebit()).isEqualTo(new BigDecimal("33.33"));
            assertThat(account.getAcctAddrZip()).isEqualTo("90210     ");
            assertThat(account.getAcctGroupId()).isEqualTo("ZEROAPR   ");
        }

        @Test
        @DisplayName("the entity carries values at the full declared widths without truncating them")
        void theEntityCarriesValuesAtTheFullDeclaredWidths() {
            final Account account = new Account(
                    "9".repeat(KEY_WIDTH), "Y",
                    new BigDecimal("9999999999.99"), new BigDecimal("9999999999.99"),
                    new BigDecimal("9999999999.99"),
                    "X".repeat(10), "X".repeat(10), "X".repeat(10),
                    new BigDecimal("9999999999.99"), new BigDecimal("9999999999.99"),
                    "X".repeat(10), "X".repeat(10));

            assertThat(account.getAcctId()).hasSize(KEY_WIDTH);
            assertThat(account.getAcctOpenDate()).hasSize(10);
            assertThat(account.getAcctAddrZip()).hasSize(10);
            assertThat(account.getAcctGroupId()).hasSize(10);
            assertThat(account.getAcctCurrBal().precision())
                    .isEqualTo(AMOUNT_INTEGER_DIGITS + AMOUNT_DECIMAL_DIGITS);
        }

        @Test
        @DisplayName("the persistence constructor leaves every field absent, so nothing is invented "
                + "before a row is read")
        void thePersistenceConstructorLeavesEveryFieldAbsent() {
            final Account account = new Account();

            assertThat(account.getAcctId()).isNull();
            assertThat(account.getAcctActiveStatus()).isNull();
            assertThat(account.getAcctCurrBal()).isNull();
            assertThat(account.getAcctCreditLimit()).isNull();
            assertThat(account.getAcctCashCreditLimit()).isNull();
            assertThat(account.getAcctOpenDate()).isNull();
            assertThat(account.getAcctExpirationDate()).isNull();
            assertThat(account.getAcctReissueDate()).isNull();
            assertThat(account.getAcctCurrCycCredit()).isNull();
            assertThat(account.getAcctCurrCycDebit()).isNull();
            assertThat(account.getAcctAddrZip()).isNull();
            assertThat(account.getAcctGroupId()).isNull();
        }
    }

    // =================================================================================================
    // DECIMAL FIDELITY
    // =================================================================================================

    /**
     * Verifies that the entity carries a decimal amount without changing it.
     */
    @Nested
    @DisplayName("decimal fidelity")
    class DecimalFidelity {

        @Test
        @DisplayName("an amount keeps its scale of two, so a stored balance renders with two decimals")
        void anAmountKeepsItsScaleOfTwo() {
            final Account account = sampleAccount("00000000001");

            assertThat(account.getAcctCurrBal().scale()).isEqualTo(AMOUNT_DECIMAL_DIGITS);
            assertThat(account.getAcctCurrBal().toPlainString()).isEqualTo("194.00");
            assertThat(account.getAcctCurrCycCredit().toPlainString()).isEqualTo("0.00");
        }

        @Test
        @DisplayName("the entity is transparent: a value handed in at another scale comes back "
                + "unchanged, because rescaling is the codec's job and not the entity's")
        void theEntityDoesNotRescale() {
            final Account account = sampleAccount("00000000001");

            account.setAcctCurrBal(new BigDecimal("5"));
            assertThat(account.getAcctCurrBal().scale()).isZero();
            assertThat(account.getAcctCurrBal().toPlainString()).isEqualTo("5");

            account.setAcctCurrBal(new BigDecimal("5.00000"));
            assertThat(account.getAcctCurrBal().scale()).isEqualTo(5);
            assertThat(account.getAcctCurrBal().toPlainString()).isEqualTo("5.00000");
        }

        @Test
        @DisplayName("the one rescaling point truncates toward zero, because no arithmetic in the estate "
                + "asks for rounding")
        void theOneRescalingPointTruncates() {
            assertThat(ZonedDecimalCodec.COBOL_TRUNCATION_MODE).isEqualTo(RoundingMode.DOWN);
            assertThat(ZonedDecimalCodec.MONETARY_SCALE).isEqualTo(AMOUNT_DECIMAL_DIGITS);
            assertThat(ZonedDecimalCodec.toMonetaryScale(new BigDecimal("1.999")).toPlainString())
                    .isEqualTo("1.99");
            assertThat(ZonedDecimalCodec.toMonetaryScale(new BigDecimal("-1.999")).toPlainString())
                    .isEqualTo("-1.99");
        }

        @Test
        @DisplayName("a negative amount survives, so a debit balance is representable")
        void aNegativeAmountSurvives() {
            final Account account = sampleAccount("00000000001");
            account.setAcctCurrBal(new BigDecimal("-9999999999.99"));

            assertThat(account.getAcctCurrBal().signum()).isNegative();
            assertThat(account.getAcctCurrBal().toPlainString()).isEqualTo("-9999999999.99");
            assertThat(account.getAcctCurrBal().precision())
                    .isEqualTo(AMOUNT_INTEGER_DIGITS + AMOUNT_DECIMAL_DIGITS);
        }

        @Test
        @DisplayName("the widest amount the picture allows fits the declared precision exactly")
        void theWidestAmountFitsTheDeclaredPrecision() {
            final BigDecimal widest = new BigDecimal("9999999999.99");

            assertThat(widest.precision()).isEqualTo(SCHEMA.declaredWidth(TABLE, "acct_curr_bal"));
            assertThat(widest.scale()).isEqualTo(SCHEMA.declaredScale(TABLE, "acct_curr_bal"));
        }
    }

    // =================================================================================================
    // SEEDED RECORD FIDELITY
    // =================================================================================================

    /**
     * Verifies that a real seeded record travels into the entity intact.
     */
    @Nested
    @DisplayName("seeded record fidelity")
    class SeededRecordFidelity {

        /**
         * Builds an account from one seeded record by slicing the copybook offsets.
         *
         * @param ordinal the one-based record position
         * @return the account the record describes
         */
        private Account accountFromSeededRecord(final int ordinal) {
            return new Account(
                    FIXTURE.field(ordinal, OFFSET_ACCT_ID, KEY_WIDTH),
                    FIXTURE.field(ordinal, OFFSET_ACTIVE_STATUS, 1),
                    ZonedDecimalCodec.decodeMonetary(
                            FIXTURE.field(ordinal, OFFSET_CURR_BAL, 12), 12, "ACCT-CURR-BAL"),
                    ZonedDecimalCodec.decodeMonetary(
                            FIXTURE.field(ordinal, OFFSET_CREDIT_LIMIT, 12), 12, "ACCT-CREDIT-LIMIT"),
                    ZonedDecimalCodec.decodeMonetary(
                            FIXTURE.field(ordinal, OFFSET_CASH_CREDIT_LIMIT, 12), 12,
                            "ACCT-CASH-CREDIT-LIMIT"),
                    FIXTURE.field(ordinal, OFFSET_OPEN_DATE, 10),
                    FIXTURE.field(ordinal, OFFSET_EXPIRATION_DATE, 10),
                    FIXTURE.field(ordinal, OFFSET_REISSUE_DATE, 10),
                    ZonedDecimalCodec.decodeMonetary(
                            FIXTURE.field(ordinal, OFFSET_CURR_CYC_CREDIT, 12), 12,
                            "ACCT-CURR-CYC-CREDIT"),
                    ZonedDecimalCodec.decodeMonetary(
                            FIXTURE.field(ordinal, OFFSET_CURR_CYC_DEBIT, 12), 12,
                            "ACCT-CURR-CYC-DEBIT"),
                    FIXTURE.field(ordinal, OFFSET_ADDR_ZIP, 10),
                    FIXTURE.field(ordinal, OFFSET_GROUP_ID, 10));
        }

        @Test
        @DisplayName("the first seeded record maps field for field onto the entity")
        void theFirstSeededRecordMapsFieldForField() {
            final Account account = accountFromSeededRecord(1);

            assertThat(account.getAcctId()).isEqualTo("00000000001");
            assertThat(account.getAcctActiveStatus()).isEqualTo("Y");
            assertThat(account.getAcctCurrBal().toPlainString()).isEqualTo("194.00");
            assertThat(account.getAcctCreditLimit().toPlainString()).isEqualTo("2020.00");
            assertThat(account.getAcctCashCreditLimit().toPlainString()).isEqualTo("1020.00");
            assertThat(account.getAcctOpenDate()).isEqualTo("2014-11-20");
            assertThat(account.getAcctExpirationDate()).isEqualTo("2025-05-20");
            assertThat(account.getAcctReissueDate()).isEqualTo("2025-05-20");
            assertThat(account.getAcctCurrCycCredit().toPlainString()).isEqualTo("0.00");
            assertThat(account.getAcctCurrCycDebit().toPlainString()).isEqualTo("0.00");
            assertThat(account.getAcctAddrZip()).isEqualTo("A000000000");
            assertThat(account.getAcctGroupId()).isBlank().hasSize(10);
        }

        @Test
        @DisplayName("every seeded record maps without loss, and every identifier is distinct")
        void everySeededRecordMapsWithoutLoss() {
            final List<Account> accounts = new ArrayList<>();
            for (int ordinal = 1; ordinal <= FIXTURE.recordCount(); ordinal++) {
                accounts.add(accountFromSeededRecord(ordinal));
            }

            assertThat(accounts).hasSize(SEEDED_RECORDS);
            assertThat(accounts.stream().map(Account::getAcctId).distinct().toList())
                    .hasSize(SEEDED_RECORDS);
            for (final Account account : accounts) {
                assertThat(account.getAcctId()).hasSize(KEY_WIDTH).containsOnlyDigits();
                assertThat(account.getAcctCurrBal().scale()).isEqualTo(AMOUNT_DECIMAL_DIGITS);
                assertThat(account.getAcctCreditLimit().scale()).isEqualTo(AMOUNT_DECIMAL_DIGITS);
                assertThat(account.getAcctCashCreditLimit().scale()).isEqualTo(AMOUNT_DECIMAL_DIGITS);
                assertThat(account.getAcctCurrCycCredit().scale()).isEqualTo(AMOUNT_DECIMAL_DIGITS);
                assertThat(account.getAcctCurrCycDebit().scale()).isEqualTo(AMOUNT_DECIMAL_DIGITS);
            }
        }

        @Test
        @DisplayName("every seeded account is active, so the seed exercises the active status only")
        void everySeededAccountIsActive() {
            for (int ordinal = 1; ordinal <= FIXTURE.recordCount(); ordinal++) {
                assertThat(accountFromSeededRecord(ordinal).getAcctActiveStatus())
                        .as("status of record %d", ordinal)
                        .isEqualTo("Y");
            }
        }

        @Test
        @DisplayName("every seeded record leaves the disclosure-group slot blank and puts a "
                + "group-shaped value in the postal-code slot, which is the data fact that drives the "
                + "interest run down its default-group path")
        void everySeededRecordLeavesTheGroupSlotBlank() {
            for (int ordinal = 1; ordinal <= FIXTURE.recordCount(); ordinal++) {
                final Account account = accountFromSeededRecord(ordinal);

                assertThat(account.getAcctGroupId())
                        .as("disclosure group of record %d", ordinal)
                        .isBlank()
                        .hasSize(10);
                assertThat(account.getAcctAddrZip())
                        .as("postal code of record %d", ordinal)
                        .isEqualTo("A000000000")
                        .hasSize(10);
            }
        }

        @Test
        @DisplayName("the two cycle amounts are zero throughout the seed, so an overlimit basis computed "
                + "from them starts from zero")
        void theTwoCycleAmountsAreZeroThroughoutTheSeed() {
            for (int ordinal = 1; ordinal <= FIXTURE.recordCount(); ordinal++) {
                final Account account = accountFromSeededRecord(ordinal);

                assertThat(account.getAcctCurrCycCredit().signum())
                        .as("cycle credit of record %d", ordinal)
                        .isZero();
                assertThat(account.getAcctCurrCycDebit().signum())
                        .as("cycle debit of record %d", ordinal)
                        .isZero();
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
        @DisplayName("an account equals itself")
        void anAccountEqualsItself() {
            final Account account = sampleAccount("00000000001");

            assertThat(account).isEqualTo(account);
            assertThat(account.hashCode()).isEqualTo(account.hashCode());
        }

        @Test
        @DisplayName("two accounts with the same identifier are equal even when every other field "
                + "differs, because the key alone decides identity")
        void sameIdentifierMeansEqualRegardlessOfTheRest() {
            final Account left = sampleAccount("00000000001");
            final Account right = new Account(
                    "00000000001", "N",
                    new BigDecimal("-1.00"), new BigDecimal("-2.00"), new BigDecimal("-3.00"),
                    "1900-01-01", "1900-01-02", "1900-01-03",
                    new BigDecimal("-4.00"), new BigDecimal("-5.00"),
                    "OTHERZIP01", "OTHERGRP01");

            assertThat(left).isEqualTo(right);
            assertThat(right).isEqualTo(left);
            assertThat(left).hasSameHashCodeAs(right);
        }

        @Test
        @DisplayName("two accounts with different identifiers are unequal even when every other field "
                + "matches")
        void differentIdentifierMeansUnequal() {
            final Account left = sampleAccount("00000000001");
            final Account right = sampleAccount("00000000002");

            assertThat(left).isNotEqualTo(right);
            assertThat(right).isNotEqualTo(left);
        }

        @Test
        @DisplayName("equality is transitive across three accounts sharing an identifier")
        void equalityIsTransitive() {
            final Account first = sampleAccount("00000000007");
            final Account second = sampleAccount("00000000007");
            final Account third = sampleAccount("00000000007");

            assertThat(first).isEqualTo(second);
            assertThat(second).isEqualTo(third);
            assertThat(first).isEqualTo(third);
            assertThat(first).hasSameHashCodeAs(third);
        }

        @Test
        @DisplayName("an account is unequal to null and to an unrelated type")
        void anAccountIsUnequalToNullAndToAnotherType() {
            final Account account = sampleAccount("00000000001");

            assertThat(account).isNotEqualTo(null);
            assertThat(account.equals("00000000001")).isFalse();
            assertThat(account).isNotEqualTo(new Object());
        }

        @Test
        @DisplayName("two accounts with an absent identifier are equal, because both keys are absent "
                + "rather than generated")
        void twoUnkeyedAccountsAreEqual() {
            final Account left = new Account();
            final Account right = new Account();

            assertThat(left).isEqualTo(right);
            assertThat(left).hasSameHashCodeAs(right);
            assertThat(left.getAcctId()).isNull();
        }

        @Test
        @DisplayName("an unkeyed account is unequal to a keyed one")
        void anUnkeyedAccountIsUnequalToAKeyedOne() {
            assertThat(new Account()).isNotEqualTo(sampleAccount("00000000001"));
            assertThat(sampleAccount("00000000001")).isNotEqualTo(new Account());
        }

        @Test
        @DisplayName("changing the identifier changes identity, which is what makes the key a business "
                + "key rather than an immutable surrogate")
        void changingTheIdentifierChangesIdentity() {
            final Account account = sampleAccount("00000000001");
            final Account original = sampleAccount("00000000001");

            assertThat(account).isEqualTo(original);
            account.setAcctId("00000000002");
            assertThat(account).isNotEqualTo(original);
        }

        @Test
        @DisplayName("identity ignores the optimistic-version field, so a re-read row still matches")
        void identityIgnoresTheVersionField() {
            final Account persisted = new Account();
            persisted.setAcctId("00000000001");

            assertThat(persisted.getVersion()).isZero();
            assertThat(persisted).isEqualTo(sampleAccount("00000000001"));
            assertThat(persisted).hasSameHashCodeAs(sampleAccount("00000000001"));
        }
    }

    // =================================================================================================
    // OPTIMISTIC VERSION
    // =================================================================================================

    /**
     * Verifies the optimistic-locking field that replaces the legacy before-and-after image comparison.
     */
    @Nested
    @DisplayName("optimistic version")
    class OptimisticVersion {

        @Test
        @DisplayName("a freshly built account starts at version zero, matching the column's default")
        void aFreshAccountStartsAtVersionZero() {
            assertThat(sampleAccount("00000000001").getVersion()).isZero();
            assertThat(new Account().getVersion()).isZero();
        }

        @Test
        @DisplayName("the version is readable but has no public mutator, so only the persistence "
                + "provider advances it")
        void theVersionIsReadOnlyThroughThePublicSurface() {
            final Account account = sampleAccount("00000000001");
            final long before = account.getVersion();

            account.setAcctCurrBal(new BigDecimal("1.00"));
            account.setAcctActiveStatus("N");

            assertThat(account.getVersion())
                    .as("mutating a field must not move the version by itself")
                    .isEqualTo(before);
        }

        @Test
        @DisplayName("the version column exists in the schema and is not one of the record's own fields")
        void theVersionColumnIsNotOneOfTheRecordsFields() {
            assertThat(SCHEMA.columnNames(TABLE)).contains("version");
            assertThat(SCHEMA.declaredType(TABLE, "version")).isEqualTo("BIGINT");
            assertThat(SCHEMA.primaryKeyColumns(TABLE)).doesNotContain("version");
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
        @DisplayName("the description names the type, the identifier and the status")
        void theDescriptionNamesTheTypeIdentifierAndStatus() {
            assertThat(sampleAccount("00000000001").toString())
                    .isEqualTo("Account[acctId='00000000001', acctActiveStatus='Y']");
        }

        @Test
        @DisplayName("the description carries no monetary amount, so a balance cannot reach a log line "
                + "through it")
        void theDescriptionCarriesNoMonetaryAmount() {
            final String description = sampleAccount("00000000001").toString();

            assertThat(description)
                    .doesNotContain("194.00")
                    .doesNotContain("2020.00")
                    .doesNotContain("1020.00");
        }

        @Test
        @DisplayName("the description carries no address or group detail either")
        void theDescriptionCarriesNoAddressDetail() {
            assertThat(sampleAccount("00000000001").toString())
                    .doesNotContain("A000000000")
                    .doesNotContain("2014-11-20");
        }

        @Test
        @DisplayName("an unkeyed account still describes itself rather than failing")
        void anUnkeyedAccountStillDescribesItself() {
            assertThat(new Account().toString())
                    .isEqualTo("Account[acctId='null', acctActiveStatus='null']");
        }
    }
}
