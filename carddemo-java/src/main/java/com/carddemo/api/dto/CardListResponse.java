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
package com.carddemo.api.dto;

import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Immutable response contract for the card-list screen, legacy transaction {@code CCLI}.
 *
 * <p>This is the REST projection of the 3270 screen driven by {@code app/cbl/COCRDLIC.cbl}, a
 * 1,459-line program, and described field by field by the symbolic map
 * {@code app/cpy-bms/COCRDLI.CPY} and the mapset {@code app/bms/COCRDLI.bms}. It carries the screen
 * heading items, the two echoed search filters, the card rows, the paging facts, the two message
 * slots, the field the screen puts the operator's attention on, the route the client calls next, and
 * the echoed navigation state. Every component crosses this boundary exactly as the service supplied
 * it.</p>
 *
 * <h2>What this type deliberately does not do</h2>
 *
 * <p>It is a carrier and nothing else. It performs no ordering, no filtering, no selection
 * interpretation, no tallying, no message choosing, no route resolution and no data access. Those
 * belong to {@code service/CardListService}, which is the component that reproduces the legacy
 * program's logic; this record only conveys what that service decided.</p>
 *
 * <h2>Seven rows, because that is the shape of the screen</h2>
 *
 * <p>The screen presents seven card rows. That number is legacy screen geometry established from
 * the source, not a value chosen here and not anything adjustable:
 * {@code app/cbl/COCRDLIC.cbl} lines 250 to 260 declare a 196-character all-rows area redefined as
 * the table {@code WS-SCREEN-ROWS}, which occurs seven times at line 255. Its element is
 * 28 characters wide - an 11-character account identifier, a 16-character card number and a
 * 1-character status indicator - and 28 multiplied by 7 accounts for all 196 characters exactly. The
 * program's screen-line constant carries the same value, and the symbolic map independently declares
 * seven row families. Changing it would put a different number of rows on the screen, which is a
 * visible behavioural change rather than a setting.</p>
 *
 * <p><strong>A short page stays short.</strong> When fewer than seven cards qualify, the row list is
 * simply shorter. The legacy program clears its row area and then fills only the slots it has data
 * for, leaving the remaining screen lines blank, so no blank filler row is ever manufactured here.
 * The list is never extended to a fixed length.</p>
 *
 * <h2>Row ordering is the service's decision and is preserved verbatim</h2>
 *
 * <p>The rows arrive in the order the screen presents them, and this record emits them in exactly
 * that order. <strong>No ordering operation of any kind is performed here</strong> - nothing is
 * re-sequenced, inverted or re-arranged, and no ordering strategy is held as state.</p>
 *
 * <p>This matters because the two paging directions fill the screen differently. A forward page is
 * read ascending and fills the slots top-down. A backward page is read descending - the legacy seeds
 * its fill index one past the screen-line constant and walks it down, filling slot seven up to slot
 * one, as the backward paragraph at {@code app/cbl/COCRDLIC.cbl} lines 1284 to 1286 and the
 * record-at-a-time backward read immediately after it show. The service therefore hands over a list
 * that is already in presentation order for both directions. Any re-ordering applied here would
 * invert a backward page that was already correct, so the rule is stated plainly: emit what was
 * received.</p>
 *
 * <h2>Paging is cursor-based and carries no record total</h2>
 *
 * <p>{@link PageMetadata} carries the browse cursors, the direction and the two independent
 * end-of-browse indicators. The legacy browse never asks the store how many records exist; it
 * discovers that another page is available by attempting one more read and observing the outcome.
 * There is consequently no record-total or page-total component, because either would be fabricated
 * information backed by a query the original never issued.</p>
 *
 * <p>The page indicator the screen renders is carried separately from {@link PageMetadata} because
 * this screen's map field is narrower than the shared contract's bound: {@code PAGENO} in
 * {@code app/cpy-bms/COCRDLI.CPY} is three characters wide, whereas the transaction-list and
 * user-list maps declare an eight-character {@code PAGENUM}. Carrying it here at the card-list width
 * states this screen's contract precisely. It is alphanumeric on the map, so it travels as text and
 * never as a number, and it is a display value only - the cursors are what navigation depends on.</p>
 *
 * <h2>The excluded map field</h2>
 *
 * <p>The symbolic map {@code app/cpy-bms/COCRDLI.CPY} declares seven row families, and rows two
 * through seven each declare one further one-character item beyond the four this type models -
 * six occurrences of it in all, one per row from the second to the seventh, each suffixed with its
 * row ordinal. <strong>Row one does not declare it at all</strong>, and that asymmetry is the first
 * sign that it is an artefact rather than part of the contract. Two further findings settle the
 * question: the mapset {@code app/bms/COCRDLI.bms} gives every one of the six the auto-skip and dark
 * attributes, so the terminal neither accepts input into them nor displays them; and an exhaustive
 * search of the 1,459-line {@code app/cbl/COCRDLIC.cbl} finds <strong>no reference to any of the six
 * anywhere in the program</strong>, which neither reads nor writes them.</p>
 *
 * <p>That item is therefore <strong>not modelled</strong>, under any name. The nested row type below
 * has exactly the four value items the program actually uses. Modelling a fifth for the sake of
 * symmetry across rows would invent an interface element the legacy system never had, and would give
 * a client something to populate that nothing consumes. This paragraph is the traceability record of
 * the exclusion, which is identified here by its position in the map rather than by its name so that
 * the name appears nowhere in this file; the exclusion is also recorded in
 * {@code docs/decision-log.md}, where the name is given.</p>
 *
 * <p>The per-field 3270 control items the map generates alongside every value item - the length,
 * flag and attribute items, and the twelve-character terminal-area filler that opens the map - are
 * likewise absent. They are generated terminal plumbing, not screen content.</p>
 *
 * <h2>Row selection semantics</h2>
 *
 * <p>The selection column accepts an action code per row: {@code S} requests the card-detail
 * transaction and {@code U} requests the card-update transaction, as the program's condition names
 * at lines 77 to 79 establish. <strong>At most one action is honoured per page.</strong> The program
 * tallies the selection codes at lines 1079 to 1082 and rejects the submission when more than one
 * row carries an action; it then converts the selection column into a positional indicator at lines
 * 1090 to 1093, marking every slot that contributed to the rejection and leaving the other slots
 * explicitly unmarked rather than dropping them.</p>
 *
 * <p>{@link #selectionErrorFlags()} is that positional indicator. It is a list rather than a keyed
 * or set-like structure precisely so the empty positions survive: element <em>i</em> describes row
 * <em>i</em>, and an unmarked slot is present and {@code false} instead of being absent. Compacting
 * it would destroy the alignment that lets the screen mark the offending rows and lets the rejection
 * message name them.</p>
 *
 * <p>The action codes are echoed back on each row as received. Neither the codes nor the flags are
 * interpreted, validated or tallied here.</p>
 *
 * <h2>Card numbers travel intact</h2>
 *
 * <p>Each row carries its full sixteen-character card number, unaltered. It is neither obscured nor
 * shortened, because the legacy screen displays it in full and the legacy design applies no
 * field-level protection to it anywhere. That absence of protection is a genuine gap in the original
 * design, and it is recorded as such in {@code docs/decision-log.md} rather than closed here:
 * silently altering the value would break the screen contract this type exists to reproduce, and
 * would be unrequested behaviour change.</p>
 *
 * <h2>Two verified source oddities, recorded for traceability</h2>
 *
 * <ul>
 *   <li>The browse walks the card cluster in <em>card-number</em> sequence and applies the account
 *       filter <em>after</em> each record is retrieved, in the filtering paragraph at
 *       {@code app/cbl/COCRDLIC.cbl} line 1382, rather than positioning on an account-keyed path.</li>
 *   <li>An alternate-index name for exactly that account-keyed path <em>is</em> declared, at
 *       {@code app/cbl/COCRDLIC.cbl} lines 213 to 217, and is then never referenced by the program.</li>
 * </ul>
 *
 * <p>Neither changes this contract; both are noted so a reader who finds the unused declaration knows
 * it was seen and understood.</p>
 *
 * <h2>Immutability</h2>
 *
 * <p>A record with no mutable state. Both lists are defensively copied through
 * {@link List#copyOf(java.util.Collection)} in the canonical constructor, so no caller retains a
 * handle that can change what a constructed instance reports, and the accessors hand back
 * unmodifiable lists. A {@code null} list becomes an empty immutable list, so no accessor returns
 * {@code null} for either collection. Every other component is a {@code String}, a {@code boolean}
 * or a deeply immutable record from this package, which makes instances safe to share across
 * threads.</p>
 *
 * <h2>Provenance</h2>
 *
 * <p>Translated from the legacy estate at commit
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. Sources consulted:
 * {@code app/cpy-bms/COCRDLI.CPY} for the field inventory and every field width;
 * {@code app/bms/COCRDLI.bms} for the terminal attributes that identified the excluded field; and
 * {@code app/cbl/COCRDLIC.cbl} for the row geometry at lines 250 to 260 and 255, the selection
 * tally at lines 1079 to 1082, the positional indicator at lines 1090 to 1093, the backward fill at
 * lines 1284 to 1286, the post-retrieval filtering paragraph at line 1382 and the unused
 * alternate-index declaration at lines 213 to 217. No source statement is reproduced here.</p>
 *
 * @param transactionName the screen's own transaction identifier, map field {@code TRNNAME}, four
 *     characters. Echoed for display; carried as supplied.
 * @param title01 the first screen heading line, map field {@code TITLE01}, forty characters.
 * @param currentDate the date the screen displays, map field {@code CURDATE}, eight characters.
 *     Text, because the map field is alphanumeric and carries the legacy display formatting.
 * @param programName the screen's own program name, map field {@code PGMNAME}, eight characters.
 * @param title02 the second screen heading line, map field {@code TITLE02}, forty characters.
 * @param currentTime the time the screen displays, map field {@code CURTIME}, eight characters.
 *     Text, for the same reason as {@code currentDate}.
 * @param pageIndicator the page indicator the screen renders, map field {@code PAGENO}, three
 *     characters. Text rather than a number because the map field is alphanumeric, and display only
 *     because the cursors in {@code pageMetadata} are what navigation depends on. May be
 *     {@code null} when there is nothing to render.
 * @param accountFilter the account-number filter echoed back so the screen can redisplay it, map
 *     field {@code ACCTSID}, eleven characters. Text, so leading zeros survive.
 * @param cardNumberFilter the card-number filter echoed back so the screen can redisplay it, map
 *     field {@code CARDSID}, sixteen characters. Text, so leading zeros survive.
 * @param rows the card rows in the order the screen presents them, at most one per screen row.
 *     Defensively copied; {@code null} becomes empty; never extended to a fixed length and never
 *     re-ordered. See the ordering and screen-shape sections above.
 * @param selectionErrorFlags the positional selection indicator described above, aligned element for
 *     element with {@code rows}: element <em>i</em> is {@code true} when row <em>i</em> carried an
 *     action code that contributed to the more-than-one-action rejection. Unmarked slots are present
 *     and {@code false}. Defensively copied; {@code null} becomes empty.
 * @param infoMessage the informational message slot, map field {@code INFOMSG}, forty-five
 *     characters. Carried byte for byte; see the message constants below. May be {@code null}.
 * @param errorMessage the error message slot, map field {@code ERRMSG}, seventy-eight characters.
 *     Carried byte for byte. May be {@code null}.
 * @param generalError whether the screen is reporting an error, stated explicitly rather than
 *     inferred from {@code errorMessage} being present. The legacy program keeps its error condition
 *     in a flag of its own and sets it independently of any message text, so the flag is a fact in
 *     its own right here too.
 * @param pageMetadata the browse cursors, direction and end-of-browse indicators. May be
 *     {@code null} on a path that established no browse position.
 * @param focusField the identity of the map field the screen puts the operator's attention on - a
 *     field name such as the account filter or one of the selection cells, at most seven characters,
 *     which is the widest name the map declares. Identity only: no screen coordinate, no attribute
 *     byte and none of the numeric control values the legacy used to achieve the effect. May be
 *     {@code null} when no field is singled out.
 * @param nextRoute the route the client calls next, opaque to this type. There is no server-side
 *     forwarding: the client drives the next call. The vocabulary belongs to
 *     {@code service/NavigationService}, so no route table, enumeration or registry appears here,
 *     and the value is deliberately unbounded because a route is not a legacy screen field. May be
 *     {@code null}.
 * @param navigationContext the echoed navigation state. Request state travelling back to the client,
 *     not a server-side session. May be {@code null}.
 */
public record CardListResponse(
        @Size(max = CardListResponse.TRANSACTION_NAME_LENGTH) String transactionName,
        @Size(max = CardListResponse.SCREEN_TITLE_LENGTH) String title01,
        @Size(max = CardListResponse.CURRENT_DATE_LENGTH) String currentDate,
        @Size(max = CardListResponse.PROGRAM_NAME_LENGTH) String programName,
        @Size(max = CardListResponse.SCREEN_TITLE_LENGTH) String title02,
        @Size(max = CardListResponse.CURRENT_TIME_LENGTH) String currentTime,
        @Size(max = CardListResponse.PAGE_INDICATOR_LENGTH) String pageIndicator,
        @Size(max = CardListResponse.ACCOUNT_NUMBER_LENGTH) String accountFilter,
        @Size(max = CardListResponse.CARD_NUMBER_LENGTH) String cardNumberFilter,
        List<CardListRow> rows,
        List<Boolean> selectionErrorFlags,
        @Size(max = CardListResponse.INFO_MESSAGE_LENGTH) String infoMessage,
        @Size(max = CardListResponse.ERROR_MESSAGE_LENGTH) String errorMessage,
        boolean generalError,
        PageMetadata pageMetadata,
        @Size(max = CardListResponse.FOCUS_FIELD_LENGTH) String focusField,
        String nextRoute,
        NavigationContext navigationContext) {

    /**
     * Canonical constructor. Copies both lists defensively and leaves every other component exactly
     * as supplied.
     *
     * <p>Each list is replaced by an immutable copy, so a caller that keeps and later changes the
     * collection it passed in cannot change what a constructed instance reports, and the accessors
     * cannot hand out a modifiable view. A {@code null} list becomes an empty immutable list, which
     * is why neither {@link #rows()} nor {@link #selectionErrorFlags()} ever answers {@code null}:
     * a screen with no qualifying cards has no rows, and "no rows" is an empty page rather than a
     * missing one.</p>
     *
     * <p><strong>Nothing else happens here.</strong> No component is defaulted, re-ordered,
     * shortened, extended, folded to another case or stripped of surrounding spaces. The legacy
     * fields are fixed width and space filled, so a value's surrounding spaces are part of what the
     * screen displayed and are carried through untouched. In particular the row list is emitted in
     * the order it arrived, and it is not extended to the screen's row shape when it is shorter -
     * both for the reasons set out in the class documentation.</p>
     *
     * <p>No component is required to be present. Blank and absent values are ordinary on this
     * screen - the legacy program renders a partly filled map on almost every path - so rejecting
     * them here would refuse responses the original produced.</p>
     *
     * @throws NullPointerException if either list contains a {@code null} element, which
     *     {@link List#copyOf(java.util.Collection)} does not admit. Neither list has a meaningful
     *     null element: a row is either present or the list is shorter, and every position of the
     *     selection indicator is explicitly marked or explicitly unmarked, exactly as the legacy
     *     program's positional conversion leaves it.
     */
    public CardListResponse {
        rows = (rows == null) ? List.of() : List.copyOf(rows);
        selectionErrorFlags =
                (selectionErrorFlags == null) ? List.of() : List.copyOf(selectionErrorFlags);
    }

    /**
     * Width in characters of the screen's transaction-identifier field: 4.
     *
     * <p>Declared as {@code TRNNAME} in {@code app/cpy-bms/COCRDLI.CPY}. A legacy field width, not a
     * restriction invented here; the bound only reports an over-long value and never alters, extends
     * or shortens one.</p>
     */
    public static final int TRANSACTION_NAME_LENGTH = 4;

    /**
     * Width in characters of each screen heading line: 40.
     *
     * <p>Declared as {@code TITLE01} and {@code TITLE02} in {@code app/cpy-bms/COCRDLI.CPY}. One
     * constant serves both because they are the same thing - a heading line on this screen - at the
     * same width, so a second name would be a second name for one number.</p>
     */
    public static final int SCREEN_TITLE_LENGTH = 40;

    /**
     * Width in characters of the displayed-date field: 8.
     *
     * <p>Declared as {@code CURDATE} in {@code app/cpy-bms/COCRDLI.CPY}. Kept separate from
     * {@link #CURRENT_TIME_LENGTH} and {@link #PROGRAM_NAME_LENGTH} even though all three are eight,
     * because the three describe unrelated screen items whose widths coincide; deriving one from
     * another would make an accidental equality look like a shared contract.</p>
     */
    public static final int CURRENT_DATE_LENGTH = 8;

    /**
     * Width in characters of the displayed-time field: 8.
     *
     * <p>Declared as {@code CURTIME} in {@code app/cpy-bms/COCRDLI.CPY}. Separate from the two other
     * eight-character widths for the reason given on {@link #CURRENT_DATE_LENGTH}.</p>
     */
    public static final int CURRENT_TIME_LENGTH = 8;

    /**
     * Width in characters of the screen's program-name field: 8.
     *
     * <p>Declared as {@code PGMNAME} in {@code app/cpy-bms/COCRDLI.CPY}. Separate from the two other
     * eight-character widths for the reason given on {@link #CURRENT_DATE_LENGTH}.</p>
     */
    public static final int PROGRAM_NAME_LENGTH = 8;

    /**
     * Width in characters of the page indicator this screen renders: 3.
     *
     * <p>Declared as {@code PAGENO} in {@code app/cpy-bms/COCRDLI.CPY}. This is narrower than the
     * indicator width the shared paging contract admits, because that contract spans three screens
     * and the other two declare an eight-character {@code PAGENUM} in
     * {@code app/cpy-bms/COTRN00.CPY} and {@code app/cpy-bms/COUSR00.CPY}. Nothing is shared between
     * the two: this constant states the card-list map's own width, which is the contract this
     * response reproduces.</p>
     */
    public static final int PAGE_INDICATOR_LENGTH = 3;

    /**
     * Width in characters of an account number: 11.
     *
     * <p>Declared as the search filter {@code ACCTSID} and as the per-row {@code ACCTNO} items in
     * {@code app/cpy-bms/COCRDLI.CPY}, and corroborated by the 28-character row element at
     * {@code app/cbl/COCRDLIC.cbl} lines 250 to 260. One constant serves the filter and the rows
     * because they hold the same value at the same width. Account numbers travel as text at this
     * width so their leading zeros survive, which a numeric type would discard.</p>
     */
    public static final int ACCOUNT_NUMBER_LENGTH = 11;

    /**
     * Width in characters of a card number: 16.
     *
     * <p>Declared as the search filter {@code CARDSID} and as the per-row {@code CRDNUM} items in
     * {@code app/cpy-bms/COCRDLI.CPY}, and corroborated by the 28-character row element at
     * {@code app/cbl/COCRDLIC.cbl} lines 250 to 260. One constant serves the filter and the rows for
     * the same reason as {@link #ACCOUNT_NUMBER_LENGTH}, and card numbers likewise travel as text so
     * their leading zeros survive.</p>
     */
    public static final int CARD_NUMBER_LENGTH = 16;

    /**
     * Width in characters of a row's selection action code: 1.
     *
     * <p>Declared as the per-row {@code CRDSEL} items in {@code app/cpy-bms/COCRDLI.CPY}. Kept
     * separate from {@link #CARD_STATUS_LENGTH} because an action code and a card status are
     * unrelated items that happen to share a width.</p>
     */
    public static final int SELECTION_LENGTH = 1;

    /**
     * Width in characters of a row's card-status indicator: 1.
     *
     * <p>Declared as the per-row {@code CRDSTS} items in {@code app/cpy-bms/COCRDLI.CPY}, and
     * corroborated by the 28-character row element at {@code app/cbl/COCRDLIC.cbl} lines 250 to 260.
     * Separate from {@link #SELECTION_LENGTH} for the reason given there.</p>
     */
    public static final int CARD_STATUS_LENGTH = 1;

    /**
     * Width in characters of the informational message slot: 45.
     *
     * <p>Declared as {@code INFOMSG} in {@code app/cpy-bms/COCRDLI.CPY}, matching the width of the
     * program's own informational message field.</p>
     */
    public static final int INFO_MESSAGE_LENGTH = 45;

    /**
     * Width in characters of the error message slot: 78.
     *
     * <p>Declared as {@code ERRMSG} in {@code app/cpy-bms/COCRDLI.CPY}. The map width governs here.
     * The program's own error message field is three characters narrower, so every message it can
     * produce fits; taking the narrower of the two would reject text the map is able to display, and
     * the map is the screen contract this response reproduces. The discrepancy is recorded in
     * {@code docs/decision-log.md}.</p>
     */
    public static final int ERROR_MESSAGE_LENGTH = 78;

    /**
     * Width in characters of a map field name, and therefore of {@link #focusField()}: 7.
     *
     * <p>Every field name declared in {@code app/cpy-bms/COCRDLI.CPY} is seven characters or fewer -
     * the heading, filter, row, page-indicator and message field names alike - so seven admits any
     * name this screen can single out and no more.</p>
     */
    public static final int FOCUS_FIELD_LENGTH = 7;

    /*
     * ==================================================================================
     * THE NINE SCREEN MESSAGES
     * ==================================================================================
     *
     * External interface text, reproduced character for character from app/cbl/COCRDLIC.cbl. These
     * strings are what an operator and any downstream tooling match on, so they are part of the
     * contract this response reproduces and not display sugar. Every one of the nine is upper case,
     * and exactly one of them ends in a full stop.
     *
     * Two of them read oddly and are correct as written: neither filter message has a space after
     * its comma, and both say "A" where English would use "AN". They are reproduced with those
     * quirks intact, because normalising them would change text an operator's procedure may key on.
     * The row-action prompt, by contrast, does carry a space after its comma - so the punctuation
     * genuinely differs between messages and is not a single rule applied inconsistently.
     *
     * Which message applies on which path is decided by service/CardListService. This type declares
     * them so there is a single authority for the exact text, and assembles, formats and chooses
     * between none of them.
     * ==================================================================================
     */

    /**
     * Rejection text for a malformed account-number filter.
     *
     * <p>Reproduced from {@code app/cbl/COCRDLIC.cbl} line 1022. <strong>There is deliberately no
     * space after the comma, and the wording is "A 11" rather than "AN 11".</strong> Both are
     * faithful to the source and must not be tidied.</p>
     */
    public static final String MSG_ACCOUNT_FILTER_INVALID =
            "ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER";

    /**
     * Rejection text for a malformed card-number filter.
     *
     * <p>Reproduced from {@code app/cbl/COCRDLIC.cbl} line 1058. <strong>As with the account filter
     * message there is no space after the comma, and the wording is "A 16".</strong></p>
     */
    public static final String MSG_CARD_FILTER_INVALID =
            "CARD ID FILTER,IF SUPPLIED MUST BE A 16 DIGIT NUMBER";

    /**
     * Rejection text for a row action code outside the accepted pair.
     *
     * <p>Reproduced from {@code app/cbl/COCRDLIC.cbl} line 126. The accepted codes are established
     * by the program's condition names at lines 77 to 79.</p>
     */
    public static final String MSG_INVALID_ACTION_CODE = "INVALID ACTION CODE";

    /**
     * Rejection text emitted when more than one row on a page carries an action code.
     *
     * <p>Reproduced from {@code app/cbl/COCRDLIC.cbl} line 124, and raised by the tally at lines
     * 1079 to 1082. It accompanies the positional indicator in {@link #selectionErrorFlags()}, which
     * is what identifies <em>which</em> rows offended.</p>
     */
    public static final String MSG_MORE_THAN_ONE_ACTION =
            "PLEASE SELECT ONLY ONE RECORD TO VIEW OR UPDATE";

    /**
     * Guard text for a backward paging request already at the start of the browse.
     *
     * <p>Reproduced from {@code app/cbl/COCRDLIC.cbl} line 903. Distinct from
     * {@link #MSG_NO_MORE_PAGES} because the legacy renders a different message for each end of the
     * browse, which is why the two end-of-browse indicators in {@link PageMetadata} are
     * independent.</p>
     */
    public static final String MSG_NO_PREVIOUS_PAGES = "NO PREVIOUS PAGES TO DISPLAY";

    /**
     * Guard text for a forward paging request already at the end of the browse.
     *
     * <p>Reproduced from {@code app/cbl/COCRDLIC.cbl} line 908. Distinct from
     * {@link #MSG_NO_PREVIOUS_PAGES} for the reason given there.</p>
     */
    public static final String MSG_NO_MORE_PAGES = "NO MORE PAGES TO DISPLAY";

    /**
     * Text emitted when a forward browse reaches the end of the cluster while filling a page.
     *
     * <p>Reproduced from {@code app/cbl/COCRDLIC.cbl}, where it is emitted on two separate paths at
     * lines 1219 and 1239. It reports the end of the data rather than a refused navigation, which is
     * why it is a separate message from the two paging guards above.</p>
     */
    public static final String MSG_NO_MORE_RECORDS = "NO MORE RECORDS TO SHOW";

    /**
     * Text emitted when the supplied filters match nothing at all.
     *
     * <p>Reproduced from {@code app/cbl/COCRDLIC.cbl} line 122. <strong>This is the only one of the
     * nine messages that ends in a full stop</strong>, and the stop is part of the text.</p>
     */
    public static final String MSG_NO_RECORDS_FOUND =
            "NO RECORDS FOUND FOR THIS SEARCH CONDITION.";

    /**
     * Informational prompt telling the operator which action codes the selection column accepts.
     *
     * <p>Reproduced from {@code app/cbl/COCRDLIC.cbl} line 116, where it is the program's
     * informational message rather than an error, so it belongs in {@link #infoMessage()} and fits
     * that slot's width. Unlike the two filter messages, this one <em>does</em> carry a space after
     * its comma.</p>
     */
    public static final String MSG_ROW_ACTION_PROMPT = "TYPE S FOR DETAIL, U TO UPDATE ANY RECORD";

    /**
     * One card row of the card-list screen: the four value items the legacy program actually uses.
     *
     * <p>Nested inside the response because a row has no meaning apart from the page that carries it
     * and no other type consumes it. Records nested in a record are implicitly static, so an instance
     * holds no reference to an enclosing response.</p>
     *
     * <p>The four components are the whole of a row's content. The symbolic map
     * {@code app/cpy-bms/COCRDLI.CPY} declares them per row as {@code CRDSEL}, {@code ACCTNO},
     * {@code CRDNUM} and {@code CRDSTS}, and the program corroborates three of them independently:
     * its 28-character row element at {@code app/cbl/COCRDLIC.cbl} lines 250 to 260 is an
     * 11-character account identifier, a 16-character card number and a 1-character status
     * indicator, which is 28 exactly with nothing left over. <strong>The one further map field that
     * rows two through seven also declare is excluded, for the three reasons set out in the
     * enclosing type's documentation - row one does not have it, the mapset makes it auto-skip and
     * dark, and the program never references it.</strong> The generated 3270 length, flag and
     * attribute items are excluded for the same reason they are excluded from the enclosing type:
     * they are terminal plumbing rather than screen content.</p>
     *
     * <p>Every component is text. The two identifiers are text so that the leading zeros the legacy
     * fixed-width fields carry survive the round trip; a numeric type would discard them and an
     * eleven-digit account identifier beginning with a zero would come back changed.</p>
     *
     * <p>Deeply immutable: four {@code String} components, no state, and no operation that inspects
     * or transforms a value. Values are carried exactly as received, including any surrounding
     * spaces, which on a fixed-width screen field are part of what was displayed.</p>
     *
     * @param selection the action code echoed back for this row, map field {@code CRDSEL}, one
     *     character. The program accepts {@code S} to request card detail and {@code U} to request
     *     card update, per its condition names at {@code app/cbl/COCRDLIC.cbl} lines 77 to 79, and
     *     honours at most one action across the whole page. Echoed as received: the code is neither
     *     validated nor interpreted here. Blank on a row the operator did not act on.
     * @param accountNumber the account this card belongs to, map field {@code ACCTNO}, eleven
     *     characters.
     * @param cardNumber the card number, map field {@code CRDNUM}, sixteen characters. Carried in
     *     full and unaltered - never obscured or shortened - for the reason set out in the enclosing
     *     type's documentation.
     * @param cardStatus the card's active-status indicator, map field {@code CRDSTS}, one character.
     *     Carried as the raw character rather than as
     *     {@link com.carddemo.domain.enums.CardStatus}, deliberately: that type enumerates the two
     *     codes the legacy card-update screen validates, but the column replacing this field
     *     constrains nothing, only the online update path ever validated it, and the batch programs
     *     that read card records take the value straight from the file. A value outside the known
     *     pair therefore flows through the legacy system untouched and must flow through this
     *     contract untouched too, which a fixed enumeration could not do without either rejecting it
     *     or absorbing it into a constant the estate never produces. Callers that need the meaning
     *     resolve it through that type's non-throwing lookup.
     */
    public record CardListRow(
            @Size(max = CardListResponse.SELECTION_LENGTH) String selection,
            @Size(max = CardListResponse.ACCOUNT_NUMBER_LENGTH) String accountNumber,
            @Size(max = CardListResponse.CARD_NUMBER_LENGTH) String cardNumber,
            @Size(max = CardListResponse.CARD_STATUS_LENGTH) String cardStatus) {
    }
}
