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
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit test for {@link Account}, the Java realisation of the 300-byte {@code ACCOUNT-RECORD} layout
 * declared by copybook member {@code CVACT01Y}.
 *
 * <h2>What is actually at risk in an entity of this shape</h2>
 *
 * <p>Three properties matter and none of them is "the getter returns what the setter stored", though
 * that is asserted too because a field mixed up between two same-typed accessors is a real and silent
 * defect in a twelve-argument constructor.</p>
 *
 * <p>The first is <strong>scale preservation</strong>. Five fields of this record are zoned decimal
 * {@code PIC S9(10)V99} and every one of them must survive as a {@code BigDecimal} whose scale is
 * exactly two. The entity does not coerce scale - that is the codec's job - so what is asserted here
 * is the weaker but essential property that the entity is <em>transparent</em>: it neither rescales
 * nor rounds nor normalises, so a value that arrives at scale two leaves at scale two and a value
 * that arrives at some other scale is not silently corrected into looking correct.</p>
 *
 * <p>The second is <strong>business-key identity</strong>. The account identifier is the primary key
 * lifted straight out of the record image, never a surrogate, so equality and hashing must follow it
 * and only it. Including a mutable amount would let an instance change its own hash while sitting in
 * a set, which is why the amounts are excluded and why that exclusion is asserted rather than assumed.</p>
 *
 * <p>The third is <strong>diagnostic redaction</strong>. Five monetary fields, a ZIP code and three
 * dates are present on this type; none of them may appear in an incidental log line.</p>
 */
@DisplayName("Account - the 300-byte CVACT01Y account record")
class AccountSecurityTest {

    private static final String ACCOUNT_ID = "00000000011";
    private static final String ACTIVE = "Y";
    private static final BigDecimal CURRENT_BALANCE = new BigDecimal("1234.56");
    private static final BigDecimal CREDIT_LIMIT = new BigDecimal("5000.00");
    private static final BigDecimal CASH_CREDIT_LIMIT = new BigDecimal("1500.00");
    private static final String OPEN_DATE = "2020-01-15";
    private static final String EXPIRATION_DATE = "2025-01-14";
    private static final String REISSUE_DATE = "2023-01-14";
    private static final BigDecimal CYCLE_CREDIT = new BigDecimal("300.00");
    private static final BigDecimal CYCLE_DEBIT = new BigDecimal("125.75");
    private static final String ADDRESS_ZIP = "20001-0000";
    private static final String GROUP_ID = "A         ";

    /** A fully populated account carrying values at the widths the record layout declares. */
    private static Account account() {
        return new Account(ACCOUNT_ID, ACTIVE, CURRENT_BALANCE, CREDIT_LIMIT, CASH_CREDIT_LIMIT,
                OPEN_DATE, EXPIRATION_DATE, REISSUE_DATE, CYCLE_CREDIT, CYCLE_DEBIT,
                ADDRESS_ZIP, GROUP_ID);
    }

    @Nested
    @DisplayName("Construction from the record image")
    class Construction {

        @Test
        @DisplayName("the twelve-argument constructor assigns every field to its own accessor, so no two "
                + "same-typed arguments are transposed")
        void everyFieldLandsOnItsOwnAccessor() {
            final Account subject = account();
            assertThat(subject.getAcctId()).isEqualTo(ACCOUNT_ID);
            assertThat(subject.getAcctActiveStatus()).isEqualTo(ACTIVE);
            assertThat(subject.getAcctCurrBal()).isEqualTo(CURRENT_BALANCE);
            assertThat(subject.getAcctCreditLimit()).isEqualTo(CREDIT_LIMIT);
            assertThat(subject.getAcctCashCreditLimit()).isEqualTo(CASH_CREDIT_LIMIT);
            assertThat(subject.getAcctOpenDate()).isEqualTo(OPEN_DATE);
            assertThat(subject.getAcctExpirationDate()).isEqualTo(EXPIRATION_DATE);
            assertThat(subject.getAcctReissueDate()).isEqualTo(REISSUE_DATE);
            assertThat(subject.getAcctCurrCycCredit()).isEqualTo(CYCLE_CREDIT);
            assertThat(subject.getAcctCurrCycDebit()).isEqualTo(CYCLE_DEBIT);
            assertThat(subject.getAcctAddrZip()).isEqualTo(ADDRESS_ZIP);
            assertThat(subject.getAcctGroupId()).isEqualTo(GROUP_ID);
        }

        @Test
        @DisplayName("the five monetary arguments are distinguishable, so a transposition among them would be "
                + "caught rather than hidden behind identical fixture values")
        void theFiveMonetaryArgumentsAreDistinguishable() {
            assertThat(Set.of(CURRENT_BALANCE, CREDIT_LIMIT, CASH_CREDIT_LIMIT,
                    CYCLE_CREDIT, CYCLE_DEBIT)).hasSize(5);
        }

        @Test
        @DisplayName("the three date arguments are distinguishable, for the same reason")
        void theThreeDateArgumentsAreDistinguishable() {
            assertThat(Set.of(OPEN_DATE, EXPIRATION_DATE, REISSUE_DATE)).hasSize(3);
        }

        @Test
        @DisplayName("the provider constructor produces an instance whose fields are all unset, because the "
                + "provider assigns state after construction")
        void theProviderConstructorLeavesEveryFieldUnset() {
            final Account subject = new Account();
            assertThat(subject.getAcctId()).isNull();
            assertThat(subject.getAcctActiveStatus()).isNull();
            assertThat(subject.getAcctCurrBal()).isNull();
            assertThat(subject.getAcctCreditLimit()).isNull();
            assertThat(subject.getAcctCashCreditLimit()).isNull();
            assertThat(subject.getAcctOpenDate()).isNull();
            assertThat(subject.getAcctExpirationDate()).isNull();
            assertThat(subject.getAcctReissueDate()).isNull();
            assertThat(subject.getAcctCurrCycCredit()).isNull();
            assertThat(subject.getAcctCurrCycDebit()).isNull();
            assertThat(subject.getAcctAddrZip()).isNull();
            assertThat(subject.getAcctGroupId()).isNull();
        }

        @Test
        @DisplayName("a nulled-out account is accepted by the constructor, because presence is enforced by the "
                + "schema rather than re-enforced here")
        void aNulledOutAccountIsAccepted() {
            final Account subject = new Account(null, null, null, null, null, null,
                    null, null, null, null, null, null);
            assertThat(subject.getAcctId()).isNull();
            assertThat(subject.getAcctCurrBal()).isNull();
        }
    }

    @Nested
    @DisplayName("Field widths carried through from the record layout")
    class RecordLayoutWidths {

        @Test
        @DisplayName("the account identifier is 11 characters and keeps its leading zeros, because the key is a "
                + "substring of the record image rather than a number")
        void theAccountIdentifierIsElevenCharactersWithLeadingZeros() {
            assertThat(ACCOUNT_ID).hasSize(11).startsWith("0");
            assertThat(account().getAcctId()).isEqualTo(ACCOUNT_ID).isNotEqualTo("11");
        }

        @Test
        @DisplayName("the status code is a single character, matching PIC X(1)")
        void theStatusCodeIsOneCharacter() {
            assertThat(account().getAcctActiveStatus()).hasSize(1);
        }

        @Test
        @DisplayName("the three dates are 10 characters, matching PIC X(10)")
        void theThreeDatesAreTenCharacters() {
            final Account subject = account();
            assertThat(subject.getAcctOpenDate()).hasSize(10);
            assertThat(subject.getAcctExpirationDate()).hasSize(10);
            assertThat(subject.getAcctReissueDate()).hasSize(10);
        }

        @Test
        @DisplayName("the ZIP and the group identifier are 10 characters, and the group identifier keeps its "
                + "trailing spaces because padding is real data in a fixed-width record")
        void theZipAndGroupIdentifierAreTenCharacters() {
            final Account subject = account();
            assertThat(subject.getAcctAddrZip()).hasSize(10);
            assertThat(subject.getAcctGroupId()).hasSize(10).isEqualTo("A         ");
            assertThat(subject.getAcctGroupId()).isNotEqualTo("A");
        }

        @ParameterizedTest
        @ValueSource(strings = {"", " ", "0", "000000000000000000000000000000"})
        @DisplayName("the entity does not police identifier width, because the column length does; a value of any "
                + "width is stored verbatim")
        void theEntityDoesNotPoliceIdentifierWidth(final String candidate) {
            final Account subject = new Account();
            subject.setAcctId(candidate);
            assertThat(subject.getAcctId()).isEqualTo(candidate);
        }
    }

    @Nested
    @DisplayName("Monetary fields: scale is carried, never coerced")
    class MonetaryScale {

        @Test
        @DisplayName("every monetary fixture is at scale two, matching the V99 clause on all five PIC S9(10)V99 "
                + "fields")
        void everyMonetaryFixtureIsAtScaleTwo() {
            assertThat(CURRENT_BALANCE.scale()).isEqualTo(2);
            assertThat(CREDIT_LIMIT.scale()).isEqualTo(2);
            assertThat(CASH_CREDIT_LIMIT.scale()).isEqualTo(2);
            assertThat(CYCLE_CREDIT.scale()).isEqualTo(2);
            assertThat(CYCLE_DEBIT.scale()).isEqualTo(2);
        }

        @Test
        @DisplayName("a scale-two value survives the round trip with its scale intact, so no accessor rescales")
        void aScaleTwoValueSurvivesWithItsScaleIntact() {
            final Account subject = account();
            assertThat(subject.getAcctCurrBal().scale()).isEqualTo(2);
            assertThat(subject.getAcctCreditLimit().scale()).isEqualTo(2);
            assertThat(subject.getAcctCashCreditLimit().scale()).isEqualTo(2);
            assertThat(subject.getAcctCurrCycCredit().scale()).isEqualTo(2);
            assertThat(subject.getAcctCurrCycDebit().scale()).isEqualTo(2);
        }

        @ParameterizedTest
        @ValueSource(strings = {"0", "0.0", "0.000", "1234.5", "1234.500", "-1234.56", "9999999999.99"})
        @DisplayName("the entity is transparent about scale: an off-scale value is stored exactly as supplied and "
                + "is NOT silently normalised into looking correct, because scale coercion belongs to the codec")
        void anOffScaleValueIsStoredExactlyAsSupplied(final String literal) {
            final BigDecimal supplied = new BigDecimal(literal);
            final Account subject = new Account();
            subject.setAcctCurrBal(supplied);
            assertThat(subject.getAcctCurrBal()).isSameAs(supplied);
            assertThat(subject.getAcctCurrBal().scale()).isEqualTo(supplied.scale());
            assertThat(subject.getAcctCurrBal().toPlainString()).isEqualTo(literal);
        }

        @Test
        @DisplayName("no monetary accessor substitutes a floating-point representation, so the values remain exact")
        void noMonetaryAccessorSubstitutesAFloatingPointRepresentation() {
            final Account subject = account();
            assertThat(subject.getAcctCurrBal()).isInstanceOf(BigDecimal.class);
            assertThat(subject.getAcctCurrBal()).isEqualByComparingTo("1234.56");
            assertThat(subject.getAcctCurrBal().unscaledValue().longValueExact()).isEqualTo(123456L);
        }

        @Test
        @DisplayName("a negative amount is preserved with its sign, because the layout is a SIGNED zoned decimal")
        void aNegativeAmountIsPreservedWithItsSign() {
            final Account subject = new Account();
            subject.setAcctCurrBal(new BigDecimal("-0.01"));
            assertThat(subject.getAcctCurrBal().signum()).isNegative();
            assertThat(subject.getAcctCurrBal()).isEqualByComparingTo("-0.01");
        }

        @Test
        @DisplayName("the widest value the PIC S9(10)V99 clause admits is stored without loss")
        void theWidestAdmissibleValueIsStoredWithoutLoss() {
            final BigDecimal widest = new BigDecimal("9999999999.99");
            final Account subject = new Account();
            subject.setAcctCreditLimit(widest);
            assertThat(subject.getAcctCreditLimit()).isEqualTo(widest);
            assertThat(subject.getAcctCreditLimit().precision()).isEqualTo(12);
        }
    }

    @Nested
    @DisplayName("Mutability through the setters, which the provider and the update path both use")
    class Mutation {

        @Test
        @DisplayName("each setter changes exactly its own field and leaves the other eleven untouched")
        void eachSetterChangesExactlyItsOwnField() {
            final Account subject = account();
            subject.setAcctActiveStatus("N");
            assertThat(subject.getAcctActiveStatus()).isEqualTo("N");
            assertThat(subject.getAcctId()).isEqualTo(ACCOUNT_ID);
            assertThat(subject.getAcctCurrBal()).isEqualTo(CURRENT_BALANCE);
            assertThat(subject.getAcctGroupId()).isEqualTo(GROUP_ID);
        }

        @Test
        @DisplayName("all twelve setters are individually effective, walking a blank instance up to a full one")
        void allTwelveSettersAreIndividuallyEffective() {
            final Account subject = new Account();
            subject.setAcctId(ACCOUNT_ID);
            subject.setAcctActiveStatus(ACTIVE);
            subject.setAcctCurrBal(CURRENT_BALANCE);
            subject.setAcctCreditLimit(CREDIT_LIMIT);
            subject.setAcctCashCreditLimit(CASH_CREDIT_LIMIT);
            subject.setAcctOpenDate(OPEN_DATE);
            subject.setAcctExpirationDate(EXPIRATION_DATE);
            subject.setAcctReissueDate(REISSUE_DATE);
            subject.setAcctCurrCycCredit(CYCLE_CREDIT);
            subject.setAcctCurrCycDebit(CYCLE_DEBIT);
            subject.setAcctAddrZip(ADDRESS_ZIP);
            subject.setAcctGroupId(GROUP_ID);
            assertThat(subject).isEqualTo(account());
            assertThat(subject.getAcctCurrCycDebit()).isEqualTo(CYCLE_DEBIT);
            assertThat(subject.getAcctReissueDate()).isEqualTo(REISSUE_DATE);
        }

        @Test
        @DisplayName("a setter accepts null, because nullability is enforced by the column rather than re-enforced "
                + "here")
        void aSetterAcceptsNull() {
            final Account subject = account();
            subject.setAcctCurrBal(null);
            subject.setAcctGroupId(null);
            assertThat(subject.getAcctCurrBal()).isNull();
            assertThat(subject.getAcctGroupId()).isNull();
        }
    }

    @Nested
    @DisplayName("The optimistic-locking version counter that replaces the before-and-after image compare")
    class VersionCounter {

        @Test
        @DisplayName("a freshly built account reports version zero, matching the schema default for a row that has "
                + "never been updated")
        void aFreshlyBuiltAccountReportsVersionZero() {
            assertThat(account().getVersion()).isZero();
            assertThat(new Account().getVersion()).isZero();
        }

        @Test
        @DisplayName("the counter is read-only to application code: there is no setter, because the provider owns "
                + "the value")
        void theCounterIsReadOnlyToApplicationCode() {
            final boolean hasSetter = java.util.Arrays.stream(Account.class.getDeclaredMethods())
                    .anyMatch(method -> "setVersion".equals(method.getName()));
            assertThat(hasSetter)
                    .as("Account must not expose setVersion; the persistence provider assigns it")
                    .isFalse();
        }
    }

    @Nested
    @DisplayName("Identity: the business key alone, never a surrogate and never a mutable amount")
    class Identity {

        @Test
        @DisplayName("an account equals itself")
        void anAccountEqualsItself() {
            final Account subject = account();
            assertThat(subject).isEqualTo(subject);
        }

        @Test
        @DisplayName("two accounts with the same identifier are equal even when every other field differs, because "
                + "identity follows the primary key")
        void thePrimaryKeyAloneDeterminesEquality() {
            final Account left = account();
            final Account right = new Account(ACCOUNT_ID, "N", BigDecimal.ZERO, BigDecimal.ONE,
                    BigDecimal.TEN, "1999-12-31", "1999-12-31", "1999-12-31",
                    BigDecimal.ZERO, BigDecimal.ZERO, "99999-9999", "ZEROAPR   ");
            assertThat(left).isEqualTo(right);
            assertThat(right).isEqualTo(left);
            assertThat(left).hasSameHashCodeAs(right);
        }

        @Test
        @DisplayName("two accounts with different identifiers are unequal even when every other field matches")
        void adifferentIdentifierMakesTwoAccountsUnequal() {
            final Account left = account();
            final Account right = new Account("00000000012", ACTIVE, CURRENT_BALANCE, CREDIT_LIMIT,
                    CASH_CREDIT_LIMIT, OPEN_DATE, EXPIRATION_DATE, REISSUE_DATE,
                    CYCLE_CREDIT, CYCLE_DEBIT, ADDRESS_ZIP, GROUP_ID);
            assertThat(left).isNotEqualTo(right);
        }

        @Test
        @DisplayName("the identifier is compared exactly, so a shortened form is a different account rather than "
                + "the same one written differently")
        void theIdentifierIsComparedExactly() {
            final Account padded = new Account();
            padded.setAcctId(ACCOUNT_ID);
            final Account shortened = new Account();
            shortened.setAcctId("11");
            assertThat(padded).isNotEqualTo(shortened);
        }

        @Test
        @DisplayName("an account is not equal to null and not equal to a foreign type")
        void anAccountIsNotEqualToNullOrAForeignType() {
            final Account subject = account();
            assertThat(subject).isNotEqualTo(null);
            assertThat(subject.equals(ACCOUNT_ID)).isFalse();
            assertThat(subject.equals(new Object())).isFalse();
        }

        @Test
        @DisplayName("two unset accounts are equal, because both carry a null key - the provider populates the key "
                + "immediately after construction")
        void twoUnsetAccountsAreEqual() {
            assertThat(new Account()).isEqualTo(new Account());
        }

        @Test
        @DisplayName("changing a mutable amount does not change the hash code, which is precisely why the amounts "
                + "are excluded: an entity in a set must not move")
        void changingAnAmountDoesNotChangeTheHashCode() {
            final Account subject = account();
            final int before = subject.hashCode();
            subject.setAcctCurrBal(new BigDecimal("99999.99"));
            subject.setAcctCurrCycDebit(new BigDecimal("-1.00"));
            subject.setAcctActiveStatus("N");
            assertThat(subject.hashCode()).isEqualTo(before);
        }

        @Test
        @DisplayName("an account remains findable in a hash set after every mutable field has changed")
        void anAccountRemainsFindableInAHashSetAfterMutation() {
            final Set<Account> accounts = new HashSet<>();
            final Account subject = account();
            accounts.add(subject);
            subject.setAcctCurrBal(new BigDecimal("0.00"));
            subject.setAcctGroupId("DEFAULT   ");
            assertThat(accounts).contains(subject);
            assertThat(accounts.contains(account())).isTrue();
        }

        @Test
        @DisplayName("an account is usable as a map key across a state change")
        void anAccountIsUsableAsAMapKeyAcrossAStateChange() {
            final Map<Account, String> byAccount = new HashMap<>();
            final Account subject = account();
            byAccount.put(subject, "seeded");
            subject.setAcctCreditLimit(new BigDecimal("1.00"));
            assertThat(byAccount.get(subject)).isEqualTo("seeded");
            assertThat(byAccount.get(account())).isEqualTo("seeded");
        }

        @Test
        @DisplayName("the hash code is stable across repeated invocations")
        void theHashCodeIsStableAcrossRepeatedInvocations() {
            final Account subject = account();
            assertThat(subject.hashCode()).isEqualTo(subject.hashCode());
        }
    }

    @Nested
    @DisplayName("Diagnostic rendering: the key and the status code, and nothing financial")
    class DiagnosticRendering {

        @Test
        @DisplayName("the rendering names the type and carries the identifier and the status code, both quoted so "
                + "padding stays visible")
        void theRenderingCarriesTheKeyAndTheStatus() {
            assertThat(account().toString())
                    .startsWith("Account[")
                    .contains("acctId='" + ACCOUNT_ID + "'")
                    .contains("acctActiveStatus='" + ACTIVE + "'")
                    .endsWith("]");
        }

        @Test
        @DisplayName("no monetary amount appears in the rendering, so an incidental log line cannot leak a balance "
                + "or a credit limit")
        void noMonetaryAmountAppearsInTheRendering() {
            final String rendering = account().toString();
            assertThat(rendering)
                    .doesNotContain("1234.56")
                    .doesNotContain("5000.00")
                    .doesNotContain("1500.00")
                    .doesNotContain("300.00")
                    .doesNotContain("125.75");
        }

        @Test
        @DisplayName("no address component, no date and no group identifier appears in the rendering")
        void noAddressDateOrGroupAppearsInTheRendering() {
            final String rendering = account().toString();
            assertThat(rendering)
                    .doesNotContain(ADDRESS_ZIP)
                    .doesNotContain(OPEN_DATE)
                    .doesNotContain(EXPIRATION_DATE)
                    .doesNotContain(REISSUE_DATE)
                    .doesNotContain("acctGroupId");
        }

        @Test
        @DisplayName("the component list carries no credential or financial marker")
        void theComponentListCarriesNothingSensitive() {
            final String rendering = account().toString();
            final String componentList =
                    rendering.substring(rendering.indexOf('[') + 1, rendering.length() - 1);
            assertThat(componentList.toUpperCase(Locale.ROOT))
                    .doesNotContain("PASSWORD", "SECRET", "SSN", "$2A$")
                    .doesNotContain("BAL=", "AMT=", "LIMIT=", "ZIP=");
        }

        @Test
        @DisplayName("an unset account renders without throwing, so a diagnostic during provider instantiation is "
                + "safe")
        void anUnsetAccountRendersWithoutThrowing() {
            assertThat(new Account().toString())
                    .startsWith("Account[")
                    .contains("null")
                    .endsWith("]");
        }

        @Test
        @DisplayName("the rendering is exactly the two documented components, with no extra field smuggled in")
        void theRenderingIsExactlyTheTwoDocumentedComponents() {
            assertThat(account().toString())
                    .isEqualTo("Account[acctId='" + ACCOUNT_ID + "', acctActiveStatus='" + ACTIVE + "']");
        }
    }
}
