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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.carddemo.api.dto.NavigationContext;
import com.carddemo.domain.Account;
import com.carddemo.domain.CardCrossReference;
import com.carddemo.domain.Transaction;
import com.carddemo.domain.enums.KeyAction;
import com.carddemo.exception.OptimisticLockConflictException;
import com.carddemo.exception.ValidationException;
import com.carddemo.repository.AccountRepository;
import com.carddemo.repository.CardCrossReferenceRepository;
import com.carddemo.repository.TransactionRepository;

import jakarta.persistence.OptimisticLockException;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.springframework.dao.OptimisticLockingFailureException;

/**
 * Unit test for {@link BillPaymentService}, the translation of the bill-payment transaction
 * {@code CB00} carried by {@code app/cbl/COBIL00C.cbl}.
 *
 * <p><strong>What is under test.</strong> One pseudo-conversational turn. The service decides, from
 * the navigation state and the attention key alone, whether to route away without a screen, send the
 * screen for a first entry, or receive and process a submitted screen. On a confirmed submission it
 * mints an identifier from the highest existing key, writes one transaction, drives the account
 * balance to zero and rewrites the account &mdash; in that order.
 *
 * <p><strong>Three contracts are verified byte for byte rather than approximately</strong>, because
 * each is easy to "improve" into a silent parity defect: the 26-character timestamp whose fraction is
 * always zeros, the sixteen-character zero-padded identifier whose first value is
 * {@code 0000000000000001} and not {@code 1}, and the success message whose two consecutive spaces
 * come from two adjoining literal fragments.
 *
 * <p>Every expected literal in this file is authored here from the cited source line rather than read
 * back from the class under test, so a change to a message, a width or a padding rule fails a test
 * instead of quietly redefining the contract.
 */
@DisplayName("BillPaymentService - the CB00 bill-payment turn")
class BillPaymentServiceTest {

    /** An account identifier at its declared eleven-character width. */
    private static final String ACCOUNT_ID = "00000000011";

    /** A card number at its declared sixteen-character width. */
    private static final String CARD_NUMBER = "4111111111111111";

    /** A customer identifier at its declared nine-character width. */
    private static final String CUSTOMER_ID = "000000011";

    /**
     * A clock whose sub-second reading is deliberately non-zero.
     *
     * <p>This is the fixture that catches a formatter emitting real microseconds: the rendered
     * timestamp must still end in six zeros even though the clock is carrying 123456789 nanoseconds.
     */
    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2024-03-07T14:25:36.123456789Z"), ZoneOffset.UTC);

    private final TransactionRepository transactionRepository =
            Mockito.mock(TransactionRepository.class);

    private final AccountRepository accountRepository = Mockito.mock(AccountRepository.class);

    private final CardCrossReferenceRepository crossReferenceRepository =
            Mockito.mock(CardCrossReferenceRepository.class);

    private final MessageCatalogService messageCatalogService = new MessageCatalogService();

    private final NavigationService navigationService = new NavigationService();

    private final BillPaymentService service = new BillPaymentService(transactionRepository,
            accountRepository, crossReferenceRepository, messageCatalogService, navigationService,
            FIXED_CLOCK);

    // ----------------------------------------------------------------------------------------------
    // Fixtures
    // ----------------------------------------------------------------------------------------------

    private static Account accountWithBalance(final String balance) {
        return new Account(ACCOUNT_ID,
                "Y",
                new BigDecimal(balance),
                new BigDecimal("5000.00"),
                new BigDecimal("1000.00"),
                "2020-01-01",
                "2030-01-01",
                "2025-01-01",
                new BigDecimal("0.00"),
                new BigDecimal("0.00"),
                "0000012345",
                "          ");
    }

    private static NavigationContext reEntry() {
        return NavigationContext.empty().withReEntry();
    }

    private static NavigationContext firstEntry() {
        return NavigationContext.empty().withFirstEntry();
    }

    private static NavigationContext firstEntryNominating(final String accountId) {
        return new NavigationContext(null, null, null, null, null, null,
                NavigationContext.ProgramContext.ENTER, null, null, null, null, accountId, null,
                null, null, null);
    }

    private BillPaymentService.BillPaymentScreenInput submitted(final String accountId,
            final String confirm) {
        return new BillPaymentService.BillPaymentScreenInput(accountId, confirm, KeyAction.ENTER,
                reEntry());
    }

    /**
     * Arranges a confirmable payment: the account exists with the given balance, the cross-reference
     * resolves to a card, no identifier collides, and the rewrite echoes the account back.
     *
     * @param balance         the account's pre-payment balance
     * @param highestExisting the highest transaction identifier already stored, or {@code null} for an
     *                        empty table
     * @return the account instance the service will mutate
     */
    private Account arrangePayableAccount(final String balance, final String highestExisting) {
        final Account account = accountWithBalance(balance);
        Mockito.when(accountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.of(account));
        Mockito.when(accountRepository.saveAndFlush(Mockito.any(Account.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        Mockito.when(crossReferenceRepository.findFirstByXrefAcctIdOrderByXrefCardNumAsc(ACCOUNT_ID))
                .thenReturn(Optional.of(new CardCrossReference(CARD_NUMBER, CUSTOMER_ID, ACCOUNT_ID)));
        Mockito.when(transactionRepository.findMaxId())
                .thenReturn(Optional.ofNullable(highestExisting));
        Mockito.when(transactionRepository.existsById(Mockito.anyString())).thenReturn(false);
        Mockito.when(transactionRepository.save(Mockito.any(Transaction.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        return account;
    }

    private Transaction capturedInsert() {
        final ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
        Mockito.verify(transactionRepository).save(captor.capture());
        return captor.getValue();
    }

    // ==============================================================================================
    // Turn entry: lines 99 to 149
    // ==============================================================================================

    @Nested
    @DisplayName("Turn entry and attention keys, lines 99 to 149")
    class TurnEntry {

        @Test
        @DisplayName("a null input is refused rather than defaulted")
        void nullInputRefused() {
            assertThatNullPointerException().isThrownBy(() -> service.processBillPayment(null));
        }

        @Test
        @DisplayName("a turn carrying no navigation state transfers to sign-on and touches no data")
        void absentContextTransfersToSignOn() {
            final BillPaymentService.BillPaymentResult result = service.processBillPayment(
                    new BillPaymentService.BillPaymentScreenInput(null, null, null, null));

            assertThat(result.route()).isEqualTo(NavigationService.Route.SIGN_ON);
            assertThat(result.reArmedTransactionId()).isEmpty();
            assertThat(result.transaction()).isNull();
            assertThat(result.account()).isNull();
            Mockito.verifyNoInteractions(transactionRepository, accountRepository,
                    crossReferenceRepository);
        }

        @Test
        @DisplayName("a first entry sets the re-enter gate, positions the cursor on the account-id "
                + "field and re-arms this same transaction")
        void firstEntrySendsTheScreen() {
            final BillPaymentService.BillPaymentResult result = service.processBillPayment(
                    new BillPaymentService.BillPaymentScreenInput(null, null, null, firstEntry()));

            assertThat(result.reEnterGateSet()).isTrue();
            assertThat(result.navigationContext().reEntry()).isTrue();
            assertThat(result.focusField()).isEqualTo("ACTIDIN");
            assertThat(result.route()).isEqualTo(NavigationService.Route.BILL_PAYMENT);
            assertThat(result.reArmedTransactionId()).isEqualTo("CB00");
            assertThat(result.errorFlag()).isFalse();
            assertThat(result.message()).isEmpty();
            Mockito.verifyNoInteractions(transactionRepository, accountRepository,
                    crossReferenceRepository);
        }

        @Test
        @DisplayName("a first entry nominating an account runs the enter-key paragraph on that very "
                + "turn, which is the branch at lines 116 to 121")
        void firstEntryWithNominatedAccountProcessesTheEnterKey() {
            arrangePayableAccount("100.00", null);

            final BillPaymentService.BillPaymentResult result = service.processBillPayment(
                    new BillPaymentService.BillPaymentScreenInput(null, null, null,
                            firstEntryNominating(ACCOUNT_ID)));

            // The nominated value reached the account-id field and the account was actually read.
            Mockito.verify(accountRepository).findById(ACCOUNT_ID);
            // No confirmation was supplied, so the turn prompts rather than paying.
            assertThat(result.message()).isEqualTo("Confirm to make a bill payment...");
            assertThat(result.transaction()).isNull();
        }

        @Test
        @DisplayName("the exit key leaves for the calling menu, which is this screen's own default")
        void exitKeyReturnsToTheUserMenu() {
            final BillPaymentService.BillPaymentResult result = service.processBillPayment(
                    new BillPaymentService.BillPaymentScreenInput(null, null, KeyAction.PFK03,
                            reEntry()));

            assertThat(result.route()).isEqualTo(NavigationService.Route.USER_MENU);
            assertThat(result.reArmedTransactionId()).isEmpty();
            assertThat(result.navigationContext().fromProgram()).isEqualTo("COBIL00C");
            assertThat(result.navigationContext().fromTransactionId()).isEqualTo("CB00");
            assertThat(result.navigationContext().firstEntry()).isTrue();
        }

        @Test
        @DisplayName("the fourth function key clears every field and leaves no message")
        void clearKeyBlanksTheScreen() {
            final BillPaymentService.BillPaymentResult result = service.processBillPayment(
                    new BillPaymentService.BillPaymentScreenInput(ACCOUNT_ID, "Y", KeyAction.PFK04,
                            reEntry()));

            assertThat(result.screen().accountId()).isEqualTo(" ".repeat(11));
            assertThat(result.screen().currentBalance()).isEqualTo(" ".repeat(14));
            assertThat(result.screen().confirm()).isEqualTo(" ");
            assertThat(result.message()).isEmpty();
            assertThat(result.errorFlag()).isFalse();
            Mockito.verifyNoInteractions(transactionRepository, accountRepository,
                    crossReferenceRepository);
        }

        @ParameterizedTest
        @ValueSource(strings = {"CLEAR", "PA1", "PFK01", "PFK12"})
        @DisplayName("a key this screen does not map reports the catalogue's invalid-key message at "
                + "its full fifty-character width, untrimmed")
        void unmappedKeyReportsTheCatalogueMessage(final String keyName) {
            final BillPaymentService.BillPaymentResult result = service.processBillPayment(
                    new BillPaymentService.BillPaymentScreenInput(null, null,
                            KeyAction.valueOf(keyName), reEntry()));

            assertThat(result.errorFlag()).isTrue();
            assertThat(result.message()).isEqualTo(MessageCatalogService.CCDA_MSG_INVALID_KEY);
            assertThat(result.message()).hasSize(MessageCatalogService.COMMON_MESSAGE_WIDTH);
            assertThat(result.message()).isEqualTo("Invalid key pressed. Please see below..."
                    + " ".repeat(10));
        }

        @Test
        @DisplayName("a key that was never decoded reaches the same catch-all arm as an unmapped one")
        void absentKeyReachesTheCatchAll() {
            final BillPaymentService.BillPaymentResult result = service.processBillPayment(
                    new BillPaymentService.BillPaymentScreenInput(null, null, null, reEntry()));

            assertThat(result.errorFlag()).isTrue();
            assertThat(result.message()).isEqualTo(MessageCatalogService.CCDA_MSG_INVALID_KEY);
        }

        @Test
        @DisplayName("the header carries both titles verbatim plus the transaction and program names")
        void headerIsPopulated() {
            final BillPaymentService.BillPaymentResult result = service.processBillPayment(
                    new BillPaymentService.BillPaymentScreenInput(null, null, null, firstEntry()));

            assertThat(result.header().title01()).isEqualTo(MessageCatalogService.CCDA_TITLE01);
            assertThat(result.header().title02()).isEqualTo(MessageCatalogService.CCDA_TITLE02);
            assertThat(result.header().transactionName()).isEqualTo("CB00");
            assertThat(result.header().programName()).isEqualTo("COBIL00C");
            // Lines 328 to 332 and 334 to 338, from the fixed clock, with a two-digit year.
            assertThat(result.header().currentDate()).isEqualTo("03/07/24");
            assertThat(result.header().currentTime()).isEqualTo("14:25:36");
            assertThat(result.header().errorMessage()).hasSize(78);
        }
    }

    // ==============================================================================================
    // Validation, lines 158 to 206
    // ==============================================================================================

    @Nested
    @DisplayName("Validation, lines 158 to 206")
    class Validation {

        @ParameterizedTest
        @ValueSource(strings = {"", "           ", "\u0000\u0000\u0000"})
        @DisplayName("a blank account-id field is faulted with its own message and no account is read")
        void blankAccountIdRejected(final String accountId) {
            final BillPaymentService.BillPaymentResult result =
                    service.processBillPayment(submitted(accountId, "Y"));

            assertThat(result.errorFlag()).isTrue();
            assertThat(result.message()).isEqualTo("Acct ID can NOT be empty...");
            assertThat(result.focusField()).isEqualTo("ACTIDIN");
            assertThat(result.fieldErrors()).singleElement()
                    .satisfies(fieldError -> {
                        assertThat(fieldError.field()).isEqualTo("accountId");
                        assertThat(fieldError.bmsFieldId()).isEqualTo("ACTIDIN");
                        assertThat(fieldError.state())
                                .isEqualTo(ValidationException.FieldState.MISSING);
                    });
            Mockito.verifyNoInteractions(accountRepository);
        }

        @Test
        @DisplayName("the receive paragraph bounds the confirmation field to ONE character, so a "
                + "longer transmitted value keeps only its first character")
        void confirmationIsBoundedToOneCharacter() {
            arrangePayableAccount("33.00", null);

            // Three characters transmitted into a one-character field: the move at line 306 keeps the
            // leftmost character, so this selects the affirmative arm rather than the catch-all. The
            // truncation is the source's own, and the echoed field proves it happened.
            final BillPaymentService.BillPaymentResult result =
                    service.processBillPayment(submitted(ACCOUNT_ID, "y y"));

            assertThat(result.paymentAccepted()).isTrue();
            assertThat(result.confirmationState())
                    .isEqualTo(BillPaymentService.ConfirmPaymentFlag.YES);
        }

        @ParameterizedTest
        @ValueSource(strings = {"X", "1", "z", "\t"})
        @DisplayName("a confirmation field holding anything but the four accepted characters is "
                + "faulted with the invalid-value message")
        void invalidConfirmationRejected(final String confirm) {
            final BillPaymentService.BillPaymentResult result =
                    service.processBillPayment(submitted(ACCOUNT_ID, confirm));

            assertThat(result.errorFlag()).isTrue();
            assertThat(result.message()).isEqualTo("Invalid value. Valid values are (Y/N)...");
            assertThat(result.focusField()).isEqualTo("CONFIRM");
            assertThat(result.fieldErrors()).singleElement()
                    .satisfies(fieldError -> assertThat(fieldError.state())
                            .isEqualTo(ValidationException.FieldState.INVALID));
            Mockito.verifyNoInteractions(accountRepository);
        }

        @ParameterizedTest
        @ValueSource(strings = {"y", "Y"})
        @DisplayName("the affirmative arm accepts either case")
        void affirmativeConfirmationAcceptsEitherCase(final String confirm) {
            arrangePayableAccount("42.00", null);

            final BillPaymentService.BillPaymentResult result =
                    service.processBillPayment(submitted(ACCOUNT_ID, confirm));

            assertThat(result.paymentAccepted()).isTrue();
            assertThat(result.confirmationState())
                    .isEqualTo(BillPaymentService.ConfirmPaymentFlag.YES);
        }

        @ParameterizedTest
        @ValueSource(strings = {"n", "N"})
        @DisplayName("the negative arm clears the screen, raises the flag and leaves no message, in "
                + "either case, without reading an account")
        void negativeConfirmationClearsTheScreen(final String confirm) {
            final BillPaymentService.BillPaymentResult result =
                    service.processBillPayment(submitted(ACCOUNT_ID, confirm));

            assertThat(result.errorFlag()).isTrue();
            assertThat(result.message()).isEmpty();
            assertThat(result.screen().accountId()).isEqualTo(" ".repeat(11));
            assertThat(result.transaction()).isNull();
            Mockito.verifyNoInteractions(accountRepository);
        }

        @Test
        @DisplayName("a blank confirmation reads the account, shows the balance and prompts, without "
                + "paying")
        void blankConfirmationPrompts() {
            arrangePayableAccount("250.75", null);

            final BillPaymentService.BillPaymentResult result =
                    service.processBillPayment(submitted(ACCOUNT_ID, " "));

            assertThat(result.errorFlag()).isFalse();
            assertThat(result.message()).isEqualTo("Confirm to make a bill payment...");
            assertThat(result.focusField()).isEqualTo("CONFIRM");
            assertThat(result.confirmationState())
                    .isEqualTo(BillPaymentService.ConfirmPaymentFlag.NO);
            assertThat(result.screenBalance()).isEqualByComparingTo("250.75");
            assertThat(result.transaction()).isNull();
            Mockito.verify(transactionRepository, Mockito.never())
                    .save(Mockito.any(Transaction.class));
        }

        @Test
        @DisplayName("an account the read does not find is reported and nothing is paid")
        void accountNotFound() {
            Mockito.when(accountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.empty());

            final BillPaymentService.BillPaymentResult result =
                    service.processBillPayment(submitted(ACCOUNT_ID, "Y"));

            assertThat(result.errorFlag()).isTrue();
            assertThat(result.message()).isEqualTo("Account ID NOT found...");
            assertThat(result.transaction()).isNull();
            // Lines 193 and 194 still ran, so the balance field shows an edited zero.
            assertThat(result.screen().currentBalance()).isEqualTo("+0000000000.00");
        }

        @Test
        @DisplayName("a record with no balance reaches the catch-all arm of the account read")
        void accountWithoutBalanceReachesTheCatchAll() {
            final Account withoutBalance = Mockito.mock(Account.class);
            Mockito.when(withoutBalance.getAcctCurrBal()).thenReturn(null);
            Mockito.when(accountRepository.findById(ACCOUNT_ID))
                    .thenReturn(Optional.of(withoutBalance));

            final BillPaymentService.BillPaymentResult result =
                    service.processBillPayment(submitted(ACCOUNT_ID, "Y"));

            assertThat(result.errorFlag()).isTrue();
            assertThat(result.message()).isEqualTo("Unable to lookup Account...");
        }
    }

    // ==============================================================================================
    // The nothing-to-pay rejection, lines 197 to 206
    // ==============================================================================================

    @Nested
    @DisplayName("The nothing-to-pay rejection, lines 197 to 206")
    class NothingToPay {

        @ParameterizedTest
        @ValueSource(strings = {"0.00", "-1.00", "-0.01"})
        @DisplayName("a non-positive balance with a supplied account-id is rejected verbatim")
        void nonPositiveBalanceWithSuppliedAccountIdRejected(final String balance) {
            arrangePayableAccount(balance, null);

            final BillPaymentService.BillPaymentResult result =
                    service.processBillPayment(submitted(ACCOUNT_ID, "Y"));

            assertThat(result.errorFlag()).isTrue();
            assertThat(result.message()).isEqualTo("You have nothing to pay...");
            assertThat(result.focusField()).isEqualTo("ACTIDIN");
            assertThat(result.transaction()).isNull();
            Mockito.verify(transactionRepository, Mockito.never())
                    .save(Mockito.any(Transaction.class));
        }

        @Test
        @DisplayName("BOTH conditions are required: a blank account-id field never reaches this "
                + "rejection, because the earlier stage faulted it first")
        void blankAccountIdDoesNotReachTheRejection() {
            final BillPaymentService.BillPaymentResult blank =
                    service.processBillPayment(submitted("           ", "Y"));

            assertThat(blank.message()).isEqualTo("Acct ID can NOT be empty...");
            assertThat(blank.message()).isNotEqualTo("You have nothing to pay...");
        }

        @Test
        @DisplayName("a positive balance passes the rejection")
        void positiveBalancePasses() {
            arrangePayableAccount("0.01", null);

            final BillPaymentService.BillPaymentResult result =
                    service.processBillPayment(submitted(ACCOUNT_ID, "Y"));

            assertThat(result.paymentAccepted()).isTrue();
        }
    }

    // ==============================================================================================
    // Identifier generation, lines 208 to 219 and 472 to 496
    // ==============================================================================================

    @Nested
    @DisplayName("Identifier generation, lines 208 to 219 and 472 to 496")
    class IdentifierGeneration {

        @Test
        @DisplayName("on an EMPTY table the first identifier is exactly the sixteen-character string "
                + "0000000000000001, and never the bare 1")
        void firstIdentifierOnAnEmptyTable() {
            arrangePayableAccount("10.00", null);

            service.processBillPayment(submitted(ACCOUNT_ID, "Y"));

            assertThat(capturedInsert().getTranId()).isEqualTo("0000000000000001");
            assertThat(capturedInsert().getTranId()).isNotEqualTo("1");
            assertThat(capturedInsert().getTranId()).hasSize(16);
        }

        @Test
        @DisplayName("a highest existing key of 0000000000000009 yields 0000000000000010, which is "
                + "the zero fill and the string increment together")
        void incrementCarriesAndZeroFills() {
            arrangePayableAccount("10.00", "0000000000000009");

            service.processBillPayment(submitted(ACCOUNT_ID, "Y"));

            assertThat(capturedInsert().getTranId()).isEqualTo("0000000000000010");
        }

        @Test
        @DisplayName("the increment happens in the service, not the repository: the repository is "
                + "asked only for the maximum")
        void incrementHappensInTheService() {
            arrangePayableAccount("10.00", "0000000000000123");

            service.processBillPayment(submitted(ACCOUNT_ID, "Y"));

            Mockito.verify(transactionRepository).findMaxId();
            assertThat(capturedInsert().getTranId()).isEqualTo("0000000000000124");
        }

        @Test
        @DisplayName("a malformed maximum is refused rather than incremented, under the source's own "
                + "catch-all text")
        void malformedMaximumRefused() {
            arrangePayableAccount("10.00", "9");

            final BillPaymentService.BillPaymentResult result =
                    service.processBillPayment(submitted(ACCOUNT_ID, "Y"));

            assertThat(result.errorFlag()).isTrue();
            // The backward read reports first, and the refused insert then overwrites the message,
            // because every message site is an unconditional move.
            assertThat(result.message()).isEqualTo("Unable to Add Bill pay Transaction...");
            assertThat(result.transaction()).isNull();
            Mockito.verify(transactionRepository, Mockito.never())
                    .save(Mockito.any(Transaction.class));
        }

        @Test
        @DisplayName("a maximum of the right WIDTH but carrying a non-digit is refused too, because "
                + "the lexicographic maximum only tracks the numeric one over digits")
        void nonDigitMaximumOfCorrectWidthRefused() {
            arrangePayableAccount("10.00", "000000000000000X");

            final BillPaymentService.BillPaymentResult result =
                    service.processBillPayment(submitted(ACCOUNT_ID, "Y"));

            assertThat(result.errorFlag()).isTrue();
            assertThat(result.transaction()).isNull();
            Mockito.verify(transactionRepository, Mockito.never())
                    .save(Mockito.any(Transaction.class));
        }

        @Test
        @DisplayName("a colliding identifier is refused with the duplicate message rather than "
                + "silently overwriting the existing row")
        void duplicateIdentifierRefused() {
            arrangePayableAccount("10.00", "0000000000000001");
            Mockito.when(transactionRepository.existsById("0000000000000002")).thenReturn(true);

            final BillPaymentService.BillPaymentResult result =
                    service.processBillPayment(submitted(ACCOUNT_ID, "Y"));

            assertThat(result.errorFlag()).isTrue();
            assertThat(result.message()).isEqualTo("Tran ID already exist...");
            Mockito.verify(transactionRepository, Mockito.never())
                    .save(Mockito.any(Transaction.class));
        }

        @Test
        @DisplayName("no cross-reference row leaves the record without a card number, which the "
                + "insert's catch-all arm refuses")
        void missingCrossReferenceRefusesTheInsert() {
            arrangePayableAccount("10.00", null);
            Mockito.when(crossReferenceRepository
                    .findFirstByXrefAcctIdOrderByXrefCardNumAsc(ACCOUNT_ID))
                    .thenReturn(Optional.empty());

            final BillPaymentService.BillPaymentResult result =
                    service.processBillPayment(submitted(ACCOUNT_ID, "Y"));

            assertThat(result.errorFlag()).isTrue();
            assertThat(result.message()).isEqualTo("Unable to Add Bill pay Transaction...");
            Mockito.verify(transactionRepository, Mockito.never())
                    .save(Mockito.any(Transaction.class));
        }

        @Test
        @DisplayName("a cross-reference row with a blank card number reaches that read's catch-all")
        void blankCardNumberReachesTheCrossReferenceCatchAll() {
            arrangePayableAccount("10.00", null);
            Mockito.when(crossReferenceRepository
                    .findFirstByXrefAcctIdOrderByXrefCardNumAsc(ACCOUNT_ID))
                    .thenReturn(Optional.of(
                            new CardCrossReference(" ".repeat(16), CUSTOMER_ID, ACCOUNT_ID)));

            final BillPaymentService.BillPaymentResult result =
                    service.processBillPayment(submitted(ACCOUNT_ID, "Y"));

            assertThat(result.errorFlag()).isTrue();
            // The cross-reference read reports its own text, which the refused insert then overwrites.
            assertThat(result.message()).isEqualTo("Unable to Add Bill pay Transaction...");
        }
    }

    // ==============================================================================================
    // The synthesized transaction, lines 218 to 232
    // ==============================================================================================

    @Nested
    @DisplayName("The synthesized transaction, lines 218 to 232")
    class SynthesizedTransaction {

        @Test
        @DisplayName("every constant field carries its exact stored value, including the four-digit "
                + "category code and the ten-character source with its trailing spaces")
        void constantFieldsAreExact() {
            arrangePayableAccount("1234.56", null);

            service.processBillPayment(submitted(ACCOUNT_ID, "Y"));
            final Transaction inserted = capturedInsert();

            assertThat(inserted.getTranTypeCd()).isEqualTo("02");
            assertThat(inserted.getTranCatCd()).isEqualTo("0002");
            assertThat(inserted.getTranCatCd()).isNotEqualTo("2");
            assertThat(inserted.getTranSource()).isEqualTo("POS TERM  ");
            assertThat(inserted.getTranSource()).hasSize(10);
            assertThat(inserted.getTranDesc()).isEqualTo("BILL PAYMENT - ONLINE");
            assertThat(inserted.getMerchantId()).isEqualTo("999999999");
            assertThat(inserted.getMerchantName()).isEqualTo("BILL PAYMENT");
            assertThat(inserted.getMerchantCity()).isEqualTo("N/A");
            assertThat(inserted.getMerchantZip()).isEqualTo("N/A");
            assertThat(inserted.getTranCardNum()).isEqualTo(CARD_NUMBER);
        }

        @Test
        @DisplayName("the amount is the account's FULL pre-payment balance, at scale two")
        void amountIsTheFullPrePaymentBalance() {
            arrangePayableAccount("1234.56", null);

            final BillPaymentService.BillPaymentResult result =
                    service.processBillPayment(submitted(ACCOUNT_ID, "Y"));

            assertThat(capturedInsert().getTranAmt()).isEqualByComparingTo("1234.56");
            assertThat(capturedInsert().getTranAmt().scale()).isEqualTo(2);
            assertThat(result.screenBalance()).isEqualByComparingTo("1234.56");
        }

        @Test
        @DisplayName("the origination and processing timestamps receive the SAME value, because one "
                + "move has two receiving fields")
        void bothTimestampsAreTheSameValue() {
            arrangePayableAccount("10.00", null);

            service.processBillPayment(submitted(ACCOUNT_ID, "Y"));
            final Transaction inserted = capturedInsert();

            assertThat(inserted.getTranOrigTs()).isEqualTo(inserted.getTranProcTs());
        }

        @Test
        @DisplayName("the projection restates the inserted record field for field")
        void projectionRestatesTheRecord() {
            arrangePayableAccount("10.00", null);

            final BillPaymentService.BillPaymentResult result =
                    service.processBillPayment(submitted(ACCOUNT_ID, "Y"));
            final Transaction inserted = capturedInsert();
            final BillPaymentService.TransactionProjection projection = result.transaction();

            assertThat(projection).isNotNull();
            assertThat(projection.tranId()).isEqualTo(inserted.getTranId());
            assertThat(projection.tranTypeCd()).isEqualTo(inserted.getTranTypeCd());
            assertThat(projection.tranCatCd()).isEqualTo(inserted.getTranCatCd());
            assertThat(projection.tranSource()).isEqualTo(inserted.getTranSource());
            assertThat(projection.tranDesc()).isEqualTo(inserted.getTranDesc());
            assertThat(projection.tranAmt()).isEqualByComparingTo(inserted.getTranAmt());
            assertThat(projection.merchantId()).isEqualTo(inserted.getMerchantId());
            assertThat(projection.merchantName()).isEqualTo(inserted.getMerchantName());
            assertThat(projection.merchantCity()).isEqualTo(inserted.getMerchantCity());
            assertThat(projection.merchantZip()).isEqualTo(inserted.getMerchantZip());
            assertThat(projection.tranCardNum()).isEqualTo(inserted.getTranCardNum());
            assertThat(projection.tranOrigTs()).isEqualTo(inserted.getTranOrigTs());
            assertThat(projection.tranProcTs()).isEqualTo(inserted.getTranProcTs());
        }
    }

    // ==============================================================================================
    // The online timestamp, lines 249 to 267
    // ==============================================================================================

    @Nested
    @DisplayName("The online timestamp, lines 249 to 267")
    class OnlineTimestamp {

        @Test
        @DisplayName("with a clock carrying a non-zero sub-second reading the fraction is STILL six "
                + "zeros, the value is 26 encoded bytes, and position 11 is a space")
        void fractionIsAlwaysZeroAndWidthIsExact() {
            arrangePayableAccount("10.00", null);

            service.processBillPayment(submitted(ACCOUNT_ID, "Y"));
            final String timestamp = capturedInsert().getTranOrigTs();

            assertThat(timestamp).isEqualTo("2024-03-07 14:25:36.000000");
            assertThat(timestamp).endsWith(".000000");
            assertThat(timestamp).doesNotContain("123456");
            assertThat(timestamp.getBytes(StandardCharsets.US_ASCII)).hasSize(26);
            assertThat(timestamp.charAt(10)).isEqualTo(' ');
            assertThat(timestamp.charAt(19)).isEqualTo('.');
            assertThat(timestamp.charAt(4)).isEqualTo('-');
            assertThat(timestamp.charAt(7)).isEqualTo('-');
            assertThat(timestamp.charAt(13)).isEqualTo(':');
            assertThat(timestamp.charAt(16)).isEqualTo(':');
        }

        @Test
        @DisplayName("the online form is NOT the batch form: no hyphen before the hour and no dots "
                + "between the time parts")
        void onlineFormIsNotTheBatchForm() {
            arrangePayableAccount("10.00", null);

            service.processBillPayment(submitted(ACCOUNT_ID, "Y"));
            final String timestamp = capturedInsert().getTranOrigTs();

            assertThat(timestamp.charAt(10)).isNotEqualTo('-');
            assertThat(timestamp.charAt(13)).isNotEqualTo('.');
            assertThat(timestamp.charAt(16)).isNotEqualTo('.');
        }

        @Test
        @DisplayName("single-digit date and time parts are zero filled to two characters")
        void partsAreZeroFilled() {
            final Clock earlyClock =
                    Clock.fixed(Instant.parse("2001-01-02T03:04:05.999999999Z"), ZoneOffset.UTC);
            final BillPaymentService earlyService = new BillPaymentService(transactionRepository,
                    accountRepository, crossReferenceRepository, messageCatalogService,
                    navigationService, earlyClock);
            arrangePayableAccount("10.00", null);

            earlyService.processBillPayment(submitted(ACCOUNT_ID, "Y"));

            assertThat(capturedInsert().getTranOrigTs()).isEqualTo("2001-01-02 03:04:05.000000");
        }
    }

    // ==============================================================================================
    // Persistence order and the balance computation, lines 233 to 235
    // ==============================================================================================

    @Nested
    @DisplayName("Persistence order and the balance computation, lines 233 to 235")
    class PersistenceOrder {

        @Test
        @DisplayName("the write order is transaction FIRST, then the account: the reverse of the "
                + "batch posting order, and deliberately not aligned with it")
        void transactionIsWrittenBeforeTheAccount() {
            arrangePayableAccount("77.77", null);

            service.processBillPayment(submitted(ACCOUNT_ID, "Y"));

            final InOrder inOrder = Mockito.inOrder(transactionRepository, accountRepository);
            inOrder.verify(transactionRepository).save(Mockito.any(Transaction.class));
            inOrder.verify(accountRepository).saveAndFlush(Mockito.any(Account.class));
            inOrder.verifyNoMoreInteractions();
        }

        @Test
        @DisplayName("the resulting balance is EXACTLY zero, because the amount paid is the whole "
                + "pre-payment balance")
        void resultingBalanceIsExactlyZero() {
            final Account account = arrangePayableAccount("1234.56", null);

            final BillPaymentService.BillPaymentResult result =
                    service.processBillPayment(submitted(ACCOUNT_ID, "Y"));

            assertThat(account.getAcctCurrBal()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(account.getAcctCurrBal().scale()).isEqualTo(2);
            assertThat(result.account()).isNotNull();
            assertThat(result.account().acctCurrBal()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(result.account().acctId()).isEqualTo(ACCOUNT_ID);
            // The screen still shows the PRE-payment balance.
            assertThat(result.screenBalance()).isEqualByComparingTo("1234.56");
        }

        @ParameterizedTest
        @ValueSource(strings = {"0.01", "9.99", "100.00", "999999999.99"})
        @DisplayName("the subtraction drives every payable balance to exactly zero, at scale two")
        void everyBalanceIsDrivenToZero(final String balance) {
            final Account account = arrangePayableAccount(balance, null);

            service.processBillPayment(submitted(ACCOUNT_ID, "Y"));

            assertThat(account.getAcctCurrBal()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(capturedInsert().getTranAmt()).isEqualByComparingTo(balance);
        }

        @Test
        @DisplayName("the account is still rewritten when the insert was refused, because the source "
                + "tests no flag between the two")
        void accountIsRewrittenEvenWhenTheInsertWasRefused() {
            final Account account = arrangePayableAccount("50.00", null);
            Mockito.when(crossReferenceRepository
                    .findFirstByXrefAcctIdOrderByXrefCardNumAsc(ACCOUNT_ID))
                    .thenReturn(Optional.empty());

            service.processBillPayment(submitted(ACCOUNT_ID, "Y"));

            Mockito.verify(accountRepository).saveAndFlush(account);
            assertThat(account.getAcctCurrBal()).isEqualByComparingTo(BigDecimal.ZERO);
        }
    }

    // ==============================================================================================
    // Optimistic locking, lines 377 to 403
    // ==============================================================================================

    @Nested
    @DisplayName("Optimistic locking, lines 377 to 403")
    class OptimisticLocking {

        @Test
        @DisplayName("a version conflict raised by the provider becomes a domain conflict carrying "
                + "the account key, and never an abend")
        void translatedProviderConflict() {
            arrangePayableAccount("10.00", null);
            Mockito.when(accountRepository.saveAndFlush(Mockito.any(Account.class)))
                    .thenThrow(new OptimisticLockingFailureException("row changed"));

            assertThatExceptionOfType(OptimisticLockConflictException.class)
                    .isThrownBy(() -> service.processBillPayment(submitted(ACCOUNT_ID, "Y")))
                    .satisfies(conflict -> {
                        assertThat(conflict.conflictKind()).isEqualTo(OptimisticLockConflictException
                                .ConflictKind.RECORD_CHANGED_BEFORE_UPDATE);
                        assertThat(conflict.entityName()).isEqualTo("Account");
                        assertThat(conflict.key()).isEqualTo(ACCOUNT_ID);
                        assertThat(conflict.getMessage())
                                .isEqualTo("Record changed by some one else. Please review");
                        assertThat(conflict.getCause())
                                .isInstanceOf(OptimisticLockingFailureException.class);
                    });
        }

        @Test
        @DisplayName("an untranslated persistence-level conflict is caught by the same arm")
        void translatedUntranslatedProviderConflict() {
            arrangePayableAccount("10.00", null);
            Mockito.when(accountRepository.saveAndFlush(Mockito.any(Account.class)))
                    .thenThrow(new OptimisticLockException("row changed"));

            assertThatExceptionOfType(OptimisticLockConflictException.class)
                    .isThrownBy(() -> service.processBillPayment(submitted(ACCOUNT_ID, "Y")))
                    .satisfies(conflict -> assertThat(conflict.getCause())
                            .isInstanceOf(OptimisticLockException.class));
        }

        @Test
        @DisplayName("the account projection reports the version the successful write left behind")
        void projectionCarriesTheVersion() {
            arrangePayableAccount("10.00", null);

            final BillPaymentService.BillPaymentResult result =
                    service.processBillPayment(submitted(ACCOUNT_ID, "Y"));

            assertThat(result.account()).isNotNull();
            assertThat(result.account().version()).isZero();
        }
    }

    // ==============================================================================================
    // The success and failure messages, lines 510 to 547
    // ==============================================================================================

    @Nested
    @DisplayName("The success and failure messages, lines 510 to 547")
    class Messages {

        @Test
        @DisplayName("the success message carries TWO consecutive spaces after the first full stop, "
                + "reproduced rather than corrected")
        void successMessageCarriesADoubleSpace() {
            arrangePayableAccount("10.00", null);

            final BillPaymentService.BillPaymentResult result =
                    service.processBillPayment(submitted(ACCOUNT_ID, "Y"));

            assertThat(result.message())
                    .isEqualTo("Payment successful.  Your Transaction ID is 0000000000000001.");
            assertThat(result.message()).contains("successful.  Your");
            assertThat(result.message()).doesNotContain("successful. Your");
            assertThat(result.message().indexOf("  ")).isEqualTo("Payment successful.".length());
        }

        @Test
        @DisplayName("the success path recolours the message field, clears the screen and reports the "
                + "payment as accepted")
        void successPathState() {
            arrangePayableAccount("10.00", null);

            final BillPaymentService.BillPaymentResult result =
                    service.processBillPayment(submitted(ACCOUNT_ID, "Y"));

            assertThat(result.messageHighlightedGreen()).isTrue();
            assertThat(result.errorFlag()).isFalse();
            assertThat(result.paymentAccepted()).isTrue();
            assertThat(result.screen().accountId()).isEqualTo(" ".repeat(11));
            assertThat(result.screen().confirm()).isEqualTo(" ");
            assertThat(result.focusField()).isEqualTo("ACTIDIN");
            assertThat(result.reArmedTransactionId()).isEqualTo("CB00");
            assertThat(result.route()).isEqualTo(NavigationService.Route.BILL_PAYMENT);
        }

        @Test
        @DisplayName("no other path recolours the message field")
        void onlyTheSuccessPathRecolours() {
            arrangePayableAccount("0.00", null);

            final BillPaymentService.BillPaymentResult result =
                    service.processBillPayment(submitted(ACCOUNT_ID, "Y"));

            assertThat(result.messageHighlightedGreen()).isFalse();
        }

        @Test
        @DisplayName("the outbound message field is the eighty-character work field truncated into "
                + "seventy-eight characters")
        void outboundMessageFieldWidth() {
            arrangePayableAccount("10.00", null);

            final BillPaymentService.BillPaymentResult result =
                    service.processBillPayment(submitted(ACCOUNT_ID, "Y"));

            assertThat(result.header().errorMessage()).hasSize(78);
            assertThat(result.header().errorMessage()).startsWith("Payment successful.  ");
        }
    }

    // ==============================================================================================
    // The edited balance field, line 56
    // ==============================================================================================

    @Nested
    @DisplayName("The edited balance field, line 56")
    class EditedBalance {

        @Test
        @DisplayName("a positive balance renders with a leading plus and ten zero-filled integer "
                + "digits across fourteen characters")
        void positiveBalance() {
            arrangePayableAccount("1234.56", null);

            final BillPaymentService.BillPaymentResult result =
                    service.processBillPayment(submitted(ACCOUNT_ID, " "));

            assertThat(result.screen().currentBalance()).isEqualTo("+0000001234.56");
            assertThat(result.screen().currentBalance()).hasSize(14);
        }

        @Test
        @DisplayName("a negative balance renders with a leading minus")
        void negativeBalance() {
            arrangePayableAccount("-9.05", null);

            final BillPaymentService.BillPaymentResult result =
                    service.processBillPayment(submitted(ACCOUNT_ID, " "));

            assertThat(result.screen().currentBalance()).isEqualTo("-0000000009.05");
        }

        @Test
        @DisplayName("a zero balance renders with a leading plus")
        void zeroBalance() {
            arrangePayableAccount("0.00", null);

            final BillPaymentService.BillPaymentResult result =
                    service.processBillPayment(submitted(ACCOUNT_ID, " "));

            assertThat(result.screen().currentBalance()).isEqualTo("+0000000000.00");
        }
    }

    // ==============================================================================================
    // The four condition-name groups, lines 43 to 53 and 68 to 70
    // ==============================================================================================

    @Nested
    @DisplayName("The four condition-name groups, lines 43 to 53 and 68 to 70")
    class ConditionNameGroups {

        @Test
        @DisplayName("the error flag's two condition names are mutually exclusive")
        void errorFlagPredicates() {
            assertThat(BillPaymentService.ErrorFlag.ON.isOn()).isTrue();
            assertThat(BillPaymentService.ErrorFlag.ON.isOff()).isFalse();
            assertThat(BillPaymentService.ErrorFlag.OFF.isOff()).isTrue();
            assertThat(BillPaymentService.ErrorFlag.OFF.isOn()).isFalse();
            assertThat(BillPaymentService.ErrorFlag.values()).hasSize(2);
        }

        @Test
        @DisplayName("the user-modified flag's two condition names are mutually exclusive")
        void userModifiedFlagPredicates() {
            assertThat(BillPaymentService.UserModifiedFlag.YES.isYes()).isTrue();
            assertThat(BillPaymentService.UserModifiedFlag.YES.isNo()).isFalse();
            assertThat(BillPaymentService.UserModifiedFlag.NO.isNo()).isTrue();
            assertThat(BillPaymentService.UserModifiedFlag.NO.isYes()).isFalse();
            assertThat(BillPaymentService.UserModifiedFlag.values()).hasSize(2);
        }

        @Test
        @DisplayName("the confirmation flag's two condition names are mutually exclusive")
        void confirmPaymentFlagPredicates() {
            assertThat(BillPaymentService.ConfirmPaymentFlag.YES.isYes()).isTrue();
            assertThat(BillPaymentService.ConfirmPaymentFlag.YES.isNo()).isFalse();
            assertThat(BillPaymentService.ConfirmPaymentFlag.NO.isNo()).isTrue();
            assertThat(BillPaymentService.ConfirmPaymentFlag.NO.isYes()).isFalse();
            assertThat(BillPaymentService.ConfirmPaymentFlag.values()).hasSize(2);
        }

        @Test
        @DisplayName("the next-page flag's two condition names are mutually exclusive")
        void nextPageFlagPredicates() {
            assertThat(BillPaymentService.NextPageFlag.YES.isYes()).isTrue();
            assertThat(BillPaymentService.NextPageFlag.YES.isNo()).isFalse();
            assertThat(BillPaymentService.NextPageFlag.NO.isNo()).isTrue();
            assertThat(BillPaymentService.NextPageFlag.NO.isYes()).isFalse();
            assertThat(BillPaymentService.NextPageFlag.values()).hasSize(2);
        }
    }

    // ==============================================================================================
    // Statelessness and the returned value
    // ==============================================================================================

    @Nested
    @DisplayName("Statelessness and the returned value")
    class Statelessness {

        @Test
        @DisplayName("two successive turns against the same empty table both mint the first "
                + "identifier, proving nothing is cached between calls")
        void nothingIsCachedBetweenTurns() {
            arrangePayableAccount("10.00", null);

            service.processBillPayment(submitted(ACCOUNT_ID, "Y"));
            final Account second = arrangePayableAccount("10.00", null);
            service.processBillPayment(submitted(ACCOUNT_ID, "Y"));

            final ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
            Mockito.verify(transactionRepository, Mockito.times(2)).save(captor.capture());
            assertThat(captor.getAllValues()).extracting(Transaction::getTranId)
                    .containsExactly("0000000000000001", "0000000000000001");
            assertThat(second.getAcctCurrBal()).isEqualByComparingTo(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("the field-error list the turn returns is unmodifiable")
        void fieldErrorsAreUnmodifiable() {
            final BillPaymentService.BillPaymentResult result =
                    service.processBillPayment(submitted("           ", "Y"));

            assertThat(result.fieldErrors()).isUnmodifiable();
        }

        @Test
        @DisplayName("a refused payment is never reported as accepted")
        void refusedPaymentIsNotAccepted() {
            arrangePayableAccount("10.00", "9");

            final BillPaymentService.BillPaymentResult result =
                    service.processBillPayment(submitted(ACCOUNT_ID, "Y"));

            assertThat(result.paymentAccepted()).isFalse();
        }

        @Test
        @DisplayName("an unconfirmed turn is not accepted either, even though it raised no error: "
                + "acceptance requires the confirmation as well as the absence of a fault")
        void unconfirmedTurnIsNotAccepted() {
            arrangePayableAccount("10.00", null);

            final BillPaymentService.BillPaymentResult result =
                    service.processBillPayment(submitted(ACCOUNT_ID, " "));

            assertThat(result.errorFlag()).isFalse();
            assertThat(result.confirmationState())
                    .isEqualTo(BillPaymentService.ConfirmPaymentFlag.NO);
            assertThat(result.paymentAccepted()).isFalse();
        }

        @Test
        @DisplayName("every collaborator is mandatory")
        void collaboratorsAreMandatory() {
            assertThatNullPointerException().isThrownBy(() -> new BillPaymentService(null,
                    accountRepository, crossReferenceRepository, messageCatalogService,
                    navigationService, FIXED_CLOCK));
            assertThatNullPointerException().isThrownBy(() -> new BillPaymentService(
                    transactionRepository, null, crossReferenceRepository, messageCatalogService,
                    navigationService, FIXED_CLOCK));
            assertThatNullPointerException().isThrownBy(() -> new BillPaymentService(
                    transactionRepository, accountRepository, null, messageCatalogService,
                    navigationService, FIXED_CLOCK));
            assertThatNullPointerException().isThrownBy(() -> new BillPaymentService(
                    transactionRepository, accountRepository, crossReferenceRepository, null,
                    navigationService, FIXED_CLOCK));
            assertThatNullPointerException().isThrownBy(() -> new BillPaymentService(
                    transactionRepository, accountRepository, crossReferenceRepository,
                    messageCatalogService, null, FIXED_CLOCK));
            assertThatNullPointerException().isThrownBy(() -> new BillPaymentService(
                    transactionRepository, accountRepository, crossReferenceRepository,
                    messageCatalogService, navigationService, null));
        }
    }
}
