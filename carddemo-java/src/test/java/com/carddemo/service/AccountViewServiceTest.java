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

import com.carddemo.api.dto.NavigationContext;
import com.carddemo.api.dto.ScreenWorkArea;
import com.carddemo.domain.Account;
import com.carddemo.domain.CardCrossReference;
import com.carddemo.domain.Customer;
import com.carddemo.domain.enums.KeyAction;
import com.carddemo.domain.enums.UserType;
import com.carddemo.repository.AccountRepository;
import com.carddemo.repository.CardCrossReferenceRepository;
import com.carddemo.repository.CustomerRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;

/**
 * Unit tests for {@link AccountViewService}, transaction {@code CAVW}, translated from
 * {@code app/cbl/COACTVWC.cbl}.
 *
 * <p>The screen has one input - an eleven-character account filter - and three reads behind it, and
 * almost everything worth asserting is in the order and the guards rather than in the happy path. Four
 * behaviours are pinned here because each one compiles cleanly if translated wrongly and would then be
 * invisible:
 *
 * <ol>
 *   <li><strong>A blank filter shows the shorter text.</strong> The field edit assigns the
 *       account-not-provided prompt at COBOL line 658 under a message-is-off guard, and the cross-field
 *       edit at lines 640 to 642 then assigns the no-input text with <em>no</em> guard, overwriting it.
 *       The second assignment is what a screen actually shows.
 *   <li><strong>The cross-reference miss is the only read guard that stops the sequence.</strong> The
 *       two guards after it, at lines 704 and 713, compare the message field against literals whose only
 *       assignments are commented out at lines 792 and 842, so they are always false. An account-master
 *       miss therefore falls through and the customer is read anyway.
 *   <li><strong>The card number can only arrive from the cross-reference.</strong> This transaction has
 *       no access path to the card table, so the value carried out at COBOL lines 739 to 740 is the only
 *       source of it.
 *   <li><strong>Leaving the screen carries the standard-user code.</strong> Line 344 assigns it
 *       unconditionally, so an administrator is carried onward as a standard user. Almost certainly a
 *       legacy defect, reproduced because it is observable.
 * </ol>
 *
 * <p>A pure unit test: the three repositories are doubles, the message catalog, navigation service and
 * abend service are real because they hold no state worth faking, and the clock is fixed.
 *
 * <p>Provenance: checkout SHA {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19.
 *
 * @since 1.0.0
 */
@DisplayName("AccountViewService :: transaction CAVW, the account view screen")
final class AccountViewServiceTest {

    /** A valid eleven-digit account identifier, the filter field's declared width. */
    private static final String ACCOUNT_ID = "00000000011";

    /** The nine-digit customer identifier the cross-reference row yields. */
    private static final String CUSTOMER_ID = "000000011";

    /** The sixteen-digit card number the cross-reference row yields. */
    private static final String CARD_NUMBER = "4111111111111111";

    /** The program name the menu carries, which the state-adoption rule tests for. */
    private static final String MENU_PROGRAM = "COMEN01C";

    /** The instant every turn is stamped with, so nothing depends on the wall clock. */
    private static final Instant FIXED_INSTANT = Instant.parse("2022-07-19T23:12:33Z");

    private AccountRepository accountRepository;

    private CustomerRepository customerRepository;

    private CardCrossReferenceRepository crossReferenceRepository;

    private AccountViewService service;

    @BeforeEach
    void constructService() {
        this.accountRepository = Mockito.mock(AccountRepository.class);
        this.customerRepository = Mockito.mock(CustomerRepository.class);
        this.crossReferenceRepository = Mockito.mock(CardCrossReferenceRepository.class);
        this.service = new AccountViewService(this.accountRepository, this.customerRepository,
                this.crossReferenceRepository, new NavigationService(), new MessageCatalogService(),
                new AbendService(), Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC));
    }

    // ==============================================================================================
    // Fixtures
    // ==============================================================================================

    /**
     * Builds an account row carrying only what this screen reads.
     *
     * @param  accountId the eleven-character key
     * @return the row
     */
    private static Account accountRow(final String accountId) {
        // The twelve persisted attributes in record order, as CVACT01Y declares them.
        return new Account(accountId, "Y", new BigDecimal("1234.56"), new BigDecimal("5000.00"),
                new BigDecimal("2000.00"), "2020-01-15", "2029-01-15", "2024-01-15",
                new BigDecimal("100.00"), new BigDecimal("50.00"), "12345", "          ");
    }

    /**
     * Builds a customer row with both regulated columns left absent, since they accept only a sealed
     * envelope and this screen's tests have no reason to hold one.
     *
     * @param  customerId the nine-character key
     * @return the row
     */
    private static Customer customerRow(final String customerId) {
        // The eighteen persisted attributes in record order. The two regulated columns are null, exactly
        // as they are in all fifty seeded rows, and absence must be tolerated here too.
        return new Customer(customerId, "ALICE", "Q", "SMITH", "1 High Street", "Flat 2",
                "Springfield", "NY", "USA", "10001", "(201)555-0100", "(202)555-0101", null, null,
                "1980-02-03", "1234567890", "Y", "750");
    }

    /**
     * Builds a cross-reference row, the only carrier of a card number on this screen.
     *
     * @param  accountId the account the row belongs to
     * @return the row
     */
    private static CardCrossReference crossReferenceRow(final String accountId) {
        return new CardCrossReference(CARD_NUMBER, CUSTOMER_ID, accountId);
    }

    /**
     * Builds the received screen work area carrying only the filter field.
     *
     * @param  accountIdFilter the value keyed into the filter, or {@code null} for an absent field
     * @return the work area
     */
    private static ScreenWorkArea filter(final String accountIdFilter) {
        return new ScreenWorkArea(KeyAction.ENTER, null, null, null, null, null, accountIdFilter,
                null, null);
    }

    /** @return a carried state that reads as a re-submission of this screen */
    private static NavigationContext reSubmission() {
        return new NavigationContext("CAVW", "COACTVWC", "CAVW", "COACTVWC", "USER0001", "U",
                NavigationContext.ProgramContext.REENTER, null, null, null, null, null, null, null,
                "CACTVWA", "COACTVW");
    }

    // ==============================================================================================
    // Construction
    // ==============================================================================================

    @Nested
    @DisplayName("construction refuses an absent collaborator")
    final class Construction {

        @Test
        @DisplayName("each of the seven constructor arguments is mandatory, so a half-wired context "
                + "fails at startup rather than on the first turn")
        void everyCollaboratorIsMandatory() {
            final Clock clock = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
            final NavigationService navigation = new NavigationService();
            final MessageCatalogService catalog = new MessageCatalogService();
            final AbendService abend = new AbendService();

            assertThatNullPointerException().isThrownBy(() -> new AccountViewService(null,
                    customerRepository, crossReferenceRepository, navigation, catalog, abend, clock));
            assertThatNullPointerException().isThrownBy(() -> new AccountViewService(accountRepository,
                    null, crossReferenceRepository, navigation, catalog, abend, clock));
            assertThatNullPointerException().isThrownBy(() -> new AccountViewService(accountRepository,
                    customerRepository, null, navigation, catalog, abend, clock));
            assertThatNullPointerException().isThrownBy(() -> new AccountViewService(accountRepository,
                    customerRepository, crossReferenceRepository, null, catalog, abend, clock));
            assertThatNullPointerException().isThrownBy(() -> new AccountViewService(accountRepository,
                    customerRepository, crossReferenceRepository, navigation, null, abend, clock));
            assertThatNullPointerException().isThrownBy(() -> new AccountViewService(accountRepository,
                    customerRepository, crossReferenceRepository, navigation, catalog, null, clock));
            assertThatNullPointerException().isThrownBy(() -> new AccountViewService(accountRepository,
                    customerRepository, crossReferenceRepository, navigation, catalog, abend, null));
        }

        @Test
        @DisplayName("a fully wired service accepts a turn, so the mandatory-argument checks above are "
                + "not the only thing construction is asserted on")
        void aFullyWiredServiceAcceptsATurn() {
            assertThat(service.viewAccount("DFHENTER", filter(null), null))
                    .as("construction with all seven collaborators yields a usable service")
                    .isNotNull();
        }
    }

    // ==============================================================================================
    // First entry
    // ==============================================================================================

    @Nested
    @DisplayName("a first entry prompts and reads nothing")
    final class FirstEntry {

        @Test
        @DisplayName("an absent communication area starts a fresh conversation: the prompt is shown, no "
                + "master is read, and the next turn is armed as a re-entry")
        void anAbsentCommunicationAreaPromptsAndReadsNothing() {
            // The zero-length communication area the legacy tests for at COBOL line 282. This screen
            // reinitialises and sends its map rather than returning to sign-on.
            final AccountViewService.AccountViewResult result =
                    service.viewAccount("DFHENTER", filter(null), null);

            assertThat(result.presentation()).isEqualTo(AccountViewService.Presentation.MAP);
            assertThat(result.infoMessage()).isEqualTo(AccountViewService.PROMPT_FOR_INPUT_MESSAGE);
            assertThat(result.errorMessage()).isEqualTo(AccountViewService.RETURN_MESSAGE_OFF);
            assertThat(result.account()).isNull();
            assertThat(result.customer()).isNull();
            assertThat(result.reEnter()).isTrue();
            assertThat(result.screenHeader()).isNotNull();
            Mockito.verifyNoInteractions(crossReferenceRepository, accountRepository,
                    customerRepository);
        }

        @Test
        @DisplayName("arriving from the user main menu on a turn that is not a re-entry discards the "
                + "carried state, which is the second arm of the adoption test at lines 282 to 293")
        void arrivingFromTheMenuOnAFirstEntryDiscardsTheCarriedState() {
            final NavigationContext staleFromMenu = new NavigationContext("CM00", MENU_PROGRAM, null,
                    null, "USER0001", "U", NavigationContext.ProgramContext.ENTER, CUSTOMER_ID, null,
                    null, null, ACCOUNT_ID, "Y", CARD_NUMBER, "CACTVWA", "COACTVW");

            final AccountViewService.AccountViewResult result =
                    service.viewAccount("DFHENTER", filter(null), staleFromMenu);

            assertThat(result.infoMessage()).isEqualTo(AccountViewService.PROMPT_FOR_INPUT_MESSAGE);
            assertThat(result.account()).isNull();
            assertThat(result.navigationContext().accountId()).isNull();
            Mockito.verifyNoInteractions(crossReferenceRepository, accountRepository);
        }

        @Test
        @DisplayName("an absent work area is read as an all-absent one rather than refused, because the "
                + "legacy receive ignores its own response code")
        void anAbsentWorkAreaIsReadAsAllAbsent() {
            final AccountViewService.AccountViewResult result =
                    service.viewAccount(null, null, null);

            assertThat(result.presentation()).isEqualTo(AccountViewService.Presentation.MAP);
            assertThat(result.infoMessage()).isEqualTo(AccountViewService.PROMPT_FOR_INPUT_MESSAGE);
        }
    }

    // ==============================================================================================
    // The attention-key gate
    // ==============================================================================================

    @Nested
    @DisplayName("the attention-key gate coerces everything except the third function key")
    final class AttentionKeys {

        @Test
        @DisplayName("the third function key transfers, overwrites the originating pair with this "
                + "screen's identity, and carries the standard-user code whatever arrived")
        void theThirdFunctionKeyTransfersAndCarriesTheStandardUserCode() {
            // COBOL lines 324 to 352. The destination is computed from the originating pair BEFORE that
            // pair is overwritten at lines 341 to 342, and line 344 assigns the standard-user code
            // unconditionally - so an administrator leaves this screen as a standard user.
            final NavigationContext fromMenuAsAdministrator = new NavigationContext("CM00",
                    MENU_PROGRAM, "CAVW", "COACTVWC", "ADMIN001", UserType.ADMIN.getCode(),
                    NavigationContext.ProgramContext.REENTER, null, null, null, null, null, null,
                    null, "CACTVWA", "COACTVW");

            final AccountViewService.AccountViewResult result =
                    service.viewAccount("DFHPF3", filter(null), fromMenuAsAdministrator);

            assertThat(result.presentation()).isEqualTo(AccountViewService.Presentation.TRANSFER);
            assertThat(result.route()).isNotNull();
            assertThat(result.screenHeader())
                    .as("the transfer path sends no screen, so it populates no header")
                    .isNull();
            assertThat(result.navigationContext().toProgram()).isEqualTo(MENU_PROGRAM);
            assertThat(result.navigationContext().toTransactionId()).isEqualTo("CM00");
            assertThat(result.navigationContext().fromProgram()).isEqualTo("COACTVWC");
            assertThat(result.navigationContext().fromTransactionId()).isEqualTo("CAVW");
            assertThat(result.navigationContext().userType())
                    .as("line 344 assigns the standard-user code unconditionally")
                    .isEqualTo(UserType.USER.getCode());
            Mockito.verifyNoInteractions(accountRepository);
        }

        @ParameterizedTest(name = "{0} is processed as if enter had been pressed")
        @ValueSource(strings = {"DFHPF4", "DFHCLEAR", "DFHPA1", "DFHPA2", "DFHPF16"})
        @DisplayName("every key except enter and the third function key is coerced to enter with no "
                + "message, which is the whole of the gate at lines 306 to 314")
        void everyOtherKeyIsCoercedToEnter(final String rawKey) {
            final AccountViewService.AccountViewResult result =
                    service.viewAccount(rawKey, filter(null), reSubmission());

            assertThat(result.presentation()).isEqualTo(AccountViewService.Presentation.MAP);
            assertThat(result.errorMessage())
                    .as("the coerced key is processed as enter, so the blank filter is what is reported")
                    .isEqualTo(AccountViewService.NO_SEARCH_CRITERIA_MESSAGE);
        }
    }

    // ==============================================================================================
    // The filter edits
    // ==============================================================================================

    @Nested
    @DisplayName("the filter edits, in the order the source performs them")
    final class FilterEdits {

        @Test
        @DisplayName("a blank filter shows the no-input text, not the account-not-provided prompt, "
                + "because the cross-field edit assigns it with no message-is-off guard")
        void aBlankFilterShowsTheUnguardedText() {
            final AccountViewService.AccountViewResult result =
                    service.viewAccount("DFHENTER", filter("           "), reSubmission());

            assertThat(result.errorMessage()).isEqualTo(AccountViewService.NO_SEARCH_CRITERIA_MESSAGE);
            assertThat(result.errorMessage())
                    .as("the shorter text is the one that reaches a screen")
                    .isNotEqualTo(AccountViewService.PROMPT_FOR_ACCOUNT_MESSAGE);
            assertThat(result.errorFlag()).isTrue();
            assertThat(result.accountFilterFlag().isBlank()).isTrue();
            assertThat(result.accountIdFilter())
                    .as("a blank filter flag blanks the echoed field")
                    .isNull();
            Mockito.verifyNoInteractions(crossReferenceRepository);
        }

        @Test
        @DisplayName("an asterisk is recognised as the reset marker and read as a blank filter, and the "
                + "marker itself is never written back")
        void anAsteriskIsTheResetMarker() {
            final AccountViewService.AccountViewResult result =
                    service.viewAccount("DFHENTER", filter("*"), reSubmission());

            assertThat(result.errorMessage()).isEqualTo(AccountViewService.NO_SEARCH_CRITERIA_MESSAGE);
            assertThat(result.accountFilterFlag().isBlank()).isTrue();
            assertThat(result.accountIdFilter()).isNull();
        }

        @ParameterizedTest(name = "filter {0} is rejected as non-numeric or zero")
        @ValueSource(strings = {"0000000001A", "00000000000", "1234567890 ", "-0000000001"})
        @DisplayName("a filter that is not wholly numeric, or is all zeros, is rejected with the "
                + "literal the source moves - double space and all - and reads nothing")
        void aNonNumericOrZeroFilterIsRejected(final String keyed) {
            final AccountViewService.AccountViewResult result =
                    service.viewAccount("DFHENTER", filter(keyed), reSubmission());

            assertThat(result.errorMessage())
                    .isEqualTo(AccountViewService.ACCOUNT_FILTER_NOT_NUMERIC_MESSAGE);
            assertThat(result.errorMessage())
                    .as("the moved literal carries a double space after the third word")
                    .contains("must  be");
            assertThat(result.errorFlag()).isTrue();
            assertThat(result.accountFilterFlag().isNotOk()).isTrue();
            Mockito.verifyNoInteractions(crossReferenceRepository, accountRepository,
                    customerRepository);
        }
    }

    // ==============================================================================================
    // The three reads
    // ==============================================================================================

    @Nested
    @DisplayName("the three reads and the guards between them")
    final class Reads {

        @Test
        @DisplayName("a valid filter resolves the cross-reference, the account and the customer, and "
                + "the card number arrives from the cross-reference because nothing else can supply it")
        void aValidFilterResolvesAllThree() {
            Mockito.when(crossReferenceRepository
                            .findFirstByXrefAcctIdOrderByXrefCardNumAsc(ACCOUNT_ID))
                    .thenReturn(Optional.of(crossReferenceRow(ACCOUNT_ID)));
            Mockito.when(accountRepository.findById(ACCOUNT_ID))
                    .thenReturn(Optional.of(accountRow(ACCOUNT_ID)));
            Mockito.when(customerRepository.findById(CUSTOMER_ID))
                    .thenReturn(Optional.of(customerRow(CUSTOMER_ID)));

            final AccountViewService.AccountViewResult result =
                    service.viewAccount("DFHENTER", filter(ACCOUNT_ID), reSubmission());

            assertThat(result.errorFlag()).isFalse();
            assertThat(result.accountFoundInMaster()).isTrue();
            assertThat(result.customerFoundInMaster()).isTrue();
            assertThat(result.accountFieldsPresented()).isTrue();
            assertThat(result.customerFieldsPresented()).isTrue();
            assertThat(result.account()).isNotNull();
            assertThat(result.account().getAcctId()).isEqualTo(ACCOUNT_ID);
            assertThat(result.customer()).isNotNull();
            assertThat(result.customer().getCustId()).isEqualTo(CUSTOMER_ID);
            assertThat(result.navigationContext().cardNumber())
                    .as("COBOL lines 739 to 740 are the only source of a card number here")
                    .isEqualTo(CARD_NUMBER);
            assertThat(result.navigationContext().customerId()).isEqualTo(CUSTOMER_ID);
            assertThat(result.accountIdFilter()).isEqualTo(ACCOUNT_ID);
        }

        @Test
        @DisplayName("a cross-reference miss is the one guard that stops the sequence: the account "
                + "master is never read and the miss text names the resource and the status")
        void aCrossReferenceMissStopsTheSequence() {
            Mockito.when(crossReferenceRepository
                            .findFirstByXrefAcctIdOrderByXrefCardNumAsc(ACCOUNT_ID))
                    .thenReturn(Optional.empty());

            final AccountViewService.AccountViewResult result =
                    service.viewAccount("DFHENTER", filter(ACCOUNT_ID), reSubmission());

            assertThat(result.errorFlag()).isTrue();
            assertThat(result.accountFilterFlag().isNotOk()).isTrue();
            assertThat(result.errorMessage()).contains("Account:", "not found in", "Cross ref file.");
            assertThat(result.account()).isNull();
            assertThat(result.accountFoundInMaster()).isFalse();
            Mockito.verifyNoInteractions(accountRepository, customerRepository);
        }

        @Test
        @DisplayName("an account-master miss falls through and the customer is read anyway, because the "
                + "guard at line 704 compares a literal whose only assignment is commented out")
        void anAccountMissFallsThroughToTheCustomerRead() {
            Mockito.when(crossReferenceRepository
                            .findFirstByXrefAcctIdOrderByXrefCardNumAsc(ACCOUNT_ID))
                    .thenReturn(Optional.of(crossReferenceRow(ACCOUNT_ID)));
            Mockito.when(accountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.empty());
            Mockito.when(customerRepository.findById(CUSTOMER_ID))
                    .thenReturn(Optional.of(customerRow(CUSTOMER_ID)));

            final AccountViewService.AccountViewResult result =
                    service.viewAccount("DFHENTER", filter(ACCOUNT_ID), reSubmission());

            Mockito.verify(customerRepository)
                    .findById(CUSTOMER_ID);
            assertThat(result.accountFoundInMaster()).isFalse();
            assertThat(result.customerFoundInMaster()).isTrue();
            assertThat(result.accountFieldsPresented())
                    .as("the presentation guard at lines 471 to 472 is satisfied by either master, so "
                            + "the account group is presented for an account that was never found")
                    .isTrue();
            assertThat(result.errorFlag()).isTrue();
            assertThat(result.errorMessage()).contains("Acct Master file.");
        }

        @Test
        @DisplayName("a customer-master miss raises the customer filter flag, which is how that miss is "
                + "told apart from the other two on a screen with one cursor position")
        void aCustomerMissRaisesItsOwnFlag() {
            Mockito.when(crossReferenceRepository
                            .findFirstByXrefAcctIdOrderByXrefCardNumAsc(ACCOUNT_ID))
                    .thenReturn(Optional.of(crossReferenceRow(ACCOUNT_ID)));
            Mockito.when(accountRepository.findById(ACCOUNT_ID))
                    .thenReturn(Optional.of(accountRow(ACCOUNT_ID)));
            Mockito.when(customerRepository.findById(CUSTOMER_ID)).thenReturn(Optional.empty());

            final AccountViewService.AccountViewResult result =
                    service.viewAccount("DFHENTER", filter(ACCOUNT_ID), reSubmission());

            assertThat(result.customerFilterFlag().isNotOk()).isTrue();
            assertThat(result.accountFilterFlag().isNotOk())
                    .as("the account filter flag stays clear, so the two misses remain distinguishable")
                    .isFalse();
            assertThat(result.customerFoundInMaster()).isFalse();
            assertThat(result.accountFoundInMaster()).isTrue();
            assertThat(result.accountFieldsPresented()).isTrue();
            assertThat(result.customerFieldsPresented()).isFalse();
            assertThat(result.errorMessage()).contains("CustId:", "not found");
        }

        @Test
        @DisplayName("the cursor sits on the filter field on every screen-returning path, since it is "
                + "the screen's only input and all three legacy arms position it identically")
        void theCursorAlwaysSitsOnTheFilterField() {
            Mockito.when(crossReferenceRepository
                            .findFirstByXrefAcctIdOrderByXrefCardNumAsc(ACCOUNT_ID))
                    .thenReturn(Optional.of(crossReferenceRow(ACCOUNT_ID)));
            Mockito.when(accountRepository.findById(ACCOUNT_ID))
                    .thenReturn(Optional.of(accountRow(ACCOUNT_ID)));
            Mockito.when(customerRepository.findById(CUSTOMER_ID))
                    .thenReturn(Optional.of(customerRow(CUSTOMER_ID)));

            final String onSuccess = service
                    .viewAccount("DFHENTER", filter(ACCOUNT_ID), reSubmission()).focusScreenFieldId();
            final String onBlank = service
                    .viewAccount("DFHENTER", filter(null), reSubmission()).focusScreenFieldId();
            final String onNonNumeric = service
                    .viewAccount("DFHENTER", filter("0000000001A"), reSubmission())
                    .focusScreenFieldId();

            assertThat(onSuccess).isNotBlank();
            assertThat(onBlank).isEqualTo(onSuccess);
            assertThat(onNonNumeric).isEqualTo(onSuccess);
        }
    }
}
