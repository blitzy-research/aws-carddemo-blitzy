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

import com.carddemo.domain.Transaction;
import com.carddemo.domain.enums.KeyAction;
import com.carddemo.exception.ValidationException;
import com.carddemo.repository.TransactionScanRepository;
import com.carddemo.util.FailureDiagnostics;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;

/**
 * Transaction-list screen behaviour: the Java translation of legacy CICS transaction {@code CT00},
 * whose sole implementing member is {@code app/cbl/COTRN00C.cbl}. Each of its
 * {@code PROCEDURE DIVISION} paragraphs has a named method on this class and states its own paragraph
 * name and source line; {@code docs/traceability-matrix.md} carries the row-per-paragraph inventory.
 *
 * <p>Provenance. The legacy estate is read-only reference at commit SHA
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. No legacy source text is transcribed here;
 * behaviour is cited by member name, paragraph name and line number so that a reviewer can hold the two
 * side by side.
 *
 * <h2>The two facts that decide whether this translation is correct</h2>
 *
 * <p><strong>The page is ten rows, and the figure comes from loop bounds rather than from a
 * table.</strong> Uniquely among the estate's three paginated screens, this member declares no
 * occurrence table for its screen rows at all. The forward paging paragraph blanks ten slots with a
 * loop varying an index from one until it exceeds ten at lines 290-292, resets the index to one at
 * line 295, and fills while the index has not reached eleven at lines 297-303. See
 * {@link #SCREEN_ROW_COUNT}, which records why that ten is a behavioural contract and not a tuning
 * knob.
 *
 * <p><strong>A backward page is read descending and assembled ascending.</strong> The backward
 * paragraph at line 333 blanks the same ten slots at lines 344-346, initialises the index to
 * <em>ten</em> at line 349, and then reads backward at line 352 and <em>decrements</em> at lines
 * 351-357 - so the highest key read lands in slot ten and the lowest in slot one. The rows are
 * therefore read in descending key order and presented in ascending key order, exactly as a forward
 * page is. This class reproduces that by querying with a descending sort and assembling the response
 * in ascending slot order, which makes the response list the reverse of the read order. Returning
 * the descending read order would present a backward page upside down relative to the legacy screen;
 * reading ascending from an offset computed out of the page number would drift the moment a row is
 * inserted, because the legacy repositions on a retained record key and never on a row number.
 *
 * <h2>How the browse verbs are reproduced</h2>
 *
 * <p>The legacy walks the transaction cluster with four browse commands, one paragraph each:
 * {@code STARTBR} at line 591, {@code READNEXT} at line 624, {@code READPREV} at line 658 and
 * {@code ENDBR} at line 692. Each paragraph evaluates the response code and branches three ways -
 * normal, an end-of-data condition, and anything else - and those three arms are reproduced in
 * clause order in the corresponding method here.
 *
 * <p>Positioning is cursor-driven. {@code STARTBR} is issued without the explicit greater-or-equal
 * option at line 597, which is the CICS default, so it positions on the lowest key not less than the
 * record identification field. A read issued immediately afterwards returns that positioned record
 * itself, which is why the two guards matter: the forward guard at lines 285-287 consumes the
 * positioned record when the attention key is neither enter, nor the seventh, nor the third program
 * function key, and the backward guard at lines 339-341 does the same when it is neither enter nor
 * the eighth. In practice the eighth key discards the last row of the page just displayed, the
 * seventh discards the first, and the enter key discards nothing so a supplied filter key is
 * included in its own page.
 *
 * <p>The persistence surface for that walk is deliberately narrow: {@link TransactionScanRepository},
 * whose four reads are all ordered on the identifier and all bounded by an explicit limit, and which
 * publishes no {@code findAll}, no bulk write and no page number. The browse opens with the inclusive
 * read in its direction - greater-or-equal ascending, less-or-equal descending, which is the
 * positioning command itself - and continues with the exclusive read strictly past the last record it
 * handed out. Positioning is therefore a single indexed seek, exactly as the keyed cluster's is, and
 * no row is read that the walk intends to discard.
 *
 * <p>Reading by retained key rather than by page number is a correctness property before it is a cost
 * one. A page number describes a position that a concurrent insert or delete moves, so a walk built on
 * one silently repeats or skips a row; the legacy browse cannot do that, because it repositions on a
 * record key it retained. A row that appears between two turns simply appears at its own key, to this
 * browse exactly as to the legacy one.
 *
 * <p>Bounds and ordering are applied by the store, on a column whose stored identifiers are sixteen
 * zero-padded digit characters, so character order and numeric order coincide. That precondition is
 * the same one the transaction repository's maximum-identifier query depends on, and it is stated
 * there as an obligation on every writer. This class performs no key comparison of its own, which is
 * one fewer place for the precondition to be got wrong.
 *
 * <h2>What this class deliberately does not do</h2>
 *
 * <p>It wires <strong>no abend path</strong>. This member is not one of the five programs that
 * include the attention-key copybook, so it has no {@code HANDLE ABEND} and no abend service call,
 * and adding one would be feature expansion. For the same reason it decodes the attention key
 * directly in the shape the source uses rather than through the utility-layer key translator: that
 * translator folds the thirteenth through twenty-fourth program function keys back onto the first
 * twelve for the five programs that include the copybook, and this member does no such folding - a
 * higher key simply falls to the catch-all arm and produces the invalid-key message.
 *
 * <p>It performs <strong>no scaling, no rounding and no temporal conversion</strong>. The amount is a
 * {@code BigDecimal} at the two-decimal scale the column declares and crosses this class untouched; the
 * estate contains no rounding clause on any arithmetic statement, and the single place permitted to
 * scale is {@code com.carddemo.util.ZonedDecimalCodec}, which truncates toward zero and is consequently
 * not imported here. Both stored timestamps are bounded twenty-six-character strings carried verbatim,
 * and the only date-and-time handling anywhere in this class is the screen header clock of the header
 * paragraph at line 569, which formats the current instant for display and never touches a stored value.
 *
 * <p>It <strong>writes nothing</strong>. This is a list transaction: the read is annotated read-only
 * and there is no save, delete, flush or modifying query. It also mints no identifier, applies no
 * date-range filter and performs no fixed-width slicing - those belong to the bill-payment, report
 * and mapper components respectively.
 *
 * <h2>Statelessness</h2>
 *
 * <p>A singleton with no mutable field. The legacy working storage - the error flag, the end-of-file
 * flag, the erase flag, the message, the row index, and the screen's private commarea group holding
 * the two boundary keys, the page number, the next-page flag and the selection - is recreated per
 * invocation in a private per-call holder, so two concurrent callers share nothing. No cursor and no
 * page is cached; the client echoes the paging state back, exactly as the pseudo-conversational turn
 * carried it in the communication area.
 *
 * <p>The turn is deliberately non-transactional. A repository browse that fails completes its own
 * transaction before this service maps the failure, so no rollback-only marker can replace the
 * source screen outcome when this method returns.
 *
 * @see NavigationService
 * @see MessageCatalogService
 * @see TransactionScanRepository
 * @since 1.0.0
 */
@Service
public final class TransactionListService {

    private static final Logger LOG = LoggerFactory.getLogger(TransactionListService.class);

    /**
     * Rows a transaction-list page carries: ten.
     *
     * <p><strong>A legacy behavioural contract, not a performance setting.</strong> Changing it would
     * put a different number of rows on the screen, which is a visible behavioural regression rather
     * than a configuration change, so it is a compile-time constant and is deliberately not exposed
     * as a configurable property.
     *
     * <p>Uniquely among the estate's three paginated screens the figure is established purely by loop
     * bounds, because {@code app/cbl/COTRN00C.cbl} declares no occurrence table for its screen rows
     * at all. Three independent bounds in the forward paging paragraph agree on it: the blanking loop
     * at lines 290-292 varies its index from one by one until the index exceeds ten, the index is
     * reset to one at line 295, and the fill loop at lines 297-303 runs until the index reaches
     * eleven. The backward paragraph mirrors the same ten from the other end, initialising its index
     * to ten at line 349. The symbolic map {@code app/cpy-bms/COTRN00.CPY} corroborates it with ten
     * row families.
     *
     * <p><strong>Not shared with any other screen's page size, even where the value coincides.</strong>
     * The card list presents seven rows and the administrative user list presents ten, and each figure
     * was proven from a different mechanism in a different member. The equality between this screen
     * and the user list is an accident of two unrelated layouts, so importing one screen's constant
     * into another would let a divergence in one propagate silently into the other.
     */
    private static final int SCREEN_ROW_COUNT = 10;

    /** Lowest screen row index, matching the index the legacy loops start from at lines 290 and 295. */
    private static final int FIRST_SCREEN_ROW = 1;

    /**
     * First page of a browse. Named separately from {@link #FIRST_SCREEN_ROW} even though both are one,
     * because they measure different things: the row index the fill loops start from, and the page number
     * the first-page tests at lines 245, 363 and 366 compare against. Conflating them would let a change
     * to either silently alter the other.
     */
    private static final int FIRST_PAGE_NUMBER = 1;

    // The browse's ordering is no longer declared here as a sort object. Both directions are part of
    // the repository's derived query names, so the forward sequence the legacy reads with READNEXT and
    // the backward sequence it reads with READPREV at line 352 each name their own ordered, bounded
    // read. The key is the transaction identifier in both directions because that is the cluster's own
    // key at offset zero, and the backward rows arrive descending and are assembled into ascending slot
    // order, which is what reproduces the legacy fill from slot ten down to slot one.

    /** Legacy transaction identifier this service implements, from the program's own work field. */
    public static final String TRANSACTION_ID = "CT00";

    /** Legacy program name this service translates, from the program's own work field. */
    public static final String PROGRAM_NAME = "COTRN00C";

    /**
     * Sign-on program the legacy nominates when a turn carries no communication area at line 108, and
     * the substitute the return paragraph applies when the destination field is blank at lines
     * 512-514.
     */
    public static final String SIGN_ON_PROGRAM_NAME = "COSGN00C";

    /** User main menu program the third program function key nominates at line 123. */
    public static final String USER_MENU_PROGRAM_NAME = "COMEN01C";

    /** Transaction-view program a valid row selection transfers to at line 188. */
    public static final String TRANSACTION_VIEW_PROGRAM_NAME = "COTRN01C";

    /**
     * Screen field the cursor is placed on. The member positions the cursor on the transaction-id
     * filter field and on nothing else, at thirteen separate sites beginning at line 105, so the
     * value is invariant for this screen.
     */
    public static final String FOCUS_SCREEN_FIELD_ID = "TRNIDIN";

    /** Property name reported for a field error on the transaction-id filter. */
    public static final String TRANSACTION_ID_FILTER_PROPERTY = "transactionIdFilter";

    /** Property name reported for a field error on a row selector. */
    public static final String ROW_SELECTOR_PROPERTY = "rowSelectors";

    /** Message for a row selected with anything other than the view action, from lines 198-200. */
    public static final String MESSAGE_INVALID_SELECTION = "Invalid selection. Valid value is S";

    /** Message for a filter that fails the numeric class test, from lines 213-215. */
    public static final String MESSAGE_TRAN_ID_NOT_NUMERIC = "Tran ID must be Numeric ...";

    /** Message for a backward request already on the first page, from lines 248-249. */
    public static final String MESSAGE_ALREADY_AT_TOP = "You are already at the top of the page...";

    /** Message for a forward request already on the last page, from lines 270-271. */
    public static final String MESSAGE_ALREADY_AT_BOTTOM =
            "You are already at the bottom of the page...";

    /** Message for a browse that could not be positioned at all, from lines 608-609. */
    public static final String MESSAGE_AT_TOP = "You are at the top of the page...";

    /** Message for a forward read that reached the end of the cluster, from lines 642-643. */
    public static final String MESSAGE_REACHED_BOTTOM = "You have reached the bottom of the page...";

    /** Message for a backward read that reached the start of the cluster, from lines 676-677. */
    public static final String MESSAGE_REACHED_TOP = "You have reached the top of the page...";

    /**
     * Message for an unexpected browse failure, from lines 615-616, 649-650 and 683-684, where the
     * same text is emitted by all three browse paragraphs.
     */
    public static final String MESSAGE_UNABLE_TO_LOOKUP = "Unable to lookup transaction...";

    /** Upper-case row action that selects a transaction for viewing, from line 186. */
    public static final String SELECTION_VIEW_UPPER = "S";

    /** Lower-case row action accepted equally, from line 187. */
    public static final String SELECTION_VIEW_LOWER = "s";

    /** Blank message, the value the member moves into its message field at line 102. */
    private static final String BLANK = "";

    /**
     * Fixed stand-in the diagnostic renderings of this class's three carried shapes emit in place of a
     * regulated value.
     *
     * <p>A constant rather than any transformation of the value, so nothing about a withheld value - not
     * its length, not a prefix, not a digest - survives into a stringified instance. A partial mask was
     * rejected deliberately: a fragment of a sixteen-character numeric key is recoverable by enumeration,
     * and one of the withheld values is a primary account number in full.
     */
    private static final String REDACTION_PLACEHOLDER = "***REDACTED***";

    /**
     * Rendering of the displayed page indicator. The page number is an eight-digit unsigned field in
     * the screen's private commarea group and is moved into an eight-character alphanumeric map field
     * at lines 324 and 373, which transfers eight zero-filled digits.
     */
    private static final String DISPLAYED_PAGE_NUMBER_FORMAT = "%08d";

    /**
     * Capacity of that eight-digit field. A value at or above it loses its high-order digits on the
     * move, which is reproduced with a remainder so the rendering stays total for any accepted input.
     */
    private static final int DISPLAYED_PAGE_NUMBER_MODULUS = 100_000_000;

    /**
     * Rendering of a row selector's legacy screen field name - the four-digit families
     * {@code SEL0001} through {@code SEL0010} scanned at lines 149 to 178.
     */
    private static final String ROW_SELECTOR_FIELD_FORMAT = "SEL%04d";

    /** Lowest character the numeric class test accepts. */
    private static final char ASCII_ZERO = '0';

    /** Highest character the numeric class test accepts. */
    private static final char ASCII_NINE = '9';

    /** The space a blank fixed-width field is filled with. */
    private static final char SPACE = ' ';

    /**
     * The low value a fixed-width field holds where nothing was transmitted. The member tests fields
     * against spaces <em>or</em> low values throughout, so both are blank.
     */
    private static final char LOW_VALUE = '\0';

    /**
     * Screen header date rendering, reproducing the two-digit month, day and year assembled at lines
     * 576-580. Fixed to the root locale so the rendering cannot vary with a host default.
     */
    private static final DateTimeFormatter HEADER_DATE_FORMAT =
            DateTimeFormatter.ofPattern("MM/dd/uu", Locale.ROOT);

    /**
     * Screen header time rendering, reproducing the two-digit hour, minute and second assembled at
     * lines 582-586. Fixed to the root locale for the same reason.
     */
    private static final DateTimeFormatter HEADER_TIME_FORMAT =
            DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT);

    private final TransactionScanRepository transactionScanRepository;

    private final MessageCatalogService messageCatalogService;

    private final NavigationService navigationService;

    private final Clock clock;

    /**
     * Constructor injection of the four collaborators, each of which replaces a distinct legacy
     * mechanism: the ordered-read view replaces the keyed cluster the browse walks, the message catalogue
     * replaces the common message copybook the invalid-key arm reads at line 132 and the screen
     * titles the header paragraph reads at lines 571-572, the navigation service replaces the
     * transfer-control dispatch of lines 192-195 and 518-521, and the clock replaces the intrinsic
     * current-date function of line 569.
     *
     * @param transactionScanRepository transaction master the browse reads, through its bounded
     *                               ordered-read view; must not be {@code null}
     * @param messageCatalogService  common message and screen title catalogue; must not be {@code null}
     * @param navigationService      route resolver for every transfer this screen performs; must not
     *                               be {@code null}
     * @param clock                  clock the screen header is rendered from; must not be {@code null}
     * @throws NullPointerException if any collaborator is {@code null}
     */
    public TransactionListService(final TransactionScanRepository transactionScanRepository,
            final MessageCatalogService messageCatalogService,
            final NavigationService navigationService,
            final Clock clock) {
        this.transactionScanRepository = Objects.requireNonNull(transactionScanRepository,
                "transactionScanRepository must not be null");
        this.messageCatalogService =
                Objects.requireNonNull(messageCatalogService, "messageCatalogService must not be null");
        this.navigationService =
                Objects.requireNonNull(navigationService, "navigationService must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    // ------------------------------------------------------------------------------------------
    // Paragraph MAIN-PARA, line 95
    // ------------------------------------------------------------------------------------------

    /**
     * Runs one transaction-list turn: the translation of paragraph {@code MAIN-PARA} at line 95.
     *
     * <p>The paragraph opens by setting its four working-storage flags at lines 97-100 - error off,
     * not at end of file, no next page, erase the screen - blanking the message and the screen's error
     * field at lines 102-103 and placing the cursor at line 105. It then splits three ways.
     *
     * <ol>
     *   <li>A turn carrying no communication area at line 107 nominates the sign-on program and
     *       returns to it, lines 108-109.</li>
     *   <li>A first entry - the program-context flag not yet set to re-enter, line 112 - sets that flag,
     *       blanks the output map at line 114 and processes the enter key, lines 113-116. Because the
     *       map is <em>never received</em> on this path, any screen field the caller submitted is
     *       ignored; that is the re-enter gate, and it is reproduced literally.</li>
     *   <li>Otherwise the map is received at line 118 and the attention key is evaluated at lines
     *       119-134 in the source's own clause order: enter, the third program function key, the
     *       seventh, the eighth, and a catch-all that raises the error flag and emits the common
     *       invalid-key message.</li>
     * </ol>
     *
     * <p>The legacy then returns to CICS re-arming its own transaction at lines 138-141. There is no
     * server-side forwarding here: the resolved route travels in the result and the client drives the
     * next call, which is what makes this endpoint independently testable.
     *
     * <p>The flag assignments at lines 97-100 happen <em>before</em> the communication area is copied
     * in at line 111, and the next-page flag lives inside that area, so the copy restores whatever the
     * caller echoed and the earlier reset is immediately undone. That ordering is preserved below,
     * because getting it wrong would make the eighth program function key report the bottom of the
     * browse on every page.
     *
     * @param command the submitted screen state and echoed navigation state; must not be {@code null}
     * @return the resolved route, the rows in presentation order and the full screen outcome
     * @throws NullPointerException if {@code command} is {@code null}
     */
    public TransactionListResult listTransactions(final TransactionListCommand command) {
        Objects.requireNonNull(command, "command must not be null");

        final BrowseState state = new BrowseState();
        // Lines 97-100: SET ERR-FLG-OFF, TRANSACT-NOT-EOF, NEXT-PAGE-NO, SEND-ERASE-YES.
        state.errorFlag = false;
        state.endOfFile = false;
        state.nextPageAvailable = false;
        state.sendErase = true;
        // Lines 102-103: blank the message work field and the screen's error message field.
        state.message = BLANK;
        state.presentedMessage = BLANK;
        // Line 105: place the cursor on the transaction-id filter field.
        state.focusScreenFieldId = FOCUS_SCREEN_FIELD_ID;
        state.transactionIdFilterEcho = nullToBlank(command.transactionIdFilter());

        // Line 107: IF EIBCALEN = 0 - the turn carries no prior navigation state at all.
        if (isNavigationStateAbsent(command.navigationContext())) {
            state.context = ScreenNavigationState.empty();
            LOG.debug("Transaction list entered with no navigation state:"
                    + " rule=absent-context transaction={} program={}", TRANSACTION_ID, PROGRAM_NAME);
            // Lines 108-109: nominate the sign-on program, then return to it.
            returnToPrevScreen(state, SIGN_ON_PROGRAM_NAME);
            return buildResult(state, command);
        }

        // Line 111: MOVE DFHCOMMAREA TO CARDDEMO-COMMAREA - this restores the whole area, including
        // the screen's private group, and therefore overwrites the next-page reset made at line 99.
        state.context = command.navigationContext();
        state.pageNumber = command.currentPageNumber();
        state.nextPageAvailable = command.nextPageAvailable();
        state.firstTransactionId = blankToNull(command.pageCursor().previousCursorKey());
        state.lastTransactionId = blankToNull(command.pageCursor().nextCursorKey());

        if (state.context.firstEntry()) {
            // Line 112: IF NOT CDEMO-PGM-REENTER.
            // Line 113: SET CDEMO-PGM-REENTER TO TRUE.
            state.context = state.context.withReEntry();
            // Line 114: MOVE LOW-VALUES TO the output map, which shares storage with the input map
            // and so discards every submitted screen field on a first entry.
            clearScreenFieldsToLowValues(state);
            // Line 115.
            processEnterKey(state, command);
            // Line 116: the send is skipped when the enter-key processing transferred control.
            if (!state.controlTransferred) {
                sendTrnlstScreen(state);
            }
        } else {
            // Line 118.
            receiveTrnlstScreen(state, command);
            // Lines 119-134: EVALUATE EIBAID, clause order preserved, WHEN OTHER as the default arm.
            switch (command.keyAction()) {
                case ENTER -> processEnterKey(state, command);
                // Lines 122-124: nominate the user main menu, then return to it.
                case PFK03 -> returnToPrevScreen(state, USER_MENU_PROGRAM_NAME);
                case PFK07 -> processPf7Key(state, command);
                case PFK08 -> processPf8Key(state, command);
                default -> {
                    // Lines 129-133. The message is the common catalogue's invalid-key text, fifty
                    // characters wide and space-padded; it is emitted untrimmed.
                    state.errorFlag = true;
                    state.focusScreenFieldId = FOCUS_SCREEN_FIELD_ID;
                    state.message = messageCatalogService.invalidKeyMessage();
                    LOG.debug("Transaction list received an unmapped attention key:"
                            + " rule=invalid-key keyAction={}", command.keyAction());
                    sendTrnlstScreen(state);
                }
            }
        }

        return buildResult(state, command);
    }

    // ------------------------------------------------------------------------------------------
    // Paragraph PROCESS-ENTER-KEY, line 146
    // ------------------------------------------------------------------------------------------

    /**
     * Processes the enter key: the translation of paragraph {@code PROCESS-ENTER-KEY} at line 146.
     *
     * <p>Three things happen, in this order, and the order is load-bearing.
     *
     * <p><strong>Row selection, lines 148-182.</strong> An evaluation of ten clauses tests each row's
     * selector against blank in ascending row order and stops at the first that is not blank, taking
     * that row's echoed identifier as the selection; a catch-all clause clears both fields. The Java
     * form is an ascending scan that stops at the first non-blank selector, which is the same
     * first-match-wins semantics over the same ordered clauses.
     *
     * <p><strong>Selection dispatch, lines 183-204.</strong> When both the selector and the identifier
     * are present, the selector is evaluated: the view action in either case transfers control to the
     * transaction-view program at lines 186-195, and any other value produces the invalid-selection
     * message at lines 196-203. <strong>That second arm does not send the screen</strong> - the send is
     * commented out at line 202 - so execution falls through and the message is carried into the page
     * that the forward paging paragraph is about to send. Returning early here, which is the obvious
     * shape for a validation failure, would lose the message.
     *
     * <p><strong>Filter handling and paging, lines 206-229.</strong> A blank filter positions the
     * browse at the low end of the cluster, line 207. A filter that passes the numeric class test is
     * moved into the record identification field <em>as characters</em>, line 210 - the identifier is
     * sixteen alphanumeric characters and is never parsed as a number anywhere in this class. A filter
     * that fails the test raises the error flag, emits its message and sends the screen at lines
     * 212-217, and then <strong>also falls through</strong>: the record identification field is left as
     * it was, the paging paragraph is still entered, and its opening browse still runs before the error
     * flag stops the rest. The page number is reset to zero at line 224 so that a filtered browse
     * always starts a fresh page count, and the echoed filter is blanked at lines 227-229 only when no
     * error occurred.
     *
     * <p>The numeric class test is reproduced as "every character is a digit". The legacy test is
     * applied to a fixed-width screen field, so a partially typed entry also fails it because the
     * unfilled positions hold a non-digit fill character. At this boundary the filter arrives as a
     * logical value rather than as a fixed-width image, so that width component of the test has no
     * counterpart here and is recorded as a decision-log entry; the digit test itself is exact.
     */
    private void processEnterKey(final BrowseState state, final TransactionListCommand command) {
        // Lines 148-182: EVALUATE TRUE over the ten row selectors, first match wins.
        state.selectionFlag = BLANK;
        state.selectedTransactionId = BLANK;
        int selectedRow = 0;
        for (int row = FIRST_SCREEN_ROW; row <= SCREEN_ROW_COUNT; row++) {
            final String selector = elementAt(state.receivedSelectors, row);
            if (isPresent(selector)) {
                state.selectionFlag = selector;
                state.selectedTransactionId =
                        nullToBlank(elementAt(state.receivedTransactionIds, row));
                selectedRow = row;
                break;
            }
        }

        // Lines 183-204.
        if (isPresent(state.selectionFlag) && isPresent(state.selectedTransactionId)) {
            // Lines 185-203: EVALUATE CDEMO-CT00-TRN-SEL-FLG, clause order preserved.
            switch (state.selectionFlag) {
                case SELECTION_VIEW_UPPER, SELECTION_VIEW_LOWER -> {
                    // Lines 186-195: transfer control to the transaction-view program.
                    LOG.debug("Transaction list dispatching a row selection:"
                            + " rule=row-selection screenRow={}", selectedRow);
                    transferToProgram(state, TRANSACTION_VIEW_PROGRAM_NAME);
                    return;
                }
                default -> {
                    // Lines 196-203. No send here: the send statement is commented out at line 202,
                    // so this message survives into the page the forward paragraph sends.
                    state.message = MESSAGE_INVALID_SELECTION;
                    state.focusScreenFieldId = FOCUS_SCREEN_FIELD_ID;
                    state.fieldErrors.add(new ValidationException.FieldError(
                            ROW_SELECTOR_PROPERTY,
                            rowSelectorScreenFieldId(selectedRow),
                            ValidationException.FieldState.INVALID,
                            MESSAGE_INVALID_SELECTION));
                    LOG.debug("Transaction list rejected a row action:"
                            + " rule=row-selection screenRow={}", selectedRow);
                }
            }
        }

        // Lines 206-219: the transaction-id filter.
        if (isBlank(state.receivedFilter)) {
            // Line 207: MOVE LOW-VALUES TO TRAN-ID - browse from the low end of the cluster.
            state.ridfldKey = null;
            state.ridfldHighValues = false;
        } else if (isCobolNumeric(state.receivedFilter)) {
            // Line 210: the filter characters become the record identification field.
            state.ridfldKey = state.receivedFilter;
            state.ridfldHighValues = false;
        } else {
            // Lines 212-217. The record identification field is deliberately left untouched, exactly
            // as the source leaves it, so the browse that follows positions at the low end.
            state.errorFlag = true;
            state.message = MESSAGE_TRAN_ID_NOT_NUMERIC;
            state.focusScreenFieldId = FOCUS_SCREEN_FIELD_ID;
            state.fieldErrors.add(new ValidationException.FieldError(
                    TRANSACTION_ID_FILTER_PROPERTY,
                    FOCUS_SCREEN_FIELD_ID,
                    ValidationException.FieldState.INVALID,
                    MESSAGE_TRAN_ID_NOT_NUMERIC));
            LOG.debug("Transaction list rejected a non-numeric filter:"
                    + " rule=filter-class-test length={}", state.receivedFilter.length());
            sendTrnlstScreen(state);
        }

        // Line 221.
        state.focusScreenFieldId = FOCUS_SCREEN_FIELD_ID;
        // Line 224: a filtered or first browse always restarts the page count.
        state.pageNumber = 0;
        // Line 225.
        processPageForward(state, command);
        // Lines 227-229: clear the echoed filter only when the turn produced no error.
        if (!state.errorFlag) {
            state.transactionIdFilterEcho = BLANK;
        }
    }

    // ------------------------------------------------------------------------------------------
    // Paragraph PROCESS-PF7-KEY, line 234
    // ------------------------------------------------------------------------------------------

    /**
     * Handles a request for the preceding page: the translation of paragraph
     * {@code PROCESS-PF7-KEY} at line 234.
     *
     * <p>The browse is repositioned on the <em>first</em> boundary key the previous page retained, or
     * on the low end of the cluster when that key is blank, lines 236-240. The next-page flag is then
     * set <strong>unconditionally</strong> at line 242 - walking back necessarily leaves a page ahead -
     * and the cursor is placed at line 243.
     *
     * <p>The guard at line 245 is the one place the page number is used as a decision rather than as a
     * display value: the backward paragraph is entered only beyond the first page, and otherwise the
     * screen reports that it is already at the top, lines 248-249. That report also clears the erase
     * flag at line 250, so the screen is re-sent <em>without</em> erasing and the rows already on it
     * remain. This class carries that as an explicit flag on the result, which is what lets a client
     * keep its current rows when the response returns none.
     */
    private void processPf7Key(final BrowseState state, final TransactionListCommand command) {
        // Lines 236-240.
        state.ridfldKey = isBlank(state.firstTransactionId) ? null : state.firstTransactionId;
        state.ridfldHighValues = false;
        // Line 242: SET NEXT-PAGE-YES TO TRUE, with no condition attached.
        state.nextPageAvailable = true;
        // Line 243.
        state.focusScreenFieldId = FOCUS_SCREEN_FIELD_ID;

        // Line 245.
        if (state.pageNumber > FIRST_PAGE_NUMBER) {
            processPageBackward(state, command);
        } else {
            // Lines 248-251.
            state.message = MESSAGE_ALREADY_AT_TOP;
            state.sendErase = false;
            LOG.debug("Transaction list refused a backward page:"
                    + " rule=top-of-browse pageNumber={}", state.pageNumber);
            sendTrnlstScreen(state);
        }
    }

    // ------------------------------------------------------------------------------------------
    // Paragraph PROCESS-PF8-KEY, line 257
    // ------------------------------------------------------------------------------------------

    /**
     * Handles a request for the following page: the translation of paragraph
     * {@code PROCESS-PF8-KEY} at line 257.
     *
     * <p>The mirror of the preceding paragraph, with two deliberate asymmetries. The browse is
     * repositioned on the <em>last</em> boundary key the previous page retained, and when that key is
     * blank the record identification field is set to high values at line 260 rather than to low values
     * - which cannot match any key, so the opening browse reports not-found. And the guard at line 267
     * tests the echoed next-page flag rather than the page number, because whether a further page exists
     * is something the legacy discovers by attempting one more read and can only carry forward as state.
     *
     * <p>As on the backward path, the already-at-the-bottom report at lines 270-273 clears the erase
     * flag so the screen keeps the rows it is showing.
     */
    private void processPf8Key(final BrowseState state, final TransactionListCommand command) {
        // Lines 259-263.
        if (isBlank(state.lastTransactionId)) {
            state.ridfldKey = null;
            // Line 260: MOVE HIGH-VALUES TO TRAN-ID.
            state.ridfldHighValues = true;
        } else {
            state.ridfldKey = state.lastTransactionId;
            state.ridfldHighValues = false;
        }
        // Line 265.
        state.focusScreenFieldId = FOCUS_SCREEN_FIELD_ID;

        // Line 267.
        if (state.nextPageAvailable) {
            processPageForward(state, command);
        } else {
            // Lines 270-273.
            state.message = MESSAGE_ALREADY_AT_BOTTOM;
            state.sendErase = false;
            LOG.debug("Transaction list refused a forward page:"
                    + " rule=bottom-of-browse pageNumber={}", state.pageNumber);
            sendTrnlstScreen(state);
        }
    }

    // ------------------------------------------------------------------------------------------
    // Paragraph PROCESS-PAGE-FORWARD, line 279
    // ------------------------------------------------------------------------------------------

    /**
     * Fills a page walking forward: the translation of paragraph {@code PROCESS-PAGE-FORWARD} at line
     * 279.
     *
     * <p>The opening browse at line 281 runs unconditionally - before the error-flag guard at line 283 -
     * which is why a filter that already failed its class test can still have its message replaced by a
     * not-found report from the browse itself. Everything after the guard is skipped once the error flag
     * is on.
     *
     * <p>The guard at lines 285-287 reads, in the source's abbreviated form, "the attention key is
     * none of enter, the seventh program function key, or the third". When it holds, one read is issued
     * and discarded, which consumes the positioned record - the last row of the page just displayed -
     * so the new page starts immediately after it. The enter key fails the guard, so a supplied filter
     * key is included in its own page. The condition is reproduced exactly as written rather than
     * simplified to "the eighth key", because the source's form is what decides the unreachable cases
     * too.
     *
     * <p>Ten slots are blanked at lines 290-292, the index is reset to one at line 295, and the fill
     * loop at lines 297-303 reads and populates while the index has not reached eleven and neither the
     * end of file nor an error has intervened. The index advances <em>only</em> when a record was
     * actually returned, so an end of file mid-page leaves the remaining slots blank rather than
     * shifting rows.
     *
     * <p>Lines 305-320 settle the page number and the next-page flag. When the page filled completely
     * the number is incremented and one further read decides whether a page follows; otherwise there is
     * no page after this one, and the number is still incremented if at least one row was placed - which
     * is what numbers a short final page. The browse is ended at line 322, the page indicator is set at
     * line 324, the echoed filter is blanked at line 325, and the screen is sent at line 326.
     */
    private void processPageForward(final BrowseState state, final TransactionListCommand command) {
        // Line 281.
        startbrTransactFile(state, true);

        // Line 283.
        if (state.errorFlag) {
            return;
        }

        // Lines 285-287: IF EIBAID NOT = DFHENTER AND DFHPF7 AND DFHPF3.
        if (command.keyAction() != KeyAction.ENTER
                && command.keyAction() != KeyAction.PFK07
                && command.keyAction() != KeyAction.PFK03) {
            readnextTransactFile(state);
        }

        // Lines 289-293.
        if (!state.endOfFile && !state.errorFlag) {
            for (int row = FIRST_SCREEN_ROW; row <= SCREEN_ROW_COUNT; row++) {
                initializeTranData(state, row);
            }
        }

        // Line 295: MOVE 1 TO WS-IDX.
        int screenRow = FIRST_SCREEN_ROW;

        // Lines 297-303: PERFORM UNTIL WS-IDX >= 11 OR TRANSACT-EOF OR ERR-FLG-ON.
        while (screenRow <= SCREEN_ROW_COUNT && !state.endOfFile && !state.errorFlag) {
            final Transaction record = readnextTransactFile(state);
            if (!state.endOfFile && !state.errorFlag) {
                populateTranData(state, screenRow, record);
                screenRow++;
            }
        }

        // Lines 305-320.
        if (!state.endOfFile && !state.errorFlag) {
            // Lines 306-307.
            state.pageNumber++;
            // Lines 308-313: one further read decides whether a page follows this one.
            readnextTransactFile(state);
            state.nextPageAvailable = !state.endOfFile && !state.errorFlag;
        } else {
            // Line 315.
            state.nextPageAvailable = false;
            // Lines 316-319: a short page still counts as a page when it placed at least one row.
            if (screenRow > FIRST_SCREEN_ROW) {
                state.pageNumber++;
            }
        }

        // Line 322.
        endbrTransactFile(state);
        // Line 324: the page indicator is rendered from the settled page number when the result is built.
        // Line 325: MOVE SPACE TO the echoed filter field.
        state.transactionIdFilterEcho = BLANK;
        LOG.debug("Transaction list assembled a forward page: rule=page-forward pageNumber={}"
                + " rows={} morePages={}", state.pageNumber, screenRow - FIRST_SCREEN_ROW,
                state.nextPageAvailable);
        // Line 326.
        sendTrnlstScreen(state);
    }

    // ------------------------------------------------------------------------------------------
    // Paragraph PROCESS-PAGE-BACKWARD, line 333
    // ------------------------------------------------------------------------------------------

    /**
     * Fills a page walking backward: the translation of paragraph {@code PROCESS-PAGE-BACKWARD} at line
     * 333, and the single most easily mistranslated paragraph in the member.
     *
     * <p><strong>The reads descend and the slots descend with them, so the assembled page ascends.</strong>
     * The index is initialised to <em>ten</em> at line 349, and the loop at lines 351-357 reads backward
     * at line 352 and then <em>decrements</em>. The first record read is the one nearest the boundary
     * key and it lands in slot ten; the last record read is the furthest away and it lands in slot one.
     * The page therefore reads top-to-bottom in ascending key order - visually identical to a forward
     * page - even though every read moved backward through the key sequence.
     *
     * <p>This class reproduces that by querying with the descending ordering and populating slots from
     * ten downward, so that assembling the result in ascending slot order yields <strong>the reverse of
     * the read order</strong>. Two shortcuts are excluded on purpose: returning the descending read
     * order would invert the page relative to the legacy screen, and reading ascending from an offset
     * derived from the page number would drift as soon as a row is inserted, because the legacy
     * repositions on a retained key and never on a row number.
     *
     * <p>A consequence worth stating, because it is easy to read as a defect: a backward page that finds
     * fewer than ten records is <em>bottom-aligned</em>, since the fill started at slot ten. Slot one is
     * then never populated, so the first boundary key keeps the value the caller echoed - see the slot
     * assignments in the population paragraph.
     *
     * <p>The guard at lines 339-341 is the mirror of the forward one, excluding enter and the eighth
     * program function key, and it consumes the positioned record - the first row of the page just
     * displayed - so the preceding page ends immediately before it.
     *
     * <p>Lines 359-369 settle the page number: one further backward read establishes whether anything
     * precedes this page, and the number is decremented only when the echoed next-page flag is set and
     * a preceding record exists beyond the first page, and is otherwise pinned to one. Two differences
     * from the forward paragraph are deliberate and preserved: the next-page flag is not recomputed here,
     * and the echoed filter field is not blanked.
     */
    private void processPageBackward(final BrowseState state, final TransactionListCommand command) {
        // Line 335.
        startbrTransactFile(state, false);

        // Line 337.
        if (state.errorFlag) {
            return;
        }

        // Lines 339-341: IF EIBAID NOT = DFHENTER AND DFHPF8.
        if (command.keyAction() != KeyAction.ENTER && command.keyAction() != KeyAction.PFK08) {
            readprevTransactFile(state);
        }

        // Lines 343-347.
        if (!state.endOfFile && !state.errorFlag) {
            for (int row = FIRST_SCREEN_ROW; row <= SCREEN_ROW_COUNT; row++) {
                initializeTranData(state, row);
            }
        }

        // Line 349: MOVE 10 TO WS-IDX - the fill starts at the bottom slot.
        int screenRow = SCREEN_ROW_COUNT;

        // Lines 351-357: PERFORM UNTIL WS-IDX <= 0 OR TRANSACT-EOF OR ERR-FLG-ON.
        while (screenRow >= FIRST_SCREEN_ROW && !state.endOfFile && !state.errorFlag) {
            final Transaction record = readprevTransactFile(state);
            if (!state.endOfFile && !state.errorFlag) {
                populateTranData(state, screenRow, record);
                screenRow--;
            }
        }

        // Lines 359-369.
        if (!state.endOfFile && !state.errorFlag) {
            // Line 360.
            readprevTransactFile(state);
            // Line 361.
            if (state.nextPageAvailable) {
                if (!state.endOfFile && !state.errorFlag && state.pageNumber > FIRST_PAGE_NUMBER) {
                    // Line 364.
                    state.pageNumber--;
                } else {
                    // Line 366.
                    state.pageNumber = FIRST_PAGE_NUMBER;
                }
            }
        }

        // Line 371.
        endbrTransactFile(state);
        // Line 373: the page indicator is rendered from the settled page number when the result is built.
        LOG.debug("Transaction list assembled a backward page: rule=page-backward pageNumber={}"
                + " rows={} readDescendingPresentedAscending=true",
                state.pageNumber, SCREEN_ROW_COUNT - screenRow);
        // Line 374.
        sendTrnlstScreen(state);
    }

    // ------------------------------------------------------------------------------------------
    // Paragraph POPULATE-TRAN-DATA, line 381
    // ------------------------------------------------------------------------------------------

    /**
     * Places one record in one screen slot: the translation of paragraph {@code POPULATE-TRAN-DATA} at
     * line 381.
     *
     * <p>The paragraph evaluates the row index across ten clauses and a catch-all that continues without
     * doing anything, lines 390-445. Nine of the ten bodies are identical in shape - identifier, date,
     * description and amount into that row's four map fields - and two carry an extra assignment that is
     * the whole reason the paragraph matters: <strong>the first slot also stores the page's first
     * boundary key</strong>, lines 392-393, and <strong>the tenth also stores its last</strong>, lines
     * 438-439. Those two keys are what the seventh and eighth program function keys reposition on, so
     * they are the browse cursor.
     *
     * <p>The switch below preserves that clause order and maps the catch-all to the default arm. Its
     * grouped middle case is not a simplification of the source's ten clauses but a statement of what
     * they have in common: only the first and tenth do anything a slot assignment does not already do.
     *
     * <p>A consequence the source makes unavoidable: because each boundary key is written only from its
     * own slot, a page that does not fill completely updates only one of them. A short forward page has
     * a first key but keeps the echoed last key; a short backward page, filled from slot ten downward,
     * has a last key but keeps the echoed first key.
     *
     * <p>The record is stored whole. The legacy reads the entire record into its record area and then
     * projects four fields onto the screen; the four displayed columns are a presentation concern and the
     * projection happens above this layer, so nothing is trimmed, folded, scaled or reformatted here.
     * The date column the legacy renders is derived from the origination timestamp by taking parts of it
     * at lines 384-388, and that derivation belongs to the presentation boundary: both timestamps are
     * carried out of here as the bounded twenty-six-character strings they are stored as.
     *
     * @param state     per-call working storage
     * @param screenRow one-based slot index, matching the legacy row index
     * @param record    the record just read, or {@code null} if the read produced none, in which case
     *                  nothing is stored - the loops that call this only do so after a successful read,
     *                  and the check keeps the method total
     */
    private void populateTranData(final BrowseState state, final int screenRow,
            final Transaction record) {
        if (record == null) {
            return;
        }
        // Lines 390-445: EVALUATE WS-IDX, clause order preserved, WHEN OTHER CONTINUE as the default.
        switch (screenRow) {
            case 1 -> {
                state.screenRows[screenRow - 1] = record;
                // Lines 392-393: the first slot also stores the page's first boundary key.
                state.firstTransactionId = record.getTranId();
            }
            case 2, 3, 4, 5, 6, 7, 8, 9 -> state.screenRows[screenRow - 1] = record;
            case 10 -> {
                state.screenRows[screenRow - 1] = record;
                // Lines 438-439: the tenth slot also stores the page's last boundary key.
                state.lastTransactionId = record.getTranId();
            }
            default -> {
                // Lines 443-444: WHEN OTHER CONTINUE - an index outside the ten slots stores nothing.
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // Paragraph INITIALIZE-TRAN-DATA, line 450
    // ------------------------------------------------------------------------------------------

    /**
     * Blanks one screen slot: the translation of paragraph {@code INITIALIZE-TRAN-DATA} at line 450.
     *
     * <p>Ten clauses that blank that row's four map fields, plus a catch-all that continues, lines
     * 452-505. All ten bodies are identical, so the switch groups them while keeping the catch-all as
     * the default arm. An absent row is represented by an absent record rather than by four blank
     * strings, which is why a short page carries fewer rows and is never padded.
     *
     * <p>Note where the callers place this: both paging paragraphs blank all ten slots only when the
     * browse is neither at end of file nor in error, lines 289 and 343. A browse that could not be
     * positioned therefore leaves the slots exactly as this turn found them - which is empty, since the
     * slots are per-call state - and the screen is re-sent with a message instead of a page.
     *
     * @param state     per-call working storage
     * @param screenRow one-based slot index, matching the legacy row index
     */
    private void initializeTranData(final BrowseState state, final int screenRow) {
        // Lines 452-505: EVALUATE WS-IDX, WHEN OTHER CONTINUE as the default.
        switch (screenRow) {
            case 1, 2, 3, 4, 5, 6, 7, 8, 9, 10 -> state.screenRows[screenRow - 1] = null;
            default -> {
                // Lines 503-504: WHEN OTHER CONTINUE - an index outside the ten slots blanks nothing.
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // Paragraph RETURN-TO-PREV-SCREEN, line 510
    // ------------------------------------------------------------------------------------------

    /**
     * Returns to the screen a caller nominated: the translation of paragraph
     * {@code RETURN-TO-PREV-SCREEN} at line 510.
     *
     * <p>The paragraph inspects the <em>destination</em>-program field of the communication area, not the
     * originating one, and substitutes the sign-on program when it is blank, lines 512-514. Its two
     * callers set that field first - the no-communication-area branch nominates sign-on at line 108 and
     * the third program function key nominates the user main menu at line 123 - so the substitution is a
     * safety net rather than the normal path, and it is reproduced here for exactly that reason.
     *
     * <p>The originating transaction and program are then stamped at lines 515-516 and the program
     * context is set to zero at line 517, which is the enter state rather than the re-enter one, so the
     * screen being entered treats the arrival as a first entry. Control transfers at lines 518-521,
     * which ends the program: no screen is sent, so the result carries no message.
     *
     * @param state              per-call working storage
     * @param nominatedProgram   the destination the caller nominated; a blank value takes the sign-on
     *                           substitution of lines 512-514
     */
    private void returnToPrevScreen(final BrowseState state, final String nominatedProgram) {
        // Lines 512-514.
        final String destination = isBlank(nominatedProgram) ? SIGN_ON_PROGRAM_NAME : nominatedProgram;
        transferToProgram(state, destination);
    }

    // ------------------------------------------------------------------------------------------
    // Paragraph SEND-TRNLST-SCREEN, line 527
    // ------------------------------------------------------------------------------------------

    /**
     * Presents the screen: the translation of paragraph {@code SEND-TRNLST-SCREEN} at line 527.
     *
     * <p>The header is refreshed at line 529, the message work field is copied into the screen's error
     * field at line 531, and the map is sent either erasing or not erasing according to the erase flag,
     * lines 533-549. Both send statements are otherwise identical and both position the cursor.
     *
     * <p>The copy at line 531 is the reason this method exists rather than being folded away. The
     * message the operator sees is whichever value the work field held at the <em>last</em> send of the
     * turn, and a turn can send more than once, or - on the three transfer paths - not at all. Copying
     * here rather than reading the work field when the result is built reproduces that precisely: the
     * invalid-selection message set at line 198 without a send survives to be picked up by the send the
     * forward paragraph performs, while a transfer that never sends carries no message at all.
     */
    private void sendTrnlstScreen(final BrowseState state) {
        // Line 529.
        populateHeaderInfo(state);
        // Line 531: MOVE WS-MESSAGE TO the screen's error message field.
        state.presentedMessage = state.message;
        // Lines 533-549: SEND MAP, with ERASE when the flag is set and without it otherwise.
        LOG.debug("Transaction list screen presented: rule=send-map erase={} error={}",
                state.sendErase, state.errorFlag);
    }

    // ------------------------------------------------------------------------------------------
    // Paragraph RECEIVE-TRNLST-SCREEN, line 554
    // ------------------------------------------------------------------------------------------

    /**
     * Reads the submitted screen: the translation of paragraph {@code RECEIVE-TRNLST-SCREEN} at line
     * 554.
     *
     * <p>A single receive of the map into the input area, lines 556-562. The Java form binds the
     * submitted field values into the per-call working storage, and it is called from exactly one place -
     * the re-entry branch at line 118. That single call site is the re-enter gate: on a first entry the
     * map is never received, so every submitted screen field is discarded, and this method is why that
     * distinction is visible rather than implicit.
     *
     * <p>Values are bound as received. Nothing is trimmed, padded or re-cased, because the selector
     * comparison at lines 149 onward is against blank and the filter class test at line 209 is against
     * the characters actually present.
     */
    private void receiveTrnlstScreen(final BrowseState state, final TransactionListCommand command) {
        // Lines 556-562.
        state.receivedFilter = nullToBlank(command.transactionIdFilter());
        state.receivedSelectors = command.rowSelectors();
        state.receivedTransactionIds = command.displayedTransactionIds();
        LOG.trace("Transaction list received the submitted map: rule=receive-map selectors={}"
                + " identifiers={}", state.receivedSelectors.size(),
                state.receivedTransactionIds.size());
    }

    // ------------------------------------------------------------------------------------------
    // Paragraph POPULATE-HEADER-INFO, line 567
    // ------------------------------------------------------------------------------------------

    /**
     * Refreshes the screen header: the translation of paragraph {@code POPULATE-HEADER-INFO} at line
     * 567.
     *
     * <p>The current instant is taken at line 569, the two screen titles come from the common title
     * copybook at lines 571-572, and the transaction identifier and program name are stamped at lines
     * 573-574 from the program's own work fields. The instant is then rendered twice: as a two-digit
     * month, day and year at lines 576-580, and as a two-digit hour, minute and second at lines 582-586.
     *
     * <p>The instant comes from an injected clock rather than from the system default, so a caller can
     * fix it and the header becomes deterministic. Both renderings are bound to the root locale, because
     * a host default locale can otherwise change the digits or the field order and the header is a fixed
     * eight characters wide on the map.
     *
     * <p>This is the only place in this class that touches a date or a time. It never reads either of the
     * stored twenty-six-character timestamps, which cross this class as the strings they are stored as.
     */
    private void populateHeaderInfo(final BrowseState state) {
        // Line 569: MOVE FUNCTION CURRENT-DATE.
        final LocalDateTime now = LocalDateTime.now(clock);
        // Lines 571-572.
        state.screenTitle01 = messageCatalogService.screenTitle01();
        state.screenTitle02 = messageCatalogService.screenTitle02();
        // Lines 576-580.
        state.currentDate = HEADER_DATE_FORMAT.format(now);
        // Lines 582-586.
        state.currentTime = HEADER_TIME_FORMAT.format(now);
    }

    // ------------------------------------------------------------------------------------------
    // Paragraph STARTBR-TRANSACT-FILE, line 591
    // ------------------------------------------------------------------------------------------

    /**
     * Opens the browse: the translation of paragraph {@code STARTBR-TRANSACT-FILE} at line 591.
     *
     * <p>The browse command at lines 593-600 names the transaction file, supplies the record
     * identification field as both the key and its length, and captures the response. The explicit
     * greater-or-equal option is commented out at line 597, which changes nothing because it is the
     * default: the browse positions on the lowest key not less than the field.
     *
     * <p>The response is then evaluated across three clauses at lines 602-619 and all three are
     * reproduced here in order.
     *
     * <ol>
     *   <li><strong>Normal</strong>, lines 603-604: continue with the browse open.</li>
     *   <li><strong>Not found</strong>, lines 605-611: no key qualifies. This sets the end-of-file flag -
     *       <em>not</em> the error flag - emits the at-the-top message, places the cursor and sends the
     *       screen. Leaving the error flag off is significant: the caller's guard therefore still passes
     *       and the paragraph continues, which is how a browse that could not be positioned still runs
     *       its guard read.</li>
     *   <li><strong>Anything else</strong>, lines 612-618: the response is written to the log, the error
     *       flag is raised, the unable-to-look-up message is emitted and the screen is sent. The display
     *       statement becomes a structured log record at error level.</li>
     * </ol>
     *
     * <p>Two positioning cases reach the not-found arm without a query. A record identification field of
     * high values cannot be matched by any key, so the eighth program function key with no retained last
     * key reports not found immediately. And a field of low values on a <em>backward</em> browse must be
     * resolved to the lowest stored key before the descending walk can start from it, so an empty table
     * reports not found there too.
     *
     * @param state     per-call working storage, carrying the record identification field
     * @param ascending {@code true} for a forward browse, ordering the walk ascending on the identifier;
     *                  {@code false} for a backward browse, ordering it descending
     */
    private void startbrTransactFile(final BrowseState state, final boolean ascending) {
        // Lines 593-600.
        try {
            final SequentialBrowse browse = openBrowse(state, ascending);
            if (browse != null) {
                // Lines 603-604: WHEN DFHRESP(NORMAL) CONTINUE.
                state.browse = browse;
                LOG.trace("Transaction list browse opened: rule=startbr ascending={} lowValues={}"
                        + " highValues={}", ascending, state.ridfldKey == null,
                        state.ridfldHighValues);
            } else {
                // Lines 605-611: WHEN DFHRESP(NOTFND). The error flag is deliberately left off.
                state.endOfFile = true;
                state.message = MESSAGE_AT_TOP;
                state.focusScreenFieldId = FOCUS_SCREEN_FIELD_ID;
                LOG.debug("Transaction list browse could not be positioned:"
                        + " rule=startbr-notfnd highValues={}", state.ridfldHighValues);
                sendTrnlstScreen(state);
            }
        } catch (final DataAccessException failure) {
            // Lines 612-618: WHEN OTHER.
            reportBrowseFailure(state, failure, "STARTBR");
        }
    }

    // ------------------------------------------------------------------------------------------
    // Paragraph READNEXT-TRANSACT-FILE, line 624
    // ------------------------------------------------------------------------------------------

    /**
     * Reads the next record forward: the translation of paragraph {@code READNEXT-TRANSACT-FILE} at line
     * 624.
     *
     * <p>The read command at lines 626-634 returns the record the browse is positioned on and advances.
     * Its response is evaluated across the same three clauses at lines 636-653: normal continues,
     * end-of-file sets the end-of-file flag and emits the reached-the-bottom message at lines 642-643,
     * and anything else raises the error flag with the unable-to-look-up message.
     *
     * <p>A read issued when no browse is open corresponds to the invalid-request response, which falls to
     * the third clause. That is reachable in the source: when the opening browse reported not found it
     * left the error flag off, so a forward page driven by the eighth program function key still takes its
     * guard read and gets that response. It is reproduced rather than defended against, because the
     * message the operator sees differs between the two arms.
     *
     * @return the record read, or {@code null} when the browse is exhausted or the read failed
     */
    private Transaction readnextTransactFile(final BrowseState state) {
        // Lines 626-634.
        try {
            if (state.browse == null) {
                // The invalid-request response: no browse is open. Falls to WHEN OTHER, lines 646-652.
                reportBrowseFailure(state, null, "READNEXT");
                return null;
            }
            final Transaction record = state.browse.read();
            if (record != null) {
                // Lines 637-638: WHEN DFHRESP(NORMAL) CONTINUE.
                return record;
            }
            // Lines 639-645: WHEN DFHRESP(ENDFILE).
            state.endOfFile = true;
            state.message = MESSAGE_REACHED_BOTTOM;
            state.focusScreenFieldId = FOCUS_SCREEN_FIELD_ID;
            sendTrnlstScreen(state);
            return null;
        } catch (final DataAccessException failure) {
            // Lines 646-652: WHEN OTHER.
            reportBrowseFailure(state, failure, "READNEXT");
            return null;
        }
    }

    // ------------------------------------------------------------------------------------------
    // Paragraph READPREV-TRANSACT-FILE, line 658
    // ------------------------------------------------------------------------------------------

    /**
     * Reads the next record backward: the translation of paragraph {@code READPREV-TRANSACT-FILE} at
     * line 658.
     *
     * <p>Structurally the mirror of the forward read - the command at lines 660-668 and the same three
     * response clauses at lines 670-687 - with one difference that is the whole point of the paragraph
     * being separate: the end-of-file arm emits the reached-the-<em>top</em> message at lines 676-677
     * instead of the bottom one.
     *
     * <p>The direction lives in the browse rather than in this method: a backward browse was opened with
     * the descending ordering, so advancing it walks the key sequence downward. That is what makes the
     * backward page arrive in descending order and, once assembled into descending slot positions,
     * present ascending.
     *
     * @return the record read, or {@code null} when the browse is exhausted or the read failed
     */
    private Transaction readprevTransactFile(final BrowseState state) {
        // Lines 660-668.
        try {
            if (state.browse == null) {
                // The invalid-request response, as on the forward read. Falls to WHEN OTHER.
                reportBrowseFailure(state, null, "READPREV");
                return null;
            }
            final Transaction record = state.browse.read();
            if (record != null) {
                // Lines 671-672: WHEN DFHRESP(NORMAL) CONTINUE.
                return record;
            }
            // Lines 673-679: WHEN DFHRESP(ENDFILE).
            state.endOfFile = true;
            state.message = MESSAGE_REACHED_TOP;
            state.focusScreenFieldId = FOCUS_SCREEN_FIELD_ID;
            sendTrnlstScreen(state);
            return null;
        } catch (final DataAccessException failure) {
            // Lines 680-686: WHEN OTHER.
            reportBrowseFailure(state, failure, "READPREV");
            return null;
        }
    }

    // ------------------------------------------------------------------------------------------
    // Paragraph ENDBR-TRANSACT-FILE, line 692
    // ------------------------------------------------------------------------------------------

    /**
     * Ends the browse: the translation of paragraph {@code ENDBR-TRANSACT-FILE} at line 692.
     *
     * <p>A single command naming the file, lines 694-696, with <strong>no response captured and no
     * condition handled</strong> - the shortest paragraph in the member and the only browse paragraph
     * without a response evaluation.
     *
     * <p>A relational read has no browse handle to release, so what this releases is the window of rows
     * the sequential browse was holding, which is what stops a per-call holder from keeping rows alive
     * after the page has been assembled. It cannot fail, and that is deliberate: because the legacy
     * captures no response here, an invalid request would have been left to CICS, and this member wires
     * no abend handler of its own. Raising anything from this method would be inventing an abend path
     * that the source does not have.
     */
    private void endbrTransactFile(final BrowseState state) {
        // Lines 694-696.
        if (state.browse != null) {
            state.browse.close();
            state.browse = null;
        }
        LOG.trace("Transaction list browse ended: rule=endbr");
    }

    // ------------------------------------------------------------------------------------------
    // Supporting steps: the inline statements the paragraphs above perform
    // ------------------------------------------------------------------------------------------

    /**
     * Positions a browse, returning it when the position exists and {@code null} for the not-found
     * response. The three positioning cases the source produces are all handled here.
     *
     * <p>A record identification field of <strong>high values</strong> can be matched by no key at all,
     * so it reports not found without touching the database. That is the field the eighth program
     * function key sets when no last boundary key was retained, line 260.
     *
     * <p>A field of <strong>low values</strong> means the low end of the cluster. On an ascending walk
     * that is simply the first row, so nothing is skipped. On a descending walk the position is the
     * <em>lowest</em> stored key, which the descending sequence reaches last, so it is resolved to that
     * key first and an empty table reports not found. That is the field the seventh program function key
     * sets when no first boundary key was retained, line 237.
     *
     * <p>Otherwise the walk advances until it reaches the field, which is the greater-or-equal
     * positioning the browse command performs by default. Ascending, that lands on the lowest key not
     * less than the field, which is exact. Descending, it lands on the highest key not greater than the
     * field, which is the same record whenever the field names a row that exists - and it always does
     * here, because a boundary key is a key this screen has just displayed and the estate has no path
     * that deletes a transaction.
     */
    private SequentialBrowse openBrowse(final BrowseState state, final boolean ascending) {
        if (state.ridfldHighValues) {
            return null;
        }
        String recordIdentification = state.ridfldKey;
        if (recordIdentification == null && !ascending) {
            recordIdentification = lowestTransactionId();
            if (recordIdentification == null) {
                return null;
            }
        }
        final SequentialBrowse browse = new SequentialBrowse(transactionScanRepository, ascending);
        if (browse.position(recordIdentification)) {
            return browse;
        }
        browse.close();
        return null;
    }

    /**
     * Reads the lowest stored transaction identifier, or {@code null} when the table holds no rows.
     *
     * <p>Used only to resolve a low-values record identification field for a descending browse. One
     * bounded ascending read of a single row answers it: a blank bound is below every stored identifier,
     * so the inclusive forward read opens at the first row of the sequence and one row is all that is
     * wanted. Reading a whole window here would fetch ten rows to use one.
     *
     * <p>The table starts empty after the reference-data seed - rows reach it only from the posting, the
     * interest run and the online add path - so an empty result is a normal outcome here and not an
     * error.
     */
    private String lowestTransactionId() {
        final List<Transaction> firstRow = transactionScanRepository
                .findByTranIdGreaterThanEqualOrderByTranIdAsc(BLANK, Limit.of(1));
        return firstRow.isEmpty() ? null : firstRow.get(0).getTranId();
    }

    /**
     * Applies the catch-all arm shared by the three browse paragraphs, lines 612-618, 646-652 and
     * 680-686: record the failure, raise the error flag, emit the unable-to-look-up message, place the
     * cursor and send the screen.
     *
     * <p>The legacy writes the response and reason codes to the operator console with a display
     * statement. The structured equivalent names the command that failed and, when a failure object
     * exists, the sanitised chain of failure types - never the failure itself. A data-access failure's
     * message is composed by the driver and routinely carries the statement it could not run and the
     * values bound into it, so handing the object to an appender would publish caller-supplied data into
     * centralised logging. The module renders such failures as a bounded chain of type names for exactly
     * that reason, and this site follows it.
     *
     * @param state         per-call working storage
     * @param failure       the failure raised, or {@code null} for the invalid-request case where no
     *                      browse was open and there is no failure object to describe
     * @param browseCommand name of the legacy browse command whose arm this is, for the log record
     */
    private void reportBrowseFailure(final BrowseState state, final DataAccessException failure,
            final String browseCommand) {
        if (failure == null) {
            LOG.error("Transaction list browse command was invalid: rule=browse-failure command={}"
                    + " reason=NO OPEN BROWSE", browseCommand);
        } else {
            LOG.error("Transaction list browse command failed: rule=browse-failure command={}"
                    + " failureChain={}", browseCommand,
                    FailureDiagnostics.failureChainOf(failure));
        }
        state.errorFlag = true;
        state.message = MESSAGE_UNABLE_TO_LOOKUP;
        state.focusScreenFieldId = FOCUS_SCREEN_FIELD_ID;
        sendTrnlstScreen(state);
    }

    /**
     * Transfers control to a named program: the transfer statement itself, which appears twice in the
     * member - inline in the selection dispatch at lines 188-195 and at the end of the return paragraph
     * at lines 515-521. Both sites perform the same four assignments before transferring, so they share
     * one implementation here.
     *
     * <p>The originating transaction and program are stamped so the destination can navigate back, the
     * program context is set to the enter state - the zero the source moves at lines 191 and 517, whose
     * condition name is the first-entry one - and the destination is resolved to a route by the
     * navigation service. No route table is declared here: the mapping from a legacy program name to a
     * route belongs to that service, which is also where an unresolvable name fails rather than being
     * quietly redirected. Every name this class passes is one of three compile-time constants naming a
     * program the service knows, so that failure arm is unreachable from here and no abend path is
     * introduced.
     *
     * <p>A transfer ends the legacy program, so no screen is sent afterwards. The flag it raises is what
     * stops the caller from sending one, reproducing lines 115-116 where the send is reached only if the
     * enter-key processing did not transfer.
     */
    private void transferToProgram(final BrowseState state, final String destinationProgram) {
        final ScreenNavigationState nominated = withTransferFields(state.context, destinationProgram);
        final NavigationService.Route target = navigationService.resolveNominatedDestination(
                carriedState(nominated), NavigationService.Route.SIGN_ON);
        state.context = nominated.withFirstEntry();
        state.route = target;
        state.controlTransferred = true;
        LOG.debug("Transaction list transferring control: rule=xctl destinationProgram={} route={}",
                destinationProgram, target.getRouteValue());
    }

    /**
     * Blanks the screen area, reproducing the move of low values into the output map at line 114.
     *
     * <p>The output map group <em>redefines</em> the input map group, so the two share storage and this
     * single move discards every submitted field as well as every displayed one. That is the mechanism
     * behind the re-enter gate: a first entry never receives the map and then blanks the area, so a
     * filter or a row selector submitted on a first entry has no effect whatsoever. Reproducing it
     * explicitly is what makes that visible instead of accidental.
     */
    private static void clearScreenFieldsToLowValues(final BrowseState state) {
        state.receivedFilter = BLANK;
        state.receivedSelectors = List.of();
        state.receivedTransactionIds = List.of();
        state.transactionIdFilterEcho = BLANK;
        for (int screenRow = FIRST_SCREEN_ROW; screenRow <= SCREEN_ROW_COUNT; screenRow++) {
            state.screenRows[screenRow - 1] = null;
        }
    }

    /**
     * Assembles the outcome of the turn from the per-call working storage.
     *
     * <p><strong>The rows are collected in ascending slot order, and that is where the backward page's
     * reversal happens.</strong> A forward page filled slots one upward, so ascending collection returns
     * the read order. A backward page filled slots ten downward, so the same ascending collection returns
     * <em>the reverse of the read order</em> - which is the legacy presentation order, identical in
     * appearance to a forward page. Reproducing the reversal through the slot positions rather than by
     * reversing a list is not a stylistic choice: it also carries the bottom-alignment of a short
     * backward page, which a list reversal would silently lose.
     *
     * <p>Absent slots contribute nothing, so a short page carries fewer rows and is never padded.
     *
     * <p>The paging direction is taken from the attention key, which is the only place the legacy records
     * it - there is no direction field in the communication area. The seventh program function key is the
     * backward request and everything else, including a first entry and a blocked forward request, is
     * reported as forward. Whether a further page exists comes from the echoed next-page flag as the
     * paging paragraphs settled it, and whether one precedes is the same first-page test the seventh key
     * itself applies at line 245. Both boundary keys travel on every turn because the legacy retains both
     * at once.
     *
     * <p>The route defaults to this screen itself when no transfer occurred, which is the legacy's own
     * behaviour: it re-arms its own transaction at lines 138-141 and the operator stays on the list.
     */
    private TransactionListResult buildResult(final BrowseState state,
            final TransactionListCommand command) {
        final List<TransactionListRow> rows = new ArrayList<>(SCREEN_ROW_COUNT);
        for (int screenRow = FIRST_SCREEN_ROW; screenRow <= SCREEN_ROW_COUNT; screenRow++) {
            final Transaction record = state.screenRows[screenRow - 1];
            if (record != null) {
                rows.add(toRow(screenRow, record));
            }
        }

        final String displayedPageNumber = displayedPageNumberImage(state.pageNumber);
        final boolean hasPreviousPages = state.pageNumber > FIRST_PAGE_NUMBER;
        final BrowseWindow pageMetadata = command.keyAction() == KeyAction.PFK07
                ? BrowseWindow.backward(SCREEN_ROW_COUNT, state.firstTransactionId,
                        state.lastTransactionId, state.nextPageAvailable, hasPreviousPages,
                        displayedPageNumber)
                : BrowseWindow.forward(SCREEN_ROW_COUNT, state.firstTransactionId,
                        state.lastTransactionId, state.nextPageAvailable, hasPreviousPages,
                        displayedPageNumber);

        final NavigationService.Route route =
                (state.route == null) ? NavigationService.Route.TRANSACTION_LIST : state.route;

        return new TransactionListResult(
                route,
                state.context,
                rows,
                pageMetadata,
                state.presentedMessage,
                state.fieldErrors,
                state.focusScreenFieldId,
                state.errorFlag,
                state.context.reEntry(),
                state.sendErase,
                state.transactionIdFilterEcho,
                state.selectedTransactionId,
                state.screenTitle01,
                state.screenTitle02,
                state.currentDate,
                state.currentTime,
                TRANSACTION_ID,
                PROGRAM_NAME);
    }

    /**
     * Projects one stored record onto one presentation row, carrying every value exactly as stored.
     *
     * <p>The source code keeps its trailing spaces and is never mapped to an enumerated type here; the
     * amount is carried as the fixed-scale decimal it is stored as, unscaled and unrounded; both
     * timestamps are carried as the bounded twenty-six-character strings they are stored as; and the four
     * merchant values use this entity's own unprefixed property names rather than the prefixed forms of
     * the byte-identical daily-transaction record.
     *
     * <p>The whole record is carried, not only the four columns the screen shows, because the legacy read
     * places the whole record in its record area and the choice of which columns to display is a
     * presentation decision made above this layer.
     */
    private static TransactionListRow toRow(final int screenRow, final Transaction record) {
        return new TransactionListRow(
                screenRow,
                record.getTranId(),
                record.getTranTypeCd(),
                record.getTranCatCd(),
                record.getTranSource(),
                record.getTranDesc(),
                record.getTranAmt(),
                record.getMerchantId(),
                record.getMerchantName(),
                record.getMerchantCity(),
                record.getMerchantZip(),
                record.getTranCardNum(),
                record.getTranOrigTs(),
                record.getTranProcTs());
    }

    /**
     * Returns a copy of the navigation state carrying the originating transaction and program of this
     * screen and the nominated destination program, with every other field left as it stands.
     *
     * <p>Reproduces the three assignments the two transfer sites share, at lines 189-190 and 515-516 for
     * the originating pair and at lines 188 and 512-514 for the destination. The state is an immutable
     * record with no per-field builder, so a copy is built through its canonical constructor.
     */
    private static ScreenNavigationState withTransferFields(final ScreenNavigationState context,
            final String destinationProgram) {
        return new ScreenNavigationState(
                TRANSACTION_ID,
                PROGRAM_NAME,
                context.toTransactionId(),
                destinationProgram,
                context.userId(),
                context.userType(),
                context.programContext(),
                context.customerId(),
                context.customerFirstName(),
                context.customerMiddleName(),
                context.customerLastName(),
                context.accountId(),
                context.accountStatus(),
                context.cardNumber(),
                context.lastMap(),
                context.lastMapset());
    }

    /**
     * Renders the displayed page indicator as the legacy move produces it: an eight-digit unsigned page
     * number moved into an eight-character alphanumeric map field at lines 324 and 373, which transfers
     * eight zero-filled digits.
     *
     * <p>The remainder reproduces the truncation an unsigned eight-digit field applies to a larger value,
     * which keeps the rendering total for every page number the command accepts. The root locale is fixed
     * so the digits cannot vary with a host default.
     */
    private static String displayedPageNumberImage(final int pageNumber) {
        return String.format(Locale.ROOT, DISPLAYED_PAGE_NUMBER_FORMAT,
                pageNumber % DISPLAYED_PAGE_NUMBER_MODULUS);
    }

    /**
     * Renders a row's legacy selector field name - the families {@code SEL0001} through {@code SEL0010}
     * the selection evaluation scans at lines 149 to 178 - so a field error can name the screen field the
     * operator must correct.
     */
    private static String rowSelectorScreenFieldId(final int screenRow) {
        return String.format(Locale.ROOT, ROW_SELECTOR_FIELD_FORMAT, screenRow);
    }

    /**
     * The COBOL numeric class test of line 209: true when every character present is a digit.
     *
     * <p>The identifier is never parsed as a number, here or anywhere else in this class. The test is a
     * character-class test and the value that passes it is used as characters, which is what preserves the
     * leading zeros the sixteen-character key depends on.
     *
     * <p>The legacy test is applied to a fixed-width screen field, so a partially typed entry fails it as
     * well, because the unfilled positions hold a non-digit fill character. At this boundary the filter
     * arrives as a logical value rather than as a fixed-width image, so that width component of the test
     * has no counterpart and is recorded as a decision-log entry. An empty value never reaches this
     * method: the blank test at line 206 takes it first.
     */
    private static boolean isCobolNumeric(final String image) {
        if (image == null || image.isEmpty()) {
            return false;
        }
        for (int position = 0; position < image.length(); position++) {
            final char character = image.charAt(position);
            if (character < ASCII_ZERO || character > ASCII_NINE) {
                return false;
            }
        }
        return true;
    }

    /**
     * The COBOL blank test the member writes as "equal to spaces or low values", used at lines 149-178,
     * 183-184, 206, 236 and 259.
     *
     * <p>It is not the conventional Java blank test. A fixed-width field is blank when every position
     * holds a space or a null, and a value containing any other white space is <em>not</em> blank - a tab
     * would make the field non-blank in the legacy and must do so here. An absent value is treated as
     * blank, which is what a field that was never transmitted amounts to.
     */
    private static boolean isBlank(final String value) {
        if (value == null) {
            return true;
        }
        for (int position = 0; position < value.length(); position++) {
            final char character = value.charAt(position);
            if (character != SPACE && character != LOW_VALUE) {
                return false;
            }
        }
        return true;
    }

    /** The negation of the blank test, for the sites the source writes as "not equal to spaces". */
    private static boolean isPresent(final String value) {
        return !isBlank(value);
    }

    /** Substitutes the blank a fixed-width field would hold for an absent value. */
    private static String nullToBlank(final String value) {
        return (value == null) ? BLANK : value;
    }

    /**
     * Reports a blank boundary key as absent, so the paging contract carries {@code null} rather than a
     * string of spaces where the legacy field holds spaces or low values.
     */
    private static String blankToNull(final String value) {
        return isBlank(value) ? null : value;
    }

    /**
     * Reads a one-based screen row from a submitted field list, treating a list shorter than the screen
     * as though the missing rows were blank - which is what an untransmitted field amounts to.
     */
    private static String elementAt(final List<String> values, final int screenRow) {
        final int index = screenRow - 1;
        return (index < values.size()) ? values.get(index) : null;
    }

    /**
     * Defensive, unmodifiable copy of a submitted field list, admitting absent entries because a screen
     * field that was not transmitted is legitimately absent. An absent list becomes an empty one.
     */
    private static List<String> immutableCopy(final List<String> values) {
        return (values == null) ? List.of() : Collections.unmodifiableList(new ArrayList<>(values));
    }

    /**
     * Defensive, unmodifiable copy of the field-error list, so a result cannot be mutated after the turn
     * that produced it has ended.
     */
    private static List<ValidationException.FieldError> immutableFieldErrors(
            final List<ValidationException.FieldError> values) {
        return (values == null) ? List.of() : Collections.unmodifiableList(new ArrayList<>(values));
    }

    /**
     * Defensive, unmodifiable copy of the presentation rows, on the same terms.
     */
    private static List<TransactionListRow> immutableRows(final List<TransactionListRow> values) {
        return (values == null) ? List.of() : Collections.unmodifiableList(new ArrayList<>(values));
    }

    // ------------------------------------------------------------------------------------------
    // Inbound and outbound shapes
    // ------------------------------------------------------------------------------------------

    /**
     * One transaction-list turn as submitted: the attention key, the echoed navigation state, the fields
     * the screen carried and the screen's own paging state.
     *
     * <p>Nested here rather than declared as a further top-level type because it is the input shape of
     * this one service and of nothing else. It is a service-tier command, not a wire contract: the
     * request body a controller binds is a separate shape, and mapping between them is the controller's
     * work.
     *
     * <p>The three paging components are the screen's private communication-area group, which the client
     * echoes because the service holds no session state. The legacy carried exactly the same values
     * across a pseudo-conversational turn, so echoing them is the faithful arrangement rather than a
     * concession.
     *
     * @param keyAction the attention key the operator pressed. <strong>Mandatory</strong>, because the
     *     legacy always has one and two guards branch on it - the forward guard at lines 285-287 and the
     *     backward guard at lines 339-341 - so an absent key would silently change which record a page
     *     starts at. A first entry arrives on the enter key. Keys above the twelfth are not representable
     *     and would in any case fall to the invalid-key arm, because this member performs no key folding
     * @param navigationContext the echoed navigation state. An absent or wholly empty value is the
     *     zero-length communication area of line 107 and routes to sign-on; an absent value is
     *     normalised to the empty one so nothing downstream sees {@code null}
     * @param transactionIdFilter the transaction-id filter field, sixteen alphanumeric characters on the
     *     map. Blank browses from the low end of the cluster, line 207; a value that passes the numeric
     *     class test positions the browse on it, line 210; anything else is rejected with the
     *     not-numeric message, lines 212-217. Carried verbatim - never trimmed, padded or parsed
     * @param rowSelectors the ten row selector fields, one character each, scanned in ascending row
     *     order at lines 149-178. A shorter list is treated as though the missing rows were blank. Never
     *     {@code null} as constructed
     * @param displayedTransactionIds the ten displayed transaction identifiers the screen echoes, from
     *     which the selected one is taken at lines 151-178. Same absence rule; never {@code null} as
     *     constructed
     * @param pageCursor the two boundary keys the previous response reported. The first key is what a
     *     backward request repositions on, line 239, and the last is what a forward request repositions
     *     on, line 262. Its direction component is deliberately <strong>not consulted</strong>: the
     *     legacy records the direction nowhere but the attention key, so the key is authoritative and a
     *     supplied direction cannot contradict it. An absent value is normalised to one carrying neither
     *     key
     * @param nextPageAvailable whether the previous turn found a page beyond the one it displayed - the
     *     screen's next-page flag. It is the guard the eighth program function key tests at line 267 and
     *     the condition the backward paragraph consults at line 361, and it can only be carried forward
     *     because the legacy discovers it by attempting one more read
     * @param currentPageNumber the page number the previous turn settled on, an unsigned eight-digit
     *     field. Used as a decision only by the first-page tests at lines 245 and 363, and otherwise
     *     displayed. Must not be negative, because the legacy field is unsigned and cannot hold one
     */
    public record TransactionListCommand(
            KeyAction keyAction,
            ScreenNavigationState navigationContext,
            String transactionIdFilter,
            List<String> rowSelectors,
            List<String> displayedTransactionIds,
            BrowseWindow.CursorRequest pageCursor,
            boolean nextPageAvailable,
            int currentPageNumber) {

        /**
         * Canonical constructor. Requires an attention key, rejects a negative page number, and
         * normalises the three optional aggregates so nothing downstream has to null-check them. No
         * string is trimmed, padded, re-cased or otherwise altered: the selector comparison and the
         * filter class test both depend on the characters exactly as submitted.
         *
         * @throws NullPointerException     if {@code keyAction} is {@code null}
         * @throws IllegalArgumentException if {@code currentPageNumber} is negative
         */
        public TransactionListCommand {
            Objects.requireNonNull(keyAction, "keyAction must not be null: the legacy always has an"
                    + " attention key and two paging guards branch on it");
            if (currentPageNumber < 0) {
                throw new IllegalArgumentException("currentPageNumber must not be negative: the legacy"
                        + " page number is an unsigned eight-digit field");
            }
            navigationContext = (navigationContext == null)
                    ? ScreenNavigationState.empty() : navigationContext;
            rowSelectors = immutableCopy(rowSelectors);
            displayedTransactionIds = immutableCopy(displayedTransactionIds);
            pageCursor = (pageCursor == null)
                    ? new BrowseWindow.CursorRequest(null, null, null) : pageCursor;
        }

        /**
         * Renders the command with every record key withheld.
         *
         * <p>The filter, the echoed identifiers and both boundary cursors are all transaction keys, and a
         * transaction key names the transaction it belongs to. Stringifying this object into a log line,
         * an exception message or a test-failure report would put them into channels with none of the
         * protections a response body has. The attention key, the counts and the paging flags are not
         * sensitive and are rendered as they stand.
         *
         * <p>Only the rendering changes: every accessor, the equality contract and the hash contract
         * continue to carry the withheld values in full.
         *
         * @return the paging and key state, with every record key replaced by a fixed placeholder
         */
        @Override
        public String toString() {
            return "TransactionListCommand["
                    + "keyAction=" + keyAction
                    + ", navigationContext=" + navigationContext
                    + ", transactionIdFilter=" + REDACTION_PLACEHOLDER
                    + ", rowSelectors=" + rowSelectors
                    + ", displayedTransactionIds=" + REDACTION_PLACEHOLDER
                    + ", pageCursor=" + pageCursor
                    + ", nextPageAvailable=" + nextPageAvailable
                    + ", currentPageNumber=" + currentPageNumber
                    + "]";
        }
    }

    /**
     * The outcome of one transaction-list turn.
     *
     * <p>A service-tier result, not an HTTP response: it carries no status and no response entity, and
     * the route travels as a value so the client drives the next call. That is what replaces the legacy
     * transfer-control statement, which forwarded on the server and therefore could not be tested a
     * screen at a time.
     *
     * @param route the destination this turn resolved to. This screen itself on every turn that stayed
     *     on the list, which is the legacy re-arming its own transaction at lines 138-141; the
     *     transaction-view screen when a row was selected, line 188; the user main menu on the third
     *     program function key, line 123; and sign-on when the turn carried no navigation state, line 108
     * @param navigationContext the navigation state to echo on the next call, with this screen stamped as
     *     the originating one on every transfer and the program context set to the enter state, lines
     *     515-517
     * @param rows the page's rows <strong>in presentation order</strong>, at most ten and never padded.
     *     For a backward page this is the reverse of the order the rows were read in, which is what
     *     reproduces the legacy fill from slot ten down to slot one. Unmodifiable, never {@code null}
     * @param pageMetadata the paging state: page size, both boundary cursors, the direction the caller
     *     asked for, the two independent end-of-browse indicators and the displayed page indicator
     * @param message the message the screen displays, being whatever the message field held at the last
     *     send of the turn, line 531. Blank on the three transfer paths, which send nothing at all
     * @param fieldErrors per-field detail for the two field-level failures this screen has - a row
     *     selected with an unsupported action and a filter that fails the numeric class test. Both are
     *     the supplied-but-invalid state; the not-supplied state cannot arise here, because this member
     *     includes no field-decoration macro and never distinguishes a blank field from a bad one.
     *     Unmodifiable, never {@code null}, and empty on a turn that had no field failure
     * @param focusScreenFieldId the screen field the cursor is placed on, invariantly the transaction-id
     *     filter for this screen
     * @param error whether the error flag was raised. Deliberately independent of the message: the
     *     already-at-the-top and already-at-the-bottom reports and the browse's own not-found report all
     *     carry a message with the flag <em>off</em>, exactly as the source leaves it
     * @param reEntry the program context this turn ends with. Set on a first entry at line 113 so the
     *     next turn is treated as a re-entry, and reset to the enter state by a transfer at line 517
     * @param eraseScreen whether the screen was sent erasing. Cleared by the two already-at-the-boundary
     *     reports at lines 250 and 272, which is how the legacy leaves the rows already displayed in
     *     place; a client seeing this false with no rows returned keeps the rows it has
     * @param transactionIdFilterEcho the filter value to redisplay. Blanked after a successful page at
     *     line 325 and at lines 227-229, and retained when the turn produced an error so the operator can
     *     see what was rejected
     * @param selectedTransactionId the identifier a row selection nominated, which the transaction-view
     *     screen consumes. Blank when no row was selected
     * @param screenTitle01 first screen title, from the common title catalogue, line 571
     * @param screenTitle02 second screen title, line 572
     * @param currentDate the header date the header paragraph renders, lines 576-580
     * @param currentTime the header time, lines 582-586
     * @param transactionName this screen's own transaction identifier, line 573
     * @param programName this screen's own legacy program name, line 574
     */
    public record TransactionListResult(
            NavigationService.Route route,
            ScreenNavigationState navigationContext,
            List<TransactionListRow> rows,
            BrowseWindow pageMetadata,
            String message,
            List<ValidationException.FieldError> fieldErrors,
            String focusScreenFieldId,
            boolean error,
            boolean reEntry,
            boolean eraseScreen,
            String transactionIdFilterEcho,
            String selectedTransactionId,
            String screenTitle01,
            String screenTitle02,
            String currentDate,
            String currentTime,
            String transactionName,
            String programName) {

        /**
         * Canonical constructor. Copies both collections defensively into unmodifiable views so a result
         * cannot be altered after the turn that produced it, and leaves every string exactly as supplied -
         * the catalogue's space padding and the legacy message text both survive byte for byte.
         */
        public TransactionListResult {
            rows = immutableRows(rows);
            fieldErrors = immutableFieldErrors(fieldErrors);
        }

        /**
         * Renders the result with the rows, the echoed filter and the selected identifier withheld, for
         * the reason the command withholds its own keys: all three carry transaction identifiers, and the
         * rows additionally carry a card number and an amount.
         *
         * @return the paging and screen state, with every regulated component replaced by a placeholder
         */
        @Override
        public String toString() {
            return "TransactionListResult["
                    + "route=" + route
                    + ", navigationContext=" + navigationContext
                    + ", rows=" + REDACTION_PLACEHOLDER
                    + ", rowCount=" + rows.size()
                    + ", pageMetadata=" + pageMetadata
                    + ", message=" + message
                    + ", fieldErrors=" + fieldErrors
                    + ", focusScreenFieldId=" + focusScreenFieldId
                    + ", error=" + error
                    + ", reEntry=" + reEntry
                    + ", eraseScreen=" + eraseScreen
                    + ", transactionIdFilterEcho=" + REDACTION_PLACEHOLDER
                    + ", selectedTransactionId=" + REDACTION_PLACEHOLDER
                    + ", screenTitle01=" + screenTitle01
                    + ", screenTitle02=" + screenTitle02
                    + ", currentDate=" + currentDate
                    + ", currentTime=" + currentTime
                    + ", transactionName=" + transactionName
                    + ", programName=" + programName
                    + "]";
        }
    }

    /**
     * One row of a transaction-list page: the stored record as read, plus the screen slot it occupies.
     *
     * <p>Every value is carried exactly as stored. Nothing is trimmed - the source code keeps its trailing
     * spaces, which downstream byte-parity checks depend on; nothing is scaled or rounded - the amount is
     * the fixed-scale decimal the column declares and the estate has no rounding clause anywhere; nothing
     * is converted temporally - both timestamps stay the bounded twenty-six-character strings they are
     * stored as; and nothing is mapped to an enumerated type at this layer.
     *
     * <p>The whole record is carried, not only the four columns the screen displays, because the legacy
     * read places the whole record in its record area. Which columns reach a client is a presentation
     * decision above this layer, and the screen's own response shape narrows it to the four.
     *
     * @param screenRow the one-based slot this record occupies, one through ten. It is the legacy row
     *     index, and it is the component that makes the backward fill visible: a backward page's first
     *     read lands in slot ten and its last in slot one
     * @param tranId the transaction identifier, sixteen alphanumeric characters, carried as characters and
     *     never as a number so its leading zeros survive
     * @param tranTypeCd the transaction type code
     * @param tranCatCd the transaction category code
     * @param tranSource the source code, a raw space-padded string. <strong>Never trimmed and never an
     *     enumerated type here</strong>
     * @param tranDesc the description, as stored at its full width rather than at the narrower width the
     *     screen shows
     * @param tranAmt the amount, a decimal at the column's own two-decimal scale. Never a binary
     *     floating-point type, and neither scaled nor rounded by this class
     * @param merchantId the merchant identifier - one of the four properties this entity names without the
     *     regular prefix, unlike the byte-identical daily-transaction record
     * @param merchantName the merchant name, unprefixed on the same terms
     * @param merchantCity the merchant city, unprefixed on the same terms
     * @param merchantZip the merchant postal code, unprefixed on the same terms
     * @param tranCardNum the card number the transaction was made on, carried as stored and withheld from
     *     the diagnostic rendering
     * @param tranOrigTs the origination timestamp, twenty-six characters. The screen's date column is
     *     derived from parts of this value at lines 384-388, and that derivation belongs to the
     *     presentation boundary rather than here
     * @param tranProcTs the processing timestamp, twenty-six characters, carried on the same terms
     */
    public record TransactionListRow(
            int screenRow,
            String tranId,
            String tranTypeCd,
            String tranCatCd,
            String tranSource,
            String tranDesc,
            BigDecimal tranAmt,
            String merchantId,
            String merchantName,
            String merchantCity,
            String merchantZip,
            String tranCardNum,
            String tranOrigTs,
            String tranProcTs) {

        /**
         * Renders the row with every regulated value withheld: the identifier, the description, the
         * amount, the card number and all four merchant values. The card number alone settles the
         * question - it is a primary account number in full - and a partial mask was not used, because a
         * fragment of a fixed-width numeric key is recoverable by enumeration.
         *
         * <p>The slot index, the type code and the category code name no cardholder and are rendered as
         * they stand, which is enough for a log line to say which slot of which page is being discussed.
         * Only the rendering changes; every accessor returns its value untouched.
         *
         * @return the row's position and classification, with every regulated value replaced by a
         *     placeholder
         */
        @Override
        public String toString() {
            return "TransactionListRow["
                    + "screenRow=" + screenRow
                    + ", tranId=" + REDACTION_PLACEHOLDER
                    + ", tranTypeCd=" + tranTypeCd
                    + ", tranCatCd=" + tranCatCd
                    + ", tranSource=" + tranSource
                    + ", tranDesc=" + REDACTION_PLACEHOLDER
                    + ", tranAmt=" + REDACTION_PLACEHOLDER
                    + ", merchantId=" + REDACTION_PLACEHOLDER
                    + ", merchantName=" + REDACTION_PLACEHOLDER
                    + ", merchantCity=" + REDACTION_PLACEHOLDER
                    + ", merchantZip=" + REDACTION_PLACEHOLDER
                    + ", tranCardNum=" + REDACTION_PLACEHOLDER
                    + ", tranOrigTs=" + tranOrigTs
                    + ", tranProcTs=" + tranProcTs
                    + "]";
        }
    }

    // ------------------------------------------------------------------------------------------
    // Per-call working storage and the browse it drives
    // ------------------------------------------------------------------------------------------

    /**
     * The legacy working storage of one turn, recreated per invocation.
     *
     * <p>{@code COTRN00C} keeps its state in two places: a working-storage group holding the error flag,
     * the end-of-file flag, the erase flag, the message and the row index, and a private group appended to
     * the shared communication area holding the two boundary keys, the page number, the next-page flag and
     * the selection. Both are per-turn state, and this holder is what lets the service that translates
     * them stay a stateless singleton: it is created at the top of a turn, passed down through the
     * paragraph methods, and discarded when the result is built, so two concurrent callers share nothing.
     *
     * <p>Fields are package-private within the enclosing class rather than exposed through accessors, which
     * keeps the paragraph methods reading like the assignments they translate. Nothing outside this file
     * can reach them.
     */
    private static final class BrowseState {

        /** The error flag, whose condition names the source tests as on and off. */
        private boolean errorFlag;

        /** The end-of-file flag, distinct from the error flag throughout - see the browse paragraphs. */
        private boolean endOfFile;

        /** The erase flag, defaulting to erasing as the source's own initial value does. */
        private boolean sendErase = true;

        /** The message work field, blanked at line 102. */
        private String message = BLANK;

        /** The screen's error message field, which the send paragraph copies the work field into. */
        private String presentedMessage = BLANK;

        /** The ten screen slots. An absent entry is a blanked slot and contributes no row. */
        private final Transaction[] screenRows = new Transaction[SCREEN_ROW_COUNT];

        /** The page's first boundary key, written only from slot one at lines 392-393. */
        private String firstTransactionId;

        /** The page's last boundary key, written only from slot ten at lines 438-439. */
        private String lastTransactionId;

        /** The page number, an unsigned eight-digit field in the private group. */
        private int pageNumber;

        /** The next-page flag, restored from the echoed state at line 111. */
        private boolean nextPageAvailable;

        /** The selection action a row carried, from lines 150-177. */
        private String selectionFlag = BLANK;

        /** The identifier the selected row carried, from lines 151-178. */
        private String selectedTransactionId = BLANK;

        /**
         * The record identification field the browse positions on - the record key the source moves a
         * filter, a boundary key, low values or high values into. Absent means low values.
         */
        private String ridfldKey;

        /** Whether that field holds high values, which no key can match. Set only at line 260. */
        private boolean ridfldHighValues;

        /** The open browse, or absent when none is open - the invalid-request case of the read paragraphs. */
        private SequentialBrowse browse;

        /** Whether a transfer ended the program, so no screen may be sent afterwards. */
        private boolean controlTransferred;

        /** The route a transfer resolved to, or absent when the turn stayed on this screen. */
        private NavigationService.Route route;

        /** The navigation state as this turn leaves it. */
        private ScreenNavigationState context = ScreenNavigationState.empty();

        /** Field-level detail for the two field failures this screen can report. */
        private final List<ValidationException.FieldError> fieldErrors = new ArrayList<>();

        /** The screen field the cursor sits on, invariantly the filter field for this screen. */
        private String focusScreenFieldId = FOCUS_SCREEN_FIELD_ID;

        /** The filter value to redisplay, blanked after a successful page. */
        private String transactionIdFilterEcho = BLANK;

        /** First screen title, from the common catalogue. */
        private String screenTitle01 = BLANK;

        /** Second screen title. */
        private String screenTitle02 = BLANK;

        /** Header date, rendered from the injected clock. */
        private String currentDate = BLANK;

        /** Header time, rendered from the same instant. */
        private String currentTime = BLANK;

        /** The filter field as received, or blank when the map was not received on this turn. */
        private String receivedFilter = BLANK;

        /** The row selectors as received, empty when the map was not received. */
        private List<String> receivedSelectors = List.of();

        /** The echoed row identifiers as received, empty when the map was not received. */
        private List<String> receivedTransactionIds = List.of();
    }

    /**
     * The four legacy browse commands over the repository's bounded ordered reads.
     *
     * <p>A CICS browse is a positioned cursor: one command opens it on a key, two advance it in either
     * direction one record at a time, and one closes it. Each of the four maps onto one read of
     * {@link TransactionScanRepository}, whose whole surface is ordered and bounded by an explicit
     * limit: the open is the inclusive read in the walk's direction, the advance is the exclusive read
     * strictly past the last record handed out, and the close releases the window this browse holds.
     *
     * <p><strong>Positioning is pushed into the query, not walked up to.</strong> The open reads at or
     * beyond the record identification field and its first row <em>is</em> the positioned record, so a
     * boundary key deep in the sequence costs exactly what a boundary key near its start costs. The
     * earlier form walked ordered windows from the beginning of the sequence, discarding every row
     * before the key; that is what this replaces.
     *
     * <p><strong>Deriving a window index from the page number was rejected, and so was walking to the
     * key.</strong> The legacy repositions on a retained record key and holds no row number. An index
     * derived from a page number is correct only until a row is inserted, after which it silently skips
     * or repeats rows - a correctness defect, not a cost difference. A key-ordered read has neither
     * problem: a row inserted between two turns simply appears, or does not, at its own key, exactly as
     * it would to the legacy browse.
     *
     * <p>Every read is delivered through {@link #read()}, which returns the positioned record first.
     * That reproduces the CICS rule that a read issued immediately after the open returns the record the
     * identification field names, which is precisely what the two paging guards rely on when they
     * discard one record to step past the boundary key.
     *
     * <p>Each window is one screen's worth of rows plus one, which is also exactly what the legacy reads
     * per page: ten rows and one further read to learn whether another page follows. A full page
     * therefore costs one query rather than eleven, and no query can return more than eleven rows
     * however large the table becomes.
     *
     * <p>Per-turn state, created and closed inside a single call, so nothing here is shared between
     * callers.
     */
    private static final class SequentialBrowse {

        /** Rows read per query: one screen plus the one further read the paging guard makes. */
        private static final int WINDOW_ROWS = SCREEN_ROW_COUNT + 1;

        private final TransactionScanRepository repository;

        /** Whether the walk reads the key sequence upward. */
        private final boolean ascending;

        /**
         * The exclusive bound of the next continuation read: the identifier of the row most recently
         * handed out, or {@code null} before the open.
         *
         * <p>A business key and not an ordinal, which is the property that makes the walk immune to a
         * concurrent insert or delete shifting it.
         */
        private String cursorKey;

        /** Rows of the window currently held, in read order. */
        private List<Transaction> window = List.of();

        /** Position within that window. */
        private int windowCursor;

        /** Whether the ordered sequence has been walked to its end in this direction. */
        private boolean sequenceExhausted;

        /** The record the identification field named, held until the first read consumes it. */
        private Transaction positionedRecord;

        SequentialBrowse(final TransactionScanRepository repository, final boolean ascending) {
            this.repository = repository;
            this.ascending = ascending;
        }

        /**
         * Positions the browse, reporting whether a position exists at all - the difference between the
         * normal and the not-found responses of the open command.
         *
         * <p>One inclusive bounded read does the whole job. Ascending, it returns the lowest key not
         * below the identification field, which is the greater-or-equal positioning the command performs
         * by default; descending, the highest key not above it. Nothing is read that the walk intends to
         * discard, and the rows after the first are the walk's read-ahead.
         *
         * <p>An absent field means the low end of the sequence: ascending, a blank bound is below every
         * stored identifier and so opens at the first row. The descending caller resolves it to the
         * lowest stored key before calling, because that key is where a descending walk from the low end
         * must start - a blank bound read backwards would position nowhere.
         *
         * <p>The bound is applied by the store on a column whose stored identifiers are sixteen
         * zero-padded digit characters, so character order and numeric order coincide. That is the same
         * precondition the repository's maximum-identifier query depends on, and one every writer in
         * this module is obliged to preserve. No key comparison happens here at all, which is one fewer
         * place for that precondition to be got wrong.
         *
         * @param recordIdentification the key to position on, or {@code null} for the low end
         * @return {@code true} when a record was positioned on, {@code false} for the not-found response
         */
        boolean position(final String recordIdentification) {
            final String bound = recordIdentification == null ? BLANK : recordIdentification;
            final Limit rows = Limit.of(WINDOW_ROWS);
            final List<Transaction> opened = this.ascending
                    ? this.repository.findByTranIdGreaterThanEqualOrderByTranIdAsc(bound, rows)
                    : this.repository.findByTranIdLessThanEqualOrderByTranIdDesc(bound, rows);
            if (opened.isEmpty()) {
                this.sequenceExhausted = true;
                return false;
            }
            this.positionedRecord = opened.get(0);
            this.cursorKey = this.positionedRecord.getTranId();
            this.window = opened.subList(1, opened.size());
            this.windowCursor = 0;
            this.sequenceExhausted = opened.size() < WINDOW_ROWS;
            return true;
        }

        /**
         * Advances the browse by one record: the forward and backward read commands, whose direction is
         * carried by the ordering this browse was opened with rather than by the call.
         *
         * @return the next record, or {@code null} for the end-of-file response
         */
        Transaction read() {
            if (positionedRecord != null) {
                final Transaction positioned = positionedRecord;
                positionedRecord = null;
                return positioned;
            }
            return nextOrderedRecord();
        }

        /**
         * Closes the browse, releasing the window it holds so no rows outlive the page they built. There
         * is no handle to release in a relational read, and nothing here can fail - see the paragraph that
         * calls it for why raising anything would invent an abend path this member does not have.
         */
        void close() {
            window = List.of();
            windowCursor = 0;
            sequenceExhausted = true;
            positionedRecord = null;
            cursorKey = null;
        }

        /**
         * Returns the next record of the ordered sequence, fetching the following window strictly past
         * the last key handed out when the held one is spent, and {@code null} once the sequence is
         * exhausted.
         */
        private Transaction nextOrderedRecord() {
            if (windowCursor >= window.size()) {
                if (sequenceExhausted || cursorKey == null) {
                    return null;
                }
                final Limit rows = Limit.of(WINDOW_ROWS);
                window = this.ascending
                        ? repository.findByTranIdGreaterThanOrderByTranIdAsc(cursorKey, rows)
                        : repository.findByTranIdLessThanOrderByTranIdDesc(cursorKey, rows);
                windowCursor = 0;
                if (window.size() < WINDOW_ROWS) {
                    // A short window is the end of the sequence in this direction, so the walk answers
                    // the next end-of-file without another query.
                    sequenceExhausted = true;
                }
                if (window.isEmpty()) {
                    return null;
                }
            }
            final Transaction delivered = window.get(windowCursor++);
            cursorKey = delivered.getTranId();
            return delivered;
        }
    }

    /**
     * Reports whether this turn carries no prior navigation state - the equivalent of the zero-length
     * communication-area test that opens the legacy program's main paragraph.
     *
     * <p>The test is on the <strong>whole</strong> echoed record and not on its four routing fields
     * alone, because a zero-length communication area describes a turn that carries nothing at all: no
     * signed-on user, no selection and no previous screen. Projecting the record down to the routing
     * fields before testing would call a turn stateless while it still carried an identity or a
     * selection, which is a different condition from the one the legacy branches on.
     *
     * @param context the echoed navigation record, which may be {@code null}
     * @return {@code true} when no navigation state was carried into this turn
     */
    private static boolean isNavigationStateAbsent(final ScreenNavigationState context) {
        return context == null || ScreenNavigationState.empty().equals(context);
    }

    /**
     * Projects the echoed navigation record onto the carried state the navigation rules read.
     *
     * <p>Back-navigation and nominated-destination resolution consult the originating and nominated
     * program names and nothing else, so the projection is loss-free for them: it carries the four
     * routing fields and the program-context flag and drops the identity and selection members, which no
     * routing rule reads. The mapping is the one the transport adapter applies, so a routing decision
     * does not depend on which side of the boundary the record was projected on.
     *
     * @param context the echoed navigation record, which may be {@code null}
     * @return the carried state the navigation rules read, never {@code null}
     */
    private static ConversationState carriedState(final ScreenNavigationState context) {
        if (context == null) {
            return ConversationState.empty();
        }
        return new ConversationState(
                context.fromTransactionId(),
                context.fromProgram(),
                context.toTransactionId(),
                context.toProgram(),
                context.reEntry()
                        ? ConversationState.EntryMode.RE_ENTRY
                        : ConversationState.EntryMode.FIRST_ENTRY);
    }
}
