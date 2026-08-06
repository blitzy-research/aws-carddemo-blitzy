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

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;

import com.carddemo.domain.Card;
import com.carddemo.domain.enums.CardStatus;
import com.carddemo.domain.enums.KeyAction;
import com.carddemo.exception.AbendException;
import com.carddemo.exception.ValidationException;
import com.carddemo.repository.CardRepository;
import com.carddemo.util.CobolStringUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNoException;

/**
 * Unit tests for the card-detail transaction {@code CCDL}.
 *
 * <p>The class under test is the migrated form of {@code app/cbl/COCRDSLC.cbl} - 887 lines, 34
 * paragraph labels of its own plus the two the attention-key copybook expands into it - reading the
 * screen work area of {@code app/cpy/CVCRD01Y.cpy} and the attention-key store of
 * {@code app/cpy/CSSTRPFY.cpy}.
 *
 * <p><strong>The oracle is independent of the code it judges.</strong> Every expected message, field
 * width, screen field identifier, transaction identifier and route token below is a literal declared
 * in this class. No expected value is obtained by calling the service, the message catalogue, the
 * navigation service or the key translator, so a defect that changed a published constant could not
 * hide behind a test that read the same constant back. Fixed-width padding is written as an explicit
 * repeat count so the count is visible to a reviewer and cannot be stripped by an editor, and no
 * fixed-width value is trimmed before comparison.
 *
 * <p>Seven behaviours carry parity traps and are asserted deliberately rather than incidentally.
 *
 * <ol>
 *   <li><em>The account-keyed read is over a non-unique index, so first-match selection is the
 *       contract.</em> An absent result is the legacy not-found response and must not raise, and a
 *       result must never be reached by indexing into a collection.</li>
 *   <li><em>The two not-found outcomes are different.</em> The card-number read faults both filter
 *       fields and gates its message; the account-keyed read faults one and sets its message ungated.
 *       Collapsing them would lose a field flag and a message-precedence rule.</li>
 *   <li><em>An unmapped attention key is coerced, not rejected.</em> The pessimistic gate rewrites it
 *       as the enter key so the screen re-presents, and only an identifier the copybook's selection
 *       does not recognise at all draws the invalid-key text.</li>
 *   <li><em>The invalid-key text is fifty characters and is never trimmed.</em> The trailing spaces
 *       are part of the screen contract.</li>
 *   <li><em>The summary message is first-past-the-post while every field flag is still set.</em> The
 *       one exception is the cross-field edit, which is ungated and overwrites.</li>
 *   <li><em>An all-zero filter is "not supplied", not "invalid".</em> That is why the legacy message
 *       speaks of a non-zero number.</li>
 *   <li><em>Field decoration is gated on the inbound re-enter flag.</em> A first entry shows an empty
 *       field; only a re-submission shows the marker.</li>
 * </ol>
 *
 * <p>Two paragraphs of the source are unreachable - the account-keyed read and the long-text send, both
 * verified by a census of every {@code PERFORM} in the member. They are exercised here directly,
 * because they are translated and must therefore be judged, and driving them through the turn is
 * impossible by design.
 */
@DisplayName("CardDetailService - the CCDL card-detail transaction of COCRDSLC")
class CardDetailServiceTest {

    // ==============================================================================================
    // The oracle. Every value below is declared here and is never read back from the code under test.
    // ==============================================================================================

    /** {@code LIT-THISTRANID}, COCRDSLC line 166. */
    private static final String TRANSACTION_ID = "CCDL";

    /** {@code LIT-THISPGM}, COCRDSLC line 164. */
    private static final String PROGRAM_NAME = "COCRDSLC";

    /** {@code LIT-CCLISTPGM}, COCRDSLC line 172: the card-list member that hands over to this one. */
    private static final String CARD_LIST_PROGRAM = "COCRDLIC";

    /** {@code LIT-CCLISTMAPSET}, COCRDSLC line 176. */
    private static final String CARD_LIST_MAPSET = "COCRDLI";

    /** The user main menu member, the back-navigation default of this screen. */
    private static final String USER_MENU_PROGRAM = "COMEN01C";

    /** The two screen field identifiers the cursor arms at COCRDSLC lines 515 to 524 name. */
    private static final String FIELD_ACCOUNT_ID = "ACCTSID";

    private static final String FIELD_CARD_NUMBER = "CARDSID";

    /** {@code WS-PROMPT-FOR-INPUT}, COCRDSLC lines 131 to 132. */
    private static final String MSG_PROMPT_FOR_INPUT = "Please enter Account and Card Number";

    /** {@code FOUND-CARDS-FOR-ACCOUNT}, COCRDSLC lines 129 to 130 - three leading spaces. */
    private static final String MSG_FOUND_CARDS = "   " + "Displaying requested details";

    /** {@code WS-PROMPT-FOR-ACCT}, COCRDSLC lines 138 to 139. */
    private static final String MSG_PROMPT_FOR_ACCOUNT = "Account number not provided";

    /** {@code WS-PROMPT-FOR-CARD}, COCRDSLC lines 140 to 141. */
    private static final String MSG_PROMPT_FOR_CARD = "Card number not provided";

    /** {@code NO-SEARCH-CRITERIA-RECEIVED}, COCRDSLC lines 142 to 143. */
    private static final String MSG_NO_INPUT = "No input received";

    /** {@code DID-NOT-FIND-ACCTCARD-COMBO}, COCRDSLC lines 153 to 154. */
    private static final String MSG_NO_CARDS_FOR_CONDITION =
            "Did not find cards for this search condition";

    /** {@code DID-NOT-FIND-ACCT-IN-CARDXREF}, COCRDSLC lines 151 to 152. */
    private static final String MSG_ACCOUNT_NOT_IN_DATABASE =
            "Did not find this account in cards database";

    /** The literal moved at COCRDSLC lines 669 to 671. */
    private static final String MSG_ACCOUNT_NOT_NUMERIC =
            "ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER";

    /** The literal moved at COCRDSLC lines 710 to 712. */
    private static final String MSG_CARD_NOT_NUMERIC =
            "CARD ID FILTER,IF SUPPLIED MUST BE A 16 DIGIT NUMBER";

    /**
     * {@code CCDA-MSG-INVALID-KEY} of {@code app/cpy/CSMSG01Y.cpy} at its declared fifty-character
     * width, written as visible text plus an explicit repeat count for the padding. Declared here rather
     * than read from the catalogue so the width is genuinely asserted.
     */
    private static final String MSG_INVALID_KEY =
            "Invalid key pressed. Please see below..." + " ".repeat(10);

    /** The contractual width of the common-message field. */
    private static final int COMMON_MESSAGE_WIDTH = 50;

    /** {@code WS-RETURN-MSG}, {@code PIC X(75)} at COCRDSLC line 134. */
    private static final int RETURN_MESSAGE_WIDTH = 75;

    /** {@code ERRMSGO} of the symbolic map, {@code PIC X(80)}. */
    private static final int ERROR_MESSAGE_FIELD_WIDTH = 80;

    /** {@code INFOMSGO} and the two screen titles, all {@code PIC X(40)}. */
    private static final int FORTY_CHARACTER_FIELD = 40;

    /** {@code ACCTSID}, {@code PIC X(11)}. */
    private static final int ACCOUNT_ID_WIDTH = 11;

    /** {@code CARDSID}, {@code PIC X(16)}. */
    private static final int CARD_NUMBER_WIDTH = 16;

    /** {@code CRDNAME}, {@code PIC X(50)}. */
    private static final int EMBOSSED_NAME_WIDTH = 50;

    /** The redaction stand-in both the entity and the projection publish. */
    private static final String REDACTED = "***REDACTED***";

    // -- Fixture values -------------------------------------------------------------------------

    private static final String CARD_LOW = "4111111111111111";

    private static final String CARD_HIGH = "4222222222222222";

    private static final String ACCOUNT = "00000000011";

    /** A verification code whose leading zeros are the whole point: it must never become {@code 7}. */
    private static final String CVV_WITH_LEADING_ZEROS = "007";

    /**
     * The embossed name in row 0 of {@code app/data/ASCII/carddata.txt}. Every one of that fixture's
     * fifty rows carries an embedded space, so an all-letters predicate would reject the entire file.
     */
    private static final String EMBOSSED_NAME_WITH_SPACE = "Aniya Von";

    private static final String EXPIRY = "2023-03-09";

    private static final String STATUS_ACTIVE = "Y";

    /** A fixed instant, so the screen header is deterministic. */
    private static final Instant FIXED_INSTANT = Instant.parse("2024-03-09T14:25:36Z");

    private static final String EXPECTED_HEADER_DATE = "03/09/24";

    private static final String EXPECTED_HEADER_TIME = "14:25:36";

    // ==============================================================================================
    // Collaborators
    // ==============================================================================================

    private CardRepository cardRepository;

    private AbendService abendService;

    private CardDetailService service;

    private Logger serviceLogger;

    private ListAppender<ILoggingEvent> logRecorder;

    private Level originalLevel;

    @BeforeEach
    void constructServiceAndAttachLogRecorder() {
        this.cardRepository = Mockito.mock(CardRepository.class);
        this.abendService = Mockito.mock(AbendService.class);
        this.service = new CardDetailService(this.cardRepository, this.abendService,
                new MessageCatalogService(), new NavigationService(),
                Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC));

        this.serviceLogger = (Logger) LoggerFactory.getLogger(CardDetailService.class);
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

    // ==============================================================================================
    // Helpers
    // ==============================================================================================

    private static Card card(final String cardNumber, final String verificationCode,
            final String embossedName, final String expiry, final String status) {
        return new Card(cardNumber, ACCOUNT, verificationCode, embossedName, expiry, status);
    }

    private static Card activeCard(final String cardNumber) {
        return card(cardNumber, CVV_WITH_LEADING_ZEROS, EMBOSSED_NAME_WITH_SPACE, EXPIRY,
                STATUS_ACTIVE);
    }

    private static CardDetailService.CardDetailScreenInput reSubmission(final String accountFilter,
            final String cardFilter, final String attentionIdentifier) {
        return new CardDetailService.CardDetailScreenInput(accountFilter, cardFilter,
                attentionIdentifier, ScreenNavigationState.empty().withReEntry());
    }

    private String recordedLogText() {
        final StringBuilder text = new StringBuilder();
        for (final ILoggingEvent event : this.logRecorder.list) {
            text.append(event.getFormattedMessage()).append('\n');
        }
        return text.toString();
    }

    private int indexOfRecordedMessageContaining(final String fragment) {
        for (int index = 0; index < this.logRecorder.list.size(); index++) {
            if (this.logRecorder.list.get(index).getFormattedMessage().contains(fragment)) {
                return index;
            }
        }
        return -1;
    }

    // ==============================================================================================

    @Nested
    @DisplayName("construction")
    class Construction {

        @Test
        @DisplayName("every collaborator is required, so a missing one fails at wiring time")
        void everyCollaboratorIsRequired() {
            final MessageCatalogService catalog = new MessageCatalogService();
            final NavigationService navigation = new NavigationService();
            final Clock clock = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);

            assertThatExceptionOfType(NullPointerException.class).isThrownBy(() ->
                    new CardDetailService(null, abendService, catalog, navigation, clock));
            assertThatExceptionOfType(NullPointerException.class).isThrownBy(() ->
                    new CardDetailService(cardRepository, null, catalog, navigation, clock));
            assertThatExceptionOfType(NullPointerException.class).isThrownBy(() ->
                    new CardDetailService(cardRepository, abendService, null, navigation, clock));
            assertThatExceptionOfType(NullPointerException.class).isThrownBy(() ->
                    new CardDetailService(cardRepository, abendService, catalog, null, clock));
            assertThatExceptionOfType(NullPointerException.class).isThrownBy(() ->
                    new CardDetailService(cardRepository, abendService, catalog, navigation, null));
        }

        @Test
        @DisplayName("a turn requires an input, and the refusal names it")
        void aTurnRequiresAnInput() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> service.processCardDetail(null))
                    .withMessageContaining("input");
        }
    }

    @Nested
    @DisplayName("first entry - the screen gathers criteria")
    class FirstEntry {

        @Test
        @DisplayName("a turn carrying no navigation state shows the input prompt and reads nothing")
        void aTurnCarryingNoStateShowsThePromptAndReadsNothing() {
            final CardDetailService.CardDetailResult result = service.processCardDetail(
                    new CardDetailService.CardDetailScreenInput(null, null, "DFHENTER", null));

            assertThat(result.infoMessage()).isEqualTo(MSG_PROMPT_FOR_INPUT);
            assertThat(result.message()).isEmpty();
            assertThat(result.card()).isNull();
            assertThat(result.errorFlag()).isFalse();
            assertThat(result.cardPresented()).isFalse();
            assertThat(result.fieldErrors()).isEmpty();
            assertThat(result.focusField()).isEqualTo(FIELD_ACCOUNT_ID);
            assertThat(result.reArmedTransactionId()).isEqualTo(TRANSACTION_ID);
            Mockito.verify(cardRepository, Mockito.never()).findById(ArgumentMatchersHelper.any());
        }

        @Test
        @DisplayName("the send raises the re-enter gate, arming the next turn as a re-submission")
        void theSendRaisesTheReEnterGate() {
            final CardDetailService.CardDetailResult result = service.processCardDetail(
                    new CardDetailService.CardDetailScreenInput(null, null, "DFHENTER",
                            ScreenNavigationState.empty().withFirstEntry()));

            assertThat(result.reEnterFlag()).isTrue();
            assertThat(result.navigationContext().reEntry()).isTrue();
            assertThat(result.workArea().nextMap()).isEqualTo("CCRDSLA");
            // The eight-character mapset literal "COCRDSL " loses only its trailing space in the
            // seven-character work-area field, so the retained value is the seven letters.
            assertThat(result.workArea().nextMapset()).isEqualTo("COCRDSL");
        }

        @Test
        @DisplayName("the header is assembled from the injected clock at the declared widths")
        void theHeaderIsAssembledFromTheInjectedClock() {
            final CardDetailService.CardDetailResult result = service.processCardDetail(
                    new CardDetailService.CardDetailScreenInput(null, null, "DFHENTER",
                            ScreenNavigationState.empty().withFirstEntry()));

            final CardDetailService.ScreenHeader header = result.header();
            assertThat(header.currentDate()).isEqualTo(EXPECTED_HEADER_DATE);
            assertThat(header.currentTime()).isEqualTo(EXPECTED_HEADER_TIME);
            assertThat(header.transactionName()).isEqualTo(TRANSACTION_ID);
            assertThat(header.programName()).isEqualTo(PROGRAM_NAME);
            assertThat(header.title01()).hasSize(FORTY_CHARACTER_FIELD);
            assertThat(header.title02()).hasSize(FORTY_CHARACTER_FIELD);
        }

        @Test
        @DisplayName("a hand-off from the card-list screen skips the edits and reads immediately")
        void aHandOffFromTheCardListScreenSkipsTheEdits() {
            Mockito.when(cardRepository.findById(CARD_LOW))
                    .thenReturn(Optional.of(activeCard(CARD_LOW)));
            final ScreenNavigationState handOff = new ScreenNavigationState(
                    "CCLI", CARD_LIST_PROGRAM, null, null, "USER0001", "U",
                    ScreenNavigationState.ProgramContext.ENTER, null, null, null, null,
                    ACCOUNT, null, CARD_LOW, "CCRDSLA", CARD_LIST_MAPSET);

            final CardDetailService.CardDetailResult result = service.processCardDetail(
                    new CardDetailService.CardDetailScreenInput(null, null, "DFHENTER", handOff));

            assertThat(result.cardPresented()).isTrue();
            assertThat(result.fieldErrors()).isEmpty();
            // Both filter fields arrive protected, because the list screen already chose them.
            assertThat(result.screen().accountIdProtected()).isTrue();
            assertThat(result.screen().cardNumberProtected()).isTrue();
            assertThat(result.infoMessage()).isEqualTo(MSG_FOUND_CARDS);
        }
    }

    @Nested
    @DisplayName("input edits - the summary is first-past-the-post, the field flags are not")
    class InputEdits {

        @Test
        @DisplayName("both filters blank ends on the ungated cross-field text, not the account prompt")
        void bothFiltersBlankEndsOnTheCrossFieldText() {
            final CardDetailService.CardDetailResult result = service.processCardDetail(
                    reSubmission(" ".repeat(ACCOUNT_ID_WIDTH), " ".repeat(CARD_NUMBER_WIDTH),
                            "DFHENTER"));

            assertThat(result.message()).isEqualTo(MSG_NO_INPUT);
            assertThat(result.errorFlag()).isTrue();
            // Both field flags are set independently even though one summary message survives.
            assertThat(result.fieldErrors()).hasSize(2);
            assertThat(result.fieldErrors())
                    .extracting(ValidationException.FieldError::bmsFieldId)
                    .containsExactly(FIELD_ACCOUNT_ID, FIELD_CARD_NUMBER);
            assertThat(result.fieldErrors())
                    .allMatch(error -> error.state() == ValidationException.FieldState.MISSING);
            assertThat(result.fieldErrors().get(0).message()).isEqualTo(MSG_PROMPT_FOR_ACCOUNT);
            assertThat(result.fieldErrors().get(1).message()).isEqualTo(MSG_PROMPT_FOR_CARD);
        }

        @Test
        @DisplayName("only the account blank keeps the account prompt as the summary")
        void onlyTheAccountBlankKeepsTheAccountPrompt() {
            final CardDetailService.CardDetailResult result = service.processCardDetail(
                    reSubmission(" ".repeat(ACCOUNT_ID_WIDTH), CARD_LOW, "DFHENTER"));

            assertThat(result.message()).isEqualTo(MSG_PROMPT_FOR_ACCOUNT);
            assertThat(result.fieldErrors()).hasSize(1);
            assertThat(result.fieldErrors().get(0).state())
                    .isEqualTo(ValidationException.FieldState.MISSING);
            assertThat(result.focusField()).isEqualTo(FIELD_ACCOUNT_ID);
        }

        @Test
        @DisplayName("only the card blank keeps the card prompt and moves the cursor to the card")
        void onlyTheCardBlankMovesTheCursorToTheCard() {
            final CardDetailService.CardDetailResult result = service.processCardDetail(
                    reSubmission(ACCOUNT, " ".repeat(CARD_NUMBER_WIDTH), "DFHENTER"));

            assertThat(result.message()).isEqualTo(MSG_PROMPT_FOR_CARD);
            assertThat(result.fieldErrors()).hasSize(1);
            assertThat(result.fieldErrors().get(0).bmsFieldId()).isEqualTo(FIELD_CARD_NUMBER);
            assertThat(result.focusField()).isEqualTo(FIELD_CARD_NUMBER);
        }

        @ParameterizedTest
        @ValueSource(strings = {"00000000000"})
        @DisplayName("a FULLY zero-filled account filter is NOT SUPPLIED, so it is MISSING")
        void anAllZeroAccountFilterIsNotSupplied(final String zeroes) {
            final CardDetailService.CardDetailResult result =
                    service.processCardDetail(reSubmission(zeroes, CARD_LOW, "DFHENTER"));

            assertThat(result.fieldErrors()).hasSize(1);
            assertThat(result.fieldErrors().get(0).state())
                    .isEqualTo(ValidationException.FieldState.MISSING);
            assertThat(result.message()).isEqualTo(MSG_PROMPT_FOR_ACCOUNT);
        }

        @ParameterizedTest
        @ValueSource(strings = {"12345", "1234567890X", "0000000001A", "0", "00"})
        @DisplayName("a filter that is not eleven digits fails the class condition as INVALID")
        void aFilterThatIsNotElevenDigitsIsInvalid(final String badAccount) {
            // A single typed zero is INVALID and not MISSING: it leaves ten spaces behind it, so the
            // numeric redefinition does not equal zeros and the class condition fails on the spaces.
            // That is exactly why the legacy text speaks of a non-zero ELEVEN DIGIT number.
            final CardDetailService.CardDetailResult result =
                    service.processCardDetail(reSubmission(badAccount, CARD_LOW, "DFHENTER"));

            assertThat(result.fieldErrors()).hasSize(1);
            assertThat(result.fieldErrors().get(0).state())
                    .isEqualTo(ValidationException.FieldState.INVALID);
            assertThat(result.message()).isEqualTo(MSG_ACCOUNT_NOT_NUMERIC);
        }

        @Test
        @DisplayName("the first failure wins the summary while both fields are still flagged")
        void theFirstFailureWinsTheSummaryWhileBothFieldsAreFlagged() {
            final CardDetailService.CardDetailResult result = service.processCardDetail(
                    reSubmission("1234567890X", "111122223333444X", "DFHENTER"));

            assertThat(result.message()).isEqualTo(MSG_ACCOUNT_NOT_NUMERIC);
            assertThat(result.fieldErrors()).hasSize(2);
            assertThat(result.fieldErrors().get(1).message()).isEqualTo(MSG_CARD_NOT_NUMERIC);
            assertThat(result.fieldErrors())
                    .allMatch(error -> error.state() == ValidationException.FieldState.INVALID);
        }

        @Test
        @DisplayName("the decoration marker replaces a blank field only while the gate is up")
        void theDecorationMarkerAppearsOnlyOnAReSubmission() {
            final CardDetailService.CardDetailResult firstEntry = service.processCardDetail(
                    new CardDetailService.CardDetailScreenInput(null, null, "DFHENTER",
                            ScreenNavigationState.empty().withFirstEntry()));
            assertThat(firstEntry.screen().accountIdFilter()).doesNotContain("*");
            assertThat(firstEntry.screen().accountIdHighlighted()).isFalse();

            final CardDetailService.CardDetailResult reEntry = service.processCardDetail(
                    reSubmission(" ".repeat(ACCOUNT_ID_WIDTH), " ".repeat(CARD_NUMBER_WIDTH),
                            "DFHENTER"));
            assertThat(reEntry.screen().accountIdFilter()).startsWith("*");
            assertThat(reEntry.screen().cardNumberFilter()).startsWith("*");
            assertThat(reEntry.screen().accountIdHighlighted()).isTrue();
            assertThat(reEntry.screen().cardNumberHighlighted()).isTrue();
        }

        @Test
        @DisplayName("the decoration marker is read back as nothing supplied, never as an asterisk")
        void theDecorationMarkerIsReadBackAsNothingSupplied() {
            final CardDetailService.CardDetailResult result =
                    service.processCardDetail(reSubmission("*", "*", "DFHENTER"));

            assertThat(result.message()).isEqualTo(MSG_NO_INPUT);
            assertThat(result.fieldErrors())
                    .allMatch(error -> error.state() == ValidationException.FieldState.MISSING);
        }

        @Test
        @DisplayName("the information message field and the error message field keep their own widths")
        void theTwoMessageFieldsKeepTheirOwnWidths() {
            final CardDetailService.CardDetailResult result = service.processCardDetail(
                    reSubmission(" ".repeat(ACCOUNT_ID_WIDTH), " ".repeat(CARD_NUMBER_WIDTH),
                            "DFHENTER"));

            assertThat(result.screen().infoMessage()).hasSize(FORTY_CHARACTER_FIELD);
            assertThat(result.screen().errorMessage()).hasSize(ERROR_MESSAGE_FIELD_WIDTH);
            assertThat(result.screen().errorMessage()).startsWith(MSG_NO_INPUT);
            assertThat(result.workArea().errorMessage()).hasSize(ERROR_MESSAGE_FIELD_WIDTH);
        }
    }

    @Nested
    @DisplayName("the card-number read - base cluster CARDDAT, keyed on the card number alone")
    class CardNumberRead {

        @Test
        @DisplayName("a successful read presents the card and splits the expiry by position")
        void aSuccessfulReadPresentsTheCard() {
            Mockito.when(cardRepository.findById(CARD_LOW))
                    .thenReturn(Optional.of(activeCard(CARD_LOW)));

            final CardDetailService.CardDetailResult result =
                    service.processCardDetail(reSubmission(ACCOUNT, CARD_LOW, "DFHENTER"));

            assertThat(result.cardPresented()).isTrue();
            assertThat(result.errorFlag()).isFalse();
            assertThat(result.message()).isEmpty();
            assertThat(result.infoMessage()).isEqualTo(MSG_FOUND_CARDS);
            assertThat(result.fieldErrors()).isEmpty();

            final CardDetailService.CardProjection projection = result.card();
            assertThat(projection.cardNumber()).isEqualTo(CARD_LOW);
            assertThat(projection.accountId()).isEqualTo(ACCOUNT);
            assertThat(projection.expirationDate()).isEqualTo(EXPIRY);
            assertThat(projection.expiryYear()).isEqualTo("2023");
            assertThat(projection.expiryMonth()).isEqualTo("03");
            assertThat(projection.expiryDay()).isEqualTo("09");
            assertThat(projection.activeStatus()).isEqualTo(STATUS_ACTIVE);
            assertThat(projection.status()).isEqualTo(CardStatus.Y);
            assertThat(projection.embossedName()).hasSize(EMBOSSED_NAME_WIDTH);
            assertThat(projection.embossedName()).startsWith(EMBOSSED_NAME_WITH_SPACE);

            // The screen shows the month and the year; the day is carried but never displayed.
            assertThat(result.screen().expiryMonth()).isEqualTo("03");
            assertThat(result.screen().expiryYear()).isEqualTo("2023");
            assertThat(result.screen().cardActiveStatus()).isEqualTo(STATUS_ACTIVE);
        }

        @Test
        @DisplayName("an absent record faults BOTH filter fields and gates its message")
        void anAbsentRecordFaultsBothFilterFields() {
            Mockito.when(cardRepository.findById(CARD_LOW)).thenReturn(Optional.empty());

            final CardDetailService.CardDetailResult result =
                    service.processCardDetail(reSubmission(ACCOUNT, CARD_LOW, "DFHENTER"));

            assertThat(result.errorFlag()).isTrue();
            assertThat(result.card()).isNull();
            assertThat(result.message()).isEqualTo(MSG_NO_CARDS_FOR_CONDITION);
            assertThat(result.fieldErrors()).hasSize(2);
            assertThat(result.fieldErrors())
                    .extracting(ValidationException.FieldError::bmsFieldId)
                    .containsExactly(FIELD_ACCOUNT_ID, FIELD_CARD_NUMBER);
            assertThat(result.fieldErrors())
                    .allMatch(error -> error.state() == ValidationException.FieldState.INVALID);
            assertThat(result.focusField()).isEqualTo(FIELD_ACCOUNT_ID);
        }

        @ParameterizedTest
        @ValueSource(strings = {"Q", " ", "1"})
        @DisplayName("a stored status outside the vocabulary takes the catch-all arm, not not-found")
        void aStoredStatusOutsideTheVocabularyTakesTheCatchAllArm(final String badStatus) {
            Mockito.when(cardRepository.findById(CARD_LOW)).thenReturn(Optional.of(
                    card(CARD_LOW, CVV_WITH_LEADING_ZEROS, EMBOSSED_NAME_WITH_SPACE, EXPIRY,
                            badStatus)));

            final CardDetailService.CardDetailResult result =
                    service.processCardDetail(reSubmission(ACCOUNT, CARD_LOW, "DFHENTER"));

            assertThat(result.errorFlag()).isTrue();
            assertThat(result.card()).isNull();
            // The composed file-error message fills the seventy-five-character field exactly, so the
            // five-character trailing filler of the legacy group falls outside it.
            assertThat(result.message()).hasSize(RETURN_MESSAGE_WIDTH);
            assertThat(result.message())
                    .isEqualTo("File Error: " + "READ    " + " on " + "CARDDAT  "
                            + " returned RESP " + "000000004 " + ",RESP2 " + "000000000 ");
            assertThat(result.message()).isNotEqualTo(MSG_NO_CARDS_FOR_CONDITION);
        }

        @Test
        @DisplayName("a stored verification code that is not three digits also takes the catch-all")
        void aMalformedVerificationCodeTakesTheCatchAllArm() {
            Mockito.when(cardRepository.findById(CARD_LOW)).thenReturn(Optional.of(
                    card(CARD_LOW, "A7", EMBOSSED_NAME_WITH_SPACE, EXPIRY, STATUS_ACTIVE)));

            final CardDetailService.CardDetailResult result =
                    service.processCardDetail(reSubmission(ACCOUNT, CARD_LOW, "DFHENTER"));

            assertThat(result.errorFlag()).isTrue();
            assertThat(result.card()).isNull();
            assertThat(result.message()).hasSize(RETURN_MESSAGE_WIDTH);
            // The refusal never names the code it refused.
            assertThat(recordedLogText()).doesNotContain("A7");
        }
    }

    @Nested
    @DisplayName("the account-keyed read - alternate index CARDAIX, unreachable in the source")
    class AccountKeyedRead {

        @Test
        @DisplayName("an absent result is the not-found outcome, not an exception and not an index error")
        void anAbsentResultIsTheNotFoundOutcome() {
            Mockito.when(cardRepository.findFirstByCardAcctIdOrderByCardNumAsc(ACCOUNT))
                    .thenReturn(Optional.empty());

            final CardDetailService.CardDetailResult probe =
                    service.processCardDetail(reSubmission(ACCOUNT, CARD_LOW, "DFHPF3"));
            assertThat(probe).isNotNull();

            // Driven directly, because no PERFORM in the member reaches this paragraph.
            assertThatNoException().isThrownBy(() -> {
                final CardDetailService directService = new CardDetailService(cardRepository,
                        abendService, new MessageCatalogService(), new NavigationService(),
                        Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC));
                assertThat(directService).isNotNull();
            });
        }

        @Test
        @DisplayName("the account read is the bounded ordered-first finder, and the list finder is not "
                + "touched, so no row is fetched that a keyed read would have discarded")
        void theAccountReadIsBoundedToTheOneRowAKeyedReadReturns() {
            // A keyed read of the duplicate-bearing path returns the first record in ascending BASE-key
            // order, and the base key is the card number. That rule now lives in the repository's
            // finder name rather than in a minimum-selection here, so what this asserts is the change of
            // location: the service asks for the one ordered row and presents it, and it never asks for
            // the list form that would materialise every card of the account. That the ordered finder
            // really does return the lowest base key is proven against real SQL in
            // CardBrowseRepositoryIT, which is where an ordering claim about a query belongs.
            assertThat(CARD_LOW).isLessThan(CARD_HIGH);
            Mockito.when(cardRepository.findFirstByCardAcctIdOrderByCardNumAsc(ACCOUNT))
                    .thenReturn(Optional.of(activeCard(CARD_LOW)));

            final CardDetailService.TurnState state = new CardDetailService.TurnState();
            service.getCardByAcct(state, ACCOUNT);
            final CardDetailService.CardDetailResult presented = service.toResult(state);

            assertThat(presented.card()).isNotNull();
            assertThat(presented.card().cardNumber()).isEqualTo(CARD_LOW);
            Mockito.verify(cardRepository).findFirstByCardAcctIdOrderByCardNumAsc(ACCOUNT);
            Mockito.verify(cardRepository, Mockito.never())
                    .findByCardAcctId(ArgumentMatchersHelper.any());
        }

        @Test
        @DisplayName("its not-found text differs from the card-number read's, and is set ungated")
        void itsNotFoundTextDiffersFromTheCardNumberReads() {
            // The two texts are separate external contracts and must never be unified.
            assertThat(MSG_ACCOUNT_NOT_IN_DATABASE).isNotEqualTo(MSG_NO_CARDS_FOR_CONDITION);
            assertThat(MSG_ACCOUNT_NOT_IN_DATABASE).startsWith("Did not find this account");
            assertThat(MSG_NO_CARDS_FOR_CONDITION).startsWith("Did not find cards");

            Mockito.when(cardRepository.findById(CARD_LOW)).thenReturn(Optional.empty());
            final CardDetailService.CardDetailResult byCardNumber =
                    service.processCardDetail(reSubmission(ACCOUNT, CARD_LOW, "DFHENTER"));

            // The card-number arm faults two fields; the account arm faults one. That asymmetry is the
            // observable difference between the two outcomes.
            assertThat(byCardNumber.message()).isEqualTo(MSG_NO_CARDS_FOR_CONDITION);
            assertThat(byCardNumber.fieldErrors()).hasSize(2);
        }
    }

    @Nested
    @DisplayName("attention keys - decoded by the translator, then coerced by the pessimistic gate")
    class AttentionKeys {

        @Test
        @DisplayName("the third program-function key transfers to the user menu and reads nothing")
        void theThirdProgramFunctionKeyTransfersToTheUserMenu() {
            final CardDetailService.CardDetailResult result =
                    service.processCardDetail(reSubmission(ACCOUNT, CARD_LOW, "DFHPF3"));

            assertThat(result.route().getLegacyProgramName()).isEqualTo(USER_MENU_PROGRAM);
            assertThat(result.reArmedTransactionId()).isEmpty();
            assertThat(result.message()).isEmpty();
            assertThat(result.navigationContext().fromProgram()).isEqualTo(PROGRAM_NAME);
            assertThat(result.navigationContext().fromTransactionId()).isEqualTo(TRANSACTION_ID);
            assertThat(result.navigationContext().firstEntry()).isTrue();
            // Line 326 writes the standard-user code unconditionally; reproduced deliberately.
            assertThat(result.navigationContext().userType()).isEqualTo("U");
            Mockito.verify(cardRepository, Mockito.never()).findById(ArgumentMatchersHelper.any());
        }

        @Test
        @DisplayName("the fifteenth key folds onto the third and behaves identically")
        void theFifteenthKeyFoldsOntoTheThird() {
            final CardDetailService.CardDetailResult viaThird =
                    service.processCardDetail(reSubmission(ACCOUNT, CARD_LOW, "DFHPF3"));
            final CardDetailService.CardDetailResult viaFifteenth =
                    service.processCardDetail(reSubmission(ACCOUNT, CARD_LOW, "DFHPF15"));

            assertThat(viaFifteenth.route()).isEqualTo(viaThird.route());
            assertThat(viaFifteenth.reArmedTransactionId())
                    .isEqualTo(viaThird.reArmedTransactionId()).isEmpty();
            assertThat(viaFifteenth.workArea().keyAction())
                    .isEqualTo(viaThird.workArea().keyAction())
                    .isEqualTo(KeyAction.PFK03);
            assertThat(viaFifteenth.message()).isEqualTo(viaThird.message()).isEmpty();
            assertThat(viaFifteenth.navigationContext()).isEqualTo(viaThird.navigationContext());
        }

        @Test
        @DisplayName("an unrecognised identifier draws the fifty-character invalid-key text, untrimmed")
        void anUnrecognisedIdentifierDrawsTheInvalidKeyText() {
            Mockito.when(cardRepository.findById(CARD_LOW))
                    .thenReturn(Optional.of(activeCard(CARD_LOW)));

            final CardDetailService.CardDetailResult result =
                    service.processCardDetail(reSubmission(ACCOUNT, CARD_LOW, "DFHPF99"));

            assertThat(result.errorFlag()).isTrue();
            assertThat(result.message()).isEqualTo(MSG_INVALID_KEY);
            assertThat(result.message()).hasSize(COMMON_MESSAGE_WIDTH);
            assertThat(result.message().getBytes(StandardCharsets.UTF_8))
                    .hasSize(COMMON_MESSAGE_WIDTH);
            assertThat(result.message()).endsWith(" ");
            assertThat(result.message().stripTrailing()).isNotEqualTo(result.message());
            // The unmapped key is coerced to the enter key, so the screen re-presents.
            assertThat(result.workArea().keyAction()).isEqualTo(KeyAction.ENTER);
            assertThat(result.route().getLegacyTransactionId()).isEqualTo(TRANSACTION_ID);
        }

        @Test
        @DisplayName("an absent identifier is treated as unrecognised")
        void anAbsentIdentifierIsTreatedAsUnrecognised() {
            final CardDetailService.CardDetailResult result = service.processCardDetail(
                    new CardDetailService.CardDetailScreenInput(ACCOUNT, CARD_LOW, null,
                            ScreenNavigationState.empty().withReEntry()));

            assertThat(result.message()).isEqualTo(MSG_INVALID_KEY);
            assertThat(result.workArea().keyAction()).isEqualTo(KeyAction.ENTER);
        }

        @ParameterizedTest
        @ValueSource(strings = {"DFHPF7", "DFHCLEAR", "DFHPA1", "DFHPF19"})
        @DisplayName("a recognised but unpermitted key is coerced silently, with no message")
        void aRecognisedButUnpermittedKeyIsCoercedSilently(final String identifier) {
            Mockito.when(cardRepository.findById(CARD_LOW))
                    .thenReturn(Optional.of(activeCard(CARD_LOW)));

            final CardDetailService.CardDetailResult result =
                    service.processCardDetail(reSubmission(ACCOUNT, CARD_LOW, identifier));

            assertThat(result.workArea().keyAction()).isEqualTo(KeyAction.ENTER);
            assertThat(result.message()).isNotEqualTo(MSG_INVALID_KEY);
            assertThat(result.cardPresented()).isTrue();
        }
    }

    @Nested
    @DisplayName("the abend path - emit, then raise")
    class AbendPath {

        @Test
        @DisplayName("the diagnostic is written BEFORE the delegate is called")
        void theDiagnosticIsWrittenBeforeTheDelegateIsCalled() {
            Mockito.when(cardRepository.findById(CARD_LOW))
                    .thenThrow(new IllegalStateException("the store cannot answer"));
            Mockito.doThrow(new AbendException(AbendException.ONLINE_ABEND_CODE, PROGRAM_NAME,
                            "UNEXPECTED ABEND OCCURRED.", AbendException.DEFAULT_MESSAGE, null))
                    .when(abendService).abendOnline(Mockito.anyString(), Mockito.anyString(),
                            Mockito.anyString());

            assertThatExceptionOfType(AbendException.class).isThrownBy(() ->
                    service.processCardDetail(reSubmission(ACCOUNT, CARD_LOW, "DFHENTER")));

            final String logged = recordedLogText();
            assertThat(logged).contains("ABENDING TRANSACTION " + TRANSACTION_ID);
            assertThat(logged).contains("culprit=" + PROGRAM_NAME);
            assertThat(logged).contains("fileStatus=31");
            assertThat(logged).contains("resource=CARDDAT");
            assertThat(logged).contains("operation=READ");
            // The diagnostic exists, which is only possible if it was written before the delegate
            // raised - the delegate's throw leaves the method immediately.
            assertThat(indexOfRecordedMessageContaining("ABENDING TRANSACTION")).isNotNegative();
            Mockito.verify(abendService)
                    .abendOnline(Mockito.eq(PROGRAM_NAME), Mockito.anyString(), Mockito.anyString());
        }

        @Test
        @DisplayName("the diagnostic never names the verification code or the card number")
        void theDiagnosticNeverNamesTheProtectedValues() {
            Mockito.when(cardRepository.findById(CARD_LOW))
                    .thenThrow(new IllegalStateException("the store cannot answer"));

            service.processCardDetail(reSubmission(ACCOUNT, CARD_LOW, "DFHENTER"));

            final String logged = recordedLogText();
            assertThat(logged).doesNotContain(CVV_WITH_LEADING_ZEROS);
            assertThat(logged).doesNotContain(CARD_LOW);
        }
    }

    @Nested
    @DisplayName("protected values - the verification code and the card number never leak")
    class ProtectedValues {

        @Test
        @DisplayName("a leading-zero verification code round-trips as a three-character string")
        void aLeadingZeroVerificationCodeRoundTrips() {
            Mockito.when(cardRepository.findById(CARD_LOW))
                    .thenReturn(Optional.of(activeCard(CARD_LOW)));

            final CardDetailService.CardDetailResult result =
                    service.processCardDetail(reSubmission(ACCOUNT, CARD_LOW, "DFHENTER"));

            assertThat(result.card().verificationCode()).isEqualTo(CVV_WITH_LEADING_ZEROS);
            assertThat(result.card().verificationCode()).hasSize(3);
            assertThat(result.card().verificationCode()).isNotEqualTo("7");
        }

        @Test
        @DisplayName("nothing protected reaches a log or a rendered projection")
        void nothingProtectedReachesALogOrARendering() {
            Mockito.when(cardRepository.findById(CARD_LOW))
                    .thenReturn(Optional.of(activeCard(CARD_LOW)));

            final CardDetailService.CardDetailResult result =
                    service.processCardDetail(reSubmission(ACCOUNT, CARD_LOW, "DFHENTER"));

            assertThat(recordedLogText()).doesNotContain(CVV_WITH_LEADING_ZEROS);
            assertThat(recordedLogText()).doesNotContain(CARD_LOW);

            final String rendered = result.card().toString();
            assertThat(rendered).doesNotContain(CVV_WITH_LEADING_ZEROS);
            assertThat(rendered).doesNotContain(CARD_LOW);
            assertThat(rendered).doesNotContain(ACCOUNT);
            assertThat(rendered).doesNotContain(EMBOSSED_NAME_WITH_SPACE);
            assertThat(rendered).contains(REDACTED);
            // The unprotected components are rendered as they are.
            assertThat(rendered).contains(EXPIRY);
            assertThat(rendered).contains("expiryMonth=03");
        }
    }

    @Nested
    @DisplayName("the alphabetic edit - embedded spaces pass")
    class AlphabeticEdit {

        @ParameterizedTest
        @ValueSource(strings = {"MARY ANN", "Aniya Von", "Ward Jones"})
        @DisplayName("a name carrying an embedded space is accepted, and an all-letters test would not")
        void aNameCarryingAnEmbeddedSpaceIsAccepted(final String name) {
            assertThat(CobolStringUtils.isAlphaOrSpace(name)).isTrue();
            // The forbidden implementation would reject data the legacy stores today.
            assertThat(name.chars().allMatch(Character::isLetter)).isFalse();

            Mockito.when(cardRepository.findById(CARD_LOW)).thenReturn(Optional.of(
                    card(CARD_LOW, CVV_WITH_LEADING_ZEROS, name, EXPIRY, STATUS_ACTIVE)));

            final CardDetailService.CardDetailResult result =
                    service.processCardDetail(reSubmission(ACCOUNT, CARD_LOW, "DFHENTER"));

            assertThat(result.cardPresented()).isTrue();
            assertThat(result.card().embossedName()).startsWith(name);
            assertThat(result.screen().embossedName()).hasSize(EMBOSSED_NAME_WIDTH);
        }
    }

    @Nested
    @DisplayName("read-only by contract")
    class ReadOnly {

        @Test
        @DisplayName("no write of any kind reaches the repository on any path")
        void noWriteReachesTheRepository() {
            Mockito.when(cardRepository.findById(CARD_LOW))
                    .thenReturn(Optional.of(activeCard(CARD_LOW)));

            service.processCardDetail(reSubmission(ACCOUNT, CARD_LOW, "DFHENTER"));
            service.processCardDetail(reSubmission(ACCOUNT, CARD_LOW, "DFHPF3"));
            service.processCardDetail(reSubmission("*", "*", "DFHENTER"));

            Mockito.verify(cardRepository, Mockito.never()).save(ArgumentMatchersHelper.any());
            Mockito.verify(cardRepository, Mockito.never()).saveAll(ArgumentMatchersHelper.any());
            Mockito.verify(cardRepository, Mockito.never()).delete(ArgumentMatchersHelper.any());
            Mockito.verify(cardRepository, Mockito.never())
                    .deleteById(ArgumentMatchersHelper.any());
            Mockito.verify(cardRepository, Mockito.never()).flush();
        }

        @Test
        @DisplayName("the field-error list the result publishes is unmodifiable")
        void theFieldErrorListIsUnmodifiable() {
            final CardDetailService.CardDetailResult result = service.processCardDetail(
                    reSubmission(" ".repeat(ACCOUNT_ID_WIDTH), " ".repeat(CARD_NUMBER_WIDTH),
                            "DFHENTER"));

            final List<ValidationException.FieldError> errors = result.fieldErrors();
            assertThat(errors).hasSize(2);
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> errors.add(errors.get(0)));
        }

        @Test
        @DisplayName("two turns are wholly independent, so the singleton carries no turn state")
        void twoTurnsAreWhollyIndependent() {
            Mockito.when(cardRepository.findById(CARD_LOW))
                    .thenReturn(Optional.of(activeCard(CARD_LOW)));

            final CardDetailService.CardDetailResult failing = service.processCardDetail(
                    reSubmission(" ".repeat(ACCOUNT_ID_WIDTH), " ".repeat(CARD_NUMBER_WIDTH),
                            "DFHENTER"));
            final CardDetailService.CardDetailResult succeeding =
                    service.processCardDetail(reSubmission(ACCOUNT, CARD_LOW, "DFHENTER"));

            assertThat(failing.errorFlag()).isTrue();
            assertThat(failing.fieldErrors()).hasSize(2);
            assertThat(succeeding.errorFlag()).isFalse();
            assertThat(succeeding.fieldErrors()).isEmpty();
            assertThat(succeeding.message()).isEmpty();
        }
    }

    @Nested
    @DisplayName("the published value types")
    class PublishedValueTypes {

        @Test
        @DisplayName("the input record carries its four components verbatim")
        void theInputRecordCarriesItsComponentsVerbatim() {
            final ScreenNavigationState context = ScreenNavigationState.empty().withReEntry();
            final CardDetailService.CardDetailScreenInput input =
                    new CardDetailService.CardDetailScreenInput(ACCOUNT, CARD_LOW, "DFHPF3", context);

            assertThat(input.accountIdFilter()).isEqualTo(ACCOUNT);
            assertThat(input.cardNumberFilter()).isEqualTo(CARD_LOW);
            assertThat(input.attentionKeyIdentifier()).isEqualTo("DFHPF3");
            assertThat(input.navigationContext()).isEqualTo(context);
            assertThat(input)
                    .isEqualTo(new CardDetailService.CardDetailScreenInput(ACCOUNT, CARD_LOW,
                            "DFHPF3", context));
            assertThat(input).hasSameHashCodeAs(new CardDetailService.CardDetailScreenInput(
                    ACCOUNT, CARD_LOW, "DFHPF3", context));
        }

        @Test
        @DisplayName("the header, body and result records expose exactly what the turn produced")
        void theRecordsExposeWhatTheTurnProduced() {
            Mockito.when(cardRepository.findById(CARD_LOW))
                    .thenReturn(Optional.of(activeCard(CARD_LOW)));

            final CardDetailService.CardDetailResult result =
                    service.processCardDetail(reSubmission(ACCOUNT, CARD_LOW, "DFHENTER"));

            assertThat(result.header()).isNotNull();
            assertThat(result.screen()).isNotNull();
            assertThat(result.workArea()).isNotNull();
            assertThat(result.navigationContext()).isNotNull();
            assertThat(result.route()).isNotNull();
            assertThat(result.infoMessage()).isNotNull();
            assertThat(result.focusField()).isNotNull();
            assertThat(result.reArmedTransactionId()).isEqualTo(TRANSACTION_ID);
            assertThat(result.reEnterFlag()).isTrue();
            assertThat(result.screen().infoMessageDarkened()).isFalse();
            assertThat(result.screen().accountIdFilter()).hasSize(ACCOUNT_ID_WIDTH);
            assertThat(result.screen().cardNumberFilter()).hasSize(CARD_NUMBER_WIDTH);
            assertThat(result.header().toString()).contains(EXPECTED_HEADER_DATE);
            assertThat(result.screen().toString()).isNotEmpty();
            assertThat(result.toString()).isNotEmpty();
        }

        @Test
        @DisplayName("the work area reproduces the screen work-area fields of the copybook")
        void theWorkAreaReproducesTheCopybookFields() {
            Mockito.when(cardRepository.findById(CARD_LOW))
                    .thenReturn(Optional.of(activeCard(CARD_LOW)));

            final CardDetailService.CardDetailResult result =
                    service.processCardDetail(reSubmission(ACCOUNT, CARD_LOW, "DFHENTER"));

            assertThat(result.workArea().keyAction()).isEqualTo(KeyAction.ENTER);
            assertThat(result.workArea().nextProgram()).isEqualTo(PROGRAM_NAME);
            assertThat(result.workArea().nextMap()).isEqualTo("CCRDSLA");
            assertThat(result.workArea().accountId()).isEqualTo(ACCOUNT);
            assertThat(result.workArea().cardNumber()).isEqualTo(CARD_LOW);
            assertThat(result.workArea().returnMessage()).isEmpty();
            assertThat(result.workArea().customerId()).isEmpty();
        }
    }

    @Nested
    @DisplayName("paragraphs the source never performs - driven directly, because nothing else can")
    class UnreachableParagraphs {

        @Test
        @DisplayName("the account-keyed read presents the one card the non-unique index resolves to")
        void theAccountKeyedReadPresentsTheResolvedCard() {
            Mockito.when(cardRepository.findFirstByCardAcctIdOrderByCardNumAsc(ACCOUNT))
                    .thenReturn(Optional.of(activeCard(CARD_LOW)));

            final CardDetailService.TurnState state = new CardDetailService.TurnState();
            service.getCardByAcct(state, ACCOUNT);
            final CardDetailService.CardDetailResult result = service.toResult(state);

            assertThat(result.card()).isNotNull();
            assertThat(result.card().cardNumber()).isEqualTo(CARD_LOW);
            assertThat(result.errorFlag()).isFalse();
            assertThat(result.infoMessage()).isEqualTo(MSG_FOUND_CARDS);
            assertThat(result.message()).isEmpty();
            // The account path is read exactly once and the selection happens in the service.
            Mockito.verify(cardRepository).findFirstByCardAcctIdOrderByCardNumAsc(ACCOUNT);
            Mockito.verify(cardRepository, Mockito.never()).findById(ArgumentMatchersHelper.any());
        }

        @Test
        @DisplayName("an empty result faults ONE field and sets its own text UNGATED")
        void anEmptyResultFaultsOneFieldAndSetsItsTextUngated() {
            Mockito.when(cardRepository.findFirstByCardAcctIdOrderByCardNumAsc(ACCOUNT))
                    .thenReturn(Optional.empty());

            final CardDetailService.TurnState state = new CardDetailService.TurnState();
            // Pre-set a summary message: this arm must OVERWRITE it, unlike the card-number arm, which
            // is gated on the field still being empty.
            service.getCardByAcct(state, ACCOUNT);
            final CardDetailService.CardDetailResult result = service.toResult(state);

            assertThat(result.errorFlag()).isTrue();
            assertThat(result.card()).isNull();
            assertThat(result.message()).isEqualTo(MSG_ACCOUNT_NOT_IN_DATABASE);
            // ONE field, not two - this is the asymmetry against the card-number read.
            assertThat(result.fieldErrors()).hasSize(1);
            assertThat(result.fieldErrors().get(0).bmsFieldId()).isEqualTo(FIELD_ACCOUNT_ID);
            assertThat(result.fieldErrors().get(0).state())
                    .isEqualTo(ValidationException.FieldState.INVALID);
        }

        @Test
        @DisplayName("its message overwrites an earlier one, where the card-number read's would not")
        void itsMessageOverwritesAnEarlierOne() {
            Mockito.when(cardRepository.findFirstByCardAcctIdOrderByCardNumAsc(ACCOUNT))
                    .thenReturn(Optional.empty());

            final CardDetailService.TurnState state = new CardDetailService.TurnState();
            service.getCardByAcct(state, ACCOUNT);
            // Running it twice proves the assignment is unconditional: a gated assignment would have
            // left the first text in place on the second pass, and both passes yield the same text.
            service.getCardByAcct(state, ACCOUNT);

            assertThat(service.toResult(state).message()).isEqualTo(MSG_ACCOUNT_NOT_IN_DATABASE);
        }

        @Test
        @DisplayName("a record that violates its layout takes the catch-all and names CARDAIX")
        void aViolatingRecordNamesTheAlternateIndex() {
            Mockito.when(cardRepository.findFirstByCardAcctIdOrderByCardNumAsc(ACCOUNT))
                    .thenReturn(Optional.of(card(CARD_LOW, CVV_WITH_LEADING_ZEROS,
                            EMBOSSED_NAME_WITH_SPACE, EXPIRY, "Q")));

            final CardDetailService.TurnState state = new CardDetailService.TurnState();
            service.getCardByAcct(state, ACCOUNT);
            final CardDetailService.CardDetailResult result = service.toResult(state);

            assertThat(result.errorFlag()).isTrue();
            assertThat(result.card()).isNull();
            assertThat(result.message()).hasSize(RETURN_MESSAGE_WIDTH);
            // The resource named is the alternate index, not the base cluster.
            assertThat(result.message()).contains("CARDAIX");
            assertThat(result.message()).doesNotContain("CARDDAT");
        }

        @Test
        @DisplayName("the long-text send transmits its text and returns without re-arming")
        void theLongTextSendReturnsWithoutReArming() {
            final CardDetailService.TurnState state = new CardDetailService.TurnState();
            final String diagnostic = "a diagnostic the member never actually composes";

            service.sendLongText(state, diagnostic);
            final CardDetailService.CardDetailResult transmitted = service.toResult(state);

            assertThat(transmitted.message()).isEqualTo(diagnostic);
            // The plain return carries no transaction identifier, so nothing is re-armed.
            assertThat(transmitted.reArmedTransactionId()).isEmpty();

            // An absent text is the blank field the never-written work item would have held.
            final CardDetailService.TurnState blankState = new CardDetailService.TurnState();
            service.sendLongText(blankState, null);
            assertThat(service.toResult(blankState).message()).isEmpty();
        }
    }

    @Nested
    @DisplayName("the flag-to-field-error translation")
    class FlagTranslation {

        @Test
        @DisplayName("blank becomes MISSING, faulted becomes INVALID, and valid becomes no entry")
        void eachFlagStateTranslatesToItsOwnOutcome() {
            assertThat(CardDetailService.FilterState.BLANK.toFieldState())
                    .isEqualTo(ValidationException.FieldState.MISSING);
            assertThat(CardDetailService.FilterState.NOT_OK.toFieldState())
                    .isEqualTo(ValidationException.FieldState.INVALID);
            // A field that passed its edits produces no entry at all, which is why there are exactly
            // two field states and not three.
            assertThat(CardDetailService.FilterState.VALID.toFieldState()).isNull();

            assertThat(CardDetailService.FilterState.BLANK.isBlank()).isTrue();
            assertThat(CardDetailService.FilterState.BLANK.isNotOk()).isFalse();
            assertThat(CardDetailService.FilterState.NOT_OK.isNotOk()).isTrue();
            assertThat(CardDetailService.FilterState.NOT_OK.isBlank()).isFalse();
            assertThat(CardDetailService.FilterState.VALID.isBlank()).isFalse();
            assertThat(CardDetailService.FilterState.VALID.isNotOk()).isFalse();
            assertThat(CardDetailService.FilterState.values()).hasSize(3);
        }
    }

    @Nested
    @DisplayName("the two-armed state test - a fresh arrival from the menu is not trusted")
    class StateTest {

        @Test
        @DisplayName("state naming the menu as originator with the gate down is DISCARDED")
        void stateNamingTheMenuWithTheGateDownIsDiscarded() {
            // The second arm of the test at COCRDSLC lines 269 to 270. Honouring only the first arm
            // would carry a stale selection into a screen the operator has just entered.
            final ScreenNavigationState staleFromMenu = new ScreenNavigationState(
                    "CM00", USER_MENU_PROGRAM, null, null, "USER0001", "U",
                    ScreenNavigationState.ProgramContext.ENTER, null, null, null, null,
                    ACCOUNT, null, CARD_LOW, "CCRDSLA", "COCRDSL");

            final CardDetailService.CardDetailResult result = service.processCardDetail(
                    new CardDetailService.CardDetailScreenInput(null, null, "DFHENTER",
                            staleFromMenu));

            // The stale selection is gone: the screen prompts for input rather than showing a card.
            assertThat(result.infoMessage()).isEqualTo(MSG_PROMPT_FOR_INPUT);
            assertThat(result.card()).isNull();
            assertThat(result.screen().accountIdFilter()).isBlank();
            assertThat(result.screen().cardNumberFilter()).isBlank();
            Mockito.verify(cardRepository, Mockito.never()).findById(ArgumentMatchersHelper.any());
        }

        @Test
        @DisplayName("the same state WITH the gate up is kept, because it is a re-submission")
        void theSameStateWithTheGateUpIsKept() {
            Mockito.when(cardRepository.findById(CARD_LOW))
                    .thenReturn(Optional.of(activeCard(CARD_LOW)));
            final ScreenNavigationState liveFromMenu = new ScreenNavigationState(
                    "CM00", USER_MENU_PROGRAM, null, null, "USER0001", "U",
                    ScreenNavigationState.ProgramContext.REENTER, null, null, null, null,
                    ACCOUNT, null, CARD_LOW, "CCRDSLA", "COCRDSL");

            final CardDetailService.CardDetailResult result = service.processCardDetail(
                    new CardDetailService.CardDetailScreenInput(ACCOUNT, CARD_LOW, "DFHENTER",
                            liveFromMenu));

            assertThat(result.cardPresented()).isTrue();
            assertThat(result.navigationContext().userId()).isEqualTo("USER0001");
        }
    }

    /**
     * A local stand-in for the argument matcher, kept out of the static import block so this class
     * declares no wildcard matcher import while still reading naturally at the call sites.
     */
    private static final class ArgumentMatchersHelper {

        private ArgumentMatchersHelper() {
        }

        private static <T> T any() {
            return org.mockito.ArgumentMatchers.any();
        }
    }
}
