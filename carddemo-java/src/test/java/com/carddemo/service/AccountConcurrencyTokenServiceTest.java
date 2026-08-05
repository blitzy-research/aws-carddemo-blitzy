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
package com.carddemo.service;

import com.carddemo.api.dto.AccountUpdateRequest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.UnaryOperator;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.carddemo.domain.Account;
import com.carddemo.domain.Customer;
import com.carddemo.exception.OptimisticLockConflictException;
import com.carddemo.util.SensitiveFieldCodec;

/**
 * Unit test for {@link AccountConcurrencyTokenService}, which carries the account-update transaction's
 * old record image across the turn between presenting a screen and confirming it.
 *
 * <h2>What is being protected</h2>
 *
 * <p>The legacy program keeps that old image in a commarea extension the terminal cannot reach
 * ({@code app/cbl/COACTUPC.cbl} line 652 onward, returned at lines 1010 to 1018 and sliced back off at
 * lines 888 to 892) and compares it under lock in paragraph {@code 9700-CHECK-CHANGE-IN-REC}. Echoed to
 * a REST client, the same state would be under the client's control, and a client that can assert
 * "nothing changed" is authorising its own overwrite. Every test below is written against that threat
 * rather than against the happy path: the accept case is one test and the refuse cases are many.
 *
 * <h2>How the fixtures are built</h2>
 *
 * <p>Two record builders produce a realistic account and customer, and every mutation test changes
 * exactly one field of one record and asserts the refusal. Both protected columns are seeded with real
 * envelopes produced by the module's own codec under this suite's key, because the customer entity
 * refuses cleartext in either of them, so the fixtures exercise the same shapes production stores.
 *
 * <p>No identifier, name, address or number below belongs to a real person: the social-security range
 * is one that is never issued, and the government-issued identifier is self-evidently invented.
 *
 * <p>Two of the assertions below record deliberate divergences from the legacy comparison rather than
 * agreement with it: the postal code participates in the account digest although paragraph
 * {@code 9700-CHECK-CHANGE-IN-REC} omits it (decision log entry DL-076), and both protected identifiers
 * are digested as stored rather than as cleartext (decision log entry DL-077). Decision log entry
 * DL-074 records why the token is a sealed digest pair.
 *
 * <p>This is a pure unit test: no container, no Spring context, no database, no queue, no network and no
 * file system, and no elapsed-time or throughput assertion.
 */
@DisplayName("AccountConcurrencyTokenService - the sealed old image of COACTUPC 9700")
class AccountConcurrencyTokenServiceTest {

    /** Key material for this suite. Thirty-two bytes, which is what the codec requires. */
    private static final String BASE64_KEY = Base64.getEncoder().encodeToString(
            "carddemo-token-test-key-01234567".getBytes(StandardCharsets.UTF_8));

    /** A second, different, equally valid key, used to prove a token cannot cross keys. */
    private static final String OTHER_BASE64_KEY = Base64.getEncoder().encodeToString(
            "carddemo-token-test-key-98765432".getBytes(StandardCharsets.UTF_8));

    /** The verbatim legacy text this service's refusals must carry. */
    private static final String LEGACY_CONFLICT_MESSAGE =
            OptimisticLockConflictException.MSG_DATA_WAS_CHANGED_BEFORE_UPDATE;

    private final SensitiveFieldEncryptionService encryption =
            new SensitiveFieldEncryptionService(BASE64_KEY);

    private final AccountConcurrencyTokenService service =
            new AccountConcurrencyTokenService(encryption);

    /**
     * The account as the screen presented it.
     *
     * @return a fresh account fixture
     */
    private static Account account() {
        return new Account("00000000011", "Y",
                new BigDecimal("-250.75"), new BigDecimal("5000.00"), new BigDecimal("1500.00"),
                "2020-01-15", "2027-12-31", "2024-06-30",
                new BigDecimal("0.00"), new BigDecimal("1234567890.12"),
                "48226", "DEFAULT   ");
    }

    /**
     * The customer as the screen presented it, with both protected columns carrying real envelopes.
     *
     * @return a fresh customer fixture
     */
    private Customer customer() {
        return new Customer("000000011", "MARY ANN", "Q", "Aniya Von",
                "1500 Woodward Avenue", "Apt. 4B", "Detroit", "MI", "USA",
                "48226", "3135550100", "2485550199",
                encryption.protect(SensitiveFieldEncryptionService.CUSTOMER_SSN_FIELD, "999887777"),
                encryption.protect(
                        SensitiveFieldEncryptionService.CUSTOMER_GOVT_ISSUED_ID_FIELD,
                        "FICTIONAL-ID-0000001"),
                "1985-07-04", "EFT0000001", "Y", "742");
    }

    /**
     * Rebuilds the account fixture with one field replaced, so a mutation test changes exactly one thing.
     *
     * @param mutation applied to the field under test
     * @param field    which field to replace
     * @return the mutated account
     */
    private static Account accountWith(AccountField field, UnaryOperator<String> mutation) {
        Account original = account();
        String[] text = {
            original.getAcctActiveStatus(), original.getAcctOpenDate(),
            original.getAcctExpirationDate(), original.getAcctReissueDate(),
            original.getAcctAddrZip(), original.getAcctGroupId()
        };
        BigDecimal[] amounts = {
            original.getAcctCurrBal(), original.getAcctCreditLimit(),
            original.getAcctCashCreditLimit(), original.getAcctCurrCycCredit(),
            original.getAcctCurrCycDebit()
        };
        if (field.textIndex >= 0) {
            text[field.textIndex] = mutation.apply(text[field.textIndex]);
        } else {
            amounts[field.amountIndex] =
                    new BigDecimal(mutation.apply(amounts[field.amountIndex].toPlainString()));
        }
        return new Account(original.getAcctId(), text[0], amounts[0], amounts[1], amounts[2],
                text[1], text[2], text[3], amounts[3], amounts[4], text[4], text[5]);
    }

    /** The account fields the comparison covers, addressed positionally so no reflection is needed. */
    private enum AccountField {

        ACTIVE_STATUS(0, -1),
        OPEN_DATE(1, -1),
        EXPIRATION_DATE(2, -1),
        REISSUE_DATE(3, -1),
        POSTAL_CODE(4, -1),
        GROUP_ID(5, -1),
        CURRENT_BALANCE(-1, 0),
        CREDIT_LIMIT(-1, 1),
        CASH_CREDIT_LIMIT(-1, 2),
        CURRENT_CYCLE_CREDIT(-1, 3),
        CURRENT_CYCLE_DEBIT(-1, 4);

        private final int textIndex;
        private final int amountIndex;

        AccountField(int textIndex, int amountIndex) {
            this.textIndex = textIndex;
            this.amountIndex = amountIndex;
        }
    }

    /**
     * Rebuilds a given customer with one of its eighteen constructor arguments replaced.
     *
     * <p>The record to rebuild is a parameter rather than a fresh fixture on purpose. Each call to
     * {@link #customer()} seals the two protected columns under a new vector, so rebuilding from a
     * fresh fixture would change those two columns as well and every mutation test would pass for
     * the wrong reason. Carrying the stored envelopes across isolates the one field under test.
     *
     * @param original the record the token was minted from, whose other seventeen values are carried
     *                 across unchanged
     * @param position zero-based argument position to replace
     * @param value    the replacement value
     * @return the mutated customer
     */
    private Customer customerWith(Customer original, int position, String value) {
        List<String> arguments = Arrays.asList(
                original.getCustId(), original.getFirstName(), original.getMiddleName(),
                original.getLastName(), original.getAddrLine1(), original.getAddrLine2(),
                original.getAddrLine3(), original.getAddrStateCd(), original.getAddrCountryCd(),
                original.getAddrZip(), original.getPhoneNum1(), original.getPhoneNum2(),
                original.getCustSsn(), original.getGovtIssuedId(), original.getCustDob(),
                original.getEftAccountId(), original.getPriCardHolderInd(),
                original.getFicoCreditScore());
        arguments.set(position, value);
        return new Customer(arguments.get(0), arguments.get(1), arguments.get(2), arguments.get(3),
                arguments.get(4), arguments.get(5), arguments.get(6), arguments.get(7),
                arguments.get(8), arguments.get(9), arguments.get(10), arguments.get(11),
                arguments.get(12), arguments.get(13), arguments.get(14), arguments.get(15),
                arguments.get(16), arguments.get(17));
    }

    @Nested
    @DisplayName("An unchanged pair of records is accepted")
    class AnUnchangedPairOfRecordsIsAccepted {

        @Test
        @DisplayName("a token minted from a pair verifies against the same pair")
        void aTokenMintedFromAPairVerifiesAgainstTheSamePair() {
            Account account = account();
            Customer customer = customer();

            String token = service.mint(account, customer);

            assertThatCode(() -> service.verify(token, account, customer))
                    .as("nothing changed, so the confirmation must be allowed to proceed")
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("an account rebuilt from the same values verifies, because the comparison is of "
                + "content and not of object identity")
        void anAccountRebuiltFromTheSameValuesVerifies() {
            Customer stored = customer();
            String token = service.mint(account(), stored);

            assertThatCode(() -> service.verify(token, account(), stored))
                    .as("a repository hands back a different instance on every read; only a "
                            + "content comparison survives that")
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("a protected column re-sealed under a fresh vector reads as a change, which is "
                + "the documented divergence from a cleartext comparison")
        void aProtectedColumnResealedUnderAFreshVectorReadsAsAChange() {
            String token = service.mint(account(), customer());

            // Each Customer fixture seals its two protected columns afresh, so the same cleartext is
            // stored as different bytes. The legacy compares cleartext and would call this unchanged;
            // digesting the stored form calls it changed. Both answers are safe, and this one errs
            // towards refusing the write, so the divergence is recorded rather than engineered away.
            assertThatExceptionOfType(OptimisticLockConflictException.class)
                    .isThrownBy(() -> service.verify(token, account(), customer()))
                    .withMessage(LEGACY_CONFLICT_MESSAGE)
                    .satisfies(conflict -> assertThat(conflict.entityName()).isEqualTo("Customer"));
        }

        @Test
        @DisplayName("a trailing zero on an amount is not a change, because the legacy compares two "
                + "zoned V99 fields numerically")
        void aTrailingZeroOnAnAmountIsNotAChange() {
            Customer customer = customer();
            String token = service.mint(account(), customer);
            Account sameAmountsDifferentScale = accountWith(AccountField.CREDIT_LIMIT,
                    plain -> new BigDecimal(plain).setScale(4, RoundingMode.UNNECESSARY)
                            .toPlainString());

            assertThat(sameAmountsDifferentScale.getAcctCreditLimit().scale()).isEqualTo(4);
            assertThatCode(() -> service.verify(token, sameAmountsDifferentScale, customer))
                    .as("5000.00 and 5000.0000 are the same amount; only a scale-sensitive "
                            + "comparison would have called this a change")
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("a different date separator is not a change, because the legacy tests the year, "
                + "month and day positions and never the separator positions")
        void aDifferentDateSeparatorIsNotAChange() {
            Customer customer = customer();
            String token = service.mint(account(), customer);
            Account slashedDates = accountWith(AccountField.OPEN_DATE, date -> date.replace('-', '/'));

            assertThat(slashedDates.getAcctOpenDate()).isEqualTo("2020/01/15");
            assertThatCode(() -> service.verify(token, slashedDates, customer))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("a case-only change to the account group id is not a change, because the legacy "
                + "compares that one field through a fold on both sides")
        void aCaseOnlyChangeToTheAccountGroupIdIsNotAChange() {
            Customer customer = customer();
            String token = service.mint(account(), customer);
            Account lowerCased = accountWith(AccountField.GROUP_ID, value -> value.toLowerCase(Locale.ROOT));

            assertThat(lowerCased.getAcctGroupId()).isEqualTo("default   ");
            assertThatCode(() -> service.verify(token, lowerCased, customer))
                    .doesNotThrowAnyException();
        }

        @ParameterizedTest(name = "customer argument {0} folded to lower case is not a change")
        @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8})
        @DisplayName("a case-only change to a folded customer field is not a change, matching the "
                + "eight fields the legacy compares through an upper fold")
        void aCaseOnlyChangeToAFoldedCustomerFieldIsNotAChange(int position) {
            Customer stored = customer();
            String token = service.mint(account(), stored);
            // Folded with an explicit root locale: the default-locale form folds ASCII I to dotless
            // i under Turkish, which is a different character rather than the same letter in another
            // case, so the intended case-only change would become a genuine change and the verify
            // below would report a conflict that has nothing to do with the behaviour under test.
            Customer foldedDifferently = customerWith(
                    stored, position, valueAt(stored, position).toLowerCase(Locale.ROOT));

            assertThatCode(() -> service.verify(token, account(), foldedDifferently))
                    .as("argument %d is compared through FUNCTION UPPER-CASE on both sides", position)
                    .doesNotThrowAnyException();
        }

        /**
         * Reads one of the eight folded customer arguments positionally, so the parameterised test can
         * name a field without reflection.
         *
         * @param customer the fixture
         * @param position zero-based constructor argument position
         * @return the value at that position
         */
        private String valueAt(Customer customer, int position) {
            return switch (position) {
                case 1 -> customer.getFirstName();
                case 2 -> customer.getMiddleName();
                case 3 -> customer.getLastName();
                case 4 -> customer.getAddrLine1();
                case 5 -> customer.getAddrLine2();
                case 6 -> customer.getAddrLine3();
                case 7 -> customer.getAddrStateCd();
                case 8 -> customer.getAddrCountryCd();
                default -> throw new IllegalArgumentException("position " + position
                        + " is not one of the eight folded customer fields");
            };
        }
    }

    @Nested
    @DisplayName("A concurrent change to either record refuses the write")
    class AConcurrentChangeToEitherRecordRefusesTheWrite {

        @ParameterizedTest(name = "a changed {0} refuses the write")
        @ValueSource(strings = {"ACTIVE_STATUS", "OPEN_DATE", "EXPIRATION_DATE", "REISSUE_DATE",
                "POSTAL_CODE", "GROUP_ID", "CURRENT_BALANCE", "CREDIT_LIMIT", "CASH_CREDIT_LIMIT",
                "CURRENT_CYCLE_CREDIT", "CURRENT_CYCLE_DEBIT"})
        @DisplayName("every account field the comparison covers refuses the write when it moves")
        void everyAccountFieldTheComparisonCoversRefusesTheWriteWhenItMoves(String fieldName) {
            AccountField field = AccountField.valueOf(fieldName);
            Customer customer = customer();
            String token = service.mint(account(), customer);
            Account changed = field.textIndex >= 0
                    ? accountWith(field, value -> field == AccountField.OPEN_DATE
                            || field == AccountField.EXPIRATION_DATE
                            || field == AccountField.REISSUE_DATE
                            ? "1999-01-01" : "Z" + value.substring(1))
                    : accountWith(field, plain -> new BigDecimal(plain)
                            .add(new BigDecimal("0.01")).toPlainString());

            assertThatExceptionOfType(OptimisticLockConflictException.class)
                    .isThrownBy(() -> service.verify(token, changed, customer))
                    .withMessage(LEGACY_CONFLICT_MESSAGE)
                    .satisfies(conflict -> {
                        assertThat(conflict.conflictKind()).isEqualTo(
                                OptimisticLockConflictException.ConflictKind
                                        .RECORD_CHANGED_BEFORE_UPDATE);
                        assertThat(conflict.entityName()).isEqualTo("Account");
                        assertThat(conflict.key()).isEqualTo("00000000011");
                    });
        }

        @ParameterizedTest(name = "a changed customer argument {0} refuses the write")
        @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 14, 15, 16, 17})
        @DisplayName("every unprotected customer field the comparison covers refuses the write when "
                + "it moves")
        void everyUnprotectedCustomerFieldRefusesTheWriteWhenItMoves(int position) {
            Customer stored = customer();
            String token = service.mint(account(), stored);
            Customer changed =
                    customerWith(stored, position, position == 14 ? "1999-01-01" : "9");

            assertThatExceptionOfType(OptimisticLockConflictException.class)
                    .isThrownBy(() -> service.verify(token, account(), changed))
                    .withMessage(LEGACY_CONFLICT_MESSAGE)
                    .satisfies(conflict -> assertThat(conflict.entityName())
                            .as("naming the record that moved is what makes the refusal diagnosable")
                            .isEqualTo("Customer"));
        }

        @Test
        @DisplayName("a changed national identifier refuses the write, so a protected column is "
                + "covered as surely as a plain one")
        void aChangedNationalIdentifierRefusesTheWrite() {
            Customer stored = customer();
            String token = service.mint(account(), stored);
            Customer changed = customerWith(stored, 12, encryption.protect(
                    SensitiveFieldEncryptionService.CUSTOMER_SSN_FIELD, "999887778"));

            assertThatExceptionOfType(OptimisticLockConflictException.class)
                    .isThrownBy(() -> service.verify(token, account(), changed))
                    .withMessage(LEGACY_CONFLICT_MESSAGE);
        }

        @Test
        @DisplayName("a changed government-issued identifier refuses the write")
        void aChangedGovernmentIssuedIdentifierRefusesTheWrite() {
            Customer stored = customer();
            String token = service.mint(account(), stored);
            Customer changed = customerWith(stored, 13,
                    encryption.protect("customer.cust_govt_issued_id", "FICTIONAL-ID-0000002"));

            assertThatExceptionOfType(OptimisticLockConflictException.class)
                    .isThrownBy(() -> service.verify(token, account(), changed))
                    .withMessage(LEGACY_CONFLICT_MESSAGE);
        }

        @Test
        @DisplayName("a field emptied rather than altered refuses the write, because absent and "
                + "empty are different states")
        void aFieldEmptiedRatherThanAlteredRefusesTheWrite() {
            Customer stored = customer();
            String token = service.mint(account(), stored);

            assertThatExceptionOfType(OptimisticLockConflictException.class)
                    .isThrownBy(() -> service.verify(token, account(), customerWith(stored, 2, "")))
                    .withMessage(LEGACY_CONFLICT_MESSAGE);
            assertThatExceptionOfType(OptimisticLockConflictException.class)
                    .isThrownBy(() -> service.verify(token, account(), customerWith(stored, 2, null)))
                    .withMessage(LEGACY_CONFLICT_MESSAGE);
        }

        @Test
        @DisplayName("a token minted for one account cannot be presented for another")
        void aTokenMintedForOneAccountCannotBePresentedForAnother() {
            Customer stored = customer();
            String token = service.mint(account(), stored);
            Account differentAccount = new Account("00000000099", "Y",
                    new BigDecimal("-250.75"), new BigDecimal("5000.00"), new BigDecimal("1500.00"),
                    "2020-01-15", "2027-12-31", "2024-06-30",
                    new BigDecimal("0.00"), new BigDecimal("1234567890.12"),
                    "48226", "DEFAULT   ");

            assertThatExceptionOfType(OptimisticLockConflictException.class)
                    .isThrownBy(() -> service.verify(token, differentAccount, stored))
                    .as("every other field is identical, so only the identifier being inside the "
                            + "digest can refuse this")
                    .withMessage(LEGACY_CONFLICT_MESSAGE);
        }

        @Test
        @DisplayName("a token minted for one customer cannot be presented for another")
        void aTokenMintedForOneCustomerCannotBePresentedForAnother() {
            Customer stored = customer();
            String token = service.mint(account(), stored);

            assertThatExceptionOfType(OptimisticLockConflictException.class)
                    .isThrownBy(() ->
                            service.verify(token, account(), customerWith(stored, 0, "000000099")))
                    .as("every other field is identical, so only the identifier being inside the "
                            + "digest can refuse this")
                    .withMessage(LEGACY_CONFLICT_MESSAGE);
        }
    }

    @Nested
    @DisplayName("An untrustworthy token refuses the write")
    class AnUntrustworthyTokenRefusesTheWrite {

        @ParameterizedTest(name = "the token [{0}] refuses the write")
        @ValueSource(strings = {"", " ", "not-a-token", "ENC1:", "ENC1:not-base64!!",
                "ENC1:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"})
        @DisplayName("a blank, malformed or unauthenticated token is refused")
        void aBlankMalformedOrUnauthenticatedTokenIsRefused(String token) {
            assertThatExceptionOfType(OptimisticLockConflictException.class)
                    .isThrownBy(() -> service.verify(token, account(), customer()))
                    .withMessage(LEGACY_CONFLICT_MESSAGE);
        }

        @Test
        @DisplayName("an absent token is refused as a conflict rather than raising a different type, "
                + "so a caller cannot reach a write by simply omitting it")
        void anAbsentTokenIsRefusedAsAConflict() {
            assertThatExceptionOfType(OptimisticLockConflictException.class)
                    .isThrownBy(() -> service.verify(null, account(), customer()))
                    .withMessage(LEGACY_CONFLICT_MESSAGE)
                    .satisfies(conflict -> assertThat(conflict.conflictKind()).isEqualTo(
                            OptimisticLockConflictException.ConflictKind
                                    .RECORD_CHANGED_BEFORE_UPDATE));
        }

        @Test
        @DisplayName("a token whose ciphertext is altered by one character is refused, because the "
                + "envelope is authenticated rather than merely encoded")
        void aTokenWhoseCiphertextIsAlteredByOneCharacterIsRefused() {
            String token = service.mint(account(), customer());
            String tampered = flipOneBodyCharacter(token);

            assertThat(tampered).isNotEqualTo(token).hasSameSizeAs(token);
            assertThatExceptionOfType(OptimisticLockConflictException.class)
                    .isThrownBy(() -> service.verify(tampered, account(), customer()))
                    .withMessage(LEGACY_CONFLICT_MESSAGE);
        }

        @Test
        @DisplayName("a token sealed under a different key is refused, so a token cannot travel "
                + "between deployments")
        void aTokenSealedUnderADifferentKeyIsRefused() {
            AccountConcurrencyTokenService otherDeployment = new AccountConcurrencyTokenService(
                    new SensitiveFieldEncryptionService(OTHER_BASE64_KEY));
            Account account = account();
            Customer stored = customer();
            String foreignToken = otherDeployment.mint(account, stored);

            assertThatExceptionOfType(OptimisticLockConflictException.class)
                    .isThrownBy(() -> service.verify(foreignToken, account, stored))
                    .withMessage(LEGACY_CONFLICT_MESSAGE);
        }

        @Test
        @DisplayName("a protected column's own envelope cannot be presented as a token, because the "
                + "binding is sealed inside the authenticated payload")
        void aProtectedColumnsOwnEnvelopeCannotBePresentedAsAToken() {
            String storedSsn = encryption.protect(
                    SensitiveFieldEncryptionService.CUSTOMER_SSN_FIELD, "999887777");

            assertThat(SensitiveFieldCodec.hasEnvelopeShape(storedSsn))
                    .as("the replayed value is structurally a valid envelope under the same key, so "
                            + "only the binding can refuse it")
                    .isTrue();
            assertThatExceptionOfType(OptimisticLockConflictException.class)
                    .isThrownBy(() -> service.verify(storedSsn, account(), customer()))
                    .withMessage(LEGACY_CONFLICT_MESSAGE);
        }

        @Test
        @DisplayName("a payload sealed under this service's own binding but carrying a foreign "
                + "scheme is refused, so a future token format is recognised rather than misread")
        void aPayloadCarryingAForeignSchemeIsRefused() {
            String foreignScheme = encryption.protect(
                    AccountConcurrencyTokenService.CONCURRENCY_TOKEN_FIELD,
                    "ACUP2\u001Fdeadbeef\u001Fdeadbeef");

            assertThatExceptionOfType(OptimisticLockConflictException.class)
                    .isThrownBy(() -> service.verify(foreignScheme, account(), customer()))
                    .withMessage(LEGACY_CONFLICT_MESSAGE);
        }

        @Test
        @DisplayName("a truncated payload is refused rather than accepted on its first part")
        void aTruncatedPayloadIsRefused() {
            String truncated = encryption.protect(
                    AccountConcurrencyTokenService.CONCURRENCY_TOKEN_FIELD,
                    AccountConcurrencyTokenService.PAYLOAD_SCHEME + "\u001Fdeadbeef");

            assertThatExceptionOfType(OptimisticLockConflictException.class)
                    .isThrownBy(() -> service.verify(truncated, account(), customer()))
                    .withMessage(LEGACY_CONFLICT_MESSAGE);
        }

        /**
         * Alters exactly one character inside the envelope body, well clear of the scheme marker and of
         * the trailing encoding padding, so the result decodes to a body of the same length and fails
         * on authentication rather than on structure.
         *
         * @param token the token to alter
         * @return the altered token
         */
        private String flipOneBodyCharacter(String token) {
            int position = SensitiveFieldCodec.ENVELOPE_PREFIX.length() + 10;
            char original = token.charAt(position);
            char replacement = original == 'A' ? 'B' : 'A';
            return token.substring(0, position) + replacement + token.substring(position + 1);
        }
    }

    @Nested
    @DisplayName("The token itself discloses nothing")
    class TheTokenItselfDisclosesNothing {

        @Test
        @DisplayName("no field value, key or digestible fragment of either record appears in the token")
        void noFieldValueOrKeyAppearsInTheToken() {
            Account account = account();
            Customer stored = customer();

            String token = service.mint(account, stored);

            // Every needle here is at least four characters. The token is an authenticated-encryption
            // envelope over a fresh vector, so its Base64 body is indistinguishable from random text
            // drawn from a 64-character alphabet: a needle of length n coincides with a body of b
            // positions with probability about b / 64^n, which for four characters is about one run in
            // eighty thousand and falls steeply from there. A shorter needle would measure the random
            // generator rather than the contract - the three-character credit score is therefore
            // asserted below by the properties that hold with certainty, not as a substring.
            assertThat(token)
                    .as("the token is a sealed digest pair; a client that could read it could plan "
                            + "an overwrite around it")
                    .doesNotContain("00000000011", "000000011", "5000.00", "1500.00", "-250.75",
                            "1234567890.12", "2020", "2027", "2024", "48226", "DEFAULT", "default",
                            "MARY", "Aniya", "Woodward", "Detroit", "3135550100", "2485550199",
                            "999887777", "FICTIONAL", "1985", "EFT0000001");
            assertThat(token)
                    .as("the body is re-randomised on every mint, so nothing short enough to coincide "
                            + "with it can be read out of it either")
                    .isNotEqualTo(service.mint(account, stored));
        }

        @Test
        @DisplayName("two mints of the same pair differ, because the envelope carries a fresh vector, "
                + "and both still verify")
        void twoMintsOfTheSamePairDifferAndBothStillVerify() {
            Account account = account();
            Customer stored = customer();

            Set<String> tokens = new LinkedHashSet<>();
            for (int mint = 0; mint < 8; mint++) {
                tokens.add(service.mint(account, stored));
            }

            assertThat(tokens)
                    .as("a deterministic token would be a stable fingerprint of the record pair")
                    .hasSize(8);
            for (String token : tokens) {
                assertThatCode(() -> service.verify(token, account, stored))
                        .doesNotThrowAnyException();
            }
        }

        @Test
        @DisplayName("the token is shaped like every other protected value in the module, so nothing "
                + "about it advertises what it is")
        void theTokenIsShapedLikeEveryOtherProtectedValue() {
            String token = service.mint(account(), customer());

            assertThat(SensitiveFieldCodec.hasEnvelopeShape(token)).isTrue();
            assertThat(token).startsWith(SensitiveFieldCodec.ENVELOPE_PREFIX);
        }

        @Test
        @DisplayName("the binding names the contract rather than a column, because no column stores it")
        void theBindingNamesTheContractRatherThanAColumn() {
            assertThat(AccountConcurrencyTokenService.CONCURRENCY_TOKEN_FIELD)
                    .isEqualTo("account_update.concurrency_token")
                    .isNotEqualTo(SensitiveFieldEncryptionService.CUSTOMER_SSN_FIELD);
            assertThat(AccountConcurrencyTokenService.PAYLOAD_SCHEME).isEqualTo("ACUP1");
        }
    }

    @Nested
    @DisplayName("An absent value is not an empty one, and a short value is not a truncated one")
    class AnAbsentValueIsNotAnEmptyOne {

        @Test
        @DisplayName("records holding no dates and no amounts still mint and verify")
        void recordsHoldingNoDatesAndNoAmountsStillMintAndVerify() {
            Account sparse = new Account("00000000011", "Y", null, null, null,
                    null, null, null, null, null, null, null);
            Customer sparseCustomer = customerWith(customer(), 14, null);

            String token = service.mint(sparse, sparseCustomer);

            assertThatCode(() -> service.verify(token, sparse, sparseCustomer))
                    .as("a record with nothing in an optional field is a legitimate state, not a "
                            + "failure to read it")
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("a date arriving where there was none refuses the write, and so does a date "
                + "leaving where there was one")
        void aDateArrivingWhereThereWasNoneRefusesTheWrite() {
            Customer stored = customer();
            Account withoutDates = new Account("00000000011", "Y",
                    new BigDecimal("-250.75"), new BigDecimal("5000.00"), new BigDecimal("1500.00"),
                    null, null, null,
                    new BigDecimal("0.00"), new BigDecimal("1234567890.12"), "48226", "DEFAULT   ");
            String tokenForTheDatelessAccount = service.mint(withoutDates, stored);
            String tokenForTheDatedAccount = service.mint(account(), stored);

            assertThatExceptionOfType(OptimisticLockConflictException.class)
                    .isThrownBy(() -> service.verify(tokenForTheDatelessAccount, account(), stored))
                    .withMessage(LEGACY_CONFLICT_MESSAGE);
            assertThatExceptionOfType(OptimisticLockConflictException.class)
                    .isThrownBy(() -> service.verify(tokenForTheDatedAccount, withoutDates, stored))
                    .withMessage(LEGACY_CONFLICT_MESSAGE);
        }

        @Test
        @DisplayName("an amount arriving where there was none refuses the write, so absent and zero "
                + "are not the same balance")
        void anAmountArrivingWhereThereWasNoneRefusesTheWrite() {
            Customer stored = customer();
            Account withoutABalance = new Account("00000000011", "Y",
                    null, new BigDecimal("5000.00"), new BigDecimal("1500.00"),
                    "2020-01-15", "2027-12-31", "2024-06-30",
                    new BigDecimal("0.00"), new BigDecimal("1234567890.12"), "48226", "DEFAULT   ");
            String token = service.mint(withoutABalance, stored);
            Account nowZero = accountWith(AccountField.CURRENT_BALANCE, plain -> "0.00");

            assertThatExceptionOfType(OptimisticLockConflictException.class)
                    .isThrownBy(() -> service.verify(token, nowZero, stored))
                    .as("a balance of zero is a value; holding none is not")
                    .withMessage(LEGACY_CONFLICT_MESSAGE);
        }

        @Test
        @DisplayName("a date too short to hold a month or a day is read as far as it goes rather "
                + "than raising")
        void aDateTooShortToHoldAMonthOrADayIsReadAsFarAsItGoes() {
            Customer stored = customer();
            Account yearOnly = accountWith(AccountField.OPEN_DATE, date -> "2020");
            String token = service.mint(yearOnly, stored);

            assertThatCode(() -> service.verify(token, yearOnly, stored))
                    .as("a malformed stored date must not make the concurrency check unusable; "
                            + "reading it consistently is enough for a comparison")
                    .doesNotThrowAnyException();
            assertThatExceptionOfType(OptimisticLockConflictException.class)
                    .isThrownBy(() -> service.verify(token, account(), stored))
                    .withMessage(LEGACY_CONFLICT_MESSAGE);
        }

        @Test
        @DisplayName("a year that agrees but a truncated month and day still refuse the write, "
                + "because the three positions are digested separately")
        void aYearThatAgreesButATruncatedMonthAndDayStillRefuseTheWrite() {
            Customer stored = customer();
            Account yearOnly = accountWith(AccountField.EXPIRATION_DATE, date -> "2027");
            Account yearAndMonth = accountWith(AccountField.EXPIRATION_DATE, date -> "2027-12");
            String token = service.mint(yearOnly, stored);

            assertThatExceptionOfType(OptimisticLockConflictException.class)
                    .isThrownBy(() -> service.verify(token, yearAndMonth, stored))
                    .withMessage(LEGACY_CONFLICT_MESSAGE);
        }
    }

    @Nested
    @DisplayName("The view turn, an intervening change, and the update turn")
    class TheViewTurnAnInterveningChangeAndTheUpdateTurn {

        @Test
        @DisplayName("an account changed between the view turn and the update turn refuses the write")
        void anAccountChangedBetweenTheViewTurnAndTheUpdateTurnRefusesTheWrite() {
            // Turn one: the operator asks to see the account. Both records are read and the screen is
            // returned carrying the token, which stands in for the commarea extension the terminal
            // could not reach.
            Account asDisplayed = account();
            Customer customerAsDisplayed = customer();
            String tokenReturnedWithTheScreen = service.mint(asDisplayed, customerAsDisplayed);

            // Between the turns: somebody else raises the credit limit. This is the whole point of the
            // check - nothing in the request the operator is about to send can reveal it.
            Account asStoredNow = accountWith(AccountField.CREDIT_LIMIT,
                    plain -> new BigDecimal(plain).add(new BigDecimal("2500.00")).toPlainString());

            // Turn two: the operator confirms. The records are read for update and compared.
            assertThatExceptionOfType(OptimisticLockConflictException.class)
                    .isThrownBy(() -> service.verify(tokenReturnedWithTheScreen, asStoredNow,
                            customerAsDisplayed))
                    .withMessage(LEGACY_CONFLICT_MESSAGE)
                    .satisfies(conflict -> {
                        assertThat(conflict.entityName()).isEqualTo("Account");
                        assertThat(conflict.key()).isEqualTo(asDisplayed.getAcctId());
                    });
        }

        @Test
        @DisplayName("a customer changed between the view turn and the update turn refuses the write, "
                + "even though the account itself did not move")
        void aCustomerChangedBetweenTheViewTurnAndTheUpdateTurnRefusesTheWrite() {
            Account asDisplayed = account();
            Customer customerAsDisplayed = customer();
            String tokenReturnedWithTheScreen = service.mint(asDisplayed, customerAsDisplayed);

            // Between the turns: the customer moves house. The account row is untouched, so a check
            // that watched only the account would let this write through and silently revert the move.
            Customer customerAsStoredNow =
                    customerWith(customerAsDisplayed, 4, "2000 Michigan Avenue");

            assertThatExceptionOfType(OptimisticLockConflictException.class)
                    .isThrownBy(() -> service.verify(tokenReturnedWithTheScreen, asDisplayed,
                            customerAsStoredNow))
                    .withMessage(LEGACY_CONFLICT_MESSAGE)
                    .satisfies(conflict -> {
                        assertThat(conflict.entityName()).isEqualTo("Customer");
                        assertThat(conflict.key()).isEqualTo(customerAsDisplayed.getCustId());
                    });
        }

        @Test
        @DisplayName("an update turn that follows the view turn with nothing intervening proceeds")
        void anUpdateTurnThatFollowsTheViewTurnWithNothingInterveningProceeds() {
            Account asDisplayed = account();
            Customer customerAsDisplayed = customer();
            String tokenReturnedWithTheScreen = service.mint(asDisplayed, customerAsDisplayed);

            assertThatCode(() -> service.verify(tokenReturnedWithTheScreen, asDisplayed,
                    customerAsDisplayed))
                    .as("the check must not stand in the way of the ordinary case")
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("a second update turn replaying the first turn's token refuses the write, so a "
                + "double submission cannot apply the same edit twice")
        void aSecondUpdateTurnReplayingTheFirstTurnsTokenRefusesTheWrite() {
            Account asDisplayed = account();
            Customer customerAsDisplayed = customer();
            String tokenReturnedWithTheScreen = service.mint(asDisplayed, customerAsDisplayed);

            // The first confirmation succeeds and writes a new credit limit.
            service.verify(tokenReturnedWithTheScreen, asDisplayed, customerAsDisplayed);
            Account afterTheFirstWrite = accountWith(AccountField.CREDIT_LIMIT, plain -> "7500.00");

            // The client resubmits the identical request, token included. The record has moved because
            // of the client's own first write, so its own token is now stale.
            assertThatExceptionOfType(OptimisticLockConflictException.class)
                    .isThrownBy(() -> service.verify(tokenReturnedWithTheScreen, afterTheFirstWrite,
                            customerAsDisplayed))
                    .withMessage(LEGACY_CONFLICT_MESSAGE);
        }

        @Test
        @DisplayName("the request the client sends back is what carries the token into the check")
        void theRequestTheClientSendsBackIsWhatCarriesTheTokenIntoTheCheck() {
            Account asDisplayed = account();
            Customer customerAsDisplayed = customer();
            String tokenReturnedWithTheScreen = service.mint(asDisplayed, customerAsDisplayed);

            // The screen contract is the only route the token takes back to the server, so the check
            // is wired to the request component rather than to a value the service kept for itself.
            AccountUpdateRequest echoed = requestCarrying(tokenReturnedWithTheScreen);

            assertThat(echoed.concurrencyToken()).isEqualTo(tokenReturnedWithTheScreen);
            assertThatCode(() -> service.verify(echoed.concurrencyToken(), asDisplayed,
                    customerAsDisplayed))
                    .doesNotThrowAnyException();
            assertThatExceptionOfType(OptimisticLockConflictException.class)
                    .isThrownBy(() -> service.verify(requestCarrying(null).concurrencyToken(),
                            asDisplayed, customerAsDisplayed))
                    .withMessage(LEGACY_CONFLICT_MESSAGE);
        }

        /**
         * Builds the confirmation request, populating only the component under test. The other
         * forty-five are the forty-three map fields plus the attention key and the carried navigation
         * state, none of which this check looks at.
         *
         * @param concurrencyToken the token the client echoes back, or {@code null} when it sends none
         * @return the request
         */
        private AccountUpdateRequest requestCarrying(String concurrencyToken) {
            return new AccountUpdateRequest(
                    null, null, null, null, null, null, null, null, null, null, null, null, null,
                    null, null, null, null, null, null, null, null, null, null, null, null, null,
                    null, null, null, null, null, null, null, null, null, null, null, null, null,
                    null, null, null, null, null, null, concurrencyToken);
        }
    }

    @Nested
    @DisplayName("Programming errors are separated from conflicts")
    class ProgrammingErrorsAreSeparatedFromConflicts {

        @Test
        @DisplayName("minting without a record is a programming error rather than a conflict")
        void mintingWithoutARecordIsAProgrammingError() {
            Customer stored = customer();

            assertThatNullPointerException().isThrownBy(() -> service.mint(null, stored));
            assertThatNullPointerException().isThrownBy(() -> service.mint(account(), null));
        }

        @Test
        @DisplayName("verifying without a record is a programming error rather than a conflict, "
                + "because a caller that has not read the records cannot be at the check yet")
        void verifyingWithoutARecordIsAProgrammingError() {
            String token = service.mint(account(), customer());

            assertThatNullPointerException()
                    .isThrownBy(() -> service.verify(token, null, customer()));
            assertThatNullPointerException()
                    .isThrownBy(() -> service.verify(token, account(), null));
        }

        @Test
        @DisplayName("constructing the service without the sealing collaborator is refused at once")
        void constructingTheServiceWithoutTheSealingCollaboratorIsRefused() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new AccountConcurrencyTokenService(null))
                    .withMessageContaining("fieldEncryption");
        }
    }
}
