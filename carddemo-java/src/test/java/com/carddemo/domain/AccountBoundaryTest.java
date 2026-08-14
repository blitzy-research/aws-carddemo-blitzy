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
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link Account}, the three-hundred-byte account record.
 *
 * <h2>What is under test</h2>
 *
 * <p>Copybook {@code app/cpy/CVACT01Y.cpy} describes a three-hundred-byte record whose
 * eleven-byte identifier occupies offset zero, corroborated by the cluster definition in
 * {@code app/jcl/ACCTFILE.jcl} and by the file description of the sequential reader
 * {@code app/cbl/CBACT01C.cbl}, which splits the same image into an eleven-byte key and a
 * two-hundred-eighty-nine-byte remainder. The ASCII fixture
 * {@code app/data/ASCII/acctdata.txt} carries fifty such records in 15,050 bytes.</p>
 *
 * <h2>Why every monetary attribute is a scaled decimal and is never rescaled here</h2>
 *
 * <p>Five fields of the legacy record — the current balance, the credit limit, the cash
 * credit limit and the two cycle totals — are declared {@code PIC S9(10)V99} zoned
 * decimal. No {@code ROUNDED} clause appears anywhere in the estate, so scaling and
 * truncation belong to the zoned-decimal codec of the utility layer and not to this
 * entity. The entity stores whatever scale it is handed, which the assertions below prove
 * directly, and which is what keeps a negative balance and a zero balance both loadable.</p>
 *
 * <h2>Why the misspelled legacy field name survives in the column while the property does not</h2>
 *
 * <p>The legacy record misspells its expiry field, and that spelling is preserved in the
 * record layout because the fixed-width offsets depend on the layout rather than on the
 * name. The Java property is spelled correctly; the correspondence is recorded in the
 * decision log rather than in code.</p>
 *
 * <h2>Why identity is the business key and the version counter has no setter</h2>
 *
 * <p>Optimistic locking replaces the legacy before-and-after image comparison, so the
 * version counter is owned by the persistence provider and exposed read-only. Identity is
 * the account identifier alone: including a mutable amount would let an instance change
 * its own hash across a flush. A test below mutates every non-key attribute of a stored
 * instance and proves it is still retrievable.</p>
 */
@DisplayName("Account: the three-hundred-byte account record")
class AccountBoundaryTest {

    /** Account identifier at its contractual eleven characters, leading zeros included. */
    private static final String ACCOUNT_ID = "00000000001";

    /** The single-character active status the legacy record carries at offset eleven. */
    private static final String ACTIVE_STATUS = "Y";

    /** Current balance at the contractual scale of two. */
    private static final BigDecimal CURRENT_BALANCE = new BigDecimal("1234.56");

    /** Credit limit at the contractual scale of two. */
    private static final BigDecimal CREDIT_LIMIT = new BigDecimal("5000.00");

    /** Cash credit limit at the contractual scale of two. */
    private static final BigDecimal CASH_CREDIT_LIMIT = new BigDecimal("500.00");

    /** Open date at the contractual ten characters. */
    private static final String OPEN_DATE = "2020-01-01";

    /** Expiry date at the contractual ten characters. */
    private static final String EXPIRATION_DATE = "2025-12-31";

    /** Reissue date at the contractual ten characters. */
    private static final String REISSUE_DATE = "2023-06-30";

    /** Current cycle credit total at the contractual scale of two. */
    private static final BigDecimal CYCLE_CREDIT = new BigDecimal("100.00");

    /** Current cycle debit total at the contractual scale of two. */
    private static final BigDecimal CYCLE_DEBIT = new BigDecimal("50.00");

    /** Address ZIP at the contractual ten characters, trailing padding included. */
    private static final String ADDRESS_ZIP = "12345     ";

    /** Account group identifier at the contractual ten characters, trailing padding included. */
    private static final String GROUP_ID = "A         ";

    /**
     * Builds the reference account used across the assertions.
     *
     * @return a fully populated account
     */
    private static Account referenceAccount() {
        return new Account(ACCOUNT_ID, ACTIVE_STATUS, CURRENT_BALANCE, CREDIT_LIMIT,
                CASH_CREDIT_LIMIT, OPEN_DATE, EXPIRATION_DATE, REISSUE_DATE,
                CYCLE_CREDIT, CYCLE_DEBIT, ADDRESS_ZIP, GROUP_ID);
    }

    @Nested
    @DisplayName("attribute carriage")
    class AttributeCarriage {

        @Test
        @DisplayName("all twelve constructor attributes are returned exactly as supplied")
        void allTwelveAttributesAreReturnedAsSupplied() {
            Account account = referenceAccount();

            assertThat(account.getAcctId()).isEqualTo(ACCOUNT_ID);
            assertThat(account.getAcctActiveStatus()).isEqualTo(ACTIVE_STATUS);
            assertThat(account.getAcctCurrBal()).isEqualTo(CURRENT_BALANCE);
            assertThat(account.getAcctCreditLimit()).isEqualTo(CREDIT_LIMIT);
            assertThat(account.getAcctCashCreditLimit()).isEqualTo(CASH_CREDIT_LIMIT);
            assertThat(account.getAcctOpenDate()).isEqualTo(OPEN_DATE);
            assertThat(account.getAcctExpirationDate()).isEqualTo(EXPIRATION_DATE);
            assertThat(account.getAcctReissueDate()).isEqualTo(REISSUE_DATE);
            assertThat(account.getAcctCurrCycCredit()).isEqualTo(CYCLE_CREDIT);
            assertThat(account.getAcctCurrCycDebit()).isEqualTo(CYCLE_DEBIT);
            assertThat(account.getAcctAddrZip()).isEqualTo(ADDRESS_ZIP);
            assertThat(account.getAcctGroupId()).isEqualTo(GROUP_ID);
        }

        @Test
        @DisplayName("the identifier keeps every leading zero, so it stays eleven characters wide")
        void theIdentifierKeepsEveryLeadingZero() {
            assertThat(referenceAccount().getAcctId())
                    .isEqualTo("00000000001")
                    .hasSize(11)
                    .isNotEqualTo("1");
        }

        @Test
        @DisplayName("the ZIP and group identifier keep their trailing padding")
        void thePaddedTextAttributesKeepTheirPadding() {
            Account account = referenceAccount();

            assertThat(account.getAcctAddrZip()).hasSize(10).endsWith("     ");
            assertThat(account.getAcctGroupId()).hasSize(10).endsWith("         ");
        }

        @Test
        @DisplayName("every monetary attribute is stored at the scale supplied and is not rescaled")
        void everyMonetaryAttributeKeepsItsSuppliedScale() {
            Account account = new Account(ACCOUNT_ID, ACTIVE_STATUS,
                    new BigDecimal("1.5"), new BigDecimal("2.5"), new BigDecimal("3.5"),
                    OPEN_DATE, EXPIRATION_DATE, REISSUE_DATE,
                    new BigDecimal("4.5"), new BigDecimal("5.5"), ADDRESS_ZIP, GROUP_ID);

            assertThat(account.getAcctCurrBal().scale()).isEqualTo(1);
            assertThat(account.getAcctCreditLimit()).isEqualTo(new BigDecimal("2.5"));
            assertThat(account.getAcctCashCreditLimit()).isEqualTo(new BigDecimal("3.5"));
            assertThat(account.getAcctCurrCycCredit()).isEqualTo(new BigDecimal("4.5"));
            assertThat(account.getAcctCurrCycDebit()).isEqualTo(new BigDecimal("5.5"));
        }

        @Test
        @DisplayName("a negative balance is stored rather than clamped, because the field is signed")
        void aNegativeBalanceIsStoredRatherThanClamped() {
            Account account = referenceAccount();

            account.setAcctCurrBal(new BigDecimal("-99.99"));

            assertThat(account.getAcctCurrBal()).isEqualTo(new BigDecimal("-99.99"));
        }

        @Test
        @DisplayName("a zero balance is stored, which the bill-payment rejection path depends on")
        void aZeroBalanceIsStored() {
            Account account = referenceAccount();

            account.setAcctCurrBal(new BigDecimal("0.00"));

            assertThat(account.getAcctCurrBal()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(account.getAcctCurrBal().scale()).isEqualTo(2);
        }

        @Test
        @DisplayName("the version counter starts at zero and is exposed read-only")
        void theVersionCounterStartsAtZero() {
            assertThat(referenceAccount().getVersion()).isZero();
            assertThat(new Account().getVersion()).isZero();
        }

        @Test
        @DisplayName("the no-argument constructor a provider needs yields an unpopulated account")
        void theNoArgumentConstructorYieldsAnUnpopulatedAccount() {
            Account account = new Account();

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

        @Test
        @DisplayName("every setter replaces its value verbatim, with no normalisation")
        void everySetterReplacesItsValueVerbatim() {
            Account account = new Account();

            account.setAcctId(" 1        ");
            account.setAcctActiveStatus("n");
            account.setAcctCurrBal(new BigDecimal("0.010"));
            account.setAcctCreditLimit(new BigDecimal("1.1"));
            account.setAcctCashCreditLimit(new BigDecimal("2.2"));
            account.setAcctOpenDate("1999-12-31");
            account.setAcctExpirationDate("2099-01-01");
            account.setAcctReissueDate("2000-02-29");
            account.setAcctCurrCycCredit(new BigDecimal("3.3"));
            account.setAcctCurrCycDebit(new BigDecimal("4.4"));
            account.setAcctAddrZip("  99999   ");
            account.setAcctGroupId("zeroapr   ");

            assertThat(account.getAcctId()).isEqualTo(" 1        ");
            assertThat(account.getAcctActiveStatus()).isEqualTo("n");
            assertThat(account.getAcctCurrBal()).isEqualTo(new BigDecimal("0.010"));
            assertThat(account.getAcctCreditLimit()).isEqualTo(new BigDecimal("1.1"));
            assertThat(account.getAcctCashCreditLimit()).isEqualTo(new BigDecimal("2.2"));
            assertThat(account.getAcctOpenDate()).isEqualTo("1999-12-31");
            assertThat(account.getAcctExpirationDate()).isEqualTo("2099-01-01");
            assertThat(account.getAcctReissueDate()).isEqualTo("2000-02-29");
            assertThat(account.getAcctCurrCycCredit()).isEqualTo(new BigDecimal("3.3"));
            assertThat(account.getAcctCurrCycDebit()).isEqualTo(new BigDecimal("4.4"));
            assertThat(account.getAcctAddrZip()).isEqualTo("  99999   ");
            assertThat(account.getAcctGroupId()).isEqualTo("zeroapr   ");
        }

        @Test
        @DisplayName("every attribute may be set back to null")
        void everyAttributeMayBeSetBackToNull() {
            Account account = referenceAccount();

            account.setAcctId(null);
            account.setAcctActiveStatus(null);
            account.setAcctCurrBal(null);
            account.setAcctCreditLimit(null);
            account.setAcctCashCreditLimit(null);
            account.setAcctOpenDate(null);
            account.setAcctExpirationDate(null);
            account.setAcctReissueDate(null);
            account.setAcctCurrCycCredit(null);
            account.setAcctCurrCycDebit(null);
            account.setAcctAddrZip(null);
            account.setAcctGroupId(null);

            assertThat(account.getAcctId()).isNull();
            assertThat(account.getAcctCurrBal()).isNull();
            assertThat(account.getAcctGroupId()).isNull();
        }
    }

    @Nested
    @DisplayName("identity derived from the business key alone")
    class Identity {

        @Test
        @DisplayName("an account equals itself")
        void anAccountEqualsItself() {
            Account account = referenceAccount();

            assertThat(account).isEqualTo(account);
        }

        @Test
        @DisplayName("two accounts sharing the identifier are equal in both directions")
        void twoAccountsSharingTheIdentifierAreEqual() {
            Account first = referenceAccount();
            Account second = referenceAccount();

            assertThat(first).isEqualTo(second);
            assertThat(second).isEqualTo(first);
        }

        @Test
        @DisplayName("equality is transitive across three accounts sharing the identifier")
        void equalityIsTransitive() {
            Account first = referenceAccount();
            Account second = referenceAccount();
            Account third = referenceAccount();

            assertThat(first).isEqualTo(second);
            assertThat(second).isEqualTo(third);
            assertThat(first).isEqualTo(third);
        }

        @Test
        @DisplayName("a differing identifier breaks equality")
        void aDifferingIdentifierBreaksEquality() {
            Account other = referenceAccount();
            other.setAcctId("00000000002");

            assertThat(referenceAccount()).isNotEqualTo(other);
        }

        @Test
        @DisplayName("a zero-suppressed identifier is not equal to its padded spelling")
        void aZeroSuppressedIdentifierIsNotEqual() {
            Account other = referenceAccount();
            other.setAcctId("1");

            assertThat(referenceAccount()).isNotEqualTo(other);
        }

        @Test
        @DisplayName("a null reference is not equal to any account")
        void aNullReferenceIsNotEqualToAnyAccount() {
            assertThat(referenceAccount()).isNotEqualTo(null);
        }

        @Test
        @DisplayName("an unrelated type is not equal to an account carrying the same identifier")
        void anUnrelatedTypeIsNotEqualToAnAccount() {
            assertThat(referenceAccount()).isNotEqualTo(ACCOUNT_ID);
        }

        @Test
        @DisplayName("two unpopulated accounts are equal, so a provider-instantiated row is coherent")
        void twoUnpopulatedAccountsAreEqual() {
            assertThat(new Account())
                    .isEqualTo(new Account())
                    .hasSameHashCodeAs(new Account());
        }

        @Test
        @DisplayName("an unpopulated account is not equal to a populated one")
        void anUnpopulatedAccountIsNotEqualToAPopulatedOne() {
            assertThat(new Account()).isNotEqualTo(referenceAccount());
        }

        @Test
        @DisplayName("equal accounts hash alike and hashing is stable across calls")
        void equalAccountsHashAlike() {
            Account account = referenceAccount();

            assertThat(account).hasSameHashCodeAs(referenceAccount());
            assertThat(account.hashCode()).isEqualTo(account.hashCode());
        }

        @Test
        @DisplayName("mutating every non-key attribute leaves the account retrievable from a map")
        void mutatingEveryNonKeyAttributeLeavesTheAccountRetrievable() {
            Account account = referenceAccount();
            Map<Account, String> index = new HashMap<>();
            index.put(account, "seeded");

            account.setAcctActiveStatus("N");
            account.setAcctCurrBal(new BigDecimal("-1.00"));
            account.setAcctCreditLimit(BigDecimal.ZERO);
            account.setAcctCashCreditLimit(BigDecimal.ZERO);
            account.setAcctOpenDate("1970-01-01");
            account.setAcctExpirationDate("1970-01-02");
            account.setAcctReissueDate("1970-01-03");
            account.setAcctCurrCycCredit(BigDecimal.ONE);
            account.setAcctCurrCycDebit(BigDecimal.TEN);
            account.setAcctAddrZip("00000     ");
            account.setAcctGroupId("DEFAULT   ");

            assertThat(index).containsEntry(account, "seeded");
            assertThat(index).containsEntry(referenceAccount(), "seeded");
        }
    }

    @Nested
    @DisplayName("diagnostic rendering")
    class DiagnosticRendering {

        @Test
        @DisplayName("the rendering carries the identifier and the raw status and nothing else")
        void theRenderingCarriesTheIdentifierAndStatusOnly() {
            assertThat(referenceAccount().toString()).isEqualTo(
                    "Account[acctId='" + ACCOUNT_ID
                            + "', acctActiveStatus='" + ACTIVE_STATUS + "']");
        }

        @Test
        @DisplayName("no monetary amount reaches the rendering")
        void noMonetaryAmountReachesTheRendering() {
            String rendered = referenceAccount().toString();

            assertThat(rendered)
                    .doesNotContain("1234.56", "5000.00", "500.00", "100.00", "50.00")
                    .doesNotContain("acctCurrBal", "acctCreditLimit");
        }

        @Test
        @DisplayName("no address component reaches the rendering")
        void noAddressComponentReachesTheRendering() {
            assertThat(referenceAccount().toString())
                    .doesNotContain("12345")
                    .doesNotContain("acctAddrZip");
        }

        @Test
        @DisplayName("an unpopulated account renders without throwing")
        void anUnpopulatedAccountRendersWithoutThrowing() {
            assertThat(new Account().toString())
                    .isEqualTo("Account[acctId='null', acctActiveStatus='null']");
        }
    }
}
