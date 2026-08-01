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
 * there is no declaration to translate, there is no constant to declare here either: the row count
 * reaches the service as data inside {@link PageMetadata}, whose own documentation proves each of
 * the three screen counts from its own source member. This record declares no row-count value, and
 * it does not borrow the administrative user list's count even though the two happen to agree - the
 * two screens are independent and were established by different mechanisms.</p>
 *
 * <p>That count is <strong>screen shape</strong>: the number of lines the operator sees. It is not a
 * tuning figure of any kind, and no tuning figure of any kind appears anywhere in this file.
 * Decision log entry DL-073 records that the module asserts no performance target at all.</p>
 *
 * <p>Navigation state travels as the cursor pair inside {@link PageMetadata} - the boundary keys the
 * program retains as its first and last identifier of the displayed page. There is no row offset and
 * no page arithmetic here, because the legacy browse has neither: it repositions on a retained key
 * and walks. The page indicator is a display value that round-trips: the program computes it and
 * writes it into the inbound item at line 324, so it comes back on the next submission, which is why
 * it is request state despite being server-produced. It is carried as text of width 8, matching the
 * map, and is never parsed into a number here; the card-list screen's indicator is a differently
 * named item of width 3, and the two are deliberately not unified.</p>
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
 * <p>The only declarative constraints are width bounds - 16 characters on the filter, 8 on the page
 * indicator and 1 on each selector entry - each taken from the map item it mirrors. A bound reports
 * an over-long value and nothing else: it never shortens, pads, folds case or normalises, so a value
 * crosses this boundary byte for byte with every leading and trailing space intact.</p>
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
 * @param pageIndicator the page indicator the screen displays, mirroring inbound map item
 *     {@code PAGENUM} of width 8 at {@code app/cpy-bms/COTRN00.CPY} line 60 and the protected screen
 *     item at {@code app/bms/COTRN00.bms} line 85. It is server-produced but request-echoed: the
 *     program writes it into the inbound item at {@code app/cbl/COTRN00C.cbl} line 324, so it returns
 *     on the following submission. Alphanumeric on the map and therefore text here, never a number,
 *     and bounded to 8 characters. Display value only - {@link PageMetadata} carries the
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
 *     belong to the service.
 * @param keyAction the operator's attention key, or {@code null} when the submitted key maps to none
 *     of the sixteen declared actions. Never defaulted: the legacy mapping has no catch-all branch,
 *     so an unrecognised key leaves the previously held action in place. The forward path is guarded
 *     on the attention key before its first read at {@code app/cbl/COTRN00C.cbl} lines 285 to 287,
 *     and the backward path begins at line 333, so the key is genuine request state rather than
 *     decoration.
 * @param navigationContext the client-echoed cross-turn navigation state, or {@code null} when the
 *     client echoes none. Carried verbatim and never interpreted, defaulted or replaced by a
 *     synthesised empty context.
 * @param pageMetadata the cursor-and-direction paging state, or {@code null} on an entry submission
 *     that starts a fresh browse rather than continuing one. It carries the row count as data, which
 *     is why no row-count value is declared in this record, and its boundary keys - not any offset or
 *     page arithmetic - are what reposition the browse.
 */
public record TransactionListRequest(
        @Size(max = TransactionListRequest.TRANSACTION_ID_FILTER_LENGTH) String transactionIdFilter,
        @Size(max = TransactionListRequest.PAGE_INDICATOR_LENGTH) String pageIndicator,
        List<@Size(max = TransactionListRequest.ROW_SELECTOR_LENGTH) String> rowSelectors,
        KeyAction keyAction,
        NavigationContext navigationContext,
        PageMetadata pageMetadata) {

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
    public static final int PAGE_INDICATOR_LENGTH = 8;

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
     */
    public TransactionListRequest {
        rowSelectors = (rowSelectors == null) ? List.of() : List.copyOf(rowSelectors);
    }
}
