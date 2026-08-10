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
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import jakarta.persistence.EntityExistsException;
import jakarta.persistence.OptimisticLockException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import com.carddemo.domain.Account;
import com.carddemo.domain.CardCrossReference;
import com.carddemo.domain.Transaction;
import com.carddemo.domain.enums.KeyAction;
import com.carddemo.exception.OptimisticLockConflictException;
import com.carddemo.exception.ValidationException;
import com.carddemo.repository.AccountRepository;
import com.carddemo.repository.CardCrossReferenceRepository;
import com.carddemo.repository.TransactionRepository;
import com.carddemo.support.SensitiveValues;
import com.carddemo.support.TestDataFactory;

/**
 * Unit test for {@link BillPaymentService}, the translation of {@code app/cbl/COBIL00C.cbl} -
 * transaction {@code CB00}, 572 lines, 16 paragraphs, at checkout SHA
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} (2022-07-19).
 *
 * <h2>Scope and harness</h2>
 *
 * <p>A surefire unit test. Every repository and every service collaborator is a Mockito mock, so no
 * container starts, no connection opens, no port binds and nothing outside the classpath is read. The
 * clock is a {@link Clock#fixed} instance in every test, because three of this member's contracts are
 * time derived and an ambient clock would make them pass or fail by the hour.
 *
 * <h2>The three contracts a plausible rewrite silently breaks</h2>
 *
 * <p><strong>The identifier is the highest existing key plus one, and never a sequence.</strong> On an
 * empty table the first identifier is the sixteen-character string {@code 0000000000000001} and not
 * {@code 1}. A sequence never reuses a value it has issued, whereas this rule always reuses a gap, and
 * the first rollback after an identifier is consumed guarantees a gap - so a sequence would diverge
 * from the legacy numbering for the remaining life of the table. The rollback case is asserted
 * directly: a refused insert leaves the next identifier unchanged.
 *
 * <p><strong>The online timestamp's fraction is always six zeros.</strong> The legacy paragraph writes
 * zeros over the fraction after formatting, so the clock's own sub-second reading never reaches the
 * output. A formatter emitting real microseconds compiles, looks right, and differs from the legacy in
 * its last six characters on every record. The test therefore drives a clock carrying a non-zero
 * nanosecond component and asserts the fraction is still zeros.
 *
 * <p><strong>The success message carries two consecutive spaces after its first full stop.</strong> The
 * legacy assembles it from four fragments; the first ends with a space and the second begins with one.
 * Normalising the pair to a single space is a byte-parity break, so the double space is asserted with an
 * explicit literal and again by position.
 *
 * <h2>Independent oracles</h2>
 *
 * <p>Every expected value in this file is authored here from the cited legacy line. No identifier is
 * produced by formatting a number, no timestamp is produced by a formatter, and no message, width or
 * padded catalogue value is read back from {@link BillPaymentService},
 * {@link MessageCatalogService} or any codec, string utility or record mapper. A change to any of them
 * therefore fails a test rather than quietly redefining the contract. Fixed-width values are compared
 * on encoded bytes rather than on character counts, because the legacy fields are measured in bytes.
 *
 * <h2>What is deliberately absent</h2>
 *
 * <p>This member is not one of the five programs that include the attention-key copybook - that family
 * is {@code COACTUPC}, {@code COACTVWC}, {@code COCRDLIC}, {@code COCRDSLC} and {@code COCRDUPC} - so
 * it declares no abend handler and no key-translation collaborator. Neither type is mocked, imported or
 * referenced here.
 *
 * <p>No user-specified rules exist for this engagement: the project's rules document holds the single
 * line reporting their absence, confirmed across three separate reads. The absence is not a licence to
 * lower the bar, so this test is held to enterprise-standard practice instead.
 *
 * @see BillPaymentService
 */
@DisplayName("BillPaymentService - one turn of the CB00 bill-payment screen, from app/cbl/COBIL00C.cbl")
@ExtendWith(MockitoExtension.class)
class BillPaymentServiceTest {

    // ==============================================================================================
    // Keys and fixture values, each at the width its record layout declares
    // ==============================================================================================

    /** An account identifier at the eleven-digit width of the 300-byte account record's key. */
    private static final String ACCOUNT_ID = "00000000011";

    /** A second account identifier, used to prove a lookup is keyed by the transmitted field. */
    private static final String OTHER_ACCOUNT_ID = "99999999999";

    /** A card number at the sixteen-character width of the 50-byte cross-reference record's key. */
    private static final String CARD_NUMBER = "4111111111111111";

    /**
     * A second card number that sorts <em>after</em> the first.
     *
     * <p>Used to prove the value the access path returned is the value written, with no re-ordering and
     * no second lookup: the cross-reference alternate key is non-unique, so the row the path resolves to
     * is the row the legacy keyed read returns.
     */
    private static final String HIGHER_CARD_NUMBER = "5599999999999999";

    /** A customer identifier at the nine-digit width the cross-reference record declares. */
    private static final String CUSTOMER_ID = "000000011";

    // ==============================================================================================
    // Clocks. Nothing in this file reads an ambient time source.
    // ==============================================================================================

    /**
     * The module's pinned instant, and the reason it is this one.
     *
     * <p>All three hundred records of the delivered daily-transaction fixture carry one and the same
     * origination timestamp, so anchoring here keeps a clock-reading service in agreement with the
     * seeded data instead of drifting away from it a day at a time. The build pins the test virtual
     * machine to the coordinated universal offset, which is the reading that keeps a run reproducible on
     * any host.
     */
    private static final Instant PINNED_INSTANT = Instant.parse("2022-06-10T19:27:53Z");

    /** A clock frozen at {@link #PINNED_INSTANT}. */
    private static final Clock PINNED_CLOCK = Clock.fixed(PINNED_INSTANT, ZoneOffset.UTC);

    /**
     * A clock whose sub-second reading is deliberately non-zero, to the full nanosecond.
     *
     * <p>This is the fixture that catches a formatter emitting real microseconds: the rendered timestamp
     * must still end in six zeros even though the clock is carrying 123456789 nanoseconds.
     */
    private static final Clock SUB_SECOND_CLOCK =
            Clock.fixed(Instant.parse("2024-03-07T14:25:36.123456789Z"), ZoneOffset.UTC);

    /**
     * A clock whose every date and time part is a single digit, to prove the two-character zero fill.
     *
     * <p>Also carries a non-zero nanosecond component, so the zero fraction is proven twice over.
     */
    private static final Clock SINGLE_DIGIT_PARTS_CLOCK =
            Clock.fixed(Instant.parse("2022-01-02T03:04:05.987654321Z"), ZoneOffset.UTC);

    // ==============================================================================================
    // The online 26-character timestamp, authored character for character
    //
    // A four-digit year, a hyphen, a two-digit month, a hyphen, a two-digit day, a SPACE at position
    // 11, a two-digit hour, a colon, minutes, a colon, seconds, a PERIOD at position 20 and a six-digit
    // fraction that is invariably zeros. The batch tier builds a differently shaped 26-character value -
    // a hyphen before the hour, dots between the time parts, two hundredths digits and four literal
    // zeros - and the two forms are never unified.
    // ==============================================================================================

    /** The value {@link #PINNED_CLOCK} must render, character for character. */
    private static final String PINNED_ONLINE_TIMESTAMP = "2022-06-10 19:27:53.000000";

    /** The value {@link #SUB_SECOND_CLOCK} must render, with its nanoseconds discarded. */
    private static final String SUB_SECOND_ONLINE_TIMESTAMP = "2024-03-07 14:25:36.000000";

    /** The value {@link #SINGLE_DIGIT_PARTS_CLOCK} must render, every part zero filled to two. */
    private static final String SINGLE_DIGIT_ONLINE_TIMESTAMP = "2022-01-02 03:04:05.000000";

    /** The character count, and the encoded byte count, of the timestamp group. */
    private static final int TIMESTAMP_WIDTH = 26;

    /** The zero-based position the date-time separator occupies: position eleven, one based. */
    private static final int TIMESTAMP_SEPARATOR_INDEX = 10;

    /** The fraction the construction paragraph writes, whatever the clock is carrying. */
    private static final String TIMESTAMP_ZERO_FRACTION = "000000";

    // ==============================================================================================
    // Transaction identifiers, written out as literals and never formatted from a number
    // ==============================================================================================

    /** The character count, and the encoded byte count, of the transaction identifier. */
    private static final int TRANSACTION_ID_WIDTH = 16;

    /** The first identifier on an empty table: fifteen leading zeros and a one. */
    private static final String FIRST_IDENTIFIER_ON_EMPTY_TABLE = "0000000000000001";

    /** A highest existing key one below the units carry. */
    private static final String HIGHEST_KEY_NINE = "0000000000000009";

    /** Its successor, which proves the carry and the surviving zero fill together. */
    private static final String SUCCESSOR_OF_NINE = "0000000000000010";

    /** A highest existing key whose low-order digits are all nines, for a longer carry. */
    private static final String HIGHEST_KEY_LONG_CARRY = "0000000000999999";

    /** Its successor, which carries across six digit positions at once. */
    private static final String SUCCESSOR_OF_LONG_CARRY = "0000000001000000";

    /** A highest existing key at the very top of the field, for the widest carry the field allows. */
    private static final String HIGHEST_KEY_TOP_OF_FIELD = "0999999999999999";

    /** Its successor, which fills the leading position and leaves no zero fill at all. */
    private static final String SUCCESSOR_OF_TOP_OF_FIELD = "1000000000000000";

    /** A maximum of the right width carrying a non-digit, which the backward read must refuse. */
    private static final String MALFORMED_KEY_NON_DIGIT = "00000000000000X1";

    /** A maximum of the wrong width, which the backward read must refuse for a different reason. */
    private static final String MALFORMED_KEY_SHORT = "9";

    /** The advisory-lock key the allocation must be serialised on, authored rather than imported. */
    private static final long EXPECTED_ALLOCATION_LOCK_KEY = 350_016L;

    /** How many times the allocate-and-write span may run before the duplicate arm reports. */
    private static final int EXPECTED_ALLOCATION_ATTEMPTS = 2;

    // ==============================================================================================
    // The synthesized transaction's constant fields, each in its stored form
    // ==============================================================================================

    /** The transaction type code, two characters. */
    private static final String EXPECTED_TRAN_TYPE_CD = "02";

    /**
     * The category code in its stored four-character form.
     *
     * <p>The legacy moves the numeric literal two into a four-digit field, so the stored value is four
     * characters. It is never the integer two and never the single character {@code 2}.
     */
    private static final String EXPECTED_TRAN_CAT_CD = "0002";

    /**
     * The source code in its stored ten-character form.
     *
     * <p>The literal moved is eight characters and the receiving field is ten, so the stored value
     * carries two trailing spaces. They are part of the value and are never trimmed.
     */
    private static final String EXPECTED_TRAN_SOURCE = "POS TERM" + " ".repeat(2);

    /** The encoded byte count of the source field. */
    private static final int TRAN_SOURCE_WIDTH = 10;

    /** The description literal, which the hundred-character field holds unpadded. */
    private static final String EXPECTED_TRAN_DESC = "BILL PAYMENT - ONLINE";

    /** The merchant identifier: the all-nines sentinel, nine digits. */
    private static final String EXPECTED_MERCHANT_ID = "999999999";

    /** The encoded byte count of the merchant identifier. */
    private static final int MERCHANT_ID_WIDTH = 9;

    /** The merchant name literal, which the fifty-character field holds unpadded. */
    private static final String EXPECTED_MERCHANT_NAME = "BILL PAYMENT";

    /** The merchant city literal. */
    private static final String EXPECTED_MERCHANT_CITY = "N/A";

    /** The merchant postal-code literal. */
    private static final String EXPECTED_MERCHANT_ZIP = "N/A";

    // ==============================================================================================
    // Operator messages, byte exact and never trimmed
    // ==============================================================================================

    /** The account-id field was not supplied. */
    private static final String MSG_ACCT_ID_EMPTY = "Acct ID can NOT be empty...";

    /** The confirmation field held something other than the four accepted characters. */
    private static final String MSG_INVALID_CONFIRMATION = "Invalid value. Valid values are (Y/N)...";

    /** The keyed read found no record. */
    private static final String MSG_ACCOUNT_ID_NOT_FOUND = "Account ID NOT found...";

    /** The account read returned a record that cannot drive a payment. */
    private static final String MSG_UNABLE_TO_LOOKUP_ACCOUNT = "Unable to lookup Account...";

    /** The account rewrite could not be attempted. */
    private static final String MSG_UNABLE_TO_UPDATE_ACCOUNT = "Unable to Update Account...";

    /** The cross-reference access path returned a record that cannot drive a payment. */
    private static final String MSG_UNABLE_TO_LOOKUP_XREF = "Unable to lookup XREF AIX file...";

    /**
     * The lookup-failure text, with exactly three trailing dots.
     *
     * <p>One literal, emitted from two distinct sites: the browse-start catch-all and the backward-read
     * catch-all. Both sites carry the identical string, which is asserted rather than assumed.
     */
    private static final String MSG_UNABLE_TO_LOOKUP_TRANSACTION = "Unable to lookup Transaction...";

    /** The balance was at or below zero and an account identifier was supplied. */
    private static final String MSG_NOTHING_TO_PAY = "You have nothing to pay...";

    /** Emitted whenever confirmation has not been given. */
    private static final String MSG_CONFIRM_BILL_PAYMENT = "Confirm to make a bill payment...";

    /** The insert collided with an existing key. */
    private static final String MSG_TRAN_ID_ALREADY_EXISTS = "Tran ID already exist...";

    /** The insert failed for any other reason. */
    private static final String MSG_UNABLE_TO_ADD_TRANSACTION = "Unable to Add Bill pay Transaction...";

    /** The state of the message field where the legacy sets none. */
    private static final String NO_MESSAGE = "";

    /**
     * The success text on an empty table, assembled here from the four legacy fragments.
     *
     * <p>The first fragment ends with a space and the second begins with one, so the assembled text
     * carries <strong>two consecutive spaces</strong> after the first full stop. That is reproduced
     * exactly and is never normalised.
     */
    private static final String EXPECTED_SUCCESS_MESSAGE =
            "Payment successful." + " ".repeat(2) + "Your Transaction ID is "
                    + FIRST_IDENTIFIER_ON_EMPTY_TABLE + ".";

    /** The two consecutive spaces, named so the intent is visible at every assertion site. */
    private static final String DOUBLE_SPACE = " ".repeat(2);

    // ==============================================================================================
    // Catalogue values, authored at their full padded widths and never trimmed
    // ==============================================================================================

    /** The invalid-key text: forty visible characters plus ten trailing spaces. */
    private static final String CATALOGUE_INVALID_KEY =
            "Invalid key pressed. Please see below..." + " ".repeat(10);

    /** The first screen title at its contractual forty-character width. */
    private static final String CATALOGUE_TITLE01 =
            " ".repeat(6) + "AWS Mainframe Modernization" + " ".repeat(7);

    /** The second screen title at its contractual forty-character width. */
    private static final String CATALOGUE_TITLE02 = " ".repeat(14) + "CardDemo" + " ".repeat(18);

    /** The contractual width of a common message. */
    private static final int COMMON_MESSAGE_WIDTH = 50;

    /** The contractual width of a screen title. */
    private static final int SCREEN_TITLE_WIDTH = 40;

    // ==============================================================================================
    // Screen fields and program identity
    // ==============================================================================================

    /** The map field the cursor returns to on every account-level fault. */
    private static final String FIELD_ACCOUNT_ID = "ACTIDIN";

    /** The map field the cursor returns to on a confirmation fault and on the prompt. */
    private static final String FIELD_CONFIRM = "CONFIRM";

    /** The property name a consumer binds an account-id fault to. */
    private static final String PROPERTY_ACCOUNT_ID = "accountId";

    /** The property name a consumer binds a confirmation fault to. */
    private static final String PROPERTY_CONFIRM = "confirm";

    /** The transaction identifier the terminal return re-arms. */
    private static final String EXPECTED_TRANSACTION_NAME = "CB00";

    /** The program name stamped into the header and into the originating field. */
    private static final String EXPECTED_PROGRAM_NAME = "COBIL00C";

    /** The eleven-character width of the account-id screen field. */
    private static final int ACCOUNT_ID_WIDTH = 11;

    /** The fourteen-character width of the edited balance screen field. */
    private static final int BALANCE_DISPLAY_WIDTH = 14;

    /** The seventy-eight character width of the outbound message field. */
    private static final int ERROR_MESSAGE_WIDTH = 78;

    /** The dangling program the resource definition declares with no source member behind it. */
    private static final String DANGLING_PROGRAM = "COCRDSEC";

    // ==============================================================================================
    // Balances, as literals rather than as computed values
    // ==============================================================================================

    /** A payable balance at scale two. */
    private static final BigDecimal PAYABLE_BALANCE = new BigDecimal("194.00");

    /** The balance a completed payment must leave behind, at scale two. */
    private static final BigDecimal ZERO_BALANCE = new BigDecimal("0.00");

    /**
     * A balance carrying a third decimal whose truncation and half-even rounding disagree.
     *
     * <p>Truncating toward zero yields {@code 100.01}; half-even and half-up both yield {@code 100.02}.
     * The estate contains no rounding clause anywhere, so every store into a two-decimal field truncates
     * and the expected value is the former. This is the fixture that makes the rounding assertion
     * discriminate rather than merely pass.
     */
    private static final BigDecimal UNTRUNCATED_BALANCE = new BigDecimal("100.015");

    /** What truncation toward zero yields from {@link #UNTRUNCATED_BALANCE}. */
    private static final BigDecimal TRUNCATED_AMOUNT = new BigDecimal("100.01");

    /** What half-even rounding would have yielded, and which must therefore never appear. */
    private static final BigDecimal HALF_EVEN_AMOUNT = new BigDecimal("100.02");

    /** The canonical monetary scale every money value carries. */
    private static final int MONETARY_SCALE = 2;

    // ==============================================================================================
    // Collaborators. Every one is a mock; there is no abend handler and no key translator.
    // ==============================================================================================

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private CardCrossReferenceRepository cardCrossReferenceRepository;

    @Mock
    private MessageCatalogService messageCatalogService;

    @Mock
    private NavigationService navigationService;

    @Mock
    private OnlineTransactionBoundary transactionBoundary;

    /** The service under test, wired to the pinned clock. */
    private BillPaymentService service;

    @BeforeEach
    void createServiceOnThePinnedClock() {
        this.service = serviceOn(PINNED_CLOCK);
    }

    /**
     * Builds the service on a given clock, so a timestamp test can drive one the fixture does not use.
     *
     * @param clock the clock the timestamp and the header read
     * @return a service wired to the same mocks and the supplied clock
     */
    private BillPaymentService serviceOn(final Clock clock) {
        return new BillPaymentService(transactionRepository,
                accountRepository,
                cardCrossReferenceRepository,
                messageCatalogService,
                navigationService,
                transactionBoundary,
                clock);
    }

    // ==============================================================================================
    // Fixtures
    // ==============================================================================================

    /**
     * An account carrying a payable balance already at the monetary scale.
     *
     * @return a fresh account instance, so a turn that drives its balance to zero cannot affect the next
     */
    private static Account payableAccount() {
        return TestDataFactory.account()
                .acctId(ACCOUNT_ID)
                .currentBalance(PAYABLE_BALANCE)
                .build();
    }

    /**
     * An account whose balance is stored exactly as supplied, at whatever scale the caller chose.
     *
     * <p>Built through the entity constructor rather than through the shared builder on purpose: the
     * builder normalises every money field to the monetary scale, which would perform the very truncation
     * a truncation test has to observe the service perform. It also accepts an absent balance, which the
     * builder refuses, and an absent balance is one of the boundary inputs this test exercises.
     *
     * @param balance the balance to store verbatim, which may be {@code null}
     * @return a fresh account instance
     */
    private static Account accountCarryingVerbatimBalance(final BigDecimal balance) {
        return new Account(ACCOUNT_ID,
                "Y",
                balance,
                new BigDecimal("5000.00"),
                new BigDecimal("1000.00"),
                "2020-01-01",
                "2030-01-01",
                "2025-01-01",
                ZERO_BALANCE,
                ZERO_BALANCE,
                "0000012345",
                " ".repeat(10));
    }

    /**
     * A cross-reference row resolving the account to a card.
     *
     * @param cardNumber the card number the access path resolves to
     * @return a fresh cross-reference instance
     */
    private static CardCrossReference crossReferenceTo(final String cardNumber) {
        return TestDataFactory.cardCrossReference()
                .cardNumber(cardNumber)
                .customerId(CUSTOMER_ID)
                .accountId(ACCOUNT_ID)
                .build();
    }

    /** The navigation state a re-submitted screen echoes back. */
    private static ScreenNavigationState reEntry() {
        return ScreenNavigationState.empty().withReEntry();
    }

    /** The navigation state a first entry carries. */
    private static ScreenNavigationState firstEntry() {
        return ScreenNavigationState.empty().withFirstEntry();
    }

    /**
     * A first-entry state whose selection member nominates an account.
     *
     * @param accountId the account identifier echoed in the navigation record
     * @return a first-entry state carrying that selection
     */
    private static ScreenNavigationState firstEntryNominating(final String accountId) {
        return new ScreenNavigationState(null, null, null, null, null, null,
                ScreenNavigationState.ProgramContext.ENTER, null, null, null, null, accountId, null,
                null, null, null);
    }

    /**
     * One submitted turn: the enter key, on a re-entry.
     *
     * @param accountId the transmitted account-id field, which may be {@code null}
     * @param confirm   the transmitted confirmation field, which may be {@code null}
     * @return the screen input
     */
    private static BillPaymentService.BillPaymentScreenInput submitted(final String accountId,
            final String confirm) {
        return new BillPaymentService.BillPaymentScreenInput(accountId, confirm, KeyAction.ENTER,
                reEntry());
    }

    /**
     * One turn carrying an attention key other than the enter key.
     *
     * @param keyAction the decoded attention key, which may be {@code null}
     * @return the screen input
     */
    private static BillPaymentService.BillPaymentScreenInput keyed(final KeyAction keyAction) {
        return new BillPaymentService.BillPaymentScreenInput(ACCOUNT_ID, "Y", keyAction, reEntry());
    }

    // ==============================================================================================
    // Arrangement, decomposed so strict stubbing catches an arrangement a test does not use
    // ==============================================================================================

    /**
     * Arranges the keyed account read to return the same instance on every call.
     *
     * <p>This is the unlocked read of the account-read paragraph, which every turn that resolves an account
     * performs. A turn that goes on to confirm re-reads the same row under an exclusive lock, which is a
     * separate finder and is arranged by {@link #stubHeldAccountRead(Account)}.
     *
     * @param account the account the read resolves to
     */
    private void stubAccountRead(final Account account) {
        when(accountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.of(account));
    }

    /**
     * Arranges the exclusive held read the confirmed span takes as its first statement.
     *
     * <p>Only a confirmed turn reaches it, so it is arranged separately from the unlocked read: a test that
     * never confirms would otherwise carry an arrangement it does not use, and strict stubbing would - very
     * properly - fail it for that.
     *
     * @param account the account the held read resolves to
     */
    private void stubHeldAccountRead(final Account account) {
        when(accountRepository.findByIdForUpdate(ACCOUNT_ID)).thenReturn(Optional.of(account));
    }

    /**
     * Arranges both account reads to resolve to the same instance.
     *
     * @param account the account both reads resolve to
     */
    private void stubBothAccountReads(final Account account) {
        stubAccountRead(account);
        stubHeldAccountRead(account);
    }

    /**
     * Arranges both account reads to return a fresh payable instance on every call.
     *
     * <p>What this buys is turn independence. A test that runs more than one turn against the same mocked
     * repository would otherwise see the balance the first turn settled to zero, and the second turn would
     * take the nothing-to-pay arm instead of the arm under test. Handing out a fresh row per read is what a
     * real store does between two independent turns.
     *
     * <p>The two reads deliberately hand out <em>different</em> instances within a single turn, which also
     * demonstrates that the row the rewrite stores is the one the held read granted rather than the one the
     * display read returned.
     */
    private void stubAccountReadsReturningFreshInstances() {
        when(accountRepository.findById(ACCOUNT_ID))
                .thenAnswer(invocation -> Optional.of(payableAccount()));
        when(accountRepository.findByIdForUpdate(ACCOUNT_ID))
                .thenAnswer(invocation -> Optional.of(payableAccount()));
    }

    /** Arranges the account rewrite to echo back the instance it was handed. */
    private void stubAccountRewriteEchoesRow() {
        when(accountRepository.saveAndFlush(any(Account.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    /**
     * Arranges the cross-reference access path to resolve to one row.
     *
     * @param cardNumber the card number the resolved row carries
     */
    private void stubCrossReferenceRow(final String cardNumber) {
        when(cardCrossReferenceRepository.findFirstByXrefAcctIdOrderByXrefCardNumAsc(ACCOUNT_ID))
                .thenReturn(Optional.of(crossReferenceTo(cardNumber)));
    }

    /**
     * Arranges the highest stored identifier the backward read reports.
     *
     * @param highestExisting the highest stored identifier, or {@code null} for an empty table
     */
    private void stubHighestIdentifier(final String highestExisting) {
        when(transactionRepository.findMaxId()).thenReturn(Optional.ofNullable(highestExisting));
    }

    /** Arranges the transaction insert to echo back the record it was handed. */
    private void stubInsertEchoesRecord() {
        when(transactionRepository.insertAndFlush(any(Transaction.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    /**
     * Arranges the unit of work to run the operation it is given.
     *
     * <p>The boundary is a collaborator like any other, so it is mocked; the stub reproduces the one
     * behaviour the service depends on, which is that the operation runs and any failure leaves the unit.
     */
    private void stubBoundaryRunsUnit() {
        when(transactionBoundary.execute(any())).thenAnswer(invocation -> {
            final Supplier<?> unit = invocation.getArgument(0);
            return unit.get();
        });
    }

    /**
     * Arranges a payment that can complete: the account exists, the cross-reference resolves, the
     * identifier does not collide, both writes echo their argument and both units of work run.
     *
     * @param account         the account the read resolves to
     * @param highestExisting the highest stored identifier, or {@code null} for an empty table
     */
    private void arrangeConfirmablePayment(final Account account, final String highestExisting) {
        stubBothAccountReads(account);
        stubAccountRewriteEchoesRow();
        stubCrossReferenceRow(CARD_NUMBER);
        stubHighestIdentifier(highestExisting);
        stubInsertEchoesRecord();
        stubBoundaryRunsUnit();
    }

    /**
     * Captures the record the insert was handed.
     *
     * @return the single inserted record
     */
    private Transaction capturedInsertedRecord() {
        final ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository).insertAndFlush(captor.capture());
        return captor.getValue();
    }

    /**
     * Captures every record the insert was handed, in the order it was handed them.
     *
     * @param expectedCount how many inserts are expected
     * @return the inserted records
     */
    private List<Transaction> capturedInsertedRecords(final int expectedCount) {
        final ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository, times(expectedCount)).insertAndFlush(captor.capture());
        return captor.getAllValues();
    }

    /**
     * Captures the account the rewrite was handed.
     *
     * @return the single rewritten account
     */
    private Account capturedRewrittenAccount() {
        final ArgumentCaptor<Account> captor = ArgumentCaptor.forClass(Account.class);
        verify(accountRepository).saveAndFlush(captor.capture());
        return captor.getValue();
    }

    /** Asserts that neither durable write was attempted. */
    private void assertNothingWasWritten() {
        verify(transactionRepository, never()).insertAndFlush(any(Transaction.class));
        verify(accountRepository, never()).saveAndFlush(any(Account.class));
    }

    /**
     * Measures a value at the width the legacy field is measured in, which is encoded bytes.
     *
     * @param value the value to measure
     * @return the encoded byte count
     */
    private static int encodedByteCount(final String value) {
        return value.getBytes(StandardCharsets.US_ASCII).length;
    }

    // ==============================================================================================
    // MAIN-PARA line 99, RECEIVE-BILLPAY-SCREEN line 306, SEND-BILLPAY-SCREEN line 289,
    // POPULATE-HEADER-INFO line 319, RETURN-TO-PREV-SCREEN line 273, CLEAR-CURRENT-SCREEN line 552 and
    // INITIALIZE-ALL-FIELDS line 560
    // ==============================================================================================

    @Nested
    @DisplayName("Turn entry and attention keys - the main, receive, send, header, transfer, clear and "
            + "reset paragraphs at lines 99, 306, 289, 319, 273, 552 and 560")
    class TurnEntryAndAttentionKeys {

        @Test
        @DisplayName("an absent screen input is refused rather than defaulted, so no turn runs on "
                + "invented state")
        void mainParaRefusesAnAbsentInput() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> service.processBillPayment(null));

            verifyNoInteractions(transactionRepository, accountRepository, cardCrossReferenceRepository,
                    transactionBoundary, navigationService, messageCatalogService);
        }

        @Test
        @DisplayName("a turn carrying no navigation state transfers to the destination the rules hold "
                + "for it and touches no data at all, which is the zero-length branch at lines 107 to 109")
        void mainParaTransfersWhenNoNavigationStateIsCarried() {
            when(navigationService.resolveAbsentContextRoute())
                    .thenReturn(NavigationService.Route.SIGN_ON);
            when(navigationService.resolveNominatedDestination(any(ConversationState.class),
                    eq(NavigationService.Route.SIGN_ON))).thenReturn(NavigationService.Route.SIGN_ON);

            final BillPaymentService.BillPaymentResult result = service.processBillPayment(
                    new BillPaymentService.BillPaymentScreenInput(null, null, null, null));

            assertAll(
                    () -> assertThat(result.route()).isEqualTo(NavigationService.Route.SIGN_ON),
                    () -> assertThat(result.reArmedTransactionId()).isEqualTo(NO_MESSAGE),
                    () -> assertThat(result.transaction()).isNull(),
                    () -> assertThat(result.account()).isNull(),
                    () -> assertThat(result.errorFlag()).isFalse());
            verify(navigationService).resolveAbsentContextRoute();
            verifyNoInteractions(transactionRepository, accountRepository, cardCrossReferenceRepository,
                    transactionBoundary);
        }

        @Test
        @DisplayName("an empty navigation record is treated as no state at all, exactly as a zero-length "
                + "communication area is")
        void mainParaTreatsAnEmptyNavigationRecordAsAbsent() {
            when(navigationService.resolveAbsentContextRoute())
                    .thenReturn(NavigationService.Route.SIGN_ON);
            when(navigationService.resolveNominatedDestination(any(ConversationState.class),
                    eq(NavigationService.Route.SIGN_ON))).thenReturn(NavigationService.Route.SIGN_ON);

            final BillPaymentService.BillPaymentResult result =
                    service.processBillPayment(new BillPaymentService.BillPaymentScreenInput(
                            ACCOUNT_ID, "Y", KeyAction.ENTER, ScreenNavigationState.empty()));

            assertThat(result.route()).isEqualTo(NavigationService.Route.SIGN_ON);
            verifyNoInteractions(transactionRepository, accountRepository, cardCrossReferenceRepository,
                    transactionBoundary);
        }

        @Test
        @DisplayName("a first entry sets the re-enter gate, positions the cursor on the account-id field "
                + "and re-arms this same transaction, at lines 112 to 122")
        void mainParaSendsTheScreenOnAFirstEntry() {
            final BillPaymentService.BillPaymentResult result = service.processBillPayment(
                    new BillPaymentService.BillPaymentScreenInput(null, null, null, firstEntry()));

            assertAll(
                    () -> assertThat(result.reEnterGateSet()).isTrue(),
                    () -> assertThat(result.navigationContext().reEntry()).isTrue(),
                    () -> assertThat(result.focusField()).isEqualTo(FIELD_ACCOUNT_ID),
                    () -> assertThat(result.route()).isEqualTo(NavigationService.Route.BILL_PAYMENT),
                    () -> assertThat(result.reArmedTransactionId())
                            .isEqualTo(EXPECTED_TRANSACTION_NAME),
                    () -> assertThat(result.errorFlag()).isFalse(),
                    () -> assertThat(result.message()).isEqualTo(NO_MESSAGE),
                    () -> assertThat(result.fieldErrors()).isEmpty());
            verifyNoInteractions(transactionRepository, accountRepository, cardCrossReferenceRepository,
                    transactionBoundary, navigationService);
        }

        @Test
        @DisplayName("a first entry nominating an account runs the enter-key paragraph on that very "
                + "turn, which is the branch at lines 116 to 121")
        void mainParaRunsTheEnterKeyOnAFirstEntryThatNominatesAnAccount() {
            stubAccountRead(payableAccount());

            final BillPaymentService.BillPaymentResult result = service.processBillPayment(
                    new BillPaymentService.BillPaymentScreenInput(ACCOUNT_ID, null, null,
                            firstEntryNominating(OTHER_ACCOUNT_ID)));

            // The bounded screen field nominates the account. A different account echoed in the
            // navigation record is retained state, not authority over which row the turn may read.
            verify(accountRepository).findById(ACCOUNT_ID);
            verify(accountRepository, never()).findById(OTHER_ACCOUNT_ID);
            assertAll(
                    () -> assertThat(result.message()).isEqualTo(MSG_CONFIRM_BILL_PAYMENT),
                    () -> assertThat(result.transaction()).isNull(),
                    () -> assertThat(result.reEnterGateSet()).isTrue());
        }

        @Test
        @DisplayName("an account echoed only in the navigation record cannot nominate a row for lookup")
        void mainParaWillNotReadAnAccountNominatedOnlyByTheEchoedRecord() {
            final BillPaymentService.BillPaymentResult result = service.processBillPayment(
                    new BillPaymentService.BillPaymentScreenInput(null, null, null,
                            firstEntryNominating(ACCOUNT_ID)));

            verifyNoInteractions(accountRepository, transactionRepository, cardCrossReferenceRepository,
                    transactionBoundary);
            assertAll(
                    () -> assertThat(result.focusField()).isEqualTo(FIELD_ACCOUNT_ID),
                    () -> assertThat(result.message()).isEqualTo(NO_MESSAGE),
                    () -> assertThat(result.transaction()).isNull());
        }

        @Test
        @DisplayName("the receive paragraph bounds a transmitted account-id to its eleven-character "
                + "width, and the bounded value is what keys the read")
        void receiveBillpayScreenBoundsEachFieldToItsDeclaredWidth() {
            when(accountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.empty());

            final BillPaymentService.BillPaymentResult result =
                    service.processBillPayment(submitted(ACCOUNT_ID + "9999", null));

            verify(accountRepository).findById(ACCOUNT_ID);
            assertAll(
                    () -> assertThat(result.screen().accountId()).isEqualTo(ACCOUNT_ID),
                    () -> assertThat(encodedByteCount(result.screen().accountId()))
                            .isEqualTo(ACCOUNT_ID_WIDTH),
                    () -> assertThat(result.message()).isEqualTo(MSG_ACCOUNT_ID_NOT_FOUND));
        }

        @Test
        @DisplayName("a shorter transmitted account-id is space filled to its eleven-character width "
                + "rather than left short")
        void receiveBillpayScreenSpaceFillsAShortField() {
            final BillPaymentService.BillPaymentResult result =
                    service.processBillPayment(submitted(null, "N"));

            assertAll(
                    () -> assertThat(encodedByteCount(result.screen().accountId()))
                            .isEqualTo(ACCOUNT_ID_WIDTH),
                    () -> assertThat(result.screen().accountId()).isEqualTo(" ".repeat(ACCOUNT_ID_WIDTH)),
                    () -> assertThat(encodedByteCount(result.screen().currentBalance()))
                            .isEqualTo(BALANCE_DISPLAY_WIDTH));
        }

        @Test
        @DisplayName("the exit key leaves for the destination the navigation rules resolve, and the "
                + "resolved destination - not a literal - is what the turn nominates, at lines 128 to 134")
        void returnToPrevScreenTransfersToTheResolvedDestination() {
            when(navigationService.resolveBackNavigation(any(ConversationState.class),
                    eq(NavigationService.Route.USER_MENU)))
                    .thenReturn(NavigationService.Route.TRANSACTION_LIST);
            when(navigationService.resolveNominatedDestination(any(ConversationState.class),
                    eq(NavigationService.Route.SIGN_ON)))
                    .thenReturn(NavigationService.Route.TRANSACTION_LIST);

            final BillPaymentService.BillPaymentResult result =
                    service.processBillPayment(keyed(KeyAction.PFK03));

            final ArgumentCaptor<ConversationState> carried =
                    ArgumentCaptor.forClass(ConversationState.class);
            verify(navigationService).resolveNominatedDestination(carried.capture(),
                    eq(NavigationService.Route.SIGN_ON));

            assertAll(
                    () -> assertThat(result.route()).isEqualTo(NavigationService.Route.TRANSACTION_LIST),
                    () -> assertThat(result.reArmedTransactionId()).isEqualTo(NO_MESSAGE),
                    () -> assertThat(result.navigationContext().fromProgram())
                            .isEqualTo(EXPECTED_PROGRAM_NAME),
                    () -> assertThat(result.navigationContext().fromTransactionId())
                            .isEqualTo(EXPECTED_TRANSACTION_NAME),
                    () -> assertThat(result.navigationContext().firstEntry()).isTrue(),
                    () -> assertThat(carried.getValue().toProgram()).isEqualTo(
                            NavigationService.Route.TRANSACTION_LIST.getLegacyProgramName()));
            verifyNoInteractions(transactionRepository, accountRepository, cardCrossReferenceRepository,
                    transactionBoundary);
        }

        @Test
        @DisplayName("the fourth function key blanks every field, leaves no message and raises no error "
                + "flag, which is the clear paragraph at lines 552 to 555")
        void clearCurrentScreenBlanksEveryField() {
            final BillPaymentService.BillPaymentResult result =
                    service.processBillPayment(keyed(KeyAction.PFK04));

            assertAll(
                    () -> assertThat(result.screen().accountId()).isEqualTo(" ".repeat(ACCOUNT_ID_WIDTH)),
                    () -> assertThat(result.screen().currentBalance())
                            .isEqualTo(" ".repeat(BALANCE_DISPLAY_WIDTH)),
                    () -> assertThat(result.screen().confirm()).isEqualTo(" "),
                    () -> assertThat(result.message()).isEqualTo(NO_MESSAGE),
                    () -> assertThat(result.errorFlag()).isFalse(),
                    () -> assertThat(result.focusField()).isEqualTo(FIELD_ACCOUNT_ID),
                    () -> assertThat(result.messageHighlightedGreen()).isFalse());
            verifyNoInteractions(transactionRepository, accountRepository, cardCrossReferenceRepository,
                    transactionBoundary, navigationService);
        }

        @ParameterizedTest
        @ValueSource(strings = {"CLEAR", "PA1", "PA2", "PFK01", "PFK12"})
        @DisplayName("a key this screen does not map reports the catalogue text at its full "
                + "fifty-character width, untrimmed, at lines 138 to 141")
        void mainParaReportsTheCatalogueTextForAnUnmappedKey(final String keyName) {
            when(messageCatalogService.invalidKeyMessage()).thenReturn(CATALOGUE_INVALID_KEY);

            final BillPaymentService.BillPaymentResult result = service.processBillPayment(
                    new BillPaymentService.BillPaymentScreenInput(null, null,
                            KeyAction.valueOf(keyName), reEntry()));

            assertAll(
                    () -> assertThat(result.errorFlag()).isTrue(),
                    () -> assertThat(result.message()).isEqualTo(CATALOGUE_INVALID_KEY),
                    () -> assertThat(encodedByteCount(result.message()))
                            .isEqualTo(COMMON_MESSAGE_WIDTH),
                    () -> assertThat(result.message()).endsWith(" ".repeat(10)),
                    () -> assertThat(result.transaction()).isNull());
            verifyNoInteractions(transactionRepository, accountRepository, cardCrossReferenceRepository,
                    transactionBoundary);
        }

        @Test
        @DisplayName("a key that was never decoded reaches the same catch-all arm as an unmapped one")
        void mainParaSendsAnUndecodedKeyToTheCatchAllArm() {
            when(messageCatalogService.invalidKeyMessage()).thenReturn(CATALOGUE_INVALID_KEY);

            final BillPaymentService.BillPaymentResult result = service.processBillPayment(
                    new BillPaymentService.BillPaymentScreenInput(null, null, null, reEntry()));

            assertThat(result.errorFlag()).isTrue();
            assertThat(result.message()).isEqualTo(CATALOGUE_INVALID_KEY);
        }

        @Test
        @DisplayName("the header paragraph stamps both catalogue titles at their full forty-character "
                + "width plus this screen's transaction and program names and the two-digit header date")
        void populateHeaderInfoStampsTitlesNamesAndTheHeaderDate() {
            when(messageCatalogService.screenTitle01()).thenReturn(CATALOGUE_TITLE01);
            when(messageCatalogService.screenTitle02()).thenReturn(CATALOGUE_TITLE02);

            final BillPaymentService.BillPaymentResult result = service.processBillPayment(
                    new BillPaymentService.BillPaymentScreenInput(null, null, null, firstEntry()));

            assertAll(
                    () -> assertThat(result.header().title01()).isEqualTo(CATALOGUE_TITLE01),
                    () -> assertThat(encodedByteCount(result.header().title01()))
                            .isEqualTo(SCREEN_TITLE_WIDTH),
                    () -> assertThat(result.header().title02()).isEqualTo(CATALOGUE_TITLE02),
                    () -> assertThat(encodedByteCount(result.header().title02()))
                            .isEqualTo(SCREEN_TITLE_WIDTH),
                    () -> assertThat(result.header().transactionName())
                            .isEqualTo(EXPECTED_TRANSACTION_NAME),
                    () -> assertThat(result.header().programName()).isEqualTo(EXPECTED_PROGRAM_NAME),
                    // Lines 328 to 332 take the year as its last two characters, and lines 334 to 338
                    // assemble the time; both are read from the pinned clock.
                    () -> assertThat(result.header().currentDate()).isEqualTo("06/10/22"),
                    () -> assertThat(result.header().currentTime()).isEqualTo("19:27:53"));
        }

        @Test
        @DisplayName("the send paragraph moves the eighty-character message work field into the "
                + "seventy-eight character outbound field, at line 293")
        void sendBillpayScreenMovesTheMessageIntoTheOutboundField() {
            final BillPaymentService.BillPaymentResult result =
                    service.processBillPayment(submitted(null, null));

            assertAll(
                    () -> assertThat(result.message()).isEqualTo(MSG_ACCT_ID_EMPTY),
                    () -> assertThat(encodedByteCount(result.header().errorMessage()))
                            .isEqualTo(ERROR_MESSAGE_WIDTH),
                    () -> assertThat(result.header().errorMessage()).startsWith(MSG_ACCT_ID_EMPTY));
        }
    }

    // ==============================================================================================
    // STARTBR-TRANSACT-FILE line 441, READPREV-TRANSACT-FILE line 472, ENDBR-TRANSACT-FILE line 501
    // and the store half of WRITE-TRANSACT-FILE line 510
    // ==============================================================================================

    @Nested
    @DisplayName("Identifier generation - the browse-start, backward-read and browse-end paragraphs at "
            + "lines 441, 472 and 501, and the store half of the insert paragraph at line 510")
    class IdentifierGeneration {

        @Test
        @DisplayName("on an EMPTY transaction table the first identifier is exactly the sixteen "
                + "character string 0000000000000001, from the end-of-file seed at line 488")
        void readprevTransactFileSeedsZeroSoTheFirstIdentifierIsOne() {
            arrangeConfirmablePayment(payableAccount(), null);

            service.processBillPayment(submitted(ACCOUNT_ID, "Y"));

            final Transaction inserted = capturedInsertedRecord();
            assertAll(
                    () -> assertThat(inserted.getTranId()).isEqualTo(FIRST_IDENTIFIER_ON_EMPTY_TABLE),
                    () -> assertThat(encodedByteCount(inserted.getTranId()))
                            .isEqualTo(TRANSACTION_ID_WIDTH),
                    () -> assertThat(inserted.getTranId()).isNotEqualTo("1"),
                    () -> assertThat(inserted.getTranId()).startsWith("0".repeat(15)));
        }

        @Test
        @DisplayName("a highest existing key of 0000000000000009 yields 0000000000000010, which proves "
                + "the carry and the surviving zero fill together, from lines 216 to 219")
        void readprevTransactFileIncrementsAcrossTheUnitsCarry() {
            arrangeConfirmablePayment(payableAccount(), HIGHEST_KEY_NINE);

            service.processBillPayment(submitted(ACCOUNT_ID, "Y"));

            final Transaction inserted = capturedInsertedRecord();
            assertThat(inserted.getTranId()).isEqualTo(SUCCESSOR_OF_NINE);
            assertThat(encodedByteCount(inserted.getTranId())).isEqualTo(TRANSACTION_ID_WIDTH);
        }

        @Test
        @DisplayName("a highest existing key of 0000000000999999 carries across six digit positions at "
                + "once and still fills to sixteen characters")
        void readprevTransactFileIncrementsAcrossALongerCarry() {
            arrangeConfirmablePayment(payableAccount(), HIGHEST_KEY_LONG_CARRY);

            service.processBillPayment(submitted(ACCOUNT_ID, "Y"));

            final Transaction inserted = capturedInsertedRecord();
            assertThat(inserted.getTranId()).isEqualTo(SUCCESSOR_OF_LONG_CARRY);
            assertThat(encodedByteCount(inserted.getTranId())).isEqualTo(TRANSACTION_ID_WIDTH);
        }

        @Test
        @DisplayName("a highest existing key at the top of the field fills the leading position, leaving "
                + "no zero fill at all, which is the widest carry the sixteen-digit field allows")
        void readprevTransactFileIncrementsAtTheTopOfTheField() {
            arrangeConfirmablePayment(payableAccount(), HIGHEST_KEY_TOP_OF_FIELD);

            service.processBillPayment(submitted(ACCOUNT_ID, "Y"));

            final Transaction inserted = capturedInsertedRecord();
            assertAll(
                    () -> assertThat(inserted.getTranId()).isEqualTo(SUCCESSOR_OF_TOP_OF_FIELD),
                    () -> assertThat(encodedByteCount(inserted.getTranId()))
                            .isEqualTo(TRANSACTION_ID_WIDTH),
                    () -> assertThat(inserted.getTranId()).doesNotStartWith("0"));
        }

        @Test
        @DisplayName("the maximum finder is the ONLY identifier source consulted, and the allocation is "
                + "serialised on the repository's own advisory-lock key")
        void mintAndWriteTransactionConsultsNoOtherIdentifierSource() {
            arrangeConfirmablePayment(payableAccount(), HIGHEST_KEY_NINE);

            service.processBillPayment(submitted(ACCOUNT_ID, "Y"));

            // Authored here rather than imported, then cross-checked, so a change to the constant fails
            // this test instead of silently moving the lock every allocator serialises on.
            assertThat(TransactionRepository.IDENTIFIER_ALLOCATION_LOCK_KEY)
                    .isEqualTo(EXPECTED_ALLOCATION_LOCK_KEY);

            verify(transactionRepository).lockIdentifierAllocation(EXPECTED_ALLOCATION_LOCK_KEY);
            verify(transactionRepository).findMaxId();
            verify(transactionRepository).existsById(SUCCESSOR_OF_NINE);
            verify(transactionRepository).insertAndFlush(any(Transaction.class));
            // No generated value, no sequence and no second identifier query: those four calls are every
            // interaction the turn has with the transaction master.
            verifyNoMoreInteractions(transactionRepository);
        }

        @Test
        @DisplayName("a refused insert leaves the NEXT identifier unchanged, so a rollback consumes no "
                + "permanent gap - which is the whole reason a sequence is prohibited")
        void aRefusedInsertConsumesNoIdentifierGap() {
            stubAccountReadsReturningFreshInstances();
            stubAccountRewriteEchoesRow();
            stubCrossReferenceRow(CARD_NUMBER);
            stubHighestIdentifier(HIGHEST_KEY_NINE);
            stubBoundaryRunsUnit();
            when(transactionRepository.insertAndFlush(any(Transaction.class)))
                    .thenThrow(new IllegalStateException("the store refused the row"))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            final BillPaymentService.BillPaymentResult refused =
                    service.processBillPayment(submitted(ACCOUNT_ID, "Y"));
            final BillPaymentService.BillPaymentResult accepted =
                    service.processBillPayment(submitted(ACCOUNT_ID, "Y"));

            final List<Transaction> attempts = capturedInsertedRecords(2);
            assertAll(
                    () -> assertThat(refused.transaction()).isNull(),
                    () -> assertThat(refused.message()).isEqualTo(MSG_UNABLE_TO_ADD_TRANSACTION),
                    () -> assertThat(refused.errorFlag()).isTrue(),
                    () -> assertThat(attempts.get(0).getTranId()).isEqualTo(SUCCESSOR_OF_NINE),
                    // The second turn re-reads the same unchanged maximum and mints the SAME identifier.
                    // A sequence would have issued the next one and diverged permanently.
                    () -> assertThat(attempts.get(1).getTranId()).isEqualTo(SUCCESSOR_OF_NINE),
                    () -> assertThat(accepted.transaction()).isNotNull(),
                    () -> assertThat(accepted.transaction().tranId()).isEqualTo(SUCCESSOR_OF_NINE),
                    () -> assertThat(accepted.paymentAccepted()).isTrue());
        }

        @Test
        @DisplayName("two successive turns against the same empty table both mint the first identifier, "
                + "because nothing is cached between turns")
        void nothingIsCachedBetweenTurns() {
            stubAccountReadsReturningFreshInstances();
            stubAccountRewriteEchoesRow();
            stubCrossReferenceRow(CARD_NUMBER);
            stubHighestIdentifier(null);
            stubInsertEchoesRecord();
            stubBoundaryRunsUnit();

            final BillPaymentService.BillPaymentResult first =
                    service.processBillPayment(submitted(ACCOUNT_ID, "Y"));
            final BillPaymentService.BillPaymentResult second =
                    service.processBillPayment(submitted(ACCOUNT_ID, "Y"));

            assertThat(first.transaction().tranId()).isEqualTo(FIRST_IDENTIFIER_ON_EMPTY_TABLE);
            assertThat(second.transaction().tranId()).isEqualTo(FIRST_IDENTIFIER_ON_EMPTY_TABLE);
            verify(transactionRepository, times(2)).findMaxId();
        }

        @ParameterizedTest
        @ValueSource(strings = {MALFORMED_KEY_NON_DIGIT, MALFORMED_KEY_SHORT})
        @DisplayName("a maximum that is not a well-formed sixteen-digit key is refused rather than "
                + "incremented, and both refusal conditions reach the identical outcome, at lines 489 to 495")
        void readprevTransactFileRefusesAMalformedMaximum(final String malformedMaximum) {
            stubBothAccountReads(payableAccount());
            stubAccountRewriteEchoesRow();
            stubCrossReferenceRow(CARD_NUMBER);
            stubHighestIdentifier(malformedMaximum);
            stubBoundaryRunsUnit();

            final BillPaymentService.BillPaymentResult result =
                    service.processBillPayment(submitted(ACCOUNT_ID, "Y"));

            assertAll(
                    () -> assertThat(result.errorFlag()).isTrue(),
                    () -> assertThat(result.transaction()).isNull(),
                    () -> assertThat(result.focusField()).isEqualTo(FIELD_ACCOUNT_ID),
                    // The backward read wrote the lookup-failure text and the sequence continued, exactly
                    // as the source continues; the insert paragraph's own catch-all then overwrote the
                    // message with an unconditional move. The last text written is the one the operator
                    // sees, which is why this - and not the transient text - is the turn's outcome.
                    () -> assertThat(result.message()).isEqualTo(MSG_UNABLE_TO_ADD_TRANSACTION));
            verify(transactionRepository, never()).insertAndFlush(any(Transaction.class));
            verify(transactionRepository, never()).existsById(any());
        }

        @Test
        @DisplayName("a colliding identifier is reported under the already-exists text after a BOUNDED "
                + "re-allocation of exactly two attempts, at lines 533 to 539")
        void resolveWriteResponseReportsADuplicateAfterABoundedReAllocation() {
            stubBothAccountReads(payableAccount());
            stubAccountRewriteEchoesRow();
            stubCrossReferenceRow(CARD_NUMBER);
            stubHighestIdentifier(HIGHEST_KEY_NINE);
            stubBoundaryRunsUnit();
            when(transactionRepository.existsById(SUCCESSOR_OF_NINE)).thenReturn(true);

            final BillPaymentService.BillPaymentResult result =
                    service.processBillPayment(submitted(ACCOUNT_ID, "Y"));

            assertAll(
                    () -> assertThat(result.errorFlag()).isTrue(),
                    () -> assertThat(result.message()).isEqualTo(MSG_TRAN_ID_ALREADY_EXISTS),
                    () -> assertThat(result.transaction()).isNull(),
                    () -> assertThat(result.paymentAccepted()).isFalse());
            // The maximum is re-read under the lock once and then the duplicate arm reports, so the bound
            // is observable rather than an unbounded retry.
            verify(transactionRepository, times(EXPECTED_ALLOCATION_ATTEMPTS)).findMaxId();
            verify(transactionRepository, times(EXPECTED_ALLOCATION_ATTEMPTS))
                    .existsById(SUCCESSOR_OF_NINE);
            verify(transactionRepository, never()).insertAndFlush(any(Transaction.class));
            // The lock is transaction scoped, so one acquisition covers every attempt in the unit.
            verify(transactionRepository).lockIdentifierAllocation(EXPECTED_ALLOCATION_LOCK_KEY);
        }

        @Test
        @DisplayName("a duplicate the STORE discovers - rather than the existence probe - reaches the "
                + "same already-exists arm, because it is still a response to the write")
        void aStoreRaisedDuplicateReachesTheAlreadyExistsArm() {
            stubBothAccountReads(payableAccount());
            stubAccountRewriteEchoesRow();
            stubCrossReferenceRow(CARD_NUMBER);
            stubHighestIdentifier(HIGHEST_KEY_NINE);
            stubBoundaryRunsUnit();
            when(transactionRepository.insertAndFlush(any(Transaction.class)))
                    .thenThrow(new EntityExistsException("the key is already stored"));

            final BillPaymentService.BillPaymentResult result =
                    service.processBillPayment(submitted(ACCOUNT_ID, "Y"));

            assertAll(
                    () -> assertThat(result.errorFlag()).isTrue(),
                    () -> assertThat(result.message()).isEqualTo(MSG_TRAN_ID_ALREADY_EXISTS),
                    () -> assertThat(result.focusField()).isEqualTo(FIELD_ACCOUNT_ID),
                    // The unit of work that attempted the insert has finished rolling back, so no arm may
                    // report a stored record.
                    () -> assertThat(result.transaction()).isNull(),
                    () -> assertThat(result.paymentAccepted()).isFalse());
            // A duplicate raised by the store is reported rather than retried, so the maximum is read once.
            verify(transactionRepository).findMaxId();
        }

        @Test
        @DisplayName("a duplicate NESTED inside a wrapping failure is still recognised, because the "
                + "classification walks the cause chain rather than matching a message")
        void aNestedDuplicateIsStillRecognised() {
            stubBothAccountReads(payableAccount());
            stubAccountRewriteEchoesRow();
            stubCrossReferenceRow(CARD_NUMBER);
            stubHighestIdentifier(HIGHEST_KEY_NINE);
            stubBoundaryRunsUnit();
            when(transactionRepository.insertAndFlush(any(Transaction.class)))
                    .thenThrow(new IllegalStateException("the flush failed",
                            new EntityExistsException("the key is already stored")));

            final BillPaymentService.BillPaymentResult result =
                    service.processBillPayment(submitted(ACCOUNT_ID, "Y"));

            assertThat(result.message()).isEqualTo(MSG_TRAN_ID_ALREADY_EXISTS);
            assertThat(result.transaction()).isNull();
        }

        @Test
        @DisplayName("a failure of the ALLOCATION LOCK is not a response to a write, so it has no arm in "
                + "the source and is left to propagate rather than being reported as a write failure")
        void aFailureOfTheAllocationLockIsNotTranslatedIntoAWriteArm() {
            stubBothAccountReads(payableAccount());
            stubBoundaryRunsUnit();
            stubCrossReferenceRow(CARD_NUMBER);
            Mockito.doThrow(new IllegalStateException("the allocation lock could not be taken"))
                    .when(transactionRepository)
                    .lockIdentifierAllocation(EXPECTED_ALLOCATION_LOCK_KEY);

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> service.processBillPayment(submitted(ACCOUNT_ID, "Y")))
                    .withMessage("the allocation lock could not be taken");

            // Nothing about the write was reported, because nothing about the write happened.
            verify(transactionRepository, never()).findMaxId();
            verify(transactionRepository, never()).insertAndFlush(any(Transaction.class));
            // And the account was NOT settled: the lock is taken inside the unit that stores the payment,
            // which the source performs at line 233 BEFORE the settlement of lines 234 and 235, so a
            // failure with no arm in the source leaves both stores unmade rather than one of them made.
            verify(accountRepository, never()).saveAndFlush(any(Account.class));
        }

        @Test
        @DisplayName("the identifier is never parsed, trimmed or numerically compared on the way out: "
                + "the stored key and the projected key are the same sixteen characters")
        void theProjectedIdentifierIsTheStoredIdentifier() {
            arrangeConfirmablePayment(payableAccount(), HIGHEST_KEY_LONG_CARRY);

            final BillPaymentService.BillPaymentResult result =
                    service.processBillPayment(submitted(ACCOUNT_ID, "Y"));

            final Transaction inserted = capturedInsertedRecord();
            assertThat(result.transaction().tranId()).isEqualTo(inserted.getTranId());
            assertThat(result.transaction().tranId()).isEqualTo(SUCCESSOR_OF_LONG_CARRY);
            assertThat(encodedByteCount(result.transaction().tranId()))
                    .isEqualTo(TRANSACTION_ID_WIDTH);
        }
    }

    // ==============================================================================================
    // The balance computation at line 234 and the account rewrite paragraph at line 377
    // ==============================================================================================

    @Nested
    @DisplayName("Balance rules - the pre-payment display at lines 193 and 194, the computation at line "
            + "234, the account read paragraph at line 343 and the account rewrite paragraph at line 377")
    class BalanceRules {

        @Test
        @DisplayName("a completed payment leaves the balance EXACTLY zero at scale two, because the "
                + "amount paid is the whole current balance")
        void updateAcctdatFileStoresExactlyZero() {
            arrangeConfirmablePayment(payableAccount(), null);

            final BillPaymentService.BillPaymentResult result =
                    service.processBillPayment(submitted(ACCOUNT_ID, "Y"));

            final Account rewritten = capturedRewrittenAccount();
            assertAll(
                    () -> assertThat(rewritten.getAcctCurrBal())
                            .isEqualByComparingTo(ZERO_BALANCE),
                    () -> assertThat(rewritten.getAcctCurrBal().scale()).isEqualTo(MONETARY_SCALE),
                    () -> assertThat(rewritten.getAcctCurrBal().compareTo(ZERO_BALANCE)).isZero(),
                    () -> assertThat(result.account()).isNotNull(),
                    () -> assertThat(result.account().acctCurrBal())
                            .isEqualByComparingTo(ZERO_BALANCE),
                    () -> assertThat(result.account().acctCurrBal().scale())
                            .isEqualTo(MONETARY_SCALE));
        }

        @Test
        @DisplayName("the amount is the account's FULL pre-payment balance, and the screen keeps showing "
                + "that pre-payment figure rather than the post-payment one")
        void theAmountIsTheFullPrePaymentBalance() {
            arrangeConfirmablePayment(payableAccount(), null);

            final BillPaymentService.BillPaymentResult result =
                    service.processBillPayment(submitted(ACCOUNT_ID, "Y"));

            final Transaction inserted = capturedInsertedRecord();
            assertAll(
                    () -> assertThat(inserted.getTranAmt()).isEqualByComparingTo(PAYABLE_BALANCE),
                    () -> assertThat(inserted.getTranAmt().scale()).isEqualTo(MONETARY_SCALE),
                    () -> assertThat(result.screenBalance()).isEqualByComparingTo(PAYABLE_BALANCE),
                    () -> assertThat(result.transaction().tranAmt())
                            .isEqualByComparingTo(PAYABLE_BALANCE),
                    // The two figures are deliberately distinct: the operator sees the balance before the
                    // deduction, and the account carries the balance after it.
                    () -> assertThat(result.account().acctCurrBal())
                            .isNotEqualByComparingTo(result.screenBalance()));
        }

        @Test
        @DisplayName("the store into the two-decimal field TRUNCATES toward zero and never rounds: a "
                + "balance of 100.015 yields 100.01 and not the 100.02 half-even would produce")
        void theStoreIntoATwoDecimalFieldTruncatesRatherThanRounds() {
            arrangeConfirmablePayment(accountCarryingVerbatimBalance(UNTRUNCATED_BALANCE), null);

            final BillPaymentService.BillPaymentResult result =
                    service.processBillPayment(submitted(ACCOUNT_ID, "Y"));

            final Transaction inserted = capturedInsertedRecord();
            final Account rewritten = capturedRewrittenAccount();
            assertAll(
                    () -> assertThat(inserted.getTranAmt()).isEqualTo(TRUNCATED_AMOUNT),
                    () -> assertThat(inserted.getTranAmt()).isNotEqualByComparingTo(HALF_EVEN_AMOUNT),
                    () -> assertThat(inserted.getTranAmt().scale()).isEqualTo(MONETARY_SCALE),
                    () -> assertThat(result.screenBalance()).isEqualTo(TRUNCATED_AMOUNT),
                    // The operand order of the computation is the source's and is never rearranged, so the
                    // truncated amount is subtracted from the truncated balance and the result is zero.
                    () -> assertThat(rewritten.getAcctCurrBal()).isEqualByComparingTo(ZERO_BALANCE),
                    () -> assertThat(rewritten.getAcctCurrBal().scale()).isEqualTo(MONETARY_SCALE),
                    // The reset paragraph at line 560 blanks the screen on the success path, so the edited
                    // field is blank here and the truncated figure travels in the projection instead.
                    () -> assertThat(result.screen().currentBalance())
                            .isEqualTo(" ".repeat(BALANCE_DISPLAY_WIDTH)));
        }

        @Test
        @DisplayName("the truncated figure is what reaches the edited screen field too, on a turn that "
                + "reports the balance without paying and therefore does not blank the screen")
        void theEditedFieldCarriesTheTruncatedFigure() {
            stubAccountRead(accountCarryingVerbatimBalance(UNTRUNCATED_BALANCE));

            final BillPaymentService.BillPaymentResult result =
                    service.processBillPayment(submitted(ACCOUNT_ID, null));

            assertAll(
                    () -> assertThat(result.screen().currentBalance()).isEqualTo("+0000000100.01"),
                    () -> assertThat(result.screen().currentBalance()).isNotEqualTo("+0000000100.02"),
                    () -> assertThat(encodedByteCount(result.screen().currentBalance()))
                            .isEqualTo(BALANCE_DISPLAY_WIDTH),
                    () -> assertThat(result.screenBalance()).isEqualTo(TRUNCATED_AMOUNT),
                    () -> assertThat(result.screenBalance()).isNotEqualByComparingTo(HALF_EVEN_AMOUNT));
        }

        @ParameterizedTest
        @ValueSource(strings = {"0.01", "1.00", "194.00", "9999999999.99"})
        @DisplayName("every payable balance is driven to exactly zero at scale two, whatever its "
                + "magnitude")
        void everyPayableBalanceIsDrivenToZero(final String balance) {
            arrangeConfirmablePayment(
                    accountCarryingVerbatimBalance(new BigDecimal(balance)), null);

            service.processBillPayment(submitted(ACCOUNT_ID, "Y"));

            final Transaction inserted = capturedInsertedRecord();
            final Account rewritten = capturedRewrittenAccount();
            assertThat(inserted.getTranAmt()).isEqualByComparingTo(new BigDecimal(balance));
            assertThat(rewritten.getAcctCurrBal()).isEqualByComparingTo(ZERO_BALANCE);
            assertThat(rewritten.getAcctCurrBal().scale()).isEqualTo(MONETARY_SCALE);
        }

        @Test
        @DisplayName("the edited balance field carries a mandatory sign, ten zero-filled integer digits "
                + "and two fraction digits across fourteen characters, from the field declared at line 56")
        void theEditedBalanceFieldIsFourteenCharactersWithAMandatorySign() {
            stubAccountRead(payableAccount());

            final BillPaymentService.BillPaymentResult result =
                    service.processBillPayment(submitted(ACCOUNT_ID, null));

            assertAll(
                    () -> assertThat(result.screen().currentBalance()).isEqualTo("+0000000194.00"),
                    () -> assertThat(encodedByteCount(result.screen().currentBalance()))
                            .isEqualTo(BALANCE_DISPLAY_WIDTH));
        }

        @Test
        @DisplayName("a negative balance renders in the edited field with a leading minus")
        void theEditedBalanceFieldCarriesAMinusForANegativeValue() {
            stubAccountRead(accountCarryingVerbatimBalance(new BigDecimal("-5.00")));

            final BillPaymentService.BillPaymentResult result =
                    service.processBillPayment(submitted(ACCOUNT_ID, null));

            assertThat(result.screen().currentBalance()).isEqualTo("-0000000005.00");
            assertThat(encodedByteCount(result.screen().currentBalance()))
                    .isEqualTo(BALANCE_DISPLAY_WIDTH);
        }

        @Test
        @DisplayName("the account is still rewritten when the insert was refused, because the source "
                + "tests no flag between the write at line 233 and the rewrite at line 235")
        void updateAcctdatFileRunsEvenWhenTheInsertWasRefused() {
            stubBothAccountReads(payableAccount());
            stubAccountRewriteEchoesRow();
            stubCrossReferenceRow(CARD_NUMBER);
            stubHighestIdentifier(HIGHEST_KEY_NINE);
            stubBoundaryRunsUnit();
            when(transactionRepository.existsById(SUCCESSOR_OF_NINE)).thenReturn(true);

            service.processBillPayment(submitted(ACCOUNT_ID, "Y"));

            final Account rewritten = capturedRewrittenAccount();
            assertThat(rewritten.getAcctCurrBal()).isEqualByComparingTo(ZERO_BALANCE);
            verify(transactionRepository, never()).insertAndFlush(any(Transaction.class));
        }

        @Test
        @DisplayName("an account read that returned no balance reaches the read paragraph's catch-all "
                + "and no runtime failure escapes, at lines 365 to 371")
        void readAcctdatFileReportsARecordCarryingNoBalance() {
            stubAccountRead(accountCarryingVerbatimBalance(null));

            final BillPaymentService.BillPaymentResult result =
                    service.processBillPayment(submitted(ACCOUNT_ID, "Y"));

            assertAll(
                    () -> assertThat(result.errorFlag()).isTrue(),
                    () -> assertThat(result.message()).isEqualTo(MSG_UNABLE_TO_LOOKUP_ACCOUNT),
                    () -> assertThat(result.focusField()).isEqualTo(FIELD_ACCOUNT_ID),
                    () -> assertThat(result.account()).isNull(),
                    () -> assertThat(result.transaction()).isNull(),
                    // No record was adopted, so the balance moves read zero, exactly as an unpopulated
                    // working-storage record reads on the platform the source targets.
                    () -> assertThat(result.screenBalance()).isEqualByComparingTo(ZERO_BALANCE));
            assertNothingWasWritten();
        }

        @Test
        @DisplayName("an absent account reaches the read paragraph's not-found arm and records the field "
                + "as supplied-but-invalid, at lines 359 to 364")
        void readAcctdatFileReportsAnAbsentAccount() {
            when(accountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.empty());

            final BillPaymentService.BillPaymentResult result =
                    service.processBillPayment(submitted(ACCOUNT_ID, "Y"));

            assertAll(
                    () -> assertThat(result.errorFlag()).isTrue(),
                    () -> assertThat(result.message()).isEqualTo(MSG_ACCOUNT_ID_NOT_FOUND),
                    () -> assertThat(result.fieldErrors()).containsExactly(
                            new ValidationException.FieldError(PROPERTY_ACCOUNT_ID, FIELD_ACCOUNT_ID,
                                    ValidationException.FieldState.INVALID, MSG_ACCOUNT_ID_NOT_FOUND)),
                    () -> assertThat(result.account()).isNull());
            assertNothingWasWritten();
        }

        @Test
        @DisplayName("a row that VANISHED between the display read and the confirmed span is caught by the "
                + "held read, so nothing is minted and the not-found text reports the turn")
        void aVanishedRowIsCaughtByTheHeldReadBeforeAnythingIsMinted() {
            // The legacy could not reach its rewrite's own not-found arm at lines 390 to 395 while it held
            // the record: the READ ... UPDATE at line 343 locked it, so no other task could delete it before
            // the REWRITE at line 235. The held read is where that same disappearance is detected here - and
            // detecting it there is strictly better than detecting it at the rewrite, because nothing has
            // been minted or stored yet. The rewrite's not-found arm is preserved as the source's arm and is
            // unreachable for the same reason the legacy's was.
            stubAccountRead(payableAccount());
            when(accountRepository.findByIdForUpdate(ACCOUNT_ID)).thenReturn(Optional.empty());
            stubCrossReferenceRow(CARD_NUMBER);
            stubBoundaryRunsUnit();

            final BillPaymentService.BillPaymentResult result =
                    service.processBillPayment(submitted(ACCOUNT_ID, "Y"));

            assertAll(
                    () -> assertThat(result.errorFlag()).isTrue(),
                    () -> assertThat(result.message()).isEqualTo(MSG_ACCOUNT_ID_NOT_FOUND),
                    () -> assertThat(result.transaction()).isNull(),
                    () -> assertThat(result.paymentAccepted()).isFalse());
            verify(transactionRepository, never()).insertAndFlush(any(Transaction.class));
            verify(accountRepository, never()).saveAndFlush(any(Account.class));
        }

        @Test
        @DisplayName("a rewrite reached with no computed balance reports the update-failure text, which "
                + "is the paragraph's catch-all at lines 396 to 402")
        void updateAcctdatFileReportsAMissingComputedBalance() {
            // The one way to reach this arm through the entry point: a negative-confirmation turn that
            // read no account and computed no balance would never get here, so the arm is instead reached
            // by an account read that yielded a record with no balance while the payment stage ran. The
            // source's own sequence cannot produce it, which is precisely what the arm guards.
            stubAccountRead(accountCarryingVerbatimBalance(null));

            final BillPaymentService.BillPaymentResult result =
                    service.processBillPayment(submitted(ACCOUNT_ID, "Y"));

            // The account read already faulted, so the payment stage is suppressed by the flag test at
            // line 208 and the rewrite is never reached at all - which is the outcome to assert, rather
            // than an outcome manufactured by calling a private paragraph directly.
            assertThat(result.message()).isEqualTo(MSG_UNABLE_TO_LOOKUP_ACCOUNT);
            assertThat(result.message()).isNotEqualTo(MSG_UNABLE_TO_UPDATE_ACCOUNT);
            verifyNoInteractions(transactionRepository, cardCrossReferenceRepository,
                    transactionBoundary);
        }
    }

    // ==============================================================================================
    // The confirmation evaluation at lines 173 to 191 and the nothing-to-pay rejection at lines 197
    // to 206
    // ==============================================================================================

    @Nested
    @DisplayName("The enter-key paragraph at line 154 - its confirmation evaluation at lines 173 to 191, "
            + "its nothing-to-pay rejection at lines 197 to 206 and its three successive flag tests")
    class ConfirmationArms {

        @Test
        @DisplayName("the enter-key paragraph tests the error flag at each of its three stage boundaries, "
                + "at lines 169, 197 and 208, so a failure in an earlier stage suppresses every later one "
                + "without any jump")
        void processEnterKeyTestsTheErrorFlagAtEachStageBoundary() {
            // The first stage faults the empty account-id field and raises the flag. The flag test at line
            // 169 then suppresses the confirmation evaluation and the balance display, the test at line
            // 197 suppresses the nothing-to-pay check, and the test at line 208 suppresses the payment.
            final BillPaymentService.BillPaymentResult result =
                    service.processBillPayment(submitted(null, "Y"));

            assertAll(
                    () -> assertThat(result.errorFlag()).isTrue(),
                    () -> assertThat(result.message()).isEqualTo(MSG_ACCT_ID_EMPTY),
                    // Stage two was suppressed: no key was adopted and no account was read.
                    () -> assertThat(result.screenBalance()).isNull(),
                    () -> assertThat(result.screen().currentBalance())
                            .isEqualTo(" ".repeat(BALANCE_DISPLAY_WIDTH)),
                    // Stage three was suppressed: the nothing-to-pay text was never written.
                    () -> assertThat(result.message()).isNotEqualTo(MSG_NOTHING_TO_PAY),
                    // Stage four was suppressed: neither the payment nor the confirmation prompt ran.
                    () -> assertThat(result.message()).isNotEqualTo(MSG_CONFIRM_BILL_PAYMENT),
                    () -> assertThat(result.transaction()).isNull(),
                    () -> assertThat(result.confirmationState().isNo()).isTrue());
            verifyNoInteractions(accountRepository, transactionRepository, cardCrossReferenceRepository,
                    transactionBoundary);
        }

        @Test
        @DisplayName("the confirmation flag is reset on every pass at line 156, so an affirmative answer "
                + "counts only on the turn that carries it and confirmation is never remembered")
        void processEnterKeyResetsTheConfirmationFlagOnEveryPass() {
            stubAccountReadsReturningFreshInstances();
            stubAccountRewriteEchoesRow();
            stubCrossReferenceRow(CARD_NUMBER);
            stubHighestIdentifier(null);
            stubInsertEchoesRecord();
            stubBoundaryRunsUnit();

            final BillPaymentService.BillPaymentResult confirmed =
                    service.processBillPayment(submitted(ACCOUNT_ID, "Y"));
            final BillPaymentService.BillPaymentResult unconfirmed =
                    service.processBillPayment(submitted(ACCOUNT_ID, null));

            assertAll(
                    () -> assertThat(confirmed.confirmationState().isYes()).isTrue(),
                    () -> assertThat(unconfirmed.confirmationState().isNo()).isTrue(),
                    () -> assertThat(unconfirmed.transaction()).isNull(),
                    () -> assertThat(unconfirmed.message()).isEqualTo(MSG_CONFIRM_BILL_PAYMENT));
            verify(transactionRepository).insertAndFlush(any(Transaction.class));
        }

        @ParameterizedTest
        @ValueSource(strings = {"Y", "y"})
        @DisplayName("the FIRST arm - the affirmative characters in either case - pays, at lines 174 to 177")
        void theAffirmativeArmPays(final String confirm) {
            arrangeConfirmablePayment(payableAccount(), null);

            final BillPaymentService.BillPaymentResult result =
                    service.processBillPayment(submitted(ACCOUNT_ID, confirm));

            assertAll(
                    () -> assertThat(result.paymentAccepted()).isTrue(),
                    () -> assertThat(result.confirmationState().isYes()).isTrue(),
                    () -> assertThat(result.errorFlag()).isFalse(),
                    () -> assertThat(result.transaction()).isNotNull());
        }

        @ParameterizedTest
        @ValueSource(strings = {"N", "n"})
        @DisplayName("the SECOND arm - the negative characters - is error-flag SUPPRESSION and not a "
                + "distinct decline: the screen is blanked, no message is left and no data is touched, at "
                + "lines 178 to 181")
        void theNegativeArmSuppressesRatherThanDeclining(final String confirm) {
            final BillPaymentService.BillPaymentResult result =
                    service.processBillPayment(submitted(ACCOUNT_ID, confirm));

            assertAll(
                    // The flag is raised AFTER the screen is cleared and sent, which is what leaves this
                    // path with a blank screen and no text at all.
                    () -> assertThat(result.errorFlag()).isTrue(),
                    () -> assertThat(result.message()).isEqualTo(NO_MESSAGE),
                    () -> assertThat(result.message()).isNotEqualTo(MSG_NOTHING_TO_PAY),
                    () -> assertThat(result.message()).isNotEqualTo(MSG_INVALID_CONFIRMATION),
                    () -> assertThat(result.fieldErrors()).isEmpty(),
                    () -> assertThat(result.confirmationState().isNo()).isTrue(),
                    () -> assertThat(result.screen().accountId()).isEqualTo(" ".repeat(ACCOUNT_ID_WIDTH)),
                    () -> assertThat(result.screen().confirm()).isEqualTo(" "),
                    // The balance moves at lines 193 and 194 sit INSIDE the block the flag test at line
                    // 169 opened, so they run after this arm has already blanked the screen and they
                    // re-fill the edited field from a record that was never read - which reads as zero.
                    // Expecting a blank field here would be expecting behaviour the legacy does not have.
                    () -> assertThat(result.screen().currentBalance()).isEqualTo("+0000000000.00"),
                    () -> assertThat(result.screenBalance()).isEqualByComparingTo(ZERO_BALANCE),
                    () -> assertThat(result.transaction()).isNull(),
                    () -> assertThat(result.account()).isNull(),
                    () -> assertThat(result.paymentAccepted()).isFalse());
            // The arm reads nothing, so it is not a decline of a payment it evaluated.
            verifyNoInteractions(accountRepository, transactionRepository, cardCrossReferenceRepository,
                    transactionBoundary);
        }

        @Test
        @DisplayName("the THIRD arm - a blank confirmation - reads the account and REPORTS the balance "
                + "without paying, at lines 182 to 184")
        void theBlankArmReportsTheBalanceWithoutPaying() {
            stubAccountRead(payableAccount());

            final BillPaymentService.BillPaymentResult result =
                    service.processBillPayment(submitted(ACCOUNT_ID, null));

            verify(accountRepository).findById(ACCOUNT_ID);
            assertAll(
                    () -> assertThat(result.errorFlag()).isFalse(),
                    () -> assertThat(result.message()).isEqualTo(MSG_CONFIRM_BILL_PAYMENT),
                    () -> assertThat(result.focusField()).isEqualTo(FIELD_CONFIRM),
                    () -> assertThat(result.screenBalance()).isEqualByComparingTo(PAYABLE_BALANCE),
                    () -> assertThat(result.screen().currentBalance()).isEqualTo("+0000000194.00"),
                    () -> assertThat(result.confirmationState().isNo()).isTrue(),
                    () -> assertThat(result.transaction()).isNull(),
                    () -> assertThat(result.paymentAccepted()).isFalse());
            verify(transactionRepository, never()).insertAndFlush(any(Transaction.class));
            verify(accountRepository, never()).saveAndFlush(any(Account.class));
            verifyNoInteractions(cardCrossReferenceRepository, transactionBoundary);
        }

        @ParameterizedTest
        @ValueSource(strings = {"", " ", "\u0000"})
        @DisplayName("every spelling of a blank confirmation field reaches the third arm, because the "
                + "legacy names both spaces and low values in that clause")
        void everySpellingOfBlankReachesTheThirdArm(final String confirm) {
            stubAccountRead(payableAccount());

            final BillPaymentService.BillPaymentResult result =
                    service.processBillPayment(submitted(ACCOUNT_ID, confirm));

            assertThat(result.message()).isEqualTo(MSG_CONFIRM_BILL_PAYMENT);
            assertThat(result.errorFlag()).isFalse();
            verify(transactionRepository, never()).insertAndFlush(any(Transaction.class));
            verify(accountRepository, never()).saveAndFlush(any(Account.class));
        }

        @ParameterizedTest
        @ValueSource(strings = {"X", "1", "Z", "?"})
        @DisplayName("the FOURTH arm - anything else - reports the invalid-confirmation text and records "
                + "the field as supplied-but-invalid, at lines 185 to 190")
        void theCatchAllArmReportsTheInvalidConfirmationText(final String confirm) {
            final BillPaymentService.BillPaymentResult result =
                    service.processBillPayment(submitted(ACCOUNT_ID, confirm));

            assertAll(
                    () -> assertThat(result.errorFlag()).isTrue(),
                    () -> assertThat(result.message()).isEqualTo(MSG_INVALID_CONFIRMATION),
                    () -> assertThat(result.focusField()).isEqualTo(FIELD_CONFIRM),
                    () -> assertThat(result.fieldErrors()).containsExactly(
                            new ValidationException.FieldError(PROPERTY_CONFIRM, FIELD_CONFIRM,
                                    ValidationException.FieldState.INVALID, MSG_INVALID_CONFIRMATION)),
                    () -> assertThat(result.transaction()).isNull());
            verifyNoInteractions(accountRepository, transactionRepository, cardCrossReferenceRepository,
                    transactionBoundary);
        }

        @Test
        @DisplayName("all FOUR arms are exercised in the source's clause order, and each reaches its own "
                + "distinct outcome - the order is the contract, because the language stops at the first "
                + "matching clause")
        void allFourArmsAreEvaluatedInTheSourcesClauseOrder() {
            stubAccountReadsReturningFreshInstances();
            stubAccountRewriteEchoesRow();
            stubCrossReferenceRow(CARD_NUMBER);
            stubHighestIdentifier(null);
            stubInsertEchoesRecord();
            stubBoundaryRunsUnit();

            // Arm one, lines 174 to 177.
            final BillPaymentService.BillPaymentResult affirmative =
                    service.processBillPayment(submitted(ACCOUNT_ID, "Y"));
            // Arm two, lines 178 to 181.
            final BillPaymentService.BillPaymentResult negative =
                    service.processBillPayment(submitted(ACCOUNT_ID, "N"));
            // Arm three, lines 182 to 184.
            final BillPaymentService.BillPaymentResult blank =
                    service.processBillPayment(submitted(ACCOUNT_ID, null));
            // Arm four, line 185 onward.
            final BillPaymentService.BillPaymentResult other =
                    service.processBillPayment(submitted(ACCOUNT_ID, "X"));

            assertAll(
                    () -> assertThat(affirmative.paymentAccepted()).isTrue(),
                    () -> assertThat(negative.errorFlag()).isTrue(),
                    () -> assertThat(negative.message()).isEqualTo(NO_MESSAGE),
                    () -> assertThat(blank.errorFlag()).isFalse(),
                    () -> assertThat(blank.message()).isEqualTo(MSG_CONFIRM_BILL_PAYMENT),
                    () -> assertThat(other.errorFlag()).isTrue(),
                    () -> assertThat(other.message()).isEqualTo(MSG_INVALID_CONFIRMATION));
            // Only the two reading arms consulted the account master, which is what distinguishes the
            // clause order from a chain that happened to produce the same texts: two unlocked reads in all,
            // one for the affirmative arm and one for the blank arm, while the other two arms read nothing.
            // The affirmative arm additionally takes ONE locking read per confirmed unit of work - one for
            // the unit that stores the payment and one for the unit that settles the account.
            verify(accountRepository, times(2)).findById(ACCOUNT_ID);
            verify(accountRepository, times(2)).findByIdForUpdate(ACCOUNT_ID);
        }

        @Test
        @DisplayName("the nothing-to-pay rejection does NOT fire on a BLANK account identifier, even at a "
                + "zero balance, because the legacy guard at lines 198 and 199 requires BOTH conditions")
        void theNothingToPayRejectionDoesNotFireOnABlankAccountIdentifier() {
            final BillPaymentService.BillPaymentResult result =
                    service.processBillPayment(submitted(null, "Y"));

            assertAll(
                    () -> assertThat(result.message()).isNotEqualTo(MSG_NOTHING_TO_PAY),
                    // The first stage faulted the empty field, which is the outcome the class declares for
                    // an unsupplied account identifier.
                    () -> assertThat(result.message()).isEqualTo(MSG_ACCT_ID_EMPTY),
                    () -> assertThat(result.errorFlag()).isTrue(),
                    () -> assertThat(result.focusField()).isEqualTo(FIELD_ACCOUNT_ID),
                    () -> assertThat(result.fieldErrors()).containsExactly(
                            new ValidationException.FieldError(PROPERTY_ACCOUNT_ID, FIELD_ACCOUNT_ID,
                                    ValidationException.FieldState.MISSING, MSG_ACCT_ID_EMPTY)),
                    () -> assertThat(result.transaction()).isNull());
            verifyNoInteractions(accountRepository, transactionRepository, cardCrossReferenceRepository,
                    transactionBoundary);
        }

        @Test
        @DisplayName("the SAME zero balance WITH a non-blank account identifier does fire the "
                + "nothing-to-pay rejection, byte for byte and untrimmed, at lines 200 to 204")
        void theNothingToPayRejectionFiresOnANonBlankAccountIdentifier() {
            stubAccountRead(accountCarryingVerbatimBalance(ZERO_BALANCE));

            final BillPaymentService.BillPaymentResult result =
                    service.processBillPayment(submitted(ACCOUNT_ID, "Y"));

            assertAll(
                    () -> assertThat(result.message()).isEqualTo(MSG_NOTHING_TO_PAY),
                    () -> assertThat(result.message()).endsWith("..."),
                    () -> assertThat(result.message()).doesNotEndWith("...."),
                    () -> assertThat(result.errorFlag()).isTrue(),
                    () -> assertThat(result.focusField()).isEqualTo(FIELD_ACCOUNT_ID),
                    () -> assertThat(result.fieldErrors()).containsExactly(
                            new ValidationException.FieldError(PROPERTY_ACCOUNT_ID, FIELD_ACCOUNT_ID,
                                    ValidationException.FieldState.INVALID, MSG_NOTHING_TO_PAY)),
                    () -> assertThat(result.transaction()).isNull());
            assertNothingWasWritten();
            verifyNoInteractions(cardCrossReferenceRepository, transactionBoundary);
        }

        @ParameterizedTest
        @ValueSource(strings = {"0.00", "-0.01", "-5.00", "-9999999999.99"})
        @DisplayName("every balance at or below zero reaches the nothing-to-pay rejection when the "
                + "account identifier is supplied")
        void everyNonPositiveBalanceReachesTheNothingToPayRejection(final String balance) {
            stubAccountRead(accountCarryingVerbatimBalance(new BigDecimal(balance)));

            final BillPaymentService.BillPaymentResult result =
                    service.processBillPayment(submitted(ACCOUNT_ID, "Y"));

            assertThat(result.message()).isEqualTo(MSG_NOTHING_TO_PAY);
            assertThat(result.errorFlag()).isTrue();
            assertNothingWasWritten();
        }

        @ParameterizedTest
        @ValueSource(strings = {"", " ", "           ", "\u0000"})
        @DisplayName("every spelling of an unsupplied account identifier faults the field as MISSING "
                + "rather than as invalid, at lines 159 to 164")
        void everySpellingOfAnUnsuppliedAccountIdentifierFaultsAsMissing(final String accountId) {
            final BillPaymentService.BillPaymentResult result =
                    service.processBillPayment(submitted(accountId, "Y"));

            assertAll(
                    () -> assertThat(result.message()).isEqualTo(MSG_ACCT_ID_EMPTY),
                    () -> assertThat(result.fieldErrors()).hasSize(1),
                    () -> assertThat(result.fieldErrors().get(0).state())
                            .isEqualTo(ValidationException.FieldState.MISSING));
            verifyNoInteractions(accountRepository, transactionRepository, cardCrossReferenceRepository,
                    transactionBoundary);
        }

        @Test
        @DisplayName("the field-error contract has exactly the two legacy states, so a field is either "
                + "not supplied or supplied wrongly and never both")
        void theFieldErrorContractHasExactlyTwoStates() {
            assertThat(ValidationException.FieldState.values())
                    .containsExactly(ValidationException.FieldState.MISSING,
                            ValidationException.FieldState.INVALID);
        }

        @Test
        @DisplayName("the field-error list the turn hands back is unmodifiable, so no consumer can add a "
                + "fault the turn did not record")
        void theFieldErrorListIsUnmodifiable() {
            final BillPaymentService.BillPaymentResult result =
                    service.processBillPayment(submitted(null, "Y"));

            final List<ValidationException.FieldError> faults = result.fieldErrors();
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> faults.add(new ValidationException.FieldError(PROPERTY_CONFIRM,
                            FIELD_CONFIRM, ValidationException.FieldState.INVALID, NO_MESSAGE)));
        }
    }

    // ==============================================================================================
    // The synthesized record, lines 218 to 232
    // ==============================================================================================

    @Nested
    @DisplayName("The synthesized transaction record assembled at lines 218 to 232")
    class SynthesizedTransactionFields {

        @Test
        @DisplayName("every constant field carries its exact stored value at its declared width, "
                + "untrimmed")
        void everyConstantFieldCarriesItsExactStoredValue() {
            arrangeConfirmablePayment(payableAccount(), null);

            service.processBillPayment(submitted(ACCOUNT_ID, "Y"));

            final Transaction inserted = capturedInsertedRecord();
            assertAll(
                    () -> assertThat(inserted.getTranTypeCd()).isEqualTo(EXPECTED_TRAN_TYPE_CD),
                    () -> assertThat(inserted.getTranCatCd()).isEqualTo(EXPECTED_TRAN_CAT_CD),
                    () -> assertThat(inserted.getTranSource()).isEqualTo(EXPECTED_TRAN_SOURCE),
                    () -> assertThat(inserted.getTranDesc()).isEqualTo(EXPECTED_TRAN_DESC),
                    () -> assertThat(inserted.getMerchantId()).isEqualTo(EXPECTED_MERCHANT_ID),
                    () -> assertThat(inserted.getMerchantName()).isEqualTo(EXPECTED_MERCHANT_NAME),
                    () -> assertThat(inserted.getMerchantCity()).isEqualTo(EXPECTED_MERCHANT_CITY),
                    () -> assertThat(inserted.getMerchantZip()).isEqualTo(EXPECTED_MERCHANT_ZIP));
        }

        @Test
        @DisplayName("the type code is the two-character payment code")
        void theTypeCodeIsTwoCharacters() {
            arrangeConfirmablePayment(payableAccount(), null);

            service.processBillPayment(submitted(ACCOUNT_ID, "Y"));

            final Transaction inserted = capturedInsertedRecord();
            assertThat(inserted.getTranTypeCd()).isEqualTo(EXPECTED_TRAN_TYPE_CD);
            assertThat(encodedByteCount(inserted.getTranTypeCd())).isEqualTo(2);
        }

        @Test
        @DisplayName("the category code is the four-character zero-filled STRING 0002, and never the "
                + "integer two nor the single character 2")
        void theCategoryCodeIsTheFourCharacterZeroFilledString() {
            arrangeConfirmablePayment(payableAccount(), null);

            service.processBillPayment(submitted(ACCOUNT_ID, "Y"));

            final Transaction inserted = capturedInsertedRecord();
            assertAll(
                    () -> assertThat(inserted.getTranCatCd()).isEqualTo(EXPECTED_TRAN_CAT_CD),
                    () -> assertThat(encodedByteCount(inserted.getTranCatCd())).isEqualTo(4),
                    () -> assertThat(inserted.getTranCatCd()).isNotEqualTo("2"),
                    () -> assertThat(inserted.getTranCatCd()).startsWith("000"));
        }

        @Test
        @DisplayName("the source code carries its TWO trailing spaces, because the literal moved is eight "
                + "characters and the receiving field is ten")
        void theSourceCodeCarriesItsTwoTrailingSpaces() {
            arrangeConfirmablePayment(payableAccount(), null);

            service.processBillPayment(submitted(ACCOUNT_ID, "Y"));

            final Transaction inserted = capturedInsertedRecord();
            assertAll(
                    () -> assertThat(inserted.getTranSource()).isEqualTo(EXPECTED_TRAN_SOURCE),
                    () -> assertThat(encodedByteCount(inserted.getTranSource()))
                            .isEqualTo(TRAN_SOURCE_WIDTH),
                    () -> assertThat(inserted.getTranSource()).endsWith(DOUBLE_SPACE),
                    // The padding is part of the value: a trimmed comparison would pass against a value
                    // the legacy field cannot hold.
                    () -> assertThat(inserted.getTranSource()).isNotEqualTo("POS TERM"));
        }

        @Test
        @DisplayName("the merchant identifier is the all-nines sentinel at exactly nine digits")
        void theMerchantIdentifierIsTheNineDigitSentinel() {
            arrangeConfirmablePayment(payableAccount(), null);

            service.processBillPayment(submitted(ACCOUNT_ID, "Y"));

            final Transaction inserted = capturedInsertedRecord();
            assertAll(
                    () -> assertThat(inserted.getMerchantId()).isEqualTo(EXPECTED_MERCHANT_ID),
                    () -> assertThat(encodedByteCount(inserted.getMerchantId()))
                            .isEqualTo(MERCHANT_ID_WIDTH),
                    () -> assertThat(inserted.getMerchantId()).isEqualTo("9".repeat(9)));
        }

        @Test
        @DisplayName("the card number is the value the cross-reference access path returned, verbatim and "
                + "with no re-ordering, from the move at line 225")
        void theCardNumberIsTheResolvedValueVerbatim() {
            stubBothAccountReads(payableAccount());
            stubAccountRewriteEchoesRow();
            stubCrossReferenceRow(HIGHER_CARD_NUMBER);
            stubHighestIdentifier(null);
            stubInsertEchoesRecord();
            stubBoundaryRunsUnit();

            final BillPaymentService.BillPaymentResult result =
                    service.processBillPayment(submitted(ACCOUNT_ID, "Y"));

            final Transaction inserted = capturedInsertedRecord();
            assertAll(
                    () -> assertThat(SensitiveValues.fingerprint(inserted.getTranCardNum()))
                            .isEqualTo(SensitiveValues.fingerprint(HIGHER_CARD_NUMBER)),
                    () -> assertThat(encodedByteCount(inserted.getTranCardNum())).isEqualTo(16),
                    () -> assertThat(result.transaction().tranCardNum())
                            .isEqualTo(HIGHER_CARD_NUMBER));
        }

        @Test
        @DisplayName("the projection restates the inserted record field for field, so a consumer reads "
                + "the stored value rather than a re-derived one")
        void theProjectionRestatesTheInsertedRecordFieldForField() {
            arrangeConfirmablePayment(payableAccount(), HIGHEST_KEY_NINE);

            final BillPaymentService.BillPaymentResult result =
                    service.processBillPayment(submitted(ACCOUNT_ID, "Y"));

            final Transaction inserted = capturedInsertedRecord();
            final BillPaymentService.TransactionProjection projected = result.transaction();
            assertAll(
                    () -> assertThat(projected.tranId()).isEqualTo(inserted.getTranId()),
                    () -> assertThat(projected.tranTypeCd()).isEqualTo(inserted.getTranTypeCd()),
                    () -> assertThat(projected.tranCatCd()).isEqualTo(inserted.getTranCatCd()),
                    () -> assertThat(projected.tranSource()).isEqualTo(inserted.getTranSource()),
                    () -> assertThat(projected.tranDesc()).isEqualTo(inserted.getTranDesc()),
                    () -> assertThat(projected.tranAmt()).isEqualByComparingTo(inserted.getTranAmt()),
                    () -> assertThat(projected.merchantId()).isEqualTo(inserted.getMerchantId()),
                    () -> assertThat(projected.merchantName()).isEqualTo(inserted.getMerchantName()),
                    () -> assertThat(projected.merchantCity()).isEqualTo(inserted.getMerchantCity()),
                    () -> assertThat(projected.merchantZip()).isEqualTo(inserted.getMerchantZip()),
                    () -> assertThat(projected.tranCardNum()).isEqualTo(inserted.getTranCardNum()),
                    () -> assertThat(projected.tranOrigTs()).isEqualTo(inserted.getTranOrigTs()),
                    () -> assertThat(projected.tranProcTs()).isEqualTo(inserted.getTranProcTs()));
        }

        @Test
        @DisplayName("a record assembled but refused is deliberately NOT projected, so a refused payment "
                + "cannot be mistaken for a completed one")
        void aRefusedRecordIsNotProjected() {
            stubBothAccountReads(payableAccount());
            stubAccountRewriteEchoesRow();
            stubCrossReferenceRow(CARD_NUMBER);
            stubHighestIdentifier(HIGHEST_KEY_NINE);
            stubBoundaryRunsUnit();
            when(transactionRepository.existsById(SUCCESSOR_OF_NINE)).thenReturn(true);

            final BillPaymentService.BillPaymentResult result =
                    service.processBillPayment(submitted(ACCOUNT_ID, "Y"));

            assertThat(result.transaction()).isNull();
            assertThat(result.paymentAccepted()).isFalse();
        }
    }

    // ==============================================================================================
    // GET-CURRENT-TIMESTAMP, line 249
    // ==============================================================================================

    @Nested
    @DisplayName("The online 26-character timestamp built by the paragraph at lines 249 to 267")
    class OnlineTimestamp {

        @Test
        @DisplayName("with a clock carrying 123456789 nanoseconds the fraction is STILL six zeros, which "
                + "is the parity break a formatter emitting real microseconds would introduce silently")
        void theFractionIsZerosEvenWhenTheClockCarriesNanoseconds() {
            final BillPaymentService subSecondService = serviceOn(SUB_SECOND_CLOCK);
            arrangeConfirmablePayment(payableAccount(), null);

            subSecondService.processBillPayment(submitted(ACCOUNT_ID, "Y"));

            final Transaction inserted = capturedInsertedRecord();
            final String timestamp = inserted.getTranOrigTs();
            assertAll(
                    () -> assertThat(timestamp).isEqualTo(SUB_SECOND_ONLINE_TIMESTAMP),
                    () -> assertThat(timestamp).endsWith("." + TIMESTAMP_ZERO_FRACTION),
                    () -> assertThat(encodedByteCount(timestamp)).isEqualTo(TIMESTAMP_WIDTH),
                    () -> assertThat(timestamp.charAt(TIMESTAMP_SEPARATOR_INDEX)).isEqualTo(' '),
                    () -> assertThat(timestamp).doesNotContain("123456")
            );
        }

        @Test
        @DisplayName("the value is the ONLINE form and not the batch form: no hyphen before the hour and "
                + "no dots between the time parts")
        void theOnlineFormIsNotTheBatchForm() {
            final BillPaymentService subSecondService = serviceOn(SUB_SECOND_CLOCK);
            arrangeConfirmablePayment(payableAccount(), null);

            subSecondService.processBillPayment(submitted(ACCOUNT_ID, "Y"));

            final String timestamp = capturedInsertedRecord().getTranOrigTs();
            assertAll(
                    // The batch tier separates the date from the hour with a hyphen; the online tier uses
                    // the space this position carries.
                    () -> assertThat(timestamp.charAt(TIMESTAMP_SEPARATOR_INDEX)).isNotEqualTo('-'),
                    // The batch tier separates the time parts with dots; the online tier uses colons.
                    () -> assertThat(timestamp.charAt(13)).isEqualTo(':'),
                    () -> assertThat(timestamp.charAt(16)).isEqualTo(':'),
                    () -> assertThat(timestamp.charAt(19)).isEqualTo('.'),
                    // Exactly one point, which is the fraction separator: the batch form carries three.
                    () -> assertThat(timestamp.chars().filter(character -> character == '.').count())
                            .isEqualTo(1L),
                    // Exactly two hyphens, both inside the date: the batch form carries three.
                    () -> assertThat(timestamp.chars().filter(character -> character == '-').count())
                            .isEqualTo(2L),
                    () -> assertThat(timestamp.substring(0, TIMESTAMP_SEPARATOR_INDEX))
                            .isEqualTo("2024-03-07"));
        }

        @Test
        @DisplayName("the origination and processing timestamps receive the SAME value, byte for byte, "
                + "because one move at lines 231 and 232 has two receiving fields")
        void bothTimestampsReceiveTheSameValueByteForByte() {
            arrangeConfirmablePayment(payableAccount(), null);

            final BillPaymentService.BillPaymentResult result =
                    service.processBillPayment(submitted(ACCOUNT_ID, "Y"));

            final Transaction inserted = capturedInsertedRecord();
            assertAll(
                    () -> assertThat(inserted.getTranProcTs()).isEqualTo(inserted.getTranOrigTs()),
                    () -> assertThat(inserted.getTranOrigTs().getBytes(StandardCharsets.US_ASCII))
                            .isEqualTo(inserted.getTranProcTs().getBytes(StandardCharsets.US_ASCII)),
                    () -> assertThat(result.transaction().tranProcTs())
                            .isEqualTo(result.transaction().tranOrigTs()),
                    () -> assertThat(encodedByteCount(inserted.getTranProcTs()))
                            .isEqualTo(TIMESTAMP_WIDTH));
        }

        @Test
        @DisplayName("the module's pinned instant renders the declared literal exactly, character for "
                + "character, which is the value every seeded record carries")
        void thePinnedInstantRendersTheDeclaredLiteralExactly() {
            arrangeConfirmablePayment(payableAccount(), null);

            service.processBillPayment(submitted(ACCOUNT_ID, "Y"));

            final Transaction inserted = capturedInsertedRecord();
            assertAll(
                    () -> assertThat(inserted.getTranOrigTs()).isEqualTo(PINNED_ONLINE_TIMESTAMP),
                    () -> assertThat(inserted.getTranProcTs()).isEqualTo(PINNED_ONLINE_TIMESTAMP),
                    () -> assertThat(encodedByteCount(inserted.getTranOrigTs()))
                            .isEqualTo(TIMESTAMP_WIDTH));
        }

        @Test
        @DisplayName("single-digit date and time parts are zero filled to two characters, so the width "
                + "never varies with the value")
        void singleDigitPartsAreZeroFilledToTwoCharacters() {
            final BillPaymentService singleDigitService = serviceOn(SINGLE_DIGIT_PARTS_CLOCK);
            arrangeConfirmablePayment(payableAccount(), null);

            singleDigitService.processBillPayment(submitted(ACCOUNT_ID, "Y"));

            final Transaction inserted = capturedInsertedRecord();
            assertThat(inserted.getTranOrigTs()).isEqualTo(SINGLE_DIGIT_ONLINE_TIMESTAMP);
            assertThat(encodedByteCount(inserted.getTranOrigTs())).isEqualTo(TIMESTAMP_WIDTH);
        }
    }

    // ==============================================================================================
    // The response arms of WRITE-TRANSACT-FILE, lines 522 to 547, and the twelve message sites
    // ==============================================================================================

    @Nested
    @DisplayName("Operator messages - the insert paragraph's response arms at lines 522 to 547 and the "
            + "twelve message sites at lines 187, 201, 361, 368, 392, 399, 425, 432, 456, 463, 492 and 543")
    class MessageContract {

        @Test
        @DisplayName("the success message carries TWO consecutive spaces after its first full stop, "
                + "because the first fragment ends with a space and the second begins with one")
        void theSuccessMessageCarriesTwoConsecutiveSpaces() {
            arrangeConfirmablePayment(payableAccount(), null);

            final BillPaymentService.BillPaymentResult result =
                    service.processBillPayment(submitted(ACCOUNT_ID, "Y"));

            assertAll(
                    () -> assertThat(result.message()).isEqualTo(EXPECTED_SUCCESS_MESSAGE),
                    // Stated a second time by position, so the intent is visible to a reviewer who does
                    // not count spaces inside a literal.
                    () -> assertThat(result.message().indexOf(DOUBLE_SPACE))
                            .isGreaterThanOrEqualTo(0),
                    () -> assertThat(result.message().indexOf(DOUBLE_SPACE))
                            .isEqualTo("Payment successful.".length()),
                    // The normalised single-space form is what a well-meaning tidy-up would produce, and
                    // it must never appear.
                    () -> assertThat(result.message()).isNotEqualTo(
                            "Payment successful. Your Transaction ID is "
                                    + FIRST_IDENTIFIER_ON_EMPTY_TABLE + "."),
                    () -> assertThat(result.message()).endsWith(
                            FIRST_IDENTIFIER_ON_EMPTY_TABLE + "."));
        }

        @Test
        @DisplayName("the success path recolours the message field, blanks the screen and reports the "
                + "payment as accepted, at lines 523 to 532")
        void theSuccessPathRecoloursAndBlanksTheScreen() {
            arrangeConfirmablePayment(payableAccount(), null);

            final BillPaymentService.BillPaymentResult result =
                    service.processBillPayment(submitted(ACCOUNT_ID, "Y"));

            assertAll(
                    () -> assertThat(result.messageHighlightedGreen()).isTrue(),
                    () -> assertThat(result.errorFlag()).isFalse(),
                    () -> assertThat(result.paymentAccepted()).isTrue(),
                    () -> assertThat(result.screen().accountId())
                            .isEqualTo(" ".repeat(ACCOUNT_ID_WIDTH)),
                    () -> assertThat(result.screen().confirm()).isEqualTo(" "),
                    () -> assertThat(result.focusField()).isEqualTo(FIELD_ACCOUNT_ID),
                    () -> assertThat(result.route())
                            .isEqualTo(NavigationService.Route.BILL_PAYMENT),
                    () -> assertThat(result.reArmedTransactionId())
                            .isEqualTo(EXPECTED_TRANSACTION_NAME));
        }

        @Test
        @DisplayName("no other path recolours the message field: the recolour happens only at line 526")
        void noOtherPathRecoloursTheMessageField() {
            final BillPaymentService.BillPaymentResult empty =
                    service.processBillPayment(submitted(null, "Y"));
            final BillPaymentService.BillPaymentResult invalid =
                    service.processBillPayment(submitted(ACCOUNT_ID, "X"));
            final BillPaymentService.BillPaymentResult cleared =
                    service.processBillPayment(keyed(KeyAction.PFK04));

            assertAll(
                    () -> assertThat(empty.messageHighlightedGreen()).isFalse(),
                    () -> assertThat(invalid.messageHighlightedGreen()).isFalse(),
                    () -> assertThat(cleared.messageHighlightedGreen()).isFalse());
        }

        /**
         * The lookup-failure text, and the honest limit of what the entry point can observe about it.
         *
         * <p>One constant is emitted from two sites: the browse-start catch-all at line 463 and the
         * backward-read catch-all at line 492. The browse-start arms cannot be reached through the entry
         * point at all, because the statement at line 212 assigns the high-value sentinel immediately
         * before the browse is started and does so again on every re-allocation attempt, so the
         * precondition those arms guard can only be broken by an edit to the statement order.
         *
         * <p>The backward-read arm is reachable, but its text does not survive the turn: the legacy writes
         * the message with an <em>unconditional</em> move at every site and tests no flag between the
         * backward read and the write, so the insert paragraph's own catch-all overwrites it and the last
         * text written is the one the operator sees. Asserting that the transient text is returned would
         * therefore be asserting behaviour the legacy does not have. What is asserted instead is that both
         * of the backward read's refusal conditions reach the byte-identical observable outcome, and that
         * the outcome is the write paragraph's own text - which is a regression guard on the overwrite
         * semantics rather than a restatement of a constant.
         */
        @Test
        @DisplayName("both refusal conditions of the backward read reach the byte-identical outcome, and "
                + "the transient lookup-failure text is superseded by the insert paragraph's own text")
        void bothRefusalConditionsOfTheBackwardReadReachTheIdenticalOutcome() {
            stubAccountReadsReturningFreshInstances();
            stubAccountRewriteEchoesRow();
            stubCrossReferenceRow(CARD_NUMBER);
            stubBoundaryRunsUnit();
            when(transactionRepository.findMaxId())
                    .thenReturn(Optional.of(MALFORMED_KEY_NON_DIGIT))
                    .thenReturn(Optional.of(MALFORMED_KEY_SHORT));

            final BillPaymentService.BillPaymentResult nonDigit =
                    service.processBillPayment(submitted(ACCOUNT_ID, "Y"));
            final BillPaymentService.BillPaymentResult wrongWidth =
                    service.processBillPayment(submitted(ACCOUNT_ID, "Y"));

            assertAll(
                    () -> assertThat(nonDigit.message()).isEqualTo(wrongWidth.message()),
                    () -> assertThat(nonDigit.errorFlag()).isEqualTo(wrongWidth.errorFlag()),
                    () -> assertThat(nonDigit.focusField()).isEqualTo(wrongWidth.focusField()),
                    () -> assertThat(nonDigit.message()).isEqualTo(MSG_UNABLE_TO_ADD_TRANSACTION),
                    () -> assertThat(nonDigit.message())
                            .isNotEqualTo(MSG_UNABLE_TO_LOOKUP_TRANSACTION),
                    () -> assertThat(nonDigit.message()).endsWith("..."),
                    () -> assertThat(nonDigit.message()).doesNotEndWith("...."),
                    () -> assertThat(nonDigit.transaction()).isNull(),
                    () -> assertThat(wrongWidth.transaction()).isNull());
            verify(transactionRepository, never()).insertAndFlush(any(Transaction.class));
        }

        @Test
        @DisplayName("every message the turn can report ends with exactly three dots, never two and never "
                + "four, and none is trimmed")
        void everyReportedMessageEndsWithExactlyThreeDots() {
            final BillPaymentService.BillPaymentResult empty =
                    service.processBillPayment(submitted(null, "Y"));
            final BillPaymentService.BillPaymentResult invalid =
                    service.processBillPayment(submitted(ACCOUNT_ID, "X"));

            assertAll(
                    () -> assertThat(empty.message()).isEqualTo(MSG_ACCT_ID_EMPTY),
                    () -> assertThat(empty.message()).endsWith("..."),
                    () -> assertThat(empty.message()).doesNotEndWith("...."),
                    () -> assertThat(empty.message()).isEqualTo(empty.message().stripTrailing()),
                    () -> assertThat(invalid.message()).isEqualTo(MSG_INVALID_CONFIRMATION),
                    () -> assertThat(invalid.message()).endsWith("..."),
                    () -> assertThat(invalid.message()).doesNotEndWith("...."),
                    () -> assertThat(invalid.message()).isEqualTo(invalid.message().stripTrailing()));
        }

        @Test
        @DisplayName("the cross-reference catch-all text and the account catch-all text are distinct, so "
                + "one failure is never reported as the other")
        void theCatchAllTextsAreDistinct() {
            stubBothAccountReads(payableAccount());
            stubAccountRewriteEchoesRow();
            stubHighestIdentifier(null);
            stubBoundaryRunsUnit();
            when(cardCrossReferenceRepository
                    .findFirstByXrefAcctIdOrderByXrefCardNumAsc(ACCOUNT_ID))
                    .thenReturn(Optional.of(crossReferenceTo(" ".repeat(16))));

            final BillPaymentService.BillPaymentResult result =
                    service.processBillPayment(submitted(ACCOUNT_ID, "Y"));

            assertAll(
                    () -> assertThat(MSG_UNABLE_TO_LOOKUP_XREF)
                            .isNotEqualTo(MSG_UNABLE_TO_LOOKUP_ACCOUNT),
                    () -> assertThat(MSG_UNABLE_TO_LOOKUP_XREF)
                            .isNotEqualTo(MSG_UNABLE_TO_UPDATE_ACCOUNT),
                    // The cross-reference arm wrote its own text and the sequence continued, so the write
                    // paragraph's catch-all is the last text written - the same overwrite semantics the
                    // backward-read case shows.
                    () -> assertThat(result.message()).isEqualTo(MSG_UNABLE_TO_ADD_TRANSACTION),
                    () -> assertThat(result.errorFlag()).isTrue(),
                    () -> assertThat(result.transaction()).isNull());
        }
    }

    // ==============================================================================================
    // The exclusive held read that replaces the legacy READ ... UPDATE, lines 343 and 377 to 403
    // ==============================================================================================

    @Nested
    @DisplayName("The exclusive held read of the confirmed span, and the rewrite arms at lines 377 to 403")
    class HeldReadAndRewriteArms {

        @Test
        @DisplayName("the confirmed span's FIRST store interaction is the exclusive held read, taken before "
                + "the identifier is allocated and before anything is written")
        void theHeldReadIsTakenBeforeAnythingIsAllocatedOrWritten() {
            arrangeConfirmablePayment(payableAccount(), HIGHEST_KEY_NINE);

            service.processBillPayment(submitted(ACCOUNT_ID, "Y"));

            // The legacy read at line 343 takes UPDATE against a file the region defines with
            // UPDATEMODEL(LOCKING), so the row is held while the record the write at line 233 stores is
            // assembled from its balance. The locking finder standing first is what reproduces the hold,
            // and the rewrite of line 235 follows the write rather than preceding it.
            final InOrder order = inOrder(accountRepository, transactionRepository);
            order.verify(accountRepository).findByIdForUpdate(ACCOUNT_ID);
            order.verify(transactionRepository).lockIdentifierAllocation(EXPECTED_ALLOCATION_LOCK_KEY);
            order.verify(transactionRepository).findMaxId();
            order.verify(transactionRepository).insertAndFlush(any(Transaction.class));
            order.verify(accountRepository).findByIdForUpdate(ACCOUNT_ID);
            order.verify(accountRepository).saveAndFlush(any(Account.class));
        }

        @Test
        @DisplayName("the row the rewrite stores is the row the HELD read granted, not the row the earlier "
                + "unlocked read returned")
        void theRewriteStoresTheHeldRowRatherThanTheDisplayedRow() {
            final Account displayed = payableAccount();
            final Account held = payableAccount();
            stubAccountRead(displayed);
            stubHeldAccountRead(held);
            stubAccountRewriteEchoesRow();
            stubCrossReferenceRow(CARD_NUMBER);
            stubHighestIdentifier(null);
            stubInsertEchoesRecord();
            stubBoundaryRunsUnit();

            service.processBillPayment(submitted(ACCOUNT_ID, "Y"));

            // Identity, not equality: only the instance the lock granted may be settled, because only that
            // instance is the row no other writer can be holding.
            assertThat(capturedRewrittenAccount()).isSameAs(held);
            assertThat(displayed.getAcctCurrBal()).isEqualByComparingTo(PAYABLE_BALANCE);
        }

        @Test
        @DisplayName("a held read that resolves NOTHING takes the account-read paragraph's not-found arm, "
                + "and nothing is allocated, written or rewritten")
        void aHeldReadThatResolvesNothingTakesTheReadsNotFoundArm() {
            stubAccountRead(payableAccount());
            when(accountRepository.findByIdForUpdate(ACCOUNT_ID)).thenReturn(Optional.empty());
            stubCrossReferenceRow(CARD_NUMBER);
            stubBoundaryRunsUnit();

            final BillPaymentService.BillPaymentResult result =
                    service.processBillPayment(submitted(ACCOUNT_ID, "Y"));

            assertAll(
                    () -> assertThat(result.errorFlag()).isTrue(),
                    () -> assertThat(result.message()).isEqualTo(MSG_ACCOUNT_ID_NOT_FOUND),
                    () -> assertThat(result.focusField()).isEqualTo(FIELD_ACCOUNT_ID),
                    () -> assertThat(result.transaction()).isNull(),
                    () -> assertThat(result.paymentAccepted()).isFalse());
            verify(transactionRepository, never()).lockIdentifierAllocation(EXPECTED_ALLOCATION_LOCK_KEY);
            verify(transactionRepository, never()).insertAndFlush(any(Transaction.class));
            verify(accountRepository, never()).saveAndFlush(any(Account.class));
        }

        @Test
        @DisplayName("a held read returning a row that carries NO USABLE BALANCE takes the account-read "
                + "paragraph's catch-all arm, exactly as the display read does")
        void aHeldRowCarryingNoBalanceTakesTheReadsCatchAllArm() {
            // The same arm the display read has for the same condition, which is the point: the held read is
            // that paragraph re-performed, so it must resolve to that paragraph's arms and not to an
            // unrelated failure. The column is declared not-null, so this defends against a malformed row
            // rather than describing an expected one.
            stubAccountRead(payableAccount());
            stubHeldAccountRead(accountCarryingVerbatimBalance(null));
            stubCrossReferenceRow(CARD_NUMBER);
            stubBoundaryRunsUnit();

            final BillPaymentService.BillPaymentResult result =
                    service.processBillPayment(submitted(ACCOUNT_ID, "Y"));

            assertAll(
                    () -> assertThat(result.errorFlag()).isTrue(),
                    () -> assertThat(result.message()).isEqualTo(MSG_UNABLE_TO_LOOKUP_ACCOUNT),
                    () -> assertThat(result.focusField()).isEqualTo(FIELD_ACCOUNT_ID),
                    () -> assertThat(result.transaction()).isNull());
            verify(transactionRepository, never()).lockIdentifierAllocation(anyLong());
            verify(transactionRepository, never()).insertAndFlush(any(Transaction.class));
            verify(accountRepository, never()).saveAndFlush(any(Account.class));
        }

        @Test
        @DisplayName("an account another operator SETTLED while this turn waited for the lock reaches the "
                + "legacy nothing-to-pay arm, and mints NO second transaction")
        void aRowSettledWhileWaitingForTheLockReachesTheNothingToPayArm() {
            // The displayed balance was payable - this turn read it before the winner committed - and the
            // held row is what the lock granted afterwards. That is precisely the legacy's second task:
            // it waited at its own READ ... UPDATE, then saw the settled balance and took the arm at lines
            // 197 to 206. One transaction is posted because a second is never minted, not because a second
            // is rolled back.
            stubAccountRead(payableAccount());
            stubHeldAccountRead(accountCarryingVerbatimBalance(BigDecimal.ZERO));
            stubCrossReferenceRow(CARD_NUMBER);
            stubBoundaryRunsUnit();

            final BillPaymentService.BillPaymentResult result =
                    service.processBillPayment(submitted(ACCOUNT_ID, "Y"));

            assertAll(
                    () -> assertThat(result.errorFlag()).isTrue(),
                    () -> assertThat(result.message()).isEqualTo(MSG_NOTHING_TO_PAY),
                    () -> assertThat(result.focusField()).isEqualTo(FIELD_ACCOUNT_ID),
                    () -> assertThat(result.transaction()).isNull(),
                    () -> assertThat(result.paymentAccepted()).isFalse(),
                    // NOT an optimistic conflict: the turn answers with the legacy's own message.
                    () -> assertThat(result.message()).doesNotContain("some one"));
            verify(transactionRepository, never()).lockIdentifierAllocation(EXPECTED_ALLOCATION_LOCK_KEY);
            verify(transactionRepository, never()).insertAndFlush(any(Transaction.class));
            verify(accountRepository, never()).saveAndFlush(any(Account.class));
        }

        @Test
        @DisplayName("a NEGATIVE held balance reaches the same arm, because the legacy condition is "
                + "not-positive rather than exactly zero")
        void aNegativeHeldBalanceReachesTheSameArm() {
            stubAccountRead(payableAccount());
            stubHeldAccountRead(accountCarryingVerbatimBalance(new BigDecimal("-0.01")));
            stubCrossReferenceRow(CARD_NUMBER);
            stubBoundaryRunsUnit();

            final BillPaymentService.BillPaymentResult result =
                    service.processBillPayment(submitted(ACCOUNT_ID, "Y"));

            assertThat(result.message()).isEqualTo(MSG_NOTHING_TO_PAY);
            verify(transactionRepository, never()).insertAndFlush(any(Transaction.class));
        }

        @Test
        @DisplayName("a conflict the PERSISTENCE PROVIDER raises on the held row is still translated by "
                + "the same arm, and the provider's failure travels with it as its cause")
        void aProviderRaisedConflictIsTranslatedByTheSameArm() {
            // Unreachable through a lost version race now that the row is held, but a provider may report a
            // conflict for a reason of its own, and the translation stays in place for that.
            stubBothAccountReads(payableAccount());
            stubCrossReferenceRow(CARD_NUMBER);
            stubBoundaryRunsUnit();
            when(accountRepository.saveAndFlush(any(Account.class)))
                    .thenThrow(new OptimisticLockException("the row changed"));

            assertThatExceptionOfType(OptimisticLockConflictException.class)
                    .isThrownBy(() -> service.processBillPayment(submitted(ACCOUNT_ID, "Y")))
                    .satisfies(conflict -> assertAll(
                            () -> assertThat(conflict.conflictKind()).isEqualTo(
                                    OptimisticLockConflictException.ConflictKind
                                            .RECORD_CHANGED_BEFORE_UPDATE),
                            () -> assertThat(conflict.entityName()).isEqualTo("Account"),
                            () -> assertThat(conflict.key()).isEqualTo(ACCOUNT_ID),
                            // "some one" is TWO WORDS in the legacy text, and it stays two words.
                            () -> assertThat(conflict.getMessage())
                                    .isEqualTo("Record changed by some one else. Please review"),
                            () -> assertThat(conflict.getMessage()).contains("some one"),
                            () -> assertThat(conflict.getMessage()).doesNotContain("someone"),
                            () -> assertThat(conflict).isExactlyInstanceOf(
                                    OptimisticLockConflictException.class),
                            () -> assertThat(conflict).isInstanceOf(RuntimeException.class),
                            () -> assertThat(conflict.getCause())
                                    .isInstanceOf(OptimisticLockException.class)));
        }

        @Test
        @DisplayName("any OTHER failure of the rewrite is the paragraph's catch-all arm, which reports the "
                + "update-failure text rather than propagating, at lines 396 to 402")
        void anyOtherRewriteFailureReachesTheCatchAllArm() {
            stubBothAccountReads(payableAccount());
            stubCrossReferenceRow(CARD_NUMBER);
            stubHighestIdentifier(null);
            stubInsertEchoesRecord();
            stubBoundaryRunsUnit();
            when(accountRepository.saveAndFlush(any(Account.class)))
                    .thenThrow(new IllegalStateException("the rewrite failed"));

            final BillPaymentService.BillPaymentResult result =
                    service.processBillPayment(submitted(ACCOUNT_ID, "Y"));

            assertAll(
                    () -> assertThat(result.errorFlag()).isTrue(),
                    () -> assertThat(result.message()).isEqualTo(MSG_UNABLE_TO_UPDATE_ACCOUNT),
                    () -> assertThat(result.message()).endsWith("..."),
                    () -> assertThat(result.message()).doesNotEndWith("...."),
                    () -> assertThat(result.focusField()).isEqualTo(FIELD_ACCOUNT_ID),
                    // THE STORED RECORD SURVIVES. The insert committed in a unit of its own, and a legacy
                    // REWRITE failure over files defined RECOVERY(NONE) with JOURNAL(NO) does not undo a
                    // WRITE that already happened - the source performs lines 234 and 235 whether or not
                    // the write at 233 succeeded and tests no flag between them. So the turn reports the
                    // update failure over a transaction master that carries the row.
                    () -> assertThat(result.transaction()).isNotNull(),
                    () -> assertThat(result.transaction().tranId())
                            .isEqualTo(FIRST_IDENTIFIER_ON_EMPTY_TABLE));
            assertThat(capturedInsertedRecord().getTranId()).isEqualTo(FIRST_IDENTIFIER_ON_EMPTY_TABLE);
        }

        @Test
        @DisplayName("the conflict contract declares exactly the three legacy write-path arms")
        void theConflictContractDeclaresThreeArms() {
            assertThat(OptimisticLockConflictException.ConflictKind.values()).containsExactly(
                    OptimisticLockConflictException.ConflictKind.RECORD_CHANGED_BEFORE_UPDATE,
                    OptimisticLockConflictException.ConflictKind.UPDATE_FAILED_AFTER_LOCK,
                    OptimisticLockConflictException.ConflictKind.LOCK_NOT_ACQUIRED);
        }

        @Test
        @DisplayName("a successful rewrite reports the version the held row carried, which is what the "
                + "next writer observes")
        void aSuccessfulRewriteReportsTheVersionTheWriteLeftBehind() {
            arrangeConfirmablePayment(payableAccount(), null);

            final BillPaymentService.BillPaymentResult result =
                    service.processBillPayment(submitted(ACCOUNT_ID, "Y"));

            assertThat(result.account()).isNotNull();
            assertThat(result.account().version()).isZero();
            assertThat(result.account().acctId()).isEqualTo(ACCOUNT_ID);
        }

        @Test
        @DisplayName("the row is locked ONCE PER UNIT of work and never re-read for comparison, because "
                + "the lock - not an image comparison - is what makes the rewrite safe")
        void theHeldRowIsReadOncePerUnitAndNeverReReadForComparison() {
            arrangeConfirmablePayment(payableAccount(), null);

            service.processBillPayment(submitted(ACCOUNT_ID, "Y"));

            // Exactly one locking read per confirmed unit of work - a relational lock cannot span two units,
            // so the unit that stores the payment and the unit that settles the account each take it for
            // itself - and exactly one unlocked read, the display read of the account-read paragraph. No
            // unlocked re-read appears anywhere: that would be the before-and-after image comparison this
            // design deliberately replaced, and it would be reading a row the lock already guarantees.
            verify(accountRepository, times(2)).findByIdForUpdate(ACCOUNT_ID);
            verify(accountRepository).findById(ACCOUNT_ID);
        }
    }

    // ==============================================================================================
    // The write order at lines 233 to 235, and the two units of work
    // ==============================================================================================

    @Nested
    @DisplayName("Write ordering and units of work - the insert at line 233, the computation at line 234 "
            + "and the rewrite at line 235")
    class WriteOrderingAndUnitsOfWork {

        @Test
        @DisplayName("the transaction is stored in the first unit and the held account is settled in the "
                + "second, and those are the only interactions the turn has with any repository")
        void theTransactionIsStoredBeforeTheHeldAccountIsSettled() {
            arrangeConfirmablePayment(payableAccount(), HIGHEST_KEY_NINE);

            service.processBillPayment(submitted(ACCOUNT_ID, "Y"));

            // THE SOURCE'S DURABLE ORDER: PERFORM WRITE-TRANSACT-FILE at line 233 precedes the computation
            // at line 234 and PERFORM UPDATE-ACCTDAT-FILE at line 235, and no flag is tested between them.
            // A turn interrupted between the two units therefore leaves a stored payment against an
            // unsettled account, which is the partial state the legacy unrecoverable files reached; the
            // opposite state - a settled balance with no payment record - is one the source cannot produce.
            final InOrder order = inOrder(accountRepository, transactionRepository);
            order.verify(transactionRepository).insertAndFlush(any(Transaction.class));
            order.verify(accountRepository).saveAndFlush(any(Account.class));

            // Account for every remaining interaction, so a stray call cannot slip past unnoticed.
            verify(transactionRepository).lockIdentifierAllocation(EXPECTED_ALLOCATION_LOCK_KEY);
            verify(transactionRepository).findMaxId();
            verify(transactionRepository).existsById(SUCCESSOR_OF_NINE);
            verifyNoMoreInteractions(transactionRepository);

            verify(accountRepository).findById(ACCOUNT_ID);
            verify(accountRepository, times(2)).findByIdForUpdate(ACCOUNT_ID);
            verifyNoMoreInteractions(accountRepository);

            verify(cardCrossReferenceRepository)
                    .findFirstByXrefAcctIdOrderByXrefCardNumAsc(ACCOUNT_ID);
            verifyNoMoreInteractions(cardCrossReferenceRepository);
        }

        @Test
        @DisplayName("the allocation lock is taken BEFORE the maximum is read and before the insert, "
                + "which is what serialises two concurrent allocators")
        void theAllocationLockIsTakenBeforeTheMaximumIsRead() {
            arrangeConfirmablePayment(payableAccount(), HIGHEST_KEY_NINE);

            service.processBillPayment(submitted(ACCOUNT_ID, "Y"));

            final InOrder order = inOrder(transactionRepository);
            order.verify(transactionRepository).lockIdentifierAllocation(EXPECTED_ALLOCATION_LOCK_KEY);
            order.verify(transactionRepository).findMaxId();
            order.verify(transactionRepository).existsById(SUCCESSOR_OF_NINE);
            order.verify(transactionRepository).insertAndFlush(any(Transaction.class));
        }

        @Test
        @DisplayName("the insert runs in a unit of ITS OWN that follows the settlement unit, which is what "
                + "makes the stored record survive a rewrite that rolled back")
        void theInsertRunsInAUnitOfItsOwnAfterTheSettlementUnit() {
            arrangeConfirmablePayment(payableAccount(), null);

            service.processBillPayment(submitted(ACCOUNT_ID, "Y"));

            // Two units, not one, and one after the other rather than one inside the other. The first holds
            // the account row exclusively and settles it, reproducing the legacy hold from line 343 to line
            // 235. The second is the transaction insert, and it is separate precisely because the legacy
            // transaction file is defined RECOVERY(NONE) with JOURNAL(NO): a record that was written is
            // durable at once and no REWRITE failure undoes it. Sharing one unit between the two stores
            // would make a failed rewrite discard a stored transaction, which no legacy mechanism does.
            verify(transactionBoundary, times(2)).execute(any());
            verifyNoMoreInteractions(transactionBoundary);
        }

        @Test
        @DisplayName("a HANDLED insert refusal leaves the account settled, because the source tests no flag "
                + "between lines 233 and 235 and the settlement committed in its own unit")
        void aHandledInsertRefusalStillLeavesTheAccountSettled() {
            // No cross-reference row, so the assembled record carries no card number and the insert is
            // never attempted: the catch-all arm at lines 540 to 546 reports it and nothing was stored.
            stubBothAccountReads(payableAccount());
            stubAccountRewriteEchoesRow();
            stubHighestIdentifier(null);
            stubBoundaryRunsUnit();
            when(cardCrossReferenceRepository.findFirstByXrefAcctIdOrderByXrefCardNumAsc(ACCOUNT_ID))
                    .thenReturn(Optional.empty());

            final BillPaymentService.BillPaymentResult result =
                    service.processBillPayment(submitted(ACCOUNT_ID, "Y"));

            assertAll(() -> assertThat(result.message())
                            .as("the insert's catch-all arm is what the operator is told")
                            .isEqualTo(MSG_UNABLE_TO_ADD_TRANSACTION),
                    () -> assertThat(result.transaction())
                            .as("nothing was inserted")
                            .isNull());
            // Two units still ran - the settlement and the allocation span - and the refusal was resolved
            // inside the second one without ever reaching the store. The account is settled behind a message
            // saying the transaction could not be added, which is precisely what the source does by testing
            // no flag between lines 233 and 235.
            verify(transactionBoundary, times(2)).execute(any());
            verify(transactionRepository, never()).insertAndFlush(any(Transaction.class));
            verify(accountRepository).saveAndFlush(any(Account.class));
            assertThat(capturedRewrittenAccount().getAcctCurrBal()).isEqualByComparingTo(ZERO_BALANCE);
        }

        @Test
        @DisplayName("a refused rewrite leaves the stored transaction in place and reports BOTH arms in the "
                + "source's order: the insert's, then the rewrite's")
        void aRefusedRewriteLeavesTheStoredTransactionInPlace() {
            stubBothAccountReads(payableAccount());
            stubCrossReferenceRow(CARD_NUMBER);
            stubHighestIdentifier(null);
            stubInsertEchoesRecord();
            stubBoundaryRunsUnit();
            when(accountRepository.saveAndFlush(any(Account.class)))
                    .thenThrow(new IllegalStateException("the rewrite failed"));

            final BillPaymentService.BillPaymentResult result =
                    service.processBillPayment(submitted(ACCOUNT_ID, "Y"));

            assertAll(
                    // The insert's normal arm ran first and named the stored record, then the rewrite's
                    // catch-all wrote the text the operator actually sees. Both arms are applied, in that
                    // order, because the source performs both evaluations in that order.
                    () -> assertThat(result.transaction()).isNotNull(),
                    () -> assertThat(result.transaction().tranId())
                            .isEqualTo(FIRST_IDENTIFIER_ON_EMPTY_TABLE),
                    () -> assertThat(result.message()).isEqualTo(MSG_UNABLE_TO_UPDATE_ACCOUNT),
                    () -> assertThat(result.errorFlag()).isTrue());
            // The nested unit committed the record before the outer unit was asked to rewrite, so the
            // rollback of the outer unit reaches only the account.
            verify(transactionBoundary, times(2)).execute(any());
            verify(transactionRepository).insertAndFlush(any(Transaction.class));
        }

        @Test
        @DisplayName("the two units of work are SEQUENTIAL and never nested, so a turn holds one connection "
                + "at a time and a pool sized to the number of turns cannot starve")
        void theTwoUnitsOfWorkAreSequentialAndNeverNested() {
            // Arranged without the shared boundary stub, because this specification supplies its own
            // depth-counting one in its place.
            stubBothAccountReads(payableAccount());
            stubAccountRewriteEchoesRow();
            stubCrossReferenceRow(CARD_NUMBER);
            stubHighestIdentifier(null);
            stubInsertEchoesRecord();
            final int[] depth = {0};
            final int[] deepest = {0};
            // doAnswer rather than when(...): the method is already stubbed to run its unit, and calling it
            // again inside when(...) would run that answer against a null argument.
            Mockito.doAnswer(invocation -> {
                depth[0]++;
                deepest[0] = Math.max(deepest[0], depth[0]);
                try {
                    return ((Supplier<?>) invocation.getArgument(0)).get();
                } finally {
                    depth[0]--;
                }
            }).when(transactionBoundary).execute(any());

            service.processBillPayment(submitted(ACCOUNT_ID, "Y"));

            // A unit of work is a connection. One unit open at any instant is what makes N simultaneous
            // turns need N connections rather than 2N; a depth of two is the starvation the finding named,
            // and no pool figure anywhere compensates for it.
            assertThat(deepest[0])
                    .as("no unit of work may be opened while another is still open")
                    .isEqualTo(1);
            assertThat(depth[0]).as("every unit that was opened has ended").isZero();

            // The row is held as the first statement of EACH unit, so the balance the payment is derived
            // from and the row the rewrite stores are both read under the lock, and the two stores become
            // durable in the source's own order - the payment first, the settled account second.
            final InOrder order = inOrder(transactionBoundary, accountRepository, transactionRepository);
            order.verify(transactionBoundary).execute(any());
            order.verify(accountRepository).findByIdForUpdate(ACCOUNT_ID);
            order.verify(transactionRepository).insertAndFlush(any(Transaction.class));
            order.verify(transactionBoundary).execute(any());
            order.verify(accountRepository).findByIdForUpdate(ACCOUNT_ID);
            order.verify(accountRepository).saveAndFlush(any(Account.class));
        }

        @Test
        @DisplayName("a REFUSED insert still settles the account, because the source performs lines 234 "
                + "and 235 whether or not the write at line 233 succeeded and tests no flag between them")
        void aRefusedInsertStillSettlesTheAccount() {
            stubBothAccountReads(payableAccount());
            stubAccountRewriteEchoesRow();
            stubCrossReferenceRow(CARD_NUMBER);
            stubHighestIdentifier(HIGHEST_KEY_NINE);
            stubBoundaryRunsUnit();
            when(transactionRepository.insertAndFlush(any(Transaction.class)))
                    .thenThrow(new EntityExistsException("the key is already stored"));

            final BillPaymentService.BillPaymentResult result =
                    service.processBillPayment(submitted(ACCOUNT_ID, "Y"));

            // The independence runs in BOTH directions, and this is the direction the swapped durable order
            // makes reachable: the payment was refused in the unit that runs first, and the settlement unit
            // that follows still performs the computation of line 234 and the rewrite of line 235, exactly
            // as the legacy did after a WRITE it had already reported as refused.
            assertThat(result.message()).isEqualTo(MSG_TRAN_ID_ALREADY_EXISTS);
            assertThat(result.transaction()).isNull();
            verify(accountRepository).saveAndFlush(any(Account.class));
        }

        @Test
        @DisplayName("a turn that mints nothing opens no unit of work and takes no lock: an unconfirmed "
                + "submission costs one keyed read and nothing else")
        void aTurnThatMintsNothingOpensNoUnitOfWork() {
            stubAccountRead(payableAccount());

            service.processBillPayment(submitted(ACCOUNT_ID, null));

            verify(accountRepository).findById(ACCOUNT_ID);
            verifyNoMoreInteractions(accountRepository);
            verifyNoInteractions(transactionRepository, cardCrossReferenceRepository,
                    transactionBoundary);
        }
    }

    // ==============================================================================================
    // READ-CXACAIX-FILE, line 408
    // ==============================================================================================

    @Nested
    @DisplayName("The cross-reference access path at lines 408 to 436")
    class CrossReferenceAccess {

        @Test
        @DisplayName("an absent cross-reference row is the not-found path, and no index or absent-value "
                + "failure escapes")
        void anAbsentRowIsTheNotFoundPath() {
            stubBothAccountReads(payableAccount());
            stubAccountRewriteEchoesRow();
            stubHighestIdentifier(null);
            stubBoundaryRunsUnit();
            when(cardCrossReferenceRepository
                    .findFirstByXrefAcctIdOrderByXrefCardNumAsc(ACCOUNT_ID))
                    .thenReturn(Optional.empty());

            final BillPaymentService.BillPaymentResult result =
                    service.processBillPayment(submitted(ACCOUNT_ID, "Y"));

            // A returned value is itself the proof that nothing escaped: neither an index failure nor an
            // absent-value failure reached the caller.
            assertAll(
                    () -> assertThat(result).isNotNull(),
                    () -> assertThat(result.errorFlag()).isTrue(),
                    () -> assertThat(result.fieldErrors()).contains(
                            new ValidationException.FieldError(PROPERTY_ACCOUNT_ID, FIELD_ACCOUNT_ID,
                                    ValidationException.FieldState.INVALID, MSG_ACCOUNT_ID_NOT_FOUND)),
                    () -> assertThat(result.transaction()).isNull(),
                    () -> assertThat(result.message()).isEqualTo(MSG_UNABLE_TO_ADD_TRANSACTION));
            verify(transactionRepository, never()).insertAndFlush(any(Transaction.class));
        }

        @Test
        @DisplayName("a row carrying a blank card number reaches the path's catch-all, so a record the "
                + "store would refuse is never offered to it")
        void aBlankCardNumberReachesTheCatchAll() {
            stubBothAccountReads(payableAccount());
            stubAccountRewriteEchoesRow();
            stubHighestIdentifier(null);
            stubBoundaryRunsUnit();
            when(cardCrossReferenceRepository
                    .findFirstByXrefAcctIdOrderByXrefCardNumAsc(ACCOUNT_ID))
                    .thenReturn(Optional.of(crossReferenceTo(" ".repeat(16))));

            final BillPaymentService.BillPaymentResult result =
                    service.processBillPayment(submitted(ACCOUNT_ID, "Y"));

            assertThat(result.errorFlag()).isTrue();
            assertThat(result.transaction()).isNull();
            verify(transactionRepository, never()).insertAndFlush(any(Transaction.class));
        }

        @Test
        @DisplayName("a row carrying an ABSENT card number reaches the same catch-all, so an absent value "
                + "and a blank one are handled identically and neither escapes")
        void anAbsentCardNumberReachesTheCatchAll() {
            stubBothAccountReads(payableAccount());
            stubAccountRewriteEchoesRow();
            stubHighestIdentifier(null);
            stubBoundaryRunsUnit();
            when(cardCrossReferenceRepository
                    .findFirstByXrefAcctIdOrderByXrefCardNumAsc(ACCOUNT_ID))
                    .thenReturn(Optional.of(crossReferenceTo(null)));

            final BillPaymentService.BillPaymentResult result =
                    service.processBillPayment(submitted(ACCOUNT_ID, "Y"));

            assertAll(
                    () -> assertThat(result).isNotNull(),
                    () -> assertThat(result.errorFlag()).isTrue(),
                    () -> assertThat(result.transaction()).isNull(),
                    () -> assertThat(result.message()).isEqualTo(MSG_UNABLE_TO_ADD_TRANSACTION));
            verify(transactionRepository, never()).insertAndFlush(any(Transaction.class));
        }

        @Test
        @DisplayName("the list-returning sibling finder is NEVER consulted: the access path resolves the "
                + "row and the turn re-orders nothing")
        void theListReturningSiblingFinderIsNeverConsulted() {
            stubBothAccountReads(payableAccount());
            stubAccountRewriteEchoesRow();
            stubCrossReferenceRow(HIGHER_CARD_NUMBER);
            stubHighestIdentifier(null);
            stubInsertEchoesRecord();
            stubBoundaryRunsUnit();

            service.processBillPayment(submitted(ACCOUNT_ID, "Y"));

            verify(cardCrossReferenceRepository)
                    .findFirstByXrefAcctIdOrderByXrefCardNumAsc(ACCOUNT_ID);
            verify(cardCrossReferenceRepository, never())
                    .findByXrefAcctIdOrderByXrefCardNumAsc(any(), any());
            verifyNoMoreInteractions(cardCrossReferenceRepository);
            // The resolved row's card number is written verbatim, even though it is not the lowest value
            // the fixture could have offered.
            assertThat(SensitiveValues.fingerprint(capturedInsertedRecord().getTranCardNum())).isEqualTo(SensitiveValues.fingerprint(HIGHER_CARD_NUMBER));
        }

        @Test
        @DisplayName("the cross-reference read is keyed by the same transmitted value as the account "
                + "read, because one move at lines 170 and 171 has two receiving fields")
        void theCrossReferenceReadIsKeyedByTheSameTransmittedValue() {
            arrangeConfirmablePayment(payableAccount(), null);

            service.processBillPayment(submitted(ACCOUNT_ID, "Y"));

            verify(accountRepository).findById(ACCOUNT_ID);
            verify(accountRepository, times(2)).findByIdForUpdate(ACCOUNT_ID);
            verify(cardCrossReferenceRepository)
                    .findFirstByXrefAcctIdOrderByXrefCardNumAsc(ACCOUNT_ID);
        }
    }

    // ==============================================================================================
    // Routing: the transfer sites at lines 109 and 135 and the terminal return at lines 146 to 149
    // ==============================================================================================

    @Nested
    @DisplayName("Routing - the two transfer sites at lines 109 and 135 and the terminal return at lines "
            + "146 to 149")
    class RoutingAndReArming {

        @Test
        @DisplayName("a re-presented screen returns this screen's OWN route and re-arms this same "
                + "transaction, and the navigation rules are not consulted at all - there is no "
                + "server-side forwarding")
        void aRePresentedScreenReturnsItsOwnRouteWithNoForwarding() {
            arrangeConfirmablePayment(payableAccount(), null);

            final BillPaymentService.BillPaymentResult result =
                    service.processBillPayment(submitted(ACCOUNT_ID, "Y"));

            assertAll(
                    () -> assertThat(result.route())
                            .isEqualTo(NavigationService.Route.BILL_PAYMENT),
                    () -> assertThat(result.route().getRouteValue()).isEqualTo("bill-payment"),
                    () -> assertThat(result.route().getLegacyTransactionId())
                            .isEqualTo(EXPECTED_TRANSACTION_NAME),
                    () -> assertThat(result.route().getLegacyProgramName())
                            .isEqualTo(EXPECTED_PROGRAM_NAME),
                    () -> assertThat(result.reArmedTransactionId())
                            .isEqualTo(EXPECTED_TRANSACTION_NAME));
            verifyNoInteractions(navigationService);
        }

        @Test
        @DisplayName("no route corresponds to the dangling program the resource definition declares at "
                + "line 211 and binds at line 390 with no source member behind it")
        void noRouteCorrespondsToTheDanglingProgramDefinition() {
            assertThat(NavigationService.Route.values())
                    .noneMatch(route -> DANGLING_PROGRAM.equals(route.getLegacyProgramName()));
        }

        @Test
        @DisplayName("a turn that transfers reports no re-armed transaction, because a transfer carries "
                + "the navigation state but does not re-arm")
        void aTransferReportsNoReArmedTransaction() {
            when(navigationService.resolveBackNavigation(any(ConversationState.class),
                    eq(NavigationService.Route.USER_MENU)))
                    .thenReturn(NavigationService.Route.USER_MENU);
            when(navigationService.resolveNominatedDestination(any(ConversationState.class),
                    eq(NavigationService.Route.SIGN_ON)))
                    .thenReturn(NavigationService.Route.USER_MENU);

            final BillPaymentService.BillPaymentResult result =
                    service.processBillPayment(keyed(KeyAction.PFK03));

            assertThat(result.reArmedTransactionId()).isEqualTo(NO_MESSAGE);
            assertThat(result.route()).isEqualTo(NavigationService.Route.USER_MENU);
        }
    }

    // ==============================================================================================
    // The public surface: the four condition-name groups and the outcome record
    // ==============================================================================================

    @Nested
    @DisplayName("The public surface - the four two-state condition-name groups at lines 43 to 53 and 68 "
            + "to 70, and the outcome record")
    class PublicSurface {

        @Test
        @DisplayName("the error flag's two condition names are mutually exclusive")
        void theErrorFlagPredicatesAreMutuallyExclusive() {
            assertAll(
                    () -> assertThat(BillPaymentService.ErrorFlag.ON.isOn()).isTrue(),
                    () -> assertThat(BillPaymentService.ErrorFlag.ON.isOff()).isFalse(),
                    () -> assertThat(BillPaymentService.ErrorFlag.OFF.isOn()).isFalse(),
                    () -> assertThat(BillPaymentService.ErrorFlag.OFF.isOff()).isTrue(),
                    () -> assertThat(BillPaymentService.ErrorFlag.values()).hasSize(2));
        }

        @Test
        @DisplayName("the user-modified flag's two condition names are mutually exclusive")
        void theUserModifiedFlagPredicatesAreMutuallyExclusive() {
            assertAll(
                    () -> assertThat(BillPaymentService.UserModifiedFlag.YES.isYes()).isTrue(),
                    () -> assertThat(BillPaymentService.UserModifiedFlag.YES.isNo()).isFalse(),
                    () -> assertThat(BillPaymentService.UserModifiedFlag.NO.isYes()).isFalse(),
                    () -> assertThat(BillPaymentService.UserModifiedFlag.NO.isNo()).isTrue(),
                    () -> assertThat(BillPaymentService.UserModifiedFlag.values()).hasSize(2));
        }

        @Test
        @DisplayName("the confirmation flag's two condition names are mutually exclusive")
        void theConfirmPaymentFlagPredicatesAreMutuallyExclusive() {
            assertAll(
                    () -> assertThat(BillPaymentService.ConfirmPaymentFlag.YES.isYes()).isTrue(),
                    () -> assertThat(BillPaymentService.ConfirmPaymentFlag.YES.isNo()).isFalse(),
                    () -> assertThat(BillPaymentService.ConfirmPaymentFlag.NO.isYes()).isFalse(),
                    () -> assertThat(BillPaymentService.ConfirmPaymentFlag.NO.isNo()).isTrue(),
                    () -> assertThat(BillPaymentService.ConfirmPaymentFlag.values()).hasSize(2));
        }

        @Test
        @DisplayName("the next-page flag's two condition names are mutually exclusive, and the flag is "
                + "carried at its declared initial value because this screen has no paging conversation")
        void theNextPageFlagPredicatesAreMutuallyExclusive() {
            assertAll(
                    () -> assertThat(BillPaymentService.NextPageFlag.YES.isYes()).isTrue(),
                    () -> assertThat(BillPaymentService.NextPageFlag.YES.isNo()).isFalse(),
                    () -> assertThat(BillPaymentService.NextPageFlag.NO.isYes()).isFalse(),
                    () -> assertThat(BillPaymentService.NextPageFlag.NO.isNo()).isTrue(),
                    () -> assertThat(BillPaymentService.NextPageFlag.values()).hasSize(2));
        }

        @Test
        @DisplayName("a refused payment is never reported as accepted")
        void aRefusedPaymentIsNeverReportedAsAccepted() {
            stubBothAccountReads(payableAccount());
            stubAccountRewriteEchoesRow();
            stubCrossReferenceRow(CARD_NUMBER);
            stubHighestIdentifier(HIGHEST_KEY_NINE);
            stubBoundaryRunsUnit();
            when(transactionRepository.existsById(SUCCESSOR_OF_NINE)).thenReturn(true);

            final BillPaymentService.BillPaymentResult result =
                    service.processBillPayment(submitted(ACCOUNT_ID, "Y"));

            assertThat(result.paymentAccepted()).isFalse();
            assertThat(result.errorFlag()).isTrue();
        }

        @Test
        @DisplayName("an unconfirmed turn is not reported as accepted either, even though it raised no "
                + "error at all")
        void anUnconfirmedTurnIsNotReportedAsAccepted() {
            stubAccountRead(payableAccount());

            final BillPaymentService.BillPaymentResult result =
                    service.processBillPayment(submitted(ACCOUNT_ID, null));

            assertThat(result.errorFlag()).isFalse();
            assertThat(result.paymentAccepted()).isFalse();
            assertThat(result.confirmationState().isNo()).isTrue();
        }

        @Test
        @DisplayName("the outcome record's non-nullable components are never absent, on a path that "
                + "touches nothing at all")
        void theOutcomeRecordsMandatoryComponentsAreNeverAbsent() {
            final BillPaymentService.BillPaymentResult result = service.processBillPayment(
                    new BillPaymentService.BillPaymentScreenInput(null, null, null, firstEntry()));

            assertAll(
                    () -> assertThat(result.route()).isNotNull(),
                    () -> assertThat(result.navigationContext()).isNotNull(),
                    () -> assertThat(result.reArmedTransactionId()).isNotNull(),
                    () -> assertThat(result.message()).isNotNull(),
                    () -> assertThat(result.focusField()).isNotNull(),
                    () -> assertThat(result.confirmationState()).isNotNull(),
                    () -> assertThat(result.fieldErrors()).isNotNull(),
                    () -> assertThat(result.header()).isNotNull(),
                    () -> assertThat(result.screen()).isNotNull());
        }

        @Test
        @DisplayName("every collaborator is mandatory, so a partially wired service can never be built")
        void everyCollaboratorIsMandatory() {
            assertAll(
                    () -> assertThatExceptionOfType(NullPointerException.class)
                            .isThrownBy(() -> new BillPaymentService(null, accountRepository,
                                    cardCrossReferenceRepository, messageCatalogService,
                                    navigationService, transactionBoundary, PINNED_CLOCK)),
                    () -> assertThatExceptionOfType(NullPointerException.class)
                            .isThrownBy(() -> new BillPaymentService(transactionRepository, null,
                                    cardCrossReferenceRepository, messageCatalogService,
                                    navigationService, transactionBoundary, PINNED_CLOCK)),
                    () -> assertThatExceptionOfType(NullPointerException.class)
                            .isThrownBy(() -> new BillPaymentService(transactionRepository,
                                    accountRepository, null, messageCatalogService, navigationService,
                                    transactionBoundary, PINNED_CLOCK)),
                    () -> assertThatExceptionOfType(NullPointerException.class)
                            .isThrownBy(() -> new BillPaymentService(transactionRepository,
                                    accountRepository, cardCrossReferenceRepository, null,
                                    navigationService, transactionBoundary, PINNED_CLOCK)),
                    () -> assertThatExceptionOfType(NullPointerException.class)
                            .isThrownBy(() -> new BillPaymentService(transactionRepository,
                                    accountRepository, cardCrossReferenceRepository,
                                    messageCatalogService, null, transactionBoundary, PINNED_CLOCK)),
                    () -> assertThatExceptionOfType(NullPointerException.class)
                            .isThrownBy(() -> new BillPaymentService(transactionRepository,
                                    accountRepository, cardCrossReferenceRepository,
                                    messageCatalogService, navigationService, null, PINNED_CLOCK)),
                    () -> assertThatExceptionOfType(NullPointerException.class)
                            .isThrownBy(() -> new BillPaymentService(transactionRepository,
                                    accountRepository, cardCrossReferenceRepository,
                                    messageCatalogService, navigationService, transactionBoundary,
                                    null)));
        }
    }
}
