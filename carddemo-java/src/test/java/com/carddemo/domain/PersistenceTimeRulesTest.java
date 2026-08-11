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
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNoException;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.Base64;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;

/**
 * Verifies the one rule set every entity applies on its way to becoming a row: natural keys at exactly
 * the width their record layout declares, digits where the legacy picture clause is numeric, and every
 * stored amount at scale two truncated toward zero.
 *
 * <h2>Why these rules are asserted together, in one place</h2>
 *
 * <p>They are one invariant expressed nine times. Gathering them here makes two things checkable that a
 * per-entity test cannot show: that every entity carrying a natural key or an amount actually declares
 * the callback - the closure assertion below enumerates them - and that the entities agree on the policy
 * rather than each choosing its own. A rule applied in eight places out of nine is the failure mode this
 * class exists to catch.
 *
 * <h2>What the callback placement buys, and what these tests therefore assert</h2>
 *
 * <p>The rules run from {@code @PrePersist} and {@code @PreUpdate} rather than from constructors and
 * setters, because the persistence provider hydrates a row by instantiating an entity and assigning its
 * fields directly - a constructor guard is bypassed on every read, while a callback sits on the one path
 * every insert and every update must take. Two consequences are asserted directly: an instance that will
 * never become a row is never refused, and both write callbacks are bound, so an identifier edited in
 * place is checked exactly as an inserted one is.
 *
 * <p>No COBOL statement is transcribed.
 */
@DisplayName("Persistence-time rules: exact key widths, digit classes, and scale-two truncation")
final class PersistenceTimeRulesTest {

    /** The name every entity gives the callback, so the closure assertion can look for one spelling. */
    private static final String CALLBACK = "normalizeAndValidateBeforeWrite";

    /**
     * A synthetic BCrypt digest at the exact sixty characters the credential rule requires. It is not a
     * credential: it hashes nothing and authenticates nobody, and it exists only so the identifier rule
     * under test is the one that fails.
     */
    private static final String DIGEST = "$2a$10$" + "a".repeat(53);

    /**
     * A synthetic protected value carrying the envelope marker the customer record requires of its
     * regulated attributes. It encrypts nothing; it exists only so the identifier rule under test is the
     * one that fails.
     */
    private static final String PROTECTED_VALUE =
            "ENC1:" + Base64.getEncoder().encodeToString(new byte[32]);

    /** Creates the test class. */
    PersistenceTimeRulesTest() {
    }

    /**
     * Every entity that carries a natural key or a stored amount, and therefore must declare the callback.
     *
     * @return the nine entity types the rules govern
     */
    private static Stream<Class<?>> guardedEntities() {
        return Stream.of(Account.class, Card.class, Customer.class, CardCrossReference.class,
                UserSecurity.class, Transaction.class, DailyTransaction.class,
                TransactionCategoryBalance.class, DisclosureGroup.class);
    }

    @Nested
    @DisplayName("closure: the rules are applied everywhere they belong")
    final class Closure {

        /** Creates the nest. */
        Closure() {
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.carddemo.domain.PersistenceTimeRulesTest#guardedEntities")
        @DisplayName("declares the callback and binds it to both write events, so an update is governed as "
                + "well as an insert")
        void declaresTheCallbackBoundToBothWriteEvents(final Class<?> entity) throws NoSuchMethodException {
            final Method callback = entity.getDeclaredMethod(CALLBACK);

            assertThat(callback.isAnnotationPresent(PrePersist.class))
                    .as("%s must apply its rules before an insert", entity.getSimpleName())
                    .isTrue();
            assertThat(callback.isAnnotationPresent(PreUpdate.class))
                    .as("%s must apply them before an update too: a key edited in place is the same hazard",
                            entity.getSimpleName())
                    .isTrue();
        }

        @Test
        @DisplayName("nine entities are governed, which is every one that carries a natural key or an "
                + "amount - a tenth added later has to be added here deliberately")
        void nineEntitiesAreGoverned() {
            assertThat(guardedEntities()).hasSize(9);
        }
    }

    @Nested
    @DisplayName("natural keys: exact width, and the digit class only where the picture clause is numeric")
    final class NaturalKeys {

        /** Creates the nest. */
        NaturalKeys() {
        }

        @Test
        @DisplayName("an account identifier of eleven digits passes and one of ten is refused, because a "
                + "short value splits one account's identity from the eleven bytes it is written into")
        void theAccountIdentifierMustBeElevenDigits() {
            assertThatNoException().isThrownBy(account("00000000011")::normalizeAndValidateBeforeWrite);
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(account("0000000001")::normalizeAndValidateBeforeWrite)
                    .withMessageContaining("acctId")
                    .withMessageContaining("exactly 11 characters");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(account("0000000001A")::normalizeAndValidateBeforeWrite)
                    .withMessageContaining("acctId")
                    .withMessageContaining("digits");
        }

        @Test
        @DisplayName("a card number is checked for width but not for digits while its owning-account "
                + "identifier is checked for both, because one picture clause is alphanumeric and the "
                + "other numeric")
        void theCardChecksWidthOnTheNumberAndDigitsOnTheAccount() {
            assertThatNoException()
                    .as("sixteen alphanumeric characters are a value the legacy field could hold")
                    .isThrownBy(new Card("ABCD111122223333", "00000000011", "123", "NAME", "2099-12-31",
                            "Y")::normalizeAndValidateBeforeWrite);
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(new Card("411111111111", "00000000011", "123", "NAME", "2099-12-31",
                            "Y")::normalizeAndValidateBeforeWrite)
                    .withMessageContaining("cardNum");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(new Card("4111111111111111", "0000000001A", "123", "NAME", "2099-12-31",
                            "Y")::normalizeAndValidateBeforeWrite)
                    .withMessageContaining("cardAcctId")
                    .withMessageContaining("digits");
        }

        @Test
        @DisplayName("a customer identifier must be nine digits")
        void theCustomerIdentifierMustBeNineDigits() {
            assertThatNoException().isThrownBy(customer("000000050")::normalizeAndValidateBeforeWrite);
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(customer("50")::normalizeAndValidateBeforeWrite)
                    .withMessageContaining("custId");
        }

        @Test
        @DisplayName("all three cross-reference identifiers are governed, because this row resolves a card "
                + "to an account and a short value mis-resolves the whole relationship")
        void allThreeCrossReferenceIdentifiersAreGoverned() {
            assertThatNoException().isThrownBy(new CardCrossReference("4111111111111111", "000000050",
                    "00000000050")::normalizeAndValidateBeforeWrite);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(new CardCrossReference("411111", "000000050", "00000000050")
                            ::normalizeAndValidateBeforeWrite)
                    .withMessageContaining("xrefCardNum");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(new CardCrossReference("4111111111111111", "50", "00000000050")
                            ::normalizeAndValidateBeforeWrite)
                    .withMessageContaining("xrefCustId");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(new CardCrossReference("4111111111111111", "000000050", "5000")
                            ::normalizeAndValidateBeforeWrite)
                    .withMessageContaining("xrefAcctId");
        }

        @Test
        @DisplayName("a sign-on identifier is checked for width alone, because the record declares it "
                + "alphanumeric and every seeded identity carries letters")
        void theSignOnIdentifierIsCheckedForWidthAlone() {
            assertThatNoException()
                    .isThrownBy(new UserSecurity("ADMIN001", "First", "Last",
                            DIGEST, "A")
                            ::normalizeAndValidateBeforeWrite);
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(new UserSecurity("ADMIN1", "First", "Last",
                            DIGEST, "A")
                            ::normalizeAndValidateBeforeWrite)
                    .withMessageContaining("secUsrId")
                    .withMessageContaining("exactly 8 characters");
        }

        @Test
        @DisplayName("the raw landing table checks the WIDTH of its image-critical fields, because a row "
                + "that cannot be re-encoded to 350 bytes cannot be reported either")
        void theLandingTableChecksTheWidthOfItsImageCriticalFields() {
            // Every field of a 350-byte image is exactly as wide as the layout declares, because it is a
            // slice of that image, so no record the sequential reader can produce is refused here. A
            // two-character identifier is a row nothing read from a file: it could not be echoed into the
            // 350-byte leading segment of a reject record, so it could not be REPORTED either - the reject
            // dataset would fail, not merely the posting.
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(dailyTransaction("42", new BigDecimal("1.00"))
                            ::normalizeAndValidateBeforeWrite)
                    .withMessageContaining("dalytranId")
                    .withMessageContaining("exactly 16 characters");
            assertThatNoException()
                    .as("and the same row at the image width is accepted")
                    .isThrownBy(dailyTransaction("0000000000000042", new BigDecimal("1.00"))
                            ::normalizeAndValidateBeforeWrite);
        }

        @Test
        @DisplayName("but NO content rule reaches it: a card number that is not a card and an overpunched "
                + "category code both pass, because content is the reject dataset's to report")
        void noContentRuleReachesTheDailyLandingTable() {
            // THE HALF OF THE ORIGINAL SPECIFICATION THAT STILL HOLDS, and it is the load-bearing half.
            // Reject reason 100 exists to report a card number that resolves to no cross-reference, and it
            // can only do so if such a row is allowed to land. A category code of 000J is the zoned-decimal
            // minus-one form app/jcl/PRTCATBL.jcl declares for the corresponding field, so no digit class
            // may be applied to it either.
            final DailyTransaction unknownCard = dailyTransaction(
                    "0000000000000042", new BigDecimal("1.00"));
            unknownCard.setDalytranCardNum("NOTACARDNUMBER01");
            unknownCard.setDalytranCatCd("000J");

            assertThatNoException()
                    .as("refusing either would delete the very case the posting job exists to report")
                    .isThrownBy(unknownCard::normalizeAndValidateBeforeWrite);
        }

        @Test
        @DisplayName("no digit class reaches the disclosure group identifier, which is ten spaces in every "
                + "seeded account row - and none reaches its category code either")
        void noDigitClassReachesTheDisclosureGroupIdentifier() {
            assertThatNoException()
                    .isThrownBy(new DisclosureGroup("          ", "01", "0005", new BigDecimal("1.75"))
                            ::normalizeAndValidateBeforeWrite);
            assertThatNoException()
                    .as("the category code's picture clause is PIC 9(04), yet the estate's own sort "
                            + "specification types the field it is looked up with as zoned decimal, whose "
                            + "sign occupies the final byte - so a digit class would make a signed row "
                            + "unmatchable rather than invalid")
                    .isThrownBy(new DisclosureGroup("ZEROAPR   ", "01", "000J", new BigDecimal("0.00"))
                            ::normalizeAndValidateBeforeWrite);
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("its WIDTH is still contractual, and a seven-character group identifier is the "
                            + "value that silently resolves nothing")
                    .isThrownBy(new DisclosureGroup("ZEROAPR", "01", "0005", new BigDecimal("0.00"))
                            ::normalizeAndValidateBeforeWrite)
                    .withMessageContaining("disAcctGroupId")
                    .withMessageContaining("exactly 10 characters");
        }

        @Test
        @DisplayName("the account group identifier is checked for width, which is the one account column "
                + "that is a key somewhere else and that no foreign key can reach")
        void theAccountGroupIdentifierIsCheckedForWidth() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(accountWithGroup("ZEROAPR")::normalizeAndValidateBeforeWrite)
                    .withMessageContaining("acctGroupId")
                    .withMessageContaining("exactly 10 characters");
            assertThatNoException()
                    .as("the padded spelling is the one that resolves its own disclosure row")
                    .isThrownBy(accountWithGroup("ZEROAPR   ")::normalizeAndValidateBeforeWrite);
            assertThatNoException()
                    .as("and the ten spaces every seeded account carries are accepted, so the rule is a "
                            + "width and not a vocabulary")
                    .isThrownBy(accountWithGroup("          ")::normalizeAndValidateBeforeWrite);
        }

        @Test
        @DisplayName("the two reference tables check their key widths, which is what stops '1' and '01' "
                + "claiming the same two bytes of one record")
        void theReferenceTablesCheckTheirKeyWidths() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(new TransactionType("1", "Purchase")::validateBeforeWrite)
                    .withMessageContaining("tranType")
                    .withMessageContaining("exactly 2 characters");
            assertThatNoException()
                    .isThrownBy(new TransactionType("01", "Purchase")::validateBeforeWrite);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(new TransactionCategory("01", "5", "Regular Sales Draft")
                            ::validateBeforeWrite)
                    .withMessageContaining("tranCatCd")
                    .withMessageContaining("exactly 4 characters");
            assertThatNoException()
                    .as("and an overpunched category code at the right width is accepted, for the same "
                            + "zoned-decimal reason as the disclosure key")
                    .isThrownBy(new TransactionCategory("01", "000J", "Regular Sales Draft")
                            ::validateBeforeWrite);
        }

        @Test
        @DisplayName("the category-balance key checks all three part widths and applies no digit class to "
                + "the two its own sort specification declares zoned decimal")
        void theCategoryBalanceKeyChecksAllThreePartWidths() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(new TransactionCategoryBalance("1", "01", "0005",
                            new BigDecimal("1.00"))::normalizeAndValidateBeforeWrite)
                    .withMessageContaining("trancatAcctId")
                    .withMessageContaining("exactly 11 characters");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(new TransactionCategoryBalance("00000000050", "01", "5",
                            new BigDecimal("1.00"))::normalizeAndValidateBeforeWrite)
                    .withMessageContaining("trancatCd")
                    .withMessageContaining("exactly 4 characters");
            assertThatNoException()
                    .as("app/jcl/PRTCATBL.jcl declares TRANCAT-CD,14,4,ZD, so minus one is a legitimate "
                            + "value of this field and the report's own ordering fixture is built from it")
                    .isThrownBy(new TransactionCategoryBalance("00000000050", "01", "000J",
                            new BigDecimal("1.00"))::normalizeAndValidateBeforeWrite);
        }
    }

    @Nested
    @DisplayName("amounts: scale two, truncated toward zero, on every stored money field")
    final class Amounts {

        /** Creates the nest. */
        Amounts() {
        }

        @Test
        @DisplayName("all five account money fields are normalised, so no single field can be left at a "
                + "scale the truncation policy never saw")
        void allFiveAccountMoneyFieldsAreNormalised() {
            final Account subject = new Account("00000000011", "Y",
                    new BigDecimal("1.999"), new BigDecimal("2.999"), new BigDecimal("3.999"),
                    "2020-01-01", "2099-12-31", "2020-01-01",
                    new BigDecimal("4.999"), new BigDecimal("5.999"), "12345", "          ");

            subject.normalizeAndValidateBeforeWrite();

            assertThat(List.of(subject.getAcctCurrBal(), subject.getAcctCreditLimit(),
                            subject.getAcctCashCreditLimit(), subject.getAcctCurrCycCredit(),
                            subject.getAcctCurrCycDebit()))
                    .as("truncated toward zero, never rounded: the estate declares no rounding clause on "
                            + "any arithmetic statement, and half-even would differ by a cent")
                    .containsExactly(new BigDecimal("1.99"), new BigDecimal("2.99"),
                            new BigDecimal("3.99"), new BigDecimal("4.99"), new BigDecimal("5.99"));
        }

        @Test
        @DisplayName("a negative amount truncates toward zero rather than away from it, which is what a "
                + "COBOL store without a rounding clause does")
        void aNegativeAmountTruncatesTowardZero() {
            final Account subject = new Account("00000000011", "Y",
                    new BigDecimal("-1.999"), BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2),
                    "2020-01-01", "2099-12-31", "2020-01-01",
                    BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2), "12345", "          ");

            subject.normalizeAndValidateBeforeWrite();

            assertThat(subject.getAcctCurrBal()).isEqualTo(new BigDecimal("-1.99"));
        }

        @Test
        @DisplayName("a value already at scale two is returned as the very same instance, so normalisation "
                + "costs nothing on the path the fixed-width codec produces")
        void aValueAlreadyAtScaleTwoIsUntouched() {
            final BigDecimal exact = new BigDecimal("123.45");
            final Transaction subject = transaction(exact);

            subject.normalizeAndValidateBeforeWrite();

            assertThat(subject.getTranAmt()).isSameAs(exact);
        }

        @Test
        @DisplayName("the transaction amount, the daily amount, the category balance and the interest rate "
                + "are each normalised by their own entity")
        void everyStoredAmountIsNormalisedByItsOwnEntity() {
            final Transaction posted = transaction(new BigDecimal("10.007"));
            final DailyTransaction daily = dailyTransaction("0000000000000042",
                    new BigDecimal("20.009"));
            final TransactionCategoryBalance balance = new TransactionCategoryBalance("00000000011", "01",
                    "0005", new BigDecimal("30.001"));
            final DisclosureGroup group = new DisclosureGroup("A000000000", "01", "0005",
                    new BigDecimal("1.759"));

            posted.normalizeAndValidateBeforeWrite();
            daily.normalizeAndValidateBeforeWrite();
            balance.normalizeAndValidateBeforeWrite();
            group.normalizeAndValidateBeforeWrite();

            assertThat(posted.getTranAmt()).isEqualTo(new BigDecimal("10.00"));
            assertThat(daily.getDalytranAmt()).isEqualTo(new BigDecimal("20.00"));
            assertThat(balance.getTranCatBal()).isEqualTo(new BigDecimal("30.00"));
            assertThat(group.getDisIntRate()).isEqualTo(new BigDecimal("1.75"));
        }

        @Test
        @DisplayName("an amount beyond the declared precision is refused, naming the attribute rather than "
                + "surfacing a numeric-overflow failure from the driver")
        void anAmountBeyondTheDeclaredPrecisionIsRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(transaction(new BigDecimal("1000000000.00"))
                            ::normalizeAndValidateBeforeWrite)
                    .withMessageContaining("tranAmt")
                    .withMessageContaining("9 digits before the decimal point");
            assertThatNoException()
                    .as("the widest value the nine-digit field can hold is accepted")
                    .isThrownBy(transaction(new BigDecimal("999999999.99"))
                            ::normalizeAndValidateBeforeWrite);
        }

        @Test
        @DisplayName("an absent amount is refused, because the column is declared not-null and the legacy "
                + "field carries zeros rather than nothing")
        void anAbsentAmountIsRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(transaction(null)::normalizeAndValidateBeforeWrite)
                    .withMessageContaining("tranAmt")
                    .withMessageContaining("must be present");
        }
    }

    /**
     * Builds an account carrying the supplied identifier and otherwise legitimate values.
     *
     * @param acctId the identifier to carry, which may deliberately be malformed
     * @return an account ready for the callback
     */
    private static Account account(final String acctId) {
        return new Account(acctId, "Y", new BigDecimal("100.00"), new BigDecimal("500.00"),
                new BigDecimal("200.00"), "2020-01-01", "2099-12-31", "2020-01-01",
                new BigDecimal("10.00"), new BigDecimal("20.00"), "12345", "          ");
    }

    /**
     * Builds a customer carrying the supplied identifier and otherwise legitimate values.
     *
     * @param custId the identifier to carry, which may deliberately be malformed
     * @return a customer ready for the callback
     */
    private static Customer customer(final String custId) {
        return new Customer(custId, "First", "M", "Last", "Line one", "Line two", "Line three", "NY",
                "USA", "10001", "(212)555-0100", "(212)555-0101", null, PROTECTED_VALUE, "1980-01-01",
                "0000000000", "Y", "700");
    }

    /**
     * Builds a posted transaction carrying the supplied amount.
     *
     * @param amount the amount to carry, which may deliberately be over-scaled, over-wide or absent
     * @return a transaction ready for the callback
     */
    private static Transaction transaction(final BigDecimal amount) {
        return new Transaction("0000000000000042", "01", "0005", "System    ", "unit fixture", amount,
                "000000001", "merchant", "city", "zip       ", "4111111111111111",
                "2022-07-18 00:00:00.000000", "2022-07-18 00:00:00.000000");
    }

    /**
     * Builds one account carrying the given group identifier and nothing else of interest.
     *
     * @param groupIdentifier the value under test
     * @return the account
     */
    private static Account accountWithGroup(final String groupIdentifier) {
        return new Account("00000000050", "Y", new BigDecimal("100.00"), new BigDecimal("5000.00"),
                new BigDecimal("500.00"), "2020-01-01", "2030-01-01", "2025-01-01",
                new BigDecimal("0.00"), new BigDecimal("0.00"), "99999     ", groupIdentifier);
    }

    /**
     * Builds a daily transaction carrying the supplied identifier and amount.
     *
     * <p>The identifier is the value under test in this class: the landing table checks its width, because
     * a row that cannot be re-encoded to 350 bytes cannot be echoed into a reject record either, and it
     * checks nothing about its content, because content is what the reject reason codes report.
     *
     * @param identifier the identifier to carry
     * @param amount     the amount to carry
     * @return a daily transaction ready for the callback
     */
    private static DailyTransaction dailyTransaction(final String identifier, final BigDecimal amount) {
        return new DailyTransaction(identifier, "01", "0005", "POS TERM  ", "unit fixture", amount,
                "000000001", "merchant", "city", "zip       ", "4111111111111111",
                "2022-07-18 00:00:00.000000", "2022-07-18 00:00:00.000000");
    }
}
