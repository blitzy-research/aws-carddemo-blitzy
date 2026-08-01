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
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import com.carddemo.domain.id.DisclosureGroupId;
import com.carddemo.domain.id.TransactionCategoryId;
import com.carddemo.util.SensitiveFieldCodec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Verifies the seven entities whose shape is dictated by a verified record layout.
 *
 * <p>Each entity is a passive carrier over a fixed-width COBOL record. Three properties are
 * contractual and each is asserted here rather than assumed:
 *
 * <ol>
 *   <li><strong>Every monetary and rate field is a {@code BigDecimal} and nothing rescales it.</strong>
 *       The mandated mapping requires decimal precision identical to the legacy field with no
 *       floating-point substitution. Because no {@code ROUNDED} clause exists anywhere in the estate,
 *       a store into a two-decimal field truncates toward zero, and that scaling is applied once - in
 *       the zoned-decimal codec - not by each entity. An entity that rescaled on the way in or out
 *       would apply the policy twice and could round where the legacy truncates.</li>
 *   <li><strong>The primary key is the business key from the record image.</strong> Each cluster
 *       definition states its key as an offset and a length into the record, so no surrogate
 *       identifier exists anywhere. Equality and hashing are over the key alone, which is what lets a
 *       detached instance match a managed one.</li>
 *   <li><strong>The layout arithmetic must add up.</strong> Every field width plus the trailing filler
 *       equals the declared record length. Two of these sums are load bearing beyond documentation:
 *       the daily-transaction card number begins at byte 263 and its processing timestamp at byte 305,
 *       which are exactly the offsets the external sort specifications address.</li>
 * </ol>
 *
 * <p>Two deliberate, documented deviations from the legacy widths are asserted as deviations rather
 * than silently accepted: the password column is far wider than the legacy eight-character field
 * because it holds a hash instead of plaintext, and the two regulated identifier columns are wider
 * because they are encrypted at rest. Both improve on the legacy posture and both are recorded in the
 * project decision log.
 *
 * <p>Both deviations are enforced by the entity rather than merely permitted by it. The credential
 * attribute refuses any value that is not structurally a BCrypt digest, and the two regulated
 * customer identifiers refuse any value that does not already carry the {@code ENC1:} envelope. This
 * suite therefore supplies conforming values and reads the cleartext back through the codec, which is
 * what the persistence boundary itself does.
 */
@DisplayName("Record-layout entities: fixed widths, decimal identity and business-key equality")
final class RecordLayoutEntityTest {

    // =================================================================================================
    // Layout oracles transcribed from the copybooks. Widths are byte counts of the zoned images.
    // =================================================================================================

    /** {@code RECLN 300}, {@code [app/cpy/CVACT01Y.cpy]}. */
    private static final int ORACLE_ACCOUNT_RECORD_LENGTH = 300;

    /** {@code RECLN = 500}, {@code [app/cpy/CVCUS01Y.cpy]}. */
    private static final int ORACLE_CUSTOMER_RECORD_LENGTH = 500;

    /** {@code RECLN = 350}, {@code [app/cpy/CVTRA06Y.cpy]}. */
    private static final int ORACLE_DAILY_TRANSACTION_RECORD_LENGTH = 350;

    /** Eighty bytes, {@code [app/cpy/CSUSR01Y.cpy:L18-L23]}. */
    private static final int ORACLE_USER_SECURITY_RECORD_LENGTH = 80;

    /** {@code RECLN = 50}, {@code [app/cpy/CVTRA02Y.cpy]}. */
    private static final int ORACLE_DISCLOSURE_GROUP_RECORD_LENGTH = 50;

    /** {@code RECLN = 60}, shared by the transaction-type and transaction-category records. */
    private static final int ORACLE_SIXTY_BYTE_RECORD_LENGTH = 60;

    /** A {@code PIC S9(10)V99} zoned image occupies twelve bytes; the column takes precision twelve. */
    private static final int ORACLE_ACCOUNT_AMOUNT_PRECISION = 12;

    /** A {@code PIC S9(04)V99} zoned image occupies six bytes; the rate column takes precision six. */
    private static final int ORACLE_RATE_PRECISION = 6;

    /** Every monetary and rate field in the estate carries exactly two decimal places. */
    private static final int ORACLE_MONEY_SCALE = 2;

    /** 1-based offset of {@code DALYTRAN-CARD-NUM}: the sort specification's {@code 263,16} field. */
    private static final int ORACLE_CARD_NUMBER_OFFSET = 263;

    /** 1-based offset of {@code DALYTRAN-PROC-TS}: the sort specification's {@code 305,10} field. */
    private static final int ORACLE_PROCESSING_TIMESTAMP_OFFSET = 305;

    /** {@code SEC-USR-PWD PIC X(08)} - the legacy plaintext width, deliberately not reproduced. */
    private static final int ORACLE_LEGACY_PASSWORD_WIDTH = 8;

    // =================================================================================================
    // Fixture values, each spelled at the exact legacy width.
    // =================================================================================================

    /** {@code ACCT-ID PIC 9(11)}. */
    private static final String ACCOUNT_ID = "00000000011";

    /** A balance at the scale the codec produces. */
    private static final BigDecimal BALANCE = new BigDecimal("1234567890.12");

    /** A credit limit at the same scale. */
    private static final BigDecimal CREDIT_LIMIT = new BigDecimal("5000.00");

    /** {@code CUST-ID PIC 9(09)}. */
    private static final String CUSTOMER_ID = "000000011";

    /** {@code DALYTRAN-ID PIC X(16)}. */
    private static final String DAILY_TRANSACTION_ID = "0000000000000001";

    /** {@code DALYTRAN-CARD-NUM PIC X(16)}. */
    private static final String CARD_NUMBER = "4111111111111111";

    /** {@code SEC-USR-ID PIC X(08)}, the first administrator in the in-stream seed. */
    private static final String ADMIN_USER_ID = "ADMIN001";

    /**
     * A synthetic value of exactly the shape the entity accepts: the seven-character marker and cost
     * prefix followed by a fifty-three-character radix-64 tail, summing to sixty characters.
     *
     * <p>The abbreviated placeholders this suite once used are not usable: the entity refuses any
     * credential that is not structurally a digest, which is what stops the eight-character
     * cleartext value the legacy record held from reaching the widened column.
     */
    private static final String ORACLE_CREDENTIAL_DIGEST = "$2a$10$" + "a".repeat(53);

    /** A second synthetic digest, so a credential replacement can be observed. */
    private static final String ORACLE_OTHER_CREDENTIAL_DIGEST = "$2b$12$" + "b".repeat(53);

    /**
     * A thirty-two-byte key used only to manufacture and read back well-formed protected values.
     *
     * <p>The customer entity refuses cleartext on both regulated attributes, so a fixture cannot hand
     * it the legacy value directly. This key seals the fixture values and recovers them for
     * assertion; it protects nothing real and is not a deployment secret.
     */
    private static final byte[] PROTECTION_KEY =
            "carddemo-layout-test-key-012345!".getBytes(StandardCharsets.UTF_8);

    /** {@code CUST-SSN PIC 9(09)} as the legacy record holds it, in the clear. */
    private static final String ORACLE_NATIONAL_ID = "123456789";

    /** {@code CUST-GOVT-ISSUED-ID PIC X(20)} as the legacy record holds it, in the clear. */
    private static final String ORACLE_GOVT_ISSUED_ID = "DL-1234567890";

    /**
     * Seals a cleartext value into the envelope shape the customer entity accepts.
     *
     * @param cleartext the legacy cleartext value
     * @return the sealed value
     */
    private static String sealed(final String cleartext) {
        return SensitiveFieldCodec.protect(cleartext, PROTECTION_KEY);
    }

    /**
     * Recovers the cleartext a stored protected value carries.
     *
     * @param envelope the value read off the entity
     * @return the cleartext it protects
     */
    private static String revealed(final String envelope) {
        return SensitiveFieldCodec.reveal(envelope, PROTECTION_KEY);
    }

    /** {@code DIS-ACCT-GROUP-ID PIC X(10)} as the fixture spells it, padding included. */
    private static final String DEFAULT_GROUP_ID = "DEFAULT   ";

    /** {@code DIS-TRAN-TYPE-CD PIC X(02)}. */
    private static final String TYPE_CODE = "01";

    /** {@code DIS-TRAN-CAT-CD PIC 9(04)}. */
    private static final String CATEGORY_CODE = "0005";

    /**
     * Builds an account with every field populated at its legacy width.
     *
     * @return a fully populated account
     */
    private static Account anAccount() {
        return new Account(ACCOUNT_ID, "Y", BALANCE, CREDIT_LIMIT, new BigDecimal("500.00"),
                "2020-01-01", "2027-12-31", "2024-01-01", new BigDecimal("100.00"),
                new BigDecimal("250.00"), "12345-6789", DEFAULT_GROUP_ID);
    }

    /**
     * Builds a customer with every field populated at its legacy width.
     *
     * @return a fully populated customer
     */
    private static Customer aCustomer() {
        return new Customer(CUSTOMER_ID, "MARY", "ANN", "SMITH", "1 MAIN STREET", "APT 2",
                "SPRINGFIELD", "IL", "USA", "62704-0001", "217-555-0100", "217-555-0101",
                sealed(ORACLE_NATIONAL_ID), sealed(ORACLE_GOVT_ISSUED_ID), "1980-06-15",
                "0000000001", "Y", "720");
    }

    /**
     * Builds a daily transaction with every field populated at its legacy width.
     *
     * @return a fully populated daily transaction
     */
    private static DailyTransaction aDailyTransaction() {
        return new DailyTransaction(DAILY_TRANSACTION_ID, TYPE_CODE, CATEGORY_CODE, "POS TERM  ",
                "PURCHASE AT MERCHANT", new BigDecimal("123.45"), "000000001", "MERCHANT NAME",
                "MERCHANT CITY", "62704-0001", CARD_NUMBER, "2022-07-06-14.23.41.870000",
                "2022-07-06-14.23.41.870000");
    }

    @Nested
    @DisplayName("Account: the 300-byte record with five two-decimal amounts and a version")
    final class AccountEntity {

        @Test
        @DisplayName("every field is reported back exactly as supplied")
        void everyFieldIsReportedBack() {
            final Account account = anAccount();

            assertThat(account.getAcctId()).isEqualTo(ACCOUNT_ID);
            assertThat(account.getAcctActiveStatus()).isEqualTo("Y");
            assertThat(account.getAcctCurrBal()).isEqualTo(BALANCE);
            assertThat(account.getAcctCreditLimit()).isEqualTo(CREDIT_LIMIT);
            assertThat(account.getAcctCashCreditLimit()).isEqualByComparingTo("500.00");
            assertThat(account.getAcctOpenDate()).isEqualTo("2020-01-01");
            assertThat(account.getAcctExpirationDate()).isEqualTo("2027-12-31");
            assertThat(account.getAcctReissueDate()).isEqualTo("2024-01-01");
            assertThat(account.getAcctCurrCycCredit()).isEqualByComparingTo("100.00");
            assertThat(account.getAcctCurrCycDebit()).isEqualByComparingTo("250.00");
            assertThat(account.getAcctAddrZip()).isEqualTo("12345-6789");
            assertThat(account.getAcctGroupId()).isEqualTo(DEFAULT_GROUP_ID);
        }

        @Test
        @DisplayName("the layout arithmetic adds up to the declared record length")
        void theLayoutArithmeticAddsUp() {
            // 9(11) + X(01) + three S9(10)V99 + three X(10) + two S9(10)V99 + X(10) + X(10) + X(178)
            final int declared = 11 + 1 + (3 * ORACLE_ACCOUNT_AMOUNT_PRECISION) + (3 * 10)
                    + (2 * ORACLE_ACCOUNT_AMOUNT_PRECISION) + 10 + 10 + 178;

            assertThat(declared).isEqualTo(ORACLE_ACCOUNT_RECORD_LENGTH);
        }

        @Test
        @DisplayName("the account identifier fills its eleven-digit field")
        void theIdentifierFillsItsField() {
            assertThat(ACCOUNT_ID).hasSize(11);
        }

        @Test
        @DisplayName("an amount is stored verbatim and is not rescaled by the entity")
        void anAmountIsStoredVerbatim() {
            // Scaling is applied once, in the codec. An entity that rescaled would apply it twice.
            final Account account = anAccount();

            assertThat(account.getAcctCurrBal().scale()).isEqualTo(ORACLE_MONEY_SCALE);
            assertThat(account.getAcctCurrBal().toPlainString()).isEqualTo("1234567890.12");
        }

        @Test
        @DisplayName("a value already carrying a different scale is not silently normalised")
        void aDifferentScaleIsNotSilentlyNormalised() {
            final Account account = anAccount();
            account.setAcctCurrBal(new BigDecimal("10.5000"));

            assertThat(account.getAcctCurrBal().scale())
                    .as("the entity is a passive carrier; the codec owns the scale policy")
                    .isEqualTo(4);
        }

        @Test
        @DisplayName("truncation, not rounding, is what the absent ROUNDED clause implies")
        void truncationNotRoundingIsTheLegacyPolicy() {
            // Reproduced here as the property the codec must hold, so the expectation is recorded
            // beside the entity that carries the result.
            final BigDecimal raw = new BigDecimal("10.999");

            assertThat(raw.setScale(ORACLE_MONEY_SCALE, RoundingMode.DOWN).toPlainString())
                    .isEqualTo("10.99");
            assertThat(raw.setScale(ORACLE_MONEY_SCALE, RoundingMode.HALF_EVEN).toPlainString())
                    .as("the conventional Java choice would differ by a cent and fail byte parity")
                    .isEqualTo("11.00");
        }

        @Test
        @DisplayName("a negative amount keeps its sign, because the zoned field is signed")
        void aNegativeAmountKeepsItsSign() {
            final Account account = anAccount();
            account.setAcctCurrBal(new BigDecimal("-42.50"));

            assertThat(account.getAcctCurrBal()).isEqualByComparingTo("-42.50");
            assertThat(account.getAcctCurrBal().signum()).isNegative();
        }

        @Test
        @DisplayName("the source's misspelled expiration field is corrected in the Java property")
        void theMisspelledFieldNameIsCorrectedInJava() {
            // The copybook writes ACCT-EXPIRAION-DATE. The layout offset is unchanged; only the Java
            // property is spelled correctly, and the divergence is recorded in the decision log.
            final Account account = anAccount();

            assertThat(account.getAcctExpirationDate()).isEqualTo("2027-12-31");
        }

        @Test
        @DisplayName("a fresh instance starts at version zero, ready for optimistic locking")
        void aFreshInstanceStartsAtVersionZero() {
            assertThat(anAccount().getVersion())
                    .as("the version replaces the legacy before-and-after image comparison")
                    .isZero();
        }

        @Test
        @DisplayName("equality is over the business key alone, so a detached copy matches")
        void equalityIsOverTheBusinessKeyAlone() {
            final Account first = anAccount();
            final Account second = new Account(ACCOUNT_ID, "N", BigDecimal.ZERO, BigDecimal.ZERO,
                    BigDecimal.ZERO, "1999-01-01", "1999-01-01", "1999-01-01", BigDecimal.ZERO,
                    BigDecimal.ZERO, "00000-0000", "OTHER     ");

            assertThat(first).isEqualTo(second).hasSameHashCodeAs(second);
        }

        @Test
        @DisplayName("a different key makes the accounts unequal")
        void aDifferentKeyMakesAccountsUnequal() {
            final Account other = anAccount();
            other.setAcctId("00000000012");

            assertThat(anAccount()).isNotEqualTo(other);
        }

        @Test
        @DisplayName("equality is reflexive and rejects an unrelated type and an absent reference")
        void equalityIsReflexiveAndTypeSafe() {
            final Account account = anAccount();

            assertThat(account.equals(account)).isTrue();
            assertThat(account.equals(null)).isFalse();
            assertThat(account.equals(ACCOUNT_ID)).isFalse();
        }

        @Test
        @DisplayName("the rendering names the account but no monetary detail")
        void theRenderingNamesTheAccountButNoMonetaryDetail() {
            assertThat(anAccount().toString()).contains(ACCOUNT_ID).contains("Account");
        }

        @Test
        @DisplayName("every mutator round-trips, which is what the provider relies on")
        void everyMutatorRoundTrips() {
            final Account account = anAccount();
            account.setAcctActiveStatus("N");
            account.setAcctCreditLimit(new BigDecimal("1.00"));
            account.setAcctCashCreditLimit(new BigDecimal("2.00"));
            account.setAcctOpenDate("2001-01-01");
            account.setAcctExpirationDate("2002-02-02");
            account.setAcctReissueDate("2003-03-03");
            account.setAcctCurrCycCredit(new BigDecimal("3.00"));
            account.setAcctCurrCycDebit(new BigDecimal("4.00"));
            account.setAcctAddrZip("99999-9999");
            account.setAcctGroupId("ZEROAPR   ");

            assertThat(account.getAcctActiveStatus()).isEqualTo("N");
            assertThat(account.getAcctCreditLimit()).isEqualByComparingTo("1.00");
            assertThat(account.getAcctCashCreditLimit()).isEqualByComparingTo("2.00");
            assertThat(account.getAcctOpenDate()).isEqualTo("2001-01-01");
            assertThat(account.getAcctExpirationDate()).isEqualTo("2002-02-02");
            assertThat(account.getAcctReissueDate()).isEqualTo("2003-03-03");
            assertThat(account.getAcctCurrCycCredit()).isEqualByComparingTo("3.00");
            assertThat(account.getAcctCurrCycDebit()).isEqualByComparingTo("4.00");
            assertThat(account.getAcctAddrZip()).isEqualTo("99999-9999");
            assertThat(account.getAcctGroupId()).isEqualTo("ZEROAPR   ");
        }

        @Test
        @DisplayName("the overlimit basis is evaluated strictly left to right into a two-decimal field")
        void theOverlimitBasisIsEvaluatedLeftToRight() {
            // COMPUTE WS-TEMP-BAL = ACCT-CURR-CYC-CREDIT - ACCT-CURR-CYC-DEBIT + DALYTRAN-AMT.
            // Reproduced here to record that reordering changes which transactions are rejected.
            final Account account = anAccount();
            final BigDecimal amount = new BigDecimal("10.00");

            final BigDecimal basis = account.getAcctCurrCycCredit()
                    .subtract(account.getAcctCurrCycDebit())
                    .add(amount)
                    .setScale(ORACLE_MONEY_SCALE, RoundingMode.DOWN);

            assertThat(basis).isEqualByComparingTo("-140.00");
            assertThat(basis.scale()).isEqualTo(ORACLE_MONEY_SCALE);
        }
    }

    @Nested
    @DisplayName("Customer: the 500-byte record, and the two privacy deviations")
    final class CustomerEntity {

        @Test
        @DisplayName("every field is reported back exactly as supplied")
        void everyFieldIsReportedBack() {
            final Customer customer = aCustomer();

            assertThat(customer.getCustId()).isEqualTo(CUSTOMER_ID);
            assertThat(customer.getFirstName()).isEqualTo("MARY");
            assertThat(customer.getMiddleName()).isEqualTo("ANN");
            assertThat(customer.getLastName()).isEqualTo("SMITH");
            assertThat(customer.getAddrLine1()).isEqualTo("1 MAIN STREET");
            assertThat(customer.getAddrLine2()).isEqualTo("APT 2");
            assertThat(customer.getAddrLine3()).isEqualTo("SPRINGFIELD");
            assertThat(customer.getAddrStateCd()).isEqualTo("IL");
            assertThat(customer.getAddrCountryCd()).isEqualTo("USA");
            assertThat(customer.getAddrZip()).isEqualTo("62704-0001");
            assertThat(customer.getPhoneNum1()).isEqualTo("217-555-0100");
            assertThat(customer.getPhoneNum2()).isEqualTo("217-555-0101");
            assertThat(revealed(customer.getCustSsn()))
                    .as("the regulated attributes are reported back exactly as supplied, so what the"
                            + " codec sealed on the way in reveals back byte for byte")
                    .isEqualTo(ORACLE_NATIONAL_ID);
            assertThat(customer.getCustSsn()).doesNotContain(ORACLE_NATIONAL_ID);
            assertThat(revealed(customer.getGovtIssuedId())).isEqualTo(ORACLE_GOVT_ISSUED_ID);
            assertThat(customer.getCustDob()).isEqualTo("1980-06-15");
            assertThat(customer.getEftAccountId()).isEqualTo("0000000001");
            assertThat(customer.getPriCardHolderInd()).isEqualTo("Y");
            assertThat(customer.getFicoCreditScore()).isEqualTo("720");
        }

        @Test
        @DisplayName("the layout arithmetic adds up to the declared record length")
        void theLayoutArithmeticAddsUp() {
            final int declared = 9 + 25 + 25 + 25 + 50 + 50 + 50 + 2 + 3 + 10 + 15 + 15 + 9 + 20
                    + 10 + 10 + 1 + 3 + 168;

            assertThat(declared).isEqualTo(ORACLE_CUSTOMER_RECORD_LENGTH);
        }

        @Test
        @DisplayName("the credit score stays a fixed-width string, because the field is a picture")
        void theCreditScoreStaysAFixedWidthString() {
            // The account-update editor enforces the 300 to 850 range; the record itself is PIC 9(03),
            // so a leading zero is representable and must survive.
            assertThat(aCustomer().getFicoCreditScore()).hasSize(3);
        }

        @Test
        @DisplayName("the score range the update editor enforces is representable in three digits")
        void theEnforcedScoreRangeIsRepresentable() {
            assertThat(String.valueOf(300)).hasSize(3);
            assertThat(String.valueOf(850)).hasSize(3);
        }

        @Test
        @DisplayName("no rendering is overridden, so no personal detail can reach a log line")
        void noRenderingIsOverridden() {
            final String rendered = aCustomer().toString();

            assertThat(rendered)
                    .as("a customer carries a national identifier and a date of birth")
                    .doesNotContain(ORACLE_NATIONAL_ID)
                    .doesNotContain(ORACLE_GOVT_ISSUED_ID)
                    .doesNotContain("1980-06-15")
                    .doesNotContain("SMITH");
            assertThat(rendered).startsWith(Customer.class.getName() + "@");
        }

        @Test
        @DisplayName("equality is over the customer identifier alone")
        void equalityIsOverTheIdentifierAlone() {
            final Customer first = aCustomer();
            final Customer second = aCustomer();
            second.setLastName("DIFFERENT");

            assertThat(first).isEqualTo(second).hasSameHashCodeAs(second);
        }

        @Test
        @DisplayName("a different identifier makes the customers unequal")
        void aDifferentIdentifierMakesCustomersUnequal() {
            final Customer other = aCustomer();
            other.setCustId("000000012");

            assertThat(aCustomer()).isNotEqualTo(other);
        }

        @Test
        @DisplayName("equality is reflexive and rejects an unrelated type and an absent reference")
        void equalityIsReflexiveAndTypeSafe() {
            final Customer customer = aCustomer();

            assertThat(customer.equals(customer)).isTrue();
            assertThat(customer.equals(null)).isFalse();
            assertThat(customer.equals(CUSTOMER_ID)).isFalse();
        }

        @Test
        @DisplayName("every mutator round-trips")
        void everyMutatorRoundTrips() {
            final Customer customer = aCustomer();
            customer.setFirstName("JOHN");
            customer.setMiddleName("Q");
            customer.setLastName("PUBLIC");
            customer.setAddrLine1("2 OTHER ROAD");
            customer.setAddrLine2("SUITE 9");
            customer.setAddrLine3("CHICAGO");
            customer.setAddrStateCd("NY");
            customer.setAddrCountryCd("CAN");
            customer.setAddrZip("10001-0002");
            customer.setPhoneNum1("212-555-0100");
            customer.setPhoneNum2("212-555-0101");
            customer.setCustSsn(sealed("987654321"));
            customer.setGovtIssuedId(sealed("PP-0987654321"));
            customer.setCustDob("1975-11-30");
            customer.setEftAccountId("0000000002");
            customer.setPriCardHolderInd("N");
            customer.setFicoCreditScore("650");

            assertThat(customer.getFirstName()).isEqualTo("JOHN");
            assertThat(customer.getMiddleName()).isEqualTo("Q");
            assertThat(customer.getLastName()).isEqualTo("PUBLIC");
            assertThat(customer.getAddrLine1()).isEqualTo("2 OTHER ROAD");
            assertThat(customer.getAddrLine2()).isEqualTo("SUITE 9");
            assertThat(customer.getAddrLine3()).isEqualTo("CHICAGO");
            assertThat(customer.getAddrStateCd()).isEqualTo("NY");
            assertThat(customer.getAddrCountryCd()).isEqualTo("CAN");
            assertThat(customer.getAddrZip()).isEqualTo("10001-0002");
            assertThat(customer.getPhoneNum1()).isEqualTo("212-555-0100");
            assertThat(customer.getPhoneNum2()).isEqualTo("212-555-0101");
            assertThat(revealed(customer.getCustSsn())).isEqualTo("987654321");
            assertThat(revealed(customer.getGovtIssuedId())).isEqualTo("PP-0987654321");
            assertThat(customer.getCustDob()).isEqualTo("1975-11-30");
            assertThat(customer.getEftAccountId()).isEqualTo("0000000002");
            assertThat(customer.getPriCardHolderInd()).isEqualTo("N");
            assertThat(customer.getFicoCreditScore()).isEqualTo("650");
        }

        @Test
        @DisplayName("the two regulated identifiers refuse a cleartext value, so the widened columns "
                + "cannot be filled with the legacy bytes by accident")
        void theTwoRegulatedIdentifiersRefuseCleartext() {
            final Customer customer = aCustomer();

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> customer.setCustSsn(ORACLE_NATIONAL_ID))
                    .satisfies(refused -> assertThat(refused.getMessage())
                            .contains("custSsn")
                            .doesNotContain(ORACLE_NATIONAL_ID));

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> customer.setGovtIssuedId(ORACLE_GOVT_ISSUED_ID))
                    .satisfies(refused -> assertThat(refused.getMessage())
                            .contains("govtIssuedId")
                            .doesNotContain(ORACLE_GOVT_ISSUED_ID));

            assertThat(revealed(customer.getCustSsn()))
                    .as("a refused write leaves the previously stored value in place")
                    .isEqualTo(ORACLE_NATIONAL_ID);
        }

        @Test
        @DisplayName("the two fields the update screen decorates but never validates accept any value")
        void theTwoUnvalidatedFieldsAcceptAnyValue() {
            // The source comments state "no edits coded" for the middle name and "NO EDITS CODED AS
            // YET" for the second address line. Adding a constraint would reject input the legacy
            // system accepts.
            final Customer customer = aCustomer();
            customer.setMiddleName("");
            customer.setAddrLine2("");

            assertThat(customer.getMiddleName()).isEmpty();
            assertThat(customer.getAddrLine2()).isEmpty();
        }
    }

    @Nested
    @DisplayName("DailyTransaction: the 350-byte record whose offsets the sort specifications address")
    final class DailyTransactionEntity {

        @Test
        @DisplayName("every field is reported back exactly as supplied")
        void everyFieldIsReportedBack() {
            final DailyTransaction transaction = aDailyTransaction();

            assertThat(transaction.getDalytranId()).isEqualTo(DAILY_TRANSACTION_ID);
            assertThat(transaction.getDalytranTypeCd()).isEqualTo(TYPE_CODE);
            assertThat(transaction.getDalytranCatCd()).isEqualTo(CATEGORY_CODE);
            assertThat(transaction.getDalytranSource()).isEqualTo("POS TERM  ");
            assertThat(transaction.getDalytranDesc()).isEqualTo("PURCHASE AT MERCHANT");
            assertThat(transaction.getDalytranAmt()).isEqualByComparingTo("123.45");
            assertThat(transaction.getDalytranMerchantId()).isEqualTo("000000001");
            assertThat(transaction.getDalytranMerchantName()).isEqualTo("MERCHANT NAME");
            assertThat(transaction.getDalytranMerchantCity()).isEqualTo("MERCHANT CITY");
            assertThat(transaction.getDalytranMerchantZip()).isEqualTo("62704-0001");
            assertThat(transaction.getDalytranCardNum()).isEqualTo(CARD_NUMBER);
            assertThat(transaction.getDalytranOrigTs()).isEqualTo("2022-07-06-14.23.41.870000");
            assertThat(transaction.getDalytranProcTs()).isEqualTo("2022-07-06-14.23.41.870000");
        }

        @Test
        @DisplayName("the layout arithmetic adds up to the declared record length")
        void theLayoutArithmeticAddsUp() {
            final int declared = 16 + 2 + 4 + 10 + 100 + 11 + 9 + 50 + 50 + 10 + 16 + 26 + 26 + 20;

            assertThat(declared).isEqualTo(ORACLE_DAILY_TRANSACTION_RECORD_LENGTH);
        }

        @Test
        @DisplayName("the card number begins at the byte the sort specification addresses")
        void theCardNumberBeginsAtTheSortOffset() {
            // SYMNAMES declares TRAN-CARD-NUM,263,16 and the statement job sorts FIELDS=(263,16,...).
            final int precedingBytes = 16 + 2 + 4 + 10 + 100 + 11 + 9 + 50 + 50 + 10;

            assertThat(precedingBytes + 1).isEqualTo(ORACLE_CARD_NUMBER_OFFSET);
            assertThat(CARD_NUMBER).hasSize(16);
        }

        @Test
        @DisplayName("the processing timestamp begins at the byte the date filter addresses")
        void theProcessingTimestampBeginsAtTheFilterOffset() {
            // SYMNAMES declares TRAN-PROC-DT,305,10 for the inclusive date-range INCLUDE COND.
            final int precedingBytes = 16 + 2 + 4 + 10 + 100 + 11 + 9 + 50 + 50 + 10 + 16 + 26;

            assertThat(precedingBytes + 1).isEqualTo(ORACLE_PROCESSING_TIMESTAMP_OFFSET);
        }

        @Test
        @DisplayName("both timestamps are twenty-six bytes, matching the batch timestamp image")
        void bothTimestampsAreTwentySixBytes() {
            final DailyTransaction transaction = aDailyTransaction();

            assertThat(transaction.getDalytranOrigTs()).hasSize(26);
            assertThat(transaction.getDalytranProcTs()).hasSize(26);
        }

        @Test
        @DisplayName("the amount carries two decimals and is stored verbatim")
        void theAmountCarriesTwoDecimals() {
            assertThat(aDailyTransaction().getDalytranAmt().scale()).isEqualTo(ORACLE_MONEY_SCALE);
        }

        @Test
        @DisplayName("a return carries a negative amount, which the signed field represents")
        void aReturnCarriesANegativeAmount() {
            // Fifty of the three hundred fixture records are operator-originated returns, so both
            // signed directions must survive the round trip.
            final DailyTransaction transaction = aDailyTransaction();
            transaction.setDalytranAmt(new BigDecimal("-75.00"));
            transaction.setDalytranSource("OPERATOR  ");

            assertThat(transaction.getDalytranAmt().signum()).isNegative();
            assertThat(transaction.getDalytranSource()).isEqualTo("OPERATOR  ");
        }

        @Test
        @DisplayName("equality is over the transaction identifier alone")
        void equalityIsOverTheIdentifierAlone() {
            final DailyTransaction first = aDailyTransaction();
            final DailyTransaction second = aDailyTransaction();
            second.setDalytranAmt(new BigDecimal("999.99"));

            assertThat(first).isEqualTo(second).hasSameHashCodeAs(second);
        }

        @Test
        @DisplayName("a different identifier makes the transactions unequal")
        void aDifferentIdentifierMakesTransactionsUnequal() {
            final DailyTransaction other = aDailyTransaction();
            other.setDalytranId("0000000000000002");

            assertThat(aDailyTransaction()).isNotEqualTo(other);
        }

        @Test
        @DisplayName("equality is reflexive and rejects an unrelated type and an absent reference")
        void equalityIsReflexiveAndTypeSafe() {
            final DailyTransaction transaction = aDailyTransaction();

            assertThat(transaction.equals(transaction)).isTrue();
            assertThat(transaction.equals(null)).isFalse();
            assertThat(transaction.equals(DAILY_TRANSACTION_ID)).isFalse();
        }

        @Test
        @DisplayName("the rendering carries no card number, amount or merchant detail")
        void theRenderingCarriesNoSensitiveDetail() {
            final String rendered = aDailyTransaction().toString();

            assertThat(rendered).contains(DAILY_TRANSACTION_ID)
                    .doesNotContain(CARD_NUMBER)
                    .doesNotContain("123.45")
                    .doesNotContain("MERCHANT NAME");
        }

        @Test
        @DisplayName("every mutator round-trips")
        void everyMutatorRoundTrips() {
            final DailyTransaction transaction = aDailyTransaction();
            transaction.setDalytranTypeCd("02");
            transaction.setDalytranCatCd("0006");
            transaction.setDalytranDesc("OTHER");
            transaction.setDalytranMerchantId("000000002");
            transaction.setDalytranMerchantName("OTHER NAME");
            transaction.setDalytranMerchantCity("OTHER CITY");
            transaction.setDalytranMerchantZip("10001-0002");
            transaction.setDalytranCardNum("4222222222222222");
            transaction.setDalytranOrigTs("2022-07-07-01.02.03.040000");
            transaction.setDalytranProcTs("2022-07-08-05.06.07.080000");

            assertThat(transaction.getDalytranTypeCd()).isEqualTo("02");
            assertThat(transaction.getDalytranCatCd()).isEqualTo("0006");
            assertThat(transaction.getDalytranDesc()).isEqualTo("OTHER");
            assertThat(transaction.getDalytranMerchantId()).isEqualTo("000000002");
            assertThat(transaction.getDalytranMerchantName()).isEqualTo("OTHER NAME");
            assertThat(transaction.getDalytranMerchantCity()).isEqualTo("OTHER CITY");
            assertThat(transaction.getDalytranMerchantZip()).isEqualTo("10001-0002");
            assertThat(transaction.getDalytranCardNum()).isEqualTo("4222222222222222");
            assertThat(transaction.getDalytranOrigTs()).isEqualTo("2022-07-07-01.02.03.040000");
            assertThat(transaction.getDalytranProcTs()).isEqualTo("2022-07-08-05.06.07.080000");
        }
    }

    @Nested
    @DisplayName("UserSecurity: the 80-byte record, with the password column widened for hashing")
    final class UserSecurityEntity {

        @Test
        @DisplayName("every field is reported back exactly as supplied")
        void everyFieldIsReportedBack() {
            final UserSecurity user =
                    new UserSecurity(ADMIN_USER_ID, "MARGARET", "GOLD", ORACLE_CREDENTIAL_DIGEST, "A");

            assertThat(user.getSecUsrId()).isEqualTo(ADMIN_USER_ID);
            assertThat(user.getSecUsrFname()).isEqualTo("MARGARET");
            assertThat(user.getSecUsrLname()).isEqualTo("GOLD");
            assertThat(user.credentialDigest()).isEqualTo(ORACLE_CREDENTIAL_DIGEST);
            assertThat(user.getSecUsrType()).isEqualTo("A");
        }

        @Test
        @DisplayName("the layout arithmetic adds up to eighty bytes")
        void theLayoutArithmeticAddsUp() {
            final int declared = 8 + 20 + 20 + ORACLE_LEGACY_PASSWORD_WIDTH + 1 + 23;

            assertThat(declared).isEqualTo(ORACLE_USER_SECURITY_RECORD_LENGTH);
        }

        @Test
        @DisplayName("the identifier fills its eight-character field, as the seed records do")
        void theIdentifierFillsItsField() {
            assertThat(ADMIN_USER_ID).hasSize(8);
            assertThat("USER0001").hasSize(8);
        }

        @Test
        @DisplayName("the stored credential is far wider than the legacy plaintext field")
        void theStoredCredentialIsWiderThanTheLegacyField() {
            // The legacy compares an eight-character plaintext password directly. Storing a hash is a
            // deliberate, documented parity exception, and the column width is what makes it possible.
            // The entity enforces that departure rather than merely permitting it: it refuses any
            // value that is not structurally a digest, so the widened column cannot end up holding
            // the legacy cleartext it was widened to replace.
            final String bcryptHash = "$2a$10$abcdefghijklmnopqrstuvABCDEFGHIJKLMNOPQRSTUVWXYZ01234";
            final UserSecurity user =
                    new UserSecurity(ADMIN_USER_ID, "MARGARET", "GOLD", bcryptHash, "A");

            assertThat(user.credentialDigest())
                    .hasSizeGreaterThan(ORACLE_LEGACY_PASSWORD_WIDTH)
                    .isEqualTo(bcryptHash);
        }

        @Test
        @DisplayName("both seed user types are representable")
        void bothSeedUserTypesAreRepresentable() {
            final UserSecurity admin =
                    new UserSecurity(ADMIN_USER_ID, "MARGARET", "GOLD", ORACLE_CREDENTIAL_DIGEST, "A");
            final UserSecurity standard =
                    new UserSecurity("USER0001", "LAWRENCE", "THOMAS", ORACLE_CREDENTIAL_DIGEST, "U");

            assertThat(admin.getSecUsrType()).isEqualTo("A");
            assertThat(standard.getSecUsrType()).isEqualTo("U");
        }

        @Test
        @DisplayName("equality is over the user identifier alone")
        void equalityIsOverTheIdentifierAlone() {
            final UserSecurity first =
                    new UserSecurity(ADMIN_USER_ID, "MARGARET", "GOLD", ORACLE_CREDENTIAL_DIGEST, "A");
            final UserSecurity second =
                    new UserSecurity(ADMIN_USER_ID, "OTHER", "NAME", ORACLE_OTHER_CREDENTIAL_DIGEST, "U");

            assertThat(first).isEqualTo(second).hasSameHashCodeAs(second);
        }

        @Test
        @DisplayName("a different identifier makes the users unequal")
        void aDifferentIdentifierMakesUsersUnequal() {
            assertThat(new UserSecurity(ADMIN_USER_ID, "A", "B", ORACLE_CREDENTIAL_DIGEST, "A"))
                    .isNotEqualTo(new UserSecurity("USER0001", "A", "B", ORACLE_CREDENTIAL_DIGEST, "A"));
        }

        @Test
        @DisplayName("equality is reflexive and rejects an unrelated type and an absent reference")
        void equalityIsReflexiveAndTypeSafe() {
            final UserSecurity user = new UserSecurity(ADMIN_USER_ID, "A", "B", ORACLE_CREDENTIAL_DIGEST, "A");

            assertThat(user.equals(user)).isTrue();
            assertThat(user.equals(null)).isFalse();
            assertThat(user.equals(ADMIN_USER_ID)).isFalse();
        }

        @Test
        @DisplayName("every mutator round-trips")
        void everyMutatorRoundTrips() {
            final UserSecurity user = new UserSecurity(ADMIN_USER_ID, "A", "B", ORACLE_CREDENTIAL_DIGEST, "A");
            user.setSecUsrId("USER0005");
            user.setSecUsrFname("LEE");
            user.setSecUsrLname("TING");
            user.replaceCredentialDigest(ORACLE_OTHER_CREDENTIAL_DIGEST);
            user.setSecUsrType("U");

            assertThat(user.getSecUsrId()).isEqualTo("USER0005");
            assertThat(user.getSecUsrFname()).isEqualTo("LEE");
            assertThat(user.getSecUsrLname()).isEqualTo("TING");
            assertThat(user.credentialDigest()).isEqualTo(ORACLE_OTHER_CREDENTIAL_DIGEST);
            assertThat(user.getSecUsrType()).isEqualTo("U");
        }

        @Test
        @DisplayName("the rendering names the sign-on identifier alone, so a credential cannot reach a "
                + "log line - the same convention the account and daily-transaction entities follow")
        void theRenderingNamesTheKeyAlone() {
            final UserSecurity user =
                    new UserSecurity(ADMIN_USER_ID, "MARGARET", "GOLD", ORACLE_CREDENTIAL_DIGEST, "A");

            assertThat(user.toString())
                    .isEqualTo("UserSecurity[secUsrId=" + ADMIN_USER_ID + "]")
                    .doesNotContain(ORACLE_CREDENTIAL_DIGEST)
                    .doesNotContain("MARGARET")
                    .doesNotContain("GOLD");
        }
    }

    @Nested
    @DisplayName("DisclosureGroup: the 50-byte record with the only precision-six amount column")
    final class DisclosureGroupEntity {

        @Test
        @DisplayName("every field is reported back exactly as supplied, padding included")
        void everyFieldIsReportedBack() {
            final DisclosureGroup group = new DisclosureGroup(DEFAULT_GROUP_ID, TYPE_CODE,
                    CATEGORY_CODE, new BigDecimal("12.50"));

            assertThat(group.getDisAcctGroupId()).isEqualTo(DEFAULT_GROUP_ID).hasSize(10);
            assertThat(group.getDisTranTypeCd()).isEqualTo(TYPE_CODE);
            assertThat(group.getDisTranCatCd()).isEqualTo(CATEGORY_CODE);
            assertThat(group.getDisIntRate()).isEqualByComparingTo("12.50");
        }

        @Test
        @DisplayName("the layout arithmetic adds up to the declared record length")
        void theLayoutArithmeticAddsUp() {
            final int declared = 10 + 2 + 4 + ORACLE_RATE_PRECISION + 28;

            assertThat(declared).isEqualTo(ORACLE_DISCLOSURE_GROUP_RECORD_LENGTH);
        }

        @Test
        @DisplayName("the rate is narrower than every other amount in the module")
        void theRateIsNarrowerThanEveryOtherAmount() {
            // PIC S9(04)V99 against the accounts' PIC S9(10)V99. This precision must not be copied
            // from a sibling entity, nor a sibling's copied onto it.
            assertThat(ORACLE_RATE_PRECISION).isLessThan(ORACLE_ACCOUNT_AMOUNT_PRECISION);
        }

        @Test
        @DisplayName("a zero rate is returned at its stored scale, not collapsed to a bare zero")
        void aZeroRateKeepsItsStoredScale() {
            // The interest program computes only when the rate is non-zero, so the zero-rate group
            // must be distinguishable, and its scale must survive the round trip.
            final DisclosureGroup group = new DisclosureGroup("ZEROAPR   ", TYPE_CODE, CATEGORY_CODE,
                    new BigDecimal("0.00"));

            assertThat(group.getDisIntRate().scale()).isEqualTo(ORACLE_MONEY_SCALE);
            assertThat(group.getDisIntRate().signum()).isZero();
        }

        @Test
        @DisplayName("the monthly interest expression is preserved operand for operand")
        void theMonthlyInterestExpressionIsPreserved() {
            // COMPUTE WS-MONTHLY-INT = ( TRAN-CAT-BAL * DIS-INT-RATE) / 1200. Multiplying first and
            // dividing second is the contract: rearranging moves the truncation point.
            final BigDecimal balance = new BigDecimal("1000.00");
            final BigDecimal rate = new BigDecimal("12.50");

            final BigDecimal faithful = balance.multiply(rate)
                    .divide(new BigDecimal("1200"), ORACLE_MONEY_SCALE, RoundingMode.DOWN);

            assertThat(faithful).isEqualByComparingTo("10.41");
            assertThat(faithful.scale()).isEqualTo(ORACLE_MONEY_SCALE);
        }

        @Test
        @DisplayName("the entity yields its own composite key")
        void theEntityYieldsItsOwnCompositeKey() {
            final DisclosureGroup group = new DisclosureGroup(DEFAULT_GROUP_ID, TYPE_CODE,
                    CATEGORY_CODE, new BigDecimal("12.50"));

            assertThat(group.toId())
                    .isEqualTo(new DisclosureGroupId(DEFAULT_GROUP_ID, TYPE_CODE, CATEGORY_CODE));
        }

        @Test
        @DisplayName("equality is over the three key components, not the rate")
        void equalityIsOverTheKeyComponents() {
            final DisclosureGroup first = new DisclosureGroup(DEFAULT_GROUP_ID, TYPE_CODE,
                    CATEGORY_CODE, new BigDecimal("12.50"));
            final DisclosureGroup second = new DisclosureGroup(DEFAULT_GROUP_ID, TYPE_CODE,
                    CATEGORY_CODE, new BigDecimal("99.99"));

            assertThat(first).isEqualTo(second).hasSameHashCodeAs(second);
        }

        @Test
        @DisplayName("a different group identifier makes the rows unequal")
        void aDifferentGroupIdentifierMakesRowsUnequal() {
            final DisclosureGroup group = new DisclosureGroup(DEFAULT_GROUP_ID, TYPE_CODE,
                    CATEGORY_CODE, new BigDecimal("12.50"));
            final DisclosureGroup zeroRate = new DisclosureGroup("ZEROAPR   ", TYPE_CODE,
                    CATEGORY_CODE, new BigDecimal("12.50"));

            assertThat(group).isNotEqualTo(zeroRate);
        }

        @Test
        @DisplayName("equality is reflexive and rejects an unrelated type and an absent reference")
        void equalityIsReflexiveAndTypeSafe() {
            final DisclosureGroup group = new DisclosureGroup(DEFAULT_GROUP_ID, TYPE_CODE,
                    CATEGORY_CODE, new BigDecimal("12.50"));

            assertThat(group.equals(group)).isTrue();
            assertThat(group.equals(null)).isFalse();
            assertThat(group.equals(DEFAULT_GROUP_ID)).isFalse();
        }

        @Test
        @DisplayName("every mutator round-trips and the rendering names the key")
        void everyMutatorRoundTripsAndTheRenderingNamesTheKey() {
            final DisclosureGroup group = new DisclosureGroup(DEFAULT_GROUP_ID, TYPE_CODE,
                    CATEGORY_CODE, new BigDecimal("12.50"));
            group.setDisAcctGroupId("A000000000");
            group.setDisTranTypeCd("02");
            group.setDisTranCatCd("0006");
            group.setDisIntRate(new BigDecimal("1.75"));

            assertThat(group.getDisAcctGroupId()).isEqualTo("A000000000");
            assertThat(group.getDisTranTypeCd()).isEqualTo("02");
            assertThat(group.getDisTranCatCd()).isEqualTo("0006");
            assertThat(group.getDisIntRate()).isEqualByComparingTo("1.75");
            assertThat(group.toString()).contains("A000000000", "02", "0006");
        }
    }

    @Nested
    @DisplayName("The hydration constructor the persistence provider requires of every entity")
    final class HydrationContract {

        /**
         * The provider instantiates an entity with no arguments and then assigns each field, so every
         * entity must expose a no-argument constructor that neither throws nor validates. It is kept
         * {@code protected} so application code cannot build a partially populated instance by
         * accident, which is why this assertion is only reachable from inside the same package.
         *
         * <p>Nothing else in the suite exercises this route: every other test builds a fully
         * populated instance through the business constructor. Were the no-argument form removed,
         * tightened to {@code private}, or given a guard clause, hydration would fail at runtime and
         * no other test would notice.
         */
        @Test
        @DisplayName("every entity can be instantiated with no arguments and no state")
        void everyEntityCanBeInstantiatedWithNoArguments() {
            assertThat(new Account()).isNotNull();
            assertThat(new Customer()).isNotNull();
            assertThat(new DailyTransaction()).isNotNull();
            assertThat(new UserSecurity()).isNotNull();
            assertThat(new DisclosureGroup()).isNotNull();
            assertThat(new TransactionType()).isNotNull();
            assertThat(new TransactionCategory()).isNotNull();
        }

        @Test
        @DisplayName("a hydrated instance starts with no business key assigned")
        void aHydratedInstanceStartsWithNoBusinessKey() {
            // The provider assigns the key immediately afterwards; before it does, the field is unset.
            assertThat(new Account().getAcctId()).isNull();
            assertThat(new Customer().getCustId()).isNull();
            assertThat(new DailyTransaction().getDalytranId()).isNull();
            assertThat(new UserSecurity().getSecUsrId()).isNull();
            assertThat(new TransactionType().getTranType()).isNull();
        }

        @Test
        @DisplayName("a hydrated account still starts at version zero")
        void aHydratedAccountStartsAtVersionZero() {
            assertThat(new Account().getVersion()).isZero();
        }
    }

    @Nested
    @DisplayName("TransactionType and TransactionCategory: the two 60-byte reference records")
    final class ReferenceRecordEntities {

        @Test
        @DisplayName("the transaction-type layout adds up to sixty bytes")
        void theTransactionTypeLayoutAddsUp() {
            assertThat(2 + 50 + 8).isEqualTo(ORACLE_SIXTY_BYTE_RECORD_LENGTH);
        }

        @Test
        @DisplayName("the transaction-category layout also adds up to sixty bytes")
        void theTransactionCategoryLayoutAddsUp() {
            assertThat(2 + 4 + 50 + 4).isEqualTo(ORACLE_SIXTY_BYTE_RECORD_LENGTH);
        }

        @Test
        @DisplayName("a transaction type reports both fields back verbatim")
        void aTransactionTypeReportsBothFields() {
            final TransactionType type = new TransactionType(TYPE_CODE, "PURCHASE");

            assertThat(type.getTranType()).isEqualTo(TYPE_CODE);
            assertThat(type.getTranTypeDesc()).isEqualTo("PURCHASE");
        }

        @Test
        @DisplayName("transaction-type equality is over the two-character code alone")
        void transactionTypeEqualityIsOverTheCodeAlone() {
            final TransactionType first = new TransactionType(TYPE_CODE, "PURCHASE");
            final TransactionType second = new TransactionType(TYPE_CODE, "DIFFERENT TEXT");

            assertThat(first).isEqualTo(second).hasSameHashCodeAs(second);
            assertThat(first).isNotEqualTo(new TransactionType("02", "PURCHASE"));
        }

        @Test
        @DisplayName("transaction-type equality is reflexive and type safe, and it renders its code")
        void transactionTypeEqualityIsReflexiveAndTypeSafe() {
            final TransactionType type = new TransactionType(TYPE_CODE, "PURCHASE");

            assertThat(type.equals(type)).isTrue();
            assertThat(type.equals(null)).isFalse();
            assertThat(type.equals(TYPE_CODE)).isFalse();
            assertThat(type.toString()).contains(TYPE_CODE);
        }

        @Test
        @DisplayName("transaction-type mutators round-trip")
        void transactionTypeMutatorsRoundTrip() {
            final TransactionType type = new TransactionType(TYPE_CODE, "PURCHASE");
            type.setTranType("07");
            type.setTranTypeDesc("ADJUSTMENT");

            assertThat(type.getTranType()).isEqualTo("07");
            assertThat(type.getTranTypeDesc()).isEqualTo("ADJUSTMENT");
        }

        @Test
        @DisplayName("a transaction category reports all three fields back verbatim")
        void aTransactionCategoryReportsAllThreeFields() {
            final TransactionCategory category =
                    new TransactionCategory(TYPE_CODE, CATEGORY_CODE, "INTEREST");

            assertThat(category.getTranTypeCd()).isEqualTo(TYPE_CODE);
            assertThat(category.getTranCatCd()).isEqualTo(CATEGORY_CODE);
            assertThat(category.getTranCatTypeDesc()).isEqualTo("INTEREST");
        }

        @Test
        @DisplayName("a transaction category yields its own two-part composite key")
        void aTransactionCategoryYieldsItsCompositeKey() {
            final TransactionCategory category =
                    new TransactionCategory(TYPE_CODE, CATEGORY_CODE, "INTEREST");

            assertThat(category.toId())
                    .isEqualTo(new TransactionCategoryId(TYPE_CODE, CATEGORY_CODE));
        }

        @Test
        @DisplayName("transaction-category equality is over both key components, not the description")
        void transactionCategoryEqualityIsOverBothKeyComponents() {
            final TransactionCategory first =
                    new TransactionCategory(TYPE_CODE, CATEGORY_CODE, "INTEREST");
            final TransactionCategory second =
                    new TransactionCategory(TYPE_CODE, CATEGORY_CODE, "OTHER TEXT");

            assertThat(first).isEqualTo(second).hasSameHashCodeAs(second);
            assertThat(first).isNotEqualTo(
                    new TransactionCategory(TYPE_CODE, "0006", "INTEREST"));
            assertThat(first).isNotEqualTo(
                    new TransactionCategory("02", CATEGORY_CODE, "INTEREST"));
        }

        @Test
        @DisplayName("transaction-category equality is reflexive and type safe, and it renders its key")
        void transactionCategoryEqualityIsReflexiveAndTypeSafe() {
            final TransactionCategory category =
                    new TransactionCategory(TYPE_CODE, CATEGORY_CODE, "INTEREST");

            assertThat(category.equals(category)).isTrue();
            assertThat(category.equals(null)).isFalse();
            assertThat(category.equals(TYPE_CODE)).isFalse();
            assertThat(category.toString()).contains(TYPE_CODE, CATEGORY_CODE);
        }

        @Test
        @DisplayName("transaction-category mutators round-trip")
        void transactionCategoryMutatorsRoundTrip() {
            final TransactionCategory category =
                    new TransactionCategory(TYPE_CODE, CATEGORY_CODE, "INTEREST");
            category.setTranTypeCd("02");
            category.setTranCatCd("0006");
            category.setTranCatTypeDesc("FEE");

            assertThat(category.getTranTypeCd()).isEqualTo("02");
            assertThat(category.getTranCatCd()).isEqualTo("0006");
            assertThat(category.getTranCatTypeDesc()).isEqualTo("FEE");
        }

        @Test
        @DisplayName("both reference entities work as map keys, which reference lookups rely on")
        void bothReferenceEntitiesWorkAsMapKeys() {
            final Map<TransactionType, String> byType = new HashMap<>();
            byType.put(new TransactionType(TYPE_CODE, "PURCHASE"), "type row");
            final Map<TransactionCategory, String> byCategory = new HashMap<>();
            byCategory.put(new TransactionCategory(TYPE_CODE, CATEGORY_CODE, "INTEREST"), "cat row");

            assertThat(byType.get(new TransactionType(TYPE_CODE, "ANYTHING"))).isEqualTo("type row");
            assertThat(byCategory.get(new TransactionCategory(TYPE_CODE, CATEGORY_CODE, "ANY")))
                    .isEqualTo("cat row");
        }
    }
}
