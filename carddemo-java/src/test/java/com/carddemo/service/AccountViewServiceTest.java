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

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;

import com.carddemo.domain.Account;
import com.carddemo.domain.Card;
import com.carddemo.domain.CardCrossReference;
import com.carddemo.domain.Customer;
import com.carddemo.domain.enums.KeyAction;
import com.carddemo.domain.enums.UserType;
import com.carddemo.exception.AbendException;
import com.carddemo.repository.AccountRepository;
import com.carddemo.repository.CardCrossReferenceRepository;
import com.carddemo.repository.CustomerRepository;
import com.carddemo.support.TestDataFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AccountViewService}, transaction {@code CAVW}, the migrated form of
 * {@code app/cbl/COACTVWC.cbl} - 941 lines, whose procedure division declares 35 Area A paragraph
 * labels of which one name is declared twice, plus a copybook directive that textually inserts the two
 * paragraphs of {@code app/cpy/CSSTRPFY.cpy}. Counting the inserted pair against the in-member slots
 * gives the 38 the class under test records; counting only the labels physically present gives the 35
 * the action plan records. Both figures describe the same member and the production class publishes
 * both, which is why neither is asserted here as though it were the only one.
 *
 * <p>The screen resolves an account identifier through the card cross-reference and joins the customer
 * onto it. It reads {@code app/cpy/CVACT01Y.cpy} as the account layout, {@code app/cpy/CVCUS01Y.cpy} as
 * the customer layout, {@code app/cpy/CVACT03Y.cpy} as the cross-reference layout,
 * {@code app/cpy/CVCRD01Y.cpy} as the screen work area and {@code app/cpy/CSSTRPFY.cpy} as the
 * attention-key store.
 *
 * <p><strong>One method carries two source paragraphs.</strong> The label {@code 0000-MAIN-EXIT} is
 * declared twice, at lines 408 and 411, each with the same no-op body; the second declaration is
 * unreachable and is almost certainly an editing accident. The translation collapses the pair into the
 * single terminator method the entry point returns through, so these tests assert one behaviour and
 * never attempt to model two. The duplication is one of the estate's registered source anomalies and one
 * of the traceability rows that record a documented non-implementation rather than a translation.
 *
 * <p><strong>The oracle is independent of the code it judges.</strong> Every expected message, every
 * cursor position, every field width and every amount below is written out as a literal in this file,
 * with padding expressed as a repeated space and amounts as decimal literals. Nothing expected is
 * obtained by calling the service, the message catalogue, the key translator, a codec, a template class
 * or a record mapper - a test that asks the subject what it should have produced proves only that it is
 * self-consistent.
 *
 * <p><strong>Two claims that the legacy member itself refutes are asserted the other way round,</strong>
 * because the member is the authority:
 *
 * <ol>
 *   <li><em>There is no card access path in this transaction.</em> Lines 186 and 190 to 191 declare a
 *       card resource name and a card-by-account path name, and no statement in the procedure division
 *       references either. No card repository is a constructor argument, and the card number that reaches
 *       the screen comes from the cross-reference row at lines 739 to 740 and from nowhere else. The
 *       account-keyed lookup this member genuinely performs is the one over the cross-reference path, and
 *       that is the lookup whose argument is captured below.
 *   <li><em>There is one cursor position, not three.</em> All three arms of the decision at lines 546 to
 *       552 move the same value into the same field, and that field is the screen's only input. The three
 *       not-found outcomes are therefore distinguished by message text and by which field flag is raised -
 *       the cross-reference and account misses raise the account filter flag, the customer miss raises the
 *       customer filter flag at line 841 - and never by a different cursor position. Asserting three
 *       positions would encode a parity defect as a requirement.
 * </ol>
 *
 * <p>A surefire unit test and nothing more: every repository and every collaborating service is a
 * Mockito double under strict stubbing, the clock is fixed, and no container, context, connection, port,
 * socket or file is involved. The diagnostic channel is captured with a list appender so that ordering
 * can be asserted rather than co-occurrence, because the failure types carry no logger of their own.
 *
 * <p>Provenance: checkout SHA {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19.
 *
 * @since 1.0.0
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AccountViewService :: transaction CAVW, the account view screen")
final class AccountViewServiceTest {

    // ==================================================================================================
    // Independent expectations. Every value below is written out here rather than read back from the
    // production class, so that a change to production text fails a test instead of moving with it.
    // ==================================================================================================

    /** A valid eleven-digit account identifier, at the filter field's declared width. */
    private static final String ACCOUNT_ID = "00000000011";

    /** The nine-digit customer identifier the cross-reference row yields. */
    private static final String CUSTOMER_ID = "000000011";

    /** The sixteen-digit card number the cross-reference row yields, the lower of the two below. */
    private static final String FIRST_CARD_NUMBER = "4111111111111111";

    /** A second card number for the same account, higher than the first in ascending order. */
    private static final String SECOND_CARD_NUMBER = "4222222222222222";

    /** The customer identifier carried by the second cross-reference row, which must never be used. */
    private static final String SECOND_CUSTOMER_ID = "000000022";

    /** Member name of the user main menu, which the carried-state adoption rule tests for. */
    private static final String MENU_PROGRAM = "COMEN01C";

    /** Transaction identifier of the user main menu. */
    private static final String MENU_TRANSACTION = "CM00";

    /** This screen's own transaction identifier. */
    private static final String THIS_TRANSACTION = "CAVW";

    /** This screen's own member name, which is also the abend culprit. */
    private static final String THIS_PROGRAM = "COACTVWC";

    /** The single screen field the cursor is ever placed on. */
    private static final String FILTER_FIELD_ID = "ACCTSID";

    /** Declared width of the screen message field. */
    private static final int MESSAGE_WIDTH = 75;

    /** Declared width of the informational field. */
    private static final int INFO_WIDTH = 40;

    /** Declared width of the long diagnostic field. */
    private static final int LONG_MESSAGE_WIDTH = 500;

    /** Contractual width of a shared common message. */
    private static final int COMMON_MESSAGE_WIDTH = 50;

    /** Declared width of the operator message the abend structure carries. */
    private static final int ABEND_MESSAGE_WIDTH = 72;

    /** The two-character status this migration reports for a row that is not there. */
    private static final String NOT_FOUND_STATUS = "23";

    /**
     * The cross-reference miss text, assembled at lines 747 to 757 and written out here at the field's
     * declared width.
     *
     * <p>The legacy assembly is 81 characters into a 75-character field, so it overflows and the last six
     * characters never reach a screen. The overflow is part of the contract and is written out as such:
     * the second response slot contributes four of its ten spaces and the rest is lost.
     */
    private static final String EXPECTED_XREF_MISS_MESSAGE =
            "Account:" + ACCOUNT_ID + " not found in" + " Cross ref file.  Resp:"
                    + NOT_FOUND_STATUS + " ".repeat(8) + " Reas:" + " ".repeat(4);

    /**
     * The account-master miss text, assembled at lines 796 to 806, differing from its cross-reference
     * sibling in the resource phrase alone and overflowing the same field by the same six characters.
     */
    private static final String EXPECTED_ACCOUNT_MISS_MESSAGE =
            "Account:" + ACCOUNT_ID + " not found in" + " Acct Master file.Resp:"
                    + NOT_FOUND_STATUS + " ".repeat(8) + " Reas:" + " ".repeat(4);

    /**
     * The customer-master miss text, assembled at lines 846 to 856. Every fixed segment differs from its
     * account counterparts, the identifier is nine characters rather than eleven, the reason phrase is
     * upper case here alone, and the assembly is 78 characters into the same field so it loses three.
     */
    private static final String EXPECTED_CUSTOMER_MISS_MESSAGE =
            "CustId:" + CUSTOMER_ID + " not found" + " in customer master.Resp: "
                    + NOT_FOUND_STATUS + " ".repeat(8) + " REAS:" + " ".repeat(7);

    /** The informational prompt, whose visible text exactly fills the 40-character field. */
    private static final String EXPECTED_INPUT_PROMPT = "Enter or update id of account to display";

    /** The text a blank filter actually leaves on the screen, assigned with no guard at lines 640-642. */
    private static final String EXPECTED_NO_INPUT_MESSAGE = "No input received" + " ".repeat(58);

    /** The text the field edit assigns at line 658 and the cross-field edit then overwrites. */
    private static final String EXPECTED_ACCOUNT_NOT_PROVIDED_MESSAGE =
            "Account number not provided" + " ".repeat(48);

    /**
     * The text the non-numeric edit moves at lines 671 to 673, reproduced exactly as moved - including
     * the double space after the third word and the hyphen in the fourth-from-last.
     */
    private static final String EXPECTED_NOT_NUMERIC_MESSAGE =
            "Account Filter must  be a non-zero 11 digit number" + " ".repeat(25);

    /** The shared invalid-key text at its contractual width, with its ten trailing spaces intact. */
    private static final String EXPECTED_INVALID_KEY_MESSAGE =
            "Invalid key pressed. Please see below..." + " ".repeat(10);

    /** The message field in its off state, at the field's declared width. */
    private static final String EXPECTED_MESSAGE_OFF = " ".repeat(MESSAGE_WIDTH);

    /** The account group identifier: ten spaces in all fifty reference rows, and never trimmed. */
    private static final String EXPECTED_GROUP_ID = " ".repeat(10);

    /** A stored credit score below the 300 floor, which this screen must display exactly as held. */
    private static final String OUT_OF_RANGE_CREDIT_SCORE = "001";

    /** A card verification code whose leading zeros make it a string rather than a number. */
    private static final String CARD_VERIFICATION_CODE = "007";

    /** The route value this screen re-arms itself with. */
    private static final String THIS_SCREEN_ROUTE = "account-view";

    /** The route value the user main menu carries. */
    private static final String MENU_ROUTE = "user-menu";

    /** The instant every turn is stamped with, so no assertion depends on the wall clock. */
    private static final Instant FIXED_INSTANT = Instant.parse("2022-07-19T23:12:33Z");

    /** The header date the fixed instant renders to at the two-digit-year width the contract fixes. */
    private static final String EXPECTED_HEADER_DATE = "07/19/22";

    /** The header time the fixed instant renders to. */
    private static final String EXPECTED_HEADER_TIME = "23:12:33";

    /** First shared screen title at its contractual width, as the catalogue is stubbed to answer. */
    private static final String STUBBED_TITLE_01 = " ".repeat(6) + "AWS Mainframe Modernization"
            + " ".repeat(7);

    /** Second shared screen title at its contractual width. */
    private static final String STUBBED_TITLE_02 = " ".repeat(14) + "CardDemo" + " ".repeat(18);

    /** Reason the registered handler records when it abends, supplied by the migration. */
    private static final String EXPECTED_ABEND_REASON = "UNEXPECTED FAILURE IN ACCOUNT VIEW";

    /** Fragment identifying the handler's diagnostic, which must be emitted before the raise. */
    private static final String ABEND_DIAGNOSTIC_FRAGMENT = "ABENDING PROGRAM";

    // ==================================================================================================
    // Collaborators, all doubles, and the captured diagnostic channel
    // ==================================================================================================

    private AccountRepository accountRepository;

    private CustomerRepository customerRepository;

    private CardCrossReferenceRepository crossReferenceRepository;

    private NavigationService navigationService;

    private MessageCatalogService messageCatalogService;

    private AbendService abendService;

    private AccountViewService service;

    private Logger serviceLogger;

    private ListAppender<ILoggingEvent> logRecorder;

    private Level originalLevel;

    @BeforeEach
    void constructServiceAndAttachLogRecorder() {
        this.accountRepository = mock(AccountRepository.class);
        this.customerRepository = mock(CustomerRepository.class);
        this.crossReferenceRepository = mock(CardCrossReferenceRepository.class);
        this.navigationService = mock(NavigationService.class);
        this.messageCatalogService = mock(MessageCatalogService.class);
        this.abendService = mock(AbendService.class);
        this.service = new AccountViewService(this.accountRepository, this.customerRepository,
                this.crossReferenceRepository, this.navigationService, this.messageCatalogService,
                this.abendService, Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC));

        this.serviceLogger = (Logger) LoggerFactory.getLogger(AccountViewService.class);
        this.originalLevel = this.serviceLogger.getLevel();
        this.logRecorder = new ListAppender<>();
        this.logRecorder.setContext(this.serviceLogger.getLoggerContext());
        this.logRecorder.start();
        this.serviceLogger.addAppender(this.logRecorder);
        this.serviceLogger.setLevel(Level.TRACE);
    }

    @AfterEach
    void detachLogRecorder() {
        this.serviceLogger.detachAppender(this.logRecorder);
        this.logRecorder.stop();
        this.serviceLogger.setLevel(this.originalLevel);
    }

    // ==================================================================================================
    // Fixtures. Entities come from the shared factory wherever it models the field in question, so the
    // measured reference defaults - a ten-space group identifier, an absent national identifier - are the
    // ones under test rather than values invented here.
    // ==================================================================================================

    /**
     * Builds an account row carrying the twelve persisted attributes in record order.
     *
     * @param  accountId the eleven-character business key, which is also the identity
     * @return the row
     */
    private static Account accountRow(final String accountId) {
        return TestDataFactory.account()
                .acctId(accountId)
                .currentBalance(new BigDecimal("1234.56"))
                .creditLimit(new BigDecimal("5000.00"))
                .cashCreditLimit(new BigDecimal("2000.00"))
                .cycleCredit(new BigDecimal("100.00"))
                .cycleDebit(new BigDecimal("50.00"))
                .build();
    }

    /**
     * Builds a customer row with the credit score supplied and both regulated identifiers left as the
     * factory leaves them: the national identifier absent, as it is on all fifty reference rows.
     *
     * @param  customerId  the nine-character business key
     * @param  creditScore the stored score, passed through with no range test of any kind
     * @return the row
     */
    private static Customer customerRow(final String customerId, final String creditScore) {
        return TestDataFactory.customer()
                .customerId(customerId)
                .creditScore(creditScore)
                .build();
    }

    /**
     * Builds a cross-reference row, the only carrier of a card number on this screen.
     *
     * @param  cardNumber the sixteen-character card number, which is the cluster's base key
     * @param  customerId the nine-character customer identifier the row yields
     * @param  accountId  the eleven-character account identifier the row belongs to
     * @return the row
     */
    private static CardCrossReference crossReferenceRow(final String cardNumber,
            final String customerId, final String accountId) {
        return TestDataFactory.cardCrossReference()
                .cardNumber(cardNumber)
                .customerId(customerId)
                .accountId(accountId)
                .build();
    }

    /**
     * Builds the received screen work area carrying only the filter field and the enter key.
     *
     * @param  accountIdFilter the value keyed into the filter, or {@code null} for an absent field
     * @return the work area
     */
    private static ScreenInputState filter(final String accountIdFilter) {
        return new ScreenInputState(KeyAction.ENTER, null, null, null, null, null, accountIdFilter,
                null, null);
    }

    /**
     * Builds a carried state that reads as a re-submission of this screen.
     *
     * @return the carried state
     */
    private static ScreenNavigationState reSubmission() {
        return new ScreenNavigationState(THIS_TRANSACTION, THIS_PROGRAM, THIS_TRANSACTION, THIS_PROGRAM,
                "USER0001", UserType.USER.getCode(), ScreenNavigationState.ProgramContext.REENTER, null,
                null, null, null, null, null, null, "CACTVWA", "COACTVW");
    }

    /**
     * Builds a carried state that reads as an arrival from the user main menu.
     *
     * @param  userTypeCode the user-type code the menu carried
     * @param  context      the program context the client echoed
     * @return the carried state
     */
    private static ScreenNavigationState fromMenu(final String userTypeCode,
            final ScreenNavigationState.ProgramContext context) {
        return new ScreenNavigationState(MENU_TRANSACTION, MENU_PROGRAM, THIS_TRANSACTION, THIS_PROGRAM,
                "USER0001", userTypeCode, context, null, null, null, null, null, null, null, "CACTVWA",
                "COACTVW");
    }

    /** Stubs the three reads of one fully resolvable account, with the score the caller supplies. */
    private void givenAccountResolves(final String creditScore) {
        when(this.crossReferenceRepository.findFirstByXrefAcctIdOrderByXrefCardNumAsc(ACCOUNT_ID))
                .thenReturn(Optional.of(crossReferenceRow(FIRST_CARD_NUMBER, CUSTOMER_ID, ACCOUNT_ID)));
        when(this.accountRepository.findById(ACCOUNT_ID))
                .thenReturn(Optional.of(accountRow(ACCOUNT_ID)));
        when(this.customerRepository.findById(CUSTOMER_ID))
                .thenReturn(Optional.of(customerRow(CUSTOMER_ID, creditScore)));
    }

    /** Runs one re-submitted turn with the enter key and the supplied filter. */
    private AccountViewService.AccountViewResult submit(final String accountIdFilter) {
        return this.service.viewAccount("DFHENTER", filter(accountIdFilter), reSubmission());
    }

    /** Renders every captured diagnostic as one block of text, for absence assertions. */
    private String recordedLogText() {
        final StringBuilder text = new StringBuilder();
        for (final ILoggingEvent event : this.logRecorder.list) {
            text.append(event.getFormattedMessage()).append('\n');
        }
        return text.toString();
    }

    /** Reports whether any captured diagnostic carries the supplied fragment. */
    private boolean recordedAnyMessageContaining(final String fragment) {
        for (final ILoggingEvent event : this.logRecorder.list) {
            if (event.getFormattedMessage().contains(fragment)) {
                return true;
            }
        }
        return false;
    }

    /** Copies the diagnostics captured so far, so a later assertion can read a point-in-time snapshot. */
    private List<String> snapshotOfRecordedMessages() {
        final List<String> snapshot = new ArrayList<>();
        for (final ILoggingEvent event : this.logRecorder.list) {
            snapshot.add(event.getFormattedMessage());
        }
        return snapshot;
    }

    /** Measures a fixed-width field on its encoded bytes, never on its character count. */
    private static int encodedWidthOf(final String field) {
        return field.getBytes(StandardCharsets.US_ASCII).length;
    }

    // ==================================================================================================

    @Nested
    @DisplayName("construction")
    final class Construction {

        @Test
        @DisplayName("all seven collaborators are mandatory, so a half-wired context fails at startup "
                + "rather than on the first turn")
        void everyCollaboratorIsMandatory() {
            final Clock clock = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);

            assertThatExceptionOfType(NullPointerException.class).isThrownBy(
                    () -> new AccountViewService(null, customerRepository, crossReferenceRepository,
                            navigationService, messageCatalogService, abendService, clock));
            assertThatExceptionOfType(NullPointerException.class).isThrownBy(
                    () -> new AccountViewService(accountRepository, null, crossReferenceRepository,
                            navigationService, messageCatalogService, abendService, clock));
            assertThatExceptionOfType(NullPointerException.class).isThrownBy(
                    () -> new AccountViewService(accountRepository, customerRepository, null,
                            navigationService, messageCatalogService, abendService, clock));
            assertThatExceptionOfType(NullPointerException.class).isThrownBy(
                    () -> new AccountViewService(accountRepository, customerRepository,
                            crossReferenceRepository, null, messageCatalogService, abendService, clock));
            assertThatExceptionOfType(NullPointerException.class).isThrownBy(
                    () -> new AccountViewService(accountRepository, customerRepository,
                            crossReferenceRepository, navigationService, null, abendService, clock));
            assertThatExceptionOfType(NullPointerException.class).isThrownBy(
                    () -> new AccountViewService(accountRepository, customerRepository,
                            crossReferenceRepository, navigationService, messageCatalogService, null,
                            clock));
            assertThatExceptionOfType(NullPointerException.class).isThrownBy(
                    () -> new AccountViewService(accountRepository, customerRepository,
                            crossReferenceRepository, navigationService, messageCatalogService,
                            abendService, null));
        }

        @Test
        @DisplayName("no card repository is a constructor argument, and the two card resource names the "
                + "member declares are published as the unreferenced literals they are")
        void noCardAccessPathExistsInThisTransaction() {
            // Lines 186 and 190 to 191 declare these two names; no statement references either, so the
            // transaction has no card access path and the card number can only arrive from the
            // cross-reference row.
            assertThat(AccountViewService.UNREFERENCED_CARD_FILE_NAME)
                    .as("the card resource name is declared and never read")
                    .isEqualTo("CARDDAT");
            assertThat(AccountViewService.UNREFERENCED_CARD_ACCOUNT_PATH_NAME)
                    .as("the card-by-account path name is declared and never read")
                    .isEqualTo("CARDAIX");
            assertThat(AccountViewService.CARD_XREF_ACCOUNT_PATH_NAME)
                    .as("the account-keyed path this member does read is the cross-reference path")
                    .isEqualTo("CXACAIX");
        }

        @Test
        @DisplayName("a fully wired service accepts a turn, so the mandatory-argument checks are not the "
                + "only thing construction is asserted on")
        void aFullyWiredServiceAcceptsATurn() {
            assertThat(service.viewAccount("DFHENTER", filter(null), null))
                    .as("construction with all seven collaborators yields a usable service")
                    .isNotNull();
        }
    }

    // ==================================================================================================

    /**
     * The cross-reference read, paragraph {@code 9200-GETCARDXREF-BYACCT} at line 723.
     *
     * <p>The alternate key over the account identifier is non-unique, so the access path can hold several
     * rows for one account and the legacy keyed read simply returns the first of them in base-key order.
     * Two things can go wrong in a translation of that, and both are pinned here: reading nothing as a
     * failure rather than as the screen message the legacy produces, and selecting from a materialised
     * collection in the service instead of letting the declared order decide.
     */
    @Nested
    @DisplayName("resolving the cross-reference")
    final class CrossReferenceResolution {

        @Test
        @DisplayName("an absent cross-reference row is the legacy not-found path: a screen message, not "
                + "an exception and not an index failure")
        void anAbsentCrossReferenceRowIsTheNotFoundPath() {
            when(crossReferenceRepository.findFirstByXrefAcctIdOrderByXrefCardNumAsc(ACCOUNT_ID))
                    .thenReturn(Optional.empty());

            final AccountViewService.AccountViewResult result = submit(ACCOUNT_ID);

            assertThat(result)
                    .as("nothing found is a message, so a result is still assembled")
                    .isNotNull();
            assertThat(result.errorMessage())
                    .as("the miss text reaches the screen untrimmed at its declared width")
                    .isEqualTo(EXPECTED_XREF_MISS_MESSAGE);
            assertThat(result.errorFlag()).isTrue();
            assertThat(result.accountFilterFlag().isNotOk()).isTrue();
            assertThat(result.presentation())
                    .isEqualTo(AccountViewService.Presentation.MAP);
            assertThat(result.resolvedRoute())
                    .as("the conversation is re-armed on this screen rather than abandoned")
                    .contains(THIS_SCREEN_ROUTE);
            assertThat(result.resolvedAccount()).isEmpty();
            assertThat(result.resolvedCustomer()).isEmpty();
            verifyNoInteractions(accountRepository, customerRepository, abendService);
        }

        @Test
        @DisplayName("an absent row leaves the carried card number and customer identifier untouched, so "
                + "no value is read out of a row that was never returned")
        void anAbsentRowYieldsNoCarriedCardOrCustomer() {
            when(crossReferenceRepository.findFirstByXrefAcctIdOrderByXrefCardNumAsc(ACCOUNT_ID))
                    .thenReturn(Optional.empty());

            final AccountViewService.AccountViewResult result = submit(ACCOUNT_ID);

            assertThat(result.navigationContext().cardNumber()).isNull();
            assertThat(result.navigationContext().customerId()).isNull();
            assertThat(result.navigationContext().accountId())
                    .as("the validated filter is still carried, since the edit accepted it")
                    .isEqualTo(ACCOUNT_ID);
        }

        @Test
        @DisplayName("with several rows on the account the first in ascending card-number order wins, and "
                + "the later row contributes nothing")
        void theFirstRowInAscendingOrderWins() {
            // The two rows the access path holds for this account, in the order the declared ascending
            // card-number ordering resolves them. The expectation is computed here, by taking the lower
            // card number of the two, rather than by asking the service which row it chose.
            final CardCrossReference firstRow =
                    crossReferenceRow(FIRST_CARD_NUMBER, CUSTOMER_ID, ACCOUNT_ID);
            final CardCrossReference laterRow =
                    crossReferenceRow(SECOND_CARD_NUMBER, SECOND_CUSTOMER_ID, ACCOUNT_ID);
            final List<CardCrossReference> rowsOnTheAccessPath = List.of(firstRow, laterRow);
            final CardCrossReference expectedRow =
                    rowsOnTheAccessPath.get(0).getXrefCardNum()
                                    .compareTo(rowsOnTheAccessPath.get(1).getXrefCardNum()) <= 0
                            ? rowsOnTheAccessPath.get(0)
                            : rowsOnTheAccessPath.get(1);
            when(crossReferenceRepository.findFirstByXrefAcctIdOrderByXrefCardNumAsc(ACCOUNT_ID))
                    .thenReturn(Optional.of(expectedRow));
            when(accountRepository.findById(ACCOUNT_ID))
                    .thenReturn(Optional.of(accountRow(ACCOUNT_ID)));
            when(customerRepository.findById(CUSTOMER_ID))
                    .thenReturn(Optional.of(customerRow(CUSTOMER_ID, "750")));

            final AccountViewService.AccountViewResult result = submit(ACCOUNT_ID);

            assertThat(result.navigationContext().cardNumber())
                    .as("the lower card number of the two is the one carried out")
                    .isEqualTo(FIRST_CARD_NUMBER);
            assertThat(result.navigationContext().cardNumber()).isNotEqualTo(SECOND_CARD_NUMBER);
            assertThat(result.navigationContext().customerId())
                    .as("the customer read is keyed on the first row's identifier")
                    .isEqualTo(CUSTOMER_ID);
            verify(customerRepository).findById(CUSTOMER_ID);
            verify(customerRepository, never()).findById(SECOND_CUSTOMER_ID);
        }

        @Test
        @DisplayName("selection is pushed into the query rather than performed here: the ordered-first "
                + "finder is used and the collection-returning finder is never called")
        void selectionIsPushedIntoTheQuery() {
            givenAccountResolves("750");

            submit(ACCOUNT_ID);

            verify(crossReferenceRepository).findFirstByXrefAcctIdOrderByXrefCardNumAsc(ACCOUNT_ID);
            verify(crossReferenceRepository, never()).findByXrefAcctId(any());
            verify(crossReferenceRepository, never()).findAll();
            verifyNoMoreInteractions(crossReferenceRepository);
        }

        @Test
        @DisplayName("the card number reaches the screen from the cross-reference row and from nowhere "
                + "else, this transaction having no card access path at all")
        void theCardNumberComesOnlyFromTheCrossReference() {
            givenAccountResolves("750");

            final AccountViewService.AccountViewResult result = submit(ACCOUNT_ID);

            assertThat(result.navigationContext().cardNumber()).isEqualTo(FIRST_CARD_NUMBER);
            assertThat(result.navigationContext().customerId()).isEqualTo(CUSTOMER_ID);
        }
    }

    // ==================================================================================================

    /**
     * The three places a turn can miss, and what each one leaves on the screen.
     *
     * <p>Each miss composes its own text and raises its own field flag. Collapsing them into one generic
     * not-found response would break the interface contract, which is why the three texts are compared
     * byte for byte against literals written out in this file and are also compared against each other.
     */
    @Nested
    @DisplayName("the three not-found paths")
    final class NotFoundPaths {

        @Test
        @DisplayName("the three misses emit three different texts, each byte-exact at the 75-character "
                + "field width and never trimmed")
        void theThreeMissesEmitThreeDifferentTexts() {
            final CardCrossReference row = crossReferenceRow(FIRST_CARD_NUMBER, CUSTOMER_ID, ACCOUNT_ID);
            when(crossReferenceRepository.findFirstByXrefAcctIdOrderByXrefCardNumAsc(ACCOUNT_ID))
                    .thenReturn(Optional.empty())
                    .thenReturn(Optional.of(row))
                    .thenReturn(Optional.of(row));
            when(accountRepository.findById(ACCOUNT_ID))
                    .thenReturn(Optional.empty())
                    .thenReturn(Optional.of(accountRow(ACCOUNT_ID)));
            when(customerRepository.findById(CUSTOMER_ID))
                    .thenReturn(Optional.of(customerRow(CUSTOMER_ID, "750")))
                    .thenReturn(Optional.empty());

            final AccountViewService.AccountViewResult crossReferenceMiss = submit(ACCOUNT_ID);
            final AccountViewService.AccountViewResult accountMiss = submit(ACCOUNT_ID);
            final AccountViewService.AccountViewResult customerMiss = submit(ACCOUNT_ID);

            assertThat(crossReferenceMiss.errorMessage()).isEqualTo(EXPECTED_XREF_MISS_MESSAGE);
            assertThat(accountMiss.errorMessage()).isEqualTo(EXPECTED_ACCOUNT_MISS_MESSAGE);
            assertThat(customerMiss.errorMessage()).isEqualTo(EXPECTED_CUSTOMER_MISS_MESSAGE);

            assertThat(crossReferenceMiss.errorMessage())
                    .as("the cross-reference and account texts differ in their resource phrase")
                    .isNotEqualTo(accountMiss.errorMessage());
            assertThat(crossReferenceMiss.errorMessage())
                    .isNotEqualTo(customerMiss.errorMessage());
            assertThat(accountMiss.errorMessage())
                    .isNotEqualTo(customerMiss.errorMessage());

            assertThat(encodedWidthOf(crossReferenceMiss.errorMessage())).isEqualTo(MESSAGE_WIDTH);
            assertThat(encodedWidthOf(accountMiss.errorMessage())).isEqualTo(MESSAGE_WIDTH);
            assertThat(encodedWidthOf(customerMiss.errorMessage())).isEqualTo(MESSAGE_WIDTH);
            assertThat(crossReferenceMiss.errorMessage())
                    .as("the overflowed response slot leaves trailing spaces that are part of the field")
                    .endsWith(" ");
            assertThat(customerMiss.errorMessage()).endsWith(" ");
        }

        @Test
        @DisplayName("all three misses place the cursor on the account filter, the screen's only input, "
                + "because all three arms of the legacy cursor decision are identical")
        void allThreeMissesPlaceTheCursorOnTheFilterField() {
            final CardCrossReference row = crossReferenceRow(FIRST_CARD_NUMBER, CUSTOMER_ID, ACCOUNT_ID);
            when(crossReferenceRepository.findFirstByXrefAcctIdOrderByXrefCardNumAsc(ACCOUNT_ID))
                    .thenReturn(Optional.empty())
                    .thenReturn(Optional.of(row))
                    .thenReturn(Optional.of(row));
            when(accountRepository.findById(ACCOUNT_ID))
                    .thenReturn(Optional.empty())
                    .thenReturn(Optional.of(accountRow(ACCOUNT_ID)));
            when(customerRepository.findById(CUSTOMER_ID))
                    .thenReturn(Optional.of(customerRow(CUSTOMER_ID, "750")))
                    .thenReturn(Optional.empty());

            final AccountViewService.AccountViewResult crossReferenceMiss = submit(ACCOUNT_ID);
            final AccountViewService.AccountViewResult accountMiss = submit(ACCOUNT_ID);
            final AccountViewService.AccountViewResult customerMiss = submit(ACCOUNT_ID);

            assertThat(crossReferenceMiss.focusScreenFieldId()).isEqualTo(FILTER_FIELD_ID);
            assertThat(accountMiss.focusScreenFieldId()).isEqualTo(FILTER_FIELD_ID);
            assertThat(customerMiss.focusScreenFieldId()).isEqualTo(FILTER_FIELD_ID);

            // What tells the three apart is the raised flag, not the cursor: the first two raise the
            // account filter flag and the third raises the customer filter flag at line 841.
            assertThat(crossReferenceMiss.accountFilterFlag().isNotOk()).isTrue();
            assertThat(crossReferenceMiss.customerFilterFlag().isNotOk()).isFalse();
            assertThat(accountMiss.accountFilterFlag().isNotOk()).isTrue();
            assertThat(accountMiss.customerFilterFlag().isNotOk()).isFalse();
            assertThat(customerMiss.customerFilterFlag().isNotOk()).isTrue();
            assertThat(customerMiss.accountFilterFlag().isNotOk())
                    .as("the customer miss leaves the account filter flag alone")
                    .isFalse();
        }

        @Test
        @DisplayName("the cross-reference miss is the one guard that stops the sequence, so neither "
                + "master is read after it")
        void theCrossReferenceMissStopsTheSequence() {
            when(crossReferenceRepository.findFirstByXrefAcctIdOrderByXrefCardNumAsc(ACCOUNT_ID))
                    .thenReturn(Optional.empty());

            final AccountViewService.AccountViewResult result = submit(ACCOUNT_ID);

            assertThat(result.accountFoundInMaster()).isFalse();
            assertThat(result.customerFoundInMaster()).isFalse();
            assertThat(result.accountFieldsPresented()).isFalse();
            assertThat(result.customerFieldsPresented()).isFalse();
            verifyNoInteractions(accountRepository, customerRepository);
        }

        @Test
        @DisplayName("an account-master miss falls through and the customer is read anyway, because the "
                + "guard after it compares a text whose only assignment is commented out")
        void anAccountMissFallsThroughToTheCustomerRead() {
            when(crossReferenceRepository.findFirstByXrefAcctIdOrderByXrefCardNumAsc(ACCOUNT_ID))
                    .thenReturn(Optional.of(crossReferenceRow(FIRST_CARD_NUMBER, CUSTOMER_ID,
                            ACCOUNT_ID)));
            when(accountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.empty());
            when(customerRepository.findById(CUSTOMER_ID))
                    .thenReturn(Optional.of(customerRow(CUSTOMER_ID, "750")));

            final AccountViewService.AccountViewResult result = submit(ACCOUNT_ID);

            verify(customerRepository).findById(CUSTOMER_ID);
            assertThat(result.accountFoundInMaster()).isFalse();
            assertThat(result.customerFoundInMaster()).isTrue();
            assertThat(result.resolvedAccount()).isEmpty();
            assertThat(result.resolvedCustomer()).isPresent();
            assertThat(result.accountFieldsPresented())
                    .as("either master satisfies the presentation guard, so the account group is "
                            + "presented for an account that was never found")
                    .isTrue();
            assertThat(result.errorMessage()).isEqualTo(EXPECTED_ACCOUNT_MISS_MESSAGE);
        }

        @Test
        @DisplayName("when the account and the customer both miss, the first text written wins and the "
                + "second is suppressed, though the second miss still raises its own flag")
        void theFirstTextWrittenWins() {
            when(crossReferenceRepository.findFirstByXrefAcctIdOrderByXrefCardNumAsc(ACCOUNT_ID))
                    .thenReturn(Optional.of(crossReferenceRow(FIRST_CARD_NUMBER, CUSTOMER_ID,
                            ACCOUNT_ID)));
            when(accountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.empty());
            when(customerRepository.findById(CUSTOMER_ID)).thenReturn(Optional.empty());

            final AccountViewService.AccountViewResult result = submit(ACCOUNT_ID);

            assertThat(result.errorMessage())
                    .as("the account text was written first, so the guarded customer text never lands")
                    .isEqualTo(EXPECTED_ACCOUNT_MISS_MESSAGE);
            assertThat(result.errorMessage()).isNotEqualTo(EXPECTED_CUSTOMER_MISS_MESSAGE);
            assertThat(result.accountFilterFlag().isNotOk()).isTrue();
            assertThat(result.customerFilterFlag().isNotOk())
                    .as("the flag is raised outside the text guard, so it is raised regardless")
                    .isTrue();
            assertThat(result.errorFlag()).isTrue();
        }

        @Test
        @DisplayName("the two texts the always-false guards compare against are published and are not "
                + "what any miss actually writes")
        void theGuardTextsAreNotWhatAnyMissWrites() {
            // The two condition names the guards at lines 704 and 713 compare the message field against.
            // Their only assignments, at lines 792 and 842, are commented out, so no path can produce
            // either text and each comparison is permanently false.
            assertThat(AccountViewService.DID_NOT_FIND_ACCOUNT_IN_ACCTDAT_MESSAGE)
                    .isEqualTo("Did not find this account in account master file" + " ".repeat(27));
            assertThat(AccountViewService.DID_NOT_FIND_CUSTOMER_IN_CUSTDAT_MESSAGE)
                    .isEqualTo("Did not find associated customer in master file" + " ".repeat(28));
            assertThat(AccountViewService.DID_NOT_FIND_ACCOUNT_IN_ACCTDAT_MESSAGE)
                    .isNotEqualTo(EXPECTED_ACCOUNT_MISS_MESSAGE);
            assertThat(AccountViewService.DID_NOT_FIND_CUSTOMER_IN_CUSTDAT_MESSAGE)
                    .isNotEqualTo(EXPECTED_CUSTOMER_MISS_MESSAGE);
            assertThat(encodedWidthOf(AccountViewService.DID_NOT_FIND_ACCOUNT_IN_ACCTDAT_MESSAGE))
                    .isEqualTo(MESSAGE_WIDTH);
            assertThat(encodedWidthOf(AccountViewService.DID_NOT_FIND_CUSTOMER_IN_CUSTDAT_MESSAGE))
                    .isEqualTo(MESSAGE_WIDTH);
        }

        @Test
        @DisplayName("a miss records the raw two-character status and the resource it read, and records "
                + "neither an account holder's name nor any regulated value")
        void aMissRecordsTheRawStatusAndTheResource() {
            when(crossReferenceRepository.findFirstByXrefAcctIdOrderByXrefCardNumAsc(ACCOUNT_ID))
                    .thenReturn(Optional.empty());

            submit(ACCOUNT_ID);

            assertThat(recordedLogText())
                    .contains("fileStatus=" + NOT_FOUND_STATUS)
                    .contains("resource=CXACAIX");
        }
    }

    // ==================================================================================================

    /**
     * What the screen carries out of a resolved turn, field by field.
     *
     * <p>This service renders nothing into fixed-width screen slots - that is the presentation layer's
     * work - so what is asserted here is that the stored values reach the result unaltered: no range
     * check, no clamp, no substitution for an absent value, no re-scaling of an amount and no folding of a
     * padded field into a trimmed one.
     */
    @Nested
    @DisplayName("field-level rendering fidelity")
    final class FieldRendering {

        @Test
        @DisplayName("a stored credit score below the 300 floor is carried through exactly as held, "
                + "because the range rule belongs to the update path and not to this one")
        void anOutOfRangeCreditScoreIsCarriedThroughUnchanged() {
            // 21 of the 50 reference customers carry a score below the floor and the lowest is three
            // digits of 001. Neither the schema nor the entity constrains the value, and only the
            // account-update validator rejects one, so this screen must display what is stored.
            givenAccountResolves(OUT_OF_RANGE_CREDIT_SCORE);

            final AccountViewService.AccountViewResult result = submit(ACCOUNT_ID);

            assertThat(result.customer()).isNotNull();
            assertThat(result.customer().getFicoCreditScore())
                    .as("no clamp to the floor and no rejection")
                    .isEqualTo(OUT_OF_RANGE_CREDIT_SCORE);
            assertThat(result.customer().getFicoCreditScore())
                    .as("the leading zeros are part of the stored three-character value")
                    .isNotEqualTo("1");
            assertThat(Integer.parseInt(OUT_OF_RANGE_CREDIT_SCORE))
                    .as("the fixture really is below the floor the update path enforces")
                    .isLessThan(TestDataFactory.CREDIT_SCORE_MINIMUM);
            assertThat(result.errorFlag())
                    .as("an out-of-range stored score is not an input error on this screen")
                    .isFalse();
            assertThat(logRecorder.list)
                    .as("a resolved turn writes no diagnostic at all, so nothing warns about the score")
                    .isEmpty();
        }

        @Test
        @DisplayName("an absent national identifier renders without failing and without a stand-in, that "
                + "column being the only nullable one and absent on all fifty reference rows")
        void anAbsentNationalIdentifierRendersWithoutFailing() {
            givenAccountResolves("750");

            final AccountViewService.AccountViewResult result = submit(ACCOUNT_ID);

            assertThat(result.customer()).isNotNull();
            assertThat(result.customer().getCustSsn())
                    .as("absence is carried as absence, with no placeholder substituted for it")
                    .isNull();
            assertThat(result.customerFoundInMaster()).isTrue();
            assertThat(result.customerFieldsPresented())
                    .as("the customer group is still presented for a row whose identifier is absent")
                    .isTrue();
            assertThat(result.errorFlag()).isFalse();
        }

        @Test
        @DisplayName("the account group identifier is carried as ten spaces, untrimmed, and is never "
                + "read as an absent field")
        void theAccountGroupIdentifierIsCarriedAsTenSpaces() {
            givenAccountResolves("750");

            final AccountViewService.AccountViewResult result = submit(ACCOUNT_ID);

            assertThat(result.account()).isNotNull();
            assertThat(result.account().getAcctGroupId())
                    .as("ten spaces, exactly as all fifty reference rows hold it")
                    .isEqualTo(EXPECTED_GROUP_ID);
            assertThat(encodedWidthOf(result.account().getAcctGroupId()))
                    .as("the width is measured on encoded bytes, never on a trimmed value")
                    .isEqualTo(10);
            assertThat(result.account().getAcctGroupId())
                    .as("the spaces are the value, so the field is neither trimmed nor emptied")
                    .isNotEmpty()
                    .isNotEqualTo("");
            assertThat(result.accountFoundInMaster()).isTrue();
        }

        @Test
        @DisplayName("every monetary field arrives as a decimal at scale two, and this class re-scales "
                + "none of them because it carries the entity rather than copying it")
        void everyMonetaryFieldArrivesAtScaleTwoUnrounded() {
            final Account stored = accountRow(ACCOUNT_ID);
            when(crossReferenceRepository.findFirstByXrefAcctIdOrderByXrefCardNumAsc(ACCOUNT_ID))
                    .thenReturn(Optional.of(crossReferenceRow(FIRST_CARD_NUMBER, CUSTOMER_ID,
                            ACCOUNT_ID)));
            when(accountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.of(stored));
            when(customerRepository.findById(CUSTOMER_ID))
                    .thenReturn(Optional.of(customerRow(CUSTOMER_ID, "750")));

            final AccountViewService.AccountViewResult result = submit(ACCOUNT_ID);

            assertThat(result.account())
                    .as("the row itself is carried, so no value can be transformed on the way out")
                    .isSameAs(stored);
            assertThat(result.account().getAcctCurrBal()).isEqualTo(new BigDecimal("1234.56"));
            assertThat(result.account().getAcctCurrBal().scale()).isEqualTo(2);
            assertThat(result.account().getAcctCreditLimit()).isEqualTo(new BigDecimal("5000.00"));
            assertThat(result.account().getAcctCreditLimit().scale()).isEqualTo(2);
            assertThat(result.account().getAcctCashCreditLimit()).isEqualTo(new BigDecimal("2000.00"));
            assertThat(result.account().getAcctCashCreditLimit().scale()).isEqualTo(2);
            assertThat(result.account().getAcctCurrCycCredit()).isEqualTo(new BigDecimal("100.00"));
            assertThat(result.account().getAcctCurrCycCredit().scale()).isEqualTo(2);
            assertThat(result.account().getAcctCurrCycDebit()).isEqualTo(new BigDecimal("50.00"));
            assertThat(result.account().getAcctCurrCycDebit().scale()).isEqualTo(2);
        }

        @Test
        @DisplayName("the misspelled legacy expiry field is a correctly spelled Java property whose "
                + "stored text is returned unchanged")
        void theExpiryPropertyIsSpelledCorrectlyAndReturnedUnchanged() {
            // The legacy layout misspells the account expiration-date field. The byte layout is unchanged
            // and only the Java identifier is corrected, so the value itself must pass straight through.
            final Account stored = TestDataFactory.account()
                    .acctId(ACCOUNT_ID)
                    .expirationDate("2029-01-15")
                    .build();
            when(crossReferenceRepository.findFirstByXrefAcctIdOrderByXrefCardNumAsc(ACCOUNT_ID))
                    .thenReturn(Optional.of(crossReferenceRow(FIRST_CARD_NUMBER, CUSTOMER_ID,
                            ACCOUNT_ID)));
            when(accountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.of(stored));
            when(customerRepository.findById(CUSTOMER_ID))
                    .thenReturn(Optional.of(customerRow(CUSTOMER_ID, "750")));

            final AccountViewService.AccountViewResult result = submit(ACCOUNT_ID);

            assertThat(result.account().getAcctExpirationDate()).isEqualTo("2029-01-15");
        }

        @Test
        @DisplayName("a card verification code stays a three-character string with its leading zeros, "
                + "and cannot reach a diagnostic because this transaction reads no card")
        void theCardVerificationCodeIsAStringAndIsNeverLogged() {
            final Card cardWithLeadingZeroCode = TestDataFactory.card()
                    .accountId(ACCOUNT_ID)
                    .verificationCode(CARD_VERIFICATION_CODE)
                    .build();
            when(crossReferenceRepository.findFirstByXrefAcctIdOrderByXrefCardNumAsc(ACCOUNT_ID))
                    .thenReturn(Optional.empty());

            submit(ACCOUNT_ID);

            assertThat(cardWithLeadingZeroCode.getCardCvvCd())
                    .as("three characters of text, never a number, so the leading zeros survive")
                    .isEqualTo(CARD_VERIFICATION_CODE)
                    .isNotEqualTo("7");
            assertThat(encodedWidthOf(cardWithLeadingZeroCode.getCardCvvCd())).isEqualTo(3);
            assertThat(recordedAnyMessageContaining(CARD_VERIFICATION_CODE))
                    .as("no diagnostic on any path can carry a verification code")
                    .isFalse();
        }

        @Test
        @DisplayName("the screen header carries this screen's identity, the two catalogue titles and the "
                + "injected clock's date and time at their eight-character widths")
        void theScreenHeaderIsBuiltFromTheInjectedClock() {
            when(messageCatalogService.screenTitle01()).thenReturn(STUBBED_TITLE_01);
            when(messageCatalogService.screenTitle02()).thenReturn(STUBBED_TITLE_02);
            givenAccountResolves("750");

            final AccountViewService.AccountViewResult result = submit(ACCOUNT_ID);

            assertThat(result.screenHeader()).isNotNull();
            assertThat(result.screenHeader().transactionName()).isEqualTo(THIS_TRANSACTION);
            assertThat(result.screenHeader().programName()).isEqualTo(THIS_PROGRAM);
            assertThat(result.screenHeader().title01()).isEqualTo(STUBBED_TITLE_01);
            assertThat(result.screenHeader().title02()).isEqualTo(STUBBED_TITLE_02);
            assertThat(result.screenHeader().currentDate()).isEqualTo(EXPECTED_HEADER_DATE);
            assertThat(result.screenHeader().currentTime()).isEqualTo(EXPECTED_HEADER_TIME);
            assertThat(encodedWidthOf(result.screenHeader().currentDate())).isEqualTo(8);
            assertThat(encodedWidthOf(result.screenHeader().currentTime())).isEqualTo(8);
        }

        @Test
        @DisplayName("the informational field defaults to the prompt at its full forty-character width, "
                + "and the message field's off state is seventy-five spaces")
        void theInformationalFieldDefaultsToThePrompt() {
            givenAccountResolves("750");

            final AccountViewService.AccountViewResult result = submit(ACCOUNT_ID);

            assertThat(result.infoMessage()).isEqualTo(EXPECTED_INPUT_PROMPT);
            assertThat(encodedWidthOf(result.infoMessage()))
                    .as("the visible text exactly fills the field, so no padding is added")
                    .isEqualTo(INFO_WIDTH);
            assertThat(result.errorMessage())
                    .as("a resolved turn leaves the message field in its off state, untrimmed")
                    .isEqualTo(EXPECTED_MESSAGE_OFF);
            assertThat(encodedWidthOf(result.errorMessage())).isEqualTo(MESSAGE_WIDTH);
            assertThat(result.informationalFieldDarkened())
                    .as("the field holds the prompt, so it is not darkened")
                    .isFalse();
        }
    }

    // ==================================================================================================

    /**
     * The attention-key store, paragraph {@code YYYY-STORE-PFKEY} supplied by
     * {@code app/cpy/CSSTRPFY.cpy} at its line 17 and expanded into this member by the directive at line
     * 913.
     *
     * <p>That copybook is 85 lines and two paragraphs, and its 28-arm selection at lines 22 to 77 carries
     * no catch-all: keys 13 through 24 assign the same twelve actions as keys 1 through 12, so 28 legacy
     * inputs collapse to 16 outcomes and the high keys are not distinct actions. This member is one of the
     * five that include the copybook. The fold itself lives in the utility-layer translator and is not
     * repeated here; what is asserted here is that the fold is <em>observable</em> - that the high key
     * behaves identically to its low twin all the way out to the result.
     */
    @Nested
    @DisplayName("attention keys, and the fold of the high keys onto the low")
    final class AttentionKeys {

        @Test
        @DisplayName("the high key that folds onto the previous-screen key produces the identical "
                + "outcome: same route, same message, same carried state")
        void theFoldedHighKeyBehavesIdenticallyToItsLowTwin() {
            when(navigationService.isBackNavigationKey(KeyAction.PFK03)).thenReturn(true);
            when(navigationService.resolveBackNavigation(any(), any()))
                    .thenReturn(NavigationService.Route.USER_MENU);

            final AccountViewService.AccountViewResult fromLowKey = service.viewAccount("DFHPF3",
                    filter(null), fromMenu(UserType.USER.getCode(),
                            ScreenNavigationState.ProgramContext.REENTER));
            final AccountViewService.AccountViewResult fromHighKey = service.viewAccount("DFHPF15",
                    filter(null), fromMenu(UserType.USER.getCode(),
                            ScreenNavigationState.ProgramContext.REENTER));

            assertThat(fromHighKey)
                    .as("28 identifiers collapse to 16 actions, so these two turns are the same turn")
                    .isEqualTo(fromLowKey);
            assertThat(fromHighKey.resolvedRoute()).contains(MENU_ROUTE);
            assertThat(fromLowKey.resolvedRoute()).contains(MENU_ROUTE);
            assertThat(fromHighKey.presentation())
                    .isEqualTo(AccountViewService.Presentation.TRANSFER);
            assertThat(fromHighKey.errorMessage()).isEqualTo(fromLowKey.errorMessage());
            assertThat(fromHighKey.navigationContext()).isEqualTo(fromLowKey.navigationContext());
            verifyNoInteractions(accountRepository, customerRepository, crossReferenceRepository);
        }

        @Test
        @DisplayName("returning to the caller computes the destination before overwriting the originating "
                + "pair, and carries the standard-user code whatever type arrived")
        void returningToTheCallerCarriesTheStandardUserCode() {
            when(navigationService.isBackNavigationKey(KeyAction.PFK03)).thenReturn(true);
            when(navigationService.resolveBackNavigation(any(), any()))
                    .thenReturn(NavigationService.Route.USER_MENU);

            final AccountViewService.AccountViewResult result = service.viewAccount("DFHPF3",
                    filter(null), fromMenu(UserType.ADMIN.getCode(),
                            ScreenNavigationState.ProgramContext.REENTER));

            assertThat(result.presentation()).isEqualTo(AccountViewService.Presentation.TRANSFER);
            assertThat(result.resolvedRoute()).contains(MENU_ROUTE);
            assertThat(result.screenHeader())
                    .as("the transfer path sends no screen, so it populates no header")
                    .isNull();
            assertThat(result.navigationContext().toTransactionId())
                    .as("the destination is taken from the originating pair as it arrived")
                    .isEqualTo(MENU_TRANSACTION);
            assertThat(result.navigationContext().toProgram()).isEqualTo(MENU_PROGRAM);
            assertThat(result.navigationContext().fromTransactionId())
                    .as("only afterwards is the originating pair overwritten with this screen's identity")
                    .isEqualTo(THIS_TRANSACTION);
            assertThat(result.navigationContext().fromProgram()).isEqualTo(THIS_PROGRAM);
            assertThat(result.navigationContext().userType())
                    .as("the unconditional assignment carries an administrator out as a standard user, "
                            + "which is preserved because it is observable")
                    .isEqualTo(UserType.USER.getCode());
        }

        @ParameterizedTest(name = "{0} is processed as if enter had been pressed")
        @ValueSource(strings = {"DFHPF4", "DFHPF16", "DFHCLEAR", "DFHPA1", "DFHPA2", "DFHPF12"})
        @DisplayName("every recognised key except enter and the previous-screen key is coerced to enter "
                + "with no message at all, so the high and low keys stay indistinguishable")
        void everyOtherRecognisedKeyIsCoercedToEnter(final String rawIdentifier) {
            final AccountViewService.AccountViewResult result =
                    service.viewAccount(rawIdentifier, filter(null), reSubmission());

            assertThat(result.presentation()).isEqualTo(AccountViewService.Presentation.MAP);
            assertThat(result.resolvedRoute())
                    .as("a coerced key changes no route: the conversation stays on this screen")
                    .contains(THIS_SCREEN_ROUTE);
            assertThat(result.errorMessage())
                    .as("the key is processed as enter, so the blank filter is what gets reported")
                    .isEqualTo(EXPECTED_NO_INPUT_MESSAGE);
            assertThat(result.errorMessage())
                    .as("a recognised but inactive key produces no message of its own")
                    .isNotEqualTo(EXPECTED_INVALID_KEY_MESSAGE);
        }

        @Test
        @DisplayName("an unmapped identifier yields the shared invalid-key text at exactly fifty encoded "
                + "bytes with its ten trailing spaces intact, and changes no route")
        void anUnmappedIdentifierYieldsTheInvalidKeyText() {
            when(messageCatalogService.invalidKeyMessage()).thenReturn(EXPECTED_INVALID_KEY_MESSAGE);

            // The selection construct has no catch-all, so an unrecognised identifier matched no arm.
            final AccountViewService.AccountViewResult unmapped =
                    service.viewAccount("DFHPF99", filter(null), null);
            final AccountViewService.AccountViewResult recognised =
                    service.viewAccount("DFHPF4", filter(null), null);

            assertThat(unmapped.errorMessage()).isEqualTo(EXPECTED_INVALID_KEY_MESSAGE);
            assertThat(encodedWidthOf(unmapped.errorMessage()))
                    .as("the shared message keeps its own contractual width and is not re-padded to 75")
                    .isEqualTo(COMMON_MESSAGE_WIDTH);
            assertThat(encodedWidthOf(unmapped.errorMessage())).isNotEqualTo(MESSAGE_WIDTH);
            assertThat(unmapped.errorMessage())
                    .as("the ten trailing spaces are part of the screen contract and are never trimmed")
                    .endsWith(" ".repeat(10));
            assertThat(unmapped.errorFlag())
                    .as("the client is told its key was not understood")
                    .isTrue();
            assertThat(unmapped.resolvedRoute())
                    .as("no route change: the turn re-arms this screen exactly as a recognised key does")
                    .contains(THIS_SCREEN_ROUTE);
            assertThat(unmapped.resolvedRoute()).isEqualTo(recognised.resolvedRoute());
            assertThat(recognised.errorMessage())
                    .as("a recognised identifier leaves the message field off")
                    .isEqualTo(EXPECTED_MESSAGE_OFF);
            verifyNoInteractions(accountRepository, customerRepository, crossReferenceRepository);
        }

        @Test
        @DisplayName("the unmapped-key text is written under the first-writer-wins guard, so it survives "
                + "a fully resolved turn that would otherwise leave the field off")
        void theUnmappedKeyTextSurvivesAResolvedTurn() {
            when(messageCatalogService.invalidKeyMessage()).thenReturn(EXPECTED_INVALID_KEY_MESSAGE);
            givenAccountResolves("750");

            final AccountViewService.AccountViewResult result =
                    service.viewAccount("DFHPF99", filter(ACCOUNT_ID), reSubmission());

            assertThat(result.errorMessage()).isEqualTo(EXPECTED_INVALID_KEY_MESSAGE);
            assertThat(result.accountFoundInMaster())
                    .as("the coercion to enter is untouched, so the reads still happen")
                    .isTrue();
            assertThat(result.customerFoundInMaster()).isTrue();
            assertThat(result.errorFlag()).isTrue();
            assertThat(result.accountFilterFlag().isValid())
                    .as("the legacy input flag keeps its own semantics: the filter itself was accepted")
                    .isTrue();
        }

        @Test
        @DisplayName("an absent identifier is not an unmapped one: the turn proceeds with whatever action "
                + "the work area still carries and no key message is raised")
        void anAbsentIdentifierRaisesNoKeyMessage() {
            final AccountViewService.AccountViewResult result =
                    service.viewAccount(null, filter(null), reSubmission());

            assertThat(result.errorMessage()).isEqualTo(EXPECTED_NO_INPUT_MESSAGE);
            assertThat(result.errorMessage()).isNotEqualTo(EXPECTED_INVALID_KEY_MESSAGE);
            assertThat(result.presentation()).isEqualTo(AccountViewService.Presentation.MAP);
        }

        @Test
        @DisplayName("a blank identifier is likewise not an unmapped one, since a blank field is the "
                + "absent state a fixed-width field holds rather than a value")
        void aBlankIdentifierRaisesNoKeyMessage() {
            final AccountViewService.AccountViewResult result =
                    service.viewAccount("     ", filter(null), reSubmission());

            assertThat(result.errorMessage()).isEqualTo(EXPECTED_NO_INPUT_MESSAGE);
            assertThat(result.errorMessage()).isNotEqualTo(EXPECTED_INVALID_KEY_MESSAGE);
        }
    }

    // ==================================================================================================

    /**
     * The registered abend handler, paragraph {@code ABEND-ROUTINE} at line 916, reached through the
     * registration at lines 264 to 266 and ending in an abend at line 934.
     *
     * <p>The legacy handler transmits its abend structure and only <em>then</em> abends, so the ordering is
     * the contract: emit first, raise second. Because the failure types carry no logger of their own, a
     * diagnostic written after the raise would be written by whatever caught the raise, if anything did.
     * These tests therefore prove <strong>ordering</strong> and not co-occurrence, by reading the captured
     * diagnostics at the moment the raise is delegated.
     */
    @Nested
    @DisplayName("the abend path")
    final class AbendPath {

        @Test
        @DisplayName("the diagnostic is already written when the raise is delegated, so the handler emits "
                + "before it abends rather than alongside it")
        void theDiagnosticIsWrittenBeforeTheRaiseIsDelegated() {
            final RuntimeException unexpectedFailure = new IllegalStateException("store unavailable");
            when(crossReferenceRepository.findFirstByXrefAcctIdOrderByXrefCardNumAsc(ACCOUNT_ID))
                    .thenThrow(unexpectedFailure);
            final List<String> diagnosticsAtDelegation = new ArrayList<>();
            Mockito.doAnswer(delegation -> {
                diagnosticsAtDelegation.addAll(snapshotOfRecordedMessages());
                return null;
            }).when(abendService).abendOnline(any(), any(), any());

            assertThatThrownBy(() -> submit(ACCOUNT_ID)).isSameAs(unexpectedFailure);

            assertThat(diagnosticsAtDelegation)
                    .as("the handler's diagnostic was already recorded when the raiser was called")
                    .anyMatch(message -> message.contains(ABEND_DIAGNOSTIC_FRAGMENT));
        }

        @Test
        @DisplayName("the abend context reaches the raiser: this member as the culprit, the migration's "
                + "reason, and the blank operator message the legacy actually transmits")
        void theAbendContextReachesTheRaiser() {
            when(crossReferenceRepository.findFirstByXrefAcctIdOrderByXrefCardNumAsc(ACCOUNT_ID))
                    .thenThrow(new IllegalStateException("store unavailable"));
            final ArgumentCaptor<String> culprit = ArgumentCaptor.forClass(String.class);
            final ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);
            final ArgumentCaptor<String> operatorMessage = ArgumentCaptor.forClass(String.class);

            assertThatThrownBy(() -> submit(ACCOUNT_ID))
                    .isInstanceOf(IllegalStateException.class);

            verify(abendService).abendOnline(culprit.capture(), reason.capture(),
                    operatorMessage.capture());
            assertThat(culprit.getValue()).isEqualTo(THIS_PROGRAM);
            assertThat(reason.getValue()).isEqualTo(EXPECTED_ABEND_REASON);
            assertThat(operatorMessage.getValue())
                    .as("the handler's substitution test never matches, so the blank field is what is "
                            + "transmitted and the default text is never supplied")
                    .isEqualTo(" ".repeat(ABEND_MESSAGE_WIDTH));
            assertThat(operatorMessage.getValue()).isNotEqualTo(AbendException.DEFAULT_MESSAGE);
        }

        @Test
        @DisplayName("the diagnostic names the abend code, the culprit, the failing operation and the "
                + "resource in flight, and carries the raw status when a read has reported one")
        void theDiagnosticNamesTheRawStatusAndTheResourceInFlight() {
            when(crossReferenceRepository.findFirstByXrefAcctIdOrderByXrefCardNumAsc(ACCOUNT_ID))
                    .thenReturn(Optional.of(crossReferenceRow(FIRST_CARD_NUMBER, CUSTOMER_ID,
                            ACCOUNT_ID)));
            when(accountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.empty());
            when(customerRepository.findById(CUSTOMER_ID))
                    .thenThrow(new IllegalStateException("store unavailable"));

            assertThatThrownBy(() -> submit(ACCOUNT_ID))
                    .isInstanceOf(IllegalStateException.class);

            assertThat(recordedLogText())
                    .contains(ABEND_DIAGNOSTIC_FRAGMENT)
                    .contains("abendCode=" + AbendException.ONLINE_ABEND_CODE)
                    .contains("culprit=" + THIS_PROGRAM)
                    .contains("reason=" + EXPECTED_ABEND_REASON)
                    .contains("fileStatus=" + NOT_FOUND_STATUS)
                    .contains("operation=READ")
                    .contains("resource=CUSTDAT");
            assertThat(recordedLogText())
                    .as("the failure's own message is withheld, only its type chain being published")
                    .doesNotContain("store unavailable");
        }

        @Test
        @DisplayName("an abend already in flight is rethrown unchanged and is never handled a second "
                + "time, which is what the legacy deregistration amounts to")
        void anAbendAlreadyInFlightIsRethrownUnchanged() {
            final AbendException alreadyAbending =
                    new AbendException(THIS_PROGRAM, "RAISED FURTHER DOWN THE STACK");
            when(crossReferenceRepository.findFirstByXrefAcctIdOrderByXrefCardNumAsc(ACCOUNT_ID))
                    .thenThrow(alreadyAbending);

            assertThatThrownBy(() -> submit(ACCOUNT_ID)).isSameAs(alreadyAbending);

            verifyNoInteractions(abendService);
            assertThat(recordedAnyMessageContaining(ABEND_DIAGNOSTIC_FRAGMENT))
                    .as("the handler is not re-entered, so it writes no second diagnostic")
                    .isFalse();
        }

        @Test
        @DisplayName("a failing read is not an abend: the read-error arm composes the fixed error text, "
                + "records the operation and the resource, and lets the screen come back")
        void aFailingReadComposesTheErrorTextRatherThanAbending() {
            // The legacy final arm of each read decision sends a screen text and does not abend, so a
            // store failure the repository reports as such must not reach the handler.
            when(crossReferenceRepository.findFirstByXrefAcctIdOrderByXrefCardNumAsc(ACCOUNT_ID))
                    .thenThrow(new DataAccessResourceFailureException("connection reset"));

            final AccountViewService.AccountViewResult result = submit(ACCOUNT_ID);

            // The fixed structure is 80 characters into a 75-character field, so the five-space trailer
            // is what disappears. Both response slots stay blank, the relational store having no
            // counterpart for the legacy response pair.
            assertThat(result.errorMessage()).isEqualTo("File Error: " + "READ    " + " on "
                    + "CXACAIX  " + " returned RESP " + " ".repeat(10) + ",RESP2 " + " ".repeat(10));
            assertThat(encodedWidthOf(result.errorMessage())).isEqualTo(MESSAGE_WIDTH);
            assertThat(result.errorFlag()).isTrue();
            assertThat(result.accountFilterFlag().isNotOk()).isTrue();
            assertThat(result.presentation()).isEqualTo(AccountViewService.Presentation.MAP);
            assertThat(recordedLogText())
                    .contains("operation=READ")
                    .contains("resource=CXACAIX");
            assertThat(recordedLogText())
                    .as("the failure's own message is withheld here too")
                    .doesNotContain("connection reset");
            verifyNoInteractions(abendService, accountRepository, customerRepository);
        }
    }

    // ==================================================================================================

    /**
     * The shape of the join: three explicit reads in the legacy order, once each, and nothing else.
     *
     * <p>This module declares no association anywhere - no mapped relationship, no fetch graph, no join
     * fetch - so the account-to-customer join <em>is</em> the sequence of calls, and the sequence is the
     * behaviour. The cross-reference is read on the account identifier, the account master on the same
     * identifier, and the customer master on the identifier the cross-reference yielded, which is why the
     * third call cannot be reordered ahead of the first.
     */
    @Nested
    @DisplayName("the interaction contract")
    final class InteractionContract {

        @Test
        @DisplayName("the three reads happen in the legacy order: cross-reference, then account master, "
                + "then customer master")
        void theThreeReadsHappenInTheLegacyOrder() {
            givenAccountResolves("750");

            submit(ACCOUNT_ID);

            final InOrder sequence =
                    inOrder(crossReferenceRepository, accountRepository, customerRepository);
            sequence.verify(crossReferenceRepository)
                    .findFirstByXrefAcctIdOrderByXrefCardNumAsc(ACCOUNT_ID);
            sequence.verify(accountRepository).findById(ACCOUNT_ID);
            sequence.verify(customerRepository).findById(CUSTOMER_ID);
            sequence.verifyNoMoreInteractions();
        }

        @Test
        @DisplayName("each master is read exactly once on a resolved turn, with no speculative extra read "
                + "and no repeated one")
        void eachMasterIsReadExactlyOnce() {
            givenAccountResolves("750");

            submit(ACCOUNT_ID);

            verify(crossReferenceRepository, times(1))
                    .findFirstByXrefAcctIdOrderByXrefCardNumAsc(ACCOUNT_ID);
            verify(accountRepository, times(1)).findById(ACCOUNT_ID);
            verify(customerRepository, times(1)).findById(CUSTOMER_ID);
            verifyNoMoreInteractions(crossReferenceRepository, accountRepository, customerRepository);
        }

        @Test
        @DisplayName("the account-keyed lookup this member genuinely performs receives the validated "
                + "filter, and the customer lookup receives the identifier the row yielded")
        void theKeysPassedToEachReadAreTheOnesTheMemberPrepares() {
            givenAccountResolves("750");
            final ArgumentCaptor<String> accountPathKey = ArgumentCaptor.forClass(String.class);
            final ArgumentCaptor<String> accountMasterKey = ArgumentCaptor.forClass(String.class);
            final ArgumentCaptor<String> customerMasterKey = ArgumentCaptor.forClass(String.class);

            submit(ACCOUNT_ID);

            verify(crossReferenceRepository)
                    .findFirstByXrefAcctIdOrderByXrefCardNumAsc(accountPathKey.capture());
            verify(accountRepository).findById(accountMasterKey.capture());
            verify(customerRepository).findById(customerMasterKey.capture());
            assertThat(accountPathKey.getValue())
                    .as("the account-keyed read over the cross-reference path takes the filter as keyed")
                    .isEqualTo(ACCOUNT_ID);
            assertThat(accountMasterKey.getValue())
                    .as("the key is prepared once and both account-keyed reads use it")
                    .isEqualTo(accountPathKey.getValue());
            assertThat(customerMasterKey.getValue())
                    .as("the customer key is the identifier the cross-reference row yielded")
                    .isEqualTo(CUSTOMER_ID);
            assertThat(customerMasterKey.getValue()).isNotEqualTo(accountPathKey.getValue());
        }

        @Test
        @DisplayName("no ordering is supplied by this service and none is imposed by a repository: the "
                + "one ordered read declares its order in the finder itself")
        void noOrderingIsSuppliedByThisService() {
            givenAccountResolves("750");

            submit(ACCOUNT_ID);

            // Three keyed reads and nothing else. There is no sequential or paged read on this screen, so
            // there is no sort specification to hand over; the only ordering that matters is the ascending
            // base-key order the cross-reference finder declares, which the query carries rather than the
            // caller. Any paging or sorting call would show up here as an unverified interaction.
            verify(crossReferenceRepository)
                    .findFirstByXrefAcctIdOrderByXrefCardNumAsc(ACCOUNT_ID);
            verify(accountRepository).findById(ACCOUNT_ID);
            verify(customerRepository).findById(CUSTOMER_ID);
            verifyNoMoreInteractions(crossReferenceRepository, accountRepository, customerRepository);
        }

        @Test
        @DisplayName("a rejected filter reaches no repository at all, so an edit failure cannot put load "
                + "on the store")
        void aRejectedFilterReachesNoRepository() {
            final AccountViewService.AccountViewResult result = submit("0000000001A");

            assertThat(result.errorMessage()).isEqualTo(EXPECTED_NOT_NUMERIC_MESSAGE);
            verifyNoInteractions(crossReferenceRepository, accountRepository, customerRepository,
                    abendService);
        }
    }

    // ==================================================================================================

    /**
     * The filter edits, paragraphs {@code 2200-EDIT-MAP-INPUTS} at line 622 and
     * {@code 2210-EDIT-ACCOUNT} at line 649, in the order the source performs them.
     */
    @Nested
    @DisplayName("the filter edits")
    final class FilterEdits {

        @Test
        @DisplayName("a blank filter shows the shorter unguarded text, not the prompt the field edit "
                + "assigned moments earlier, because the cross-field edit carries no message-off guard")
        void aBlankFilterShowsTheUnguardedText() {
            final AccountViewService.AccountViewResult result = submit(" ".repeat(11));

            assertThat(result.errorMessage()).isEqualTo(EXPECTED_NO_INPUT_MESSAGE);
            assertThat(result.errorMessage())
                    .as("the guarded prompt is overwritten, so it is not what a screen shows")
                    .isNotEqualTo(EXPECTED_ACCOUNT_NOT_PROVIDED_MESSAGE);
            assertThat(AccountViewService.PROMPT_FOR_ACCOUNT_MESSAGE)
                    .as("the overwritten text is published all the same, since the assignment is real")
                    .isEqualTo(EXPECTED_ACCOUNT_NOT_PROVIDED_MESSAGE);
            assertThat(result.errorFlag()).isTrue();
            assertThat(result.accountFilterFlag().isBlank()).isTrue();
            assertThat(result.accountIdFilter())
                    .as("a blank filter flag blanks the echoed field")
                    .isNull();
            assertThat(result.filterMissingOnReEntry())
                    .as("blank on a re-entry is the state the asterisk marker is written for")
                    .isTrue();
            assertThat(result.navigationContext().accountId())
                    .as("the carried identifier is zeroed at the field's declared width")
                    .isEqualTo("0".repeat(11));
            verifyNoInteractions(crossReferenceRepository);
        }

        @Test
        @DisplayName("an asterisk is recognised as the reset marker and read as a blank filter, and the "
                + "marker itself is never written back from here")
        void anAsteriskIsTheResetMarker() {
            final AccountViewService.AccountViewResult result = submit("*");

            assertThat(result.errorMessage()).isEqualTo(EXPECTED_NO_INPUT_MESSAGE);
            assertThat(result.accountFilterFlag().isBlank()).isTrue();
            assertThat(result.accountIdFilter()).isNull();
            assertThat(result.errorMessage())
                    .as("the marker is the presentation layer's to add and never appears in the text")
                    .doesNotContain("*");
        }

        // The last value ends in an Arabic-Indic digit, written as an escape so this file stays ASCII. A
        // library digit predicate accepts that code point; the legacy numeric test, written against the
        // ASCII digit range over a single-byte field, never would.
        @ParameterizedTest(name = "filter [{0}] is rejected as non-numeric or as all zeros")
        @ValueSource(strings = {"0000000001A", "00000000000", "1234567890 ", "-0000000001",
            "0000000001\u0663"})
        @DisplayName("a filter that is not wholly composed of the eleven ASCII digits, or is all zeros, "
                + "is rejected with the literal the source moves - double space and all")
        void aNonNumericOrZeroFilterIsRejected(final String keyed) {
            final AccountViewService.AccountViewResult result = submit(keyed);

            assertThat(result.errorMessage()).isEqualTo(EXPECTED_NOT_NUMERIC_MESSAGE);
            assertThat(result.errorMessage())
                    .as("the moved literal carries a double space after the third word")
                    .contains("must  be");
            assertThat(encodedWidthOf(result.errorMessage())).isEqualTo(MESSAGE_WIDTH);
            assertThat(result.errorFlag()).isTrue();
            assertThat(result.accountFilterFlag().isNotOk()).isTrue();
            assertThat(result.filterInError()).isTrue();
            assertThat(result.navigationContext().accountId()).isEqualTo("0".repeat(11));
            verifyNoInteractions(crossReferenceRepository, accountRepository, customerRepository);
        }

        @Test
        @DisplayName("a filter of eleven ASCII digits that is not all zeros is accepted and carried, and "
                + "the echoed field carries it back")
        void anElevenDigitFilterIsAccepted() {
            givenAccountResolves("750");

            final AccountViewService.AccountViewResult result = submit(ACCOUNT_ID);

            assertThat(result.accountFilterFlag().isValid()).isTrue();
            assertThat(result.filterInError()).isFalse();
            assertThat(result.filterMissingOnReEntry()).isFalse();
            assertThat(result.accountIdFilter()).isEqualTo(ACCOUNT_ID);
            assertThat(result.navigationContext().accountId()).isEqualTo(ACCOUNT_ID);
            assertThat(result.errorFlag()).isFalse();
        }
    }

    // ==================================================================================================

    /**
     * Boundary and absent input, in the terms a fixed-width screen field understands.
     *
     * <p>The filter is taken at its declared eleven-character width before any edit runs, so a shorter
     * value arrives space-padded and a longer one arrives truncated. Both consequences are observable and
     * both are asserted, because a length rule invented in the translation would reject or accept input
     * the legacy does not.
     */
    @Nested
    @DisplayName("boundary and absent input")
    final class BoundaryInput {

        @Test
        @DisplayName("an absent filter is the blank path and reaches no repository, with no failure "
                + "escaping for the absent reference")
        void anAbsentFilterIsTheBlankPath() {
            final AccountViewService.AccountViewResult result = submit(null);

            assertThat(result.accountFilterFlag().isBlank()).isTrue();
            assertThat(result.errorMessage()).isEqualTo(EXPECTED_NO_INPUT_MESSAGE);
            verifyNoInteractions(crossReferenceRepository, accountRepository, customerRepository);
        }

        @Test
        @DisplayName("all three arguments may be absent at once: the work area is read as an all-absent "
                + "one and the carried state as the zero-length area the legacy tests for")
        void allThreeArgumentsMayBeAbsent() {
            final AccountViewService.AccountViewResult result = service.viewAccount(null, null, null);

            assertThat(result).isNotNull();
            assertThat(result.presentation()).isEqualTo(AccountViewService.Presentation.MAP);
            assertThat(result.infoMessage()).isEqualTo(EXPECTED_INPUT_PROMPT);
            assertThat(result.errorMessage()).isEqualTo(EXPECTED_MESSAGE_OFF);
            assertThat(result.resolvedRoute()).contains(THIS_SCREEN_ROUTE);
            verifyNoInteractions(crossReferenceRepository, accountRepository, customerRepository,
                    abendService);
        }

        @Test
        @DisplayName("a filter shorter than the field is space-padded to the declared width and is "
                + "therefore rejected as not wholly numeric, not silently accepted")
        void aShortFilterIsPaddedAndRejected() {
            final AccountViewService.AccountViewResult result = submit("123");

            assertThat(result.errorMessage())
                    .as("the padded field holds spaces, and a space is not one of the eleven digits")
                    .isEqualTo(EXPECTED_NOT_NUMERIC_MESSAGE);
            assertThat(result.accountFilterFlag().isNotOk()).isTrue();
            verifyNoInteractions(crossReferenceRepository, accountRepository, customerRepository);
        }

        @Test
        @DisplayName("a filter longer than the field is taken at the declared width, so the twelfth "
                + "character is discarded and the truncated value is what is read")
        void aLongFilterIsTruncatedToTheDeclaredWidth() {
            when(crossReferenceRepository.findFirstByXrefAcctIdOrderByXrefCardNumAsc(ACCOUNT_ID))
                    .thenReturn(Optional.empty());

            final AccountViewService.AccountViewResult result = submit(ACCOUNT_ID + "9");

            assertThat(result.navigationContext().accountId())
                    .as("eleven characters are carried, never twelve")
                    .isEqualTo(ACCOUNT_ID);
            assertThat(encodedWidthOf(result.navigationContext().accountId())).isEqualTo(11);
            assertThat(result.errorMessage())
                    .as("the read used the truncated key, so the miss text names the truncated value")
                    .isEqualTo(EXPECTED_XREF_MISS_MESSAGE);
            verify(crossReferenceRepository).findFirstByXrefAcctIdOrderByXrefCardNumAsc(ACCOUNT_ID);
        }

        @Test
        @DisplayName("the identifier slot of a miss text carries the key that was actually read, at its "
                + "declared eleven-character width and never trimmed")
        void theMissTextCarriesTheKeyThatWasRead() {
            // A second accepted identifier, so the slot is proved to carry the key rather than a constant.
            when(crossReferenceRepository.findFirstByXrefAcctIdOrderByXrefCardNumAsc("00000000001"))
                    .thenReturn(Optional.empty());

            final AccountViewService.AccountViewResult result = submit("00000000001");

            assertThat(result.errorMessage())
                    .isEqualTo("Account:" + "00000000001" + " not found in"
                            + " Cross ref file.  Resp:" + NOT_FOUND_STATUS + " ".repeat(8) + " Reas:"
                            + " ".repeat(4));
            assertThat(encodedWidthOf(result.errorMessage())).isEqualTo(MESSAGE_WIDTH);
        }
    }

    // ==================================================================================================

    /**
     * What a turn hands back on the remaining paths, and the published value types the result is built
     * from.
     *
     * <p>Two of this member's terminal paragraphs are not reachable through the entry point and are
     * covered here on their own terms rather than left unexercised: the long-text exit at line 896, whose
     * every caller is commented out, and the unexpected-context arm at lines 375 to 382, which needs a
     * context state the two-state carried context cannot represent.
     */
    @Nested
    @DisplayName("the remaining paths and the published value types")
    final class ScreenAndValueContract {

        @Test
        @DisplayName("a turn with no carried state starts a fresh conversation: the prompt is shown, no "
                + "master is read, and the next turn is armed as a re-entry")
        void aTurnWithNoCarriedStatePromptsAndReadsNothing() {
            final AccountViewService.AccountViewResult result =
                    service.viewAccount("DFHENTER", filter(null), null);

            assertThat(result.presentation()).isEqualTo(AccountViewService.Presentation.MAP);
            assertThat(result.infoMessage()).isEqualTo(EXPECTED_INPUT_PROMPT);
            assertThat(result.errorMessage()).isEqualTo(EXPECTED_MESSAGE_OFF);
            assertThat(result.resolvedAccount()).isEmpty();
            assertThat(result.resolvedCustomer()).isEmpty();
            assertThat(result.reEnter())
                    .as("transmitting the screen is what makes the next turn a re-entry")
                    .isTrue();
            assertThat(result.screenHeader()).isNotNull();
            assertThat(result.errorFlag()).isFalse();
            verifyNoInteractions(crossReferenceRepository, accountRepository, customerRepository);
        }

        @Test
        @DisplayName("arriving from the user main menu on a turn that is not a re-entry discards the "
                + "carried state, which is the second arm of the adoption test")
        void arrivingFromTheMenuOnAFirstEntryDiscardsTheCarriedState() {
            final AccountViewService.AccountViewResult result = service.viewAccount("DFHENTER",
                    filter(ACCOUNT_ID), fromMenu(UserType.USER.getCode(),
                            ScreenNavigationState.ProgramContext.ENTER));

            assertThat(result.infoMessage()).isEqualTo(EXPECTED_INPUT_PROMPT);
            assertThat(result.navigationContext().accountId())
                    .as("the discarded state is reinitialised, so nothing is carried into the new one")
                    .isNull();
            assertThat(result.navigationContext().userId()).isNull();
            assertThat(result.resolvedAccount()).isEmpty();
            verifyNoInteractions(crossReferenceRepository, accountRepository, customerRepository);
        }

        @Test
        @DisplayName("the long-text exit ends the conversation without re-arming anything, which is why "
                + "it resolves no route, and it carries the 500-character field untrimmed")
        void theLongTextExitResolvesNoRoute() {
            // Defined and unwired: all three statements that would perform this paragraph are commented
            // out in the member, so it is exercised here rather than by a live path - the same treatment
            // the plan gives the batch program that no job stream invokes.
            final AccountViewService.AccountViewResult result =
                    service.sendLongText(new AccountViewService.WorkingStorage());

            assertThat(result.presentation()).isEqualTo(AccountViewService.Presentation.LONG_TEXT);
            assertThat(result.resolvedRoute())
                    .as("nothing is re-armed, so there is no next turn for a route to name")
                    .isEmpty();
            assertThat(result.longMessage()).isNotNull();
            assertThat(encodedWidthOf(result.longMessage()))
                    .as("the long diagnostic field keeps its declared width")
                    .isEqualTo(LONG_MESSAGE_WIDTH);
            assertThat(result.longMessage()).isEqualTo(" ".repeat(LONG_MESSAGE_WIDTH));
            verifyNoInteractions(crossReferenceRepository, accountRepository, customerRepository,
                    abendService);
        }

        @Test
        @DisplayName("the unexpected-context arm's published values are the ones the member fills in, and "
                + "the arm itself needs a context the carried state cannot express")
        void theUnexpectedContextArmPublishesWhatTheMemberFillsIn() {
            // The arm fills an abend structure and then does not abend: it sends plain text and ends the
            // task. Its code is distinct from the one the handler abends with, and neither the code nor
            // the text can be reached from the entry point, because the carried context has exactly two
            // states and both are claimed by the arms above it.
            assertThat(AccountViewService.UNEXPECTED_DATA_SCENARIO_ABEND_CODE).isEqualTo("0001");
            assertThat(AccountViewService.UNEXPECTED_DATA_SCENARIO_ABEND_CODE)
                    .isNotEqualTo(AbendException.ONLINE_ABEND_CODE);
            assertThat(AccountViewService.UNEXPECTED_DATA_SCENARIO_MESSAGE)
                    .isEqualTo("UNEXPECTED DATA SCENARIO" + " ".repeat(51));
            assertThat(encodedWidthOf(AccountViewService.UNEXPECTED_DATA_SCENARIO_MESSAGE))
                    .isEqualTo(MESSAGE_WIDTH);
        }

        @Test
        @DisplayName("this screen's published identity and the two resource names it reads are the "
                + "literals the member declares")
        void theScreenIdentityAndResourceNamesArePublished() {
            assertThat(AccountViewService.TRANSACTION_ID).isEqualTo(THIS_TRANSACTION);
            assertThat(AccountViewService.PROGRAM_NAME).isEqualTo(THIS_PROGRAM);
            assertThat(AccountViewService.MAPSET_NAME)
                    .as("the eight-character literal is moved into a seven-wide field, so seven survive")
                    .isEqualTo("COACTVW");
            assertThat(AccountViewService.MAP_NAME).isEqualTo("CACTVWA");
            assertThat(AccountViewService.MENU_PROGRAM_NAME).isEqualTo(MENU_PROGRAM);
            assertThat(AccountViewService.MENU_TRANSACTION_ID).isEqualTo(MENU_TRANSACTION);
            assertThat(AccountViewService.ACCOUNT_FILE_NAME).isEqualTo("ACCTDAT");
            assertThat(AccountViewService.CUSTOMER_FILE_NAME).isEqualTo("CUSTDAT");
            assertThat(AccountViewService.READ_OPERATION).isEqualTo("READ");
            assertThat(AccountViewService.ACCOUNT_ID_SCREEN_FIELD_ID).isEqualTo(FILTER_FIELD_ID);
            assertThat(AccountViewService.ACCOUNT_ID_WIDTH).isEqualTo(11);
            assertThat(AccountViewService.CUSTOMER_ID_WIDTH).isEqualTo(9);
            assertThat(AccountViewService.RETURN_MESSAGE_WIDTH).isEqualTo(MESSAGE_WIDTH);
            assertThat(AccountViewService.INFO_MESSAGE_WIDTH).isEqualTo(INFO_WIDTH);
            assertThat(AccountViewService.LONG_MESSAGE_WIDTH).isEqualTo(LONG_MESSAGE_WIDTH);
            assertThat(AccountViewService.RESPONSE_CODE_WIDTH).isEqualTo(10);
            assertThat(AccountViewService.OPERATION_NAME_WIDTH).isEqualTo(8);
            assertThat(AccountViewService.ERROR_FILE_NAME_WIDTH).isEqualTo(9);
        }

        @Test
        @DisplayName("the three off-state fields are spaces at their declared widths, so a field that is "
                + "off is a value rather than an absence")
        void theOffStateFieldsAreSpacesAtTheirDeclaredWidths() {
            assertThat(AccountViewService.RETURN_MESSAGE_OFF).isEqualTo(EXPECTED_MESSAGE_OFF);
            assertThat(encodedWidthOf(AccountViewService.RETURN_MESSAGE_OFF)).isEqualTo(MESSAGE_WIDTH);
            assertThat(AccountViewService.INFO_MESSAGE_OFF).isEqualTo(" ".repeat(INFO_WIDTH));
            assertThat(encodedWidthOf(AccountViewService.INFO_MESSAGE_OFF)).isEqualTo(INFO_WIDTH);
            assertThat(AccountViewService.LONG_MESSAGE_OFF).isEqualTo(" ".repeat(LONG_MESSAGE_WIDTH));
            assertThat(encodedWidthOf(AccountViewService.LONG_MESSAGE_OFF))
                    .isEqualTo(LONG_MESSAGE_WIDTH);
            assertThat(AccountViewService.PROMPT_FOR_INPUT_MESSAGE).isEqualTo(EXPECTED_INPUT_PROMPT);
            assertThat(AccountViewService.NO_SEARCH_CRITERIA_MESSAGE)
                    .isEqualTo(EXPECTED_NO_INPUT_MESSAGE);
            assertThat(AccountViewService.ACCOUNT_FILTER_NOT_NUMERIC_MESSAGE)
                    .isEqualTo(EXPECTED_NOT_NUMERIC_MESSAGE);
        }

        @Test
        @DisplayName("the filter flag carries the three declared values and answers one condition name "
                + "each, the space being both a declared value and the initialised state")
        void theFilterFlagCarriesTheThreeDeclaredValues() {
            assertThat(AccountViewService.FilterFlag.NOT_OK.getCode()).isEqualTo('0');
            assertThat(AccountViewService.FilterFlag.VALID.getCode()).isEqualTo('1');
            assertThat(AccountViewService.FilterFlag.BLANK.getCode()).isEqualTo(' ');
            assertThat(AccountViewService.FilterFlag.NOT_OK.isNotOk()).isTrue();
            assertThat(AccountViewService.FilterFlag.NOT_OK.isValid()).isFalse();
            assertThat(AccountViewService.FilterFlag.NOT_OK.isBlank()).isFalse();
            assertThat(AccountViewService.FilterFlag.VALID.isValid()).isTrue();
            assertThat(AccountViewService.FilterFlag.VALID.isNotOk()).isFalse();
            assertThat(AccountViewService.FilterFlag.VALID.isBlank()).isFalse();
            assertThat(AccountViewService.FilterFlag.BLANK.isBlank()).isTrue();
            assertThat(AccountViewService.FilterFlag.BLANK.isNotOk()).isFalse();
            assertThat(AccountViewService.FilterFlag.BLANK.isValid()).isFalse();
        }

        @Test
        @DisplayName("the input flag and the attention-key flag each carry their declared values, with "
                + "one not-yet-decided state standing for both characters that describe it")
        void theInputAndKeyFlagsCarryTheirDeclaredValues() {
            assertThat(AccountViewService.InputFlag.OK.getCode()).isEqualTo('0');
            assertThat(AccountViewService.InputFlag.ERROR.getCode()).isEqualTo('1');
            assertThat(AccountViewService.InputFlag.PENDING.getCode())
                    .isEqualTo(Character.MIN_VALUE);
            assertThat(AccountViewService.InputFlag.OK.isOk()).isTrue();
            assertThat(AccountViewService.InputFlag.OK.isError()).isFalse();
            assertThat(AccountViewService.InputFlag.OK.isPending()).isFalse();
            assertThat(AccountViewService.InputFlag.ERROR.isError()).isTrue();
            assertThat(AccountViewService.InputFlag.ERROR.isOk()).isFalse();
            assertThat(AccountViewService.InputFlag.PENDING.isPending()).isTrue();
            assertThat(AccountViewService.InputFlag.PENDING.isError()).isFalse();

            assertThat(AccountViewService.KeyValidity.VALID.getCode()).isEqualTo('0');
            assertThat(AccountViewService.KeyValidity.INVALID.getCode()).isEqualTo('1');
            assertThat(AccountViewService.KeyValidity.PENDING.getCode())
                    .isEqualTo(Character.MIN_VALUE);
            assertThat(AccountViewService.KeyValidity.VALID.isValid()).isTrue();
            assertThat(AccountViewService.KeyValidity.VALID.isInvalid()).isFalse();
            assertThat(AccountViewService.KeyValidity.VALID.isPending()).isFalse();
            assertThat(AccountViewService.KeyValidity.INVALID.isInvalid()).isTrue();
            assertThat(AccountViewService.KeyValidity.INVALID.isValid()).isFalse();
            assertThat(AccountViewService.KeyValidity.PENDING.isPending()).isTrue();
            assertThat(AccountViewService.KeyValidity.PENDING.isInvalid()).isFalse();
        }

        @Test
        @DisplayName("the read outcome names the three arms each read decision declares, so a missing row "
                + "and a failing read never collapse into one")
        void theReadOutcomeNamesTheThreeArms() {
            assertThat(AccountViewService.ReadOutcome.FOUND.isFound()).isTrue();
            assertThat(AccountViewService.ReadOutcome.FOUND.isNotFound()).isFalse();
            assertThat(AccountViewService.ReadOutcome.FOUND.isReadError()).isFalse();
            assertThat(AccountViewService.ReadOutcome.NOT_FOUND.isNotFound()).isTrue();
            assertThat(AccountViewService.ReadOutcome.NOT_FOUND.isFound()).isFalse();
            assertThat(AccountViewService.ReadOutcome.NOT_FOUND.isReadError()).isFalse();
            assertThat(AccountViewService.ReadOutcome.READ_ERROR.isReadError()).isTrue();
            assertThat(AccountViewService.ReadOutcome.READ_ERROR.isFound()).isFalse();
            assertThat(AccountViewService.ReadOutcome.READ_ERROR.isNotFound()).isFalse();
            assertThat(AccountViewService.Presentation.values())
                    .containsExactly(AccountViewService.Presentation.MAP,
                            AccountViewService.Presentation.PLAIN_TEXT,
                            AccountViewService.Presentation.LONG_TEXT,
                            AccountViewService.Presentation.TRANSFER);
        }

        @Test
        @DisplayName("a stringified result withholds the account, the customer and the filter, so a "
                + "diagnostic that renders one cannot leak an identifier or a regulated value")
        void aStringifiedResultWithholdsEveryIdentifier() {
            givenAccountResolves("750");

            final String rendered = submit(ACCOUNT_ID).toString();

            assertThat(rendered).contains("route=" + THIS_SCREEN_ROUTE);
            assertThat(rendered).contains("***REDACTED***");
            assertThat(rendered)
                    .as("neither entity is rendered, both carrying values that identify a person")
                    .doesNotContain(ACCOUNT_ID)
                    .doesNotContain(CUSTOMER_ID)
                    .doesNotContain(FIRST_CARD_NUMBER);
            assertThat(rendered).contains("presentation=MAP");
        }
    }
}
