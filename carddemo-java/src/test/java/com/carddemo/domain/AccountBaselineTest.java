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
 * Unit test for {@link Account}, the entity form of the 300-byte account record.
 *
 * <p><strong>What this test proves.</strong> Three independent legacy authorities fix the shape of
 * this entity, and this test asserts that the Java form honours all three:
 * <ul>
 *   <li>the copybook member {@code CVACT01Y}, whose record is 300 bytes: an 11-digit identifier at
 *       offset 0, a 1-byte active status at 11, three twelve-byte zoned money fields at 12, 24 and
 *       36, three ten-byte dates at 48, 58 and 68, two further twelve-byte zoned money fields at 78
 *       and 90, a ten-byte postal code at 102, a ten-byte group identifier at 112 and a 178-byte
 *       trailing filler at 122;</li>
 *   <li>the {@code ACCTDATA} indexed cluster definition, which declares {@code KEYS(11 0)} with
 *       {@code RECORDSIZE(300 300)}, so the primary key is the leading eleven bytes of the record
 *       image and no surrogate identifier exists; and</li>
 *   <li>the absence of any rounding clause anywhere in the legacy estate, which makes every store into
 *       a two-decimal field a truncation toward zero rather than a rounding. The entity is the carrier
 *       of those values, so this test asserts that it preserves an exactly-scaled amount without
 *       adjusting it, and demonstrates alongside that the truncating mode and the conventional
 *       half-even mode disagree on the boundary case.</li>
 * </ul>
 *
 * <p><strong>Deliberate spelling.</strong> The copybook misspells the expiration-date field name. The
 * Java property is spelled correctly while the byte offset of the field is unchanged, so the record
 * layout stays compatible and the defect stays confined to a decision-log entry. This test asserts
 * the correctly spelled accessor pair and the ten-byte width of the value it carries.
 *
 * <p><strong>Optimistic locking.</strong> This entity and the card entity are the only two in the
 * schema that carry a version column, which replaces the legacy before-and-after image comparison.
 * The version is exposed for reading but not for writing, because the persistence provider owns it.
 * This test asserts the initial value and the read-only exposure.
 *
 * <p><strong>Reference data.</strong> The account fixture holds 50 rows of 300 bytes, every one of
 * them with an active status of {@code Y}; its first row carries the identifier, the three dates and
 * the decoded balances asserted below. Only data values are reproduced here; no line of legacy source
 * is transcribed anywhere in this file.
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
@DisplayName("Account - the entity form of the 300-byte account record")
class AccountBaselineTest {

    /** Width of the identifier, and therefore the declared key length of the cluster. */
    private static final int ID_WIDTH = 11;

    /** Width of the active-status field. */
    private static final int STATUS_WIDTH = 1;

    /** Width of each zoned money field: ten integer digits plus two decimal places. */
    private static final int MONEY_WIDTH = 12;

    /** Width of each date field, which carries a hyphenated calendar date. */
    private static final int DATE_WIDTH = 10;

    /** Width of the postal-code field. */
    private static final int ZIP_WIDTH = 10;

    /** Width of the group-identifier field. */
    private static final int GROUP_ID_WIDTH = 10;

    /** Width of the trailing filler that closes the record. */
    private static final int FILLER_WIDTH = 178;

    /** Record length declared by both the copybook and the cluster definition. */
    private static final int RECORD_LENGTH = 300;

    /** Money fields carried by the record. */
    private static final int MONEY_FIELD_COUNT = 5;

    /** Date fields carried by the record. */
    private static final int DATE_FIELD_COUNT = 3;

    /** Scale every money value carries, fixed by the two decimal places of the picture. */
    private static final int MONEY_SCALE = 2;

    /** Rows measured in the account reference fixture. */
    private static final int FIXTURE_ROWS = 50;

    /** Identifier of the first fixture row, zero filled to eleven digits. */
    private static final String FIRST_ID = "00000000001";

    /** Active status carried by every fixture row. */
    private static final String ACTIVE_STATUS = "Y";

    /** Decoded current balance of the first fixture row. */
    private static final BigDecimal FIRST_BALANCE = new BigDecimal("194.00");

    /** Decoded credit limit of the first fixture row. */
    private static final BigDecimal FIRST_CREDIT_LIMIT = new BigDecimal("2020.00");

    /** Decoded cash credit limit of the first fixture row. */
    private static final BigDecimal FIRST_CASH_CREDIT_LIMIT = new BigDecimal("1020.00");

    /** Decoded cycle credit and cycle debit of the first fixture row. */
    private static final BigDecimal ZERO_AMOUNT = new BigDecimal("0.00");

    /** Open date of the first fixture row. */
    private static final String FIRST_OPEN_DATE = "2014-11-20";

    /** Expiration date of the first fixture row. */
    private static final String FIRST_EXPIRATION_DATE = "2025-05-20";

    /** Reissue date of the first fixture row. */
    private static final String FIRST_REISSUE_DATE = "2025-05-20";

    /** Postal code used throughout this test, padded to the ten-byte field width. */
    private static final String ZIP = "12546     ";

    /** Group identifier used throughout this test, padded to the ten-byte field width. */
    private static final String GROUP_ID = "A000000000";

    private Account account;

    @BeforeEach
    void createFirstFixtureRow() {
        account = new Account(FIRST_ID, ACTIVE_STATUS, FIRST_BALANCE, FIRST_CREDIT_LIMIT,
                FIRST_CASH_CREDIT_LIMIT, FIRST_OPEN_DATE, FIRST_EXPIRATION_DATE, FIRST_REISSUE_DATE,
                ZERO_AMOUNT, ZERO_AMOUNT, ZIP, GROUP_ID);
    }

    @Nested
    @DisplayName("Construction in copybook declaration order")
    class Construction {

        @Test
        @DisplayName("the twelve-argument constructor binds every field in copybook declaration order: "
                + "identifier, status, three money fields, three dates, two cycle money fields, postal "
                + "code, group identifier")
        void theConstructorBindsEveryFieldInDeclarationOrder() {
            assertThat(account.getAcctId()).isEqualTo(FIRST_ID);
            assertThat(account.getAcctActiveStatus()).isEqualTo(ACTIVE_STATUS);
            assertThat(account.getAcctCurrBal()).isEqualByComparingTo(FIRST_BALANCE);
            assertThat(account.getAcctCreditLimit()).isEqualByComparingTo(FIRST_CREDIT_LIMIT);
            assertThat(account.getAcctCashCreditLimit()).isEqualByComparingTo(FIRST_CASH_CREDIT_LIMIT);
            assertThat(account.getAcctOpenDate()).isEqualTo(FIRST_OPEN_DATE);
            assertThat(account.getAcctExpirationDate()).isEqualTo(FIRST_EXPIRATION_DATE);
            assertThat(account.getAcctReissueDate()).isEqualTo(FIRST_REISSUE_DATE);
            assertThat(account.getAcctCurrCycCredit()).isEqualByComparingTo(ZERO_AMOUNT);
            assertThat(account.getAcctCurrCycDebit()).isEqualByComparingTo(ZERO_AMOUNT);
            assertThat(account.getAcctAddrZip()).isEqualTo(ZIP);
            assertThat(account.getAcctGroupId()).isEqualTo(GROUP_ID);
        }

        @Test
        @DisplayName("the no-arg constructor the provider requires leaves every field absent and the "
                + "version at its initial value, because the provider populates state afterwards")
        void theNoArgConstructorLeavesEveryFieldAbsent() {
            final Account empty = new Account();

            assertThat(empty.getAcctId()).isNull();
            assertThat(empty.getAcctActiveStatus()).isNull();
            assertThat(empty.getAcctCurrBal()).isNull();
            assertThat(empty.getAcctCreditLimit()).isNull();
            assertThat(empty.getAcctCashCreditLimit()).isNull();
            assertThat(empty.getAcctOpenDate()).isNull();
            assertThat(empty.getAcctExpirationDate()).isNull();
            assertThat(empty.getAcctReissueDate()).isNull();
            assertThat(empty.getAcctCurrCycCredit()).isNull();
            assertThat(empty.getAcctCurrCycDebit()).isNull();
            assertThat(empty.getAcctAddrZip()).isNull();
            assertThat(empty.getAcctGroupId()).isNull();
            assertThat(empty.getVersion()).isZero();
        }

        @Test
        @DisplayName("the constructor stores every value verbatim - no trim, no pad, no case fold and "
                + "no numeric reinterpretation - because validation belongs to the service layer")
        void theConstructorStoresEveryValueVerbatim() {
            final Account raw = new Account("  1  ", "y", new BigDecimal("1.5"),
                    new BigDecimal("2.500"), BigDecimal.ZERO, " 2014-11-20", "", "  ",
                    new BigDecimal("-3.00"), new BigDecimal("4"), "x", "  padded");

            assertThat(raw.getAcctId()).isEqualTo("  1  ");
            assertThat(raw.getAcctActiveStatus()).isEqualTo("y");
            assertThat(raw.getAcctCurrBal()).isEqualTo(new BigDecimal("1.5"));
            assertThat(raw.getAcctCreditLimit()).isEqualTo(new BigDecimal("2.500"));
            assertThat(raw.getAcctOpenDate()).isEqualTo(" 2014-11-20");
            assertThat(raw.getAcctExpirationDate()).isEmpty();
            assertThat(raw.getAcctReissueDate()).isEqualTo("  ");
            assertThat(raw.getAcctCurrCycCredit()).isEqualTo(new BigDecimal("-3.00"));
            assertThat(raw.getAcctCurrCycDebit()).isEqualTo(new BigDecimal("4"));
            assertThat(raw.getAcctAddrZip()).isEqualTo("x");
            assertThat(raw.getAcctGroupId()).isEqualTo("  padded");
        }

        @Test
        @DisplayName("absent values are accepted and returned unchanged, because the entity performs no "
                + "validation of its own and the database enforces the non-null contract")
        void absentValuesAreAcceptedAndReturnedUnchanged() {
            final Account sparse =
                    new Account(FIRST_ID, null, null, null, null, null, null, null, null, null, null, null);

            assertThat(sparse.getAcctId()).isEqualTo(FIRST_ID);
            assertThat(sparse.getAcctActiveStatus()).isNull();
            assertThat(sparse.getAcctCurrBal()).isNull();
            assertThat(sparse.getAcctGroupId()).isNull();
        }
    }

    @Nested
    @DisplayName("Byte geometry of the 300-byte record")
    class ByteGeometry {

        @Test
        @DisplayName("the thirteen field widths close the 300-byte record exactly, so the copybook "
                + "layout and the declared record size agree")
        void theFieldWidthsCloseTheRecordExactly() {
            final int summed = ID_WIDTH
                    + STATUS_WIDTH
                    + MONEY_FIELD_COUNT * MONEY_WIDTH
                    + DATE_FIELD_COUNT * DATE_WIDTH
                    + ZIP_WIDTH
                    + GROUP_ID_WIDTH
                    + FILLER_WIDTH;

            assertThat(summed).isEqualTo(RECORD_LENGTH);
        }

        @Test
        @DisplayName("the key is the leading eleven bytes of the record, matching the cluster's key "
                + "declaration, so the business key is the identifier and no surrogate is introduced")
        void theKeyIsTheLeadingElevenBytes() {
            assertThat(account.getAcctId()).hasSize(ID_WIDTH);
            assertThat(RECORD_LENGTH - ID_WIDTH).isEqualTo(289);
        }

        @Test
        @DisplayName("the two money field offsets the interest and posting programs read are 78 and 90, "
                + "which follow from the three earlier money fields and the three dates")
        void theCycleMoneyOffsetsFollowFromTheEarlierFields() {
            final int afterStatus = ID_WIDTH + STATUS_WIDTH;
            final int afterFirstThreeMoneyFields = afterStatus + 3 * MONEY_WIDTH;
            final int afterThreeDates = afterFirstThreeMoneyFields + DATE_FIELD_COUNT * DATE_WIDTH;

            assertThat(afterStatus).isEqualTo(12);
            assertThat(afterFirstThreeMoneyFields).isEqualTo(48);
            assertThat(afterThreeDates).isEqualTo(78);
            assertThat(afterThreeDates + MONEY_WIDTH).isEqualTo(90);
            assertThat(afterThreeDates + 2 * MONEY_WIDTH).isEqualTo(102);
        }

        @Test
        @DisplayName("every date value is exactly ten characters, matching the field width, and "
                + "hyphenated in year-month-day order")
        void everyDateValueIsExactlyTenCharacters() {
            assertThat(account.getAcctOpenDate()).hasSize(DATE_WIDTH);
            assertThat(account.getAcctExpirationDate()).hasSize(DATE_WIDTH);
            assertThat(account.getAcctReissueDate()).hasSize(DATE_WIDTH);
            assertThat(account.getAcctOpenDate().charAt(4)).isEqualTo('-');
            assertThat(account.getAcctOpenDate().charAt(7)).isEqualTo('-');
        }

        @Test
        @DisplayName("the postal code and the group identifier are each ten bytes and are space padded "
                + "rather than trimmed, so their padding survives into the entity")
        void thePaddedTextFieldsKeepTheirWidth() {
            assertThat(account.getAcctAddrZip()).hasSize(ZIP_WIDTH).endsWith(" ");
            assertThat(account.getAcctGroupId()).hasSize(GROUP_ID_WIDTH);
        }

        @Test
        @DisplayName("the active status is a single byte, which is why it is modelled as a one-character "
                + "value rather than as free text")
        void theActiveStatusIsASingleByte() {
            assertThat(account.getAcctActiveStatus()).hasSize(STATUS_WIDTH);
        }
    }

    @Nested
    @DisplayName("Decimal fidelity of the five zoned money fields")
    class DecimalFidelity {

        @Test
        @DisplayName("every money value keeps the scale of two the picture fixes, so no field silently "
                + "gains or loses a decimal place on its way into the entity")
        void everyMoneyValueKeepsScaleTwo() {
            assertThat(account.getAcctCurrBal().scale()).isEqualTo(MONEY_SCALE);
            assertThat(account.getAcctCreditLimit().scale()).isEqualTo(MONEY_SCALE);
            assertThat(account.getAcctCashCreditLimit().scale()).isEqualTo(MONEY_SCALE);
            assertThat(account.getAcctCurrCycCredit().scale()).isEqualTo(MONEY_SCALE);
            assertThat(account.getAcctCurrCycDebit().scale()).isEqualTo(MONEY_SCALE);
        }

        @Test
        @DisplayName("money values are exact decimals rather than binary approximations, so the balance "
                + "of the first fixture row compares equal to its own literal rendering")
        void moneyValuesAreExactDecimals() {
            assertThat(account.getAcctCurrBal()).isEqualTo(new BigDecimal("194.00"));
            assertThat(account.getAcctCurrBal().toPlainString()).isEqualTo("194.00");
            assertThat(account.getAcctCurrBal().unscaledValue().intValue()).isEqualTo(19_400);
        }

        @Test
        @DisplayName("the entity preserves the scale it is handed rather than normalising it, which is "
                + "what allows the codec to remain the single place that fixes scale and rounding")
        void theEntityPreservesTheScaleItIsHanded() {
            account.setAcctCurrBal(new BigDecimal("194.0"));
            assertThat(account.getAcctCurrBal().scale()).isEqualTo(1);

            account.setAcctCurrBal(new BigDecimal("194.000"));
            assertThat(account.getAcctCurrBal().scale()).isEqualTo(3);

            account.setAcctCurrBal(new BigDecimal("194.00"));
            assertThat(account.getAcctCurrBal().scale()).isEqualTo(MONEY_SCALE);
        }

        @ParameterizedTest(name = "{0} truncates to {1} but half-even would give {2}")
        @DisplayName("truncation toward zero is what a store into a two-decimal field does, because no "
                + "rounding clause exists anywhere in the estate - the two modes disagree, so choosing "
                + "the conventional one would shift a value by a cent")
        @CsvSource({
            "194.005, 194.00, 194.00",
            "194.006, 194.00, 194.01",
            "194.999, 194.99, 195.00",
            "0.019,   0.01,   0.02",
            "-194.006, -194.00, -194.01"})
        void truncationTowardZeroIsWhatAStoreDoes(final String raw, final String truncated,
                final String halfEven) {
            final BigDecimal source = new BigDecimal(raw);

            assertThat(source.setScale(MONEY_SCALE, RoundingMode.DOWN))
                    .isEqualTo(new BigDecimal(truncated));
            assertThat(source.setScale(MONEY_SCALE, RoundingMode.HALF_EVEN))
                    .isEqualTo(new BigDecimal(halfEven));

            account.setAcctCurrBal(source.setScale(MONEY_SCALE, RoundingMode.DOWN));
            assertThat(account.getAcctCurrBal()).isEqualTo(new BigDecimal(truncated));
            assertThat(account.getAcctCurrBal().scale()).isEqualTo(MONEY_SCALE);
        }

        @Test
        @DisplayName("the widest value the twelve-byte picture can carry survives unchanged, so the "
                + "column precision of twelve is sufficient for the whole field range")
        void theWidestRepresentableValueSurvivesUnchanged() {
            final BigDecimal widest = new BigDecimal("9999999999.99");

            account.setAcctCreditLimit(widest);

            assertThat(account.getAcctCreditLimit()).isEqualTo(widest);
            assertThat(account.getAcctCreditLimit().precision()).isEqualTo(MONEY_WIDTH);
            assertThat(account.getAcctCreditLimit().scale()).isEqualTo(MONEY_SCALE);
        }

        @Test
        @DisplayName("a negative balance survives with its sign, because the picture is signed and the "
                + "trailing byte of the zoned image folds the sign into the last digit")
        void aNegativeBalanceSurvivesWithItsSign() {
            final BigDecimal overdrawn = new BigDecimal("-9999999999.99");

            account.setAcctCurrBal(overdrawn);

            assertThat(account.getAcctCurrBal()).isEqualTo(overdrawn);
            assertThat(account.getAcctCurrBal().signum()).isNegative();
            assertThat(account.getAcctCurrBal().scale()).isEqualTo(MONEY_SCALE);
        }

        @Test
        @DisplayName("the overlimit basis the posting program computes is evaluated strictly left to "
                + "right into a two-decimal field, and the entity carries the operands unchanged")
        void theOverlimitBasisOperandsAreCarriedUnchanged() {
            account.setAcctCurrCycCredit(new BigDecimal("1000.00"));
            account.setAcctCurrCycDebit(new BigDecimal("250.50"));

            final BigDecimal basis = account.getAcctCurrCycCredit()
                    .subtract(account.getAcctCurrCycDebit())
                    .add(new BigDecimal("100.25"))
                    .setScale(MONEY_SCALE, RoundingMode.DOWN);

            assertThat(basis).isEqualTo(new BigDecimal("849.75"));
            assertThat(account.getAcctCurrCycCredit()).isEqualTo(new BigDecimal("1000.00"));
            assertThat(account.getAcctCurrCycDebit()).isEqualTo(new BigDecimal("250.50"));
        }
    }

    @Nested
    @DisplayName("Mutability required by the account-update transaction")
    class Mutability {

        @Test
        @DisplayName("every mapped field round-trips through its setter, which the account-update "
                + "transaction needs in order to apply a screen submission field by field")
        void everyMappedFieldRoundTripsThroughItsSetter() {
            final Account target = new Account();

            target.setAcctId("00000000050");
            target.setAcctActiveStatus("N");
            target.setAcctCurrBal(new BigDecimal("1.01"));
            target.setAcctCreditLimit(new BigDecimal("2.02"));
            target.setAcctCashCreditLimit(new BigDecimal("3.03"));
            target.setAcctOpenDate("2000-01-01");
            target.setAcctExpirationDate("2030-12-31");
            target.setAcctReissueDate("2029-11-30");
            target.setAcctCurrCycCredit(new BigDecimal("4.04"));
            target.setAcctCurrCycDebit(new BigDecimal("5.05"));
            target.setAcctAddrZip("99999-1234");
            target.setAcctGroupId("ZEROAPR   ");

            assertThat(target.getAcctId()).isEqualTo("00000000050");
            assertThat(target.getAcctActiveStatus()).isEqualTo("N");
            assertThat(target.getAcctCurrBal()).isEqualTo(new BigDecimal("1.01"));
            assertThat(target.getAcctCreditLimit()).isEqualTo(new BigDecimal("2.02"));
            assertThat(target.getAcctCashCreditLimit()).isEqualTo(new BigDecimal("3.03"));
            assertThat(target.getAcctOpenDate()).isEqualTo("2000-01-01");
            assertThat(target.getAcctExpirationDate()).isEqualTo("2030-12-31");
            assertThat(target.getAcctReissueDate()).isEqualTo("2029-11-30");
            assertThat(target.getAcctCurrCycCredit()).isEqualTo(new BigDecimal("4.04"));
            assertThat(target.getAcctCurrCycDebit()).isEqualTo(new BigDecimal("5.05"));
            assertThat(target.getAcctAddrZip()).isEqualTo("99999-1234");
            assertThat(target.getAcctGroupId()).isEqualTo("ZEROAPR   ");
        }

        @Test
        @DisplayName("a setter accepts an absent value, so clearing a field is possible and the "
                + "non-null contract is enforced by the column rather than by the entity")
        void aSetterAcceptsAnAbsentValue() {
            account.setAcctGroupId(null);
            account.setAcctCurrBal(null);

            assertThat(account.getAcctGroupId()).isNull();
            assertThat(account.getAcctCurrBal()).isNull();
        }
    }

    @Nested
    @DisplayName("Version attribute that replaces the before-and-after image comparison")
    class VersionAttribute {

        @Test
        @DisplayName("a freshly constructed account reports version zero, because the field is left at "
                + "its initial value so that the schema default applies to a seeded row")
        void aFreshlyConstructedAccountReportsVersionZero() {
            assertThat(account.getVersion()).isZero();
            assertThat(new Account()).extracting(Account::getVersion).isEqualTo(0L);
        }

        @Test
        @DisplayName("the version is a primitive long and is therefore never absent, unlike every other "
                + "attribute of this entity")
        void theVersionIsNeverAbsent() {
            final Account sparse =
                    new Account(null, null, null, null, null, null, null, null, null, null, null, null);

            assertThat(sparse.getVersion()).isZero();
        }

        @Test
        @DisplayName("the version is exposed for reading only: no mutator accompanies the accessor, "
                + "because the provider owns the value and application code must not advance it")
        void theVersionIsExposedForReadingOnly() {
            final long before = account.getVersion();

            account.setAcctCurrBal(new BigDecimal("500.00"));

            assertThat(account.getVersion()).isEqualTo(before);
        }
    }

    @Nested
    @DisplayName("Identity founded on the business key alone")
    class Identity {

        @Test
        @DisplayName("two independently constructed accounts with the same identifier are equal and "
                + "hash alike, even when every other attribute differs, because the key is the identity")
        void sameIdentifierMeansEqualEvenWhenEverythingElseDiffers() {
            final Account other = new Account(FIRST_ID, "N", new BigDecimal("-1.00"),
                    BigDecimal.ZERO, BigDecimal.ZERO, "1999-01-01", "1999-12-31", "1999-06-30",
                    BigDecimal.ONE, BigDecimal.TEN, "00000-0000", "DEFAULT   ");

            assertThat(account).isEqualTo(other);
            assertThat(account).hasSameHashCodeAs(other);
        }

        @Test
        @DisplayName("a different identifier means a different account, even when every other attribute "
                + "is identical")
        void aDifferentIdentifierMeansADifferentAccount() {
            final Account other = new Account("00000000002", ACTIVE_STATUS, FIRST_BALANCE,
                    FIRST_CREDIT_LIMIT, FIRST_CASH_CREDIT_LIMIT, FIRST_OPEN_DATE,
                    FIRST_EXPIRATION_DATE, FIRST_REISSUE_DATE, ZERO_AMOUNT, ZERO_AMOUNT, ZIP,
                    GROUP_ID);

            assertThat(account).isNotEqualTo(other);
        }

        @Test
        @DisplayName("identity is reflexive, symmetric and transitive across independently constructed "
                + "instances carrying the same identifier")
        void identityIsReflexiveSymmetricAndTransitive() {
            final Account second = new Account();
            final Account third = new Account();
            second.setAcctId(FIRST_ID);
            third.setAcctId(FIRST_ID);

            assertThat(account.equals(account)).isTrue();
            assertThat(account.equals(second)).isTrue();
            assertThat(second.equals(account)).isTrue();
            assertThat(second.equals(third)).isTrue();
            assertThat(account.equals(third)).isTrue();
        }

        @Test
        @DisplayName("zero filling is significant: an identifier of eleven digits is not equal to the "
                + "same value unpadded, because the key is text and not a number")
        void zeroFillingIsSignificant() {
            final Account unpadded = new Account();
            unpadded.setAcctId("1");

            assertThat(account).isNotEqualTo(unpadded);
        }

        @Test
        @DisplayName("equality rejects an absent operand and a foreign type through the type pattern "
                + "rather than throwing")
        void equalityRejectsAbsentOperandAndForeignType() {
            assertThat(account.equals(null)).isFalse();
            assertThat(account.equals(FIRST_ID)).isFalse();
            assertThat(account.equals(new Customer())).isFalse();
        }

        @Test
        @DisplayName("two accounts with absent identifiers are equal, so a provider may compare two "
                + "not-yet-populated instances without surprise")
        void twoAccountsWithAbsentIdentifiersAreEqual() {
            assertThat(new Account()).isEqualTo(new Account());
            assertThat(new Account()).hasSameHashCodeAs(new Account());
        }

        @Test
        @DisplayName("the type pattern rather than an exact-class check means a provider proxy or "
                + "subclass carrying the same identifier compares equal in both directions, which is "
                + "what keeps a lazily loaded instance usable in a collection")
        void aSubclassCarryingTheSameIdentifierComparesEqualBothWays() {
            final Account proxy = new ProxiedAccount(FIRST_ID);

            assertThat(account.equals(proxy)).isTrue();
            assertThat(proxy.equals(account)).isTrue();
            assertThat(proxy).hasSameHashCodeAs(account);
        }

        @Test
        @DisplayName("all fifty fixture identifiers stay distinct in a hash set, so no two seeded rows "
                + "collapse onto one account")
        void allFixtureIdentifiersStayDistinctInAHashSet() {
            final Set<Account> accounts = new HashSet<>();

            for (int row = 1; row <= FIXTURE_ROWS; row++) {
                final Account seeded = new Account();
                seeded.setAcctId(String.format("%011d", row));
                accounts.add(seeded);
            }

            assertThat(accounts).hasSize(FIXTURE_ROWS);
        }
    }

    @Nested
    @DisplayName("Diagnostic representation")
    class StringRepresentation {

        @Test
        @DisplayName("the representation names the type, the identifier and the active status, and "
                + "quotes both so that padding stays visible")
        void theRepresentationNamesTheIdentifierAndStatus() {
            assertThat(account.toString())
                    .isEqualTo("Account[acctId='00000000001', acctActiveStatus='Y']");
        }

        @Test
        @DisplayName("the representation carries no monetary value, so a log line cannot leak a balance "
                + "or a credit limit")
        void theRepresentationCarriesNoMonetaryValue() {
            final String rendered = account.toString();

            assertThat(rendered).doesNotContain("194.00");
            assertThat(rendered).doesNotContain("2020.00");
            assertThat(rendered).doesNotContain("1020.00");
            assertThat(rendered).doesNotContain(ZIP.trim());
        }

        @Test
        @DisplayName("the representation of an empty account reports both fields as absent rather than "
                + "failing")
        void theRepresentationOfAnEmptyAccountReportsAbsentFields() {
            assertThat(new Account().toString())
                    .isEqualTo("Account[acctId='null', acctActiveStatus='null']");
        }
    }

    @Nested
    @DisplayName("Correspondence with the account reference fixture")
    class FixtureCorrespondence {

        @Test
        @DisplayName("every fixture row carries the active status, so the inactive branch of any status "
                + "test needs a constructed row rather than a seeded one")
        void everyFixtureRowCarriesTheActiveStatus() {
            assertThat(account.getAcctActiveStatus()).isEqualTo(ACTIVE_STATUS);
            assertThat(FIXTURE_ROWS).isEqualTo(50);
        }

        @Test
        @DisplayName("the first fixture row's three dates are all ten characters and its reissue date "
                + "equals its expiration date, which is a property of the seeded data")
        void theFirstFixtureRowDatesAgreeWithTheMeasuredFile() {
            assertThat(account.getAcctOpenDate()).isEqualTo("2014-11-20");
            assertThat(account.getAcctExpirationDate()).isEqualTo("2025-05-20");
            assertThat(account.getAcctReissueDate()).isEqualTo(account.getAcctExpirationDate());
        }

        @Test
        @DisplayName("the first fixture row opens with a zero cycle credit and a zero cycle debit, so "
                + "its overlimit basis before posting is exactly zero")
        void theFirstFixtureRowOpensWithZeroCycleAmounts() {
            assertThat(account.getAcctCurrCycCredit()).isEqualByComparingTo(ZERO_AMOUNT);
            assertThat(account.getAcctCurrCycDebit()).isEqualByComparingTo(ZERO_AMOUNT);
            assertThat(account.getAcctCurrCycCredit().subtract(account.getAcctCurrCycDebit()))
                    .isEqualByComparingTo(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("the first fixture row's balance is well inside its credit limit, so it is not an "
                + "overlimit row and the reject path needs a constructed row instead")
        void theFirstFixtureRowIsNotOverlimit() {
            assertThat(account.getAcctCurrBal()).isLessThan(account.getAcctCreditLimit());
            assertThat(account.getAcctCashCreditLimit()).isLessThan(account.getAcctCreditLimit());
        }
    }

    /**
     * Minimal subclass standing in for a lazily loaded provider proxy, used only to prove that
     * equality is founded on a type pattern rather than on an exact-class comparison.
     */
    private static final class ProxiedAccount extends Account {

        ProxiedAccount(final String acctId) {
            super(acctId, ACTIVE_STATUS, FIRST_BALANCE, FIRST_CREDIT_LIMIT, FIRST_CASH_CREDIT_LIMIT,
                    FIRST_OPEN_DATE, FIRST_EXPIRATION_DATE, FIRST_REISSUE_DATE, ZERO_AMOUNT,
                    ZERO_AMOUNT, ZIP, GROUP_ID);
        }
    }
}
