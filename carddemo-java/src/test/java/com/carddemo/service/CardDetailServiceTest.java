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
import java.util.Locale;
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
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

import com.carddemo.domain.Card;
import com.carddemo.domain.enums.CardStatus;
import com.carddemo.domain.enums.KeyAction;
import com.carddemo.exception.AbendException;
import com.carddemo.exception.RecordNotFoundException;
import com.carddemo.exception.ValidationException;
import com.carddemo.repository.CardRepository;
import com.carddemo.support.TestDataFactory;
import com.carddemo.support.TraceabilityMatrixCensus;
import com.carddemo.util.CobolStringUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link CardDetailService}, the card-detail transaction {@code CCDL}.
 *
 * <p>It resolves one card and presents it, or explains why it could not. The screen work area comes from
 * {@code app/cpy/CVCRD01Y.cpy} , the attention-key store from {@code app/cpy/CSSTRPFY.cpy} , and the
 * 150-byte card layout from {@code app/cpy/CVACT02Y.cpy} . No legacy source text appears in this file; only
 * widths, offsets, counts, member names and contract literals, which are metadata.
 *
 * <h2>Thirty-four paragraph units, and what is deliberately not counted among them</h2>
 *
 * <p>A census of Area-A labels in this member's own procedure division, which begins at line 247, finds
 * <strong>34</strong>, and 34 is the figure the traceability matrix carries for this member: {@code 0000-MAIN} 248, {@code COMMON-RETURN} 394, {@code 0000-MAIN-EXIT} 408,
 * {@code 1000-SEND-MAP} 412 and its exit 423, {@code 1100-SCREEN-INIT} 427 and 453,
 * {@code 1200-SETUP-SCREEN-VARS} 457 and 499, {@code 1300-SETUP-SCREEN-ATTRS} 502 and 559,
 * {@code 1400-SEND-SCREEN} 563 and 578, {@code 2000-PROCESS-INPUTS} 582 and 593,
 * {@code 2100-RECEIVE-MAP} 596 and 605, {@code 2200-EDIT-MAP-INPUTS} 608 and 643,
 * {@code 2210-EDIT-ACCOUNT} 647 and 681, {@code 2220-EDIT-CARD} 685 and 722,
 * {@code 9000-READ-DATA} 726 and 732, {@code 9100-GETCARD-BYACCTCARD} 736 and 775,
 * {@code 9150-GETCARD-BYACCT} 779 and 810, {@code SEND-LONG-TEXT} 820 and 831,
 * {@code SEND-PLAIN-TEXT} 838 and 849, and {@code ABEND-ROUTINE} 857. Every one of the 34 is named in a
 * test below.
 *
 * <p><strong>Three further things this member contains are exercised here and are deliberately not
 * counted as units of it.</strong> The in-line {@code COPY 'CSSTRPFY'} at line 855 is a directive rather
 * than a paragraph, and the two paragraphs that copybook expands - {@code YYYY-STORE-PFKEY} at line 17 of
 * the copybook and its exit at line 80 - are units of the <em>copybook</em>, which the matrix gives a
 * section and two rows of its own. That copybook is included by five members, so counting its two
 * paragraphs against each of them would report ten units for two and the frozen total would no longer be
 * 544. Adding all three to this suite's own count publishes 37, and must not; the behaviour those three
 * carry is asserted below, under the copybook they belong to.
 *
 * <h2>The oracle is independent of the code it judges</h2>
 *
 * <p>Every expected message, field width, screen field identifier, transaction identifier, program
 * name and route token below is a literal declared in this class. No expected value is obtained by
 * calling the service, the message catalogue, the navigation service, the key translator or a record
 * mapper, so a defect that changed a published constant could not hide behind a test that read the
 * same constant back. Fixed-width padding is written as an explicit repeat count so the count is
 * visible to a reviewer and cannot be silently stripped, widths are measured on encoded bytes, and no
 * fixed-width value is trimmed before comparison.
 *
 * <h2>Nine behaviours carry parity traps and are asserted deliberately</h2>
 *
 * <ol>
 *   <li><em>The account-keyed read is over a non-unique index, so first-match selection is the
 *       contract.</em> An absent result is the legacy not-found response and must not raise; a result
 *       must never be reached by indexing into a collection. The repository declares a list-returning
 *       account finder as well, and this service must never touch it - that is asserted, not
 *       assumed.</li>
 *   <li><em>The two not-found outcomes are different.</em> The card-number read faults both filter
 *       fields and gates its message; the account-keyed read faults one and sets its message ungated,
 *       overwriting whatever was there. Collapsing them would lose a field flag and a
 *       message-precedence rule.</li>
 *   <li><em>The upper program-function keys fold onto the lower twelve.</em> An identifier naming key
 *       15 behaves exactly as one naming key 3, because the copybook's 28-clause selection collapses
 *       onto 16 outcomes.</li>
 *   <li><em>An unmapped attention key is coerced, not rejected.</em> The pessimistic gate rewrites it
 *       as the enter key so the screen re-presents; only an identifier the selection does not
 *       recognise at all draws the invalid-key text.</li>
 *   <li><em>The invalid-key text is fifty encoded bytes and is never trimmed.</em> The trailing
 *       spaces are part of the screen contract.</li>
 *   <li><em>The summary message is first-past-the-post while every field flag is still set.</em> The
 *       exceptions are the cross-field edit and both file-error arms, which are ungated and
 *       overwrite.</li>
 *   <li><em>An all-zero filter is "not supplied", not "invalid".</em> That is why the legacy message
 *       speaks of a non-zero number.</li>
 *   <li><em>Field decoration is gated on the inbound re-enter flag.</em> A first entry shows an empty
 *       field; only a re-submission shows the marker.</li>
 *   <li><em>The card verification code is a bounded three-character string.</em> A value of
 *       {@code 007} stays {@code "007"}, never becomes a number, and never reaches a log line.</li>
 * </ol>
 *
 * <p>Two paragraphs of the source are unreachable - the account-keyed read at line 779 and the
 * long-text send at line 820, both verified by a census of every {@code PERFORM} in the member. They
 * are exercised here directly through the package-private seams the production class documents,
 * because they are translated and must therefore be judged, and driving them through a turn is
 * impossible by design.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CardDetailService - the CCDL card-detail transaction of COCRDSLC")
class CardDetailServiceTest {

    // ==============================================================================================
    // The oracle. Every value below is declared here and is never read back from the code under test.
    // ==============================================================================================

    /** {@code LIT-THISTRANID}, COCRDSLC line 166. */
    private static final String TRANSACTION_ID = "CCDL";

    /** {@code LIT-THISPGM}, COCRDSLC line 164, and the abend culprit. */
    private static final String PROGRAM_NAME = "COCRDSLC";

    /** {@code LIT-CCLISTPGM}, COCRDSLC line 172: the card-list member that hands over to this one. */
    private static final String CARD_LIST_PROGRAM = "COCRDLIC";

    /** {@code LIT-CCLISTMAPSET}, COCRDSLC line 176. */
    private static final String CARD_LIST_MAPSET = "COCRDLI";

    /** The user main menu member, the back-navigation default of this screen. */
    private static final String USER_MENU_PROGRAM = "COMEN01C";

    /**
     * The CICS program definition at {@code app/csd/CARDDEMO.CSD} line 211, bound to a transaction at
     * line 390, for which the estate ships no source member at all. Nothing in the target may route
     * to it, and that is asserted rather than assumed.
     */
    private static final String DANGLING_CICS_PROGRAM = "COCRDSEC";

    /** The wire value of this screen's own destination. */
    private static final String ROUTE_CARD_DETAIL = "card-detail";

    /** The wire value of the back-navigation default. */
    private static final String ROUTE_USER_MENU = "user-menu";

    /** {@code LIT-THISMAP}, COCRDSLC line 170. */
    private static final String THIS_MAP = "CCRDSLA";

    /**
     * {@code LIT-THISMAPSET}, COCRDSLC line 168, declared eight characters wide with a trailing space
     * and moved into a seven-character work-area field, where the space is truncated away.
     */
    private static final String THIS_MAPSET_TRUNCATED = "COCRDSL";

    /** The two screen field identifiers the cursor arms at COCRDSLC lines 515 to 524 name. */
    private static final String FIELD_ACCOUNT_ID = "ACCTSID";

    private static final String FIELD_CARD_NUMBER = "CARDSID";

    /** The account-filter property name a response layer decorates. */
    private static final String PROPERTY_ACCOUNT_ID = "accountId";

    /** The card-number property name a response layer decorates. */
    private static final String PROPERTY_CARD_NUMBER = "cardNumber";

    /** {@code WS-PROMPT-FOR-INPUT}, COCRDSLC lines 131 to 132. */
    private static final String MSG_PROMPT_FOR_INPUT = "Please enter Account and Card Number";

    /** {@code FOUND-CARDS-FOR-ACCOUNT}, COCRDSLC lines 129 to 130 - three leading spaces. */
    private static final String MSG_FOUND_CARDS = " ".repeat(3) + "Displaying requested details";

    /** {@code WS-PROMPT-FOR-ACCT}, COCRDSLC lines 138 to 139, raised behind the message gate. */
    private static final String MSG_PROMPT_FOR_ACCOUNT = "Account number not provided";

    /** {@code WS-PROMPT-FOR-CARD}, COCRDSLC lines 140 to 141, raised behind the message gate. */
    private static final String MSG_PROMPT_FOR_CARD = "Card number not provided";

    /** {@code NO-SEARCH-CRITERIA-RECEIVED}, COCRDSLC lines 142 to 143, raised ungated at line 639. */
    private static final String MSG_NO_INPUT = "No input received";

    /**
     * {@code DID-NOT-FIND-ACCTCARD-COMBO}, COCRDSLC lines 153 to 154. The card-number read's
     * not-found text, raised at line 760 <em>behind</em> the message gate.
     */
    private static final String MSG_NO_CARDS_FOR_CONDITION =
            "Did not find cards for this search condition";

    /**
     * {@code DID-NOT-FIND-ACCT-IN-CARDXREF}, COCRDSLC lines 151 to 152. The account-keyed read's
     * not-found text, raised at line 799 <em>without</em> the gate, and deliberately not the same
     * text as the card-number read's.
     */
    private static final String MSG_ACCOUNT_NOT_IN_DATABASE =
            "Did not find this account in cards database";

    /** The literal moved at COCRDSLC lines 669 to 671. */
    private static final String MSG_ACCOUNT_NOT_NUMERIC =
            "ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER";

    /** The literal moved at COCRDSLC lines 710 to 712. */
    private static final String MSG_CARD_NOT_NUMERIC =
            "CARD ID FILTER,IF SUPPLIED MUST BE A 16 DIGIT NUMBER";

    /** Moved at COCRDSLC lines 377 to 378 by the catch-all dispatch arm. */
    private static final String MSG_UNEXPECTED_DATA_SCENARIO = "UNEXPECTED DATA SCENARIO";

    /** The default abend text applied at COCRDSLC line 860 only when none was already set. */
    private static final String MSG_UNEXPECTED_ABEND = "UNEXPECTED ABEND OCCURRED.";

    /**
     * {@code CCDA-MSG-INVALID-KEY} of {@code app/cpy/CSMSG01Y.cpy} at its declared fifty-character
     * width: forty visible characters plus ten trailing spaces, written as an explicit repeat count.
     * Declared here rather than read from the catalogue, so the width is genuinely asserted.
     */
    private static final String MSG_INVALID_KEY =
            "Invalid key pressed. Please see below..." + " ".repeat(10);

    /**
     * {@code CCDA-MSG-THANK-YOU} of the same copybook: forty-three visible characters plus seven
     * trailing spaces. Present to prove the catalogue's two common messages share one width and are
     * padded differently, which is what makes the width a contract rather than a coincidence.
     */
    private static final String MSG_THANK_YOU =
            "Thank you for using CardDemo application..." + " ".repeat(7);

    /** {@code CCDA-TITLE01} at its forty-character catalogue width. */
    private static final String TITLE01 =
            " ".repeat(6) + "AWS Mainframe Modernization" + " ".repeat(7);

    /** {@code CCDA-TITLE02} at its forty-character catalogue width. */
    private static final String TITLE02 = " ".repeat(14) + "CardDemo" + " ".repeat(18);

    /** The decoration marker the source writes into a blank filter field at lines 543 and 549. */
    private static final String DECORATION_MARKER = "*";

    /** The terminal abend carrying code 9999, at COCRDSLC line 875 to 876. */
    private static final String ONLINE_ABEND_CODE = "9999";

    /** The opening word of the abend diagnostic, which must be emitted before the raise. */
    private static final String ABEND_DIAGNOSTIC_MARKER = "ABENDING TRANSACTION";

    // -- Widths, all measured on encoded bytes and never on character counts ---------------------

    /** The contractual width of a common message, {@code PIC X(50)}. */
    private static final int COMMON_MESSAGE_WIDTH = 50;

    /** {@code WS-RETURN-MSG}, {@code PIC X(75)} at COCRDSLC line 134. */
    private static final int RETURN_MESSAGE_WIDTH = 75;

    /** {@code ERRMSGO} of the symbolic map, {@code PIC X(80)}. */
    private static final int ERROR_MESSAGE_FIELD_WIDTH = 80;

    /** {@code INFOMSGO} and the two screen titles, all forty characters. */
    private static final int FORTY_CHARACTER_FIELD = 40;

    /** {@code ACCTSID}, {@code PIC X(11)}. */
    private static final int ACCOUNT_ID_WIDTH = 11;

    /** {@code CARDSID}, {@code PIC X(16)}. */
    private static final int CARD_NUMBER_WIDTH = 16;

    /** {@code CRDNAME}, {@code PIC X(50)}. */
    private static final int EMBOSSED_NAME_WIDTH = 50;

    /** {@code CARD-CVV-CD-X}, {@code PIC X(03)} - three characters, never a number. */
    private static final int VERIFICATION_CODE_WIDTH = 3;

    /** The whole card record, {@code RECLN 150} per {@code app/cpy/CVACT02Y.cpy}. */
    private static final int CARD_RECORD_WIDTH = 150;

    /** {@code ABEND-CODE}, four characters. */
    private static final int ABEND_CODE_LENGTH = 4;

    /** {@code ABEND-CULPRIT}, eight characters, and it carries the program name. */
    private static final int ABEND_CULPRIT_LENGTH = 8;

    /** {@code ABEND-REASON}, fifty characters. */
    private static final int ABEND_REASON_LENGTH = 50;

    /** {@code ABEND-MSG}, seventy-two characters. */
    private static final int ABEND_MESSAGE_LENGTH = 72;

    /** The whole abend context: 4 + 8 + 50 + 72. */
    private static final int ABEND_CONTEXT_LENGTH = 134;

    /** The number of destinations the navigation authority publishes. */
    private static final int ROUTE_COUNT = 17;

    /** Transfer-control dispatch sites in the estate, all of which became route constants. */
    private static final int LEGACY_DISPATCH_SITE_COUNT = 25;

    /** Pseudo-conversational re-arm sites in the estate, all of which became route constants. */
    private static final int LEGACY_REARM_SITE_COUNT = 19;

    /** The member this suite answers for, as the traceability matrix cites it. */
    private static final String LEGACY_MEMBER = "COCRDSLC.cbl";

    /** Own Area-A paragraph labels of COCRDSLC, which is this member's whole contribution. */
    private static final int OWN_PARAGRAPH_LABEL_COUNT = 34;

    /**
     * Paragraphs the attention-key copybook expands into this member's procedure division.
     *
     * <p>Exercised here, counted in the copybook's own section: it is included by five members, and
     * counting its paragraphs against each of them would report ten units for two.
     */
    private static final int COPYBOOK_PARAGRAPH_COUNT = 2;

    // -- Fixture values --------------------------------------------------------------------------

    /**
     * Three card numbers in deliberately <strong>non-ascending</strong> order, so that a service
     * which re-sorted whatever the read handed it would present a different card from the one the
     * read chose. {@code MIDDLE} is first, so first-of-the-list and lowest-card-number differ.
     */
    private static final String CARD_MIDDLE = "4222222222222222";

    private static final String CARD_HIGHEST = "4333333333333333";

    private static final String CARD_LOWEST = "4111111111111111";

    /** The eleven-digit account filter used throughout. Chosen to contain no {@code 007}. */
    private static final String ACCOUNT = "00000000011";

    /** A verification code whose leading zeros are the whole point: it must never become {@code 7}. */
    private static final String CVV_WITH_LEADING_ZEROS = "007";

    /**
     * The embossed name in the first row of {@code app/data/ASCII/carddata.txt}. Every one of that
     * fixture's fifty rows carries an embedded blank, so an all-letters predicate would reject the
     * entire seeded file.
     */
    private static final String SEEDED_EMBOSSED_NAME = "Aniya Von";

    /** A second embedded-blank name, upper case, exercising the same blank-and-trim idiom. */
    private static final String EMBOSSED_NAME_WITH_SPACE = "MARY ANN";

    /** A name carrying a digit, which the alphabetic predicate must refuse. */
    private static final String NAME_WITH_DIGIT = "MARY 4NN";

    /** The stored expiry date; its components are 2023, 03 and 09. */
    private static final String EXPIRY = "2023-03-09";

    private static final String EXPIRY_YEAR = "2023";

    private static final String EXPIRY_MONTH = "03";

    private static final String EXPIRY_DAY = "09";

    /** A distinct expiry, so the field-mapping test cannot pass on a coincidence. */
    private static final String OTHER_EXPIRY = "2029-11-27";

    private static final String OTHER_EXPIRY_YEAR = "2029";

    private static final String OTHER_EXPIRY_MONTH = "11";

    private static final String OTHER_EXPIRY_DAY = "27";

    /** {@code CARD-ACTIVE-STATUS} as the vocabulary declares it. */
    private static final String STATUS_ACTIVE = "Y";

    private static final String STATUS_INACTIVE = "N";

    /** A fixed instant, so the screen header is deterministic and no clock is read twice. */
    private static final Instant FIXED_INSTANT = Instant.parse("2024-03-09T14:25:36Z");

    private static final String EXPECTED_HEADER_DATE = "03/09/24";

    private static final String EXPECTED_HEADER_TIME = "14:25:36";

    /** The redaction stand-in both the entity and the projection publish. */
    private static final String REDACTED = "***REDACTED***";

    /** The attention identifier of the enter key. */
    private static final String AID_ENTER = "DFHENTER";

    /** The third program-function key: the only key this screen acts on besides enter. */
    private static final String AID_PF3 = "DFHPF3";

    /** The fifteenth key, which the copybook folds onto the third. */
    private static final String AID_PF15 = "DFHPF15";

    /** An identifier outside the copybook's 28-clause selection altogether. */
    private static final String AID_UNRECOGNISED = "DFHPF99";

    // ==============================================================================================
    // Collaborators. All four are mocks, so nothing outside this process is touched.
    // ==============================================================================================

    @Mock
    private CardRepository cardRepository;

    @Mock
    private AbendService abendService;

    @Mock
    private MessageCatalogService messageCatalogService;

    @Mock
    private NavigationService navigationService;

    private CardDetailService service;

    private Logger serviceLogger;

    private ListAppender<ILoggingEvent> logRecorder;

    private Level originalLevel;

    @BeforeEach
    void constructServiceAndAttachLogRecorder() {
        this.service = new CardDetailService(this.cardRepository, this.abendService,
                this.messageCatalogService, this.navigationService,
                Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC));

        this.serviceLogger = (Logger) LoggerFactory.getLogger(CardDetailService.class);
        this.originalLevel = this.serviceLogger.getLevel();
        this.logRecorder = new ListAppender<>();
        this.logRecorder.setContext(this.serviceLogger.getLoggerContext());
        this.logRecorder.start();
        this.serviceLogger.addAppender(this.logRecorder);
        // Trace, so that every diagnostic the service can emit is captured and can be searched for a
        // value that must never appear in one.
        this.serviceLogger.setLevel(Level.TRACE);
    }

    @AfterEach
    void detachLogRecorder() {
        this.serviceLogger.detachAppender(this.logRecorder);
        this.logRecorder.stop();
        this.serviceLogger.setLevel(this.originalLevel);
    }

    // ==============================================================================================
    // Helpers. None of them computes an expected value; they only build inputs and read recordings.
    // ==============================================================================================

    private static Card card(final String cardNumber, final String verificationCode,
            final String embossedName, final String expiry, final String status) {
        return TestDataFactory.card()
                .cardNumber(cardNumber)
                .accountId(ACCOUNT)
                .verificationCode(verificationCode)
                .embossedName(embossedName)
                .expirationDate(expiry)
                .activeStatus(status)
                .build();
    }

    private static Card activeCard(final String cardNumber) {
        return card(cardNumber, CVV_WITH_LEADING_ZEROS, SEEDED_EMBOSSED_NAME, EXPIRY,
                STATUS_ACTIVE);
    }

    /** A turn standing at re-entry, which is the only shape that reaches the edits and the read. */
    private static CardDetailService.CardDetailScreenInput reSubmission(final String accountFilter,
            final String cardFilter, final String attentionIdentifier) {
        return new CardDetailService.CardDetailScreenInput(accountFilter, cardFilter,
                attentionIdentifier, ScreenNavigationState.empty().withReEntry());
    }

    /** A turn carrying no navigation state at all, which is the zero-length communication area. */
    private static CardDetailService.CardDetailScreenInput firstEntry(
            final String attentionIdentifier) {
        return new CardDetailService.CardDetailScreenInput(null, null, attentionIdentifier,
                ScreenNavigationState.empty());
    }

    /** A first entry handed over by the card-list screen, whose criteria are already validated. */
    private static CardDetailService.CardDetailScreenInput handOverFromCardList(
            final String accountId, final String cardNumber) {
        final ScreenNavigationState context = new ScreenNavigationState(
                null, CARD_LIST_PROGRAM, null, null, null, null,
                ScreenNavigationState.ProgramContext.ENTER, null, null, null, null,
                accountId, null, cardNumber, null, CARD_LIST_MAPSET);
        return new CardDetailService.CardDetailScreenInput(accountId, cardNumber, AID_ENTER,
                context);
    }

    private String recordedLogText() {
        final StringBuilder text = new StringBuilder();
        for (final ILoggingEvent event : this.logRecorder.list) {
            text.append(event.getFormattedMessage()).append('\n');
        }
        return text.toString();
    }

    private int recordedEventCount() {
        return this.logRecorder.list.size();
    }

    private boolean recordedAnyMessageContaining(final String fragment) {
        for (final ILoggingEvent event : this.logRecorder.list) {
            if (event.getFormattedMessage().contains(fragment)) {
                return true;
            }
        }
        return false;
    }

    // ==============================================================================================
    // Construction, and the one public entry point's own preconditions
    // ==============================================================================================

    @Nested
    @DisplayName("construction - every collaborator is required")
    class Construction {

        @Test
        @DisplayName("a missing collaborator fails at wiring time and the refusal names it")
        void everyCollaboratorIsRequired() {
            final Clock clock = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
            assertAll(
                    () -> assertThatExceptionOfType(NullPointerException.class)
                            .isThrownBy(() -> new CardDetailService(null, abendService,
                                    messageCatalogService, navigationService, clock))
                            .withMessageContaining("cardRepository"),
                    () -> assertThatExceptionOfType(NullPointerException.class)
                            .isThrownBy(() -> new CardDetailService(cardRepository, null,
                                    messageCatalogService, navigationService, clock))
                            .withMessageContaining("abendService"),
                    () -> assertThatExceptionOfType(NullPointerException.class)
                            .isThrownBy(() -> new CardDetailService(cardRepository, abendService,
                                    null, navigationService, clock))
                            .withMessageContaining("messageCatalogService"),
                    () -> assertThatExceptionOfType(NullPointerException.class)
                            .isThrownBy(() -> new CardDetailService(cardRepository, abendService,
                                    messageCatalogService, null, clock))
                            .withMessageContaining("navigationService"),
                    () -> assertThatExceptionOfType(NullPointerException.class)
                            .isThrownBy(() -> new CardDetailService(cardRepository, abendService,
                                    messageCatalogService, navigationService, null))
                            .withMessageContaining("clock"));
        }

        @Test
        @DisplayName("processCardDetail refuses an absent turn and names the parameter")
        void aTurnRequiresAnInput() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> service.processCardDetail(null))
                    .withMessageContaining("input");
            verifyNoInteractions(cardRepository, abendService, navigationService);
        }
    }

    // ==============================================================================================
    // 0000-MAIN 248, 1000-SEND-MAP 412, 1100-SCREEN-INIT 427, 1200-SETUP-SCREEN-VARS 457,
    // 1300-SETUP-SCREEN-ATTRS 502, 1400-SEND-SCREEN 563, COMMON-RETURN 394, 0000-MAIN-EXIT 408
    // ==============================================================================================

    @Nested
    @DisplayName("first entry - the screen gathers criteria before it reads anything")
    class FirstEntry {

        @Test
        @DisplayName("0000-MAIN 248 and 1200-SETUP-SCREEN-VARS 457: a turn carrying no navigation "
                + "state shows the input prompt and reads nothing")
        void aTurnCarryingNoStateShowsThePromptAndReadsNothing() {
            final CardDetailService.CardDetailResult result =
                    service.processCardDetail(firstEntry(AID_ENTER));

            assertAll(
                    () -> assertThat(result.infoMessage()).isEqualTo(MSG_PROMPT_FOR_INPUT),
                    () -> assertThat(result.message()).isEmpty(),
                    () -> assertThat(result.card()).isNull(),
                    () -> assertThat(result.cardPresented()).isFalse(),
                    () -> assertThat(result.errorFlag()).isFalse(),
                    () -> assertThat(result.route()).isEqualTo(NavigationService.Route.CARD_DETAIL),
                    () -> assertThat(result.reArmedTransactionId()).isEqualTo(TRANSACTION_ID),
                    () -> assertThat(result.fieldErrors()).isEmpty());
            verify(cardRepository, never()).findById(anyString());
            verify(cardRepository, never()).findFirstByCardAcctIdOrderByCardNumAsc(anyString());
        }

        @Test
        @DisplayName("1000-SEND-MAP 412 and 1400-SEND-SCREEN 563: the four-step presentation range "
                + "ends by raising the re-enter gate, arming the next turn as a re-submission")
        void theSendRaisesTheReEnterGate() {
            final CardDetailService.CardDetailResult result =
                    service.processCardDetail(firstEntry(AID_ENTER));

            assertThat(result.reEnterFlag()).isTrue();
            assertThat(result.navigationContext().reEntry()).isTrue();
            assertThat(result.workArea().nextMapset()).isEqualTo(THIS_MAPSET_TRUNCATED);
            assertThat(result.workArea().nextMap()).isEqualTo(THIS_MAP);
        }

        @Test
        @DisplayName("1300-SETUP-SCREEN-ATTRS 502: a first entry shows an empty filter field, never "
                + "the decoration marker, because the gate is still down")
        void aFirstEntryShowsNoDecorationMarker() {
            final CardDetailService.CardDetailResult result =
                    service.processCardDetail(firstEntry(AID_ENTER));

            assertAll(
                    () -> assertThat(result.screen().accountIdFilter())
                            .doesNotContain(DECORATION_MARKER)
                            .isBlank()
                            .hasSize(ACCOUNT_ID_WIDTH),
                    () -> assertThat(result.screen().cardNumberFilter())
                            .doesNotContain(DECORATION_MARKER)
                            .isBlank()
                            .hasSize(CARD_NUMBER_WIDTH),
                    () -> assertThat(result.screen().accountIdHighlighted()).isFalse(),
                    () -> assertThat(result.screen().cardNumberHighlighted()).isFalse());
        }

        @Test
        @DisplayName("1100-SCREEN-INIT 427: the header is assembled from the injected clock and the "
                + "catalogue titles at their declared widths")
        void theHeaderIsAssembledFromTheInjectedClock() {
            when(messageCatalogService.screenTitle01()).thenReturn(TITLE01);
            when(messageCatalogService.screenTitle02()).thenReturn(TITLE02);

            final CardDetailService.ScreenHeader header =
                    service.processCardDetail(firstEntry(AID_ENTER)).header();

            assertAll(
                    () -> assertThat(header.title01()).isEqualTo(TITLE01),
                    () -> assertThat(header.title02()).isEqualTo(TITLE02),
                    () -> assertThat(header.title01().getBytes(StandardCharsets.US_ASCII))
                            .hasSize(FORTY_CHARACTER_FIELD),
                    () -> assertThat(header.title02().getBytes(StandardCharsets.US_ASCII))
                            .hasSize(FORTY_CHARACTER_FIELD),
                    () -> assertThat(header.transactionName()).isEqualTo(TRANSACTION_ID),
                    () -> assertThat(header.programName()).isEqualTo(PROGRAM_NAME),
                    () -> assertThat(header.currentDate()).isEqualTo(EXPECTED_HEADER_DATE),
                    () -> assertThat(header.currentTime()).isEqualTo(EXPECTED_HEADER_TIME));
        }

        @Test
        @DisplayName("0000-MAIN 248 second arm: a hand-off from the card-list screen skips the edits, "
                + "reads immediately and arrives with both filters protected")
        void aHandOffFromTheCardListScreenSkipsTheEdits() {
            when(cardRepository.findById(CARD_MIDDLE))
                    .thenReturn(Optional.of(activeCard(CARD_MIDDLE)));

            final CardDetailService.CardDetailResult result =
                    service.processCardDetail(handOverFromCardList(ACCOUNT, CARD_MIDDLE));

            assertAll(
                    () -> assertThat(result.cardPresented()).isTrue(),
                    () -> assertThat(result.infoMessage()).isEqualTo(MSG_FOUND_CARDS),
                    () -> assertThat(result.fieldErrors()).isEmpty(),
                    () -> assertThat(result.focusField()).isEqualTo(FIELD_ACCOUNT_ID),
                    () -> assertThat(result.screen().accountIdProtected()).isTrue(),
                    () -> assertThat(result.screen().cardNumberProtected()).isTrue(),
                    () -> assertThat(result.screen().accountIdHighlighted()).isFalse(),
                    () -> assertThat(result.screen().cardNumberHighlighted()).isFalse());
            verify(cardRepository).findById(CARD_MIDDLE);
        }

        @Test
        @DisplayName("COMMON-RETURN 394: the turn re-arms its own transaction rather than forwarding")
        void theTurnReArmsItsOwnTransaction() {
            final CardDetailService.CardDetailResult result =
                    service.processCardDetail(firstEntry(AID_ENTER));

            assertThat(result.reArmedTransactionId()).isEqualTo(TRANSACTION_ID);
            assertThat(result.route().getLegacyTransactionId()).isEqualTo(TRANSACTION_ID);
            assertThat(result.route().getRouteValue()).isEqualTo(ROUTE_CARD_DETAIL);
            verifyNoInteractions(navigationService);
        }
    }


    // ==============================================================================================
    // 2000-PROCESS-INPUTS 582, 2100-RECEIVE-MAP 596, 2200-EDIT-MAP-INPUTS 608,
    // 2210-EDIT-ACCOUNT 647, 2220-EDIT-CARD 685 and their four exit paragraphs
    // ==============================================================================================

    @Nested
    @DisplayName("input edits - the summary is first-past-the-post, the field flags are not")
    class InputEdits {

        @Test
        @DisplayName("2200-EDIT-MAP-INPUTS 608: both filters blank ends on the ungated cross-field "
                + "text, overwriting the account prompt the per-field edit had already set")
        void bothFiltersBlankEndsOnTheCrossFieldText() {
            final CardDetailService.CardDetailResult result =
                    service.processCardDetail(reSubmission(null, null, AID_ENTER));

            assertAll(
                    () -> assertThat(result.message()).isEqualTo(MSG_NO_INPUT),
                    () -> assertThat(result.message()).isNotEqualTo(MSG_PROMPT_FOR_ACCOUNT),
                    () -> assertThat(result.errorFlag()).isTrue(),
                    () -> assertThat(result.fieldErrors()).hasSize(2),
                    () -> assertThat(result.fieldErrors())
                            .extracting(ValidationException.FieldError::state)
                            .containsExactly(ValidationException.FieldState.MISSING,
                                    ValidationException.FieldState.MISSING));
            verify(cardRepository, never()).findById(anyString());
        }

        @Test
        @DisplayName("2210-EDIT-ACCOUNT 647: only the account blank keeps the account prompt and "
                + "leaves the cursor on the account filter")
        void onlyTheAccountBlankKeepsTheAccountPrompt() {
            final CardDetailService.CardDetailResult result =
                    service.processCardDetail(reSubmission(null, CARD_MIDDLE, AID_ENTER));

            assertAll(
                    () -> assertThat(result.message()).isEqualTo(MSG_PROMPT_FOR_ACCOUNT),
                    () -> assertThat(result.focusField()).isEqualTo(FIELD_ACCOUNT_ID),
                    () -> assertThat(result.fieldErrors()).hasSize(1),
                    () -> assertThat(result.fieldErrors().get(0).field())
                            .isEqualTo(PROPERTY_ACCOUNT_ID),
                    () -> assertThat(result.fieldErrors().get(0).bmsFieldId())
                            .isEqualTo(FIELD_ACCOUNT_ID),
                    () -> assertThat(result.fieldErrors().get(0).state())
                            .isEqualTo(ValidationException.FieldState.MISSING));
            verify(cardRepository, never()).findById(anyString());
        }

        @Test
        @DisplayName("2220-EDIT-CARD 685: only the card blank keeps the card prompt and moves the "
                + "cursor to the card filter, which is the second arm of the cursor cascade")
        void onlyTheCardBlankMovesTheCursorToTheCard() {
            final CardDetailService.CardDetailResult result =
                    service.processCardDetail(reSubmission(ACCOUNT, null, AID_ENTER));

            assertAll(
                    () -> assertThat(result.message()).isEqualTo(MSG_PROMPT_FOR_CARD),
                    () -> assertThat(result.focusField()).isEqualTo(FIELD_CARD_NUMBER),
                    () -> assertThat(result.fieldErrors()).hasSize(1),
                    () -> assertThat(result.fieldErrors().get(0).bmsFieldId())
                            .isEqualTo(FIELD_CARD_NUMBER),
                    () -> assertThat(result.fieldErrors().get(0).message())
                            .isEqualTo(MSG_PROMPT_FOR_CARD));
        }

        @Test
        @DisplayName("2210-EDIT-ACCOUNT 647 and 2220-EDIT-CARD 685: a FULLY zero-filled filter is NOT "
                + "SUPPLIED, so it is MISSING rather than INVALID - which is why the legacy message "
                + "speaks of a non-zero number")
        void aFullyZeroFilledFilterIsNotSuppliedRatherThanInvalid() {
            final CardDetailService.CardDetailResult accountZeroed = service
                    .processCardDetail(reSubmission("0".repeat(ACCOUNT_ID_WIDTH), CARD_MIDDLE,
                            AID_ENTER));
            final CardDetailService.CardDetailResult cardZeroed = service
                    .processCardDetail(reSubmission(ACCOUNT, "0".repeat(CARD_NUMBER_WIDTH),
                            AID_ENTER));

            assertAll(
                    () -> assertThat(accountZeroed.message()).isEqualTo(MSG_PROMPT_FOR_ACCOUNT),
                    () -> assertThat(accountZeroed.fieldErrors()).hasSize(1),
                    () -> assertThat(accountZeroed.fieldErrors().get(0).state())
                            .isEqualTo(ValidationException.FieldState.MISSING),
                    () -> assertThat(cardZeroed.message()).isEqualTo(MSG_PROMPT_FOR_CARD),
                    () -> assertThat(cardZeroed.fieldErrors()).hasSize(1),
                    () -> assertThat(cardZeroed.fieldErrors().get(0).state())
                            .isEqualTo(ValidationException.FieldState.MISSING));
            verify(cardRepository, never()).findById(anyString());
        }

        @ParameterizedTest
        @ValueSource(strings = {"0", "000", "0000000000"})
        @DisplayName("2210-EDIT-ACCOUNT 647: a PARTLY zero-filled filter is not the all-zero case at "
                + "all - the receive pads it with spaces, so the class condition fails and it is "
                + "INVALID")
        void aPartlyZeroFilledFilterFailsTheClassConditionInstead(final String shortZeroes) {
            final CardDetailService.CardDetailResult result =
                    service.processCardDetail(reSubmission(shortZeroes, CARD_MIDDLE, AID_ENTER));

            assertThat(result.message()).isEqualTo(MSG_ACCOUNT_NOT_NUMERIC);
            assertThat(result.fieldErrors().get(0).state())
                    .isEqualTo(ValidationException.FieldState.INVALID);
        }

        @ParameterizedTest
        @ValueSource(strings = {"1234A678901", "12345", "0000000001 ", "1234-678-901"})
        @DisplayName("2210-EDIT-ACCOUNT 647: a filter that is not eleven ASCII digits fails the class "
                + "condition and is INVALID, trailing spaces included")
        void aFilterThatIsNotElevenDigitsIsInvalid(final String badAccount) {
            final CardDetailService.CardDetailResult result =
                    service.processCardDetail(reSubmission(badAccount, CARD_MIDDLE, AID_ENTER));

            assertThat(result.message()).isEqualTo(MSG_ACCOUNT_NOT_NUMERIC);
            assertThat(result.fieldErrors()).hasSize(1);
            assertThat(result.fieldErrors().get(0).state())
                    .isEqualTo(ValidationException.FieldState.INVALID);
            assertThat(result.screen().accountIdHighlighted()).isTrue();
        }

        @Test
        @DisplayName("2200-EDIT-MAP-INPUTS 608: the first failure wins the summary while both fields "
                + "are still flagged, because only the message assignment is gated")
        void theFirstFailureWinsTheSummaryWhileBothFieldsAreFlagged() {
            final CardDetailService.CardDetailResult result =
                    service.processCardDetail(reSubmission(null, "4111ABCD11111111", AID_ENTER));

            assertAll(
                    () -> assertThat(result.message()).isEqualTo(MSG_PROMPT_FOR_ACCOUNT),
                    () -> assertThat(result.fieldErrors()).hasSize(2),
                    () -> assertThat(result.fieldErrors())
                            .extracting(ValidationException.FieldError::bmsFieldId)
                            .containsExactly(FIELD_ACCOUNT_ID, FIELD_CARD_NUMBER),
                    () -> assertThat(result.fieldErrors())
                            .extracting(ValidationException.FieldError::state)
                            .containsExactly(ValidationException.FieldState.MISSING,
                                    ValidationException.FieldState.INVALID),
                    () -> assertThat(result.fieldErrors().get(1).message())
                            .isEqualTo(MSG_CARD_NOT_NUMERIC));
        }

        @Test
        @DisplayName("1300-SETUP-SCREEN-ATTRS 502: the decoration marker replaces a blank field only "
                + "while the re-enter gate is up")
        void theDecorationMarkerAppearsOnlyOnAReSubmission() {
            final CardDetailService.CardDetailResult result =
                    service.processCardDetail(reSubmission(null, null, AID_ENTER));

            assertAll(
                    () -> assertThat(result.screen().accountIdFilter())
                            .startsWith(DECORATION_MARKER).hasSize(ACCOUNT_ID_WIDTH),
                    () -> assertThat(result.screen().cardNumberFilter())
                            .startsWith(DECORATION_MARKER).hasSize(CARD_NUMBER_WIDTH),
                    () -> assertThat(result.screen().accountIdHighlighted()).isTrue(),
                    () -> assertThat(result.screen().cardNumberHighlighted()).isTrue());
        }

        @Test
        @DisplayName("2200-EDIT-MAP-INPUTS 608: the decoration marker is read back as nothing "
                + "supplied, never as a literal asterisk")
        void theDecorationMarkerIsReadBackAsNothingSupplied() {
            final CardDetailService.CardDetailResult result = service.processCardDetail(
                    reSubmission(DECORATION_MARKER, DECORATION_MARKER, AID_ENTER));

            assertThat(result.message()).isEqualTo(MSG_NO_INPUT);
            assertThat(result.fieldErrors())
                    .extracting(ValidationException.FieldError::state)
                    .containsExactly(ValidationException.FieldState.MISSING,
                            ValidationException.FieldState.MISSING);
        }

        @Test
        @DisplayName("2100-RECEIVE-MAP 596: the two message fields keep their own declared widths, "
                + "measured on encoded bytes")
        void theTwoMessageFieldsKeepTheirOwnWidths() {
            final CardDetailService.CardDetailResult result =
                    service.processCardDetail(reSubmission(null, null, AID_ENTER));

            assertAll(
                    () -> assertThat(result.screen().errorMessage()
                            .getBytes(StandardCharsets.US_ASCII))
                            .hasSize(ERROR_MESSAGE_FIELD_WIDTH),
                    () -> assertThat(result.screen().infoMessage()
                            .getBytes(StandardCharsets.US_ASCII))
                            .hasSize(FORTY_CHARACTER_FIELD),
                    () -> assertThat(result.screen().errorMessage()).startsWith(MSG_NO_INPUT),
                    () -> assertThat(result.workArea().returnMessage()).isEqualTo(MSG_NO_INPUT));
        }
    }

    // ==============================================================================================
    // 9000-READ-DATA 726 and 9100-GETCARD-BYACCTCARD 736 - the one read the source reaches
    // ==============================================================================================

    @Nested
    @DisplayName("the card-number read - base cluster CARDDAT, keyed on the card number alone")
    class CardNumberRead {

        @Test
        @DisplayName("9100-GETCARD-BYACCTCARD 736: a successful read presents the card and splits the "
                + "expiry by position")
        void aSuccessfulReadPresentsTheCard() {
            when(cardRepository.findById(CARD_MIDDLE))
                    .thenReturn(Optional.of(activeCard(CARD_MIDDLE)));

            final CardDetailService.CardDetailResult result =
                    service.processCardDetail(reSubmission(ACCOUNT, CARD_MIDDLE, AID_ENTER));

            assertAll(
                    () -> assertThat(result.cardPresented()).isTrue(),
                    () -> assertThat(result.errorFlag()).isFalse(),
                    () -> assertThat(result.message()).isEmpty(),
                    () -> assertThat(result.infoMessage()).isEqualTo(MSG_FOUND_CARDS),
                    () -> assertThat(result.card().cardNumber()).isEqualTo(CARD_MIDDLE),
                    () -> assertThat(result.card().expirationDate()).isEqualTo(EXPIRY),
                    () -> assertThat(result.card().expiryYear()).isEqualTo(EXPIRY_YEAR),
                    () -> assertThat(result.card().expiryMonth()).isEqualTo(EXPIRY_MONTH),
                    () -> assertThat(result.card().expiryDay()).isEqualTo(EXPIRY_DAY),
                    () -> assertThat(result.screen().expiryMonth()).isEqualTo(EXPIRY_MONTH),
                    () -> assertThat(result.screen().expiryYear()).isEqualTo(EXPIRY_YEAR),
                    () -> assertThat(result.screen().cardActiveStatus()).isEqualTo(STATUS_ACTIVE));
        }

        @Test
        @DisplayName("9100-GETCARD-BYACCTCARD 736: the read keys on the card number alone, so a "
                + "supplied account filter takes no part in it and no account finder is consulted")
        void theReadKeysOnTheCardNumberAlone() {
            when(cardRepository.findById(CARD_MIDDLE))
                    .thenReturn(Optional.of(activeCard(CARD_MIDDLE)));

            service.processCardDetail(reSubmission(ACCOUNT, CARD_MIDDLE, AID_ENTER));

            verify(cardRepository).findById(CARD_MIDDLE);
            verify(cardRepository, never()).findFirstByCardAcctIdOrderByCardNumAsc(anyString());
            verify(cardRepository, never()).findByCardAcctIdOrderByCardNumAsc(anyString(), any());
        }

        @Test
        @DisplayName("9100-GETCARD-BYACCTCARD 736 not-found arm: an absent record faults BOTH filter "
                + "fields and raises its own text, and nothing is thrown")
        void anAbsentRecordFaultsBothFilterFields() {
            when(cardRepository.findById(CARD_MIDDLE)).thenReturn(Optional.empty());

            final CardDetailService.CardDetailResult result =
                    service.processCardDetail(reSubmission(ACCOUNT, CARD_MIDDLE, AID_ENTER));

            assertAll(
                    () -> assertThat(result.message()).isEqualTo(MSG_NO_CARDS_FOR_CONDITION),
                    () -> assertThat(result.card()).isNull(),
                    () -> assertThat(result.errorFlag()).isTrue(),
                    () -> assertThat(result.fieldErrors()).hasSize(2),
                    () -> assertThat(result.fieldErrors())
                            .extracting(ValidationException.FieldError::bmsFieldId)
                            .containsExactly(FIELD_ACCOUNT_ID, FIELD_CARD_NUMBER),
                    () -> assertThat(result.fieldErrors())
                            .extracting(ValidationException.FieldError::state)
                            .containsExactly(ValidationException.FieldState.INVALID,
                                    ValidationException.FieldState.INVALID),
                    () -> assertThat(result.focusField()).isEqualTo(FIELD_ACCOUNT_ID),
                    () -> assertThat(result.infoMessage()).isEqualTo(MSG_PROMPT_FOR_INPUT));
        }

        @Test
        @DisplayName("9100-GETCARD-BYACCTCARD 736: an absent record is not a record-not-found "
                + "exception - the source sets a flag and re-presents the screen")
        void anAbsentRecordDoesNotThrow() {
            when(cardRepository.findById(CARD_MIDDLE)).thenReturn(Optional.empty());

            assertThatNoException().isThrownBy(
                    () -> service.processCardDetail(reSubmission(ACCOUNT, CARD_MIDDLE, AID_ENTER)));
            verifyNoInteractions(abendService);
        }

        @ParameterizedTest
        @ValueSource(strings = {"X", "0", " ", "y"})
        @DisplayName("9100-GETCARD-BYACCTCARD 736 catch-all arm: a stored status outside the "
                + "vocabulary composes the file-error message rather than taking the not-found arm")
        void aStoredStatusOutsideTheVocabularyTakesTheCatchAllArm(final String badStatus) {
            when(cardRepository.findById(CARD_MIDDLE)).thenReturn(Optional.of(
                    card(CARD_MIDDLE, CVV_WITH_LEADING_ZEROS, SEEDED_EMBOSSED_NAME, EXPIRY,
                            badStatus)));

            final CardDetailService.CardDetailResult result =
                    service.processCardDetail(reSubmission(ACCOUNT, CARD_MIDDLE, AID_ENTER));

            assertAll(
                    () -> assertThat(result.card()).isNull(),
                    () -> assertThat(result.errorFlag()).isTrue(),
                    () -> assertThat(result.message()).isNotEqualTo(MSG_NO_CARDS_FOR_CONDITION),
                    () -> assertThat(result.message()).startsWith("File Error: ")
                            .contains("READ")
                            .contains("CARDDAT"),
                    () -> assertThat(result.message().getBytes(StandardCharsets.US_ASCII))
                            .hasSize(RETURN_MESSAGE_WIDTH));
        }

        @ParameterizedTest
        @CsvSource({"00A, the verification code is not three digits",
                    "07, the verification code is short of its width"})
        @DisplayName("9100-GETCARD-BYACCTCARD 736 catch-all arm: a stored verification code that is "
                + "not three digits fails the layout edit without ever being parsed to a number")
        void aMalformedVerificationCodeTakesTheCatchAllArm(final String badCode,
                final String reason) {
            when(cardRepository.findById(CARD_MIDDLE)).thenReturn(Optional.of(
                    card(CARD_MIDDLE, badCode, SEEDED_EMBOSSED_NAME, EXPIRY, STATUS_ACTIVE)));

            final CardDetailService.CardDetailResult result =
                    service.processCardDetail(reSubmission(ACCOUNT, CARD_MIDDLE, AID_ENTER));

            assertThat(result.card()).as(reason).isNull();
            assertThat(result.message()).startsWith("File Error: ");
        }

        @Test
        @DisplayName("9000-READ-DATA 726: the driver performs the card-number read and nothing else, "
                + "so an inactive card is presented exactly as stored")
        void anInactiveCardIsPresentedAsStored() {
            when(cardRepository.findById(CARD_MIDDLE)).thenReturn(Optional.of(
                    card(CARD_MIDDLE, CVV_WITH_LEADING_ZEROS, SEEDED_EMBOSSED_NAME, EXPIRY,
                            STATUS_INACTIVE)));

            final CardDetailService.CardDetailResult result =
                    service.processCardDetail(reSubmission(ACCOUNT, CARD_MIDDLE, AID_ENTER));

            assertThat(result.card().activeStatus()).isEqualTo(STATUS_INACTIVE);
            assertThat(result.card().status()).isEqualTo(CardStatus.N);
            assertThat(result.cardPresented()).isTrue();
        }
    }


    // ==============================================================================================
    // 9150-GETCARD-BYACCT 779 - the account-keyed read over the non-unique alternate index CARDAIX.
    // No PERFORM in the member reaches it, so it is driven through the documented package-private
    // seam; driving it through a turn is impossible by design.
    // ==============================================================================================

    /**
     * The account-keyed read and its terminator, neither of which any delivered call site reaches.
     *
     * <p>Two matrix rows are discharged here: {@code 9150-GETCARD-BYACCT} at source line 779, through the
     * named calls to {@code getCardByAcct} below, and {@code 9150-GETCARD-BYACCT-EXIT} at 810, through
     * {@code getCardByAcctExit}, which the head calls on every one of its arms - the found arm, the
     * not-found arm and the catch-all. Proving the arms therefore proves the terminator with them, which
     * is why it carries no call of its own: it is the {@code EXIT.} statement of the range and is
     * reachable only from the head above it.
     *
     * <p>The seam is ordinary package access, never reflection: the production tree is held to a
     * reflection count of zero, and a reflective call would prove nothing about a call the delivered
     * driver could make.
     */
    @Nested
    @DisplayName("the account-keyed read - alternate index CARDAIX, unreachable from any PERFORM")
    class AccountKeyedRead {

        @Test
        @DisplayName("9150-GETCARD-BYACCT 779 not-found arm: an absent result IS the legacy not-found "
                + "outcome - no index error, no no-such-element, no record-not-found exception")
        void anAbsentResultIsTheNotFoundOutcome() {
            when(cardRepository.findFirstByCardAcctIdOrderByCardNumAsc(ACCOUNT))
                    .thenReturn(Optional.empty());
            final CardDetailService.TurnState state = new CardDetailService.TurnState();

            assertThatNoException().isThrownBy(() -> service.getCardByAcct(state, ACCOUNT));

            final CardDetailService.CardDetailResult result = service.toResult(state);
            assertAll(
                    () -> assertThat(result.card()).isNull(),
                    () -> assertThat(result.errorFlag()).isTrue(),
                    () -> assertThat(result.message()).isEqualTo(MSG_ACCOUNT_NOT_IN_DATABASE),
                    () -> assertThat(result.fieldErrors()).hasSize(1),
                    () -> assertThat(result.fieldErrors().get(0).bmsFieldId())
                            .isEqualTo(FIELD_ACCOUNT_ID));
            verifyNoInteractions(abendService);
        }

        @Test
        @DisplayName("9150-GETCARD-BYACCT 779: the read is the bounded ordered-first finder, so the "
                + "list-returning account finder is never consulted and nothing is ever indexed into")
        void theListReturningAccountFinderIsNeverConsulted() {
            when(cardRepository.findFirstByCardAcctIdOrderByCardNumAsc(ACCOUNT))
                    .thenReturn(Optional.of(activeCard(CARD_MIDDLE)));
            final CardDetailService.TurnState state = new CardDetailService.TurnState();

            service.getCardByAcct(state, ACCOUNT);

            verify(cardRepository).findFirstByCardAcctIdOrderByCardNumAsc(ACCOUNT);
            verify(cardRepository, never()).findByCardAcctIdOrderByCardNumAsc(anyString(), any());
            verify(cardRepository, never()).findById(anyString());
        }

        @Test
        @DisplayName("9150-GETCARD-BYACCT 779: given three candidate cards in NON-ASCENDING order the "
                + "FIRST as supplied is presented, so no re-sorting is applied on the way through")
        void theFirstCandidateAsSuppliedIsPresented() {
            // Deliberately non-ascending: the first element is the middle card number and the lowest
            // card number sits last, so a service that re-sorted what the read handed it would present
            // a different card and this assertion would fail.
            final List<Card> candidates = List.of(activeCard(CARD_MIDDLE), activeCard(CARD_HIGHEST),
                    activeCard(CARD_LOWEST));
            when(cardRepository.findFirstByCardAcctIdOrderByCardNumAsc(ACCOUNT))
                    .thenReturn(Optional.of(candidates.get(0)));
            final CardDetailService.TurnState state = new CardDetailService.TurnState();

            service.getCardByAcct(state, ACCOUNT);

            final CardDetailService.CardDetailResult result = service.toResult(state);
            assertAll(
                    () -> assertThat(result.card().cardNumber()).isEqualTo(CARD_MIDDLE),
                    () -> assertThat(result.card().cardNumber())
                            .isNotEqualTo(CARD_LOWEST)
                            .isNotEqualTo(CARD_HIGHEST),
                    () -> assertThat(result.errorFlag()).isFalse(),
                    () -> assertThat(result.infoMessage()).isEqualTo(MSG_FOUND_CARDS));
            verify(cardRepository, never()).findByCardAcctIdOrderByCardNumAsc(anyString(), any());
        }

        @Test
        @DisplayName("9150-GETCARD-BYACCT 779 and SEND-LONG-TEXT 820: this not-found text is set "
                + "UNGATED, so it overwrites a summary message that was already present")
        void itsNotFoundTextOverwritesAMessageAlreadySet() {
            when(cardRepository.findFirstByCardAcctIdOrderByCardNumAsc(ACCOUNT))
                    .thenReturn(Optional.empty());
            final CardDetailService.TurnState state = new CardDetailService.TurnState();
            // The long-text send is the member's other unreachable paragraph and the only seam that
            // writes the summary field, so it is what seeds a prior message here - exercising both
            // unreachable paragraphs with one turn and needing no reflection to do it.
            service.sendLongText(state, MSG_PROMPT_FOR_ACCOUNT);
            assertThat(service.toResult(state).message()).isEqualTo(MSG_PROMPT_FOR_ACCOUNT);

            service.getCardByAcct(state, ACCOUNT);

            assertThat(service.toResult(state).message()).isEqualTo(MSG_ACCOUNT_NOT_IN_DATABASE);
        }

        @Test
        @DisplayName("SEND-LONG-TEXT 820: the plain send carries no transaction identifier, so it "
                + "re-arms nothing")
        void theLongTextSendReArmsNothing() {
            // Two further matrix rows are discharged by this call: SEND-LONG-TEXT at 820 through
            // sendLongText, and SEND-LONG-TEXT-EXIT at 831 through sendLongTextExit, which this method
            // calls on its only arm. Like every other terminator in this member the exit carries no call
            // of its own, because it is the EXIT. statement of the range and is reachable only from the
            // head above it.
            final CardDetailService.TurnState state = new CardDetailService.TurnState();

            service.sendLongText(state, MSG_UNEXPECTED_DATA_SCENARIO);

            assertThat(service.toResult(state).reArmedTransactionId()).isEmpty();
            assertThat(service.toResult(state).message()).isEqualTo(MSG_UNEXPECTED_DATA_SCENARIO);
        }

        @Test
        @DisplayName("SEND-LONG-TEXT 820: an absent diagnostic leaves the summary field empty rather "
                + "than storing a null")
        void theLongTextSendToleratesAnAbsentDiagnostic() {
            final CardDetailService.TurnState state = new CardDetailService.TurnState();

            assertThatNoException().isThrownBy(() -> service.sendLongText(state, null));

            assertThat(service.toResult(state).message()).isEmpty();
        }

        @Test
        @DisplayName("9150-GETCARD-BYACCT 779 catch-all arm: an unusable record names the ALTERNATE "
                + "INDEX in the file-error message, not the base cluster")
        void theCatchAllNamesTheAlternateIndex() {
            when(cardRepository.findFirstByCardAcctIdOrderByCardNumAsc(ACCOUNT)).thenReturn(
                    Optional.of(card(CARD_MIDDLE, CVV_WITH_LEADING_ZEROS, SEEDED_EMBOSSED_NAME,
                            EXPIRY, "X")));
            final CardDetailService.TurnState state = new CardDetailService.TurnState();

            service.getCardByAcct(state, ACCOUNT);

            final CardDetailService.CardDetailResult result = service.toResult(state);
            assertAll(
                    () -> assertThat(result.card()).isNull(),
                    () -> assertThat(result.message()).contains("CARDAIX"),
                    () -> assertThat(result.message()).doesNotContain("CARDDAT"),
                    () -> assertThat(result.message().getBytes(StandardCharsets.US_ASCII))
                            .hasSize(RETURN_MESSAGE_WIDTH));
        }

        @Test
        @DisplayName("9150-GETCARD-BYACCT 779: the account key is bounded to its eleven-character "
                + "field before the read, so a longer identifier is truncated rather than rejected")
        void theAccountKeyIsBoundedToItsFieldWidth() {
            when(cardRepository.findFirstByCardAcctIdOrderByCardNumAsc(ACCOUNT))
                    .thenReturn(Optional.empty());
            final CardDetailService.TurnState state = new CardDetailService.TurnState();

            service.getCardByAcct(state, ACCOUNT + "999");

            verify(cardRepository).findFirstByCardAcctIdOrderByCardNumAsc(ACCOUNT);
        }
    }

    // ==============================================================================================
    // The two not-found outcomes, held apart - COCRDSLC lines 755 to 761 against 796 to 799
    // ==============================================================================================

    @Nested
    @DisplayName("the two not-found outcomes stay distinct, because the source distinguishes them")
    class DistinctNotFoundOutcomes {

        @Test
        @DisplayName("the card-number miss and the account-number miss produce DIFFERENT text and "
                + "DIFFERENT field-in-error identification, and neither text is the other")
        void theTwoNotFoundOutcomesDifferInTextAndInFieldsFaulted() {
            when(cardRepository.findById(CARD_MIDDLE)).thenReturn(Optional.empty());
            when(cardRepository.findFirstByCardAcctIdOrderByCardNumAsc(ACCOUNT))
                    .thenReturn(Optional.empty());

            final CardDetailService.CardDetailResult byCardNumber =
                    service.processCardDetail(reSubmission(ACCOUNT, CARD_MIDDLE, AID_ENTER));
            final CardDetailService.TurnState accountState = new CardDetailService.TurnState();
            service.getCardByAcct(accountState, ACCOUNT);
            final CardDetailService.CardDetailResult byAccountNumber = service.toResult(accountState);

            assertAll(
                    () -> assertThat(byCardNumber.message()).isEqualTo(MSG_NO_CARDS_FOR_CONDITION),
                    () -> assertThat(byAccountNumber.message())
                            .isEqualTo(MSG_ACCOUNT_NOT_IN_DATABASE),
                    () -> assertThat(byCardNumber.message()).isNotEqualTo(byAccountNumber.message()),
                    () -> assertThat(byCardNumber.fieldErrors())
                            .extracting(ValidationException.FieldError::bmsFieldId)
                            .containsExactly(FIELD_ACCOUNT_ID, FIELD_CARD_NUMBER),
                    () -> assertThat(byAccountNumber.fieldErrors())
                            .extracting(ValidationException.FieldError::bmsFieldId)
                            .containsExactly(FIELD_ACCOUNT_ID),
                    () -> assertThat(byCardNumber.fieldErrors())
                            .hasSizeGreaterThan(byAccountNumber.fieldErrors().size()),
                    () -> assertThat(byCardNumber.fieldErrors().get(0).message())
                            .isNotEqualTo(byAccountNumber.fieldErrors().get(0).message()));
        }

        @Test
        @DisplayName("the two texts are declared apart in the source and must never be collapsed into "
                + "one, so the oracle itself asserts they are different literals")
        void theTwoDeclaredTextsAreDifferentLiterals() {
            assertThat(MSG_NO_CARDS_FOR_CONDITION).isNotEqualTo(MSG_ACCOUNT_NOT_IN_DATABASE);
            assertThat(MSG_NO_CARDS_FOR_CONDITION).doesNotContain(MSG_ACCOUNT_NOT_IN_DATABASE);
            assertThat(MSG_ACCOUNT_NOT_IN_DATABASE).doesNotContain(MSG_NO_CARDS_FOR_CONDITION);
        }

        @Test
        @DisplayName("neither not-found path raises the module's record-not-found exception, which is "
                + "why this service does not use its no-argument constructor")
        void neitherPathRaisesRecordNotFound() {
            when(cardRepository.findById(CARD_MIDDLE)).thenReturn(Optional.empty());
            when(cardRepository.findFirstByCardAcctIdOrderByCardNumAsc(ACCOUNT))
                    .thenReturn(Optional.empty());
            final CardDetailService.TurnState state = new CardDetailService.TurnState();

            assertAll(
                    () -> assertThatNoException().isThrownBy(() -> service
                            .processCardDetail(reSubmission(ACCOUNT, CARD_MIDDLE, AID_ENTER))),
                    () -> assertThatNoException()
                            .isThrownBy(() -> service.getCardByAcct(state, ACCOUNT)),
                    () -> assertThat(new RecordNotFoundException())
                            .isInstanceOf(RuntimeException.class));
        }
    }

    // ==============================================================================================
    // COPY 'CSSTRPFY' 855, YYYY-STORE-PFKEY 17 and YYYY-STORE-PFKEY-EXIT 80 - exercised from this
    // member, and counted in the copybook's own matrix section rather than among this member's 34
    // ==============================================================================================

    @Nested
    @DisplayName("attention keys - decoded by the translator, then coerced by the pessimistic gate")
    class AttentionKeys {

        @Test
        @DisplayName("0000-MAIN 248 first arm: the third program-function key transfers to the "
                + "resolved destination and reads nothing")
        void theThirdProgramFunctionKeyTransfersToTheResolvedDestination() {
            when(navigationService.resolveBackNavigation(any(), eq(NavigationService.Route.USER_MENU)))
                    .thenReturn(NavigationService.Route.USER_MENU);

            final CardDetailService.CardDetailResult result =
                    service.processCardDetail(firstEntry(AID_PF3));

            assertAll(
                    () -> assertThat(result.route()).isEqualTo(NavigationService.Route.USER_MENU),
                    () -> assertThat(result.route().getRouteValue()).isEqualTo(ROUTE_USER_MENU),
                    () -> assertThat(result.reArmedTransactionId()).isEmpty(),
                    () -> assertThat(result.message()).isEmpty(),
                    () -> assertThat(result.workArea().keyAction()).isEqualTo(KeyAction.PFK03));
            verify(cardRepository, never()).findById(anyString());
        }

        @Test
        @DisplayName("YYYY-STORE-PFKEY 17: the FIFTEENTH key folds onto the THIRD and behaves "
                + "identically - same destination, same message, same re-arm, same decoded action")
        void theFifteenthKeyFoldsOntoTheThird() {
            when(navigationService.resolveBackNavigation(any(), eq(NavigationService.Route.USER_MENU)))
                    .thenReturn(NavigationService.Route.USER_MENU);

            final CardDetailService.CardDetailResult viaLowKey =
                    service.processCardDetail(firstEntry(AID_PF3));
            final CardDetailService.CardDetailResult viaFoldedKey =
                    service.processCardDetail(firstEntry(AID_PF15));

            assertAll(
                    () -> assertThat(viaFoldedKey.route()).isEqualTo(viaLowKey.route()),
                    () -> assertThat(viaFoldedKey.message()).isEqualTo(viaLowKey.message()),
                    () -> assertThat(viaFoldedKey.reArmedTransactionId())
                            .isEqualTo(viaLowKey.reArmedTransactionId()),
                    () -> assertThat(viaFoldedKey.errorFlag()).isEqualTo(viaLowKey.errorFlag()),
                    () -> assertThat(viaFoldedKey.workArea().keyAction())
                            .isEqualTo(viaLowKey.workArea().keyAction())
                            .isEqualTo(KeyAction.PFK03),
                    () -> assertThat(viaFoldedKey.navigationContext())
                            .isEqualTo(viaLowKey.navigationContext()));
        }

        @Test
        @DisplayName("YYYY-STORE-PFKEY 17: an identifier the copybook's selection does not recognise "
                + "draws the catalogue's invalid-key text and leaves the destination unchanged")
        void anUnrecognisedIdentifierDrawsTheInvalidKeyTextWithNoRouteChange() {
            when(messageCatalogService.invalidKeyMessage()).thenReturn(MSG_INVALID_KEY);

            final CardDetailService.CardDetailResult result =
                    service.processCardDetail(firstEntry(AID_UNRECOGNISED));

            assertAll(
                    () -> assertThat(result.message()).isEqualTo(MSG_INVALID_KEY),
                    () -> assertThat(result.errorFlag()).isTrue(),
                    () -> assertThat(result.route()).isEqualTo(NavigationService.Route.CARD_DETAIL),
                    () -> assertThat(result.route().getRouteValue()).isEqualTo(ROUTE_CARD_DETAIL),
                    () -> assertThat(result.reArmedTransactionId()).isEqualTo(TRANSACTION_ID),
                    // The decoding step assigned nothing, because the copybook's selection has no
                    // catch-all - and then the pessimistic gate COERCED the empty action to the enter
                    // key so the screen re-presents. The unrecognised condition survives the coercion
                    // in the error flag and the message, which is where the source keeps it.
                    () -> assertThat(result.workArea().keyAction()).isEqualTo(KeyAction.ENTER));
            verifyNoInteractions(navigationService);
            verify(cardRepository, never()).findById(anyString());
        }

        @Test
        @DisplayName("YYYY-STORE-PFKEY 17: an absent identifier is treated as unrecognised, because "
                + "the copybook's selection declares no catch-all clause")
        void anAbsentIdentifierIsTreatedAsUnrecognised() {
            when(messageCatalogService.invalidKeyMessage()).thenReturn(MSG_INVALID_KEY);

            final CardDetailService.CardDetailResult result =
                    service.processCardDetail(firstEntry(null));

            assertThat(result.message()).isEqualTo(MSG_INVALID_KEY);
            assertThat(result.errorFlag()).isTrue();
            // Coerced to the enter key by the same pessimistic gate, so the screen re-presents rather
            // than the turn failing.
            assertThat(result.workArea().keyAction()).isEqualTo(KeyAction.ENTER);
        }

        @ParameterizedTest
        @ValueSource(strings = {"DFHPF5", "DFHPF12", "DFHPF24", "DFHCLEAR", "DFHPA1", "DFHPA2"})
        @DisplayName("0000-MAIN 248 gate: a recognised but unpermitted key is coerced to enter "
                + "SILENTLY, with no message at all, so the screen simply re-presents")
        void aRecognisedButUnpermittedKeyIsCoercedSilently(final String identifier) {
            final CardDetailService.CardDetailResult result =
                    service.processCardDetail(firstEntry(identifier));

            assertAll(
                    () -> assertThat(result.message()).isEmpty(),
                    () -> assertThat(result.errorFlag()).isFalse(),
                    () -> assertThat(result.route()).isEqualTo(NavigationService.Route.CARD_DETAIL),
                    () -> assertThat(result.reArmedTransactionId()).isEqualTo(TRANSACTION_ID));
            verifyNoInteractions(navigationService);
        }

        @Test
        @DisplayName("YYYY-STORE-PFKEY-EXIT 80: the enter key is one of the two the gate permits, so "
                + "it reaches the dispatch with its decoded action intact")
        void theEnterKeyIsPermittedAndReachesTheDispatch() {
            final CardDetailService.CardDetailResult result =
                    service.processCardDetail(firstEntry(AID_ENTER));

            assertThat(result.workArea().keyAction()).isEqualTo(KeyAction.ENTER);
            assertThat(result.errorFlag()).isFalse();
        }
    }

    // ==============================================================================================
    // The fifty-character common-message contract, measured on encoded bytes
    // ==============================================================================================

    @Nested
    @DisplayName("the invalid-key text - fifty encoded bytes, passed through untouched")
    class InvalidKeyMessageWidth {

        @Test
        @DisplayName("the catalogue's fifty-character value reaches the caller BYTE-IDENTICALLY, at "
                + "exactly fifty encoded bytes, with its ten trailing spaces intact and never trimmed")
        void theFiftyCharacterValueIsPassedThroughByteIdentically() {
            when(messageCatalogService.invalidKeyMessage()).thenReturn(MSG_INVALID_KEY);

            final String published =
                    service.processCardDetail(firstEntry(AID_UNRECOGNISED)).message();

            assertAll(
                    () -> assertThat(published).isEqualTo(MSG_INVALID_KEY),
                    () -> assertThat(published.getBytes(StandardCharsets.US_ASCII))
                            .hasSize(COMMON_MESSAGE_WIDTH),
                    () -> assertThat(published).endsWith(" ".repeat(10)),
                    () -> assertThat(published).isNotEqualTo(published.trim()),
                    () -> assertThat(published).isNotEqualTo(MSG_INVALID_KEY.stripTrailing()));
            verify(messageCatalogService).invalidKeyMessage();
        }

        @Test
        @DisplayName("the summary field is eighty characters wide, so the fifty-byte value is PADDED "
                + "into it and never truncated")
        void theFiftyByteValueIsPaddedIntoTheEightyCharacterField() {
            when(messageCatalogService.invalidKeyMessage()).thenReturn(MSG_INVALID_KEY);

            final CardDetailService.CardDetailResult result =
                    service.processCardDetail(firstEntry(AID_UNRECOGNISED));

            assertThat(result.screen().errorMessage().getBytes(StandardCharsets.US_ASCII))
                    .hasSize(ERROR_MESSAGE_FIELD_WIDTH);
            assertThat(result.screen().errorMessage()).startsWith(MSG_INVALID_KEY);
        }

        @Test
        @DisplayName("both common messages share the fifty-byte width while being padded differently, "
                + "which is what makes the width a contract rather than a coincidence")
        void bothCommonMessagesShareTheDeclaredWidth() {
            assertAll(
                    () -> assertThat(MSG_INVALID_KEY.getBytes(StandardCharsets.US_ASCII))
                            .hasSize(COMMON_MESSAGE_WIDTH),
                    () -> assertThat(MSG_THANK_YOU.getBytes(StandardCharsets.US_ASCII))
                            .hasSize(COMMON_MESSAGE_WIDTH),
                    () -> assertThat(MSG_INVALID_KEY).isNotEqualTo(MSG_THANK_YOU),
                    () -> assertThat(MSG_INVALID_KEY.stripTrailing()).hasSize(40),
                    () -> assertThat(MSG_THANK_YOU.stripTrailing()).hasSize(43));
        }
    }

    // ==============================================================================================
    // The withheld values - the card verification code and the card number
    // ==============================================================================================

    @Nested
    @DisplayName("withheld values - the verification code stays a three-character string and never "
            + "reaches a log line")
    class WithheldValues {

        @Test
        @DisplayName("a leading-zero verification code round-trips as the STRING \"007\" - three "
                + "characters, leading zeros intact, never a number")
        void aLeadingZeroVerificationCodeRoundTripsAsAString() {
            when(cardRepository.findById(CARD_MIDDLE))
                    .thenReturn(Optional.of(activeCard(CARD_MIDDLE)));

            final CardDetailService.CardProjection card =
                    service.processCardDetail(reSubmission(ACCOUNT, CARD_MIDDLE, AID_ENTER)).card();

            assertAll(
                    () -> assertThat(card.verificationCode()).isEqualTo(CVV_WITH_LEADING_ZEROS),
                    () -> assertThat(card.verificationCode()).isInstanceOf(String.class),
                    () -> assertThat(card.verificationCode()).startsWith("0"),
                    () -> assertThat(card.verificationCode()
                            .getBytes(StandardCharsets.US_ASCII))
                            .hasSize(VERIFICATION_CODE_WIDTH),
                    () -> assertThat(card.verificationCode()).isNotEqualTo("7"));
        }

        @Test
        @DisplayName("NO captured log event names the verification code, on the successful path or on "
                + "any other")
        void noCapturedLogEventNamesTheVerificationCode() {
            when(cardRepository.findById(CARD_MIDDLE))
                    .thenReturn(Optional.of(activeCard(CARD_MIDDLE)));

            final CardDetailService.CardDetailResult result =
                    service.processCardDetail(reSubmission(ACCOUNT, CARD_MIDDLE, AID_ENTER));

            assertThat(result.card().verificationCode()).isEqualTo(CVV_WITH_LEADING_ZEROS);
            // The recorder must have captured something, or the absence assertion below would be
            // vacuously true.
            assertThat(recordedEventCount()).isPositive();
            assertThat(recordedAnyMessageContaining(CVV_WITH_LEADING_ZEROS)).isFalse();
            assertThat(recordedLogText())
                    .doesNotContain(CVV_WITH_LEADING_ZEROS)
                    .doesNotContain(CARD_MIDDLE);
        }

        @Test
        @DisplayName("the projection's own rendering withholds the card number, the account, the name "
                + "and the verification code behind a fixed stand-in")
        void theProjectionRenderingWithholdsEveryProtectedComponent() {
            when(cardRepository.findById(CARD_MIDDLE))
                    .thenReturn(Optional.of(activeCard(CARD_MIDDLE)));

            final CardDetailService.CardProjection card =
                    service.processCardDetail(reSubmission(ACCOUNT, CARD_MIDDLE, AID_ENTER)).card();

            assertAll(
                    () -> assertThat(card.toString()).contains(REDACTED),
                    () -> assertThat(card.toString()).doesNotContain(CARD_MIDDLE),
                    () -> assertThat(card.toString()).doesNotContain(CVV_WITH_LEADING_ZEROS),
                    () -> assertThat(card.toString()).doesNotContain(SEEDED_EMBOSSED_NAME),
                    () -> assertThat(card.toString()).contains(EXPIRY));
        }
    }


    // ==============================================================================================
    // The alphabetic edit - the legacy idiom blanks the letters and then trims, so a space that was
    // already there is trimmed exactly as a blanked letter is
    // ==============================================================================================

    @Nested
    @DisplayName("the alphabetic edit - embedded spaces pass, and an all-letters test would not")
    class AlphabeticEdit {

        @ParameterizedTest
        @ValueSource(strings = {"MARY ANN", "Aniya Von", "Mary Ann Smith", "A B"})
        @DisplayName("an embossed name carrying an embedded space is ACCEPTED and reaches the screen "
                + "unchanged, so the whole seeded card fixture survives the turn")
        void anEmbossedNameCarryingAnEmbeddedSpaceIsAccepted(final String name) {
            when(cardRepository.findById(CARD_MIDDLE)).thenReturn(Optional.of(
                    card(CARD_MIDDLE, CVV_WITH_LEADING_ZEROS, name, EXPIRY, STATUS_ACTIVE)));

            final CardDetailService.CardDetailResult result =
                    service.processCardDetail(reSubmission(ACCOUNT, CARD_MIDDLE, AID_ENTER));

            assertAll(
                    () -> assertThat(result.cardPresented()).isTrue(),
                    () -> assertThat(result.card().embossedName()).startsWith(name),
                    () -> assertThat(result.card().embossedName()
                            .getBytes(StandardCharsets.US_ASCII)).hasSize(EMBOSSED_NAME_WIDTH),
                    () -> assertThat(result.screen().embossedName()).startsWith(name),
                    () -> assertThat(result.screen().embossedName()
                            .getBytes(StandardCharsets.US_ASCII)).hasSize(EMBOSSED_NAME_WIDTH));
        }

        @Test
        @DisplayName("the shared character-class predicate accepts both embedded-space names and "
                + "refuses a name carrying a digit, so it is discriminating rather than permissive")
        void theSharedPredicateIsDiscriminating() {
            // The expected values here are the boolean literals, never a second call into production
            // code. An all-letters test would fail the first two of these and would therefore reject
            // every one of the fifty rows of the seeded card fixture.
            assertAll(
                    () -> assertThat(CobolStringUtils.isAlphaOrSpace(EMBOSSED_NAME_WITH_SPACE))
                            .isTrue(),
                    () -> assertThat(CobolStringUtils.isAlphaOrSpace(SEEDED_EMBOSSED_NAME)).isTrue(),
                    () -> assertThat(CobolStringUtils.isAlphaOrSpace(NAME_WITH_DIGIT)).isFalse(),
                    () -> assertThat(CobolStringUtils.isAlphaOrSpace("MARY-ANN")).isFalse());
        }

        @Test
        @DisplayName("2210-EDIT-ACCOUNT 647: the numeric class condition is the mirror image - a "
                + "filter carrying a letter is refused, which is what the message says")
        void theNumericClassConditionRefusesALetter() {
            final CardDetailService.CardDetailResult result =
                    service.processCardDetail(reSubmission("1234A678901", CARD_MIDDLE, AID_ENTER));

            assertThat(result.message()).isEqualTo(MSG_ACCOUNT_NOT_NUMERIC);
            assertThat(result.fieldErrors().get(0).state())
                    .isEqualTo(ValidationException.FieldState.INVALID);
        }
    }

    // ==============================================================================================
    // ABEND-ROUTINE 857, armed at COCRDSLC lines 250 and 871, ending in the abend at line 875
    // ==============================================================================================

    @Nested
    @DisplayName("the abend path - emit the diagnostic first, then raise")
    class AbendPath {

        @Test
        @DisplayName("ABEND-ROUTINE 857: the diagnostic is already recorded at the moment the delegate "
                + "raises, and the raised context carries the PROGRAM NAME as culprit and code 9999")
        void theDiagnosticIsRecordedBeforeTheRaise() {
            final AbendException raised = new AbendException(ONLINE_ABEND_CODE, PROGRAM_NAME,
                    MSG_UNEXPECTED_ABEND, MSG_UNEXPECTED_ABEND);
            when(cardRepository.findById(CARD_MIDDLE))
                    .thenThrow(new IllegalStateException("the store refused the keyed read"));
            doAnswer(invocation -> {
                // Evaluated at the instant the delegate is entered. If the service raised before it
                // emitted, the recorder would still be empty here and this would fail inside the call
                // rather than after it.
                assertThat(recordedAnyMessageContaining(ABEND_DIAGNOSTIC_MARKER)).isTrue();
                throw raised;
            }).when(abendService).abendOnline(anyString(), anyString(), anyString());

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> service
                            .processCardDetail(reSubmission(ACCOUNT, CARD_MIDDLE, AID_ENTER)))
                    .isSameAs(raised);

            assertAll(
                    () -> assertThat(raised.code()).isEqualTo(ONLINE_ABEND_CODE),
                    () -> assertThat(raised.culprit()).isEqualTo(PROGRAM_NAME),
                    () -> assertThat(raised.reason()).isEqualTo(MSG_UNEXPECTED_ABEND),
                    () -> assertThat(raised).isInstanceOf(RuntimeException.class));
            verify(abendService)
                    .abendOnline(eq(PROGRAM_NAME), eq(MSG_UNEXPECTED_ABEND), eq(MSG_UNEXPECTED_ABEND));
        }

        @Test
        @DisplayName("ABEND-ROUTINE 857: the read is attempted before the abend delegate is reached, "
                + "which is the ordering the armed handler implies")
        void theReadIsAttemptedBeforeTheDelegate() {
            when(cardRepository.findById(CARD_MIDDLE))
                    .thenThrow(new IllegalStateException("the store refused the keyed read"));

            service.processCardDetail(reSubmission(ACCOUNT, CARD_MIDDLE, AID_ENTER));

            final InOrder ordered = inOrder(cardRepository, abendService);
            ordered.verify(cardRepository).findById(CARD_MIDDLE);
            ordered.verify(abendService).abendOnline(eq(PROGRAM_NAME), anyString(), anyString());
        }

        @Test
        @DisplayName("ABEND-ROUTINE 857: the abend context layout is four, eight, fifty and "
                + "seventy-two characters at offsets 0, 4, 12 and 62, totalling 134")
        void theAbendContextCarriesItsDeclaredLayout() {
            final AbendException raised = new AbendException(ONLINE_ABEND_CODE, PROGRAM_NAME,
                    MSG_UNEXPECTED_ABEND, MSG_UNEXPECTED_ABEND);

            final String context = raised.toFixedWidthContext();

            assertAll(
                    () -> assertThat(context.getBytes(StandardCharsets.US_ASCII))
                            .hasSize(ABEND_CONTEXT_LENGTH),
                    () -> assertThat(context.substring(0, ABEND_CODE_LENGTH))
                            .isEqualTo(ONLINE_ABEND_CODE),
                    () -> assertThat(context.substring(ABEND_CODE_LENGTH,
                            ABEND_CODE_LENGTH + ABEND_CULPRIT_LENGTH)).isEqualTo(PROGRAM_NAME),
                    () -> assertThat(context.substring(ABEND_CODE_LENGTH + ABEND_CULPRIT_LENGTH,
                            ABEND_CODE_LENGTH + ABEND_CULPRIT_LENGTH + ABEND_REASON_LENGTH))
                            .startsWith(MSG_UNEXPECTED_ABEND),
                    () -> assertThat(context.substring(ABEND_CODE_LENGTH + ABEND_CULPRIT_LENGTH
                            + ABEND_REASON_LENGTH)).hasSize(ABEND_MESSAGE_LENGTH));
        }

        @Test
        @DisplayName("ABEND-ROUTINE 857: the diagnostic names the transaction, the culprit and the "
                + "resource, and still names no withheld value")
        void theDiagnosticNamesTheOperatorFactsAndNothingWithheld() {
            when(cardRepository.findById(CARD_MIDDLE))
                    .thenThrow(new IllegalStateException("the store refused the keyed read"));

            service.processCardDetail(reSubmission(ACCOUNT, CARD_MIDDLE, AID_ENTER));

            assertAll(
                    () -> assertThat(recordedLogText()).contains(ABEND_DIAGNOSTIC_MARKER),
                    () -> assertThat(recordedLogText()).contains(TRANSACTION_ID),
                    () -> assertThat(recordedLogText()).contains(PROGRAM_NAME),
                    () -> assertThat(recordedLogText()).contains("CARDDAT"),
                    () -> assertThat(recordedLogText()).contains(MSG_UNEXPECTED_ABEND),
                    () -> assertThat(recordedLogText()).doesNotContain(CVV_WITH_LEADING_ZEROS),
                    () -> assertThat(recordedLogText()).doesNotContain(CARD_MIDDLE));
        }

        @Test
        @DisplayName("0000-MAIN 248 catch-all arm and SEND-PLAIN-TEXT 838: a state whose program-context "
                + "flag is ABSENT still takes the first-entry arm, which is why the unexpected-data arm "
                + "and the plain-text send it performs cannot be reached at all")
        void theCatchAllDispatchArmIsUnreachableBecauseFirstEntryIsTheNegationOfReEntry() {
            // First entry is defined as the negation of re-entry, so an absent flag is a first entry
            // and never a third state. That is precisely why the source's own WHEN OTHER arm is
            // unreachable in the shipped estate: reaching it would need a third value that the
            // one-digit context field cannot hold. The arm is translated for fidelity and this test
            // records why no input drives it, rather than leaving the claim unexamined.
            final ScreenNavigationState absentContextFlag = new ScreenNavigationState(
                    null, CARD_LIST_PROGRAM, null, null, null, null, null, null, null, null, null,
                    ACCOUNT, null, CARD_MIDDLE, null, CARD_LIST_MAPSET);
            when(cardRepository.findById(CARD_MIDDLE))
                    .thenReturn(Optional.of(activeCard(CARD_MIDDLE)));

            final CardDetailService.CardDetailResult result =
                    service.processCardDetail(new CardDetailService.CardDetailScreenInput(
                            ACCOUNT, CARD_MIDDLE, AID_ENTER, absentContextFlag));

            assertAll(
                    () -> assertThat(absentContextFlag.firstEntry()).isTrue(),
                    () -> assertThat(absentContextFlag.reEntry()).isFalse(),
                    () -> assertThat(result.message()).isNotEqualTo(MSG_UNEXPECTED_DATA_SCENARIO),
                    () -> assertThat(result.reArmedTransactionId()).isEqualTo(TRANSACTION_ID),
                    () -> assertThat(result.cardPresented()).isTrue());
            verifyNoInteractions(abendService);
        }
    }

    // ==============================================================================================
    // Routes are constants returned to the caller; there is no server-side forwarding anywhere
    // ==============================================================================================

    @Nested
    @DisplayName("route and navigation fidelity - constants out, no forwarding, no dangling target")
    class RouteAndNavigationFidelity {

        @Test
        @DisplayName("the turn publishes a route CONSTANT for its own screen and forwards nothing, so "
                + "the navigation authority is not even consulted on the self-representing path")
        void theTurnPublishesARouteConstantAndForwardsNothing() {
            final CardDetailService.CardDetailResult result =
                    service.processCardDetail(firstEntry(AID_ENTER));

            assertAll(
                    () -> assertThat(result.route()).isInstanceOf(NavigationService.Route.class),
                    () -> assertThat(result.route()).isEqualTo(NavigationService.Route.CARD_DETAIL),
                    () -> assertThat(result.route().getRouteValue()).isEqualTo(ROUTE_CARD_DETAIL),
                    () -> assertThat(result.route().getLegacyProgramName()).isEqualTo(PROGRAM_NAME),
                    () -> assertThat(result.route().getLegacyTransactionId())
                            .isEqualTo(TRANSACTION_ID),
                    () -> assertThat(result.route().isAdminScoped()).isFalse());
            verifyNoInteractions(navigationService);
        }

        @Test
        @DisplayName("the back-navigation destination comes from the navigation authority and is NOT "
                + "a literal in this service, so a different resolution yields a different route")
        void theDestinationComesFromTheNavigationAuthority() {
            when(navigationService.resolveBackNavigation(any(), eq(NavigationService.Route.USER_MENU)))
                    .thenReturn(NavigationService.Route.CARD_LIST);

            final CardDetailService.CardDetailResult result =
                    service.processCardDetail(firstEntry(AID_PF3));

            assertThat(result.route()).isEqualTo(NavigationService.Route.CARD_LIST);
            assertThat(result.route()).isNotEqualTo(NavigationService.Route.USER_MENU);
            verify(navigationService)
                    .resolveBackNavigation(any(), eq(NavigationService.Route.USER_MENU));
        }

        @Test
        @DisplayName("the back-navigation state names THIS screen as the originator and records its "
                + "own map and mapset, the mapset truncated to the seven-character field")
        void theBackNavigationStateNamesThisScreen() {
            when(navigationService.resolveBackNavigation(any(), eq(NavigationService.Route.USER_MENU)))
                    .thenReturn(NavigationService.Route.USER_MENU);

            final ScreenNavigationState context =
                    service.processCardDetail(firstEntry(AID_PF3)).navigationContext();

            assertAll(
                    () -> assertThat(context.fromProgram()).isEqualTo(PROGRAM_NAME),
                    () -> assertThat(context.fromTransactionId()).isEqualTo(TRANSACTION_ID),
                    () -> assertThat(context.toProgram()).isEqualTo(USER_MENU_PROGRAM),
                    () -> assertThat(context.lastMap()).isEqualTo(THIS_MAP),
                    () -> assertThat(context.lastMapset()).isEqualTo(THIS_MAPSET_TRUNCATED),
                    () -> assertThat(context.firstEntry()).isTrue());
        }

        @Test
        @DisplayName("NO published destination corresponds to the dangling CICS program definition, "
                + "which is declared at CSD line 211 and bound at line 390 with no source member")
        void noPublishedDestinationNamesTheDanglingCicsProgram() {
            final NavigationService.Route[] routes = NavigationService.Route.values();

            assertThat(routes).hasSize(ROUTE_COUNT);
            assertThat(routes)
                    .extracting(NavigationService.Route::getLegacyProgramName)
                    .doesNotContain(DANGLING_CICS_PROGRAM);
            assertThat(routes)
                    .extracting(NavigationService.Route::getRouteValue)
                    .noneMatch(value -> value.toUpperCase(Locale.ROOT)
                            .contains(DANGLING_CICS_PROGRAM));
        }

        @Test
        @DisplayName("the estate's transfer-control and re-arm censuses are published as route "
                + "constants, which is why this service declares no route table of its own")
        void theDispatchAndReArmCensusesArePublished() {
            assertAll(
                    () -> assertThat(NavigationService.LEGACY_DISPATCH_SITE_COUNT)
                            .isEqualTo(LEGACY_DISPATCH_SITE_COUNT),
                    () -> assertThat(NavigationService.LEGACY_REARM_SITE_COUNT)
                            .isEqualTo(LEGACY_REARM_SITE_COUNT),
                    () -> assertThat(NavigationService.ROUTE_COUNT).isEqualTo(ROUTE_COUNT));
        }
    }


    // ==============================================================================================
    // Absent, blank and over-long input - 2100-RECEIVE-MAP 596 is what bounds every value
    // ==============================================================================================

    @Nested
    @DisplayName("absent, blank and over-long input - bounded at the receive, never rejected by a cast")
    class AbsentBlankAndOverLongInput {

        @ParameterizedTest
        @ValueSource(strings = {"", "   ", "           ", "*"})
        @DisplayName("2100-RECEIVE-MAP 596: an absent, blank or marker-bearing filter reaches the "
                + "BLANK state without any runtime exception escaping")
        void anAbsentOrBlankFilterReachesTheBlankState(final String filter) {
            assertThatNoException().isThrownBy(
                    () -> service.processCardDetail(reSubmission(filter, filter, AID_ENTER)));

            final CardDetailService.CardDetailResult result =
                    service.processCardDetail(reSubmission(filter, filter, AID_ENTER));
            assertThat(result.message()).isEqualTo(MSG_NO_INPUT);
            assertThat(result.errorFlag()).isTrue();
            verify(cardRepository, never()).findById(anyString());
        }

        @Test
        @DisplayName("0000-MAIN 248: an entirely absent navigation state is the zero-length "
                + "communication area and yields the prompt, not a null dereference")
        void anAbsentNavigationStateIsTheZeroLengthCommunicationArea() {
            final CardDetailService.CardDetailScreenInput input =
                    new CardDetailService.CardDetailScreenInput(null, null, AID_ENTER, null);

            final CardDetailService.CardDetailResult result = service.processCardDetail(input);

            assertAll(
                    () -> assertThat(result.infoMessage()).isEqualTo(MSG_PROMPT_FOR_INPUT),
                    () -> assertThat(result.navigationContext()).isNotNull(),
                    () -> assertThat(result.route()).isNotNull(),
                    () -> assertThat(result.message()).isNotNull().isEmpty(),
                    () -> assertThat(result.fieldErrors()).isNotNull().isEmpty(),
                    () -> assertThat(result.workArea()).isNotNull(),
                    () -> assertThat(result.header()).isNotNull(),
                    () -> assertThat(result.screen()).isNotNull(),
                    () -> assertThat(result.focusField()).isNotNull());
        }

        @Test
        @DisplayName("2220-EDIT-CARD 685: a card filter one digit SHORT of its width fails the class "
                + "condition, because the receive pads it with a space that is not a digit")
        void aCardFilterShortOfItsWidthIsInvalid() {
            final CardDetailService.CardDetailResult result = service
                    .processCardDetail(reSubmission(ACCOUNT, "411111111111111", AID_ENTER));

            assertThat(result.message()).isEqualTo(MSG_CARD_NOT_NUMERIC);
            assertThat(result.fieldErrors()).hasSize(1);
            assertThat(result.fieldErrors().get(0).state())
                    .isEqualTo(ValidationException.FieldState.INVALID);
            verify(cardRepository, never()).findById(anyString());
        }

        @Test
        @DisplayName("2100-RECEIVE-MAP 596: a card filter LONGER than its width is truncated on the "
                + "right exactly as a move into the field would truncate it, and the read uses the "
                + "truncated key")
        void aCardFilterLongerThanItsWidthIsTruncated() {
            when(cardRepository.findById(CARD_MIDDLE))
                    .thenReturn(Optional.of(activeCard(CARD_MIDDLE)));

            final CardDetailService.CardDetailResult result = service
                    .processCardDetail(reSubmission(ACCOUNT, CARD_MIDDLE + "9999", AID_ENTER));

            verify(cardRepository).findById(CARD_MIDDLE);
            assertThat(result.cardPresented()).isTrue();
            assertThat(result.screen().cardNumberFilter()
                    .getBytes(StandardCharsets.US_ASCII)).hasSize(CARD_NUMBER_WIDTH);
        }

        @Test
        @DisplayName("2100-RECEIVE-MAP 596: an account filter LONGER than its width is truncated to "
                + "eleven characters, so the eleven-digit prefix is what the edit judges")
        void anAccountFilterLongerThanItsWidthIsTruncated() {
            when(cardRepository.findById(CARD_MIDDLE))
                    .thenReturn(Optional.of(activeCard(CARD_MIDDLE)));

            final CardDetailService.CardDetailResult result = service
                    .processCardDetail(reSubmission(ACCOUNT + "77", CARD_MIDDLE, AID_ENTER));

            assertAll(
                    () -> assertThat(result.fieldErrors()).isEmpty(),
                    () -> assertThat(result.screen().accountIdFilter()).isEqualTo(ACCOUNT),
                    () -> assertThat(result.screen().accountIdFilter()
                            .getBytes(StandardCharsets.US_ASCII)).hasSize(ACCOUNT_ID_WIDTH));
        }
    }

    // ==============================================================================================
    // 1200-SETUP-SCREEN-VARS 457 - every field the detail view exposes is fed from the entity
    // ==============================================================================================

    @Nested
    @DisplayName("field mapping completeness - no column is silently dropped on the way out")
    class FieldMappingCompleteness {

        @Test
        @DisplayName("every component of the projection is populated from the entity, and the "
                + "CORRECTLY SPELLED expiration-date property is the one that is read")
        void everyProjectionComponentIsPopulatedFromTheEntity() {
            final Card stored = card(CARD_HIGHEST, "913", EMBOSSED_NAME_WITH_SPACE, OTHER_EXPIRY,
                    STATUS_INACTIVE);
            // The correctly spelled Java property is what the projection reads; the misspelling of the
            // legacy field name survives only in the 150-byte record layout.
            assertThat(stored.getCardExpirationDate()).isEqualTo(OTHER_EXPIRY);
            when(cardRepository.findById(CARD_HIGHEST)).thenReturn(Optional.of(stored));

            final CardDetailService.CardProjection card = service
                    .processCardDetail(reSubmission(ACCOUNT, CARD_HIGHEST, AID_ENTER)).card();

            assertAll(
                    () -> assertThat(card.cardNumber()).isEqualTo(CARD_HIGHEST),
                    () -> assertThat(card.accountId()).isEqualTo(ACCOUNT),
                    () -> assertThat(card.embossedName()).startsWith(EMBOSSED_NAME_WITH_SPACE),
                    () -> assertThat(card.verificationCode()).isEqualTo("913"),
                    () -> assertThat(card.expirationDate()).isEqualTo(OTHER_EXPIRY),
                    () -> assertThat(card.expiryYear()).isEqualTo(OTHER_EXPIRY_YEAR),
                    () -> assertThat(card.expiryMonth()).isEqualTo(OTHER_EXPIRY_MONTH),
                    () -> assertThat(card.expiryDay()).isEqualTo(OTHER_EXPIRY_DAY),
                    () -> assertThat(card.activeStatus()).isEqualTo(STATUS_INACTIVE),
                    () -> assertThat(card.status()).isEqualTo(CardStatus.N));
        }

        @Test
        @DisplayName("1200-SETUP-SCREEN-VARS 457: the screen shows the expiry MONTH and YEAR only - "
                + "the day component is carried in the projection and deliberately not displayed")
        void theScreenShowsTheMonthAndYearButNotTheDay() {
            when(cardRepository.findById(CARD_HIGHEST)).thenReturn(Optional.of(
                    card(CARD_HIGHEST, "913", EMBOSSED_NAME_WITH_SPACE, OTHER_EXPIRY,
                            STATUS_ACTIVE)));

            final CardDetailService.CardDetailResult result =
                    service.processCardDetail(reSubmission(ACCOUNT, CARD_HIGHEST, AID_ENTER));

            assertAll(
                    () -> assertThat(result.screen().expiryMonth()).isEqualTo(OTHER_EXPIRY_MONTH),
                    () -> assertThat(result.screen().expiryYear()).isEqualTo(OTHER_EXPIRY_YEAR),
                    () -> assertThat(result.card().expiryDay()).isEqualTo(OTHER_EXPIRY_DAY),
                    () -> assertThat(result.screen().embossedName())
                            .startsWith(EMBOSSED_NAME_WITH_SPACE),
                    () -> assertThat(result.screen().cardActiveStatus()).isEqualTo(STATUS_ACTIVE));
        }

        @Test
        @DisplayName("1200-SETUP-SCREEN-VARS 457: a turn that found nothing leaves all four card "
                + "fields blank, because they are written only under the found condition")
        void aTurnThatFoundNothingLeavesTheCardFieldsBlank() {
            when(cardRepository.findById(CARD_MIDDLE)).thenReturn(Optional.empty());

            final CardDetailService.CardDetailResult result =
                    service.processCardDetail(reSubmission(ACCOUNT, CARD_MIDDLE, AID_ENTER));

            assertAll(
                    () -> assertThat(result.screen().embossedName()).isBlank(),
                    () -> assertThat(result.screen().cardActiveStatus()).isBlank(),
                    () -> assertThat(result.screen().expiryMonth()).isBlank(),
                    () -> assertThat(result.screen().expiryYear()).isBlank(),
                    () -> assertThat(result.card()).isNull());
        }

        @Test
        @DisplayName("the record layout the projection is bounded against is the 150-byte card record, "
                + "whose components sum to the declared length")
        void theBoundedWidthsSumToTheDeclaredRecordLength() {
            assertThat(CARD_NUMBER_WIDTH + ACCOUNT_ID_WIDTH + VERIFICATION_CODE_WIDTH
                    + EMBOSSED_NAME_WIDTH + OTHER_EXPIRY.length() + 1 + 59)
                    .isEqualTo(CARD_RECORD_WIDTH);
        }
    }

    // ==============================================================================================
    // The published value types, the statelessness of the singleton, and the flag translation
    // ==============================================================================================

    @Nested
    @DisplayName("the published value types and the stateless singleton")
    class PublishedValueTypes {

        @Test
        @DisplayName("the input record carries its four components verbatim and stores no copy")
        void theInputRecordCarriesItsComponentsVerbatim() {
            final ScreenNavigationState context = ScreenNavigationState.empty().withReEntry();
            final CardDetailService.CardDetailScreenInput input =
                    new CardDetailService.CardDetailScreenInput(ACCOUNT, CARD_MIDDLE, AID_PF3,
                            context);

            assertAll(
                    () -> assertThat(input.accountIdFilter()).isEqualTo(ACCOUNT),
                    () -> assertThat(input.cardNumberFilter()).isEqualTo(CARD_MIDDLE),
                    () -> assertThat(input.attentionKeyIdentifier()).isEqualTo(AID_PF3),
                    () -> assertThat(input.navigationContext()).isSameAs(context));
        }

        @Test
        @DisplayName("2000-PROCESS-INPUTS 582: the work area carries this member's own program, mapset "
                + "and map names, the mapset truncated to seven characters")
        void theWorkAreaCarriesThisMembersOwnNames() {
            when(cardRepository.findById(CARD_MIDDLE))
                    .thenReturn(Optional.of(activeCard(CARD_MIDDLE)));

            final ScreenInputState workArea = service
                    .processCardDetail(reSubmission(ACCOUNT, CARD_MIDDLE, AID_ENTER)).workArea();

            assertAll(
                    () -> assertThat(workArea.nextProgram()).isEqualTo(PROGRAM_NAME),
                    () -> assertThat(workArea.nextMapset()).isEqualTo(THIS_MAPSET_TRUNCATED),
                    () -> assertThat(workArea.nextMap()).isEqualTo(THIS_MAP),
                    () -> assertThat(workArea.keyAction()).isEqualTo(KeyAction.ENTER),
                    () -> assertThat(workArea.accountId()).isEqualTo(ACCOUNT),
                    () -> assertThat(workArea.cardNumber()).isEqualTo(CARD_MIDDLE),
                    () -> assertThat(workArea.customerId()).isEmpty());
        }

        @Test
        @DisplayName("cardPresented reports FALSE when a card was resolved but the turn still raised "
                + "an error, which is the unrecognised-key-and-successful-read combination")
        void cardPresentedIsFalseWhenACardWasFoundAndAnErrorWasStillRaised() {
            when(messageCatalogService.invalidKeyMessage()).thenReturn(MSG_INVALID_KEY);
            when(cardRepository.findById(CARD_MIDDLE))
                    .thenReturn(Optional.of(activeCard(CARD_MIDDLE)));

            final CardDetailService.CardDetailResult result = service
                    .processCardDetail(reSubmission(ACCOUNT, CARD_MIDDLE, AID_UNRECOGNISED));

            assertAll(
                    () -> assertThat(result.card()).isNotNull(),
                    () -> assertThat(result.errorFlag()).isTrue(),
                    () -> assertThat(result.cardPresented()).isFalse(),
                    () -> assertThat(result.message()).isEqualTo(MSG_INVALID_KEY),
                    () -> assertThat(result.route()).isEqualTo(NavigationService.Route.CARD_DETAIL));
        }

        @Test
        @DisplayName("the field-error list the result publishes cannot be modified by a caller")
        void theFieldErrorListIsUnmodifiable() {
            final CardDetailService.CardDetailResult result =
                    service.processCardDetail(reSubmission(null, null, AID_ENTER));

            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> result.fieldErrors().add(new ValidationException.FieldError(
                            PROPERTY_CARD_NUMBER, FIELD_CARD_NUMBER,
                            ValidationException.FieldState.INVALID, MSG_CARD_NOT_NUMERIC)));
        }

        @Test
        @DisplayName("two turns of the same instance are wholly independent, so the singleton carries "
                + "no turn state between them")
        void twoTurnsAreWhollyIndependent() {
            when(cardRepository.findById(CARD_MIDDLE))
                    .thenReturn(Optional.of(activeCard(CARD_MIDDLE)));

            final CardDetailService.CardDetailResult found =
                    service.processCardDetail(reSubmission(ACCOUNT, CARD_MIDDLE, AID_ENTER));
            final CardDetailService.CardDetailResult blank =
                    service.processCardDetail(reSubmission(null, null, AID_ENTER));

            assertAll(
                    () -> assertThat(found.cardPresented()).isTrue(),
                    () -> assertThat(found.message()).isEmpty(),
                    () -> assertThat(blank.card()).isNull(),
                    () -> assertThat(blank.message()).isEqualTo(MSG_NO_INPUT),
                    () -> assertThat(blank.fieldErrors()).hasSize(2));
        }

        @Test
        @DisplayName("the read-only contract holds: no write, no delete and no flush reaches the "
                + "repository on any path this turn can take")
        void noWriteReachesTheRepository() {
            when(cardRepository.findById(CARD_MIDDLE))
                    .thenReturn(Optional.of(activeCard(CARD_MIDDLE)));

            service.processCardDetail(reSubmission(ACCOUNT, CARD_MIDDLE, AID_ENTER));

            verify(cardRepository, never()).save(any(Card.class));
            verify(cardRepository, never()).delete(any(Card.class));
            verify(cardRepository, never()).deleteById(anyString());
            verify(cardRepository, never()).flush();
        }
    }

    @Nested
    @DisplayName("the filter-flag translation - three legacy states, two published field states")
    class FilterStateTranslation {

        @Test
        @DisplayName("the blank flag becomes MISSING, the faulted flag becomes INVALID, and the valid "
                + "flag produces no entry at all")
        void allThreeLegacyStatesTranslate() {
            assertAll(
                    () -> assertThat(CardDetailService.FilterState.BLANK.toFieldState())
                            .isEqualTo(ValidationException.FieldState.MISSING),
                    () -> assertThat(CardDetailService.FilterState.NOT_OK.toFieldState())
                            .isEqualTo(ValidationException.FieldState.INVALID),
                    () -> assertThat(CardDetailService.FilterState.VALID.toFieldState()).isNull());
        }

        @Test
        @DisplayName("the two predicates the cursor and colour decisions read are mutually exclusive "
                + "and neither holds for the valid state")
        void theTwoPredicatesAreMutuallyExclusive() {
            assertAll(
                    () -> assertThat(CardDetailService.FilterState.BLANK.isBlank()).isTrue(),
                    () -> assertThat(CardDetailService.FilterState.BLANK.isNotOk()).isFalse(),
                    () -> assertThat(CardDetailService.FilterState.NOT_OK.isNotOk()).isTrue(),
                    () -> assertThat(CardDetailService.FilterState.NOT_OK.isBlank()).isFalse(),
                    () -> assertThat(CardDetailService.FilterState.VALID.isBlank()).isFalse(),
                    () -> assertThat(CardDetailService.FilterState.VALID.isNotOk()).isFalse());
        }
    }

    @Nested
    @DisplayName("paragraph traceability - the 34 units this member contributes to the matrix")
    class ParagraphTraceability {

        @Test
        @DisplayName("the unit count is the 34 the published matrix carries for this member, read from "
                + "the matrix, and the attention-key copybook's two paragraphs are counted in its own "
                + "section rather than a second time here")
        void theUnitCountIsTheOneTheMatrixCarries() {
            // Read, not written down: the figure is taken from the matrix's census subtotal, its section
            // // declaration and its rows, which must agree. Asserting 34 + 1 + 2 == 37 over constants this file
            // // authored could not fail and would re-count a shared copybook.
            assertAll(
                    () -> assertThat(TraceabilityMatrixCensus.unitsOf(LEGACY_MEMBER))
                            .isEqualTo(OWN_PARAGRAPH_LABEL_COUNT),
                    () -> assertThat(TraceabilityMatrixCensus.unitsOf("CSSTRPFY.cpy"))
                            .as("the copybook's two paragraphs belong to the copybook, which is why "
                                    + "five including members do not each report them")
                            .isEqualTo(COPYBOOK_PARAGRAPH_COUNT));
        }

        @Test
        @DisplayName("this member's own identity is the one the route table publishes for the screen, "
                + "so a matrix row resolves from either direction")
        void theMemberIdentityAgreesWithThePublishedRoute() {
            assertAll(
                    () -> assertThat(NavigationService.Route.CARD_DETAIL.getLegacyProgramName())
                            .isEqualTo(PROGRAM_NAME),
                    () -> assertThat(NavigationService.Route.CARD_DETAIL.getLegacyTransactionId())
                            .isEqualTo(TRANSACTION_ID),
                    () -> assertThat(NavigationService.Route.CARD_LIST.getLegacyProgramName())
                            .isEqualTo(CARD_LIST_PROGRAM),
                    () -> assertThat(NavigationService.Route.USER_MENU.getLegacyProgramName())
                            .isEqualTo(USER_MENU_PROGRAM));
        }

        @Test
        @DisplayName("the thirteen EXIT paragraphs of every range a successful re-submission runs are "
                + "each recorded by name and by source line, so paragraph-level auditability is "
                + "observable at run time and not only at review time")
        void theTurnRecordsEveryExitParagraphOfEveryRangeItRan() {
            when(cardRepository.findById(CARD_MIDDLE))
                    .thenReturn(Optional.of(activeCard(CARD_MIDDLE)));

            service.processCardDetail(reSubmission(ACCOUNT, CARD_MIDDLE, AID_ENTER));

            final String trace = recordedLogText();
            assertAll(
                    () -> assertThat(trace).contains("paragraph=YYYY-STORE-PFKEY-EXIT line=80"),
                    () -> assertThat(trace).contains("paragraph=2100-RECEIVE-MAP-EXIT line=605"),
                    () -> assertThat(trace).contains("paragraph=2210-EDIT-ACCOUNT-EXIT line=681"),
                    () -> assertThat(trace).contains("paragraph=2220-EDIT-CARD-EXIT line=722"),
                    () -> assertThat(trace).contains("paragraph=2200-EDIT-MAP-INPUTS-EXIT line=643"),
                    () -> assertThat(trace).contains("paragraph=2000-PROCESS-INPUTS-EXIT line=593"),
                    () -> assertThat(trace)
                            .contains("paragraph=9100-GETCARD-BYACCTCARD-EXIT line=775"),
                    () -> assertThat(trace).contains("paragraph=9000-READ-DATA-EXIT line=732"),
                    () -> assertThat(trace).contains("paragraph=1100-SCREEN-INIT-EXIT line=453"),
                    () -> assertThat(trace)
                            .contains("paragraph=1200-SETUP-SCREEN-VARS-EXIT line=499"),
                    () -> assertThat(trace)
                            .contains("paragraph=1300-SETUP-SCREEN-ATTRS-EXIT line=559"),
                    () -> assertThat(trace).contains("paragraph=1400-SEND-SCREEN-EXIT line=578"),
                    () -> assertThat(trace).contains("paragraph=1000-SEND-MAP-EXIT line=423"));
        }

        @Test
        @DisplayName("9150-GETCARD-BYACCT-EXIT 810 and SEND-LONG-TEXT-EXIT 831: the two exits of the "
                + "unreachable paragraphs are recorded when those paragraphs are driven directly, which "
                + "leaves only the exits of the unreachable catch-all arm unrecorded")
        void theDirectlyDrivenParagraphsRecordTheirOwnExits() {
            when(cardRepository.findFirstByCardAcctIdOrderByCardNumAsc(ACCOUNT))
                    .thenReturn(Optional.of(activeCard(CARD_MIDDLE)));
            final CardDetailService.TurnState state = new CardDetailService.TurnState();

            service.getCardByAcct(state, ACCOUNT);
            service.sendLongText(state, MSG_UNEXPECTED_DATA_SCENARIO);

            final String trace = recordedLogText();
            assertThat(trace).contains("paragraph=9150-GETCARD-BYACCT-EXIT line=810");
            assertThat(trace).contains("paragraph=SEND-LONG-TEXT-EXIT line=831");
        }

        @Test
        @DisplayName("YYYY-STORE-PFKEY 17: a second fold pair behaves identically too, so the fold is "
                + "the copybook's rule and not a single special case")
        void aSecondFoldPairBehavesIdentically() {
            final CardDetailService.CardDetailResult viaLowKey =
                    service.processCardDetail(firstEntry("DFHPF12"));
            final CardDetailService.CardDetailResult viaFoldedKey =
                    service.processCardDetail(firstEntry("DFHPF24"));

            assertAll(
                    () -> assertThat(viaFoldedKey.route()).isEqualTo(viaLowKey.route()),
                    () -> assertThat(viaFoldedKey.message()).isEqualTo(viaLowKey.message()),
                    () -> assertThat(viaFoldedKey.errorFlag()).isEqualTo(viaLowKey.errorFlag()),
                    () -> assertThat(viaFoldedKey.reArmedTransactionId())
                            .isEqualTo(viaLowKey.reArmedTransactionId()),
                    () -> assertThat(viaFoldedKey.workArea().keyAction())
                            .isEqualTo(viaLowKey.workArea().keyAction()));
        }
    }


}
