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

import com.carddemo.domain.enums.KeyAction;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Immutable transaction-list request contract for legacy CICS transaction {@code CT00}.
 *
 * <p>The REST-era replacement for the <em>inbound</em> half of the 3270 transaction-list screen,
 * driven by program {@code app/cbl/COTRN00C.cbl} and laid out by mapset
 * {@code app/bms/COTRN00.bms}. The symbolic map {@code app/cpy-bms/COTRN00.CPY} declares the
 * inbound group at line 17 and an outbound redefinition of the very same storage at line 373 - one
 * area, two views, which is why a naive reading of the map appears to declare every item twice.
 * This record models the inbound view alone.
 *
 * <h2>Three of the map's item families are genuine request state; the rest are not</h2>
 *
 * <p>The map declares a great deal that never travels inbound, and separating the two is the whole
 * job of this type:</p>
 * <ul>
 *   <li><strong>Carried here.</strong> The transaction-identifier filter (item {@code TRNIDIN},
 *       width 16, map line 66; enterable on the screen per mapset line 95), the displayed page
 *       indicator (item {@code PAGENUM}, width 8, map line 60; mapset line 85), and the ten row
 *       selectors (items {@code SEL0001} through {@code SEL0010}, width 1 each, map lines 72, 102,
 *       132, 162, 192, 222, 252, 282, 312 and 342; enterable per mapset lines 153 and 414).</li>
 *   <li><strong>Excluded because the program writes them outbound rather than reading them
 *       inbound.</strong> The six screen-header families - transaction name, the two title lines,
 *       the current date, the program name and the current time - and the message item at map
 *       line 372, whose width on <em>this</em> map is 78 rather than the 80 the card detail and
 *       card update maps use. All of them belong to the response contract.</li>
 *   <li><strong>Excluded because they are the displayed row values.</strong> The four row-value
 *       families - identifier, date, description and amount, at widths 16, 8, 26 and 12 - are what
 *       the program renders <em>into</em> the page; they belong to the response contract beside the
 *       message item. Their generated names carry inconsistent suffix widths, a four-digit suffix
 *       on the selector family against two digits on three of the value families and three digits
 *       on the amount family. That is an artefact of map generation, carries no meaning, and is
 *       deliberately not encoded anywhere in this contract.</li>
 *   <li><strong>Excluded because they are terminal plumbing.</strong> Every map item is generated
 *       with its own length, flag, attribute, colour, highlight, outline and validation companions,
 *       and the inbound group opens with a twelve-byte terminal input/output area filler. None of
 *       that is contract, and none of it is modelled.</li>
 * </ul>
 *
 * <h2>The identifier filter is optional, alphanumeric, and never defaulted</h2>
 *
 * <p>A blank filter is a legitimate submission meaning "start at the beginning of the browse": the
 * program tests the item for blankness at line 206 of {@code COTRN00C} and, when it is blank,
 * positions the browse at the low end of the key sequence instead of at a key. This record
 * therefore accepts {@code null}, the empty string and an all-blank string without complaint, and
 * substitutes nothing - no empty-string default, no sentinel, no positioning key. Manufacturing a
 * default here would turn "list from the beginning" into "list from a key", which is a different
 * query.</p>
 *
 * <p>The value is a 16-character <strong>alphanumeric</strong> identifier and is typed
 * {@link String} accordingly. A numeric type would be wrong twice over: the persisted transaction
 * identifier is a 16-character text key in {@code app/cpy/CVTRA05Y.cpy}, and a zero-prefixed
 * identifier is not the same key as its unprefixed form - a distinction decision log entry DL-035
 * records for fixed-width keys generally.</p>
 *
 * <p>The program does nonetheless reject a non-numeric filter, at lines 209 to 218 of
 * {@code COTRN00C}, with a specific message set at line 214. That check is <em>message-bearing</em>:
 * it must produce that one message, in that one position, with the screen cursor placed back on the
 * filter item. It is therefore owned by {@code TransactionListService} and is deliberately
 * <strong>not</strong> expressed as a declarative constraint here. See the validation policy
 * below.</p>
 *
 * <h2>The ten row selectors are positional, and a blank slot is information</h2>
 *
 * <p>{@link #rowSelectors()} is an ordered, index-aligned, immutable sequence: the entry at index
 * zero is the selector the operator left on the first displayed row, the entry at index one belongs
 * to the second row, and so on across the ten rows the screen offers. The index is the contract -
 * it is the only thing that says <em>which</em> displayed transaction was chosen, because the
 * program pairs each selector item with the row-value item beside it when it resolves the
 * selection, in the ten-clause selection construct at lines 148 to 182 of {@code COTRN00C}.</p>
 *
 * <p>Consequently a de-duplicating collection, a collection keyed by row, a compacted sequence or
 * any element-dropping projection is unusable: discarding the blank slots shifts every later entry
 * and silently re-points the selection at the wrong row. The sequence is copied defensively and
 * exposed immutably, and blank entries are preserved exactly as received.</p>
 *
 * <p><strong>First non-blank wins, and that resolution belongs to the service.</strong> The
 * selection construct evaluates the ten slots in ascending row order and stops at the first one
 * that is neither blank nor unset, so a submission carrying two marked rows resolves to the earlier
 * row and the later mark is ignored rather than rejected. The accepted mark is a single letter, in
 * either case, and it routes to the transaction-view transaction {@code CT01}; any other non-blank
 * value produces the message set at line 199, which names exactly one accepted letter - notably
 * singular, where the administrative user list names two, so the two texts are neither shared nor
 * unified. None of that lives here: this record performs no scan, no tally, no bitmap, no
 * case-insensitive comparison, no letter check and no routing. Preserving the stop-at-first-match
 * ordering is the service's obligation, recorded as decision log entry DL-021.</p>
 *
 * <h2>Paging is cursor-based, and the row count travels as data</h2>
 *
 * <p>The screen presents ten rows, and that count exists nowhere in the program as a declaration:
 * there is no table for these rows at all. It emerges entirely from loop bounds - the row-clearing
 * loop bound at line 290, the row index reset to one at line 295, and the filling loop at lines 297
 * to 303 that stops once the index passes the last row, the whole passage running from line 290 to
 * line 303 with the pre-read guard on the attention key just above it at lines 285 to 287. Because
 * there is no declaration to translate, there is no <em>page</em> row count declared here either: the
 * row count reaches the service as data inside {@link PageMetadata}, whose own documentation proves
 * each of the three screen counts from its own source member. Nothing in this record tells the service
 * how many rows a page carries, and nothing here borrows the administrative user list's figure even
 * though the two screens happen to agree - they are independent and were established by different
 * mechanisms.</p>
 *
 * <p>{@link #ROW_SELECTOR_COUNT} is a different quantity and is genuinely declared here: it is the
 * number of selector <em>items the map declares</em>, which the map does declare explicitly, one item
 * at a time. It bounds how many selectors a submission may carry and tells no one how many rows to
 * read, present or fetch. The two figures coincide at ten for this screen because a map declares one
 * selector per row, and they remain separate declarations for separate purposes.</p>
 *
 * <p>That count is <strong>screen shape</strong>: the number of lines the operator sees. It is not a
 * tuning figure of any kind, and no tuning figure of any kind appears anywhere in this file.
 * Decision log entry DL-073 records that the module asserts no performance target at all.</p>
 *
 * <p>Navigation state travels as the cursor pair inside {@link PageMetadata.PageCursorRequest} - the
 * boundary keys the program retains as its first and last identifier of the displayed page. There is
 * no row offset and no page arithmetic here, because the legacy browse has neither: it repositions on
 * a retained key and walks.</p>
 *
 * <p><strong>The inbound paging shape is narrower than the outbound one, and deliberately so.</strong>
 * A submission chooses only where to resume and in which direction. The page depth is the number of
 * lines the screen has, and whether a further page exists is something the browse discovers by
 * attempting one more access - so neither is a value a client is in a position to state. Accepting
 * them inbound would let a submission name a page depth the screen does not have, or assert the
 * availability of a page the browse never found. {@link PageMetadata.PageCursorRequest} therefore
 * carries the two boundary keys and the direction and nothing else, while the full
 * {@link PageMetadata} remains the outbound shape on the response contract.</p>
 *
 * <p><strong>The page indicator is echoed, not accepted.</strong> The item physically returns on the
 * next submission because a fixed-width screen transmits every field it displays, but the program
 * never reads the returned value: {@code PAGENUMI} appears at exactly two sites in
 * {@code app/cbl/COTRN00C.cbl}, lines 324 and 373, and both are writes of
 * {@code CDEMO-CT00-PAGE-NUM} into the item. The authoritative page number lives in the
 * communication area the program carries across turns, and the displayed item is a rendering of it.
 * The component is therefore kept - it is part of the layout this contract mirrors, and it is
 * serialised outbound - but it is marked non-bindable, so a submission cannot substitute a page
 * number the server did not compute. That is not a restriction added on top of the legacy behaviour;
 * it is the legacy behaviour, which read the value from its own state and not from the terminal. It
 * is carried as text of width 8, matching the map, and is never parsed into a number here; the
 * card-list screen's indicator is a differently named item of width 3, and the two are deliberately
 * not unified.</p>
 *
 * <p><strong>Backward paging inverts the fill order, and this record does not participate.</strong>
 * The backward paragraph begins at line 333, seeds the row index to the last row at line 349, and
 * walks upward from the bottom row to the top in the loop at lines 351 to 357, reading backward at
 * line 352 - so the order in which rows are read is the inverse of the order in which they are
 * presented. Re-ordering the rows is therefore the service's responsibility, and the ordered rows
 * are carried by the response contract. This record holds no ordering, no comparison rule and no
 * re-sequencing of any kind.</p>
 *
 * <p>No framework paging abstraction appears in this file, and none may: the request is expressed in
 * the legacy screen's own terms, and the service translates it to whatever the data-access layer
 * needs. That keeps the published contract stable across a framework change and keeps this package
 * dependent on nothing above it.</p>
 *
 * <h2>The attention key and the echoed navigation state</h2>
 *
 * <p>{@link #keyAction()} carries the operator's attention key as {@link KeyAction}, the enumeration
 * of the sixteen condition names the estate declares. It is nullable and is never defaulted. The
 * legacy key-mapping construct has no catch-all branch, so an unrecognised key produces no
 * assignment at all and the caller keeps whatever action it was already holding; absence is modelled
 * as absence, and no synthetic unknown constant exists to model it - decision log entry DL-024.
 * Function keys thirteen through twenty-four are not distinct actions either: the estate folds them
 * onto the lower twelve, that fold lives in the utility-layer translator, and this record neither
 * performs it nor names its inputs - decision log entry DL-025.</p>
 *
 * <p>{@link #navigationContext()} carries the cross-turn state the legacy communication area carried:
 * client-echoed request state, not a server session, and not interpreted here. It too is left exactly
 * as supplied, including {@code null}, because a synthesised empty context is still a default and this
 * contract manufactures none.</p>
 *
 * <p>The screen work area that five other online programs share is deliberately absent. The
 * transaction programs are not part of that five-program family and include no such copybook, so
 * adding it here would import state this transaction never had.</p>
 *
 * <h2>Validation policy: bounds only, and they measure rather than change</h2>
 *
 * <p>The declarative constraints on values are width bounds - 16 characters on the filter, 8 on the
 * page indicator and 1 on each selector entry - each taken from the map item it mirrors. A bound
 * reports an over-long value and nothing else: it never shortens, pads, folds case or normalises, so a
 * value crosses this boundary byte for byte with every leading and trailing space intact.</p>
 *
 * <p><strong>Three structural bounds sit beside them and none is a field edit.</strong> The selector
 * sequence is bounded at the map's ten items, and the navigation and paging components each carry a
 * cascade so that the widths <em>they</em> declare are actually evaluated - a nested constraint fires
 * only when the enclosing component asks for it, so without the cascade those widths are stated and
 * unenforced, and an arbitrarily wide echoed identifier or browse cursor crosses this boundary
 * unmeasured on its way to a query. None of the three constrains a value this record declares, none
 * alters anything, and none can pre-empt a service cascade: a sequence longer than the screen and an
 * over-wide nested value are states no 3270 submission could produce, so the estate has no ordered
 * check and no message for either. What they prevent is unbounded work rather than a bad value.</p>
 *
 * <p>No presence constraint, character-class constraint, numeric constraint or bound-check constraint
 * appears, and their absence is deliberate rather than an omission. The filter is legitimately blank
 * and so is every selector, so a presence constraint would reject submissions the legacy screen
 * accepts. The two checks the program does perform - the numeric test on the filter, made at line 209
 * with its rejection message set at line 214, and the accepted-letter test on the resolved selector,
 * whose rejection message is set at line 199 - are message-bearing and ordered: each must yield one
 * specific message, and the selection construct stops at its first match.
 * Declarative validation is evaluated in an unspecified order and would report several violations at
 * once, so it cannot reproduce a first-match-wins cascade carrying exact texts. Those checks stay in
 * the service, where the ordering and the texts can both be honoured; decision log entry DL-051
 * records how the declarative failures that <em>do</em> occur are answered in the legacy two-state
 * field vocabulary. No message text is declared in this file - the texts belong to the response
 * contract.</p>
 *
 * <h2>Regulated values are carried verbatim and disclosed nowhere</h2>
 *
 * <p>Two components carry regulated content. The filter is a transaction identifier, which names one
 * specific movement of money on one specific card, and the paging component's retained browse keys are
 * transaction identifiers of the same kind. Both are transported exactly as received - never masked,
 * truncated, partially obscured or transformed - because the browse compares the filter to a retrieved
 * key character for character and repositions on the cursors verbatim, so any alteration would change
 * which rows the screen lists.
 *
 * <p>Disclosure is prevented on the rendering path instead: {@link #toString()} substitutes a fixed
 * placeholder for the filter and for the paging component, while {@link NavigationContext} redacts its
 * own identifying values and is therefore printed by delegation. The reasoning behind each choice, and
 * why the paging component is withheld whole rather than by delegation, is on that method. This file
 * holds no logger and emits nothing on its own.
 *
 * <h2>Provenance</h2>
 *
 * <p>Translated from the CardDemo COBOL estate at checkout commit
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. The estate under {@code app/} is read-only
 * reference: it is cited here by member name, item name, item width and line number only, and no
 * source text is reproduced.</p>
 *
 * @param transactionIdFilter the operator-entered transaction-identifier filter, mirroring inbound
 *     map item {@code TRNIDIN} of width 16 at {@code app/cpy-bms/COTRN00.CPY} line 66 and the
 *     enterable screen item at {@code app/bms/COTRN00.bms} line 95. Optional: {@code null}, empty
 *     and all-blank are all legitimate and all mean "start at the beginning of the browse", which is
 *     the branch {@code app/cbl/COTRN00C.cbl} takes at line 206. Bounded to 16 characters, carried
 *     unaltered, and never defaulted or substituted. The non-numeric rejection at line 214 is a
 *     message-bearing service check over this text, not a type change.
 * @param displayedPageNumber the page indicator the screen displays, mirroring inbound map item
 *     {@code PAGENUM} of width 8 at {@code app/cpy-bms/COTRN00.CPY} line 60 and the protected screen
 *     item at {@code app/bms/COTRN00.bms} line 85. Server-owned and <strong>non-bindable</strong>:
 *     the program writes it at {@code app/cbl/COTRN00C.cbl} lines 324 and 373 and reads it at neither,
 *     taking the authoritative figure from the communication area instead, so a submitted value is
 *     discarded rather than trusted. Still serialised outbound, so the echo the layout expects is
 *     preserved. Alphanumeric on the map and therefore text here, never a number, and bounded to 8
 *     characters. Display value only - {@link PageMetadata.PageCursorRequest} carries the
 *     authoritative cursor state. May be {@code null} when the client has none to echo.
 * @param rowSelectors the row selectors, one entry per displayed row in row order, mirroring inbound
 *     map items {@code SEL0001} through {@code SEL0010} of width 1 each at
 *     {@code app/cpy-bms/COTRN00.CPY} lines 72, 102, 132, 162, 192, 222, 252, 282, 312 and 342.
 *     Positional and index-aligned: index zero is the first displayed row. Blank entries are
 *     preserved because a blank slot is what says that row was not chosen. Normalised only in that
 *     {@code null} becomes the empty immutable sequence - the shape of a submission that marked no
 *     row - and a supplied sequence is copied defensively. Never de-duplicated, keyed, compacted,
 *     re-ordered or interpreted here; the first-non-blank resolution at
 *     {@code app/cbl/COTRN00C.cbl} lines 148 to 182 and the accepted-letter check at line 199 both
 *     belong to the service. Bounded in count as well as in entry width: the screen declares ten row
 *     families, so a sequence longer than ten describes a screen that does not exist and is rejected.
 * @param keyAction the operator's attention key, or {@code null} when the submitted key maps to none
 *     of the sixteen declared actions. Never defaulted: the legacy mapping has no catch-all branch,
 *     so an unrecognised key leaves the previously held action in place. The forward path is guarded
 *     on the attention key before its first read at {@code app/cbl/COTRN00C.cbl} lines 285 to 287,
 *     and the backward path begins at line 333, so the key is genuine request state rather than
 *     decoration.
 * @param navigationContext the client-echoed cross-turn navigation state, or {@code null} when the
 *     client echoes none. Carried verbatim and never interpreted, defaulted or replaced by a
 *     synthesised empty context. Validated transitively, so the widths that contract declares are
 *     actually evaluated when it arrives as part of this request.
 * @param pageMetadata the cursor-and-direction paging state, or {@code null} on an entry submission
 *     that starts a fresh browse rather than continuing one. Its boundary keys - not any offset or
 *     page arithmetic - are what reposition the browse. The inbound shape carries the two keys and the
 *     direction only: the row count is screen shape rather than a submitted value, which is why no
 *     row-count value is declared in this record either, and page availability is something the browse
 *     discovers rather than something a client asserts. Validated transitively for the same reason as
 *     the navigation state.
 */
public record TransactionListRequest(
        @Size(max = TransactionListRequest.TRANSACTION_ID_FILTER_LENGTH) String transactionIdFilter,
        @Size(max = TransactionListRequest.ROW_SELECTOR_COUNT)
                List<@Size(max = TransactionListRequest.ROW_SELECTOR_LENGTH) String> rowSelectors,
        KeyAction keyAction,
        @Valid NavigationContext navigationContext,
        @Valid ScreenContinuation continuation) {

    /**
     * Fixed stand-in emitted by {@link #toString()} in place of each regulated component.
     *
     * <p>A constant rather than any transformation of the value, so nothing about a withheld component
     * - not its length, not a prefix or suffix, not a digest, not a partial mask - can be recovered
     * from a stringified instance. A partial rendering was rejected deliberately: a fragment of a
     * transaction identifier still names the transaction it belongs to once it is read beside the
     * account the same log line already identifies.
     *
     * <p>Private because it is a rendering detail and not part of the request contract. It stands in
     * only on the rendering path: every accessor returns its component untouched.
     */
    private static final String REDACTION_PLACEHOLDER = "***REDACTED***";

    /**
     * Width in characters of the transaction-identifier filter: 16.
     *
     * <p>A legacy item width rather than a restriction invented here. Inbound map item
     * {@code TRNIDIN} is declared 16 characters wide at {@code app/cpy-bms/COTRN00.CPY} line 66,
     * the screen item is defined with the same length at {@code app/bms/COTRN00.bms} line 95, and
     * the persisted transaction key is 16 characters of text in {@code app/cpy/CVTRA05Y.cpy}. The
     * bound only reports an over-long value; it never shortens, pads or normalises one.</p>
     */
    public static final int TRANSACTION_ID_FILTER_LENGTH = 16;

    /**
     * Width in characters of the displayed page indicator: 8.
     *
     * <p>A legacy item width rather than a restriction invented here. Inbound map item
     * {@code PAGENUM} is declared 8 characters wide at {@code app/cpy-bms/COTRN00.CPY} line 60 and
     * the screen item is defined with the same length at {@code app/bms/COTRN00.bms} line 85. The
     * card-list screen's indicator is a differently named item of width 3, so the two widths are
     * deliberately independent. The bound only reports an over-long value.</p>
     */
    public static final int DISPLAYED_PAGE_NUMBER_LENGTH = 8;

    /**
     * The number of row families the transaction-list screen declares: 10.
     *
     * <p>Screen shape rather than a tuning figure. The map declares ten selector items,
     * {@code SEL0001} through {@code SEL0010}, at {@code app/cpy-bms/COTRN00.CPY} lines 72, 102, 132,
     * 162, 192, 222, 252, 282, 312 and 342, and the program's own loops are bounded by the same
     * figure - the forward fill runs while the index is not greater than ten at
     * {@code app/cbl/COTRN00C.cbl} line 290 and the row walk stops at eleven at line 297. It is the
     * number of lines the operator sees, and it is stated here because a submission carrying more
     * selectors than there are rows describes a screen that does not exist.</p>
     *
     * <p>It bounds the selector <em>count</em> only. It is not a page size a client may choose, it is
     * not a limit on anything the response carries, and it is not a performance guard; decision log
     * entry DL-073 records that the module asserts no performance target at all.</p>
     */
    public static final int ROW_COUNT = 10;

    /**
     * Width in characters of one row selector: 1.
     *
     * <p>A legacy item width rather than a restriction invented here. Each of the ten inbound
     * selector items is declared a single character wide at {@code app/cpy-bms/COTRN00.CPY}, and
     * each screen item is defined with the same length at {@code app/bms/COTRN00.bms}. The bound
     * applies to each entry of {@link #rowSelectors()} and only reports an over-long entry; it
     * expresses no opinion about <em>which</em> single character is acceptable, because that check
     * is message-bearing and belongs to the service.</p>
     */
    public static final int ROW_SELECTOR_LENGTH = 1;

    /**
     * Number of selector items the map declares, and therefore the longest sequence
     * {@link #rowSelectors()} can legitimately carry: 10.
     *
     * <p>A legacy item count rather than a limit invented here. Inbound map items {@code SEL0001}
     * through {@code SEL0010} are declared at {@code app/cpy-bms/COTRN00.CPY} lines 72, 102, 132,
     * 162, 192, 222, 252, 282, 312 and 342 - ten items and no eleventh - and the program fills
     * exactly ten rows, its clearing loop bounded at ten at {@code app/cbl/COTRN00C.cbl} line 290 and
     * its filling loop stopping once the index reaches eleven at line 297.</p>
     *
     * <p><strong>Why a cardinality bound belongs here when no format or presence rule does.</strong>
     * The sequence is index-aligned: entry <em>n</em> is the selector typed against displayed row
     * <em>n</em>, so an eleventh entry corresponds to no displayed row and no submission of this
     * screen could produce one. There is consequently no ordered legacy check to defer to and no
     * message to preserve, which is the reason every other rule in this file is a service concern.
     * What the bound does prevent is unbounded work: the canonical constructor copies the sequence
     * faithfully, so without a cardinality bound an arbitrarily long one is retained in full and
     * carried into whatever iterates it. The bound reports and never truncates, so no selector is
     * silently discarded and index alignment is never quietly altered.</p>
     *
     * <p>Declared independently of the row figure the paging contract names for this screen. The two
     * are equal and are not the same quantity: this counts items the map declares, that counts rows a
     * page presents.</p>
     */
    public static final int ROW_SELECTOR_COUNT = 10;

    /** Width of each displayed transaction identifier retained in the continuation. */
    public static final int DISPLAYED_TRANSACTION_ID_LENGTH = TRANSACTION_ID_FILTER_LENGTH;

    /** Maximum displayed identifiers retained in one continuation. */
    public static final int DISPLAYED_TRANSACTION_ID_COUNT = ROW_COUNT;

    /**
     * Compatibility constructor for callers that carry the split paging shape, in which the displayed
     * page number and the cursor pair arrive as two separate components rather than inside one
     * continuation.
     *
     * <p>The two are folded into a {@link ScreenContinuation}: when both are absent the continuation is
     * {@code null}, which is the shape of a first entry; otherwise a continuation is assembled from the
     * cursor pair's previous key, next key and direction together with the page number, with the
     * next-page flag cleared and no displayed identifiers carried. Every other component is passed
     * straight to the canonical constructor, whose contract is the authority for it.
     *
     * @param transactionIdFilter the echoed transaction-identifier filter, as transmitted
     * @param displayedPageNumber the page number the screen displayed, or {@code null} when none was
     *                            displayed
     * @param rowSelectors        the ten row selectors as transmitted; {@code null} becomes the empty
     *                            sequence
     * @param keyAction           the decoded attention key
     * @param navigationContext   the navigation state the client echoed back
     * @param pageMetadata        the cursor pair the screen carried, or {@code null} when none was
     *                            carried
     */
    public TransactionListRequest(final String transactionIdFilter,
                                  final String displayedPageNumber,
                                  final List<String> rowSelectors,
                                  final KeyAction keyAction,
                                  final NavigationContext navigationContext,
                                  final PageMetadata.PageCursorRequest pageMetadata) {
        this(transactionIdFilter, rowSelectors, keyAction, navigationContext,
                pageMetadata == null && displayedPageNumber == null
                        ? null
                        : new ScreenContinuation(
                                pageMetadata == null ? null : pageMetadata.previousCursorKey(),
                                pageMetadata == null ? null : pageMetadata.nextCursorKey(),
                                pageMetadata == null ? null : pageMetadata.direction(),
                                displayedPageNumber,
                                false,
                                List.of()));
    }

    /**
     * Compatibility view of the split page-number component.
     *
     * @return the page number the continuation carries, or {@code null} when this request carries no
     *         continuation at all
     */
    public String displayedPageNumber() {
        return continuation == null ? null : continuation.displayedPageNumber();
    }

    /**
     * Compatibility view of the split cursor component.
     *
     * @return the previous-key, next-key and direction triple the continuation carries, or {@code null}
     *         when this request carries no continuation at all
     */
    public PageMetadata.PageCursorRequest pageMetadata() {
        return continuation == null ? null : continuation.pageCursor();
    }

    /**
     * Canonical constructor. Detaches the selector sequence from the caller and leaves every other
     * component exactly as supplied.
     *
     * <p>A {@code null} sequence becomes the empty immutable sequence, which is the shape of a
     * submission that marked no row, so accessors and serialised payloads always see a usable
     * sequence. A supplied sequence is copied with {@link List#copyOf(java.util.Collection)}, which
     * both detaches it from caller-owned state and rejects a {@code null} entry. Rejecting a
     * {@code null} entry is deliberate: the wire form of a row the operator did not mark is the
     * empty string, exactly as the fixed-width screen item transmits blanks, so a {@code null} entry
     * is a caller defect rather than an unmarked row - and silently accepting one would leave an
     * index-aligned sequence carrying an entry that means nothing.</p>
     *
     * <p>Blank entries survive untouched and no entry is dropped, so index alignment with the
     * displayed rows holds exactly. Nothing else is normalised: the filter, the page indicator, the
     * attention key, the navigation state and the paging state all cross this boundary as given,
     * including {@code null} and including every leading and trailing space, because the items they
     * mirror are fixed-width and space-significant. No component is defaulted, and in particular
     * neither the attention key nor the paging state acquires a stand-in value here.</p>
     *
     * <p><strong>One count is checked.</strong> The selector sequence is positional and index-aligned
     * with the ten row families the screen declares, so a sequence longer than {@link #ROW_COUNT}
     * cannot be interpreted: its surplus entries would refer to rows the screen does not have. It is
     * rejected rather than truncated, because truncating would silently discard a selection the
     * operator believes was made. A <em>shorter</em> sequence is accepted exactly as supplied - a
     * submission that marks an early row need not pad the sequence out, and a final short page
     * legitimately displays fewer rows - so this is an upper bound and not a fixed length.</p>
     *
     * @throws IllegalArgumentException if the selector sequence holds more entries than the screen has
     *     row families
     */
    public TransactionListRequest {
        rowSelectors = (rowSelectors == null) ? List.of() : List.copyOf(rowSelectors);
        if (rowSelectors.size() > ROW_COUNT) {
            throw new IllegalArgumentException("rowSelectors may hold at most " + ROW_COUNT
                    + " entries, because that is how many row families the transaction-list screen"
                    + " declares, but it holds " + rowSelectors.size());
        }
    }

    /**
     * Complete bounded state of the page the client is resubmitting.
     *
     * @param previousCursorKey first identifier displayed on the page
     * @param nextCursorKey last identifier displayed on the page
     * @param direction direction in which the page was reached
     * @param displayedPageNumber fixed-width page-number image
     * @param nextPageAvailable whether the look-ahead read found another page
     * @param displayedTransactionIds identifiers displayed in row order
     */
    public record ScreenContinuation(
            @Size(max = TransactionListRequest.TRANSACTION_ID_FILTER_LENGTH)
                    String previousCursorKey,
            @Size(max = TransactionListRequest.TRANSACTION_ID_FILTER_LENGTH)
                    String nextCursorKey,
            PageMetadata.PagingDirection direction,
            @Size(max = TransactionListRequest.DISPLAYED_PAGE_NUMBER_LENGTH)
            @Pattern(regexp = PageMetadata.RETAINED_PAGE_NUMBER_PATTERN) String displayedPageNumber,
            boolean nextPageAvailable,
            @Size(max = TransactionListRequest.ROW_COUNT)
                    List<@Size(max = TransactionListRequest.TRANSACTION_ID_FILTER_LENGTH)
                            String> displayedTransactionIds) {

        public ScreenContinuation {
            displayedTransactionIds = displayedTransactionIds == null
                    ? List.of()
                    : List.copyOf(displayedTransactionIds);
            if (displayedTransactionIds.size() > ROW_COUNT) {
                throw new IllegalArgumentException("displayedTransactionIds may hold at most "
                        + ROW_COUNT + " entries");
            }
        }

        public static ScreenContinuation empty() {
            return new ScreenContinuation(null, null, null, null, false, List.of());
        }

        public PageMetadata.PageCursorRequest pageCursor() {
            return new PageMetadata.PageCursorRequest(previousCursorKey, nextCursorKey, direction,
                    displayedPageNumber, nextPageAvailable);
        }

        /**
         * Reads the retained page indicator as a number, answering zero for anything that is not one.
         *
         * <p><strong>Total by construction, and that is the point.</strong> The indicator is a fixed-width
         * screen item that a client echoes back, so it arrives with whatever padding its field carried, and
         * the value the legacy program's own initialisation leaves in it is not a number at all. A parse
         * that could throw would turn an echoed screen item into a server fault, which is a failure mode
         * the legacy screen has no counterpart for: it reads the item, finds it unusable, and carries on
         * from its own state. The declared pattern already refuses an internally spaced value at the
         * boundary; this answers zero for every remaining shape - absent, empty, all spaces, or wider than
         * a page indicator can be - so the two together make a fault unreachable rather than merely
         * unlikely.
         *
         * @return the retained page number, or zero when the indicator carries none
         */
        public int currentPageNumber() {
            if (displayedPageNumber == null) {
                return 0;
            }
            final String numericImage = displayedPageNumber.strip();
            if (numericImage.isEmpty() || numericImage.length() > DISPLAYED_PAGE_NUMBER_LENGTH) {
                return 0;
            }
            int accumulated = 0;
            for (int index = 0; index < numericImage.length(); index++) {
                final char digit = numericImage.charAt(index);
                if (digit < '0' || digit > '9') {
                    return 0;
                }
                accumulated = accumulated * 10 + (digit - '0');
            }
            return accumulated;
        }

        @Override
        public String toString() {
            return "ScreenContinuation["
                    + "previousCursorKey=" + REDACTION_PLACEHOLDER
                    + ", nextCursorKey=" + REDACTION_PLACEHOLDER
                    + ", direction=" + direction
                    + ", displayedPageNumber=" + displayedPageNumber
                    + ", nextPageAvailable=" + nextPageAvailable
                    + ", displayedTransactionIds=" + REDACTION_PLACEHOLDER
                    + "]";
        }
    }

    /**
     * Returns a diagnostic representation that mirrors the request layout and discloses no regulated
     * value.
     *
     * <p><strong>Why the implicit record rendering could not stand.</strong> A record's generated
     * {@code toString()} prints every component. Two of these are regulated: the filter is a
     * transaction identifier, which names one specific movement of money on one specific card, and the
     * paging component's retained browse keys are transaction identifiers of the same kind - the first
     * and last of the page just displayed. A structured logger, framework diagnostic, failed
     * assertion, exception message or bare string interpolation touching an instance would otherwise
     * have emitted all three values, and the pair of browse keys additionally discloses the bounds of
     * what the operator was looking at.
     *
     * <p>The paging component is withheld whole rather than by delegation. Its own rendering does
     * withhold both cursors, but depending on that would make this type's safety a property of another
     * type's rendering: a change there would silently open a disclosure path here, and a diagnostic
     * gains nothing from the difference.
     *
     * <p><strong>Why the remainder is retained.</strong> The page indicator is a display label, the
     * selectors are positional keystrokes that identify a row on a screen rather than a value, and the
     * attention key is the operator's navigation choice. That set is exactly what a diagnostic on this
     * browse needs - which page, which row was marked, which direction was asked for - and none of it
     * identifies a person, an account or an amount. The navigation context is printed by delegation
     * because it redacts its own identifying values, and unlike the paging component it carries
     * screen-flow state a diagnostic genuinely reads.
     *
     * <p><strong>Withholding is confined to this method.</strong> Every accessor returns its component
     * exactly as supplied. The filter is compared to a retrieved key character for character and the
     * browse repositions on the cursors verbatim, so nothing here masks, truncates or transforms a
     * value; {@code equals} and {@code hashCode} are left exactly as the record contract generates
     * them, comparing every component by value, because an in-memory comparison emits nothing.
     *
     * @return the request layout with the filter and the paging component replaced by a fixed
     *     placeholder
     */
    @Override
    public String toString() {
        return "TransactionListRequest["
                + "transactionIdFilter=" + REDACTION_PLACEHOLDER
                + ", rowSelectors=" + rowSelectors
                + ", keyAction=" + keyAction
                + ", navigationContext=" + navigationContext
                + ", continuation=" + REDACTION_PLACEHOLDER
                + "]";
    }
}
