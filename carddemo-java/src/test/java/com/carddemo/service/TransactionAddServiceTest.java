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
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.carddemo.domain.CardCrossReference;
import com.carddemo.domain.Transaction;
import com.carddemo.domain.enums.KeyAction;
import com.carddemo.repository.CardCrossReferenceRepository;
import com.carddemo.repository.TransactionRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

/**
 * Unit tests for {@link TransactionAddService}, transaction {@code CT02}, translated from
 * {@code app/cbl/COTRN02C.cbl}.
 *
 * <p>Five behaviours are pinned here because each is a contract that a plausible translation would get
 * wrong silently:
 *
 * <ol>
 *   <li><strong>The identifier is the highest existing key plus one.</strong> COBOL lines 444 to 451
 *       position the browse at high values, read backwards, add one and store the result into a
 *       {@code PIC 9(16)} field. An empty master seeds from zero, so the first identifier is one. A
 *       database sequence would diverge permanently after the first rolled-back turn.
 *   <li><strong>The amount lexeme is exactly twelve characters with a mandatory sign.</strong> The
 *       evaluation at lines 339 to 351 tests a sign that is "neither a minus nor a plus", so an unsigned
 *       amount is rejected rather than assumed positive.
 *   <li><strong>Whichever key field is supplied resolves the other.</strong> The first-match-wins arms at
 *       lines 196 and 210 mean a supplied account identifier wins and overwrites the card field with
 *       whatever the cross reference holds.
 *   <li><strong>The numeric test is the legacy's.</strong> Every character position of an alphanumeric
 *       field must hold a digit, so a value padded with spaces is not numeric.
 *   <li><strong>A declined confirmation and an unanswered one take the same arm.</strong> Lines 173 to
 *       181 conflate them, and the conflation is preserved rather than tidied.
 * </ol>
 *
 * <p>A pure unit test: the transaction master and the cross reference are doubles, the date validator,
 * message catalog and navigation service are real, and the clock is fixed so both timestamps are stable.
 *
 * <p>Provenance: checkout SHA {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19.
 *
 * @since 1.0.0
 */
@DisplayName("TransactionAddService :: transaction CT02, the transaction add screen")
final class TransactionAddServiceTest {

    /** A valid eleven-digit account identifier. */
    private static final String ACCOUNT_ID = "00000000011";

    /** A valid sixteen-digit card number. */
    private static final String CARD_NUMBER = "4111111111111111";

    /** A well-formed amount lexeme: sign, eight digits, point, two digits. */
    private static final String AMOUNT = "-00000100.00";

    /** A well-formed screen date. */
    private static final String ORIG_DATE = "2022-07-19";

    /** A second well-formed screen date. */
    private static final String PROC_DATE = "2022-07-20";

    /** The instant every turn is stamped with. */
    private static final Instant FIXED_INSTANT = Instant.parse("2022-07-19T23:12:33Z");

    private TransactionRepository transactionRepository;

    private CardCrossReferenceRepository crossReferenceRepository;

    private TransactionAddService service;

    @BeforeEach
    void constructService() {
        this.transactionRepository = Mockito.mock(TransactionRepository.class);
        this.crossReferenceRepository = Mockito.mock(CardCrossReferenceRepository.class);
        this.service = new TransactionAddService(new DateValidationService(),
                new MessageCatalogService(), new NavigationService(), this.transactionRepository,
                this.crossReferenceRepository, new OnlineTransactionBoundary(),
                Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC));
    }

    // ==============================================================================================
    // Fixtures
    // ==============================================================================================

    /** Stubs the cross reference so the account identifier resolves to the fixture card. */
    private void seedCrossReferenceByAccount() {
        Mockito.when(crossReferenceRepository
                        .findFirstByXrefAcctIdOrderByXrefCardNumAsc(ACCOUNT_ID))
                .thenReturn(Optional.of(new CardCrossReference(CARD_NUMBER, "000000011",
                        ACCOUNT_ID)));
    }

    /** Stubs the cross reference so the card number resolves to the fixture account. */
    private void seedCrossReferenceByCard() {
        Mockito.when(crossReferenceRepository.findById(CARD_NUMBER))
                .thenReturn(Optional.of(new CardCrossReference(CARD_NUMBER, "000000011",
                        ACCOUNT_ID)));
    }

    /**
     * Stubs the descending browse of the transaction master.
     *
     * @param highestKey the identifier the highest existing row carries, or {@code null} for an empty
     *                   master
     */
    private void seedHighestTransaction(final String highestKey) {
        Mockito.when(transactionRepository.findAll(ArgumentMatchers.any(Pageable.class)))
                .thenReturn(pageOf(highestKey));
        Mockito.when(transactionRepository.insertAndFlush(ArgumentMatchers.any(Transaction.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    /**
     * Builds the single-row page the descending browse returns.
     *
     * <p>Extracted from the stub above so that a test which needs the browse to answer <em>differently
     * on a second read</em> - which is what a re-allocation under the lock observes - can supply two
     * pages without restating the fixture row.
     *
     * @param highestKey the identifier the highest existing row carries, or {@code null} for an empty
     *                   master
     * @return a page holding that one row, or an empty page
     */
    private static PageImpl<Transaction> pageOf(final String highestKey) {
        final List<Transaction> rows = (highestKey == null)
                ? List.of()
                : List.of(new Transaction(highestKey, "01", "0001", "POS TERM", "PURCHASE",
                        new BigDecimal("10.00"), "000000001", "MERCHANT", "CITY", "10001",
                        CARD_NUMBER, "2022-07-19 00:00:00.000000", "2022-07-19 00:00:00.000000"));
        return new PageImpl<>(rows, PageRequest.of(0, 1), rows.size());
    }

    /** @return a carried state that reads as this screen submitting to itself */
    private static ScreenNavigationState reSubmission() {
        return new ScreenNavigationState("CT02", "COTRN02C", "CT02", "COTRN02C", "USER0001", "U",
                ScreenNavigationState.ProgramContext.REENTER, null, null, null, null, null, null, null,
                "COTRN2A", "COTRN02");
    }

    /**
     * Builds a fully populated, valid turn with the confirmation field as supplied.
     *
     * @param  confirm the confirmation field
     * @return the input
     */
    private static TransactionAddService.TransactionAddScreenInput validTurn(final String confirm) {
        return new TransactionAddService.TransactionAddScreenInput(ACCOUNT_ID, null, "01", "0001",
                "POS TERM", "PURCHASE AT MERCHANT", AMOUNT, ORIG_DATE, PROC_DATE, "000000001",
                "MERCHANT NAME", "MERCHANT CITY", "10001", confirm, null, KeyAction.ENTER,
                reSubmission());
    }

    /**
     * Builds a turn with one field replaced, using a small mutator so each edit test names only what it
     * changes.
     *
     * @param  base    the turn to copy
     * @param  amount  the amount lexeme to substitute, or {@code null} to keep the base's
     * @param  origin  the origination date to substitute, or {@code null} to keep the base's
     * @param  process the processing date to substitute, or {@code null} to keep the base's
     * @return the input
     */
    private static TransactionAddService.TransactionAddScreenInput withDataFields(
            final TransactionAddService.TransactionAddScreenInput base, final String amount,
            final String origin, final String process) {
        return new TransactionAddService.TransactionAddScreenInput(base.accountId(), base.cardNumber(),
                base.typeCd(), base.categoryCd(), base.source(), base.description(),
                amount == null ? base.amount() : amount,
                origin == null ? base.origDate() : origin,
                process == null ? base.procDate() : process,
                base.merchantId(), base.merchantName(), base.merchantCity(), base.merchantZip(),
                base.confirm(), base.selectedTransaction(), base.keyAction(),
                base.navigationContext());
    }

    // ==============================================================================================
    // Construction
    // ==============================================================================================

    @Nested
    @DisplayName("construction and argument checking")
    final class Construction {

        @Test
        @DisplayName("each of the six constructor arguments is mandatory")
        void everyCollaboratorIsMandatory() {
            final DateValidationService dates = new DateValidationService();
            final MessageCatalogService catalog = new MessageCatalogService();
            final NavigationService navigation = new NavigationService();
            final OnlineTransactionBoundary boundary = new OnlineTransactionBoundary();
            final Clock clock = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);

            assertThatNullPointerException().isThrownBy(() -> new TransactionAddService(null, catalog,
                    navigation, transactionRepository, crossReferenceRepository, boundary, clock));
            assertThatNullPointerException().isThrownBy(() -> new TransactionAddService(dates, null,
                    navigation, transactionRepository, crossReferenceRepository, boundary, clock));
            assertThatNullPointerException().isThrownBy(() -> new TransactionAddService(dates, catalog,
                    null, transactionRepository, crossReferenceRepository, boundary, clock));
            assertThatNullPointerException().isThrownBy(() -> new TransactionAddService(dates, catalog,
                    navigation, null, crossReferenceRepository, boundary, clock));
            assertThatNullPointerException().isThrownBy(() -> new TransactionAddService(dates, catalog,
                    navigation, transactionRepository, null, boundary, clock));
            assertThatNullPointerException().isThrownBy(() -> new TransactionAddService(dates, catalog,
                    navigation, transactionRepository, crossReferenceRepository, null, clock));
            assertThatNullPointerException().isThrownBy(() -> new TransactionAddService(dates, catalog,
                    navigation, transactionRepository, crossReferenceRepository, boundary, null));
        }

        @Test
        @DisplayName("a turn with no input at all is refused")
        void anAbsentTurnIsRefused() {
            assertThatNullPointerException()
                    .isThrownBy(() -> service.processTransactionAdd(null));
        }

        @Test
        @DisplayName("a turn carrying no navigation state routes to sign-on and writes nothing, which is "
                + "the zero-length communication area test at line 115")
        void aTurnWithNoStateRoutesToSignOn() {
            final TransactionAddService.TransactionAddResult result = service.processTransactionAdd(
                    new TransactionAddService.TransactionAddScreenInput(null, null, null, null, null,
                            null, null, null, null, null, null, null, null, null, null,
                            KeyAction.ENTER, null));

            assertThat(result.route()).isNotNull();
            assertThat(result.transactionAdded()).isFalse();
            Mockito.verifyNoInteractions(transactionRepository, crossReferenceRepository);
        }
    }

    // ==============================================================================================
    // Key-field resolution
    // ==============================================================================================

    @Nested
    @DisplayName("the two key fields, and the cross reference that resolves whichever is missing")
    final class KeyFields {

        @Test
        @DisplayName("a supplied account identifier resolves the card number from the cross reference "
                + "and writes it back over the card field")
        void anAccountIdentifierResolvesTheCardNumber() {
            seedCrossReferenceByAccount();

            final TransactionAddService.TransactionAddResult result =
                    service.processTransactionAdd(validTurn(""));

            assertThat(result.screen().cardNumber()).contains(CARD_NUMBER);
            Mockito.verify(crossReferenceRepository)
                    .findFirstByXrefAcctIdOrderByXrefCardNumAsc(ACCOUNT_ID);
        }

        @Test
        @DisplayName("a supplied card number resolves the account identifier through the base cluster "
                + "rather than the alternate index, which is the second arm at line 210")
        void aCardNumberResolvesTheAccountIdentifier() {
            seedCrossReferenceByCard();
            final TransactionAddService.TransactionAddScreenInput byCard =
                    new TransactionAddService.TransactionAddScreenInput(null, CARD_NUMBER, "01",
                            "0001", "POS TERM", "PURCHASE", AMOUNT, ORIG_DATE, PROC_DATE,
                            "000000001", "MERCHANT", "CITY", "10001", "", null, KeyAction.ENTER,
                            reSubmission());

            final TransactionAddService.TransactionAddResult result =
                    service.processTransactionAdd(byCard);

            assertThat(result.screen().accountId()).contains(ACCOUNT_ID);
            Mockito.verify(crossReferenceRepository).findById(CARD_NUMBER);
        }

        @Test
        @DisplayName("neither key field supplied reports the key-required text and reads nothing")
        void neitherKeyFieldSuppliedIsRefused() {
            final TransactionAddService.TransactionAddScreenInput noKeys =
                    new TransactionAddService.TransactionAddScreenInput(null, null, "01", "0001",
                            "POS TERM", "PURCHASE", AMOUNT, ORIG_DATE, PROC_DATE, "000000001",
                            "MERCHANT", "CITY", "10001", "", null, KeyAction.ENTER, reSubmission());

            final TransactionAddService.TransactionAddResult result =
                    service.processTransactionAdd(noKeys);

            assertThat(result.errorFlag()).isTrue();
            assertThat(result.message()).contains("Account or Card Number must be entered");
            Mockito.verifyNoInteractions(crossReferenceRepository, transactionRepository);
        }

        @Test
        @DisplayName("an account identifier padded with spaces is not numeric, because the legacy test "
                + "requires a digit in every character position")
        void aSpacePaddedAccountIdentifierIsNotNumeric() {
            final TransactionAddService.TransactionAddScreenInput padded =
                    new TransactionAddService.TransactionAddScreenInput("123        ", null, "01",
                            "0001", "POS TERM", "PURCHASE", AMOUNT, ORIG_DATE, PROC_DATE,
                            "000000001", "MERCHANT", "CITY", "10001", "", null, KeyAction.ENTER,
                            reSubmission());

            final TransactionAddService.TransactionAddResult result =
                    service.processTransactionAdd(padded);

            assertThat(result.errorFlag()).isTrue();
            assertThat(result.message()).contains("Account ID must be Numeric");
            Mockito.verifyNoInteractions(crossReferenceRepository);
        }

        @Test
        @DisplayName("an account identifier the cross reference does not hold reports not-found rather "
                + "than a lookup failure, so the two arms stay distinguishable")
        void anUnknownAccountIdentifierReportsNotFound() {
            Mockito.when(crossReferenceRepository
                            .findFirstByXrefAcctIdOrderByXrefCardNumAsc(ACCOUNT_ID))
                    .thenReturn(Optional.empty());

            final TransactionAddService.TransactionAddResult result =
                    service.processTransactionAdd(validTurn(""));

            assertThat(result.errorFlag()).isTrue();
            assertThat(result.message()).contains("Account ID NOT found");
            Mockito.verifyNoInteractions(transactionRepository);
        }
    }

    // ==============================================================================================
    // Data-field edits
    // ==============================================================================================

    @Nested
    @DisplayName("the data-field edits, in the order the cascade performs them")
    final class DataFieldEdits {

        @ParameterizedTest(name = "amount {0} is refused")
        @ValueSource(strings = {"00000100.00", " 0000100.00", "-0000100.000", "-0000010O.00",
            "-00000100,00"})
        @DisplayName("an amount that is not sign, eight digits, point and two digits is refused, so an "
                + "unsigned amount is never assumed positive")
        void aMalformedAmountIsRefused(final String amount) {
            seedCrossReferenceByAccount();

            final TransactionAddService.TransactionAddResult result = service.processTransactionAdd(
                    withDataFields(validTurn(""), amount, null, null));

            assertThat(result.errorFlag()).isTrue();
            assertThat(result.message()).isEqualTo("Amount should be in format -99999999.99");
            Mockito.verify(transactionRepository, Mockito.never())
                    .insertAndFlush(ArgumentMatchers.any(Transaction.class));
        }

        @ParameterizedTest(name = "a positive and a negative lexeme are both accepted: {0}")
        @ValueSource(strings = {"-00000100.00", "+00000100.00"})
        @DisplayName("both signs are accepted, which is what the sign test at line 340 actually says")
        void bothSignsAreAccepted(final String amount) {
            seedCrossReferenceByAccount();
            seedHighestTransaction(null);

            final TransactionAddService.TransactionAddResult result = service.processTransactionAdd(
                    withDataFields(validTurn("Y"), amount, null, null));

            assertThat(result.transactionAdded()).isTrue();
        }

        @ParameterizedTest(name = "origination date {0} is refused on shape")
        @ValueSource(strings = {"2022/07/19", "22-07-19", "2022-7-19"})
        @DisplayName("a date whose five fixed positions do not hold four digits, a hyphen, two digits, a "
                + "hyphen and two digits is refused on shape before any calendar test")
        void aMalformedOriginationDateIsRefused(final String origin) {
            seedCrossReferenceByAccount();

            final TransactionAddService.TransactionAddResult result = service.processTransactionAdd(
                    withDataFields(validTurn(""), null, origin, null));

            assertThat(result.errorFlag()).isTrue();
            assertThat(result.message()).isEqualTo("Orig Date should be in format YYYY-MM-DD");
        }

        @ParameterizedTest(name = "{0} on {1} is refused as not a valid date")
        @CsvSource({"2022-02-30,orig", "2022-13-01,orig", "2022-02-30,proc"})
        @DisplayName("a well-shaped date that is not a real calendar date is refused by the date "
                + "validator, which is the second of the two tests and not a substitute for the first")
        void anImpossibleCalendarDateIsRefused(final String date, final String which) {
            seedCrossReferenceByAccount();
            final boolean origination = "orig".equals(which);

            final TransactionAddService.TransactionAddResult result = service.processTransactionAdd(
                    withDataFields(validTurn(""), null, origination ? date : null,
                            origination ? null : date));

            assertThat(result.errorFlag()).isTrue();
            assertThat(result.message()).contains("Not a valid date");
        }

        @Test
        @DisplayName("a merchant identifier that is not wholly numeric is refused, and it is the last "
                + "edit in the cascade")
        void aNonNumericMerchantIdentifierIsRefused() {
            seedCrossReferenceByAccount();
            final TransactionAddService.TransactionAddScreenInput badMerchant =
                    new TransactionAddService.TransactionAddScreenInput(ACCOUNT_ID, null, "01",
                            "0001", "POS TERM", "PURCHASE", AMOUNT, ORIG_DATE, PROC_DATE,
                            "00000000A", "MERCHANT", "CITY", "10001", "", null, KeyAction.ENTER,
                            reSubmission());

            final TransactionAddService.TransactionAddResult result =
                    service.processTransactionAdd(badMerchant);

            assertThat(result.errorFlag()).isTrue();
            assertThat(result.message()).contains("Merchant ID must be Numeric");
        }

        @Test
        @DisplayName("an empty mandatory field is refused with its own text, and the type code is the "
                + "first of the eleven the empty-field paragraph tests")
        void anEmptyMandatoryFieldIsRefused() {
            seedCrossReferenceByAccount();
            final TransactionAddService.TransactionAddScreenInput noTypeCode =
                    new TransactionAddService.TransactionAddScreenInput(ACCOUNT_ID, null, null,
                            "0001", "POS TERM", "PURCHASE", AMOUNT, ORIG_DATE, PROC_DATE,
                            "000000001", "MERCHANT", "CITY", "10001", "", null, KeyAction.ENTER,
                            reSubmission());

            final TransactionAddService.TransactionAddResult result =
                    service.processTransactionAdd(noTypeCode);

            assertThat(result.errorFlag()).isTrue();
            assertThat(result.message()).isEqualTo("Type CD can NOT be empty...");
            assertThat(result.fieldErrors()).isNotEmpty();
        }
    }

    // ==============================================================================================
    // The confirmation gate
    // ==============================================================================================

    @Nested
    @DisplayName("the confirmation gate, whose four arms are preserved in order")
    final class ConfirmationGate {

        @ParameterizedTest(name = "confirmation {0} prompts rather than writing")
        @ValueSource(strings = {"N", "n", " ", ""})
        @DisplayName("a declined confirmation and an unanswered one take the same arm and produce the "
                + "same prompt, which is the source's own conflation")
        void aDeclinedOrUnansweredConfirmationOnlyPrompts(final String confirm) {
            seedCrossReferenceByAccount();
            seedHighestTransaction("0000000000000042");

            final TransactionAddService.TransactionAddResult result =
                    service.processTransactionAdd(validTurn(confirm));

            assertThat(result.message()).isEqualTo("Confirm to add this transaction...");
            assertThat(result.transactionAdded()).isFalse();
            Mockito.verify(transactionRepository, Mockito.never())
                    .insertAndFlush(ArgumentMatchers.any(Transaction.class));
        }

        @ParameterizedTest(name = "confirmation {0} is refused as an invalid value")
        @ValueSource(strings = {"X", "1", "?"})
        @DisplayName("any other confirmation value is refused with the valid-values text, which is the "
                + "trailing arm at lines 182 to 187")
        void anyOtherConfirmationValueIsRefused(final String confirm) {
            seedCrossReferenceByAccount();

            final TransactionAddService.TransactionAddResult result =
                    service.processTransactionAdd(validTurn(confirm));

            assertThat(result.errorFlag()).isTrue();
            assertThat(result.message()).isEqualTo("Invalid value. Valid values are (Y/N)...");
            Mockito.verify(transactionRepository, Mockito.never())
                    .insertAndFlush(ArgumentMatchers.any(Transaction.class));
        }

        @Test
        @DisplayName("the confirmation field is one character wide, so a trailing space is not part of "
                + "the value and an affirmative answer keyed with one still writes")
        void theConfirmationFieldIsOneCharacterWide() {
            // MOVE CONFIRMI OF COTRN2AI at line 1804 stores into a one-character field, and a move into
            // a fixed field truncates on the right. Treating "y " as a distinct value would refuse input
            // the screen accepts.
            seedCrossReferenceByAccount();
            seedHighestTransaction(null);

            final TransactionAddService.TransactionAddResult result =
                    service.processTransactionAdd(validTurn("y "));

            assertThat(result.transactionAdded()).isTrue();
            Mockito.verify(transactionRepository)
                    .insertAndFlush(ArgumentMatchers.any(Transaction.class));
        }

        @ParameterizedTest(name = "confirmation {0} writes the transaction")
        @ValueSource(strings = {"Y", "y"})
        @DisplayName("both cases of the affirmative confirmation write, since the source names both")
        void bothCasesOfTheAffirmativeConfirmationWrite(final String confirm) {
            seedCrossReferenceByAccount();
            seedHighestTransaction(null);

            final TransactionAddService.TransactionAddResult result =
                    service.processTransactionAdd(validTurn(confirm));

            assertThat(result.transactionAdded()).isTrue();
            Mockito.verify(transactionRepository)
                    .insertAndFlush(ArgumentMatchers.any(Transaction.class));
        }
    }

    // ==============================================================================================
    // The write
    // ==============================================================================================

    @Nested
    @DisplayName("the write, and the identifier it derives")
    final class Write {

        @Test
        @DisplayName("the identifier is the highest existing key plus one, zero filled on the left to "
                + "sixteen characters")
        void theIdentifierIsTheHighestKeyPlusOne() {
            seedCrossReferenceByAccount();
            seedHighestTransaction("0000000000000042");

            final TransactionAddService.TransactionAddResult result =
                    service.processTransactionAdd(validTurn("Y"));

            assertThat(result.transactionAdded()).isTrue();
            assertThat(result.transaction().tranId()).isEqualTo("0000000000000043");
            assertThat(result.transaction().tranId()).hasSize(16);
        }

        @Test
        @DisplayName("an empty master seeds from zero, so the first identifier written is one")
        void anEmptyMasterSeedsFromZero() {
            seedCrossReferenceByAccount();
            seedHighestTransaction(null);

            final TransactionAddService.TransactionAddResult result =
                    service.processTransactionAdd(validTurn("Y"));

            assertThat(result.transaction().tranId()).isEqualTo("0000000000000001");
        }

        @Test
        @DisplayName("the browse is a single descending read rather than a scan, because the legacy "
                + "positions at high values and reads once backwards")
        void theBrowseIsASingleDescendingRead() {
            seedCrossReferenceByAccount();
            seedHighestTransaction("0000000000000042");

            service.processTransactionAdd(validTurn("Y"));

            final ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
            Mockito.verify(transactionRepository).findAll(captor.capture());
            assertThat(captor.getValue().getPageSize())
                    .as("one row is read, not a page of them")
                    .isOne();
            assertThat(captor.getValue().getSort().toString())
                    .as("the walk is descending, which is what positioning at high values expresses")
                    .contains("DESC");
        }

        @Test
        @DisplayName("allocation is locked before the highest key is read and stays in the same turn "
                + "through the flushed insert")
        void identifierAllocationIsLockedBeforeTheReadAndInsert() {
            seedCrossReferenceByAccount();
            seedHighestTransaction("0000000000000042");

            service.processTransactionAdd(validTurn("Y"));

            final InOrder allocationOrder = Mockito.inOrder(transactionRepository);
            allocationOrder.verify(transactionRepository).lockIdentifierAllocation(
                    TransactionRepository.IDENTIFIER_ALLOCATION_LOCK_KEY);
            allocationOrder.verify(transactionRepository)
                    .findAll(ArgumentMatchers.any(Pageable.class));
            allocationOrder.verify(transactionRepository)
                    .insertAndFlush(ArgumentMatchers.any(Transaction.class));
        }

        @Test
        @DisplayName("the written row carries the amount at the stored scale, the resolved card number "
                + "and both timestamps from the injected clock")
        void theWrittenRowCarriesTheEditedValues() {
            seedCrossReferenceByAccount();
            seedHighestTransaction(null);

            service.processTransactionAdd(validTurn("Y"));

            final ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
            Mockito.verify(transactionRepository).insertAndFlush(captor.capture());
            final Transaction written = captor.getValue();
            assertThat(written.getTranCardNum()).isEqualTo(CARD_NUMBER);
            assertThat(written.getTranAmt()).isEqualByComparingTo(new BigDecimal("-100.00"));
            assertThat(written.getTranAmt().scale())
                    .as("the stored column is PIC S9(09)V99, so the scale is two")
                    .isEqualTo(2);
            assertThat(written.getTranTypeCd()).isEqualTo("01");
            assertThat(written.getTranCatCd()).isEqualTo("0001");
            assertThat(written.getTranOrigTs()).isNotBlank();
            assertThat(written.getTranProcTs()).isNotBlank();
        }

        @Test
        @DisplayName("the amount is truncated rather than rounded on the way into the stored scale, "
                + "because no arithmetic in the estate carries a ROUNDED clause")
        void theAmountIsTruncatedRatherThanRounded() {
            seedCrossReferenceByAccount();
            seedHighestTransaction(null);

            service.processTransactionAdd(
                    withDataFields(validTurn("Y"), "-00000100.99", null, null));

            final ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
            Mockito.verify(transactionRepository).insertAndFlush(captor.capture());
            assertThat(captor.getValue().getTranAmt())
                    .isEqualByComparingTo(new BigDecimal("-100.99"));
        }
    }

    // ==============================================================================================
    // Serialised identifier allocation, lines 444 to 466
    //
    // The legacy region held its browse position across the read, the increment and the write, which
    // admitted one allocator at a time. A relational store expresses that hold as the repository's
    // transaction-scoped advisory lock plus an existence probe, and the repository's contract obliges
    // this service to take the lock BEFORE the highest key is read and to re-read under it when a
    // writer that skipped the lock has already taken the identifier. A shared transaction is not
    // sufficient on its own: under READ COMMITTED an uncommitted insert is invisible, so two adds could
    // otherwise read the same highest key, derive the same successor and collide on the primary key.
    // ==============================================================================================

    @Nested
    @DisplayName("serialised identifier allocation, lines 444 to 466")
    final class SerialisedAllocation {

        @Test
        @DisplayName("the advisory lock is taken BEFORE the descending browse reads the highest key, "
                + "because a lock taken afterwards serialises nothing")
        void theLockPrecedesTheBrowse() {
            seedCrossReferenceByAccount();
            seedHighestTransaction("0000000000000042");

            service.processTransactionAdd(validTurn("Y"));

            final InOrder inOrder = Mockito.inOrder(transactionRepository);
            inOrder.verify(transactionRepository).lockIdentifierAllocation(
                    TransactionRepository.IDENTIFIER_ALLOCATION_LOCK_KEY);
            inOrder.verify(transactionRepository).findAll(ArgumentMatchers.any(Pageable.class));
        }

        @Test
        @DisplayName("the key the lock is taken on is the repository's own constant, because a lock on "
                + "any other key serialises against nobody")
        void theLockKeyIsTheRepositoryConstant() {
            seedCrossReferenceByAccount();
            seedHighestTransaction(null);

            service.processTransactionAdd(validTurn("Y"));

            assertThat(TransactionRepository.IDENTIFIER_ALLOCATION_LOCK_KEY).isEqualTo(350_016L);
            Mockito.verify(transactionRepository).lockIdentifierAllocation(350_016L);
        }

        @Test
        @DisplayName("the lock is still held when the write is flushed, so the row reaches the server "
                + "inside the serialised window rather than at commit")
        void theLockIsHeldWhenTheWriteIsFlushed() {
            seedCrossReferenceByAccount();
            seedHighestTransaction(null);

            service.processTransactionAdd(validTurn("Y"));

            final InOrder inOrder = Mockito.inOrder(transactionRepository);
            inOrder.verify(transactionRepository).lockIdentifierAllocation(
                    TransactionRepository.IDENTIFIER_ALLOCATION_LOCK_KEY);
            inOrder.verify(transactionRepository)
                    .insertAndFlush(ArgumentMatchers.any(Transaction.class));
        }

        @Test
        @DisplayName("the lock is taken exactly once per turn, because it is re-entrant within a "
                + "session and a second acquisition would buy nothing")
        void theLockIsTakenOncePerTurn() {
            seedCrossReferenceByAccount();
            seedHighestTransaction(null);

            service.processTransactionAdd(validTurn("Y"));

            Mockito.verify(transactionRepository, Mockito.times(1))
                    .lockIdentifierAllocation(ArgumentMatchers.anyLong());
        }

        @Test
        @DisplayName("the copy-last-transaction key browses the very same rows and takes NO lock, "
                + "because it mints nothing and must not serialise every allocator behind it")
        void theCopyLastKeyTakesNoLock() {
            seedCrossReferenceByAccount();
            seedHighestTransaction("0000000000000042");

            service.processTransactionAdd(new TransactionAddService.TransactionAddScreenInput(
                    ACCOUNT_ID, null, null, null, null, null, null, null, null, null, null, null,
                    null, null, null, KeyAction.PFK05, reSubmission()));

            Mockito.verify(transactionRepository).findAll(ArgumentMatchers.any(Pageable.class));
            Mockito.verify(transactionRepository, Mockito.never())
                    .lockIdentifierAllocation(ArgumentMatchers.anyLong());
        }

        @Test
        @DisplayName("an identifier already stored is re-minted from a re-read highest key rather than "
                + "refused, which is the bounded retry the repository contract obliges")
        void aTakenIdentifierIsReMintedFromAReReadHighestKey() {
            seedCrossReferenceByAccount();
            seedHighestTransaction("0000000000000009");
            // The re-read under the lock observes the row the lock-skipping writer committed.
            Mockito.when(transactionRepository.findAll(ArgumentMatchers.any(Pageable.class)))
                    .thenReturn(pageOf("0000000000000009"))
                    .thenReturn(pageOf("0000000000000019"));
            Mockito.when(transactionRepository.existsById("0000000000000010")).thenReturn(true);

            final TransactionAddService.TransactionAddResult result =
                    service.processTransactionAdd(validTurn("Y"));

            assertThat(result.transactionAdded())
                    .as("the second attempt succeeds, so the add is not refused for a reason that has "
                            + "nothing to do with the transaction")
                    .isTrue();
            assertThat(result.transaction().tranId()).isEqualTo("0000000000000020");
            Mockito.verify(transactionRepository, Mockito.times(2))
                    .findAll(ArgumentMatchers.any(Pageable.class));
            Mockito.verify(transactionRepository, Mockito.times(1))
                    .lockIdentifierAllocation(ArgumentMatchers.anyLong());
        }

        @Test
        @DisplayName("the retry is BOUNDED: a highest key that never moves is attempted twice and then "
                + "reported under the source's duplicate text, never retried forever")
        void theRetryIsBounded() {
            seedCrossReferenceByAccount();
            seedHighestTransaction("0000000000000009");
            Mockito.when(transactionRepository.existsById("0000000000000010")).thenReturn(true);

            final TransactionAddService.TransactionAddResult result =
                    service.processTransactionAdd(validTurn("Y"));

            assertThat(result.errorFlag()).isTrue();
            assertThat(result.message()).isEqualTo("Tran ID already exist...");
            assertThat(result.transaction()).isNull();
            Mockito.verify(transactionRepository, Mockito.times(2))
                    .findAll(ArgumentMatchers.any(Pageable.class));
            Mockito.verify(transactionRepository, Mockito.never())
                    .save(ArgumentMatchers.any(Transaction.class));
        }

        @Test
        @DisplayName("the existence probe runs before the save, so a taken identifier can never reach "
                + "the store as a merge that would silently overwrite the existing row")
        void theProbeRunsBeforeTheSave() {
            seedCrossReferenceByAccount();
            seedHighestTransaction("0000000000000009");

            service.processTransactionAdd(validTurn("Y"));

            final InOrder inOrder = Mockito.inOrder(transactionRepository);
            inOrder.verify(transactionRepository).existsById("0000000000000010");
            inOrder.verify(transactionRepository)
                    .insertAndFlush(ArgumentMatchers.any(Transaction.class));
        }

        @Test
        @DisplayName("a duplicate raised by the FLUSH is reported and not retried, because a refused "
                + "flush leaves the transaction unable to commit whatever is attempted next")
        void aFlushDuplicateIsReportedRatherThanRetried() {
            seedCrossReferenceByAccount();
            seedHighestTransaction(null);
            Mockito.doThrow(new DataIntegrityViolationException("duplicate key value"))
                    .when(transactionRepository)
                    .insertAndFlush(ArgumentMatchers.any(Transaction.class));

            final TransactionAddService.TransactionAddResult result =
                    service.processTransactionAdd(validTurn("Y"));

            assertThat(result.errorFlag()).isTrue();
            assertThat(result.message()).isEqualTo("Tran ID already exist...");
            assertThat(result.transaction()).isNull();
            Mockito.verify(transactionRepository, Mockito.times(1))
                    .findAll(ArgumentMatchers.any(Pageable.class));
        }

        @Test
        @DisplayName("a flush failure that is NOT a duplicate reaches the write's catch-all text, so "
                + "the failure is reported by this paragraph instead of escaping at commit")
        void aFlushFailureThatIsNotADuplicateReachesTheCatchAll() {
            seedCrossReferenceByAccount();
            seedHighestTransaction(null);
            Mockito.doThrow(new DataAccessResourceFailureException("connection reset"))
                    .when(transactionRepository)
                    .insertAndFlush(ArgumentMatchers.any(Transaction.class));

            final TransactionAddService.TransactionAddResult result =
                    service.processTransactionAdd(validTurn("Y"));

            assertThat(result.errorFlag()).isTrue();
            assertThat(result.message()).isEqualTo("Unable to Add Transaction...");
            assertThat(result.transaction()).isNull();
        }
    }

    // ==============================================================================================
    // Keys
    // ==============================================================================================

    @Nested
    @DisplayName("the five arms of the attention-key dispatch")
    final class Keys {

        @Test
        @DisplayName("the third function key returns to the calling screen, whose blank fallback is "
                + "this screen's own default rather than sign-on")
        void theThirdFunctionKeyReturnsToTheCaller() {
            final TransactionAddService.TransactionAddScreenInput exit =
                    new TransactionAddService.TransactionAddScreenInput(null, null, null, null, null,
                            null, null, null, null, null, null, null, null, null, null,
                            KeyAction.PFK03, reSubmission());

            final TransactionAddService.TransactionAddResult result =
                    service.processTransactionAdd(exit);

            assertThat(result.route()).isNotNull();
            assertThat(result.transactionAdded()).isFalse();
            Mockito.verifyNoInteractions(transactionRepository);
        }

        @Test
        @DisplayName("the clear key is not an arm of the dispatch, so it reaches the catch-all and "
                + "produces the invalid-key message rather than clearing the screen")
        void theClearKeyReachesTheCatchAll() {
            final TransactionAddService.TransactionAddScreenInput cleared =
                    new TransactionAddService.TransactionAddScreenInput(null, null, null, null, null,
                            null, null, null, null, null, null, null, null, null, null,
                            KeyAction.CLEAR, reSubmission());

            final TransactionAddService.TransactionAddResult result =
                    service.processTransactionAdd(cleared);

            assertThat(result.errorFlag()).isTrue();
            assertThat(result.message()).isNotBlank();
            Mockito.verifyNoInteractions(transactionRepository);
        }

        @Test
        @DisplayName("a first entry carrying a pre-selected transaction processes the enter key without "
                + "an operator keystroke, which no sibling screen does")
        void aPreSelectedTransactionProcessesTheEnterKeyImmediately() {
            seedCrossReferenceByCard();
            final ScreenNavigationState firstEntry = new ScreenNavigationState("CT00", "COTRN00C", "CT02",
                    "COTRN02C", "USER0001", "U", ScreenNavigationState.ProgramContext.ENTER, null, null,
                    null, null, null, null, null, "COTRN2A", "COTRN02");
            final TransactionAddService.TransactionAddScreenInput preSelected =
                    new TransactionAddService.TransactionAddScreenInput(null, null, null, null, null,
                            null, null, null, null, null, null, null, null, null, CARD_NUMBER,
                            KeyAction.ENTER, firstEntry);

            final TransactionAddService.TransactionAddResult result =
                    service.processTransactionAdd(preSelected);

            assertThat(result.screen().cardNumber()).contains(CARD_NUMBER);
            Mockito.verify(crossReferenceRepository).findById(CARD_NUMBER);
            assertThat(result.reEnter())
                    .as("the gate is set on a first entry, so the next turn is a re-submission")
                    .isTrue();
        }

        @Test
        @DisplayName("a first entry with nothing pre-selected sends the screen and reads nothing")
        void aPlainFirstEntrySendsTheScreen() {
            final ScreenNavigationState firstEntry = new ScreenNavigationState("CT00", "COTRN00C", "CT02",
                    "COTRN02C", "USER0001", "U", ScreenNavigationState.ProgramContext.ENTER, null, null,
                    null, null, null, null, null, "COTRN2A", "COTRN02");
            final TransactionAddService.TransactionAddScreenInput plain =
                    new TransactionAddService.TransactionAddScreenInput(null, null, null, null, null,
                            null, null, null, null, null, null, null, null, null, null,
                            KeyAction.ENTER, firstEntry);

            final TransactionAddService.TransactionAddResult result =
                    service.processTransactionAdd(plain);

            assertThat(result.header()).isNotNull();
            assertThat(result.focusField()).isNotBlank();
            assertThat(result.transactionAdded()).isFalse();
            Mockito.verifyNoInteractions(transactionRepository, crossReferenceRepository);
        }
    }
}
