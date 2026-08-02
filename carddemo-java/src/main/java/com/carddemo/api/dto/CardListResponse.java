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
 * <p>REST projection of the 3270 screen driven by {@code app/cbl/COCRDLIC.cbl} and described field
 * by field by {@code app/cpy-bms/COCRDLI.CPY} and {@code app/bms/COCRDLI.bms}. It is a carrier and
 * nothing else: it performs no ordering, filtering, selection interpretation, tallying, message
 * choosing, route resolution or data access. Those belong to {@code service/CardListService}, which
 * reproduces the legacy program's logic; this record conveys what that service decided, component
 * for component, exactly as supplied.</p>
 *
 * <p><strong>Seven rows is screen geometry, not a tunable page size.</strong> The row table in the
 * program occurs seven times and its 28-character element accounts for the whole 196-character
 * all-rows area, and the symbolic map independently declares seven row families. Changing it puts a
 * different number of rows on the screen, which is a behavioural change rather than a setting. A
 * short page stays short: when fewer cards qualify the row list is simply shorter, and no blank
 * filler row is ever manufactured to reach seven.</p>
 *
 * <p><strong>Row order is preserved verbatim and must never be re-sequenced here.</strong> A forward
 * page is read ascending and fills the screen top-down; a backward page is read descending and fills
 * the last slot up to the first. The service therefore hands over a list that is already in
 * presentation order for both directions, so any re-ordering applied here would invert a backward
 * page that was already correct.</p>
 *
 * <p>Paging is cursor-based and carries no record total: the legacy browse never asks the store how
 * many records exist, it discovers a further page by attempting one more read. A record-total or
 * page-total component would be fabricated information backed by a query the original never issued.
 * The page indicator the screen renders is carried here rather than in {@link PageMetadata} because
 * this map declares it three characters wide while the other two paginated maps declare eight;
 * nothing is shared between them. It is alphanumeric on the map, so it travels as text, and it is a
 * display value only - the cursors are what navigation depends on.</p>
 *
 * <p>Each screen item declares its own width constant even where several coincide, because a shared
 * constant would turn an accidental equality into a contract.</p>
 *
 * <p><strong>One further one-character map item, declared on the second through seventh row families
 * only, is deliberately not modelled under any name.</strong> Row one does not declare it, the
 * mapset gives all six occurrences the auto-skip and dark attributes so the terminal neither accepts
 * nor displays them, and the program references none of them. Modelling it for symmetry would invent
 * an interface element the legacy system never had and give a client something to populate that
 * nothing consumes. Recorded in {@code docs/decision-log.md}, which names it. The generated 3270
 * length, flag and attribute items and the terminal-area filler are absent for the same reason: they
 * are terminal plumbing, not screen content.</p>
 *
 * <p><strong>At most one row action is honoured per page.</strong> The program tallies the selection
 * codes, rejects a submission carrying more than one, and converts the selection column into a
 * positional indicator that marks every offending slot and leaves the others explicitly unmarked.
 * {@link #selectionErrorFlags()} is that indicator, and it is a list rather than a keyed or set-like
 * structure precisely so the empty positions survive: element <em>i</em> describes row <em>i</em>,
 * and an unmarked slot is present and {@code false} rather than absent. Compacting it would destroy
 * the alignment that lets the screen mark the offending rows and lets the rejection message name
 * them.</p>
 *
 * <p>Each row carries its full sixteen-character card number, unaltered - neither obscured nor
 * shortened - because the legacy screen displays it in full and the legacy design applies no
 * field-level protection to it anywhere. That absence is a genuine gap in the original design and is
 * recorded as such in {@code docs/decision-log.md} rather than closed here; altering the value would
 * break the screen contract this type exists to reproduce.</p>
 *
 * <p>Deeply immutable. Both lists are defensively copied and the accessors hand back unmodifiable
 * lists; every other component is a {@code String}, a {@code boolean} or an immutable record from
 * this package, which makes instances safe to share across threads.</p>
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
 * <p>Travelling intact to the client is a contract; appearing in a log line is not. Both this type
 * and {@link CardListRow} therefore override {@code toString()} to withhold every regulated value,
 * and the outer rendering withholds the whole row list rather than delegating to the rows, so that
 * this type's safety does not depend on the nested type's rendering staying correct. The components
 * themselves and the serialized payload are untouched.</p>
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
 * @param displayedPageNumber the page indicator the screen renders, map field {@code PAGENO}, three
 *     characters. Text rather than a number because the map field is alphanumeric, and display only
 *     because the cursors in {@code pageMetadata} are what navigation depends on. Named for the
 *     concept rather than for the 3270 item, which is the spelling every paginated contract in this
 *     package uses. May be {@code null} when there is nothing to render.
 * @param accountFilter the account-number filter echoed back so the screen can redisplay it, map
 *     field {@code ACCTSID}, eleven characters. Text, so leading zeros survive.
 * @param cardNumberFilter the card-number filter echoed back so the screen can redisplay it, map
 *     field {@code CARDSID}, sixteen characters. Text, so leading zeros survive.
 * @param rows the card rows in the order the screen presents them, at most one per screen row.
 *     Defensively copied; {@code null} becomes empty; never extended to a fixed length and never
 *     re-ordered. <strong>Bounded above at {@link PageMetadata#CARD_LIST_PAGE_SIZE}</strong> and
 *     rejected beyond it: the screen has that many row slots and no more, so a longer list could not
 *     be a page this screen ever rendered. See the ordering and screen-shape sections above.
 * @param selectionErrorFlags the positional selection indicator described above, aligned element for
 *     element with {@code rows}: element <em>i</em> is {@code true} when row <em>i</em> carried an
 *     action code that contributed to the more-than-one-action rejection. Unmarked slots are present
 *     and {@code false}. Defensively copied; {@code null} becomes empty. <strong>Either empty or
 *     exactly as long as {@code rows}</strong>, and rejected otherwise: the legacy program builds the
 *     indicator only on the rejection path, so no indicator at all is the ordinary case, but an
 *     indicator that exists and does not align position for position with the rows would attribute an
 *     error to the wrong row or to a row the page does not have.
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
 * @param focusScreenFieldId the identity of the map field the screen puts the operator's attention
 *     on - a field name such as the account filter or one of the selection cells, at most seven
 *     characters, which is the widest name the map declares. Identity only: no screen coordinate, no
 *     attribute byte and none of the numeric control values the legacy used to achieve the effect.
 *     Named for the concept rather than for the field, which is the spelling every screen contract in
 *     this package uses. May be {@code null} when no field is singled out.
 * @param nextRoute the route the client calls next, opaque to this type. There is no server-side
 *     forwarding: the client drives the next call. The vocabulary belongs to
 *     {@code service/NavigationService}, so no route table, enumeration or registry appears here.
 *     May be {@code null}.
 * @param navigationContext the echoed navigation state - request state travelling back to the
 *     client, not a server-side session. May be {@code null}.
 */
public record CardListResponse(
        @Size(max = CardListResponse.TRANSACTION_NAME_LENGTH) String transactionName,
        @Size(max = CardListResponse.SCREEN_TITLE_LENGTH) String title01,
        @Size(max = CardListResponse.CURRENT_DATE_LENGTH) String currentDate,
        @Size(max = CardListResponse.PROGRAM_NAME_LENGTH) String programName,
        @Size(max = CardListResponse.SCREEN_TITLE_LENGTH) String title02,
        @Size(max = CardListResponse.CURRENT_TIME_LENGTH) String currentTime,
        @Size(max = CardListResponse.DISPLAYED_PAGE_NUMBER_LENGTH) String displayedPageNumber,
        @Size(max = CardListResponse.ACCOUNT_NUMBER_LENGTH) String accountFilter,
        @Size(max = CardListResponse.CARD_NUMBER_LENGTH) String cardNumberFilter,
        List<CardListRow> rows,
        List<Boolean> selectionErrorFlags,
        @Size(max = CardListResponse.INFO_MESSAGE_LENGTH) String infoMessage,
        @Size(max = CardListResponse.ERROR_MESSAGE_LENGTH) String errorMessage,
        boolean generalError,
        PageMetadata pageMetadata,
        @Size(max = CardListResponse.SCREEN_FIELD_ID_LENGTH) String focusScreenFieldId,
        String nextRoute,
        NavigationContext navigationContext) {

    /**
     * Canonical constructor. Replaces each list with an immutable copy and leaves every other
     * component exactly as supplied.
     *
     * <p>A {@code null} list becomes an empty immutable list, so neither accessor ever answers
     * {@code null}: a screen with no qualifying cards is an empty page rather than a missing one.
     * Nothing else happens here - no component is defaulted, re-ordered, shortened, extended,
     * case-folded or stripped of surrounding spaces, which on a fixed-width space-filled screen
     * field are part of what was displayed. No component is required to be present, because the
     * legacy program renders a partly filled map on almost every path.</p>
     *
     * <p><strong>Two things are checked, and both are screen geometry rather than business
     * rules.</strong> The row list may not be longer than the screen has row slots: the count is
     * {@link PageMetadata#CARD_LIST_PAGE_SIZE}, established from the source in the screen-shape
     * section above, and a longer list could not be a page the legacy screen ever rendered, so
     * admitting one would let a response describe a screen that does not exist and would leave the
     * page size unbounded. A <em>shorter</em> list is accepted unchanged, because a short page stays
     * short. The selection indicator, when it is present at all, must align position for position
     * with the rows: the legacy program builds it only on the rejection path, so an absent indicator
     * is the ordinary case and stays absent, but one that exists and disagrees in length would either
     * attribute an error to the wrong row or attribute one to a row the page does not contain.</p>
     *
     * @throws NullPointerException if either list contains a {@code null} element, which
     *     {@link List#copyOf(java.util.Collection)} does not admit. Neither list has a meaningful
     *     null element: a row is either present or the list is shorter, and every position of the
     *     selection indicator is explicitly marked or explicitly unmarked, exactly as the legacy
     *     program's positional conversion leaves it.
     * @throws IllegalArgumentException if the row list holds more entries than the screen has row
     *     slots, or if a non-empty selection indicator does not have exactly one entry per row
     */
    public CardListResponse {
        rows = (rows == null) ? List.of() : List.copyOf(rows);
        selectionErrorFlags =
                (selectionErrorFlags == null) ? List.of() : List.copyOf(selectionErrorFlags);
        if (rows.size() > PageMetadata.CARD_LIST_PAGE_SIZE) {
            throw new IllegalArgumentException("rows may hold at most "
                    + PageMetadata.CARD_LIST_PAGE_SIZE + " entries, because that is how many row"
                    + " slots the card-list screen has, but it holds " + rows.size());
        }
        if (!selectionErrorFlags.isEmpty() && selectionErrorFlags.size() != rows.size()) {
            throw new IllegalArgumentException("selectionErrorFlags must be empty or hold exactly one"
                    + " entry per row, because the indicator is positional, but it holds "
                    + selectionErrorFlags.size() + " entries for " + rows.size() + " rows");
        }
    }

    /**
     * Fixed stand-in emitted by the {@code toString()} overrides in place of each regulated component.
     *
     * <p>A constant rather than any transformation of the value, so nothing about a withheld component
     * - not its length, not a prefix or suffix, not a digest - can be recovered from a stringified
     * instance. A partial stand-in was rejected deliberately: a shortened card number is still
     * cardholder data. The same literal is used by every redacting contract in this package so that the
     * absence of a regulated value is auditable by one search across the whole DTO surface.
     *
     * <p>Declared once here and referenced from {@link CardListRow} as well, which a nested record may
     * do because it is a member of this type and so shares its private access. One literal for both
     * renderings rather than a copy in each is the point: two copies could drift apart, and the
     * single-search audit this constant exists to enable would then find one and miss the other.
     *
     * <p>Private because it is a rendering detail rather than part of the card-list contract.
     */
    private static final String REDACTION_PLACEHOLDER = "***REDACTED***";

    /**
     * Width in characters of the screen's transaction-identifier field: 4.
     *
     * <p>Declared as {@code TRNNAME} in {@code app/cpy-bms/COCRDLI.CPY}. A legacy field width, not a
     * restriction invented here; the bound only reports an over-long value and never alters, extends
     * or shortens one.</p>
     */
    public static final int TRANSACTION_NAME_LENGTH = 4;

    public static final int SCREEN_TITLE_LENGTH = 40;

    public static final int CURRENT_DATE_LENGTH = 8;

    public static final int CURRENT_TIME_LENGTH = 8;

    public static final int PROGRAM_NAME_LENGTH = 8;

    /**
     * Width in characters of the page indicator this screen renders: 3. Narrower than the
     * eight-character indicator the other two paginated maps declare, and shared with neither.
     */
    public static final int DISPLAYED_PAGE_NUMBER_LENGTH = 3;

    public static final int ACCOUNT_NUMBER_LENGTH = 11;

    public static final int CARD_NUMBER_LENGTH = 16;

    public static final int SELECTION_LENGTH = 1;

    public static final int CARD_STATUS_LENGTH = 1;

    public static final int INFO_MESSAGE_LENGTH = 45;

    public static final int ERROR_MESSAGE_LENGTH = 78;

    /**
     * Width in characters of a map field name, and therefore of {@link #focusScreenFieldId()}: 7.
     *
     * <p>Every field name declared in {@code app/cpy-bms/COCRDLI.CPY} is seven characters or fewer -
     * the heading, filter, row, page-indicator and message field names alike - so seven admits any
     * name this screen can single out and no more. Seven is also the widest name any of the estate's
     * seventeen mapsets declares, which is why the same bound appears on every screen contract in this
     * package: the generator reserves the eighth position of a symbolic name for the suffix that
     * distinguishes the input, output, length, flag and attribute items of one field.</p>
     */
    public static final int SCREEN_FIELD_ID_LENGTH = 7;

    /*
     * The nine screen messages. External interface text, reproduced character for character from
     * app/cbl/COCRDLIC.cbl: operators and downstream tooling match on it, so it is part of the
     * contract this response reproduces and not display sugar. Every one is upper case.
     *
     * Three quirks are faithful to the source and must not be tidied: neither filter message has a
     * space after its comma and both say "A" where English would use "AN"; the row-action prompt
     * does carry a space after its comma, so the punctuation genuinely differs between messages
     * rather than being one rule applied inconsistently; and exactly one message ends in a full
     * stop, which is part of its text.
     *
     * Which message applies on which path is decided by service/CardListService. This type declares
     * them so there is a single authority for the exact text, and assembles, formats and chooses
     * between none of them.
     */

    /** Rejection text for a malformed account-number filter. */
    public static final String MSG_ACCOUNT_FILTER_INVALID =
            "ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER";

    /** Rejection text for a malformed card-number filter. */
    public static final String MSG_CARD_FILTER_INVALID =
            "CARD ID FILTER,IF SUPPLIED MUST BE A 16 DIGIT NUMBER";

    /** Rejection text for a row action code outside the accepted {@code S} / {@code U} pair. */
    public static final String MSG_INVALID_ACTION_CODE = "INVALID ACTION CODE";

    /**
     * Rejection text emitted when more than one row on a page carries an action code. It accompanies
     * the positional indicator in {@link #selectionErrorFlags()}, which identifies <em>which</em>
     * rows offended.
     */
    public static final String MSG_MORE_THAN_ONE_ACTION =
            "PLEASE SELECT ONLY ONE RECORD TO VIEW OR UPDATE";

    /**
     * Guard text for a backward paging request already at the start of the browse. The legacy
     * renders a different message for each end of the browse, which is why the two end-of-browse
     * indicators in {@link PageMetadata} are independent.
     */
    public static final String MSG_NO_PREVIOUS_PAGES = "NO PREVIOUS PAGES TO DISPLAY";

    /** Guard text for a forward paging request already at the end of the browse. */
    public static final String MSG_NO_MORE_PAGES = "NO MORE PAGES TO DISPLAY";

    /**
     * Text emitted when a forward browse reaches the end of the cluster while filling a page. It
     * reports the end of the data rather than a refused navigation, which is why it is separate from
     * the two paging guards above.
     */
    public static final String MSG_NO_MORE_RECORDS = "NO MORE RECORDS TO SHOW";

    /** Text emitted when the supplied filters match nothing at all. */
    public static final String MSG_NO_RECORDS_FOUND =
            "NO RECORDS FOUND FOR THIS SEARCH CONDITION.";

    /**
     * Informational prompt telling the operator which action codes the selection column accepts. It
     * is the program's informational message rather than an error, so it belongs in
     * {@link #infoMessage()} and fits that slot's width.
     */
    public static final String MSG_ROW_ACTION_PROMPT = "TYPE S FOR DETAIL, U TO UPDATE ANY RECORD";

    /**
     * One card row of the card-list screen: the four value items the legacy program actually uses.
     *
     * <p>Nested inside the response because a row has no meaning apart from the page that carries it
     * and no other type consumes it. Records nested in a record are implicitly static, so an
     * instance holds no reference to an enclosing response. The four components are the whole of a
     * row's content: the further map item that only the second through seventh row families declare
     * is excluded for the three reasons set out in the enclosing type's documentation, and the
     * generated 3270 control items are excluded as terminal plumbing.</p>
     *
     * <p>Every component is text so that the leading zeros the legacy fixed-width fields carry
     * survive the round trip; a numeric type would discard them and an eleven-digit account
     * identifier beginning with a zero would come back changed. Values are carried exactly as
     * received, including surrounding spaces.</p>
     *
     * @param selection the action code echoed back for this row: {@code S} requests card detail and
     *     {@code U} requests card update, and at most one action is honoured across the whole page.
     *     Echoed as received - neither validated nor interpreted here. Blank on a row the operator
     *     did not act on.
     * @param accountNumber the account this card belongs to, eleven characters.
     * @param cardNumber the card number, sixteen characters, carried in full and unaltered for the
     *     reason set out in the enclosing type's documentation.
     * @param cardStatus the card's active-status indicator, carried as the raw character rather than
     *     as {@link com.carddemo.domain.enums.CardStatus}, deliberately: the column replacing this
     *     field constrains nothing, only the online update path ever validated it, and the batch
     *     programs take the value straight from the file. A code outside the known pair therefore
     *     flows through the legacy system untouched and must flow through this contract untouched
     *     too, which a fixed enumeration could not do without either rejecting it or absorbing it
     *     into a constant the estate never produces. Callers that need the meaning resolve it
     *     through that type's non-throwing lookup.
     */
    public record CardListRow(
            @Size(max = CardListResponse.SELECTION_LENGTH) String selection,
            @Size(max = CardListResponse.ACCOUNT_NUMBER_LENGTH) String accountNumber,
            @Size(max = CardListResponse.CARD_NUMBER_LENGTH) String cardNumber,
            @Size(max = CardListResponse.CARD_STATUS_LENGTH) String cardStatus) {

        /**
         * Returns a diagnostic representation of one row that discloses neither identifier.
         *
         * <p><strong>Why the implicit record rendering could not stand.</strong> A record's generated
         * {@code toString()} prints every component, and two of these four are regulated: the card
         * number is a primary account number carried in full, and the account number is the key that
         * joins straight to a cardholder. A page holds up to seven rows, so a single stringified
         * response would have emitted up to seven complete card numbers beside the accounts they
         * belong to - the densest disclosure any type in this package could produce. Any structured
         * logger, framework diagnostic, failed assertion, exception message or string interpolation
         * touching a row would have done it.</p>
         *
         * <p><strong>Why the remainder is retained.</strong> The echoed action code and the
         * one-character status indicator identify nobody and are the part of a row worth seeing in a
         * diagnostic. Withholding them would remove the only useful content without protecting
         * anything.</p>
         *
         * <p><strong>Withholding is confined to this method.</strong> Both identifiers are still
         * carried in full and returned untouched by their accessors, exactly as the enclosing type's
         * documentation requires: nothing here masks, truncates or transforms a value, and this
         * method is not on the serialization path, which is produced from the accessors.</p>
         *
         * @return the row layout with both identifiers replaced by a fixed placeholder
         */
        @Override
        public String toString() {
            return "CardListRow["
                    + "selection=" + selection
                    + ", accountNumber=" + REDACTION_PLACEHOLDER
                    + ", cardNumber=" + REDACTION_PLACEHOLDER
                    + ", cardStatus=" + cardStatus
                    + "]";
        }
    }

    /**
     * Returns a diagnostic representation that mirrors the response layout and discloses no regulated
     * value.
     *
     * <p><strong>Why the implicit record rendering could not stand.</strong> A record's generated
     * {@code toString()} prints every component. Four of these carry regulated content: the two
     * echoed filters, of which one is a primary account number and the other joins straight to a
     * cardholder; the row list, whose every entry carries a full card number beside its account; and
     * the paging component, whose retained browse keys are the composite of a card number followed by
     * an account identifier. A single stringified response would therefore have emitted up to seven
     * card numbers, seven account identifiers, both filters and both browse keys at once.</p>
     *
     * <p>The row list and the paging component are each withheld <em>whole</em> rather than rendered
     * through their own representations. For the paging component that is necessary, because its own
     * rendering does not withhold its browse keys and this type must not become the path by which they
     * surface. For the row list it is a deliberate second line of defence: each row already withholds
     * its own identifiers, and withholding the list as well means a future change to a row's rendering
     * cannot silently reopen the disclosure here. The row count is stated instead, because the number
     * of rows on a page identifies nobody and is the single most useful fact about a card-list
     * response in a diagnostic.</p>
     *
     * <p><strong>Why the remainder is retained.</strong> The six header items are screen furniture,
     * the page indicator and the selection indicator are screen-interaction state, the two message
     * slots carry operator text drawn from the fixed catalogue declared below, the error flag is a
     * boolean, and the focus hint is a map field name: none of them identifies anybody, and all of
     * them are what a diagnostic is read for. The navigation context is printed by delegation because
     * it withholds its own identifying values.</p>
     *
     * <p><strong>Withholding is confined to this method.</strong> Every accessor returns its component
     * exactly as supplied; no value is masked, truncated, shortened or transformed anywhere in this
     * type, because the card number is carried intact for the reason the class documentation gives.
     * This method changes what a diagnostic prints and changes nothing that is transported, and it is
     * not on the serialization path.</p>
     *
     * <p>{@code equals} and {@code hashCode} are deliberately left as the record contract generates
     * them. They compare every component by value, which is what a response contract requires, and
     * neither emits anything: an in-memory comparison is not a disclosure surface.</p>
     *
     * @return the response layout with each regulated component replaced by a fixed placeholder, and
     *     the row list reduced to its size
     */
    @Override
    public String toString() {
        return "CardListResponse["
                + "transactionName=" + transactionName
                + ", title01=" + title01
                + ", currentDate=" + currentDate
                + ", programName=" + programName
                + ", title02=" + title02
                + ", currentTime=" + currentTime
                + ", displayedPageNumber=" + displayedPageNumber
                + ", accountFilter=" + REDACTION_PLACEHOLDER
                + ", cardNumberFilter=" + REDACTION_PLACEHOLDER
                + ", rowCount=" + rows.size()
                + ", rows=" + REDACTION_PLACEHOLDER
                + ", selectionErrorFlags=" + selectionErrorFlags
                + ", infoMessage=" + infoMessage
                + ", errorMessage=" + errorMessage
                + ", generalError=" + generalError
                + ", pageMetadata=" + REDACTION_PLACEHOLDER
                + ", focusScreenFieldId=" + focusScreenFieldId
                + ", nextRoute=" + nextRoute
                + ", navigationContext=" + navigationContext
                + "]";
    }
}
