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
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
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
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Pageable;

import com.carddemo.domain.Card;
import com.carddemo.domain.enums.CardStatus;
import com.carddemo.domain.enums.KeyAction;
import com.carddemo.exception.ValidationException;
import com.carddemo.repository.CardRepository;
import com.carddemo.support.TestDataFactory;
import com.carddemo.support.TraceabilityMatrixCensus;
import com.carddemo.util.CobolStringUtils;
import com.carddemo.util.PfKeyTranslator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

/**
 * Surefire unit tests for {@link CardListService} &mdash; legacy transaction {@code CCLI}, translated
 * from {@code app/cbl/COCRDLIC.cbl} (1,459 lines). Provenance is checkout SHA
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec} and upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19.
 *
 * <p>Every collaborator is a Mockito double, so this class starts no container, opens no JDBC
 * connection, binds no port, reaches no network and touches no file. The repository double is the
 * card base cluster; the message catalogue, navigation and abend doubles let the expected values
 * below be this test's own literals rather than a production class's output.
 *
 * <h2>The nine behaviours that carry the contract</h2>
 *
 * <ol>
 *   <li><strong>The page is exactly seven rows.</strong> Proven twice in the source and independent
 *       of each other: the comment at line 250 states the geometry as 28 characters by 7 rows, and
 *       line 255 occurs the row table seven times. It is a legacy contract value, not a tuning
 *       figure &mdash; nothing here is a fetch size, a chunk size or a throughput target. The other
 *       two list screens in the estate carry ten rows and no constant is shared with them, so this
 *       class declares its own.</li>
 *   <li><strong>The browse walks the base cluster by card number and filters by account
 *       afterwards.</strong> The account alternate index is declared at lines 215 to 217 of the
 *       source and referenced by nothing in it. The finder that would use it is proven never to be
 *       called, and the fixture in {@link BrowseOrderAndPostRetrievalFilter} is built so that an
 *       account-scoped query could not produce the outcome asserted.</li>
 *   <li><strong>One action per page, and both offenders are named.</strong> The tally at lines 1079
 *       to 1082 counts both accepted selection characters, line 1084 refuses a count above one, and
 *       the positional bitmap at lines 1088 to 1093 is written <em>only</em> inside that refusal.</li>
 *   <li><strong>The bitmap is positional and is never compacted.</strong> It writes a cleared flag at
 *       every slot carrying no selection, so an empty slot keeps its own position and a cleared entry
 *       is a reported fact rather than an absence.</li>
 *   <li><strong>Backward paging presents the reverse of the read order.</strong> The backward
 *       paragraph reads descending and fills slots downward from the seventh, so the assembled page
 *       is ascending. Order is asserted, never membership.</li>
 *   <li><strong>The high attention keys fold onto the low ones.</strong> Twenty-eight raw identifiers
 *       collapse to sixteen actions, so the nineteenth program-function key is indistinguishable from
 *       the seventh &mdash; which is this screen's page-backward key.</li>
 *   <li><strong>Message text is an external contract.</strong> This member's message family is
 *       entirely upper case, unlike the mixed-case common catalogue, and the one-action refusal
 *       carries <em>no</em> trailing period. Every literal is asserted byte for byte and nothing is
 *       trimmed.</li>
 *   <li><strong>Nothing here abends.</strong> A census of the member finds no abend statement and no
 *       call to the language-environment abort routine on any path, so an unclassified browse
 *       condition is logged, delegated to the diagnostic entry point, and reported on the screen.
 *       {@link AbendDiagnostic} proves the log precedes the delegation and that no abend follows.</li>
 *   <li><strong>The card verification value never leaves the record.</strong> It is not one of the
 *       three fields the browse stores, it stays a three-character string, and it reaches no log.</li>
 * </ol>
 *
 * <h2>Traceability: the 39 units this member contributes, and the three things that are not units</h2>
 *
 * <p><strong>39</strong> named labels stand in this member's own procedure division, and 39 is what the
 * action plan records and what the traceability matrix carries rows for. The nests below cover them as
 * follows.
 *
 * <p>The list runs to 42 lines because three further things are exercised from this member and are
 * <em>not</em> units of it: the copybook expansion site at line 1416 is a directive rather than a
 * paragraph, and the two paragraphs that expansion delivers are units of {@code app/cpy/CSSTRPFY.cpy},
 * which the matrix gives a section and two rows of its own. That copybook is included by five members, so
 * counting its two paragraphs against each of them would report ten units for two and the frozen 544 would
 * no longer hold. This suite previously described all 42 lines as units it contributed; the three marked
 * below carry their behaviour here and their rows elsewhere.
 *
 * <pre>
 *  1  0000-MAIN                     every nest (the entry point)
 *  2  COMMON-RETURN                 PageComposition, AttentionKeys (the re-armed transaction)
 *  3  0000-MAIN-EXIT                PageComposition (reached on every completed turn)
 *  4  1000-SEND-MAP                 PageComposition (the header and rows it assembles)
 *  5  1000-SEND-MAP-EXIT            PageComposition
 *  6  1100-SCREEN-INIT              PageComposition (the stamped header)
 *  7  1100-SCREEN-INIT-EXIT         PageComposition
 *  8  1200-SCREEN-ARRAY-INIT        PageComposition (populated slots only, never padded)
 *  9  1200-SCREEN-ARRAY-INIT-EXIT   PageComposition
 * 10  1250-SETUP-ARRAY-ATTRIBS      SelectionTallyAndBitmap (the two-state field findings)
 * 11  1250-SETUP-ARRAY-ATTRIBS-EXIT SelectionTallyAndBitmap
 * 12  1300-SETUP-SCREEN-ATTRS       KeyFilterEdits (the focused field)
 * 13  1300-SETUP-SCREEN-ATTRS-EXIT  KeyFilterEdits
 * 14  1400-SETUP-MESSAGE            MessageContract, PageMetadataFidelity (the arm order)
 * 15  1400-SETUP-MESSAGE-EXIT       MessageContract
 * 16  1500-SEND-SCREEN              AbendDiagnostic (the assembled-screen record)
 * 17  1500-SEND-SCREEN-EXIT         AbendDiagnostic
 * 18  2000-RECEIVE-MAP              SelectionTallyAndBitmap (gated on this screen re-submitting)
 * 19  2000-RECEIVE-MAP-EXIT         SelectionTallyAndBitmap
 * 20  2100-RECEIVE-SCREEN           NullAndBoundaryInput (staged exactly as transmitted)
 * 21  2100-RECEIVE-SCREEN-EXIT      NullAndBoundaryInput
 * 22  2200-EDIT-INPUTS              KeyFilterEdits (the three edits in source order)
 * 23  2200-EDIT-INPUTS-EXIT         KeyFilterEdits
 * 24  2210-EDIT-ACCOUNT             KeyFilterEdits
 * 25  2210-EDIT-ACCOUNT-EXIT        KeyFilterEdits
 * 26  2220-EDIT-CARD                KeyFilterEdits
 * 27  2220-EDIT-CARD-EXIT           KeyFilterEdits
 * 28  2250-EDIT-ARRAY               SelectionTallyAndBitmap
 * 29  2250-EDIT-ARRAY-EXIT          SelectionTallyAndBitmap (the early exit on a filter error)
 * 30  9000-READ-FORWARD             PageComposition, BrowseOrderAndPostRetrievalFilter
 * 31  9000-READ-FORWARD-EXIT        PageComposition
 * 32  9100-READ-BACKWARDS           PagingDirection
 * 33  9100-READ-BACKWARDS-EXIT      PagingDirection
 * 34  9500-FILTER-RECORDS           BrowseOrderAndPostRetrievalFilter
 * 35  9500-FILTER-RECORDS-EXIT      BrowseOrderAndPostRetrievalFilter
 * 36  SEND-PLAIN-TEXT               DiagnosticSenders
 * 37  SEND-PLAIN-TEXT-EXIT          DiagnosticSenders
 * 38  SEND-LONG-TEXT                DiagnosticSenders
 * 39  SEND-LONG-TEXT-EXIT           DiagnosticSenders
 * --  COPY 'CSSTRPFY' (site 1416)  AttentionKeys        - a directive, not a paragraph
 * --  YYYY-STORE-PFKEY              AttentionKeys        - a unit of CSSTRPFY.cpy, row held there
 * --  YYYY-STORE-PFKEY-EXIT         AttentionKeys        - a unit of CSSTRPFY.cpy, row held there
 * </pre>
 *
 * <h2>Independent oracles</h2>
 *
 * <p>No expected value below is produced by a production class. Every message literal, every padded
 * catalogue value, every ordered card-number list and every composed fixed-width image is declared
 * here from this test's own constants, with padding written out as a repeated space. The catalogue,
 * navigation and abend doubles are stubbed with those same literals, so a production regression
 * cannot move the target it is being measured against.
 *
 * @since 1.0.0
 */
@DisplayName("CardListService :: transaction CCLI, the seven-row card list browse")
@ExtendWith(MockitoExtension.class)
final class CardListServiceTest {

    // ==============================================================================================
    // Screen geometry. Declared here and nowhere shared: the estate's three list screens are three
    // independent shapes that happen to disagree, and a shared constant would tie them together.
    // ==============================================================================================

    /**
     * The seven rows this screen carries, proven twice in {@code app/cbl/COCRDLIC.cbl}: the comment at
     * line 250 states 28 characters by 7 rows, and line 255 occurs the row table seven times. A legacy
     * behavioural contract, not a tuning value.
     */
    private static final int PAGE_SIZE = 7;

    /** {@code WS-ROW-ACCTNO}, line 258: the account identifier on a row is eleven characters. */
    private static final int ACCOUNT_IDENTIFIER_WIDTH = 11;

    /** {@code WS-ROW-CARD-NUM}, line 259: the card number on a row is sixteen characters. */
    private static final int CARD_NUMBER_WIDTH = 16;

    /**
     * Every decimal representation a monetary field could be declared as.
     *
     * <p>{@code BigDecimal} is the module's one permitted representation and the two floating-point
     * primitives are the substitution the mandate forbids, so all three are named: a screen that declared
     * any of them would have a site for a scale or a rounding policy, and this one has none.
     */
    private static final List<Class<?>> DECIMAL_TYPES =
            List.of(BigDecimal.class, double.class, float.class);

    /** {@code WS-ERROR-MSG}, line 117: the summary message field is 75 characters. */
    private static final int ERROR_MESSAGE_WIDTH = 75;

    /** {@code WS-LONG-MSG}, line 111: the long diagnostic field is 500 characters. */
    private static final int LONG_MESSAGE_WIDTH = 500;

    /** {@code CCDA-MSG-*} of {@code app/cpy/CSMSG01Y.cpy}: each common message is 50 characters. */
    private static final int COMMON_MESSAGE_WIDTH = 50;

    /** {@code CCDA-TITLE0n} of {@code app/cpy/COTTL01Y.cpy}: each screen title is 40 characters. */
    private static final int SCREEN_TITLE_WIDTH = 40;

    // ==============================================================================================
    // Identity literals, from the source's own declarations.
    // ==============================================================================================

    /** {@code LIT-THISTRANID}: this screen's transaction, re-armed on every completed turn. */
    private static final String THIS_TRANSACTION = "CCLI";

    /** {@code LIT-THISPGM}: this screen's own program name, which the state-adoption gate tests. */
    private static final String THIS_PROGRAM = "COCRDLIC";

    /** {@code LIT-THISMAP}: the map this screen records as last displayed. */
    private static final String THIS_MAP = "CCRDLIA";

    /** {@code LIT-THISMAPSET}: the mapset this screen records as last displayed. */
    private static final String THIS_MAPSET = "COCRDLI";

    /** {@code LIT-MENUPGM}, line 188: where the exit key hands control. */
    private static final String MENU_PROGRAM = "COMEN01C";

    /** {@code LIT-CARDDTLPGM}, line 196: where a view selection hands control. */
    private static final String CARD_DETAIL_PROGRAM = "COCRDSLC";

    /** {@code LIT-CARDUPDPGM}, line 204: where an update selection hands control. */
    private static final String CARD_UPDATE_PROGRAM = "COCRDUPC";

    /** {@code LIT-CARD-FILE}, lines 213 to 214: the base cluster, the only resource ever browsed. */
    private static final String CARD_BASE_CLUSTER = "CARDDAT";

    /**
     * {@code LIT-CARD-FILE-ACCT-PATH}, lines 215 to 217: the account alternate index, declared by the
     * source and referenced by nothing in it. It reaches the failure diagnostic and no query.
     */
    private static final String CARD_ACCOUNT_PATH = "CARDAIX";

    // ==============================================================================================
    // Message oracles. Reproduced from the source's own condition-name values, character for
    // character. This family is entirely upper case, and the one-action refusal has NO trailing
    // period -- neither is a transcription slip and neither may be tidied.
    // ==============================================================================================

    /** {@code WS-INFORM-REC-ACTIONS}, lines 115 to 116. */
    private static final String ORACLE_INFORM_REC_ACTIONS = "TYPE S FOR DETAIL, U TO UPDATE ANY RECORD";

    /** {@code WS-EXIT-MESSAGE}, lines 119 to 120. Note the full stop mid-sentence: it is the source's. */
    private static final String ORACLE_EXIT_MESSAGE = "PF03 PRESSED.EXITING";

    /** {@code WS-NO-RECORDS-FOUND}, lines 121 to 122. This one <em>does</em> carry a trailing period. */
    private static final String ORACLE_NO_RECORDS_FOUND = "NO RECORDS FOUND FOR THIS SEARCH CONDITION.";

    /**
     * {@code WS-MORE-THAN-1-ACTION}, lines 123 to 124.
     *
     * <p>Upper case throughout and carrying <strong>no trailing period</strong>. Both facts are part of
     * the external contract: operators and downstream tooling match on this text, so a tidied full stop
     * would be a breaking change.
     */
    private static final String ORACLE_MORE_THAN_ONE_ACTION =
            "PLEASE SELECT ONLY ONE RECORD TO VIEW OR UPDATE";

    /** {@code WS-INVALID-ACTION-CODE}, lines 125 to 126. */
    private static final String ORACLE_INVALID_ACTION_CODE = "INVALID ACTION CODE";

    /** The account-filter format message, lines 1021 to 1023. The missing space after the comma is the source's. */
    private static final String ORACLE_ACCOUNT_FILTER_FORMAT =
            "ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER";

    /** The card-filter format message, lines 1057 to 1059, with the same comma spacing. */
    private static final String ORACLE_CARD_FILTER_FORMAT =
            "CARD ID FILTER,IF SUPPLIED MUST BE A 16 DIGIT NUMBER";

    /** Line 903: the backward key while already standing on the first page. */
    private static final String ORACLE_NO_PREVIOUS_PAGES = "NO PREVIOUS PAGES TO DISPLAY";

    /** Line 908: the forward key after the last page has already been shown. */
    private static final String ORACLE_NO_MORE_PAGES = "NO MORE PAGES TO DISPLAY";

    /** Lines 1219 and 1239: the browse reached the end of the cluster. */
    private static final String ORACLE_NO_MORE_RECORDS = "NO MORE RECORDS TO SHOW";

    // ==============================================================================================
    // Common catalogue oracles. Padded here with an explicit repeated space so that the fifty-byte
    // width is visible in the source of the test rather than computed by the class under test.
    // ==============================================================================================

    /** The visible part of {@code CCDA-MSG-INVALID-KEY}: forty characters, mixed case. */
    private static final String ORACLE_INVALID_KEY_TEXT = "Invalid key pressed. Please see below...";

    /**
     * {@code CCDA-MSG-INVALID-KEY} at its full {@code PIC X(50)} width: forty visible characters plus
     * ten trailing spaces. Never trimmed anywhere, because the trailing spaces are part of what the
     * fixed-width field transmits.
     */
    private static final String ORACLE_INVALID_KEY_MESSAGE = ORACLE_INVALID_KEY_TEXT + " ".repeat(10);

    /** The visible part of {@code CCDA-MSG-THANK-YOU}: forty-three characters, mixed case. */
    private static final String ORACLE_THANK_YOU_TEXT = "Thank you for using CardDemo application...";

    /**
     * {@code CCDA-MSG-THANK-YOU} at its full {@code PIC X(50)} width: forty-three visible characters
     * plus seven trailing spaces. Declared so that the assertion that this screen does <em>not</em> use
     * it can be made against the real value rather than against an approximation.
     */
    private static final String ORACLE_THANK_YOU_MESSAGE = ORACLE_THANK_YOU_TEXT + " ".repeat(7);

    /** {@code CCDA-TITLE01} at its full {@code PIC X(40)} width. */
    private static final String ORACLE_TITLE01 =
            " ".repeat(6) + "AWS Mainframe Modernization" + " ".repeat(7);

    /** {@code CCDA-TITLE02} at its full {@code PIC X(40)} width. */
    private static final String ORACLE_TITLE02 = " ".repeat(14) + "CardDemo" + " ".repeat(18);

    // ==============================================================================================
    // The composed file-error message, assembled here from this test's own fragments and widths so
    // that it is an independent oracle rather than an echo of the class under test. The eight declared
    // parts of the source's message field at lines 153 to 170 total exactly 75 characters, which is why
    // the move into the 75-character message field loses nothing.
    // ==============================================================================================

    /** {@code FILLER PIC X(12) VALUE 'File Error:'}: eleven characters in a field of twelve. */
    private static final String ORACLE_FILE_ERROR_PREFIX = "File Error: ";

    /** The {@code READ} operation name: the only operation this member ever names. */
    private static final String ORACLE_OPERATION_READ = "READ";

    /** The two-character status the unclassified arm maps onto: a permanent error. */
    private static final String ORACLE_STATUS_PERMANENT_ERROR = "31";

    /**
     * The whole 75-character message an unclassified browse condition composes, built from the eight
     * declared fragments at their declared widths.
     */
    private static final String ORACLE_FILE_ERROR_MESSAGE = ORACLE_FILE_ERROR_PREFIX
            + ORACLE_OPERATION_READ + " ".repeat(8 - ORACLE_OPERATION_READ.length())
            + " on "
            + CARD_BASE_CLUSTER + " ".repeat(9 - CARD_BASE_CLUSTER.length())
            + " returned RESP "
            + ORACLE_STATUS_PERMANENT_ERROR + " ".repeat(10 - ORACLE_STATUS_PERMANENT_ERROR.length())
            + ",RESP2 "
            + " ".repeat(10);

    // ==============================================================================================
    // Field-level contract oracles.
    // ==============================================================================================

    /** The 3270 field name of the account filter. */
    private static final String BMS_ACCOUNT_FILTER = "ACCTSID";

    /** The 3270 field name of the card filter. */
    private static final String BMS_CARD_FILTER = "CARDSID";

    /** Stem of the seven 3270 selection field names, {@code CRDSEL1} through {@code CRDSEL7}. */
    private static final String BMS_SELECTION_STEM = "CRDSEL";

    /** Stem of the seven reported selection property names. */
    private static final String PROPERTY_SELECTION_STEM = "selection";

    /** The stand-in every redacting rendering emits in place of a withheld component. */
    private static final String REDACTION_PLACEHOLDER = "***REDACTED***";

    /** The view selection character, {@code VIEW-REQUESTED-ON} at line 78. */
    private static final String SELECT_VIEW = "S";

    /** The update selection character, {@code UPDATE-REQUESTED-ON} at line 79. */
    private static final String SELECT_UPDATE = "U";

    /** An untransmitted selection field, which the terminal sends as low values and reads as blank. */
    private static final String SELECT_NONE = "";

    // ==============================================================================================
    // Record fixtures.
    // ==============================================================================================

    /** The account the filtered fixture asks for. Eleven digits, and deliberately not all zeros. */
    private static final String ACCOUNT_A = "00000000011";

    /** A second account, interleaved with the first by card number so the filter has work to do. */
    private static final String ACCOUNT_B = "00000000022";

    /** The fourteen-character stem every fixture card number carries, taking a two-digit ordinal. */
    private static final String CARD_NUMBER_STEM = "41111111111111";

    /**
     * The three-character card verification value.
     *
     * <p>{@code CARD-CVV-CD} is {@code PIC 9(03)} in {@code app/cpy/CVACT02Y.cpy}, and a leading zero
     * is significant, so the value stays the string it is. Read as a number it would become 7 and the
     * record image would no longer be reproducible.
     */
    private static final String CARD_VERIFICATION_CODE = "007";

    /**
     * An embossed name with an embedded space.
     *
     * <p>The legacy alphabetic idiom blanks every letter and then measures what is left, so a name with
     * a space inside it passes. A letters-only predicate would reject it and would therefore reject
     * data the estate already holds.
     */
    private static final String EMBOSSED_NAME_WITH_SPACE = "MARY ANN";

    /** {@code CARD-EXPIRAION-DATE}: the field name is misspelled in the copybook and is left as it is. */
    private static final String CARD_EXPIRY = "2029-12-31";

    /** {@code CARD-ACTIVE-STATUS} for an active card. */
    private static final String STATUS_ACTIVE = "Y";

    // ==============================================================================================
    // The clock the header is stamped from.
    // ==============================================================================================

    /** A fixed instant, so the two assembled header fields are exact values rather than moving ones. */
    private static final Instant FIXED_INSTANT = Instant.parse("2024-03-09T14:25:36Z");

    /** The header date that instant produces, as {@code MM/DD/YY}. */
    private static final String ORACLE_HEADER_DATE = "03/09/24";

    /** The header time that instant produces, as {@code HH:MM:SS}. */
    private static final String ORACLE_HEADER_TIME = "14:25:36";

    // ==============================================================================================
    // Doubles. The repository is the base cluster; the three services are doubles so that every
    // expected value in this file is this test's own literal.
    // ==============================================================================================

    @Mock
    private CardRepository cardRepository;

    @Mock
    private MessageCatalogService messageCatalogService;

    @Mock
    private NavigationService navigationService;

    @Mock
    private AbendService abendService;

    /** The class under test, rebuilt for every test so no state can survive a method boundary. */
    private CardListService service;

    /** The class-under-test logger the recorder below is attached to. */
    private Logger serviceLogger;

    /** Every event the class under test emitted during one test, in emission order. */
    private ListAppender<ILoggingEvent> logRecorder;

    /** The level the service logger stood at before the recorder lowered it, restored afterwards. */
    private Level originalLevel;

    /**
     * Every card the repository double handed to the browse, in the order it handed them over.
     *
     * <p>This is what makes post-retrieval filtering directly observable: a page of seven assembled out
     * of fourteen delivered rows can only have been filtered after the read.
     */
    private final List<Card> deliveredRows = new ArrayList<>();

    /**
     * Builds the service over the four doubles and a fixed clock, and attaches the log recorder.
     *
     * <p>The recorder is attached here rather than inside the tests that read it because the ordering
     * proof in {@link AbendDiagnostic} needs the recorder to be live <em>before</em> the class under
     * test emits anything, and a recorder attached mid-turn could not show that.
     *
     * <p>The three catalogue and navigation stubs are lenient on purpose: several paths complete without
     * reaching them. The exit key hands control away before the header is stamped, a selection transfers
     * before the screen is assembled, and the two diagnostic senders touch no collaborator at all. A
     * strict stub would fail those tests for not using wiring they are not meant to reach.
     */
    @BeforeEach
    void buildServiceAndAttachLogRecorder() {
        Mockito.lenient().when(this.messageCatalogService.screenTitle01()).thenReturn(ORACLE_TITLE01);
        Mockito.lenient().when(this.messageCatalogService.screenTitle02()).thenReturn(ORACLE_TITLE02);
        Mockito.lenient().when(this.messageCatalogService.invalidKeyMessage())
                .thenReturn(ORACLE_INVALID_KEY_MESSAGE);
        Mockito.lenient().when(this.navigationService.resolveNominatedDestination(any(), any()))
                .thenAnswer(CardListServiceTest::routeForNomination);

        this.service = new CardListService(this.cardRepository, this.messageCatalogService,
                this.navigationService, this.abendService, Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC));

        this.serviceLogger = (Logger) LoggerFactory.getLogger(CardListService.class);
        this.originalLevel = this.serviceLogger.getLevel();
        this.logRecorder = new ListAppender<>();
        this.logRecorder.setContext(this.serviceLogger.getLoggerContext());
        this.logRecorder.start();
        this.serviceLogger.addAppender(this.logRecorder);
        this.serviceLogger.setLevel(Level.TRACE);
    }

    /** Detaches the recorder and restores the level the service logger stood at. */
    @AfterEach
    void detachLogRecorder() {
        this.serviceLogger.detachAppender(this.logRecorder);
        this.logRecorder.stop();
        this.serviceLogger.setLevel(this.originalLevel);
        this.deliveredRows.clear();
    }

    // ==============================================================================================
    // Oracles for the navigation double
    // ==============================================================================================

    /**
     * This test's own destination table, standing in for the navigation rules.
     *
     * <p>Deliberately written here rather than delegated: the point of asserting a route is to prove
     * which program this screen <em>nominates</em>, and a real resolver would answer the question with
     * its own table instead of with the nomination under test.
     *
     * @param  invocation the stubbed resolution, carrying the nominated state and the caller's default
     * @return the destination the nominated program names, or the caller's own default
     */
    private static NavigationService.Route routeForNomination(final InvocationOnMock invocation) {
        final ConversationState nominated = invocation.getArgument(0);
        final NavigationService.Route callerDefault = invocation.getArgument(1);
        final String nominatedProgram = nominated.toProgram();
        if (nominatedProgram == null) {
            return callerDefault;
        }
        return switch (nominatedProgram) {
            case THIS_PROGRAM -> NavigationService.Route.CARD_LIST;
            case MENU_PROGRAM -> NavigationService.Route.USER_MENU;
            case CARD_DETAIL_PROGRAM -> NavigationService.Route.CARD_DETAIL;
            case CARD_UPDATE_PROGRAM -> NavigationService.Route.CARD_UPDATE;
            default -> callerDefault;
        };
    }

    // ==============================================================================================
    // Record fixtures
    // ==============================================================================================

    /**
     * The card number a one-based ordinal denotes: the fourteen-character stem plus a two-digit ordinal,
     * which is exactly sixteen characters.
     *
     * <p>The root locale is mandatory rather than decorative. A card number is a fixed-width sixteen-byte
     * field that has to stay inside US-ASCII, and an unqualified format emits the default locale's digits
     * &mdash; under a locale whose numbering system is not Latin those are not ASCII digits at all, and
     * the row would carry a number no fixed-width image could hold.
     *
     * @param  ordinal the one-based ordinal, between 1 and 99
     * @return the sixteen-character card number
     */
    private static String cardNumber(final int ordinal) {
        return CARD_NUMBER_STEM + String.format(Locale.ROOT, "%02d", ordinal);
    }

    /**
     * One card row, built through the shared factory so its widths match the 150-byte record layout.
     *
     * @param  ordinal   the one-based ordinal, which becomes the low-order digits of the card number
     * @param  accountId the owning account identifier
     * @return the card
     */
    private static Card cardRow(final int ordinal, final String accountId) {
        return TestDataFactory.card()
                .cardNumber(cardNumber(ordinal))
                .accountId(accountId)
                .verificationCode(CARD_VERIFICATION_CODE)
                .embossedName(EMBOSSED_NAME_WITH_SPACE)
                .expirationDate(CARD_EXPIRY)
                .activeStatus(STATUS_ACTIVE)
                .build();
    }

    /**
     * A cluster of consecutively numbered cards, all owned by one account.
     *
     * @param  rowCount how many rows the cluster holds
     * @return the rows in ascending card-number order
     */
    private static List<Card> clusterOf(final int rowCount) {
        final List<Card> rows = new ArrayList<>(rowCount);
        for (int ordinal = 1; ordinal <= rowCount; ordinal++) {
            rows.add(cardRow(ordinal, ACCOUNT_A));
        }
        return List.copyOf(rows);
    }

    /**
     * A cluster whose rows alternate between two accounts by card number: every odd ordinal belongs to
     * the first account and every even ordinal to the second.
     *
     * <p>Interleaving is what makes the filter observable. A query scoped to one account would return
     * that account's rows and nothing else; walking the base cluster returns both and discards one.
     *
     * @param  rowCount how many rows the cluster holds
     * @return the rows in ascending card-number order
     */
    private static List<Card> interleavedClusterOf(final int rowCount) {
        final List<Card> rows = new ArrayList<>(rowCount);
        for (int ordinal = 1; ordinal <= rowCount; ordinal++) {
            rows.add(cardRow(ordinal, ordinal % 2 == 1 ? ACCOUNT_A : ACCOUNT_B));
        }
        return List.copyOf(rows);
    }

    // ==============================================================================================
    // Repository stubbing. Each direction is stubbed separately, because strict stubbing makes a stub
    // a test never reaches a failure -- and a forward-only turn must not be made to declare a backward
    // read it never issues.
    // ==============================================================================================

    /**
     * Stubs the inclusive positioning read: the keyed lookup the browse performs once, before any range
     * read, to deliver the row it positioned on.
     *
     * @param rows the ordered cluster contents
     */
    private void stubPositioningRead(final List<Card> rows) {
        Mockito.when(this.cardRepository.findById(anyString())).thenAnswer(invocation -> {
            final String key = invocation.getArgument(0);
            for (final Card row : rows) {
                if (row.getCardNum().equals(key)) {
                    this.deliveredRows.add(row);
                    return Optional.of(row);
                }
            }
            return Optional.empty();
        });
    }

    /**
     * Stubs the ascending range read: one bounded chunk strictly beyond the cursor, in key order.
     *
     * <p>The bound and the strictness are both applied here exactly as the derived query name declares
     * them. A stub that read inclusively would hide a repeated boundary row and one that ignored the
     * bound would hide an unbounded read, so both are reproduced rather than approximated.
     *
     * @param rows the ordered cluster contents
     */
    private void stubAscendingChunks(final List<Card> rows) {
        Mockito.when(this.cardRepository.findByCardNumGreaterThanOrderByCardNumAsc(
                        anyString(), any(Limit.class)))
                .thenAnswer(invocation -> chunk(rows, invocation, true));
    }

    /**
     * Stubs the descending range read, on the same terms as the ascending one.
     *
     * @param rows the ordered cluster contents
     */
    private void stubDescendingChunks(final List<Card> rows) {
        Mockito.when(this.cardRepository.findByCardNumLessThanOrderByCardNumDesc(
                        anyString(), any(Limit.class)))
                .thenAnswer(invocation -> chunk(rows, invocation, false));
    }

    /**
     * Returns one strict, ordered, bounded chunk and records every row it hands over.
     *
     * @param  rows       the ordered cluster contents
     * @param  invocation the stubbed call, carrying the cursor and the bound
     * @param  ascending  whether the read runs in key order
     * @return the rows the read delivers, in read order
     */
    private List<Card> chunk(final List<Card> rows, final InvocationOnMock invocation,
            final boolean ascending) {
        final String cursor = invocation.getArgument(0);
        final Limit bound = invocation.getArgument(1);
        final List<Card> ordered = new ArrayList<>(rows);
        if (!ascending) {
            Collections.reverse(ordered);
        }
        final List<Card> delivered = new ArrayList<>(bound.max());
        for (final Card row : ordered) {
            if (delivered.size() >= bound.max()) {
                break;
            }
            final int comparison = row.getCardNum().compareTo(cursor);
            if (ascending ? comparison > 0 : comparison < 0) {
                delivered.add(row);
            }
        }
        this.deliveredRows.addAll(delivered);
        return List.copyOf(delivered);
    }

    /** Stubs an exhausted cluster: the positioning read finds nothing and the forward read is empty. */
    private void stubEmptyCluster() {
        Mockito.when(this.cardRepository.findById(anyString())).thenReturn(Optional.empty());
        Mockito.when(this.cardRepository.findByCardNumGreaterThanOrderByCardNumAsc(
                anyString(), any(Limit.class))).thenReturn(List.of());
    }

    // ==============================================================================================
    // Turn construction
    // ==============================================================================================

    /**
     * The shared screen work area carrying the two key filters.
     *
     * @param  accountFilter the account filter exactly as keyed, or {@code null}
     * @param  cardFilter    the card filter exactly as keyed, or {@code null}
     * @return the work area
     */
    private static ScreenInputState workArea(final String accountFilter, final String cardFilter) {
        return new ScreenInputState(KeyAction.ENTER, null, null, null, null, null, accountFilter,
                cardFilter, null);
    }

    /**
     * A carried state that reads as this screen submitting back to itself, which is the gate on
     * receiving and editing input at all.
     *
     * @return the re-submission state
     */
    private static ScreenNavigationState reSubmission() {
        return new ScreenNavigationState(THIS_TRANSACTION, THIS_PROGRAM, THIS_TRANSACTION, THIS_PROGRAM,
                "USER0001", "U", ScreenNavigationState.ProgramContext.REENTER, null, null, null, null,
                null, null, null, THIS_MAP, THIS_MAPSET);
    }

    /**
     * A first entry: no carried state at all, which the source answers by initialising itself.
     *
     * @param  rawKey the raw attention-key identifier
     * @return the turn
     */
    private static CardListService.CardListScreenInput firstEntry(final String rawKey) {
        return new CardListService.CardListScreenInput(rawKey, workArea(null, null), null, null, 1,
                false, false, null);
    }

    /**
     * A re-submission of this screen carrying selections and no key filters.
     *
     * @param  rawKey     the raw attention-key identifier
     * @param  selections the transmitted selection fields, padded to the page width by the record
     * @return the turn
     */
    private static CardListService.CardListScreenInput reSubmit(final String rawKey,
            final List<String> selections) {
        return new CardListService.CardListScreenInput(rawKey, workArea(null, null), selections, null, 1,
                false, false, reSubmission());
    }

    /**
     * A re-submission of this screen carrying the two key filters and no selections.
     *
     * @param  rawKey        the raw attention-key identifier
     * @param  accountFilter the account filter exactly as keyed, or {@code null}
     * @param  cardFilter    the card filter exactly as keyed, or {@code null}
     * @return the turn
     */
    private static CardListService.CardListScreenInput reSubmitFiltered(final String rawKey,
            final String accountFilter, final String cardFilter) {
        return new CardListService.CardListScreenInput(rawKey, workArea(accountFilter, cardFilter), null,
                null, 1, false, false, reSubmission());
    }

    /**
     * A re-submission standing on a later page, carrying the boundary key a backward walk resumes from.
     *
     * @param  rawKey       the raw attention-key identifier
     * @param  pageNumber   the page indicator the previous turn displayed
     * @param  resumeAtKey  the retained first key of the page being left
     * @return the turn
     */
    private static CardListService.CardListScreenInput reSubmitOnPage(final String rawKey,
            final int pageNumber, final String resumeAtKey) {
        return new CardListService.CardListScreenInput(rawKey, workArea(null, null), null,
                new BrowseWindow.CursorRequest(resumeAtKey, resumeAtKey,
                        BrowseWindow.PagingDirection.BACKWARD),
                pageNumber, false, false, reSubmission());
    }

    /**
     * The seven selection fields with an action on one row and the rest untransmitted.
     *
     * @param  slot   the one-based row the action sits on
     * @param  action the action character
     * @return the seven fields in slot order
     */
    private static List<String> selectionAt(final int slot, final String action) {
        return selectionsAt(action, slot);
    }

    /**
     * The seven selection fields with the same action on each of the named rows.
     *
     * @param  action the action character
     * @param  slots  the one-based rows the action sits on
     * @return the seven fields in slot order
     */
    private static List<String> selectionsAt(final String action, final int... slots) {
        final List<String> selections = new ArrayList<>(Collections.nCopies(PAGE_SIZE, SELECT_NONE));
        for (final int slot : slots) {
            selections.set(slot - 1, action);
        }
        return selections;
    }

    // ==============================================================================================
    // Log inspection
    // ==============================================================================================

    /**
     * Every recorded event's formatted message, in emission order.
     *
     * @return the recorded messages
     */
    private List<String> recordedMessages() {
        final List<String> messages = new ArrayList<>(this.logRecorder.list.size());
        for (final ILoggingEvent event : this.logRecorder.list) {
            messages.add(event.getFormattedMessage());
        }
        return messages;
    }

    /**
     * Every recorded card number the browse walked, taken from the rows the repository double delivered.
     *
     * @return the delivered card numbers in delivery order
     */
    private List<String> deliveredCardNumbers() {
        final List<String> numbers = new ArrayList<>(this.deliveredRows.size());
        for (final Card row : this.deliveredRows) {
            numbers.add(row.getCardNum());
        }
        return numbers;
    }

    /**
     * The card numbers a result presents, in screen-slot order.
     *
     * @param  result the turn's outcome
     * @return the presented card numbers
     */
    private static List<String> presentedCardNumbers(final CardListService.CardListResult result) {
        final List<String> numbers = new ArrayList<>(result.rows().size());
        for (final CardListService.CardListRow row : result.rows()) {
            numbers.add(row.cardNumber());
        }
        return numbers;
    }

    // ==============================================================================================
    // Negative verifications, named once because several turns must prove them
    // ==============================================================================================

    /**
     * Proves the account finder is never reached, in either of the two forms the repository declares.
     *
     * <p>This is the single most consequential negative in the file. The account alternate index is
     * declared in the source and referenced by nothing in it: every browse verb names the base cluster
     * with the card number as its record identifier, and the account filter is applied afterwards.
     * Routing this list through the account finder would silently reorder the screen from card number to
     * account identifier and change which rows land on a page, so the absence is asserted rather than
     * assumed. The finder is not dead code either &mdash; the account-view, account-update, card-detail
     * and card-update flows are its genuine consumers.
     */
    private void verifyTheAccountFinderIsNeverReached() {
        verify(cardRepository, never()).findByCardAcctIdOrderByCardNumAsc(any(), any());
        verify(cardRepository, never()).findFirstByCardAcctIdOrderByCardNumAsc(any());
    }

    /**
     * Proves the browse never falls back to an offset page.
     *
     * <p>An offset page would ask the store to count and discard every earlier row on each fetch, and it
     * is not stable under a concurrent write: a card inserted between two fetches shifts the window, so a
     * row is delivered twice or missed. The source cannot have that defect, because it retains the card
     * number of the row it stopped at and repositions on that value.
     */
    private void verifyNoOffsetPageIsRequested() {
        verify(cardRepository, never()).findAll(any(Pageable.class));
    }

    /**
     * Proves nothing at all was read: no positioning read, no range read in either direction, no offset
     * page and no account query.
     */
    private void verifyNoInteractionsWithTheCluster() {
        verify(cardRepository, never()).findById(anyString());
        verify(cardRepository, never())
                .findByCardNumGreaterThanOrderByCardNumAsc(anyString(), any(Limit.class));
        verify(cardRepository, never())
                .findByCardNumLessThanOrderByCardNumDesc(anyString(), any(Limit.class));
        verifyNoOffsetPageIsRequested();
        verifyTheAccountFinderIsNeverReached();
    }

    /**
     * Proves this screen never abends.
     *
     * <p>A census of {@code app/cbl/COCRDLIC.cbl} finds no abend statement and no call to the
     * language-environment abort routine on any path, and its abend-variable include is commented out.
     * Every unexpected browse condition is tolerated: the source leaves the read loop, composes a
     * fixed-format message and presents it. So the only permitted use of the abend collaborator is its
     * no-abend diagnostic entry point, and any other interaction would be a failure this estate does not
     * have.
     */
    private void verifyNoAbendWasRaised() {
        verify(abendService, never()).abendOnline(anyString(), anyString());
        verify(abendService, never()).abendOnline(anyString(), anyString(), anyString());
        verify(abendService, never()).abendBatch(anyString(), anyString());
        verify(abendService, never()).abendOnFileStatus(anyString(), anyString(), anyString(),
                anyString());
    }

    // ==============================================================================================

    @Nested
    @DisplayName("construction and the transmitted screen record")
    final class Construction {

        /** Creates the nest. */
        Construction() {
        }

        @Test
        @DisplayName("every one of the five collaborators is mandatory, so a missing one fails at wiring "
                + "time rather than on the first turn")
        void everyCollaboratorIsMandatory() {
            final Clock headerClock = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);

            assertThatExceptionOfType(NullPointerException.class).isThrownBy(() -> new CardListService(
                    null, messageCatalogService, navigationService, abendService, headerClock));
            assertThatExceptionOfType(NullPointerException.class).isThrownBy(() -> new CardListService(
                    cardRepository, null, navigationService, abendService, headerClock));
            assertThatExceptionOfType(NullPointerException.class).isThrownBy(() -> new CardListService(
                    cardRepository, messageCatalogService, null, abendService, headerClock));
            assertThatExceptionOfType(NullPointerException.class).isThrownBy(() -> new CardListService(
                    cardRepository, messageCatalogService, navigationService, null, headerClock));
            assertThatExceptionOfType(NullPointerException.class).isThrownBy(() -> new CardListService(
                    cardRepository, messageCatalogService, navigationService, abendService, null));
        }

        @Test
        @DisplayName("a turn with no transmitted screen at all is refused rather than read as an empty "
                + "screen, and nothing is read")
        void anAbsentTurnIsRefused() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> service.processCardList(null));

            verifyNoInteractionsWithTheCluster();
        }

        @Test
        @DisplayName("an absent selection list reads as seven untransmitted fields and a short one is "
                + "padded to the page width, because that is what the terminal leaves behind")
        void anAbsentOrShortSelectionListIsPaddedToSevenFields() {
            final CardListService.CardListScreenInput absent = reSubmit("DFHENTER", null);
            final CardListService.CardListScreenInput shortList =
                    reSubmit("DFHENTER", List.of(SELECT_NONE, SELECT_VIEW));

            assertThat(absent.selections()).hasSize(PAGE_SIZE).containsOnly(SELECT_NONE);
            assertThat(shortList.selections()).hasSize(PAGE_SIZE);
            assertThat(shortList.selectionAt(2)).isEqualTo(SELECT_VIEW);
            assertThat(shortList.selectionAt(PAGE_SIZE)).isEqualTo(SELECT_NONE);
        }

        @Test
        @DisplayName("a selection list wider than the page is refused outright, because a screen with "
                + "more than seven rows is not this screen")
        void aSelectionListWiderThanThePageIsRefused() {
            final List<String> tooMany = Collections.nCopies(PAGE_SIZE + 1, SELECT_NONE);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> reSubmit("DFHENTER", tooMany))
                    .withMessageContaining(String.valueOf(PAGE_SIZE));
        }

        @Test
        @DisplayName("a slot outside the seven the screen has cannot be read, so the index is checked "
                + "rather than trusted")
        void aSlotOutsideThePageCannotBeRead() {
            final CardListService.CardListScreenInput input =
                    reSubmit("DFHENTER", selectionAt(1, SELECT_VIEW));

            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .isThrownBy(() -> input.selectionAt(0))
                    .withMessageContaining(String.valueOf(PAGE_SIZE));
            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .isThrownBy(() -> input.selectionAt(PAGE_SIZE + 1))
                    .withMessageContaining(String.valueOf(PAGE_SIZE + 1));
        }

        @Test
        @DisplayName("a row outside the seven slots cannot be constructed, so a caller cannot assemble a "
                + "page the screen could not present")
        void aRowOutsideTheSevenSlotsCannotBeConstructed() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new CardListService.CardListRow(0, ACCOUNT_A, cardNumber(1),
                            STATUS_ACTIVE, SELECT_NONE))
                    .withMessageContaining(String.valueOf(PAGE_SIZE));
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new CardListService.CardListRow(PAGE_SIZE + 1, ACCOUNT_A,
                            cardNumber(1), STATUS_ACTIVE, SELECT_NONE))
                    .withMessageContaining(String.valueOf(PAGE_SIZE + 1));
        }

        @Test
        @DisplayName("the two selection characters resolve exactly and nothing else does, so a lower-case "
                + "or multi-character field selects nothing")
        void theTwoSelectionCharactersResolveExactly() {
            assertThat(CardListService.SelectionAction.fromSelection(SELECT_VIEW))
                    .contains(CardListService.SelectionAction.VIEW);
            assertThat(CardListService.SelectionAction.fromSelection(SELECT_UPDATE))
                    .contains(CardListService.SelectionAction.UPDATE);
            assertThat(CardListService.SelectionAction.VIEW.getCode()).isEqualTo('S');
            assertThat(CardListService.SelectionAction.UPDATE.getCode()).isEqualTo('U');
            assertThat(CardListService.SelectionAction.VIEW.isViewRequested()).isTrue();
            assertThat(CardListService.SelectionAction.VIEW.isUpdateRequested()).isFalse();
            assertThat(CardListService.SelectionAction.UPDATE.isUpdateRequested()).isTrue();
            assertThat(CardListService.SelectionAction.UPDATE.isViewRequested()).isFalse();

            assertThat(CardListService.SelectionAction.fromSelection("s")).isEmpty();
            assertThat(CardListService.SelectionAction.fromSelection("u")).isEmpty();
            assertThat(CardListService.SelectionAction.fromSelection("SU")).isEmpty();
            assertThat(CardListService.SelectionAction.fromSelection(" ")).isEmpty();
            assertThat(CardListService.SelectionAction.fromSelection(SELECT_NONE)).isEmpty();
            assertThat(CardListService.SelectionAction.fromSelection(null)).isEmpty();
        }

        @Test
        @DisplayName("the three filter states are distinct, because a filter that failed its edit and one "
                + "that was never supplied both leave the data unfiltered while differing on the screen")
        void theThreeFilterStatesAreDistinct() {
            assertThat(CardListService.FilterFlag.NOT_OK.isNotOk()).isTrue();
            assertThat(CardListService.FilterFlag.NOT_OK.isValid()).isFalse();
            assertThat(CardListService.FilterFlag.NOT_OK.isBlank()).isFalse();
            assertThat(CardListService.FilterFlag.VALID.isValid()).isTrue();
            assertThat(CardListService.FilterFlag.VALID.isNotOk()).isFalse();
            assertThat(CardListService.FilterFlag.VALID.isBlank()).isFalse();
            assertThat(CardListService.FilterFlag.BLANK.isBlank()).isTrue();
            assertThat(CardListService.FilterFlag.BLANK.isValid()).isFalse();
            assertThat(CardListService.FilterFlag.BLANK.isNotOk()).isFalse();
        }
    }

    // ==============================================================================================

    @Nested
    @DisplayName("the page is exactly seven rows, and a short page is short rather than padded")
    final class PageComposition {

        /** Creates the nest. */
        PageComposition() {
        }

        @Test
        @DisplayName("a cluster of exactly seven fills all seven slots, stamps the header and carries the "
                + "action advisory")
        void exactlySevenRowsFillThePage() {
            final List<Card> cluster = clusterOf(PAGE_SIZE);
            stubPositioningRead(cluster);
            stubAscendingChunks(cluster);

            final CardListService.CardListResult result =
                    service.processCardList(firstEntry("DFHENTER"));

            assertThat(result.rows()).hasSize(PAGE_SIZE);
            assertThat(result.recordsFound()).isTrue();
            assertThat(presentedCardNumbers(result)).containsExactly(
                    cardNumber(1), cardNumber(2), cardNumber(3), cardNumber(4),
                    cardNumber(5), cardNumber(6), cardNumber(7));
            assertThat(result.rows().get(0).screenSlot()).isOne();
            assertThat(result.rows().get(PAGE_SIZE - 1).screenSlot()).isEqualTo(PAGE_SIZE);
            assertThat(result.infoMessage()).isEqualTo(ORACLE_INFORM_REC_ACTIONS);
            assertThat(result.errorFlag()).isFalse();
            assertThat(result.reArmedTransactionId()).isEqualTo(THIS_TRANSACTION);
        }

        @Test
        @DisplayName("a cluster wider than the page still yields exactly seven, because the page size is "
                + "the legacy table's own width and not the chunk the reader happened to deliver")
        void aWiderClusterStillYieldsExactlySevenRows() {
            final List<Card> cluster = clusterOf(PAGE_SIZE * 3);
            stubPositioningRead(cluster);
            stubAscendingChunks(cluster);

            final CardListService.CardListResult result =
                    service.processCardList(firstEntry("DFHENTER"));

            assertThat(result.rows()).hasSize(PAGE_SIZE);
            assertThat(presentedCardNumbers(result)).containsExactly(
                    cardNumber(1), cardNumber(2), cardNumber(3), cardNumber(4),
                    cardNumber(5), cardNumber(6), cardNumber(7));
            assertThat(result.pageMetadata().pageSize()).isEqualTo(PAGE_SIZE);
        }

        @Test
        @DisplayName("a cluster shorter than the page fills only what it holds, leaving the remaining "
                + "slots unbuilt rather than padded with blank rows or nulls")
        void aShorterClusterIsNotPadded() {
            final List<Card> cluster = clusterOf(3);
            stubPositioningRead(cluster);
            stubAscendingChunks(cluster);

            final CardListService.CardListResult result =
                    service.processCardList(firstEntry("DFHENTER"));

            assertThat(result.rows()).hasSize(3).doesNotContainNull();
            assertThat(presentedCardNumbers(result))
                    .containsExactly(cardNumber(1), cardNumber(2), cardNumber(3));
            assertThat(result.rows()).extracting(CardListService.CardListRow::screenSlot)
                    .containsExactly(1, 2, 3);
            assertThat(result.rows()).extracting(CardListService.CardListRow::accountId)
                    .containsOnly(ACCOUNT_A);
        }

        @Test
        @DisplayName("every read the browse issues is bounded to one screen's worth of rows, so no call "
                + "asks for more than the screen can present")
        void everyReadIsBoundedToTheScreenWidth() {
            final List<Card> cluster = clusterOf(PAGE_SIZE * 2);
            stubPositioningRead(cluster);
            stubAscendingChunks(cluster);

            service.processCardList(firstEntry("DFHENTER"));

            final ArgumentCaptor<Limit> bounds = ArgumentCaptor.forClass(Limit.class);
            verify(cardRepository, atLeastOnce())
                    .findByCardNumGreaterThanOrderByCardNumAsc(anyString(), bounds.capture());
            assertThat(bounds.getAllValues()).isNotEmpty()
                    .allSatisfy(bound -> assertThat(bound.max()).isEqualTo(PAGE_SIZE));
        }

        @Test
        @DisplayName("an exhausted cluster presents no rows and says so in the source's own words")
        void anExhaustedClusterPresentsNoRows() {
            stubEmptyCluster();

            final CardListService.CardListResult result =
                    service.processCardList(firstEntry("DFHENTER"));

            assertThat(result.rows()).isEmpty();
            assertThat(result.recordsFound()).isFalse();
            assertThat(result.errorMessage()).isEqualTo(ORACLE_NO_RECORDS_FOUND);
            assertThat(result.infoMessage())
                    .as("the advisory to type S for detail is suppressed on an empty result, because it "
                            + "would invite the operator to select a row that is not there")
                    .isEmpty();
        }

        @Test
        @DisplayName("the header is stamped from the injected clock and carries the catalogue titles at "
                + "their padded widths, untrimmed")
        void theHeaderIsStampedFromTheInjectedClock() {
            final List<Card> cluster = clusterOf(PAGE_SIZE);
            stubPositioningRead(cluster);
            stubAscendingChunks(cluster);

            final CardListService.CardListResult result =
                    service.processCardList(firstEntry("DFHENTER"));

            final CardListService.ScreenHeader header = result.header();
            assertThat(header.currentDate()).isEqualTo(ORACLE_HEADER_DATE);
            assertThat(header.currentTime()).isEqualTo(ORACLE_HEADER_TIME);
            assertThat(header.transactionName()).isEqualTo(THIS_TRANSACTION);
            assertThat(header.programName()).isEqualTo(THIS_PROGRAM);
            assertThat(header.title01()).isEqualTo(ORACLE_TITLE01);
            assertThat(header.title02()).isEqualTo(ORACLE_TITLE02);
            assertThat(header.title01().getBytes(StandardCharsets.US_ASCII))
                    .hasSize(SCREEN_TITLE_WIDTH);
            assertThat(header.title02().getBytes(StandardCharsets.US_ASCII))
                    .hasSize(SCREEN_TITLE_WIDTH);
        }

        @Test
        @DisplayName("the positional selection bitmap is seven cleared entries on a clean page, because a "
                + "cleared entry is a reported fact about a slot rather than an absence")
        void theBitmapIsSevenClearedEntriesOnACleanPage() {
            final List<Card> cluster = clusterOf(PAGE_SIZE);
            stubPositioningRead(cluster);
            stubAscendingChunks(cluster);

            final CardListService.CardListResult result =
                    service.processCardList(firstEntry("DFHENTER"));

            assertThat(result.selectionErrorFlags()).hasSize(PAGE_SIZE).containsOnly(Boolean.FALSE);
            for (int slot = 1; slot <= PAGE_SIZE; slot++) {
                assertThat(result.selectionErrorAt(slot)).isFalse();
            }
            assertThat(result.fieldErrors()).isEmpty();
            assertThat(result.selectedRowIndex()).isZero();
            assertThat(result.selectedRow()).isEmpty();
        }

        @Test
        @DisplayName("a slot outside the seven cannot be interrogated for an error either")
        void anErrorFlagOutsideTheSevenSlotsCannotBeRead() {
            stubEmptyCluster();

            final CardListService.CardListResult result =
                    service.processCardList(firstEntry("DFHENTER"));

            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .isThrownBy(() -> result.selectionErrorAt(0))
                    .withMessageContaining(String.valueOf(PAGE_SIZE));
            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .isThrownBy(() -> result.selectionErrorAt(PAGE_SIZE + 1))
                    .withMessageContaining(String.valueOf(PAGE_SIZE + 1));
        }

        @Test
        @DisplayName("the status character crosses as text so a value outside the two the estate defines "
                + "still reaches the screen, and the typed view is offered beside it")
        void theStatusCharacterCrossesAsTextAndOffersATypedView() {
            final Card active = cardRow(1, ACCOUNT_A);
            final Card undefined = TestDataFactory.card().cardNumber(cardNumber(2))
                    .accountId(ACCOUNT_A).verificationCode(CARD_VERIFICATION_CODE)
                    .embossedName(EMBOSSED_NAME_WITH_SPACE).expirationDate(CARD_EXPIRY)
                    .activeStatus("Q").build();
            final List<Card> cluster = List.of(active, undefined);
            stubPositioningRead(cluster);
            stubAscendingChunks(cluster);

            final CardListService.CardListResult result =
                    service.processCardList(firstEntry("DFHENTER"));

            assertThat(result.rows()).hasSize(2);
            assertThat(result.rows().get(0).cardActiveStatus()).isEqualTo(STATUS_ACTIVE);
            assertThat(result.rows().get(0).resolvedStatus()).contains(CardStatus.Y);
            assertThat(result.rows().get(1).cardActiveStatus())
                    .as("an undefined status character is carried through unchanged, as the move does")
                    .isEqualTo("Q");
            assertThat(result.rows().get(1).resolvedStatus()).isEmpty();
        }
    }

    // ==============================================================================================

    @Nested
    @DisplayName("the browse walks the base cluster by card number and filters by account afterwards")
    final class BrowseOrderAndPostRetrievalFilter {

        /** Creates the nest. */
        BrowseOrderAndPostRetrievalFilter() {
        }

        /**
         * The rows of the interleaved fixture that belong to the requested account, in the order the
         * global card-number sequence puts them.
         *
         * <p>Written out rather than computed, because a computed expectation would reproduce whatever
         * selection rule the production class happens to apply and would agree with it by construction.
         */
        private static final List<String> ORACLE_FIRST_ACCOUNT_PAGE = List.of(
                CARD_NUMBER_STEM + "01", CARD_NUMBER_STEM + "03", CARD_NUMBER_STEM + "05",
                CARD_NUMBER_STEM + "07", CARD_NUMBER_STEM + "09", CARD_NUMBER_STEM + "11",
                CARD_NUMBER_STEM + "13");

        @Test
        @DisplayName("with two accounts interleaved by card number, page one carries the requested "
                + "account's seven rows drawn from the GLOBAL card-number order, and the account finder is "
                + "never reached")
        void theFilterIsAppliedAfterACardNumberOrderedRead() {
            final List<Card> cluster = interleavedClusterOf(14);
            stubPositioningRead(cluster);
            stubAscendingChunks(cluster);

            final CardListService.CardListResult result =
                    service.processCardList(reSubmitFiltered("DFHENTER", ACCOUNT_A, null));

            assertThat(result.errorFlag()).isFalse();
            assertThat(result.rows()).hasSize(PAGE_SIZE);
            assertThat(presentedCardNumbers(result))
                    .containsExactlyElementsOf(ORACLE_FIRST_ACCOUNT_PAGE);
            assertThat(result.rows()).extracting(CardListService.CardListRow::accountId)
                    .containsOnly(ACCOUNT_A);

            verifyTheAccountFinderIsNeverReached();
            verifyNoOffsetPageIsRequested();
        }

        @Test
        @DisplayName("the walk DELIVERS fourteen rows to present seven, and half of what it delivered "
                + "belongs to the other account - which an account-scoped query could not have returned")
        void theWalkReadsRowsItThenDiscards() {
            final List<Card> cluster = interleavedClusterOf(14);
            stubPositioningRead(cluster);
            stubAscendingChunks(cluster);

            final CardListService.CardListResult result =
                    service.processCardList(reSubmitFiltered("DFHENTER", ACCOUNT_A, null));

            assertThat(result.rows()).hasSize(PAGE_SIZE);
            assertThat(deliveredRows)
                    .as("the read loop keeps going past excluded rows until seven have been accepted, so "
                            + "the cluster hands over every row in the range and the filter discards half")
                    .hasSize(14);
            assertThat(deliveredRows).extracting(Card::getCardAcctId)
                    .as("rows of the other account really were read before being discarded")
                    .contains(ACCOUNT_B);
            assertThat(deliveredCardNumbers())
                    .as("delivery follows the global card-number order, not a per-account order")
                    .containsExactly(
                            cardNumber(1), cardNumber(2), cardNumber(3), cardNumber(4),
                            cardNumber(5), cardNumber(6), cardNumber(7), cardNumber(8),
                            cardNumber(9), cardNumber(10), cardNumber(11), cardNumber(12),
                            cardNumber(13), cardNumber(14));
        }

        @Test
        @DisplayName("the retained forward cursor lands on the EIGHTH PHYSICAL row and not the seventh "
                + "accepted one, so with a filter active it can name a card the page never showed")
        void theRetainedCursorIsTheNextPhysicalRow() {
            final List<Card> cluster = interleavedClusterOf(14);
            stubPositioningRead(cluster);
            stubAscendingChunks(cluster);

            final CardListService.CardListResult result =
                    service.processCardList(reSubmitFiltered("DFHENTER", ACCOUNT_A, null));

            assertThat(result.pageMetadata().nextCursorKey())
                    .as("the look-ahead read is not passed through the filter, so the cursor is the next "
                            + "physical row - here a card belonging to the account that was filtered out")
                    .isEqualTo(cardNumber(14));
            assertThat(presentedCardNumbers(result))
                    .as("and that card was never presented, which is exactly the point")
                    .doesNotContain(cardNumber(14));
            assertThat(result.pageMetadata().hasMorePages())
                    .as("a further page is promised on the strength of the next physical row even though "
                            + "no further row matches the filter; an account-scoped query would say no")
                    .isTrue();
        }

        @Test
        @DisplayName("the ascending finder is the one that carries the forward ordering, and the descending "
                + "one is untouched on a forward walk")
        void theForwardWalkUsesTheAscendingFinderOnly() {
            final List<Card> cluster = clusterOf(PAGE_SIZE * 2);
            stubPositioningRead(cluster);
            stubAscendingChunks(cluster);

            service.processCardList(firstEntry("DFHENTER"));

            final ArgumentCaptor<String> cursors = ArgumentCaptor.forClass(String.class);
            verify(cardRepository, atLeastOnce())
                    .findByCardNumGreaterThanOrderByCardNumAsc(cursors.capture(), any(Limit.class));
            assertThat(cursors.getAllValues().get(0))
                    .as("a blank retained identifier is the source's own initial value, which a "
                            + "greater-or-equal start resolves to the first row of the cluster")
                    .isEmpty();
            assertThat(cursors.getAllValues())
                    .as("each continuation resumes strictly beyond the row last handed out")
                    .isSubsetOf(SELECT_NONE, cardNumber(PAGE_SIZE));
            verify(cardRepository, never())
                    .findByCardNumLessThanOrderByCardNumDesc(anyString(), any(Limit.class));
            verifyTheAccountFinderIsNeverReached();
            verifyNoOffsetPageIsRequested();
        }

        @Test
        @DisplayName("a card filter that matches nothing on the page still leaves the browse ordered by "
                + "card number, because a filter the edit accepted narrows rows and never reorders them")
        void aCardFilterNarrowsWithoutReordering() {
            final List<Card> cluster = clusterOf(PAGE_SIZE * 2);
            stubPositioningRead(cluster);
            stubAscendingChunks(cluster);

            final CardListService.CardListResult result =
                    service.processCardList(reSubmitFiltered("DFHENTER", null, cardNumber(5)));

            assertThat(result.errorFlag()).isFalse();
            assertThat(presentedCardNumbers(result)).containsExactly(cardNumber(5));
            verifyTheAccountFinderIsNeverReached();
        }

        @Test
        @DisplayName("a filter whose edit failed leaves the data unfiltered rather than excluding "
                + "everything, and nothing is read at all because the refusal comes first")
        void aFilterThatFailedItsEditReadsNothing() {
            final CardListService.CardListResult result =
                    service.processCardList(reSubmitFiltered("DFHENTER", "4111", null));

            assertThat(result.errorFlag()).isTrue();
            assertThat(result.rows()).isEmpty();
            verifyNoInteractionsWithTheCluster();
        }

        @Test
        @DisplayName("an account identifier of nothing but zeros is read as no filter at all, which is the "
                + "third limb of the not-supplied test and the one easily missed")
        void anAllZeroAccountIdentifierIsNoFilter() {
            final List<Card> cluster = interleavedClusterOf(4);
            stubPositioningRead(cluster);
            stubAscendingChunks(cluster);

            final CardListService.CardListResult result =
                    service.processCardList(reSubmitFiltered("DFHENTER", "0".repeat(11), null));

            assertThat(result.errorFlag())
                    .as("an eleven-digit filter of zeros is not supplied, so it is not a format error")
                    .isFalse();
            assertThat(result.rows()).extracting(CardListService.CardListRow::accountId)
                    .as("and being no filter at all, it excludes nothing: both accounts reach the page")
                    .contains(ACCOUNT_A, ACCOUNT_B);
        }
    }

    // ==============================================================================================

    @Nested
    @DisplayName("the two key filters are edited before anything is read, in the source's own order")
    final class KeyFilterEdits {

        /** Creates the nest. */
        KeyFilterEdits() {
        }

        @ParameterizedTest(name = "an account filter of \"{0}\" is refused")
        @ValueSource(strings = {"4111", "0000000001A", "000000000111", " 0000000011"})
        @DisplayName("an account filter that is not eleven digits is refused in the source's own wording, "
                + "and the account field takes the cursor")
        void aMalformedAccountFilterIsRefused(final String keyed) {
            final CardListService.CardListResult result =
                    service.processCardList(reSubmitFiltered("DFHENTER", keyed, null));

            assertThat(result.errorFlag()).isTrue();
            assertThat(result.errorMessage()).isEqualTo(ORACLE_ACCOUNT_FILTER_FORMAT);
            assertThat(result.focusField()).isEqualTo(BMS_ACCOUNT_FILTER);
            assertThat(result.infoMessage())
                    .as("a not-OK filter leaves the message exactly as the edit composed it and the "
                            + "advisory blank, which is the first arm of the message cascade")
                    .isEmpty();
            verifyNoInteractionsWithTheCluster();
        }

        @ParameterizedTest(name = "a card filter of \"{0}\" is refused")
        @ValueSource(strings = {"4111", "411111111111111A", "41111111111111111"})
        @DisplayName("a card filter that is not sixteen digits is refused in its own wording, and the card "
                + "field takes the cursor")
        void aMalformedCardFilterIsRefused(final String keyed) {
            final CardListService.CardListResult result =
                    service.processCardList(reSubmitFiltered("DFHENTER", null, keyed));

            assertThat(result.errorFlag()).isTrue();
            assertThat(result.errorMessage()).isEqualTo(ORACLE_CARD_FILTER_FORMAT);
            assertThat(result.focusField()).isEqualTo(BMS_CARD_FILTER);
            verifyNoInteractionsWithTheCluster();
        }

        @Test
        @DisplayName("when BOTH filters are bad the operator is told about the account filter, because the "
                + "account edit assigns unconditionally and the card edit only fills a blank message")
        void theAccountMessageWinsWhenBothFiltersAreBad() {
            final CardListService.CardListResult result =
                    service.processCardList(reSubmitFiltered("DFHENTER", "4111", "4111"));

            assertThat(result.errorFlag()).isTrue();
            assertThat(result.errorMessage()).isEqualTo(ORACLE_ACCOUNT_FILTER_FORMAT);
            assertThat(result.errorMessage()).isNotEqualTo(ORACLE_CARD_FILTER_FORMAT);
            assertThat(result.focusField())
                    .as("the account field is first in the map, so it is the one the terminal focuses")
                    .isEqualTo(BMS_ACCOUNT_FILTER);
        }

        @Test
        @DisplayName("a well-formed filter is accepted and the browse still runs, so a filter that passes "
                + "its edit does not become an error by accident")
        void aWellFormedFilterIsAccepted() {
            final List<Card> cluster = clusterOf(PAGE_SIZE);
            stubPositioningRead(cluster);
            stubAscendingChunks(cluster);

            final CardListService.CardListResult result =
                    service.processCardList(reSubmitFiltered("DFHENTER", ACCOUNT_A, null));

            assertThat(result.errorFlag()).isFalse();
            assertThat(result.errorMessage()).isNotEqualTo(ORACLE_ACCOUNT_FILTER_FORMAT);
            assertThat(result.rows()).hasSize(PAGE_SIZE);
            assertThat(result.navigationContext().accountId()).isEqualTo(ACCOUNT_A);
        }

        @Test
        @DisplayName("a filter error and a slot error can never both stand, because the array edit leaves "
                + "immediately when either filter has already failed")
        void aFilterErrorSuppressesTheSlotEdit() {
            final CardListService.CardListScreenInput input =
                    new CardListService.CardListScreenInput("DFHENTER", workArea("4111", null),
                            selectionsAt(SELECT_VIEW, 2, 6), null, 1, false, false, reSubmission());

            final CardListService.CardListResult result = service.processCardList(input);

            assertThat(result.errorMessage())
                    .as("two selections would otherwise have raised the one-action refusal")
                    .isEqualTo(ORACLE_ACCOUNT_FILTER_FORMAT);
            assertThat(result.errorMessage()).isNotEqualTo(ORACLE_MORE_THAN_ONE_ACTION);
            assertThat(result.selectionErrorFlags())
                    .as("no slot flag is raised, because the tally never ran")
                    .hasSize(PAGE_SIZE).containsOnly(Boolean.FALSE);
            assertThat(result.fieldErrors()).isEmpty();
        }

        @Test
        @DisplayName("the filters are not edited at all on a first entry, because the source receives input "
                + "only when this screen is submitting back to itself")
        void theFiltersAreNotEditedOnAFirstEntry() {
            final List<Card> cluster = clusterOf(PAGE_SIZE);
            stubPositioningRead(cluster);
            stubAscendingChunks(cluster);

            final CardListService.CardListScreenInput input =
                    new CardListService.CardListScreenInput("DFHENTER", workArea("4111", null), null,
                            null, 1, false, false, null);

            final CardListService.CardListResult result = service.processCardList(input);

            assertThat(result.errorFlag())
                    .as("a malformed filter arriving on a first entry is never looked at")
                    .isFalse();
            assertThat(result.errorMessage()).isNotEqualTo(ORACLE_ACCOUNT_FILTER_FORMAT);
            assertThat(result.rows()).hasSize(PAGE_SIZE);
        }
    }

    // ==============================================================================================

    @Nested
    @DisplayName("the selection tally, the one-action-per-page refusal and the positional bitmap")
    final class SelectionTallyAndBitmap {

        /** Creates the nest. */
        SelectionTallyAndBitmap() {
        }

        @Test
        @DisplayName("two actions on one page are refused and BOTH offending rows are named - in the "
                + "bitmap, in the field findings and in the diagnostic - which is what the tally buys over "
                + "stopping at the first")
        void twoActionsAreRefusedAndBothRowsAreNamed() {
            final List<Card> cluster = clusterOf(PAGE_SIZE);
            stubPositioningRead(cluster);
            stubAscendingChunks(cluster);

            final CardListService.CardListResult result = service.processCardList(
                    reSubmit("DFHENTER", selectionsAt(SELECT_VIEW, 2, 6)));

            assertThat(result.errorFlag()).isTrue();
            assertThat(result.selectionErrorAt(2)).isTrue();
            assertThat(result.selectionErrorAt(6)).isTrue();
            assertThat(result.fieldErrors())
                    .extracting(ValidationException.FieldError::field)
                    .containsExactly(PROPERTY_SELECTION_STEM + 2, PROPERTY_SELECTION_STEM + 6);
            assertThat(result.fieldErrors())
                    .extracting(ValidationException.FieldError::bmsFieldId)
                    .containsExactly(BMS_SELECTION_STEM + 2, BMS_SELECTION_STEM + 6);
            assertThat(recordedMessages())
                    .as("the offending slots reach the structured diagnostic as well as the screen")
                    .anySatisfy(message -> assertThat(message).contains("offendingSlots=[2, 6]"));
        }

        @Test
        @DisplayName("the one-action refusal reads byte for byte as the source wrote it: upper case "
                + "throughout, and with NO trailing period")
        void theRefusalMessageIsByteForByteTheSourcesOwn() {
            final List<Card> cluster = clusterOf(PAGE_SIZE);
            stubPositioningRead(cluster);
            stubAscendingChunks(cluster);

            final CardListService.CardListResult result = service.processCardList(
                    reSubmit("DFHENTER", selectionsAt(SELECT_VIEW, 1, 5)));

            final String message = result.errorMessage();
            assertThat(message).isEqualTo(ORACLE_MORE_THAN_ONE_ACTION);
            assertThat(message.getBytes(StandardCharsets.US_ASCII))
                    .as("compared as encoded bytes, so no invisible difference can pass")
                    .isEqualTo(ORACLE_MORE_THAN_ONE_ACTION.getBytes(StandardCharsets.US_ASCII));
            assertThat(message)
                    .as("this member's message family is entirely upper case, unlike the mixed-case "
                            + "common catalogue")
                    .isEqualTo(message.toUpperCase(Locale.ROOT))
                    .doesNotEndWith(".")
                    .isEqualTo(message.stripTrailing());
            assertThat(message).isNotEqualTo(ORACLE_MORE_THAN_ONE_ACTION + ".");
        }

        @Test
        @DisplayName("the bitmap keeps its empty slots at their own positions: rows two and six are named "
                + "and the other five are reported as unselected where they sit, never compacted away")
        void theBitmapIsPositionalAndNeverCompacted() {
            final List<Card> cluster = clusterOf(PAGE_SIZE);
            stubPositioningRead(cluster);
            stubAscendingChunks(cluster);

            final CardListService.CardListResult result = service.processCardList(
                    reSubmit("DFHENTER", selectionsAt(SELECT_UPDATE, 2, 6)));

            assertThat(result.selectionErrorFlags())
                    .as("exactly seven entries, one per screen slot - not two entries for the two "
                            + "offenders, because a cleared flag is a reported fact about its slot")
                    .hasSize(PAGE_SIZE)
                    .containsExactly(Boolean.FALSE, Boolean.TRUE, Boolean.FALSE, Boolean.FALSE,
                            Boolean.FALSE, Boolean.TRUE, Boolean.FALSE);
            assertThat(result.selectionErrorFlags()).filteredOn(Boolean.TRUE::equals).hasSize(2);
            assertThat(result.selectionErrorAt(1)).isFalse();
            assertThat(result.selectionErrorAt(3)).isFalse();
            assertThat(result.selectionErrorAt(4)).isFalse();
            assertThat(result.selectionErrorAt(5)).isFalse();
            assertThat(result.selectionErrorAt(PAGE_SIZE)).isFalse();
        }

        @Test
        @DisplayName("the bitmap is built ONLY inside the refusal, so a single unrecognised character "
                + "flags its own row and leaves the other six clear")
        void anUnrecognisedCharacterFlagsOnlyItsOwnRow() {
            final List<Card> cluster = clusterOf(PAGE_SIZE);
            stubPositioningRead(cluster);
            stubAscendingChunks(cluster);

            final CardListService.CardListResult result =
                    service.processCardList(reSubmit("DFHENTER", selectionAt(4, "X")));

            assertThat(result.errorFlag()).isTrue();
            assertThat(result.errorMessage()).isEqualTo(ORACLE_INVALID_ACTION_CODE);
            assertThat(result.selectionErrorFlags()).containsExactly(Boolean.FALSE, Boolean.FALSE,
                    Boolean.FALSE, Boolean.TRUE, Boolean.FALSE, Boolean.FALSE, Boolean.FALSE);
            assertThat(result.fieldErrors()).hasSize(1);
            final ValidationException.FieldError finding = result.fieldErrors().get(0);
            assertThat(finding.field()).isEqualTo(PROPERTY_SELECTION_STEM + 4);
            assertThat(finding.bmsFieldId()).isEqualTo(BMS_SELECTION_STEM + 4);
            assertThat(finding.state())
                    .as("a character was supplied and is unusable, which the legacy signals by colouring "
                            + "the field rather than by writing the blank-field marker")
                    .isEqualTo(ValidationException.FieldState.INVALID);
            assertThat(finding.message()).isEqualTo(ORACLE_INVALID_ACTION_CODE);
            assertThat(result.selectedRowIndex())
                    .as("a refused character selects nothing")
                    .isZero();
        }

        @ParameterizedTest(name = "a selection of \"{0}\" is refused")
        @ValueSource(strings = {"s", "u", "X", "1", "*"})
        @DisplayName("only the two upper-case characters are accepted, so a lower-case selection is refused "
                + "rather than folded - folding it would admit input the screen rejects")
        void onlyTheTwoUpperCaseCharactersAreAccepted(final String keyed) {
            final List<Card> cluster = clusterOf(PAGE_SIZE);
            stubPositioningRead(cluster);
            stubAscendingChunks(cluster);

            final CardListService.CardListResult result =
                    service.processCardList(reSubmit("DFHENTER", selectionAt(5, keyed)));

            assertThat(result.errorFlag()).isTrue();
            assertThat(result.errorMessage()).isEqualTo(ORACLE_INVALID_ACTION_CODE);
            assertThat(result.selectionErrorAt(5)).isTrue();
            assertThat(result.selectedRowIndex()).isZero();
        }

        @Test
        @DisplayName("ONE view selection is accepted and hands control to the card detail screen, and no "
                + "row is read on the way because the transfer happens before the browse")
        void oneViewSelectionIsAcceptedAndTransfers() {
            final CardListService.CardListResult result = service.processCardList(
                    reSubmit("DFHENTER", selectionAt(2, SELECT_VIEW)));

            assertThat(result.errorFlag()).isFalse();
            assertThat(result.errorMessage()).isNotEqualTo(ORACLE_MORE_THAN_ONE_ACTION);
            assertThat(result.selectedRowIndex()).isEqualTo(2);
            assertThat(result.navigationContext().toProgram()).isEqualTo(CARD_DETAIL_PROGRAM);
            assertThat(result.route()).isEqualTo(NavigationService.Route.CARD_DETAIL);
            verifyNoInteractionsWithTheCluster();
        }

        @Test
        @DisplayName("ONE update selection hands control to the card update screen instead, which is the "
                + "second of the two transfer arms")
        void oneUpdateSelectionTransfersToTheUpdateScreen() {
            final CardListService.CardListResult result = service.processCardList(
                    reSubmit("DFHENTER", selectionAt(3, SELECT_UPDATE)));

            assertThat(result.errorFlag()).isFalse();
            assertThat(result.selectedRowIndex()).isEqualTo(3);
            assertThat(result.navigationContext().toProgram()).isEqualTo(CARD_UPDATE_PROGRAM);
            assertThat(result.route()).isEqualTo(NavigationService.Route.CARD_UPDATE);
        }

        @Test
        @DisplayName("ZERO selections take no action at all: the page is simply re-presented, nothing is "
                + "chosen and no slot is flagged")
        void zeroSelectionsTakeNoAction() {
            final List<Card> cluster = clusterOf(PAGE_SIZE);
            stubPositioningRead(cluster);
            stubAscendingChunks(cluster);

            final CardListService.CardListResult result =
                    service.processCardList(reSubmit("DFHENTER", Collections.nCopies(PAGE_SIZE,
                            SELECT_NONE)));

            assertThat(result.errorFlag()).isFalse();
            assertThat(result.selectedRowIndex()).isZero();
            assertThat(result.selectedRow()).isEmpty();
            assertThat(result.selectionErrorFlags()).containsOnly(Boolean.FALSE);
            assertThat(result.fieldErrors()).isEmpty();
            assertThat(result.navigationContext().toProgram())
                    .as("nothing was chosen, so control stays on this screen")
                    .isEqualTo(THIS_PROGRAM);
            assertThat(result.route()).isEqualTo(NavigationService.Route.CARD_LIST);
        }

        @Test
        @DisplayName("a single space is a blank slot and not an unrecognised character, because the source "
                + "names a space and low values as blank and nothing else")
        void aSingleSpaceIsBlankAndNotAnError() {
            final List<Card> cluster = clusterOf(PAGE_SIZE);
            stubPositioningRead(cluster);
            stubAscendingChunks(cluster);

            final CardListService.CardListResult result =
                    service.processCardList(reSubmit("DFHENTER", selectionAt(3, " ")));

            assertThat(result.errorFlag()).isFalse();
            assertThat(result.errorMessage()).isNotEqualTo(ORACLE_INVALID_ACTION_CODE);
            assertThat(result.selectionErrorFlags()).containsOnly(Boolean.FALSE);
        }

        @Test
        @DisplayName("with two selections standing, the chosen slot ends as the LAST accepted row rather "
                + "than the first - the source's own outcome, harmless because the refusal already fired")
        void theChosenSlotEndsAsTheLastAcceptedRow() {
            final List<Card> cluster = clusterOf(PAGE_SIZE);
            stubPositioningRead(cluster);
            stubAscendingChunks(cluster);

            final CardListService.CardListResult result = service.processCardList(
                    reSubmit("DFHENTER", selectionsAt(SELECT_VIEW, 2, 6)));

            assertThat(result.selectedRowIndex()).isEqualTo(6);
            assertThat(result.navigationContext().toProgram())
                    .as("a refused page transfers nowhere, whichever slot the index ended on")
                    .isEqualTo(THIS_PROGRAM);
            assertThat(result.route()).isEqualTo(NavigationService.Route.CARD_LIST);
        }

        @Test
        @DisplayName("THE TALLY COUNTS FROM ZERO: three turns on the same instance - two selections, then "
                + "one, then two again - each give the right answer, so no count leaks across a turn")
        void theTallyCountsFromZeroOnEveryTurn() {
            final List<Card> cluster = clusterOf(PAGE_SIZE);
            stubPositioningRead(cluster);
            stubAscendingChunks(cluster);

            final CardListService.CardListResult firstTurn = service.processCardList(
                    reSubmit("DFHENTER", selectionsAt(SELECT_VIEW, 1, 5)));
            final CardListService.CardListResult secondTurn = service.processCardList(
                    reSubmit("DFHENTER", selectionAt(3, SELECT_VIEW)));
            final CardListService.CardListResult thirdTurn = service.processCardList(
                    reSubmit("DFHENTER", selectionsAt(SELECT_UPDATE, 4, 7)));

            assertThat(firstTurn.errorFlag()).isTrue();
            assertThat(firstTurn.errorMessage()).isEqualTo(ORACLE_MORE_THAN_ONE_ACTION);

            assertThat(secondTurn.errorFlag())
                    .as("a carried-over count of two would have made this single selection look like a "
                            + "third and refused it; the count starts from zero, so it is accepted")
                    .isFalse();
            assertThat(secondTurn.errorMessage()).isNotEqualTo(ORACLE_MORE_THAN_ONE_ACTION);
            assertThat(secondTurn.selectedRowIndex()).isEqualTo(3);
            assertThat(secondTurn.navigationContext().toProgram()).isEqualTo(CARD_DETAIL_PROGRAM);

            assertThat(thirdTurn.errorFlag())
                    .as("and the counter has not been left low either: two selections still refuse")
                    .isTrue();
            assertThat(thirdTurn.errorMessage()).isEqualTo(ORACLE_MORE_THAN_ONE_ACTION);
            assertThat(thirdTurn.selectionErrorAt(4)).isTrue();
            assertThat(thirdTurn.selectionErrorAt(PAGE_SIZE)).isTrue();
            assertThat(thirdTurn.selectionErrorAt(1))
                    .as("the first turn's flags have not survived into the third")
                    .isFalse();
        }

        @Test
        @DisplayName("a field finding is only ever raised on a re-submission, because the decoration the "
                + "source applies is gated on re-entry")
        void fieldFindingsOnlyAriseOnARsubmission() {
            final List<Card> cluster = clusterOf(PAGE_SIZE);
            stubPositioningRead(cluster);
            stubAscendingChunks(cluster);

            final CardListService.CardListResult firstEntryResult =
                    service.processCardList(firstEntry("DFHENTER"));

            assertThat(firstEntryResult.reEntry()).isFalse();
            assertThat(firstEntryResult.fieldErrors()).isEmpty();
        }
    }

    // ==============================================================================================

    @Nested
    @DisplayName("backward paging reads descending and presents ascending")
    final class PagingDirection {

        /** Creates the nest. */
        PagingDirection() {
        }

        /**
         * The order a backward page presents, written out explicitly.
         *
         * <p>This is the reverse of the descending read order, and asserting it as an ordered list rather
         * than as a set is the whole point: a page that carried the right seven rows in the read order
         * would present the screen upside down and would still satisfy a membership assertion.
         */
        private static final List<String> ORACLE_ASCENDING_PAGE = List.of(
                CARD_NUMBER_STEM + "01", CARD_NUMBER_STEM + "02", CARD_NUMBER_STEM + "03",
                CARD_NUMBER_STEM + "04", CARD_NUMBER_STEM + "05", CARD_NUMBER_STEM + "06",
                CARD_NUMBER_STEM + "07");

        /** The order the backward read delivers those same seven rows in. */
        private static final List<String> ORACLE_DESCENDING_READ_ORDER = List.of(
                CARD_NUMBER_STEM + "07", CARD_NUMBER_STEM + "06", CARD_NUMBER_STEM + "05",
                CARD_NUMBER_STEM + "04", CARD_NUMBER_STEM + "03", CARD_NUMBER_STEM + "02",
                CARD_NUMBER_STEM + "01");

        @Test
        @DisplayName("a backward page returns the REVERSE of the descending read order, asserted as an "
                + "ordered list and not as membership")
        void aBackwardPageIsTheReverseOfTheReadOrder() {
            final List<Card> cluster = clusterOf(8);
            stubPositioningRead(cluster);
            stubDescendingChunks(cluster);

            final CardListService.CardListResult result =
                    service.processCardList(reSubmitOnPage("DFHPF7", 2, cardNumber(8)));

            assertThat(presentedCardNumbers(result))
                    .as("the screen presents ascending")
                    .containsExactlyElementsOf(ORACLE_ASCENDING_PAGE);
            assertThat(deliveredCardNumbers())
                    .as("while the read walked descending, consuming the page's own first row unstored")
                    .containsExactly(cardNumber(8), cardNumber(7), cardNumber(6), cardNumber(5),
                            cardNumber(4), cardNumber(3), cardNumber(2), cardNumber(1));
            assertThat(presentedCardNumbers(result))
                    .as("so the presented order really is the reverse of the read order")
                    .isNotEqualTo(ORACLE_DESCENDING_READ_ORDER)
                    .containsExactlyElementsOf(ORACLE_DESCENDING_READ_ORDER.reversed());
            assertThat(result.rows()).extracting(CardListService.CardListRow::screenSlot)
                    .containsExactly(1, 2, 3, 4, 5, 6, PAGE_SIZE);
        }

        @Test
        @DisplayName("the backward walk uses the descending finder and the ascending one is untouched, so "
                + "the ordering really does come from the read and not from a later sort")
        void theBackwardWalkUsesTheDescendingFinderOnly() {
            final List<Card> cluster = clusterOf(8);
            stubPositioningRead(cluster);
            stubDescendingChunks(cluster);

            service.processCardList(reSubmitOnPage("DFHPF7", 2, cardNumber(8)));

            final ArgumentCaptor<String> cursors = ArgumentCaptor.forClass(String.class);
            final ArgumentCaptor<Limit> bounds = ArgumentCaptor.forClass(Limit.class);
            verify(cardRepository, times(1)).findByCardNumLessThanOrderByCardNumDesc(
                    cursors.capture(), bounds.capture());
            assertThat(cursors.getValue()).isEqualTo(cardNumber(8));
            assertThat(bounds.getValue().max()).isEqualTo(PAGE_SIZE);
            verify(cardRepository, never())
                    .findByCardNumGreaterThanOrderByCardNumAsc(anyString(), any(Limit.class));
            verifyTheAccountFinderIsNeverReached();
            verifyNoOffsetPageIsRequested();
        }

        @Test
        @DisplayName("the backward page reports its direction and both boundary keys, because either "
                + "direction can be reversed out of")
        void theBackwardPageReportsItsDirectionAndBothKeys() {
            final List<Card> cluster = clusterOf(8);
            stubPositioningRead(cluster);
            stubDescendingChunks(cluster);

            final CardListService.CardListResult result =
                    service.processCardList(reSubmitOnPage("DFHPF7", 2, cardNumber(8)));

            final BrowseWindow window = result.pageMetadata();
            assertThat(window.direction()).isEqualTo(BrowseWindow.PagingDirection.BACKWARD);
            assertThat(window.pageSize()).isEqualTo(PAGE_SIZE);
            assertThat(window.previousCursorKey()).isEqualTo(cardNumber(1));
            assertThat(window.nextCursorKey()).isEqualTo(cardNumber(8));
            assertThat(window.displayedPageNumber())
                    .as("the page indicator is lowered before the walk, so a step back from page two "
                            + "displays page one")
                    .isEqualTo("1");
        }

        @Test
        @DisplayName("stepping back onto the first page says there is nothing before it, because the page "
                + "indicator is lowered before the message paragraph reads it")
        void steppingBackOntoTheFirstPageSaysSo() {
            final List<Card> cluster = clusterOf(8);
            stubPositioningRead(cluster);
            stubDescendingChunks(cluster);

            final CardListService.CardListResult result =
                    service.processCardList(reSubmitOnPage("DFHPF7", 2, cardNumber(8)));

            assertThat(result.errorMessage()).isEqualTo(ORACLE_NO_PREVIOUS_PAGES);
        }

        @Test
        @DisplayName("the backward key on the first page re-reads the same page FORWARD rather than walking "
                + "before the beginning of the cluster")
        void theBackwardKeyOnTheFirstPageRereadsForward() {
            final List<Card> cluster = clusterOf(PAGE_SIZE);
            stubPositioningRead(cluster);
            stubAscendingChunks(cluster);

            final CardListService.CardListResult result =
                    service.processCardList(reSubmit("DFHPF7", null));

            assertThat(presentedCardNumbers(result)).containsExactlyElementsOf(ORACLE_ASCENDING_PAGE);
            assertThat(result.pageMetadata().direction())
                    .isEqualTo(BrowseWindow.PagingDirection.FORWARD);
            assertThat(result.errorMessage()).isEqualTo(ORACLE_NO_PREVIOUS_PAGES);
            verify(cardRepository, never())
                    .findByCardNumLessThanOrderByCardNumDesc(anyString(), any(Limit.class));
        }

        @Test
        @DisplayName("THE GUARD: the exit key suppresses the browse entirely, so no read of any kind is "
                + "issued on a turn that hands control away")
        void theExitKeySuppressesTheBrowseEntirely() {
            final CardListService.CardListResult result =
                    service.processCardList(reSubmit("DFHPF3", null));

            assertThat(result.errorMessage()).isEqualTo(ORACLE_EXIT_MESSAGE);
            assertThat(result.navigationContext().toProgram()).isEqualTo(MENU_PROGRAM);
            assertThat(result.route()).isEqualTo(NavigationService.Route.USER_MENU);
            assertThat(result.rows()).isEmpty();
            verifyNoInteractionsWithTheCluster();
        }

        @Test
        @DisplayName("THE GUARD: the forward key with no further page recorded suppresses the read too, "
                + "because the arm tests the echoed indicator before the browse recomputes it")
        void theForwardKeyWithoutAFurtherPageStillRereadsFromTheFirstKey() {
            final List<Card> cluster = clusterOf(PAGE_SIZE);
            stubPositioningRead(cluster);
            stubAscendingChunks(cluster);

            final CardListService.CardListScreenInput input =
                    new CardListService.CardListScreenInput("DFHPF8", workArea(null, null), null,
                            new BrowseWindow.CursorRequest(cardNumber(1), cardNumber(PAGE_SIZE),
                                    BrowseWindow.PagingDirection.FORWARD),
                            1, true, false, reSubmission());

            final CardListService.CardListResult result = service.processCardList(input);

            assertThat(result.errorMessage())
                    .as("the operator has already been told they are at the end, so they are told again "
                            + "rather than shown the advisory")
                    .isEqualTo(ORACLE_NO_MORE_PAGES);
            assertThat(result.pageMetadata().direction())
                    .isEqualTo(BrowseWindow.PagingDirection.FORWARD);
            verify(cardRepository, never())
                    .findByCardNumLessThanOrderByCardNumDesc(anyString(), any(Limit.class));
        }

        @Test
        @DisplayName("a forward step raises the echoed page indicator rather than restarting from one, so "
                + "the second page reports itself as the second")
        void aForwardStepRaisesTheEchoedPageIndicator() {
            final List<Card> cluster = clusterOf(PAGE_SIZE * 3);
            stubPositioningRead(cluster);
            stubAscendingChunks(cluster);

            // The retained forward key is the EIGHTH physical row, not the seventh the page showed, so
            // page two resumes at it and no boundary row is presented twice. Handing back the seventh
            // here would repeat it, which is the defect the look-ahead exists to avoid.
            final CardListService.CardListScreenInput input =
                    new CardListService.CardListScreenInput("DFHPF8", workArea(null, null), null,
                            new BrowseWindow.CursorRequest(cardNumber(1), cardNumber(PAGE_SIZE + 1),
                                    BrowseWindow.PagingDirection.FORWARD),
                            1, false, true, reSubmission());

            final CardListService.CardListResult result = service.processCardList(input);

            assertThat(result.pageMetadata().displayedPageNumber()).isEqualTo("2");
            assertThat(result.pageMetadata().hasPreviousPages()).isTrue();
            assertThat(presentedCardNumbers(result)).containsExactly(
                    cardNumber(8), cardNumber(9), cardNumber(10), cardNumber(11),
                    cardNumber(12), cardNumber(13), cardNumber(14));
        }

        @Test
        @DisplayName("an exhausted backward walk is reported as a file condition rather than as a clean "
                + "message, because the backward read has no end-of-file arm at all - reproduced, not fixed")
        void anExhaustedBackwardWalkReportsAFileCondition() {
            final List<Card> cluster = clusterOf(2);
            stubPositioningRead(cluster);
            stubDescendingChunks(cluster);

            // Stepping back from page three, so the lowered indicator lands on page two rather than page
            // one. From page one the message paragraph's own arm would replace the file text with the
            // no-previous-pages text, and the arm order is the contract, so the fixture avoids that arm
            // rather than the assertion working around it.
            final CardListService.CardListResult result =
                    service.processCardList(reSubmitOnPage("DFHPF7", 3, cardNumber(2)));

            assertThat(result.errorMessage())
                    .as("the source's backward evaluation has only a normal arm and a trailing arm, so "
                            + "exhausting the data falls to the trailing one and composes the file text")
                    .isEqualTo(ORACLE_FILE_ERROR_MESSAGE);
            assertThat(result.errorMessage()).isNotEqualTo(ORACLE_NO_PREVIOUS_PAGES);
            verifyNoAbendWasRaised();
        }
    }

    // ==============================================================================================

    @Nested
    @DisplayName("the high attention keys fold onto the low ones, and only four keys are meaningful here")
    final class AttentionKeys {

        /** Creates the nest. */
        AttentionKeys() {
        }

        @Test
        @DisplayName("THE FOLD: the nineteenth program-function key behaves IDENTICALLY to the seventh - "
                + "same rows, same metadata, same messages, same route - because the key store folds it")
        void theNineteenthKeyBehavesIdenticallyToTheSeventh() {
            final List<Card> cluster = clusterOf(8);
            stubPositioningRead(cluster);
            stubDescendingChunks(cluster);

            final CardListService.CardListResult low =
                    service.processCardList(reSubmitOnPage("DFHPF7", 2, cardNumber(8)));
            final CardListService.CardListResult high =
                    service.processCardList(reSubmitOnPage("DFHPF19", 2, cardNumber(8)));

            assertThat(presentedCardNumbers(high))
                    .as("the folded key walks the same page in the same order")
                    .containsExactlyElementsOf(presentedCardNumbers(low));
            assertThat(high.pageMetadata()).isEqualTo(low.pageMetadata());
            assertThat(high.errorMessage()).isEqualTo(low.errorMessage());
            assertThat(high.infoMessage()).isEqualTo(low.infoMessage());
            assertThat(high.route()).isEqualTo(low.route());
            assertThat(high.errorFlag()).isEqualTo(low.errorFlag());
            assertThat(high.selectionErrorFlags()).isEqualTo(low.selectionErrorFlags());
            assertThat(high.header()).isEqualTo(low.header());
            assertThat(high.navigationContext()).isEqualTo(low.navigationContext());
            assertThat(high.errorMessage())
                    .as("and both really did take the backward-paging arm")
                    .isEqualTo(ORACLE_NO_PREVIOUS_PAGES);
        }

        @Test
        @DisplayName("THE FOLD: the fifteenth key is the exit key too, so keys thirteen to twenty-four are "
                + "not distinct actions on this screen")
        void theFifteenthKeyIsTheExitKeyToo() {
            final CardListService.CardListResult low =
                    service.processCardList(reSubmit("DFHPF3", null));
            final CardListService.CardListResult high =
                    service.processCardList(reSubmit("DFHPF15", null));

            assertThat(high.errorMessage()).isEqualTo(ORACLE_EXIT_MESSAGE)
                    .isEqualTo(low.errorMessage());
            assertThat(high.navigationContext().toProgram()).isEqualTo(MENU_PROGRAM);
            assertThat(high.route()).isEqualTo(low.route())
                    .isEqualTo(NavigationService.Route.USER_MENU);
            verifyNoInteractionsWithTheCluster();
        }

        @Test
        @DisplayName("THE FOLD: the twentieth key is the forward key, and the fold is visible in the "
                + "translator's own answer as well as in this screen's behaviour")
        void theTwentiethKeyIsTheForwardKey() {
            assertThat(PfKeyTranslator.translate("DFHPF19"))
                    .contains(KeyAction.PFK07);
            assertThat(PfKeyTranslator.translate("DFHPF20"))
                    .contains(KeyAction.PFK08);
            assertThat(PfKeyTranslator.translate("DFHPF15"))
                    .contains(KeyAction.PFK03);
            assertThat(PfKeyTranslator.translate("DFHPF7"))
                    .as("the low key and its high twin are indistinguishable to every caller")
                    .isEqualTo(PfKeyTranslator.translate("DFHPF19"));
        }

        @Test
        @DisplayName("AN UNMAPPED IDENTIFIER: the fifty-character common message is emitted, the error flag "
                + "rises, and control stays on this screen - no route changes")
        void anUnmappedIdentifierYieldsTheInvalidKeyOutcome() {
            final List<Card> cluster = clusterOf(PAGE_SIZE);
            stubPositioningRead(cluster);
            stubAscendingChunks(cluster);

            final CardListService.CardListResult result =
                    service.processCardList(reSubmit("DFHMSRE", null));

            assertThat(result.errorFlag()).isTrue();
            assertThat(result.errorMessage()).isEqualTo(ORACLE_INVALID_KEY_MESSAGE);
            assertThat(result.route())
                    .as("no route changes: the screen re-presents itself")
                    .isEqualTo(NavigationService.Route.CARD_LIST);
            assertThat(result.navigationContext().toProgram()).isEqualTo(THIS_PROGRAM);
            assertThat(result.navigationContext().toProgram())
                    .isNotEqualTo(MENU_PROGRAM)
                    .isNotEqualTo(CARD_DETAIL_PROGRAM)
                    .isNotEqualTo(CARD_UPDATE_PROGRAM);
            verify(messageCatalogService).invalidKeyMessage();
        }

        @Test
        @DisplayName("AN UNMAPPED IDENTIFIER is not the same state as an unrecognised-but-mapped key: the "
                + "translator reports absence rather than inventing a placeholder action")
        void anUnmappedIdentifierIsReportedAsAbsenceRatherThanAPlaceholder() {
            assertThat(PfKeyTranslator.translate("DFHMSRE"))
                    .as("the copybook's selection has twenty-eight arms and no catch-all, so an "
                            + "identifier matching none of them causes no assignment at all")
                    .isEmpty();
            assertThat(KeyAction.values())
                    .as("and there is no synthetic constant standing in for that absence")
                    .noneMatch(action -> "UNKNOWN".equals(action.name()));
        }

        @ParameterizedTest(name = "{0} is coerced to the enter key and carries no message of its own")
        @ValueSource(strings = {"DFHPF4", "DFHPF5", "DFHCLEAR", "DFHPA1", "DFHPA2", "DFHPF12"})
        @DisplayName("ONLY FOUR KEYS are meaningful here, so any other mapped key is processed as the enter "
                + "key - which is a different outcome from an identifier the key store could not map")
        void anyOtherMappedKeyIsCoercedToEnter(final String rawKey) {
            final List<Card> cluster = clusterOf(PAGE_SIZE);
            stubPositioningRead(cluster);
            stubAscendingChunks(cluster);

            final CardListService.CardListResult result = service.processCardList(reSubmit(rawKey, null));

            assertThat(result.rows()).hasSize(PAGE_SIZE);
            assertThat(result.errorMessage())
                    .as("a mapped but meaningless key is not the exit key")
                    .isNotEqualTo(ORACLE_EXIT_MESSAGE);
            assertThat(result.errorMessage())
                    .as("nor is it the unmapped-identifier outcome, which is the distinction that matters")
                    .isNotEqualTo(ORACLE_INVALID_KEY_MESSAGE);
            assertThat(result.route()).isEqualTo(NavigationService.Route.CARD_LIST);
        }

        @Test
        @DisplayName("an absent identifier is the same observable state as an unrecognised one, and is "
                + "handled here rather than passed on to a translator that treats it as a caller defect")
        void anAbsentIdentifierIsTreatedAsUnrecognised() {
            final List<Card> cluster = clusterOf(PAGE_SIZE);
            stubPositioningRead(cluster);
            stubAscendingChunks(cluster);

            final CardListService.CardListResult absent =
                    service.processCardList(reSubmit(null, null));
            final CardListService.CardListResult blank =
                    service.processCardList(reSubmit("   ", null));

            assertThat(absent.errorMessage()).isEqualTo(ORACLE_INVALID_KEY_MESSAGE);
            assertThat(blank.errorMessage()).isEqualTo(ORACLE_INVALID_KEY_MESSAGE);
            assertThat(absent.errorFlag()).isTrue();
            assertThat(blank.errorFlag()).isTrue();
        }
    }

    // ==============================================================================================

    @Nested
    @DisplayName("the message contract: fifty encoded bytes, never trimmed, upper case where the member is")
    final class MessageContract {

        /** Creates the nest. */
        MessageContract() {
        }

        @Test
        @DisplayName("the common invalid-key message crosses at exactly FIFTY encoded bytes with its ten "
                + "trailing spaces intact, measured on the encoded form and never on the character count")
        void theInvalidKeyMessageIsExactlyFiftyEncodedBytes() {
            final List<Card> cluster = clusterOf(PAGE_SIZE);
            stubPositioningRead(cluster);
            stubAscendingChunks(cluster);

            final CardListService.CardListResult result =
                    service.processCardList(reSubmit("DFHMSRE", null));

            final String emitted = result.errorMessage();
            assertThat(emitted.getBytes(StandardCharsets.US_ASCII))
                    .as("the field is PIC X(50), so the encoded width is the contract")
                    .hasSize(COMMON_MESSAGE_WIDTH);
            assertThat(emitted.getBytes(StandardCharsets.US_ASCII))
                    .isEqualTo(ORACLE_INVALID_KEY_MESSAGE.getBytes(StandardCharsets.US_ASCII));
            assertThat(emitted).isEqualTo(ORACLE_INVALID_KEY_MESSAGE);
            assertThat(emitted).endsWith(" ".repeat(10));
            assertThat(emitted)
                    .as("nothing is trimmed: the trailing spaces are part of what the field transmits")
                    .isNotEqualTo(ORACLE_INVALID_KEY_TEXT)
                    .isNotEqualTo(emitted.stripTrailing());
            assertThat(emitted.stripTrailing()).isEqualTo(ORACLE_INVALID_KEY_TEXT);
        }

        @Test
        @DisplayName("this screen emits its OWN upper-case exit text on the exit key and not the mixed-case "
                + "common thank-you message, so the two message families stay separate")
        void theExitKeyEmitsThisMembersOwnUpperCaseText() {
            final CardListService.CardListResult result =
                    service.processCardList(reSubmit("DFHPF3", null));

            assertThat(result.errorMessage()).isEqualTo(ORACLE_EXIT_MESSAGE);
            assertThat(result.errorMessage())
                    .as("the common catalogue's thank-you is a different message on a different screen")
                    .isNotEqualTo(ORACLE_THANK_YOU_MESSAGE)
                    .isNotEqualTo(ORACLE_THANK_YOU_TEXT);
            assertThat(ORACLE_THANK_YOU_MESSAGE.getBytes(StandardCharsets.US_ASCII))
                    .as("that message is nonetheless fifty encoded bytes: forty-three visible plus seven")
                    .hasSize(COMMON_MESSAGE_WIDTH);
            verify(messageCatalogService, never()).thankYouMessage();
        }

        @ParameterizedTest(name = "\"{0}\" is upper case throughout and reproduced verbatim")
        @ValueSource(strings = {
            "TYPE S FOR DETAIL, U TO UPDATE ANY RECORD",
            "PF03 PRESSED.EXITING",
            "NO RECORDS FOUND FOR THIS SEARCH CONDITION.",
            "PLEASE SELECT ONLY ONE RECORD TO VIEW OR UPDATE",
            "INVALID ACTION CODE",
            "NO PREVIOUS PAGES TO DISPLAY",
            "NO MORE PAGES TO DISPLAY",
            "NO MORE RECORDS TO SHOW",
            "ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER",
            "CARD ID FILTER,IF SUPPLIED MUST BE A 16 DIGIT NUMBER"})
        @DisplayName("this member's whole message family is upper case, unlike the mixed-case common "
                + "catalogue, and every one is pure US-ASCII so its encoded width equals its length")
        void thisMembersMessageFamilyIsUpperCaseThroughout(final String literal) {
            assertThat(literal).isEqualTo(literal.toUpperCase(Locale.ROOT));
            assertThat(literal.getBytes(StandardCharsets.US_ASCII)).hasSize(literal.length());
            assertThat(literal).isEqualTo(literal.strip());
        }

        @Test
        @DisplayName("exactly one message in the family carries a trailing period and it is the no-records "
                + "text, so the one-action refusal must not acquire one by tidying")
        void onlyTheNoRecordsTextCarriesATrailingPeriod() {
            assertThat(ORACLE_NO_RECORDS_FOUND).endsWith(".");
            assertThat(ORACLE_MORE_THAN_ONE_ACTION).doesNotEndWith(".");
            assertThat(ORACLE_INVALID_ACTION_CODE).doesNotEndWith(".");
            assertThat(ORACLE_INFORM_REC_ACTIONS).doesNotEndWith(".");
            assertThat(ORACLE_NO_MORE_RECORDS).doesNotEndWith(".");
            assertThat(ORACLE_NO_MORE_PAGES).doesNotEndWith(".");
            assertThat(ORACLE_NO_PREVIOUS_PAGES).doesNotEndWith(".");
            assertThat(ORACLE_EXIT_MESSAGE)
                    .as("this one carries a full stop mid-sentence instead, which is the source's own")
                    .contains(".").doesNotEndWith(".");
        }

        @Test
        @DisplayName("the composed file-error message is exactly the seventy-five characters of the summary "
                + "field, so the move into it loses nothing and truncates nothing")
        void theComposedFileErrorMessageFillsTheSummaryFieldExactly() {
            final List<Card> cluster = clusterOf(PAGE_SIZE);
            stubPositioningRead(cluster);
            Mockito.when(cardRepository.findByCardNumGreaterThanOrderByCardNumAsc(
                            anyString(), any(Limit.class)))
                    .thenThrow(new DataAccessResourceFailureException("the cluster is unreachable"));

            final CardListService.CardListResult result =
                    service.processCardList(firstEntry("DFHENTER"));

            assertThat(result.errorMessage()).isEqualTo(ORACLE_FILE_ERROR_MESSAGE);
            assertThat(result.errorMessage().getBytes(StandardCharsets.US_ASCII))
                    .hasSize(ERROR_MESSAGE_WIDTH);
            assertThat(result.errorMessage())
                    .contains(ORACLE_OPERATION_READ)
                    .contains(CARD_BASE_CLUSTER)
                    .contains(ORACLE_STATUS_PERMANENT_ERROR);
        }
    }

    // ==============================================================================================

    @Nested
    @DisplayName("the paging state the turn leaves is consistent with the page it presents")
    final class PageMetadataFidelity {

        /** Creates the nest. */
        PageMetadataFidelity() {
        }

        @Test
        @DisplayName("a first forward page reports its direction, its own page size, no preceding page, and "
                + "both boundary keys taken from the rows it presented")
        void aFirstForwardPageReportsItselfConsistently() {
            final List<Card> cluster = clusterOf(PAGE_SIZE * 2);
            stubPositioningRead(cluster);
            stubAscendingChunks(cluster);

            final CardListService.CardListResult result =
                    service.processCardList(firstEntry("DFHENTER"));

            final BrowseWindow window = result.pageMetadata();
            assertThat(window.direction()).isEqualTo(BrowseWindow.PagingDirection.FORWARD);
            assertThat(window.pageSize()).isEqualTo(PAGE_SIZE);
            assertThat(window.hasPreviousPages()).isFalse();
            assertThat(window.displayedPageNumber()).isEqualTo("1");
            assertThat(window.previousCursorKey())
                    .as("the retained first key is the first row the page accepted")
                    .isEqualTo(cardNumber(1))
                    .isEqualTo(result.rows().get(0).cardNumber());
            assertThat(window.hasMorePages()).isTrue();
        }

        @Test
        @DisplayName("a LOOK-AHEAD raises the more-pages indicator only when a further row genuinely "
                + "exists, and the retained key is that row rather than the last one presented")
        void theMorePagesIndicatorFollowsTheLookAhead() {
            final List<Card> cluster = clusterOf(PAGE_SIZE + 1);
            stubPositioningRead(cluster);
            stubAscendingChunks(cluster);

            final CardListService.CardListResult result =
                    service.processCardList(firstEntry("DFHENTER"));

            assertThat(result.rows()).hasSize(PAGE_SIZE);
            assertThat(result.pageMetadata().hasMorePages())
                    .as("an eighth row exists, so a further page is promised")
                    .isTrue();
            assertThat(result.pageMetadata().nextCursorKey())
                    .as("and the cursor is that eighth row, not the seventh the page showed")
                    .isEqualTo(cardNumber(PAGE_SIZE + 1))
                    .isNotEqualTo(result.rows().get(PAGE_SIZE - 1).cardNumber());
        }

        @Test
        @DisplayName("A PARTIAL FINAL PAGE reports no further page, and says the cluster is exhausted "
                + "without claiming the search found nothing")
        void aPartialFinalPageReportsNoFurtherPage() {
            final List<Card> cluster = clusterOf(3);
            stubPositioningRead(cluster);
            stubAscendingChunks(cluster);

            final CardListService.CardListResult result =
                    service.processCardList(firstEntry("DFHENTER"));

            assertThat(result.rows()).hasSize(3);
            assertThat(result.pageMetadata().hasMorePages()).isFalse();
            assertThat(result.pageMetadata().hasPreviousPages()).isFalse();
            assertThat(result.errorMessage()).isEqualTo(ORACLE_NO_MORE_RECORDS);
            assertThat(result.errorMessage()).isNotEqualTo(ORACLE_NO_RECORDS_FOUND);
            assertThat(result.errorFlag())
                    .as("reaching the end of the data is not an input error")
                    .isFalse();
            assertThat(result.infoMessage())
                    .as("the advisory still stands, because rows were found")
                    .isEqualTo(ORACLE_INFORM_REC_ACTIONS);
        }

        @Test
        @DisplayName("a full FINAL page, exactly the width of the screen, also reports no further page")
        void aFullFinalPageAlsoReportsNoFurtherPage() {
            final List<Card> cluster = clusterOf(PAGE_SIZE);
            stubPositioningRead(cluster);
            stubAscendingChunks(cluster);

            final CardListService.CardListResult result =
                    service.processCardList(firstEntry("DFHENTER"));

            assertThat(result.rows()).hasSize(PAGE_SIZE);
            assertThat(result.pageMetadata().hasMorePages())
                    .as("the look-ahead found nothing beyond the seventh row")
                    .isFalse();
            assertThat(result.errorMessage()).isEqualTo(ORACLE_NO_MORE_RECORDS);
            assertThat(result.lastPageAlreadyShown())
                    .as("the operator has not yet been told they are at the end, so a first forward press "
                            + "will show the advisory rather than the no-more-pages text")
                    .isFalse();
        }

        @Test
        @DisplayName("the last-page memory is raised the FIRST time the forward key finds nothing beyond, "
                + "so a second press can say something different from the first")
        void theLastPageMemoryIsRaisedOnFirstArrivalAtTheEnd() {
            final List<Card> cluster = clusterOf(PAGE_SIZE);
            stubPositioningRead(cluster);
            stubAscendingChunks(cluster);

            final CardListService.CardListScreenInput input =
                    new CardListService.CardListScreenInput("DFHPF8", workArea(null, null), null,
                            new BrowseWindow.CursorRequest(cardNumber(1), cardNumber(PAGE_SIZE),
                                    BrowseWindow.PagingDirection.FORWARD),
                            1, false, false, reSubmission());

            final CardListService.CardListResult result = service.processCardList(input);

            assertThat(result.infoMessage())
                    .as("a first arrival at the end shows the advisory")
                    .isEqualTo(ORACLE_INFORM_REC_ACTIONS);
            assertThat(result.lastPageAlreadyShown())
                    .as("and records that the end has now been shown, which nothing else in the reported "
                            + "state could express")
                    .isTrue();
            assertThat(result.errorMessage()).isNotEqualTo(ORACLE_NO_MORE_PAGES);
        }

        @Test
        @DisplayName("an empty cluster still reports a positional page: seven cleared flags, a page size of "
                + "seven and no page in either direction")
        void anEmptyClusterStillReportsAPositionalPage() {
            stubEmptyCluster();

            final CardListService.CardListResult result =
                    service.processCardList(firstEntry("DFHENTER"));

            assertThat(result.pageMetadata().pageSize()).isEqualTo(PAGE_SIZE);
            assertThat(result.pageMetadata().hasMorePages()).isFalse();
            assertThat(result.pageMetadata().hasPreviousPages()).isFalse();
            assertThat(result.selectionErrorFlags()).hasSize(PAGE_SIZE).containsOnly(Boolean.FALSE);
        }

        @Test
        @DisplayName("the three list screens in the estate keep their own page sizes, so this one's seven "
                + "is never taken from the ten the other two carry")
        void thisScreensPageSizeIsItsOwn() {
            stubEmptyCluster();

            final CardListService.CardListResult result =
                    service.processCardList(firstEntry("DFHENTER"));

            assertThat(result.pageMetadata().pageSize())
                    .as("seven rows, from this member's own seven-occurrence row table")
                    .isEqualTo(PAGE_SIZE)
                    .isNotEqualTo(10);
        }
    }

    // ==============================================================================================

    @Nested
    @DisplayName("an unclassified browse condition is logged BEFORE it is delegated, and nothing abends")
    final class AbendDiagnostic {

        /** Creates the nest. */
        AbendDiagnostic() {
        }

        /** The fragment the unclassified-condition diagnostic carries, which the ordering proof looks for. */
        private static final String DIAGNOSTIC_FRAGMENT =
                "Card list browse reported an unclassified condition";

        /**
         * Makes the range read fail the way a data-store outage would, which is the analogue of the
         * source's unclassified response condition.
         */
        private void stubAFailingRangeRead() {
            stubPositioningRead(clusterOf(PAGE_SIZE));
            Mockito.when(cardRepository.findByCardNumGreaterThanOrderByCardNumAsc(
                            anyString(), any(Limit.class)))
                    .thenThrow(new DataAccessResourceFailureException("the cluster is unreachable"));
        }

        @Test
        @DisplayName("THE ORDERING: the diagnostic is already in the log at the moment the abend service is "
                + "called, which is what emit-then-delegate means")
        void theDiagnosticIsAlreadyLoggedWhenTheAbendServiceIsCalled() {
            stubAFailingRangeRead();
            final List<String> logAsItStoodAtDelegation = new ArrayList<>();
            Mockito.doAnswer(invocation -> {
                logAsItStoodAtDelegation.addAll(recordedMessages());
                return null;
            }).when(abendService).displayIoStatus(anyString(), anyString(), anyString());

            service.processCardList(firstEntry("DFHENTER"));

            assertThat(logAsItStoodAtDelegation)
                    .as("captured inside the delegated call itself, so this is the state of the log "
                            + "strictly before the delegation completed - not merely afterwards")
                    .isNotEmpty()
                    .anySatisfy(message -> assertThat(message).contains(DIAGNOSTIC_FRAGMENT));
        }

        @Test
        @DisplayName("THE ORDERING, corroborated: the read precedes the delegation, and the raw status, the "
                + "operation and the resource all reach the diagnostic")
        void theReadPrecedesTheDelegationAndCarriesTheRightCode() {
            stubAFailingRangeRead();

            service.processCardList(firstEntry("DFHENTER"));

            final InOrder ordered = inOrder(cardRepository, abendService);
            ordered.verify(cardRepository)
                    .findByCardNumGreaterThanOrderByCardNumAsc(anyString(), any(Limit.class));
            ordered.verify(abendService).displayIoStatus(eq(ORACLE_STATUS_PERMANENT_ERROR),
                    eq(ORACLE_OPERATION_READ), eq(CARD_BASE_CLUSTER));
            ordered.verifyNoMoreInteractions();
        }

        @Test
        @DisplayName("the two-character status the unclassified arm carries is the estate's own permanent "
                + "error code, and it reaches the log as well as the diagnostic entry point")
        void theRawStatusReachesBothTheLogAndTheDiagnostic() {
            stubAFailingRangeRead();

            service.processCardList(firstEntry("DFHENTER"));

            verify(abendService).displayIoStatus(ORACLE_STATUS_PERMANENT_ERROR, ORACLE_OPERATION_READ,
                    CARD_BASE_CLUSTER);
            assertThat(recordedMessages()).anySatisfy(message -> assertThat(message)
                    .contains(DIAGNOSTIC_FRAGMENT)
                    .contains("fileStatus=" + ORACLE_STATUS_PERMANENT_ERROR)
                    .contains("operation=" + ORACLE_OPERATION_READ)
                    .contains("resource=" + CARD_BASE_CLUSTER)
                    .contains("alternateIndexResource=" + CARD_ACCOUNT_PATH));
        }

        @Test
        @DisplayName("NOTHING ABENDS: the diagnostic entry point is the only interaction, because a census "
                + "of the member finds no abend statement on any path")
        void nothingAbendsOnAFailedBrowse() {
            stubAFailingRangeRead();

            final CardListService.CardListResult result =
                    service.processCardList(firstEntry("DFHENTER"));

            verify(abendService).displayIoStatus(anyString(), anyString(), anyString());
            verifyNoMoreInteractions(abendService);
            verifyNoAbendWasRaised();
            assertThat(result.errorMessage())
                    .as("the condition is reported on the screen and the turn completes normally")
                    .isEqualTo(ORACLE_FILE_ERROR_MESSAGE);
            assertThat(result.route()).isEqualTo(NavigationService.Route.CARD_LIST);
        }

        @Test
        @DisplayName("a turn that completes cleanly never touches the abend service at all")
        void aCleanTurnNeverTouchesTheAbendService() {
            final List<Card> cluster = clusterOf(PAGE_SIZE);
            stubPositioningRead(cluster);
            stubAscendingChunks(cluster);

            service.processCardList(firstEntry("DFHENTER"));

            verify(abendService, never()).displayIoStatus(anyString(), anyString(), anyString());
            verifyNoMoreInteractions(abendService);
        }

        @Test
        @DisplayName("the screen still records that it was assembled, so a diagnostic reader can see the "
                + "turn complete and how many rows it settled on")
        void theAssembledScreenIsStillRecorded() {
            final List<Card> cluster = clusterOf(3);
            stubPositioningRead(cluster);
            stubAscendingChunks(cluster);

            service.processCardList(firstEntry("DFHENTER"));

            assertThat(recordedMessages()).anySatisfy(message -> assertThat(message)
                    .contains("Card list screen assembled")
                    .contains("rows=3")
                    .contains("pageSize=" + PAGE_SIZE));
        }
    }

    // ==============================================================================================

    @Nested
    @DisplayName("field-level fidelity: the verification value, the identifiers and the alphabetic idiom")
    final class FieldFidelity {

        /** Creates the nest. */
        FieldFidelity() {
        }

        @Test
        @DisplayName("THE VERIFICATION VALUE stays the three-character string it is, leading zero and all, "
                + "because read as a number it would become a single digit")
        void theVerificationValueStaysAThreeCharacterString() {
            final Card row = cardRow(1, ACCOUNT_A);

            assertThat(row.getCardCvvCd())
                    .isEqualTo(CARD_VERIFICATION_CODE)
                    .hasSize(3)
                    .isEqualTo("007");
            assertThat(row.getCardCvvCd().getBytes(StandardCharsets.US_ASCII)).hasSize(3);
            assertThat(row.getCardCvvCd())
                    .as("the leading zero is significant: the numeric reading would be 7 and the record "
                            + "image would no longer be reproducible")
                    .isNotEqualTo(String.valueOf(7));
        }

        @Test
        @DisplayName("THE VERIFICATION VALUE reaches no log event and no reported row, because it is not one "
                + "of the three fields the browse stores")
        void theVerificationValueReachesNoLogAndNoRow() {
            final List<Card> cluster = clusterOf(PAGE_SIZE);
            stubPositioningRead(cluster);
            stubAscendingChunks(cluster);

            final CardListService.CardListResult result =
                    service.processCardList(firstEntry("DFHENTER"));

            assertThat(recordedMessages())
                    .as("not one recorded event names the verification value")
                    .noneSatisfy(message ->
                            assertThat(message).contains(CARD_VERIFICATION_CODE));
            assertThat(result.rows()).isNotEmpty();
            for (final CardListService.CardListRow row : result.rows()) {
                assertThat(row.accountId()).doesNotContain(CARD_VERIFICATION_CODE);
                assertThat(row.cardNumber()).doesNotContain(CARD_VERIFICATION_CODE);
                assertThat(row.cardActiveStatus()).doesNotContain(CARD_VERIFICATION_CODE);
                assertThat(row.selection()).doesNotContain(CARD_VERIFICATION_CODE);
                assertThat(row.toString()).doesNotContain(CARD_VERIFICATION_CODE);
            }
        }

        @Test
        @DisplayName("both business keys are withheld from every diagnostic rendering, replaced by a fixed "
                + "stand-in rather than by a partial mask - a truncated card number is still cardholder data")
        void bothBusinessKeysAreWithheldFromDiagnosticRenderings() {
            final List<Card> cluster = clusterOf(PAGE_SIZE);
            stubPositioningRead(cluster);
            stubAscendingChunks(cluster);

            final CardListService.CardListResult result =
                    service.processCardList(firstEntry("DFHENTER"));

            final String renderedRow = result.rows().get(0).toString();
            assertThat(renderedRow).contains(REDACTION_PLACEHOLDER)
                    .doesNotContain(cardNumber(1))
                    .doesNotContain(ACCOUNT_A);
            assertThat(result.pageMetadata().toString()).contains(REDACTION_PLACEHOLDER)
                    .doesNotContain(cardNumber(1));
            assertThat(recordedMessages()).noneSatisfy(message ->
                    assertThat(message).contains(cardNumber(1)));
            assertThat(result.pageMetadata().previousCursorKey())
                    .as("the accessor still carries the key byte for byte, because the browse cannot be "
                            + "resumed without it - only the rendering withholds it")
                    .isEqualTo(cardNumber(1));
        }

        @Test
        @DisplayName("the two identifiers cross byte for byte at their declared widths, neither trimmed nor "
                + "re-padded nor re-cased, so nothing on this screen is normalised or scaled")
        void theIdentifiersCrossByteForByteAtTheirDeclaredWidths() {
            final List<Card> cluster = clusterOf(1);
            stubPositioningRead(cluster);
            stubAscendingChunks(cluster);

            final CardListService.CardListResult result =
                    service.processCardList(firstEntry("DFHENTER"));

            final CardListService.CardListRow row = result.rows().get(0);
            assertThat(row.accountId()).isEqualTo(ACCOUNT_A);
            assertThat(row.accountId().getBytes(StandardCharsets.US_ASCII))
                    .hasSize(ACCOUNT_IDENTIFIER_WIDTH);
            assertThat(row.cardNumber()).isEqualTo(cardNumber(1));
            assertThat(row.cardNumber().getBytes(StandardCharsets.US_ASCII)).hasSize(CARD_NUMBER_WIDTH);
            assertThat(row.cardActiveStatus().getBytes(StandardCharsets.US_ASCII)).hasSize(1);
        }

        @Test
        @DisplayName("NO VALUE HERE IS SCALED: the card record declares no monetary field, so the estate's "
                + "truncating scale rule has no site on this screen and none is introduced")
        void noValueOnThisScreenIsScaledOrRounded() {
            final List<Card> cluster = clusterOf(1);
            stubPositioningRead(cluster);
            stubAscendingChunks(cluster);

            final CardListService.CardListResult result =
                    service.processCardList(firstEntry("DFHENTER"));

            final CardListService.CardListRow row = result.rows().get(0);
            assertThat(row.accountId()).isEqualTo(ACCOUNT_A);
            assertThat(row.cardNumber()).isEqualTo(cardNumber(1));
            assertThat(row.cardActiveStatus()).isEqualTo(STATUS_ACTIVE);
            assertThat(row.selection()).isEqualTo(SELECT_NONE);
            assertThat(row.screenSlot()).isOne();

            // The estate's persisted amounts are zoned decimal at two decimal places and carry no ROUNDED
            // clause anywhere, so a store into such a field truncates - and the codec that performs that
            // store is the only place in the module allowed to. The claim this test makes is that THIS
            // screen offers no site for the operation at all, which is a statement about the types the
            // screen path declares rather than about arithmetic. It is therefore asserted over those
            // types: neither the row the screen returns, nor the result that carries it, nor the 150-byte
            // record they are built from declares a decimal field of any kind.
            assertThat(CardListService.CardListRow.class.getRecordComponents())
                    .as("the row the screen returns declares no decimal component, so there is nothing "
                            + "on it for a scale or a rounding policy to act on")
                    .isNotEmpty()
                    .noneMatch(component -> DECIMAL_TYPES.contains(component.getType()));
            assertThat(CardListService.CardListResult.class.getRecordComponents())
                    .as("and neither does the result that carries the rows")
                    .isNotEmpty()
                    .noneMatch(component -> DECIMAL_TYPES.contains(component.getType()));
            assertThat(Arrays.stream(Card.class.getDeclaredFields())
                            .filter(field -> !field.isSynthetic())
                            .map(Field::getType)
                            .toList())
                    .as("the 150-byte card layout itself declares no monetary field: the estate's five "
                            + "PIC S9(10)V99 balances belong to the 300-byte account layout, which this "
                            + "screen never reads")
                    .isNotEmpty()
                    .doesNotContainAnyElementsOf(DECIMAL_TYPES);
        }

        @Test
        @DisplayName("AN EMBEDDED SPACE PASSES the estate's alphabetic idiom, so a cardholder name with a "
                + "space in it is accepted - a letters-only predicate would reject data already held")
        void anEmbeddedSpacePassesTheAlphabeticIdiom() {
            assertThat(CobolStringUtils.isAlphaOrSpace(EMBOSSED_NAME_WITH_SPACE))
                    .as("the legacy idiom blanks every letter and measures what is left, so a space "
                            + "survives the blanking and the value passes")
                    .isTrue();

            // Written out as the trap it guards against, rather than delegated: the naive predicate below
            // is this test's own, so the assertion states what a letters-only rule would have decided
            // instead of asking a production class what it decides.
            boolean everyCharacterIsALetter = true;
            boolean everyCharacterIsALetterOrSpace = true;
            for (int position = 0; position < EMBOSSED_NAME_WITH_SPACE.length(); position++) {
                final char character = EMBOSSED_NAME_WITH_SPACE.charAt(position);
                final boolean letter = character >= 'A' && character <= 'Z'
                        || character >= 'a' && character <= 'z';
                everyCharacterIsALetter = everyCharacterIsALetter && letter;
                everyCharacterIsALetterOrSpace =
                        everyCharacterIsALetterOrSpace && (letter || character == ' ');
            }
            assertThat(everyCharacterIsALetter)
                    .as("a letters-only rule would reject this name")
                    .isFalse();
            assertThat(everyCharacterIsALetterOrSpace)
                    .as("while the estate's rule accepts it")
                    .isTrue();
        }

        @Test
        @DisplayName("a card whose embossed name carries an embedded space browses onto the page unchanged, "
                + "because this screen applies no alphabetic edit to it at all")
        void aNameWithAnEmbeddedSpaceBrowsesOntoThePage() {
            final List<Card> cluster = clusterOf(PAGE_SIZE);
            stubPositioningRead(cluster);
            stubAscendingChunks(cluster);

            final CardListService.CardListResult result =
                    service.processCardList(firstEntry("DFHENTER"));

            assertThat(result.errorFlag()).isFalse();
            assertThat(result.rows()).hasSize(PAGE_SIZE);
            assertThat(cluster.get(0).getCardEmbossedName())
                    .as("the name is held as keyed, with its embedded space intact and no case folding")
                    .isEqualTo(EMBOSSED_NAME_WITH_SPACE);
            assertThat(result.rows())
                    .as("and the name is not one of the three fields this screen presents, so it reaches "
                            + "no row")
                    .allSatisfy(row -> assertThat(row.cardNumber()).doesNotContain(
                            EMBOSSED_NAME_WITH_SPACE));
        }

        @Test
        @DisplayName("the echoed selection is returned on its own row, so the operator sees back what they "
                + "typed even when the page was refused")
        void theEchoedSelectionIsReturnedOnItsOwnRow() {
            final List<Card> cluster = clusterOf(PAGE_SIZE);
            stubPositioningRead(cluster);
            stubAscendingChunks(cluster);

            final CardListService.CardListResult result = service.processCardList(
                    reSubmit("DFHENTER", selectionsAt(SELECT_VIEW, 2, 6)));

            assertThat(result.rows()).hasSize(PAGE_SIZE);
            assertThat(result.rows().get(1).selection()).isEqualTo(SELECT_VIEW);
            assertThat(result.rows().get(5).selection()).isEqualTo(SELECT_VIEW);
            assertThat(result.rows().get(0).selection()).isEqualTo(SELECT_NONE);
            assertThat(result.rows().get(1).requestedAction())
                    .contains(CardListService.SelectionAction.VIEW);
            assertThat(result.rows().get(0).requestedAction()).isEmpty();
        }
    }

    // ==============================================================================================

    @Nested
    @DisplayName("absent, blank and out-of-range input is answered rather than allowed to escape")
    final class NullAndBoundaryInput {

        /** Creates the nest. */
        NullAndBoundaryInput() {
        }

        @Test
        @DisplayName("an absent work area is read as an unfiltered screen with no retained action, and the "
                + "turn completes without an escaping failure")
        void anAbsentWorkAreaIsReadAsAnUnfilteredScreen() {
            final List<Card> cluster = clusterOf(PAGE_SIZE);
            stubPositioningRead(cluster);
            stubAscendingChunks(cluster);

            final CardListService.CardListScreenInput input =
                    new CardListService.CardListScreenInput("DFHENTER", null, null, null, 1, false,
                            false, reSubmission());

            final CardListService.CardListResult result = service.processCardList(input);

            assertThat(result.rows()).hasSize(PAGE_SIZE);
            assertThat(result.errorFlag()).isFalse();
        }

        @ParameterizedTest(name = "an account filter of [{0}] is read as not supplied")
        @ValueSource(strings = {"", " ", "           "})
        @DisplayName("a blank account filter is read as not supplied rather than as a malformed one, so a "
                + "screen the operator has not typed into is not an error")
        void aBlankAccountFilterIsNotSupplied(final String keyed) {
            final List<Card> cluster = clusterOf(2);
            stubPositioningRead(cluster);
            stubAscendingChunks(cluster);

            final CardListService.CardListResult result =
                    service.processCardList(reSubmitFiltered("DFHENTER", keyed, null));

            assertThat(result.errorFlag()).isFalse();
            assertThat(result.errorMessage()).isNotEqualTo(ORACLE_ACCOUNT_FILTER_FORMAT);
            assertThat(result.rows()).hasSize(2);
        }

        @Test
        @DisplayName("an absent account filter is read the same way, and the navigation state's account "
                + "identifier is cleared rather than left holding a stale value")
        void anAbsentAccountFilterClearsTheCarriedIdentifier() {
            final List<Card> cluster = clusterOf(2);
            stubPositioningRead(cluster);
            stubAscendingChunks(cluster);

            final CardListService.CardListResult result =
                    service.processCardList(reSubmitFiltered("DFHENTER", null, null));

            assertThat(result.errorFlag()).isFalse();
            assertThat(result.navigationContext().accountId()).isEmpty();
            assertThat(result.navigationContext().cardNumber()).isEmpty();
        }

        @Test
        @DisplayName("a page indicator of zero is raised to one by the first accepted row, which is the "
                + "source's own initialisation and not a guard added here")
        void aZeroPageIndicatorIsRaisedToOne() {
            final List<Card> cluster = clusterOf(3);
            stubPositioningRead(cluster);
            stubAscendingChunks(cluster);

            final CardListService.CardListScreenInput input =
                    new CardListService.CardListScreenInput("DFHENTER", workArea(null, null), null, null,
                            0, false, false, reSubmission());

            final CardListService.CardListResult result = service.processCardList(input);

            assertThat(result.pageMetadata().displayedPageNumber()).isEqualTo("1");
            assertThat(result.rows()).hasSize(3);
        }

        @Test
        @DisplayName("a negative page indicator is carried as given rather than corrected, because the source "
                + "corrects only the zero its own initialisation leaves - and the turn still completes")
        void aNegativePageIndicatorStillCompletesTheTurn() {
            final List<Card> cluster = clusterOf(3);
            stubPositioningRead(cluster);
            stubAscendingChunks(cluster);

            final CardListService.CardListScreenInput input =
                    new CardListService.CardListScreenInput("DFHENTER", workArea(null, null), null, null,
                            -1, false, false, reSubmission());

            final CardListService.CardListResult result = service.processCardList(input);

            assertThat(result.rows()).hasSize(3);
            assertThat(result.errorFlag()).isFalse();
            assertThat(result.pageMetadata().displayedPageNumber()).isEqualTo("-1");
            assertThat(result.pageMetadata().hasPreviousPages())
                    .as("not standing on page one, so a preceding page is reported - the source's own test")
                    .isTrue();
        }

        @Test
        @DisplayName("an absent boundary cursor is read as the source's own initial value, which a "
                + "greater-or-equal start resolves to the first row of the cluster")
        void anAbsentBoundaryCursorStartsAtTheFirstRow() {
            final List<Card> cluster = clusterOf(3);
            stubPositioningRead(cluster);
            stubAscendingChunks(cluster);

            final CardListService.CardListResult result =
                    service.processCardList(reSubmit("DFHENTER", null));

            assertThat(presentedCardNumbers(result))
                    .containsExactly(cardNumber(1), cardNumber(2), cardNumber(3));
            verify(cardRepository).findById(SELECT_NONE);
        }

        @Test
        @DisplayName("a selection list holding nulls is read as untransmitted fields rather than being "
                + "allowed to reach the edits as absent references")
        void aSelectionListHoldingNullsIsReadAsUntransmitted() {
            final List<Card> cluster = clusterOf(PAGE_SIZE);
            stubPositioningRead(cluster);
            stubAscendingChunks(cluster);

            final List<String> withNulls = new ArrayList<>(Collections.nCopies(PAGE_SIZE, null));
            withNulls.set(2, SELECT_VIEW);
            final CardListService.CardListScreenInput input =
                    new CardListService.CardListScreenInput("DFHPF8", workArea(null, null), withNulls,
                            null, 1, false, false, reSubmission());

            assertThat(input.selections()).doesNotContainNull();
            assertThat(input.selectionAt(1)).isEqualTo(SELECT_NONE);
            assertThat(input.selectionAt(3)).isEqualTo(SELECT_VIEW);

            final CardListService.CardListResult result = service.processCardList(input);

            assertThat(result.rows()).hasSize(PAGE_SIZE);
            assertThat(result.errorFlag()).isFalse();
        }

        @Test
        @DisplayName("a card record carrying absent fields still browses onto the page, with each absent "
                + "field reported as an empty screen field rather than as a null")
        void aRecordWithAbsentFieldsStillBrowsesOntoThePage() {
            final Card sparse = new Card(cardNumber(1), null, null, null, null, null);
            final List<Card> cluster = List.of(sparse);
            stubPositioningRead(cluster);
            stubAscendingChunks(cluster);

            final CardListService.CardListResult result =
                    service.processCardList(firstEntry("DFHENTER"));

            assertThat(result.rows()).hasSize(1);
            final CardListService.CardListRow row = result.rows().get(0);
            assertThat(row.cardNumber()).isEqualTo(cardNumber(1));
            assertThat(row.accountId()).isEmpty();
            assertThat(row.cardActiveStatus()).isEmpty();
            assertThat(row.resolvedStatus()).isEmpty();
        }
    }

    // ==============================================================================================

    /**
     * The two diagnostic senders, which no delivered call site reaches, called here by name.
     *
     * <p>Four matrix rows are discharged here, and none of them can be discharged by driving the screen
     * turn: {@code SEND-PLAIN-TEXT} at source line 1422 and {@code SEND-LONG-TEXT} at 1441, through the
     * named calls to {@code sendPlainText} and {@code sendLongText} below, and their paired terminators
     * {@code SEND-PLAIN-TEXT-EXIT} at 1433 and {@code SEND-LONG-TEXT-EXIT} at 1452, through
     * {@code sendPlainTextExit} and {@code sendLongTextExit}, which each head calls on its only arm.
     *
     * <p>The two exits need no call of their own, and fabricating one would prove nothing: each is the
     * {@code EXIT.} statement of its range and each is reachable only from the head above it, so an
     * invocation would be this suite calling a method it had chosen to expose rather than evidence about
     * the delivered code. Naming them is what a reader following those two rows needs.
     *
     * <p>Nothing reflective is used: both heads are public on the service because the member's own
     * comments mark them as diagnostic entry points, and the production tree is held to a reflection
     * count of zero.
     */
    @Nested
    @DisplayName("the two unreachable diagnostic senders render at their declared widths")
    final class DiagnosticSenders {

        /** Creates the nest. */
        DiagnosticSenders() {
        }

        @Test
        @DisplayName("the plain-text sender renders at the seventy-five characters of the summary field, "
                + "space padded on the right and never trimmed")
        void thePlainTextSenderRendersAtSeventyFiveBytes() {
            final String rendered = service.sendPlainText(ORACLE_INVALID_ACTION_CODE);

            assertThat(rendered.getBytes(StandardCharsets.US_ASCII)).hasSize(ERROR_MESSAGE_WIDTH);
            assertThat(rendered).startsWith(ORACLE_INVALID_ACTION_CODE);
            assertThat(rendered).isEqualTo(ORACLE_INVALID_ACTION_CODE
                    + " ".repeat(ERROR_MESSAGE_WIDTH - ORACLE_INVALID_ACTION_CODE.length()));
            assertThat(rendered)
                    .as("a move into a fixed field pads on the right, and trimming here would change a "
                            + "transmitted record's width")
                    .isNotEqualTo(ORACLE_INVALID_ACTION_CODE);
        }

        @Test
        @DisplayName("the long-text sender renders at the five hundred characters of the long field, on the "
                + "same terms and at its own width")
        void theLongTextSenderRendersAtFiveHundredBytes() {
            final String rendered = service.sendLongText(ORACLE_MORE_THAN_ONE_ACTION);

            assertThat(rendered.getBytes(StandardCharsets.US_ASCII)).hasSize(LONG_MESSAGE_WIDTH);
            assertThat(rendered).startsWith(ORACLE_MORE_THAN_ONE_ACTION);
            assertThat(rendered).isEqualTo(ORACLE_MORE_THAN_ONE_ACTION
                    + " ".repeat(LONG_MESSAGE_WIDTH - ORACLE_MORE_THAN_ONE_ACTION.length()));
            assertThat(rendered.getBytes(StandardCharsets.US_ASCII).length)
                    .as("the long diagnostic field is wider than the summary field")
                    .isGreaterThan(ERROR_MESSAGE_WIDTH);
        }

        @Test
        @DisplayName("an absent message renders as an all-space field at each sender's own width rather "
                + "than as an empty string, because that is what a move from an empty field leaves")
        void anAbsentMessageRendersAsAnAllSpaceField() {
            final String plain = service.sendPlainText(null);
            final String longText = service.sendLongText(null);

            assertThat(plain).isEqualTo(" ".repeat(ERROR_MESSAGE_WIDTH));
            assertThat(plain.getBytes(StandardCharsets.US_ASCII)).hasSize(ERROR_MESSAGE_WIDTH);
            assertThat(longText).isEqualTo(" ".repeat(LONG_MESSAGE_WIDTH));
            assertThat(longText.getBytes(StandardCharsets.US_ASCII)).hasSize(LONG_MESSAGE_WIDTH);
        }

        @Test
        @DisplayName("a message wider than the receiving field is truncated on the right, which is what a "
                + "move into a narrower alphanumeric field does")
        void anOverWideMessageIsTruncatedOnTheRight() {
            final String tooWide = "X".repeat(ERROR_MESSAGE_WIDTH + 25);

            final String rendered = service.sendPlainText(tooWide);

            assertThat(rendered.getBytes(StandardCharsets.US_ASCII)).hasSize(ERROR_MESSAGE_WIDTH);
            assertThat(rendered).isEqualTo("X".repeat(ERROR_MESSAGE_WIDTH));
        }

        @Test
        @DisplayName("neither sender touches a collaborator, because neither is on any path the screen turn "
                + "takes - both are translated so the paragraph mapping stays complete")
        void neitherSenderTouchesACollaborator() {
            service.sendPlainText(ORACLE_EXIT_MESSAGE);
            service.sendLongText(ORACLE_EXIT_MESSAGE);

            verifyNoInteractionsWithTheCluster();
            verifyNoMoreInteractions(abendService);
        }
    }

    @Nested
    @DisplayName("paragraph traceability: the 39 units this member contributes to the matrix")
    class ParagraphTraceability {

        /** Creates the nested specification. */
        ParagraphTraceability() {
            // Intentionally empty.
        }

        @Test
        @DisplayName("the unit count is the 39 the published matrix carries, read from the matrix, and "
                + "the attention-key copybook's two paragraphs are counted in its own section")
        void theUnitCountIsTheOneTheMatrixCarries() {
            // Read from the matrix - its census subtotal, its section declaration and its rows, which the
            // reader requires to agree - rather than restated as a constant here. The heading of this file
            // once called all 42 lines of its coverage map units of this member, which counted a copy
            // directive and a shared copybook's two paragraphs a second time.
            assertAll(
                    () -> assertThat(TraceabilityMatrixCensus.unitsOf("COCRDLIC.cbl"))
                            .as("the member's own procedure-division labels, and nothing else")
                            .isEqualTo(39),
                    () -> assertThat(TraceabilityMatrixCensus.unitsOf("CSSTRPFY.cpy"))
                            .as("the copybook is included by five members and contributes two units in "
                                    + "total, not two per includer")
                            .isEqualTo(2));
        }
    }
}
